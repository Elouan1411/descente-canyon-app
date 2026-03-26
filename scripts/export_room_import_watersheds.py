from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def resolve_input_path(value: Path) -> Path:
    if value.is_dir():
        candidate = value / "import_ready_watersheds.json"
        if candidate.exists():
            return candidate
    return value


def normalize_bbox(value: Any) -> list[float] | None:
    if not isinstance(value, list) or len(value) != 4:
        return None
    try:
        return [float(item) for item in value]
    except (TypeError, ValueError):
        return None


def normalize_row(row: dict[str, Any]) -> dict[str, Any] | None:
    canyon_id = row.get("canyonId")
    if canyon_id is None:
        return None
    geometry = row.get("geometry")
    area_km2 = row.get("upstreamCatchmentAreaKm2")
    bbox = normalize_bbox(row.get("bbox"))
    if geometry is None and area_km2 is None and bbox is None:
        return None
    normalized: dict[str, Any] = {"canyonId": int(canyon_id)}
    if area_km2 is not None:
        normalized["upstreamCatchmentAreaKm2"] = float(area_km2)
    if bbox is not None:
        normalized["bbox"] = bbox
    if geometry is not None:
        normalized["geometry"] = geometry
    return normalized


def dedupe_rows(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    by_canyon: dict[int, dict[str, Any]] = {}
    for row in rows:
        canyon_id = int(row["canyonId"])
        existing = by_canyon.get(canyon_id)
        if existing is None:
            by_canyon[canyon_id] = row
            continue
        existing_has_geometry = existing.get("geometry") is not None
        row_has_geometry = row.get("geometry") is not None
        if row_has_geometry and not existing_has_geometry:
            by_canyon[canyon_id] = row
    return [by_canyon[canyon_id] for canyon_id in sorted(by_canyon)]


def update_manifest(manifest_path: Path, watersheds_count: int) -> None:
    manifest = load_json(manifest_path)
    tables = manifest.setdefault("tables", {})
    counts = manifest.setdefault("counts", {})
    versions = manifest.setdefault("versions", {})
    tables["watersheds"] = "watersheds.json"
    counts["watersheds"] = watersheds_count
    versions["watersheds"] = datetime.now(timezone.utc).isoformat()
    write_json(manifest_path, manifest)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Genere offline-data/full/room-import/watersheds.json a partir de watershed results."
    )
    parser.add_argument(
        "--input",
        type=Path,
        default=Path("watershed-results/import_ready_watersheds.json"),
        help="Fichier import_ready_watersheds.json ou dossier le contenant.",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("offline-data/full/room-import/watersheds.json"),
        help="Fichier de sortie pour l'import Room.",
    )
    parser.add_argument(
        "--manifest",
        type=Path,
        default=Path("offline-data/full/room-import/manifest.json"),
        help="Manifest Room a mettre a jour.",
    )
    parser.add_argument(
        "--skip-manifest",
        action="store_true",
        help="N'actualise pas le manifest d'import.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    input_path = resolve_input_path(args.input)
    if not input_path.exists():
        raise FileNotFoundError(f"Fichier introuvable: {input_path}")

    raw_rows = load_json(input_path)
    if not isinstance(raw_rows, list):
        raise ValueError("Le fichier source doit contenir une liste JSON")

    normalized_rows = [
        normalized
        for item in raw_rows
        if isinstance(item, dict)
        for normalized in [normalize_row(item)]
        if normalized is not None
    ]
    output_rows = dedupe_rows(normalized_rows)
    write_json(args.output, output_rows)

    if not args.skip_manifest and args.manifest.exists():
        update_manifest(args.manifest, len(output_rows))

    print(
        json.dumps(
            {
                "input": str(input_path),
                "output": str(args.output),
                "watersheds": len(output_rows),
                "manifestUpdated": (not args.skip_manifest and args.manifest.exists()),
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
