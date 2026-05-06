from __future__ import annotations

import argparse
import json
import os
import shlex
import subprocess
from dataclasses import dataclass, field
from pathlib import Path
import sys

_SCRIPT_ROOT = next(parent for parent in Path(__file__).resolve().parents if (parent / "common").is_dir())
if str(_SCRIPT_ROOT) not in sys.path:
    sys.path.insert(0, str(_SCRIPT_ROOT))

import numpy as np
import rasterio
from whitebox.whitebox_tools import WhiteboxTools

from common.cli_tools import default_gdal_translate, resolve_executable


DEFAULT_LAMBERT93_PROJ4 = "+proj=lcc +lat_1=49 +lat_2=44 +lat_0=46.5 +lon_0=3 +x_0=700000 +y_0=6600000 +ellps=GRS80 +towgs84=0,0,0,0,0,0,0 +units=m +no_defs"


@dataclass
class WhiteboxLogCapture:
    emit: bool = False
    max_lines: int = 400
    lines: list[str] = field(default_factory=list)

    def __call__(self, line: str) -> None:
        text = str(line).rstrip()
        if not text:
            return
        self.lines.append(text)
        if len(self.lines) > self.max_lines:
            self.lines = self.lines[-self.max_lines :]
        if self.emit:
            print(text, flush=True)

    def clear(self) -> None:
        self.lines.clear()

    def tail_text(self, limit: int = 60) -> str:
        if not self.lines:
            return "<no whitebox output captured>"
        tail = self.lines[-limit:]
        return "\n".join(tail)


def write_json(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def reset_outputs(paths: list[Path]) -> None:
    for path in paths:
        path.unlink(missing_ok=True)


def validate_outputs(paths: list[Path], *, step_name: str) -> None:
    missing = [str(path) for path in paths if not path.exists() or path.stat().st_size == 0]
    if missing:
        raise RuntimeError(
            f"{step_name} did not create expected raster outputs: {', '.join(missing)}"
        )


def describe_raster(path: Path) -> str:
    with rasterio.open(path) as src:
        x_res = abs(src.transform.a)
        y_res = abs(src.transform.e)
        return (
            f"path={path} width={src.width} height={src.height} bands={src.count} "
            f"dtype={','.join(src.dtypes)} nodata={src.nodata} crs={src.crs} "
            f"resolution=({x_res}, {y_res}) bounds={tuple(round(value, 6) for value in src.bounds)}"
        )


def build_whitebox_failure_message(
    *,
    dem_path: Path,
    step_name: str,
    workflow_status: int,
    outputs: list[Path],
    log_capture: WhiteboxLogCapture,
    cause: Exception | None = None,
) -> str:
    missing = [str(path) for path in outputs if not path.exists() or path.stat().st_size == 0]
    parts = [
        f"{step_name} failed for DEM {dem_path}",
        f"DEM info: {describe_raster(dem_path)}",
        (
            f"Whitebox wrapper returned status={workflow_status}. "
            "Note: the Python wrapper can still return 0 even when the Whitebox executable fails, "
            "so missing outputs and the tool log below are the most useful diagnostics."
        ),
    ]
    if missing:
        parts.append(f"Missing outputs: {', '.join(missing)}")
    if cause is not None:
        parts.append(f"Validation error: {type(cause).__name__}: {cause}")
    parts.append("Whitebox output tail:\n" + log_capture.tail_text())
    return "\n".join(parts)


def raster_crs_string(path: Path) -> str | None:
    with rasterio.open(path) as src:
        return src.crs.to_string() if src.crs is not None else None


def resolve_whitebox_executable() -> Path:
    wbt = WhiteboxTools()
    executable = Path(wbt.exe_path) / wbt.exe_name
    if executable.exists():
        return executable
    fallback = Path(wbt.exe_path) / "WBT" / wbt.exe_name
    if fallback.exists():
        return fallback
    raise SystemExit(f"Whitebox executable not found near {wbt.exe_path}")


def quarantine_invalid_whitebox_settings(executable_path: Path) -> Path | None:
    settings_path = executable_path.parent / "settings.json"
    if not settings_path.exists():
        return None
    try:
        json.loads(settings_path.read_text(encoding="utf-8"))
        return None
    except json.JSONDecodeError:
        backup_path = executable_path.parent / f"settings.invalid-{os.getpid()}.json"
        try:
            backup_path.unlink(missing_ok=True)
            settings_path.replace(backup_path)
        except FileNotFoundError:
            return None
        except OSError as exc:
            raise SystemExit(
                f"Whitebox settings.json is invalid and could not be quarantined: {settings_path} ({exc})"
            ) from exc
        return backup_path


def run_whitebox_command(
    *,
    executable_path: Path,
    args: list[str],
    log_capture: WhiteboxLogCapture,
) -> int:
    log_capture.clear()
    quarantined = quarantine_invalid_whitebox_settings(executable_path)
    if quarantined is not None:
        log_capture(f"Quarantined invalid Whitebox settings file: {quarantined}")
    command = [str(executable_path), *args]
    log_capture(shlex.join(command))
    process = subprocess.Popen(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    assert process.stdout is not None
    for line in process.stdout:
        log_capture(line)
    return process.wait()


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


def materialize_dem_if_needed(
    input_dem: Path,
    output_dir: Path,
    gdal_translate: str,
    srs: str,
    *,
    force: bool = False,
    output_name: str = "dem_for_whitebox.tif",
    compress: str = "LZW",
    tiled: bool = True,
) -> Path:
    if not force and input_dem.suffix.lower() in {".tif", ".tiff"}:
        return input_dem

    materialized = output_dir / output_name
    if materialized.exists():
        materialized.unlink()

    command = [
        gdal_translate,
        "-of",
        "GTiff",
        "-co",
        "BIGTIFF=YES",
    ]
    source_crs = raster_crs_string(input_dem)
    if source_crs is None:
        command.extend(["-a_srs", srs])
    if tiled:
        command.extend(["-co", "TILED=YES"])
    if compress and compress.upper() != "NONE":
        command.extend(["-co", f"COMPRESS={compress}"])
    command.extend([str(input_dem), str(materialized)])
    subprocess.run(command, check=True)
    return materialized


def validate_input_dem(input_dem: Path) -> None:
    with rasterio.open(input_dem) as src:
        if src.width < 2 or src.height < 2:
            raise SystemExit(f"DEM too small for Whitebox: {input_dem} ({src.width}x{src.height})")
        data = src.read(1, masked=True)
        valid = (~np.ma.getmaskarray(data)) & np.isfinite(data.data)
        valid_count = int(np.count_nonzero(valid))
        if valid_count == 0:
            raise SystemExit(f"DEM has no valid cells for Whitebox: {input_dem}")


def run_flow_accumulation_workflow(
    *,
    whitebox_executable: Path,
    dem_path: Path,
    breached_dem: Path,
    pointer_raster: Path,
    accumulation_m2: Path,
    out_type: str,
    log_capture: WhiteboxLogCapture,
) -> None:
    reset_outputs([breached_dem, pointer_raster, accumulation_m2])
    workflow_status = run_whitebox_command(
        executable_path=whitebox_executable,
        args=[
            "--run=FlowAccumulationFullWorkflow",
            f"--dem={dem_path}",
            f"--out_dem={breached_dem}",
            f"--out_pntr={pointer_raster}",
            f"--out_accum={accumulation_m2}",
            f"--out_type={out_type}",
            "--esri_pntr",
        ],
        log_capture=log_capture,
    )
    if workflow_status != 0:
        raise RuntimeError(
            build_whitebox_failure_message(
                dem_path=dem_path,
                step_name="Whitebox flow_accumulation_full_workflow",
                workflow_status=workflow_status,
                outputs=[breached_dem, pointer_raster, accumulation_m2],
                log_capture=log_capture,
            )
        )
    try:
        validate_outputs(
            [breached_dem, pointer_raster, accumulation_m2],
            step_name="Whitebox flow_accumulation_full_workflow",
        )
    except RuntimeError as exc:
        raise RuntimeError(
            build_whitebox_failure_message(
                dem_path=dem_path,
                step_name="Whitebox flow_accumulation_full_workflow",
                workflow_status=workflow_status,
                outputs=[breached_dem, pointer_raster, accumulation_m2],
                log_capture=log_capture,
                cause=exc,
            )
        ) from exc


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
    validate_input_dem(dem_for_whitebox)

    breached_dem = output_dir / "ign_breached_dem.tif"
    pointer_raster = output_dir / "ign_d8_pointer_esri.tif"
    accumulation_m2 = output_dir / "ign_upstream_area_m2.tif"
    accumulation_km2 = output_dir / "ign_upstream_area_km2.tif"
    reset_outputs([breached_dem, pointer_raster, accumulation_m2, accumulation_km2])

    whitebox_executable = resolve_whitebox_executable()
    if args.work_dir is not None:
        work_dir = args.work_dir.resolve()
        work_dir.mkdir(parents=True, exist_ok=True)

    log_capture = WhiteboxLogCapture(emit=args.whitebox_verbose)
    whitebox_attempts: list[dict[str, str]] = []
    try:
        run_flow_accumulation_workflow(
            whitebox_executable=whitebox_executable,
            dem_path=dem_for_whitebox,
            breached_dem=breached_dem,
            pointer_raster=pointer_raster,
            accumulation_m2=accumulation_m2,
            out_type=args.out_type,
            log_capture=log_capture,
        )
        whitebox_attempts.append({"dem": str(dem_for_whitebox), "mode": "primary", "status": "ok"})
    except Exception as exc:
        whitebox_attempts.append(
            {
                "dem": str(dem_for_whitebox),
                "mode": "primary",
                "status": f"error:{type(exc).__name__}: {exc}",
                "whiteboxOutputTail": log_capture.tail_text(),
            }
        )
        retry_dem = materialize_dem_if_needed(
            args.dem.resolve(),
            output_dir,
            gdal_translate,
            args.srs,
            force=True,
            output_name="dem_for_whitebox_retry.tif",
            compress="NONE",
            tiled=False,
        )
        validate_input_dem(retry_dem)
        try:
            run_flow_accumulation_workflow(
                whitebox_executable=whitebox_executable,
                dem_path=retry_dem,
                breached_dem=breached_dem,
                pointer_raster=pointer_raster,
                accumulation_m2=accumulation_m2,
                out_type=args.out_type,
                log_capture=log_capture,
            )
            dem_for_whitebox = retry_dem
            whitebox_attempts.append({"dem": str(retry_dem), "mode": "retry_plain_geotiff", "status": "ok"})
        except Exception as retry_exc:
            whitebox_attempts.append(
                {
                    "dem": str(retry_dem),
                    "mode": "retry_plain_geotiff",
                    "status": f"error:{type(retry_exc).__name__}: {retry_exc}",
                    "whiteboxOutputTail": log_capture.tail_text(),
                }
            )
            raise SystemExit(
                "Whitebox hydrology failed after retry. "
                f"\nPrimary attempt:\n{exc}\n"
                f"\nRetry attempt:\n{retry_exc}"
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
        "whiteboxExecutable": str(whitebox_executable),
        "whiteboxOutType": args.out_type,
        "whiteboxAttempts": whitebox_attempts,
        "notes": [
            "Le raster d8PointerEsri est compatible avec compute_entry_watersheds.py.",
            "Le raster upstreamAreaKm2 est la couche a utiliser comme --upa-raster pour les runs IGN si out_type=ca.",
            "Whitebox est lance avec des chemins absolus sans --wd/-v/--compress_rasters pour eviter les ecritures concurrentes dans settings.json.",
        ],
    }
    write_json(output_dir / "metadata.json", metadata)
    print(json.dumps(metadata, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
