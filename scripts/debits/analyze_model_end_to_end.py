from __future__ import annotations

import argparse
import json
import math
from collections import Counter, defaultdict
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any

from pipeline_lib import DEBIT_DERIVED_MODEL_FEATURE_NAMES, with_debit_derived_model_features, write_json
from train_baseline_model import NUMERIC_FEATURES, numeric_feature_value, target_three_classes


DEFAULT_OBSERVATIONS_DIR = "build/debit-pipeline/observations"
DEFAULT_FEATURES_PATH = "build/debit-pipeline/training-features-improved/training_features.jsonl"
DEFAULT_MODEL_METRICS_PATH = "modele_statistique/metrics.json"
DEFAULT_RELIABILITY_REPORT_PATH = "build/debit-pipeline/model-reliability-derived-quick/reliability_report.json"
DEFAULT_OUTPUT_DIR = "build/debit-pipeline/model-analysis"

WEATHER_FEATURE_PREFIXES = (
    "antecedent_precipitation_index",
    "days_since_precip",
    "max_daily_precip",
    "positive_degree_days",
    "precip_",
    "precipitation",
    "rain",
    "snowfall",
    "temperature",
    "wet_days",
)
STATIC_DESCRIPTOR_PREFIXES = (
    "basin",
    "carbon",
    "channel",
    "climate",
    "drainage",
    "flow",
    "forest",
    "fraction",
    "gdw",
    "geology",
    "glacier",
    "hand",
    "impervious",
    "karst",
    "lake",
    "land",
    "main",
    "mean",
    "median",
    "melton",
    "osm",
    "reservoir",
    "riparian",
    "runoff",
    "soil",
    "stream",
    "terrain",
    "topographic",
    "upstream",
    "valley",
    "water",
    "watershed",
)


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            stripped = line.strip()
            if stripped:
                rows.append(json.loads(stripped))
    return rows


def quantiles(values: list[float]) -> dict[str, float | None]:
    if not values:
        return {"min": None, "p25": None, "median": None, "p75": None, "p90": None, "p95": None, "p99": None, "max": None}
    sorted_values = sorted(values)

    def percentile(p: float) -> float:
        if len(sorted_values) == 1:
            return sorted_values[0]
        index = (len(sorted_values) - 1) * p
        lower = math.floor(index)
        upper = math.ceil(index)
        if lower == upper:
            return sorted_values[lower]
        return sorted_values[lower] * (upper - index) + sorted_values[upper] * (index - lower)

    return {
        "min": round(sorted_values[0], 6),
        "p25": round(percentile(0.25), 6),
        "median": round(percentile(0.50), 6),
        "p75": round(percentile(0.75), 6),
        "p90": round(percentile(0.90), 6),
        "p95": round(percentile(0.95), 6),
        "p99": round(percentile(0.99), 6),
        "max": round(sorted_values[-1], 6),
    }


def counter_summary(counter: Counter[Any], *, limit: int = 15) -> dict[str, Any]:
    total = sum(counter.values())
    return {
        "total": total,
        "unique": len(counter),
        "top": [
            {"key": str(key), "count": count, "fraction": round(count / total, 6) if total else 0.0}
            for key, count in counter.most_common(limit)
        ],
    }


def gini(values: list[int]) -> float | None:
    if not values:
        return None
    sorted_values = sorted(value for value in values if value >= 0)
    total = sum(sorted_values)
    if total == 0:
        return 0.0
    weighted_sum = sum((index + 1) * value for index, value in enumerate(sorted_values))
    return round((2 * weighted_sum) / (len(sorted_values) * total) - (len(sorted_values) + 1) / len(sorted_values), 6)


def date_range(rows: list[dict[str, Any]]) -> dict[str, str | None]:
    dates = sorted(row.get("date") for row in rows if row.get("date"))
    return {"start": dates[0] if dates else None, "end": dates[-1] if dates else None}


def year_month_summaries(rows: list[dict[str, Any]]) -> dict[str, Any]:
    by_year = Counter()
    by_month = Counter()
    by_year_class: dict[str, Counter[str]] = defaultdict(Counter)
    by_month_class: dict[str, Counter[str]] = defaultdict(Counter)
    for row in rows:
        raw_date = row.get("date")
        label = target_three_classes(row.get("niveau"))
        if not raw_date:
            continue
        year = raw_date[:4]
        month = raw_date[5:7]
        by_year[year] += 1
        by_month[month] += 1
        if label:
            by_year_class[year][label] += 1
            by_month_class[month][label] += 1
    return {
        "byYear": dict(sorted(by_year.items())),
        "byMonth": dict(sorted(by_month.items())),
        "classByYear": {key: dict(sorted(value.items())) for key, value in sorted(by_year_class.items())},
        "classByMonth": {key: dict(sorted(value.items())) for key, value in sorted(by_month_class.items())},
    }


def canyon_distribution(rows: list[dict[str, Any]]) -> dict[str, Any]:
    counts = Counter(str(row.get("canyonId")) for row in rows if row.get("canyonId") is not None)
    values = list(counts.values())
    total = sum(values)

    def top_share(limit: int) -> float:
        return round(sum(count for _, count in counts.most_common(limit)) / total, 6) if total else 0.0

    return {
        "canyonCount": len(counts),
        "rowCount": total,
        "rowsPerCanyon": quantiles([float(value) for value in values]),
        "gini": gini(values),
        "top10Share": top_share(10),
        "top50Share": top_share(50),
        "top100Share": top_share(100),
        "canyonCountLe1Obs": sum(1 for value in values if value <= 1),
        "canyonCountLe5Obs": sum(1 for value in values if value <= 5),
        "topCanyons": counter_summary(counts, limit=20)["top"],
    }


def label_distribution(rows: list[dict[str, Any]]) -> dict[str, Any]:
    raw = Counter(row.get("niveau") or "__MISSING__" for row in rows)
    three = Counter(target_three_classes(row.get("niveau")) or "__UNKNOWN__" for row in rows)
    total = len(rows)
    return {
        "raw": counter_summary(raw),
        "threeClass": counter_summary(three),
        "highFraction": round(three.get("HIGH", 0) / total, 6) if total else 0.0,
        "lowFraction": round(three.get("LOW", 0) / total, 6) if total else 0.0,
        "mediumFraction": round(three.get("MEDIUM", 0) / total, 6) if total else 0.0,
    }


def same_day_conflicts(rows: list[dict[str, Any]]) -> dict[str, Any]:
    groups: dict[tuple[Any, Any], list[str]] = defaultdict(list)
    for row in rows:
        label = target_three_classes(row.get("niveau"))
        if row.get("canyonId") is not None and row.get("date") and label:
            groups[(row.get("canyonId"), row.get("date"))].append(label)
    conflicting = [labels for labels in groups.values() if len(set(labels)) > 1]
    rows_in_conflict = sum(len(labels) for labels in conflicting)
    return {
        "groupCount": len(groups),
        "conflictingGroupCount": len(conflicting),
        "conflictingGroupFraction": round(len(conflicting) / len(groups), 6) if groups else 0.0,
        "rowsInConflictingGroups": rows_in_conflict,
        "rowsInConflictingGroupsFraction": round(rows_in_conflict / len(rows), 6) if rows else 0.0,
    }


def near_term_contradictions(rows: list[dict[str, Any]], *, window_days: int = 3) -> dict[str, Any]:
    by_canyon: dict[Any, list[tuple[date, str]]] = defaultdict(list)
    for row in rows:
        label = target_three_classes(row.get("niveau"))
        if row.get("canyonId") is None or not row.get("date") or label not in {"LOW", "HIGH"}:
            continue
        by_canyon[row.get("canyonId")].append((date.fromisoformat(row["date"]), label))

    pair_count = 0
    contradiction_count = 0
    canyon_with_contradiction: set[Any] = set()
    for canyon_id, values in by_canyon.items():
        values.sort()
        for index, (current_date, current_label) in enumerate(values):
            next_index = index + 1
            while next_index < len(values) and (values[next_index][0] - current_date).days <= window_days:
                pair_count += 1
                if values[next_index][1] != current_label:
                    contradiction_count += 1
                    canyon_with_contradiction.add(canyon_id)
                next_index += 1
    return {
        "windowDays": window_days,
        "lowHighPairCount": pair_count,
        "contradictionCount": contradiction_count,
        "contradictionFraction": round(contradiction_count / pair_count, 6) if pair_count else 0.0,
        "canyonWithContradictionCount": len(canyon_with_contradiction),
    }


def history_profile(rows: list[dict[str, Any]]) -> dict[str, Any]:
    buckets = Counter()
    bucket_classes: dict[str, Counter[str]] = defaultdict(Counter)
    counts: list[float] = []
    for row in rows:
        value = numeric_feature_value(row.get("canyonPastObsCount")) or 0.0
        counts.append(value)
        if value <= 0:
            bucket = "0"
        elif value <= 5:
            bucket = "1_5"
        elif value <= 20:
            bucket = "6_20"
        else:
            bucket = "20_plus"
        label = target_three_classes(row.get("niveau"))
        buckets[bucket] += 1
        if label:
            bucket_classes[bucket][label] += 1
    total = len(rows)
    return {
        "canyonPastObsCount": quantiles(counts),
        "buckets": [
            {
                "bucket": bucket,
                "rowCount": count,
                "fraction": round(count / total, 6) if total else 0.0,
                "classes": dict(sorted(bucket_classes[bucket].items())),
            }
            for bucket, count in sorted(buckets.items())
        ],
    }


def feature_family(feature_name: str) -> str:
    if feature_name in DEBIT_DERIVED_MODEL_FEATURE_NAMES:
        if feature_name.endswith("Confidence") or feature_name.endswith("Lift") or feature_name.endswith("Entropy") or feature_name.endswith("Spread"):
            return "derived_history"
        return "derived_hydrology"
    if "Prior" in feature_name or feature_name.endswith("PastObsCount") or feature_name.startswith("historical") or feature_name.startswith("historically"):
        return "history_lookup"
    if feature_name.startswith(WEATHER_FEATURE_PREFIXES):
        return "weather"
    if feature_name in {"month", "monthSin", "monthCos"}:
        return "temporal"
    if feature_name.startswith(STATIC_DESCRIPTOR_PREFIXES) or feature_name in {"altitudeDepartM", "deniveleM", "longueurM", "cascadeMaxM", "hasWatershed"}:
        return "static_physical"
    return "other"


def feature_coverage(rows: list[dict[str, Any]], feature_names: list[str]) -> dict[str, Any]:
    summaries = []
    family_missing: dict[str, list[float]] = defaultdict(list)
    total = len(rows)
    for feature_name in feature_names:
        present = 0
        invalid = 0
        unique_values: set[float] = set()
        for row in rows:
            value = numeric_feature_value(row.get(feature_name))
            if value is None:
                if row.get(feature_name) is not None:
                    invalid += 1
                continue
            present += 1
            if len(unique_values) <= 2048:
                unique_values.add(value)
        missing_fraction = 1.0 - (present / total) if total else 1.0
        family_missing[feature_family(feature_name)].append(missing_fraction)
        summaries.append(
            {
                "feature": feature_name,
                "family": feature_family(feature_name),
                "presentCount": present,
                "invalidCount": invalid,
                "missingFraction": round(missing_fraction, 6),
                "sampledUniqueCount": len(unique_values),
                "allMissing": present == 0,
                "constantOrSingleValue": present > 0 and len(unique_values) == 1,
            }
        )
    return {
        "featureCount": len(feature_names),
        "allMissingFeatureCount": sum(1 for summary in summaries if summary["allMissing"]),
        "constantOrSingleValueFeatureCount": sum(1 for summary in summaries if summary["constantOrSingleValue"]),
        "mostlyMissingFeatures": [summary for summary in summaries if summary["missingFraction"] >= 0.95 and not summary["allMissing"]][:50],
        "worstCoverageFeatures": sorted(summaries, key=lambda item: item["missingFraction"], reverse=True)[:30],
        "families": {
            family: {
                "featureCount": len(values),
                "meanMissingFraction": round(sum(values) / len(values), 6) if values else 0.0,
            }
            for family, values in sorted(family_missing.items())
        },
    }


def descriptor_profile(rows: list[dict[str, Any]]) -> dict[str, Any]:
    flag_names = [
        "hasWatershed",
        "hasWatershedDescriptors",
        "hasClimateDescriptors",
        "hasSoilDescriptors",
        "hasRegulationDescriptors",
        "hasGeologyDescriptors",
        "hasImperviousDescriptors",
        "hasGlacierDescriptors",
        "watershedDescriptorForcedByReview",
    ]
    total = len(rows)
    return {
        flag_name: {
            "trueCount": sum(1 for row in rows if bool(row.get(flag_name))),
            "trueFraction": round(sum(1 for row in rows if bool(row.get(flag_name))) / total, 6) if total else 0.0,
        }
        for flag_name in flag_names
    }


def regional_label_profile(rows: list[dict[str, Any]], key: str) -> list[dict[str, Any]]:
    counters: dict[str, Counter[str]] = defaultdict(Counter)
    for row in rows:
        label = target_three_classes(row.get("niveau"))
        value = row.get(key) or "__UNKNOWN__"
        if label:
            counters[str(value)][label] += 1
    result = []
    for value, counter in counters.items():
        total = sum(counter.values())
        if total < 500:
            continue
        result.append(
            {
                key: value,
                "rowCount": total,
                "highFraction": round(counter.get("HIGH", 0) / total, 6),
                "lowFraction": round(counter.get("LOW", 0) / total, 6),
                "mediumFraction": round(counter.get("MEDIUM", 0) / total, 6),
                "classes": dict(sorted(counter.items())),
            }
        )
    return sorted(result, key=lambda item: item["rowCount"], reverse=True)[:30]


def model_summary(metrics: dict[str, Any]) -> dict[str, Any]:
    threshold_policies = metrics.get("thresholdPolicies", {})
    return {
        "model": metrics.get("model"),
        "featureCount": metrics.get("featureCount"),
        "canyonHistoryDropoutRate": metrics.get("canyonHistoryDropoutRate"),
        "trainRowCount": metrics.get("trainRowCount"),
        "calibrationRowCount": metrics.get("calibrationRowCount"),
        "testRowCount": metrics.get("testRowCount"),
        "argmax": compact_metrics(metrics.get("argmaxMetrics", {})),
        "thresholdPolicies": {
            policy: {
                "threshold": payload.get("threshold"),
                "testMetrics": compact_metrics(payload.get("testMetrics", {})),
            }
            for policy, payload in sorted(threshold_policies.items())
        },
        "topFeatureImportances": metrics.get("topFeatureImportances", [])[:30],
    }


def compact_metrics(metrics: dict[str, Any]) -> dict[str, Any]:
    keys = ["accuracy", "balancedAccuracy", "macroF1", "weightedF1", "precisionHigh", "recallHigh", "f1High"]
    return {key: metrics.get(key) for key in keys}


def reliability_summary(report: dict[str, Any] | None) -> dict[str, Any] | None:
    if not report:
        return None
    runs = []
    for run in report.get("runs", []):
        balanced = run.get("thresholdPolicies", {}).get("balanced", {}).get("testMetrics", {})
        runs.append(
            {
                "splitMode": run.get("splitMode"),
                "featureVariant": run.get("featureVariant"),
                "featureCount": run.get("featureCount"),
                "argmax": compact_metrics(run.get("argmaxMetrics", {})),
                "balancedPolicy": compact_metrics(balanced),
                "brierHigh": run.get("probabilityMetrics", {}).get("brierHigh"),
                "featureFamilyImportances": run.get("featureFamilyImportances", []),
            }
        )
    return {
        "model": report.get("model"),
        "nEstimators": report.get("nEstimators"),
        "recommendation": report.get("recommendation"),
        "runs": runs,
    }


def improvement_findings(analysis: dict[str, Any]) -> list[dict[str, Any]]:
    findings: list[dict[str, Any]] = []
    concentration = analysis["features"]["canyons"]
    if concentration["top100Share"] > 0.35 or (concentration["gini"] is not None and concentration["gini"] > 0.75):
        findings.append(
            {
                "area": "dataset_balance",
                "priority": "high",
                "finding": "Les observations sont fortement concentrées sur peu de canyons.",
                "evidence": f"Top 100 canyons = {concentration['top100Share']:.1%} des lignes; Gini = {concentration['gini']}.",
                "improvement": "Ajouter une pondération par canyon ou un échantillonnage groupé pour réduire la domination des canyons très observés.",
            }
        )

    conflicts = analysis["features"]["labelQuality"]
    if conflicts["sameDayConflicts"]["rowsInConflictingGroupsFraction"] > 0.005:
        findings.append(
            {
                "area": "label_quality",
                "priority": "high",
                "finding": "Une part non négligeable des labels est contradictoire le même jour pour le même canyon.",
                "evidence": f"{conflicts['sameDayConflicts']['rowsInConflictingGroupsFraction']:.2%} des lignes sont dans un groupe canyon/jour contradictoire.",
                "improvement": "Construire une cible agrégée canyon-jour avec consensus, pondération par confiance et suppression des contradictions LOW/HIGH fortes.",
            }
        )

    cold_gap = analysis.get("model", {}).get("coldStartGap")
    if cold_gap and cold_gap.get("balancedHighF1Gap", 0.0) > 0.05:
        findings.append(
            {
                "area": "generalization",
                "priority": "high",
                "finding": "La généralisation hors canyons connus reste nettement moins fiable que le split temporel.",
                "evidence": f"Écart HIGH F1 balanced temporal/cold-canyon = {cold_gap['balancedHighF1Gap']:.4f}.",
                "improvement": "Entraîner deux politiques ou deux modèles: known-canyon avec historique, cold-start sans historique canyon, sélectionnés par canyonPastObsCount/canyonHistoryConfidence.",
            }
        )

    coverage = analysis["features"]["featureCoverage"]
    if coverage["allMissingFeatureCount"] or coverage["constantOrSingleValueFeatureCount"]:
        findings.append(
            {
                "area": "feature_coverage",
                "priority": "medium",
                "finding": "Certaines features restent inutilisables ou constantes dans le corpus courant.",
                "evidence": f"All-missing = {coverage['allMissingFeatureCount']}; constantes = {coverage['constantOrSingleValueFeatureCount']}.",
                "improvement": "Garder l'audit de couverture dans chaque export et prioriser les descripteurs qui couvrent les canyons peu observés.",
            }
        )

    findings.append(
        {
            "area": "target_definition",
            "priority": "high",
            "finding": "La cible qualitative utilisateur limite la fidélité absolue du modèle.",
            "evidence": "Les classes mélangent débit physique, praticabilité perçue, saison, régulation et tolérance utilisateur.",
            "improvement": "Introduire une cible canyon-jour probabiliste ou ordinale, et intégrer des mesures hydrométriques/proxys locaux quand disponibles.",
        }
    )
    return findings


def compute_cold_start_gap(reliability: dict[str, Any] | None) -> dict[str, Any] | None:
    if not reliability:
        return None
    temporal_full = None
    cold_full = None
    for run in reliability.get("runs", []):
        if run.get("featureVariant") == "full" and run.get("splitMode") == "temporal":
            temporal_full = run
        if run.get("featureVariant") == "full" and run.get("splitMode") == "cold_canyon":
            cold_full = run
    if not temporal_full or not cold_full:
        return None
    temporal_f1 = temporal_full.get("balancedPolicy", {}).get("f1High") or 0.0
    cold_f1 = cold_full.get("balancedPolicy", {}).get("f1High") or 0.0
    return {
        "temporalFullBalancedHighF1": temporal_f1,
        "coldCanyonFullBalancedHighF1": cold_f1,
        "balancedHighF1Gap": round(temporal_f1 - cold_f1, 6),
    }


def markdown_value(value: Any) -> str:
    if value is None:
        return "n/a"
    if isinstance(value, float):
        return f"{value:.4f}"
    return str(value)


def write_markdown(path: Path, analysis: dict[str, Any]) -> None:
    lines: list[str] = []
    lines.extend(
        [
            "# Analyse End-To-End Du Modèle Débit",
            "",
            f"Généré le: `{analysis['generatedAt']}`",
            f"Features: `{analysis['inputs']['featuresPath']}`",
            f"Modèle: `{analysis['inputs']['modelMetricsPath']}`",
            "",
            "## Résumé Exécutif",
            "",
        ]
    )
    for finding in analysis["findings"]:
        lines.append(f"- **{finding['priority']} / {finding['area']}**: {finding['finding']} {finding['evidence']} Amélioration: {finding['improvement']}")

    labels = analysis["features"]["labels"]
    canyons = analysis["features"]["canyons"]
    history = analysis["features"]["history"]
    lines.extend(
        [
            "",
            "## Données",
            "",
            f"- Observations valides `{analysis['observations']['validCount']}`, incertaines `{analysis['observations']['uncertainCount']}`, invalides `{analysis['observations']['invalidCount']}`.",
            f"- Lignes features: `{analysis['features']['rowCount']}`; période `{analysis['features']['dateRange']['start']}` à `{analysis['features']['dateRange']['end']}`.",
            f"- Distribution 3 classes: HIGH `{labels['highFraction']:.2%}`, MEDIUM `{labels['mediumFraction']:.2%}`, LOW `{labels['lowFraction']:.2%}`.",
            f"- Canyons: `{canyons['canyonCount']}`; top 100 = `{canyons['top100Share']:.2%}` des observations; Gini `{canyons['gini']}`.",
            f"- Historique au moment de l'observation: médiane `{history['canyonPastObsCount']['median']}`, p90 `{history['canyonPastObsCount']['p90']}`.",
            "",
            "## Qualité Des Labels",
            "",
        ]
    )
    label_quality = analysis["features"]["labelQuality"]
    lines.append(f"- Conflits même canyon/jour: `{label_quality['sameDayConflicts']['conflictingGroupCount']}` groupes, `{label_quality['sameDayConflicts']['rowsInConflictingGroupsFraction']:.2%}` des lignes.")
    lines.append(f"- Contradictions LOW/HIGH à `{label_quality['nearTermContradictions']['windowDays']}` jours: `{label_quality['nearTermContradictions']['contradictionCount']}` paires, fraction `{label_quality['nearTermContradictions']['contradictionFraction']:.2%}`.")

    lines.extend(["", "## Couverture Features", ""])
    coverage = analysis["features"]["featureCoverage"]
    lines.append(f"- Features analysées: `{coverage['featureCount']}`; all-missing `{coverage['allMissingFeatureCount']}`; constantes `{coverage['constantOrSingleValueFeatureCount']}`.")
    for family, payload in coverage["families"].items():
        lines.append(f"- `{family}`: `{payload['featureCount']}` features, missing moyen `{payload['meanMissingFraction']:.2%}`.")

    lines.extend(["", "## Modèle", ""])
    model = analysis.get("model", {})
    export = model.get("export", {})
    lines.append(f"- Export mobile: `{export.get('featureCount')}` features, dropout historique canyon `{export.get('canyonHistoryDropoutRate')}`.")
    lines.append(f"- Argmax: macroF1 `{markdown_value(export.get('argmax', {}).get('macroF1'))}`, HIGH F1 `{markdown_value(export.get('argmax', {}).get('f1High'))}`, HIGH recall `{markdown_value(export.get('argmax', {}).get('recallHigh'))}`.")
    for policy, payload in export.get("thresholdPolicies", {}).items():
        metrics = payload.get("testMetrics", {})
        lines.append(f"- Policy `{policy}` seuil `{payload.get('threshold')}`: HIGH F1 `{markdown_value(metrics.get('f1High'))}`, precision `{markdown_value(metrics.get('precisionHigh'))}`, recall `{markdown_value(metrics.get('recallHigh'))}`.")

    reliability = model.get("reliability")
    if reliability:
        lines.extend(["", "## Ablations Et Généralisation", "", "| Split | Variante | HIGH F1 balanced | Recall HIGH | Brier HIGH |", "| --- | --- | ---: | ---: | ---: |"])
        for run in reliability.get("runs", []):
            metrics = run.get("balancedPolicy", {})
            lines.append(f"| {run.get('splitMode')} | {run.get('featureVariant')} | {markdown_value(metrics.get('f1High'))} | {markdown_value(metrics.get('recallHigh'))} | {markdown_value(run.get('brierHigh'))} |")

    lines.extend(["", "## Priorités D'Amélioration", ""])
    for index, finding in enumerate(analysis["findings"], start=1):
        lines.append(f"{index}. {finding['improvement']}")

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description="Analyze debit datasets, features and model reliability end-to-end")
    parser.add_argument("--observations-dir", default=DEFAULT_OBSERVATIONS_DIR)
    parser.add_argument("--features-path", default=DEFAULT_FEATURES_PATH)
    parser.add_argument("--model-metrics-path", default=DEFAULT_MODEL_METRICS_PATH)
    parser.add_argument("--reliability-report-path", default=DEFAULT_RELIABILITY_REPORT_PATH)
    parser.add_argument("--output-dir", default=DEFAULT_OUTPUT_DIR)
    args = parser.parse_args()

    observations_dir = Path(args.observations_dir)
    features_path = Path(args.features_path)
    model_metrics_path = Path(args.model_metrics_path)
    reliability_report_path = Path(args.reliability_report_path)

    observations_metadata = read_json(observations_dir / "metadata.json") if (observations_dir / "metadata.json").exists() else {}
    valid_observations = read_jsonl(observations_dir / "valid_debit_observations.jsonl") if (observations_dir / "valid_debit_observations.jsonl").exists() else []
    uncertain_observations = read_jsonl(observations_dir / "uncertain_debit_observations.jsonl") if (observations_dir / "uncertain_debit_observations.jsonl").exists() else []
    invalid_observations = read_jsonl(observations_dir / "invalid_debit_observations.jsonl") if (observations_dir / "invalid_debit_observations.jsonl").exists() else []
    feature_rows = [with_debit_derived_model_features(row) for row in read_jsonl(features_path)]
    model_metrics = read_json(model_metrics_path) if model_metrics_path.exists() else {}
    reliability_report = read_json(reliability_report_path) if reliability_report_path.exists() else None

    active_features = model_metrics.get("features") or NUMERIC_FEATURES
    reliability = reliability_summary(reliability_report)
    model_payload = {
        "export": model_summary(model_metrics),
        "reliability": reliability,
    }
    cold_start_gap = compute_cold_start_gap(reliability)
    if cold_start_gap:
        model_payload["coldStartGap"] = cold_start_gap

    analysis: dict[str, Any] = {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "inputs": {
            "observationsDir": str(observations_dir),
            "featuresPath": str(features_path),
            "modelMetricsPath": str(model_metrics_path),
            "reliabilityReportPath": str(reliability_report_path) if reliability_report_path.exists() else None,
        },
        "observations": {
            "metadata": observations_metadata,
            "validCount": len(valid_observations),
            "uncertainCount": len(uncertain_observations),
            "invalidCount": len(invalid_observations),
            "validLabels": label_distribution(valid_observations),
            "validDateRange": date_range(valid_observations),
        },
        "features": {
            "rowCount": len(feature_rows),
            "dateRange": date_range(feature_rows),
            "labels": label_distribution(feature_rows),
            "time": year_month_summaries(feature_rows),
            "canyons": canyon_distribution(feature_rows),
            "history": history_profile(feature_rows),
            "labelQuality": {
                "sameDayConflicts": same_day_conflicts(feature_rows),
                "nearTermContradictions": near_term_contradictions(feature_rows),
            },
            "descriptorCoverage": descriptor_profile(feature_rows),
            "featureCoverage": feature_coverage(feature_rows, active_features),
            "regions": regional_label_profile(feature_rows, "region"),
            "massifs": regional_label_profile(feature_rows, "massif"),
        },
        "model": model_payload,
    }
    analysis["findings"] = improvement_findings(analysis)

    output_dir = Path(args.output_dir)
    write_json(output_dir / "end_to_end_analysis.json", analysis)
    write_markdown(output_dir / "end_to_end_analysis.md", analysis)


if __name__ == "__main__":
    main()
