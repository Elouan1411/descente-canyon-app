from __future__ import annotations

import argparse
import json
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from pipeline_lib import with_debit_derived_model_features, write_json
from export_runtime_lookups import build_runtime_lookup_payload
from export_mobile_embedded_model import (
    DEFAULT_FEATURES_PATH,
    DEFAULT_WATERSHED_DESCRIPTORS_PATH,
    build_canyon_static_features,
    compact_runtime_lookup_payload,
    feature_spec_payload,
    probabilities_to_predictions,
    read_jsonl,
    split_temporal_rows,
)
from train_baseline_model import (
    NUMERIC_FEATURES,
    apply_canyon_history_dropout,
    build_feature_coverage_report,
    row_to_numeric_vector,
    sample_weights,
    select_model_features,
)
from train_ordinal_model import (
    LEVEL_TO_RANK,
    SIX_LABELS,
    THREE_LABELS,
    evaluate_predictions,
    find_ordinal_thresholds,
    ordinal_error_metrics,
    rank_to_level,
    rank_to_three,
    target_rank,
    target_three_classes,
    top_feature_importances,
)


DEFAULT_OUTPUT_DIR = "build/debit-pipeline/mobile-ordinal-catboost-candidate"


def expected_scores(probabilities: list[list[float]], labels: list[str]) -> list[float]:
    return [
        sum(float(probability) * float(LEVEL_TO_RANK.get(label, 0)) for label, probability in zip(labels, row))
        for row in probabilities
    ]


def aggregate_three_class_probabilities(probabilities: list[list[float]], labels: list[str]) -> list[list[float]]:
    label_index = {label: index for index, label in enumerate(labels)}
    result: list[list[float]] = []
    for row in probabilities:
        low = sum(float(row[label_index[label]]) for label in ("SEC", "FILET") if label in label_index)
        medium = float(row[label_index["CORRECT"]]) if "CORRECT" in label_index else 0.0
        high = sum(float(row[label_index[label]]) for label in ("GROS", "TRES_GROS", "CRUE") if label in label_index)
        result.append([low, medium, high])
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description="Export a mobile ONNX ordinal CatBoost débit model candidate")
    parser.add_argument("--features-path", default=DEFAULT_FEATURES_PATH)
    parser.add_argument("--output-dir", default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--canyons-path", default="offline-data/full/room-import/canyons.json")
    parser.add_argument("--watersheds-path", default="offline-data/full/room-import/watersheds.json")
    parser.add_argument("--watershed-descriptors-path", default=DEFAULT_WATERSHED_DESCRIPTORS_PATH)
    parser.add_argument("--calibration-fraction", type=float, default=0.10)
    parser.add_argument("--test-fraction", type=float, default=0.20)
    parser.add_argument("--iterations", type=int, default=900)
    parser.add_argument("--depth", type=int, default=8)
    parser.add_argument("--learning-rate", type=float, default=0.035)
    parser.add_argument("--l2-leaf-reg", type=float, default=8.0)
    parser.add_argument("--canyon-history-dropout-rate", type=float, default=0.15)
    parser.add_argument("--default-policy", choices=["balanced", "prudent", "safety_first"], default="balanced")
    parser.add_argument("--ignore-sample-weights", action="store_true")
    parser.add_argument("--keep-uninformative-features", action="store_true")
    parser.add_argument(
        "--final-train-on-all",
        action="store_true",
        help="Calibrate thresholds on the temporal split, then fit the exported model on every labelled row.",
    )
    args = parser.parse_args()

    try:
        import onnx
        from catboost import CatBoostClassifier  # type: ignore
        from sklearn.metrics import accuracy_score, balanced_accuracy_score, classification_report, confusion_matrix, f1_score
    except ImportError as exc:  # pragma: no cover
        raise SystemExit("This export requires catboost, onnx and scikit-learn.") from exc

    rows_raw = read_jsonl(Path(args.features_path))
    rows = [with_debit_derived_model_features(row) for row in rows_raw]
    runtime_lookup_payload, runtime_lookup_metadata = build_runtime_lookup_payload(rows_raw)
    filtered = [row for row in rows if target_rank(row.get("niveau")) is not None and row.get("date")]
    filtered.sort(key=lambda row: row["date"])
    if len(filtered) < 200:
        raise SystemExit("Not enough rows to export an ordinal mobile model")

    feature_coverage = build_feature_coverage_report(filtered, NUMERIC_FEATURES)
    active_feature_names = select_model_features(
        NUMERIC_FEATURES,
        feature_coverage,
        keep_uninformative_features=args.keep_uninformative_features,
    )
    train_rows, calibration_rows, test_rows = split_temporal_rows(
        filtered,
        calibration_fraction=args.calibration_fraction,
        test_fraction=args.test_fraction,
    )
    training_feature_rows = apply_canyon_history_dropout(
        train_rows,
        dropout_rate=args.canyon_history_dropout_rate,
        random_seed=42,
    )

    x_train = [row_to_numeric_vector(row, active_feature_names) for row in training_feature_rows]
    y_train = [str(row["niveau"]) for row in train_rows]
    x_calibration = [row_to_numeric_vector(row, active_feature_names) for row in calibration_rows]
    y_calibration_three = [target_three_classes(row["niveau"]) for row in calibration_rows]
    x_test = [row_to_numeric_vector(row, active_feature_names) for row in test_rows]
    y_test = [str(row["niveau"]) for row in test_rows]
    y_test_ranks = [target_rank(row["niveau"]) for row in test_rows]
    y_test_three = [target_three_classes(row["niveau"]) for row in test_rows]

    model = CatBoostClassifier(
        iterations=args.iterations,
        depth=args.depth,
        learning_rate=args.learning_rate,
        l2_leaf_reg=args.l2_leaf_reg,
        loss_function="MultiClass",
        eval_metric="TotalF1",
        random_seed=42,
        thread_count=-1,
        verbose=False,
        allow_writing_files=False,
    )
    train_sample_weights = None if args.ignore_sample_weights else sample_weights(training_feature_rows)
    fit_kwargs = {"sample_weight": train_sample_weights} if train_sample_weights is not None else {}
    model.fit(x_train, y_train, **fit_kwargs)

    labels = [str(label) for label in model.classes_]
    if set(labels) != set(SIX_LABELS):
        raise SystemExit(f"Unexpected ordinal labels: {labels}")

    calibration_probabilities = model.predict_proba(x_calibration).tolist()
    test_probabilities = model.predict_proba(x_test).tolist()
    calibration_scores = expected_scores(calibration_probabilities, labels)
    test_scores = expected_scores(test_probabilities, labels)

    rounded_six_predictions = [rank_to_level(score) for score in test_scores]
    rounded_three_predictions = [target_three_classes(label) for label in rounded_six_predictions]
    rounded_six_metrics = evaluate_predictions(
        y_true=y_test,
        predictions=rounded_six_predictions,
        labels=SIX_LABELS,
        accuracy_score_fn=accuracy_score,
        balanced_accuracy_score_fn=balanced_accuracy_score,
        classification_report_fn=classification_report,
        confusion_matrix_fn=confusion_matrix,
        f1_score_fn=f1_score,
    )
    rounded_three_metrics = evaluate_predictions(
        y_true=y_test_three,
        predictions=rounded_three_predictions,
        labels=THREE_LABELS,
        accuracy_score_fn=accuracy_score,
        balanced_accuracy_score_fn=balanced_accuracy_score,
        classification_report_fn=classification_report,
        confusion_matrix_fn=confusion_matrix,
        f1_score_fn=f1_score,
    )

    threshold_policies: dict[str, Any] = {}
    for policy_name in ("balanced", "prudent", "safety_first"):
        calibration_metrics = find_ordinal_thresholds(
            y_true=y_calibration_three,
            scores=calibration_scores,
            policy=policy_name,
            accuracy_score_fn=accuracy_score,
            balanced_accuracy_score_fn=balanced_accuracy_score,
            classification_report_fn=classification_report,
            confusion_matrix_fn=confusion_matrix,
            f1_score_fn=f1_score,
        )
        low_threshold = calibration_metrics["lowThreshold"]
        high_threshold = calibration_metrics["highThreshold"]
        test_predictions = [rank_to_three(score, low_threshold=low_threshold, high_threshold=high_threshold) for score in test_scores]
        test_metrics = evaluate_predictions(
            y_true=y_test_three,
            predictions=test_predictions,
            labels=THREE_LABELS,
            accuracy_score_fn=accuracy_score,
            balanced_accuracy_score_fn=balanced_accuracy_score,
            classification_report_fn=classification_report,
            confusion_matrix_fn=confusion_matrix,
            f1_score_fn=f1_score,
        )
        threshold_policies[policy_name] = {
            "lowThreshold": low_threshold,
            "highThreshold": high_threshold,
            "calibrationMetrics": calibration_metrics,
            "testMetrics": test_metrics,
        }

    exported_training_row_count = len(train_rows)
    if args.final_train_on_all:
        final_training_feature_rows = apply_canyon_history_dropout(
            filtered,
            dropout_rate=args.canyon_history_dropout_rate,
            random_seed=42,
        )
        x_final_train = [row_to_numeric_vector(row, active_feature_names) for row in final_training_feature_rows]
        y_final_train = [str(row["niveau"]) for row in filtered]
        final_sample_weights = None if args.ignore_sample_weights else sample_weights(final_training_feature_rows)
        final_fit_kwargs = {"sample_weight": final_sample_weights} if final_sample_weights is not None else {}
        model = CatBoostClassifier(
            iterations=args.iterations,
            depth=args.depth,
            learning_rate=args.learning_rate,
            l2_leaf_reg=args.l2_leaf_reg,
            loss_function="MultiClass",
            eval_metric="TotalF1",
            random_seed=42,
            thread_count=-1,
            verbose=False,
            allow_writing_files=False,
        )
        model.fit(x_final_train, y_final_train, **final_fit_kwargs)
        exported_training_row_count = len(filtered)

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    model_path = output_dir / "model.onnx"
    model.save_model(str(model_path), format="onnx")

    onnx_model = onnx.load(str(model_path))
    input_dimensions = onnx_model.graph.input[0].type.tensor_type.shape.dim
    if len(input_dimensions) < 2 or input_dimensions[1].dim_value != len(active_feature_names):
        raise SystemExit(
            f"ONNX input feature dimension mismatch: {input_dimensions[1].dim_value if len(input_dimensions) >= 2 else None} != {len(active_feature_names)}"
        )

    write_json(output_dir / "feature_spec.json", feature_spec_payload(labels, active_feature_names))
    write_json(
        output_dir / "canyon_static_features.json",
        build_canyon_static_features(
            Path(args.canyons_path),
            Path(args.watersheds_path),
            Path(args.watershed_descriptors_path) if args.watershed_descriptors_path else None,
        ),
    )
    write_json(output_dir / "runtime_feature_lookups.json", compact_runtime_lookup_payload(runtime_lookup_payload))
    write_json(
        output_dir / "thresholds.json",
        {
            "schemaVersion": 1,
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "targetMode": "six_ordinal",
            "labels": labels,
            "defaultPolicy": args.default_policy,
            "policies": {
                policy_name: {
                    "lowThreshold": payload["lowThreshold"],
                    "highThreshold": payload["highThreshold"],
                }
                for policy_name, payload in threshold_policies.items()
            },
        },
    )
    write_json(
        output_dir / "metrics.json",
        {
            "schemaVersion": 1,
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "model": "catboost_mobile_ordinal_classifier",
            "targetMode": "six_ordinal",
            "labels": labels,
            "featureCount": len(active_feature_names),
            "features": active_feature_names,
            "droppedFeatureCount": len(NUMERIC_FEATURES) - len(active_feature_names),
            "featureCoverage": feature_coverage,
            "canyonHistoryDropoutRate": args.canyon_history_dropout_rate,
            "usesSampleWeights": train_sample_weights is not None,
            "finalTrainOnAll": args.final_train_on_all,
            "exportedTrainingRowCount": exported_training_row_count,
            "trainRowCount": len(train_rows),
            "calibrationRowCount": len(calibration_rows),
            "testRowCount": len(test_rows),
            "trainClassCounts": dict(sorted(Counter(y_train).items())),
            "testClassCounts": dict(sorted(Counter(y_test).items())),
            "ordinalScoreMetrics": ordinal_error_metrics(y_test_ranks, test_scores),
            "roundedSixMetrics": rounded_six_metrics,
            "roundedThreeMetrics": rounded_three_metrics,
            "thresholdPolicies": threshold_policies,
            "topFeatureImportances": top_feature_importances(active_feature_names, list(model.get_feature_importance()), limit=30),
            "runtimeLookupMetadata": runtime_lookup_metadata,
            "onnxOutputs": [output.name for output in onnx_model.graph.output],
            "files": {
                "model": "model.onnx",
                "featureSpec": "feature_spec.json",
                "canyonStaticFeatures": "canyon_static_features.json",
                "runtimeLookups": "runtime_feature_lookups.json",
                "thresholds": "thresholds.json",
            },
        },
    )


if __name__ == "__main__":
    main()
