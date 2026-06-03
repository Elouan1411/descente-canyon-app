from __future__ import annotations

import argparse
import json
import math
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import numpy as np

from evaluate_mobile_model_on_features import read_jsonl
from evaluate_post_cutoff_app_model import extract_probability_map, feature_vector_from_values, ordinal_score
from pipeline_lib import write_json


DEFAULT_FEATURES_PATH = "build/debit-pipeline/training-features-reviewed-era5-land-grid-v2/training_features.jsonl"
DEFAULT_MODEL_DIR = "build/debit-pipeline/mobile-ordinal-era5-land-grid-v2-candidate"
DEFAULT_OUTPUT_DIR = "build/debit-pipeline/mobile-ordinal-era5-land-grid-v2-calibration"
RANK_TO_LEVEL = ["SEC", "FILET", "CORRECT", "GROS", "TRES_GROS", "CRUE"]
LEVEL_TO_RANK = {level: index for index, level in enumerate(RANK_TO_LEVEL)}
THREE_LABELS = ["LOW", "MEDIUM", "HIGH"]


def read_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def target_three(level: str | None) -> str | None:
    if level in {"SEC", "FILET"}:
        return "LOW"
    if level == "CORRECT":
        return "MEDIUM"
    if level in {"GROS", "TRES_GROS", "CRUE"}:
        return "HIGH"
    return None


def split_temporal_rows(rows: list[dict[str, Any]], calibration_fraction: float, test_fraction: float) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
    ordered = sorted(rows, key=lambda row: (row.get("date") or "", row.get("observationId") or ""))
    total = len(ordered)
    test_count = max(1, int(total * test_fraction))
    calibration_count = max(1, int(total * calibration_fraction))
    train_count = max(0, total - test_count - calibration_count)
    return ordered[:train_count], ordered[train_count:train_count + calibration_count], ordered[train_count + calibration_count:]


def run_scores(*, rows: list[dict[str, Any]], model_dir: Path, feature_spec: dict[str, Any]) -> list[dict[str, Any]]:
    try:
        import onnxruntime as ort  # type: ignore
    except ImportError as exc:
        raise SystemExit("onnxruntime is required") from exc

    labels = [str(label) for label in feature_spec.get("labels", [])]
    session = ort.InferenceSession(str(model_dir / "model.onnx"), providers=["CPUExecutionProvider"])
    input_name = session.get_inputs()[0].name
    output: list[dict[str, Any]] = []
    for row in rows:
        vector = feature_vector_from_values(row, feature_spec)
        probabilities = extract_probability_map(session.run(None, {input_name: vector}), labels)
        score = ordinal_score(probabilities)
        if score is None:
            continue
        output.append(
            {
                "observationId": row.get("observationId"),
                "date": row.get("date"),
                "level": row.get("niveau"),
                "rank": LEVEL_TO_RANK[row["niveau"]],
                "three": target_three(row.get("niveau")),
                "score": float(score),
                "probabilities": probabilities,
            }
        )
    return output


def three_from_score(score: float, low_threshold: float, high_threshold: float) -> str:
    if score >= high_threshold:
        return "HIGH"
    if score < low_threshold:
        return "LOW"
    return "MEDIUM"


def six_from_cutpoints(score: float, cutpoints: list[float]) -> str:
    index = 0
    while index < len(cutpoints) and score >= cutpoints[index]:
        index += 1
    return RANK_TO_LEVEL[index]


def three_metrics(scored_rows: list[dict[str, Any]], low_threshold: float, high_threshold: float) -> dict[str, Any]:
    matrix = {truth: {pred: 0 for pred in THREE_LABELS} for truth in THREE_LABELS}
    for row in scored_rows:
        matrix[row["three"]][three_from_score(float(row["score"]), low_threshold, high_threshold)] += 1
    total = sum(sum(row.values()) for row in matrix.values())
    correct = sum(matrix[label][label] for label in THREE_LABELS)
    recalls: list[float] = []
    f1s: list[float] = []
    per_label: dict[str, Any] = {}
    for label in THREE_LABELS:
        tp = matrix[label][label]
        support = sum(matrix[label].values())
        predicted = sum(matrix[truth][label] for truth in THREE_LABELS)
        precision = tp / predicted if predicted else 0.0
        recall = tp / support if support else 0.0
        f1 = 2.0 * precision * recall / (precision + recall) if precision + recall else 0.0
        recalls.append(recall)
        f1s.append(f1)
        per_label[label] = {"precision": precision, "recall": recall, "f1": f1, "support": support, "predicted": predicted}
    return {
        "lowThreshold": low_threshold,
        "highThreshold": high_threshold,
        "accuracy": correct / total if total else 0.0,
        "balancedAccuracy": sum(recalls) / len(recalls) if recalls else 0.0,
        "macroF1": sum(f1s) / len(f1s) if f1s else 0.0,
        "precisionHigh": per_label["HIGH"]["precision"],
        "recallHigh": per_label["HIGH"]["recall"],
        "f1High": per_label["HIGH"]["f1"],
        "perLabel": per_label,
        "confusionMatrix": matrix,
    }


def policy_score(metrics: dict[str, Any], policy: str) -> tuple[float, ...]:
    high_f1 = float(metrics["f1High"])
    balanced = float(metrics["balancedAccuracy"])
    precision = float(metrics["precisionHigh"])
    recall = float(metrics["recallHigh"])
    high_threshold = float(metrics["highThreshold"])
    if policy == "prudent":
        return (1.0 if recall >= 0.40 else 0.0, precision, high_f1, balanced, high_threshold)
    if policy == "safety_first":
        return (1.0 if precision >= 0.25 else 0.0, recall, high_f1, balanced, -high_threshold)
    return (high_f1, balanced, precision, recall, -abs(high_threshold - 2.3))


def find_three_thresholds(scored_rows: list[dict[str, Any]], policy: str) -> dict[str, Any]:
    candidates = [round(value / 20.0, 2) for value in range(5, 101)]
    best: dict[str, Any] | None = None
    for low in candidates:
        for high in candidates:
            if low >= high:
                continue
            metrics = three_metrics(scored_rows, low, high)
            if best is None or policy_score(metrics, policy) > policy_score(best, policy):
                best = metrics
    assert best is not None
    return best


def boundary_metrics(scored_rows: list[dict[str, Any]], boundary_rank: int, threshold: float) -> dict[str, float]:
    # Boundary rank 0 means SEC vs FILET+, rank 1 means <=FILET vs CORRECT+, etc.
    tp = tn = fp = fn = 0
    for row in scored_rows:
        actual_high_side = int(row["rank"]) > boundary_rank
        predicted_high_side = float(row["score"]) >= threshold
        if actual_high_side and predicted_high_side:
            tp += 1
        elif actual_high_side and not predicted_high_side:
            fn += 1
        elif not actual_high_side and predicted_high_side:
            fp += 1
        else:
            tn += 1
    tpr = tp / (tp + fn) if tp + fn else 0.0
    tnr = tn / (tn + fp) if tn + fp else 0.0
    precision = tp / (tp + fp) if tp + fp else 0.0
    f1 = 2.0 * precision * tpr / (precision + tpr) if precision + tpr else 0.0
    return {"balancedAccuracy": (tpr + tnr) / 2.0, "precision": precision, "recall": tpr, "f1": f1}


def find_cutpoints(scored_rows: list[dict[str, Any]]) -> list[float]:
    candidates_by_index = [
        [round(value / 20.0, 2) for value in range(5, 31)],
        [round(value / 20.0, 2) for value in range(20, 46)],
        [round(value / 20.0, 2) for value in range(40, 66)],
        [round(value / 20.0, 2) for value in range(56, 86)],
        [round(value / 20.0, 2) for value in range(70, 101)],
    ]
    cutpoints = [0.5, 1.5, 2.5, 3.5, 4.5]

    def valid(values: list[float]) -> bool:
        return all(values[index] + 0.25 <= values[index + 1] for index in range(4))

    def score(values: list[float]) -> tuple[float, ...]:
        metrics = six_metrics(scored_rows, values)
        return (
            -float(metrics["maeRank"] or 99.0),
            float(metrics["accuracy"]),
            -float(metrics["severeOrdinalErrorFraction"] or 1.0),
            -sum(abs(value - (index + 0.5)) for index, value in enumerate(values)) * 0.001,
        )

    for _ in range(8):
        changed = False
        for index in range(5):
            best = list(cutpoints)
            best_score = score(best)
            for candidate in candidates_by_index[index]:
                current = list(cutpoints)
                current[index] = candidate
                if not valid(current):
                    continue
                current_score = score(current)
                if current_score > best_score:
                    best = current
                    best_score = current_score
            if best != cutpoints:
                cutpoints = best
                changed = True
        if not changed:
            break
    return [round(value, 2) for value in cutpoints]


def six_metrics(scored_rows: list[dict[str, Any]], cutpoints: list[float]) -> dict[str, Any]:
    labels = RANK_TO_LEVEL
    matrix = {truth: {pred: 0 for pred in labels} for truth in labels}
    errors: list[float] = []
    for row in scored_rows:
        prediction = six_from_cutpoints(float(row["score"]), cutpoints)
        matrix[row["level"]][prediction] += 1
        errors.append(LEVEL_TO_RANK[prediction] - int(row["rank"]))
    total = len(scored_rows)
    correct = sum(matrix[label][label] for label in labels)
    absolute = [abs(error) for error in errors]
    recalls: list[float] = []
    f1s: list[float] = []
    for label in labels:
        tp = matrix[label][label]
        support = sum(matrix[label].values())
        predicted = sum(matrix[truth][label] for truth in labels)
        precision = tp / predicted if predicted else 0.0
        recall = tp / support if support else 0.0
        f1 = 2.0 * precision * recall / (precision + recall) if precision + recall else 0.0
        recalls.append(recall)
        f1s.append(f1)
    return {
        "cutpoints": cutpoints,
        "accuracy": correct / total if total else 0.0,
        "balancedAccuracy": sum(recalls) / len(recalls) if recalls else 0.0,
        "macroF1": sum(f1s) / len(f1s) if f1s else 0.0,
        "maeRank": sum(absolute) / len(absolute) if absolute else None,
        "severeOrdinalErrorFraction": sum(1 for value in absolute if value >= 2.0) / len(absolute) if absolute else None,
        "confusionMatrix": matrix,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Calibrate three-class thresholds and six-level cutpoints for an exported mobile ordinal model")
    parser.add_argument("--features-path", default=DEFAULT_FEATURES_PATH)
    parser.add_argument("--model-dir", default=DEFAULT_MODEL_DIR)
    parser.add_argument("--output-dir", default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--calibration-fraction", type=float, default=0.10)
    parser.add_argument("--test-fraction", type=float, default=0.20)
    args = parser.parse_args()

    model_dir = Path(args.model_dir)
    output_dir = Path(args.output_dir)
    rows = [row for row in read_jsonl(Path(args.features_path)) if row.get("date") and row.get("niveau") in LEVEL_TO_RANK]
    _, calibration_rows, test_rows = split_temporal_rows(rows, args.calibration_fraction, args.test_fraction)
    feature_spec = read_json(model_dir / "feature_spec.json")
    base_thresholds = read_json(model_dir / "thresholds.json")
    calibration_scores = run_scores(rows=calibration_rows, model_dir=model_dir, feature_spec=feature_spec)
    test_scores = run_scores(rows=test_rows, model_dir=model_dir, feature_spec=feature_spec)

    policies: dict[str, Any] = {}
    threshold_policies: dict[str, Any] = {}
    for policy in ("balanced", "prudent", "safety_first"):
        calibration_metrics = find_three_thresholds(calibration_scores, policy)
        test_metrics = three_metrics(test_scores, calibration_metrics["lowThreshold"], calibration_metrics["highThreshold"])
        policies[policy] = {
            "lowThreshold": calibration_metrics["lowThreshold"],
            "highThreshold": calibration_metrics["highThreshold"],
        }
        threshold_policies[policy] = {"calibrationMetrics": calibration_metrics, "testMetrics": test_metrics}

    cutpoints = find_cutpoints(calibration_scores)
    cutpoint_report = {"calibrationMetrics": six_metrics(calibration_scores, cutpoints), "testMetrics": six_metrics(test_scores, cutpoints)}
    calibrated_thresholds = {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "targetMode": base_thresholds.get("targetMode", "six_ordinal"),
        "labels": base_thresholds.get("labels", RANK_TO_LEVEL),
        "defaultPolicy": base_thresholds.get("defaultPolicy", "balanced"),
        "policies": policies,
        "ordinalCutpoints": {
            "secFilet": cutpoints[0],
            "filetCorrect": cutpoints[1],
            "correctGros": cutpoints[2],
            "grosTresGros": cutpoints[3],
            "tresGrosCrue": cutpoints[4],
        },
    }
    report = {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "featuresPath": args.features_path,
        "modelDir": args.model_dir,
        "calibrationRowCount": len(calibration_scores),
        "testRowCount": len(test_scores),
        "thresholdPolicies": threshold_policies,
        "cutpoints": cutpoint_report,
        "files": {"thresholds": "thresholds_calibrated.json", "report": "calibration_report.json"},
    }
    write_json(output_dir / "thresholds_calibrated.json", calibrated_thresholds)
    write_json(output_dir / "calibration_report.json", report)
    print(json.dumps({"policies": policies, "ordinalCutpoints": calibrated_thresholds["ordinalCutpoints"]}, indent=2))


if __name__ == "__main__":
    main()
