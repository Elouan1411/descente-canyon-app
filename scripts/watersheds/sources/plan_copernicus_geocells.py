from __future__ import annotations

import argparse
import json
import math
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def cell_name(latitude: float, longitude: float) -> str:
    lat_prefix = "N" if latitude >= 0 else "S"
    lon_prefix = "E" if longitude >= 0 else "W"
    lat_degree = abs(math.floor(latitude))
    lon_degree = abs(math.floor(longitude))
    return f"{lat_prefix}{lat_degree:02d}_{lon_prefix}{lon_degree:03d}"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Prepare la liste des geocells Copernicus DEM GLO-30.")
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
        "--exclude-country",
        action="append",
        default=["France"],
        help="Pays exclus du plan Copernicus, par defaut France",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("build/watersheds/copernicus-plan"),
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    canyons = {int(item["id"]): item for item in load_json(args.canyons_json)}
    points = load_json(args.geo_points_json)
    excluded = set(args.exclude_country)

    cells = Counter()
    countries = Counter()
    cell_examples: dict[str, list[dict[str, Any]]] = defaultdict(list)

    for point in points:
        if point.get("type") != "ENTREE":
            continue
        canyon = canyons.get(int(point["canyonId"]))
        if canyon is None:
            continue
        country = canyon.get("pays")
        if country in excluded:
            continue

        name = cell_name(float(point["latitude"]), float(point["longitude"]))
        cells[name] += 1
        countries[country or "UNKNOWN"] += 1
        if len(cell_examples[name]) < 5:
            cell_examples[name].append(
                {
                    "canyonId": canyon["id"],
                    "canyonName": canyon.get("nomComplet") or canyon.get("nom"),
                    "country": country,
                    "latitude": point["latitude"],
                    "longitude": point["longitude"],
                }
            )

    geocells = [
        {
            "cell": name,
            "entryPoints": count,
            "examples": cell_examples[name],
        }
        for name, count in cells.most_common()
    ]

    summary = {
        "geocellCount": len(geocells),
        "countryCounts": dict(countries.most_common()),
        "topGeocells": geocells[:50],
    }

    output_dir = args.output_dir
    write_json(output_dir / "copernicus_geocells.json", geocells)
    write_json(output_dir / "summary.json", summary)
    print(json.dumps(summary, ensure_ascii=True, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
