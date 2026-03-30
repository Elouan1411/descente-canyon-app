from __future__ import annotations

import argparse
import json
import math
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from debit_pipeline_lib import (
    compute_daily_precipitation_features,
    load_canyon_lookup,
    load_watershed_lookup,
    normalize_text,
    write_json,
    write_jsonl,
)


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

    feature_rows: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []
    for observation in observations:
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
        }
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
        feature_rows.append(feature_row)

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
