from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import time
import urllib.request
import zipfile
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError


ARTICLE_API_URL = "https://api.figshare.com/v2/articles/25988293"
TARGET_ARCHIVE_NAME = "GDW_v1_0_shp.zip"
LEGACY_URL = "https://ndownloader.figshare.com/files/47913754"
BROWSER_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
    ),
    "Accept": "application/octet-stream,*/*;q=0.8",
    "Accept-Language": "en-US,en;q=0.9",
    "Referer": "https://figshare.com/",
    "Origin": "https://figshare.com",
}


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Download and extract Global Dam Watch shapefiles once.")
    parser.add_argument("--output-dir", type=Path, default=Path("build/watersheds/gdw"))
    parser.add_argument("--keep-archive", action="store_true")
    return parser.parse_args()


def acquire_lock(lock_path: Path, *, timeout_sec: int = 1800) -> int:
    started = time.time()
    while True:
        try:
            return os.open(str(lock_path), os.O_CREAT | os.O_EXCL | os.O_RDWR)
        except FileExistsError:
            if time.time() - started > timeout_sec:
                raise SystemExit(f"Timeout waiting for GDW lock: {lock_path}")
            time.sleep(5)


def release_lock(lock_fd: int, lock_path: Path) -> None:
    os.close(lock_fd)
    lock_path.unlink(missing_ok=True)


def _md5(path: Path) -> str:
    digest = hashlib.md5()
    with path.open("rb") as handle:
        while True:
            chunk = handle.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def _valid_archive(path: Path) -> bool:
    return path.exists() and path.stat().st_size > 0 and zipfile.is_zipfile(path)


def find_extracted_shapefiles(raw_dir: Path) -> tuple[Path | None, Path | None]:
    barriers = next(raw_dir.rglob("*barriers*.shp"), None) if raw_dir.exists() else None
    reservoirs = next(raw_dir.rglob("*reservoirs*.shp"), None) if raw_dir.exists() else None
    return barriers, reservoirs


def fetch_json(url: str) -> Any:
    request = urllib.request.Request(
        url,
        headers={
            **BROWSER_HEADERS,
            "Accept": "application/json",
        },
    )
    with urllib.request.urlopen(request, timeout=120) as response:
        return json.loads(response.read().decode("utf-8"))


def resolve_download_sources() -> tuple[list[str], str | None]:
    payload = fetch_json(ARTICLE_API_URL)
    files = payload.get("files") or []
    for file_info in files:
        if file_info.get("name") != TARGET_ARCHIVE_NAME:
            continue
        file_id = file_info.get("id")
        candidates = []
        if file_id is not None:
            candidates.append(f"https://api.figshare.com/v2/file/download/{file_id}")
        download_url = file_info.get("download_url")
        if download_url:
            candidates.append(str(download_url))
        candidates.append(LEGACY_URL)
        deduped = []
        for candidate in candidates:
            if candidate not in deduped:
                deduped.append(candidate)
        return deduped, file_info.get("computed_md5") or file_info.get("supplied_md5")
    return [LEGACY_URL], None


def download_with_urllib(url: str, temp_path: Path) -> None:
    request = urllib.request.Request(url, headers=BROWSER_HEADERS)
    with urllib.request.urlopen(request, timeout=300) as response, open(temp_path, "wb") as handle:
        while True:
            chunk = response.read(1024 * 1024)
            if not chunk:
                break
            handle.write(chunk)


def download_with_curl(url: str, temp_path: Path) -> None:
    curl = shutil.which("curl")
    if curl is None:
        raise FileNotFoundError("curl not available")
    subprocess.run(
        [
            curl,
            "-fL",
            "--retry",
            "5",
            "--retry-all-errors",
            "--connect-timeout",
            "30",
            "--max-time",
            "600",
            "-A",
            BROWSER_HEADERS["User-Agent"],
            "-H",
            f"Referer: {BROWSER_HEADERS['Referer']}",
            "-H",
            f"Origin: {BROWSER_HEADERS['Origin']}",
            "-H",
            f"Accept-Language: {BROWSER_HEADERS['Accept-Language']}",
            "-H",
            f"Accept: {BROWSER_HEADERS['Accept']}",
            "-o",
            str(temp_path),
            url,
        ],
        check=True,
    )


def download_file(destination: Path) -> dict[str, Any]:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if _valid_archive(destination):
        return {"url": None, "method": "existing_archive"}
    destination.unlink(missing_ok=True)
    temp_path = destination.with_suffix(destination.suffix + f".{os.getpid()}.part")
    last_error: Exception | None = None
    errors: list[str] = []
    download_methods: list[tuple[str, Any]] = []
    if shutil.which("curl") is not None:
        download_methods.append(("curl", download_with_curl))
    download_methods.append(("urllib", download_with_urllib))

    for attempt in range(1, 6):
        try:
            if _valid_archive(destination):
                return {"url": None, "method": "existing_archive"}
            candidates, expected_md5 = resolve_download_sources()
            for url in candidates:
                for method_name, method in download_methods:
                    try:
                        temp_path.unlink(missing_ok=True)
                        method(url, temp_path)
                        if expected_md5 and _md5(temp_path) != expected_md5:
                            raise SystemExit(f"GDW archive checksum mismatch for {url}")
                        if not _valid_archive(temp_path):
                            raise SystemExit(f"Downloaded GDW archive is not a valid zip: {url}")
                        temp_path.replace(destination)
                        return {"url": url, "method": method_name}
                    except (HTTPError, URLError, subprocess.CalledProcessError, FileNotFoundError, SystemExit) as exc:
                        last_error = exc
                        errors.append(f"attempt={attempt} method={method_name} url={url} error={type(exc).__name__}: {exc}")
                        temp_path.unlink(missing_ok=True)
                        if isinstance(exc, HTTPError) and exc.code not in {403, 429, 500, 502, 503, 504}:
                            raise
                        if isinstance(exc, SystemExit):
                            continue
        except (HTTPError, URLError) as exc:
            last_error = exc
            temp_path.unlink(missing_ok=True)
            if isinstance(exc, HTTPError) and exc.code not in {403, 429, 500, 502, 503, 504}:
                raise
        if attempt < 5:
            time.sleep(min(60, 5 * attempt))
    details = "; ".join(errors[-6:]) if errors else str(last_error)
    raise SystemExit(f"Global Dam Watch download failed: {details}")


def extract_archive(archive_path: Path, raw_dir: Path) -> tuple[Path, Path]:
    marker = raw_dir / ".extracted"
    if marker.exists():
        barriers, reservoirs = find_extracted_shapefiles(raw_dir)
        if barriers and reservoirs:
            return barriers, reservoirs
    raw_dir.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive_path) as archive:
        archive.extractall(raw_dir)
    marker.write_text("ok", encoding="utf-8")
    barriers, reservoirs = find_extracted_shapefiles(raw_dir)
    if barriers is None or reservoirs is None:
        raise SystemExit("GDW shapefiles not found after extraction")
    return barriers, reservoirs


def main() -> int:
    args = parse_args()
    output_dir = args.output_dir.resolve()
    archive_path = output_dir / "downloads" / "GDW_v1_0_shp.zip"
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
        barriers, reservoirs = find_extracted_shapefiles(raw_dir)
        download_info: dict[str, Any] = {"url": None, "method": None}
        if barriers is None or reservoirs is None:
            if not _valid_archive(archive_path):
                download_info = download_file(archive_path)
            barriers, reservoirs = extract_archive(archive_path, raw_dir)
        if not args.keep_archive:
            archive_path.unlink(missing_ok=True)
        payload = {
            "barriers": str(barriers),
            "reservoirs": str(reservoirs),
            "download": download_info,
        }
        write_json(ready_path, payload)
        print(json.dumps(payload, ensure_ascii=False, indent=2))
        return 0
    finally:
        release_lock(lock_fd, lock_path)


if __name__ == "__main__":
    raise SystemExit(main())
