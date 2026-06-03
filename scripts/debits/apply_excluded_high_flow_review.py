from __future__ import annotations

import argparse
import json
import re
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from pipeline_lib import write_json


DEFAULT_REVIEW_TEXT_PATH = "build/debit-pipeline/excluded-high-flow-review/excluded_high_flow_review.txt"
DEFAULT_REVIEW_JSONL_PATH = "build/debit-pipeline/excluded-high-flow-review/excluded_high_flow_review.jsonl"
DEFAULT_OVERRIDES_PATH = "scripts/debits/observation_overrides.json"
LEVEL_PREFIXES = ("GROS |", "TRES_GROS |", "CRUE |")


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            stripped = line.strip()
            if stripped:
                rows.append(json.loads(stripped))
    return rows


def read_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def reviewed_decisions(review_text_path: Path) -> dict[str, str]:
    decisions: dict[str, str] = {}
    for line in review_text_path.read_text(encoding="utf-8").splitlines():
        stripped = line.lstrip(" \t")
        if not stripped.startswith(LEVEL_PREFIXES):
            continue
        match = re.search(r"observationId=([^\s]+)", line)
        if not match:
            continue
        observation_id = match.group(1)
        if line.startswith("\t"):
            decisions[observation_id] = "invalid"
        elif line.startswith(" "):
            decisions[observation_id] = "valid"
    return decisions


def primary_author(row: dict[str, Any]) -> str | None:
    value = row.get("primaryAuthor")
    if value:
        return str(value)
    authors = row.get("authors") or []
    if authors:
        return str(authors[0])
    return None


def override_for_review(row: dict[str, Any], action: str) -> dict[str, Any]:
    original_quality = row.get("qualityLabel")
    if action == "valid":
        reason = f"Manual high-flow exclusion review: reintegrate previously {original_quality} observation"
    else:
        reason = f"Manual high-flow exclusion review: confirmed {original_quality} observation should stay invalid"

    payload: dict[str, Any] = {
        "observationId": row.get("observationId"),
        "canyonId": row.get("canyonId"),
        "date": row.get("date"),
        "niveau": row.get("niveau"),
        "action": action,
        "reason": reason,
    }
    if row.get("remarkId") is not None:
        payload["remarkId"] = row.get("remarkId")
    author = primary_author(row)
    if author:
        payload["author"] = author
    if original_quality:
        payload["originalQualityLabel"] = original_quality
    return payload


def upsert_overrides(
    existing_overrides: list[dict[str, Any]],
    review_overrides: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    by_observation_id = {
        override.get("observationId"): index
        for index, override in enumerate(existing_overrides)
        if override.get("observationId")
    }
    result = [dict(override) for override in existing_overrides]
    for override in review_overrides:
        observation_id = override.get("observationId")
        index = by_observation_id.get(observation_id)
        if index is None:
            by_observation_id[observation_id] = len(result)
            result.append(override)
        else:
            result[index] = override
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description="Apply manual review decisions from excluded high-flow observations to observation_overrides.json")
    parser.add_argument("--review-text-path", default=DEFAULT_REVIEW_TEXT_PATH)
    parser.add_argument("--review-jsonl-path", default=DEFAULT_REVIEW_JSONL_PATH)
    parser.add_argument("--overrides-path", default=DEFAULT_OVERRIDES_PATH)
    parser.add_argument("--summary-path", default="build/debit-pipeline/excluded-high-flow-review/applied_review_summary.json")
    args = parser.parse_args()

    review_text_path = Path(args.review_text_path)
    review_rows_by_id = {str(row.get("observationId")): row for row in read_jsonl(Path(args.review_jsonl_path))}
    decisions = reviewed_decisions(review_text_path)
    unknown_ids = sorted(observation_id for observation_id in decisions if observation_id not in review_rows_by_id)
    if unknown_ids:
        raise SystemExit(f"Reviewed observation ids not found in JSONL: {unknown_ids[:10]}")

    review_overrides = [
        override_for_review(review_rows_by_id[observation_id], action)
        for observation_id, action in sorted(decisions.items(), key=lambda item: (item[1], item[0]))
    ]
    overrides_path = Path(args.overrides_path)
    existing_overrides = read_json(overrides_path) if overrides_path.exists() else []
    updated_overrides = upsert_overrides(existing_overrides, review_overrides)
    write_json(overrides_path, updated_overrides)

    action_counts = Counter(override["action"] for override in review_overrides)
    original_quality_counts = Counter(str(override.get("originalQualityLabel")) for override in review_overrides)
    summary = {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "reviewTextPath": str(review_text_path),
        "reviewJsonlPath": args.review_jsonl_path,
        "overridesPath": str(overrides_path),
        "reviewDecisionCount": len(decisions),
        "validOverrideCount": action_counts.get("valid", 0),
        "invalidOverrideCount": action_counts.get("invalid", 0),
        "originalQualityCounts": dict(sorted(original_quality_counts.items())),
        "totalOverrideCount": len(updated_overrides),
    }
    write_json(Path(args.summary_path), summary)
    print(json.dumps(summary, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
