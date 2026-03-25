from __future__ import annotations

import argparse
import json
import os
import subprocess
import time
import urllib.request
import zipfile
from pathlib import Path
from typing import Any
from urllib.error import URLError

from cli_tools import default_gdalbuildvrt, resolve_executable
from rasterio.warp import transform


SLOVENIA_FILE_IDS = [469, 516, 517, 518]


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Prepare Slovenia DMV0050 DEM by downloading official quadrant ZIPs.")
    parser.add_argument("--output-dir", type=Path, default=Path("build/watersheds/slovenia-national-dem"))
    parser.add_argument("--gdalbuildvrt", default=default_gdalbuildvrt())
    parser.add_argument("--file-id", action="append", type=int)
    parser.add_argument("--point", action="append")
    parser.add_argument("--buffer-km", type=float, default=10.0)
    return parser.parse_args()


def parse_points(values: list[str] | None) -> list[tuple[float, float]]:
    if not values:
        return []
    return [(float(v.split(",", 1)[0]), float(v.split(",", 1)[1])) for v in values]


def bbox_for_points(points: list[tuple[float, float]], buffer_km: float) -> tuple[float, float, float, float]:
    lats = [p[0] for p in points]
    lons = [p[1] for p in points]
    xs, ys = transform("EPSG:4326", "EPSG:3794", lons, lats)
    min_x = min(xs) - buffer_km * 1000.0
    max_x = max(xs) + buffer_km * 1000.0
    min_y = min(ys) - buffer_km * 1000.0
    max_y = max(ys) + buffer_km * 1000.0
    return min_x, min_y, max_x, max_y


def fetch_download_url(file_id: int) -> str:
    url = f"https://ipi.eprostor.gov.si/jgp-service-api/display-views/groups/113/files/{file_id}"
    with urllib.request.urlopen(url, timeout=120) as response:
        payload = json.load(response)
    return payload["url"]


def download_file(url: str, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() and destination.stat().st_size > 0:
        return
    last_error: Exception | None = None
    temp_path = destination.with_suffix(destination.suffix + ".part")
    for attempt in range(1, 4):
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
        except URLError as exc:
            last_error = exc
            temp_path.unlink(missing_ok=True)
            time.sleep(5 * attempt)
    raise SystemExit(f"Slovenia DEM download failed: {last_error}")


def extract_zip(zip_path: Path, extract_dir: Path) -> None:
    marker = extract_dir / ".extracted"
    if marker.exists():
        return
    extract_dir.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(zip_path) as archive:
        archive.extractall(extract_dir)
    marker.write_text("ok", encoding="utf-8")
    zip_path.unlink(missing_ok=True)


def build_vrt(gdalbuildvrt: str, xyz_paths: list[Path], vrt_path: Path) -> None:
    if not xyz_paths:
        raise SystemExit("No Slovenia XYZ files available to build VRT")
    vrt_path.parent.mkdir(parents=True, exist_ok=True)
    input_list = vrt_path.with_suffix(".txt")
    input_list.write_text("\n".join(str(path) for path in xyz_paths), encoding="utf-8")
    subprocess.run([gdalbuildvrt, "-input_file_list", str(input_list), str(vrt_path)], check=True)


def xyz_bounds(path: Path) -> tuple[float, float, float, float] | None:
    with open(path, "rb") as handle:
        first_line = handle.readline().decode("utf-8", "ignore").strip().split()
        if len(first_line) < 3:
            return None
        handle.seek(max(0, os.path.getsize(path) - 4096))
        tail = handle.read().decode("utf-8", "ignore").splitlines()
    tail = [line for line in tail if line.strip()]
    if not tail:
        return None
    last_line = tail[-1].strip().split()
    if len(last_line) < 3:
        return None
    x1, y1 = float(first_line[0]), float(first_line[1])
    x2, y2 = float(last_line[0]), float(last_line[1])
    return min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2)


def select_xyz_paths(xyz_paths: list[Path], bbox: tuple[float, float, float, float] | None) -> list[Path]:
    if bbox is None:
        return xyz_paths
    min_x, min_y, max_x, max_y = bbox
    selected = []
    for path in xyz_paths:
        bounds = xyz_bounds(path)
        if bounds is None:
            continue
        bx0, by0, bx1, by1 = bounds
        if bx1 < min_x or bx0 > max_x or by1 < min_y or by0 > max_y:
            continue
        selected.append(path)
    return selected


def main() -> int:
    args = parse_args()
    output_dir = args.output_dir.resolve()
    gdalbuildvrt = resolve_executable(args.gdalbuildvrt, extra_candidates=[default_gdalbuildvrt()])
    file_ids = args.file_id or SLOVENIA_FILE_IDS
    points = parse_points(args.point)
    bbox = bbox_for_points(points, args.buffer_km) if points else None

    downloaded = []
    for file_id in file_ids:
        url = fetch_download_url(file_id)
        zip_path = output_dir / "downloads" / f"{file_id}.zip"
        extract_dir = output_dir / "raw" / str(file_id)
        download_file(url, zip_path)
        extract_zip(zip_path, extract_dir)
        downloaded.append({"fileId": file_id, "url": url, "extractDir": str(extract_dir)})

    xyz_paths = sorted((output_dir / "raw").rglob("*.xyz"))
    xyz_paths = select_xyz_paths(xyz_paths, bbox)
    vrt_path = output_dir / "vrt" / "_all_downloaded.vrt"
    build_vrt(gdalbuildvrt, xyz_paths, vrt_path)
    write_json(output_dir / "downloaded_units.json", {"downloads": downloaded, "xyzCount": len(xyz_paths), "bbox3794": bbox})
    print(json.dumps({"xyzCount": len(xyz_paths), "vrt": str(vrt_path)}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
