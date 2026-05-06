from __future__ import annotations

import argparse
import json
import os
import shutil
import time
import urllib.request
import zipfile
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError


URL = "https://data.hydrosheds.org/file/hydrolakes/HydroLAKES_polys_v10_shp.zip"


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Download and extract HydroLAKES shapefile once.")
    parser.add_argument("--output-dir", type=Path, default=Path("build/watersheds/hydrolakes"))
    parser.add_argument("--keep-archive", action="store_true")
    return parser.parse_args()


def acquire_lock(lock_path: Path, *, timeout_sec: int = 1800) -> int:
    started = time.time()
    while True:
        try:
            return os.open(str(lock_path), os.O_CREAT | os.O_EXCL | os.O_RDWR)
        except FileExistsError:
            if time.time() - started > timeout_sec:
                raise SystemExit(f"Timeout waiting for HydroLAKES lock: {lock_path}")
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
            if destination.exists() and destination.stat().st_size > 0:
                return
            request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(request, timeout=300) as response, open(temp_path, "wb") as handle:
                while True:
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    handle.write(chunk)
            if destination.exists() and destination.stat().st_size > 0:
                temp_path.unlink(missing_ok=True)
                return
            temp_path.replace(destination)
            return
        except (HTTPError, URLError) as exc:
            last_error = exc
            temp_path.unlink(missing_ok=True)
            if isinstance(exc, HTTPError) and exc.code not in {429, 500, 502, 503, 504}:
                raise
            time.sleep(min(60, 5 * attempt))
    raise SystemExit(f"HydroLAKES download failed: {last_error}")


def extract_archive(archive_path: Path, raw_dir: Path) -> None:
    marker = raw_dir / ".extracted"
    if marker.exists():
        return
    raw_dir.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive_path) as archive:
        archive.extractall(raw_dir)
    marker.write_text("ok", encoding="utf-8")


def main() -> int:
    args = parse_args()
    output_dir = args.output_dir.resolve()
    archive_path = output_dir / "downloads" / "HydroLAKES_polys_v10_shp.zip"
    raw_dir = output_dir / "raw"
    lock_path = output_dir / ".prepare.lock"
    output_dir.mkdir(parents=True, exist_ok=True)
    lock_fd = acquire_lock(lock_path)
    try:
        ready_path = output_dir / "ready.json"
        if ready_path.exists():
            ready = json.loads(ready_path.read_text(encoding="utf-8"))
            print(json.dumps(ready, ensure_ascii=False, indent=2))
            return 0
        download_file(URL, archive_path)
        extract_archive(archive_path, raw_dir)
        shp = next(raw_dir.rglob("HydroLAKES_polys_v10.shp"), None)
        if shp is None:
            raise SystemExit("HydroLAKES shapefile not found after extraction")
        if not args.keep_archive:
            archive_path.unlink(missing_ok=True)
        payload = {"shapefile": str(shp)}
        write_json(ready_path, payload)
        print(json.dumps(payload, ensure_ascii=False, indent=2))
        return 0
    finally:
        release_lock(lock_fd, lock_path)


if __name__ == "__main__":
    raise SystemExit(main())
