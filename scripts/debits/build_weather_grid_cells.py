from __future__ import annotations

import argparse
import json
import math
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from pipeline_lib import GEO_POINT_PRIORITY, load_canyon_lookup, load_geo_points_lookup, load_watershed_lookup, stable_id, write_json, write_jsonl


DEFAULT_WATERSHEDS_PATH = "offline-data/full/room-import/watersheds.json"
DEFAULT_CANYONS_PATH = "offline-data/full/room-import/canyons.json"
DEFAULT_GEO_POINTS_PATH = "offline-data/full/room-import/geo_points.json"
DEFAULT_OUTPUT_DIR = "build/debit-pipeline/weather-grid-cells-era5-land"
EARTH_RADIUS_KM = 6371.0088


def grid_cell_id(model: str, resolution_degrees: float, latitude: float, longitude: float) -> str:
    return stable_id("weathercell", model, f"{resolution_degrees:.4f}", f"{latitude:.6f}", f"{longitude:.6f}")


def snap_coordinate(value: float, resolution: float) -> float:
    return round(round(value / resolution) * resolution, 6)


def coordinate_values(min_value: float, max_value: float, resolution: float) -> list[float]:
    half = resolution / 2.0
    start = math.floor((min_value - half) / resolution) * resolution
    end = math.ceil((max_value + half) / resolution) * resolution
    values: list[float] = []
    current = start
    while current <= end + 1e-9:
        values.append(round(current, 6))
        current += resolution
    return values


def project_geometry(geometry: Any, reference_latitude: float) -> Any:
    from shapely.ops import transform  # type: ignore

    cos_lat = math.cos(math.radians(reference_latitude))

    def project(x: float, y: float, z: float | None = None) -> tuple[float, float] | tuple[float, float, float]:
        projected = (
            math.radians(x) * EARTH_RADIUS_KM * cos_lat,
            math.radians(y) * EARTH_RADIUS_KM,
        )
        if z is None:
            return projected
        return projected[0], projected[1], z

    return transform(project, geometry)


def valid_shape(geometry: dict[str, Any]) -> Any | None:
    from shapely.geometry import shape  # type: ignore

    try:
        geom = shape(geometry)
        if geom.is_empty:
            return None
        if not geom.is_valid:
            geom = geom.buffer(0)
        return geom if not geom.is_empty else None
    except Exception:
        return None


def build_cells_for_watershed(
    *,
    canyon_id: int,
    watershed: dict[str, Any],
    model: str,
    resolution_degrees: float,
    min_area_weight: float,
    max_cells: int,
) -> tuple[dict[str, Any] | None, list[dict[str, Any]]]:
    from shapely.geometry import box  # type: ignore

    geometry = watershed.get("geometry")
    if not isinstance(geometry, dict):
        return None, []
    basin = valid_shape(geometry)
    if basin is None:
        return None, []

    min_lon, min_lat, max_lon, max_lat = basin.bounds
    reference_latitude = (min_lat + max_lat) / 2.0
    projected_basin = project_geometry(basin, reference_latitude)
    basin_area = float(projected_basin.area)
    if basin_area <= 0.0:
        return None, []

    half = resolution_degrees / 2.0
    raw_cells: list[dict[str, Any]] = []
    for latitude in coordinate_values(min_lat, max_lat, resolution_degrees):
        for longitude in coordinate_values(min_lon, max_lon, resolution_degrees):
            cell_polygon = box(longitude - half, latitude - half, longitude + half, latitude + half)
            if not basin.intersects(cell_polygon):
                continue
            intersection = basin.intersection(cell_polygon)
            if intersection.is_empty:
                continue
            projected_intersection = project_geometry(intersection, reference_latitude)
            intersection_area = float(projected_intersection.area)
            if intersection_area <= 0.0:
                continue
            raw_cells.append(
                {
                    "gridCellId": grid_cell_id(model, resolution_degrees, latitude, longitude),
                    "model": model,
                    "resolutionDegrees": resolution_degrees,
                    "latitude": latitude,
                    "longitude": longitude,
                    "rawAreaWeight": intersection_area / basin_area,
                    "intersectionAreaKm2Approx": round(intersection_area, 6),
                }
            )

    retained = [cell for cell in raw_cells if float(cell["rawAreaWeight"]) >= min_area_weight]
    if not retained:
        retained = sorted(raw_cells, key=lambda cell: float(cell["rawAreaWeight"]), reverse=True)[:1]
    if max_cells > 0 and len(retained) > max_cells:
        retained = sorted(retained, key=lambda cell: float(cell["rawAreaWeight"]), reverse=True)[:max_cells]
    total_weight = sum(float(cell["rawAreaWeight"]) for cell in retained)
    if total_weight <= 0.0:
        return None, []
    for cell in retained:
        cell["areaWeight"] = round(float(cell["rawAreaWeight"]) / total_weight, 8)
        cell.pop("rawAreaWeight", None)

    weighted_latitude = sum(float(cell["latitude"]) * float(cell["areaWeight"]) for cell in retained)
    weighted_longitude = sum(float(cell["longitude"]) * float(cell["areaWeight"]) for cell in retained)
    payload = {
        "canyonId": canyon_id,
        "model": model,
        "resolutionDegrees": resolution_degrees,
        "gridCellCount": len(retained),
        "candidateGridCellCount": len(raw_cells),
        "coveredAreaFractionApprox": round(sum(float(cell["intersectionAreaKm2Approx"]) for cell in retained) / basin_area, 8),
        "weightedLatitude": round(weighted_latitude, 6),
        "weightedLongitude": round(weighted_longitude, 6),
        "basinAreaKm2Approx": round(basin_area, 6),
        "cells": sorted(retained, key=lambda cell: (cell["latitude"], cell["longitude"])),
    }
    return payload, retained


def build_point_fallback_cells(
    *,
    canyon_id: int,
    canyon: dict[str, Any],
    geo_points: list[dict[str, Any]],
    model: str,
    resolution_degrees: float,
) -> tuple[dict[str, Any] | None, list[dict[str, Any]]]:
    usable_points = [point for point in geo_points if point.get("latitude") is not None and point.get("longitude") is not None]
    if not usable_points:
        return None, []
    best_point = min(usable_points, key=lambda point: GEO_POINT_PRIORITY.get(point.get("type"), 99))
    latitude = snap_coordinate(float(best_point["latitude"]), resolution_degrees)
    longitude = snap_coordinate(float(best_point["longitude"]), resolution_degrees)
    cell = {
        "gridCellId": grid_cell_id(model, resolution_degrees, latitude, longitude),
        "model": model,
        "resolutionDegrees": resolution_degrees,
        "latitude": latitude,
        "longitude": longitude,
        "areaWeight": 1.0,
        "pointFallbackType": best_point.get("type"),
        "pointFallbackLabel": best_point.get("label"),
    }
    payload = {
        "canyonId": canyon_id,
        "model": model,
        "resolutionDegrees": resolution_degrees,
        "weatherGridSource": "POINT_FALLBACK",
        "gridCellCount": 1,
        "candidateGridCellCount": 1,
        "coveredAreaFractionApprox": 1.0,
        "weightedLatitude": latitude,
        "weightedLongitude": longitude,
        "basinAreaKm2Approx": None,
        "fallbackPointType": best_point.get("type"),
        "fallbackPointLabel": best_point.get("label"),
        "canyonName": canyon.get("nom"),
        "cells": [cell],
    }
    return payload, [cell]


def main() -> None:
    parser = argparse.ArgumentParser(description="Build ERA5-Land weather grid cells intersecting each canyon watershed")
    parser.add_argument("--canyons-path", default=DEFAULT_CANYONS_PATH)
    parser.add_argument("--geo-points-path", default=DEFAULT_GEO_POINTS_PATH)
    parser.add_argument("--watersheds-path", default=DEFAULT_WATERSHEDS_PATH)
    parser.add_argument("--output-dir", default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--model", default="era5_land")
    parser.add_argument("--resolution-degrees", type=float, default=0.1)
    parser.add_argument("--min-area-weight", type=float, default=0.005)
    parser.add_argument("--max-cells", type=int, default=64)
    args = parser.parse_args()

    canyons = load_canyon_lookup(Path(args.canyons_path))
    geo_points = load_geo_points_lookup(Path(args.geo_points_path))
    watersheds = load_watershed_lookup(Path(args.watersheds_path))
    canyon_payloads: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []
    cells_by_id: dict[str, dict[str, Any]] = {}
    for canyon_id, canyon in sorted(canyons.items()):
        watershed = watersheds.get(canyon_id)
        if watershed is not None:
            payload, retained_cells = build_cells_for_watershed(
                canyon_id=canyon_id,
                watershed=watershed,
                model=args.model,
                resolution_degrees=args.resolution_degrees,
                min_area_weight=args.min_area_weight,
                max_cells=args.max_cells,
            )
            if payload is not None:
                payload["weatherGridSource"] = "WATERSHED_GRID"
                payload["canyonName"] = canyon.get("nom")
        else:
            payload, retained_cells = build_point_fallback_cells(
                canyon_id=canyon_id,
                canyon=canyon,
                geo_points=geo_points.get(canyon_id, []),
                model=args.model,
                resolution_degrees=args.resolution_degrees,
            )
        if payload is None:
            skipped.append({"canyonId": canyon_id, "reason": "missing_geometry_and_point_fallback"})
            continue
        canyon_payloads.append(payload)
        for cell in retained_cells:
            cells_by_id.setdefault(
                str(cell["gridCellId"]),
                {
                    "gridCellId": cell["gridCellId"],
                    "model": cell["model"],
                    "resolutionDegrees": cell["resolutionDegrees"],
                    "latitude": cell["latitude"],
                    "longitude": cell["longitude"],
                },
            )

    output_dir = Path(args.output_dir)
    cell_counts = Counter(payload["gridCellCount"] for payload in canyon_payloads)
    write_json(output_dir / "weather_grid_cells_by_canyon.json", canyon_payloads)
    write_json(output_dir / "weather_grid_cell_lookup.json", dict(sorted(cells_by_id.items())))
    write_jsonl(output_dir / "skipped_watersheds.jsonl", skipped)
    write_json(
        output_dir / "metadata.json",
        {
            "schemaVersion": 1,
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "watershedsPath": args.watersheds_path,
            "canyonsPath": args.canyons_path,
            "geoPointsPath": args.geo_points_path,
            "model": args.model,
            "resolutionDegrees": args.resolution_degrees,
            "minAreaWeight": args.min_area_weight,
            "maxCells": args.max_cells,
            "canyonCount": len(canyon_payloads),
            "uniqueGridCellCount": len(cells_by_id),
            "skippedCount": len(skipped),
            "weatherGridSources": dict(sorted(Counter(payload.get("weatherGridSource") for payload in canyon_payloads).items())),
            "gridCellCountHistogram": {str(key): value for key, value in sorted(cell_counts.items())},
            "files": {
                "gridCellsByCanyon": "weather_grid_cells_by_canyon.json",
                "gridCellLookup": "weather_grid_cell_lookup.json",
                "skippedWatersheds": "skipped_watersheds.jsonl",
            },
        },
    )
    print(f"Built weather grid cells for {len(canyon_payloads)} canyon(s), unique cells={len(cells_by_id)}")


if __name__ == "__main__":
    main()
