from __future__ import annotations

import math
from typing import Any

import numpy as np
import rasterio
from rasterio.features import shapes
from rasterio.enums import Resampling
from rasterio.transform import Affine
from rasterio.vrt import WarpedVRT
from rasterio.warp import transform, transform_geom

from compute_entry_watersheds import FLOW_DIRECTION_OFFSETS


SOILGRIDS_LAYERS = {
    "clay": "https://files.isric.org/soilgrids/latest/data/clay/clay_0-5cm_mean.vrt",
    "sand": "https://files.isric.org/soilgrids/latest/data/sand/sand_0-5cm_mean.vrt",
}

GLIM_CLASS_CODES = {
    "su": 1,
    "vb": 2,
    "ss": 3,
    "pb": 4,
    "sm": 5,
    "sc": 6,
    "va": 7,
    "mt": 8,
    "pa": 9,
    "vi": 10,
    "wb": 11,
    "py": 12,
    "pi": 13,
    "ev": 14,
    "nd": 15,
    "ig": 16,
}


def _cell_center(transform: Affine, row: int, col: int) -> tuple[float, float]:
    x = transform.c + (col + 0.5) * transform.a + (row + 0.5) * transform.b
    y = transform.f + (col + 0.5) * transform.d + (row + 0.5) * transform.e
    return x, y


def _haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    radius_m = 6_371_008.8
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    delta_phi = math.radians(lat2 - lat1)
    delta_lambda = math.radians(lon2 - lon1)
    a = (
        math.sin(delta_phi / 2.0) ** 2
        + math.cos(phi1) * math.cos(phi2) * math.sin(delta_lambda / 2.0) ** 2
    )
    return 2.0 * radius_m * math.atan2(math.sqrt(a), math.sqrt(1.0 - a))


def _cell_step_length_m(transform: Affine, crs: Any, row1: int, col1: int, row2: int, col2: int) -> float:
    x1, y1 = _cell_center(transform, row1, col1)
    x2, y2 = _cell_center(transform, row2, col2)
    if crs is not None and getattr(crs, "is_geographic", False):
        (lon1,), (lat1,) = transform_coords(crs, "EPSG:4326", [x1], [y1])
        (lon2,), (lat2,) = transform_coords(crs, "EPSG:4326", [x2], [y2])
        return _haversine_m(lat1, lon1, lat2, lon2)
    return math.hypot(x2 - x1, y2 - y1)


def transform_coords(src_crs: Any, dst_crs: Any, xs: list[float], ys: list[float]) -> tuple[list[float], list[float]]:
    out_xs, out_ys = transform(src_crs, dst_crs, xs, ys)
    return list(out_xs), list(out_ys)


def build_watershed_mask_data(
    *,
    flowdir_path: str,
    snapped_longitude: float,
    snapped_latitude: float,
    source_srs: str | None,
) -> dict[str, Any] | None:
    with rasterio.open(flowdir_path) as src:
        raster_crs = src.crs or source_srs
        if raster_crs is None:
            raise SystemExit(f"Flow direction raster has no CRS: {flowdir_path}")

        xs, ys = transform("EPSG:4326", raster_crs, [snapped_longitude], [snapped_latitude])
        target_row, target_col = src.index(xs[0], ys[0])
        flow = src.read(1, masked=True).filled(0).astype(np.int16)
        if target_row < 0 or target_row >= flow.shape[0] or target_col < 0 or target_col >= flow.shape[1]:
            return None

        visited = np.zeros(flow.shape, dtype=np.uint8)
        path_lengths = np.full(flow.shape, np.nan, dtype=np.float32)
        queue: list[tuple[int, int]] = [(target_row, target_col)]
        visited[target_row, target_col] = 1
        path_lengths[target_row, target_col] = 0.0

        index = 0
        while index < len(queue):
            row, col = queue[index]
            index += 1
            for d_row in (-1, 0, 1):
                for d_col in (-1, 0, 1):
                    if d_row == 0 and d_col == 0:
                        continue
                    n_row = row + d_row
                    n_col = col + d_col
                    if n_row < 0 or n_row >= flow.shape[0] or n_col < 0 or n_col >= flow.shape[1]:
                        continue
                    if visited[n_row, n_col] == 1:
                        continue
                    direction_code = int(flow[n_row, n_col])
                    offset = FLOW_DIRECTION_OFFSETS.get(direction_code)
                    if offset is None:
                        continue
                    if n_row + offset[0] == row and n_col + offset[1] == col:
                        visited[n_row, n_col] = 1
                        queue.append((n_row, n_col))
                        step = _cell_step_length_m(src.transform, raster_crs, n_row, n_col, row, col)
                        path_lengths[n_row, n_col] = float(path_lengths[row, col] + step)

        return {
            "mask": visited == 1,
            "pathLengthsM": path_lengths,
            "transform": src.transform,
            "crs": raster_crs,
            "targetCell": [target_row, target_col],
        }


def mask_to_geometry(mask_data: dict[str, Any], simplify_tolerance: float) -> dict[str, Any] | None:
    from run_catchment_batch import simplify_projected_geometry

    mask = mask_data["mask"].astype(np.uint8)
    raster_crs = mask_data["crs"]
    transform_affine = mask_data["transform"]
    polygon_geometries = []
    for geometry, value in shapes(mask, mask=mask == 1, transform=transform_affine):
        if int(value) != 1:
            continue
        simplified = simplify_projected_geometry(geometry, simplify_tolerance)
        polygon_geometries.append(transform_geom(raster_crs, "EPSG:4326", simplified, precision=6))

    if not polygon_geometries:
        return None

    polygons: list[Any] = []
    for geometry in polygon_geometries:
        if geometry["type"] == "Polygon":
            polygons.append(geometry["coordinates"])
        elif geometry["type"] == "MultiPolygon":
            polygons.extend(geometry["coordinates"])
    if not polygons:
        return None
    if len(polygons) == 1:
        return {"type": "Polygon", "coordinates": polygons[0]}
    return {"type": "MultiPolygon", "coordinates": polygons}


def _resolution_m(transform_affine: Affine, crs: Any, lat_hint: float) -> tuple[float, float]:
    x_res = abs(transform_affine.a)
    y_res = abs(transform_affine.e)
    if crs is not None and getattr(crs, "is_geographic", False):
        y_res_m = 111_320.0 * y_res
        x_res_m = 111_320.0 * max(0.1, abs(math.cos(math.radians(lat_hint)))) * x_res
        return x_res_m, y_res_m
    return x_res, y_res


def _slope_aspect(dem: np.ndarray, x_res_m: float, y_res_m: float) -> tuple[np.ndarray, np.ndarray]:
    dz_dy, dz_dx = np.gradient(dem, y_res_m, x_res_m)
    slope_rad = np.arctan(np.sqrt(dz_dx ** 2 + dz_dy ** 2))
    slope_deg = np.degrees(slope_rad)
    aspect = np.degrees(np.arctan2(dz_dx, -dz_dy))
    aspect = np.where(aspect < 0, 360.0 + aspect, aspect)
    return slope_deg, aspect


def _tri(dem: np.ndarray) -> np.ndarray:
    padded = np.pad(dem, 1, mode="constant", constant_values=np.nan)
    center = padded[1:-1, 1:-1]
    diffs = []
    for d_row in (-1, 0, 1):
        for d_col in (-1, 0, 1):
            if d_row == 0 and d_col == 0:
                continue
            neighbor = padded[1 + d_row : 1 + d_row + dem.shape[0], 1 + d_col : 1 + d_col + dem.shape[1]]
            diffs.append(np.abs(neighbor - center))
    stacked = np.stack(diffs, axis=0)
    return np.nanmean(stacked, axis=0)


def _network_threshold_km2(dem_resolution_m: float) -> float:
    if dem_resolution_m <= 10.0:
        return 0.05
    if dem_resolution_m <= 30.0:
        return 0.1
    if dem_resolution_m <= 100.0:
        return 0.5
    if dem_resolution_m <= 500.0:
        return 2.0
    return 5.0


def _step_lengths_for_grid(x_res_m: float, y_res_m: float) -> dict[tuple[int, int], float]:
    lengths: dict[tuple[int, int], float] = {}
    for d_row in (-1, 0, 1):
        for d_col in (-1, 0, 1):
            if d_row == 0 and d_col == 0:
                continue
            lengths[(d_row, d_col)] = math.hypot(y_res_m * d_row, x_res_m * d_col)
    return lengths


def _compute_network_metrics(
    *,
    mask: np.ndarray,
    path_lengths: np.ndarray,
    flow: np.ndarray,
    uparea: np.ndarray,
    area_km2: float,
    dem_resolution_m: float,
) -> dict[str, Any]:
    threshold = _network_threshold_km2(dem_resolution_m)
    stream_mask = mask & np.isfinite(uparea) & (uparea >= threshold)
    thresholds = [threshold, threshold / 2.0, threshold / 5.0, 0.0]
    for candidate_threshold in thresholds:
        stream_mask = mask & np.isfinite(uparea) & (uparea >= candidate_threshold)
        if np.count_nonzero(stream_mask) > 0:
            threshold = candidate_threshold
            break

    stream_count = int(np.count_nonzero(stream_mask))
    if stream_count == 0:
        return {
            "streamExtractionThresholdKm2": _round(threshold, 6),
            "streamCellCount": 0,
            "streamFrequencyPerKm2": None,
            "drainageDensityKmPerKm2": None,
            "streamSegmentCount": 0,
            "junctionCount": 0,
            "strahlerOrder": None,
            "firstOrderLengthFraction": None,
            "totalStreamLengthKm": None,
        }

    valid_path = np.where(stream_mask & np.isfinite(path_lengths), path_lengths, -1.0)
    ordered_indices = np.argwhere(stream_mask)
    ordered_indices = sorted(ordered_indices.tolist(), key=lambda rc: float(valid_path[rc[0], rc[1]]), reverse=True)

    # Derive local step lengths from the mean path increment among neighbors to stay CRS-agnostic.
    step_lengths: dict[tuple[int, int], float] = {}
    for d_row in (-1, 0, 1):
        for d_col in (-1, 0, 1):
            if d_row == 0 and d_col == 0:
                continue
            diffs = []
            for row, col in ordered_indices[: min(len(ordered_indices), 400)]:
                n_row = row + d_row
                n_col = col + d_col
                if n_row < 0 or n_row >= path_lengths.shape[0] or n_col < 0 or n_col >= path_lengths.shape[1]:
                    continue
                if not np.isfinite(path_lengths[n_row, n_col]) or not np.isfinite(path_lengths[row, col]):
                    continue
                diff = abs(float(path_lengths[n_row, n_col] - path_lengths[row, col]))
                if diff > 0:
                    diffs.append(diff)
            if diffs:
                step_lengths[(d_row, d_col)] = float(np.median(diffs))

    fallback_lengths = _step_lengths_for_grid(dem_resolution_m, dem_resolution_m)
    for key, value in fallback_lengths.items():
        step_lengths.setdefault(key, value)

    orders = np.zeros(flow.shape, dtype=np.int16)
    junction_count = 0
    source_count = 0
    total_length_m = 0.0
    first_order_length_m = 0.0
    stream_segment_count = 0

    for row, col in ordered_indices:
        upstream_orders = []
        for d_row in (-1, 0, 1):
            for d_col in (-1, 0, 1):
                if d_row == 0 and d_col == 0:
                    continue
                n_row = row + d_row
                n_col = col + d_col
                if n_row < 0 or n_row >= flow.shape[0] or n_col < 0 or n_col >= flow.shape[1]:
                    continue
                if not stream_mask[n_row, n_col]:
                    continue
                direction_code = int(flow[n_row, n_col])
                offset = FLOW_DIRECTION_OFFSETS.get(direction_code)
                if offset is None:
                    continue
                if n_row + offset[0] == row and n_col + offset[1] == col and orders[n_row, n_col] > 0:
                    upstream_orders.append(int(orders[n_row, n_col]))

        if not upstream_orders:
            orders[row, col] = 1
            source_count += 1
        else:
            max_order = max(upstream_orders)
            if upstream_orders.count(max_order) >= 2:
                orders[row, col] = max_order + 1
            else:
                orders[row, col] = max_order
            if len(upstream_orders) >= 2:
                junction_count += 1

    for row, col in ordered_indices:
        direction_code = int(flow[row, col])
        offset = FLOW_DIRECTION_OFFSETS.get(direction_code)
        if offset is None:
            continue
        n_row = row + offset[0]
        n_col = col + offset[1]
        if n_row < 0 or n_row >= flow.shape[0] or n_col < 0 or n_col >= flow.shape[1]:
            continue
        if not stream_mask[n_row, n_col]:
            continue
        step_length = step_lengths.get(offset, fallback_lengths.get(offset, dem_resolution_m))
        total_length_m += step_length
        stream_segment_count += 1
        if orders[row, col] == 1:
            first_order_length_m += step_length

    strahler_order = int(np.max(orders[stream_mask])) if stream_count > 0 else None
    total_stream_length_km = total_length_m / 1000.0 if total_length_m > 0 else None
    drainage_density = (total_stream_length_km / area_km2) if total_stream_length_km and area_km2 > 0 else None
    stream_frequency = (source_count / area_km2) if area_km2 > 0 else None
    first_order_fraction = (first_order_length_m / total_length_m) if total_length_m > 0 else None

    return {
        "streamExtractionThresholdKm2": _round(threshold, 6),
        "streamCellCount": stream_count,
        "streamFrequencyPerKm2": _round(stream_frequency, 6),
        "drainageDensityKmPerKm2": _round(drainage_density, 6),
        "streamSegmentCount": stream_segment_count,
        "junctionCount": junction_count,
        "strahlerOrder": strahler_order,
        "firstOrderLengthFraction": _round(first_order_fraction, 6),
        "totalStreamLengthKm": _round(total_stream_length_km, 6),
    }


def _connected_component_count(mask: np.ndarray, *, min_pixels: int = 1) -> int:
    visited = np.zeros(mask.shape, dtype=np.uint8)
    count = 0
    for row in range(mask.shape[0]):
        for col in range(mask.shape[1]):
            if not mask[row, col] or visited[row, col] == 1:
                continue
            size = 0
            stack = [(row, col)]
            visited[row, col] = 1
            while stack:
                cur_row, cur_col = stack.pop()
                size += 1
                for d_row in (-1, 0, 1):
                    for d_col in (-1, 0, 1):
                        if d_row == 0 and d_col == 0:
                            continue
                        n_row = cur_row + d_row
                        n_col = cur_col + d_col
                        if n_row < 0 or n_row >= mask.shape[0] or n_col < 0 or n_col >= mask.shape[1]:
                            continue
                        if not mask[n_row, n_col] or visited[n_row, n_col] == 1:
                            continue
                        visited[n_row, n_col] = 1
                        stack.append((n_row, n_col))
            if size >= min_pixels:
                count += 1
    return count


def _worldcover_metrics(
    *,
    worldcover_path: str,
    mask: np.ndarray,
    reference_transform: Affine,
    reference_crs: Any,
    width: int,
    height: int,
) -> dict[str, Any]:
    with rasterio.open(worldcover_path) as src:
        with WarpedVRT(
            src,
            crs=reference_crs,
            transform=reference_transform,
            width=width,
            height=height,
            resampling=Resampling.nearest,
        ) as vrt:
            worldcover = vrt.read(1, masked=True).filled(0).astype(np.int16)

    valid = mask & (worldcover > 0)
    valid_count = int(np.count_nonzero(valid))
    if valid_count == 0:
        return {
            "landCoverValidFraction": 0.0,
            "forestFraction": None,
            "shrubFraction": None,
            "grassFraction": None,
            "croplandFraction": None,
            "urbanFraction": None,
            "bareRockFraction": None,
            "snowIceFraction": None,
            "permanentWaterFraction": None,
            "wetlandFraction": None,
            "mangroveFraction": None,
            "mossLichenFraction": None,
            "waterPatchCount": 0,
            "wetlandPatchCount": 0,
        }

    values = worldcover[valid]
    def frac(code: int) -> float:
        return float(np.count_nonzero(values == code) / valid_count)

    water_mask = valid & (worldcover == 80)
    wetland_mask = valid & (worldcover == 90)
    return {
        "landCoverValidFraction": _round(valid_count / max(int(np.count_nonzero(mask)), 1), 6),
        "forestFraction": _round(frac(10), 6),
        "shrubFraction": _round(frac(20), 6),
        "grassFraction": _round(frac(30), 6),
        "croplandFraction": _round(frac(40), 6),
        "urbanFraction": _round(frac(50), 6),
        "bareRockFraction": _round(frac(60), 6),
        "snowIceFraction": _round(frac(70), 6),
        "permanentWaterFraction": _round(frac(80), 6),
        "wetlandFraction": _round(frac(90), 6),
        "mangroveFraction": _round(frac(95), 6),
        "mossLichenFraction": _round(frac(100), 6),
        "waterPatchCount": _connected_component_count(water_mask, min_pixels=3),
        "wetlandPatchCount": _connected_component_count(wetland_mask, min_pixels=3),
    }


def _soilgrids_metrics(
    *,
    mask: np.ndarray,
    reference_transform: Affine,
    reference_crs: Any,
    width: int,
    height: int,
) -> dict[str, Any]:
    arrays: dict[str, np.ndarray] = {}
    for key, url in SOILGRIDS_LAYERS.items():
        with rasterio.open(url) as src:
            with WarpedVRT(
                src,
                crs=reference_crs,
                transform=reference_transform,
                width=width,
                height=height,
                resampling=Resampling.bilinear,
            ) as vrt:
                arrays[key] = vrt.read(1, masked=True).filled(np.nan).astype(np.float32)

    clay = arrays["clay"]
    sand = arrays["sand"]
    valid = mask & np.isfinite(clay) & np.isfinite(sand)
    valid_count = int(np.count_nonzero(valid))
    if valid_count == 0:
        return {
            "soilValidFraction": 0.0,
            "meanClayTopsoilPct": None,
            "medianClayTopsoilPct": None,
            "p90ClayTopsoilPct": None,
            "meanSandTopsoilPct": None,
            "medianSandTopsoilPct": None,
            "lowPermeabilitySoilFraction": None,
            "highInfiltrationSoilFraction": None,
            "runoffPotentialIndex": None,
        }

    # SoilGrids clay/sand mean values are in g/kg. Convert to percent.
    clay_pct = clay[valid] / 10.0
    sand_pct = sand[valid] / 10.0
    low_perm = np.count_nonzero(clay_pct >= 35.0) / valid_count
    high_infil = np.count_nonzero((sand_pct >= 60.0) & (clay_pct < 20.0)) / valid_count
    runoff_index = np.clip(((clay_pct / 50.0) - (sand_pct / 100.0) + 0.5), 0.0, 1.0)

    return {
        "soilValidFraction": _round(valid_count / max(int(np.count_nonzero(mask)), 1), 6),
        "meanClayTopsoilPct": _round(float(np.nanmean(clay_pct)), 3),
        "medianClayTopsoilPct": _round(float(np.nanmedian(clay_pct)), 3),
        "p90ClayTopsoilPct": _round(float(np.nanpercentile(clay_pct, 90)), 3),
        "meanSandTopsoilPct": _round(float(np.nanmean(sand_pct)), 3),
        "medianSandTopsoilPct": _round(float(np.nanmedian(sand_pct)), 3),
        "lowPermeabilitySoilFraction": _round(float(low_perm), 6),
        "highInfiltrationSoilFraction": _round(float(high_infil), 6),
        "runoffPotentialIndex": _round(float(np.nanmean(runoff_index)), 6),
    }


def _hydrolakes_metrics(
    *,
    hydrolakes_path: str,
    watershed_geometry: dict[str, Any] | None,
    basin_area_km2: float,
) -> dict[str, Any]:
    if watershed_geometry is None:
        return {
            "lakeFraction": None,
            "lakeCount": 0,
            "reservoirCountUpstream": 0,
            "regulatedLakeCountUpstream": 0,
            "damCountUpstream": 0,
            "majorReservoirDamCountUpstream": 0,
            "reservoirAreaUpstreamKm2": None,
            "reservoirAreaFraction": None,
            "reservoirStorageUpstreamMcm": None,
            "largestUpstreamReservoirAreaKm2": None,
            "largestUpstreamReservoirStorageMcm": None,
            "regulatedCatchment": None,
        }
    import shapefile  # type: ignore
    from shapely.geometry import shape as shapely_shape  # type: ignore

    basin = shapely_shape(watershed_geometry)
    bbox = basin.bounds
    total_lake_area_km2 = 0.0
    lake_count = 0
    reservoir_count = 0
    regulated_lake_count = 0
    major_reservoir_dam_count = 0
    reservoir_area_upstream_km2 = 0.0
    reservoir_storage_upstream_mcm = 0.0
    largest_reservoir_area_km2 = 0.0
    largest_reservoir_storage_mcm = 0.0

    reader = shapefile.Reader(hydrolakes_path)
    for shape_record in reader.iterShapeRecords(bbox=bbox):
        lake_geom = shapely_shape(shape_record.shape.__geo_interface__)
        if not lake_geom.is_valid or lake_geom.is_empty:
            continue
        if not basin.intersects(lake_geom):
            continue
        inter = basin.intersection(lake_geom)
        if inter.is_empty:
            continue
        # Geometry is in EPSG:4326; area proxy from geodesic-free polygon area is too wrong.
        # Use lake area attribute as a practical fallback and count intersecting lakes.
        attrs = shape_record.record.as_dict() if hasattr(shape_record.record, "as_dict") else {}
        lake_area = attrs.get("Lake_area") or attrs.get("Lake_area__") or attrs.get("Lake_area_1")
        if lake_area is not None:
            total_lake_area_km2 += float(lake_area)
        lake_count += 1

        lake_type = attrs.get("Lake_type") or attrs.get("Lake_type_") or attrs.get("Lake_type1")
        grand_id = attrs.get("Grand_id") or attrs.get("Grand_id_") or attrs.get("Grand_id1") or 0
        reservoir_volume = attrs.get("Vol_res") or attrs.get("Vol_res__") or attrs.get("Vol_res_1") or 0.0
        if reservoir_volume in (None, ""):
            reservoir_volume = 0.0

        if lake_type in (2, 3):
            regulated_lake_count += 1
            reservoir_count += 1
            if float(grand_id or 0) > 0:
                major_reservoir_dam_count += 1
            lake_area_value = float(lake_area or 0.0)
            reservoir_area_upstream_km2 += lake_area_value
            reservoir_storage_value = float(reservoir_volume or 0.0)
            reservoir_storage_upstream_mcm += reservoir_storage_value
            largest_reservoir_area_km2 = max(largest_reservoir_area_km2, lake_area_value)
            largest_reservoir_storage_mcm = max(largest_reservoir_storage_mcm, reservoir_storage_value)

    lake_fraction = (total_lake_area_km2 / basin_area_km2) if basin_area_km2 > 0 else None
    reservoir_fraction = (reservoir_area_upstream_km2 / basin_area_km2) if basin_area_km2 > 0 else None
    return {
        "lakeFraction": _round(lake_fraction, 6),
        "lakeCount": lake_count,
        "reservoirCountUpstream": reservoir_count,
        "regulatedLakeCountUpstream": regulated_lake_count,
        "damCountUpstream": major_reservoir_dam_count,
        "majorReservoirDamCountUpstream": major_reservoir_dam_count,
        "reservoirAreaUpstreamKm2": _round(reservoir_area_upstream_km2, 6),
        "reservoirAreaFraction": _round(reservoir_fraction, 6),
        "reservoirStorageUpstreamMcm": _round(reservoir_storage_upstream_mcm, 6),
        "largestUpstreamReservoirAreaKm2": _round(largest_reservoir_area_km2 if reservoir_count > 0 else None, 6),
        "largestUpstreamReservoirStorageMcm": _round(largest_reservoir_storage_mcm if reservoir_count > 0 else None, 6),
        "regulatedCatchment": reservoir_count > 0,
    }


def _record_value(attrs: dict[str, Any], *candidates: str) -> Any:
    normalized = {"".join(ch.lower() for ch in key if ch.isalnum()): value for key, value in attrs.items()}
    for candidate in candidates:
        key = "".join(ch.lower() for ch in candidate if ch.isalnum())
        if key in normalized:
            return normalized[key]
    return None


def _gdw_regulation_metrics(
    *,
    barriers_path: str,
    reservoirs_path: str,
    watershed_geometry: dict[str, Any] | None,
    basin_area_km2: float,
) -> dict[str, Any]:
    if watershed_geometry is None:
        return {
            "gdwBarrierCountUpstream": 0,
            "gdwReservoirCountUpstream": 0,
            "gdwHydropowerBarrierCountUpstream": 0,
            "gdwReservoirAreaUpstreamKm2": None,
            "gdwReservoirStorageUpstreamMcm": None,
            "gdwLargestReservoirStorageMcm": None,
            "gdwLargestReservoirAreaKm2": None,
            "gdwMaxUpstreamDorPct": None,
            "gdwNewestUpstreamDamYear": None,
            "gdwMaxDamHeightM": None,
            "gdwRegulatedCatchment": None,
        }

    import shapefile  # type: ignore
    from shapely.geometry import shape as shapely_shape  # type: ignore

    basin = shapely_shape(watershed_geometry)
    bbox = basin.bounds

    barrier_count = 0
    hydropower_barrier_count = 0
    max_dor = 0.0
    newest_year = None
    max_dam_height = 0.0

    barriers_reader = shapefile.Reader(barriers_path)
    for shape_record in barriers_reader.iterShapeRecords(bbox=bbox):
        barrier_geom = shapely_shape(shape_record.shape.__geo_interface__)
        if barrier_geom.is_empty or not basin.intersects(barrier_geom):
            continue
        attrs = shape_record.record.as_dict() if hasattr(shape_record.record, "as_dict") else {}
        barrier_count += 1
        dor = _record_value(attrs, "Dor_pc", "DOR_PC")
        if dor not in (None, ""):
            max_dor = max(max_dor, float(dor))
        main_use = _record_value(attrs, "Main_use", "MAIN_USE")
        use_elec = _record_value(attrs, "Use_elec", "USE_ELEC")
        if (isinstance(main_use, str) and "hydro" in main_use.lower()) or (isinstance(use_elec, str) and use_elec.strip()):
            hydropower_barrier_count += 1
        year_dam = _record_value(attrs, "Year_dam", "YEAR_DAM")
        if year_dam not in (None, ""):
            try:
                year_value = int(float(year_dam))
                if newest_year is None or year_value > newest_year:
                    newest_year = year_value
            except ValueError:
                pass
        dam_height = _record_value(attrs, "Dam_hgt_m", "DAM_HGT_M")
        if dam_height not in (None, ""):
            max_dam_height = max(max_dam_height, float(dam_height))

    reservoir_count = 0
    reservoir_area = 0.0
    reservoir_storage = 0.0
    largest_storage = 0.0
    largest_area = 0.0

    reservoirs_reader = shapefile.Reader(reservoirs_path)
    for shape_record in reservoirs_reader.iterShapeRecords(bbox=bbox):
        reservoir_geom = shapely_shape(shape_record.shape.__geo_interface__)
        if reservoir_geom.is_empty or not basin.intersects(reservoir_geom):
            continue
        inter = basin.intersection(reservoir_geom)
        if inter.is_empty:
            continue
        attrs = shape_record.record.as_dict() if hasattr(shape_record.record, "as_dict") else {}
        reservoir_count += 1
        area_km2 = _record_value(attrs, "Area_skm", "AREA_SKM")
        if area_km2 not in (None, ""):
            area_value = float(area_km2)
            reservoir_area += area_value
            largest_area = max(largest_area, area_value)
        storage_mcm = _record_value(attrs, "Cap_mcm", "CAP_MCM")
        if storage_mcm not in (None, ""):
            storage_value = float(storage_mcm)
            reservoir_storage += storage_value
            largest_storage = max(largest_storage, storage_value)
        dor = _record_value(attrs, "Dor_pc", "DOR_PC")
        if dor not in (None, ""):
            max_dor = max(max_dor, float(dor))

    return {
        "gdwBarrierCountUpstream": barrier_count,
        "gdwReservoirCountUpstream": reservoir_count,
        "gdwHydropowerBarrierCountUpstream": hydropower_barrier_count,
        "gdwReservoirAreaUpstreamKm2": _round(reservoir_area, 6),
        "gdwReservoirStorageUpstreamMcm": _round(reservoir_storage, 6),
        "gdwLargestReservoirStorageMcm": _round(largest_storage if reservoir_count > 0 else None, 6),
        "gdwLargestReservoirAreaKm2": _round(largest_area if reservoir_count > 0 else None, 6),
        "gdwMaxUpstreamDorPct": _round(max_dor if max_dor > 0 else None, 6),
        "gdwNewestUpstreamDamYear": newest_year,
        "gdwMaxDamHeightM": _round(max_dam_height if max_dam_height > 0 else None, 3),
        "gdwRegulatedCatchment": barrier_count > 0 or reservoir_count > 0,
    }


def _glim_metrics(
    *,
    glim_path: str,
    mask: np.ndarray,
    reference_transform: Affine,
    reference_crs: Any,
    width: int,
    height: int,
) -> dict[str, Any]:
    with rasterio.open(glim_path) as src:
        with WarpedVRT(
            src,
            crs=reference_crs,
            transform=reference_transform,
            width=width,
            height=height,
            resampling=Resampling.nearest,
        ) as vrt:
            geology = vrt.read(1, masked=True).filled(-9999).astype(np.int16)

    valid = mask & (geology > 0)
    valid_count = int(np.count_nonzero(valid))
    if valid_count == 0:
        return {
            "geologyValidFraction": 0.0,
            "carbonateFraction": None,
            "unconsolidatedFraction": None,
            "crystallineFraction": None,
            "volcanicFraction": None,
            "evaporiteFraction": None,
            "dominantLithologyCode": None,
            "karstIndicator": None,
        }

    values = geology[valid]
    def frac(codes: list[int]) -> float:
        return float(np.count_nonzero(np.isin(values, codes)) / valid_count)

    carbonate = frac([GLIM_CLASS_CODES["sc"]])
    unconsolidated = frac([GLIM_CLASS_CODES["su"]])
    crystalline = frac([GLIM_CLASS_CODES["mt"], GLIM_CLASS_CODES["pa"], GLIM_CLASS_CODES["pb"], GLIM_CLASS_CODES["pi"]])
    volcanic = frac([GLIM_CLASS_CODES["va"], GLIM_CLASS_CODES["vi"], GLIM_CLASS_CODES["vb"], GLIM_CLASS_CODES["py"], GLIM_CLASS_CODES["ig"]])
    evaporite = frac([GLIM_CLASS_CODES["ev"]])
    unique, counts = np.unique(values, return_counts=True)
    dominant_code = int(unique[int(np.argmax(counts))]) if unique.size else None
    code_to_name = {value: key for key, value in GLIM_CLASS_CODES.items()}
    karst = 1.0 if carbonate >= 0.3 else (0.5 if carbonate >= 0.1 else 0.0)

    return {
        "geologyValidFraction": _round(valid_count / max(int(np.count_nonzero(mask)), 1), 6),
        "carbonateFraction": _round(carbonate, 6),
        "unconsolidatedFraction": _round(unconsolidated, 6),
        "crystallineFraction": _round(crystalline, 6),
        "volcanicFraction": _round(volcanic, 6),
        "evaporiteFraction": _round(evaporite, 6),
        "dominantLithologyCode": code_to_name.get(dominant_code, str(dominant_code) if dominant_code is not None else None),
        "karstIndicator": _round(karst, 6),
    }


def _ghsl_built_metrics(
    *,
    ghsl_path: str,
    mask: np.ndarray,
    reference_transform: Affine,
    reference_crs: Any,
    width: int,
    height: int,
    cell_area_m2: float,
) -> dict[str, Any]:
    with rasterio.open(ghsl_path) as src:
        with WarpedVRT(
            src,
            crs=reference_crs,
            transform=reference_transform,
            width=width,
            height=height,
            resampling=Resampling.bilinear,
        ) as vrt:
            built = vrt.read(1, masked=True).filled(0).astype(np.float32)

    valid = mask & np.isfinite(built) & (built >= 0)
    valid_count = int(np.count_nonzero(valid))
    if valid_count == 0:
        return {
            "imperviousValidFraction": 0.0,
            "imperviousBuiltSurfaceFraction": None,
            "meanBuiltSurfaceM2PerCell": None,
        }

    built_values = built[valid]
    total_built_m2 = float(np.nansum(built_values))
    basin_built_fraction = total_built_m2 / max(valid_count * cell_area_m2, 1.0)
    mean_built_surface = float(np.nanmean(built_values))
    return {
        "imperviousValidFraction": _round(valid_count / max(int(np.count_nonzero(mask)), 1), 6),
        "imperviousBuiltSurfaceFraction": _round(max(0.0, min(1.0, basin_built_fraction)), 6),
        "meanBuiltSurfaceM2PerCell": _round(mean_built_surface, 3),
    }


def _safe_stat(values: np.ndarray, fn: str) -> float | None:
    if values.size == 0:
        return None
    result = getattr(np, fn)(values)
    if np.isnan(result):
        return None
    return float(result)


def _round(value: float | None, digits: int = 6) -> float | None:
    if value is None:
        return None
    return round(value, digits)


def compute_watershed_descriptors(
    *,
    dem_path: str,
    uparea_path: str,
    flowdir_path: str,
    worldcover_path: str | None,
    ghsl_built_path: str | None,
    hydrolakes_path: str | None,
    gdw_barriers_path: str | None,
    gdw_reservoirs_path: str | None,
    glim_path: str | None,
    watershed_geometry: dict[str, Any] | None,
    mask_data: dict[str, Any],
    selected_candidate: dict[str, Any],
) -> dict[str, Any]:
    with rasterio.open(dem_path) as src:
        dem = src.read(1, masked=True).filled(np.nan).astype(np.float32)
        mask = mask_data["mask"]
        if dem.shape != mask.shape:
            raise SystemExit(f"DEM/mask shape mismatch for descriptors: {dem.shape} vs {mask.shape}")

        valid = mask & np.isfinite(dem)
        mask_count = int(np.count_nonzero(mask))
        valid_count = int(np.count_nonzero(valid))
        nodata_fraction = 1.0 if mask_count == 0 else max(0.0, 1.0 - valid_count / mask_count)
        if valid_count == 0:
            return {
                "descriptorStatus": "no_valid_dem_cells",
                "watershedCellCount": mask_count,
                "watershedNoDataFraction": _round(nodata_fraction),
            }

        values = dem[valid]
        mean_lat = float(np.nanmean([selected_candidate["evaluation"]["snapped_latitude"], selected_candidate["latitude"]]))
        x_res_m, y_res_m = _resolution_m(src.transform, src.crs, mean_lat)
        cell_area_m2 = max(x_res_m * y_res_m, 1.0)
        area_km2 = valid_count * cell_area_m2 / 1_000_000.0

        slope_deg, aspect_deg = _slope_aspect(dem, x_res_m, y_res_m)
        tri = _tri(dem)
        valid_slope = slope_deg[valid & np.isfinite(slope_deg)]
        valid_aspect = aspect_deg[valid & np.isfinite(aspect_deg)]
        valid_tri = tri[valid & np.isfinite(tri)]

        path_lengths = mask_data["pathLengthsM"]
        valid_path = path_lengths[mask & np.isfinite(path_lengths)]
        max_flow_path_m = _safe_stat(valid_path, "max") or 0.0
        max_flow_path_km = max_flow_path_m / 1000.0 if max_flow_path_m > 0 else None

        outlet_elevation = selected_candidate["evaluation"].get("elevation_m")
        main_channel_slope_percent = None
        if max_flow_path_m > 0 and outlet_elevation is not None:
            max_index = np.nanargmax(path_lengths)
            row, col = np.unravel_index(max_index, path_lengths.shape)
            start_elevation = float(dem[row, col]) if np.isfinite(dem[row, col]) else None
            if start_elevation is not None:
                rise = max(start_elevation - float(outlet_elevation), 0.0)
                main_channel_slope_percent = (rise / max_flow_path_m) * 100.0

        relief_m = (_safe_stat(values, "max") or 0.0) - (_safe_stat(values, "min") or 0.0)
        kirpich = None
        if max_flow_path_m > 0 and main_channel_slope_percent and main_channel_slope_percent > 0:
            slope_m_per_m = main_channel_slope_percent / 100.0
            kirpich = 0.01947 * (max_flow_path_m ** 0.77) * (slope_m_per_m ** -0.385)
        giandotti = None
        if area_km2 > 0 and max_flow_path_km and relief_m > 0:
            giandotti = ((4 * math.sqrt(area_km2)) + (1.5 * max_flow_path_km)) / (0.8 * math.sqrt(relief_m)) * 60.0

        aspect_n = np.count_nonzero(((valid_aspect >= 315) | (valid_aspect < 45))) / max(valid_aspect.size, 1)
        aspect_e = np.count_nonzero((valid_aspect >= 45) & (valid_aspect < 135)) / max(valid_aspect.size, 1)
        aspect_s = np.count_nonzero((valid_aspect >= 135) & (valid_aspect < 225)) / max(valid_aspect.size, 1)
        aspect_w = np.count_nonzero((valid_aspect >= 225) & (valid_aspect < 315)) / max(valid_aspect.size, 1)

        snap_distance = float(selected_candidate["evaluation"].get("snap_distance_m") or 0.0)
        dem_resolution = math.sqrt(x_res_m * y_res_m)
        verdict = (selected_candidate.get("analysis") or {}).get("selectionVerdict") or "unknown"
        quality = 1.0
        quality -= min(nodata_fraction * 0.7, 0.7)
        quality -= min(snap_distance / 200.0 * 0.25, 0.25)
        quality -= 0.15 if dem_resolution > 20 else (0.08 if dem_resolution > 10 else 0.0)
        quality -= {"off_channel": 0.5, "uncertain": 0.2, "possible_proxy": 0.1}.get(verdict, 0.0)
        quality = max(0.0, min(1.0, quality))

    with rasterio.open(uparea_path) as upa_src, rasterio.open(flowdir_path) as flow_src:
        uparea = upa_src.read(1, masked=True).filled(np.nan).astype(np.float32)
        flow = flow_src.read(1, masked=True).filled(0).astype(np.int16)
        if uparea.shape != mask.shape or flow.shape != mask.shape:
            raise SystemExit(f"Hydrology raster shape mismatch for descriptors: upa={uparea.shape}, flow={flow.shape}, mask={mask.shape}")
        network_metrics = _compute_network_metrics(
            mask=mask,
            path_lengths=path_lengths,
            flow=flow,
            uparea=uparea,
            area_km2=area_km2,
            dem_resolution_m=dem_resolution,
        )

    landcover_metrics = {}
    if worldcover_path:
        landcover_metrics = _worldcover_metrics(
            worldcover_path=worldcover_path,
            mask=mask,
            reference_transform=mask_data["transform"],
            reference_crs=mask_data["crs"],
            width=mask.shape[1],
            height=mask.shape[0],
        )

    try:
        soil_metrics = _soilgrids_metrics(
            mask=mask,
            reference_transform=mask_data["transform"],
            reference_crs=mask_data["crs"],
            width=mask.shape[1],
            height=mask.shape[0],
        )
        soil_metrics["soilDescriptorStatus"] = "ok"
    except Exception as exc:
        soil_metrics = {
            "soilDescriptorStatus": f"error:{type(exc).__name__}",
            "soilValidFraction": None,
            "meanClayTopsoilPct": None,
            "medianClayTopsoilPct": None,
            "p90ClayTopsoilPct": None,
            "meanSandTopsoilPct": None,
            "medianSandTopsoilPct": None,
            "lowPermeabilitySoilFraction": None,
            "highInfiltrationSoilFraction": None,
            "runoffPotentialIndex": None,
        }

    try:
        hydrolakes_metrics = _hydrolakes_metrics(
            hydrolakes_path=hydrolakes_path,
            watershed_geometry=watershed_geometry,
            basin_area_km2=area_km2,
        ) if hydrolakes_path else {"lakeFraction": None, "lakeCount": 0}
        hydrolakes_metrics["hydroLakesStatus"] = "ok" if hydrolakes_path else "skipped"
    except Exception as exc:
        hydrolakes_metrics = {
            "hydroLakesStatus": f"error:{type(exc).__name__}",
            "lakeFraction": None,
            "lakeCount": None,
        }

    try:
        gdw_metrics = _gdw_regulation_metrics(
            barriers_path=gdw_barriers_path,
            reservoirs_path=gdw_reservoirs_path,
            watershed_geometry=watershed_geometry,
            basin_area_km2=area_km2,
        ) if gdw_barriers_path and gdw_reservoirs_path else {
            "gdwBarrierCountUpstream": None,
            "gdwReservoirCountUpstream": None,
            "gdwHydropowerBarrierCountUpstream": None,
            "gdwReservoirAreaUpstreamKm2": None,
            "gdwReservoirStorageUpstreamMcm": None,
            "gdwLargestReservoirStorageMcm": None,
            "gdwLargestReservoirAreaKm2": None,
            "gdwMaxUpstreamDorPct": None,
            "gdwNewestUpstreamDamYear": None,
            "gdwMaxDamHeightM": None,
            "gdwRegulatedCatchment": None,
        }
        gdw_metrics["gdwStatus"] = "ok" if gdw_barriers_path and gdw_reservoirs_path else "skipped"
    except Exception as exc:
        gdw_metrics = {
            "gdwStatus": f"error:{type(exc).__name__}",
            "gdwBarrierCountUpstream": None,
            "gdwReservoirCountUpstream": None,
            "gdwHydropowerBarrierCountUpstream": None,
            "gdwReservoirAreaUpstreamKm2": None,
            "gdwReservoirStorageUpstreamMcm": None,
            "gdwLargestReservoirStorageMcm": None,
            "gdwLargestReservoirAreaKm2": None,
            "gdwMaxUpstreamDorPct": None,
            "gdwNewestUpstreamDamYear": None,
            "gdwMaxDamHeightM": None,
            "gdwRegulatedCatchment": None,
        }

    try:
        glim_metrics = _glim_metrics(
            glim_path=glim_path,
            mask=mask,
            reference_transform=mask_data["transform"],
            reference_crs=mask_data["crs"],
            width=mask.shape[1],
            height=mask.shape[0],
        ) if glim_path else {
            "geologyValidFraction": None,
            "carbonateFraction": None,
            "unconsolidatedFraction": None,
            "crystallineFraction": None,
            "volcanicFraction": None,
            "evaporiteFraction": None,
            "dominantLithologyCode": None,
            "karstIndicator": None,
        }
        glim_metrics["geologyDescriptorStatus"] = "ok" if glim_path else "skipped"
    except Exception as exc:
        glim_metrics = {
            "geologyDescriptorStatus": f"error:{type(exc).__name__}",
            "geologyValidFraction": None,
            "carbonateFraction": None,
            "unconsolidatedFraction": None,
            "crystallineFraction": None,
            "volcanicFraction": None,
            "evaporiteFraction": None,
            "dominantLithologyCode": None,
            "karstIndicator": None,
        }

    try:
        ghsl_metrics = _ghsl_built_metrics(
            ghsl_path=ghsl_built_path,
            mask=mask,
            reference_transform=mask_data["transform"],
            reference_crs=mask_data["crs"],
            width=mask.shape[1],
            height=mask.shape[0],
            cell_area_m2=cell_area_m2,
        ) if ghsl_built_path else {
            "imperviousValidFraction": None,
            "imperviousBuiltSurfaceFraction": None,
            "meanBuiltSurfaceM2PerCell": None,
        }
        ghsl_metrics["imperviousDescriptorStatus"] = "ok" if ghsl_built_path else "skipped"
    except Exception as exc:
        ghsl_metrics = {
            "imperviousDescriptorStatus": f"error:{type(exc).__name__}",
            "imperviousValidFraction": None,
            "imperviousBuiltSurfaceFraction": None,
            "meanBuiltSurfaceM2PerCell": None,
        }

    return {
            "descriptorStatus": "ok",
            "watershedCellCount": mask_count,
            "watershedValidCellCount": valid_count,
            "watershedNoDataFraction": _round(nodata_fraction),
            "basinAreaRasterKm2": _round(area_km2),
            "basinMinElevationM": _round(_safe_stat(values, "min"), 3),
            "basinMeanElevationM": _round(_safe_stat(values, "mean"), 3),
            "basinMedianElevationM": _round(_safe_stat(values, "median"), 3),
            "basinMaxElevationM": _round(_safe_stat(values, "max"), 3),
            "basinElevationStdM": _round(_safe_stat(values, "std"), 3),
            "basinReliefM": _round(relief_m, 3),
            "fractionAbove1500m": _round(float(np.count_nonzero(values >= 1500.0) / values.size), 6),
            "fractionAbove2000m": _round(float(np.count_nonzero(values >= 2000.0) / values.size), 6),
            "fractionAbove2500m": _round(float(np.count_nonzero(values >= 2500.0) / values.size), 6),
            "meanSlopeDeg": _round(_safe_stat(valid_slope, "mean"), 3),
            "medianSlopeDeg": _round(_safe_stat(valid_slope, "median"), 3),
            "p90SlopeDeg": _round(float(np.nanpercentile(valid_slope, 90)) if valid_slope.size else None, 3),
            "maxSlopeDeg": _round(_safe_stat(valid_slope, "max"), 3),
            "fractionSlopeOver10Deg": _round(float(np.count_nonzero(valid_slope >= 10.0) / max(valid_slope.size, 1)), 6),
            "fractionSlopeOver20Deg": _round(float(np.count_nonzero(valid_slope >= 20.0) / max(valid_slope.size, 1)), 6),
            "fractionSlopeOver30Deg": _round(float(np.count_nonzero(valid_slope >= 30.0) / max(valid_slope.size, 1)), 6),
            "aspectNorthFraction": _round(aspect_n, 6),
            "aspectEastFraction": _round(aspect_e, 6),
            "aspectSouthFraction": _round(aspect_s, 6),
            "aspectWestFraction": _round(aspect_w, 6),
            "terrainRuggednessIndex": _round(_safe_stat(valid_tri, "mean"), 6),
            "maxFlowPathLengthKm": _round(max_flow_path_km, 6),
            "mainFlowLengthKm": _round(max_flow_path_km, 6),
            "mainChannelSlopePercent": _round(main_channel_slope_percent, 6),
            "timeOfConcentrationKirpichMin": _round(kirpich, 6),
            "timeOfConcentrationGiandottiMin": _round(giandotti, 6),
            "meltonRuggedness": _round((relief_m / 1000.0) / math.sqrt(area_km2), 6) if area_km2 > 0 else None,
            "outletElevationM": _round(float(outlet_elevation) if outlet_elevation is not None else None, 3),
            "outletSnapDistanceM": _round(snap_distance, 3),
            "demResolutionM": _round(dem_resolution, 3),
            "watershedQualityScore": _round(quality, 6),
            **network_metrics,
            **landcover_metrics,
            **soil_metrics,
            **hydrolakes_metrics,
            **gdw_metrics,
            **glim_metrics,
            **ghsl_metrics,
            "imperviousProxyFraction": landcover_metrics.get("urbanFraction"),
        }
