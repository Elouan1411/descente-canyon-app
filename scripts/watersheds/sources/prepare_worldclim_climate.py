from __future__ import annotations

import argparse
import json
import os
import time
import urllib.request
import zipfile
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError


BASE_URL = "https://geodata.ucdavis.edu/climate/worldclim/2_1/base"
VARIABLES = ("prec", "tavg", "tmin", "tmax")


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Download and extract WorldClim climate normals once.")
    parser.add_argument("--output-dir", type=Path, default=Path("build/watersheds/worldclim-climate"))
    parser.add_argument("--resolution", default="5m", choices=["10m", "5m", "2.5m", "30s"])
    return parser.parse_args()


def acquire_lock(lock_path: Path, *, timeout_sec: int = 1800) -> int:
    started = time.time()
    while True:
        try:
            return os.open(str(lock_path), os.O_CREAT | os.O_EXCL | os.O_RDWR)
        except FileExistsError:
            if time.time() - started > timeout_sec:
                raise SystemExit(f"Timeout waiting for WorldClim lock: {lock_path}")
            time.sleep(5)


def release_lock(lock_fd: int, lock_path: Path) -> None:
    os.close(lock_fd)
    lock_path.unlink(missing_ok=True)


def download_file(url: str, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() and destination.stat().st_size > 0:
        return
    temp_path = destination.with_suffix(destination.suffix + f".{os.getpid()}.part")
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
    raise SystemExit(f"WorldClim download failed: {last_error}")


def extract_zip(zip_path: Path, output_dir: Path) -> list[str]:
    output_dir.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(zip_path) as archive:
        tif_names = sorted(name for name in archive.namelist() if name.lower().endswith(".tif"))
        for tif_name in tif_names:
            target_path = output_dir / Path(tif_name).name
            if target_path.exists() and target_path.stat().st_size > 0:
                continue
            with archive.open(tif_name) as source, open(target_path, "wb") as handle:
                handle.write(source.read())
    return [str(output_dir / Path(name).name) for name in tif_names]


def main() -> int:
    args = parse_args()
    output_dir = args.output_dir.resolve()
    downloads_dir = output_dir / "downloads"
    raw_dir = output_dir / "raw"
    ready_path = output_dir / "ready.json"
    lock_path = output_dir / ".prepare.lock"

    output_dir.mkdir(parents=True, exist_ok=True)
    lock_fd = acquire_lock(lock_path)
    try:
        monthly: dict[str, list[str]] = {}
        for variable in VARIABLES:
            zip_name = f"wc2.1_{args.resolution}_{variable}.zip"
            url = f"{BASE_URL}/{zip_name}"
            zip_path = downloads_dir / zip_name
            download_file(url, zip_path)
            extracted = extract_zip(zip_path, raw_dir / variable)
            if len(extracted) != 12:
                raise SystemExit(f"Unexpected WorldClim file count for {variable}: {len(extracted)}")
            monthly[variable] = sorted(extracted)

        ready = {
            "resolution": args.resolution,
            "monthly": monthly,
        }
        write_json(ready_path, ready)
        print(json.dumps({"resolution": args.resolution, "variables": list(monthly), "ready": str(ready_path)}, ensure_ascii=False, indent=2))
        return 0
    finally:
        release_lock(lock_fd, lock_path)


if __name__ == "__main__":
    raise SystemExit(main())
