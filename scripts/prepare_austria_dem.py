from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path
from typing import Any

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
    parser.add_argument("--gdalbuildvrt", default=default_gdalbuildvrt())
    return parser.parse_args()


def parse_points(values: list[str]) -> list[tuple[float, float]]:
    return [(float(v.split(",", 1)[0]), float(v.split(",", 1)[1])) for v in values]


def intersecting_tiles(points: list[tuple[float, float]], buffer_km: float) -> list[str]:
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
            urls.append(
                "/vsicurl/https://data.bev.gv.at/download/ALS/DTM/"
                f"{AUSTRIA_TILE_DATE}/ALS_DTM_CRS3035RES50000mN{north}E{east}.tif"
            )
    return sorted(set(urls))


def build_vrt(gdalbuildvrt: str, urls: list[str], vrt_path: Path) -> None:
    if not urls:
        raise SystemExit("No Austrian ALS tiles resolved")
    vrt_path.parent.mkdir(parents=True, exist_ok=True)
    input_list = vrt_path.with_suffix(".txt")
    input_list.write_text("\n".join(urls), encoding="utf-8")
    subprocess.run([gdalbuildvrt, "-input_file_list", str(input_list), str(vrt_path)], check=True)


def main() -> int:
    args = parse_args()
    points = parse_points(args.point)
    urls = intersecting_tiles(points, args.buffer_km)
    gdalbuildvrt = resolve_executable(args.gdalbuildvrt, extra_candidates=[default_gdalbuildvrt()])
    vrt_path = args.output_dir / "vrt" / "_all_downloaded.vrt"
    build_vrt(gdalbuildvrt, urls, vrt_path)
    write_json(args.output_dir / "selected_tiles.json", {"tileCount": len(urls), "urls": urls})
    print(json.dumps({"tileCount": len(urls), "vrt": str(vrt_path)}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
