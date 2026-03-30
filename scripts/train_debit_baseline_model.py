from __future__ import annotations

import argparse
import json
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from debit_pipeline_lib import write_json


NUMERIC_FEATURES = [
    "month",
    "monthSin",
    "monthCos",
    "altitudeDepartM",
    "deniveleM",
    "longueurM",
    "cascadeMaxM",
    "upstreamCatchmentAreaKm2",
    "precip_prev_day_mm",
    "precip_2d_mm",
    "precip_3d_mm",
    "precip_5d_mm",
    "precip_7d_mm",
    "precip_10d_mm",
    "precip_14d_mm",
    "precip_21d_mm",
    "precip_30d_mm",
    "max_daily_precip_3d_mm",
    "max_daily_precip_7d_mm",
    "max_daily_precip_14d_mm",
    "wet_days_7d",
    "wet_days_14d",
    "wet_days_30d",
    "days_since_precip_over_1mm",
    "days_since_precip_over_5mm",
    "days_since_precip_over_10mm",
    "antecedent_precipitation_index_daily",
    "antecedent_precipitation_index_daily_70",
    "antecedent_precipitation_index_daily_85",
    "antecedent_precipitation_index_daily_93",
    "temperature2mAtObservation",
    "temperature2mMeanPrevDay",
    "temperature2mMinPrevDay",
    "temperature2mMaxPrevDay",
    "temperature2mMean_3d",
    "temperature2mMean_7d",
    "temperature2mMean_14d",
    "temperature2mMinAtObservationDay",
    "temperature2mMaxAtObservationDay",
    "positive_degree_days_3d",
    "positive_degree_days_7d",
    "positive_degree_days_14d",
    "rain_prev_day_mm",
    "rain_3d_mm",
    "rain_7d_mm",
    "snowfall_prev_day_cm",
    "snowfall_3d_cm",
    "snowfall_7d_cm",
    "snowfall_14d_cm",
    "rainAtObservationDay",
    "snowfallAtObservationDay",
    "precipitation_hours_3d",
    "precipitation_hours_7d",
    "precipitation_hours_14d",
    "precipitationHoursAtObservationDay",
    "hasWatershed",
    "globalPastObsCount",
    "regionPastObsCount",
    "massifPastObsCount",
    "canyonPastObsCount",
    "globalPriorLow",
    "globalPriorMedium",
    "globalPriorHigh",
    "regionPriorLow",
    "regionPriorMedium",
    "regionPriorHigh",
    "massifPriorLow",
    "massifPriorMedium",
    "massifPriorHigh",
    "canyonPriorLow",
    "canyonPriorMedium",
    "canyonPriorHigh",
    "historicalRegulatedSignalCountCanyon",
    "historicalSnowmeltSignalCountCanyon",
    "historicalRegulatedSignalRatioCanyon",
    "historicalSnowmeltSignalRatioCanyon",
    "historicalRegulatedSignalRatioMassif",
    "historicalSnowmeltSignalRatioMassif",
    "historicalRegulatedSignalRatioRegion",
    "historicalSnowmeltSignalRatioRegion",
    "historicallyRegulatedCanyon",
    "historicallySnowmeltCanyon",
    "historicallyAtypicalCanyon",
]


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            stripped = line.strip()
            if stripped:
                rows.append(json.loads(stripped))
    return rows


def target_three_classes(level: str) -> str | None:
    if level in {"SEC", "FILET"}:
        return "LOW"
    if level == "CORRECT":
        return "MEDIUM"
    if level in {"GROS", "TRES_GROS", "CRUE"}:
        return "HIGH"
    return None


def target_six_classes(level: str) -> str | None:
    return level if level in {"SEC", "FILET", "CORRECT", "GROS", "TRES_GROS", "CRUE"} else None


def row_to_numeric_vector(row: dict[str, Any]) -> list[float]:
    vector: list[float] = []
    for feature_name in NUMERIC_FEATURES:
        value = row.get(feature_name)
        vector.append(float(value) if value is not None else -9999.0)
    return vector


def top_feature_importances(feature_names: list[str], importances: list[float], limit: int = 20) -> list[dict[str, Any]]:
    return sorted(
        [
            {"feature": feature_name, "importance": float(importance)}
            for feature_name, importance in zip(feature_names, importances)
        ],
        key=lambda item: item["importance"],
        reverse=True,
    )[:limit]


def split_temporal_rows(rows: list[dict[str, Any]], calibration_fraction: float, test_fraction: float) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
    total = len(rows)
    test_count = max(1, int(total * test_fraction))
    calibration_count = max(1, int(total * calibration_fraction))
    train_count = total - test_count - calibration_count
    if train_count < 50:
        raise SystemExit("Not enough rows left for the training split; lower --calibration-fraction or --test-fraction")
    train_rows = rows[:train_count]
    calibration_rows = rows[train_count:train_count + calibration_count]
    test_rows = rows[train_count + calibration_count:]
    return train_rows, calibration_rows, test_rows


def probabilities_to_predictions(probabilities: list[list[float]], labels: list[str]) -> list[str]:
    return [labels[max(range(len(labels)), key=lambda index: probs[index])] for probs in probabilities]


def probability_by_label(probabilities: list[list[float]], labels: list[str], label: str) -> list[float]:
    if label not in labels:
        return [0.0 for _ in probabilities]
    label_index = labels.index(label)
    return [float(probs[label_index]) for probs in probabilities]


def threshold_high_predictions(probabilities: list[list[float]], labels: list[str], threshold: float) -> list[str]:
    if "HIGH" not in labels:
        return probabilities_to_predictions(probabilities, labels)
    high_index = labels.index("HIGH")
    non_high_indices = [index for index, label in enumerate(labels) if label != "HIGH"]
    predictions: list[str] = []
    for probs in probabilities:
        if probs[high_index] >= threshold:
            predictions.append("HIGH")
            continue
        best_non_high_index = max(non_high_indices, key=lambda index: probs[index]) if non_high_indices else high_index
        predictions.append(labels[best_non_high_index])
    return predictions


def evaluate_predictions(
    *,
    y_true: list[str],
    predictions: list[str],
    labels: list[str],
    accuracy_score_fn: Any,
    balanced_accuracy_score_fn: Any,
    classification_report_fn: Any,
    confusion_matrix_fn: Any,
    f1_score_fn: Any,
) -> dict[str, Any]:
    report = classification_report_fn(y_true, predictions, labels=labels, output_dict=True, zero_division=0)
    return {
        "accuracy": accuracy_score_fn(y_true, predictions),
        "balancedAccuracy": balanced_accuracy_score_fn(y_true, predictions),
        "macroF1": f1_score_fn(y_true, predictions, labels=labels, average="macro", zero_division=0),
        "weightedF1": f1_score_fn(y_true, predictions, labels=labels, average="weighted", zero_division=0),
        "precisionHigh": report.get("HIGH", {}).get("precision"),
        "recallHigh": report.get("HIGH", {}).get("recall"),
        "f1High": report.get("HIGH", {}).get("f1-score"),
        "classificationReport": report,
        "confusionMatrix": confusion_matrix_fn(y_true, predictions, labels=labels).tolist(),
    }


def find_high_threshold(
    *,
    y_true: list[str],
    probabilities: list[list[float]],
    labels: list[str],
    policy: str,
    accuracy_score_fn: Any,
    balanced_accuracy_score_fn: Any,
    classification_report_fn: Any,
    confusion_matrix_fn: Any,
    f1_score_fn: Any,
) -> dict[str, Any]:
    candidate_thresholds = [round(step / 100.0, 2) for step in range(10, 91, 2)]
    best_result: dict[str, Any] | None = None

    for threshold in candidate_thresholds:
        predictions = threshold_high_predictions(probabilities, labels, threshold)
        metrics = evaluate_predictions(
            y_true=y_true,
            predictions=predictions,
            labels=labels,
            accuracy_score_fn=accuracy_score_fn,
            balanced_accuracy_score_fn=balanced_accuracy_score_fn,
            classification_report_fn=classification_report_fn,
            confusion_matrix_fn=confusion_matrix_fn,
            f1_score_fn=f1_score_fn,
        )
        metrics["threshold"] = threshold

        if best_result is None:
            best_result = metrics
            continue

        if policy == "prudent":
            current_precision = metrics.get("precisionHigh") or 0.0
            current_recall = metrics.get("recallHigh") or 0.0
            best_precision = best_result.get("precisionHigh") or 0.0
            best_recall = best_result.get("recallHigh") or 0.0
            current_score = (1 if current_recall >= 0.40 else 0, current_precision, current_recall, metrics["balancedAccuracy"])
            best_score = (1 if best_recall >= 0.40 else 0, best_precision, best_recall, best_result["balancedAccuracy"])
            if current_score > best_score:
                best_result = metrics
        else:
            current_score = (metrics.get("f1High") or 0.0, metrics["balancedAccuracy"], metrics.get("precisionHigh") or 0.0)
            best_score = (best_result.get("f1High") or 0.0, best_result["balancedAccuracy"], best_result.get("precisionHigh") or 0.0)
            if current_score > best_score:
                best_result = metrics

    assert best_result is not None
    return best_result


def main() -> None:
    parser = argparse.ArgumentParser(description="Train a calibrated débit baseline model from generated features")
    parser.add_argument("--features-path", default="build/debit-pipeline/training-features/training_features.jsonl")
    parser.add_argument("--output-dir", default="build/debit-pipeline/model-baseline")
    parser.add_argument("--model", choices=["random_forest", "catboost"], default="random_forest")
    parser.add_argument("--target", choices=["three", "six"], default="three")
    parser.add_argument("--calibration-method", choices=["none", "sigmoid", "isotonic"], default="sigmoid")
    parser.add_argument("--calibration-fraction", type=float, default=0.10)
    parser.add_argument("--test-fraction", type=float, default=0.20)
    args = parser.parse_args()

    try:
        from sklearn.calibration import CalibratedClassifierCV
        from sklearn.ensemble import RandomForestClassifier
        from sklearn.metrics import (
            accuracy_score,
            balanced_accuracy_score,
            brier_score_loss,
            classification_report,
            confusion_matrix,
            f1_score,
            log_loss,
        )
    except ImportError as exc:  # pragma: no cover
        raise SystemExit(
            "scikit-learn is required for this script. Install it with `python -m pip install scikit-learn`."
        ) from exc

    try:
        from sklearn.frozen import FrozenEstimator  # type: ignore
    except ImportError:  # pragma: no cover
        FrozenEstimator = None

    CatBoostClassifier = None
    if args.model == "catboost":
        try:
            from catboost import CatBoostClassifier  # type: ignore
        except ImportError as exc:  # pragma: no cover
            raise SystemExit(
                "catboost is required for `--model catboost`. Install it with `python -m pip install catboost`."
            ) from exc

    rows = read_jsonl(Path(args.features_path))
    target_mapper = target_three_classes if args.target == "three" else target_six_classes
    filtered = [row for row in rows if target_mapper(row.get("niveau")) is not None and row.get("date")]
    filtered.sort(key=lambda row: row["date"])
    if len(filtered) < 200:
        raise SystemExit("Not enough rows to train and calibrate a baseline model")

    train_rows, calibration_rows, test_rows = split_temporal_rows(
        filtered,
        calibration_fraction=args.calibration_fraction,
        test_fraction=args.test_fraction,
    )

    x_train = [row_to_numeric_vector(row) for row in train_rows]
    y_train = [target_mapper(row["niveau"]) for row in train_rows]
    x_calibration = [row_to_numeric_vector(row) for row in calibration_rows]
    y_calibration = [target_mapper(row["niveau"]) for row in calibration_rows]
    x_test = [row_to_numeric_vector(row) for row in test_rows]
    y_test = [target_mapper(row["niveau"]) for row in test_rows]

    if args.model == "catboost":
        model = CatBoostClassifier(
            iterations=500,
            depth=8,
            learning_rate=0.05,
            loss_function="MultiClass",
            eval_metric="TotalF1",
            random_seed=42,
            verbose=False,
            auto_class_weights="Balanced",
        )
    else:
        model = RandomForestClassifier(
            n_estimators=300,
            max_depth=12,
            min_samples_leaf=3,
            random_state=42,
            n_jobs=-1,
            class_weight="balanced_subsample",
        )

    model.fit(x_train, y_train)
    calibrated_model: Any = model
    if args.calibration_method != "none":
        if FrozenEstimator is not None:
            calibrated_model = CalibratedClassifierCV(FrozenEstimator(model), method=args.calibration_method, cv=None)
        else:
            calibrated_model = CalibratedClassifierCV(model, method=args.calibration_method, cv="prefit")
        calibrated_model.fit(x_calibration, y_calibration)

    labels = list(calibrated_model.classes_) if hasattr(calibrated_model, "classes_") else sorted(set(y_train) | set(y_test))
    calibration_probabilities = calibrated_model.predict_proba(x_calibration)
    test_probabilities = calibrated_model.predict_proba(x_test)
    argmax_predictions = probabilities_to_predictions(test_probabilities, labels)
    argmax_metrics = evaluate_predictions(
        y_true=y_test,
        predictions=argmax_predictions,
        labels=labels,
        accuracy_score_fn=accuracy_score,
        balanced_accuracy_score_fn=balanced_accuracy_score,
        classification_report_fn=classification_report,
        confusion_matrix_fn=confusion_matrix,
        f1_score_fn=f1_score,
    )

    calibration_high_probs = probability_by_label(calibration_probabilities, labels, "HIGH")
    test_high_probs = probability_by_label(test_probabilities, labels, "HIGH")
    probability_metrics = {
        "logLoss": log_loss(y_test, test_probabilities, labels=labels),
        "brierHigh": brier_score_loss([1 if label == "HIGH" else 0 for label in y_test], test_high_probs),
        "meanHighProbability": sum(test_high_probs) / len(test_high_probs) if test_high_probs else None,
    }

    threshold_policies: dict[str, Any] = {}
    if args.target == "three" and "HIGH" in labels:
        balanced_threshold_metrics = find_high_threshold(
            y_true=y_calibration,
            probabilities=calibration_probabilities,
            labels=labels,
            policy="balanced",
            accuracy_score_fn=accuracy_score,
            balanced_accuracy_score_fn=balanced_accuracy_score,
            classification_report_fn=classification_report,
            confusion_matrix_fn=confusion_matrix,
            f1_score_fn=f1_score,
        )
        prudent_threshold_metrics = find_high_threshold(
            y_true=y_calibration,
            probabilities=calibration_probabilities,
            labels=labels,
            policy="prudent",
            accuracy_score_fn=accuracy_score,
            balanced_accuracy_score_fn=balanced_accuracy_score,
            classification_report_fn=classification_report,
            confusion_matrix_fn=confusion_matrix,
            f1_score_fn=f1_score,
        )

        for policy_name, calibration_metrics in (("balanced", balanced_threshold_metrics), ("prudent", prudent_threshold_metrics)):
            threshold = calibration_metrics["threshold"]
            test_predictions = threshold_high_predictions(test_probabilities, labels, threshold)
            test_metrics = evaluate_predictions(
                y_true=y_test,
                predictions=test_predictions,
                labels=labels,
                accuracy_score_fn=accuracy_score,
                balanced_accuracy_score_fn=balanced_accuracy_score,
                classification_report_fn=classification_report,
                confusion_matrix_fn=confusion_matrix,
                f1_score_fn=f1_score,
            )
            threshold_policies[policy_name] = {
                "threshold": threshold,
                "calibrationMetrics": calibration_metrics,
                "testMetrics": test_metrics,
            }

    if args.model == "catboost":
        importances = list(model.get_feature_importance())
    else:
        importances = list(model.feature_importances_)
    feature_importance = top_feature_importances(NUMERIC_FEATURES, importances)

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    write_json(
        output_dir / "metrics.json",
        {
            "schemaVersion": 1,
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "model": args.model,
            "targetMode": args.target,
            "calibrationMethod": args.calibration_method,
            "trainRowCount": len(train_rows),
            "calibrationRowCount": len(calibration_rows),
            "testRowCount": len(test_rows),
            "validationRowCount": len(test_rows),
            "trainClassCounts": dict(sorted(Counter(y_train).items())),
            "calibrationClassCounts": dict(sorted(Counter(y_calibration).items())),
            "testClassCounts": dict(sorted(Counter(y_test).items())),
            "validationClassCounts": dict(sorted(Counter(y_test).items())),
            "accuracy": argmax_metrics["accuracy"],
            "balancedAccuracy": argmax_metrics["balancedAccuracy"],
            "macroF1": argmax_metrics["macroF1"],
            "weightedF1": argmax_metrics["weightedF1"],
            "precisionHigh": argmax_metrics["precisionHigh"],
            "recallHigh": argmax_metrics["recallHigh"],
            "f1High": argmax_metrics["f1High"],
            "classificationReport": argmax_metrics["classificationReport"],
            "labels": labels,
            "confusionMatrix": argmax_metrics["confusionMatrix"],
            "probabilityMetrics": probability_metrics,
            "thresholdPolicies": threshold_policies,
            "topFeatureImportances": feature_importance,
        },
    )


if __name__ == "__main__":
    main()
