from __future__ import annotations

import argparse
import json
import unicodedata
from collections import Counter
from pathlib import Path
from typing import Any


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def normalize_text(value: str | None) -> str:
    if not value:
        return ""
    normalized = unicodedata.normalize("NFKD", value)
    normalized = "".join(char for char in normalized if not unicodedata.combining(char))
    return " ".join(normalized.lower().split())


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Prepare les URLs IGN prioritaires pour les canyons francais.")
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
        "--bdalti-catalog",
        type=Path,
        default=Path("build/watersheds/ign-catalog/bdalti_catalog.json"),
    )
    parser.add_argument(
        "--rgealti-1m-catalog",
        type=Path,
        default=Path("build/watersheds/ign-catalog/rgealti_1m_catalog.json"),
    )
    parser.add_argument(
        "--rgealti-5m-catalog",
        type=Path,
        default=Path("build/watersheds/ign-catalog/rgealti_5m_catalog.json"),
    )
    parser.add_argument(
        "--top",
        type=int,
        default=20,
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("build/watersheds/ign-plan"),
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    canyons = {int(item["id"]): item for item in load_json(args.canyons_json)}
    points = load_json(args.geo_points_json)
    bdalti_catalog = load_json(args.bdalti_catalog)
    rgealti_1m_catalog = load_json(args.rgealti_1m_catalog)
    rgealti_5m_catalog = load_json(args.rgealti_5m_catalog)

    french_departments = Counter()
    for point in points:
        if point.get("type") != "ENTREE":
            continue
        canyon = canyons.get(int(point["canyonId"]))
        if canyon is None or canyon.get("pays") != "France":
            continue
        french_departments[canyon.get("departement") or "UNKNOWN"] += 1

    bdalti_by_department = {
        normalize_text(item["departmentName"]): item for item in bdalti_catalog
    }
    rgealti_1m_by_department = {
        normalize_text(item["departmentName"]): item for item in rgealti_1m_catalog
    }
    rgealti_5m_by_department = {
        normalize_text(item["departmentName"]): item for item in rgealti_5m_catalog
    }

    manifest = []
    missing = []
    for department, count in french_departments.most_common():
        normalized = normalize_text(department)
        bdalti = bdalti_by_department.get(normalized)
        rgealti_1m = rgealti_1m_by_department.get(normalized)
        rgealti_5m = rgealti_5m_by_department.get(normalized)
        payload = {
            "department": department,
            "entryPoints": count,
            "bdAlti": bdalti,
            "rgeAlti5m": rgealti_5m,
            "rgeAlti1m": rgealti_1m,
        }
        manifest.append(payload)
        if bdalti is None or rgealti_5m is None:
            missing.append(payload)

    summary = {
        "departmentCount": len(manifest),
        "missingCatalogEntries": len(missing),
        "topDepartments": manifest[: args.top],
    }

    output_dir = args.output_dir
    write_json(output_dir / "ign_download_manifest.json", manifest)
    write_json(output_dir / "summary.json", summary)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
