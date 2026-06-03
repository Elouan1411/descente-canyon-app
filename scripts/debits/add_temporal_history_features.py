from __future__ import annotations

import argparse
import json
from collections import Counter, defaultdict, deque
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any

from build_training_features import (
    TARGET_BUCKETS,
    counter_total,
    empty_bucket_counter,
    normalized_probabilities,
    smoothed_probabilities,
    target_bucket_for_level,
)
from train_ordinal_model import LEVEL_TO_RANK
from pipeline_lib import write_json, write_jsonl


DEFAULT_FEATURES_PATH = "build/debit-pipeline/training-features-through-2026-05-28-reviewed-causal-history-weight025/training_features.jsonl"
DEFAULT_OUTPUT_DIR = "build/debit-pipeline/training-features-reviewed-causal-weight025-temporal-history"
WINDOW_DAYS = (30, 90, 365)


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            stripped = line.strip()
            if stripped:
                rows.append(json.loads(stripped))
    return rows


def row_date(row: dict[str, Any]) -> date | None:
    raw = row.get("date")
    if not raw:
        return None
    return date.fromisoformat(str(raw))


def group_time_key(row: dict[str, Any]) -> str:
    return str(row.get("assumedObservationTimeLocal") or row.get("date") or row.get("observationId") or "")


def month_key(row: dict[str, Any]) -> int:
    raw = row.get("month")
    if raw is not None:
        return int(float(raw))
    raw_date = str(row.get("date") or "2000-01-01")
    return int(raw_date.split("-")[1])


def season_key(month: int) -> str:
    if month in {12, 1, 2}:
        return "winter"
    if month in {3, 4, 5}:
        return "spring"
    if month in {6, 7, 8}:
        return "summer"
    return "autumn"


def new_bucket_rank_history() -> dict[str, Any]:
    return {"counts": empty_bucket_counter(), "rankSum": 0.0, "rankCount": 0}


def increment_bucket_rank_history(history: dict[str, Any], bucket: str | None, rank: float | None) -> None:
    if bucket:
        history["counts"][bucket] += 1
    if rank is not None:
        history["rankSum"] += rank
        history["rankCount"] += 1


def mean_rank(history: dict[str, Any]) -> float | None:
    count = int(history.get("rankCount") or 0)
    if count <= 0:
        return None
    return round(float(history.get("rankSum") or 0.0) / count, 6)


def prefixed_prior(prefix: str, probabilities: dict[str, float]) -> dict[str, float]:
    return {f"{prefix}{bucket.title()}": round(probabilities[bucket], 6) for bucket in TARGET_BUCKETS}


def purge_deque(items: deque[tuple[date, str, float]], current_date: date, window_days: int) -> None:
    cutoff = current_date.toordinal() - window_days
    while items and items[0][0].toordinal() < cutoff:
        items.popleft()


def recent_summary(items: deque[tuple[date, str, float]]) -> dict[str, float | None]:
    if not items:
        return {"count": 0.0, "priorHigh": 0.0, "meanRank": None}
    count = len(items)
    high_count = sum(1 for _, bucket, _ in items if bucket == "HIGH")
    mean = sum(rank for _, _, rank in items) / count
    return {"count": float(count), "priorHigh": round(high_count / count, 6), "meanRank": round(mean, 6)}


def entity_recent_features(prefix: str, rows_by_window: dict[int, deque[tuple[date, str, float]]], current_date: date) -> dict[str, Any]:
    features: dict[str, Any] = {}
    for window in WINDOW_DAYS:
        items = rows_by_window[window]
        purge_deque(items, current_date, window)
        summary = recent_summary(items)
        features[f"{prefix}Recent{window}dObsCount"] = summary["count"]
        features[f"{prefix}Recent{window}dPriorHigh"] = summary["priorHigh"]
        if prefix == "canyon" and window == 365:
            features[f"{prefix}Recent{window}dMeanRank"] = summary["meanRank"]
    return features


def sorted_rows(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return sorted(rows, key=lambda row: (group_time_key(row), row.get("observationId") or ""))


def add_temporal_history_features(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    global_month: dict[int, dict[str, Any]] = defaultdict(new_bucket_rank_history)
    global_season: dict[str, dict[str, Any]] = defaultdict(new_bucket_rank_history)
    region_month: dict[tuple[str, int], dict[str, Any]] = defaultdict(new_bucket_rank_history)
    region_season: dict[tuple[str, str], dict[str, Any]] = defaultdict(new_bucket_rank_history)
    massif_month: dict[tuple[str, int], dict[str, Any]] = defaultdict(new_bucket_rank_history)
    massif_season: dict[tuple[str, str], dict[str, Any]] = defaultdict(new_bucket_rank_history)
    canyon_month: dict[tuple[int, int], dict[str, Any]] = defaultdict(new_bucket_rank_history)
    canyon_season: dict[tuple[int, str], dict[str, Any]] = defaultdict(new_bucket_rank_history)

    recent_canyon: dict[int, dict[int, deque[tuple[date, str, float]]]] = defaultdict(lambda: {window: deque() for window in WINDOW_DAYS})
    recent_massif: dict[str, dict[int, deque[tuple[date, str, float]]]] = defaultdict(lambda: {window: deque() for window in WINDOW_DAYS})
    recent_region: dict[str, dict[int, deque[tuple[date, str, float]]]] = defaultdict(lambda: {window: deque() for window in WINDOW_DAYS})
    last_canyon_observation: dict[int, tuple[date, float]] = {}

    output: list[dict[str, Any]] = []
    ordered = sorted_rows(rows)
    index = 0
    while index < len(ordered):
        current_key = group_time_key(ordered[index])
        group_rows: list[dict[str, Any]] = []
        while index < len(ordered) and group_time_key(ordered[index]) == current_key:
            group_rows.append(ordered[index])
            index += 1

        processed: list[tuple[dict[str, Any], date, str, float, int, str, str, int]] = []
        for source in group_rows:
            current_date = row_date(source)
            if current_date is None:
                output.append(dict(source))
                continue
            row = dict(source)
            canyon_id = int(row["canyonId"])
            region = str(row.get("region") or "__UNKNOWN_REGION__")
            massif = str(row.get("massif") or "__UNKNOWN_MASSIF__")
            month = month_key(row)
            season = season_key(month)
            bucket = target_bucket_for_level(row.get("niveau"))
            rank = LEVEL_TO_RANK.get(str(row.get("niveau") or ""))
            rank_value = float(rank) if rank is not None else None

            global_month_prior = normalized_probabilities(global_month[month]["counts"])
            global_season_prior = normalized_probabilities(global_season[season]["counts"])
            region_month_prior = smoothed_probabilities(region_month[(region, month)]["counts"], global_month_prior, strength=20.0)
            region_season_prior = smoothed_probabilities(region_season[(region, season)]["counts"], global_season_prior, strength=20.0)
            massif_month_prior = smoothed_probabilities(massif_month[(massif, month)]["counts"], region_month_prior, strength=15.0)
            massif_season_prior = smoothed_probabilities(massif_season[(massif, season)]["counts"], region_season_prior, strength=15.0)
            canyon_month_prior = smoothed_probabilities(canyon_month[(canyon_id, month)]["counts"], massif_month_prior, strength=8.0)
            canyon_season_prior = smoothed_probabilities(canyon_season[(canyon_id, season)]["counts"], massif_season_prior, strength=8.0)

            row["temporalHistorySeason"] = season
            row["canyonMonthPastObsCount"] = counter_total(canyon_month[(canyon_id, month)]["counts"])
            row["canyonSeasonPastObsCount"] = counter_total(canyon_season[(canyon_id, season)]["counts"])
            row["massifMonthPastObsCount"] = counter_total(massif_month[(massif, month)]["counts"])
            row["massifSeasonPastObsCount"] = counter_total(massif_season[(massif, season)]["counts"])
            row["regionMonthPastObsCount"] = counter_total(region_month[(region, month)]["counts"])
            row["regionSeasonPastObsCount"] = counter_total(region_season[(region, season)]["counts"])
            row.update(prefixed_prior("canyonMonthPrior", canyon_month_prior))
            row.update(prefixed_prior("canyonSeasonPrior", canyon_season_prior))
            row.update(prefixed_prior("massifMonthPrior", massif_month_prior))
            row.update(prefixed_prior("massifSeasonPrior", massif_season_prior))
            row.update(prefixed_prior("regionMonthPrior", region_month_prior))
            row.update(prefixed_prior("regionSeasonPrior", region_season_prior))
            row["canyonMonthMeanRank"] = mean_rank(canyon_month[(canyon_id, month)])
            row["canyonSeasonMeanRank"] = mean_rank(canyon_season[(canyon_id, season)])

            row.update(entity_recent_features("canyon", recent_canyon[canyon_id], current_date))
            row.update(entity_recent_features("massif", recent_massif[massif], current_date))
            row.update(entity_recent_features("region", recent_region[region], current_date))
            last = last_canyon_observation.get(canyon_id)
            row["canyonDaysSinceLastObs"] = float((current_date - last[0]).days) if last else None
            row["canyonLastObservedRank"] = last[1] if last else None

            output.append(row)
            if bucket is not None and rank_value is not None:
                processed.append((row, current_date, bucket, rank_value, canyon_id, massif, region, month))

        for _, current_date, bucket, rank_value, canyon_id, massif, region, month in processed:
            season = season_key(month)
            for history in (
                global_month[month], global_season[season],
                region_month[(region, month)], region_season[(region, season)],
                massif_month[(massif, month)], massif_season[(massif, season)],
                canyon_month[(canyon_id, month)], canyon_season[(canyon_id, season)],
            ):
                increment_bucket_rank_history(history, bucket, rank_value)
            for entity in (recent_canyon[canyon_id], recent_massif[massif], recent_region[region]):
                for window in WINDOW_DAYS:
                    entity[window].append((current_date, bucket, rank_value))
            last_canyon_observation[canyon_id] = (current_date, rank_value)

    return output


def main() -> None:
    parser = argparse.ArgumentParser(description="Add causal seasonal and recent-history debit features")
    parser.add_argument("--features-path", default=DEFAULT_FEATURES_PATH)
    parser.add_argument("--output-dir", default=DEFAULT_OUTPUT_DIR)
    args = parser.parse_args()

    rows = read_jsonl(Path(args.features_path))
    enriched = add_temporal_history_features(rows)
    output_dir = Path(args.output_dir)
    write_jsonl(output_dir / "training_features.jsonl", enriched)
    write_json(
        output_dir / "metadata.json",
        {
            "schemaVersion": 1,
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "sourceFeaturesPath": args.features_path,
            "rowCount": len(enriched),
            "featureFamilies": ["seasonal_priors", "recent_history"],
            "files": {"trainingFeatures": "training_features.jsonl"},
        },
    )
    print(f"Wrote {output_dir / 'training_features.jsonl'} ({len(enriched)} rows)")


if __name__ == "__main__":
    main()
