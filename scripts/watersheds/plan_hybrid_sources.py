from __future__ import annotations

import argparse
import json
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Prepare le plan France IGN / Europe Copernicus pour les bassins versants."
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
        default=Path("build/watersheds/hybrid-plan"),
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    canyons = {int(item["id"]): item for item in load_json(args.canyons_json)}
    points = load_json(args.geo_points_json)

    french_departments = Counter()
    europe_countries = Counter()
    french_canyons = set()
    europe_canyons = set()

    for point in points:
        if point.get("type") != "ENTREE":
            continue
        canyon = canyons.get(int(point["canyonId"]))
        if canyon is None:
            continue

        country = canyon.get("pays")
        department = canyon.get("departement")
        if country == "France":
            french_departments[department or "UNKNOWN"] += 1
            french_canyons.add(int(point["canyonId"]))
        else:
            europe_countries[country or "UNKNOWN"] += 1
            europe_canyons.add(int(point["canyonId"]))

    recommendations = {
        "france": {
            "dataset": "IGN RGE ALTI 5m as baseline, BD ALTI 25m as light fallback, RGE ALTI 1m for targeted recalc",
            "why": "Much finer national terrain model than MERIT 90m, better adapted to small French catchments",
            "priorityDepartments": [
                {"department": department, "entryPoints": count}
                for department, count in french_departments.most_common(25)
            ],
        },
        "europe": {
            "dataset": "Copernicus DEM GLO-30",
            "why": "30m global/european coverage with free access for GLO-30",
            "priorityCountries": [
                {"country": country, "entryPoints": count}
                for country, count in europe_countries.most_common(25)
            ],
        },
    }

    summary = {
        "frenchCanyonsWithEntry": len(french_canyons),
        "europeAndRestCanyonsWithEntry": len(europe_canyons),
        "frenchDepartmentCount": len(french_departments),
        "nonFrenchCountryCount": len(europe_countries),
        "recommendations": recommendations,
    }

    output_dir = args.output_dir
    write_json(output_dir / "summary.json", summary)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
