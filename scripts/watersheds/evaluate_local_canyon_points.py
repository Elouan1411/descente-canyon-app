from __future__ import annotations

import argparse
from dataclasses import asdict
import json
import subprocess
import sys
from collections import defaultdict
from pathlib import Path
import sys

_SCRIPT_ROOT = next(parent for parent in Path(__file__).resolve().parents if (parent / "common").is_dir())
if str(_SCRIPT_ROOT) not in sys.path:
    sys.path.insert(0, str(_SCRIPT_ROOT))
from typing import Any

from compute_entry_watersheds import EntryPoint, create_raster, evaluate_entry, load_canyons
from common.cli_tools import default_gdal_translate, resolve_executable
from run_local_ign_workflow import clip_dem, materialize_dem_if_needed, DEFAULT_LAMBERT93_PROJ4


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Evalue plusieurs types de points canyon sur un workflow hydrologique IGN local."
    )
    parser.add_argument("--dem", type=Path, required=True)
    parser.add_argument("--canyon-id", type=int, action="append", required=True)
    parser.add_argument(
        "--point-type",
        action="append",
        default=["ENTREE", "SORTIE", "PARKING_AMONT", "PARKING_AVAL"],
    )
    parser.add_argument("--buffer-km", type=float, default=20.0)
    parser.add_argument("--search-radius-m", type=float, default=120.0)
    parser.add_argument("--channel-min-upa-km2", type=float, default=0.05)
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
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("build/watersheds/local-point-evals"),
    )
    parser.add_argument(
        "--gdal-translate",
        default=default_gdal_translate(),
    )
    parser.add_argument("--srs", default=DEFAULT_LAMBERT93_PROJ4)
    parser.add_argument("--skip-existing", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    gdal_translate = resolve_executable(args.gdal_translate, extra_candidates=[default_gdal_translate()])
    dem_path = materialize_dem_if_needed(args.dem.resolve(), output_dir, gdal_translate, args.srs)

    canyons = load_canyons(args.canyons_json)
    points = load_json(args.geo_points_json)
    point_types = set(args.point_type)

    points_by_canyon: dict[int, list[dict[str, Any]]] = defaultdict(list)
    for point in points:
        if point.get("type") in point_types:
            points_by_canyon[int(point["canyonId"])] .append(point)

    results = []
    summaries = []

    for canyon_id in args.canyon_id:
        canyon = canyons.get(canyon_id)
        if canyon is None:
            continue
        canyon_dir = output_dir / str(canyon_id)
        canyon_result_path = canyon_dir / "canyon_evaluation.json"
        if args.skip_existing and canyon_result_path.exists():
            results.append(load_json(canyon_result_path))
            summaries.append({"canyonId": canyon_id, "canyonName": (canyons[canyon_id].get("nomComplet") or canyons[canyon_id].get("nom")), "status": "reused"})
            continue
        source_points = points_by_canyon.get(canyon_id, [])
        if not source_points:
            summaries.append({"canyonId": canyon_id, "canyonName": canyon.get("nomComplet") or canyon.get("nom"), "status": "no_requested_points"})
            continue

        clip_path = canyon_dir / "clip_dem.tif"
        hydrology_dir = canyon_dir / "hydrology"
        run_metadata_path = canyon_dir / "metadata.json"

        clip_dem(
            dem_path,
            clip_path,
            [(float(point["latitude"]), float(point["longitude"])) for point in source_points],
            args.buffer_km * 1000.0,
            source_srs=args.srs,
        )

        if not (hydrology_dir / "ign_upstream_area_km2.tif").exists():
            subprocess.run(
                [
                    sys.executable,
                    "scripts/watersheds/sources/derive_ign_hydrology.py",
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

        upa_raster = create_raster(hydrology_dir / "ign_upstream_area_km2.tif", band_index=1, name="upa")
        flowdir_raster = create_raster(hydrology_dir / "ign_d8_pointer_esri.tif", band_index=1, name="dir")
        elevation_raster = create_raster(hydrology_dir / "ign_breached_dem.tif", band_index=1, name="elv")
        try:
            canyon_results = []
            for point_index, point in enumerate(source_points, start=1):
                entry = EntryPoint(
                    canyon_id=canyon_id,
                    canyon_name=str(canyon.get("nomComplet") or canyon.get("nom") or canyon_id),
                    entry_index=point_index,
                    geo_point_index=point_index,
                    latitude=float(point["latitude"]),
                    longitude=float(point["longitude"]),
                    label=point.get("label"),
                )
                evaluated = evaluate_entry(
                    entry,
                    upa_raster,
                    flowdir_raster,
                    elevation_raster,
                    search_radius_cells=2,
                    search_radius_m=args.search_radius_m,
                    candidate_strategy="nearest_channel",
                    channel_min_upa_km2=args.channel_min_upa_km2,
                )
                payload = {
                    "pointType": point.get("type"),
                    "label": point.get("label"),
                    "latitude": point.get("latitude"),
                    "longitude": point.get("longitude"),
                    "evaluation": asdict(evaluated),
                }
                canyon_results.append(payload)

            result = {
                "canyonId": canyon_id,
                "canyonName": canyon.get("nomComplet") or canyon.get("nom"),
                "points": canyon_results,
            }
            write_json(canyon_result_path, result)
            results.append(result)
            summaries.append({"canyonId": canyon_id, "canyonName": result["canyonName"], "status": "ok", "pointCount": len(canyon_results)})
        finally:
            upa_raster.close()
            if flowdir_raster is not None:
                flowdir_raster.close()
            if elevation_raster is not None:
                elevation_raster.close()

        write_json(run_metadata_path, {"canyonId": canyon_id, "bufferKm": args.buffer_km, "searchRadiusM": args.search_radius_m, "channelMinUpaKm2": args.channel_min_upa_km2})

    write_json(output_dir / "evaluations.json", results)
    write_json(output_dir / "summary.json", summaries)
    print(json.dumps({"evaluatedCanyons": len(results)}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
