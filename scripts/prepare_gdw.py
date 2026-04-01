from __future__ import annotations

import argparse
import json
import time
import urllib.request
import zipfile
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError


URL = "https://ndownloader.figshare.com/files/47913754"


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Download and extract Global Dam Watch shapefiles once.")
    parser.add_argument("--output-dir", type=Path, default=Path("build/watersheds/gdw"))
    parser.add_argument("--keep-archive", action="store_true")
    return parser.parse_args()


def download_file(url: str, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() and destination.stat().st_size > 0:
        return
    temp_path = destination.with_suffix(destination.suffix + ".part")
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
    raise SystemExit(f"Global Dam Watch download failed: {last_error}")


def extract_archive(archive_path: Path, raw_dir: Path) -> tuple[Path, Path]:
    marker = raw_dir / ".extracted"
    if marker.exists():
        barriers = next(raw_dir.rglob("*barriers*.shp"), None)
        reservoirs = next(raw_dir.rglob("*reservoirs*.shp"), None)
        if barriers and reservoirs:
            return barriers, reservoirs
    raw_dir.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive_path) as archive:
        archive.extractall(raw_dir)
    marker.write_text("ok", encoding="utf-8")
    barriers = next(raw_dir.rglob("*barriers*.shp"), None)
    reservoirs = next(raw_dir.rglob("*reservoirs*.shp"), None)
    if barriers is None or reservoirs is None:
        raise SystemExit("GDW shapefiles not found after extraction")
    return barriers, reservoirs


def main() -> int:
    args = parse_args()
    output_dir = args.output_dir.resolve()
    archive_path = output_dir / "downloads" / "GDW_v1_0_shp.zip"
    raw_dir = output_dir / "raw"
    download_file(URL, archive_path)
    barriers, reservoirs = extract_archive(archive_path, raw_dir)
    if not args.keep_archive:
        archive_path.unlink(missing_ok=True)
    write_json(output_dir / "ready.json", {"barriers": str(barriers), "reservoirs": str(reservoirs)})
    print(json.dumps({"barriers": str(barriers), "reservoirs": str(reservoirs)}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
