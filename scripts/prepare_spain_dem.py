from __future__ import annotations

import argparse
import json
import math
import time
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError

from rasterio.warp import transform


WCS_URL = "https://servicios.idee.es/wcs-inspire/mdt"


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Prepare Spanish IGN DEM extract around canyon points via official WCS.")
    parser.add_argument("--point", action="append", required=True, help="Point as lat,lon")
    parser.add_argument("--buffer-km", type=float, default=10.0)
    parser.add_argument("--output-dir", type=Path, default=Path("build/watersheds/spain-national-dem"))
    return parser.parse_args()


def parse_points(values: list[str]) -> list[tuple[float, float]]:
    return [(float(v.split(",", 1)[0]), float(v.split(",", 1)[1])) for v in values]


def utm_zone(longitude: float) -> int:
    return int(math.floor((longitude + 180.0) / 6.0) + 1)


def main() -> int:
    args = parse_args()
    points = parse_points(args.point)
    mean_lon = sum(p[1] for p in points) / len(points)
    zone = utm_zone(mean_lon)
    if zone not in {28, 29, 30, 31}:
        raise SystemExit(f"Unsupported Spain UTM zone for official DEM service: {zone}")

    epsg = f"EPSG:258{zone:02d}"
    coverage_id = f"Elevacion258{zone:02d}_5"
    lats = [p[0] for p in points]
    lons = [p[1] for p in points]
    xs, ys = transform("EPSG:4326", epsg, lons, lats)
    min_x = min(xs) - args.buffer_km * 1000.0
    max_x = max(xs) + args.buffer_km * 1000.0
    min_y = min(ys) - args.buffer_km * 1000.0
    max_y = max(ys) + args.buffer_km * 1000.0

    query = urllib.parse.urlencode(
        [
            ("service", "WCS"),
            ("version", "2.0.1"),
            ("request", "GetCoverage"),
            ("coverageId", coverage_id),
            ("format", "image/tiff"),
            ("subsettingCrs", f"http://www.opengis.net/def/crs/EPSG/0/258{zone:02d}"),
            ("subset", f"x({min_x},{max_x})"),
            ("subset", f"y({min_y},{max_y})"),
        ]
    )
    url = f"{WCS_URL}?{query}"
    output_path = args.output_dir / "raw" / f"spain_{zone}.tif"
    output_path.parent.mkdir(parents=True, exist_ok=True)
    last_error: Exception | None = None
    for attempt in range(1, 4):
        try:
            with urllib.request.urlopen(url, timeout=300) as response, open(output_path, "wb") as handle:
                handle.write(response.read())
            break
        except (HTTPError, URLError) as exc:
            last_error = exc
            output_path.unlink(missing_ok=True)
            if isinstance(exc, HTTPError) and exc.code not in {429, 500, 502, 503, 504}:
                raise
            time.sleep(5 * attempt)
    else:
        raise SystemExit(f"Spain DEM request failed: {last_error}")

    write_json(args.output_dir / "request.json", {"url": url, "epsg": epsg, "coverageId": coverage_id})
    print(json.dumps({"dem": str(output_path), "epsg": epsg, "coverageId": coverage_id}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
