from __future__ import annotations

import argparse
import concurrent.futures
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import scrape_descente_canyon_samples as scrape_lib


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def load_access_from_description(canyon_path: Path) -> tuple[int, str | None, str | None, bool]:
    payload = read_json(canyon_path)
    canyon_id = int(payload["canyon"]["id"])
    description_url = payload["source"]["urls"]["description"]
    topo = payload["canyon"].setdefault("topo", {})
    previous_aval = topo.get("accesAval")
    previous_amont = topo.get("accesAmont")

    parsed = scrape_lib.parse_description_page(canyon_id, scrape_lib.fetch_text(description_url))
    topo["accesAval"] = parsed.get("accesAval")
    topo["accesAmont"] = parsed.get("accesAmont")
    changed = topo.get("accesAval") != previous_aval or topo.get("accesAmont") != previous_amont
    if changed:
        scrape_lib.write_json(canyon_path, payload)
    return canyon_id, topo.get("accesAval"), topo.get("accesAmont"), changed


def update_room_import(output_dir: Path, access_by_canyon: dict[int, tuple[str | None, str | None]]) -> None:
    canyons_path = output_dir / "room-import" / "canyons.json"
    rows = read_json(canyons_path)
    for row in rows:
        canyon_id = int(row["id"])
        acces_aval, acces_amont = access_by_canyon[canyon_id]
        row["accesAval"] = acces_aval
        row["accesAmont"] = acces_amont
    scrape_lib.write_json(canyons_path, rows)


def update_optimized_details(output_dir: Path, access_by_canyon: dict[int, tuple[str | None, str | None]]) -> None:
    shards_dir = output_dir / "optimized" / "shards"
    for shard_path in sorted(shards_dir.glob("canyon-details-*.json")):
        rows = read_json(shard_path)
        for row in rows:
            canyon_id = int(row["id"])
            acces_aval, acces_amont = access_by_canyon[canyon_id]
            row.setdefault("topo", {})["accesAval"] = acces_aval
            row.setdefault("topo", {})["accesAmont"] = acces_amont
        scrape_lib.write_json(shard_path, rows)


def bump_generated_at(manifest_path: Path, generated_at: str) -> None:
    manifest = read_json(manifest_path)
    manifest["generatedAt"] = generated_at
    scrape_lib.write_json(manifest_path, manifest)


def main() -> None:
    parser = argparse.ArgumentParser(description="Refresh only embedded access fields from canyon topo pages")
    parser.add_argument("--output-dir", default="offline-data/full")
    parser.add_argument("--workers", type=int, default=4)
    args = parser.parse_args()

    output_dir = Path(args.output_dir)
    canyon_paths = sorted((output_dir / "canyons").glob("*.json"), key=lambda path: int(path.stem))
    access_by_canyon: dict[int, tuple[str | None, str | None]] = {}
    changed_count = 0

    with concurrent.futures.ThreadPoolExecutor(max_workers=max(args.workers, 1)) as executor:
        futures = {executor.submit(load_access_from_description, path): path for path in canyon_paths}
        for index, future in enumerate(concurrent.futures.as_completed(futures), start=1):
            canyon_id, acces_aval, acces_amont, changed = future.result()
            access_by_canyon[canyon_id] = (acces_aval, acces_amont)
            if changed:
                changed_count += 1
            if index % 100 == 0 or index == len(canyon_paths):
                print(f"Progress {index}/{len(canyon_paths)} | changed={changed_count}")

    update_room_import(output_dir, access_by_canyon)
    update_optimized_details(output_dir, access_by_canyon)

    generated_at = datetime.now(timezone.utc).isoformat()
    bump_generated_at(output_dir / "room-import" / "manifest.json", generated_at)
    bump_generated_at(output_dir / "optimized" / "manifest.json", generated_at)

    print(json.dumps({
        "generatedAt": generated_at,
        "canyons": len(access_by_canyon),
        "changed": changed_count,
    }, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
