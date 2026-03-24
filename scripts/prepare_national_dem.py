from __future__ import annotations

import argparse
import json
import subprocess
import urllib.request
from pathlib import Path
from typing import Any

from cli_tools import default_gdalbuildvrt, resolve_executable


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Downloads unit-based national DEM data and rebuilds a combined VRT.")
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--unit", action="append", required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--gdalbuildvrt", default=default_gdalbuildvrt())
    return parser.parse_args()


def resolve_units(manifest: dict[str, Any], requested_units: list[str]) -> list[dict[str, Any]]:
    units = manifest.get("units", {})
    aliases = manifest.get("aliases", {})
    resolved = []
    for unit in requested_units:
        actual = aliases.get(unit, unit)
        payload = units.get(actual)
        if payload is None:
            raise SystemExit(f"No national DEM unit found for {unit}")
        resolved.append({"name": actual, **payload})
    return resolved


def download_file(url: str, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() and destination.stat().st_size > 0:
        return
    request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(request, timeout=300) as response, open(destination, "wb") as handle:
        while True:
            chunk = response.read(1024 * 1024)
            if not chunk:
                break
            handle.write(chunk)


def build_combined_vrt(gdalbuildvrt: str, raw_dir: Path, vrt_path: Path) -> None:
    rasters = sorted(list(raw_dir.rglob("*.tif")) + list(raw_dir.rglob("*.asc")) + list(raw_dir.rglob("*.img")))
    if not rasters:
        raise SystemExit(f"No rasters found in {raw_dir}")
    vrt_path.parent.mkdir(parents=True, exist_ok=True)
    input_list = vrt_path.with_suffix(".txt")
    input_list.write_text("\n".join(str(path) for path in rasters), encoding="utf-8")
    subprocess.run([gdalbuildvrt, "-input_file_list", str(input_list), str(vrt_path)], check=True)


def main() -> int:
    args = parse_args()
    gdalbuildvrt = resolve_executable(args.gdalbuildvrt, extra_candidates=[default_gdalbuildvrt()])
    manifest = load_json(args.manifest)
    output_dir = args.output_dir.resolve()
    units = resolve_units(manifest, args.unit)

    downloaded = []
    for unit in units:
        for url in unit.get("urls", []):
            filename = url.split("/")[-1]
            destination = output_dir / "raw" / unit["name"] / filename
            download_file(url, destination)
            downloaded.append({"unit": unit["name"], "url": url, "path": str(destination)})

    vrt_path = output_dir / "vrt" / "_all_downloaded.vrt"
    build_combined_vrt(gdalbuildvrt, output_dir / "raw", vrt_path)
    write_json(output_dir / "downloaded_units.json", downloaded)
    print(json.dumps({"units": len(units), "vrt": str(vrt_path)}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
