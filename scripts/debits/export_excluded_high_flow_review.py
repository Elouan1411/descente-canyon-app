from __future__ import annotations

import argparse
import json
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from pipeline_lib import write_json, write_jsonl


DEFAULT_INPUT_DIR = "build/debit-pipeline/post-cutoff-descente-refresh"
DEFAULT_OUTPUT_DIR = "build/debit-pipeline/excluded-high-flow-review"
HIGH_FLOW_LEVELS = {"GROS", "TRES_GROS", "CRUE"}
LEVEL_SORT = {"CRUE": 0, "TRES_GROS": 1, "GROS": 2}
QUALITY_SORT = {"invalid": 0, "uncertain": 1}


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    if not path.exists():
        return rows
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            stripped = line.strip()
            if stripped:
                rows.append(json.loads(stripped))
    return rows


def review_row(row: dict[str, Any]) -> dict[str, Any]:
    return {
        "niveau": row.get("niveau"),
        "qualityLabel": row.get("qualityLabel"),
        "canyonName": row.get("canyonName"),
        "canyonId": row.get("canyonId"),
        "date": row.get("date"),
        "comment": row.get("comment") or row.get("commentText"),
        "qualityReasons": row.get("qualityReasons") or [],
        "observationId": row.get("observationId"),
        "remarkId": row.get("remarkId"),
        "primaryAuthor": row.get("primaryAuthor"),
        "authors": row.get("authors") or [],
        "isDescended": row.get("isDescended"),
        "sourceUrl": row.get("sourceUrl"),
        "manualOverride": bool(row.get("manualOverride")),
    }


def sort_key(row: dict[str, Any]) -> tuple[Any, ...]:
    return (
        QUALITY_SORT.get(str(row.get("qualityLabel")), 99),
        LEVEL_SORT.get(str(row.get("niveau")), 99),
        str(row.get("date") or ""),
        int(row.get("canyonId") or 0),
        str(row.get("observationId") or ""),
    )


def write_review_text(path: Path, rows: list[dict[str, Any]]) -> None:
    lines = [
        "# Hauts debits exclus par le pipeline brut",
        "",
        "Convention de revue: ajoute un espace devant le debit pour marquer une observation a reintegrer.",
        "Sans espace, l'observation reste exclue.",
        "",
    ]
    current_section: tuple[str, str] | None = None
    for row in rows:
        section = (str(row.get("qualityLabel") or "unknown"), str(row.get("niveau") or "UNKNOWN"))
        if section != current_section:
            current_section = section
            lines.append(f"## {section[0]} / {section[1]}")
            lines.append("")
        comment = str(row.get("comment") or "").strip().replace("\r\n", "\n")
        reasons = ", ".join(str(reason) for reason in row.get("qualityReasons") or [])
        authors = ", ".join(str(author) for author in row.get("authors") or []) or str(row.get("primaryAuthor") or "")
        lines.append(
            f"{row.get('niveau')} | {row.get('qualityLabel')} | {row.get('canyonName') or 'Canyon inconnu'} | {row.get('date')} | canyonId={row.get('canyonId')} | observationId={row.get('observationId')}"
        )
        lines.append(f"Raisons: {reasons}")
        lines.append(f"Auteur(s): {authors} | parcouru={row.get('isDescended')} | remarkId={row.get('remarkId')}")
        lines.append(f"Commentaire: {comment}")
        lines.append("")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description="Export high-flow observations excluded by the raw debit quality pipeline for manual review")
    parser.add_argument("--input-dir", default=DEFAULT_INPUT_DIR)
    parser.add_argument("--output-dir", default=DEFAULT_OUTPUT_DIR)
    args = parser.parse_args()

    input_dir = Path(args.input_dir)
    output_dir = Path(args.output_dir)
    invalid_rows = read_jsonl(input_dir / "invalid_debit_observations.jsonl")
    uncertain_rows = read_jsonl(input_dir / "uncertain_debit_observations.jsonl")
    review_rows = [
        review_row(row)
        for row in [*invalid_rows, *uncertain_rows]
        if row.get("niveau") in HIGH_FLOW_LEVELS
    ]
    review_rows.sort(key=sort_key)

    invalid_review_rows = [row for row in review_rows if row.get("qualityLabel") == "invalid"]
    uncertain_review_rows = [row for row in review_rows if row.get("qualityLabel") == "uncertain"]

    write_jsonl(output_dir / "excluded_high_flow_review.jsonl", review_rows)
    write_jsonl(output_dir / "invalid_high_flow_review.jsonl", invalid_review_rows)
    write_jsonl(output_dir / "uncertain_high_flow_review.jsonl", uncertain_review_rows)
    write_review_text(output_dir / "excluded_high_flow_review.txt", review_rows)
    write_review_text(output_dir / "invalid_high_flow_review.txt", invalid_review_rows)
    write_review_text(output_dir / "uncertain_high_flow_review.txt", uncertain_review_rows)

    write_json(
        output_dir / "metadata.json",
        {
            "schemaVersion": 1,
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "inputDir": str(input_dir),
            "reviewRowCount": len(review_rows),
            "invalidHighFlowCount": len(invalid_review_rows),
            "uncertainHighFlowCount": len(uncertain_review_rows),
            "levelCounts": dict(sorted(Counter(row.get("niveau") for row in review_rows).items())),
            "qualityCounts": dict(sorted(Counter(row.get("qualityLabel") for row in review_rows).items())),
            "reasonCounts": dict(
                Counter(reason for row in review_rows for reason in (row.get("qualityReasons") or [])).most_common()
            ),
            "files": {
                "reviewText": "excluded_high_flow_review.txt",
                "reviewJsonl": "excluded_high_flow_review.jsonl",
                "invalidText": "invalid_high_flow_review.txt",
                "invalidJsonl": "invalid_high_flow_review.jsonl",
                "uncertainText": "uncertain_high_flow_review.txt",
                "uncertainJsonl": "uncertain_high_flow_review.jsonl",
            },
        },
    )
    print(f"Wrote {output_dir / 'excluded_high_flow_review.txt'} ({len(review_rows)} rows)")


if __name__ == "__main__":
    main()
