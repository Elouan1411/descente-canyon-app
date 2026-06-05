from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from pipeline_lib import write_json, write_jsonl


DEFAULT_FEATURES_PATH = "build/debit-pipeline/training-features-reviewed-causal-weight025-temporal-history/training_features.jsonl"
DEFAULT_OUTPUT_DIR = "build/debit-pipeline/stratified-holdout-descente-reviewed"
HIGH_LEVELS = {"GROS", "TRES_GROS", "CRUE"}
LEVELS = ("SEC", "FILET", "CORRECT", "GROS", "TRES_GROS", "CRUE")


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            stripped = line.strip()
            if stripped:
                rows.append(json.loads(stripped))
    return rows


def stable_unit(value: str, seed: int) -> float:
    digest = hashlib.sha1(f"{seed}|{value}".encode("utf-8")).hexdigest()[:12]
    return int(digest, 16) / float(0xFFFFFFFFFFFF)


def group_key(row: dict[str, Any]) -> tuple[int, str]:
    return int(row["canyonId"]), str(row["date"])


def group_year(rows: list[dict[str, Any]]) -> str:
    return str(rows[0].get("date") or "0000")[:4]


def group_level(rows: list[dict[str, Any]]) -> str:
    counts = Counter(str(row.get("niveau")) for row in rows)
    return counts.most_common(1)[0][0]


def build_groups(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    grouped: dict[tuple[int, str], list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        if row.get("canyonId") is None or not row.get("date") or row.get("niveau") not in LEVELS:
            continue
        grouped[group_key(row)].append(row)
    groups: list[dict[str, Any]] = []
    for key, group_rows in grouped.items():
        groups.append(
            {
                "key": f"{key[0]}_{key[1]}",
                "canyonId": key[0],
                "date": key[1],
                "year": group_year(group_rows),
                "level": group_level(group_rows),
                "rows": group_rows,
                "rowCount": len(group_rows),
            }
        )
    return groups


def select_holdout_groups(
    groups: list[dict[str, Any]],
    *,
    seed: int,
    fraction: float,
    min_per_year_level: int,
    max_per_year_level: int,
    max_per_canyon: int,
) -> set[str]:
    by_stratum: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    for group in groups:
        by_stratum[(group["year"], group["level"])].append(group)

    selected: set[str] = set()
    selected_by_canyon: Counter[int] = Counter()
    for stratum, stratum_groups in sorted(by_stratum.items()):
        ordered = sorted(stratum_groups, key=lambda group: stable_unit(group["key"], seed))
        target = max(min_per_year_level, int(round(len(ordered) * fraction)))
        target = min(target, max_per_year_level, len(ordered))
        if len(ordered) < min_per_year_level:
            target = max(1, int(round(len(ordered) * min(fraction, 0.5))))
        picked = 0
        for group in ordered:
            canyon_id = int(group["canyonId"])
            if selected_by_canyon[canyon_id] >= max_per_canyon:
                continue
            selected.add(group["key"])
            selected_by_canyon[canyon_id] += 1
            picked += 1
            if picked >= target:
                break

    return selected


def main() -> None:
    parser = argparse.ArgumentParser(description="Build a year/level/canyon-day stratified holdout split from debit feature rows")
    parser.add_argument("--features-path", default=DEFAULT_FEATURES_PATH)
    parser.add_argument("--output-dir", default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--fraction", type=float, default=0.10)
    parser.add_argument("--min-per-year-level", type=int, default=2)
    parser.add_argument("--max-per-year-level", type=int, default=40)
    parser.add_argument("--max-per-canyon", type=int, default=8)
    args = parser.parse_args()

    rows = read_jsonl(Path(args.features_path))
    groups = build_groups(rows)
    selected_group_keys = select_holdout_groups(
        groups,
        seed=args.seed,
        fraction=args.fraction,
        min_per_year_level=args.min_per_year_level,
        max_per_year_level=args.max_per_year_level,
        max_per_canyon=args.max_per_canyon,
    )

    train_rows: list[dict[str, Any]] = []
    test_rows: list[dict[str, Any]] = []
    test_group_rows: list[dict[str, Any]] = []
    for group in groups:
        target = test_rows if group["key"] in selected_group_keys else train_rows
        target.extend(group["rows"])
        if group["key"] in selected_group_keys:
            test_group_rows.append({key: value for key, value in group.items() if key != "rows"})

    output_dir = Path(args.output_dir)
    write_jsonl(output_dir / "train_features.jsonl", sorted(train_rows, key=lambda row: (row.get("date") or "", row.get("observationId") or "")))
    write_jsonl(output_dir / "test_features.jsonl", sorted(test_rows, key=lambda row: (row.get("date") or "", row.get("observationId") or "")))
    write_jsonl(output_dir / "test_groups.jsonl", sorted(test_group_rows, key=lambda row: (row["year"], row["level"], row["canyonId"], row["date"])))

    metadata = {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "featuresPath": args.features_path,
        "seed": args.seed,
        "fraction": args.fraction,
        "minPerYearLevel": args.min_per_year_level,
        "maxPerYearLevel": args.max_per_year_level,
        "maxPerCanyon": args.max_per_canyon,
        "sourceRowCount": len(rows),
        "groupCount": len(groups),
        "trainRowCount": len(train_rows),
        "testRowCount": len(test_rows),
        "testGroupCount": len(test_group_rows),
        "trainLevelCounts": dict(sorted(Counter(row["niveau"] for row in train_rows).items())),
        "testLevelCounts": dict(sorted(Counter(row["niveau"] for row in test_rows).items())),
        "testYearCounts": dict(sorted(Counter(row["date"][:4] for row in test_rows).items())),
        "testCanyonCount": len({row["canyonId"] for row in test_rows}),
        "files": {
            "trainFeatures": "train_features.jsonl",
            "testFeatures": "test_features.jsonl",
            "testGroups": "test_groups.jsonl",
        },
        "methodologyWarning": "Feature rows contain precomputed causal history from the full feature build. For a strict production backtest, rebuild causal history and runtime lookups from train rows only.",
    }
    write_json(output_dir / "metadata.json", metadata)
    print(json.dumps(metadata, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
