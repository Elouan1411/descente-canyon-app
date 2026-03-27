from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import time
import urllib.request
import zipfile
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError

from cli_tools import default_gdalbuildvrt, resolve_executable


DOWNLOAD_PAGE_URL = "http://tinitaly.pi.ingv.it/Download_Area1_1.html"
BASE_URL = "http://tinitaly.pi.ingv.it/"


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Prepare nationwide Italy TINITALY DEM tiles and VRT.")
    parser.add_argument("--output-dir", type=Path, default=Path("build/watersheds/italy-national-dem"))
    parser.add_argument("--gdalbuildvrt", default=default_gdalbuildvrt())
    parser.add_argument("--keep-archives", action="store_true")
    return parser.parse_args()


def acquire_lock(lock_path: Path, *, timeout_sec: int = 1800) -> int:
    started = time.time()
    while True:
        try:
            return os.open(str(lock_path), os.O_CREAT | os.O_EXCL | os.O_RDWR)
        except FileExistsError:
            if time.time() - started > timeout_sec:
                raise SystemExit(f"Timeout waiting for TINITALY lock: {lock_path}")
            time.sleep(5)


def release_lock(lock_fd: int, lock_path: Path) -> None:
    os.close(lock_fd)
    lock_path.unlink(missing_ok=True)


def fetch_html(url: str) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read().decode("utf-8", "ignore")


def tile_links() -> list[tuple[str, str]]:
    html = fetch_html(DOWNLOAD_PAGE_URL)
    matches = sorted(set(re.findall(r'href="([^"]+\.zip)"', html)))
    results = []
    for href in matches:
        if "TINITALY_image.zip" in href:
            continue
        tile_name = Path(href).stem
        if not tile_name.endswith("_s10"):
            continue
        if href.startswith("http://") or href.startswith("https://"):
            url = href
        else:
            url = urllib.request.urljoin(BASE_URL, href)
        results.append((tile_name, url))
    return results


def download_file(url: str, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() and destination.stat().st_size > 0:
        return
    temp_path = destination.with_suffix(destination.suffix + f".{os.getpid()}.part")
    last_error: Exception | None = None
    for attempt in range(1, 7):
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
    raise SystemExit(f"TINITALY download failed for {url}: {last_error}")


def extract_archive(archive_path: Path, raw_dir: Path) -> list[Path]:
    raw_dir.mkdir(parents=True, exist_ok=True)
    extracted_paths: list[Path] = []
    with zipfile.ZipFile(archive_path) as archive:
        for member in archive.namelist():
            if member.endswith("/"):
                continue
            target_path = raw_dir / Path(member).name
            if target_path.exists() and target_path.stat().st_size > 0:
                extracted_paths.append(target_path)
                continue
            with archive.open(member) as src, open(target_path, "wb") as dst:
                shutil.copyfileobj(src, dst)
            extracted_paths.append(target_path)
    return extracted_paths


def build_vrt(gdalbuildvrt: str, tif_paths: list[Path], vrt_path: Path) -> None:
    if not tif_paths:
        raise SystemExit("No TINITALY rasters available to build VRT")
    vrt_path.parent.mkdir(parents=True, exist_ok=True)
    input_list = vrt_path.with_suffix(".txt")
    input_list.write_text("\n".join(str(path) for path in tif_paths), encoding="utf-8")
    subprocess.run([gdalbuildvrt, "-input_file_list", str(input_list), str(vrt_path)], check=True)


def main() -> int:
    args = parse_args()
    output_dir = args.output_dir.resolve()
    downloads_dir = output_dir / "downloads"
    raw_dir = output_dir / "raw"
    vrt_path = output_dir / "vrt" / "_all_downloaded.vrt"
    lock_path = output_dir / ".prepare.lock"
    gdalbuildvrt = resolve_executable(args.gdalbuildvrt, extra_candidates=[default_gdalbuildvrt()])

    if vrt_path.exists():
        print(json.dumps({"tileCount": None, "vrt": str(vrt_path), "reused": True}, ensure_ascii=False, indent=2))
        return 0

    output_dir.mkdir(parents=True, exist_ok=True)
    lock_fd = acquire_lock(lock_path)
    try:
        if vrt_path.exists():
            print(json.dumps({"tileCount": None, "vrt": str(vrt_path), "reused": True}, ensure_ascii=False, indent=2))
            return 0

        links = tile_links()
        all_rasters: list[Path] = []
        manifest_rows = []
        for tile_name, url in links:
            archive_path = downloads_dir / f"{tile_name}.zip"
            download_file(url, archive_path)
            extracted = extract_archive(archive_path, raw_dir / tile_name)
            tif_like = [path for path in extracted if path.suffix.lower() in {".tif", ".tiff", ".img", ".asc"}]
            all_rasters.extend(tif_like)
            manifest_rows.append({"tile": tile_name, "url": url, "archive": str(archive_path), "rasters": [str(path) for path in tif_like]})
            if not args.keep_archives:
                archive_path.unlink(missing_ok=True)

        build_vrt(gdalbuildvrt, sorted(set(all_rasters)), vrt_path)
        write_json(output_dir / "download_manifest.json", {"tileCount": len(links), "tiles": manifest_rows})
        print(json.dumps({"tileCount": len(links), "vrt": str(vrt_path)}, ensure_ascii=False, indent=2))
        return 0
    finally:
        release_lock(lock_fd, lock_path)


if __name__ == "__main__":
    raise SystemExit(main())
