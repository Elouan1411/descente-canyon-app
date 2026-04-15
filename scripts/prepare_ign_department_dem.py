from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
import unicodedata
import urllib.request
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError

from cli_tools import default_7zip, default_gdalbuildvrt, resolve_executable


DATASET_KEYS = {
    "bdalti": "bdAlti",
    "rgealti5m": "rgeAlti5m",
    "rgealti1m": "rgeAlti1m",
}


def normalize_text(value: str | None) -> str:
    if not value:
        return ""
    normalized = unicodedata.normalize("NFKD", value)
    normalized = "".join(char for char in normalized if not unicodedata.combining(char))
    normalized = normalized.lower()
    normalized = re.sub(r"[^a-z0-9]+", "-", normalized)
    return normalized.strip("-")


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def acquire_lock(lock_path: Path, *, timeout_sec: int = 1800) -> int:
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    started = time.time()
    while True:
        try:
            return os.open(str(lock_path), os.O_CREAT | os.O_EXCL | os.O_RDWR)
        except FileExistsError:
            if time.time() - started > timeout_sec:
                raise SystemExit(f"Timeout waiting for IGN prepare lock: {lock_path}")
            time.sleep(5)


def release_lock(lock_fd: int, lock_path: Path) -> None:
    os.close(lock_fd)
    lock_path.unlink(missing_ok=True)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Telecharge, extrait et prepare un VRT IGN par departement."
    )
    parser.add_argument(
        "--manifest",
        type=Path,
        default=Path("build/watersheds/ign-plan/ign_download_manifest.json"),
    )
    parser.add_argument(
        "--dataset",
        choices=sorted(DATASET_KEYS),
        default="rgealti5m",
    )
    parser.add_argument(
        "--department",
        action="append",
        required=True,
        help="Nom ou code de departement; option repetable",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("build/watersheds/ign-data"),
    )
    parser.add_argument(
        "--gdalbuildvrt",
        default=default_gdalbuildvrt(),
    )
    parser.add_argument(
        "--sevenzip",
        default=default_7zip(),
    )
    parser.add_argument(
        "--skip-download",
        action="store_true",
    )
    parser.add_argument(
        "--skip-extract",
        action="store_true",
    )
    parser.add_argument(
        "--skip-vrt",
        action="store_true",
    )
    return parser.parse_args()


def resolve_departments(manifest: list[dict[str, Any]], requested: list[str]) -> list[dict[str, Any]]:
    index = {}
    for item in manifest:
        dept_name = item["department"]
        keys = {
            normalize_text(dept_name),
        }
        for dataset_key in DATASET_KEYS.values():
            payload = item.get(dataset_key)
            if payload is not None:
                keys.add(str(payload.get("departmentCode", "")).lower())
        for key in keys:
            if key:
                index[key] = item

    resolved = []
    for value in requested:
        item = index.get(normalize_text(value)) or index.get(value.lower())
        if item is None:
            raise SystemExit(f"Departement introuvable dans le manifeste: {value}")
        resolved.append(item)
    return resolved


def department_slug(item: dict[str, Any], dataset_field: str) -> str:
    payload = item.get(dataset_field) or {}
    code = str(payload.get("departmentCode") or "na")
    name = normalize_text(item.get("department") or "unknown")
    return f"{code}-{name}"


def download_file(url: str, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() and destination.stat().st_size > 0:
        print(f"skip download {destination.name}")
        return

    last_error: Exception | None = None
    temp_destination = destination.with_suffix(destination.suffix + ".part")
    for attempt in range(1, 6):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(request, timeout=300) as response, open(temp_destination, "wb") as handle:
                while True:
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    handle.write(chunk)
            temp_destination.replace(destination)
            print(f"downloaded {destination}")
            return
        except (HTTPError, URLError) as exc:
            last_error = exc
            temp_destination.unlink(missing_ok=True)
            if isinstance(exc, HTTPError) and exc.code not in {429, 500, 502, 503, 504}:
                raise
            wait_seconds = min(60, 5 * attempt)
            print(f"retry download {destination.name} after {wait_seconds}s due to {exc}")
            time.sleep(wait_seconds)

    raise SystemExit(f"Failed to download {url}: {last_error}")


def archive_roots(download_dir: Path) -> list[Path]:
    first_parts = sorted(download_dir.glob("*.7z.001"))
    if first_parts:
        return first_parts
    archives = sorted(download_dir.glob("*.7z"))
    return archives


def extract_archives(sevenzip: str, download_dir: Path, extract_dir: Path) -> None:
    extract_dir.mkdir(parents=True, exist_ok=True)
    marker = extract_dir / ".extracted"
    if marker.exists():
        if raster_inputs(extract_dir):
            print(f"skip extract {extract_dir}")
            return
        print(f"stale extract marker {marker}; re-extracting because no rasters were found")
        marker.unlink(missing_ok=True)

    roots = archive_roots(download_dir)
    if not roots:
        raise SystemExit(f"Aucune archive a extraire dans {download_dir}")

    for archive in roots:
        print(f"extract {archive.name}")
        subprocess.run(
            [sevenzip, "x", str(archive), f"-o{extract_dir}", "-y"],
            check=True,
        )
    extracted_rasters = raster_inputs(extract_dir)
    if not extracted_rasters:
        raise SystemExit(f"No rasters found in {extract_dir} after extraction from {download_dir}")
    marker.write_text("ok", encoding="utf-8")


def raster_inputs(raw_dir: Path) -> list[Path]:
    raster_suffixes = {".asc", ".tif", ".img"}
    return sorted(
        path
        for path in raw_dir.rglob("*")
        if path.is_file() and path.suffix.lower() in raster_suffixes
    )


def build_vrt(gdalbuildvrt: str, raw_dir: Path, vrt_path: Path) -> int:
    rasters = [path.resolve() for path in raster_inputs(raw_dir) if path.is_file()]
    if not rasters:
        raise SystemExit(f"Aucun raster trouve dans {raw_dir}")
    vrt_path.parent.mkdir(parents=True, exist_ok=True)
    input_list = vrt_path.parent / f".{vrt_path.stem}.{os.getpid()}.input-files.txt"
    input_list.write_text("\n".join(str(path) for path in rasters), encoding="utf-8")
    command = [gdalbuildvrt, "-input_file_list", str(input_list), str(vrt_path.resolve())]
    try:
        subprocess.run(command, check=True)
    except subprocess.CalledProcessError as exc:
        sample = ", ".join(str(path) for path in rasters[:3])
        raise SystemExit(
            f"gdalbuildvrt failed for {raw_dir} with {len(rasters)} raster(s). "
            f"Sample inputs: {sample}"
        ) from exc
    finally:
        input_list.unlink(missing_ok=True)
    return len(rasters)


def build_combined_vrt(gdalbuildvrt: str, dataset_root: Path, combined_vrt_path: Path) -> int:
    rasters = [path.resolve() for path in raster_inputs(dataset_root) if path.is_file()]
    if not rasters:
        raise SystemExit(f"Aucun raster trouve dans {dataset_root}")
    combined_vrt_path.parent.mkdir(parents=True, exist_ok=True)
    input_list = combined_vrt_path.parent / f".{combined_vrt_path.stem}.{os.getpid()}.input-files.txt"
    input_list.write_text("\n".join(str(path) for path in rasters), encoding="utf-8")
    try:
        subprocess.run(
            [gdalbuildvrt, "-input_file_list", str(input_list), str(combined_vrt_path.resolve())],
            check=True,
        )
    finally:
        input_list.unlink(missing_ok=True)
    return len(rasters)


def main() -> int:
    args = parse_args()
    gdalbuildvrt = resolve_executable(args.gdalbuildvrt, extra_candidates=[default_gdalbuildvrt()])
    sevenzip = resolve_executable(args.sevenzip, extra_candidates=[default_7zip(), "7zz"])
    manifest = load_json(args.manifest)
    departments = resolve_departments(manifest, args.department)
    dataset_field = DATASET_KEYS[args.dataset]
    output_dir = args.output_dir

    results = []
    combined_vrt = output_dir / "vrt" / args.dataset / "_all_downloaded.vrt"
    for item in departments:
        dataset_payload = item.get(dataset_field)
        if dataset_payload is None:
            raise SystemExit(f"Pas d'URL {args.dataset} pour {item['department']}")

        slug = department_slug(item, dataset_field)
        download_dir = output_dir / "downloads" / args.dataset / slug
        raw_dir = output_dir / "raw" / args.dataset / slug
        vrt_path = output_dir / "vrt" / args.dataset / f"{slug}.vrt"

        lock_path = output_dir / ".locks" / args.dataset / f"{slug}.lock"
        lock_fd = acquire_lock(lock_path)
        try:
            if not args.skip_download:
                for url in dataset_payload["urls"]:
                    filename = url.split("/")[-1]
                    download_file(url, download_dir / filename)

            if not args.skip_extract:
                extract_archives(sevenzip, download_dir, raw_dir)

            raster_count = None
            if not args.skip_vrt:
                raster_count = build_vrt(gdalbuildvrt, raw_dir, vrt_path)
        finally:
            release_lock(lock_fd, lock_path)

        results.append(
            {
                "department": item["department"],
                "dataset": args.dataset,
                "downloadDir": str(download_dir),
                "rawDir": str(raw_dir),
                "vrtPath": str(vrt_path),
                "rasterCount": raster_count,
                "urls": dataset_payload["urls"],
            }
        )

    combined_count = None
    if not args.skip_vrt:
        combined_lock_path = output_dir / ".locks" / args.dataset / ".combined.lock"
        combined_lock_fd = acquire_lock(combined_lock_path)
        try:
            combined_count = build_combined_vrt(
                gdalbuildvrt,
                output_dir / "raw" / args.dataset,
                combined_vrt,
            )
        finally:
            release_lock(combined_lock_fd, combined_lock_path)

    write_json(
        output_dir / f"prepare_{args.dataset}_result.json",
        {
            "departments": results,
            "combinedVrt": str(combined_vrt) if not args.skip_vrt else None,
            "combinedRasterCount": combined_count,
        },
    )
    print(json.dumps(results, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
