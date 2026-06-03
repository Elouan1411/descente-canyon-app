from __future__ import annotations

import argparse
import json
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from pipeline_lib import write_json, write_jsonl


DEFAULT_GRID_CELLS_PATH = "build/debit-pipeline/weather-grid-cells-era5-land/weather_grid_cells_by_canyon.json"
DEFAULT_MERGED_WINDOWS_PATH = "build/debit-pipeline/weather-grid-planning-era5-land/merged_weather_windows.jsonl"
DEFAULT_GRID_DAILY_ROWS_PATH = "build/debit-pipeline/weather-grid-archive-era5-land/weather_daily_rows.jsonl"
DEFAULT_OUTPUT_DIR = "build/debit-pipeline/weather-grid-aggregated-era5-land"
WEATHER_VARIABLES = (
    "precipitation_sum",
    "rain_sum",
    "snowfall_sum",
    "temperature_2m_mean",
    "temperature_2m_min",
    "temperature_2m_max",
    "precipitation_hours",
)


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


def percentile(values: list[float], q: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, int(round((len(ordered) - 1) * q))))
    return ordered[index]


def aggregate_day(*, cells: list[dict[str, Any]], rows_by_cell_and_date: dict[tuple[str, str], dict[str, Any]], raw_date: str) -> dict[str, Any] | None:
    available: list[tuple[dict[str, Any], dict[str, Any]]] = []
    for cell in cells:
        row = rows_by_cell_and_date.get((str(cell["gridCellId"]), raw_date))
        if row is not None:
            available.append((cell, row))
    if not available:
        return None
    total_weight = sum(float(cell["areaWeight"]) for cell, _ in available)
    if total_weight <= 0.0:
        return None
    output: dict[str, Any] = {"date": raw_date}
    for variable in WEATHER_VARIABLES:
        weighted_values: list[tuple[float, float]] = []
        for cell, row in available:
            value = row.get(variable)
            if value is None:
                continue
            weighted_values.append((float(value), float(cell["areaWeight"]) / total_weight))
        output[variable] = round(sum(value * weight for value, weight in weighted_values), 6) if weighted_values else None

    precip_values = [float(row.get("precipitation_sum") or 0.0) for _, row in available]
    output["max_cell_precipitation_sum"] = round(max(precip_values), 6) if precip_values else None
    p90 = percentile(precip_values, 0.9)
    output["p90_cell_precipitation_sum"] = round(p90, 6) if p90 is not None else None
    output["weatherGridCellCount"] = len(cells)
    output["weatherGridAvailableCellCount"] = len(available)
    output["weatherGridAvailableWeightFraction"] = round(total_weight, 8)
    return output


def main() -> None:
    parser = argparse.ArgumentParser(description="Aggregate ERA5-Land grid cell weather rows into canyon-level daily weather rows")
    parser.add_argument("--grid-cells-path", default=DEFAULT_GRID_CELLS_PATH)
    parser.add_argument("--merged-windows-path", default=DEFAULT_MERGED_WINDOWS_PATH)
    parser.add_argument("--grid-daily-rows-path", default=DEFAULT_GRID_DAILY_ROWS_PATH)
    parser.add_argument("--output-dir", default=DEFAULT_OUTPUT_DIR)
    args = parser.parse_args()

    grid_by_canyon = {int(row["canyonId"]): row for row in read_json(Path(args.grid_cells_path))}
    merged_windows = read_jsonl(Path(args.merged_windows_path))
    grid_rows = read_jsonl(Path(args.grid_daily_rows_path))
    rows_by_cell_and_date = {(str(row["targetId"]), str(row["date"])): row for row in grid_rows}
    dates_by_cell: dict[str, set[str]] = defaultdict(set)
    for row in grid_rows:
        dates_by_cell[str(row["targetId"])].add(str(row["date"]))

    output_rows: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []
    for window in merged_windows:
        canyon_ids = window.get("canyonIds") or []
        if not canyon_ids:
            skipped.append({"mergedWindowId": window.get("mergedWindowId"), "reason": "missing_canyon_id"})
            continue
        canyon_id = int(canyon_ids[0])
        grid = grid_by_canyon.get(canyon_id)
        if grid is None:
            skipped.append({"mergedWindowId": window.get("mergedWindowId"), "canyonId": canyon_id, "reason": "missing_grid_cells"})
            continue
        start = str(window["archiveStartDate"])
        end = str(window["archiveEndDate"])
        candidate_dates: set[str] = set()
        for cell in grid["cells"]:
            candidate_dates.update(date for date in dates_by_cell.get(str(cell["gridCellId"]), set()) if start <= date <= end)
        for raw_date in sorted(candidate_dates):
            aggregated = aggregate_day(cells=grid["cells"], rows_by_cell_and_date=rows_by_cell_and_date, raw_date=raw_date)
            if aggregated is not None:
                aggregated.update(
                    {
                        "mergedWindowId": window["mergedWindowId"],
                        "targetId": window["targetId"],
                        "timezone": "area_weighted_grid",
                        "resolvedLatitude": window.get("targetLatitude"),
                        "resolvedLongitude": window.get("targetLongitude"),
                        "resolvedElevation": None,
                        "weatherGridSource": grid.get("weatherGridSource") or "WATERSHED_GRID",
                        "weatherGridHasWatershed": 0.0 if grid.get("weatherGridSource") == "POINT_FALLBACK" else 1.0,
                        "weatherGridCoveredAreaFraction": grid.get("coveredAreaFractionApprox"),
                    }
                )
                output_rows.append(aggregated)

    output_dir = Path(args.output_dir)
    write_jsonl(output_dir / "weather_daily_rows.jsonl", output_rows)
    write_jsonl(output_dir / "skipped_windows.jsonl", skipped)
    write_json(
        output_dir / "metadata.json",
        {
            "schemaVersion": 1,
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "gridCellsPath": args.grid_cells_path,
            "mergedWindowsPath": args.merged_windows_path,
            "gridDailyRowsPath": args.grid_daily_rows_path,
            "mergedWindowCount": len(merged_windows),
            "gridDailyRowCount": len(grid_rows),
            "aggregatedDailyRowCount": len(output_rows),
            "skippedWindowCount": len(skipped),
            "files": {
                "dailyRows": "weather_daily_rows.jsonl",
                "skippedWindows": "skipped_windows.jsonl",
            },
        },
    )
    print(f"Wrote {len(output_rows)} aggregated canyon daily weather rows")


if __name__ == "__main__":
    main()
