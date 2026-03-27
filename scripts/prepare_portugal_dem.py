from __future__ import annotations

import argparse
import json
import subprocess
import time
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError

from rasterio.warp import transform

from cli_tools import default_gdalbuildvrt, resolve_executable


ITEMS_URL = "https://cdd.dgterritorio.gov.pt/dgt-be/v1/collections/MDT-2m/items"


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Prepare Portugal DGT MDT-2m DEM tiles around one or more canyon points.")
    parser.add_argument("--point", action="append", required=True, help="Point as lat,lon")
    parser.add_argument("--buffer-km", type=float, default=15.0)
    parser.add_argument("--output-dir", type=Path, default=Path("build/watersheds/portugal-national-dem"))
    parser.add_argument("--cache-dir", type=Path)
    parser.add_argument("--gdalbuildvrt", default=default_gdalbuildvrt())
    return parser.parse_args()


def parse_points(values: list[str]) -> list[tuple[float, float]]:
    return [(float(v.split(",", 1)[0]), float(v.split(",", 1)[1])) for v in values]


def bbox_for_points(points: list[tuple[float, float]], buffer_km: float) -> tuple[float, float, float, float]:
    lats = [p[0] for p in points]
    lons = [p[1] for p in points]
    xs, ys = transform("EPSG:4326", "EPSG:3763", lons, lats)
    min_x = min(xs) - buffer_km * 1000.0
    max_x = max(xs) + buffer_km * 1000.0
    min_y = min(ys) - buffer_km * 1000.0
    max_y = max(ys) + buffer_km * 1000.0
    out_lons, out_lats = transform("EPSG:3763", "EPSG:4326", [min_x, max_x], [min_y, max_y])
    return min(out_lons), min(out_lats), max(out_lons), max(out_lats)


def fetch_items(bbox: tuple[float, float, float, float]) -> list[dict[str, Any]]:
    query = urllib.parse.urlencode({"f": "json", "limit": 100, "bbox": ",".join(str(v) for v in bbox)})
    next_url = f"{ITEMS_URL}?{query}"
    items: list[dict[str, Any]] = []
    seen_ids: set[str] = set()
    while next_url:
        last_error: Exception | None = None
        for attempt in range(1, 5):
            try:
                request = urllib.request.Request(next_url, headers={"User-Agent": "Mozilla/5.0"})
                with urllib.request.urlopen(request, timeout=120) as response:
                    payload = json.load(response)
                break
            except URLError as exc:
                last_error = exc
                time.sleep(5 * attempt)
        else:
            raise SystemExit(f"Portugal DGT item request failed: {last_error}")

        data = payload.get("data", {})
        for feature in data.get("features", []):
            feature_id = feature.get("id")
            if feature_id and feature_id not in seen_ids:
                seen_ids.add(feature_id)
                items.append(feature)

        next_url = None
        for link in data.get("links", []):
            if link.get("rel") == "next":
                next_url = link.get("href")
                break
    return items


def tile_assets(items: list[dict[str, Any]]) -> list[tuple[str, str]]:
    assets: list[tuple[str, str]] = []
    for item in items:
        asset = (item.get("assets") or {}).get("data")
        if not asset:
            continue
        href = asset.get("href")
        if not href:
            continue
        filename = f"{item['id']}.tif"
        assets.append((filename, href))
    return sorted(set(assets))


def download_file(url: str, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() and destination.stat().st_size > 0:
        return
    temp_path = destination.with_suffix(destination.suffix + ".part")
    last_error: Exception | None = None
    for attempt in range(1, 6):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(request, timeout=300) as response, open(temp_path, "wb") as handle:
                while True:
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    handle.write(chunk)
            temp_path.replace(destination)
            return
        except (HTTPError, URLError) as exc:
            last_error = exc
            temp_path.unlink(missing_ok=True)
            if isinstance(exc, HTTPError) and exc.code not in {429, 500, 502, 503, 504}:
                raise
            time.sleep(min(60, 5 * attempt))
    raise SystemExit(f"Portugal DEM download failed: {last_error}")


def build_vrt(gdalbuildvrt: str, tif_paths: list[Path], vrt_path: Path) -> None:
    if not tif_paths:
        raise SystemExit("No Portugal DGT MDT-2m tiles found for requested bbox")
    vrt_path.parent.mkdir(parents=True, exist_ok=True)
    input_list = vrt_path.with_suffix(".txt")
    input_list.write_text("\n".join(str(path) for path in tif_paths), encoding="utf-8")
    subprocess.run([gdalbuildvrt, "-input_file_list", str(input_list), str(vrt_path)], check=True)


def main() -> int:
    args = parse_args()
    points = parse_points(args.point)
    bbox = bbox_for_points(points, args.buffer_km)
    items = fetch_items(bbox)
    assets = tile_assets(items)
    gdalbuildvrt = resolve_executable(args.gdalbuildvrt, extra_candidates=[default_gdalbuildvrt()])
    raw_dir = (args.cache_dir or (args.output_dir / "raw")).resolve()
    tif_paths = []
    for filename, href in assets:
        path = raw_dir / filename
        download_file(href, path)
        tif_paths.append(path)
    vrt_path = args.output_dir / "vrt" / "_all_downloaded.vrt"
    build_vrt(gdalbuildvrt, tif_paths, vrt_path)
    write_json(args.output_dir / "selected_tiles.json", {"bbox": bbox, "tileCount": len(tif_paths), "paths": [str(path) for path in tif_paths]})
    print(json.dumps({"tileCount": len(tif_paths), "vrt": str(vrt_path)}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
