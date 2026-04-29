from __future__ import annotations

import argparse
import json
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from debit_pipeline_lib import write_json, write_jsonl


DEFAULT_DESCENTE_OBSERVATIONS_PATH = "build/debit-pipeline/observations/valid_debit_observations.jsonl"
DEFAULT_OPENCANYON_OBSERVATIONS_PATH = "build/opencanyon/prepared-debit-observations/opencanyon_valid_observations.jsonl"
DEFAULT_OUTPUT_DIR = "build/debit-pipeline/observations-merged"


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            stripped = line.strip()
            if stripped:
                rows.append(json.loads(stripped))
    return rows


def duplicate_key(row: dict[str, Any]) -> tuple[Any, ...]:
    return (
        row.get("source") or "descente-canyon",
        row.get("canyonId"),
        row.get("date"),
        row.get("primaryAuthor"),
        row.get("niveau"),
        row.get("remarkId"),
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Merge Descente-Canyon and prepared OpenCanyon debit observations")
    parser.add_argument("--descente-observations-path", default=DEFAULT_DESCENTE_OBSERVATIONS_PATH)
    parser.add_argument("--opencanyon-observations-path", default=DEFAULT_OPENCANYON_OBSERVATIONS_PATH)
    parser.add_argument("--output-dir", default=DEFAULT_OUTPUT_DIR)
    args = parser.parse_args()

    descente_rows = read_jsonl(Path(args.descente_observations_path))
    for row in descente_rows:
        row.setdefault("source", "descente-canyon")
    opencanyon_rows = read_jsonl(Path(args.opencanyon_observations_path)) if Path(args.opencanyon_observations_path).exists() else []

    merged: list[dict[str, Any]] = []
    skipped_duplicates: list[dict[str, Any]] = []
    seen: set[tuple[Any, ...]] = set()
    for row in [*descente_rows, *opencanyon_rows]:
        key = duplicate_key(row)
        if key in seen:
            skipped_duplicates.append(row)
            continue
        seen.add(key)
        merged.append(row)
    merged.sort(key=lambda row: (row.get("date") or "", int(row.get("canyonId") or 0), row.get("source") or ""))

    output_dir = Path(args.output_dir)
    write_jsonl(output_dir / "valid_debit_observations.jsonl", merged)
    write_jsonl(output_dir / "skipped_duplicate_observations.jsonl", skipped_duplicates)
    write_json(
        output_dir / "metadata.json",
        {
            "schemaVersion": 1,
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "descenteObservationCount": len(descente_rows),
            "opencanyonObservationCount": len(opencanyon_rows),
            "mergedObservationCount": len(merged),
            "skippedDuplicateCount": len(skipped_duplicates),
            "sourceCounts": dict(sorted(Counter(row.get("source") or "descente-canyon" for row in merged).items())),
            "licenseWarning": "OpenCanyon data is CC BY-NC-SA 4.0; verify compatibility before shipping merged derived datasets.",
            "files": {
                "valid": "valid_debit_observations.jsonl",
                "skippedDuplicates": "skipped_duplicate_observations.jsonl",
            },
        },
    )


if __name__ == "__main__":
    main()
