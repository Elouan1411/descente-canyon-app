from __future__ import annotations

import argparse
import json
import subprocess
import tarfile
import urllib.request
from pathlib import Path
from typing import Any

from cli_tools import default_gdalbuildvrt, resolve_executable


LAYERS = ["upa", "dir", "elv"]


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Downloads MERIT Hydro packages on demand and rebuilds layer VRTs.")
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--package", action="append", required=True)
    parser.add_argument("--output-dir", type=Path, default=Path("build/watersheds/merit"))
    parser.add_argument("--gdalbuildvrt", default=default_gdalbuildvrt())
    return parser.parse_args()


def download_file(url: str, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() and destination.stat().st_size > 0:
        return
    if "dl=0" in url:
        url = url.replace("dl=0", "dl=1")
    request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(request, timeout=300) as response, open(destination, "wb") as handle:
        while True:
            chunk = response.read(1024 * 1024)
            if not chunk:
                break
            handle.write(chunk)


def build_vrt(gdalbuildvrt: str, tif_paths: list[Path], vrt_path: Path) -> None:
    if not tif_paths:
        raise SystemExit(f"No MERIT tiles available for {vrt_path.name}")
    vrt_path.parent.mkdir(parents=True, exist_ok=True)
    input_list = vrt_path.with_suffix(".txt")
    input_list.write_text("\n".join(str(path) for path in tif_paths), encoding="utf-8")
    subprocess.run([gdalbuildvrt, "-input_file_list", str(input_list), str(vrt_path)], check=True)


def main() -> int:
    args = parse_args()
    gdalbuildvrt = resolve_executable(args.gdalbuildvrt, extra_candidates=[default_gdalbuildvrt()])
    manifest = load_json(args.manifest)
    packages = manifest.get("packages", {})
    output_dir = args.output_dir.resolve()
    actions = []

    for package_name in sorted(set(args.package)):
        package_urls = packages.get(package_name)
        if package_urls is None:
            raise SystemExit(f"No MERIT package URLs found for {package_name}")
        for layer in LAYERS:
            url = package_urls.get(layer)
            if not url:
                raise SystemExit(f"No MERIT URL for package {package_name} layer {layer}")
            tar_path = output_dir / "downloads" / layer / f"{layer}_{package_name}.tar"
            raw_dir = output_dir / "raw" / layer / package_name
            marker = raw_dir / ".extracted"
            download_file(str(url), tar_path)
            if not marker.exists():
                raw_dir.mkdir(parents=True, exist_ok=True)
                if not tar_path.exists():
                    raise SystemExit(f"MERIT archive missing after download attempt: {tar_path}")
                if not tarfile.is_tarfile(tar_path):
                    tar_path.unlink(missing_ok=True)
                    raise SystemExit(
                        f"Downloaded MERIT file is not a tar archive for {package_name}/{layer}. "
                        "This usually means Dropbox returned an HTML page instead of the file. "
                        "Use France-only for now, or fetch MERIT from a machine/session where the passworded Dropbox links are unlocked."
                    )
                with tarfile.open(tar_path) as archive:
                    archive.extractall(raw_dir)
                marker.write_text("ok", encoding="utf-8")
                tar_path.unlink(missing_ok=True)
            actions.append({"package": package_name, "layer": layer, "url": url})

    for layer in LAYERS:
        tif_paths = sorted((output_dir / "raw" / layer).rglob(f"*_{layer}.tif"))
        build_vrt(gdalbuildvrt, tif_paths, output_dir / "vrt" / f"merit_{layer}.vrt")

    write_json(output_dir / "downloaded_packages.json", actions)
    print(json.dumps({"packages": len(set(args.package)), "outputDir": str(output_dir)}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
