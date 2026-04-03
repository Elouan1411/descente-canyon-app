from __future__ import annotations

import math
import threading
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
    "subsoilClay": "https://files.isric.org/soilgrids/latest/data/clay/clay_30-60cm_mean.vrt",
    "subsoilSand": "https://files.isric.org/soilgrids/latest/data/sand/sand_30-60cm_mean.vrt",
    "coarseFragments": "https://files.isric.org/soilgrids/latest/data/cfvo/cfvo_0-5cm_mean.vrt",
}

SOIL_AUXILIARY_LAYERS = {
    "bedrockDepth": "https://files.isric.org/soilgrids/former/2017-03-10/data/BDRICM_M_250m_ll.tif",
    "soilDepth": "https://files.isric.org/soilgrids/former/2017-03-10/data/BDTICM_M_250m_ll.tif",
    "availableWaterCapacity": "https://zenodo.org/api/records/2629149/files/sol_available.water.capacity_usda.mm_m_250m_0..200cm_1950..2017_v0.1.tif/content",
    "saturatedHydraulicConductivity": "https://zenodo.org/api/records/3935359/files/Global_Ksat_1Km_s0....0cm_v1.0.tif/content",
}

WORLDCLIM_MONTHS = tuple(range(1, 13))
MONTH_DAY_OF_YEAR = (15, 45, 74, 105, 135, 166, 196, 227, 258, 288, 319, 349)
MONTH_DAYS = (31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

_SHARED_RASTER_CACHE = threading.local()

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


def _open_shared_raster(path: str):
    cache = getattr(_SHARED_RASTER_CACHE, "datasets", None)
    if cache is None:
        cache = {}
        _SHARED_RASTER_CACHE.datasets = cache
    dataset = cache.get(path)
    if dataset is None:
        dataset = rasterio.open(path, sharing=True)
        cache[path] = dataset
    return dataset


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


def _step_lengths_for_transform(transform_affine: Affine, crs: Any, lat_hint: float) -> dict[tuple[int, int], float]:
    x_res_m, y_res_m = _resolution_m(transform_affine, crs, lat_hint)
    return _step_lengths_for_grid(x_res_m, y_res_m)


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
        step_lengths = _step_lengths_for_transform(src.transform, raster_crs, snapped_latitude)

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
                        step = step_lengths.get(offset, 0.0)
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
    total = np.zeros(dem.shape, dtype=np.float32)
    counts = np.zeros(dem.shape, dtype=np.uint8)
    for d_row in (-1, 0, 1):
        for d_col in (-1, 0, 1):
            if d_row == 0 and d_col == 0:
                continue
            neighbor = padded[1 + d_row : 1 + d_row + dem.shape[0], 1 + d_col : 1 + d_col + dem.shape[1]]
            diff = np.abs(neighbor - center)
            valid = np.isfinite(diff)
            total[valid] += diff[valid]
            counts[valid] += 1
    result = np.full(dem.shape, np.nan, dtype=np.float32)
    valid_counts = counts > 0
    result[valid_counts] = total[valid_counts] / counts[valid_counts]
    return result


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


def _stream_mask_for_grid(mask: np.ndarray, uparea: np.ndarray, dem_resolution_m: float) -> tuple[float, np.ndarray]:
    threshold = _network_threshold_km2(dem_resolution_m)
    stream_mask = mask & np.isfinite(uparea) & (uparea >= threshold)
    thresholds = [threshold, threshold / 2.0, threshold / 5.0, 0.0]
    for candidate_threshold in thresholds:
        stream_mask = mask & np.isfinite(uparea) & (uparea >= candidate_threshold)
        if np.count_nonzero(stream_mask) > 0:
            threshold = candidate_threshold
            break
    return threshold, stream_mask


def _compute_network_metrics(
    *,
    mask: np.ndarray,
    path_lengths: np.ndarray,
    flow: np.ndarray,
    uparea: np.ndarray,
    area_km2: float,
    dem_resolution_m: float,
) -> dict[str, Any]:
    threshold, stream_mask = _stream_mask_for_grid(mask, uparea, dem_resolution_m)

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


def _connected_component_sizes(mask: np.ndarray, *, min_pixels: int = 1) -> list[int]:
    visited = np.zeros(mask.shape, dtype=np.uint8)
    sizes: list[int] = []
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
                sizes.append(size)
    return sizes


def _connected_component_count(mask: np.ndarray, *, min_pixels: int = 1) -> int:
    return len(_connected_component_sizes(mask, min_pixels=min_pixels))


def _largest_component_fraction(mask: np.ndarray, *, min_pixels: int = 1) -> float | None:
    sizes = _connected_component_sizes(mask, min_pixels=min_pixels)
    total = sum(sizes)
    if total <= 0:
        return None
    return float(max(sizes) / total)


def _mid_month_ra_mm_per_day(latitude_deg: float, day_of_year: int) -> float:
    g_sc = 0.0820
    phi = math.radians(latitude_deg)
    d_r = 1.0 + 0.033 * math.cos((2.0 * math.pi / 365.0) * day_of_year)
    delta = 0.409 * math.sin((2.0 * math.pi / 365.0) * day_of_year - 1.39)
    cos_arg = -math.tan(phi) * math.tan(delta)
    cos_arg = max(-1.0, min(1.0, cos_arg))
    omega_s = math.acos(cos_arg)
    ra_mj_m2_day = (
        (24.0 * 60.0 / math.pi)
        * g_sc
        * d_r
        * (
            omega_s * math.sin(phi) * math.sin(delta)
            + math.cos(phi) * math.cos(delta) * math.sin(omega_s)
        )
    )
    return ra_mj_m2_day * 0.408


def _worldclim_metrics(
    *,
    monthly_paths: dict[str, list[str]],
    mask: np.ndarray,
    reference_transform: Affine,
    reference_crs: Any,
    width: int,
    height: int,
) -> dict[str, Any]:
    required = {"prec", "tavg", "tmin", "tmax"}
    if not required.issubset(monthly_paths):
        raise ValueError(f"Missing climate layers: {sorted(required - set(monthly_paths))}")

    monthly_means: dict[str, list[float]] = {key: [] for key in required}
    sampled_cells = np.argwhere(mask)[:2000]
    sample_xs = []
    sample_ys = []
    for row, col in sampled_cells:
        x, y = _cell_center(reference_transform, int(row), int(col))
        sample_xs.append(x)
        sample_ys.append(y)
    _, sample_lats = transform_coords(reference_crs, "EPSG:4326", sample_xs, sample_ys)
    mean_lat = float(np.nanmean(sample_lats)) if sample_lats else 45.0

    for key in required:
        paths = monthly_paths[key]
        if len(paths) != 12:
            raise ValueError(f"Expected 12 monthly rasters for {key}, got {len(paths)}")
        for path in paths:
            src = _open_shared_raster(path)
            with WarpedVRT(
                src,
                crs=reference_crs,
                transform=reference_transform,
                width=width,
                height=height,
                resampling=Resampling.bilinear,
            ) as vrt:
                data = vrt.read(1, masked=True)
                values = np.where(mask & ~np.ma.getmaskarray(data), data.data.astype(np.float32), np.nan)
                monthly_means[key].append(float(np.nanmean(values)))

    monthly_prec = np.array(monthly_means["prec"], dtype=np.float64)
    monthly_tavg = np.array(monthly_means["tavg"], dtype=np.float64)
    monthly_tmin = np.array(monthly_means["tmin"], dtype=np.float64)
    monthly_tmax = np.array(monthly_means["tmax"], dtype=np.float64)

    annual_precip_mm = float(np.nansum(monthly_prec))
    mean_annual_temp_c = float(np.nanmean(monthly_tavg))
    precip_mean = float(np.nanmean(monthly_prec))
    precip_seasonality = float(np.nanstd(monthly_prec) / precip_mean) if precip_mean > 0 else None
    winter_months = (11, 0, 1) if mean_lat >= 0 else (5, 6, 7)
    winter_temp_c = float(np.nanmean(monthly_tavg[list(winter_months)]))
    continentality = float(np.nanmax(monthly_tavg) - np.nanmin(monthly_tavg))
    oceanicity = 1.0 / (1.0 + max(continentality, 0.0))

    monthly_pet_mm: list[float] = []
    snow_monthly_mm = 0.0
    for index, day_of_year in enumerate(MONTH_DAY_OF_YEAR):
        delta_t = max(monthly_tmax[index] - monthly_tmin[index], 0.0)
        if monthly_tavg[index] <= -20.0:
            pet_mm_day = 0.0
        else:
            pet_mm_day = 0.0023 * _mid_month_ra_mm_per_day(mean_lat, day_of_year) * (monthly_tavg[index] + 17.8) * math.sqrt(delta_t)
        pet_mm_day = max(pet_mm_day, 0.0)
        monthly_pet_mm.append(pet_mm_day * MONTH_DAYS[index])
        snow_fraction = max(0.0, min(1.0, (2.0 - monthly_tavg[index]) / 4.0))
        snow_monthly_mm += float(monthly_prec[index] * snow_fraction)

    annual_pet_mm = float(np.nansum(monthly_pet_mm))
    aridity_index = (annual_precip_mm / annual_pet_mm) if annual_pet_mm > 0 else None
    snow_fraction_climatology = (snow_monthly_mm / annual_precip_mm) if annual_precip_mm > 0 else None

    return {
        "meanAnnualPrecipMm": _round(annual_precip_mm, 3),
        "meanMonthlyPrecipSeasonality": _round(precip_seasonality, 6),
        "meanAnnualTemperatureC": _round(mean_annual_temp_c, 3),
        "meanWinterTemperatureC": _round(winter_temp_c, 3),
        "meanSnowFractionClimatology": _round(snow_fraction_climatology, 6),
        "potentialEvapotranspiration": _round(annual_pet_mm, 3),
        "aridityIndex": _round(aridity_index, 6),
        "continentalityProxy": _round(continentality, 3),
        "oceanicityProxy": _round(oceanicity, 6),
    }


def _compute_curvatures(dem: np.ndarray, x_res_m: float, y_res_m: float) -> tuple[np.ndarray, np.ndarray]:
    dz_dy, dz_dx = np.gradient(dem, y_res_m, x_res_m)
    d2z_dx2 = np.gradient(dz_dx, x_res_m, axis=1)
    d2z_dy2 = np.gradient(dz_dy, y_res_m, axis=0)
    d2z_dxdy = np.gradient(dz_dx, y_res_m, axis=0)

    p = dz_dx
    q = dz_dy
    r = d2z_dx2
    s = d2z_dxdy
    t = d2z_dy2
    grad_sq = p ** 2 + q ** 2
    safe_grad_sq = np.where(grad_sq <= 1e-12, np.nan, grad_sq)
    safe_surface = np.where(grad_sq <= 1e-12, np.nan, 1.0 + grad_sq)

    plan = ((q ** 2) * r - (2.0 * p * q * s) + (p ** 2) * t) / (safe_grad_sq * np.sqrt(safe_surface))
    profile = ((p ** 2) * r + (2.0 * p * q * s) + (q ** 2) * t) / (safe_grad_sq * np.power(safe_surface, 1.5))
    return plan, profile


def _compute_hand(dem: np.ndarray, mask: np.ndarray, flow: np.ndarray, stream_mask: np.ndarray) -> np.ndarray:
    hand = np.full(dem.shape, np.nan, dtype=np.float32)
    stream_elevation = np.full(dem.shape, np.nan, dtype=np.float32)
    valid_cells = mask & np.isfinite(dem)
    stream_elevation[stream_mask & valid_cells] = dem[stream_mask & valid_cells]
    hand[stream_mask & valid_cells] = 0.0

    for start_row, start_col in np.argwhere(valid_cells & ~stream_mask):
        row = int(start_row)
        col = int(start_col)
        stack: list[tuple[int, int]] = []
        while True:
            if not valid_cells[row, col]:
                target_elevation = np.nan
                break
            if np.isfinite(stream_elevation[row, col]):
                target_elevation = float(stream_elevation[row, col])
                break
            stack.append((row, col))
            direction_code = int(flow[row, col])
            offset = FLOW_DIRECTION_OFFSETS.get(direction_code)
            if offset is None:
                target_elevation = np.nan
                break
            next_row = row + offset[0]
            next_col = col + offset[1]
            if next_row < 0 or next_row >= flow.shape[0] or next_col < 0 or next_col >= flow.shape[1]:
                target_elevation = np.nan
                break
            if any(next_row == existing_row and next_col == existing_col for existing_row, existing_col in stack):
                target_elevation = np.nan
                break
            row = next_row
            col = next_col

        for cell_row, cell_col in reversed(stack):
            stream_elevation[cell_row, cell_col] = target_elevation
            if np.isfinite(target_elevation):
                hand[cell_row, cell_col] = max(float(dem[cell_row, cell_col]) - target_elevation, 0.0)
            else:
                hand[cell_row, cell_col] = np.nan
    return hand


def _local_closed_depression_count(dem: np.ndarray, valid: np.ndarray, *, minimum_drop_m: float = 2.0) -> int:
    if dem.shape[0] < 3 or dem.shape[1] < 3:
        return 0
    center = dem[1:-1, 1:-1]
    center_valid = valid[1:-1, 1:-1] & np.isfinite(center)
    if not np.any(center_valid):
        return 0
    neighbor_stack = []
    valid_stack = []
    for d_row in (-1, 0, 1):
        for d_col in (-1, 0, 1):
            if d_row == 0 and d_col == 0:
                continue
            neighbor_stack.append(dem[1 + d_row : 1 + d_row + center.shape[0], 1 + d_col : 1 + d_col + center.shape[1]])
            valid_stack.append(valid[1 + d_row : 1 + d_row + center.shape[0], 1 + d_col : 1 + d_col + center.shape[1]])
    neighbors = np.stack(neighbor_stack, axis=0)
    neighbors_valid = np.stack(valid_stack, axis=0)
    all_neighbors_valid = np.all(neighbors_valid, axis=0)
    any_neighbors_valid = np.any(neighbors_valid, axis=0)
    neighbor_min = np.where(
        any_neighbors_valid,
        np.min(np.where(neighbors_valid, neighbors, np.inf), axis=0),
        np.nan,
    )
    depressions = center_valid & all_neighbors_valid & np.isfinite(neighbor_min) & ((neighbor_min - center) >= minimum_drop_m)
    return int(np.count_nonzero(depressions))


def _topo_hydrology_metrics(
    *,
    dem: np.ndarray,
    mask: np.ndarray,
    flow: np.ndarray,
    uparea: np.ndarray,
    x_res_m: float,
    y_res_m: float,
    dem_resolution_m: float,
    values: np.ndarray,
) -> tuple[dict[str, Any], np.ndarray, np.ndarray]:
    _, stream_mask = _stream_mask_for_grid(mask, uparea, dem_resolution_m)
    hand = _compute_hand(dem, mask, flow, stream_mask)
    plan_curvature, profile_curvature = _compute_curvatures(dem, x_res_m, y_res_m)

    slope_deg, _ = _slope_aspect(dem, x_res_m, y_res_m)
    slope_tan = np.tan(np.radians(np.clip(slope_deg, 0.1, 89.0)))
    specific_area_m = np.maximum(uparea, 1e-6) * 1_000_000.0 / max(dem_resolution_m, 1.0)
    twi = np.log(np.maximum(specific_area_m / np.maximum(slope_tan, 1e-6), 1e-6))

    valid_hand = hand[mask & np.isfinite(hand)]
    valid_twi = twi[mask & np.isfinite(twi)]
    valid_plan = plan_curvature[mask & np.isfinite(plan_curvature)]
    valid_profile = profile_curvature[mask & np.isfinite(profile_curvature)]
    valid_uparea = uparea[mask & np.isfinite(uparea)]

    relief = (_safe_stat(values, "max") or 0.0) - (_safe_stat(values, "min") or 0.0)
    hypsometric = None
    if relief > 0:
        hypsometric = ((_safe_stat(values, "mean") or 0.0) - (_safe_stat(values, "min") or 0.0)) / relief

    if valid_hand.size > 0:
        valley_floor_threshold_m = float(np.clip(np.nanpercentile(valid_hand, 20), 5.0, 30.0))
        valley_floor_mask = mask & np.isfinite(hand) & (hand <= valley_floor_threshold_m)
    else:
        valley_floor_mask = np.zeros(mask.shape, dtype=bool)
    valley_floor_count = int(np.count_nonzero(valley_floor_mask))
    stream_count = int(np.count_nonzero(stream_mask))
    valley_confinement = None
    channel_confinement = None
    if np.count_nonzero(mask) > 0 and valley_floor_count > 0:
        valley_floor_fraction = valley_floor_count / max(int(np.count_nonzero(mask)), 1)
        valley_confinement = max(0.0, min(1.0, 1.0 - valley_floor_fraction))
        channel_confinement = stream_count / valley_floor_count

    return (
        {
            "hypsometricIntegral": _round(hypsometric, 6),
            "topographicWetnessIndexMean": _round(_safe_stat(valid_twi, "mean"), 6),
            "topographicWetnessIndexP90": _round(float(np.nanpercentile(valid_twi, 90)) if valid_twi.size else None, 6),
            "handMeanM": _round(_safe_stat(valid_hand, "mean"), 3),
            "handMedianM": _round(_safe_stat(valid_hand, "median"), 3),
            "handP90M": _round(float(np.nanpercentile(valid_hand, 90)) if valid_hand.size else None, 3),
            "meanPlanCurvature": _round(_safe_stat(valid_plan, "mean"), 6),
            "meanProfileCurvature": _round(_safe_stat(valid_profile, "mean"), 6),
            "valleyConfinementIndex": _round(valley_confinement, 6),
            "channelConfinementRatio": _round(channel_confinement, 6),
            "flowAccumulationP50Km2": _round(float(np.nanpercentile(valid_uparea, 50)) if valid_uparea.size else None, 6),
            "flowAccumulationP90Km2": _round(float(np.nanpercentile(valid_uparea, 90)) if valid_uparea.size else None, 6),
            "flowAccumulationP99Km2": _round(float(np.nanpercentile(valid_uparea, 99)) if valid_uparea.size else None, 6),
        },
        hand,
        stream_mask,
    )



def _worldcover_metrics(
    *,
    worldcover_path: str,
    mask: np.ndarray,
    hand: np.ndarray | None,
    stream_mask: np.ndarray | None,
    reference_transform: Affine,
    reference_crs: Any,
    width: int,
    height: int,
) -> dict[str, Any]:
    src = _open_shared_raster(worldcover_path)
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
            "forestPatchCount": 0,
            "urbanPatchCount": 0,
            "largestForestPatchFraction": None,
            "landCoverFragmentationIndex": None,
            "riparianForestFraction": None,
            "imperviousConnectivityProxy": None,
        }

    values = worldcover[valid]
    def frac(code: int) -> float:
        return float(np.count_nonzero(values == code) / valid_count)

    forest_mask = valid & (worldcover == 10)
    shrub_grass_mask = valid & np.isin(worldcover, [20, 30, 90, 95, 100])
    urban_mask = valid & (worldcover == 50)
    water_mask = valid & (worldcover == 80)
    wetland_mask = valid & (worldcover == 90)
    natural_mask = forest_mask | shrub_grass_mask
    largest_forest_fraction = _largest_component_fraction(forest_mask, min_pixels=3)
    largest_natural_fraction = _largest_component_fraction(natural_mask, min_pixels=3)

    riparian_forest_fraction = None
    if hand is not None and stream_mask is not None:
        riparian_mask = valid & np.isfinite(hand) & ((hand <= 20.0) | stream_mask)
        riparian_count = int(np.count_nonzero(riparian_mask))
        if riparian_count > 0:
            riparian_forest_fraction = float(np.count_nonzero(riparian_mask & (worldcover == 10)) / riparian_count)

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
        "forestPatchCount": _connected_component_count(forest_mask, min_pixels=3),
        "urbanPatchCount": _connected_component_count(urban_mask, min_pixels=2),
        "largestForestPatchFraction": _round(largest_forest_fraction, 6),
        "landCoverFragmentationIndex": _round((1.0 - largest_natural_fraction) if largest_natural_fraction is not None else None, 6),
        "riparianForestFraction": _round(riparian_forest_fraction, 6),
        "imperviousConnectivityProxy": _round(_largest_component_fraction(urban_mask, min_pixels=2), 6),
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
    for key, url in {**SOILGRIDS_LAYERS, **SOIL_AUXILIARY_LAYERS}.items():
        src = _open_shared_raster(url)
        with WarpedVRT(
            src,
            crs=reference_crs,
            transform=reference_transform,
            width=width,
            height=height,
            resampling=Resampling.bilinear,
        ) as vrt:
            data = vrt.read(1, masked=True)
            arrays[key] = np.where(np.ma.getmaskarray(data), np.nan, data.data.astype(np.float32))

    clay = arrays["clay"]
    sand = arrays["sand"]
    subsoil_clay = arrays["subsoilClay"]
    subsoil_sand = arrays["subsoilSand"]
    coarse_fragments = arrays["coarseFragments"]
    bedrock_depth = arrays["bedrockDepth"]
    soil_depth = arrays["soilDepth"]
    available_water_capacity = arrays["availableWaterCapacity"]
    saturated_hydraulic_conductivity = arrays["saturatedHydraulicConductivity"]
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
            "coarseFragmentFraction": None,
            "subsoilClayFraction": None,
            "subsoilSandFraction": None,
            "soilDepthMean": None,
            "soilDepthShallowFraction": None,
            "bedrockDepth": None,
            "availableWaterCapacity": None,
            "saturatedHydraulicConductivity": None,
        }

    # SoilGrids clay/sand mean values are in g/kg. Convert to percent.
    clay_pct = clay[valid] / 10.0
    sand_pct = sand[valid] / 10.0
    subsoil_valid = mask & np.isfinite(subsoil_clay) & np.isfinite(subsoil_sand)
    coarse_valid = mask & np.isfinite(coarse_fragments)
    depth_valid = mask & np.isfinite(soil_depth) & (soil_depth >= 0)
    bedrock_valid = mask & np.isfinite(bedrock_depth) & (bedrock_depth >= 0)
    awc_valid = mask & np.isfinite(available_water_capacity) & (available_water_capacity >= 0)
    ksat_valid = mask & np.isfinite(saturated_hydraulic_conductivity) & (saturated_hydraulic_conductivity > -30)

    subsoil_clay_fraction = None
    subsoil_sand_fraction = None
    if np.count_nonzero(subsoil_valid) > 0:
        subsoil_clay_fraction = float(np.nanmean(subsoil_clay[subsoil_valid] / 1000.0))
        subsoil_sand_fraction = float(np.nanmean(subsoil_sand[subsoil_valid] / 1000.0))

    coarse_fragment_fraction = None
    if np.count_nonzero(coarse_valid) > 0:
        coarse_fragment_fraction = float(np.nanmean(coarse_fragments[coarse_valid] / 1000.0))

    soil_depth_mean = float(np.nanmean(soil_depth[depth_valid])) if np.count_nonzero(depth_valid) > 0 else None
    shallow_fraction = None
    if np.count_nonzero(depth_valid) > 0:
        shallow_fraction = float(np.count_nonzero(soil_depth[depth_valid] <= 100.0) / np.count_nonzero(depth_valid))

    bedrock_depth_mean = float(np.nanmean(bedrock_depth[bedrock_valid])) if np.count_nonzero(bedrock_valid) > 0 else None
    awc_mean = float(np.nanmean(available_water_capacity[awc_valid])) if np.count_nonzero(awc_valid) > 0 else None

    ksat_mean = None
    if np.count_nonzero(ksat_valid) > 0:
        # Global_Ksat is log10(Ksat [cm/day]); convert back to physical units.
        ksat_mean = float(np.nanmean(np.power(10.0, saturated_hydraulic_conductivity[ksat_valid])))

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
        "coarseFragmentFraction": _round(coarse_fragment_fraction, 6),
        "subsoilClayFraction": _round(subsoil_clay_fraction, 6),
        "subsoilSandFraction": _round(subsoil_sand_fraction, 6),
        "soilDepthMean": _round(soil_depth_mean, 3),
        "soilDepthShallowFraction": _round(shallow_fraction, 6),
        "bedrockDepth": _round(bedrock_depth_mean, 3),
        "availableWaterCapacity": _round(awc_mean, 3),
        "saturatedHydraulicConductivity": _round(ksat_mean, 3),
    }


def _hydrolakes_metrics(
    *,
    hydrolakes_path: str,
    watershed_geometry: dict[str, Any] | None,
    basin_area_km2: float,
    outlet_longitude: float | None,
    outlet_latitude: float | None,
) -> dict[str, Any]:
    if watershed_geometry is None or outlet_longitude is None or outlet_latitude is None:
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
            "hydroLakesNearestRegulationDistanceKm": None,
        }
    import shapefile  # type: ignore
    from shapely.geometry import shape as shapely_shape  # type: ignore

    basin = shapely_shape(watershed_geometry)
    outlet_point = shapely_shape(
        transform_geom("EPSG:4326", "EPSG:3857", {"type": "Point", "coordinates": [outlet_longitude, outlet_latitude]})
    )
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
    nearest_regulation_distance_km = None

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
            regulation_geom = shapely_shape(transform_geom("EPSG:4326", "EPSG:3857", lake_geom.__geo_interface__))
            distance_km = outlet_point.distance(regulation_geom) / 1000.0
            if nearest_regulation_distance_km is None or distance_km < nearest_regulation_distance_km:
                nearest_regulation_distance_km = distance_km

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
        "hydroLakesNearestRegulationDistanceKm": _round(nearest_regulation_distance_km, 6),
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
    outlet_longitude: float | None,
    outlet_latitude: float | None,
) -> dict[str, Any]:
    if watershed_geometry is None or outlet_longitude is None or outlet_latitude is None:
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
            "gdwNearestRegulationDistanceKm": None,
        }

    import shapefile  # type: ignore
    from shapely.geometry import shape as shapely_shape  # type: ignore

    basin = shapely_shape(watershed_geometry)
    outlet_point = shapely_shape(
        transform_geom("EPSG:4326", "EPSG:3857", {"type": "Point", "coordinates": [outlet_longitude, outlet_latitude]})
    )
    bbox = basin.bounds

    barrier_count = 0
    hydropower_barrier_count = 0
    max_dor = 0.0
    newest_year = None
    max_dam_height = 0.0
    nearest_regulation_distance_km = None

    barriers_reader = shapefile.Reader(barriers_path)
    for shape_record in barriers_reader.iterShapeRecords(bbox=bbox):
        barrier_geom = shapely_shape(shape_record.shape.__geo_interface__)
        if barrier_geom.is_empty or not basin.intersects(barrier_geom):
            continue
        attrs = shape_record.record.as_dict() if hasattr(shape_record.record, "as_dict") else {}
        barrier_count += 1
        barrier_geom_projected = shapely_shape(transform_geom("EPSG:4326", "EPSG:3857", barrier_geom.__geo_interface__))
        barrier_distance_km = outlet_point.distance(barrier_geom_projected) / 1000.0
        if nearest_regulation_distance_km is None or barrier_distance_km < nearest_regulation_distance_km:
            nearest_regulation_distance_km = barrier_distance_km
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
        reservoir_geom_projected = shapely_shape(transform_geom("EPSG:4326", "EPSG:3857", reservoir_geom.__geo_interface__))
        reservoir_distance_km = outlet_point.distance(reservoir_geom_projected) / 1000.0
        if nearest_regulation_distance_km is None or reservoir_distance_km < nearest_regulation_distance_km:
            nearest_regulation_distance_km = reservoir_distance_km
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
        "gdwNearestRegulationDistanceKm": _round(nearest_regulation_distance_km, 6),
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
    src = _open_shared_raster(glim_path)
    with WarpedVRT(
        src,
        src_crs="EPSG:4326",
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
    src = _open_shared_raster(ghsl_path)
    src_x_res = abs(src.transform.a)
    src_y_res = abs(src.transform.e)
    source_cell_area_m2 = max(src_x_res * src_y_res, 1.0)
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
    built_fraction_values = np.clip(built_values / source_cell_area_m2, 0.0, 1.0)
    basin_built_fraction = float(np.nanmean(built_fraction_values))
    mean_built_surface = float(np.nanmean(built_values))
    return {
        "imperviousValidFraction": _round(valid_count / max(int(np.count_nonzero(mask)), 1), 6),
        "imperviousBuiltSurfaceFraction": _round(max(0.0, min(1.0, basin_built_fraction)), 6),
        "meanBuiltSurfaceM2PerCell": _round(mean_built_surface, 3),
    }


def _rgi_metrics(
    *,
    glacier_shapefiles: list[str],
    watershed_geometry: dict[str, Any] | None,
    basin_area_km2: float,
) -> dict[str, Any]:
    if watershed_geometry is None:
        return {
            "glacierFraction": None,
            "glacierCount": 0,
            "largestGlacierAreaKm2": None,
        }

    import shapefile  # type: ignore
    from shapely.geometry import shape as shapely_shape  # type: ignore

    basin = shapely_shape(watershed_geometry)
    bbox = basin.bounds
    glacier_count = 0
    glacier_area_km2 = 0.0
    largest_glacier_area_km2 = 0.0

    for glacier_path in glacier_shapefiles:
        reader = shapefile.Reader(glacier_path)
        for shape_record in reader.iterShapeRecords(bbox=bbox):
            glacier_geom = shapely_shape(shape_record.shape.__geo_interface__)
            if glacier_geom.is_empty or not basin.intersects(glacier_geom):
                continue
            inter = basin.intersection(glacier_geom)
            if inter.is_empty:
                continue
            attrs = shape_record.record.as_dict() if hasattr(shape_record.record, "as_dict") else {}
            area_km2 = _record_value(attrs, "Area", "AREA", "Area_km2", "RGI_AREA")
            if area_km2 in (None, ""):
                continue
            area_value = float(area_km2)
            glacier_count += 1
            glacier_area_km2 += area_value
            largest_glacier_area_km2 = max(largest_glacier_area_km2, area_value)

    glacier_fraction = (glacier_area_km2 / basin_area_km2) if basin_area_km2 > 0 else None
    return {
        "glacierFraction": _round(glacier_fraction, 6),
        "glacierCount": glacier_count,
        "largestGlacierAreaKm2": _round(largest_glacier_area_km2 if glacier_count > 0 else None, 6),
    }


def _advanced_regulation_metrics(descriptors: dict[str, Any]) -> dict[str, Any]:
    basin_area_km2 = float(descriptors.get("basinAreaRasterKm2") or 0.0)
    total_stream_length_km = float(descriptors.get("totalStreamLengthKm") or 0.0)
    hydrolakes_distance = descriptors.get("hydroLakesNearestRegulationDistanceKm")
    gdw_distance = descriptors.get("gdwNearestRegulationDistanceKm")
    distance_candidates = [float(value) for value in [hydrolakes_distance, gdw_distance] if value is not None]
    nearest_distance = min(distance_candidates) if distance_candidates else None

    gdw_dor_fraction = float(descriptors.get("gdwMaxUpstreamDorPct") or 0.0) / 100.0
    reservoir_fraction = float(descriptors.get("reservoirAreaFraction") or 0.0)
    regulated_area_fraction = max(gdw_dor_fraction, reservoir_fraction)

    hydropower_count = int(descriptors.get("gdwHydropowerBarrierCountUpstream") or 0) + int(descriptors.get("osmHydropowerPlantCountUpstream") or 0)
    barrier_only_count = int(descriptors.get("gdwBarrierCountUpstream") or 0) + int(descriptors.get("osmDamCountUpstream") or 0)
    storage_count = int(descriptors.get("gdwReservoirCountUpstream") or 0) + int(descriptors.get("reservoirCountUpstream") or 0) + int(descriptors.get("osmReservoirCountUpstream") or 0)
    diversion_count = int(descriptors.get("osmWeirCountUpstream") or 0) + int(descriptors.get("osmCanalCountUpstream") or 0) + int(descriptors.get("osmPenstockCountUpstream") or 0)
    regulation_vector = {
        "hydropower": hydropower_count,
        "barrier": barrier_only_count,
        "reservoir": storage_count,
        "diversion": diversion_count,
    }
    dominant_type = "none"
    non_zero = [key for key, value in regulation_vector.items() if value > 0]
    if len(non_zero) >= 2:
        sorted_counts = sorted(regulation_vector.values(), reverse=True)
        dominant_type = "mixed" if len(sorted_counts) >= 2 and sorted_counts[0] == sorted_counts[1] else max(regulation_vector, key=regulation_vector.get)
    elif len(non_zero) == 1:
        dominant_type = non_zero[0]

    water_intake_count = int(descriptors.get("osmWeirCountUpstream") or 0) + int(descriptors.get("osmCanalCountUpstream") or 0) + int(descriptors.get("osmPenstockCountUpstream") or 0)
    water_intake_density = None
    if total_stream_length_km > 0:
        water_intake_density = water_intake_count / total_stream_length_km
    elif basin_area_km2 > 0:
        water_intake_density = water_intake_count / basin_area_km2

    cascade_sources = hydropower_count + int(descriptors.get("osmPenstockCountUpstream") or 0)
    cascade_count = max(cascade_sources - 1, 0)
    interbasin_transfer = (int(descriptors.get("osmCanalCountUpstream") or 0) > 0) or (int(descriptors.get("osmPenstockCountUpstream") or 0) > 1)

    storage_mcm = max(float(descriptors.get("gdwReservoirStorageUpstreamMcm") or 0.0), float(descriptors.get("reservoirStorageUpstreamMcm") or 0.0))
    barrier_count = max(
        int(descriptors.get("gdwBarrierCountUpstream") or 0),
        int(descriptors.get("damCountUpstream") or 0),
        int(descriptors.get("osmDamCountUpstream") or 0),
    )
    distance_score = 0.0 if nearest_distance is None else max(0.0, 1.0 - min(nearest_distance / 20.0, 1.0))
    severity = min(
        1.0,
        (0.35 * min(gdw_dor_fraction, 1.0))
        + (0.25 * min(math.log1p(storage_mcm) / math.log1p(10_000.0), 1.0))
        + (0.2 * min((barrier_count + storage_count + diversion_count) / 8.0, 1.0))
        + (0.2 * distance_score),
    )

    return {
        "distanceToNearestRegulationUpstreamKm": _round(nearest_distance, 6),
        "regulatedAreaFraction": _round(regulated_area_fraction if regulated_area_fraction > 0 else None, 6),
        "dominantRegulationType": dominant_type,
        "interbasinTransferLikely": interbasin_transfer,
        "waterIntakeDensity": _round(water_intake_density, 6),
        "hydropowerCascadeCount": cascade_count,
        "regulationSeverityIndex": _round(severity if severity > 0 else None, 6),
    }


def _karst_hydrology_proxy_metrics(
    *,
    dem: np.ndarray,
    valid: np.ndarray,
    area_km2: float,
    carbonate_fraction: float | None,
    karst_indicator: float | None,
    high_infiltration_soil_fraction: float | None,
    soil_depth_shallow_fraction: float | None,
    drainage_density_km_per_km2: float | None,
    stream_frequency_per_km2: float | None,
) -> dict[str, Any]:
    if carbonate_fraction is None and karst_indicator is None:
        return {
            "sinkholeDensity": None,
            "springDensity": None,
            "losingStreamIndicator": None,
            "resurgenceIndicator": None,
            "karstConnectivityIndex": None,
        }

    carbonate = float(carbonate_fraction or 0.0)
    karst = float(karst_indicator or 0.0)
    high_infiltration = float(high_infiltration_soil_fraction or 0.0)
    shallow_fraction = float(soil_depth_shallow_fraction or 0.0)
    drainage_density = float(drainage_density_km_per_km2 or 0.0)
    stream_frequency = float(stream_frequency_per_km2 or 0.0)

    depression_count = _local_closed_depression_count(dem, valid, minimum_drop_m=2.0)
    sinkhole_density = ((depression_count / area_km2) * max(carbonate, karst, 0.1)) if area_km2 > 0 else None
    spring_density = stream_frequency * max(carbonate, karst)
    sinkhole_score = 0.0 if sinkhole_density is None else min(sinkhole_density / 1.0, 1.0)
    karst_connectivity = min(
        1.0,
        (0.45 * max(carbonate, karst))
        + (0.2 * high_infiltration)
        + (0.2 * shallow_fraction)
        + (0.15 * sinkhole_score),
    )
    losing_stream_indicator = max(0.0, min(1.0, karst_connectivity * (1.0 - min(drainage_density / 3.0, 1.0))))
    resurgence_indicator = max(0.0, min(1.0, karst_connectivity * min(spring_density / 1.5, 1.0)))

    return {
        "sinkholeDensity": _round(sinkhole_density, 6),
        "springDensity": _round(spring_density, 6),
        "losingStreamIndicator": _round(losing_stream_indicator, 6),
        "resurgenceIndicator": _round(resurgence_indicator, 6),
        "karstConnectivityIndex": _round(karst_connectivity, 6),
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


def _same_crs(left: Any, right: Any) -> bool:
    if left is None and right is None:
        return True
    if left is None or right is None:
        return False
    return str(left) == str(right)


def _same_transform(left: Affine, right: Affine, tolerance: float = 1e-9) -> bool:
    return all(abs(float(a) - float(b)) <= tolerance for a, b in zip(left[:6], right[:6]))


def _validate_reference_grid(
    *,
    dataset: Any,
    mask_data: dict[str, Any],
    expected_shape: tuple[int, int],
    name: str,
) -> None:
    if (dataset.height, dataset.width) != expected_shape:
        raise SystemExit(
            f"{name}/mask shape mismatch for descriptors: {(dataset.height, dataset.width)} vs {expected_shape}"
        )
    if not _same_crs(dataset.crs, mask_data["crs"]):
        raise SystemExit(f"{name}/mask CRS mismatch for descriptors: {dataset.crs} vs {mask_data['crs']}")
    if not _same_transform(dataset.transform, mask_data["transform"]):
        raise SystemExit(
            f"{name}/mask transform mismatch for descriptors: {dataset.transform} vs {mask_data['transform']}"
        )


def compute_watershed_descriptors(
    *,
    dem_path: str,
    uparea_path: str,
    flowdir_path: str,
    climate_monthly_paths: dict[str, list[str]] | None,
    worldcover_path: str | None,
    ghsl_built_path: str | None,
    hydrolakes_path: str | None,
    gdw_barriers_path: str | None,
    gdw_reservoirs_path: str | None,
    glim_path: str | None,
    rgi_glacier_paths: list[str] | None,
    watershed_geometry: dict[str, Any] | None,
    mask_data: dict[str, Any],
    selected_candidate: dict[str, Any],
) -> dict[str, Any]:
    with rasterio.open(dem_path) as src:
        dem = src.read(1, masked=True).filled(np.nan).astype(np.float32)
        mask = mask_data["mask"]
        _validate_reference_grid(dataset=src, mask_data=mask_data, expected_shape=mask.shape, name="DEM")

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

        outlet_longitude = selected_candidate["evaluation"].get("snapped_longitude")
        outlet_latitude = selected_candidate["evaluation"].get("snapped_latitude")

    with rasterio.open(uparea_path) as upa_src, rasterio.open(flowdir_path) as flow_src:
        _validate_reference_grid(dataset=upa_src, mask_data=mask_data, expected_shape=mask.shape, name="UPA")
        _validate_reference_grid(dataset=flow_src, mask_data=mask_data, expected_shape=mask.shape, name="flowdir")
        uparea = upa_src.read(1, masked=True).filled(np.nan).astype(np.float32)
        flow = flow_src.read(1, masked=True).filled(0).astype(np.int16)
        network_metrics = _compute_network_metrics(
            mask=mask,
            path_lengths=path_lengths,
            flow=flow,
            uparea=uparea,
            area_km2=area_km2,
            dem_resolution_m=dem_resolution,
        )
        topo_hydrology_metrics, hand, stream_mask = _topo_hydrology_metrics(
            dem=dem,
            mask=mask,
            flow=flow,
            uparea=uparea,
            x_res_m=x_res_m,
            y_res_m=y_res_m,
            dem_resolution_m=dem_resolution,
            values=values,
        )

    try:
        climate_metrics = _worldclim_metrics(
            monthly_paths=climate_monthly_paths or {},
            mask=mask,
            reference_transform=mask_data["transform"],
            reference_crs=mask_data["crs"],
            width=mask.shape[1],
            height=mask.shape[0],
        ) if climate_monthly_paths else {
            "meanAnnualPrecipMm": None,
            "meanMonthlyPrecipSeasonality": None,
            "meanAnnualTemperatureC": None,
            "meanWinterTemperatureC": None,
            "meanSnowFractionClimatology": None,
            "potentialEvapotranspiration": None,
            "aridityIndex": None,
            "continentalityProxy": None,
            "oceanicityProxy": None,
        }
        climate_metrics["climateDescriptorStatus"] = "ok" if climate_monthly_paths else "skipped"
    except Exception as exc:
        climate_metrics = {
            "climateDescriptorStatus": f"error:{type(exc).__name__}",
            "meanAnnualPrecipMm": None,
            "meanMonthlyPrecipSeasonality": None,
            "meanAnnualTemperatureC": None,
            "meanWinterTemperatureC": None,
            "meanSnowFractionClimatology": None,
            "potentialEvapotranspiration": None,
            "aridityIndex": None,
            "continentalityProxy": None,
            "oceanicityProxy": None,
        }

    landcover_metrics = {}
    if worldcover_path:
        landcover_metrics = _worldcover_metrics(
            worldcover_path=worldcover_path,
            mask=mask,
            hand=hand,
            stream_mask=stream_mask,
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
            outlet_longitude=float(outlet_longitude) if outlet_longitude is not None else None,
            outlet_latitude=float(outlet_latitude) if outlet_latitude is not None else None,
        ) if hydrolakes_path else {"lakeFraction": None, "lakeCount": 0}
        hydrolakes_metrics["hydroLakesStatus"] = "ok" if hydrolakes_path else "skipped"
    except Exception as exc:
        hydrolakes_metrics = {
            "hydroLakesStatus": f"error:{type(exc).__name__}",
            "lakeFraction": None,
            "lakeCount": None,
            "hydroLakesNearestRegulationDistanceKm": None,
        }

    try:
        gdw_metrics = _gdw_regulation_metrics(
            barriers_path=gdw_barriers_path,
            reservoirs_path=gdw_reservoirs_path,
            watershed_geometry=watershed_geometry,
            basin_area_km2=area_km2,
            outlet_longitude=float(outlet_longitude) if outlet_longitude is not None else None,
            outlet_latitude=float(outlet_latitude) if outlet_latitude is not None else None,
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
            "gdwNearestRegulationDistanceKm": None,
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
            "gdwNearestRegulationDistanceKm": None,
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

    try:
        glacier_metrics = _rgi_metrics(
            glacier_shapefiles=rgi_glacier_paths or [],
            watershed_geometry=watershed_geometry,
            basin_area_km2=area_km2,
        ) if rgi_glacier_paths else {
            "glacierFraction": None,
            "glacierCount": 0,
            "largestGlacierAreaKm2": None,
        }
        glacier_metrics["glacierDescriptorStatus"] = "ok" if rgi_glacier_paths else "skipped"
    except Exception as exc:
        glacier_metrics = {
            "glacierDescriptorStatus": f"error:{type(exc).__name__}",
            "glacierFraction": None,
            "glacierCount": None,
            "largestGlacierAreaKm2": None,
        }

    karst_metrics = _karst_hydrology_proxy_metrics(
        dem=dem,
        valid=valid,
        area_km2=area_km2,
        carbonate_fraction=glim_metrics.get("carbonateFraction"),
        karst_indicator=glim_metrics.get("karstIndicator"),
        high_infiltration_soil_fraction=soil_metrics.get("highInfiltrationSoilFraction"),
        soil_depth_shallow_fraction=soil_metrics.get("soilDepthShallowFraction"),
        drainage_density_km_per_km2=network_metrics.get("drainageDensityKmPerKm2"),
        stream_frequency_per_km2=network_metrics.get("streamFrequencyPerKm2"),
    )

    advanced_regulation_metrics = _advanced_regulation_metrics(
        {
            "basinAreaRasterKm2": area_km2,
            **network_metrics,
            **hydrolakes_metrics,
            **gdw_metrics,
        }
    )

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
            **climate_metrics,
            **topo_hydrology_metrics,
            **network_metrics,
            **landcover_metrics,
            **soil_metrics,
            **hydrolakes_metrics,
            **gdw_metrics,
            **glim_metrics,
            **ghsl_metrics,
            **glacier_metrics,
            **advanced_regulation_metrics,
            **karst_metrics,
            "imperviousProxyFraction": landcover_metrics.get("urbanFraction"),
        }
