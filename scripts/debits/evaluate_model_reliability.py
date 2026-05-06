from __future__ import annotations

import argparse
import math
import random
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from pipeline_lib import with_debit_derived_model_features, write_json
from train_baseline_model import (
    NUMERIC_FEATURES,
    apply_canyon_history_dropout,
    build_feature_coverage_report,
    evaluate_predictions,
    find_high_threshold,
    probability_by_label,
    probabilities_to_predictions,
    read_jsonl,
    row_to_numeric_vector,
    select_model_features,
    split_temporal_rows,
    target_three_classes,
    threshold_high_predictions,
    top_feature_importances,
)


DEFAULT_FEATURES_PATH = "build/debit-pipeline/training-features-improved/training_features.jsonl"
FALLBACK_FEATURES_PATH = "build/debit-pipeline/training-features/training_features.jsonl"
DEFAULT_OUTPUT_DIR = "build/debit-pipeline/model-reliability"
TARGET_LABELS = ["HIGH", "LOW", "MEDIUM"]
FEATURE_VARIANTS = (
    "full",
    "full_canyon_history_dropout",
    "no_canyon_history",
    "no_lookup_history",
    "history_only",
    "weather_only",
    "physical_only",
    "physical_weather_no_history",
)
SPLIT_MODES = ("temporal", "cold_canyon")
TEMPORAL_FEATURES = {"month", "monthSin", "monthCos"}
WEATHER_PREFIXES = (
    "antecedent_precipitation_index",
    "days_since_precip",
    "max_daily_precip",
    "positive_degree_days",
    "precip_",
    "precipitation",
    "rain",
    "snowfall",
    "temperature",
    "wet_days",
)


def json_ready(value: Any) -> Any:
    if isinstance(value, dict):
        return {str(key): json_ready(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [json_ready(item) for item in value]
    if hasattr(value, "item"):
        return value.item()
    if isinstance(value, float) and not math.isfinite(value):
        return None
    return value


def resolve_features_path(raw_path: str) -> Path:
    path = Path(raw_path)
    if raw_path == DEFAULT_FEATURES_PATH and not path.exists():
        fallback = Path(FALLBACK_FEATURES_PATH)
        if fallback.exists():
            return fallback
    return path


def is_weather_feature(feature_name: str) -> bool:
    return feature_name.startswith(WEATHER_PREFIXES)


def is_canyon_history_feature(feature_name: str) -> bool:
    return (
        feature_name == "canyonPastObsCount"
        or feature_name == "canyonHistoryConfidence"
        or feature_name in {"canyonHighPriorLift", "canyonLowPriorLift", "canyonPriorEntropy", "highLowPriorSpread"}
        or feature_name.startswith("canyonPrior")
        or feature_name.endswith("Canyon")
        or feature_name.startswith("historically")
    )


def is_lookup_history_feature(feature_name: str) -> bool:
    return (
        "Prior" in feature_name
        or feature_name.endswith("HistoryConfidence")
        or feature_name.endswith("PriorLift")
        or feature_name.endswith("PriorEntropy")
        or feature_name.endswith("PriorSpread")
        or feature_name == "highLowPriorSpread"
        or feature_name.endswith("PastObsCount")
        or feature_name.startswith("historical")
        or feature_name.startswith("historically")
    )


def feature_family(feature_name: str) -> str:
    if feature_name in TEMPORAL_FEATURES:
        return "temporal"
    if (
        feature_name.endswith("ClimatologyRatio")
        or feature_name.endswith("Signal")
        or feature_name.endswith("Volume30dProxy")
    ):
        return "derived_hydrology"
    if feature_name.endswith("Confidence") or feature_name.endswith("Lift") or feature_name.endswith("Entropy") or feature_name.endswith("Spread"):
        return "derived_history"
    if is_canyon_history_feature(feature_name):
        return "canyon_history"
    if is_lookup_history_feature(feature_name):
        return "regional_history"
    if is_weather_feature(feature_name):
        return "weather"
    return "physical_static"


def feature_names_for_variant(variant: str, active_feature_names: list[str]) -> list[str]:
    if variant in {"full", "full_canyon_history_dropout"}:
        selected = active_feature_names
    elif variant == "no_canyon_history":
        selected = [feature for feature in active_feature_names if not is_canyon_history_feature(feature)]
    elif variant == "no_lookup_history":
        selected = [feature for feature in active_feature_names if not is_lookup_history_feature(feature)]
    elif variant == "history_only":
        selected = [feature for feature in active_feature_names if is_lookup_history_feature(feature)]
    elif variant == "weather_only":
        selected = [feature for feature in active_feature_names if feature in TEMPORAL_FEATURES or is_weather_feature(feature)]
    elif variant == "physical_only":
        selected = [
            feature
            for feature in active_feature_names
            if feature in TEMPORAL_FEATURES
            or (not is_weather_feature(feature) and not is_lookup_history_feature(feature))
        ]
    elif variant == "physical_weather_no_history":
        selected = [feature for feature in active_feature_names if not is_lookup_history_feature(feature)]
    else:
        raise SystemExit(f"Unknown feature variant: {variant}")

    if not selected:
        raise SystemExit(f"Feature variant has no usable features: {variant}")
    return selected


def neutralize_canyon_history(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    neutralized_rows: list[dict[str, Any]] = []
    for row in rows:
        neutralized = dict(row)
        for feature_name in NUMERIC_FEATURES:
            if is_canyon_history_feature(feature_name):
                neutralized[feature_name] = 0.0
        neutralized_rows.append(neutralized)
    return neutralized_rows


def parse_csv(value: str, allowed_values: tuple[str, ...], label: str) -> list[str]:
    raw_values = [item.strip() for item in value.split(",") if item.strip()]
    unknown_values = [item for item in raw_values if item not in allowed_values]
    if unknown_values:
        raise SystemExit(f"Unknown {label}: {', '.join(unknown_values)}")
    return raw_values


def row_canyon_key(row: dict[str, Any]) -> str:
    return str(row.get("canyonId") or "__UNKNOWN_CANYON__")


def sorted_by_date(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return sorted(rows, key=lambda row: (row.get("date") or "", row.get("observationId") or ""))


def cold_canyon_split_rows(
    rows: list[dict[str, Any]],
    *,
    calibration_fraction: float,
    test_fraction: float,
    random_seed: int,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]], dict[str, Any]]:
    rows_by_canyon: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        rows_by_canyon[row_canyon_key(row)].append(row)

    canyon_keys = sorted(rows_by_canyon)
    random.Random(random_seed).shuffle(canyon_keys)
    total = len(rows)
    calibration_target = max(1, int(total * calibration_fraction))
    test_target = max(1, int(total * test_fraction))

    calibration_keys: set[str] = set()
    test_keys: set[str] = set()
    calibration_count = 0
    test_count = 0

    for canyon_key in canyon_keys:
        canyon_count = len(rows_by_canyon[canyon_key])
        if test_count < test_target:
            test_keys.add(canyon_key)
            test_count += canyon_count
        elif calibration_count < calibration_target:
            calibration_keys.add(canyon_key)
            calibration_count += canyon_count

    train_keys = set(canyon_keys) - calibration_keys - test_keys
    train_rows = sorted_by_date([row for key in train_keys for row in rows_by_canyon[key]])
    calibration_rows = sorted_by_date([row for key in calibration_keys for row in rows_by_canyon[key]])
    test_rows = sorted_by_date([row for key in test_keys for row in rows_by_canyon[key]])

    if len(train_rows) < 50 or not calibration_rows or not test_rows:
        raise SystemExit("Not enough rows left for cold-canyon train/calibration/test splits")

    metadata = {
        "splitMode": "cold_canyon",
        "trainCanyonCount": len(train_keys),
        "calibrationCanyonCount": len(calibration_keys),
        "testCanyonCount": len(test_keys),
        "trainRowCount": len(train_rows),
        "calibrationRowCount": len(calibration_rows),
        "testRowCount": len(test_rows),
        "hasCanyonLeakage": bool((train_keys & calibration_keys) or (train_keys & test_keys) or (calibration_keys & test_keys)),
    }
    return train_rows, calibration_rows, test_rows, metadata


def split_rows(
    rows: list[dict[str, Any]],
    *,
    split_mode: str,
    calibration_fraction: float,
    test_fraction: float,
    random_seed: int,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]], dict[str, Any]]:
    if split_mode == "temporal":
        train_rows, calibration_rows, test_rows = split_temporal_rows(
            rows,
            calibration_fraction=calibration_fraction,
            test_fraction=test_fraction,
        )
        metadata = {
            "splitMode": "temporal",
            "trainCanyonCount": len({row_canyon_key(row) for row in train_rows}),
            "calibrationCanyonCount": len({row_canyon_key(row) for row in calibration_rows}),
            "testCanyonCount": len({row_canyon_key(row) for row in test_rows}),
            "trainRowCount": len(train_rows),
            "calibrationRowCount": len(calibration_rows),
            "testRowCount": len(test_rows),
        }
        return train_rows, calibration_rows, test_rows, metadata
    if split_mode == "cold_canyon":
        return cold_canyon_split_rows(
            rows,
            calibration_fraction=calibration_fraction,
            test_fraction=test_fraction,
            random_seed=random_seed,
        )
    raise SystemExit(f"Unknown split mode: {split_mode}")


def probability_metrics(
    *,
    y_true: list[str],
    probabilities: Any,
    labels: list[str],
    log_loss_fn: Any,
    brier_score_loss_fn: Any,
) -> dict[str, Any]:
    probability_rows = [list(row) for row in probabilities]
    high_probabilities = probability_by_label(probability_rows, labels, "HIGH")
    high_truth = [1 if label == "HIGH" else 0 for label in y_true]
    return {
        "logLoss": log_loss_fn(y_true, probability_rows, labels=labels),
        "brierHigh": brier_score_loss_fn(high_truth, high_probabilities),
        "meanHighProbability": sum(high_probabilities) / len(high_probabilities) if high_probabilities else None,
        "highCalibration": calibration_bins(high_truth, high_probabilities),
    }


def calibration_bins(y_true_high: list[int], probabilities_high: list[float], *, bin_count: int = 10) -> dict[str, Any]:
    bins: list[dict[str, Any]] = []
    expected_calibration_error = 0.0
    total = len(probabilities_high)
    for index in range(bin_count):
        lower = index / bin_count
        upper = (index + 1) / bin_count
        selected_indices = [
            row_index
            for row_index, probability in enumerate(probabilities_high)
            if lower <= probability < upper or (index == bin_count - 1 and probability == upper)
        ]
        if not selected_indices:
            bins.append({"lower": lower, "upper": upper, "count": 0, "meanProbability": None, "observedRate": None})
            continue

        mean_probability = sum(probabilities_high[row_index] for row_index in selected_indices) / len(selected_indices)
        observed_rate = sum(y_true_high[row_index] for row_index in selected_indices) / len(selected_indices)
        expected_calibration_error += (len(selected_indices) / total) * abs(mean_probability - observed_rate) if total else 0.0
        bins.append(
            {
                "lower": round(lower, 2),
                "upper": round(upper, 2),
                "count": len(selected_indices),
                "meanProbability": round(mean_probability, 6),
                "observedRate": round(observed_rate, 6),
            }
        )

    return {
        "expectedCalibrationError": round(expected_calibration_error, 6),
        "bins": bins,
    }


def history_bucket(row: dict[str, Any]) -> str:
    raw_count = row.get("canyonPastObsCount")
    try:
        count = float(raw_count)
    except (TypeError, ValueError):
        count = 0.0
    if count <= 0:
        return "0"
    if count <= 5:
        return "1_5"
    if count <= 20:
        return "6_20"
    return "20_plus"


def segmented_metrics(
    *,
    rows: list[dict[str, Any]],
    y_true: list[str],
    predictions: list[str],
    labels: list[str],
    accuracy_score_fn: Any,
    balanced_accuracy_score_fn: Any,
    classification_report_fn: Any,
    confusion_matrix_fn: Any,
    f1_score_fn: Any,
) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for bucket in ("0", "1_5", "6_20", "20_plus"):
        indices = [index for index, row in enumerate(rows) if history_bucket(row) == bucket]
        if not indices:
            continue
        bucket_true = [y_true[index] for index in indices]
        bucket_predictions = [predictions[index] for index in indices]
        result[bucket] = {
            "rowCount": len(indices),
            "classCounts": dict(sorted(Counter(bucket_true).items())),
            "metrics": evaluate_predictions(
                y_true=bucket_true,
                predictions=bucket_predictions,
                labels=labels,
                accuracy_score_fn=accuracy_score_fn,
                balanced_accuracy_score_fn=balanced_accuracy_score_fn,
                classification_report_fn=classification_report_fn,
                confusion_matrix_fn=confusion_matrix_fn,
                f1_score_fn=f1_score_fn,
            ),
        }
    return result


def error_samples(
    *,
    rows: list[dict[str, Any]],
    y_true: list[str],
    predictions: list[str],
    probabilities: Any,
    labels: list[str],
    limit: int,
) -> list[dict[str, Any]]:
    probability_rows = [list(row) for row in probabilities]
    high_index = labels.index("HIGH") if "HIGH" in labels else None
    samples: list[dict[str, Any]] = []
    for row, truth, prediction, probability_row in zip(rows, y_true, predictions, probability_rows):
        if truth == prediction:
            continue
        max_probability = max(float(value) for value in probability_row)
        samples.append(
            {
                "observationId": row.get("observationId"),
                "canyonId": row.get("canyonId"),
                "canyonName": row.get("canyonName"),
                "date": row.get("date"),
                "trueLabel": truth,
                "predictedLabel": prediction,
                "maxProbability": round(max_probability, 6),
                "highProbability": round(float(probability_row[high_index]), 6) if high_index is not None else None,
                "canyonPastObsCount": row.get("canyonPastObsCount"),
                "region": row.get("region"),
                "massif": row.get("massif"),
                "precip30dMm": row.get("precip_30d_mm"),
                "temperatureMean14d": row.get("temperature2mMean_14d"),
            }
        )
    samples.sort(key=lambda item: item["maxProbability"], reverse=True)
    return samples[:limit]


def summed_feature_importances(feature_names: list[str], importances: list[float]) -> list[dict[str, Any]]:
    totals: dict[str, float] = defaultdict(float)
    for feature_name, importance in zip(feature_names, importances):
        totals[feature_family(feature_name)] += float(importance)
    return [
        {"featureFamily": family, "importance": round(importance, 8)}
        for family, importance in sorted(totals.items(), key=lambda item: item[1], reverse=True)
    ]


def fit_model(
    *,
    x_train: list[list[float]],
    y_train: list[str],
    x_calibration: list[list[float]],
    y_calibration: list[str],
    calibration_method: str,
    n_estimators: int,
    max_depth: int,
    min_samples_leaf: int,
    random_seed: int,
    n_jobs: int,
    RandomForestClassifier: Any,
    CalibratedClassifierCV: Any,
    FrozenEstimator: Any,
) -> tuple[Any, Any]:
    model = RandomForestClassifier(
        n_estimators=n_estimators,
        max_depth=max_depth,
        min_samples_leaf=min_samples_leaf,
        random_state=random_seed,
        n_jobs=n_jobs,
        class_weight="balanced_subsample",
    )
    model.fit(x_train, y_train)

    calibrated_model: Any = model
    if calibration_method != "none":
        if FrozenEstimator is not None:
            calibrated_model = CalibratedClassifierCV(FrozenEstimator(model), method=calibration_method, cv=None)
        else:
            calibrated_model = CalibratedClassifierCV(model, method=calibration_method, cv="prefit")
        calibrated_model.fit(x_calibration, y_calibration)
    return model, calibrated_model


def evaluate_model_run(
    *,
    split_mode: str,
    variant: str,
    feature_names: list[str],
    train_rows: list[dict[str, Any]],
    calibration_rows: list[dict[str, Any]],
    test_rows: list[dict[str, Any]],
    split_metadata: dict[str, Any],
    args: argparse.Namespace,
    sklearn: dict[str, Any],
) -> dict[str, Any]:
    if split_mode == "cold_canyon" and not args.keep_cold_canyon_history:
        calibration_rows = neutralize_canyon_history(calibration_rows)
        test_rows = neutralize_canyon_history(test_rows)
        split_metadata = {**split_metadata, "canyonHistoryNeutralized": True}
    else:
        split_metadata = {**split_metadata, "canyonHistoryNeutralized": False}

    training_feature_rows = train_rows
    canyon_history_dropout_rate = 0.0
    if variant == "full_canyon_history_dropout":
        canyon_history_dropout_rate = args.canyon_history_dropout_rate
        training_feature_rows = apply_canyon_history_dropout(
            train_rows,
            dropout_rate=canyon_history_dropout_rate,
            random_seed=args.random_seed,
        )

    x_train = [row_to_numeric_vector(row, feature_names) for row in training_feature_rows]
    y_train = [target_three_classes(row["niveau"]) for row in train_rows]
    x_calibration = [row_to_numeric_vector(row, feature_names) for row in calibration_rows]
    y_calibration = [target_three_classes(row["niveau"]) for row in calibration_rows]
    x_test = [row_to_numeric_vector(row, feature_names) for row in test_rows]
    y_test = [target_three_classes(row["niveau"]) for row in test_rows]

    model, calibrated_model = fit_model(
        x_train=x_train,
        y_train=y_train,
        x_calibration=x_calibration,
        y_calibration=y_calibration,
        calibration_method=args.calibration_method,
        n_estimators=args.n_estimators,
        max_depth=args.max_depth,
        min_samples_leaf=args.min_samples_leaf,
        random_seed=args.random_seed,
        n_jobs=args.n_jobs,
        RandomForestClassifier=sklearn["RandomForestClassifier"],
        CalibratedClassifierCV=sklearn["CalibratedClassifierCV"],
        FrozenEstimator=sklearn["FrozenEstimator"],
    )

    labels = list(calibrated_model.classes_) if hasattr(calibrated_model, "classes_") else TARGET_LABELS
    calibration_probabilities = calibrated_model.predict_proba(x_calibration)
    test_probabilities = calibrated_model.predict_proba(x_test)
    argmax_predictions = probabilities_to_predictions([list(row) for row in test_probabilities], labels)
    argmax_metrics = evaluate_predictions(
        y_true=y_test,
        predictions=argmax_predictions,
        labels=labels,
        accuracy_score_fn=sklearn["accuracy_score"],
        balanced_accuracy_score_fn=sklearn["balanced_accuracy_score"],
        classification_report_fn=sklearn["classification_report"],
        confusion_matrix_fn=sklearn["confusion_matrix"],
        f1_score_fn=sklearn["f1_score"],
    )

    threshold_policies: dict[str, Any] = {}
    if "HIGH" in labels:
        for policy_name in ("balanced", "prudent", "safety_first"):
            calibration_metrics = find_high_threshold(
                y_true=y_calibration,
                probabilities=[list(row) for row in calibration_probabilities],
                labels=labels,
                policy=policy_name,
                accuracy_score_fn=sklearn["accuracy_score"],
                balanced_accuracy_score_fn=sklearn["balanced_accuracy_score"],
                classification_report_fn=sklearn["classification_report"],
                confusion_matrix_fn=sklearn["confusion_matrix"],
                f1_score_fn=sklearn["f1_score"],
            )
            threshold = calibration_metrics["threshold"]
            test_predictions = threshold_high_predictions([list(row) for row in test_probabilities], labels, threshold)
            test_metrics = evaluate_predictions(
                y_true=y_test,
                predictions=test_predictions,
                labels=labels,
                accuracy_score_fn=sklearn["accuracy_score"],
                balanced_accuracy_score_fn=sklearn["balanced_accuracy_score"],
                classification_report_fn=sklearn["classification_report"],
                confusion_matrix_fn=sklearn["confusion_matrix"],
                f1_score_fn=sklearn["f1_score"],
            )
            threshold_policies[policy_name] = {
                "threshold": threshold,
                "calibrationMetrics": calibration_metrics,
                "testMetrics": test_metrics,
            }

    balanced_predictions = argmax_predictions
    if threshold_policies.get("balanced"):
        balanced_predictions = threshold_high_predictions(
            [list(row) for row in test_probabilities],
            labels,
            threshold_policies["balanced"]["threshold"],
        )

    importances = list(model.feature_importances_)
    return {
        "splitMode": split_mode,
        "featureVariant": variant,
        "canyonHistoryDropoutRate": canyon_history_dropout_rate,
        "featureCount": len(feature_names),
        "features": feature_names,
        "split": split_metadata,
        "trainClassCounts": dict(sorted(Counter(y_train).items())),
        "calibrationClassCounts": dict(sorted(Counter(y_calibration).items())),
        "testClassCounts": dict(sorted(Counter(y_test).items())),
        "argmaxMetrics": argmax_metrics,
        "probabilityMetrics": probability_metrics(
            y_true=y_test,
            probabilities=test_probabilities,
            labels=labels,
            log_loss_fn=sklearn["log_loss"],
            brier_score_loss_fn=sklearn["brier_score_loss"],
        ),
        "thresholdPolicies": threshold_policies,
        "historyBucketMetrics": segmented_metrics(
            rows=test_rows,
            y_true=y_test,
            predictions=balanced_predictions,
            labels=labels,
            accuracy_score_fn=sklearn["accuracy_score"],
            balanced_accuracy_score_fn=sklearn["balanced_accuracy_score"],
            classification_report_fn=sklearn["classification_report"],
            confusion_matrix_fn=sklearn["confusion_matrix"],
            f1_score_fn=sklearn["f1_score"],
        ),
        "topFeatureImportances": top_feature_importances(feature_names, importances, limit=30),
        "featureFamilyImportances": summed_feature_importances(feature_names, importances),
        "errorSamples": error_samples(
            rows=test_rows,
            y_true=y_test,
            predictions=balanced_predictions,
            probabilities=test_probabilities,
            labels=labels,
            limit=args.error_sample_limit,
        ),
    }


def run_score(run: dict[str, Any]) -> tuple[float, float, float, float]:
    balanced_policy = run.get("thresholdPolicies", {}).get("balanced", {})
    test_metrics = balanced_policy.get("testMetrics") or run.get("argmaxMetrics", {})
    return (
        float(test_metrics.get("f1High") or 0.0),
        float(test_metrics.get("balancedAccuracy") or 0.0),
        float(test_metrics.get("recallHigh") or 0.0),
        float(test_metrics.get("precisionHigh") or 0.0),
    )


def build_recommendation(runs: list[dict[str, Any]]) -> dict[str, Any]:
    recommendation: dict[str, Any] = {
        "selectionMetric": "balanced policy HIGH F1, then balanced accuracy",
        "notes": [
            "Use cold_canyon as the primary fidelity signal for canyons without own history.",
            "Use temporal as the production-like signal for already observed canyons.",
        ],
    }
    for split_mode in SPLIT_MODES:
        split_runs = [run for run in runs if run["splitMode"] == split_mode]
        if not split_runs:
            continue
        best_run = max(split_runs, key=run_score)
        recommendation[f"best_{split_mode}"] = {
            "featureVariant": best_run["featureVariant"],
            "featureCount": best_run["featureCount"],
            "score": run_score(best_run),
        }
    return recommendation


def markdown_metric(value: Any) -> str:
    if value is None:
        return "n/a"
    try:
        return f"{float(value):.4f}"
    except (TypeError, ValueError):
        return str(value)


def build_markdown_report(report: dict[str, Any]) -> str:
    lines = [
        "# Debit Model Reliability",
        "",
        f"Generated at: `{report['generatedAt']}`",
        f"Features path: `{report['featuresPath']}`",
        f"Rows: `{report['rowCount']}`",
        "",
        "## Summary",
        "",
        "| Split | Variant | Features | Argmax macro F1 | Argmax HIGH F1 | Balanced HIGH F1 | Balanced HIGH recall | Brier HIGH |",
        "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for run in report["runs"]:
        balanced_metrics = run.get("thresholdPolicies", {}).get("balanced", {}).get("testMetrics", {})
        probability_metrics_payload = run.get("probabilityMetrics", {})
        lines.append(
            "| "
            + " | ".join(
                [
                    run["splitMode"],
                    run["featureVariant"],
                    str(run["featureCount"]),
                    markdown_metric(run["argmaxMetrics"].get("macroF1")),
                    markdown_metric(run["argmaxMetrics"].get("f1High")),
                    markdown_metric(balanced_metrics.get("f1High")),
                    markdown_metric(balanced_metrics.get("recallHigh")),
                    markdown_metric(probability_metrics_payload.get("brierHigh")),
                ]
            )
            + " |"
        )

    recommendation = report.get("recommendation", {})
    lines.extend(["", "## Recommendation", ""])
    for key, value in recommendation.items():
        if key in {"notes", "selectionMetric"}:
            continue
        if isinstance(value, dict):
            score = value.get("score") or []
            score_text = ", ".join(markdown_metric(item) for item in score)
            lines.append(
                f"- `{key}`: `{value.get('featureVariant')}` "
                f"({value.get('featureCount')} features, score: {score_text})"
            )
        else:
            lines.append(f"- `{key}`: `{value}`")
    if recommendation.get("selectionMetric"):
        lines.append(f"- Selection metric: {recommendation['selectionMetric']}")
    for note in recommendation.get("notes", []):
        lines.append(f"- {note}")
    lines.append("")
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate debit model reliability across splits and feature ablations")
    parser.add_argument("--features-path", default=DEFAULT_FEATURES_PATH)
    parser.add_argument("--output-dir", default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--split-modes", default="temporal,cold_canyon")
    parser.add_argument("--feature-variants", default="full,full_canyon_history_dropout,no_canyon_history,no_lookup_history,history_only,weather_only,physical_only")
    parser.add_argument("--calibration-method", choices=["none", "sigmoid", "isotonic"], default="sigmoid")
    parser.add_argument("--calibration-fraction", type=float, default=0.10)
    parser.add_argument("--test-fraction", type=float, default=0.20)
    parser.add_argument("--n-estimators", type=int, default=180)
    parser.add_argument("--max-depth", type=int, default=12)
    parser.add_argument("--min-samples-leaf", type=int, default=3)
    parser.add_argument(
        "--canyon-history-dropout-rate",
        type=float,
        default=0.35,
        help="Dropout rate used by the full_canyon_history_dropout reliability variant",
    )
    parser.add_argument("--random-seed", type=int, default=42)
    parser.add_argument("--n-jobs", type=int, default=-1)
    parser.add_argument("--error-sample-limit", type=int, default=40)
    parser.add_argument(
        "--keep-cold-canyon-history",
        action="store_true",
        help="Keep precomputed canyon-specific history in cold-canyon calibration/test rows",
    )
    parser.add_argument(
        "--keep-uninformative-features",
        action="store_true",
        help="Keep features that are all missing or constant in the training corpus",
    )
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

    split_modes = parse_csv(args.split_modes, SPLIT_MODES, "split mode")
    feature_variants = parse_csv(args.feature_variants, FEATURE_VARIANTS, "feature variant")
    features_path = resolve_features_path(args.features_path)
    rows = read_jsonl(features_path)
    filtered = [
        with_debit_derived_model_features(row)
        for row in rows
        if target_three_classes(row.get("niveau")) is not None and row.get("date")
    ]
    filtered = sorted_by_date(filtered)
    if len(filtered) < 200:
        raise SystemExit("Not enough rows to evaluate model reliability")

    feature_coverage = build_feature_coverage_report(filtered, NUMERIC_FEATURES)
    active_feature_names = select_model_features(
        NUMERIC_FEATURES,
        feature_coverage,
        keep_uninformative_features=args.keep_uninformative_features,
    )
    if not active_feature_names:
        raise SystemExit("No usable numeric features left after coverage filtering")

    sklearn = {
        "RandomForestClassifier": RandomForestClassifier,
        "CalibratedClassifierCV": CalibratedClassifierCV,
        "FrozenEstimator": FrozenEstimator,
        "accuracy_score": accuracy_score,
        "balanced_accuracy_score": balanced_accuracy_score,
        "brier_score_loss": brier_score_loss,
        "classification_report": classification_report,
        "confusion_matrix": confusion_matrix,
        "f1_score": f1_score,
        "log_loss": log_loss,
    }

    runs: list[dict[str, Any]] = []
    for split_mode in split_modes:
        train_rows, calibration_rows, test_rows, split_metadata = split_rows(
            filtered,
            split_mode=split_mode,
            calibration_fraction=args.calibration_fraction,
            test_fraction=args.test_fraction,
            random_seed=args.random_seed,
        )
        for variant in feature_variants:
            feature_names = feature_names_for_variant(variant, active_feature_names)
            runs.append(
                evaluate_model_run(
                    split_mode=split_mode,
                    variant=variant,
                    feature_names=feature_names,
                    train_rows=train_rows,
                    calibration_rows=calibration_rows,
                    test_rows=test_rows,
                    split_metadata=split_metadata,
                    args=args,
                    sklearn=sklearn,
                )
            )

    report = json_ready(
        {
            "schemaVersion": 1,
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "featuresPath": str(features_path),
            "rowCount": len(filtered),
            "targetMode": "three",
            "model": "random_forest_reliability_ablation",
            "calibrationMethod": args.calibration_method,
            "nEstimators": args.n_estimators,
            "maxDepth": args.max_depth,
            "minSamplesLeaf": args.min_samples_leaf,
            "activeFeatureCount": len(active_feature_names),
            "activeFeatures": active_feature_names,
            "droppedFeatureCount": len(NUMERIC_FEATURES) - len(active_feature_names),
            "featureCoverage": feature_coverage,
            "runs": runs,
            "recommendation": build_recommendation(runs),
        }
    )

    output_dir = Path(args.output_dir)
    write_json(output_dir / "reliability_report.json", report)
    (output_dir / "reliability_report.md").write_text(build_markdown_report(report), encoding="utf-8")


if __name__ == "__main__":
    main()
