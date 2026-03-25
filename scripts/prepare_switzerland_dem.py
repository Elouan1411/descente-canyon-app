from __future__ import annotations

import argparse
import json
import subprocess
import time
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any
from urllib.error import URLError

from rasterio.warp import transform

from cli_tools import default_gdalbuildvrt, resolve_executable


STAC_SEARCH_URL = "https://data.geo.admin.ch/api/stac/v1/search"


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Prepare swissALTI3D DEM tiles around one or more canyon points.")
    parser.add_argument("--point", action="append", required=True, help="Point as lat,lon")
    parser.add_argument("--buffer-km", type=float, default=10.0)
    parser.add_argument("--output-dir", type=Path, default=Path("build/watersheds/switzerland-national-dem"))
    parser.add_argument("--gdalbuildvrt", default=default_gdalbuildvrt())
    return parser.parse_args()


def parse_points(values: list[str]) -> list[tuple[float, float]]:
    result = []
    for value in values:
        lat_text, lon_text = value.split(",", 1)
        result.append((float(lat_text), float(lon_text)))
    return result


def bbox_for_points(points: list[tuple[float, float]], buffer_km: float) -> tuple[float, float, float, float]:
    lats = [p[0] for p in points]
    lons = [p[1] for p in points]
    xs, ys = transform("EPSG:4326", "EPSG:2056", lons, lats)
    min_x = min(xs) - buffer_km * 1000.0
    max_x = max(xs) + buffer_km * 1000.0
    min_y = min(ys) - buffer_km * 1000.0
    max_y = max(ys) + buffer_km * 1000.0
    out_lons, out_lats = transform("EPSG:2056", "EPSG:4326", [min_x, max_x], [min_y, max_y])
    return min(out_lons), min(out_lats), max(out_lons), max(out_lats)


def fetch_items(bbox: tuple[float, float, float, float]) -> list[dict[str, Any]]:
    query = urllib.parse.urlencode(
        {
            "collections": "ch.swisstopo.swissalti3d",
            "bbox": ",".join(str(v) for v in bbox),
            "limit": 100,
        }
    )
    next_url = f"{STAC_SEARCH_URL}?{query}"
    items: list[dict[str, Any]] = []
    while next_url:
        last_error: Exception | None = None
        for attempt in range(1, 4):
            try:
                with urllib.request.urlopen(next_url, timeout=120) as response:
                    payload = json.load(response)
                break
            except URLError as exc:
                last_error = exc
                time.sleep(5 * attempt)
        else:
            raise SystemExit(f"Swiss STAC request failed: {last_error}")
        items.extend(payload.get("features", []))
        next_url = None
        for link in payload.get("links", []):
            if link.get("rel") == "next":
                next_url = link.get("href")
                break
    return items


def tile_urls(items: list[dict[str, Any]]) -> list[str]:
    urls = []
    for item in items:
        for asset_name, asset in item.get("assets", {}).items():
            if asset_name.endswith("_2_2056_5728.tif"):
                urls.append("/vsicurl/" + asset["href"])
                break
    return sorted(set(urls))


def build_vrt(gdalbuildvrt: str, urls: list[str], vrt_path: Path) -> None:
    if not urls:
        raise SystemExit("No swissALTI3D 2m tiles found for requested bbox")
    vrt_path.parent.mkdir(parents=True, exist_ok=True)
    input_list = vrt_path.with_suffix(".txt")
    input_list.write_text("\n".join(urls), encoding="utf-8")
    subprocess.run([gdalbuildvrt, "-input_file_list", str(input_list), str(vrt_path)], check=True)


def main() -> int:
    args = parse_args()
    points = parse_points(args.point)
    bbox = bbox_for_points(points, args.buffer_km)
    items = fetch_items(bbox)
    urls = tile_urls(items)
    gdalbuildvrt = resolve_executable(args.gdalbuildvrt, extra_candidates=[default_gdalbuildvrt()])
    vrt_path = args.output_dir / "vrt" / "_all_downloaded.vrt"
    build_vrt(gdalbuildvrt, urls, vrt_path)
    write_json(args.output_dir / "selected_tiles.json", {"bbox": bbox, "tileCount": len(urls), "urls": urls})
    print(json.dumps({"tileCount": len(urls), "vrt": str(vrt_path)}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
