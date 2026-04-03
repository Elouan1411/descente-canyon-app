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
TARGET_FILE_ID = "47913754"
LEGACY_URL = "https://ndownloader.figshare.com/files/47913754"
STATIC_DOWNLOAD_URLS = [
    f"https://api.figshare.com/v2/file/download/{TARGET_FILE_ID}",
    f"https://figshare.com/ndownloader/files/{TARGET_FILE_ID}",
    LEGACY_URL,
]
STATIC_ARCHIVE_MD5 = "5064cf2315ef6159d9133b03596e761a"
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
    parser.add_argument("--archive-path", type=Path, help="Local GDW_v1_0_shp.zip archive to reuse instead of downloading")
    parser.add_argument("--barriers-path", type=Path, help="Existing GDW barriers shapefile to reuse directly")
    parser.add_argument("--reservoirs-path", type=Path, help="Existing GDW reservoirs shapefile to reuse directly")
    parser.add_argument("--download-url", action="append", default=[], help="Additional download URL(s) to try before Figshare")
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


def resolve_local_override_paths(args: argparse.Namespace) -> tuple[Path | None, Path | None, Path | None]:
    archive_path = args.archive_path or os.environ.get("GDW_ARCHIVE_PATH")
    barriers_path = args.barriers_path or os.environ.get("GDW_BARRIERS_PATH")
    reservoirs_path = args.reservoirs_path or os.environ.get("GDW_RESERVOIRS_PATH")
    return (
        Path(archive_path).resolve() if archive_path else None,
        Path(barriers_path).resolve() if barriers_path else None,
        Path(reservoirs_path).resolve() if reservoirs_path else None,
    )


def resolve_additional_download_urls(args: argparse.Namespace) -> list[str]:
    env_value = os.environ.get("GDW_DOWNLOAD_URLS", "")
    env_urls = [value.strip() for value in env_value.split(",") if value.strip()]
    cli_urls = [str(url).strip() for url in args.download_url if str(url).strip()]
    deduped: list[str] = []
    for candidate in [*cli_urls, *env_urls]:
        if candidate not in deduped:
            deduped.append(candidate)
    return deduped


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


def resolve_download_sources(additional_urls: list[str] | None = None) -> tuple[list[str], str | None]:
    additional_urls = additional_urls or []
    try:
        payload = fetch_json(ARTICLE_API_URL)
    except (HTTPError, URLError, json.JSONDecodeError) as exc:
        print(
            f"WARN GDW article API unavailable ({type(exc).__name__}: {exc}); using static download fallbacks.",
            flush=True,
        )
        return [*additional_urls, *STATIC_DOWNLOAD_URLS], STATIC_ARCHIVE_MD5

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
        candidates.extend(additional_urls)
        candidates.extend(STATIC_DOWNLOAD_URLS)
        deduped = []
        for candidate in candidates:
            if candidate not in deduped:
                deduped.append(candidate)
        return deduped, file_info.get("computed_md5") or file_info.get("supplied_md5")
    return [*additional_urls, *STATIC_DOWNLOAD_URLS], STATIC_ARCHIVE_MD5


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


def copy_local_archive(source: Path, destination: Path) -> dict[str, Any]:
    if not source.exists():
        raise SystemExit(f"GDW local archive not found: {source}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)
    if not _valid_archive(destination):
        destination.unlink(missing_ok=True)
        raise SystemExit(f"GDW local archive is not a valid zip: {source}")
    return {"url": str(source), "method": "local_archive"}


def download_file(destination: Path, *, additional_urls: list[str] | None = None) -> dict[str, Any]:
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
            candidates, expected_md5 = resolve_download_sources(additional_urls)
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
    raise SystemExit(
        "Global Dam Watch download failed: "
        f"{details}. "
        "You can seed the dataset manually by placing GDW_v1_0_shp.zip in build/watersheds/gdw/downloads/ "
        "or by passing --archive-path /path/to/GDW_v1_0_shp.zip."
    )


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
        local_archive_override, local_barriers_override, local_reservoirs_override = resolve_local_override_paths(args)
        additional_download_urls = resolve_additional_download_urls(args)
        if local_barriers_override is not None or local_reservoirs_override is not None:
            if local_barriers_override is None or local_reservoirs_override is None:
                raise SystemExit("Provide both --barriers-path and --reservoirs-path together for GDW local overrides")
            if not local_barriers_override.exists() or not local_reservoirs_override.exists():
                raise SystemExit("GDW local shapefile override path(s) not found")
            payload = {
                "barriers": str(local_barriers_override),
                "reservoirs": str(local_reservoirs_override),
                "download": {"url": None, "method": "local_shapefiles"},
            }
            write_json(ready_path, payload)
            print(json.dumps(payload, ensure_ascii=False, indent=2))
            return 0
        barriers, reservoirs = find_extracted_shapefiles(raw_dir)
        download_info: dict[str, Any] = {"url": None, "method": None}
        if barriers is None or reservoirs is None:
            if not _valid_archive(archive_path):
                if local_archive_override is not None:
                    download_info = copy_local_archive(local_archive_override, archive_path)
                else:
                    download_info = download_file(archive_path, additional_urls=additional_download_urls)
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
