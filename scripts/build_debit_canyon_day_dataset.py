from __future__ import annotations

import argparse
import json
import math
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from debit_pipeline_lib import normalize_text, write_json, write_jsonl
from train_debit_baseline_model import target_three_classes


DEFAULT_FEATURES_PATH = "build/debit-pipeline/training-features-improved/training_features.jsonl"
DEFAULT_OUTPUT_DIR = "build/debit-pipeline/training-features-canyon-day"
THREE_CLASS_TO_REPRESENTATIVE_NIVEAU = {
    "LOW": "FILET",
    "MEDIUM": "CORRECT",
    "HIGH": "GROS",
}
NIVEAU_TO_RANK = {
    "SEC": 0.0,
    "FILET": 1.0,
    "CORRECT": 2.0,
    "GROS": 3.0,
    "TRES_GROS": 4.0,
    "CRUE": 5.0,
}
RANK_TO_NIVEAU = ["SEC", "FILET", "CORRECT", "GROS", "TRES_GROS", "CRUE"]
TEXT_SIGNALS = (
    ("CRUE", 5.0, 0.95, ("crue", "en crue", "flood", "flooded", "spate")),
    ("TRES_GROS", 4.0, 0.9, ("tres gros", "tgd", "trop d eau", "trop d'eau", "dangerous water", "very high water")),
    ("GROS", 3.0, 0.85, ("gros debit", "gros débit", "gd", "beaucoup d eau", "beaucoup d'eau", "high water", "strong flow", "grosse eau", "debit fort", "débit fort")),
    ("FILET", 1.0, 0.8, ("filet", "petit debit", "petit débit", "peu d eau", "peu d'eau", "low water", "little water", "faible debit", "faible débit")),
    ("SEC", 0.0, 0.9, ("sec", "a sec", "à sec", "asseche", "asséché", "dry", "no water", "pas d eau", "pas d'eau")),
    ("CORRECT", 2.0, 0.65, ("debit correct", "débit correct", "niveau correct", "normal", "nice flow", "good flow", "bon debit", "bon débit")),
)


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            stripped = line.strip()
            if stripped:
                rows.append(json.loads(stripped))
    return rows


def group_key(row: dict[str, Any]) -> tuple[int, str]:
    return int(row["canyonId"]), str(row["date"])


def choose_representative_row(rows: list[dict[str, Any]]) -> dict[str, Any]:
    sorted_rows = sorted(rows, key=lambda row: (row.get("assumedObservationTimeLocal") or row.get("date") or "", row.get("observationId") or ""))
    return dict(sorted_rows[0])


def niveau_from_rank(rank: float) -> str:
    bounded = min(max(rank, 0.0), 5.0)
    return RANK_TO_NIVEAU[int(round(bounded))]


def text_hydrology_signal(text: str | None) -> dict[str, Any] | None:
    normalized = normalize_text(text)
    if not normalized:
        return None
    for label, rank, confidence, patterns in TEXT_SIGNALS:
        for pattern in patterns:
            if normalize_text(pattern) in normalized:
                return {
                    "label": label,
                    "rank": rank,
                    "confidence": confidence,
                    "pattern": pattern,
                }
    return None


def row_vote_weight(row: dict[str, Any]) -> tuple[float, dict[str, Any] | None]:
    quality_score = row.get("qualityScore")
    try:
        weight = float(quality_score) if quality_score is not None else 1.0
    except (TypeError, ValueError):
        weight = 1.0
    weight = min(max(weight, 0.1), 1.5)

    signal = text_hydrology_signal(row.get("commentText") or row.get("comment"))
    raw_level = row.get("niveau")
    raw_rank = NIVEAU_TO_RANK.get(raw_level)
    if signal is None or raw_rank is None:
        return weight, signal

    distance = abs(float(signal["rank"]) - raw_rank)
    if distance >= 2.0 and signal["confidence"] >= 0.85:
        weight *= 0.35
    elif distance <= 0.5 and signal["confidence"] >= 0.8:
        weight *= 1.10
    return weight, signal


def entropy(probabilities: list[float]) -> float:
    values = [value for value in probabilities if value > 0.0]
    if not values:
        return 0.0
    return -sum(value * math.log(value) for value in values) / math.log(len(probabilities))


def consensus_for_rows(rows: list[dict[str, Any]]) -> dict[str, Any]:
    class_counts: Counter[str] = Counter()
    raw_counts: Counter[str] = Counter()
    weighted_class_counts: dict[str, float] = defaultdict(float)
    weighted_raw_counts: dict[str, float] = defaultdict(float)
    weighted_rank_sum = 0.0
    total_weight = 0.0
    text_signal_counts: Counter[str] = Counter()
    text_conflict_count = 0
    observation_ids: list[str] = []
    for row in rows:
        raw_level = row.get("niveau")
        target = target_three_classes(raw_level)
        raw_rank = NIVEAU_TO_RANK.get(raw_level)
        if target is None or raw_rank is None:
            continue
        weight, signal = row_vote_weight(row)
        class_counts[target] += 1
        raw_counts[str(raw_level)] += 1
        weighted_class_counts[target] += weight
        weighted_raw_counts[str(raw_level)] += weight
        weighted_rank_sum += raw_rank * weight
        total_weight += weight
        if signal is not None:
            text_signal_counts[str(signal["label"])] += 1
            if abs(float(signal["rank"]) - raw_rank) >= 2.0 and signal["confidence"] >= 0.85:
                text_conflict_count += 1
        if row.get("observationId"):
            observation_ids.append(str(row["observationId"]))

    if not class_counts:
        return {"usable": False, "reason": "no_target"}

    ranked = sorted(weighted_class_counts.items(), key=lambda item: item[1], reverse=True)
    top_label, top_weight = ranked[0]
    top_count = class_counts[top_label]
    has_tie = len(ranked) > 1 and abs(ranked[1][1] - top_weight) < 1e-9
    total = sum(class_counts.values())
    soft_target_rank = weighted_rank_sum / total_weight if total_weight > 0.0 else NIVEAU_TO_RANK[THREE_CLASS_TO_REPRESENTATIVE_NIVEAU[top_label]]
    weighted_probabilities = {
        label: round(weighted_class_counts.get(label, 0.0) / total_weight, 6) if total_weight > 0.0 else 0.0
        for label in ("LOW", "MEDIUM", "HIGH")
    }
    raw_probabilities = {
        label: round(weighted_raw_counts.get(label, 0.0) / total_weight, 6) if total_weight > 0.0 else 0.0
        for label in NIVEAU_TO_RANK
    }
    variance = sum(
        (NIVEAU_TO_RANK[level] - soft_target_rank) ** 2 * weighted_raw_counts.get(level, 0.0)
        for level in NIVEAU_TO_RANK
    ) / total_weight if total_weight > 0.0 else 0.0
    has_low_high_conflict = class_counts.get("LOW", 0) > 0 and class_counts.get("HIGH", 0) > 0
    return {
        "usable": True,
        "targetThreeClass": top_label,
        "representativeNiveau": niveau_from_rank(soft_target_rank),
        "classVoteCounts": dict(sorted(class_counts.items())),
        "rawNiveauCounts": dict(sorted(raw_counts.items())),
        "weightedClassVoteCounts": {key: round(value, 6) for key, value in sorted(weighted_class_counts.items())},
        "weightedRawNiveauCounts": {key: round(value, 6) for key, value in sorted(weighted_raw_counts.items())},
        "targetClassProbabilities": weighted_probabilities,
        "targetRawNiveauProbabilities": raw_probabilities,
        "softTargetRank": round(soft_target_rank, 6),
        "targetRankVariance": round(variance, 6),
        "targetEntropy": round(entropy(list(weighted_probabilities.values())), 6),
        "sourceObservationIds": observation_ids,
        "sourceObservationCount": len(observation_ids),
        "consensusConfidence": round(top_weight / total_weight, 6) if total_weight > 0.0 else round(top_count / total, 6),
        "textSignalCounts": dict(sorted(text_signal_counts.items())),
        "textConflictCount": text_conflict_count,
        "textConflictFraction": round(text_conflict_count / total, 6) if total else 0.0,
        "isContradictory": len(class_counts) > 1,
        "hasLowHighConflict": has_low_high_conflict,
        "hasConsensusTie": has_tie,
    }


def build_canyon_day_rows(
    rows: list[dict[str, Any]],
    *,
    min_consensus_confidence: float,
    drop_low_high_conflicts: bool,
    drop_consensus_ties: bool,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    grouped: dict[tuple[int, str], list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        if row.get("canyonId") is None or not row.get("date"):
            continue
        grouped[group_key(row)].append(row)

    output_rows: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []
    for (canyon_id, observation_date), group_rows in sorted(grouped.items(), key=lambda item: (item[0][1], item[0][0])):
        consensus = consensus_for_rows(group_rows)
        if not consensus.get("usable"):
            skipped.append({"canyonId": canyon_id, "date": observation_date, "reason": consensus.get("reason"), "rowCount": len(group_rows)})
            continue
        if drop_consensus_ties and consensus["hasConsensusTie"]:
            skipped.append({"canyonId": canyon_id, "date": observation_date, "reason": "consensus_tie", **consensus})
            continue
        if drop_low_high_conflicts and consensus["hasLowHighConflict"]:
            skipped.append({"canyonId": canyon_id, "date": observation_date, "reason": "low_high_conflict", **consensus})
            continue
        if consensus["consensusConfidence"] < min_consensus_confidence:
            skipped.append({"canyonId": canyon_id, "date": observation_date, "reason": "low_consensus_confidence", **consensus})
            continue

        row = choose_representative_row(group_rows)
        row["observationId"] = f"canyon_day_{canyon_id}_{observation_date}"
        row["sourceObservationIds"] = consensus["sourceObservationIds"]
        row["sourceObservationCount"] = consensus["sourceObservationCount"]
        row["classVoteCounts"] = consensus["classVoteCounts"]
        row["rawNiveauCounts"] = consensus["rawNiveauCounts"]
        row["weightedClassVoteCounts"] = consensus["weightedClassVoteCounts"]
        row["weightedRawNiveauCounts"] = consensus["weightedRawNiveauCounts"]
        row["targetClassProbabilities"] = consensus["targetClassProbabilities"]
        row["targetRawNiveauProbabilities"] = consensus["targetRawNiveauProbabilities"]
        row["targetThreeClass"] = consensus["targetThreeClass"]
        row["niveau"] = consensus["representativeNiveau"]
        row["softTargetRank"] = consensus["softTargetRank"]
        row["targetRankVariance"] = consensus["targetRankVariance"]
        row["targetEntropy"] = consensus["targetEntropy"]
        row["consensusConfidence"] = consensus["consensusConfidence"]
        row["textSignalCounts"] = consensus["textSignalCounts"]
        row["textConflictCount"] = consensus["textConflictCount"]
        row["textConflictFraction"] = consensus["textConflictFraction"]
        row["isContradictoryCanyonDay"] = consensus["isContradictory"]
        row["hasLowHighConflictCanyonDay"] = consensus["hasLowHighConflict"]
        row["hasConsensusTieCanyonDay"] = consensus["hasConsensusTie"]
        row["commentText"] = "\n\n".join(str(item.get("commentText") or "") for item in group_rows if item.get("commentText")) or row.get("commentText")
        output_rows.append(row)
    return output_rows, skipped


def assign_canyon_balanced_weights(rows: list[dict[str, Any]]) -> None:
    canyon_day_counts: Counter[int] = Counter(int(row["canyonId"]) for row in rows)
    raw_weights: list[float] = []
    for row in rows:
        canyon_count = canyon_day_counts[int(row["canyonId"])]
        confidence = float(row.get("consensusConfidence") or 1.0)
        source_count = max(float(row.get("sourceObservationCount") or 1.0), 1.0)
        entropy_penalty = 1.0 - min(max(float(row.get("targetEntropy") or 0.0), 0.0), 1.0) * 0.35
        text_conflict_penalty = 1.0 - min(max(float(row.get("textConflictFraction") or 0.0), 0.0), 1.0) * 0.50
        source_bonus = 1.0 + min(math.log1p(source_count), 2.0) * 0.08
        raw_weights.append(confidence * entropy_penalty * text_conflict_penalty * source_bonus / math.sqrt(canyon_count))

    total_raw_weight = sum(raw_weights)
    scale = len(raw_weights) / total_raw_weight if total_raw_weight > 0.0 else 1.0
    for row, raw_weight in zip(rows, raw_weights):
        row["sampleWeight"] = round(raw_weight * scale, 6)


def main() -> None:
    parser = argparse.ArgumentParser(description="Aggregate débit training features to cleaned canyon-day targets")
    parser.add_argument("--features-path", default=DEFAULT_FEATURES_PATH)
    parser.add_argument("--output-dir", default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--min-consensus-confidence", type=float, default=0.5)
    parser.add_argument("--keep-low-high-conflicts", action="store_true")
    parser.add_argument("--keep-consensus-ties", action="store_true")
    args = parser.parse_args()

    rows = read_jsonl(Path(args.features_path))
    canyon_day_rows, skipped = build_canyon_day_rows(
        rows,
        min_consensus_confidence=args.min_consensus_confidence,
        drop_low_high_conflicts=not args.keep_low_high_conflicts,
        drop_consensus_ties=not args.keep_consensus_ties,
    )
    assign_canyon_balanced_weights(canyon_day_rows)

    output_dir = Path(args.output_dir)
    write_jsonl(output_dir / "training_features.jsonl", canyon_day_rows)
    write_json(output_dir / "skipped_canyon_days.json", skipped)
    write_json(
        output_dir / "metadata.json",
        {
            "schemaVersion": 1,
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "sourceFeaturesPath": args.features_path,
            "sourceRowCount": len(rows),
            "canyonDayRowCount": len(canyon_day_rows),
            "skippedCanyonDayCount": len(skipped),
            "dropLowHighConflicts": not args.keep_low_high_conflicts,
            "dropConsensusTies": not args.keep_consensus_ties,
            "minConsensusConfidence": args.min_consensus_confidence,
            "classCounts": dict(sorted(Counter(row["targetThreeClass"] for row in canyon_day_rows).items())),
            "rawNiveauCounts": dict(sorted(Counter(row["niveau"] for row in canyon_day_rows).items())),
            "contradictoryCanyonDayCount": sum(1 for row in canyon_day_rows if row.get("isContradictoryCanyonDay")),
            "textConflictCanyonDayCount": sum(1 for row in canyon_day_rows if (row.get("textConflictCount") or 0) > 0),
            "canyonCount": len({row["canyonId"] for row in canyon_day_rows}),
            "sampleWeightSum": round(sum(float(row.get("sampleWeight") or 0.0) for row in canyon_day_rows), 6),
            "files": {
                "trainingFeatures": "training_features.jsonl",
                "skippedCanyonDays": "skipped_canyon_days.json",
            },
        },
    )


if __name__ == "__main__":
    main()
