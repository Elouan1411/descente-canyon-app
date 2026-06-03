from __future__ import annotations

import argparse
import json
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from pipeline_lib import (
    build_annual_windows,
    build_observation_window,
    build_target_history_windows,
    build_weather_target,
    load_canyon_lookup,
    load_geo_points_lookup,
    load_watershed_lookup,
    merge_windows,
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


def main() -> None:
    parser = argparse.ArgumentParser(description="Plan merged weather windows around valid debit observations")
    parser.add_argument("--observations-path", default="build/debit-pipeline/observations/valid_debit_observations.jsonl")
    parser.add_argument("--canyons-path", default="offline-data/full/room-import/canyons.json")
    parser.add_argument("--geo-points-path", default="offline-data/full/room-import/geo_points.json")
    parser.add_argument("--watersheds-path", default="offline-data/full/room-import/watersheds.json")
    parser.add_argument("--output-dir", default="build/debit-pipeline/weather-planning")
    parser.add_argument("--lookback-days", type=int, default=7)
    parser.add_argument(
        "--fetch-strategy",
        choices=["proximity", "annual", "history_daily"],
        default="history_daily",
        help="How to batch weather fetch windows for API calls",
    )
    parser.add_argument(
        "--history-end-date",
        help="End date for history_daily fetch windows. Defaults to today's date.",
    )
    parser.add_argument(
        "--fetch-max-gap-days",
        type=int,
        default=14,
        help="Merge nearby observation windows for the same weather target when the gap stays below this threshold",
    )
    parser.add_argument(
        "--fetch-max-span-days",
        type=int,
        default=45,
        help="Limit a merged weather fetch window to this many days to avoid huge payloads",
    )
    args = parser.parse_args()

    observations = read_jsonl(Path(args.observations_path))
    canyon_lookup = load_canyon_lookup(Path(args.canyons_path))
    geo_points_lookup = load_geo_points_lookup(Path(args.geo_points_path))
    watershed_lookup = load_watershed_lookup(Path(args.watersheds_path))

    targets_by_canyon: dict[int, dict[str, Any]] = {}
    targets_by_id: dict[str, dict[str, Any]] = {}
    observation_windows: list[dict[str, Any]] = []
    skipped_observations: list[dict[str, Any]] = []

    for observation in observations:
        canyon_id = int(observation["canyonId"])
        target = targets_by_canyon.get(canyon_id)
        if target is None:
            canyon = canyon_lookup.get(canyon_id)
            if canyon is None:
                skipped_observations.append({
                    "observationId": observation["observationId"],
                    "canyonId": canyon_id,
                    "reason": "missing_canyon",
                })
                continue
            target = build_weather_target(
                canyon=canyon,
                geo_points=geo_points_lookup.get(canyon_id, []),
                watershed=watershed_lookup.get(canyon_id),
            )
            if target is None:
                skipped_observations.append({
                    "observationId": observation["observationId"],
                    "canyonId": canyon_id,
                    "reason": "missing_weather_target",
                })
                continue
            canonical_target = targets_by_id.get(target["targetId"])
            if canonical_target is None:
                canonical_target = dict(target)
                canonical_target["canyonIds"] = [canyon_id]
                canonical_target["canyonCount"] = 1
                targets_by_id[target["targetId"]] = canonical_target
            else:
                canyon_ids = set(canonical_target.get("canyonIds", []))
                canyon_ids.add(canyon_id)
                canonical_target["canyonIds"] = sorted(canyon_ids)
                canonical_target["canyonCount"] = len(canonical_target["canyonIds"])
            targets_by_canyon[canyon_id] = canonical_target

        if observation.get("assumedObservationTimeLocal") is None:
            skipped_observations.append({
                "observationId": observation["observationId"],
                "canyonId": canyon_id,
                "reason": "missing_assumed_local_time",
            })
            continue

        observation_windows.append(
            build_observation_window(
                observation,
                target,
                lookback_days=args.lookback_days,
            )
        )

    history_end_date = args.history_end_date or datetime.now().date().isoformat()
    if args.fetch_strategy == "history_daily":
        merged_windows = build_target_history_windows(
            observation_windows,
            history_end_date=history_end_date,
        )
    elif args.fetch_strategy == "annual":
        merged_windows = build_annual_windows(observation_windows)
    else:
        merged_windows = merge_windows(
            observation_windows,
            max_gap_days=args.fetch_max_gap_days,
            max_span_days=args.fetch_max_span_days,
        )
    targets = sorted(targets_by_id.values(), key=lambda item: (item["source"], item["targetId"]))
    source_counts = Counter(target["source"] for target in targets)

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    write_jsonl(output_dir / "weather_targets.jsonl", sorted(targets, key=lambda item: item["canyonId"]))
    write_jsonl(output_dir / "observation_weather_windows.jsonl", observation_windows)
    write_jsonl(output_dir / "merged_weather_windows.jsonl", merged_windows)
    write_json(output_dir / "skipped_observations.json", skipped_observations)
    write_json(
        output_dir / "metadata.json",
        {
            "schemaVersion": 1,
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "lookbackDays": args.lookback_days,
            "fetchStrategy": args.fetch_strategy,
            "historyEndDate": history_end_date if args.fetch_strategy == "history_daily" else None,
            "fetchMaxGapDays": args.fetch_max_gap_days,
            "fetchMaxSpanDays": args.fetch_max_span_days,
            "observationCount": len(observations),
            "targetCount": len(targets),
            "observationWindowCount": len(observation_windows),
            "mergedWindowCount": len(merged_windows),
            "skippedObservationCount": len(skipped_observations),
            "targetSources": dict(sorted(source_counts.items())),
            "files": {
                "targets": "weather_targets.jsonl",
                "observationWindows": "observation_weather_windows.jsonl",
                "mergedWindows": "merged_weather_windows.jsonl",
                "skipped": "skipped_observations.json",
            },
        },
    )


if __name__ == "__main__":
    main()
