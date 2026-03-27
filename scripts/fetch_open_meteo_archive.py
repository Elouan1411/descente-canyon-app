from __future__ import annotations

import argparse
import concurrent.futures
import json
import sys
import threading
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


def append_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    if not rows:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")


def load_completed_window_ids(path: Path) -> set[str]:
    if not path.exists():
        return set()
    return {row["mergedWindowId"] for row in read_jsonl(path) if row.get("mergedWindowId")}


class RequestThrottler:
    def __init__(self, delay_seconds: float) -> None:
        self.delay_seconds = max(delay_seconds, 0.0)
        self._lock = threading.Lock()
        self._next_allowed_at = 0.0

    def wait_turn(self) -> None:
        if self.delay_seconds <= 0:
            return
        with self._lock:
            now = time.monotonic()
            if now < self._next_allowed_at:
                time.sleep(self._next_allowed_at - now)
                now = time.monotonic()
            self._next_allowed_at = now + self.delay_seconds


def fetch_single_window(
    *,
    merged_window: dict[str, Any],
    cache_dir: Path,
    model: str,
    hourly_variables: list[str],
    user_agent: str,
    throttler: RequestThrottler,
    request_timeout_seconds: int,
    max_attempts: int,
    base_backoff_seconds: float,
    refetch_cached: bool,
) -> dict[str, Any]:
    cache_path = cache_dir / f"{merged_window['mergedWindowId']}.json"
    if refetch_cached and cache_path.exists():
        cache_path.unlink()

    url = build_open_meteo_archive_url(
        latitude=float(merged_window["targetLatitude"]),
        longitude=float(merged_window["targetLongitude"]),
        start_date=merged_window["archiveStartDate"],
        end_date=merged_window["archiveEndDate"],
        model=model,
        hourly_variables=hourly_variables,
    )

    if cache_path.exists():
        payload = fetch_json(url, user_agent=user_agent, timeout=request_timeout_seconds, cache_path=cache_path)
        return {
            "mergedWindowId": merged_window["mergedWindowId"],
            "url": url,
            "cachePath": str(cache_path),
            "payload": payload,
            "hourlyRows": flatten_open_meteo_hourly_rows(merged_window=merged_window, payload=payload),
            "source": "cache",
        }

    last_error: Exception | None = None
    for attempt in range(max(max_attempts, 1)):
        try:
            throttler.wait_turn()
            payload = fetch_json(url, user_agent=user_agent, timeout=request_timeout_seconds, cache_path=cache_path)
            return {
                "mergedWindowId": merged_window["mergedWindowId"],
                "url": url,
                "cachePath": str(cache_path),
                "payload": payload,
                "hourlyRows": flatten_open_meteo_hourly_rows(merged_window=merged_window, payload=payload),
                "source": "network",
            }
        except Exception as exc:  # noqa: BLE001
            last_error = exc
            if attempt == max_attempts - 1 or not is_retryable_weather_error(exc):
                raise
            time.sleep(base_backoff_seconds * (2 ** attempt))

    assert last_error is not None
    raise last_error


def main() -> None:
    parser = argparse.ArgumentParser(description="Fetch merged historical weather windows from Open-Meteo archive")
    parser.add_argument("--merged-windows-path", default="build/debit-pipeline/weather-planning/merged_weather_windows.jsonl")
    parser.add_argument("--output-dir", default="build/debit-pipeline/weather-archive")
    parser.add_argument("--model", default="era5")
    parser.add_argument("--hourly", default=",".join(DEFAULT_HOURLY_VARIABLES))
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--request-delay-ms", type=int, default=1000)
    parser.add_argument("--timeout-s", type=int, default=20)
    parser.add_argument("--max-attempts", type=int, default=6)
    parser.add_argument("--base-backoff-ms", type=int, default=2000)
    parser.add_argument("--refetch-cached", action="store_true", help="Ignore cached raw JSON and fetch again")
    parser.add_argument("--user-agent", default="DescenteCanyonDebitPipeline/0.1")
    args = parser.parse_args()

    merged_windows = read_jsonl(Path(args.merged_windows_path))
    output_dir = Path(args.output_dir)
    cache_dir = output_dir / "raw-json"
    cache_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = output_dir / "weather_window_manifest.jsonl"
    hourly_rows_path = output_dir / "weather_hourly_rows.jsonl"
    failures_path = output_dir / "failures.json"

    hourly_variables = [value.strip() for value in args.hourly.split(",") if value.strip()]
    throttler = RequestThrottler(max(args.request_delay_ms, 0) / 1000.0)
    base_backoff_seconds = max(args.base_backoff_ms, 0) / 1000.0
    completed_window_ids = load_completed_window_ids(manifest_path)
    windows_to_process = [window for window in merged_windows if window["mergedWindowId"] not in completed_window_ids]

    failures: list[dict[str, Any]] = []
    success_count = 0
    cache_source_count = 0
    network_source_count = 0

    print(
        f"Fetching Open-Meteo archive for {len(windows_to_process)}/{len(merged_windows)} merged window(s)... "
        f"already_done={len(completed_window_ids)}",
        file=sys.stderr,
    )

    with concurrent.futures.ThreadPoolExecutor(max_workers=max(args.workers, 1)) as executor:
        future_map = {
            executor.submit(
                fetch_single_window,
                merged_window=window,
                cache_dir=cache_dir,
                model=args.model,
                hourly_variables=hourly_variables,
                user_agent=args.user_agent,
                throttler=throttler,
                request_timeout_seconds=max(args.timeout_s, 1),
                max_attempts=args.max_attempts,
                base_backoff_seconds=base_backoff_seconds,
                refetch_cached=args.refetch_cached,
            ): window
            for window in windows_to_process
        }

        completed = 0
        total_to_process = len(windows_to_process)
        pending = set(future_map.keys())
        last_heartbeat = time.monotonic()
        while pending:
            done, pending = concurrent.futures.wait(
                pending,
                timeout=10,
                return_when=concurrent.futures.FIRST_COMPLETED,
            )
            if not done:
                print(
                    f"Waiting... completed={completed}/{total_to_process} | failures={len(failures)} "
                    f"| cache={cache_source_count} | network={network_source_count}",
                    file=sys.stderr,
                )
                last_heartbeat = time.monotonic()
                continue

            for future in done:
                window = future_map[future]
                completed += 1
                try:
                    result = future.result()
                    payload = result["payload"]
                    manifest_row = {
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
                        "source": result.get("source"),
                    }
                    append_jsonl(manifest_path, [manifest_row])
                    append_jsonl(hourly_rows_path, result["hourlyRows"])
                    success_count += 1
                    if result.get("source") == "cache":
                        cache_source_count += 1
                    else:
                        network_source_count += 1
                except Exception as exc:  # noqa: BLE001
                    failures.append(
                        {
                            "mergedWindowId": window["mergedWindowId"],
                            "targetId": window["targetId"],
                            "archiveStartDate": window.get("archiveStartDate"),
                            "archiveEndDate": window.get("archiveEndDate"),
                            "error": repr(exc),
                        }
                    )
                    write_json(failures_path, failures)

                if completed <= 10 or completed % 50 == 0 or completed == total_to_process:
                    print(
                        f"Progress {completed}/{total_to_process} | successes={success_count} | failures={len(failures)} "
                        f"| cache={cache_source_count} | network={network_source_count}",
                        file=sys.stderr,
                    )

    write_json(
        output_dir / "metadata.json",
        {
            "schemaVersion": 1,
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "model": args.model,
            "hourlyVariables": hourly_variables,
            "requestedWindowCount": len(merged_windows),
            "alreadyCompletedWindowCount": len(completed_window_ids),
            "processedWindowCount": len(windows_to_process),
            "successfulWindowCount": success_count,
            "failureCount": len(failures),
            "cacheSourceCount": cache_source_count,
            "networkSourceCount": network_source_count,
            "requestDelayMs": args.request_delay_ms,
            "timeoutSeconds": args.timeout_s,
            "maxAttempts": args.max_attempts,
            "baseBackoffMs": args.base_backoff_ms,
            "refetchCached": args.refetch_cached,
            "files": {
                "manifest": "weather_window_manifest.jsonl",
                "hourlyRows": "weather_hourly_rows.jsonl",
                "rawJsonDir": "raw-json",
                "failures": "failures.json",
            },
        },
    )
    write_json(failures_path, failures)


if __name__ == "__main__":
    main()
