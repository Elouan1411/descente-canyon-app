from __future__ import annotations

import argparse
import json
import time
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError

from shapely.geometry import LineString, MultiLineString, MultiPolygon, Point, Polygon, shape


OVERPASS_URLS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://lz4.overpass-api.de/api/interpreter",
]
KEYWORDS = [
    ("waterway", ["dam", "weir", "canal", "pressurised"]),
    ("water", ["reservoir"]),
    ("landuse", ["reservoir"]),
    ("man_made", ["pipeline"]),
    ("power", ["plant"]),
]


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Probe OSM hydraulic features intersecting canyon watershed polygons.")
    parser.add_argument("--watersheds-json", type=Path, default=Path("offline-data/full/room-import/watersheds.json"))
    parser.add_argument("--canyons-json", type=Path, default=Path("offline-data/full/room-import/canyons.json"))
    parser.add_argument("--canyon-id", type=int, action="append", required=True)
    parser.add_argument("--output", type=Path, default=Path("build/watershed-review/osm-regulation-probe.json"))
    return parser.parse_args()


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
            return LineString(coords)
    return LineString(coords)


def overpass_query(bbox: tuple[float, float, float, float]) -> dict[str, Any]:
    south, west, north, east = bbox[1], bbox[0], bbox[3], bbox[2]
    parts = []
    for key, values in KEYWORDS:
        for value in values:
            parts.append(f'nwr["{key}"="{value}"]({south},{west},{north},{east});')
    ql = f"[out:json][timeout:120];({''.join(parts)});out geom;"
    data = urllib.parse.urlencode({"data": ql}).encode("utf-8")
    last_error: Exception | None = None
    for attempt in range(1, 5):
        for url in OVERPASS_URLS:
            try:
                request = urllib.request.Request(url, data=data, headers={"User-Agent": "Mozilla/5.0"})
                with urllib.request.urlopen(request, timeout=180) as response:
                    raw = response.read().decode("utf-8", "ignore")
                    return json.loads(raw)
            except (HTTPError, URLError) as exc:
                last_error = exc
                continue
            except json.JSONDecodeError as exc:
                last_error = exc
                continue
        time.sleep(min(60, 5 * attempt))
    raise SystemExit(f"Overpass query failed: {last_error}")


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


def main() -> int:
    args = parse_args()
    watersheds = {item["canyonId"]: item for item in load_json(args.watersheds_json)}
    canyons = {item["id"]: item for item in load_json(args.canyons_json)}

    results = []
    for canyon_id in args.canyon_id:
        watershed = watersheds.get(canyon_id)
        canyon = canyons.get(canyon_id)
        if watershed is None or canyon is None:
            results.append({"canyonId": canyon_id, "status": "missing_watershed_or_canyon"})
            continue

        basin_geom = shape(watershed["geometry"])
        bbox = basin_geom.bounds
        started = time.perf_counter()
        payload = overpass_query(bbox)
        elapsed = time.perf_counter() - started

        matches = []
        for element in payload.get("elements", []):
            geom = geometry_from_overpass(element)
            if geom is None or geom.is_empty:
                continue
            if not basin_geom.intersects(geom):
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

        results.append(
            {
                "canyonId": canyon_id,
                "canyonName": canyon.get("nomComplet") or canyon.get("nom"),
                "matchCount": len(matches),
                "elapsedSec": round(elapsed, 3),
                "matches": matches,
            }
        )

    write_json(args.output, results)
    print(json.dumps(results, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
