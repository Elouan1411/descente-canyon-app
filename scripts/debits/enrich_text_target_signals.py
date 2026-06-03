from __future__ import annotations

import argparse
import json
import math
import re
import unicodedata
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from pipeline_lib import write_json, write_jsonl


DEFAULT_FEATURES_PATH = "build/debit-pipeline/training-features-through-2026-05-28-reviewed/training_features.jsonl"
DEFAULT_OUTPUT_DIR = "build/debit-pipeline/training-features-text-target-signals"

LEVEL_TO_RANK = {
    "SEC": 0.0,
    "FILET": 1.0,
    "CORRECT": 2.0,
    "GROS": 3.0,
    "TRES_GROS": 4.0,
    "CRUE": 5.0,
}
RANK_TO_LEVEL = ["SEC", "FILET", "CORRECT", "GROS", "TRES_GROS", "CRUE"]
ABBREVIATION_BASE_RANK = {
    "PD": 1.0,
    "DC": 2.0,
    "GD": 3.0,
    "TGD": 4.0,
}
SIGN_ADJUSTMENTS = {
    "--": -0.35,
    "-": -0.18,
    "": 0.0,
    "+-": 0.0,
    "+": 0.22,
    "++": 0.38,
    "+++": 0.50,
}
ABBREVIATION_RE = re.compile(
    r"(?<![A-Z0-9])"
    r"((?:PD|DC|GD|TGD)\s*(?:[+\-]\s*){0,3}(?:/\s*(?:PD|DC|GD|TGD)\s*(?:[+\-]\s*){0,3}){0,3})"
    r"(?![A-Z0-9])",
    re.IGNORECASE,
)
ABBREVIATION_PART_RE = re.compile(r"(PD|DC|GD|TGD)\s*([+\-]{0,3})", re.IGNORECASE)

# Ordered from specific/high-confidence to broader/lower-confidence signals.
PHRASE_SIGNALS: tuple[dict[str, Any], ...] = (
    {"signal": "debit_correct_plus", "rank": 2.22, "confidence": 0.88, "pattern": r"\bdebit correct\s*\+"},
    {"signal": "debit_correct_minus", "rank": 1.85, "confidence": 0.88, "pattern": r"\bdebit correct\s*-"},
    {"signal": "gros_debit_plus", "rank": 3.25, "confidence": 0.88, "pattern": r"\bgros debit\s*\+"},
    {"signal": "gros_debit_minus", "rank": 2.85, "confidence": 0.88, "pattern": r"\bgros debit\s*-"},
    {"signal": "tres_gros_plus", "rank": 4.22, "confidence": 0.88, "pattern": r"\btres gros\s*\+"},
    {"signal": "tres_gros_minus", "rank": 3.82, "confidence": 0.88, "pattern": r"\btres gros\s*-"},
    {"signal": "correct_to_gros", "rank": 2.50, "confidence": 0.82, "pattern": r"\b(?:correct\s*/\s*gros|correct a gros|debit correct\s*/\s*gros debit)\b"},
    {"signal": "petit_to_correct", "rank": 1.50, "confidence": 0.78, "pattern": r"\b(?:petit debit\s*/\s*debit correct|petit debit a debit correct)\b"},
    {"signal": "gros_to_tres_gros", "rank": 3.50, "confidence": 0.82, "pattern": r"\b(?:gros\s*/\s*tres gros|gros a tres gros|gros debit\s*/\s*tres gros)\b"},
    {"signal": "sec", "rank": 0.0, "confidence": 0.86, "pattern": r"\b(?:a sec|asseche|assechee|dry|pas d eau|sans eau|rien ne coule)\b"},
    {"signal": "filet", "rank": 1.0, "confidence": 0.82, "pattern": r"\b(?:petit filet|filet d eau|goutte a goutte)\b"},
    {"signal": "petit_debit", "rank": 1.25, "confidence": 0.72, "pattern": r"\b(?:petit debit|faible debit|debit faible|peu d eau|quasi pas d eau|manque d eau)\b"},
    {"signal": "debit_correct", "rank": 2.0, "confidence": 0.78, "pattern": r"\b(?:debit correct|niveau correct|debit normal|debit ok|bon debit|debit sympa|debit agreable)\b"},
    {"signal": "correct_plus_context", "rank": 2.45, "confidence": 0.60, "pattern": r"\b(?:bon gros debit|beau debit|bien en eau|plein d eau|beaucoup d eau|ca pousse un peu)\b"},
    {"signal": "gros_debit", "rank": 3.0, "confidence": 0.75, "pattern": r"\b(?:gros debit|debit fort|grosse eau|gros volume|ca pousse fort|ca brasse|mouvements d eau|drossage|machine a laver)\b"},
    {"signal": "tres_gros", "rank": 4.0, "confidence": 0.80, "pattern": r"\b(?:tres gros|trop d eau|trop gros|enorme debit|debit enorme|tres haut|too much water|troppa acqua|mucha agua)\b"},
    {"signal": "crue_current", "rank": 5.0, "confidence": 0.86, "pattern": r"\b(?:en crue|crue en cours|flooded|spate)\b"},
    {"signal": "impraticable", "rank": 4.5, "confidence": 0.76, "pattern": r"\b(?:impraticable|impassable|infranchissable|infaisable|pas faisable|non praticable)\b"},
)
PHRASE_SIGNAL_RES = tuple({**item, "regex": re.compile(str(item["pattern"]))} for item in PHRASE_SIGNALS)
NEGATED_TOKEN_RE = re.compile(r"\b(?:pas|ne mets pas|je ne mets pas|pas en|plus en)\s+(?:PD|DC|GD|TGD)\b", re.IGNORECASE)


def normalize_for_matching(value: str | None) -> str:
    if not value:
        return ""
    text = unicodedata.normalize("NFKD", value)
    text = "".join(character for character in text if not unicodedata.combining(character))
    text = text.lower().replace("++", " ++ ").replace("--", " -- ")
    text = re.sub(r"[^a-z0-9+\-/]+", " ", text)
    return re.sub(r"\s+", " ", text).strip()


def selected_rank(row: dict[str, Any]) -> float | None:
    return LEVEL_TO_RANK.get(str(row.get("niveau") or ""))


def bounded_rank(value: float) -> float:
    return min(max(value, 0.0), 5.0)


def level_from_rank(value: float) -> str:
    return RANK_TO_LEVEL[int(round(bounded_rank(value)))]


def abbreviation_part_rank(abbreviation: str, sign: str) -> float:
    normalized_sign = "".join(character for character in sign if character in "+-")[:3]
    return bounded_rank(ABBREVIATION_BASE_RANK[abbreviation.upper()] + SIGN_ADJUSTMENTS.get(normalized_sign, 0.0))


def parse_abbreviation_match(raw: str, selected: float | None) -> dict[str, Any] | None:
    parts = [
        (match.group(1).upper(), "".join(character for character in match.group(2) if character in "+-"))
        for match in ABBREVIATION_PART_RE.finditer(raw)
    ]
    if not parts:
        return None
    ranks = [abbreviation_part_rank(abbreviation, sign) for abbreviation, sign in parts]
    has_range = len(parts) > 1
    has_modifier = any(sign for _, sign in parts)
    confidence = 0.95 if has_range or has_modifier else 0.86
    signal = "/".join(f"{abbreviation}{sign}" for abbreviation, sign in parts)
    rank_value = sum(ranks) / len(ranks)
    if has_range and selected is not None:
        low = min(ranks)
        high = max(ranks)
        if low <= selected <= high:
            rank_value = selected
    return {
        "source": "abbreviation",
        "signal": signal,
        "matchedText": raw.strip(),
        "rank": round(bounded_rank(rank_value), 6),
        "rankMin": round(min(ranks), 6),
        "rankMax": round(max(ranks), 6),
        "confidence": confidence,
        "specificity": 3 if has_range else 2 if has_modifier else 1,
    }


def should_skip_abbreviation(text: str, start: int, end: int) -> bool:
    before = text[max(0, start - 24):start]
    candidate = text[start:end]
    return bool(NEGATED_TOKEN_RE.search(f"{before}{candidate}"))


def should_skip_phrase(signal: str, text: str, start: int, end: int) -> bool:
    before = text[max(0, start - 42):start]
    after = text[end:end + 42]
    before_tail = before.strip()
    if signal in {"correct_plus_context", "gros_debit", "tres_gros", "impraticable"}:
        if re.search(r"\b(?:pas|sans|peu|quasi pas|manque de)\s+$", before_tail):
            return True
        if re.search(r"\b(?:pas trop|pas beaucoup|pas de trop|pas un gros|pas gros)\s+$", before_tail):
            return True
    if signal in {"gros_debit", "tres_gros", "crue_current"}:
        if re.search(r"\b(?:si|par|en cas de|eviter en cas de|shunt en cas de)\s+$", before_tail):
            return True
        if re.search(r"\b(?:apres la|apres une|apres un|suite a la|suite aux|prochaine|ancienne|dernieres?)\s+$", before_tail):
            return True
    if signal == "sec":
        if re.search(r"\b(?:pas|n est pas|n etait pas|pas totalement)\s+$", before_tail):
            return True
    if signal == "debit_correct":
        if re.search(r"\b(?:pas|plus que|moins que)\s+$", before_tail):
            return True
        if re.search(r"^\s*(?:entendez|veut dire|signifie)\b", after):
            return True
    return False


def extract_text_target_signal(comment: str | None, selected: float | None = None) -> dict[str, Any]:
    text = normalize_for_matching(comment)
    matches: list[dict[str, Any]] = []
    if not text:
        return {"matches": [], "best": None, "weightedMeanRank": None}

    for match in ABBREVIATION_RE.finditer(text):
        if should_skip_abbreviation(text, match.start(1), match.end(1)):
            continue
        payload = parse_abbreviation_match(match.group(1), selected)
        if payload is not None:
            matches.append(payload)

    for item in PHRASE_SIGNAL_RES:
        regex = item["regex"]
        for match in regex.finditer(text):
            if should_skip_phrase(str(item["signal"]), text, match.start(), match.end()):
                continue
            matches.append(
                {
                    "source": "phrase",
                    "signal": item["signal"],
                    "matchedText": match.group(0).strip(),
                    "rank": item["rank"],
                    "rankMin": item["rank"],
                    "rankMax": item["rank"],
                    "confidence": item["confidence"],
                    "specificity": 0,
                }
            )

    if not matches:
        return {"matches": [], "best": None, "weightedMeanRank": None}

    deduped: dict[tuple[str, str, str], dict[str, Any]] = {}
    for item in matches:
        key = (str(item["source"]), str(item["signal"]), str(item["matchedText"]))
        current = deduped.get(key)
        if current is None or (float(item["confidence"]), int(item["specificity"])) > (float(current["confidence"]), int(current["specificity"])):
            deduped[key] = item
    matches = list(deduped.values())
    weighted_sum = sum(float(item["rank"]) * float(item["confidence"]) for item in matches)
    confidence_sum = sum(float(item["confidence"]) for item in matches)
    weighted_mean = weighted_sum / confidence_sum if confidence_sum else None

    def best_key(item: dict[str, Any]) -> tuple[float, int, float]:
        distance = abs(float(item["rank"]) - selected) if selected is not None else 0.0
        return (float(item["confidence"]), int(item["specificity"]), -distance)

    best = max(matches, key=best_key)
    return {
        "matches": sorted(matches, key=lambda item: (-float(item["confidence"]), -int(item["specificity"]), str(item["signal"]))),
        "best": best,
        "weightedMeanRank": round(weighted_mean, 6) if weighted_mean is not None else None,
    }


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            stripped = line.strip()
            if stripped:
                rows.append(json.loads(stripped))
    return rows


def agreement_category(delta: float | None) -> str:
    if delta is None:
        return "no_signal"
    absolute = abs(delta)
    if absolute <= 0.25:
        return "strong_agreement"
    if absolute <= 0.75:
        return "near_boundary"
    if absolute <= 1.25:
        return "soft_conflict"
    return "strong_conflict"


def enrich_row(row: dict[str, Any]) -> dict[str, Any]:
    enriched = dict(row)
    selected = selected_rank(row)
    extracted = extract_text_target_signal(row.get("commentText") or row.get("comment"), selected)
    best = extracted["best"]
    if best is None:
        enriched.update(
            {
                "textTargetHasSignal": False,
                "textTargetMatchCount": 0,
                "textTargetAgreement": "no_signal",
            }
        )
        return enriched

    text_rank = float(best["rank"])
    delta = text_rank - selected if selected is not None else None
    enriched.update(
        {
            "textTargetHasSignal": True,
            "textTargetRank": round(text_rank, 6),
            "textTargetLevel": level_from_rank(text_rank),
            "textTargetRankMin": best["rankMin"],
            "textTargetRankMax": best["rankMax"],
            "textTargetWeightedMeanRank": extracted["weightedMeanRank"],
            "textTargetSource": best["source"],
            "textTargetSignal": best["signal"],
            "textTargetMatchedText": best["matchedText"],
            "textTargetConfidence": best["confidence"],
            "textTargetMatchCount": len(extracted["matches"]),
            "textTargetMatches": extracted["matches"],
            "textSelectedLevelDelta": round(delta, 6) if delta is not None else None,
            "textSelectedLevelAbsDelta": round(abs(delta), 6) if delta is not None else None,
            "textTargetAgreement": agreement_category(delta),
        }
    )
    soft_rank = enriched.get("softTargetRank")
    if soft_rank is None:
        enriched["softTargetRank"] = round(text_rank, 6)
        enriched["softTargetRankSource"] = "text_target_signal"
    return enriched


def compact_audit_row(row: dict[str, Any]) -> dict[str, Any]:
    return {
        "observationId": row.get("observationId"),
        "canyonId": row.get("canyonId"),
        "canyonName": row.get("canyonName"),
        "date": row.get("date"),
        "niveau": row.get("niveau"),
        "textTargetRank": row.get("textTargetRank"),
        "textTargetLevel": row.get("textTargetLevel"),
        "textTargetSignal": row.get("textTargetSignal"),
        "textTargetMatchedText": row.get("textTargetMatchedText"),
        "textTargetConfidence": row.get("textTargetConfidence"),
        "textSelectedLevelDelta": row.get("textSelectedLevelDelta"),
        "textTargetAgreement": row.get("textTargetAgreement"),
        "commentText": row.get("commentText") or row.get("comment"),
    }


def signal_summary(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    grouped: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        if row.get("textTargetHasSignal"):
            grouped[(str(row.get("textTargetSource")), str(row.get("textTargetSignal")))].append(row)
    summary: list[dict[str, Any]] = []
    for (source, signal), group in grouped.items():
        level_counts = Counter(str(row.get("niveau")) for row in group)
        agreements = Counter(str(row.get("textTargetAgreement")) for row in group)
        deltas = [float(row.get("textSelectedLevelDelta")) for row in group if row.get("textSelectedLevelDelta") is not None]
        examples = [compact_audit_row(row) for row in group[:3]]
        summary.append(
            {
                "source": source,
                "signal": signal,
                "count": len(group),
                "meanTextTargetRank": round(sum(float(row.get("textTargetRank")) for row in group) / len(group), 6),
                "meanDelta": round(sum(deltas) / len(deltas), 6) if deltas else None,
                "selectedLevelCounts": dict(sorted(level_counts.items())),
                "agreementCounts": dict(sorted(agreements.items())),
                "examples": examples,
            }
        )
    return sorted(summary, key=lambda item: (-int(item["count"]), str(item["source"]), str(item["signal"])))


def markdown_table(headers: list[str], rows: list[list[Any]]) -> str:
    lines = ["| " + " | ".join(headers) + " |", "| " + " | ".join("---" for _ in headers) + " |"]
    for row in rows:
        cells = []
        for value in row:
            if isinstance(value, float):
                cells.append(f"{value:.4f}")
            else:
                cells.append(str(value))
        lines.append("| " + " | ".join(cells) + " |")
    return "\n".join(lines)


def write_markdown_report(path: Path, metadata: dict[str, Any], top_signals: list[dict[str, Any]]) -> None:
    lines = [
        "# Text Target Signal Audit",
        "",
        f"- Source rows: `{metadata['sourceRowCount']}`",
        f"- Rows with text target signal: `{metadata['textSignalRowCount']}`",
        f"- Rows with abbreviation signal: `{metadata['abbreviationSignalRowCount']}`",
        f"- Strong conflicts: `{metadata['agreementCounts'].get('strong_conflict', 0)}`",
        "",
        "## Agreement Counts",
        "",
        markdown_table(
            ["Agreement", "Count"],
            [[key, value] for key, value in sorted(metadata["agreementCounts"].items())],
        ),
        "",
        "## Top Signals",
        "",
        markdown_table(
            ["Source", "Signal", "Count", "Mean rank", "Mean delta"],
            [
                [item["source"], item["signal"], item["count"], item["meanTextTargetRank"], item["meanDelta"]]
                for item in top_signals[:40]
            ],
        ),
        "",
    ]
    path.write_text("\n".join(lines), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description="Extract debit nuance from comments into text target rank audit fields")
    parser.add_argument("--features-path", default=DEFAULT_FEATURES_PATH)
    parser.add_argument("--output-dir", default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--conflict-threshold", type=float, default=1.0)
    args = parser.parse_args()

    rows = read_jsonl(Path(args.features_path))
    enriched_rows = [enrich_row(row) for row in rows]
    signal_rows = [row for row in enriched_rows if row.get("textTargetHasSignal")]
    conflict_rows = [
        row
        for row in signal_rows
        if (row.get("textSelectedLevelAbsDelta") is not None and float(row["textSelectedLevelAbsDelta"]) >= args.conflict_threshold)
    ]
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    top_signals = signal_summary(enriched_rows)
    agreement_counts = Counter(str(row.get("textTargetAgreement")) for row in enriched_rows)
    source_counts = Counter(str(row.get("textTargetSource")) for row in signal_rows)
    level_counts = Counter(str(row.get("niveau")) for row in signal_rows)
    metadata = {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "sourceFeaturesPath": args.features_path,
        "sourceRowCount": len(rows),
        "textSignalRowCount": len(signal_rows),
        "abbreviationSignalRowCount": source_counts.get("abbreviation", 0),
        "phraseSignalRowCount": source_counts.get("phrase", 0),
        "conflictThreshold": args.conflict_threshold,
        "conflictRowCount": len(conflict_rows),
        "agreementCounts": dict(sorted(agreement_counts.items())),
        "signalSourceCounts": dict(sorted(source_counts.items())),
        "signalSelectedLevelCounts": dict(sorted(level_counts.items())),
        "files": {
            "trainingFeatures": "training_features.jsonl",
            "textSignalRows": "text_signal_rows.jsonl",
            "textSignalConflicts": "text_signal_conflicts.jsonl",
            "textSignalPatterns": "text_signal_patterns.json",
            "reportMarkdown": "text_signal_summary.md",
        },
    }

    write_jsonl(output_dir / "training_features.jsonl", enriched_rows)
    write_jsonl(output_dir / "text_signal_rows.jsonl", [compact_audit_row(row) for row in signal_rows])
    write_jsonl(output_dir / "text_signal_conflicts.jsonl", [compact_audit_row(row) for row in conflict_rows])
    write_json(output_dir / "text_signal_patterns.json", top_signals)
    write_json(output_dir / "metadata.json", metadata)
    write_markdown_report(output_dir / "text_signal_summary.md", metadata, top_signals)
    print(json.dumps(metadata, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
