from __future__ import annotations

import argparse
import concurrent.futures
import json
import math
import shutil
import subprocess
import sys
from dataclasses import asdict
from pathlib import Path
from typing import Any

from cli_tools import default_gdal_translate, resolve_executable
from compute_entry_watersheds import EntryPoint, create_raster, evaluate_entry, load_canyons
from run_local_ign_canyon_workflow import DEFAULT_LAMBERT93_PROJ4


POINT_TYPE_PRIORITY = {
    "ENTREE": 0,
    "SORTIE": 1,
    "PARKING_AMONT": 2,
    "PARKING_AVAL": 3,
    "POINT_REMARQUABLE": 4,
}


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def normalized_path(value: str | Path | None) -> Path | None:
    if value is None:
        return None
    return Path(str(value).replace("\\", "/"))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Batch canyon-by-canyon resumable catchment computation across the whole base."
    )
    parser.add_argument(
        "--source-config",
        type=Path,
        default=Path("scripts/watersheds/source_config.example.json"),
    )
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
        default=Path("build/watersheds/batch-run"),
    )
    parser.add_argument("--only-canyon-id", type=int, action="append")
    parser.add_argument("--max-canyons", type=int)
    parser.add_argument("--jobs", type=int, default=1)
    parser.add_argument("--skip-existing", action="store_true", default=True)
    parser.add_argument(
        "--point-type",
        action="append",
        default=["ENTREE", "SORTIE", "PARKING_AMONT", "PARKING_AVAL", "POINT_REMARQUABLE"],
    )
    parser.add_argument(
        "--gdal-translate",
        default=default_gdal_translate(),
    )
    parser.add_argument("--country", action="append")
    parser.add_argument("--france-only", action="store_true")
    parser.add_argument("--prepare-france-ign-first", action="store_true")
    parser.add_argument("--keep-work", action="store_true")
    return parser.parse_args()


def canyon_matches(canyon: dict[str, Any], match: dict[str, Any]) -> bool:
    if match.get("default"):
        return True
    for key, expected in match.items():
        if key == "default":
            continue
        actual = canyon.get(key)
        if isinstance(expected, list):
            if actual not in expected:
                return False
            continue
        if actual != expected:
            return False
    return True


def source_is_available(source: dict[str, Any]) -> bool:
    mode = source.get("mode")
    if mode == "derive_local_hydrology":
        dem = source.get("dem")
        dem_path = normalized_path(dem)
        return dem_path is not None and dem_path.exists()
    if mode == "precomputed_hydrology":
        required = [source.get("upaRaster")]
        optional = [source.get("flowdirRaster"), source.get("elevationRaster")]
        if not all(required):
            return False
        if not all(normalized_path(path).exists() for path in required if path):
            return False
        if not all(normalized_path(path).exists() for path in optional if path):
            return False
        return True
    return False


def copernicus_cell_name(latitude: float, longitude: float) -> str:
    lat_prefix = "N" if latitude >= 0 else "S"
    lon_prefix = "E" if longitude >= 0 else "W"
    lat_degree = abs(math.floor(float(latitude)))
    lon_degree = abs(math.floor(float(longitude)))
    return f"{lat_prefix}{lat_degree:02d}_{lon_prefix}{lon_degree:03d}"


def merit_package_name(latitude: float, longitude: float) -> str:
    lat0 = int(float(latitude) // 30) * 30
    lon0 = int(float(longitude) // 30) * 30
    lat_prefix = "n" if lat0 >= 0 else "s"
    lon_prefix = "e" if lon0 >= 0 else "w"
    return f"{lat_prefix}{abs(lat0):02d}{lon_prefix}{abs(lon0):03d}"


def points_copernicus_cells(points: list[dict[str, Any]]) -> list[str]:
    return sorted({copernicus_cell_name(float(point["latitude"]), float(point["longitude"])) for point in points})


def points_merit_packages(points: list[dict[str, Any]]) -> list[str]:
    return sorted({merit_package_name(float(point["latitude"]), float(point["longitude"])) for point in points})


def planned_source_sort_key(canyon: dict[str, Any], points: list[dict[str, Any]], sources: list[dict[str, Any]]) -> tuple[Any, ...]:
    for source in sources:
        if not canyon_matches(canyon, source.get("match", {})):
            continue
        provider = (source.get("autoPrepare") or {}).get("provider")
        if provider == "ign":
            return (0, str(canyon.get("departement") or ""), int(canyon["id"]))
        if provider == "copernicus":
            return (1, ",".join(points_copernicus_cells(points)), int(canyon["id"]))
        if provider == "merit":
            return (2, ",".join(points_merit_packages(points)), int(canyon["id"]))
        return (3, source.get("name", ""), int(canyon["id"]))
    return (9, int(canyon["id"]))


def auto_prepare_source(
    *,
    source: dict[str, Any],
    canyon: dict[str, Any],
    points: list[dict[str, Any]],
    output_dir: Path,
    gdal_translate: str,
) -> None:
    auto_prepare = source.get("autoPrepare")
    if not auto_prepare:
        return

    provider = auto_prepare.get("provider")
    if provider == "ign":
        manifest_path = Path(auto_prepare.get("manifest", "build/watersheds/ign-plan/ign_download_manifest.json"))
        if not manifest_path.exists():
            subprocess.run(
                [sys.executable, "scripts/fetch_ign_alti_catalog.py"],
                check=True,
            )
            subprocess.run(
                [sys.executable, "scripts/plan_ign_downloads.py"],
                check=True,
            )
        subprocess.run(
            [
                sys.executable,
                "scripts/prepare_ign_department_dem.py",
                "--manifest",
                str(manifest_path),
                "--dataset",
                auto_prepare.get("dataset", "rgealti5m"),
                "--department",
                str(canyon.get("departement") or ""),
                "--output-dir",
                auto_prepare.get("outputDir", "build/watersheds/ign-data"),
            ],
            check=True,
        )
        return

    if provider == "copernicus":
        command = [
            sys.executable,
            "scripts/prepare_copernicus_dem.py",
            "--manifest",
            auto_prepare.get("manifest", "scripts/watersheds/copernicus_url_manifest.example.json"),
            "--output-dir",
            auto_prepare.get("outputDir", "build/watersheds/copernicus-data"),
        ]
        for cell in points_copernicus_cells(points):
            command.extend(["--cell", cell])
        subprocess.run(command, check=True)
        return

    if provider == "merit":
        command = [
            sys.executable,
            "scripts/prepare_merit_hydrology.py",
            "--manifest",
            auto_prepare.get("manifest", "scripts/watersheds/merit_url_manifest.example.json"),
            "--output-dir",
            auto_prepare.get("outputDir", "build/watersheds/merit"),
        ]
        for package_name in points_merit_packages(points):
            command.extend(["--package", package_name])
        subprocess.run(command, check=True)
        return

    raise SystemExit(f"Unsupported autoPrepare provider: {provider}")


def bootstrap_ign_manifest(manifest_path: Path) -> None:
    if manifest_path.exists():
        return
    subprocess.run([sys.executable, "scripts/fetch_ign_alti_catalog.py"], check=True)
    subprocess.run([sys.executable, "scripts/plan_ign_downloads.py"], check=True)


def prepare_all_france_ign_sources(canyon_ids: list[int], canyons: dict[int, dict[str, Any]], output_dir: Path) -> None:
    manifest_path = Path("build/watersheds/ign-plan/ign_download_manifest.json")
    bootstrap_ign_manifest(manifest_path)
    manifest = load_json(manifest_path)
    by_department = {item["department"]: item for item in manifest}
    departments = sorted(
        {
            canyons[canyon_id].get("departement")
            for canyon_id in canyon_ids
            if canyons[canyon_id].get("pays") == "France"
            and canyons[canyon_id].get("departement")
            and "," not in str(canyons[canyon_id].get("departement"))
        }
    )

    for department in departments:
        item = by_department.get(department)
        if not item:
            continue
        for dataset_field, dataset_name in (("rgeAlti5m", "rgealti5m"), ("bdAlti", "bdalti")):
            if not item.get(dataset_field):
                continue
            subprocess.run(
                [
                    sys.executable,
                    "scripts/prepare_ign_department_dem.py",
                    "--manifest",
                    str(manifest_path),
                    "--dataset",
                    dataset_name,
                    "--department",
                    department,
                    "--output-dir",
                    "build/watersheds/ign-data",
                ],
                check=True,
            )


def resolve_source_for_canyon(
    *,
    canyon: dict[str, Any],
    points: list[dict[str, Any]],
    sources: list[dict[str, Any]],
    output_dir: Path,
    gdal_translate: str,
) -> dict[str, Any] | None:
    for source in sources:
        if not canyon_matches(canyon, source.get("match", {})):
            continue
        if not source_is_available(source):
            try:
                auto_prepare_source(
                    source=source,
                    canyon=canyon,
                    points=points,
                    output_dir=output_dir,
                    gdal_translate=gdal_translate,
                )
            except Exception:
                continue
        if source_is_available(source):
            return source
    return None


def choose_source(canyon: dict[str, Any], sources: list[dict[str, Any]]) -> dict[str, Any] | None:
    for source in sources:
        if canyon_matches(canyon, source.get("match", {})) and source_is_available(source):
            return source
    return None


def all_canyon_points(geo_points: list[dict[str, Any]], point_types: set[str]) -> dict[int, list[dict[str, Any]]]:
    by_canyon: dict[int, list[dict[str, Any]]] = {}
    for point in geo_points:
        if point.get("type") not in point_types:
            continue
        by_canyon.setdefault(int(point["canyonId"]), []).append(point)
    return by_canyon


def ensure_local_hydrology(
    *,
    source: dict[str, Any],
    canyon_id: int,
    output_dir: Path,
    points: list[dict[str, Any]],
    gdal_translate: str,
) -> dict[str, Path]:
    from run_local_ign_canyon_workflow import clip_dem

    dem_path = normalized_path(source["dem"]).resolve()
    if not dem_path.exists():
        raise SystemExit(f"DEM source not found: {dem_path}")

    canyon_work_dir = output_dir / "work" / str(canyon_id)
    clip_path = canyon_work_dir / "clip_dem.tif"
    hydrology_dir = canyon_work_dir / "hydrology"
    upa_path = hydrology_dir / "ign_upstream_area_km2.tif"
    flowdir_path = hydrology_dir / "ign_d8_pointer_esri.tif"
    elevation_path = hydrology_dir / "ign_breached_dem.tif"

    if not upa_path.exists() or not flowdir_path.exists() or not elevation_path.exists():
        clip_dem(
            dem_path,
            clip_path,
            [(float(point["latitude"]), float(point["longitude"])) for point in points],
            float(source.get("bufferKm", 20.0)) * 1000.0,
            source_srs=source.get("srs"),
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
                source.get("srs", DEFAULT_LAMBERT93_PROJ4),
            ],
            check=True,
        )

    return {
        "upa": upa_path,
        "flowdir": flowdir_path,
        "elevation": elevation_path,
    }


def evaluate_points_for_canyon(
    *,
    canyon: dict[str, Any],
    points: list[dict[str, Any]],
    source: dict[str, Any],
    raster_paths: dict[str, Path],
) -> list[dict[str, Any]]:
    upa_raster = create_raster(raster_paths["upa"], band_index=int(source.get("upaBand", 1)), name="upa")
    flowdir_raster = create_raster(raster_paths.get("flowdir"), band_index=int(source.get("flowdirBand", 1)), name="dir")
    elevation_raster = create_raster(raster_paths.get("elevation"), band_index=int(source.get("elevationBand", 1)), name="elv")
    try:
        results = []
        for point_index, point in enumerate(points, start=1):
            entry = EntryPoint(
                canyon_id=int(canyon["id"]),
                canyon_name=str(canyon.get("nomComplet") or canyon.get("nom") or canyon["id"]),
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
                search_radius_cells=int(source.get("searchRadiusCells", 2)),
                search_radius_m=source.get("searchRadiusM"),
                candidate_strategy=str(source.get("candidateStrategy", "max_upa")),
                channel_min_upa_km2=source.get("channelMinUpaKm2"),
            )
            results.append(
                {
                    "pointType": point.get("type"),
                    "label": point.get("label"),
                    "latitude": point.get("latitude"),
                    "longitude": point.get("longitude"),
                    "evaluation": asdict(evaluated),
                }
            )
        return results
    finally:
        upa_raster.close()
        if flowdir_raster is not None:
            flowdir_raster.close()
        if elevation_raster is not None:
            elevation_raster.close()


def suggested_candidate(points: list[dict[str, Any]]) -> dict[str, Any] | None:
    valid = [point for point in points if point["evaluation"]["snapped_upa_km2"] is not None]
    if not valid:
        return None
    return min(
        valid,
        key=lambda point: (
            POINT_TYPE_PRIORITY.get(str(point["pointType"]), 99),
            point["evaluation"]["snap_distance_m"] if point["evaluation"]["snap_distance_m"] is not None else float("inf"),
            -(point["evaluation"]["snapped_upa_km2"] or 0.0),
        ),
    )


def aggregate_results(output_dir: Path) -> None:
    canyon_files = sorted((output_dir / "canyons").glob("*.json")) if (output_dir / "canyons").exists() else []
    canyon_results = [load_json(path) for path in canyon_files]
    write_json(output_dir / "all_canyon_point_catchments.json", canyon_results)

    import_rows = []
    for item in canyon_results:
        candidate = item.get("suggestedCandidate")
        if not candidate:
            continue
        import_rows.append(
            {
                "canyonId": item["canyonId"],
                "canyonName": item["canyonName"],
                "sourceName": item["sourceName"],
                "pointType": candidate["pointType"],
                "label": candidate.get("label"),
                "latitude": candidate.get("latitude"),
                "longitude": candidate.get("longitude"),
                "upstreamCatchmentAreaKm2": candidate["evaluation"]["snapped_upa_km2"],
                "snapDistanceM": candidate["evaluation"]["snap_distance_m"],
                "rawToSnappedUpaRatio": candidate["evaluation"]["raw_to_snapped_upa_ratio"],
            }
        )

    write_json(output_dir / "import_ready_catchments.json", import_rows)
    write_json(
        output_dir / "summary.json",
        {
            "canyonsWithResults": len(canyon_results),
            "importReadyRows": len(import_rows),
        },
    )


def process_single_canyon(
    *,
    canyon_id: int,
    canyon: dict[str, Any],
    points: list[dict[str, Any]],
    sources: list[dict[str, Any]],
    output_dir: Path,
    gdal_translate: str,
    keep_work: bool,
) -> str:
    canyon_file = output_dir / "canyons" / f"{canyon_id}.json"

    source = resolve_source_for_canyon(
        canyon=canyon,
        points=points,
        sources=sources,
        output_dir=output_dir,
        gdal_translate=gdal_translate,
    )
    if source is None:
        write_json(
            canyon_file,
            {
                "canyonId": canyon_id,
                "canyonName": canyon.get("nomComplet") or canyon.get("nom"),
                "status": "no_matching_source",
            },
        )
        return "no_matching_source"

    if source["mode"] == "derive_local_hydrology":
        raster_paths = ensure_local_hydrology(
            source=source,
            canyon_id=canyon_id,
            output_dir=output_dir,
            points=points,
            gdal_translate=gdal_translate,
        )
    elif source["mode"] == "precomputed_hydrology":
        raster_paths = {
            "upa": normalized_path(source["upaRaster"]).resolve(),
            "flowdir": normalized_path(source["flowdirRaster"]).resolve() if source.get("flowdirRaster") else None,
            "elevation": normalized_path(source["elevationRaster"]).resolve() if source.get("elevationRaster") else None,
        }
    else:
        raise SystemExit(f"Unsupported source mode: {source['mode']}")

    point_results = evaluate_points_for_canyon(
        canyon=canyon,
        points=points,
        source=source,
        raster_paths=raster_paths,
    )
    result = {
        "canyonId": canyon_id,
        "canyonName": canyon.get("nomComplet") or canyon.get("nom"),
        "country": canyon.get("pays"),
        "department": canyon.get("departement"),
        "sourceName": source["name"],
        "sourceMode": source["mode"],
        "points": point_results,
        "suggestedCandidate": suggested_candidate(point_results),
    }
    write_json(canyon_file, result)
    if not keep_work:
        shutil.rmtree(output_dir / "work" / str(canyon_id), ignore_errors=True)
    return "ok"


def main() -> int:
    args = parse_args()
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    gdal_translate = resolve_executable(args.gdal_translate, extra_candidates=[default_gdal_translate()])

    config = load_json(args.source_config)
    sources = config["sources"]
    if args.france_only:
        sources = [
            source
            for source in sources
            if source.get("match", {}).get("pays") == "France"
        ]
    canyons = load_canyons(args.canyons_json)
    point_types = set(args.point_type)
    geo_points = load_json(args.geo_points_json)
    points_by_canyon = all_canyon_points(geo_points, point_types)

    canyon_ids = sorted(points_by_canyon)
    countries = set(args.country or [])
    if args.france_only:
        countries.add("France")
    if countries:
        canyon_ids = [canyon_id for canyon_id in canyon_ids if (canyons.get(canyon_id, {}).get("pays") in countries)]
    if args.only_canyon_id:
        allowed = set(args.only_canyon_id)
        canyon_ids = [canyon_id for canyon_id in canyon_ids if canyon_id in allowed]
    canyon_ids = sorted(
        canyon_ids,
        key=lambda canyon_id: planned_source_sort_key(
            canyons[canyon_id],
            points_by_canyon.get(canyon_id, []),
            sources,
        ),
    )
    if args.max_canyons is not None:
        canyon_ids = canyon_ids[: args.max_canyons]

    if args.france_only and args.prepare_france_ign_first:
        prepare_all_france_ign_sources(canyon_ids, canyons, output_dir)

    processed = 0
    try:
        pending = []
        for canyon_id in canyon_ids:
            canyon_file = output_dir / "canyons" / f"{canyon_id}.json"
            if args.skip_existing and canyon_file.exists():
                continue
            canyon = canyons.get(canyon_id)
            if canyon is None:
                continue
            points = points_by_canyon.get(canyon_id, [])
            if not points:
                continue
            pending.append((canyon_id, canyon, points))

        if args.jobs <= 1:
            for canyon_id, canyon, points in pending:
                process_single_canyon(
                    canyon_id=canyon_id,
                    canyon=canyon,
                    points=points,
                    sources=sources,
                    output_dir=output_dir,
                    gdal_translate=gdal_translate,
                    keep_work=args.keep_work,
                )
                aggregate_results(output_dir)
                processed += 1
        else:
            with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as executor:
                future_to_canyon = {
                    executor.submit(
                        process_single_canyon,
                        canyon_id=canyon_id,
                        canyon=canyon,
                        points=points,
                        sources=sources,
                        output_dir=output_dir,
                        gdal_translate=gdal_translate,
                        keep_work=args.keep_work,
                    ): canyon_id
                    for canyon_id, canyon, points in pending
                }
                for future in concurrent.futures.as_completed(future_to_canyon):
                    future.result()
                    aggregate_results(output_dir)
                    processed += 1
    except KeyboardInterrupt:
        print("Interrupted cleanly. Resume with the same command to continue.")
    finally:
        aggregate_results(output_dir)

    print(json.dumps({"processedThisRun": processed}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
