from __future__ import annotations

import argparse
import itertools
import json
import math
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from debit_pipeline_lib import (
    compute_daily_precipitation_features,
    compute_watershed_morphology_features,
    load_canyon_lookup,
    load_watershed_lookup,
    normalize_text,
    write_json,
    write_jsonl,
)


TARGET_BUCKETS = ("LOW", "MEDIUM", "HIGH")
REGULATED_KEYWORDS = (
    "barrage",
    "captage",
    "centrale",
    "edf",
    "debit reserve",
    "retenue",
    "conduite forcee",
    "prise d eau",
    "lacher",
    "hydroelect",
    "turbinage",
    "dam",
)
SNOWMELT_KEYWORDS = (
    "neige",
    "neve",
    "glace",
    "glacier",
    "avalanche",
    "fonte",
    "snow",
    "nival",
)


def target_bucket_for_level(level: str | None) -> str | None:
    if level in {"SEC", "FILET"}:
        return "LOW"
    if level == "CORRECT":
        return "MEDIUM"
    if level in {"GROS", "TRES_GROS", "CRUE"}:
        return "HIGH"
    return None


def empty_bucket_counter() -> dict[str, int]:
    return {bucket: 0 for bucket in TARGET_BUCKETS}


def counter_total(counts: dict[str, int]) -> int:
    return sum(counts.get(bucket, 0) for bucket in TARGET_BUCKETS)


def normalized_probabilities(counts: dict[str, int]) -> dict[str, float]:
    total = counter_total(counts)
    if total <= 0:
        uniform = 1.0 / len(TARGET_BUCKETS)
        return {bucket: uniform for bucket in TARGET_BUCKETS}
    return {bucket: counts.get(bucket, 0) / total for bucket in TARGET_BUCKETS}


def smoothed_probabilities(counts: dict[str, int], base_probs: dict[str, float], strength: float) -> dict[str, float]:
    total = counter_total(counts)
    denominator = total + strength
    if denominator <= 0:
        return dict(base_probs)
    return {
        bucket: (counts.get(bucket, 0) + strength * base_probs[bucket]) / denominator
        for bucket in TARGET_BUCKETS
    }


def new_signal_history() -> dict[str, int]:
    return {"observedCount": 0, "regulatedCount": 0, "snowmeltCount": 0}


def comment_signal_flags(comment: str | None) -> dict[str, bool]:
    normalized_comment = normalize_text(comment)
    if not normalized_comment:
        return {"regulated": False, "snowmelt": False}
    return {
        "regulated": any(keyword in normalized_comment for keyword in REGULATED_KEYWORDS),
        "snowmelt": any(keyword in normalized_comment for keyword in SNOWMELT_KEYWORDS),
    }


def signal_ratio(history: dict[str, int], signal_key: str) -> float:
    observed_count = history.get("observedCount", 0)
    if observed_count <= 0:
        return 0.0
    return history.get(signal_key, 0) / observed_count


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            stripped = line.strip()
            if stripped:
                rows.append(json.loads(stripped))
    return rows


def last_daily_row_before(rows: list[dict[str, Any]], observation_date: str) -> dict[str, Any] | None:
    cutoff_date = observation_date
    selected: dict[str, Any] | None = None
    for row in rows:
        if row["date"] < cutoff_date:
            selected = row
        else:
            break
    return selected


def main() -> None:
    parser = argparse.ArgumentParser(description="Build training features from valid debit observations and daily weather cache")
    parser.add_argument("--observations-path", default="build/debit-pipeline/observations/valid_debit_observations.jsonl")
    parser.add_argument("--observation-windows-path", default="build/debit-pipeline/weather-planning/observation_weather_windows.jsonl")
    parser.add_argument("--merged-windows-path", default="build/debit-pipeline/weather-planning/merged_weather_windows.jsonl")
    parser.add_argument("--weather-daily-path", default="build/debit-pipeline/weather-archive/weather_daily_rows.jsonl")
    parser.add_argument("--canyons-path", default="offline-data/full/room-import/canyons.json")
    parser.add_argument("--watersheds-path", default="offline-data/full/room-import/watersheds.json")
    parser.add_argument("--output-dir", default="build/debit-pipeline/training-features")
    args = parser.parse_args()

    observations = read_jsonl(Path(args.observations_path))
    observation_windows = read_jsonl(Path(args.observation_windows_path))
    merged_windows = read_jsonl(Path(args.merged_windows_path))
    weather_rows = read_jsonl(Path(args.weather_daily_path))
    canyon_lookup = load_canyon_lookup(Path(args.canyons_path))
    watershed_lookup = load_watershed_lookup(Path(args.watersheds_path))
    default_watershed_features = compute_watershed_morphology_features(None)
    watershed_features_by_canyon = {
        canyon_id: compute_watershed_morphology_features(watershed)
        for canyon_id, watershed in watershed_lookup.items()
    }

    observation_window_by_id = {row["observationId"]: row for row in observation_windows}
    observation_to_merged: dict[str, dict[str, Any]] = {}
    for merged_window in merged_windows:
        for observation_id in merged_window.get("observationIds", []):
            observation_to_merged[observation_id] = merged_window

    weather_by_merged_window: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in weather_rows:
        weather_by_merged_window[row["mergedWindowId"]].append(row)
    for rows in weather_by_merged_window.values():
        rows.sort(key=lambda item: item["date"])

    observations_sorted = sorted(
        observations,
        key=lambda row: (
            row.get("assumedObservationTimeLocal") or row.get("date") or "",
            row.get("observationId") or "",
        ),
    )

    global_class_counts = empty_bucket_counter()
    region_class_counts: dict[str, dict[str, int]] = defaultdict(empty_bucket_counter)
    massif_class_counts: dict[str, dict[str, int]] = defaultdict(empty_bucket_counter)
    canyon_class_counts: dict[int, dict[str, int]] = defaultdict(empty_bucket_counter)

    global_signal_history = new_signal_history()
    region_signal_history: dict[str, dict[str, int]] = defaultdict(new_signal_history)
    massif_signal_history: dict[str, dict[str, int]] = defaultdict(new_signal_history)
    canyon_signal_history: dict[int, dict[str, int]] = defaultdict(new_signal_history)

    feature_rows: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []

    for _, observation_group in itertools.groupby(
        observations_sorted,
        key=lambda row: row.get("assumedObservationTimeLocal") or row.get("date") or row.get("observationId"),
    ):
        group_rows = list(observation_group)
        group_feature_rows: list[dict[str, Any]] = []
        processed_group_observations: list[tuple[dict[str, Any], int, str, str, str, dict[str, bool]]] = []

        for observation in group_rows:
            observation_id = observation["observationId"]
            observation_window = observation_window_by_id.get(observation_id)
            merged_window = observation_to_merged.get(observation_id)
            if observation_window is None or merged_window is None:
                skipped.append({"observationId": observation_id, "reason": "missing_weather_window"})
                continue
            daily_rows = weather_by_merged_window.get(merged_window["mergedWindowId"], [])
            if not daily_rows:
                skipped.append({"observationId": observation_id, "reason": "missing_daily_weather"})
                continue

            canyon_id = int(observation["canyonId"])
            canyon = canyon_lookup.get(canyon_id, {})
            watershed = watershed_lookup.get(canyon_id)
            observation_date = observation.get("date")
            latest_weather = last_daily_row_before(daily_rows, observation_date) if observation_date else None
            observation_month = int(observation["date"].split("-")[1]) if observation.get("date") else None
            region_key = canyon.get("region") or "__UNKNOWN_REGION__"
            massif_key = canyon.get("massif") or "__UNKNOWN_MASSIF__"
            target_bucket = target_bucket_for_level(observation.get("niveau"))
            signal_flags = comment_signal_flags(observation.get("comment"))

            global_prior = normalized_probabilities(global_class_counts)
            region_prior = smoothed_probabilities(region_class_counts[region_key], global_prior, strength=30.0)
            massif_prior = smoothed_probabilities(massif_class_counts[massif_key], region_prior, strength=20.0)
            canyon_prior = smoothed_probabilities(canyon_class_counts[canyon_id], massif_prior, strength=10.0)

            canyon_history = canyon_signal_history[canyon_id]
            massif_history = massif_signal_history[massif_key]
            region_history = region_signal_history[region_key]

            feature_row = {
                "observationId": observation_id,
                "canyonId": canyon_id,
                "canyonName": observation.get("canyonName"),
                "date": observation.get("date"),
                "assumedObservationTimeLocal": observation.get("assumedObservationTimeLocal"),
                "niveau": observation.get("niveau"),
                "niveauRank": observation.get("niveauRank"),
                "isDescended": observation.get("isDescended"),
                "targetId": observation_window["targetId"],
                "targetSource": observation_window["targetSource"],
                "targetLatitude": observation_window["targetLatitude"],
                "targetLongitude": observation_window["targetLongitude"],
                "country": canyon.get("pays"),
                "region": canyon.get("region"),
                "departement": canyon.get("departement"),
                "massif": canyon.get("massif"),
                "bassin": canyon.get("bassin"),
                "month": observation_month,
                "monthSin": round(math.sin(2.0 * math.pi * ((observation_month or 1) - 1) / 12.0), 6) if observation_month else None,
                "monthCos": round(math.cos(2.0 * math.pi * ((observation_month or 1) - 1) / 12.0), 6) if observation_month else None,
                "altitudeDepartM": canyon.get("altitudeDepart"),
                "deniveleM": canyon.get("denivele"),
                "longueurM": canyon.get("longueur"),
            "cascadeMaxM": canyon.get("cascadeMax"),
            "upstreamCatchmentAreaKm2": watershed.get("upstreamCatchmentAreaKm2") if watershed else None,
            "hasWatershed": watershed is not None,
            "commentText": observation.get("comment"),
            "commentTokenCount": len(normalize_text(observation.get("comment")).split()) if observation.get("comment") else 0,
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
            feature_row.update(watershed_features_by_canyon.get(canyon_id, default_watershed_features))
            if observation_date is not None:
                feature_row.update(compute_daily_precipitation_features(daily_rows, observation_date))
            if latest_weather is not None:
                feature_row.update(
                    {
                        "temperature2mAtObservation": latest_weather.get("temperature_2m_mean"),
                        "temperature2mMinAtObservationDay": latest_weather.get("temperature_2m_min"),
                        "temperature2mMaxAtObservationDay": latest_weather.get("temperature_2m_max"),
                        "rainAtObservationDay": latest_weather.get("rain_sum"),
                        "snowfallAtObservationDay": latest_weather.get("snowfall_sum"),
                        "precipitationHoursAtObservationDay": latest_weather.get("precipitation_hours"),
                        "weatherTimezone": latest_weather.get("timezone"),
                    }
                )
            group_feature_rows.append(feature_row)
            processed_group_observations.append(
                (observation, canyon_id, region_key, massif_key, target_bucket or "", signal_flags)
            )

        feature_rows.extend(group_feature_rows)

        for observation, canyon_id, region_key, massif_key, target_bucket, signal_flags in processed_group_observations:
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

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    write_jsonl(output_dir / "training_features.jsonl", feature_rows)
    write_json(
        output_dir / "metadata.json",
        {
            "schemaVersion": 1,
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "featureRowCount": len(feature_rows),
            "skippedObservationCount": len(skipped),
            "files": {
                "trainingFeatures": "training_features.jsonl",
                "skipped": "skipped_observations.json",
            },
        },
    )
    write_json(output_dir / "skipped_observations.json", skipped)


if __name__ == "__main__":
    main()
