from __future__ import annotations

import argparse
import os
import subprocess
import time
import unicodedata
import urllib.request
from pathlib import Path
from urllib.error import HTTPError, URLError

from cli_tools import default_ogr2ogr, gdal_env, resolve_executable


LAYER_QUERIES = [
    (
        "lines",
        "SELECT osm_id, name, waterway, barrier, man_made, other_tags, geometry FROM lines "
        "WHERE waterway IN ('dam','weir','canal','pressurised','sluice_gate') "
        "OR man_made='pipeline' OR other_tags LIKE '%\"power\"=>\"plant\"%'",
    ),
    (
        "multipolygons",
        "SELECT osm_id, name, landuse, man_made, natural, other_tags, geometry FROM multipolygons "
        "WHERE landuse='reservoir' OR natural='water' OR other_tags LIKE '%\"power\"=>\"plant\"%'",
    ),
    (
        "points",
        "SELECT osm_id, name, barrier, man_made, other_tags, geometry FROM points "
        "WHERE barrier IN ('dam','weir') OR man_made IN ('water_tower','pipeline') OR other_tags LIKE '%\"power\"=>\"plant\"%'",
    ),
]

GEOFABRIK_COUNTRY_PATHS = {
    "France": "europe/france",
    "Espagne": "europe/spain",
    "Portugal": "europe/portugal",
    "Italie": "europe/italy",
    "Suisse": "europe/switzerland",
    "Autriche": "europe/austria",
    "Slovénie": "europe/slovenia",
    "Allemagne": "europe/germany",
    "Grèce": "europe/greece",
    "Croatie": "europe/croatia",
    "Monténégro": "europe/montenegro",
    "Turquie": "asia/turkey",
    "Andorre": "europe/andorra",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Extract OSM regulation features from a local .osm.pbf into a GeoPackage.")
    parser.add_argument("--input-pbf", type=Path)
    parser.add_argument("--country")
    parser.add_argument("--output-gpkg", type=Path, required=True)
    parser.add_argument("--ogr2ogr", default=default_ogr2ogr())
    return parser.parse_args()


def geofabrik_url_for_country(country: str) -> str:
    path = GEOFABRIK_COUNTRY_PATHS.get(country)
    if not path:
        raise SystemExit(f"No Geofabrik mapping for country: {country}")
    return f"https://download.geofabrik.de/{path}-latest.osm.pbf"


def slugify_country(country: str) -> str:
    normalized = unicodedata.normalize("NFKD", country)
    normalized = "".join(ch for ch in normalized if not unicodedata.combining(ch))
    normalized = normalized.lower().replace(" ", "-").replace(",", "")
    return normalized


def download_file(url: str, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() and destination.stat().st_size > 0:
        return
    temp_path = destination.with_suffix(destination.suffix + f'.{os.getpid()}.part')
    last_error = None
    for attempt in range(1, 6):
        try:
            if destination.exists() and destination.stat().st_size > 0:
                return
            request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(request, timeout=300) as response, open(temp_path, 'wb') as handle:
                while True:
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    handle.write(chunk)
            if destination.exists() and destination.stat().st_size > 0:
                temp_path.unlink(missing_ok=True)
                return
            temp_path.replace(destination)
            return
        except (HTTPError, URLError) as exc:
            last_error = exc
            temp_path.unlink(missing_ok=True)
            if isinstance(exc, HTTPError) and exc.code not in {429, 500, 502, 503, 504}:
                raise
            time.sleep(min(60, 5 * attempt))
    raise SystemExit(f"OSM PBF download failed: {last_error}")


def main() -> int:
    args = parse_args()
    ogr2ogr = resolve_executable(args.ogr2ogr, extra_candidates=[default_ogr2ogr()])
    output_gpkg = args.output_gpkg.resolve()
    output_gpkg.parent.mkdir(parents=True, exist_ok=True)
    output_gpkg.unlink(missing_ok=True)

    input_pbf = args.input_pbf.resolve() if args.input_pbf else None
    if input_pbf is None:
        if not args.country:
            raise SystemExit("Provide either --input-pbf or --country")
        cache_dir = output_gpkg.parent / "pbf"
        cache_dir.mkdir(parents=True, exist_ok=True)
        input_pbf = cache_dir / f"{slugify_country(args.country)}.osm.pbf"
        download_file(geofabrik_url_for_country(args.country), input_pbf)

    env = gdal_env(ogr2ogr)

    first = True
    for layer_name, sql in LAYER_QUERIES:
        command = [
            ogr2ogr,
            "-f",
            "GPKG",
            str(output_gpkg),
            str(input_pbf),
            layer_name,
            "-nln",
            "regulation_features",
            "-dialect",
            "SQLITE",
            "-sql",
            sql,
        ]
        if not first:
            command.insert(1, "-append")
        subprocess.run(command, check=True, env=env)
        first = False

    print(output_gpkg)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
