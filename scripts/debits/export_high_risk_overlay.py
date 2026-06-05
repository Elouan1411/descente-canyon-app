from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import numpy as np

from export_mobile_embedded_model import feature_spec_payload
from pipeline_lib import with_debit_derived_model_features, write_json
from train_baseline_model import (
    NUMERIC_FEATURES,
    build_feature_coverage_report,
    row_to_numeric_vector,
    select_model_features,
    target_three_classes,
)


THREE_LABELS = ["LOW", "MEDIUM", "HIGH"]
OVERLAY_LABELS = ["NOT_HIGH", "HIGH"]


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [with_debit_derived_model_features(json.loads(line)) for line in path.open(encoding="utf-8") if line.strip()]


def base_predictions(eval_rows: list[dict[str, Any]], path: Path) -> list[str]:
    prediction_rows = [json.loads(line) for line in path.open(encoding="utf-8") if line.strip()]
    by_id = {str(row["observationId"]): row for row in prediction_rows if row.get("observationId")}
    return [by_id[str(row["observationId"])] ["predictedThree"] for row in eval_rows]


def classification_metrics(rows: list[dict[str, Any]], predictions: list[str]) -> dict[str, Any]:
    matrix = {truth: {pred: 0 for pred in THREE_LABELS} for truth in THREE_LABELS}
    for row, prediction in zip(rows, predictions):
        matrix[target_three_classes(row["niveau"])][prediction] += 1
    total = len(rows)
    correct = sum(matrix[label][label] for label in THREE_LABELS)
    per_label: dict[str, Any] = {}
    recalls: list[float] = []
    f1s: list[float] = []
    for label in THREE_LABELS:
        true_positive = matrix[label][label]
        support = sum(matrix[label].values())
        predicted = sum(matrix[truth][label] for truth in THREE_LABELS)
        precision = true_positive / predicted if predicted else 0.0
        recall = true_positive / support if support else 0.0
        f1 = 2.0 * precision * recall / (precision + recall) if precision + recall else 0.0
        per_label[label] = {
            "precision": precision,
            "recall": recall,
            "f1": f1,
            "support": support,
            "predicted": predicted,
        }
        recalls.append(recall)
        f1s.append(f1)
    return {
        "accuracy": correct / total if total else 0.0,
        "balancedAccuracy": sum(recalls) / len(recalls) if recalls else 0.0,
        "macroF1": sum(f1s) / len(f1s) if f1s else 0.0,
        "highPrecision": per_label["HIGH"]["precision"],
        "highRecall": per_label["HIGH"]["recall"],
        "highF1": per_label["HIGH"]["f1"],
        "predictedHighCount": per_label["HIGH"]["predicted"],
        "perLabel": per_label,
        "confusionMatrix": matrix,
    }


def overlay_metrics(
    *,
    rows: list[dict[str, Any]],
    probabilities: np.ndarray,
    base: list[str],
    threshold: float,
) -> dict[str, Any]:
    predictions = ["HIGH" if probability >= threshold else base_prediction for probability, base_prediction in zip(probabilities, base)]
    return classification_metrics(rows, predictions)


def main() -> None:
    parser = argparse.ArgumentParser(description="Export a compact Descente-Canyon HIGH-risk overlay model")
    parser.add_argument("--train-features", default="build/debit-pipeline/stratified-holdout-strict/train_features_strict/training_features.jsonl")
    parser.add_argument("--strict-features", default="build/debit-pipeline/stratified-holdout-strict/test_features_strict/test_features.jsonl")
    parser.add_argument("--strict-base-predictions", default="build/debit-pipeline/stratified-holdout-strict-evaluation/predictions.jsonl")
    parser.add_argument("--recent-features", default="build/debit-pipeline/post-cutoff-final-temporal-modele-statistique-evaluation-descente-2026-06-05-with-features/post_cutoff_feature_rows.jsonl")
    parser.add_argument("--recent-base-predictions", default="build/debit-pipeline/post-cutoff-final-temporal-modele-statistique-evaluation-descente-2026-06-05-with-features/predictions.jsonl")
    parser.add_argument("--output-dir", default="build/debit-pipeline/high-risk-overlay-extra-trees-compact")
    parser.add_argument("--n-estimators", type=int, default=40)
    parser.add_argument("--max-depth", type=int)
    parser.add_argument("--min-samples-leaf", type=int, default=3)
    parser.add_argument("--threshold", type=float, default=0.35)
    args = parser.parse_args()

    try:
        import onnxruntime as ort  # type: ignore
        from skl2onnx import convert_sklearn  # type: ignore
        from skl2onnx.common.data_types import FloatTensorType  # type: ignore
        from sklearn.ensemble import ExtraTreesClassifier  # type: ignore
    except ImportError as exc:  # pragma: no cover
        raise SystemExit("Export requires scikit-learn, skl2onnx and onnxruntime") from exc

    train_rows = read_jsonl(Path(args.train_features))
    feature_coverage = build_feature_coverage_report(train_rows, NUMERIC_FEATURES)
    features = select_model_features(NUMERIC_FEATURES, feature_coverage, keep_uninformative_features=False)
    x_train = np.array([row_to_numeric_vector(row, features) for row in train_rows], dtype=np.float32)
    y_train = np.array([1 if target_three_classes(row["niveau"]) == "HIGH" else 0 for row in train_rows], dtype=np.int64)

    model = ExtraTreesClassifier(
        n_estimators=args.n_estimators,
        max_depth=args.max_depth,
        min_samples_leaf=args.min_samples_leaf,
        n_jobs=-1,
        random_state=42,
        class_weight="balanced_subsample",
    )
    model.fit(x_train, y_train)

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    initial_types = [("input", FloatTensorType([None, len(features)]))]
    onnx_model = convert_sklearn(model, initial_types=initial_types, options={id(model): {"zipmap": False}})
    (output_dir / "high_risk_overlay.onnx").write_bytes(onnx_model.SerializeToString())
    write_json(output_dir / "feature_spec.json", feature_spec_payload(OVERLAY_LABELS, features))

    node_counts = [estimator.tree_.node_count for estimator in model.estimators_]
    max_depths = [estimator.tree_.max_depth for estimator in model.estimators_]

    eval_sets = {
        "strict": {
            "rows": read_jsonl(Path(args.strict_features)),
            "base": base_predictions(read_jsonl(Path(args.strict_features)), Path(args.strict_base_predictions)),
            "featuresPath": args.strict_features,
            "basePredictionsPath": args.strict_base_predictions,
        },
        "recent": {
            "rows": read_jsonl(Path(args.recent_features)),
            "base": base_predictions(read_jsonl(Path(args.recent_features)), Path(args.recent_base_predictions)),
            "featuresPath": args.recent_features,
            "basePredictionsPath": args.recent_base_predictions,
        },
    }

    session = ort.InferenceSession(str(output_dir / "high_risk_overlay.onnx"), providers=["CPUExecutionProvider"])
    input_name = session.get_inputs()[0].name
    report_eval: dict[str, Any] = {}
    for name, payload in eval_sets.items():
        rows = payload["rows"]
        x_eval = np.array([row_to_numeric_vector(row, features) for row in rows], dtype=np.float32)
        sklearn_probabilities = model.predict_proba(x_eval)[:, 1]
        outputs = session.run(None, {input_name: x_eval})
        probability_tensor = next(output for output in outputs if isinstance(output, np.ndarray) and output.ndim == 2)
        high_index = list(model.classes_).index(1)
        onnx_probabilities = probability_tensor[:, high_index]
        max_probability_delta = float(np.max(np.abs(sklearn_probabilities - onnx_probabilities))) if len(rows) else 0.0
        report_eval[name] = {
            "featuresPath": payload["featuresPath"],
            "basePredictionsPath": payload["basePredictionsPath"],
            "rowCount": len(rows),
            "base": classification_metrics(rows, payload["base"]),
            "overlay": overlay_metrics(rows=rows, probabilities=onnx_probabilities, base=payload["base"], threshold=args.threshold),
            "maxOnnxSklearnProbabilityDelta": max_probability_delta,
        }

    report = {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "model": "extra_trees_high_risk_overlay",
        "targetMode": "binary_high_overlay",
        "labels": OVERLAY_LABELS,
        "featureCount": len(features),
        "features": features,
        "nEstimators": args.n_estimators,
        "maxDepth": args.max_depth,
        "minSamplesLeaf": args.min_samples_leaf,
        "threshold": args.threshold,
        "totalTreeNodes": int(sum(node_counts)),
        "maxTreeNodes": int(max(node_counts)),
        "averageTreeNodes": float(sum(node_counts) / len(node_counts)),
        "maxTreeDepth": int(max(max_depths)),
        "averageTreeDepth": float(sum(max_depths) / len(max_depths)),
        "evaluations": report_eval,
        "files": {
            "model": "high_risk_overlay.onnx",
            "featureSpec": "feature_spec.json",
            "report": "metrics.json",
        },
    }
    write_json(output_dir / "metrics.json", report)
    print(json.dumps({name: value["overlay"] for name, value in report_eval.items()}, indent=2))


if __name__ == "__main__":
    main()
