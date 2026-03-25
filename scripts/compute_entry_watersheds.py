from __future__ import annotations

import argparse
import json
import math
import sys
import unicodedata
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import numpy as np


FLOW_DIRECTION_OFFSETS = {
    1: (0, 1),
    2: (1, 1),
    4: (1, 0),
    8: (1, -1),
    16: (0, -1),
    32: (-1, -1),
    64: (-1, 0),
    128: (-1, 1),
}

UPSTREAM_HINTS = {
    "amont",
    "haut",
    "haute",
    "upper",
    "upstream",
    "sup",
    "superieur",
    "integrale",
    "integral",
    "complete",
    "complet",
}

DOWNSTREAM_HINTS = {
    "aval",
    "bas",
    "basse",
    "lower",
    "downstream",
    "inf",
    "inferieur",
}


@dataclass(frozen=True)
class Cell:
    row: int
    col: int


@dataclass(frozen=True)
class CandidateCell:
    cell: Cell
    value: float
    distance_m: float
    latitude: float | None = None
    longitude: float | None = None
    x: float | None = None
    y: float | None = None


@dataclass(frozen=True)
class EntryPoint:
    canyon_id: int
    canyon_name: str
    entry_index: int
    geo_point_index: int
    latitude: float
    longitude: float
    label: str | None


@dataclass
class EvaluatedEntry:
    canyon_id: int
    canyon_name: str
    entry_index: int
    geo_point_index: int
    latitude: float
    longitude: float
    label: str | None
    label_hint: str
    raw_upa_km2: float | None
    snapped_upa_km2: float | None
    raw_cell: Cell | None
    snapped_cell: Cell | None
    snapped_latitude: float | None
    snapped_longitude: float | None
    snap_distance_m: float | None
    pixel_size_m: float | None
    candidate_count: int
    raw_to_snapped_upa_ratio: float | None
    elevation_m: float | None
    flowdir_value: int | None
    status: str
    status_detail: str | None


def normalize_text(value: str | None) -> str:
    if not value:
        return ""
    normalized = unicodedata.normalize("NFKD", value)
    normalized = "".join(char for char in normalized if not unicodedata.combining(char))
    normalized = normalized.lower()
    cleaned = []
    for char in normalized:
        cleaned.append(char if char.isalnum() else " ")
    return " ".join("".join(cleaned).split())


def detect_label_hint(label: str | None) -> str:
    normalized = normalize_text(label)
    if not normalized:
        return "unknown"

    tokens = set(normalized.split())
    has_upstream = bool(tokens & UPSTREAM_HINTS)
    has_downstream = bool(tokens & DOWNSTREAM_HINTS)

    if has_upstream and has_downstream:
        return "ambiguous"
    if has_upstream:
        return "upstream"
    if has_downstream:
        return "downstream"
    return "unknown"


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
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


def ratio(numerator: float | None, denominator: float | None) -> float | None:
    if numerator is None or denominator is None:
        return None
    if denominator <= 0:
        return None
    return numerator / denominator


def round_if_not_none(value: float | None, digits: int) -> float | None:
    if value is None:
        return None
    return round(value, digits)


def resolve_search_radius_cells(
    upa_raster: Any,
    *,
    longitude: float,
    latitude: float,
    search_radius_cells: int,
    search_radius_m: float | None,
) -> int:
    if search_radius_m is None:
        return search_radius_cells
    pixel_size_m = upa_raster.approximate_cell_size_m(longitude, latitude)
    if pixel_size_m <= 0:
        return search_radius_cells
    return max(search_radius_cells, int(math.ceil(search_radius_m / pixel_size_m)))


def select_candidate(
    candidates: list[CandidateCell],
    *,
    strategy: str,
    channel_min_upa_km2: float | None,
) -> CandidateCell:
    if strategy == "nearest_channel":
        filtered = candidates
        if channel_min_upa_km2 is not None:
            threshold_matches = [candidate for candidate in candidates if candidate.value >= channel_min_upa_km2]
            if threshold_matches:
                filtered = threshold_matches
        return min(filtered, key=lambda candidate: (candidate.distance_m, -candidate.value))

    return max(candidates, key=lambda candidate: (candidate.value, -candidate.distance_m))


class ArrayRaster:
    def __init__(
        self,
        array: np.ndarray,
        *,
        top_left_lon: float,
        top_left_lat: float,
        pixel_size_deg: float,
        nodata: float | None = None,
        name: str,
    ) -> None:
        self.array = np.asarray(array)
        self.top_left_lon = top_left_lon
        self.top_left_lat = top_left_lat
        self.pixel_size_deg = pixel_size_deg
        self.nodata = nodata
        self.height, self.width = self.array.shape
        self.name = name

    def point_to_cell(self, longitude: float, latitude: float) -> Cell | None:
        col = math.floor((longitude - self.top_left_lon) / self.pixel_size_deg)
        row = math.floor((self.top_left_lat - latitude) / self.pixel_size_deg)
        if row < 0 or row >= self.height or col < 0 or col >= self.width:
            return None
        return Cell(row=row, col=col)

    def cell_center(self, cell: Cell) -> tuple[float, float]:
        longitude = self.top_left_lon + (cell.col + 0.5) * self.pixel_size_deg
        latitude = self.top_left_lat - (cell.row + 0.5) * self.pixel_size_deg
        return latitude, longitude

    def value_at_cell(self, cell: Cell | None) -> float | None:
        if cell is None:
            return None
        if cell.row < 0 or cell.row >= self.height or cell.col < 0 or cell.col >= self.width:
            return None
        value = self.array[cell.row, cell.col]
        if self.nodata is not None and value == self.nodata:
            return None
        if np.ma.is_masked(value) or not np.isfinite(value):
            return None
        return float(value)

    def neighborhood_candidates(
        self,
        *,
        longitude: float,
        latitude: float,
        radius_cells: int,
    ) -> tuple[Cell | None, list[CandidateCell]]:
        raw_cell = self.point_to_cell(longitude, latitude)
        if raw_cell is None:
            return None, []

        candidates: list[CandidateCell] = []
        row_min = max(0, raw_cell.row - radius_cells)
        row_max = min(self.height - 1, raw_cell.row + radius_cells)
        col_min = max(0, raw_cell.col - radius_cells)
        col_max = min(self.width - 1, raw_cell.col + radius_cells)

        for row in range(row_min, row_max + 1):
            for col in range(col_min, col_max + 1):
                if math.hypot(row - raw_cell.row, col - raw_cell.col) > radius_cells + 1e-9:
                    continue
                cell = Cell(row=row, col=col)
                value = self.value_at_cell(cell)
                if value is None:
                    continue
                cell_latitude, cell_longitude = self.cell_center(cell)
                candidates.append(
                    CandidateCell(
                        cell=cell,
                        latitude=cell_latitude,
                        longitude=cell_longitude,
                        value=value,
                        distance_m=haversine_m(latitude, longitude, cell_latitude, cell_longitude),
                    )
                )

        return raw_cell, candidates

    def approximate_cell_size_m(self, longitude: float, latitude: float) -> float:
        north_south = 111_320.0 * self.pixel_size_deg
        east_west = 111_320.0 * math.cos(math.radians(latitude)) * self.pixel_size_deg
        return math.sqrt(abs(north_south * east_west))


class RasterioRaster:
    def __init__(self, path: Path, *, band_index: int, name: str) -> None:
        try:
            import rasterio  # type: ignore
            from rasterio.transform import xy  # type: ignore
            from rasterio.warp import transform as warp_transform  # type: ignore
            from rasterio.windows import Window  # type: ignore
        except ImportError as exc:  # pragma: no cover - exercised in runtime, not self-check
            raise RuntimeError(
                "rasterio is required for real raster runs. Install dependencies with "
                "pip install -r scripts/watersheds/requirements.txt"
            ) from exc

        self._rasterio = rasterio
        self._xy = xy
        self._warp_transform = warp_transform
        self._window_cls = Window
        self.dataset = rasterio.open(path)
        self.band_index = band_index
        self.name = name
        self.path = path
        self.height = self.dataset.height
        self.width = self.dataset.width
        self.nodata = self.dataset.nodata
        self._is_geographic = bool(self.dataset.crs is not None and getattr(self.dataset.crs, "is_geographic", False))
        self._cell_value_cache: dict[tuple[int, int], float | None] = {}

    def close(self) -> None:
        self.dataset.close()

    def _to_dataset_xy(self, longitude: float, latitude: float) -> tuple[float, float]:
        if self.dataset.crs is None or str(self.dataset.crs).upper() == "EPSG:4326":
            return longitude, latitude
        xs, ys = self._warp_transform("EPSG:4326", self.dataset.crs, [longitude], [latitude])
        return xs[0], ys[0]

    def _to_wgs84(self, x: float, y: float) -> tuple[float, float]:
        if self.dataset.crs is None or str(self.dataset.crs).upper() == "EPSG:4326":
            return y, x
        longitudes, latitudes = self._warp_transform(self.dataset.crs, "EPSG:4326", [x], [y])
        return latitudes[0], longitudes[0]

    def point_to_cell(self, longitude: float, latitude: float) -> Cell | None:
        x, y = self._to_dataset_xy(longitude, latitude)
        row, col = self.dataset.index(x, y)
        if row < 0 or row >= self.height or col < 0 or col >= self.width:
            return None
        return Cell(row=row, col=col)

    def cell_center(self, cell: Cell) -> tuple[float, float]:
        x, y = self.cell_center_xy(cell)
        return self._to_wgs84(x, y)

    def cell_center_xy(self, cell: Cell) -> tuple[float, float]:
        transform = self.dataset.transform
        col = cell.col + 0.5
        row = cell.row + 0.5
        x = transform.c + col * transform.a + row * transform.b
        y = transform.f + col * transform.d + row * transform.e
        return x, y

    def value_at_cell(self, cell: Cell | None) -> float | None:
        if cell is None:
            return None
        cache_key = (cell.row, cell.col)
        if cache_key in self._cell_value_cache:
            return self._cell_value_cache[cache_key]
        if cell.row < 0 or cell.row >= self.height or cell.col < 0 or cell.col >= self.width:
            self._cell_value_cache[cache_key] = None
            return None

        window = self._window_cls(col_off=cell.col, row_off=cell.row, width=1, height=1)
        sample = self.dataset.read(self.band_index, window=window, masked=True)
        if sample.size == 0 or np.ma.is_masked(sample[0, 0]) or not np.isfinite(sample[0, 0]):
            self._cell_value_cache[cache_key] = None
            return None

        value = float(sample[0, 0])
        if self.nodata is not None and value == self.nodata:
            self._cell_value_cache[cache_key] = None
            return None

        self._cell_value_cache[cache_key] = value
        return value

    def neighborhood_candidates(
        self,
        *,
        longitude: float,
        latitude: float,
        radius_cells: int,
    ) -> tuple[Cell | None, list[CandidateCell]]:
        raw_cell = self.point_to_cell(longitude, latitude)
        if raw_cell is None:
            return None, []

        origin_x, origin_y = self._to_dataset_xy(longitude, latitude)

        row_min = max(0, raw_cell.row - radius_cells)
        row_max = min(self.height - 1, raw_cell.row + radius_cells)
        col_min = max(0, raw_cell.col - radius_cells)
        col_max = min(self.width - 1, raw_cell.col + radius_cells)
        window = self._window_cls(
            col_off=col_min,
            row_off=row_min,
            width=col_max - col_min + 1,
            height=row_max - row_min + 1,
        )
        values = self.dataset.read(self.band_index, window=window, masked=True)

        candidates: list[CandidateCell] = []
        for row_offset in range(values.shape[0]):
            for col_offset in range(values.shape[1]):
                absolute_row = row_min + row_offset
                absolute_col = col_min + col_offset
                if math.hypot(absolute_row - raw_cell.row, absolute_col - raw_cell.col) > radius_cells + 1e-9:
                    continue

                value = values[row_offset, col_offset]
                if np.ma.is_masked(value) or not np.isfinite(value):
                    continue

                cell = Cell(row=absolute_row, col=absolute_col)
                cell_x, cell_y = self.cell_center_xy(cell)
                if self._is_geographic:
                    cell_latitude, cell_longitude = self._to_wgs84(cell_x, cell_y)
                    distance_m = haversine_m(latitude, longitude, cell_latitude, cell_longitude)
                else:
                    cell_latitude = None
                    cell_longitude = None
                    distance_m = math.hypot(cell_x - origin_x, cell_y - origin_y)
                candidates.append(
                    CandidateCell(
                        cell=cell,
                        value=float(value),
                        distance_m=distance_m,
                        latitude=cell_latitude,
                        longitude=cell_longitude,
                        x=cell_x,
                        y=cell_y,
                    )
                )

        return raw_cell, candidates

    def approximate_cell_size_m(self, longitude: float, latitude: float) -> float:
        cell = self.point_to_cell(longitude, latitude)
        if cell is None:
            resolution_x = abs(self.dataset.transform.a)
            resolution_y = abs(self.dataset.transform.e)
            if self.dataset.crs is not None and getattr(self.dataset.crs, "is_geographic", False):
                north_south = 111_320.0 * resolution_y
                east_west = 111_320.0 * math.cos(math.radians(latitude)) * resolution_x
                return math.sqrt(abs(north_south * east_west))
            return math.sqrt(max(resolution_x, 1.0) * max(resolution_y, 1.0))

        center_latitude, center_longitude = self.cell_center(cell)
        east_cell = Cell(row=cell.row, col=min(cell.col + 1, self.width - 1))
        south_cell = Cell(row=min(cell.row + 1, self.height - 1), col=cell.col)
        east_latitude, east_longitude = self.cell_center(east_cell)
        south_latitude, south_longitude = self.cell_center(south_cell)
        east_west = haversine_m(center_latitude, center_longitude, east_latitude, east_longitude)
        north_south = haversine_m(center_latitude, center_longitude, south_latitude, south_longitude)
        return math.sqrt(max(east_west, 1.0) * max(north_south, 1.0))


def load_canyons(path: Path) -> dict[int, dict[str, Any]]:
    items = json.loads(path.read_text(encoding="utf-8"))
    return {int(item["id"]): item for item in items}


def load_entry_points(path: Path, canyon_lookup: dict[int, dict[str, Any]]) -> dict[int, list[EntryPoint]]:
    items = json.loads(path.read_text(encoding="utf-8"))
    by_canyon: dict[int, list[EntryPoint]] = defaultdict(list)

    for geo_point_index, item in enumerate(items):
        if item.get("type") != "ENTREE":
            continue

        canyon_id = int(item["canyonId"])
        canyon = canyon_lookup.get(canyon_id)
        if canyon is None:
            continue

        entries = by_canyon[canyon_id]
        entries.append(
            EntryPoint(
                canyon_id=canyon_id,
                canyon_name=str(canyon.get("nomComplet") or canyon.get("nom") or canyon_id),
                entry_index=len(entries) + 1,
                geo_point_index=geo_point_index,
                latitude=float(item["latitude"]),
                longitude=float(item["longitude"]),
                label=item.get("label"),
            )
        )

    return by_canyon


def evaluate_entry(
    entry: EntryPoint,
    upa_raster: Any,
    flowdir_raster: Any,
    elevation_raster: Any,
    *,
    search_radius_cells: int,
    search_radius_m: float | None,
    candidate_strategy: str,
    channel_min_upa_km2: float | None,
) -> EvaluatedEntry:
    effective_radius_cells = resolve_search_radius_cells(
        upa_raster,
        longitude=entry.longitude,
        latitude=entry.latitude,
        search_radius_cells=search_radius_cells,
        search_radius_m=search_radius_m,
    )
    raw_cell, candidates = upa_raster.neighborhood_candidates(
        longitude=entry.longitude,
        latitude=entry.latitude,
        radius_cells=effective_radius_cells,
    )

    label_hint = detect_label_hint(entry.label)
    raw_upa = upa_raster.value_at_cell(raw_cell)

    if raw_cell is None:
        return EvaluatedEntry(
            canyon_id=entry.canyon_id,
            canyon_name=entry.canyon_name,
            entry_index=entry.entry_index,
            geo_point_index=entry.geo_point_index,
            latitude=entry.latitude,
            longitude=entry.longitude,
            label=entry.label,
            label_hint=label_hint,
            raw_upa_km2=None,
            snapped_upa_km2=None,
            raw_cell=None,
            snapped_cell=None,
            snapped_latitude=None,
            snapped_longitude=None,
            snap_distance_m=None,
            pixel_size_m=None,
            candidate_count=0,
            raw_to_snapped_upa_ratio=None,
            elevation_m=None,
            flowdir_value=None,
            status="outside_upa_raster",
            status_detail="point_outside_upa_raster_coverage",
        )

    if not candidates:
        return EvaluatedEntry(
            canyon_id=entry.canyon_id,
            canyon_name=entry.canyon_name,
            entry_index=entry.entry_index,
            geo_point_index=entry.geo_point_index,
            latitude=entry.latitude,
            longitude=entry.longitude,
            label=entry.label,
            label_hint=label_hint,
            raw_upa_km2=round_if_not_none(raw_upa, 6),
            snapped_upa_km2=None,
            raw_cell=raw_cell,
            snapped_cell=None,
            snapped_latitude=None,
            snapped_longitude=None,
            snap_distance_m=None,
            pixel_size_m=round_if_not_none(upa_raster.approximate_cell_size_m(entry.longitude, entry.latitude), 3),
            candidate_count=0,
            raw_to_snapped_upa_ratio=None,
            elevation_m=None,
            flowdir_value=None,
            status="no_valid_upa_candidate",
            status_detail="no_non_nodata_upa_cell_within_search_radius",
        )

    snapped_candidate = select_candidate(
        candidates,
        strategy=candidate_strategy,
        channel_min_upa_km2=channel_min_upa_km2,
    )
    snapped_upa = snapped_candidate.value
    snapped_latitude = snapped_candidate.latitude
    snapped_longitude = snapped_candidate.longitude
    if (snapped_latitude is None or snapped_longitude is None) and hasattr(upa_raster, "cell_center"):
        snapped_latitude, snapped_longitude = upa_raster.cell_center(snapped_candidate.cell)
    if snapped_latitude is None or snapped_longitude is None:
        raise RuntimeError("Unable to resolve snapped candidate coordinates")

    pixel_size_m = upa_raster.approximate_cell_size_m(snapped_longitude, snapped_latitude)
    elevation = None
    flowdir_value = None

    if elevation_raster is not None:
        snapped_elevation_cell = elevation_raster.point_to_cell(snapped_longitude, snapped_latitude)
        elevation = elevation_raster.value_at_cell(snapped_elevation_cell)

    if flowdir_raster is not None:
        snapped_flowdir_cell = flowdir_raster.point_to_cell(snapped_longitude, snapped_latitude)
        raw_flowdir_value = flowdir_raster.value_at_cell(snapped_flowdir_cell)
        if raw_flowdir_value is not None:
            flowdir_value = int(round(raw_flowdir_value))

    return EvaluatedEntry(
        canyon_id=entry.canyon_id,
        canyon_name=entry.canyon_name,
        entry_index=entry.entry_index,
        geo_point_index=entry.geo_point_index,
        latitude=entry.latitude,
        longitude=entry.longitude,
        label=entry.label,
        label_hint=label_hint,
        raw_upa_km2=round_if_not_none(raw_upa, 6),
        snapped_upa_km2=round(snapped_upa, 6),
        raw_cell=raw_cell,
        snapped_cell=snapped_candidate.cell,
        snapped_latitude=round(snapped_latitude, 6),
        snapped_longitude=round(snapped_longitude, 6),
        snap_distance_m=round(snapped_candidate.distance_m, 3),
        pixel_size_m=round_if_not_none(pixel_size_m, 3),
        candidate_count=len(candidates),
        raw_to_snapped_upa_ratio=round_if_not_none(ratio(snapped_upa, raw_upa), 6),
        elevation_m=round_if_not_none(elevation, 3),
        flowdir_value=flowdir_value,
        status="ok",
        status_detail=None,
    )


def make_case(
    *,
    code: str,
    severity: str,
    canyon_id: int,
    canyon_name: str,
    message: str,
    entry_indexes: list[int] | None,
    data: dict[str, Any],
) -> dict[str, Any]:
    return {
        "code": code,
        "severity": severity,
        "canyonId": canyon_id,
        "canyonName": canyon_name,
        "message": message,
        "entryIndexes": entry_indexes or [],
        "data": data,
    }


def write_geojson(path: Path, features: list[dict[str, Any]]) -> None:
    collection = {
        "type": "FeatureCollection",
        "features": features,
    }
    write_json(path, collection)


def feature_properties(
    entry: EvaluatedEntry,
    *,
    is_selected: bool,
    selection_reason: str,
    suspicious_codes: list[str],
) -> dict[str, Any]:
    return {
        "canyonId": entry.canyon_id,
        "canyonName": entry.canyon_name,
        "entryIndex": entry.entry_index,
        "geoPointIndex": entry.geo_point_index,
        "label": entry.label,
        "labelHint": entry.label_hint,
        "status": entry.status,
        "statusDetail": entry.status_detail,
        "isSelected": is_selected,
        "selectionReason": selection_reason,
        "rawUpaKm2": entry.raw_upa_km2,
        "snappedUpaKm2": entry.snapped_upa_km2,
        "snapDistanceM": entry.snap_distance_m,
        "pixelSizeM": entry.pixel_size_m,
        "elevationM": entry.elevation_m,
        "flowdirValue": entry.flowdir_value,
        "candidateCount": entry.candidate_count,
        "suspiciousCodes": suspicious_codes,
    }


def entry_point_feature(
    entry: EvaluatedEntry,
    *,
    is_selected: bool,
    selection_reason: str,
    suspicious_codes: list[str],
) -> dict[str, Any]:
    return {
        "type": "Feature",
        "geometry": {
            "type": "Point",
            "coordinates": [entry.longitude, entry.latitude],
        },
        "properties": {
            **feature_properties(
                entry,
                is_selected=is_selected,
                selection_reason=selection_reason,
                suspicious_codes=suspicious_codes,
            ),
            "pointRole": "original_entry",
        },
    }


def snapped_point_feature(
    entry: EvaluatedEntry,
    *,
    is_selected: bool,
    selection_reason: str,
    suspicious_codes: list[str],
) -> dict[str, Any] | None:
    if entry.snapped_longitude is None or entry.snapped_latitude is None:
        return None
    return {
        "type": "Feature",
        "geometry": {
            "type": "Point",
            "coordinates": [entry.snapped_longitude, entry.snapped_latitude],
        },
        "properties": {
            **feature_properties(
                entry,
                is_selected=is_selected,
                selection_reason=selection_reason,
                suspicious_codes=suspicious_codes,
            ),
            "pointRole": "snapped_entry",
        },
    }


def snap_line_feature(
    entry: EvaluatedEntry,
    *,
    is_selected: bool,
    selection_reason: str,
    suspicious_codes: list[str],
) -> dict[str, Any] | None:
    if entry.snapped_longitude is None or entry.snapped_latitude is None:
        return None
    if entry.longitude == entry.snapped_longitude and entry.latitude == entry.snapped_latitude:
        return None
    return {
        "type": "Feature",
        "geometry": {
            "type": "LineString",
            "coordinates": [
                [entry.longitude, entry.latitude],
                [entry.snapped_longitude, entry.snapped_latitude],
            ],
        },
        "properties": {
            **feature_properties(
                entry,
                is_selected=is_selected,
                selection_reason=selection_reason,
                suspicious_codes=suspicious_codes,
            ),
            "pointRole": "snap_segment",
        },
    }


def build_entry_case_index(suspicious_cases: list[dict[str, Any]]) -> dict[tuple[int, int], list[str]]:
    index: dict[tuple[int, int], list[str]] = defaultdict(list)
    for case in suspicious_cases:
        canyon_id = int(case["canyonId"])
        for entry_index in case.get("entryIndexes", []):
            index[(canyon_id, int(entry_index))].append(str(case["code"]))
    return {key: sorted(set(value)) for key, value in index.items()}


def build_geojson_outputs(selected_entries: list[dict[str, Any]], suspicious_cases: list[dict[str, Any]]) -> dict[str, list[dict[str, Any]]]:
    case_index = build_entry_case_index(suspicious_cases)
    all_entry_points: list[dict[str, Any]] = []
    all_snapped_points: list[dict[str, Any]] = []
    all_snap_lines: list[dict[str, Any]] = []
    selected_points: list[dict[str, Any]] = []

    for canyon_result in selected_entries:
        canyon_id = int(canyon_result["canyonId"])
        selected_entry_index = canyon_result["selectedEntryIndex"]
        selection_reason = str(canyon_result["selectionReason"])

        for entry_payload in canyon_result["entries"]:
            entry = EvaluatedEntry(**entry_payload)
            suspicious_codes = case_index.get((canyon_id, entry.entry_index), [])
            is_selected = selected_entry_index == entry.entry_index

            all_entry_points.append(
                entry_point_feature(
                    entry,
                    is_selected=is_selected,
                    selection_reason=selection_reason,
                    suspicious_codes=suspicious_codes,
                )
            )

            snapped_feature = snapped_point_feature(
                entry,
                is_selected=is_selected,
                selection_reason=selection_reason,
                suspicious_codes=suspicious_codes,
            )
            if snapped_feature is not None:
                all_snapped_points.append(snapped_feature)
                if is_selected:
                    selected_points.append(snapped_feature)

            line_feature = snap_line_feature(
                entry,
                is_selected=is_selected,
                selection_reason=selection_reason,
                suspicious_codes=suspicious_codes,
            )
            if line_feature is not None:
                all_snap_lines.append(line_feature)

    return {
        "all_entry_points": all_entry_points,
        "all_snapped_points": all_snapped_points,
        "all_snap_lines": all_snap_lines,
        "selected_points": selected_points,
    }


def suspicious_snap_distance_threshold_m(entry: EvaluatedEntry) -> float:
    if entry.pixel_size_m is None:
        return 150.0
    return max(120.0, entry.pixel_size_m * 1.5)


def log_entry_level_cases(entry: EvaluatedEntry, suspicious_cases: list[dict[str, Any]]) -> None:
    if entry.status == "outside_upa_raster":
        suspicious_cases.append(
            make_case(
                code="ENTRY_OUTSIDE_UPA_RASTER",
                severity="error",
                canyon_id=entry.canyon_id,
                canyon_name=entry.canyon_name,
                message="Point d'entree hors couverture du raster UPA.",
                entry_indexes=[entry.entry_index],
                data={
                    "entryIndex": entry.entry_index,
                    "geoPointIndex": entry.geo_point_index,
                    "latitude": entry.latitude,
                    "longitude": entry.longitude,
                    "label": entry.label,
                },
            )
        )
        return

    if entry.status == "no_valid_upa_candidate":
        suspicious_cases.append(
            make_case(
                code="ENTRY_NO_VALID_UPA_CANDIDATE",
                severity="error",
                canyon_id=entry.canyon_id,
                canyon_name=entry.canyon_name,
                message="Aucune cellule UPA exploitable dans le rayon de snap.",
                entry_indexes=[entry.entry_index],
                data={
                    "entryIndex": entry.entry_index,
                    "geoPointIndex": entry.geo_point_index,
                    "latitude": entry.latitude,
                    "longitude": entry.longitude,
                    "label": entry.label,
                },
            )
        )
        return

    if entry.snap_distance_m is not None and entry.snap_distance_m > suspicious_snap_distance_threshold_m(entry):
        suspicious_cases.append(
            make_case(
                code="SNAP_DISTANCE_LARGE",
                severity="warning",
                canyon_id=entry.canyon_id,
                canyon_name=entry.canyon_name,
                message="Distance de snap importante, a verifier contre le GPS.",
                entry_indexes=[entry.entry_index],
                data={
                    "entryIndex": entry.entry_index,
                    "geoPointIndex": entry.geo_point_index,
                    "label": entry.label,
                    "snapDistanceM": entry.snap_distance_m,
                    "pixelSizeM": entry.pixel_size_m,
                    "rawUpaKm2": entry.raw_upa_km2,
                    "snappedUpaKm2": entry.snapped_upa_km2,
                    "snappedLatitude": entry.snapped_latitude,
                    "snappedLongitude": entry.snapped_longitude,
                },
            )
        )

    if (
        entry.raw_to_snapped_upa_ratio is not None
        and entry.raw_to_snapped_upa_ratio >= 10.0
        and entry.snap_distance_m is not None
        and entry.pixel_size_m is not None
        and entry.snap_distance_m > entry.pixel_size_m
    ):
        suspicious_cases.append(
            make_case(
                code="SNAP_UPA_JUMP_LARGE",
                severity="warning",
                canyon_id=entry.canyon_id,
                canyon_name=entry.canyon_name,
                message="Le snap change fortement l'UPA, possible souci GPS ou rayon trop large.",
                entry_indexes=[entry.entry_index],
                data={
                    "entryIndex": entry.entry_index,
                    "geoPointIndex": entry.geo_point_index,
                    "label": entry.label,
                    "rawUpaKm2": entry.raw_upa_km2,
                    "snappedUpaKm2": entry.snapped_upa_km2,
                    "rawToSnappedUpaRatio": entry.raw_to_snapped_upa_ratio,
                    "snapDistanceM": entry.snap_distance_m,
                },
            )
        )

    if entry.flowdir_value in {0, -1}:
        suspicious_cases.append(
            make_case(
                code="ENTRY_SNAPPED_TO_OUTLET_OR_SINK",
                severity="warning",
                canyon_id=entry.canyon_id,
                canyon_name=entry.canyon_name,
                message="Le point snappe tombe sur une cellule sans direction d'ecoulement exploitable.",
                entry_indexes=[entry.entry_index],
                data={
                    "entryIndex": entry.entry_index,
                    "geoPointIndex": entry.geo_point_index,
                    "label": entry.label,
                    "flowdirValue": entry.flowdir_value,
                    "snappedUpaKm2": entry.snapped_upa_km2,
                    "snappedLatitude": entry.snapped_latitude,
                    "snappedLongitude": entry.snapped_longitude,
                },
            )
        )


def cell_from_lonlat(raster: Any, longitude: float | None, latitude: float | None) -> Cell | None:
    if raster is None or longitude is None or latitude is None:
        return None
    return raster.point_to_cell(longitude, latitude)


def trace_downstream_to_target(
    flowdir_raster: Any,
    *,
    start_cell: Cell,
    target_cell: Cell,
    max_steps: int,
) -> bool:
    if start_cell == target_cell:
        return True

    current = start_cell
    visited: set[tuple[int, int]] = set()

    for _ in range(max_steps):
        key = (current.row, current.col)
        if key in visited:
            return False
        visited.add(key)

        direction_value = flowdir_raster.value_at_cell(current)
        if direction_value is None:
            return False

        direction_code = int(round(direction_value))
        offset = FLOW_DIRECTION_OFFSETS.get(direction_code)
        if offset is None:
            return False

        current = Cell(row=current.row + offset[0], col=current.col + offset[1])
        if current == target_cell:
            return True
        if current.row < 0 or current.row >= flowdir_raster.height or current.col < 0 or current.col >= flowdir_raster.width:
            return False

    return False


def analyze_multi_entry_canyon(
    entries: list[EvaluatedEntry],
    *,
    flowdir_raster: Any,
    max_flow_steps: int,
    suspicious_cases: list[dict[str, Any]],
) -> tuple[int | None, str]:
    valid_indices = [index for index, entry in enumerate(entries) if entry.snapped_upa_km2 is not None]
    if not valid_indices:
        return None, "no_valid_entry"
    if len(valid_indices) == 1:
        return valid_indices[0], "single_valid_entry"

    relationships: dict[tuple[int, int], bool] = {}

    if flowdir_raster is not None:
        for left_index in valid_indices:
            for right_index in valid_indices:
                if left_index == right_index:
                    continue
                left_entry = entries[left_index]
                right_entry = entries[right_index]
                left_cell = cell_from_lonlat(flowdir_raster, left_entry.snapped_longitude, left_entry.snapped_latitude)
                right_cell = cell_from_lonlat(flowdir_raster, right_entry.snapped_longitude, right_entry.snapped_latitude)
                if left_cell is None or right_cell is None:
                    relationships[(left_index, right_index)] = False
                    continue
                relationships[(left_index, right_index)] = trace_downstream_to_target(
                    flowdir_raster,
                    start_cell=left_cell,
                    target_cell=right_cell,
                    max_steps=max_flow_steps,
                )

    for left_position, left_index in enumerate(valid_indices):
        left_entry = entries[left_index]
        for right_index in valid_indices[left_position + 1 :]:
            right_entry = entries[right_index]
            if left_entry.snapped_cell == right_entry.snapped_cell:
                suspicious_cases.append(
                    make_case(
                        code="MULTI_ENTRY_SAME_SNAP_CELL",
                        severity="warning",
                        canyon_id=left_entry.canyon_id,
                        canyon_name=left_entry.canyon_name,
                        message="Deux entrees snappees sur la meme cellule.",
                        entry_indexes=[left_entry.entry_index, right_entry.entry_index],
                        data={
                            "entryIndexes": [left_entry.entry_index, right_entry.entry_index],
                            "labels": [left_entry.label, right_entry.label],
                            "snappedLatitude": left_entry.snapped_latitude,
                            "snappedLongitude": left_entry.snapped_longitude,
                        },
                    )
                )

            left_to_right = relationships.get((left_index, right_index), False)
            right_to_left = relationships.get((right_index, left_index), False)

            if left_to_right and left_entry.snapped_upa_km2 is not None and right_entry.snapped_upa_km2 is not None:
                if left_entry.snapped_upa_km2 > right_entry.snapped_upa_km2 * 1.05:
                    suspicious_cases.append(
                        make_case(
                            code="MULTI_ENTRY_UPA_ORDER_CONFLICT",
                            severity="warning",
                            canyon_id=left_entry.canyon_id,
                            canyon_name=left_entry.canyon_name,
                            message="Un point amont calcule une UPA superieure a un point aval.",
                            entry_indexes=[left_entry.entry_index, right_entry.entry_index],
                            data={
                                "upstreamEntryIndex": left_entry.entry_index,
                                "downstreamEntryIndex": right_entry.entry_index,
                                "upstreamUpaKm2": left_entry.snapped_upa_km2,
                                "downstreamUpaKm2": right_entry.snapped_upa_km2,
                                "upstreamLabel": left_entry.label,
                                "downstreamLabel": right_entry.label,
                            },
                        )
                    )
                if (
                    left_entry.elevation_m is not None
                    and right_entry.elevation_m is not None
                    and left_entry.elevation_m < right_entry.elevation_m - 10.0
                ):
                    suspicious_cases.append(
                        make_case(
                            code="MULTI_ENTRY_ELEVATION_ORDER_CONFLICT",
                            severity="warning",
                            canyon_id=left_entry.canyon_id,
                            canyon_name=left_entry.canyon_name,
                            message="Un point amont a une altitude inferieure a un point aval.",
                            entry_indexes=[left_entry.entry_index, right_entry.entry_index],
                            data={
                                "upstreamEntryIndex": left_entry.entry_index,
                                "downstreamEntryIndex": right_entry.entry_index,
                                "upstreamElevationM": left_entry.elevation_m,
                                "downstreamElevationM": right_entry.elevation_m,
                                "upstreamLabel": left_entry.label,
                                "downstreamLabel": right_entry.label,
                            },
                        )
                    )

            if right_to_left and left_entry.snapped_upa_km2 is not None and right_entry.snapped_upa_km2 is not None:
                if right_entry.snapped_upa_km2 > left_entry.snapped_upa_km2 * 1.05:
                    suspicious_cases.append(
                        make_case(
                            code="MULTI_ENTRY_UPA_ORDER_CONFLICT",
                            severity="warning",
                            canyon_id=left_entry.canyon_id,
                            canyon_name=left_entry.canyon_name,
                            message="Un point amont calcule une UPA superieure a un point aval.",
                            entry_indexes=[left_entry.entry_index, right_entry.entry_index],
                            data={
                                "upstreamEntryIndex": right_entry.entry_index,
                                "downstreamEntryIndex": left_entry.entry_index,
                                "upstreamUpaKm2": right_entry.snapped_upa_km2,
                                "downstreamUpaKm2": left_entry.snapped_upa_km2,
                                "upstreamLabel": right_entry.label,
                                "downstreamLabel": left_entry.label,
                            },
                        )
                    )

            if flowdir_raster is not None and not left_to_right and not right_to_left:
                suspicious_cases.append(
                    make_case(
                        code="MULTI_ENTRY_FLOW_DISCONNECTED",
                        severity="warning",
                        canyon_id=left_entry.canyon_id,
                        canyon_name=left_entry.canyon_name,
                        message="Deux entrees ne semblent pas etre sur la meme branche d'ecoulement.",
                        entry_indexes=[left_entry.entry_index, right_entry.entry_index],
                        data={
                            "entryIndexes": [left_entry.entry_index, right_entry.entry_index],
                            "labels": [left_entry.label, right_entry.label],
                            "upaKm2": [left_entry.snapped_upa_km2, right_entry.snapped_upa_km2],
                        },
                    )
                )

            if left_entry.label_hint == "upstream" and right_entry.label_hint == "downstream":
                if right_to_left or (
                    left_entry.snapped_upa_km2 is not None
                    and right_entry.snapped_upa_km2 is not None
                    and left_entry.snapped_upa_km2 > right_entry.snapped_upa_km2 * 1.05
                ):
                    suspicious_cases.append(
                        make_case(
                            code="MULTI_ENTRY_LABEL_ORDER_CONFLICT",
                            severity="warning",
                            canyon_id=left_entry.canyon_id,
                            canyon_name=left_entry.canyon_name,
                            message="Les labels amont/aval ne collent pas avec l'ordre hydrologique calcule.",
                            entry_indexes=[left_entry.entry_index, right_entry.entry_index],
                            data={
                                "upstreamHintEntryIndex": left_entry.entry_index,
                                "downstreamHintEntryIndex": right_entry.entry_index,
                                "upstreamHintLabel": left_entry.label,
                                "downstreamHintLabel": right_entry.label,
                                "upstreamHintUpaKm2": left_entry.snapped_upa_km2,
                                "downstreamHintUpaKm2": right_entry.snapped_upa_km2,
                            },
                        )
                    )

            if left_entry.label_hint == "downstream" and right_entry.label_hint == "upstream":
                if left_to_right or (
                    left_entry.snapped_upa_km2 is not None
                    and right_entry.snapped_upa_km2 is not None
                    and right_entry.snapped_upa_km2 > left_entry.snapped_upa_km2 * 1.05
                ):
                    suspicious_cases.append(
                        make_case(
                            code="MULTI_ENTRY_LABEL_ORDER_CONFLICT",
                            severity="warning",
                            canyon_id=left_entry.canyon_id,
                            canyon_name=left_entry.canyon_name,
                            message="Les labels amont/aval ne collent pas avec l'ordre hydrologique calcule.",
                            entry_indexes=[left_entry.entry_index, right_entry.entry_index],
                            data={
                                "upstreamHintEntryIndex": right_entry.entry_index,
                                "downstreamHintEntryIndex": left_entry.entry_index,
                                "upstreamHintLabel": right_entry.label,
                                "downstreamHintLabel": left_entry.label,
                                "upstreamHintUpaKm2": right_entry.snapped_upa_km2,
                                "downstreamHintUpaKm2": left_entry.snapped_upa_km2,
                            },
                        )
                    )

    if flowdir_raster is not None:
        dominant_indices = [
            index
            for index in valid_indices
            if all(index == other_index or relationships.get((index, other_index), False) for other_index in valid_indices)
        ]
        if len(dominant_indices) == 1:
            return dominant_indices[0], "flow_connectivity"

    selected_index = min(
        valid_indices,
        key=lambda index: (
            entries[index].snapped_upa_km2 if entries[index].snapped_upa_km2 is not None else float("inf"),
            entries[index].snap_distance_m if entries[index].snap_distance_m is not None else float("inf"),
            entries[index].entry_index,
        ),
    )
    return selected_index, "smallest_upa_fallback"


def analyze_canyons(
    *,
    canyons: dict[int, dict[str, Any]],
    entries_by_canyon: dict[int, list[EntryPoint]],
    upa_raster: Any,
    flowdir_raster: Any,
    elevation_raster: Any,
    search_radius_cells: int,
    search_radius_m: float | None,
    candidate_strategy: str,
    channel_min_upa_km2: float | None,
    max_flow_steps: int,
    only_canyon_ids: set[int] | None,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], dict[str, Any]]:
    selected_entries: list[dict[str, Any]] = []
    suspicious_cases: list[dict[str, Any]] = []
    summary_counter: Counter[str] = Counter()
    selection_reason_counter: Counter[str] = Counter()

    canyon_ids = sorted(entries_by_canyon)
    if only_canyon_ids:
        canyon_ids = [canyon_id for canyon_id in canyon_ids if canyon_id in only_canyon_ids]

    for canyon_id in canyon_ids:
        canyon = canyons[canyon_id]
        source_entries = entries_by_canyon[canyon_id]
        evaluated_entries = [
            evaluate_entry(
                entry,
                upa_raster,
                flowdir_raster,
                elevation_raster,
                search_radius_cells=search_radius_cells,
                search_radius_m=search_radius_m,
                candidate_strategy=candidate_strategy,
                channel_min_upa_km2=channel_min_upa_km2,
            )
            for entry in source_entries
        ]

        for evaluated_entry in evaluated_entries:
            log_entry_level_cases(evaluated_entry, suspicious_cases)

        selected_index, selection_reason = analyze_multi_entry_canyon(
            evaluated_entries,
            flowdir_raster=flowdir_raster,
            max_flow_steps=max_flow_steps,
            suspicious_cases=suspicious_cases,
        )
        selection_reason_counter[selection_reason] += 1

        if len(evaluated_entries) > 1 and selection_reason == "smallest_upa_fallback":
            selected_entry_for_case = evaluated_entries[selected_index] if selected_index is not None else None
            suspicious_cases.append(
                make_case(
                    code="MULTI_ENTRY_SELECTION_FALLBACK",
                    severity="warning",
                    canyon_id=canyon_id,
                    canyon_name=str(canyon.get("nomComplet") or canyon.get("nom") or canyon_id),
                    message="Selection de l'entree amont par plus petite UPA faute de relation hydrologique claire.",
                    entry_indexes=[entry.entry_index for entry in evaluated_entries],
                    data={
                        "selectedEntryIndex": selected_entry_for_case.entry_index if selected_entry_for_case is not None else None,
                        "entryCount": len(evaluated_entries),
                        "hasFlowdirRaster": flowdir_raster is not None,
                    },
                )
            )

        if selection_reason == "no_valid_entry":
            suspicious_cases.append(
                make_case(
                    code="CANYON_NO_VALID_ENTRY_RESULT",
                    severity="error",
                    canyon_id=canyon_id,
                    canyon_name=str(canyon.get("nomComplet") or canyon.get("nom") or canyon_id),
                    message="Aucune entree exploitable n'a pu etre retenue pour ce canyon.",
                    entry_indexes=[entry.entry_index for entry in evaluated_entries],
                    data={
                        "entryCount": len(evaluated_entries),
                    },
                )
            )

        selected_entry = evaluated_entries[selected_index] if selected_index is not None else None
        selected_entries.append(
            {
                "canyonId": canyon_id,
                "canyonName": canyon.get("nomComplet") or canyon.get("nom"),
                "selectedEntryIndex": selected_entry.entry_index if selected_entry is not None else None,
                "selectionReason": selection_reason,
                "entryCount": len(evaluated_entries),
                "selectedUpaKm2": selected_entry.snapped_upa_km2 if selected_entry is not None else None,
                "selectedSnapDistanceM": selected_entry.snap_distance_m if selected_entry is not None else None,
                "selectedLabel": selected_entry.label if selected_entry is not None else None,
                "entries": [asdict(entry) for entry in evaluated_entries],
            }
        )

        summary_counter["canyonsProcessed"] += 1
        summary_counter["entriesProcessed"] += len(evaluated_entries)
        summary_counter["multiEntryCanyons"] += 1 if len(evaluated_entries) > 1 else 0
        summary_counter["entriesWithValidSnap"] += sum(entry.snapped_upa_km2 is not None for entry in evaluated_entries)
        summary_counter["entriesOutsideRaster"] += sum(entry.status == "outside_upa_raster" for entry in evaluated_entries)
        summary_counter["entriesWithoutValidCandidate"] += sum(entry.status == "no_valid_upa_candidate" for entry in evaluated_entries)

    suspicious_code_counter = Counter(case["code"] for case in suspicious_cases)
    suspicious_severity_counter = Counter(case["severity"] for case in suspicious_cases)

    summary = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "canyonsProcessed": summary_counter["canyonsProcessed"],
        "entriesProcessed": summary_counter["entriesProcessed"],
        "multiEntryCanyons": summary_counter["multiEntryCanyons"],
        "entriesWithValidSnap": summary_counter["entriesWithValidSnap"],
        "entriesOutsideRaster": summary_counter["entriesOutsideRaster"],
        "entriesWithoutValidCandidate": summary_counter["entriesWithoutValidCandidate"],
        "selectionReasonCounts": dict(selection_reason_counter),
        "suspiciousSeverityCounts": dict(suspicious_severity_counter),
        "suspiciousCaseCounts": dict(suspicious_code_counter),
    }
    return selected_entries, suspicious_cases, summary


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def create_raster(path: Path | None, *, band_index: int, name: str) -> Any:
    if path is None:
        return None
    return RasterioRaster(path, band_index=band_index, name=name)


def run_self_check() -> int:
    upa = np.array(
        [
            [0.05, 0.10, 1.00, 2.00, 3.00],
            [0.05, 0.15, 1.50, 4.00, 8.00],
            [0.05, 0.10, 0.30, 1.00, 12.0],
            [0.01, 0.05, 0.10, 0.50, 20.0],
            [0.01, 0.01, 0.05, 0.20, 30.0],
        ],
        dtype=float,
    )
    flowdir = np.array(
        [
            [0, 0, 2, 2, 4],
            [0, 0, 1, 2, 4],
            [0, 0, 0, 1, 4],
            [0, 0, 0, 0, 4],
            [0, 0, 0, 0, 0],
        ],
        dtype=float,
    )
    elevation = np.array(
        [
            [1400, 1380, 1320, 1280, 1220],
            [1390, 1375, 1300, 1250, 1180],
            [1380, 1360, 1290, 1210, 1140],
            [1370, 1355, 1280, 1190, 1100],
            [1360, 1340, 1270, 1180, 1080],
        ],
        dtype=float,
    )

    upa_raster = ArrayRaster(
        upa,
        top_left_lon=6.0,
        top_left_lat=45.0,
        pixel_size_deg=0.001,
        name="upa",
    )
    flowdir_raster = ArrayRaster(
        flowdir,
        top_left_lon=6.0,
        top_left_lat=45.0,
        pixel_size_deg=0.001,
        name="dir",
    )
    elevation_raster = ArrayRaster(
        elevation,
        top_left_lon=6.0,
        top_left_lat=45.0,
        pixel_size_deg=0.001,
        name="elv",
    )

    canyons = {
        1: {"id": 1, "nomComplet": "Canyon de test"},
    }
    entries_by_canyon = {
        1: [
            EntryPoint(
                canyon_id=1,
                canyon_name="Canyon de test",
                entry_index=1,
                geo_point_index=10,
                latitude=44.9991,
                longitude=6.0021,
                label="depart aval",
            ),
            EntryPoint(
                canyon_id=1,
                canyon_name="Canyon de test",
                entry_index=2,
                geo_point_index=11,
                latitude=44.9980,
                longitude=6.0041,
                label="depart amont",
            ),
        ]
    }

    selected_entries, suspicious_cases, summary = analyze_canyons(
        canyons=canyons,
        entries_by_canyon=entries_by_canyon,
        upa_raster=upa_raster,
        flowdir_raster=flowdir_raster,
        elevation_raster=elevation_raster,
        search_radius_cells=1,
        search_radius_m=None,
        candidate_strategy="max_upa",
        channel_min_upa_km2=None,
        max_flow_steps=100,
        only_canyon_ids=None,
    )

    if summary["canyonsProcessed"] != 1:
        raise AssertionError("self-check expected one canyon")
    if selected_entries[0]["selectedEntryIndex"] != 1:
        raise AssertionError("self-check expected entry 1 to be selected as most upstream")
    if summary["selectionReasonCounts"].get("flow_connectivity") != 1:
        raise AssertionError("self-check expected flow connectivity selection")
    if not any(case["code"] == "MULTI_ENTRY_LABEL_ORDER_CONFLICT" for case in suspicious_cases):
        raise AssertionError("self-check expected a label order conflict")

    geojson_outputs = build_geojson_outputs(selected_entries, suspicious_cases)
    if len(geojson_outputs["all_entry_points"]) != 2:
        raise AssertionError("self-check expected two original entry point features")
    if len(geojson_outputs["selected_points"]) != 1:
        raise AssertionError("self-check expected one selected snapped point feature")

    print("Self-check OK")
    return 0


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Calcule une surface amont (UPA) au point d'entree, choisit l'entree la plus en amont "
            "par canyon, et journalise les cas suspicieux."
        )
    )
    parser.add_argument("--canyons-json", type=Path, default=Path("offline-data/full/room-import/canyons.json"))
    parser.add_argument("--geo-points-json", type=Path, default=Path("offline-data/full/room-import/geo_points.json"))
    parser.add_argument("--upa-raster", type=Path)
    parser.add_argument("--upa-band", type=int, default=1)
    parser.add_argument("--flowdir-raster", type=Path)
    parser.add_argument("--flowdir-band", type=int, default=1)
    parser.add_argument("--elevation-raster", type=Path)
    parser.add_argument("--elevation-band", type=int, default=1)
    parser.add_argument("--search-radius-cells", type=int, default=2)
    parser.add_argument("--search-radius-m", type=float)
    parser.add_argument(
        "--candidate-strategy",
        choices=["max_upa", "nearest_channel"],
        default="max_upa",
    )
    parser.add_argument("--channel-min-upa-km2", type=float)
    parser.add_argument("--max-flow-steps", type=int, default=50_000)
    parser.add_argument("--output-dir", type=Path, default=Path("build/watersheds"))
    parser.add_argument("--only-canyon-id", dest="only_canyon_ids", type=int, action="append")
    parser.add_argument("--self-check", action="store_true")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    if args.self_check:
        return run_self_check()

    if args.upa_raster is None:
        raise SystemExit("--upa-raster est requis sauf en mode --self-check")

    canyons = load_canyons(args.canyons_json)
    entries_by_canyon = load_entry_points(args.geo_points_json, canyons)

    upa_raster = create_raster(args.upa_raster, band_index=args.upa_band, name="upa")
    flowdir_raster = create_raster(args.flowdir_raster, band_index=args.flowdir_band, name="dir")
    elevation_raster = create_raster(args.elevation_raster, band_index=args.elevation_band, name="elv")

    try:
        selected_entries, suspicious_cases, summary = analyze_canyons(
            canyons=canyons,
            entries_by_canyon=entries_by_canyon,
            upa_raster=upa_raster,
            flowdir_raster=flowdir_raster,
            elevation_raster=elevation_raster,
            search_radius_cells=args.search_radius_cells,
            search_radius_m=args.search_radius_m,
            candidate_strategy=args.candidate_strategy,
            channel_min_upa_km2=args.channel_min_upa_km2,
            max_flow_steps=args.max_flow_steps,
            only_canyon_ids=set(args.only_canyon_ids) if args.only_canyon_ids else None,
        )
    finally:
        upa_raster.close()
        if flowdir_raster is not None:
            flowdir_raster.close()
        if elevation_raster is not None:
            elevation_raster.close()

    run_metadata = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "input": {
            "canyonsJson": str(args.canyons_json),
            "geoPointsJson": str(args.geo_points_json),
            "upaRaster": str(args.upa_raster),
            "flowdirRaster": str(args.flowdir_raster) if args.flowdir_raster else None,
            "elevationRaster": str(args.elevation_raster) if args.elevation_raster else None,
        },
        "parameters": {
            "searchRadiusCells": args.search_radius_cells,
            "searchRadiusM": args.search_radius_m,
            "candidateStrategy": args.candidate_strategy,
            "channelMinUpaKm2": args.channel_min_upa_km2,
            "maxFlowSteps": args.max_flow_steps,
            "onlyCanyonIds": args.only_canyon_ids or [],
        },
    }

    output_dir = args.output_dir
    geojson_outputs = build_geojson_outputs(selected_entries, suspicious_cases)
    write_json(output_dir / "run_metadata.json", run_metadata)
    write_json(output_dir / "selected_entries.json", selected_entries)
    write_json(output_dir / "suspicious_cases.json", suspicious_cases)
    write_json(output_dir / "summary.json", summary)
    write_geojson(output_dir / "all_entry_points.geojson", geojson_outputs["all_entry_points"])
    write_geojson(output_dir / "all_snapped_points.geojson", geojson_outputs["all_snapped_points"])
    write_geojson(output_dir / "entry_snap_lines.geojson", geojson_outputs["all_snap_lines"])
    write_geojson(output_dir / "selected_entry_points.geojson", geojson_outputs["selected_points"])

    print(f"Selected entries written to {output_dir / 'selected_entries.json'}")
    print(f"Suspicious cases written to {output_dir / 'suspicious_cases.json'}")
    print(f"Summary written to {output_dir / 'summary.json'}")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
