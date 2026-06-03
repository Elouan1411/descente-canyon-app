from __future__ import annotations

import argparse
import json
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from build_training_features import (
    comment_signal_flags,
    counter_total,
    empty_bucket_counter,
    new_signal_history,
    normalized_probabilities,
    signal_ratio,
    smoothed_probabilities,
    target_bucket_for_level,
)
from pipeline_lib import compute_debit_derived_model_features, write_json, write_jsonl


DEFAULT_FEATURES_PATH = "build/debit-pipeline/training-features-through-2026-05-28-reviewed/training_features.jsonl"
DEFAULT_OUTPUT_DIR = "build/debit-pipeline/training-features-through-2026-05-28-reviewed-causal-history"


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            stripped = line.strip()
            if stripped:
                rows.append(json.loads(stripped))
    return rows


def group_time_key(row: dict[str, Any]) -> str:
    return str(row.get("assumedObservationTimeLocal") or row.get("date") or row.get("observationId") or "")


def sorted_rows(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return sorted(rows, key=lambda row: (group_time_key(row), row.get("observationId") or ""))


def reset_derived_history_fields(row: dict[str, Any]) -> None:
    for key in (
        "canyonHistoryConfidence",
        "massifHistoryConfidence",
        "regionHistoryConfidence",
        "canyonHighPriorLift",
        "canyonLowPriorLift",
        "massifHighPriorLift",
        "regionHighPriorLift",
        "canyonPriorEntropy",
        "highLowPriorSpread",
    ):
        row.pop(key, None)


def rebuild_rows(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    global_class_counts = empty_bucket_counter()
    region_class_counts: dict[str, dict[str, int]] = defaultdict(empty_bucket_counter)
    massif_class_counts: dict[str, dict[str, int]] = defaultdict(empty_bucket_counter)
    canyon_class_counts: dict[int, dict[str, int]] = defaultdict(empty_bucket_counter)

    global_signal_history = new_signal_history()
    region_signal_history: dict[str, dict[str, int]] = defaultdict(new_signal_history)
    massif_signal_history: dict[str, dict[str, int]] = defaultdict(new_signal_history)
    canyon_signal_history: dict[int, dict[str, int]] = defaultdict(new_signal_history)

    output_rows: list[dict[str, Any]] = []
    ordered = sorted_rows(rows)
    index = 0
    while index < len(ordered):
        current_key = group_time_key(ordered[index])
        group_rows: list[dict[str, Any]] = []
        while index < len(ordered) and group_time_key(ordered[index]) == current_key:
            group_rows.append(ordered[index])
            index += 1

        processed_group: list[tuple[dict[str, Any], int, str, str, str, dict[str, bool]]] = []
        for source_row in group_rows:
            row = dict(source_row)
            canyon_id = int(row["canyonId"])
            region_key = row.get("region") or "__UNKNOWN_REGION__"
            massif_key = row.get("massif") or "__UNKNOWN_MASSIF__"
            target_bucket = target_bucket_for_level(row.get("niveau"))
            signal_flags = comment_signal_flags(row.get("commentText") or row.get("comment"))

            global_prior = normalized_probabilities(global_class_counts)
            region_prior = smoothed_probabilities(region_class_counts[region_key], global_prior, strength=30.0)
            massif_prior = smoothed_probabilities(massif_class_counts[massif_key], region_prior, strength=20.0)
            canyon_prior = smoothed_probabilities(canyon_class_counts[canyon_id], massif_prior, strength=10.0)

            canyon_history = canyon_signal_history[canyon_id]
            massif_history = massif_signal_history[massif_key]
            region_history = region_signal_history[region_key]

            row.update(
                {
                    "globalPastObsCount": counter_total(global_class_counts),
                    "regionPastObsCount": counter_total(region_class_counts[region_key]),
                    "massifPastObsCount": counter_total(massif_class_counts[massif_key]),
                    "canyonPastObsCount": counter_total(canyon_class_counts[canyon_id]),
                    "globalPriorLow": round(global_prior["LOW"], 6),
                    "globalPriorMedium": round(global_prior["MEDIUM"], 6),
                    "globalPriorHigh": round(global_prior["HIGH"], 6),
                    "regionPriorLow": round(region_prior["LOW"], 6),
                    "regionPriorMedium": round(region_prior["MEDIUM"], 6),
                    "regionPriorHigh": round(region_prior["HIGH"], 6),
                    "massifPriorLow": round(massif_prior["LOW"], 6),
                    "massifPriorMedium": round(massif_prior["MEDIUM"], 6),
                    "massifPriorHigh": round(massif_prior["HIGH"], 6),
                    "canyonPriorLow": round(canyon_prior["LOW"], 6),
                    "canyonPriorMedium": round(canyon_prior["MEDIUM"], 6),
                    "canyonPriorHigh": round(canyon_prior["HIGH"], 6),
                    "historicalRegulatedSignalCountCanyon": canyon_history["regulatedCount"],
                    "historicalSnowmeltSignalCountCanyon": canyon_history["snowmeltCount"],
                    "historicalRegulatedSignalRatioCanyon": round(signal_ratio(canyon_history, "regulatedCount"), 6),
                    "historicalSnowmeltSignalRatioCanyon": round(signal_ratio(canyon_history, "snowmeltCount"), 6),
                    "historicalRegulatedSignalRatioMassif": round(signal_ratio(massif_history, "regulatedCount"), 6),
                    "historicalSnowmeltSignalRatioMassif": round(signal_ratio(massif_history, "snowmeltCount"), 6),
                    "historicalRegulatedSignalRatioRegion": round(signal_ratio(region_history, "regulatedCount"), 6),
                    "historicalSnowmeltSignalRatioRegion": round(signal_ratio(region_history, "snowmeltCount"), 6),
                    "historicallyRegulatedCanyon": canyon_history["regulatedCount"] >= 2,
                    "historicallySnowmeltCanyon": canyon_history["snowmeltCount"] >= 2,
                    "historicallyAtypicalCanyon": canyon_history["regulatedCount"] >= 2 or canyon_history["snowmeltCount"] >= 2,
                }
            )
            reset_derived_history_fields(row)
            row.update(compute_debit_derived_model_features(row))
            output_rows.append(row)
            processed_group.append((row, canyon_id, region_key, massif_key, target_bucket or "", signal_flags))

        for _, canyon_id, region_key, massif_key, target_bucket, signal_flags in processed_group:
            if target_bucket:
                global_class_counts[target_bucket] += 1
                region_class_counts[region_key][target_bucket] += 1
                massif_class_counts[massif_key][target_bucket] += 1
                canyon_class_counts[canyon_id][target_bucket] += 1

            global_signal_history["observedCount"] += 1
            region_signal_history[region_key]["observedCount"] += 1
            massif_signal_history[massif_key]["observedCount"] += 1
            canyon_signal_history[canyon_id]["observedCount"] += 1

            if signal_flags["regulated"]:
                global_signal_history["regulatedCount"] += 1
                region_signal_history[region_key]["regulatedCount"] += 1
                massif_signal_history[massif_key]["regulatedCount"] += 1
                canyon_signal_history[canyon_id]["regulatedCount"] += 1
            if signal_flags["snowmelt"]:
                global_signal_history["snowmeltCount"] += 1
                region_signal_history[region_key]["snowmeltCount"] += 1
                massif_signal_history[massif_key]["snowmeltCount"] += 1
                canyon_signal_history[canyon_id]["snowmeltCount"] += 1

    return output_rows


def main() -> None:
    parser = argparse.ArgumentParser(description="Rebuild causal history/prior features on an existing feature dataset")
    parser.add_argument("--features-path", default=DEFAULT_FEATURES_PATH)
    parser.add_argument("--output-dir", default=DEFAULT_OUTPUT_DIR)
    args = parser.parse_args()

    rows = read_jsonl(Path(args.features_path))
    rebuilt = rebuild_rows(rows)
    output_dir = Path(args.output_dir)
    write_jsonl(output_dir / "training_features.jsonl", rebuilt)
    write_json(
        output_dir / "metadata.json",
        {
            "schemaVersion": 1,
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "sourceFeaturesPath": args.features_path,
            "rowCount": len(rebuilt),
            "note": "Recomputed causal global/region/massif/canyon history priors and derived history features; preserved existing weather/static features.",
            "files": {"trainingFeatures": "training_features.jsonl"},
        },
    )
    print(f"Wrote {output_dir / 'training_features.jsonl'} ({len(rebuilt)} rows)")


if __name__ == "__main__":
    main()
