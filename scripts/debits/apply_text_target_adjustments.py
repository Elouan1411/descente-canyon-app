from __future__ import annotations

import argparse
import json
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from pipeline_lib import write_json, write_jsonl


DEFAULT_FEATURES_PATH = "build/debit-pipeline/training-features-through-2026-05-28-reviewed-text-signals/training_features.jsonl"
DEFAULT_OUTPUT_DIR = "build/debit-pipeline/training-features-through-2026-05-28-reviewed-text-abbrev-adjusted"
RANK_TO_LEVEL = ["SEC", "FILET", "CORRECT", "GROS", "TRES_GROS", "CRUE"]
LEVEL_TO_RANK = {level: float(index) for index, level in enumerate(RANK_TO_LEVEL)}


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            stripped = line.strip()
            if stripped:
                rows.append(json.loads(stripped))
    return rows


def level_from_rank(rank: float) -> str:
    bounded = min(max(rank, 0.0), 5.0)
    return RANK_TO_LEVEL[int(round(bounded))]


def should_apply_text_target(row: dict[str, Any], *, min_confidence: float) -> bool:
    if row.get("textTargetSource") != "abbreviation":
        return False
    try:
        confidence = float(row.get("textTargetConfidence"))
    except (TypeError, ValueError):
        return False
    if confidence < min_confidence:
        return False
    signal = str(row.get("textTargetSignal") or "")
    return any(character in signal for character in ("+", "-", "/"))


def adjusted_row(row: dict[str, Any], *, min_confidence: float) -> tuple[dict[str, Any], dict[str, Any] | None]:
    output = dict(row)
    if not should_apply_text_target(row, min_confidence=min_confidence):
        output["textTargetApplied"] = False
        return output, None

    text_rank = float(row["textTargetRank"])
    text_level = level_from_rank(text_rank)
    original_level = str(row.get("niveau"))
    output.update(
        {
            "originalNiveau": original_level,
            "originalNiveauRank": row.get("niveauRank"),
            "niveau": text_level,
            "niveauRank": LEVEL_TO_RANK[text_level],
            "softTargetRank": round(text_rank, 6),
            "softTargetRankSource": "high_confidence_text_abbreviation",
            "textTargetApplied": True,
            "textTargetChangedLevel": text_level != original_level,
        }
    )
    audit = {
        "observationId": row.get("observationId"),
        "canyonId": row.get("canyonId"),
        "canyonName": row.get("canyonName"),
        "date": row.get("date"),
        "originalNiveau": original_level,
        "adjustedNiveau": text_level,
        "textTargetRank": round(text_rank, 6),
        "textTargetSignal": row.get("textTargetSignal"),
        "textTargetMatchedText": row.get("textTargetMatchedText"),
        "textTargetConfidence": row.get("textTargetConfidence"),
        "textSelectedLevelDelta": row.get("textSelectedLevelDelta"),
        "textTargetAgreement": row.get("textTargetAgreement"),
        "changedLevel": text_level != original_level,
        "commentText": row.get("commentText") or row.get("comment"),
    }
    return output, audit


def main() -> None:
    parser = argparse.ArgumentParser(description="Apply conservative text-target label adjustments from high-confidence debit abbreviations")
    parser.add_argument("--features-path", default=DEFAULT_FEATURES_PATH)
    parser.add_argument("--output-dir", default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--min-confidence", type=float, default=0.9)
    args = parser.parse_args()

    rows = read_jsonl(Path(args.features_path))
    output_rows: list[dict[str, Any]] = []
    audit_rows: list[dict[str, Any]] = []
    for row in rows:
        output, audit = adjusted_row(row, min_confidence=args.min_confidence)
        output_rows.append(output)
        if audit is not None:
            audit_rows.append(audit)

    output_dir = Path(args.output_dir)
    changed_rows = [row for row in audit_rows if row["changedLevel"]]
    write_jsonl(output_dir / "training_features.jsonl", output_rows)
    write_jsonl(output_dir / "text_target_adjusted_rows.jsonl", audit_rows)
    write_jsonl(output_dir / "text_target_changed_level_rows.jsonl", changed_rows)
    write_json(
        output_dir / "metadata.json",
        {
            "schemaVersion": 1,
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "sourceFeaturesPath": args.features_path,
            "rowCount": len(output_rows),
            "minConfidence": args.min_confidence,
            "appliedAdjustmentCount": len(audit_rows),
            "changedLevelCount": len(changed_rows),
            "originalLevelCounts": dict(sorted(Counter(row["originalNiveau"] for row in audit_rows).items())),
            "adjustedLevelCounts": dict(sorted(Counter(row["adjustedNiveau"] for row in audit_rows).items())),
            "changedLevelPairs": dict(
                sorted(Counter(f"{row['originalNiveau']}->{row['adjustedNiveau']}" for row in changed_rows).items())
            ),
            "signalCounts": dict(sorted(Counter(str(row["textTargetSignal"]) for row in audit_rows).items())),
            "files": {
                "trainingFeatures": "training_features.jsonl",
                "adjustedRows": "text_target_adjusted_rows.jsonl",
                "changedLevelRows": "text_target_changed_level_rows.jsonl",
            },
        },
    )
    print(f"Wrote {output_dir / 'training_features.jsonl'} with {len(audit_rows)} text-target adjustments")


if __name__ == "__main__":
    main()
