from __future__ import annotations

import argparse
import json
import subprocess
import time
import urllib.request
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError

from cli_tools import default_gdalbuildvrt, resolve_executable


S3_BASE_URL = "https://copernicus-dem-30m.s3.amazonaws.com"


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Downloads Copernicus DEM geocells on demand and rebuilds a VRT.")
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--cell", action="append", required=True)
    parser.add_argument("--output-dir", type=Path, default=Path("build/watersheds/copernicus-data"))
    parser.add_argument("--gdalbuildvrt", default=default_gdalbuildvrt())
    return parser.parse_args()


def resolve_url(manifest: dict[str, Any] | None, cell: str) -> str:
    if manifest is None:
        return copernicus_public_url(cell)
    cells = manifest.get("cells", {})
    if cell in cells:
        return str(cells[cell])
    template = manifest.get("template")
    if template:
        if "example.invalid" in str(template):
            return copernicus_public_url(cell)
        return str(template).format(cell=cell)
    return copernicus_public_url(cell)


def copernicus_public_url(cell: str) -> str:
    lat_part, lon_part = cell.split("_")
    northing = f"{lat_part}_00"
    easting = f"{lon_part}_00"
    tile_id = f"Copernicus_DSM_COG_10_{northing}_{easting}_DEM"
    return f"{S3_BASE_URL}/{tile_id}/{tile_id}.tif"


def download_file(url: str, destination: Path) -> bool:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() and destination.stat().st_size > 0:
        return True
    temp_path = destination.with_suffix(destination.suffix + ".part")
    last_error: Exception | None = None
    for attempt in range(1, 5):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(request, timeout=300) as response, open(temp_path, "wb") as handle:
                while True:
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    handle.write(chunk)
            temp_path.replace(destination)
            return True
        except (HTTPError, URLError) as exc:
            last_error = exc
            temp_path.unlink(missing_ok=True)
            if isinstance(exc, HTTPError):
                if exc.code == 404:
                    return False
                if exc.code not in {429, 500, 502, 503, 504}:
                    raise
            time.sleep(min(60, 5 * attempt))
    raise SystemExit(f"Copernicus DEM download failed: {last_error}")


def build_vrt(gdalbuildvrt: str, tif_paths: list[Path], vrt_path: Path) -> None:
    if not tif_paths:
        raise SystemExit("No Copernicus DEM tiles available to build VRT")
    vrt_path.parent.mkdir(parents=True, exist_ok=True)
    input_list = vrt_path.with_suffix(".txt")
    input_list.write_text("\n".join(str(path) for path in tif_paths), encoding="utf-8")
    subprocess.run([gdalbuildvrt, "-input_file_list", str(input_list), str(vrt_path)], check=True)


def main() -> int:
    args = parse_args()
    gdalbuildvrt = resolve_executable(args.gdalbuildvrt, extra_candidates=[default_gdalbuildvrt()])
    manifest = load_json(args.manifest) if args.manifest else None
    output_dir = args.output_dir.resolve()
    raw_dir = output_dir / "raw"
    vrt_path = output_dir / "vrt" / "copernicus_glo30.vrt"

    downloaded = []
    missing = []
    for cell in sorted(set(args.cell)):
        url = resolve_url(manifest, cell)
        destination = raw_dir / f"{cell}.tif"
        if download_file(url, destination):
            downloaded.append({"cell": cell, "url": url, "path": str(destination)})
        else:
            missing.append({"cell": cell, "url": url})

    tif_paths = sorted(raw_dir.glob("*.tif"))
    if not tif_paths:
        raise SystemExit("No Copernicus DEM tiles available to build VRT")
    build_vrt(gdalbuildvrt, tif_paths, vrt_path)
    write_json(output_dir / "downloaded_cells.json", {"downloaded": downloaded, "missing": missing})
    print(json.dumps({"cells": len(downloaded), "missing": len(missing), "vrt": str(vrt_path)}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
