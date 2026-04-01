from __future__ import annotations

import argparse
import csv
import io
import json
import time
import urllib.request
import zipfile
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError


URL = "https://epic.awi.de/id/eprint/31092/1/hartmann-moosdorf_2012.zip"


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Download and extract GLiM 0.5 degree raster once.")
    parser.add_argument("--output-dir", type=Path, default=Path("build/watersheds/glim"))
    return parser.parse_args()


def download_bytes(url: str) -> bytes:
    last_error: Exception | None = None
    for attempt in range(1, 6):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(request, timeout=300) as response:
                return response.read()
        except (HTTPError, URLError) as exc:
            last_error = exc
            if isinstance(exc, HTTPError) and exc.code not in {429, 500, 502, 503, 504}:
                raise
            time.sleep(min(60, 5 * attempt))
    raise SystemExit(f"GLiM download failed: {last_error}")


def main() -> int:
    args = parse_args()
    output_dir = args.output_dir.resolve()
    asc_path = output_dir / "raw" / "glim_wgs84_0point5deg.txt.asc"
    classnames_path = output_dir / "raw" / "Classnames.txt"
    if not asc_path.exists() or not classnames_path.exists():
        data = download_bytes(URL)
        output_dir.joinpath("raw").mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            archive.extract("glim_wgs84_0point5deg.txt.asc", output_dir / "raw")
            archive.extract("Classnames.txt", output_dir / "raw")

    class_map = {}
    with classnames_path.open(encoding="utf-8", errors="ignore") as handle:
        reader = csv.DictReader(handle, delimiter=';')
        for row in reader:
            class_map[int(row['Value_'])] = row['xx']
    write_json(output_dir / "ready.json", {"raster": str(asc_path), "classMap": class_map})
    print(json.dumps({"raster": str(asc_path), "classCount": len(class_map)}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
