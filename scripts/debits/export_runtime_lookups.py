from __future__ import annotations

import argparse
from collections import defaultdict, deque
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any

from build_training_features import (
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
from pipeline_lib import write_json

TARGET_MODE = "three"
UNKNOWN_REGION_KEY = "__UNKNOWN_REGION__"
UNKNOWN_MASSIF_KEY = "__UNKNOWN_MASSIF__"
REGION_SMOOTHING_STRENGTH = 30.0
MASSIF_SMOOTHING_STRENGTH = 20.0
CANYON_SMOOTHING_STRENGTH = 10.0
RANK_TO_LEVEL = ["SEC", "FILET", "CORRECT", "GROS", "TRES_GROS", "CRUE"]
LEVEL_TO_RANK = {level: float(index) for index, level in enumerate(RANK_TO_LEVEL)}
WINDOW_DAYS = (30, 90, 365)


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


def season_key(month: int) -> str:
    if month in {12, 1, 2}:
        return "winter"
    if month in {3, 4, 5}:
        return "spring"
    if month in {6, 7, 8}:
        return "summer"
    return "autumn"


def empty_rank_aggregate() -> dict[str, Any]:
    return {"classCounts": empty_bucket_counter(), "rankSum": 0.0, "rankCount": 0}


def increment_rank_aggregate(aggregate: dict[str, Any], level: str | None) -> None:
    bucket = target_bucket_for_level(level)
    rank = LEVEL_TO_RANK.get(level or "")
    if bucket is not None:
        aggregate["classCounts"][bucket] += 1
    if rank is not None:
        aggregate["rankSum"] += rank
        aggregate["rankCount"] += 1


def mean_rank(aggregate: dict[str, Any]) -> float | None:
    count = int(aggregate.get("rankCount") or 0)
    if count <= 0:
        return None
    return round(float(aggregate.get("rankSum") or 0.0) / count, 6)


def encoded(prefix: str, key: str, feature_name: str) -> str:
    return f"{prefix}.{key}.{feature_name}"


def temporal_prior_values(
    *,
    feature_prefix: str,
    aggregate: dict[str, Any],
    base_priors: dict[str, float],
    strength: float,
    include_mean_rank: bool,
) -> dict[str, Any]:
    priors = smoothed_probabilities(aggregate["classCounts"], base_priors, strength=strength)
    values: dict[str, Any] = {
        f"{feature_prefix}PastObsCount": counter_total(aggregate["classCounts"]),
        **prefixed_priors(f"{feature_prefix}Prior", priors),
    }
    if include_mean_rank:
        values[f"{feature_prefix}MeanRank"] = mean_rank(aggregate)
    return values


def row_date(row: dict[str, Any]) -> date | None:
    raw = row.get("date")
    return date.fromisoformat(str(raw)) if raw else None


def recent_values(prefix: str, rows: list[tuple[date, str, float]], reference_date: date) -> dict[str, Any]:
    values: dict[str, Any] = {}
    for window in WINDOW_DAYS:
        cutoff = reference_date.toordinal() - window
        selected = [(day, bucket, rank) for day, bucket, rank in rows if day.toordinal() >= cutoff]
        count = len(selected)
        high_count = sum(1 for _, bucket, _ in selected if bucket == "HIGH")
        values[f"{prefix}Recent{window}dObsCount"] = float(count)
        values[f"{prefix}Recent{window}dPriorHigh"] = round(high_count / count, 6) if count else 0.0
        if prefix == "canyon" and window == 365:
            values[f"{prefix}Recent{window}dMeanRank"] = round(sum(rank for _, _, rank in selected) / count, 6) if count else None
    return values


def add_temporal_runtime_snapshots(
    *,
    rows: list[dict[str, Any]],
    global_snapshot: dict[str, Any],
    region_snapshots: dict[str, dict[str, Any]],
    massif_snapshots: dict[str, dict[str, Any]],
    canyon_snapshots: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    global_month: dict[int, dict[str, Any]] = defaultdict(empty_rank_aggregate)
    global_season: dict[str, dict[str, Any]] = defaultdict(empty_rank_aggregate)
    region_month: dict[tuple[str, int], dict[str, Any]] = defaultdict(empty_rank_aggregate)
    region_season: dict[tuple[str, str], dict[str, Any]] = defaultdict(empty_rank_aggregate)
    massif_month: dict[tuple[str, int], dict[str, Any]] = defaultdict(empty_rank_aggregate)
    massif_season: dict[tuple[str, str], dict[str, Any]] = defaultdict(empty_rank_aggregate)
    canyon_month: dict[tuple[int, int], dict[str, Any]] = defaultdict(empty_rank_aggregate)
    canyon_season: dict[tuple[int, str], dict[str, Any]] = defaultdict(empty_rank_aggregate)
    recent_region: dict[str, list[tuple[date, str, float]]] = defaultdict(list)
    recent_massif: dict[str, list[tuple[date, str, float]]] = defaultdict(list)
    recent_canyon: dict[int, list[tuple[date, str, float]]] = defaultdict(list)
    last_canyon: dict[int, tuple[date, float]] = {}
    reference_date: date | None = None

    for row in rows:
        canyon_raw = row.get("canyonId")
        day = row_date(row)
        level = row.get("niveau")
        rank = LEVEL_TO_RANK.get(level or "")
        bucket = target_bucket_for_level(level)
        if canyon_raw is None or day is None or rank is None or bucket is None:
            continue
        reference_date = day if reference_date is None or day > reference_date else reference_date
        canyon_id = int(canyon_raw)
        region = row.get("region") or UNKNOWN_REGION_KEY
        massif = row.get("massif") or UNKNOWN_MASSIF_KEY
        month = int(float(row.get("month") or day.month))
        season = season_key(month)
        for aggregate in (
            global_month[month], global_season[season],
            region_month[(region, month)], region_season[(region, season)],
            massif_month[(massif, month)], massif_season[(massif, season)],
            canyon_month[(canyon_id, month)], canyon_season[(canyon_id, season)],
        ):
            increment_rank_aggregate(aggregate, level)
        recent_region[region].append((day, bucket, rank))
        recent_massif[massif].append((day, bucket, rank))
        recent_canyon[canyon_id].append((day, bucket, rank))
        if canyon_id not in last_canyon or day > last_canyon[canyon_id][0]:
            last_canyon[canyon_id] = (day, rank)

    if reference_date is None:
        return {"temporalReferenceDate": None}

    global_priors_by_month = {month: normalized_probabilities(global_month[month]["classCounts"]) for month in range(1, 13)}
    global_priors_by_season = {season: normalized_probabilities(global_season[season]["classCounts"]) for season in ("winter", "spring", "summer", "autumn")}

    for month, priors in global_priors_by_month.items():
        for name, value in prefixed_priors("globalMonthPrior", priors).items():
            global_snapshot[encoded("month", str(month), name)] = value
    for season, priors in global_priors_by_season.items():
        for name, value in prefixed_priors("globalSeasonPrior", priors).items():
            global_snapshot[encoded("season", season, name)] = value

    for region_key, snapshot in region_snapshots.items():
        for month in range(1, 13):
            values = temporal_prior_values(
                feature_prefix="regionMonth",
                aggregate=region_month[(region_key, month)],
                base_priors=global_priors_by_month[month],
                strength=20.0,
                include_mean_rank=False,
            )
            for name, value in values.items():
                snapshot[encoded("month", str(month), name)] = value
        for season in ("winter", "spring", "summer", "autumn"):
            values = temporal_prior_values(
                feature_prefix="regionSeason",
                aggregate=region_season[(region_key, season)],
                base_priors=global_priors_by_season[season],
                strength=20.0,
                include_mean_rank=False,
            )
            for name, value in values.items():
                snapshot[encoded("season", season, name)] = value
        snapshot.update(recent_values("region", recent_region.get(region_key, []), reference_date))

    for massif_key, snapshot in massif_snapshots.items():
        region_key = next((row.get("regionKey") for row in canyon_snapshots.values() if row.get("massifKey") == massif_key), UNKNOWN_REGION_KEY)
        for month in range(1, 13):
            region_base = smoothed_probabilities(region_month[(region_key, month)]["classCounts"], global_priors_by_month[month], strength=20.0)
            values = temporal_prior_values(
                feature_prefix="massifMonth",
                aggregate=massif_month[(massif_key, month)],
                base_priors=region_base,
                strength=15.0,
                include_mean_rank=False,
            )
            for name, value in values.items():
                snapshot[encoded("month", str(month), name)] = value
        for season in ("winter", "spring", "summer", "autumn"):
            region_base = smoothed_probabilities(region_season[(region_key, season)]["classCounts"], global_priors_by_season[season], strength=20.0)
            values = temporal_prior_values(
                feature_prefix="massifSeason",
                aggregate=massif_season[(massif_key, season)],
                base_priors=region_base,
                strength=15.0,
                include_mean_rank=False,
            )
            for name, value in values.items():
                snapshot[encoded("season", season, name)] = value
        snapshot.update(recent_values("massif", recent_massif.get(massif_key, []), reference_date))

    for canyon_key, snapshot in canyon_snapshots.items():
        canyon_id = int(canyon_key)
        region_key = snapshot.get("regionKey") or UNKNOWN_REGION_KEY
        massif_key = snapshot.get("massifKey") or UNKNOWN_MASSIF_KEY
        for month in range(1, 13):
            region_base = smoothed_probabilities(region_month[(region_key, month)]["classCounts"], global_priors_by_month[month], strength=20.0)
            massif_base = smoothed_probabilities(massif_month[(massif_key, month)]["classCounts"], region_base, strength=15.0)
            values = temporal_prior_values(
                feature_prefix="canyonMonth",
                aggregate=canyon_month[(canyon_id, month)],
                base_priors=massif_base,
                strength=8.0,
                include_mean_rank=True,
            )
            for name, value in values.items():
                snapshot[encoded("month", str(month), name)] = value
        for season in ("winter", "spring", "summer", "autumn"):
            region_base = smoothed_probabilities(region_season[(region_key, season)]["classCounts"], global_priors_by_season[season], strength=20.0)
            massif_base = smoothed_probabilities(massif_season[(massif_key, season)]["classCounts"], region_base, strength=15.0)
            values = temporal_prior_values(
                feature_prefix="canyonSeason",
                aggregate=canyon_season[(canyon_id, season)],
                base_priors=massif_base,
                strength=8.0,
                include_mean_rank=True,
            )
            for name, value in values.items():
                snapshot[encoded("season", season, name)] = value
        snapshot.update(recent_values("canyon", recent_canyon.get(canyon_id, []), reference_date))
        last = last_canyon.get(canyon_id)
        if last is not None:
            snapshot["canyonLastObservationEpochDay"] = float(last[0].toordinal())
            snapshot["canyonLastObservedRank"] = last[1]

    return {"temporalReferenceDate": reference_date.isoformat()}


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

    temporal_metadata = add_temporal_runtime_snapshots(
        rows=rows,
        global_snapshot=global_snapshot,
        region_snapshots=region_snapshots,
        massif_snapshots=massif_snapshots,
        canyon_snapshots=canyon_snapshots,
    )

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
        **temporal_metadata,
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
