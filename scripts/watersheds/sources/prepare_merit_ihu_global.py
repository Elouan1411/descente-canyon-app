from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import time
import urllib.request
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError


FILES = {
    "elevation": "https://zenodo.org/records/7936280/files/30sec_elevtn.tif?download=1",
    "flowdir": "https://zenodo.org/records/7936280/files/30sec_flwdir.tif?download=1",
    "uparea": "https://zenodo.org/records/7936280/files/30sec_uparea.tif?download=1",
}

BROWSER_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
    ),
    "Accept": "application/octet-stream,*/*;q=0.8",
    "Accept-Language": "en-US,en;q=0.9",
}


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def acquire_lock(lock_path: Path, *, timeout_sec: int = 1800) -> int:
    started = time.time()
    while True:
        try:
            return os.open(str(lock_path), os.O_CREAT | os.O_EXCL | os.O_RDWR)
        except FileExistsError:
            if time.time() - started > timeout_sec:
                raise SystemExit(f"Timeout waiting for MERIT IHU lock: {lock_path}")
            time.sleep(5)


def release_lock(lock_fd: int, lock_path: Path) -> None:
    os.close(lock_fd)
    lock_path.unlink(missing_ok=True)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Downloads the public MERIT Hydro IHU global fallback rasters.")
    parser.add_argument("--output-dir", type=Path, default=Path("build/watersheds/merit-ihu-global"))
    parser.add_argument("--elevation-path", type=Path)
    parser.add_argument("--flowdir-path", type=Path)
    parser.add_argument("--uparea-path", type=Path)
    parser.add_argument("--download-url", action="append", default=[], help="Override download URL as key=url")
    return parser.parse_args()


def resolve_local_override_paths(args: argparse.Namespace) -> dict[str, Path | None]:
    return {
        "elevation": Path(args.elevation_path or os.environ.get("MERIT_IHU_ELEVATION_PATH")).resolve() if (args.elevation_path or os.environ.get("MERIT_IHU_ELEVATION_PATH")) else None,
        "flowdir": Path(args.flowdir_path or os.environ.get("MERIT_IHU_FLOWDIR_PATH")).resolve() if (args.flowdir_path or os.environ.get("MERIT_IHU_FLOWDIR_PATH")) else None,
        "uparea": Path(args.uparea_path or os.environ.get("MERIT_IHU_UPAREA_PATH")).resolve() if (args.uparea_path or os.environ.get("MERIT_IHU_UPAREA_PATH")) else None,
    }


def resolve_download_overrides(args: argparse.Namespace) -> dict[str, str]:
    overrides: dict[str, str] = {}
    for raw_value in args.download_url:
        if "=" not in raw_value:
            continue
        key, value = raw_value.split("=", 1)
        key = key.strip()
        value = value.strip()
        if key in FILES and value:
            overrides[key] = value
    for key in FILES:
        env_value = os.environ.get(f"MERIT_IHU_{key.upper()}_URL")
        if env_value:
            overrides[key] = env_value.strip()
    return overrides


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
            f"Accept-Language: {BROWSER_HEADERS['Accept-Language']}",
            "-H",
            f"Accept: {BROWSER_HEADERS['Accept']}",
            "-o",
            str(temp_path),
            url,
        ],
        check=True,
    )


def copy_local_file(source: Path, destination: Path) -> None:
    if not source.exists():
        raise SystemExit(f"MERIT IHU local file not found: {source}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)


def download_file(url: str, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() and destination.stat().st_size > 0:
        return
    temp_path = destination.with_suffix(destination.suffix + f".{os.getpid()}.part")
    last_error: Exception | None = None
    errors: list[str] = []
    methods: list[tuple[str, Any]] = []
    if shutil.which("curl") is not None:
        methods.append(("curl", download_with_curl))
    methods.append(("urllib", download_with_urllib))
    for attempt in range(1, 6):
        for method_name, method in methods:
            try:
                if destination.exists() and destination.stat().st_size > 0:
                    return
                temp_path.unlink(missing_ok=True)
                method(url, temp_path)
                if destination.exists() and destination.stat().st_size > 0:
                    temp_path.unlink(missing_ok=True)
                    return
                temp_path.replace(destination)
                return
            except (HTTPError, URLError, subprocess.CalledProcessError, FileNotFoundError) as exc:
                last_error = exc
                errors.append(f"attempt={attempt} method={method_name} error={type(exc).__name__}: {exc}")
                temp_path.unlink(missing_ok=True)
                if isinstance(exc, HTTPError) and exc.code not in {403, 429, 500, 502, 503, 504}:
                    raise
        if attempt < 5:
            time.sleep(min(60, 5 * attempt))
    details = "; ".join(errors[-6:]) if errors else str(last_error)
    raise SystemExit(
        f"MERIT Hydro IHU download failed for {url}: {details}. "
        "You can seed local rasters with --elevation-path/--flowdir-path/--uparea-path or MERIT_IHU_*_PATH."
    )


def main() -> int:
    args = parse_args()
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    lock_fd = acquire_lock(output_dir / ".prepare.lock")
    try:
        ready_path = output_dir / "ready.json"
        if ready_path.exists():
            ready = json.loads(ready_path.read_text(encoding="utf-8"))
            print(json.dumps(ready, ensure_ascii=False, indent=2))
            return 0

        local_overrides = resolve_local_override_paths(args)
        download_overrides = resolve_download_overrides(args)
        downloaded = []
        paths = {}
        for key, base_url in FILES.items():
            url = download_overrides.get(key, base_url)
            destination = output_dir / "raw" / Path(base_url.split("/")[-1].split("?")[0])
            local_override = local_overrides.get(key)
            if local_override is not None:
                copy_local_file(local_override, destination)
                downloaded.append({"key": key, "url": None, "path": str(destination), "method": "local_file"})
            else:
                download_file(url, destination)
                downloaded.append({"key": key, "url": url, "path": str(destination), "method": "download"})
            paths[key] = str(destination)
        write_json(output_dir / "download_manifest.json", downloaded)
        write_json(ready_path, paths)
        print(json.dumps({"outputDir": str(output_dir), "paths": paths}, ensure_ascii=False, indent=2))
        return 0
    finally:
        release_lock(lock_fd, output_dir / ".prepare.lock")


if __name__ == "__main__":
    raise SystemExit(main())
