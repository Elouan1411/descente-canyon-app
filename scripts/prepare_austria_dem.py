from __future__ import annotations

import argparse
import json
import subprocess
import time
import urllib.request
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError

from rasterio.warp import transform

from cli_tools import default_gdalbuildvrt, resolve_executable


AUSTRIA_TILE_DATE = "20240915"


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Prepare Austrian BEV ALS DEM tiles around canyon points.")
    parser.add_argument("--point", action="append", required=True, help="Point as lat,lon")
    parser.add_argument("--buffer-km", type=float, default=20.0)
    parser.add_argument("--output-dir", type=Path, default=Path("build/watersheds/austria-national-dem"))
    parser.add_argument("--cache-dir", type=Path)
    parser.add_argument("--gdalbuildvrt", default=default_gdalbuildvrt())
    return parser.parse_args()


def parse_points(values: list[str]) -> list[tuple[float, float]]:
    return [(float(v.split(",", 1)[0]), float(v.split(",", 1)[1])) for v in values]


def intersecting_tiles(points: list[tuple[float, float]], buffer_km: float) -> list[tuple[str, str]]:
    lats = [p[0] for p in points]
    lons = [p[1] for p in points]
    xs, ys = transform("EPSG:4326", "EPSG:3035", lons, lats)
    min_x = min(xs) - buffer_km * 1000.0
    max_x = max(xs) + buffer_km * 1000.0
    min_y = min(ys) - buffer_km * 1000.0
    max_y = max(ys) + buffer_km * 1000.0
    east_start = int(min_x // 50000) * 50000
    east_end = int(max_x // 50000) * 50000
    north_start = int(min_y // 50000) * 50000
    north_end = int(max_y // 50000) * 50000
    urls = []
    for east in range(east_start, east_end + 1, 50000):
        for north in range(north_start, north_end + 1, 50000):
            filename = f"ALS_DTM_CRS3035RES50000mN{north}E{east}.tif"
            urls.append(
                (
                    filename,
                    "https://data.bev.gv.at/download/ALS/DTM/"
                    f"{AUSTRIA_TILE_DATE}/{filename}",
                )
            )
    return sorted(set(urls))


def download_file(url: str, destination: Path) -> bool:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() and destination.stat().st_size > 0:
        return True
    temp_path = destination.with_suffix(destination.suffix + ".part")
    last_error: Exception | None = None
    for attempt in range(1, 5):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(request, timeout=300) as response, open(temp_path, "wb") as handle:
                while True:
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    handle.write(chunk)
            temp_path.replace(destination)
            return True
        except (HTTPError, URLError) as exc:
            last_error = exc
            temp_path.unlink(missing_ok=True)
            if isinstance(exc, HTTPError):
                if exc.code == 404:
                    return False
                if exc.code not in {429, 500, 502, 503, 504}:
                    raise
            time.sleep(5 * attempt)
    raise SystemExit(f"Austria DEM download failed: {last_error}")


def build_vrt(gdalbuildvrt: str, tif_paths: list[Path], vrt_path: Path) -> None:
    if not tif_paths:
        raise SystemExit("No Austrian ALS tiles resolved")
    vrt_path.parent.mkdir(parents=True, exist_ok=True)
    input_list = vrt_path.with_suffix(".txt")
    input_list.write_text("\n".join(str(path) for path in tif_paths), encoding="utf-8")
    subprocess.run([gdalbuildvrt, "-input_file_list", str(input_list), str(vrt_path)], check=True)


def main() -> int:
    args = parse_args()
    points = parse_points(args.point)
    tiles = intersecting_tiles(points, args.buffer_km)
    gdalbuildvrt = resolve_executable(args.gdalbuildvrt, extra_candidates=[default_gdalbuildvrt()])
    raw_dir = (args.cache_dir or (args.output_dir / "raw")).resolve()
    tif_paths = []
    missing = []
    for filename, url in tiles:
        path = raw_dir / filename
        if download_file(url, path):
            tif_paths.append(path)
        else:
            missing.append({"filename": filename, "url": url})
    vrt_path = args.output_dir / "vrt" / "_all_downloaded.vrt"
    build_vrt(gdalbuildvrt, tif_paths, vrt_path)
    write_json(
        args.output_dir / "selected_tiles.json",
        {
            "tileCount": len(tiles),
            "availableTileCount": len(tif_paths),
            "missingTileCount": len(missing),
            "missing": missing,
            "tiles": [{"filename": filename, "url": url} for filename, url in tiles],
            "paths": [str(path) for path in tif_paths],
        },
    )
    print(json.dumps({"tileCount": len(tiles), "availableTileCount": len(tif_paths), "missingTileCount": len(missing), "vrt": str(vrt_path)}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
