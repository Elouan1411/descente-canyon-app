from __future__ import annotations

import argparse
import json
from collections import Counter, defaultdict
import calendar
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from pipeline_lib import stable_id, write_json, write_jsonl


DEFAULT_OBSERVATIONS_PATH = "build/debit-pipeline/post-cutoff-descente-refresh-reviewed/valid_debit_observations.jsonl"
DEFAULT_GRID_CELLS_PATH = "build/debit-pipeline/weather-grid-cells-era5-land/weather_grid_cells_by_canyon.json"
DEFAULT_OUTPUT_DIR = "build/debit-pipeline/weather-grid-planning-era5-land"


def read_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            stripped = line.strip()
            if stripped:
                rows.append(json.loads(stripped))
    return rows


def observation_time(row: dict[str, Any]) -> datetime | None:
    raw = row.get("assumedObservationTimeLocal")
    if raw:
        return datetime.fromisoformat(str(raw))
    if row.get("date"):
        return datetime.fromisoformat(f"{row['date']}T08:00:00")
    return None


def merge_cell_windows(
    windows: list[dict[str, Any]],
    *,
    max_gap_days: int,
    max_span_days: int,
) -> list[dict[str, Any]]:
    if not windows:
        return []
    ordered = sorted(windows, key=lambda row: (row["archiveStartDate"], row["archiveEndDate"], row["canyonId"], row["observationId"]))
    merged: list[dict[str, Any]] = []
    current = dict(ordered[0])
    observation_ids = [current["observationId"]]
    canyon_ids = {int(current["canyonId"])}
    for window in ordered[1:]:
        current_start = date.fromisoformat(current["archiveStartDate"])
        current_end = date.fromisoformat(current["archiveEndDate"])
        window_start = date.fromisoformat(window["archiveStartDate"])
        window_end = date.fromisoformat(window["archiveEndDate"])
        gap_days = (window_start - current_end).days
        merged_end = max(current_end, window_end)
        span_days = (merged_end - current_start).days
        if gap_days <= max_gap_days and span_days <= max_span_days:
            current["archiveEndDate"] = merged_end.isoformat()
            current["windowEndLocal"] = f"{merged_end.isoformat()}T23:59:59"
            observation_ids.append(window["observationId"])
            canyon_ids.add(int(window["canyonId"]))
            continue
        current["observationIds"] = sorted(set(observation_ids))
        current["observationCount"] = len(set(observation_ids))
        current["canyonIds"] = sorted(canyon_ids)
        current["canyonCount"] = len(canyon_ids)
        current["mergedWindowId"] = stable_id("gridcellwindow", current["gridCellId"], current["archiveStartDate"], current["archiveEndDate"])
        merged.append(current)
        current = dict(window)
        observation_ids = [current["observationId"]]
        canyon_ids = {int(current["canyonId"])}
    current["observationIds"] = sorted(set(observation_ids))
    current["observationCount"] = len(set(observation_ids))
    current["canyonIds"] = sorted(canyon_ids)
    current["canyonCount"] = len(canyon_ids)
    current["mergedWindowId"] = stable_id("gridcellwindow", current["gridCellId"], current["archiveStartDate"], current["archiveEndDate"])
    merged.append(current)
    return merged


def month_start(value: date) -> date:
    return date(value.year, value.month, 1)


def next_month(value: date) -> date:
    if value.month == 12:
        return date(value.year + 1, 1, 1)
    return date(value.year, value.month + 1, 1)


def month_end(value: date) -> date:
    return date(value.year, value.month, calendar.monthrange(value.year, value.month)[1])


def monthly_cell_windows(
    windows_by_cell: dict[str, list[dict[str, Any]]],
    *,
    history_end_date: str,
) -> list[dict[str, Any]]:
    history_end = date.fromisoformat(history_end_date)
    grouped: dict[tuple[str, str], dict[str, Any]] = {}
    for cell_id, windows in windows_by_cell.items():
        for window in windows:
            start = date.fromisoformat(window["archiveStartDate"])
            end = min(date.fromisoformat(window["archiveEndDate"]), history_end)
            current = month_start(start)
            while current <= end:
                raw_month = f"{current.year:04d}-{current.month:02d}"
                key = (cell_id, raw_month)
                month_window = grouped.setdefault(
                    key,
                    {
                        "gridCellId": cell_id,
                        "targetId": cell_id,
                        "targetLatitude": window["targetLatitude"],
                        "targetLongitude": window["targetLongitude"],
                        "targetSource": "ERA5_LAND_GRID_CELL",
                        "archiveStartDate": month_start(current).isoformat(),
                        "archiveEndDate": min(month_end(current), history_end).isoformat(),
                        "windowStartLocal": f"{month_start(current).isoformat()}T00:00:00",
                        "windowEndLocal": f"{min(month_end(current), history_end).isoformat()}T23:59:59",
                        "observationIds": set(),
                        "canyonIds": set(),
                        "month": raw_month,
                    },
                )
                month_window["observationIds"].add(window["observationId"])
                month_window["canyonIds"].add(int(window["canyonId"]))
                current = next_month(current)

    result: list[dict[str, Any]] = []
    for row in grouped.values():
        observation_ids = sorted(row.pop("observationIds"))
        canyon_ids = sorted(row.pop("canyonIds"))
        row["observationIds"] = observation_ids
        row["observationCount"] = len(observation_ids)
        row["canyonIds"] = canyon_ids
        row["canyonCount"] = len(canyon_ids)
        row["mergedWindowId"] = stable_id("gridcellmonth", row["gridCellId"], row["month"], row["archiveEndDate"])
        result.append(row)
    return sorted(result, key=lambda item: (item["month"], item["gridCellId"]))


def main() -> None:
    parser = argparse.ArgumentParser(description="Plan canyon and ERA5-Land grid-cell weather windows")
    parser.add_argument("--observations-path", default=DEFAULT_OBSERVATIONS_PATH)
    parser.add_argument("--grid-cells-path", default=DEFAULT_GRID_CELLS_PATH)
    parser.add_argument("--output-dir", default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--lookback-days", type=int, default=30)
    parser.add_argument("--history-end-date", default="2026-05-28")
    parser.add_argument(
        "--grid-fetch-strategy",
        choices=["monthly", "sparse", "history"],
        default="sparse",
        help="Fetch monthly cell windows, sparse merged observation lookback windows, or one full history per cell.",
    )
    parser.add_argument("--grid-fetch-max-gap-days", type=int, default=7)
    parser.add_argument("--grid-fetch-max-span-days", type=int, default=75)
    args = parser.parse_args()

    observations = read_jsonl(Path(args.observations_path))
    grid_payloads = read_json(Path(args.grid_cells_path))
    grid_by_canyon = {int(row["canyonId"]): row for row in grid_payloads}
    history_end_date = args.history_end_date

    observation_windows: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []
    grouped_observations: dict[int, list[dict[str, Any]]] = defaultdict(list)
    cell_observation_windows: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for observation in observations:
        canyon_id = int(observation["canyonId"])
        grid = grid_by_canyon.get(canyon_id)
        obs_time = observation_time(observation)
        if grid is None:
            skipped.append({"observationId": observation.get("observationId"), "canyonId": canyon_id, "reason": "missing_grid_cells"})
            continue
        if obs_time is None:
            skipped.append({"observationId": observation.get("observationId"), "canyonId": canyon_id, "reason": "missing_observation_time"})
            continue
        target_id = stable_id("gridcanyontarget", canyon_id, grid["model"], grid["resolutionDegrees"])
        start_time = obs_time - timedelta(days=args.lookback_days)
        observation_windows.append(
            {
                "observationWindowId": stable_id("gridobswindow", observation["observationId"], target_id, start_time.isoformat(), obs_time.isoformat()),
                "observationId": observation["observationId"],
                "canyonId": canyon_id,
                "targetId": target_id,
                "targetLatitude": grid["weightedLatitude"],
                "targetLongitude": grid["weightedLongitude"],
                "targetSource": grid.get("weatherGridSource") or "ERA5_LAND_GRID_AREA_WEIGHTED",
                "windowStartLocal": start_time.isoformat(timespec="seconds"),
                "windowEndLocal": obs_time.isoformat(timespec="seconds"),
                "archiveStartDate": start_time.date().isoformat(),
                "archiveEndDate": obs_time.date().isoformat(),
            }
        )
        grouped_observations[canyon_id].append(observation)
        for cell in grid["cells"]:
            cell_observation_windows[str(cell["gridCellId"])].append(
                {
                    "gridCellId": cell["gridCellId"],
                    "targetId": cell["gridCellId"],
                    "targetLatitude": cell["latitude"],
                    "targetLongitude": cell["longitude"],
                    "targetSource": "ERA5_LAND_GRID_CELL",
                    "archiveStartDate": start_time.date().isoformat(),
                    "archiveEndDate": obs_time.date().isoformat(),
                    "windowStartLocal": start_time.isoformat(timespec="seconds"),
                    "windowEndLocal": obs_time.isoformat(timespec="seconds"),
                    "observationId": observation["observationId"],
                    "canyonId": canyon_id,
                }
            )

    merged_windows: list[dict[str, Any]] = []
    cell_usage: dict[str, dict[str, Any]] = {}
    for canyon_id, group_rows in sorted(grouped_observations.items()):
        grid = grid_by_canyon[canyon_id]
        target_id = stable_id("gridcanyontarget", canyon_id, grid["model"], grid["resolutionDegrees"])
        obs_windows = [window for window in observation_windows if int(window["canyonId"]) == canyon_id]
        archive_start = min(window["archiveStartDate"] for window in obs_windows)
        merged_id = stable_id("gridcanyonhistory", canyon_id, target_id, archive_start, history_end_date)
        merged_windows.append(
            {
                "mergedWindowId": merged_id,
                "targetId": target_id,
                "targetLatitude": grid["weightedLatitude"],
                "targetLongitude": grid["weightedLongitude"],
                "targetSource": grid.get("weatherGridSource") or "ERA5_LAND_GRID_AREA_WEIGHTED",
                "windowStartLocal": min(window["windowStartLocal"] for window in obs_windows),
                "windowEndLocal": max(window["windowEndLocal"] for window in obs_windows),
                "archiveStartDate": archive_start,
                "archiveEndDate": history_end_date,
                "observationIds": sorted(row["observationId"] for row in group_rows),
                "observationCount": len(group_rows),
                "canyonIds": [canyon_id],
                "fetchStrategy": "grid_canyon_history_daily",
            }
        )
    cell_fetch_windows: list[dict[str, Any]] = []
    if args.grid_fetch_strategy == "monthly":
        cell_fetch_windows = monthly_cell_windows(
            cell_observation_windows,
            history_end_date=history_end_date,
        )
    elif args.grid_fetch_strategy == "history":
        for cell_id, windows in sorted(cell_observation_windows.items()):
            first = windows[0]
            archive_start = min(window["archiveStartDate"] for window in windows)
            archive_end = history_end_date
            canyon_ids = sorted({int(window["canyonId"]) for window in windows})
            cell_fetch_windows.append(
                {
                    "gridCellId": cell_id,
                    "targetId": cell_id,
                    "targetLatitude": first["targetLatitude"],
                    "targetLongitude": first["targetLongitude"],
                    "targetSource": "ERA5_LAND_GRID_CELL",
                    "archiveStartDate": archive_start,
                    "archiveEndDate": archive_end,
                    "windowStartLocal": f"{archive_start}T00:00:00",
                    "windowEndLocal": f"{archive_end}T23:59:59",
                    "canyonIds": canyon_ids,
                    "canyonCount": len(canyon_ids),
                    "observationCount": len({window["observationId"] for window in windows}),
                    "mergedWindowId": stable_id("gridcellhistory", cell_id, archive_start, archive_end),
                }
            )
    else:
        for cell_id, windows in sorted(cell_observation_windows.items()):
            cell_fetch_windows.extend(
                merge_cell_windows(
                    windows,
                    max_gap_days=args.grid_fetch_max_gap_days,
                    max_span_days=args.grid_fetch_max_span_days,
                )
            )

    output_dir = Path(args.output_dir)
    write_jsonl(output_dir / "observation_weather_windows.jsonl", observation_windows)
    write_jsonl(output_dir / "merged_weather_windows.jsonl", merged_windows)
    write_jsonl(output_dir / "grid_cell_fetch_windows.jsonl", cell_fetch_windows)
    write_json(output_dir / "skipped_observations.json", skipped)
    write_json(
        output_dir / "metadata.json",
        {
            "schemaVersion": 1,
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "observationsPath": args.observations_path,
            "gridCellsPath": args.grid_cells_path,
            "lookbackDays": args.lookback_days,
            "historyEndDate": history_end_date,
            "gridFetchStrategy": args.grid_fetch_strategy,
            "gridFetchMaxGapDays": args.grid_fetch_max_gap_days,
            "gridFetchMaxSpanDays": args.grid_fetch_max_span_days,
            "observationCount": len(observations),
            "observationWindowCount": len(observation_windows),
            "mergedWindowCount": len(merged_windows),
            "gridCellFetchWindowCount": len(cell_fetch_windows),
            "skippedObservationCount": len(skipped),
            "targetSources": dict(sorted(Counter(window["targetSource"] for window in observation_windows).items())),
            "files": {
                "observationWindows": "observation_weather_windows.jsonl",
                "mergedWindows": "merged_weather_windows.jsonl",
                "gridCellFetchWindows": "grid_cell_fetch_windows.jsonl",
                "skipped": "skipped_observations.json",
            },
        },
    )
    print(f"Planned {len(cell_fetch_windows)} unique ERA5-Land grid cell fetch window(s)")


if __name__ == "__main__":
    main()
