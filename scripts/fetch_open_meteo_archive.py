from __future__ import annotations

import argparse
import concurrent.futures
import json
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from debit_pipeline_lib import (
    build_open_meteo_archive_url,
    fetch_json,
    flatten_open_meteo_hourly_rows,
    is_retryable_weather_error,
    write_json,
    write_jsonl,
)


DEFAULT_HOURLY_VARIABLES = [
    "precipitation",
    "rain",
    "snowfall",
    "temperature_2m",
    "soil_moisture_0_to_7cm",
]


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            stripped = line.strip()
            if stripped:
                rows.append(json.loads(stripped))
    return rows


def fetch_single_window(
    *,
    merged_window: dict[str, Any],
    cache_dir: Path,
    model: str,
    hourly_variables: list[str],
    user_agent: str,
    request_delay_seconds: float,
) -> dict[str, Any]:
    cache_path = cache_dir / f"{merged_window['mergedWindowId']}.json"
    url = build_open_meteo_archive_url(
        latitude=float(merged_window["targetLatitude"]),
        longitude=float(merged_window["targetLongitude"]),
        start_date=merged_window["archiveStartDate"],
        end_date=merged_window["archiveEndDate"],
        model=model,
        hourly_variables=hourly_variables,
    )

    last_error: Exception | None = None
    for attempt in range(3):
        try:
            payload = fetch_json(
                url,
                user_agent=user_agent,
                delay_seconds=request_delay_seconds,
                cache_path=cache_path,
            )
            hourly_rows = flatten_open_meteo_hourly_rows(merged_window=merged_window, payload=payload)
            return {
                "mergedWindowId": merged_window["mergedWindowId"],
                "url": url,
                "cachePath": str(cache_path),
                "payload": payload,
                "hourlyRows": hourly_rows,
            }
        except Exception as exc:  # noqa: BLE001
            last_error = exc
            if attempt == 2 or not is_retryable_weather_error(exc):
                raise
            time.sleep(2 * (attempt + 1))

    assert last_error is not None
    raise last_error


def main() -> None:
    parser = argparse.ArgumentParser(description="Fetch merged historical weather windows from Open-Meteo archive")
    parser.add_argument("--merged-windows-path", default="build/debit-pipeline/weather-planning/merged_weather_windows.jsonl")
    parser.add_argument("--output-dir", default="build/debit-pipeline/weather-archive")
    parser.add_argument("--model", default="era5")
    parser.add_argument("--hourly", default=",".join(DEFAULT_HOURLY_VARIABLES))
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--request-delay-ms", type=int, default=200)
    parser.add_argument("--user-agent", default="DescenteCanyonDebitPipeline/0.1")
    args = parser.parse_args()

    merged_windows = read_jsonl(Path(args.merged_windows_path))
    output_dir = Path(args.output_dir)
    cache_dir = output_dir / "raw-json"
    cache_dir.mkdir(parents=True, exist_ok=True)
    hourly_variables = [value.strip() for value in args.hourly.split(",") if value.strip()]
    request_delay_seconds = max(args.request_delay_ms, 0) / 1000.0

    manifests: list[dict[str, Any]] = []
    all_hourly_rows: list[dict[str, Any]] = []
    failures: list[dict[str, Any]] = []

    print(f"Fetching Open-Meteo archive for {len(merged_windows)} merged window(s)...", file=sys.stderr)
    with concurrent.futures.ThreadPoolExecutor(max_workers=max(args.workers, 1)) as executor:
        future_map = {
            executor.submit(
                fetch_single_window,
                merged_window=window,
                cache_dir=cache_dir,
                model=args.model,
                hourly_variables=hourly_variables,
                user_agent=args.user_agent,
                request_delay_seconds=request_delay_seconds,
            ): window
            for window in merged_windows
        }

        completed = 0
        for future in concurrent.futures.as_completed(future_map):
            window = future_map[future]
            completed += 1
            try:
                result = future.result()
                payload = result["payload"]
                manifests.append(
                    {
                        "mergedWindowId": result["mergedWindowId"],
                        "targetId": window["targetId"],
                        "archiveStartDate": window["archiveStartDate"],
                        "archiveEndDate": window["archiveEndDate"],
                        "requestedLatitude": window["targetLatitude"],
                        "requestedLongitude": window["targetLongitude"],
                        "resolvedLatitude": payload.get("latitude"),
                        "resolvedLongitude": payload.get("longitude"),
                        "resolvedElevation": payload.get("elevation"),
                        "timezone": payload.get("timezone"),
                        "hourlyRowCount": len(result["hourlyRows"]),
                        "url": result["url"],
                        "cachePath": result["cachePath"],
                    }
                )
                all_hourly_rows.extend(result["hourlyRows"])
            except Exception as exc:  # noqa: BLE001
                failures.append({
                    "mergedWindowId": window["mergedWindowId"],
                    "targetId": window["targetId"],
                    "error": repr(exc),
                })
            if completed % 50 == 0 or completed == len(merged_windows):
                print(f"Progress {completed}/{len(merged_windows)} | failures={len(failures)}", file=sys.stderr)

    output_dir.mkdir(parents=True, exist_ok=True)
    write_jsonl(output_dir / "weather_window_manifest.jsonl", sorted(manifests, key=lambda item: item["mergedWindowId"]))
    write_jsonl(output_dir / "weather_hourly_rows.jsonl", all_hourly_rows)
    write_json(
        output_dir / "metadata.json",
        {
            "schemaVersion": 1,
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "model": args.model,
            "hourlyVariables": hourly_variables,
            "requestedWindowCount": len(merged_windows),
            "successfulWindowCount": len(manifests),
            "failureCount": len(failures),
            "hourlyRowCount": len(all_hourly_rows),
            "files": {
                "manifest": "weather_window_manifest.jsonl",
                "hourlyRows": "weather_hourly_rows.jsonl",
                "rawJsonDir": "raw-json",
            },
        },
    )
    write_json(output_dir / "failures.json", failures)


if __name__ == "__main__":
    main()
