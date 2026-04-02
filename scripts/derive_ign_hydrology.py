from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path

import rasterio
from whitebox.whitebox_tools import WhiteboxTools

from cli_tools import default_gdal_translate, resolve_executable


DEFAULT_LAMBERT93_PROJ4 = "+proj=lcc +lat_1=49 +lat_2=44 +lat_0=46.5 +lon_0=3 +x_0=700000 +y_0=6600000 +ellps=GRS80 +towgs84=0,0,0,0,0,0,0 +units=m +no_defs"


def write_json(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def reset_outputs(paths: list[Path]) -> None:
    for path in paths:
        path.unlink(missing_ok=True)


def validate_outputs(paths: list[Path], *, step_name: str) -> None:
    missing = [str(path) for path in paths if not path.exists() or path.stat().st_size == 0]
    if missing:
        raise SystemExit(
            f"{step_name} did not create expected raster outputs: {', '.join(missing)}"
        )


def convert_area_m2_to_km2(input_path: Path, output_path: Path) -> None:
    with rasterio.open(input_path) as src:
        profile = src.profile.copy()
        profile.update(dtype="float32", compress="lzw")
        output_path.parent.mkdir(parents=True, exist_ok=True)
        with rasterio.open(output_path, "w", **profile) as dst:
            for band_index in range(1, src.count + 1):
                data = src.read(band_index, masked=True)
                data = (data / 1_000_000.0).astype("float32")
                dst.write(data.filled(profile.get("nodata") if profile.get("nodata") is not None else -9999), band_index)


def materialize_dem_if_needed(input_dem: Path, output_dir: Path, gdal_translate: str, srs: str) -> Path:
    if input_dem.suffix.lower() in {".tif", ".tiff"}:
        return input_dem

    materialized = output_dir / "dem_for_whitebox.tif"
    if materialized.exists():
        materialized.unlink()

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


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Derive flow direction and upstream area rasters from an IGN DEM."
    )
    parser.add_argument("--dem", type=Path, required=True, help="DEM IGN en projection metrique (GeoTIFF/VRT)")
    parser.add_argument(
        "--output-dir",
        type=Path,
        required=True,
        help="Dossier de sortie pour le DEM breche, le pointeur D8 et l'UPA",
    )
    parser.add_argument(
        "--work-dir",
        type=Path,
        help="Dossier de travail WhiteboxTools",
    )
    parser.add_argument(
        "--out-type",
        choices=["ca", "cells", "sca"],
        default="ca",
        help="Type de sortie Whitebox pour l'accumulation; utiliser 'ca' pour catchment area",
    )
    parser.add_argument(
        "--gdal-translate",
        default=default_gdal_translate(),
    )
    parser.add_argument(
        "--srs",
        default=DEFAULT_LAMBERT93_PROJ4,
        help="CRS horizontal a assigner si le DEM source ne porte pas ses geokeys",
    )
    parser.add_argument("--whitebox-verbose", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    gdal_translate = resolve_executable(args.gdal_translate, extra_candidates=[default_gdal_translate()])
    dem_for_whitebox = materialize_dem_if_needed(
        args.dem.resolve(),
        output_dir,
        gdal_translate,
        args.srs,
    )

    breached_dem = output_dir / "ign_breached_dem.tif"
    pointer_raster = output_dir / "ign_d8_pointer_esri.tif"
    accumulation_m2 = output_dir / "ign_upstream_area_m2.tif"
    accumulation_km2 = output_dir / "ign_upstream_area_km2.tif"
    reset_outputs([breached_dem, pointer_raster, accumulation_m2, accumulation_km2])

    wbt = WhiteboxTools()
    if args.work_dir is not None:
        work_dir = args.work_dir.resolve()
        work_dir.mkdir(parents=True, exist_ok=True)
        wbt.set_working_dir(str(work_dir))

    wbt.verbose = args.whitebox_verbose
    workflow_status = wbt.flow_accumulation_full_workflow(
        dem=str(dem_for_whitebox),
        out_dem=str(breached_dem),
        out_pntr=str(pointer_raster),
        out_accum=str(accumulation_m2),
        out_type=args.out_type,
        esri_pntr=True,
    )
    if workflow_status != 0:
        raise SystemExit(f"Whitebox flow_accumulation_full_workflow failed with status={workflow_status}")
    validate_outputs(
        [breached_dem, pointer_raster, accumulation_m2],
        step_name="Whitebox flow_accumulation_full_workflow",
    )

    if args.out_type == "ca":
        convert_area_m2_to_km2(accumulation_m2, accumulation_km2)
        validate_outputs([accumulation_km2], step_name="convert_area_m2_to_km2")

    metadata = {
        "inputDem": str(args.dem),
        "demForWhitebox": str(dem_for_whitebox),
        "outputs": {
            "breachedDem": str(breached_dem),
            "d8PointerEsri": str(pointer_raster),
            "upstreamAreaM2": str(accumulation_m2),
            "upstreamAreaKm2": str(accumulation_km2) if args.out_type == "ca" else None,
        },
        "whiteboxOutType": args.out_type,
        "notes": [
            "Le raster d8PointerEsri est compatible avec compute_entry_watersheds.py.",
            "Le raster upstreamAreaKm2 est la couche a utiliser comme --upa-raster pour les runs IGN si out_type=ca.",
        ],
    }
    write_json(output_dir / "metadata.json", metadata)
    print(json.dumps(metadata, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
