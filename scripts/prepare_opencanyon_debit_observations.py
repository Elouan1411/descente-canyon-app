from __future__ import annotations

import argparse
import json
import re
import unicodedata
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from debit_pipeline_lib import load_canyon_lookup, write_json, write_jsonl


DEFAULT_REPORTS_PATH = "build/opencanyon/reports/opencanyon_reports.jsonl"
DEFAULT_DESCENTE_OBSERVATIONS_PATH = "build/debit-pipeline/observations/valid_debit_observations.jsonl"
DEFAULT_CANYONS_PATH = "offline-data/full/room-import/canyons.json"
DEFAULT_OUTPUT_DIR = "build/opencanyon/prepared-debit-observations"
NIVEAU_RANK = {"SEC": 0, "FILET": 1, "CORRECT": 2, "GROS": 3, "TRES_GROS": 4, "CRUE": 5}


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            stripped = line.strip()
            if stripped:
                rows.append(json.loads(stripped))
    return rows


def normalize_text(value: str | None) -> str:
    if not value:
        return ""
    normalized = unicodedata.normalize("NFKD", value)
    normalized = "".join(character for character in normalized if not unicodedata.combining(character))
    normalized = normalized.lower()
    normalized = normalized.replace("&", " et ")
    normalized = re.sub(r"\([^)]*\)", " ", normalized)
    normalized = re.sub(r"[^a-z0-9]+", " ", normalized)
    return re.sub(r"\s+", " ", normalized).strip()


def compact_comment(value: str | None) -> str:
    normalized = normalize_text(value)
    stop_words = {"le", "la", "les", "de", "du", "des", "un", "une", "and", "the", "a", "to", "of", "et", "en"}
    return " ".join(token for token in normalized.split() if token not in stop_words)


def token_jaccard(left: str, right: str) -> float:
    left_tokens = set(left.split())
    right_tokens = set(right.split())
    if not left_tokens or not right_tokens:
        return 0.0
    return len(left_tokens & right_tokens) / len(left_tokens | right_tokens)


def canyon_name_candidates(canyon: dict[str, Any]) -> set[str]:
    values = {canyon.get("nom"), canyon.get("nomComplet")}
    result: set[str] = set()
    for value in values:
        normalized = normalize_text(value)
        if normalized:
            result.add(normalized)
    return result


def build_canyon_name_index(canyon_lookup: dict[int, dict[str, Any]]) -> dict[str, list[int]]:
    index: dict[str, list[int]] = defaultdict(list)
    for canyon_id, canyon in canyon_lookup.items():
        for name in canyon_name_candidates(canyon):
            index[name].append(canyon_id)
    return index


def match_report_to_canyon(report: dict[str, Any], canyon_lookup: dict[int, dict[str, Any]], name_index: dict[str, list[int]]) -> tuple[int | None, str | None]:
    name = normalize_text(report.get("canyonName"))
    if not name:
        return None, None
    candidates = name_index.get(name, [])
    if len(candidates) == 1:
        return candidates[0], "unique_name"
    if len(candidates) > 1:
        report_region = normalize_text(report.get("region"))
        region_matches = [
            canyon_id
            for canyon_id in candidates
            if report_region and normalize_text(canyon_lookup[canyon_id].get("region")) == report_region
        ]
        if len(region_matches) == 1:
            return region_matches[0], "name_region"
        return None, "ambiguous_name"
    return None, "no_name_match"


def descente_duplicate_score(report: dict[str, Any], descente_rows: list[dict[str, Any]]) -> float:
    report_comment = compact_comment(report.get("comment"))
    best_score = 0.0
    for row in descente_rows:
        if report.get("targetThreeClass") and row.get("targetThreeClass") and report.get("targetThreeClass") != row.get("targetThreeClass"):
            continue
        score = token_jaccard(report_comment, compact_comment(row.get("comment") or row.get("commentText")))
        best_score = max(best_score, score)
    return round(best_score, 6)


def target_three_class_from_niveau(level: str | None) -> str | None:
    if level in {"SEC", "FILET"}:
        return "LOW"
    if level == "CORRECT":
        return "MEDIUM"
    if level in {"GROS", "TRES_GROS", "CRUE"}:
        return "HIGH"
    return None


def observation_from_report(report: dict[str, Any], canyon_id: int, canyon: dict[str, Any], duplicate_score: float) -> dict[str, Any]:
    niveau = report.get("niveau")
    authors = [value for value in [report.get("author"), *[user.get("name") for user in report.get("otherUsers", [])]] if value]
    return {
        "observationId": f"oc_{report['sourceReportId']}",
        "source": "opencanyon",
        "sourceReportId": report.get("sourceReportId"),
        "openCanyonUuid": report.get("openCanyonUuid"),
        "canyonId": canyon_id,
        "canyonName": canyon.get("nom") or report.get("canyonName"),
        "openCanyonCanyonName": report.get("canyonName"),
        "dateRaw": report.get("dateRaw"),
        "date": report.get("date"),
        "assumedObservationHourLocal": 12,
        "assumedObservationTimeLocal": f"{report.get('date')}T12:00:00" if report.get("date") else None,
        "niveau": niveau,
        "niveauRank": NIVEAU_RANK.get(niveau),
        "targetThreeClass": target_three_class_from_niveau(niveau),
        "authors": authors,
        "authorCount": len(authors),
        "primaryAuthor": report.get("author"),
        "isDescended": bool(report.get("completed")),
        "waterTemperature": None,
        "airTemperature": None,
        "comment": report.get("comment"),
        "comments": [comment.get("text") for comment in report.get("comments", []) if comment.get("text")],
        "remarkId": report.get("sourceReportId"),
        "observationTitle": "completed" if report.get("completed") else "not_completed_or_unknown",
        "sourceUrl": report.get("sourceUrl"),
        "qualityLabel": "valid" if niveau else "uncertain",
        "qualityScore": max(0.0, 1.0 - duplicate_score),
        "qualityReasons": ["opencanyon_report", f"water_level:{report.get('waterLevel')}"] if niveau else ["opencanyon_report", "missing_water_level"],
        "likelyDescenteDuplicateScore": duplicate_score,
        "manualOverride": False,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Prepare OpenCanyon reports as candidate debit observations")
    parser.add_argument("--reports-path", default=DEFAULT_REPORTS_PATH)
    parser.add_argument("--descente-observations-path", default=DEFAULT_DESCENTE_OBSERVATIONS_PATH)
    parser.add_argument("--canyons-path", default=DEFAULT_CANYONS_PATH)
    parser.add_argument("--output-dir", default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--duplicate-threshold", type=float, default=0.82)
    parser.add_argument("--keep-likely-descente-duplicates", action="store_true")
    args = parser.parse_args()

    reports = read_jsonl(Path(args.reports_path))
    descente_rows_raw = read_jsonl(Path(args.descente_observations_path)) if Path(args.descente_observations_path).exists() else []
    for row in descente_rows_raw:
        row["targetThreeClass"] = target_three_class_from_niveau(row.get("niveau"))
    descente_by_canyon_date: dict[tuple[int, str], list[dict[str, Any]]] = defaultdict(list)
    for row in descente_rows_raw:
        if row.get("canyonId") is not None and row.get("date"):
            descente_by_canyon_date[(int(row["canyonId"]), str(row["date"]))].append(row)

    canyon_lookup = load_canyon_lookup(Path(args.canyons_path))
    name_index = build_canyon_name_index(canyon_lookup)
    observations: list[dict[str, Any]] = []
    unmatched: list[dict[str, Any]] = []
    likely_duplicates: list[dict[str, Any]] = []
    match_methods: Counter[str] = Counter()

    for report in reports:
        if report.get("isDescenteCanyonImport"):
            continue
        canyon_id, match_method = match_report_to_canyon(report, canyon_lookup, name_index)
        match_methods[match_method or "unknown"] += 1
        if canyon_id is None:
            unmatched.append({"reason": match_method, **report})
            continue

        duplicate_score = descente_duplicate_score(report, descente_by_canyon_date.get((canyon_id, str(report.get("date"))), []))
        observation = observation_from_report(report, canyon_id, canyon_lookup[canyon_id], duplicate_score)
        observation["matchMethod"] = match_method
        if duplicate_score >= args.duplicate_threshold:
            likely_duplicates.append(observation)
            if not args.keep_likely_descente_duplicates:
                continue
        observations.append(observation)

    valid_observations = [row for row in observations if row.get("qualityLabel") == "valid"]
    uncertain_observations = [row for row in observations if row.get("qualityLabel") != "valid"]
    output_dir = Path(args.output_dir)
    write_jsonl(output_dir / "opencanyon_candidate_observations.jsonl", observations)
    write_jsonl(output_dir / "opencanyon_valid_observations.jsonl", valid_observations)
    write_jsonl(output_dir / "opencanyon_uncertain_observations.jsonl", uncertain_observations)
    write_jsonl(output_dir / "opencanyon_unmatched_reports.jsonl", unmatched)
    write_jsonl(output_dir / "opencanyon_likely_descente_duplicates.jsonl", likely_duplicates)
    write_json(
        output_dir / "metadata.json",
        {
            "schemaVersion": 1,
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "reportsPath": args.reports_path,
            "sourceReportCount": len(reports),
            "candidateObservationCount": len(observations),
            "validObservationCount": len(valid_observations),
            "uncertainObservationCount": len(uncertain_observations),
            "unmatchedReportCount": len(unmatched),
            "likelyDescenteDuplicateCount": len(likely_duplicates),
            "duplicateThreshold": args.duplicate_threshold,
            "excludedLikelyDescenteDuplicates": not args.keep_likely_descente_duplicates,
            "matchMethods": dict(sorted(match_methods.items())),
            "targetThreeClassCounts": dict(sorted(Counter(row.get("targetThreeClass") or "UNKNOWN" for row in observations).items())),
            "licenseWarning": "OpenCanyon data is CC BY-NC-SA 4.0; verify compatibility before shipping derived datasets.",
            "files": {
                "candidateObservations": "opencanyon_candidate_observations.jsonl",
                "validObservations": "opencanyon_valid_observations.jsonl",
                "uncertainObservations": "opencanyon_uncertain_observations.jsonl",
                "unmatchedReports": "opencanyon_unmatched_reports.jsonl",
                "likelyDescenteDuplicates": "opencanyon_likely_descente_duplicates.jsonl",
            },
        },
    )


if __name__ == "__main__":
    main()
