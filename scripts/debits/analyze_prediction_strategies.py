from __future__ import annotations

import argparse
import json
import math
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from pipeline_lib import write_json


DEFAULT_OUTPUT_DIR = "build/debit-pipeline/final-model-strategy-analysis"
THREE_LABELS = ["LOW", "MEDIUM", "HIGH"]
SIX_LABELS = ["SEC", "FILET", "CORRECT", "GROS", "TRES_GROS", "CRUE"]
LEVEL_TO_RANK = {level: index for index, level in enumerate(SIX_LABELS)}
DEFAULT_PREDICTION_PATHS = {
    "final": "build/debit-pipeline/post-cutoff-final-temporal-modele-statistique-evaluation-descente/predictions.jsonl",
    "era5_land": "build/debit-pipeline/post-cutoff-era5-land-grid-v2-calibrated-evaluation-descente/predictions.jsonl",
    "base_text_abbrev": "build/debit-pipeline/post-cutoff-base-text-abbrev-adjusted-evaluation-descente/predictions.jsonl",
}


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    with path.open("r", encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def by_observation_id(rows: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    return {str(row["observationId"]): row for row in rows if row.get("observationId")}


def three_from_score(score: float, low_threshold: float = 1.85, high_threshold: float = 2.35) -> str:
    if score >= high_threshold:
        return "HIGH"
    if score < low_threshold:
        return "LOW"
    return "MEDIUM"


def six_from_score(score: float, cutpoints: list[float] | None = None) -> str:
    cuts = cutpoints or [0.7, 1.5, 2.6, 3.25, 3.75]
    index = 0
    while index < len(cuts) and score >= cuts[index]:
        index += 1
    return SIX_LABELS[index]


def metrics(rows: list[dict[str, Any]], predictions: dict[str, tuple[str, float]]) -> dict[str, Any]:
    matrix = {actual: {pred: 0 for pred in THREE_LABELS} for actual in THREE_LABELS}
    ordinal_errors: list[float] = []
    for row in rows:
        pred_three, pred_score = predictions[str(row["observationId"])]
        matrix[row["actualThree"]][pred_three] += 1
        ordinal_errors.append(pred_score - float(row["actualRank"]))
    total = len(rows)
    correct = sum(matrix[label][label] for label in THREE_LABELS)
    per_label: dict[str, Any] = {}
    recalls: list[float] = []
    f1s: list[float] = []
    for label in THREE_LABELS:
        tp = matrix[label][label]
        support = sum(matrix[label].values())
        predicted = sum(matrix[truth][label] for truth in THREE_LABELS)
        precision = tp / predicted if predicted else 0.0
        recall = tp / support if support else 0.0
        f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
        per_label[label] = {"precision": precision, "recall": recall, "f1": f1, "support": support, "predicted": predicted}
        recalls.append(recall)
        f1s.append(f1)
    abs_errors = [abs(value) for value in ordinal_errors]
    return {
        "rowCount": total,
        "accuracy": correct / total if total else 0.0,
        "balancedAccuracy": sum(recalls) / len(recalls) if recalls else 0.0,
        "macroF1": sum(f1s) / len(f1s) if f1s else 0.0,
        "maeRank": sum(abs_errors) / len(abs_errors) if abs_errors else None,
        "rmseRank": math.sqrt(sum(value * value for value in ordinal_errors) / len(ordinal_errors)) if ordinal_errors else None,
        "severeOrdinalErrorFraction": sum(1 for value in abs_errors if value >= 2.0) / len(abs_errors) if abs_errors else None,
        "highPrecision": per_label["HIGH"]["precision"],
        "highRecall": per_label["HIGH"]["recall"],
        "highF1": per_label["HIGH"]["f1"],
        "predictedHighCount": per_label["HIGH"]["predicted"],
        "confusionMatrix": matrix,
    }


def current_predictions(rows: list[dict[str, Any]], low_threshold: float = 1.85, high_threshold: float = 2.35) -> dict[str, tuple[str, float]]:
    return {
        str(row["observationId"]): (three_from_score(float(row["predictedOrdinalScore"]), low_threshold, high_threshold), float(row["predictedOrdinalScore"]))
        for row in rows
    }


def ensemble_predictions(rows: list[dict[str, Any]], model_rows: dict[str, dict[str, dict[str, Any]]], weights: dict[str, float]) -> dict[str, tuple[str, float]]:
    result: dict[str, tuple[str, float]] = {}
    total_weight = sum(weights.values())
    for row in rows:
        observation_id = str(row["observationId"])
        score = 0.0
        probabilities = {label: 0.0 for label in THREE_LABELS}
        for model_name, weight in weights.items():
            candidate = model_rows[model_name][observation_id]
            score += float(candidate["predictedOrdinalScore"]) * weight
            probabilities["LOW"] += float(candidate["probabilityLow"]) * weight
            probabilities["MEDIUM"] += float(candidate["probabilityMedium"]) * weight
            probabilities["HIGH"] += float(candidate["probabilityHigh"]) * weight
        score /= total_weight
        probabilities = {key: value / total_weight for key, value in probabilities.items()}
        # Keep the final model risk-threshold policy while averaging the ordinal score.
        result[observation_id] = (three_from_score(score), score)
    return result


def segment_threshold_predictions(rows: list[dict[str, Any]], segment_key: str) -> tuple[dict[str, tuple[str, float]], dict[str, Any]]:
    grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        if segment_key == "season":
            month = int(str(row["date"])[5:7])
            segment = "winter" if month in {12, 1, 2} else "spring" if month in {3, 4, 5} else "summer" if month in {6, 7, 8} else "autumn"
        elif segment_key == "month":
            segment = str(row["date"])[:7]
        else:
            segment = str(row.get(segment_key) or "UNKNOWN")
        grouped[segment].append(row)
    thresholds: dict[str, dict[str, float]] = {}
    predictions: dict[str, tuple[str, float]] = {}
    for segment, segment_rows in grouped.items():
        best_threshold = 2.35
        best_score: tuple[float, ...] | None = None
        for threshold_step in range(40, 71):
            high_threshold = threshold_step / 20.0
            candidate = current_predictions(segment_rows, high_threshold=high_threshold)
            m = metrics(segment_rows, candidate)
            score = (m["highF1"], m["balancedAccuracy"], m["accuracy"], -abs(high_threshold - 2.35))
            if best_score is None or score > best_score:
                best_score = score
                best_threshold = high_threshold
        thresholds[segment] = {"lowThreshold": 1.85, "highThreshold": best_threshold, "rowCount": len(segment_rows)}
        predictions.update(current_predictions(segment_rows, high_threshold=best_threshold))
    return predictions, thresholds


def uncertainty_report(rows: list[dict[str, Any]]) -> dict[str, Any]:
    enriched: list[dict[str, Any]] = []
    for row in rows:
        probabilities = row.get("probabilitiesByLabel") or {}
        score = float(row["predictedOrdinalScore"])
        variance = 0.0
        for level, rank in LEVEL_TO_RANK.items():
            probability = float(probabilities.get(level, 0.0))
            variance += probability * ((rank - score) ** 2)
        std = math.sqrt(max(variance, 0.0))
        abs_error = abs(float(row["ordinalError"]))
        enriched.append({"std": std, "absError": abs_error, "isCorrectThree": row["actualThree"] == row["predictedThree"]})
    thresholds = [0.5, 0.75, 1.0, 1.25, 1.5]
    buckets = []
    for threshold in thresholds:
        selected = [row for row in enriched if row["std"] >= threshold]
        if selected:
            buckets.append(
                {
                    "stdAtLeast": threshold,
                    "count": len(selected),
                    "fraction": len(selected) / len(enriched),
                    "meanAbsError": sum(row["absError"] for row in selected) / len(selected),
                    "threeClassErrorRate": sum(1 for row in selected if not row["isCorrectThree"]) / len(selected),
                }
            )
    return {"buckets": buckets}


def probability_calibration(rows: list[dict[str, Any]]) -> dict[str, Any]:
    bins = [[] for _ in range(10)]
    brier = 0.0
    for row in rows:
        p = float(row["probabilityHigh"])
        actual = 1.0 if row["actualThree"] == "HIGH" else 0.0
        brier += (p - actual) ** 2
        bins[min(9, max(0, int(p * 10)))].append((p, actual))
    payload = []
    ece = 0.0
    for index, values in enumerate(bins):
        if not values:
            payload.append({"binStart": index / 10, "binEnd": (index + 1) / 10, "count": 0})
            continue
        confidence = sum(p for p, _ in values) / len(values)
        accuracy = sum(actual for _, actual in values) / len(values)
        ece += len(values) / len(rows) * abs(confidence - accuracy)
        payload.append({"binStart": index / 10, "binEnd": (index + 1) / 10, "count": len(values), "meanPredicted": confidence, "actualFraction": accuracy})
    return {"brierHigh": brier / len(rows), "eceHigh": ece, "bins": payload}


def crue_report(rows: list[dict[str, Any]]) -> dict[str, Any]:
    crue = [row for row in rows if row["actualLevel"] == "CRUE"]
    return {
        "crueCount": len(crue),
        "notPredictedHighCount": sum(1 for row in crue if row["predictedThree"] != "HIGH"),
        "severeErrorCount": sum(1 for row in crue if abs(float(row["ordinalError"])) >= 2.0),
        "worst": sorted(crue, key=lambda row: float(row["ordinalError"]))[:20],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Analyze additional decision strategies on prediction JSONL files")
    parser.add_argument("--output-dir", default=DEFAULT_OUTPUT_DIR)
    args = parser.parse_args()

    loaded = {name: read_jsonl(Path(path)) for name, path in DEFAULT_PREDICTION_PATHS.items() if Path(path).exists()}
    model_rows = {name: by_observation_id(rows) for name, rows in loaded.items()}
    final_rows = loaded["final"]
    common_ids = set(model_rows["final"])
    for rows_by_id in model_rows.values():
        common_ids &= set(rows_by_id)
    common_rows = [row for row in final_rows if str(row["observationId"]) in common_ids]

    reports: dict[str, Any] = {
        "schemaVersion": 1,
        "generatedAt": datetime.now().isoformat(),
        "rowCounts": {name: len(rows) for name, rows in loaded.items()},
        "commonRowCount": len(common_rows),
    }
    strategies: dict[str, Any] = {}
    strategies["final_default"] = metrics(final_rows, current_predictions(final_rows))
    for key in ("season", "month", "country"):
        preds, thresholds = segment_threshold_predictions(final_rows, key)
        strategies[f"segment_threshold_{key}"] = {"metrics": metrics(final_rows, preds), "thresholds": thresholds}
    if {"final", "era5_land"}.issubset(model_rows):
        for final_weight in [0.25, 0.5, 0.75]:
            weights = {"final": final_weight, "era5_land": 1.0 - final_weight}
            strategies[f"ensemble_final_era5_{final_weight:.2f}"] = metrics(common_rows, ensemble_predictions(common_rows, model_rows, weights))
    if {"final", "base_text_abbrev"}.issubset(model_rows):
        for final_weight in [0.25, 0.5, 0.75]:
            weights = {"final": final_weight, "base_text_abbrev": 1.0 - final_weight}
            strategies[f"ensemble_final_text_{final_weight:.2f}"] = metrics(common_rows, ensemble_predictions(common_rows, model_rows, weights))
    reports["strategies"] = strategies
    reports["uncertainty"] = uncertainty_report(final_rows)
    reports["probabilityCalibration"] = probability_calibration(final_rows)
    reports["crue"] = crue_report(final_rows)

    output_dir = Path(args.output_dir)
    write_json(output_dir / "strategy_analysis.json", reports)

    lines = ["# Additional Prediction Strategy Analysis", ""]
    lines.append("| Strategy | Accuracy | Balanced Acc. | MAE | Severe | HIGH Precision | HIGH Recall | HIGH F1 | HIGH Pred |")
    lines.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
    flat_rows = []
    for name, payload in strategies.items():
        m = payload.get("metrics", payload) if isinstance(payload, dict) else payload
        flat_rows.append((name, m))
    for name, m in sorted(flat_rows, key=lambda item: item[1]["highF1"], reverse=True):
        lines.append(
            f"| {name} | {m['accuracy']:.4f} | {m['balancedAccuracy']:.4f} | {m['maeRank']:.4f} | {m['severeOrdinalErrorFraction']:.4f} | "
            f"{m['highPrecision']:.4f} | {m['highRecall']:.4f} | {m['highF1']:.4f} | {m['predictedHighCount']} |"
        )
    lines.append("")
    lines.append(f"HIGH Brier: `{reports['probabilityCalibration']['brierHigh']:.4f}`")
    lines.append(f"HIGH ECE: `{reports['probabilityCalibration']['eceHigh']:.4f}`")
    lines.append(f"CRUE not HIGH: `{reports['crue']['notPredictedHighCount']}/{reports['crue']['crueCount']}`")
    write_path = output_dir / "strategy_analysis.md"
    write_path.parent.mkdir(parents=True, exist_ok=True)
    write_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Wrote {write_path}")


if __name__ == "__main__":
    main()
