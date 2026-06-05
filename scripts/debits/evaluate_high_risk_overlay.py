from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from pipeline_lib import write_json
from train_baseline_model import NUMERIC_FEATURES, build_feature_coverage_report, row_to_numeric_vector, select_model_features, target_three_classes


LABELS = ["LOW", "MEDIUM", "HIGH"]


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.open(encoding="utf-8") if line.strip()]


def metrics(rows: list[dict[str, Any]], predictions: list[str]) -> dict[str, Any]:
    matrix = {truth: {pred: 0 for pred in LABELS} for truth in LABELS}
    for row, pred in zip(rows, predictions):
        matrix[target_three_classes(row["niveau"])][pred] += 1
    total = len(rows)
    correct = sum(matrix[label][label] for label in LABELS)
    per_label: dict[str, Any] = {}
    recalls: list[float] = []
    f1s: list[float] = []
    for label in LABELS:
        tp = matrix[label][label]
        support = sum(matrix[label].values())
        predicted = sum(matrix[truth][label] for truth in LABELS)
        precision = tp / predicted if predicted else 0.0
        recall = tp / support if support else 0.0
        f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
        per_label[label] = {"precision": precision, "recall": recall, "f1": f1, "support": support, "predicted": predicted}
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
        "confusionMatrix": matrix,
    }


def base_predictions_from_report(rows: list[dict[str, Any]], prediction_rows: list[dict[str, Any]]) -> list[str]:
    by_id = {str(row["observationId"]): row for row in prediction_rows if row.get("observationId")}
    return [by_id[str(row["observationId"])] ["predictedThree"] for row in rows]


def evaluate_overlay(*, train_rows: list[dict[str, Any]], eval_rows: list[dict[str, Any]], base_predictions: list[str], model_name: str) -> dict[str, Any]:
    from sklearn.ensemble import ExtraTreesClassifier, HistGradientBoostingClassifier

    coverage = build_feature_coverage_report(train_rows, NUMERIC_FEATURES)
    features = select_model_features(NUMERIC_FEATURES, coverage, keep_uninformative_features=False)
    x_train = [row_to_numeric_vector(row, features) for row in train_rows]
    y_train = [1 if target_three_classes(row["niveau"]) == "HIGH" else 0 for row in train_rows]
    x_eval = [row_to_numeric_vector(row, features) for row in eval_rows]
    if model_name == "extra_trees":
        model = ExtraTreesClassifier(n_estimators=400, min_samples_leaf=3, n_jobs=-1, random_state=42, class_weight="balanced_subsample")
    elif model_name == "hgb":
        model = HistGradientBoostingClassifier(max_iter=260, learning_rate=0.04, max_leaf_nodes=31, min_samples_leaf=30, random_state=42)
    else:
        raise SystemExit(f"Unknown model: {model_name}")
    model.fit(x_train, y_train)
    probabilities = model.predict_proba(x_eval)[:, 1]
    variants: dict[str, Any] = {"base": metrics(eval_rows, base_predictions)}
    for threshold in [0.10, 0.15, 0.20, 0.25, 0.26, 0.30, 0.35, 0.40, 0.45, 0.50, 0.55]:
        predictions = ["HIGH" if probability >= threshold else base for probability, base in zip(probabilities, base_predictions)]
        variants[f"overlay_{threshold:.2f}"] = metrics(eval_rows, predictions)
    best_name = max(variants, key=lambda key: (variants[key]["highF1"], variants[key]["balancedAccuracy"], variants[key]["accuracy"]))
    return {"model": model_name, "featureCount": len(features), "bestVariant": best_name, "variants": variants}


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate a secondary high-flow risk classifier overlay")
    parser.add_argument("--train-features", default="build/debit-pipeline/stratified-holdout-strict/train_features_strict/training_features.jsonl")
    parser.add_argument("--eval-features", required=True)
    parser.add_argument("--base-predictions", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    train_rows = read_jsonl(Path(args.train_features))
    eval_rows = read_jsonl(Path(args.eval_features))
    prediction_rows = read_jsonl(Path(args.base_predictions))
    base_predictions = base_predictions_from_report(eval_rows, prediction_rows)
    report = {
        "hgb": evaluate_overlay(train_rows=train_rows, eval_rows=eval_rows, base_predictions=base_predictions, model_name="hgb"),
        "extra_trees": evaluate_overlay(train_rows=train_rows, eval_rows=eval_rows, base_predictions=base_predictions, model_name="extra_trees"),
    }
    write_json(Path(args.output), report)
    print(json.dumps({name: {"best": value["bestVariant"], "metrics": value["variants"][value["bestVariant"]]} for name, value in report.items()}, indent=2))


if __name__ == "__main__":
    main()
