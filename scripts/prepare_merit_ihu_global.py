from __future__ import annotations

import argparse
import json
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


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Downloads the public MERIT Hydro IHU global fallback rasters.")
    parser.add_argument("--output-dir", type=Path, default=Path("build/watersheds/merit-ihu-global"))
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
    raise SystemExit(f"MERIT Hydro IHU download failed for {url}: {last_error}")


def main() -> int:
    args = parse_args()
    output_dir = args.output_dir.resolve()
    downloaded = []
    paths = {}
    for key, url in FILES.items():
        destination = output_dir / "raw" / Path(url.split("/")[-1].split("?")[0])
        download_file(url, destination)
        downloaded.append({"key": key, "url": url, "path": str(destination)})
        paths[key] = str(destination)
    write_json(output_dir / "download_manifest.json", downloaded)
    write_json(output_dir / "ready.json", paths)
    print(json.dumps({"outputDir": str(output_dir), "paths": paths}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
