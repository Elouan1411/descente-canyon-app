from __future__ import annotations

import argparse
import json
import math
import os
import subprocess
import time
import urllib.request
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError

from cli_tools import default_gdalbuildvrt, resolve_executable


BASE_URL = "https://esa-worldcover.s3.eu-central-1.amazonaws.com/v200/2021/map"


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Download ESA WorldCover tiles on demand and rebuild a global VRT cache.")
    parser.add_argument("--point", action="append", required=True, help="Point as lat,lon")
    parser.add_argument("--buffer-km", type=float, default=20.0)
    parser.add_argument("--output-dir", type=Path, default=Path("build/watersheds/worldcover"))
    parser.add_argument("--gdalbuildvrt", default=default_gdalbuildvrt())
    return parser.parse_args()


def parse_points(values: list[str]) -> list[tuple[float, float]]:
    return [(float(v.split(",", 1)[0]), float(v.split(",", 1)[1])) for v in values]


def acquire_lock(lock_path: Path, *, timeout_sec: int = 1800) -> int:
    started = time.time()
    while True:
        try:
            return os.open(str(lock_path), os.O_CREAT | os.O_EXCL | os.O_RDWR)
        except FileExistsError:
            if time.time() - started > timeout_sec:
                raise SystemExit(f"Timeout waiting for WorldCover lock: {lock_path}")
            time.sleep(5)


def release_lock(lock_fd: int, lock_path: Path) -> None:
    os.close(lock_fd)
    lock_path.unlink(missing_ok=True)


def worldcover_tile_name(lat: float, lon: float) -> str:
    lat_base = math.floor(lat / 3.0) * 3
    lon_base = math.floor(lon / 3.0) * 3
    lat_prefix = "N" if lat_base >= 0 else "S"
    lon_prefix = "E" if lon_base >= 0 else "W"
    return f"ESA_WorldCover_10m_2021_v200_{lat_prefix}{abs(int(lat_base)):02d}{lon_prefix}{abs(int(lon_base)):03d}_Map.tif"


def tiles_for_points(points: list[tuple[float, float]], buffer_km: float) -> list[str]:
    deg_buffer_lat = buffer_km / 111.32
    tiles = set()
    for lat, lon in points:
        cos_lat = max(0.1, abs(math.cos(math.radians(lat))))
        deg_buffer_lon = buffer_km / (111.32 * cos_lat)
        for test_lat in (lat - deg_buffer_lat, lat, lat + deg_buffer_lat):
            for test_lon in (lon - deg_buffer_lon, lon, lon + deg_buffer_lon):
                tiles.add(worldcover_tile_name(test_lat, test_lon))
    return sorted(tiles)


def download_file(url: str, destination: Path) -> bool:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() and destination.stat().st_size > 0:
        return True
    temp_path = destination.with_suffix(destination.suffix + f".{os.getpid()}.part")
    last_error: Exception | None = None
    for attempt in range(1, 6):
        try:
            if destination.exists() and destination.stat().st_size > 0:
                return True
            request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(request, timeout=300) as response, open(temp_path, "wb") as handle:
                while True:
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    handle.write(chunk)
            if destination.exists() and destination.stat().st_size > 0:
                temp_path.unlink(missing_ok=True)
                return True
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
            time.sleep(min(60, 5 * attempt))
    raise SystemExit(f"WorldCover download failed: {last_error}")


def build_vrt(gdalbuildvrt: str, tif_paths: list[Path], vrt_path: Path) -> None:
    if not tif_paths:
        raise SystemExit("No WorldCover tiles available to build VRT")
    vrt_path.parent.mkdir(parents=True, exist_ok=True)
    input_list = vrt_path.with_suffix(".txt")
    input_list.write_text("\n".join(str(path) for path in tif_paths), encoding="utf-8")
    subprocess.run([gdalbuildvrt, "-input_file_list", str(input_list), str(vrt_path)], check=True)


def main() -> int:
    args = parse_args()
    points = parse_points(args.point)
    output_dir = args.output_dir.resolve()
    raw_dir = output_dir / "raw"
    vrt_path = output_dir / "vrt" / "_all_downloaded.vrt"
    lock_path = output_dir / ".prepare.lock"
    gdalbuildvrt = resolve_executable(args.gdalbuildvrt, extra_candidates=[default_gdalbuildvrt()])

    output_dir.mkdir(parents=True, exist_ok=True)
    lock_fd = acquire_lock(lock_path)
    try:
        tiles = tiles_for_points(points, args.buffer_km)
        downloaded = []
        missing = []
        for filename in tiles:
            url = f"{BASE_URL}/{filename}"
            path = raw_dir / filename
            if download_file(url, path):
                downloaded.append({"tile": filename, "url": url, "path": str(path)})
            else:
                missing.append({"tile": filename, "url": url})

        tif_paths = sorted(raw_dir.glob("*.tif"))
        build_vrt(gdalbuildvrt, tif_paths, vrt_path)
        write_json(output_dir / "downloaded_tiles.json", {"downloaded": downloaded, "missing": missing})
        print(json.dumps({"tiles": len(downloaded), "missing": len(missing), "vrt": str(vrt_path)}, ensure_ascii=False, indent=2))
        return 0
    finally:
        release_lock(lock_fd, lock_path)


if __name__ == "__main__":
    raise SystemExit(main())
