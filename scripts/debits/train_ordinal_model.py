from __future__ import annotations

import argparse
import json
import math
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from pipeline_lib import with_debit_derived_model_features, write_json
from evaluate_model_reliability import neutralize_canyon_history, sorted_by_date, split_rows
from train_baseline_model import (
    NUMERIC_FEATURES,
    apply_canyon_history_dropout,
    build_feature_coverage_report,
    evaluate_predictions,
    numeric_feature_value,
    row_to_numeric_vector,
    sample_weights,
    select_model_features,
    target_three_classes,
    top_feature_importances,
)


DEFAULT_FEATURES_PATH = "build/debit-pipeline/training-features-improved/training_features.jsonl"
DEFAULT_OUTPUT_DIR = "build/debit-pipeline/model-ordinal"
LEVEL_TO_RANK = {
    "SEC": 0,
    "FILET": 1,
    "CORRECT": 2,
    "GROS": 3,
    "TRES_GROS": 4,
    "CRUE": 5,
}
RANK_TO_LEVEL = ["SEC", "FILET", "CORRECT", "GROS", "TRES_GROS", "CRUE"]
THREE_LABELS = ["LOW", "MEDIUM", "HIGH"]
SIX_LABELS = ["SEC", "FILET", "CORRECT", "GROS", "TRES_GROS", "CRUE"]


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            stripped = line.strip()
            if stripped:
                rows.append(with_debit_derived_model_features(json.loads(stripped)))
    return rows


def target_rank(level: str | None) -> int | None:
    return LEVEL_TO_RANK.get(level or "")


def training_rank(row: dict[str, Any], *, use_soft_target_rank: bool) -> float | int | None:
    if use_soft_target_rank:
        soft_rank = numeric_feature_value(row.get("softTargetRank"))
        if soft_rank is not None:
            return min(max(soft_rank, 0.0), 5.0)
    return target_rank(row.get("niveau"))


def rank_to_level(score: float) -> str:
    rank = int(round(min(max(score, 0.0), 5.0)))
    return RANK_TO_LEVEL[rank]


def rank_to_three(score: float, *, low_threshold: float, high_threshold: float) -> str:
    if score >= high_threshold:
        return "HIGH"
    if score < low_threshold:
        return "LOW"
    return "MEDIUM"


def ordinal_error_metrics(y_true_ranks: list[int], predicted_scores: list[float]) -> dict[str, Any]:
    errors = [float(predicted) - float(actual) for actual, predicted in zip(y_true_ranks, predicted_scores)]
    abs_errors = [abs(error) for error in errors]
    severe_errors = [abs(error) >= 2.0 for error in errors]
    return {
        "maeRank": sum(abs_errors) / len(abs_errors) if abs_errors else None,
        "rmseRank": math.sqrt(sum(error * error for error in errors) / len(errors)) if errors else None,
        "meanSignedErrorRank": sum(errors) / len(errors) if errors else None,
        "severeOrdinalErrorFraction": sum(1 for value in severe_errors if value) / len(severe_errors) if severe_errors else None,
    }


def class_balanced_sample_weights(rows: list[dict[str, Any]], base_weights: list[float] | None) -> list[float]:
    labels = [row.get("niveau") for row in rows]
    counts = Counter(label for label in labels if target_rank(label) is not None)
    total = sum(counts.values())
    class_count = len(counts)
    weights = base_weights or [1.0 for _ in rows]
    if total == 0 or class_count == 0:
        return weights
    result: list[float] = []
    for row, weight in zip(rows, weights):
        label = row.get("niveau")
        count = counts.get(label, 0)
        multiplier = total / (class_count * count) if count else 1.0
        result.append(weight * multiplier)
    return result


def fit_sample_weights(rows: list[dict[str, Any]], *, ignore_sample_weights: bool, class_balanced_weights: bool) -> list[float] | None:
    weights = None if ignore_sample_weights else sample_weights(rows)
    if class_balanced_weights:
        weights = class_balanced_sample_weights(rows, weights)
    return weights


def threshold_policy_score(metrics: dict[str, Any], threshold_pair: tuple[float, float], policy: str) -> tuple[float, ...]:
    precision = metrics.get("precisionHigh") or 0.0
    recall = metrics.get("recallHigh") or 0.0
    high_f1 = metrics.get("f1High") or 0.0
    balanced = metrics.get("balancedAccuracy") or 0.0
    low_threshold, high_threshold = threshold_pair
    if policy == "prudent":
        return (1.0 if recall >= 0.40 else 0.0, precision, recall, balanced, high_threshold)
    if policy == "safety_first":
        return (1.0 if precision >= 0.25 else 0.0, recall, high_f1, balanced, -high_threshold)
    return (high_f1, balanced, precision, recall, -abs(low_threshold - 1.5))


def fast_three_metrics(y_true: list[str], scores: list[float], *, low_threshold: float, high_threshold: float) -> dict[str, Any]:
    label_to_index = {label: index for index, label in enumerate(THREE_LABELS)}
    confusion = [[0, 0, 0] for _ in THREE_LABELS]
    for truth, score in zip(y_true, scores):
        prediction = rank_to_three(score, low_threshold=low_threshold, high_threshold=high_threshold)
        confusion[label_to_index[truth]][label_to_index[prediction]] += 1

    total = sum(sum(row) for row in confusion)
    correct = sum(confusion[index][index] for index in range(len(THREE_LABELS)))
    recalls: list[float] = []
    f1_scores: list[float] = []
    supports: list[int] = []
    for index in range(len(THREE_LABELS)):
        tp = confusion[index][index]
        support = sum(confusion[index])
        predicted = sum(confusion[row_index][index] for row_index in range(len(THREE_LABELS)))
        precision = tp / predicted if predicted else 0.0
        recall = tp / support if support else 0.0
        f1 = 2.0 * precision * recall / (precision + recall) if precision + recall else 0.0
        recalls.append(recall)
        f1_scores.append(f1)
        supports.append(support)

    high_index = label_to_index["HIGH"]
    high_tp = confusion[high_index][high_index]
    high_predicted = sum(confusion[row_index][high_index] for row_index in range(len(THREE_LABELS)))
    high_support = sum(confusion[high_index])
    precision_high = high_tp / high_predicted if high_predicted else 0.0
    recall_high = high_tp / high_support if high_support else 0.0
    f1_high = 2.0 * precision_high * recall_high / (precision_high + recall_high) if precision_high + recall_high else 0.0
    return {
        "accuracy": correct / total if total else 0.0,
        "balancedAccuracy": sum(recalls) / len(recalls) if recalls else 0.0,
        "macroF1": sum(f1_scores) / len(f1_scores) if f1_scores else 0.0,
        "weightedF1": sum(f1 * support for f1, support in zip(f1_scores, supports)) / total if total else 0.0,
        "precisionHigh": precision_high,
        "recallHigh": recall_high,
        "f1High": f1_high,
    }


def find_ordinal_thresholds(
    *,
    y_true: list[str],
    scores: list[float],
    policy: str,
    accuracy_score_fn: Any,
    balanced_accuracy_score_fn: Any,
    classification_report_fn: Any,
    confusion_matrix_fn: Any,
    f1_score_fn: Any,
) -> dict[str, Any]:
    best_result: dict[str, Any] | None = None
    best_pair: tuple[float, float] | None = None
    low_thresholds = [round(value / 10.0, 2) for value in range(5, 26)]
    high_thresholds = [round(value / 10.0, 2) for value in range(22, 51)]
    for low_threshold in low_thresholds:
        for high_threshold in high_thresholds:
            if low_threshold >= high_threshold:
                continue
            metrics = fast_three_metrics(y_true, scores, low_threshold=low_threshold, high_threshold=high_threshold)
            pair = (low_threshold, high_threshold)
            if best_result is None or threshold_policy_score(metrics, pair, policy) > threshold_policy_score(best_result, best_pair or pair, policy):
                best_result = metrics
                best_pair = pair

    assert best_result is not None and best_pair is not None
    predictions = [rank_to_three(score, low_threshold=best_pair[0], high_threshold=best_pair[1]) for score in scores]
    full_result = evaluate_predictions(
        y_true=y_true,
        predictions=predictions,
        labels=THREE_LABELS,
        accuracy_score_fn=accuracy_score_fn,
        balanced_accuracy_score_fn=balanced_accuracy_score_fn,
        classification_report_fn=classification_report_fn,
        confusion_matrix_fn=confusion_matrix_fn,
        f1_score_fn=f1_score_fn,
    )
    full_result["lowThreshold"] = best_pair[0]
    full_result["highThreshold"] = best_pair[1]
    return full_result


def evaluate_ordinal_model(
    *,
    train_rows: list[dict[str, Any]],
    calibration_rows: list[dict[str, Any]],
    test_rows: list[dict[str, Any]],
    feature_names: list[str],
    args: argparse.Namespace,
    sklearn: dict[str, Any],
) -> dict[str, Any]:
    training_feature_rows = apply_canyon_history_dropout(
        train_rows,
        dropout_rate=args.canyon_history_dropout_rate,
        random_seed=args.random_seed,
    )
    x_train = [row_to_numeric_vector(row, feature_names) for row in training_feature_rows]
    y_train = [training_rank(row, use_soft_target_rank=args.use_soft_target_rank) for row in train_rows]
    x_calibration = [row_to_numeric_vector(row, feature_names) for row in calibration_rows]
    y_calibration_ranks = [target_rank(row.get("niveau")) for row in calibration_rows]
    x_test = [row_to_numeric_vector(row, feature_names) for row in test_rows]
    y_test_ranks = [target_rank(row.get("niveau")) for row in test_rows]

    if args.model == "catboost_classifier":
        model = sklearn["CatBoostClassifier"](
            iterations=args.n_estimators,
            depth=args.max_depth,
            learning_rate=args.learning_rate,
            loss_function="MultiClass",
            eval_metric="TotalF1",
            random_seed=args.random_seed,
            thread_count=args.n_jobs,
            verbose=False,
            allow_writing_files=False,
            l2_leaf_reg=args.l2_leaf_reg,
        )
    elif args.model == "catboost":
        model = sklearn["CatBoostRegressor"](
            iterations=args.n_estimators,
            depth=args.max_depth,
            learning_rate=args.learning_rate,
            loss_function="RMSE",
            eval_metric="MAE",
            random_seed=args.random_seed,
            thread_count=args.n_jobs,
            verbose=False,
            allow_writing_files=False,
            l2_leaf_reg=args.l2_leaf_reg,
        )
    elif args.model == "hist_gradient_boosting":
        model = sklearn["HistGradientBoostingRegressor"](
            max_iter=args.n_estimators,
            max_leaf_nodes=args.max_leaf_nodes,
            max_depth=args.max_depth,
            min_samples_leaf=args.min_samples_leaf,
            learning_rate=args.learning_rate,
            random_state=args.random_seed,
        )
    elif args.model == "extra_trees":
        model = sklearn["ExtraTreesRegressor"](
            n_estimators=args.n_estimators,
            max_depth=args.max_depth,
            min_samples_leaf=args.min_samples_leaf,
            random_state=args.random_seed,
            n_jobs=args.n_jobs,
        )
    else:
        model = sklearn["RandomForestRegressor"](
            n_estimators=args.n_estimators,
            max_depth=args.max_depth,
            min_samples_leaf=args.min_samples_leaf,
            random_state=args.random_seed,
            n_jobs=args.n_jobs,
        )
    train_weights = fit_sample_weights(
        training_feature_rows,
        ignore_sample_weights=args.ignore_sample_weights,
        class_balanced_weights=not args.no_class_balanced_weights,
    )
    fit_kwargs = {"sample_weight": train_weights} if train_weights is not None else {}
    if args.model == "catboost_classifier":
        y_fit = [str(row.get("niveau")) for row in train_rows]
    else:
        y_fit = y_train
    model.fit(x_train, y_fit, **fit_kwargs)

    if args.model == "catboost_classifier":
        classes = [str(value) for value in model.classes_]

        def expected_scores(probabilities: Any) -> list[float]:
            scores: list[float] = []
            for row in probabilities:
                scores.append(
                    sum(float(probability) * float(LEVEL_TO_RANK.get(label, 0)) for label, probability in zip(classes, row))
                )
            return scores

        calibration_scores = expected_scores(model.predict_proba(x_calibration))
        test_scores = expected_scores(model.predict_proba(x_test))
    else:
        calibration_scores = [float(value) for value in model.predict(x_calibration)]
        test_scores = [float(value) for value in model.predict(x_test)]
    y_calibration_three = [target_three_classes(row.get("niveau")) for row in calibration_rows]
    y_test_three = [target_three_classes(row.get("niveau")) for row in test_rows]
    y_test_six = [row.get("niveau") for row in test_rows]

    rounded_six_predictions = [rank_to_level(score) for score in test_scores]
    rounded_three_predictions = [target_three_classes(level) for level in rounded_six_predictions]

    rounded_three_metrics = evaluate_predictions(
        y_true=y_test_three,
        predictions=rounded_three_predictions,
        labels=THREE_LABELS,
        accuracy_score_fn=sklearn["accuracy_score"],
        balanced_accuracy_score_fn=sklearn["balanced_accuracy_score"],
        classification_report_fn=sklearn["classification_report"],
        confusion_matrix_fn=sklearn["confusion_matrix"],
        f1_score_fn=sklearn["f1_score"],
    )
    rounded_six_metrics = evaluate_predictions(
        y_true=y_test_six,
        predictions=rounded_six_predictions,
        labels=SIX_LABELS,
        accuracy_score_fn=sklearn["accuracy_score"],
        balanced_accuracy_score_fn=sklearn["balanced_accuracy_score"],
        classification_report_fn=sklearn["classification_report"],
        confusion_matrix_fn=sklearn["confusion_matrix"],
        f1_score_fn=sklearn["f1_score"],
    )

    threshold_policies: dict[str, Any] = {}
    for policy in ("balanced", "prudent", "safety_first"):
        calibration_metrics = find_ordinal_thresholds(
            y_true=y_calibration_three,
            scores=calibration_scores,
            policy=policy,
            accuracy_score_fn=sklearn["accuracy_score"],
            balanced_accuracy_score_fn=sklearn["balanced_accuracy_score"],
            classification_report_fn=sklearn["classification_report"],
            confusion_matrix_fn=sklearn["confusion_matrix"],
            f1_score_fn=sklearn["f1_score"],
        )
        low_threshold = calibration_metrics["lowThreshold"]
        high_threshold = calibration_metrics["highThreshold"]
        test_predictions = [rank_to_three(score, low_threshold=low_threshold, high_threshold=high_threshold) for score in test_scores]
        test_metrics = evaluate_predictions(
            y_true=y_test_three,
            predictions=test_predictions,
            labels=THREE_LABELS,
            accuracy_score_fn=sklearn["accuracy_score"],
            balanced_accuracy_score_fn=sklearn["balanced_accuracy_score"],
            classification_report_fn=sklearn["classification_report"],
            confusion_matrix_fn=sklearn["confusion_matrix"],
            f1_score_fn=sklearn["f1_score"],
        )
        threshold_policies[policy] = {
            "lowThreshold": low_threshold,
            "highThreshold": high_threshold,
            "calibrationMetrics": calibration_metrics,
            "testMetrics": test_metrics,
        }

    return {
        "model": model,
        "metrics": {
            "scoreMetrics": ordinal_error_metrics(y_test_ranks, test_scores),
            "roundedSixMetrics": rounded_six_metrics,
            "roundedThreeMetrics": rounded_three_metrics,
            "thresholdPolicies": threshold_policies,
            "topFeatureImportances": top_feature_importances(feature_names, list(model.get_feature_importance()), limit=30)
            if args.model in {"catboost", "catboost_classifier"}
            else top_feature_importances(feature_names, list(model.feature_importances_), limit=30)
            if hasattr(model, "feature_importances_")
            else [],
            "trainClassCounts": dict(sorted(Counter(row.get("niveau") for row in train_rows).items())),
            "calibrationClassCounts": dict(sorted(Counter(row.get("niveau") for row in calibration_rows).items())),
            "testClassCounts": dict(sorted(Counter(row.get("niveau") for row in test_rows).items())),
        },
    }


def load_catboost_regressor() -> Any:
    try:
        from catboost import CatBoostRegressor  # type: ignore
    except ImportError as exc:  # pragma: no cover
        raise SystemExit("catboost is required for `--model catboost`. Install it with `python -m pip install catboost`.") from exc
    return CatBoostRegressor


def load_catboost_classifier() -> Any:
    try:
        from catboost import CatBoostClassifier  # type: ignore
    except ImportError as exc:  # pragma: no cover
        raise SystemExit("catboost is required for `--model catboost_classifier`. Install it with `python -m pip install catboost`.") from exc
    return CatBoostClassifier


def main() -> None:
    parser = argparse.ArgumentParser(description="Train an ordinal débit model on the six ordered water levels")
    parser.add_argument("--features-path", default=DEFAULT_FEATURES_PATH)
    parser.add_argument("--output-dir", default=DEFAULT_OUTPUT_DIR)
    parser.add_argument(
        "--model",
        choices=["random_forest", "extra_trees", "hist_gradient_boosting", "catboost", "catboost_classifier"],
        default="extra_trees",
    )
    parser.add_argument("--split-mode", choices=["temporal", "cold_canyon"], default="temporal")
    parser.add_argument("--calibration-fraction", type=float, default=0.10)
    parser.add_argument("--test-fraction", type=float, default=0.20)
    parser.add_argument("--n-estimators", type=int, default=300)
    parser.add_argument("--learning-rate", type=float, default=0.05)
    parser.add_argument("--l2-leaf-reg", type=float, default=3.0)
    parser.add_argument("--max-depth", type=int, default=12)
    parser.add_argument("--max-leaf-nodes", type=int, default=31)
    parser.add_argument("--min-samples-leaf", type=int, default=3)
    parser.add_argument("--canyon-history-dropout-rate", type=float, default=0.0)
    parser.add_argument("--random-seed", type=int, default=42)
    parser.add_argument("--n-jobs", type=int, default=-1)
    parser.add_argument("--ignore-sample-weights", action="store_true")
    parser.add_argument("--no-class-balanced-weights", action="store_true")
    parser.add_argument("--use-soft-target-rank", action="store_true", help="Train on softTargetRank when present")
    parser.add_argument("--keep-uninformative-features", action="store_true")
    parser.add_argument("--keep-cold-canyon-history", action="store_true")
    args = parser.parse_args()

    try:
        from sklearn.ensemble import ExtraTreesRegressor, HistGradientBoostingRegressor, RandomForestRegressor
        from sklearn.metrics import accuracy_score, balanced_accuracy_score, classification_report, confusion_matrix, f1_score
    except ImportError as exc:  # pragma: no cover
        raise SystemExit("scikit-learn is required for this script. Install it with `python -m pip install scikit-learn`.") from exc

    rows = [row for row in read_jsonl(Path(args.features_path)) if target_rank(row.get("niveau")) is not None and row.get("date")]
    rows = sorted_by_date(rows)
    if len(rows) < 200:
        raise SystemExit("Not enough rows to train an ordinal débit model")

    feature_coverage = build_feature_coverage_report(rows, NUMERIC_FEATURES)
    active_feature_names = select_model_features(
        NUMERIC_FEATURES,
        feature_coverage,
        keep_uninformative_features=args.keep_uninformative_features,
    )
    train_rows, calibration_rows, test_rows, split_metadata = split_rows(
        rows,
        split_mode=args.split_mode,
        calibration_fraction=args.calibration_fraction,
        test_fraction=args.test_fraction,
        random_seed=args.random_seed,
    )
    if args.split_mode == "cold_canyon" and not args.keep_cold_canyon_history:
        calibration_rows = neutralize_canyon_history(calibration_rows)
        test_rows = neutralize_canyon_history(test_rows)
        split_metadata = {**split_metadata, "canyonHistoryNeutralized": True}
    else:
        split_metadata = {**split_metadata, "canyonHistoryNeutralized": False}

    result = evaluate_ordinal_model(
        train_rows=train_rows,
        calibration_rows=calibration_rows,
        test_rows=test_rows,
        feature_names=active_feature_names,
        args=args,
        sklearn={
            "RandomForestRegressor": RandomForestRegressor,
            "ExtraTreesRegressor": ExtraTreesRegressor,
            "HistGradientBoostingRegressor": HistGradientBoostingRegressor,
            "CatBoostRegressor": load_catboost_regressor() if args.model == "catboost" else None,
            "CatBoostClassifier": load_catboost_classifier() if args.model == "catboost_classifier" else None,
            "accuracy_score": accuracy_score,
            "balanced_accuracy_score": balanced_accuracy_score,
            "classification_report": classification_report,
            "confusion_matrix": confusion_matrix,
            "f1_score": f1_score,
        },
    )
    metrics = result["metrics"]
    output_dir = Path(args.output_dir)
    write_json(
        output_dir / "metrics.json",
        {
            "schemaVersion": 1,
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "model": "random_forest_ordinal_regressor",
            "estimator": args.model,
            "targetMode": "six_ordinal",
            "splitMode": args.split_mode,
            "split": split_metadata,
            "featureCount": len(active_feature_names),
            "features": active_feature_names,
            "droppedFeatureCount": len(NUMERIC_FEATURES) - len(active_feature_names),
            "featureCoverage": feature_coverage,
            "canyonHistoryDropoutRate": args.canyon_history_dropout_rate,
            "usesSampleWeights": not args.ignore_sample_weights,
            "usesClassBalancedWeights": not args.no_class_balanced_weights,
            "usesSoftTargetRank": args.use_soft_target_rank,
            **metrics,
        },
    )


if __name__ == "__main__":
    main()
