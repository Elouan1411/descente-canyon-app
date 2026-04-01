from __future__ import annotations

import argparse
import json
import math
import subprocess
import sys
import unicodedata
from pathlib import Path
from typing import Any

import rasterio
from rasterio.enums import Resampling
from rasterio.windows import Window
from rasterio.warp import calculate_default_transform, reproject, transform

from cli_tools import default_gdal_translate, resolve_executable


DEFAULT_LAMBERT93_PROJ4 = "+proj=lcc +lat_1=49 +lat_2=44 +lat_0=46.5 +lon_0=3 +x_0=700000 +y_0=6600000 +ellps=GRS80 +towgs84=0,0,0,0,0,0,0 +units=m +no_defs"


def normalize_text(value: str | None) -> str:
    if not value:
        return ""
    normalized = unicodedata.normalize("NFKD", value)
    normalized = "".join(char for char in normalized if not unicodedata.combining(char))
    return " ".join(normalized.lower().split())


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def materialize_dem_if_needed(input_dem: Path, output_dir: Path, gdal_translate: str, srs: str) -> Path:
    output_dir.mkdir(parents=True, exist_ok=True)
    if input_dem.suffix.lower() in {".tif", ".tiff"}:
        return input_dem
    materialized = output_dir / "department_dem.tif"
    if not materialized.exists():
        subprocess.run(
            [
                gdal_translate,
                "-of",
                "GTiff",
                "-a_srs",
                srs,
                "-co",
                "TILED=YES",
                "-co",
                "BIGTIFF=YES",
                "-co",
                "COMPRESS=LZW",
                str(input_dem),
                str(materialized),
            ],
            check=True,
        )
    return materialized


def clip_dem(
    source_dem: Path,
    output_dem: Path,
    points: list[tuple[float, float]],
    buffer_m: float,
    *,
    source_srs: str | None = None,
) -> None:
    with rasterio.Env(GDAL_CACHEMAX=256):
        with rasterio.open(source_dem) as src:
            crs_candidates = []
            if src.crs is not None:
                crs_candidates.append(src.crs)
            if source_srs is not None and str(source_srs) not in {str(candidate) for candidate in crs_candidates}:
                crs_candidates.append(source_srs)

            if not crs_candidates:
                raise SystemExit(f"DEM sans CRS exploitable: {source_dem}")

            longitudes = [point[1] for point in points]
            latitudes = [point[0] for point in points]
            last_window_error = None
            source_crs = None
            row_off = col_off = height = width = 0
            clipped_window = None

            for crs_candidate in crs_candidates:
                xs, ys = transform("EPSG:4326", crs_candidate, longitudes, latitudes)
                if hasattr(crs_candidate, "is_geographic") and crs_candidate.is_geographic:
                    mean_lat = sum(latitudes) / len(latitudes)
                    lat_buffer = buffer_m / 111_320.0
                    lon_buffer = buffer_m / (111_320.0 * max(0.1, abs(math.cos(math.radians(mean_lat)))))
                    min_x = min(xs) - lon_buffer
                    max_x = max(xs) + lon_buffer
                    min_y = min(ys) - lat_buffer
                    max_y = max(ys) + lat_buffer
                else:
                    min_x = min(xs) - buffer_m
                    max_x = max(xs) + buffer_m
                    min_y = min(ys) - buffer_m
                    max_y = max(ys) + buffer_m

                window = src.window(min_x, min_y, max_x, max_y)
                window = window.round_offsets().round_lengths()
                row_off = max(0, int(window.row_off))
                col_off = max(0, int(window.col_off))
                height = min(src.height - row_off, int(window.height))
                width = min(src.width - col_off, int(window.width))

                if row_off < src.height and col_off < src.width and height > 0 and width > 0:
                    source_crs = crs_candidate
                    clipped_window = Window(row_off=row_off, col_off=col_off, height=height, width=width)
                    break

                last_window_error = (
                    f"Clip window outside raster coverage for {source_dem}. "
                    f"crs={crs_candidate} row_off={row_off}, col_off={col_off}, height={height}, width={width}"
                )

            if clipped_window is None or source_crs is None:
                raise SystemExit(last_window_error or f"Clip window outside raster coverage for {source_dem}.")


            profile = src.profile.copy()
            profile.pop("blockxsize", None)
            profile.pop("blockysize", None)
            profile.pop("tiled", None)
            profile.pop("compress", None)
            profile.pop("interleave", None)
            profile.update(
                height=height,
                width=width,
                transform=src.window_transform(clipped_window),
                crs=source_crs,
                driver="GTiff",
                compress="lzw",
                tiled=True,
                BIGTIFF="YES",
            )

            output_dem.parent.mkdir(parents=True, exist_ok=True)
            with rasterio.open(output_dem, "w", **profile) as dst:
                for band_index in range(1, src.count + 1):
                    data = src.read(band_index, window=clipped_window)
                    dst.write(data, band_index)


def resample_dem(input_dem: Path, output_dem: Path, target_resolution: float) -> None:
    with rasterio.open(input_dem) as src:
        x_res = abs(src.transform.a)
        y_res = abs(src.transform.e)
        if x_res <= 0 or y_res <= 0:
            raise SystemExit(f"Invalid source resolution for {input_dem}")
        scale_x = x_res / target_resolution
        scale_y = y_res / target_resolution
        width = max(1, int(round(src.width * scale_x)))
        height = max(1, int(round(src.height * scale_y)))
        data = src.read(
            out_shape=(src.count, height, width),
            resampling=Resampling.bilinear,
        )
        transform_out = src.transform * src.transform.scale(src.width / width, src.height / height)
        profile = src.profile.copy()
        profile.update(
            driver="GTiff",
            width=width,
            height=height,
            transform=transform_out,
            compress="lzw",
            tiled=True,
            BIGTIFF="YES",
        )
        output_dem.parent.mkdir(parents=True, exist_ok=True)
        with rasterio.open(output_dem, "w", **profile) as dst:
            dst.write(data)


def reproject_dem(input_dem: Path, output_dem: Path, dst_crs: str) -> None:
    with rasterio.open(input_dem) as src:
        transform_out, width, height = calculate_default_transform(
            src.crs,
            dst_crs,
            src.width,
            src.height,
            *src.bounds,
        )
        profile = src.profile.copy()
        profile.update(
            driver="GTiff",
            crs=dst_crs,
            transform=transform_out,
            width=width,
            height=height,
            compress="lzw",
            tiled=True,
            BIGTIFF="YES",
        )
        output_dem.parent.mkdir(parents=True, exist_ok=True)
        with rasterio.open(output_dem, "w", **profile) as dst:
            for band_index in range(1, src.count + 1):
                reproject(
                    source=rasterio.band(src, band_index),
                    destination=rasterio.band(dst, band_index),
                    src_transform=src.transform,
                    src_crs=src.crs,
                    dst_transform=transform_out,
                    dst_crs=dst_crs,
                    resampling=Resampling.bilinear,
                )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Workflow local IGN: clip DEM, derive hydrology, compute entry watershed for target canyons."
    )
    parser.add_argument("--dem", type=Path, required=True)
    parser.add_argument(
        "--canyons-json",
        type=Path,
        default=Path("offline-data/full/room-import/canyons.json"),
    )
    parser.add_argument(
        "--geo-points-json",
        type=Path,
        default=Path("offline-data/full/room-import/geo_points.json"),
    )
    parser.add_argument("--department", action="append", default=[])
    parser.add_argument("--canyon-id", type=int, action="append", default=[])
    parser.add_argument("--buffer-km", type=float, default=20.0)
    parser.add_argument("--search-radius-m", type=float, default=120.0)
    parser.add_argument("--channel-min-upa-km2", type=float, default=0.05)
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("build/watersheds/local-ign-runs"),
    )
    parser.add_argument(
        "--gdal-translate",
        default=default_gdal_translate(),
    )
    parser.add_argument("--srs", default=DEFAULT_LAMBERT93_PROJ4)
    return parser.parse_args()


def select_canyon_ids(canyons: list[dict[str, Any]], departments: list[str], canyon_ids: list[int]) -> list[int]:
    selected = set(canyon_ids)
    normalized_departments = {normalize_text(value) for value in departments}
    if normalized_departments:
        for canyon in canyons:
            if normalize_text(canyon.get("departement")) in normalized_departments:
                selected.add(int(canyon["id"]))
    return sorted(selected)


def main() -> int:
    args = parse_args()
    canyons = load_json(args.canyons_json)
    canyon_by_id = {int(item["id"]): item for item in canyons}
    points = load_json(args.geo_points_json)
    gdal_translate = resolve_executable(args.gdal_translate, extra_candidates=[default_gdal_translate()])
    dem_path = materialize_dem_if_needed(args.dem.resolve(), args.output_dir.resolve(), gdal_translate, args.srs)

    points_by_canyon: dict[int, list[dict[str, Any]]] = {}
    for point in points:
        if point.get("type") != "ENTREE":
            continue
        points_by_canyon.setdefault(int(point["canyonId"]), []).append(point)

    target_ids = select_canyon_ids(canyons, args.department, args.canyon_id)
    if not target_ids:
        raise SystemExit("Aucun canyon cible selectionne")

    selected_outputs = []
    summaries = []
    buffer_m = args.buffer_km * 1000.0

    for canyon_id in target_ids:
        canyon = canyon_by_id.get(canyon_id)
        if canyon is None:
            continue
        entry_points = points_by_canyon.get(canyon_id, [])
        if not entry_points:
            continue

        canyon_dir = args.output_dir / str(canyon_id)
        clip_path = canyon_dir / "clip_dem.tif"
        hydrology_dir = canyon_dir / "hydrology"
        run_dir = canyon_dir / "run"

        clip_dem(
            dem_path,
            clip_path,
            [(float(point["latitude"]), float(point["longitude"])) for point in entry_points],
            buffer_m,
            source_srs=args.srs,
        )

        subprocess.run(
            [
                sys.executable,
                "scripts/derive_ign_hydrology.py",
                "--dem",
                str(clip_path),
                "--output-dir",
                str(hydrology_dir),
                "--work-dir",
                str(hydrology_dir / "work"),
                "--srs",
                args.srs,
            ],
            check=True,
        )

        subprocess.run(
            [
                sys.executable,
                "scripts/compute_entry_watersheds.py",
                "--canyons-json",
                str(args.canyons_json),
                "--geo-points-json",
                str(args.geo_points_json),
                "--upa-raster",
                str(hydrology_dir / "ign_upstream_area_km2.tif"),
                "--flowdir-raster",
                str(hydrology_dir / "ign_d8_pointer_esri.tif"),
                "--elevation-raster",
                str(hydrology_dir / "ign_breached_dem.tif"),
                "--candidate-strategy",
                "nearest_channel",
                "--search-radius-m",
                str(args.search_radius_m),
                "--channel-min-upa-km2",
                str(args.channel_min_upa_km2),
                "--only-canyon-id",
                str(canyon_id),
                "--output-dir",
                str(run_dir),
            ],
            check=True,
        )

        selected_data = load_json(run_dir / "selected_entries.json")
        summary_data = load_json(run_dir / "summary.json")
        selected_outputs.extend(selected_data)
        summaries.append(
            {
                "canyonId": canyon_id,
                "canyonName": canyon.get("nomComplet") or canyon.get("nom"),
                "bufferKm": args.buffer_km,
                "clipDem": str(clip_path),
                "runDir": str(run_dir),
                "summary": summary_data,
            }
        )

    write_json(args.output_dir / "selected_entries.json", selected_outputs)
    write_json(args.output_dir / "run_summaries.json", summaries)
    print(json.dumps({"processedCanyons": len(summaries)}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
