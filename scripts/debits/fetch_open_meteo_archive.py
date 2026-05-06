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

from pipeline_lib import (
    build_open_meteo_archive_daily_url,
    fetch_json,
    flatten_open_meteo_daily_rows,
    get_weather_retry_delay,
    is_retryable_weather_error,
    stable_id,
    write_json,
)


DEFAULT_DAILY_VARIABLES = [
    "precipitation_sum",
    "rain_sum",
    "snowfall_sum",
    "temperature_2m_mean",
    "temperature_2m_min",
    "temperature_2m_max",
    "precipitation_hours",
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


def chunk_windows(windows: list[dict[str, Any]], max_batch_targets: int) -> list[list[dict[str, Any]]]:
    ordered = sorted(windows, key=lambda item: (item["archiveStartDate"], item["targetId"]))
    batch_size = max(max_batch_targets, 1)
    return [ordered[index:index + batch_size] for index in range(0, len(ordered), batch_size)]


def batch_url_for_windows(batch_windows: list[dict[str, Any]], daily_variables: list[str], model: str) -> str:
    latitudes = ",".join(str(window["targetLatitude"]) for window in batch_windows)
    longitudes = ",".join(str(window["targetLongitude"]) for window in batch_windows)
    start_date = min(window["archiveStartDate"] for window in batch_windows)
    end_date = max(window["archiveEndDate"] for window in batch_windows)
    return build_open_meteo_archive_daily_url(
        latitude=latitudes,
        longitude=longitudes,
        start_date=start_date,
        end_date=end_date,
        model=model,
        daily_variables=daily_variables,
    )


def payloads_from_response(payload: Any) -> list[dict[str, Any]]:
    if isinstance(payload, list):
        return payload
    if isinstance(payload, dict):
        return [payload]
    raise ValueError(f"Unexpected payload type: {type(payload).__name__}")


def fetch_batch(
    *,
    batch_windows: list[dict[str, Any]],
    raw_cache_dir: Path,
    model: str,
    daily_variables: list[str],
    user_agent: str,
    throttler: RequestThrottler,
    request_timeout_seconds: int,
    max_attempts: int,
    base_backoff_seconds: float,
    refetch_cached: bool,
) -> dict[str, Any]:
    batch_id = stable_id(
        "weatherbatch",
        model,
        min(window["archiveStartDate"] for window in batch_windows),
        max(window["archiveEndDate"] for window in batch_windows),
        "|".join(window["mergedWindowId"] for window in batch_windows),
    )
    cache_path = raw_cache_dir / f"{batch_id}.json"
    if refetch_cached and cache_path.exists():
        cache_path.unlink()

    url = batch_url_for_windows(batch_windows, daily_variables, model)

    if cache_path.exists():
        payload = fetch_json(url, user_agent=user_agent, timeout=request_timeout_seconds, cache_path=cache_path)
        return {"batchId": batch_id, "cachePath": str(cache_path), "payload": payload, "source": "cache", "url": url}

    last_error: Exception | None = None
    for attempt in range(max(max_attempts, 1)):
        try:
            throttler.wait_turn()
            payload = fetch_json(url, user_agent=user_agent, timeout=request_timeout_seconds, cache_path=cache_path)
            return {"batchId": batch_id, "cachePath": str(cache_path), "payload": payload, "source": "network", "url": url}
        except Exception as exc:  # noqa: BLE001
            last_error = exc
            if attempt == max_attempts - 1 or not is_retryable_weather_error(exc):
                raise
            retry_delay = get_weather_retry_delay(exc, base_backoff_seconds * (2 ** attempt))
            time.sleep(retry_delay)

    assert last_error is not None
    raise last_error


def main() -> None:
    parser = argparse.ArgumentParser(description="Fetch historical daily weather windows from Open-Meteo archive")
    parser.add_argument("--merged-windows-path", default="build/debit-pipeline/weather-planning/merged_weather_windows.jsonl")
    parser.add_argument("--output-dir", default="build/debit-pipeline/weather-archive")
    parser.add_argument("--model", default="era5")
    parser.add_argument("--daily", default=",".join(DEFAULT_DAILY_VARIABLES))
    parser.add_argument("--workers", type=int, default=1)
    parser.add_argument("--max-batch-targets", type=int, default=25)
    parser.add_argument("--request-delay-ms", type=int, default=5000)
    parser.add_argument("--timeout-s", type=int, default=30)
    parser.add_argument("--max-attempts", type=int, default=3)
    parser.add_argument("--base-backoff-ms", type=int, default=10000)
    parser.add_argument("--refetch-cached", action="store_true", help="Ignore cached raw JSON and fetch again")
    parser.add_argument("--user-agent", default="DescenteCanyonDebitPipeline/0.1")
    args = parser.parse_args()

    merged_windows = read_jsonl(Path(args.merged_windows_path))
    output_dir = Path(args.output_dir)
    raw_cache_dir = output_dir / "raw-json"
    raw_cache_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = output_dir / "weather_window_manifest.jsonl"
    daily_rows_path = output_dir / "weather_daily_rows.jsonl"
    failures_path = output_dir / "failures.json"

    daily_variables = [value.strip() for value in args.daily.split(",") if value.strip()]
    throttler = RequestThrottler(max(args.request_delay_ms, 0) / 1000.0)
    base_backoff_seconds = max(args.base_backoff_ms, 0) / 1000.0
    completed_window_ids = load_completed_window_ids(manifest_path)
    windows_to_process = [window for window in merged_windows if window["mergedWindowId"] not in completed_window_ids]
    batches = chunk_windows(windows_to_process, args.max_batch_targets)

    failures: list[dict[str, Any]] = []
    success_count = 0
    cache_source_count = 0
    network_source_count = 0

    print(
        f"Fetching Open-Meteo daily archive for {len(windows_to_process)}/{len(merged_windows)} target window(s) in {len(batches)} batch(es)... "
        f"already_done={len(completed_window_ids)}",
        file=sys.stderr,
    )

    with concurrent.futures.ThreadPoolExecutor(max_workers=max(args.workers, 1)) as executor:
        future_map = {
            executor.submit(
                fetch_batch,
                batch_windows=batch,
                raw_cache_dir=raw_cache_dir,
                model=args.model,
                daily_variables=daily_variables,
                user_agent=args.user_agent,
                throttler=throttler,
                request_timeout_seconds=max(args.timeout_s, 1),
                max_attempts=args.max_attempts,
                base_backoff_seconds=base_backoff_seconds,
                refetch_cached=args.refetch_cached,
            ): batch
            for batch in batches
        }

        completed_batches = 0
        total_batches = len(batches)
        pending = set(future_map.keys())
        while pending:
            done, pending = concurrent.futures.wait(
                pending,
                timeout=10,
                return_when=concurrent.futures.FIRST_COMPLETED,
            )
            if not done:
                print(
                    f"Waiting... batches={completed_batches}/{total_batches} | windows_success={success_count} | failures={len(failures)} "
                    f"| cache={cache_source_count} | network={network_source_count}",
                    file=sys.stderr,
                )
                continue

            for future in done:
                batch_windows = future_map[future]
                completed_batches += 1
                try:
                    result = future.result()
                    payloads = payloads_from_response(result["payload"])
                    if len(payloads) != len(batch_windows):
                        raise ValueError(
                            f"Batch payload length mismatch: expected {len(batch_windows)} got {len(payloads)}"
                        )

                    manifest_rows: list[dict[str, Any]] = []
                    all_daily_rows: list[dict[str, Any]] = []
                    for window, payload in zip(batch_windows, payloads):
                        daily_rows = flatten_open_meteo_daily_rows(merged_window=window, payload=payload)
                        manifest_rows.append(
                            {
                                "mergedWindowId": window["mergedWindowId"],
                                "targetId": window["targetId"],
                                "archiveStartDate": window["archiveStartDate"],
                                "archiveEndDate": window["archiveEndDate"],
                                "requestedLatitude": window["targetLatitude"],
                                "requestedLongitude": window["targetLongitude"],
                                "resolvedLatitude": payload.get("latitude"),
                                "resolvedLongitude": payload.get("longitude"),
                                "resolvedElevation": payload.get("elevation"),
                                "timezone": payload.get("timezone"),
                                "dailyRowCount": len(daily_rows),
                                "url": result["url"],
                                "cachePath": result["cachePath"],
                                "source": result.get("source"),
                                "batchId": result["batchId"],
                            }
                        )
                        all_daily_rows.extend(daily_rows)

                    append_jsonl(manifest_path, manifest_rows)
                    append_jsonl(daily_rows_path, all_daily_rows)
                    success_count += len(batch_windows)
                    if result.get("source") == "cache":
                        cache_source_count += len(batch_windows)
                    else:
                        network_source_count += len(batch_windows)
                except Exception as exc:  # noqa: BLE001
                    for window in batch_windows:
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

                if completed_batches <= 5 or completed_batches % 10 == 0 or completed_batches == total_batches:
                    print(
                        f"Progress batches {completed_batches}/{total_batches} | windows_success={success_count} | failures={len(failures)} "
                        f"| cache={cache_source_count} | network={network_source_count}",
                        file=sys.stderr,
                    )

    write_json(
        output_dir / "metadata.json",
        {
            "schemaVersion": 1,
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "model": args.model,
            "dailyVariables": daily_variables,
            "requestedWindowCount": len(merged_windows),
            "alreadyCompletedWindowCount": len(completed_window_ids),
            "processedWindowCount": len(windows_to_process),
            "batchCount": len(batches),
            "maxBatchTargets": args.max_batch_targets,
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
                "dailyRows": "weather_daily_rows.jsonl",
                "rawJsonDir": "raw-json",
                "failures": "failures.json",
            },
        },
    )
    write_json(failures_path, failures)


if __name__ == "__main__":
    main()
