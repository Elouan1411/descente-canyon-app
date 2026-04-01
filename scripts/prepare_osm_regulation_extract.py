from __future__ import annotations

import argparse
import subprocess
from pathlib import Path

from cli_tools import default_ogr2ogr, resolve_executable


LAYER_QUERIES = [
    (
        "lines",
        "SELECT osm_id, name, waterway, water, landuse, man_made, power, other_tags, geometry FROM lines "
        "WHERE waterway IN ('dam','weir','canal','pressurised','sluice_gate') "
        "OR man_made='pipeline' OR power='plant'",
    ),
    (
        "multipolygons",
        "SELECT osm_id, name, waterway, water, landuse, man_made, power, other_tags, geometry FROM multipolygons "
        "WHERE water='reservoir' OR landuse='reservoir' OR power='plant'",
    ),
    (
        "points",
        "SELECT osm_id, name, waterway, water, landuse, man_made, power, other_tags, geometry FROM points "
        "WHERE waterway IN ('dam','weir','sluice_gate') OR power='plant'",
    ),
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Extract OSM regulation features from a local .osm.pbf into a GeoPackage.")
    parser.add_argument("--input-pbf", type=Path, required=True)
    parser.add_argument("--output-gpkg", type=Path, required=True)
    parser.add_argument("--ogr2ogr", default=default_ogr2ogr())
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    ogr2ogr = resolve_executable(args.ogr2ogr, extra_candidates=[default_ogr2ogr()])
    output_gpkg = args.output_gpkg.resolve()
    output_gpkg.parent.mkdir(parents=True, exist_ok=True)
    output_gpkg.unlink(missing_ok=True)

    first = True
    for layer_name, sql in LAYER_QUERIES:
        command = [
            ogr2ogr,
            "-f",
            "GPKG",
            str(output_gpkg),
            str(args.input_pbf.resolve()),
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
        subprocess.run(command, check=True)
        first = False

    print(output_gpkg)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
