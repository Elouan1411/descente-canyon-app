from __future__ import annotations

import argparse
import json
import math
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import numpy as np

from evaluate_post_cutoff_app_model import (
    calibration_bins,
    classification_metrics,
    extract_probability_map,
    feature_vector_from_values,
    normalized_three_probabilities,
    ordinal_level,
    ordinal_metrics,
    ordinal_score,
    predicted_three_label,
    write_markdown_report,
)
from pipeline_lib import with_debit_derived_model_features, write_json, write_jsonl


DEFAULT_FEATURES_PATH = "build/debit-pipeline/training-features-reviewed-era5-land-grid/training_features.jsonl"
DEFAULT_MODEL_DIR = "build/debit-pipeline/mobile-ordinal-era5-land-grid-candidate"
DEFAULT_OUTPUT_DIR = "build/debit-pipeline/post-cutoff-era5-land-grid-candidate-evaluation-descente"
LEVEL_TO_THREE = {
    "SEC": "LOW",
    "FILET": "LOW",
    "CORRECT": "MEDIUM",
    "GROS": "HIGH",
    "TRES_GROS": "HIGH",
    "CRUE": "HIGH",
}
LEVEL_TO_RANK = {"SEC": 0, "FILET": 1, "CORRECT": 2, "GROS": 3, "TRES_GROS": 4, "CRUE": 5}


def read_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            stripped = line.strip()
            if stripped:
                rows.append(with_debit_derived_model_features(json.loads(stripped)))
    return rows


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate an exported mobile debit model directly on feature rows")
    parser.add_argument("--features-path", default=DEFAULT_FEATURES_PATH)
    parser.add_argument("--model-dir", default=DEFAULT_MODEL_DIR)
    parser.add_argument("--thresholds-path", help="Override thresholds JSON path instead of <model-dir>/thresholds.json")
    parser.add_argument("--output-dir", default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--cutoff-date", default="2026-03-25")
    args = parser.parse_args()

    try:
        import onnxruntime as ort  # type: ignore
    except ImportError as exc:
        raise SystemExit("onnxruntime is required") from exc

    model_dir = Path(args.model_dir)
    feature_spec = read_json(model_dir / "feature_spec.json")
    thresholds = read_json(Path(args.thresholds_path) if args.thresholds_path else model_dir / "thresholds.json")
    labels = [str(label) for label in feature_spec.get("labels", [])]
    rows = [
        row
        for row in read_jsonl(Path(args.features_path))
        if row.get("date") and row["date"] > args.cutoff_date and row.get("niveau") in LEVEL_TO_THREE
    ]
    rows.sort(key=lambda row: (row.get("date") or "", int(row.get("canyonId") or 0), row.get("observationId") or ""))

    session = ort.InferenceSession(str(model_dir / "model.onnx"), providers=["CPUExecutionProvider"])
    input_name = session.get_inputs()[0].name
    predictions: list[dict[str, Any]] = []
    for row in rows:
        vector = feature_vector_from_values(row, feature_spec)
        outputs = session.run(None, {input_name: vector})
        probabilities_by_label = extract_probability_map(outputs, labels)
        three_probabilities = normalized_three_probabilities(probabilities_by_label)
        score = ordinal_score(probabilities_by_label)
        predicted_three = predicted_three_label(probabilities=probabilities_by_label, score=score, thresholds=thresholds)
        actual_level = row["niveau"]
        actual_rank = LEVEL_TO_RANK[actual_level]
        ordinal_error = (score - actual_rank) if score is not None else None
        predictions.append(
            {
                "observationId": row.get("observationId"),
                "canyonId": row.get("canyonId"),
                "canyonName": row.get("canyonName"),
                "country": row.get("country"),
                "region": row.get("region"),
                "massif": row.get("massif"),
                "date": row.get("date"),
                "month": str(row.get("date"))[:7],
                "actualLevel": actual_level,
                "actualThree": LEVEL_TO_THREE[actual_level],
                "actualRank": actual_rank,
                "predictedThree": predicted_three,
                "predictedOrdinalScore": score,
                "predictedOrdinalLevel": ordinal_level(score),
                "ordinalError": ordinal_error,
                "absoluteOrdinalError": abs(ordinal_error) if ordinal_error is not None else None,
                "probabilityLow": three_probabilities.get("LOW", 0.0),
                "probabilityMedium": three_probabilities.get("MEDIUM", 0.0),
                "probabilityHigh": three_probabilities.get("HIGH", 0.0),
                "probabilitiesByLabel": probabilities_by_label,
                "lookupSource": "FEATURE_ROWS",
            }
        )

    if not predictions:
        raise SystemExit("No rows to evaluate")
    dates = [row["date"] for row in predictions]
    metrics = classification_metrics(predictions)
    ordinal = ordinal_metrics(predictions)
    worst_errors = sorted(
        predictions,
        key=lambda row: (row.get("absoluteOrdinalError") or 0.0, row.get("probabilityHigh") or 0.0),
        reverse=True,
    )[:20]
    report = {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "cutoffDate": args.cutoff_date,
        "source": "feature_rows",
        "scenario": "direct_feature_rows",
        "modelDir": str(model_dir),
        "featuresPath": args.features_path,
        "modelLabels": labels,
        "thresholdPolicy": thresholds.get("defaultPolicy"),
        "thresholds": thresholds.get("policies", {}).get(thresholds.get("defaultPolicy")),
        "candidateObservationCount": len(rows),
        "evaluatedObservationCount": len(predictions),
        "skippedObservationCount": 0,
        "distinctCanyonCount": len({row["canyonId"] for row in predictions}),
        "dateRange": [min(dates), max(dates)],
        "actualLevelCounts": dict(sorted(Counter(row["actualLevel"] for row in predictions).items())),
        "predictedThreeCounts": dict(sorted(Counter(row["predictedThree"] for row in predictions).items())),
        "lookupSourceCounts": {"FEATURE_ROWS": len(predictions)},
        "countryCounts": dict(sorted(Counter(row.get("country") or "UNKNOWN" for row in predictions).items())),
        "metrics": metrics,
        "ordinalMetrics": ordinal,
        "highCalibrationBins": calibration_bins(predictions),
        "segmentMetrics": {"country": {}, "lookupSource": {}, "month": {}},
        "worstErrors": worst_errors,
        "skippedReasons": {},
        "files": {
            "predictions": "predictions.jsonl",
            "reportJson": "reliability_post_cutoff_report.json",
            "reportMarkdown": "reliability_post_cutoff_report.md",
        },
    }
    output_dir = Path(args.output_dir)
    write_jsonl(output_dir / "predictions.jsonl", predictions)
    write_json(output_dir / "reliability_post_cutoff_report.json", report)
    write_markdown_report(output_dir / "reliability_post_cutoff_report.md", report, worst_errors)
    print(f"Wrote {output_dir / 'reliability_post_cutoff_report.md'}")


if __name__ == "__main__":
    main()
