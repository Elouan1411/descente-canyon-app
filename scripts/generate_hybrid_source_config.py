from __future__ import annotations

import argparse
import json
import unicodedata
from pathlib import Path
from typing import Any


EUROPEAN_COUNTRIES = [
    "Albanie",
    "Allemagne",
    "Allemagne , Autriche",
    "Andorre",
    "Autriche",
    "Belgique",
    "Bosnie-Herzégovine",
    "Bulgarie",
    "Chypre",
    "Croatie",
    "Danemark",
    "Espagne",
    "Estonie",
    "Finlande",
    "France",
    "France , Espagne",
    "France , Suisse",
    "Grèce",
    "Hongrie",
    "Irlande",
    "Islande",
    "Italie",
    "Kosovo",
    "Lettonie",
    "Liechtenstein",
    "Lituanie",
    "Luxembourg",
    "Macédoine du Nord",
    "Malte",
    "Monténégro",
    "Norvège",
    "Pays-Bas",
    "Pologne",
    "Portugal",
    "République tchèque",
    "Roumanie",
    "Royaume-Uni",
    "Serbie",
    "Slovaquie",
    "Slovénie",
    "Suède",
    "Suisse",
    "Turquie",
    "Ukraine"
]


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def normalize_slug(value: str) -> str:
    normalized = unicodedata.normalize("NFKD", value)
    normalized = "".join(char for char in normalized if not unicodedata.combining(char))
    normalized = normalized.lower()
    cleaned = []
    for char in normalized:
        cleaned.append(char if char.isalnum() else "-")
    result = "".join(cleaned)
    while "--" in result:
        result = result.replace("--", "-")
    return result.strip("-")


def france_department_srs(department: str) -> str:
    if department == "La Réunion":
        return "EPSG:2975"
    if department in {"Martinique", "Guadeloupe"}:
        return "EPSG:32620"
    if department == "Mayotte":
        return "EPSG:32738"
    return "+proj=lcc +lat_1=49 +lat_2=44 +lat_0=46.5 +lon_0=3 +x_0=700000 +y_0=6600000 +ellps=GRS80 +towgs84=0,0,0,0,0,0,0 +units=m +no_defs"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generates a hybrid source config: IGN France, Copernicus Europe, MERIT fallback."
    )
    parser.add_argument(
        "--ign-manifest",
        type=Path,
        default=Path("build/watersheds/ign-plan/ign_download_manifest.json"),
    )
    parser.add_argument(
        "--ign-vrt-root",
        type=Path,
        default=Path("build/watersheds/ign-data/vrt"),
    )
    parser.add_argument(
        "--copernicus-upa",
        type=Path,
        default=Path("build/watersheds/copernicus-europe-hydrology/copernicus_upstream_area_km2.tif"),
    )
    parser.add_argument(
        "--copernicus-flowdir",
        type=Path,
        default=Path("build/watersheds/copernicus-europe-hydrology/copernicus_d8_pointer_esri.tif"),
    )
    parser.add_argument(
        "--copernicus-elevation",
        type=Path,
        default=Path("build/watersheds/copernicus-europe-hydrology/copernicus_breached_dem.tif"),
    )
    parser.add_argument(
        "--merit-upa",
        type=Path,
        default=Path("build/watersheds/merit/vrt/merit_upa.vrt"),
    )
    parser.add_argument(
        "--merit-flowdir",
        type=Path,
        default=Path("build/watersheds/merit/vrt/merit_dir.vrt"),
    )
    parser.add_argument(
        "--merit-elevation",
        type=Path,
        default=Path("build/watersheds/merit/vrt/merit_elv.vrt"),
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("scripts/watersheds/source_config.hybrid.json"),
    )
    parser.add_argument("--swiss-manifest", type=Path, default=Path("scripts/watersheds/switzerland_national_dem_manifest.example.json"))
    parser.add_argument("--spain-manifest", type=Path, default=Path("scripts/watersheds/spain_national_dem_manifest.example.json"))
    parser.add_argument("--austria-manifest", type=Path, default=Path("scripts/watersheds/austria_national_dem_manifest.example.json"))
    parser.add_argument("--slovenia-manifest", type=Path, default=Path("scripts/watersheds/slovenia_national_dem_manifest.example.json"))
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    manifest = load_json(args.ign_manifest)

    sources: list[dict[str, Any]] = []

    def add_national_source(*, name: str, country: str, manifest_path: Path, output_dir: str, unit_fields: list[str], srs: str | None = None) -> None:
        source: dict[str, Any] = {
            "name": name,
            "mode": "derive_local_hydrology",
            "dem": f"{output_dir}/vrt/_all_downloaded.vrt",
            "bufferKm": 20.0,
            "candidateStrategy": "nearest_channel",
            "searchRadiusM": 120.0,
            "channelMinUpaKm2": 0.05,
            "autoPrepare": {
                "provider": "national-dem",
                "manifest": str(manifest_path),
                "outputDir": output_dir,
                "unitFields": unit_fields,
            },
            "match": {
                "pays": country,
            },
        }
        if srs:
            source["srs"] = srs
        sources.append(source)

    for item in manifest:
        department = item["department"]
        dept_code = None
        if item.get("rgeAlti5m"):
            dept_code = item["rgeAlti5m"].get("departmentCode")
        elif item.get("bdAlti"):
            dept_code = item["bdAlti"].get("departmentCode")
        if not dept_code:
            continue

        slug = f"{dept_code.lower()}-{normalize_slug(department)}"
        rge_vrt = args.ign_vrt_root / "rgealti5m" / "_all_downloaded.vrt"
        bd_vrt = args.ign_vrt_root / "bdalti" / "_all_downloaded.vrt"
        rge_department_vrt = args.ign_vrt_root / "rgealti5m" / f"{slug}.vrt"
        bd_department_vrt = args.ign_vrt_root / "bdalti" / f"{slug}.vrt"

        sources.append(
            {
                "name": f"ign-rgealti5m-{slug}",
                "mode": "derive_local_hydrology",
                "dem": str(rge_vrt),
                "availabilityPath": str(rge_department_vrt),
                "srs": france_department_srs(department),
                "bufferKm": 20.0,
                "searchRadiusM": 120.0,
                "channelMinUpaKm2": 0.05,
                "candidateStrategy": "nearest_channel",
                "autoPrepare": {
                    "provider": "ign",
                    "dataset": "rgealti5m",
                    "manifest": "build/watersheds/ign-plan/ign_download_manifest.json",
                    "outputDir": "build/watersheds/ign-data"
                },
                "match": {
                    "pays": "France",
                    "departement": department
                }
            }
        )
        sources.append(
            {
                "name": f"ign-bdalti-{slug}",
                "mode": "derive_local_hydrology",
                "dem": str(bd_vrt),
                "availabilityPath": str(bd_department_vrt),
                "srs": france_department_srs(department),
                "bufferKm": 20.0,
                "searchRadiusM": 120.0,
                "channelMinUpaKm2": 0.05,
                "candidateStrategy": "nearest_channel",
                "autoPrepare": {
                    "provider": "ign",
                    "dataset": "bdalti",
                    "manifest": "build/watersheds/ign-plan/ign_download_manifest.json",
                    "outputDir": "build/watersheds/ign-data"
                },
                "match": {
                    "pays": "France",
                    "departement": department
                }
            }
        )

    sources.append(
        {
            "name": "slovenia-dmv5-direct",
            "mode": "derive_local_hydrology",
            "dem": "build/watersheds/slovenia-national-dem/vrt/_all_downloaded.vrt",
            "srs": "EPSG:3794",
            "bufferKm": 20.0,
            "processingResolutionM": 10.0,
            "candidateStrategy": "nearest_channel",
            "searchRadiusM": 120.0,
            "channelMinUpaKm2": 0.05,
            "autoPrepare": {
                "provider": "slovenia-jgp",
                "outputDir": "build/watersheds/slovenia-national-dem"
            },
            "match": {
                "pays": "Slovénie"
            }
        }
    )

    sources.append(
        {
            "name": "switzerland-swissalti3d",
            "mode": "derive_local_hydrology",
            "dem": "build/watersheds/switzerland-national-dem/vrt/_all_downloaded.vrt",
            "srs": "EPSG:2056",
            "bufferKm": 10.0,
            "processingResolutionM": 5.0,
            "candidateStrategy": "nearest_channel",
            "searchRadiusM": 120.0,
            "channelMinUpaKm2": 0.05,
            "autoPrepare": {
                "provider": "switzerland-stac",
                "outputDir": "build/watersheds/switzerland-national-dem",
                "bufferKm": 10.0,
            },
            "match": {
                "pays": "Suisse"
            }
        }
    )
    sources.append(
        {
            "name": "spain-ign-mdt25",
            "mode": "derive_local_hydrology",
            "dem": "build/watersheds/spain-national-dem/raw/spain_4258_25m.tif",
            "srs": None,
            "bufferKm": 10.0,
            "candidateStrategy": "nearest_channel",
            "searchRadiusM": 120.0,
            "channelMinUpaKm2": 0.05,
            "autoPrepare": {
                "provider": "spain-wcs",
                "outputDir": "build/watersheds/spain-national-dem",
                "bufferKm": 10.0,
            },
            "match": {
                "pays": "Espagne"
            }
        }
    )
    sources.append(
        {
            "name": "austria-bev-als-dtm",
            "mode": "derive_local_hydrology",
            "dem": "build/watersheds/austria-national-dem/vrt/_all_downloaded.vrt",
            "srs": "EPSG:3035",
            "bufferKm": 20.0,
            "processingResolutionM": 5.0,
            "candidateStrategy": "nearest_channel",
            "searchRadiusM": 120.0,
            "channelMinUpaKm2": 0.05,
            "autoPrepare": {
                "provider": "austria-als",
                "outputDir": "build/watersheds/austria-national-dem",
                "bufferKm": 20.0,
            },
            "match": {
                "pays": "Autriche"
            }
        }
    )
    sources.append(
        {
            "name": "italy-liguria-dem",
            "mode": "derive_local_hydrology",
            "dem": "build/watersheds/italy-liguria-dem/raw/liguria_5m.tif",
            "bufferKm": 10.0,
            "processingResolutionM": 5.0,
            "candidateStrategy": "nearest_channel",
            "searchRadiusM": 120.0,
            "channelMinUpaKm2": 0.05,
            "autoPrepare": {
                "provider": "liguria-wcs",
                "outputDir": "build/watersheds/italy-liguria-dem",
                "bufferKm": 10.0,
            },
            "match": {
                "pays": "Italie",
                "region": "Ligurie"
            }
        }
    )

    sources.append(
        {
            "name": "copernicus-europe",
            "mode": "derive_local_hydrology",
            "dem": "build/watersheds/copernicus-data/vrt/copernicus_glo30.vrt",
            "srs": "EPSG:4326",
            "bufferKm": 20.0,
            "candidateStrategy": "nearest_channel",
            "searchRadiusM": 120.0,
            "channelMinUpaKm2": 0.05,
            "autoPrepare": {
                "provider": "copernicus",
                "manifest": "scripts/watersheds/copernicus_url_manifest.example.json",
                "outputDir": "build/watersheds/copernicus-data"
            },
            "match": {
                "pays": EUROPEAN_COUNTRIES
            }
        }
    )
    sources.append(
        {
            "name": "merit-fallback",
            "mode": "precomputed_hydrology",
            "upaRaster": str(args.merit_upa),
            "flowdirRaster": str(args.merit_flowdir),
            "elevationRaster": str(args.merit_elevation),
            "searchRadiusCells": 2,
            "candidateStrategy": "max_upa",
                "autoPrepare": {
                    "provider": "merit",
                    "manifest": "scripts/watersheds/merit_url_manifest.json",
                    "outputDir": "build/watersheds/merit"
                },
            "match": {
                "default": True
            }
        }
    )

    config = {"sources": sources}
    write_json(args.output, config)
    print(json.dumps({"sourceCount": len(sources), "output": str(args.output)}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
