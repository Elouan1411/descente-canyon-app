from __future__ import annotations

import argparse
import json
import random
import time
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError

from rasterio.warp import transform


WCS_URL = "https://geoservizi.regione.liguria.it/geoserver/M2056/wcs"


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Prepare Liguria regional DEM extract around canyon points.")
    parser.add_argument("--point", action="append", required=True, help="Point as lat,lon")
    parser.add_argument("--buffer-km", type=float, default=10.0)
    parser.add_argument("--output-dir", type=Path, default=Path("build/watersheds/italy-liguria-dem"))
    return parser.parse_args()


def parse_points(values: list[str]) -> list[tuple[float, float]]:
    return [(float(v.split(",", 1)[0]), float(v.split(",", 1)[1])) for v in values]


def main() -> int:
    args = parse_args()
    points = parse_points(args.point)
    lats = [p[0] for p in points]
    lons = [p[1] for p in points]
    xs, ys = transform("EPSG:4326", "EPSG:3003", lons, lats)
    min_x = min(xs) - args.buffer_km * 1000.0
    max_x = max(xs) + args.buffer_km * 1000.0
    min_y = min(ys) - args.buffer_km * 1000.0
    max_y = max(ys) + args.buffer_km * 1000.0
    width = max(256, int((max_x - min_x) / 5.0))
    height = max(256, int((max_y - min_y) / 5.0))

    query = urllib.parse.urlencode(
        {
            "service": "WCS",
            "version": "1.0.0",
            "request": "GetCoverage",
            "coverage": "M2056:L6952",
            "crs": "EPSG:3003",
            "bbox": f"{min_x},{min_y},{max_x},{max_y}",
            "width": width,
            "height": height,
            "format": "GeoTIFF",
        }
    )
    url = f"{WCS_URL}?{query}"
    output_path = args.output_dir / "raw" / "liguria_5m.tif"
    output_path.parent.mkdir(parents=True, exist_ok=True)
    last_error: Exception | None = None
    for attempt in range(1, 7):
        try:
            with urllib.request.urlopen(url, timeout=300) as response, open(output_path, "wb") as handle:
                handle.write(response.read())
            break
        except (HTTPError, URLError) as exc:
            last_error = exc
            output_path.unlink(missing_ok=True)
            if isinstance(exc, HTTPError) and exc.code not in {429, 500, 502, 503, 504}:
                raise
            time.sleep(min(90, 5 * attempt) + random.uniform(0, 2.0))
    else:
        raise SystemExit(f"Liguria DEM request failed: {last_error}")

    write_json(args.output_dir / "request.json", {"url": url, "width": width, "height": height})
    print(json.dumps({"dem": str(output_path)}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
