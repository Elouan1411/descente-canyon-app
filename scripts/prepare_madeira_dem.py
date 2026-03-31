from __future__ import annotations

import argparse
import json
import time
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError

from rasterio.warp import transform


WCS_URL = "https://geoservices.madeira.gov.pt/geoserver/EL_MDTMadeira_5m_2018/wcs"
COVERAGE_ID = "EL_MDTMadeira_5m_2018__EL.MDTMadeira_5m_2018"


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Prepare Madeira official 5m DEM extract around canyon points.")
    parser.add_argument("--point", action="append", required=True, help="Point as lat,lon")
    parser.add_argument("--buffer-km", type=float, default=10.0)
    parser.add_argument("--output-dir", type=Path, default=Path("build/watersheds/madeira-national-dem"))
    return parser.parse_args()


def parse_points(values: list[str]) -> list[tuple[float, float]]:
    return [(float(v.split(",", 1)[0]), float(v.split(",", 1)[1])) for v in values]


def main() -> int:
    args = parse_args()
    points = parse_points(args.point)
    lats = [p[0] for p in points]
    lons = [p[1] for p in points]
    xs, ys = transform("EPSG:4326", "EPSG:5016", lons, lats)
    min_x = min(xs) - args.buffer_km * 1000.0
    max_x = max(xs) + args.buffer_km * 1000.0
    min_y = min(ys) - args.buffer_km * 1000.0
    max_y = max(ys) + args.buffer_km * 1000.0

    query = urllib.parse.urlencode(
        [
            ("service", "WCS"),
            ("version", "2.0.1"),
            ("request", "GetCoverage"),
            ("coverageId", COVERAGE_ID),
            ("format", "image/tiff"),
            ("subsettingCrs", "http://www.opengis.net/def/crs/EPSG/0/5016"),
            ("subset", f"x({min_x},{max_x})"),
            ("subset", f"y({min_y},{max_y})"),
        ]
    )
    url = f"{WCS_URL}?{query}"
    output_path = args.output_dir / "raw" / "madeira_5m.tif"
    output_path.parent.mkdir(parents=True, exist_ok=True)

    last_error: Exception | None = None
    for attempt in range(1, 7):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(request, timeout=300) as response, open(output_path, "wb") as handle:
                handle.write(response.read())
            break
        except (HTTPError, URLError) as exc:
            last_error = exc
            output_path.unlink(missing_ok=True)
            if isinstance(exc, HTTPError) and exc.code not in {429, 500, 502, 503, 504}:
                raise
            time.sleep(min(90, 5 * attempt))
    else:
        raise SystemExit(f"Madeira DEM request failed: {last_error}")

    write_json(args.output_dir / "request.json", {"url": url, "coverageId": COVERAGE_ID, "epsg": "EPSG:5016"})
    print(json.dumps({"dem": str(output_path), "epsg": "EPSG:5016", "coverageId": COVERAGE_ID}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
