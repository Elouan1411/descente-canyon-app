from __future__ import annotations

import json
import os
import subprocess
import sys
import time
import unicodedata
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError

from shapely.geometry import Point, Polygon, shape

from cli_tools import default_ogr2ogr, resolve_executable


OVERPASS_URLS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://lz4.overpass-api.de/api/interpreter",
]
MIN_INTERVAL_SEC = 8.0
OVERPASS_FAILURE_COOLDOWN_SEC = 3600.0
DEFAULT_OFFLINE_GPKG = Path("build/watersheds/osm-regulation/regulation_features.gpkg")

KEYWORDS = [
    ("waterway", ["dam", "weir", "canal", "pressurised", "sluice_gate"]),
    ("water", ["reservoir"]),
    ("landuse", ["reservoir"]),
    ("man_made", ["pipeline"]),
    ("power", ["plant"]),
]


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def acquire_lock(lock_path: Path, *, timeout_sec: int = 1800) -> int:
    started = time.time()
    while True:
        try:
            return os.open(str(lock_path), os.O_CREAT | os.O_EXCL | os.O_RDWR)
        except FileExistsError:
            if time.time() - started > timeout_sec:
                raise SystemExit(f"Timeout waiting for OSM regulation lock: {lock_path}")
            time.sleep(2)


def release_lock(lock_fd: int, lock_path: Path) -> None:
    os.close(lock_fd)
    lock_path.unlink(missing_ok=True)


def geometry_from_overpass(element: dict[str, Any]):
    geom = element.get("geometry")
    if element["type"] == "node":
        return Point(element["lon"], element["lat"])
    if not geom:
        return None
    coords = [(point["lon"], point["lat"]) for point in geom]
    tags = element.get("tags", {})
    if len(coords) >= 4 and coords[0] == coords[-1] and ("water" in tags or "landuse" in tags or "natural" in tags):
        try:
            return Polygon(coords)
        except Exception:
            pass
    from shapely.geometry import LineString
    return LineString(coords)


def feature_tags_summary(tags: dict[str, Any]) -> dict[str, Any]:
    keep = [
        "name",
        "waterway",
        "water",
        "landuse",
        "man_made",
        "power",
        "usage",
        "operator",
        "plant:source",
        "generator:source",
    ]
    return {key: tags.get(key) for key in keep if key in tags}


def parse_other_tags(value: str | None) -> dict[str, str]:
    if not value:
        return {}
    result = {}
    for chunk in value.split('","'):
        text = chunk.strip('"')
        if '=>"' not in text:
            continue
        key, val = text.split('=>"', 1)
        result[key] = val.strip('"')
    return result


def overpass_query(bbox: tuple[float, float, float, float], *, cache_dir: Path) -> dict[str, Any]:
    south, west, north, east = bbox[1], bbox[0], bbox[3], bbox[2]
    parts = []
    for key, values in KEYWORDS:
        for value in values:
            parts.append(f'nwr["{key}"="{value}"]({south},{west},{north},{east});')
    ql = f"[out:json][timeout:120];({''.join(parts)});out geom;"
    data = urllib.parse.urlencode({"data": ql}).encode("utf-8")
    last_error: Exception | None = None
    lock_path = cache_dir / ".overpass.lock"
    stamp_path = cache_dir / ".overpass.last"
    cache_dir.mkdir(parents=True, exist_ok=True)
    lock_fd = acquire_lock(lock_path)
    try:
        for attempt in range(1, 5):
            if stamp_path.exists():
                try:
                    elapsed = time.time() - float(stamp_path.read_text(encoding="utf-8"))
                    if elapsed < MIN_INTERVAL_SEC:
                        time.sleep(MIN_INTERVAL_SEC - elapsed)
                except Exception:
                    pass
            for url in OVERPASS_URLS:
                try:
                    request = urllib.request.Request(url, data=data, headers={"User-Agent": "Mozilla/5.0"})
                    with urllib.request.urlopen(request, timeout=180) as response:
                        raw = response.read().decode("utf-8", "ignore")
                        stamp_path.write_text(str(time.time()), encoding="utf-8")
                        return json.loads(raw)
                except (HTTPError, URLError, json.JSONDecodeError) as exc:
                    last_error = exc
                    stamp_path.write_text(str(time.time()), encoding="utf-8")
                    continue
            time.sleep(min(60, 5 * attempt))
    finally:
        release_lock(lock_fd, lock_path)
    raise SystemExit(f"Overpass query failed: {last_error}")


def query_osm_regulation(
    *,
    canyon_id: int,
    canyon_name: str,
    country: str,
    watershed_geometry: dict[str, Any],
    cache_dir: Path,
) -> dict[str, Any]:
    cache_path = cache_dir / f"{canyon_id}.json"
    if cache_path.exists():
        return read_json(cache_path)

    offline_path = DEFAULT_OFFLINE_GPKG if DEFAULT_OFFLINE_GPKG.exists() else None
    country_slug = slugify_country(country)
    country_offline_path = Path("build/watersheds/osm-regulation") / country_slug / "regulation_features.gpkg"
    if country_offline_path.exists():
        offline_path = country_offline_path
    cooldown_path = cache_dir / ".overpass.disabled_until"

    if offline_path and cooldown_path.exists():
        try:
            disabled_until = float(cooldown_path.read_text(encoding="utf-8"))
            if time.time() < disabled_until:
                payload = query_osm_regulation_offline(bbox=shape(watershed_geometry).bounds, offline_gpkg=str(offline_path), ogr2ogr=None)
                matches = offline_features_to_matches(shape(watershed_geometry), payload.get("features", []))
                result = {
                    "canyonId": canyon_id,
                    "canyonName": canyon_name,
                    "status": "ok",
                    "source": "offline_fallback",
                    "matchCount": len(matches),
                    "elapsedSec": 0.0,
                    "matches": matches,
                }
                write_json(cache_path, result)
                return result
        except Exception:
            pass

    basin_geom = shape(watershed_geometry)
    bbox = basin_geom.bounds
    started = time.perf_counter()
    try:
        payload = overpass_query(bbox, cache_dir=cache_dir)
        elapsed = time.perf_counter() - started
    except Exception as exc:
        if offline_path is None:
            try:
                ensure_country_offline_extract(country=country, output_gpkg=country_offline_path)
                if country_offline_path.exists():
                    offline_path = country_offline_path
            except Exception:
                offline_path = None
        if offline_path:
            cooldown_path.write_text(str(time.time() + OVERPASS_FAILURE_COOLDOWN_SEC), encoding="utf-8")
            payload = query_osm_regulation_offline(bbox=bbox, offline_gpkg=str(offline_path), ogr2ogr=None)
            matches = offline_features_to_matches(basin_geom, payload.get("features", []))
            result = {
                "canyonId": canyon_id,
                "canyonName": canyon_name,
                "status": "ok",
                "source": "offline_fallback",
                "matchCount": len(matches),
                "elapsedSec": round(time.perf_counter() - started, 3),
                "matches": matches,
            }
            write_json(cache_path, result)
            return result
        return {
            "canyonId": canyon_id,
            "canyonName": canyon_name,
            "status": f"error:{type(exc).__name__}",
            "matchCount": 0,
            "elapsedSec": round(time.perf_counter() - started, 3),
            "matches": [],
            "error": str(exc),
        }

    matches = _filter_matches(basin_geom, payload.get("elements", []))

    payload = {
        "canyonId": canyon_id,
        "canyonName": canyon_name,
        "status": "ok",
        "source": "overpass",
        "matchCount": len(matches),
        "elapsedSec": round(elapsed, 3),
        "matches": matches,
    }
    write_json(cache_path, payload)
    return payload


def ensure_country_offline_extract(*, country: str, output_gpkg: Path) -> None:
    if output_gpkg.exists():
        return
    output_gpkg.parent.mkdir(parents=True, exist_ok=True)
    command = [
        sys.executable,
        "scripts/prepare_osm_regulation_extract.py",
        "--country",
        country,
        "--output-gpkg",
        str(output_gpkg),
    ]
    subprocess.run(command, check=True)


def slugify_country(country: str) -> str:
    normalized = unicodedata.normalize("NFKD", country)
    normalized = "".join(ch for ch in normalized if not unicodedata.combining(ch))
    normalized = normalized.lower().replace(" ", "-").replace(",", "")
    return normalized


def _filter_matches(basin_geom, elements: list[dict[str, Any]]) -> list[dict[str, Any]]:
    matches = []
    for element in elements:
        geom = geometry_from_overpass(element)
        if geom is None or geom.is_empty or not basin_geom.intersects(geom):
            continue
        tags = element.get("tags", {})
        matches.append(
            {
                "elementType": element["type"],
                "elementId": element["id"],
                "tags": feature_tags_summary(tags),
                "geometryType": geom.geom_type,
            }
        )
    return matches


def query_osm_regulation_offline(*, bbox: tuple[float, float, float, float], offline_gpkg: str, ogr2ogr: str | None) -> dict[str, Any]:
    ogr2ogr_bin = resolve_executable(ogr2ogr or default_ogr2ogr(), extra_candidates=[default_ogr2ogr()])
    west, south, east, north = bbox
    command = [
        ogr2ogr_bin,
        "-f",
        "GeoJSON",
        "/vsistdout/",
        str(Path(offline_gpkg).resolve()),
        "regulation_features",
        "-spat",
        str(west),
        str(south),
        str(east),
        str(north),
    ]
    completed = subprocess.run(command, check=True, capture_output=True, text=True)
    return json.loads(completed.stdout)


def offline_features_to_matches(basin_geom, features: list[dict[str, Any]]) -> list[dict[str, Any]]:
    matches = []
    for feature in features:
        geom = shape(feature.get('geometry'))
        if geom.is_empty or not basin_geom.intersects(geom):
            continue
        properties = feature.get('properties', {})
        tags = {}
        for key in ['name', 'waterway', 'barrier', 'man_made', 'landuse', 'natural']:
            if key in properties and properties[key] not in (None, ''):
                tags[key] = properties[key]
        tags.update(parse_other_tags(properties.get('other_tags')))
        matches.append(
            {
                'elementType': 'feature',
                'elementId': properties.get('osm_id'),
                'tags': feature_tags_summary(tags),
                'geometryType': geom.geom_type,
            }
        )
    return matches


def summarize_osm_regulation(matches: list[dict[str, Any]]) -> dict[str, Any]:
    dam_count = 0
    weir_count = 0
    reservoir_count = 0
    canal_count = 0
    penstock_count = 0
    hydropower_plant_count = 0
    operator_edf_count = 0
    example_names = []

    for match in matches:
        tags = match.get("tags", {})
        waterway = tags.get("waterway")
        water = tags.get("water")
        landuse = tags.get("landuse")
        man_made = tags.get("man_made")
        power = tags.get("power")
        usage = tags.get("usage")
        operator = (tags.get("operator") or "").lower()
        name = tags.get("name")
        if name and len(example_names) < 5:
            example_names.append(name)

        if waterway == "dam":
            dam_count += 1
        if waterway == "weir":
            weir_count += 1
        if water == "reservoir" or landuse == "reservoir":
            reservoir_count += 1
        if waterway == "canal":
            canal_count += 1
        if waterway == "pressurised" or (man_made == "pipeline" and usage in {"penstock", "headrace", "transmission", "spillway"}):
            penstock_count += 1
        if power == "plant" and ((tags.get("plant:source") or tags.get("generator:source") or "").lower() == "hydro"):
            hydropower_plant_count += 1
        if "edf" in operator:
            operator_edf_count += 1

    likely_hydropower_scheme = (dam_count + weir_count > 0) and (penstock_count > 0 or hydropower_plant_count > 0)
    regulation_present = (dam_count + weir_count + reservoir_count + canal_count + penstock_count + hydropower_plant_count) > 0
    if likely_hydropower_scheme or (hydropower_plant_count > 0 and operator_edf_count > 0):
        confidence = "high"
    elif regulation_present:
        confidence = "medium"
    else:
        confidence = "none"

    return {
        "osmRegulationStatus": "ok",
        "osmRegulationPresent": regulation_present,
        "osmDamCountUpstream": dam_count,
        "osmWeirCountUpstream": weir_count,
        "osmReservoirCountUpstream": reservoir_count,
        "osmCanalCountUpstream": canal_count,
        "osmPenstockCountUpstream": penstock_count,
        "osmHydropowerPlantCountUpstream": hydropower_plant_count,
        "osmOperatorEdfCountUpstream": operator_edf_count,
        "osmLikelyHydropowerScheme": likely_hydropower_scheme,
        "osmRegulationConfidence": confidence,
        "osmExampleNames": example_names,
    }
