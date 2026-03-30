from __future__ import annotations

import argparse
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from build_debit_training_features import (
    TARGET_BUCKETS,
    comment_signal_flags,
    counter_total,
    empty_bucket_counter,
    new_signal_history,
    normalized_probabilities,
    read_jsonl,
    signal_ratio,
    smoothed_probabilities,
    target_bucket_for_level,
)
from debit_pipeline_lib import write_json

TARGET_MODE = "three"
UNKNOWN_REGION_KEY = "__UNKNOWN_REGION__"
UNKNOWN_MASSIF_KEY = "__UNKNOWN_MASSIF__"
REGION_SMOOTHING_STRENGTH = 30.0
MASSIF_SMOOTHING_STRENGTH = 20.0
CANYON_SMOOTHING_STRENGTH = 10.0


def empty_group_aggregate() -> dict[str, Any]:
    return {
        "classCounts": empty_bucket_counter(),
        "signalHistory": new_signal_history(),
    }


def increment_signal_history(history: dict[str, int], signal_flags: dict[str, bool]) -> None:
    history["observedCount"] += 1
    if signal_flags["regulated"]:
        history["regulatedCount"] += 1
    if signal_flags["snowmelt"]:
        history["snowmeltCount"] += 1


def rounded_priors(priors: dict[str, float]) -> dict[str, float]:
    return {bucket: round(priors[bucket], 6) for bucket in TARGET_BUCKETS}


def prefixed_priors(prefix: str, priors: dict[str, float]) -> dict[str, float]:
    rounded = rounded_priors(priors)
    return {f"{prefix}{bucket.title()}": rounded[bucket] for bucket in TARGET_BUCKETS}


def serialize_group_aggregate(aggregate: dict[str, Any]) -> dict[str, Any]:
    class_counts = dict(aggregate["classCounts"])
    signal_history = dict(aggregate["signalHistory"])
    return {
        "pastObsCount": counter_total(class_counts),
        "classCounts": class_counts,
        "signalHistory": signal_history,
        "historicalRegulatedSignalRatio": round(signal_ratio(signal_history, "regulatedCount"), 6),
        "historicalSnowmeltSignalRatio": round(signal_ratio(signal_history, "snowmeltCount"), 6),
    }


def build_runtime_lookup_payload(rows: list[dict[str, Any]]) -> tuple[dict[str, Any], dict[str, Any]]:
    global_aggregate = empty_group_aggregate()
    region_aggregates: dict[str, dict[str, Any]] = {}
    massif_aggregates: dict[str, dict[str, Any]] = {}
    canyon_aggregates: dict[int, dict[str, Any]] = {}
    canyon_context_by_id: dict[int, dict[str, str]] = {}

    retained_row_count = 0
    skipped_missing_canyon_count = 0
    skipped_unknown_target_count = 0

    for row in rows:
        canyon_id_raw = row.get("canyonId")
        if canyon_id_raw is None:
            skipped_missing_canyon_count += 1
            continue

        canyon_id = int(canyon_id_raw)
        region_key = row.get("region") or UNKNOWN_REGION_KEY
        massif_key = row.get("massif") or UNKNOWN_MASSIF_KEY
        signal_flags = comment_signal_flags(row.get("commentText"))
        target_bucket = target_bucket_for_level(row.get("niveau"))

        canyon_context_by_id.setdefault(
            canyon_id,
            {
                "regionKey": region_key,
                "massifKey": massif_key,
            },
        )

        region_aggregate = region_aggregates.setdefault(region_key, empty_group_aggregate())
        massif_aggregate = massif_aggregates.setdefault(massif_key, empty_group_aggregate())
        canyon_aggregate = canyon_aggregates.setdefault(canyon_id, empty_group_aggregate())

        increment_signal_history(global_aggregate["signalHistory"], signal_flags)
        increment_signal_history(region_aggregate["signalHistory"], signal_flags)
        increment_signal_history(massif_aggregate["signalHistory"], signal_flags)
        increment_signal_history(canyon_aggregate["signalHistory"], signal_flags)

        if target_bucket is None:
            skipped_unknown_target_count += 1
            continue

        retained_row_count += 1
        global_aggregate["classCounts"][target_bucket] += 1
        region_aggregate["classCounts"][target_bucket] += 1
        massif_aggregate["classCounts"][target_bucket] += 1
        canyon_aggregate["classCounts"][target_bucket] += 1

    global_priors = normalized_probabilities(global_aggregate["classCounts"])
    global_snapshot = serialize_group_aggregate(global_aggregate)
    global_snapshot.update(prefixed_priors("globalPrior", global_priors))

    region_snapshots: dict[str, dict[str, Any]] = {}
    for region_key, aggregate in sorted(region_aggregates.items()):
        region_priors = smoothed_probabilities(
            aggregate["classCounts"],
            global_priors,
            strength=REGION_SMOOTHING_STRENGTH,
        )
        snapshot = serialize_group_aggregate(aggregate)
        snapshot.update(prefixed_priors("regionPrior", region_priors))
        region_snapshots[region_key] = snapshot

    massif_snapshots: dict[str, dict[str, Any]] = {}
    for massif_key, aggregate in sorted(massif_aggregates.items()):
        massif_snapshots[massif_key] = serialize_group_aggregate(aggregate)

    canyon_snapshots: dict[str, dict[str, Any]] = {}
    canyon_ids = sorted(set(canyon_context_by_id.keys()) | set(canyon_aggregates.keys()))
    global_past_obs_count = counter_total(global_aggregate["classCounts"])

    for canyon_id in canyon_ids:
        context = canyon_context_by_id.get(
            canyon_id,
            {"regionKey": UNKNOWN_REGION_KEY, "massifKey": UNKNOWN_MASSIF_KEY},
        )
        region_key = context["regionKey"]
        massif_key = context["massifKey"]
        canyon_aggregate = canyon_aggregates.setdefault(canyon_id, empty_group_aggregate())
        region_aggregate = region_aggregates.setdefault(region_key, empty_group_aggregate())
        massif_aggregate = massif_aggregates.setdefault(massif_key, empty_group_aggregate())

        region_priors = smoothed_probabilities(
            region_aggregate["classCounts"],
            global_priors,
            strength=REGION_SMOOTHING_STRENGTH,
        )
        massif_priors = smoothed_probabilities(
            massif_aggregate["classCounts"],
            region_priors,
            strength=MASSIF_SMOOTHING_STRENGTH,
        )
        canyon_priors = smoothed_probabilities(
            canyon_aggregate["classCounts"],
            massif_priors,
            strength=CANYON_SMOOTHING_STRENGTH,
        )

        canyon_signal_history = canyon_aggregate["signalHistory"]
        region_signal_history = region_aggregate["signalHistory"]
        massif_signal_history = massif_aggregate["signalHistory"]

        canyon_snapshots[str(canyon_id)] = {
            "canyonId": canyon_id,
            "regionKey": region_key,
            "massifKey": massif_key,
            "globalPastObsCount": global_past_obs_count,
            "regionPastObsCount": counter_total(region_aggregate["classCounts"]),
            "massifPastObsCount": counter_total(massif_aggregate["classCounts"]),
            "canyonPastObsCount": counter_total(canyon_aggregate["classCounts"]),
            **prefixed_priors("globalPrior", global_priors),
            **prefixed_priors("regionPrior", region_priors),
            **prefixed_priors("massifPrior", massif_priors),
            **prefixed_priors("canyonPrior", canyon_priors),
            "historicalRegulatedSignalCountCanyon": canyon_signal_history["regulatedCount"],
            "historicalSnowmeltSignalCountCanyon": canyon_signal_history["snowmeltCount"],
            "historicalRegulatedSignalRatioCanyon": round(signal_ratio(canyon_signal_history, "regulatedCount"), 6),
            "historicalSnowmeltSignalRatioCanyon": round(signal_ratio(canyon_signal_history, "snowmeltCount"), 6),
            "historicalRegulatedSignalRatioMassif": round(signal_ratio(massif_signal_history, "regulatedCount"), 6),
            "historicalSnowmeltSignalRatioMassif": round(signal_ratio(massif_signal_history, "snowmeltCount"), 6),
            "historicalRegulatedSignalRatioRegion": round(signal_ratio(region_signal_history, "regulatedCount"), 6),
            "historicalSnowmeltSignalRatioRegion": round(signal_ratio(region_signal_history, "snowmeltCount"), 6),
            "historicallyRegulatedCanyon": canyon_signal_history["regulatedCount"] >= 2,
            "historicallySnowmeltCanyon": canyon_signal_history["snowmeltCount"] >= 2,
            "historicallyAtypicalCanyon": (
                canyon_signal_history["regulatedCount"] >= 2
                or canyon_signal_history["snowmeltCount"] >= 2
            ),
            "classCounts": dict(canyon_aggregate["classCounts"]),
            "signalHistory": dict(canyon_signal_history),
        }

    payload = {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "targetMode": TARGET_MODE,
        "labels": list(TARGET_BUCKETS),
        "unknownKeys": {
            "region": UNKNOWN_REGION_KEY,
            "massif": UNKNOWN_MASSIF_KEY,
        },
        "smoothingStrengths": {
            "region": REGION_SMOOTHING_STRENGTH,
            "massif": MASSIF_SMOOTHING_STRENGTH,
            "canyon": CANYON_SMOOTHING_STRENGTH,
        },
        "global": global_snapshot,
        "regions": region_snapshots,
        "massifs": massif_snapshots,
        "canyons": canyon_snapshots,
    }

    metadata = {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "targetMode": TARGET_MODE,
        "inputRowCount": len(rows),
        "retainedRowCount": retained_row_count,
        "skippedMissingCanyonCount": skipped_missing_canyon_count,
        "skippedUnknownTargetCount": skipped_unknown_target_count,
        "globalPastObsCount": global_past_obs_count,
        "regionCount": len(region_snapshots),
        "massifCount": len(massif_snapshots),
        "canyonCount": len(canyon_snapshots),
    }
    return payload, metadata


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Export runtime lookup tables from generated debit training features"
    )
    parser.add_argument(
        "--features-path",
        default="build/debit-pipeline/training-features/training_features.jsonl",
    )
    parser.add_argument(
        "--output-dir",
        default="build/debit-pipeline/runtime-lookups",
    )
    args = parser.parse_args()

    rows = read_jsonl(Path(args.features_path))

    payload, metadata = build_runtime_lookup_payload(rows)
    metadata["sourceFeaturesPath"] = str(Path(args.features_path))

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    write_json(output_dir / "runtime_feature_lookups.json", payload)

    write_json(
        output_dir / "metadata.json",
        {
            **metadata,
            "files": {
                "runtimeLookups": "runtime_feature_lookups.json",
            },
        },
    )


if __name__ == "__main__":
    main()
