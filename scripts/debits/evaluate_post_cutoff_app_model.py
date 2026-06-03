from __future__ import annotations

import argparse
import json
import math
import time
from collections import Counter, defaultdict
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any
from urllib.parse import urlencode

import numpy as np

from pipeline_lib import (
    compute_daily_precipitation_features,
    compute_debit_derived_model_features,
    fetch_json,
    load_canyon_lookup,
    load_geo_points_lookup,
    load_watershed_lookup,
    write_json,
    write_jsonl,
)


DEFAULT_CUTOFF_DATE = "2026-03-25"
DEFAULT_OUTPUT_DIR = "build/debit-pipeline/post-cutoff-app-model-evaluation"
DEFAULT_DAILY_VARIABLES = [
    "precipitation_sum",
    "rain_sum",
    "snowfall_sum",
    "temperature_2m_mean",
    "temperature_2m_min",
    "temperature_2m_max",
    "precipitation_hours",
]
LEVEL_TO_THREE = {
    "SEC": "LOW",
    "FILET": "LOW",
    "CORRECT": "MEDIUM",
    "GROS": "HIGH",
    "TRES_GROS": "HIGH",
    "CRUE": "HIGH",
}
LEVEL_TO_RANK = {
    "SEC": 0,
    "FILET": 1,
    "CORRECT": 2,
    "GROS": 3,
    "TRES_GROS": 4,
    "CRUE": 5,
}
RANK_TO_LEVEL = ["SEC", "FILET", "CORRECT", "GROS", "TRES_GROS", "CRUE"]
THREE_LABELS = ["LOW", "MEDIUM", "HIGH"]
ORDINAL_RANK_BY_LABEL = {
    "SEC": 0.0,
    "FILET": 1.0,
    "CORRECT": 2.0,
    "GROS": 3.0,
    "TRES_GROS": 4.0,
    "CRUE": 5.0,
}
WEATHER_PRIORITY_BY_POINT_TYPE = {
    "ENTREE": 0,
    "PARKING_AMONT": 1,
    "SORTIE": 2,
    "PARKING_AVAL": 3,
    "POINT_REMARQUABLE": 4,
    "ECHAPPATOIRE": 5,
    "UNKNOWN": 6,
}
WEATHER_SOURCE_BY_POINT_TYPE = {
    "ENTREE": "ENTRY",
    "PARKING_AMONT": "UPSTREAM_PARKING",
    "SORTIE": "EXIT",
    "PARKING_AVAL": "DOWNSTREAM_PARKING",
    "POINT_REMARQUABLE": "REMARKABLE_POINT",
    "ECHAPPATOIRE": "ESCAPE",
    "UNKNOWN": "UNKNOWN",
}


def read_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            stripped = line.strip()
            if stripped:
                rows.append(json.loads(stripped))
    return rows


def numeric_value(value: Any) -> float | None:
    if value is None:
        return None
    if isinstance(value, bool):
        return 1.0 if value else 0.0
    if isinstance(value, (int, float)):
        number = float(value)
        return number if math.isfinite(number) else None
    return None


def round_to(value: float, decimals: int) -> float:
    return round(value, decimals)


def computed_temporal_features(target_date: date) -> dict[str, float]:
    angle = 2.0 * math.pi * (target_date.month - 1) / 12.0
    return {
        "month": float(target_date.month),
        "monthSin": round_to(math.sin(angle), 6),
        "monthCos": round_to(math.cos(angle), 6),
    }


MONTH_LOOKUP_FEATURES = [
    "canyonMonthPastObsCount",
    "canyonMonthPriorLow",
    "canyonMonthPriorMedium",
    "canyonMonthPriorHigh",
    "canyonMonthMeanRank",
    "massifMonthPastObsCount",
    "massifMonthPriorLow",
    "massifMonthPriorMedium",
    "massifMonthPriorHigh",
    "regionMonthPastObsCount",
    "regionMonthPriorLow",
    "regionMonthPriorMedium",
    "regionMonthPriorHigh",
]
SEASON_LOOKUP_FEATURES = [
    "canyonSeasonPastObsCount",
    "canyonSeasonPriorLow",
    "canyonSeasonPriorMedium",
    "canyonSeasonPriorHigh",
    "canyonSeasonMeanRank",
    "massifSeasonPastObsCount",
    "massifSeasonPriorLow",
    "massifSeasonPriorMedium",
    "massifSeasonPriorHigh",
    "regionSeasonPastObsCount",
    "regionSeasonPriorLow",
    "regionSeasonPriorMedium",
    "regionSeasonPriorHigh",
]


def season_key(month: int) -> str:
    if month in {12, 1, 2}:
        return "winter"
    if month in {3, 4, 5}:
        return "spring"
    if month in {6, 7, 8}:
        return "summer"
    return "autumn"


def temporal_history_lookup_features(lookup_values: dict[str, float], target_date: date) -> dict[str, float | None]:
    month = str(target_date.month)
    season = season_key(target_date.month)
    values: dict[str, float | None] = {}
    for feature_name in MONTH_LOOKUP_FEATURES:
        values[feature_name] = lookup_values.get(f"month.{month}.{feature_name}")
    for feature_name in SEASON_LOOKUP_FEATURES:
        values[feature_name] = lookup_values.get(f"season.{season}.{feature_name}")
    last_epoch_day = lookup_values.get("canyonLastObservationEpochDay")
    if last_epoch_day is not None:
        values["canyonDaysSinceLastObs"] = max(float(target_date.toordinal()) - float(last_epoch_day), 0.0)
    return values


def resolve_runtime_lookup(canyon: dict[str, Any], lookups: dict[str, Any]) -> tuple[str, dict[str, float]]:
    unknown_keys = lookups.get("unknownKeys") or {}
    resolved_region = canyon.get("region") or unknown_keys.get("region") or "__UNKNOWN_REGION__"
    resolved_massif = canyon.get("massif") or unknown_keys.get("massif") or "__UNKNOWN_MASSIF__"
    canyon_entry = (lookups.get("canyons") or {}).get(str(canyon["id"]))
    region_values = (lookups.get("regions") or {}).get(resolved_region) or {}
    massif_values = (lookups.get("massifs") or {}).get(resolved_massif) or {}

    feature_values: dict[str, float] = {}
    for source in (lookups.get("defaults") or {}, lookups.get("global") or {}, region_values, massif_values):
        for key, value in source.items():
            number = numeric_value(value)
            if number is not None:
                feature_values[key] = number
    if canyon_entry is not None:
        for key, value in canyon_entry.items():
            number = numeric_value(value)
            if number is not None:
                feature_values[key] = number

    if canyon_entry is not None:
        source_name = "CANYON"
    elif massif_values:
        source_name = "MASSIF"
    elif region_values:
        source_name = "REGION"
    else:
        source_name = "GLOBAL"
    return source_name, feature_values


def resolve_weather_target(
    canyon_id: int,
    watersheds: dict[int, dict[str, Any]],
    geo_points: dict[int, list[dict[str, Any]]],
) -> dict[str, Any] | None:
    watershed = watersheds.get(canyon_id)
    bbox = watershed.get("bbox") if watershed else None
    if isinstance(bbox, list) and len(bbox) == 4:
        min_lon, min_lat, max_lon, max_lat = [float(value) for value in bbox]
        return {
            "latitude": (min_lat + max_lat) / 2.0,
            "longitude": (min_lon + max_lon) / 2.0,
            "source": "WATERSHED_CENTER",
        }

    points = geo_points.get(canyon_id) or []
    usable = [point for point in points if point.get("latitude") is not None and point.get("longitude") is not None]
    if not usable:
        return None
    point = min(usable, key=lambda item: WEATHER_PRIORITY_BY_POINT_TYPE.get(item.get("type") or "UNKNOWN", 6))
    point_type = point.get("type") or "UNKNOWN"
    return {
        "latitude": float(point["latitude"]),
        "longitude": float(point["longitude"]),
        "source": WEATHER_SOURCE_BY_POINT_TYPE.get(point_type, "UNKNOWN"),
    }


def open_meteo_archive_url(
    *,
    latitude: float,
    longitude: float,
    start_date: str,
    end_date: str,
    daily_variables: list[str],
    include_model_param: bool,
    model: str,
) -> str:
    params: dict[str, Any] = {
        "latitude": latitude,
        "longitude": longitude,
        "start_date": start_date,
        "end_date": end_date,
        "daily": ",".join(daily_variables),
        "timezone": "auto",
    }
    if include_model_param:
        params["models"] = model
    return f"https://archive-api.open-meteo.com/v1/archive?{urlencode(params)}"


def flatten_daily_weather(payload: dict[str, Any], target_id: str) -> list[dict[str, Any]]:
    daily = payload.get("daily") or {}
    dates = daily.get("time") or []
    variable_names = [name for name in daily.keys() if name != "time"]
    rows: list[dict[str, Any]] = []
    for index, raw_date in enumerate(dates):
        row = {
            "targetId": target_id,
            "date": raw_date,
            "timezone": payload.get("timezone"),
            "resolvedLatitude": payload.get("latitude"),
            "resolvedLongitude": payload.get("longitude"),
            "resolvedElevation": payload.get("elevation"),
        }
        for name in variable_names:
            values = daily.get(name) or []
            row[name] = values[index] if index < len(values) else None
        rows.append(row)
    return rows


def load_or_fetch_weather(
    *,
    target: dict[str, Any],
    observation_date: str,
    cache_dir: Path,
    request_delay_seconds: float,
    daily_variables: list[str],
    include_model_param: bool,
    weather_model: str,
    timeout_seconds: int,
) -> tuple[list[dict[str, Any]], bool]:
    target_day = date.fromisoformat(observation_date)
    start = (target_day - timedelta(days=30)).isoformat()
    end = (target_day - timedelta(days=1)).isoformat()
    target_id = f"{target['latitude']:.6f},{target['longitude']:.6f}"
    cache_key = f"{target['latitude']:.6f}_{target['longitude']:.6f}_{start}_{end}_{'model-' + weather_model if include_model_param else 'app-default'}"
    cache_path = cache_dir / f"{cache_key.replace('-', '').replace(',', '_')}.json"
    url = open_meteo_archive_url(
        latitude=target["latitude"],
        longitude=target["longitude"],
        start_date=start,
        end_date=end,
        daily_variables=daily_variables,
        include_model_param=include_model_param,
        model=weather_model,
    )
    if not cache_path.exists() and request_delay_seconds > 0:
        time.sleep(request_delay_seconds)
    payload = fetch_json(url, timeout=timeout_seconds, cache_path=cache_path)
    return flatten_daily_weather(payload, target_id), cache_path.exists()


def latest_weather_before(daily_rows: list[dict[str, Any]], observation_date: str) -> dict[str, Any] | None:
    target_day = date.fromisoformat(observation_date)
    eligible = [row for row in daily_rows if date.fromisoformat(row["date"]) < target_day]
    if not eligible:
        return None
    return sorted(eligible, key=lambda row: row["date"])[-1]


def build_feature_values(
    *,
    observation: dict[str, Any],
    canyon: dict[str, Any],
    daily_rows: list[dict[str, Any]],
    static_features: dict[str, Any] | None,
    lookup_values: dict[str, float],
) -> dict[str, Any]:
    target_day = date.fromisoformat(observation["date"])
    values: dict[str, Any] = {}
    values.update(computed_temporal_features(target_day))
    values.update(static_features or {})
    values.update(compute_daily_precipitation_features(daily_rows, observation["date"]))
    previous_day = latest_weather_before(daily_rows, observation["date"])
    if previous_day is not None:
        values.update(
            {
                "temperature2mAtObservation": previous_day.get("temperature_2m_mean"),
                "temperature2mMinAtObservationDay": previous_day.get("temperature_2m_min"),
                "temperature2mMaxAtObservationDay": previous_day.get("temperature_2m_max"),
                "rainAtObservationDay": previous_day.get("rain_sum"),
                "snowfallAtObservationDay": previous_day.get("snowfall_sum"),
                "precipitationHoursAtObservationDay": previous_day.get("precipitation_hours"),
            }
        )
    values.update(lookup_values)
    values.update(temporal_history_lookup_features(lookup_values, target_day))
    values.update(compute_debit_derived_model_features(values))
    return values


def feature_vector_from_values(feature_values: dict[str, Any], feature_spec: dict[str, Any]) -> np.ndarray:
    vector: list[float] = []
    for feature in feature_spec["features"]:
        value = numeric_value(feature_values.get(feature["name"]))
        if value is None:
            value = float(feature["default"])
        vector.append(value)
    return np.asarray([vector], dtype=np.float32)


def feature_row_from_values(
    *,
    observation: dict[str, Any],
    canyon: dict[str, Any],
    target: dict[str, Any],
    lookup_source: str,
    feature_values: dict[str, Any],
) -> dict[str, Any]:
    row = {
        "observationId": observation.get("observationId"),
        "canyonId": int(observation["canyonId"]),
        "canyonName": observation.get("canyonName") or canyon.get("nom"),
        "date": observation.get("date"),
        "assumedObservationTimeLocal": observation.get("assumedObservationTimeLocal"),
        "niveau": observation.get("niveau"),
        "niveauRank": observation.get("niveauRank"),
        "isDescended": observation.get("isDescended"),
        "targetId": f"post_cutoff_{target['latitude']:.6f}_{target['longitude']:.6f}",
        "targetSource": target.get("source"),
        "targetLatitude": target.get("latitude"),
        "targetLongitude": target.get("longitude"),
        "country": canyon.get("pays"),
        "region": canyon.get("region"),
        "departement": canyon.get("departement"),
        "massif": canyon.get("massif"),
        "bassin": canyon.get("bassin"),
        "commentText": observation.get("comment"),
        "lookupSource": lookup_source,
        "source": observation.get("source") or "descente-canyon",
    }
    row.update(feature_values)
    return row


def extract_probability_map(outputs: list[Any], labels: list[str]) -> dict[str, float]:
    for output in outputs:
        if isinstance(output, list) and output and isinstance(output[0], dict):
            return {str(key): float(value) for key, value in output[0].items()}
        if isinstance(output, dict):
            return {str(key): float(value) for key, value in output.items()}
        if isinstance(output, np.ndarray):
            if output.dtype.kind in {"f", "c"}:
                raw = output[0] if output.ndim > 1 else output
                if len(raw) == len(labels):
                    return {label: float(raw[index]) for index, label in enumerate(labels)}
    raise ValueError(f"No probability output found in ONNX outputs: {[type(output).__name__ for output in outputs]}")


def normalized_three_probabilities(probabilities: dict[str, float]) -> dict[str, float]:
    if all(label in probabilities for label in ORDINAL_RANK_BY_LABEL):
        return {
            "LOW": probabilities.get("SEC", 0.0) + probabilities.get("FILET", 0.0),
            "MEDIUM": probabilities.get("CORRECT", 0.0),
            "HIGH": probabilities.get("GROS", 0.0) + probabilities.get("TRES_GROS", 0.0) + probabilities.get("CRUE", 0.0),
        }
    return {label: probabilities.get(label, 0.0) for label in THREE_LABELS}


def ordinal_score(probabilities: dict[str, float]) -> float | None:
    if not all(label in probabilities for label in ORDINAL_RANK_BY_LABEL):
        return None
    return sum(probabilities[label] * rank for label, rank in ORDINAL_RANK_BY_LABEL.items())


def ordinal_level(score: float | None) -> str | None:
    if score is None:
        return None
    return RANK_TO_LEVEL[int(round(score)) if 0 <= round(score) <= 5 else max(0, min(5, int(round(score))))]


def predicted_three_label(
    *,
    probabilities: dict[str, float],
    score: float | None,
    thresholds: dict[str, Any],
) -> str:
    policy_name = thresholds.get("defaultPolicy") or "balanced"
    policy = thresholds["policies"][policy_name]
    high_threshold = float(policy["highThreshold"])
    low_threshold = policy.get("lowThreshold")
    if score is not None and low_threshold is not None:
        if score >= high_threshold:
            return "HIGH"
        if score < float(low_threshold):
            return "LOW"
        return "MEDIUM"
    normalized = normalized_three_probabilities(probabilities)
    if normalized["HIGH"] >= high_threshold:
        return "HIGH"
    return "LOW" if normalized["LOW"] >= normalized["MEDIUM"] else "MEDIUM"


def confusion_matrix(rows: list[dict[str, Any]]) -> dict[str, dict[str, int]]:
    matrix = {truth: {predicted: 0 for predicted in THREE_LABELS} for truth in THREE_LABELS}
    for row in rows:
        matrix[row["actualThree"]][row["predictedThree"]] += 1
    return matrix


def classification_metrics(rows: list[dict[str, Any]]) -> dict[str, Any]:
    matrix = confusion_matrix(rows)
    total = len(rows)
    correct = sum(matrix[label][label] for label in THREE_LABELS)
    per_label: dict[str, Any] = {}
    recalls: list[float] = []
    f1s: list[float] = []
    for label in THREE_LABELS:
        tp = matrix[label][label]
        support = sum(matrix[label].values())
        predicted_count = sum(matrix[truth][label] for truth in THREE_LABELS)
        precision = tp / predicted_count if predicted_count else 0.0
        recall = tp / support if support else 0.0
        f1 = 2.0 * precision * recall / (precision + recall) if precision + recall else 0.0
        per_label[label] = {"precision": precision, "recall": recall, "f1": f1, "support": support, "predicted": predicted_count}
        recalls.append(recall)
        f1s.append(f1)
    return {
        "rowCount": total,
        "accuracy": correct / total if total else None,
        "balancedAccuracy": sum(recalls) / len(recalls) if recalls else None,
        "macroF1": sum(f1s) / len(f1s) if f1s else None,
        "perLabel": per_label,
        "confusionMatrix": matrix,
    }


def ordinal_metrics(rows: list[dict[str, Any]]) -> dict[str, Any]:
    errors = [float(row["predictedOrdinalScore"]) - float(row["actualRank"]) for row in rows if row.get("predictedOrdinalScore") is not None]
    absolute = [abs(error) for error in errors]
    return {
        "maeRank": sum(absolute) / len(absolute) if absolute else None,
        "rmseRank": math.sqrt(sum(error * error for error in errors) / len(errors)) if errors else None,
        "meanSignedErrorRank": sum(errors) / len(errors) if errors else None,
        "severeOrdinalErrorFraction": sum(1 for error in absolute if error >= 2.0) / len(absolute) if absolute else None,
    }


def calibration_bins(rows: list[dict[str, Any]], *, bins: int = 5) -> list[dict[str, Any]]:
    buckets: list[list[dict[str, Any]]] = [[] for _ in range(bins)]
    for row in rows:
        probability = float(row["probabilityHigh"])
        index = min(bins - 1, max(0, int(probability * bins)))
        buckets[index].append(row)
    result = []
    for index, bucket in enumerate(buckets):
        result.append(
            {
                "binStart": index / bins,
                "binEnd": (index + 1) / bins,
                "count": len(bucket),
                "meanPredictedHighProbability": sum(float(row["probabilityHigh"]) for row in bucket) / len(bucket) if bucket else None,
                "actualHighFraction": sum(1 for row in bucket if row["actualThree"] == "HIGH") / len(bucket) if bucket else None,
            }
        )
    return result


def grouped_metrics(rows: list[dict[str, Any]], key: str, *, min_count: int = 20) -> dict[str, Any]:
    grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        grouped[str(row.get(key) or "UNKNOWN")].append(row)
    result: dict[str, Any] = {}
    for value, group_rows in sorted(grouped.items(), key=lambda item: (-len(item[1]), item[0])):
        if len(group_rows) < min_count:
            continue
        metrics = classification_metrics(group_rows)
        ordinal = ordinal_metrics(group_rows)
        result[value] = {
            "rowCount": len(group_rows),
            "accuracy": metrics["accuracy"],
            "balancedAccuracy": metrics["balancedAccuracy"],
            "macroF1": metrics["macroF1"],
            "highPrecision": metrics["perLabel"]["HIGH"]["precision"],
            "highRecall": metrics["perLabel"]["HIGH"]["recall"],
            "highF1": metrics["perLabel"]["HIGH"]["f1"],
            "ordinalMaeRank": ordinal["maeRank"],
        }
    return result


def month_key(raw_date: str) -> str:
    return raw_date[:7]


def markdown_table(headers: list[str], rows: list[list[Any]]) -> str:
    def cell(value: Any) -> str:
        if isinstance(value, float):
            return f"{value:.4f}"
        if value is None:
            return "-"
        return str(value)

    lines = ["| " + " | ".join(headers) + " |", "| " + " | ".join("---" for _ in headers) + " |"]
    lines.extend("| " + " | ".join(cell(value) for value in row) + " |" for row in rows)
    return "\n".join(lines)


def write_markdown_report(path: Path, report: dict[str, Any], worst_errors: list[dict[str, Any]]) -> None:
    metrics = report["metrics"]
    per_label = metrics["perLabel"]
    lines = [
        "# Post-Cutoff Debit Model Evaluation",
        "",
        f"- Cutoff date: `{report['cutoffDate']}`",
        f"- Source: `{report['source']}`",
        f"- Scenario: `{report['scenario']}`",
        f"- Evaluated observations: `{report['evaluatedObservationCount']}`",
        f"- Skipped observations: `{report['skippedObservationCount']}`",
        f"- Distinct canyons: `{report['distinctCanyonCount']}`",
        f"- Date range: `{report['dateRange'][0]}` -> `{report['dateRange'][1]}`",
        "",
        "## Overall Metrics",
        "",
        markdown_table(
            ["Metric", "Value"],
            [
                ["Accuracy", metrics["accuracy"]],
                ["Balanced accuracy", metrics["balancedAccuracy"]],
                ["Macro F1", metrics["macroF1"]],
                ["Ordinal MAE", report["ordinalMetrics"]["maeRank"]],
                ["Ordinal RMSE", report["ordinalMetrics"]["rmseRank"]],
                ["Severe ordinal errors >= 2", report["ordinalMetrics"]["severeOrdinalErrorFraction"]],
            ],
        ),
        "",
        "## Per-Class Metrics",
        "",
        markdown_table(
            ["Class", "Precision", "Recall", "F1", "Support", "Predicted"],
            [
                [label, per_label[label]["precision"], per_label[label]["recall"], per_label[label]["f1"], per_label[label]["support"], per_label[label]["predicted"]]
                for label in THREE_LABELS
            ],
        ),
        "",
        "## Confusion Matrix",
        "",
        markdown_table(
            ["Actual \\ Pred", *THREE_LABELS],
            [[label, *[metrics["confusionMatrix"][label][predicted] for predicted in THREE_LABELS]] for label in THREE_LABELS],
        ),
        "",
        "## HIGH Calibration Bins",
        "",
        markdown_table(
            ["Bin", "Count", "Mean predicted HIGH", "Actual HIGH fraction"],
            [
                [f"{item['binStart']:.1f}-{item['binEnd']:.1f}", item["count"], item["meanPredictedHighProbability"], item["actualHighFraction"]]
                for item in report["highCalibrationBins"]
            ],
        ),
        "",
        "## Segment Metrics",
        "",
        "### By Country",
        "",
        markdown_table(
            ["Country", "Count", "Accuracy", "Balanced acc.", "HIGH F1", "Ordinal MAE"],
            [
                [key, value["rowCount"], value["accuracy"], value["balancedAccuracy"], value["highF1"], value["ordinalMaeRank"]]
                for key, value in report["segmentMetrics"]["country"].items()
            ],
        ),
        "",
        "### By Lookup Source",
        "",
        markdown_table(
            ["Lookup", "Count", "Accuracy", "Balanced acc.", "HIGH F1", "Ordinal MAE"],
            [
                [key, value["rowCount"], value["accuracy"], value["balancedAccuracy"], value["highF1"], value["ordinalMaeRank"]]
                for key, value in report["segmentMetrics"]["lookupSource"].items()
            ],
        ),
        "",
        "### By Month",
        "",
        markdown_table(
            ["Month", "Count", "Accuracy", "Balanced acc.", "HIGH F1", "Ordinal MAE"],
            [
                [key, value["rowCount"], value["accuracy"], value["balancedAccuracy"], value["highF1"], value["ordinalMaeRank"]]
                for key, value in report["segmentMetrics"]["month"].items()
            ],
        ),
        "",
        "## Worst Ordinal Errors",
        "",
        markdown_table(
            ["Canyon", "Date", "Actual", "Predicted", "Score", "P(HIGH)", "Error"],
            [
                [
                    row.get("canyonName"),
                    row.get("date"),
                    row.get("actualLevel"),
                    row.get("predictedOrdinalLevel") or row.get("predictedThree"),
                    row.get("predictedOrdinalScore"),
                    row.get("probabilityHigh"),
                    row.get("ordinalError"),
                ]
                for row in worst_errors
            ],
        ),
        "",
    ]
    path.write_text("\n".join(lines), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate the embedded app debit model on post-cutoff Descente-Canyon observations")
    parser.add_argument("--cutoff-date", default=DEFAULT_CUTOFF_DATE)
    parser.add_argument("--observations-path", default="build/debit-pipeline/observations/valid_debit_observations.jsonl")
    parser.add_argument("--model-dir", default="modele_statistique")
    parser.add_argument("--thresholds-path", help="Override thresholds JSON path instead of <model-dir>/thresholds.json")
    parser.add_argument("--canyons-path", default="offline-data/full/room-import/canyons.json")
    parser.add_argument("--watersheds-path", default="offline-data/full/room-import/watersheds.json")
    parser.add_argument("--geo-points-path", default="offline-data/full/room-import/geo_points.json")
    parser.add_argument("--output-dir", default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--weather-model", default="era5_land")
    parser.add_argument("--include-weather-model-param", action="store_true", help="Use Open-Meteo models=... like the training archive instead of app-default archive requests")
    parser.add_argument("--weather-cache-dir", help="Reuse or write Open-Meteo daily archive cache in this directory")
    parser.add_argument("--write-feature-rows", action="store_true", help="Also write post-cutoff feature rows usable by model export scripts")
    parser.add_argument("--request-delay-ms", type=int, default=1200)
    parser.add_argument("--timeout-s", type=int, default=120)
    args = parser.parse_args()

    try:
        import onnxruntime as ort  # type: ignore
    except ImportError as exc:  # pragma: no cover
        raise SystemExit("onnxruntime is required. Install it with `.venv/bin/python -m pip install onnxruntime`.") from exc

    cutoff = args.cutoff_date
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    weather_cache_dir = Path(args.weather_cache_dir) if args.weather_cache_dir else output_dir / "weather-cache"
    weather_cache_dir.mkdir(parents=True, exist_ok=True)

    model_dir = Path(args.model_dir)
    feature_spec = read_json(model_dir / "feature_spec.json")
    thresholds = read_json(Path(args.thresholds_path) if args.thresholds_path else model_dir / "thresholds.json")
    runtime_lookups = read_json(model_dir / "runtime_feature_lookups.json")
    static_features = read_json(model_dir / "canyon_static_features.json")
    labels = [str(label) for label in feature_spec.get("labels", [])]

    canyons = load_canyon_lookup(Path(args.canyons_path))
    watersheds = load_watershed_lookup(Path(args.watersheds_path))
    geo_points = load_geo_points_lookup(Path(args.geo_points_path))
    observations = [row for row in read_jsonl(Path(args.observations_path)) if row.get("date") and row.get("date") > cutoff]
    observations = [row for row in observations if (row.get("source") or "descente-canyon") == "descente-canyon"]
    observations = [row for row in observations if row.get("niveau") in LEVEL_TO_THREE]
    observations.sort(key=lambda row: (row["date"], int(row.get("canyonId") or 0), row.get("observationId") or ""))

    session = ort.InferenceSession(str(model_dir / "model.onnx"), providers=["CPUExecutionProvider"])
    input_name = session.get_inputs()[0].name

    predictions: list[dict[str, Any]] = []
    feature_rows: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []
    for index, observation in enumerate(observations, start=1):
        canyon_id = int(observation["canyonId"])
        canyon = canyons.get(canyon_id)
        if canyon is None:
            skipped.append({"observationId": observation.get("observationId"), "canyonId": canyon_id, "reason": "unknown_canyon"})
            continue
        target = resolve_weather_target(canyon_id, watersheds, geo_points)
        if target is None:
            skipped.append({"observationId": observation.get("observationId"), "canyonId": canyon_id, "reason": "missing_weather_target"})
            continue
        try:
            daily_rows, _ = load_or_fetch_weather(
                target=target,
                observation_date=observation["date"],
                cache_dir=weather_cache_dir,
                request_delay_seconds=max(args.request_delay_ms, 0) / 1000.0,
                daily_variables=DEFAULT_DAILY_VARIABLES,
                include_model_param=args.include_weather_model_param,
                weather_model=args.weather_model,
                timeout_seconds=args.timeout_s,
            )
            if not daily_rows:
                skipped.append({"observationId": observation.get("observationId"), "canyonId": canyon_id, "reason": "missing_weather_rows"})
                continue
            lookup_source, lookup_values = resolve_runtime_lookup(canyon, runtime_lookups)
            feature_values = build_feature_values(
                observation=observation,
                canyon=canyon,
                daily_rows=daily_rows,
                static_features=static_features.get(str(canyon_id)),
                lookup_values=lookup_values,
            )
            vector = feature_vector_from_values(feature_values, feature_spec)
            outputs = session.run(None, {input_name: vector})
            probabilities_by_label = extract_probability_map(outputs, labels)
            three_probabilities = normalized_three_probabilities(probabilities_by_label)
            score = ordinal_score(probabilities_by_label)
            predicted_three = predicted_three_label(probabilities=probabilities_by_label, score=score, thresholds=thresholds)
            actual_level = observation["niveau"]
            actual_rank = LEVEL_TO_RANK[actual_level]
            ordinal_error = (score - actual_rank) if score is not None else None
            predictions.append(
                {
                    "observationId": observation.get("observationId"),
                    "sourceUrl": observation.get("sourceUrl"),
                    "canyonId": canyon_id,
                    "canyonName": observation.get("canyonName") or canyon.get("nom"),
                    "country": canyon.get("pays"),
                    "region": canyon.get("region"),
                    "massif": canyon.get("massif"),
                    "date": observation["date"],
                    "month": month_key(observation["date"]),
                    "actualLevel": actual_level,
                    "actualThree": LEVEL_TO_THREE[actual_level],
                    "actualRank": actual_rank,
                    "predictedThree": predicted_three,
                    "predictedOrdinalScore": score,
                    "predictedOrdinalLevel": ordinal_level(score),
                    "ordinalError": ordinal_error,
                    "absoluteOrdinalError": abs(ordinal_error) if ordinal_error is not None else None,
                    "probabilityLow": three_probabilities.get("LOW", 0.0),
                    "probabilityMedium": three_probabilities.get("MEDIUM", 0.0),
                    "probabilityHigh": three_probabilities.get("HIGH", 0.0),
                    "probabilitiesByLabel": probabilities_by_label,
                    "lookupSource": lookup_source,
                    "weatherTarget": target,
                    "comment": observation.get("comment"),
                    "primaryAuthor": observation.get("primaryAuthor"),
                }
            )
            if args.write_feature_rows:
                feature_rows.append(
                    feature_row_from_values(
                        observation=observation,
                        canyon=canyon,
                        target=target,
                        lookup_source=lookup_source,
                        feature_values=feature_values,
                    )
                )
        except Exception as exc:  # noqa: BLE001
            skipped.append({"observationId": observation.get("observationId"), "canyonId": canyon_id, "reason": repr(exc)})
        if index % 25 == 0:
            print(f"Processed {index}/{len(observations)} observations | predictions={len(predictions)} | skipped={len(skipped)}")

    if not predictions:
        write_json(output_dir / "skipped_observations.json", skipped)
        raise SystemExit("No post-cutoff Descente-Canyon observations could be evaluated")

    worst_errors = sorted(
        predictions,
        key=lambda row: (row.get("absoluteOrdinalError") or 0.0, row.get("probabilityHigh") or 0.0),
        reverse=True,
    )[:20]
    by_lookup_source = Counter(row["lookupSource"] for row in predictions)
    by_actual = Counter(row["actualLevel"] for row in predictions)
    by_predicted = Counter(row["predictedThree"] for row in predictions)
    by_country = Counter(row.get("country") or "UNKNOWN" for row in predictions)
    dates = [row["date"] for row in predictions]
    report = {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "cutoffDate": cutoff,
        "source": "descente-canyon",
        "scenario": "app_like_target_day_weather_before_target_day",
        "weatherArchiveMode": f"training_{args.weather_model}_models_param" if args.include_weather_model_param else "app_default_no_models_param",
        "modelDir": str(model_dir),
        "modelLabels": labels,
        "thresholdPolicy": thresholds.get("defaultPolicy"),
        "thresholds": thresholds.get("policies", {}).get(thresholds.get("defaultPolicy")),
        "candidateObservationCount": len(observations),
        "evaluatedObservationCount": len(predictions),
        "skippedObservationCount": len(skipped),
        "distinctCanyonCount": len({row["canyonId"] for row in predictions}),
        "dateRange": [min(dates), max(dates)],
        "actualLevelCounts": dict(sorted(by_actual.items())),
        "predictedThreeCounts": dict(sorted(by_predicted.items())),
        "lookupSourceCounts": dict(sorted(by_lookup_source.items())),
        "countryCounts": dict(sorted(by_country.items())),
        "metrics": classification_metrics(predictions),
        "ordinalMetrics": ordinal_metrics(predictions),
        "highCalibrationBins": calibration_bins(predictions),
        "segmentMetrics": {
            "country": grouped_metrics(predictions, "country", min_count=20),
            "lookupSource": grouped_metrics(predictions, "lookupSource", min_count=1),
            "month": grouped_metrics(predictions, "month", min_count=20),
        },
        "worstErrors": worst_errors,
        "skippedReasons": dict(sorted(Counter(row["reason"] for row in skipped).items())),
        "files": {
            "predictions": "predictions.jsonl",
            "skipped": "skipped_observations.json",
            "reportJson": "reliability_post_cutoff_report.json",
            "reportMarkdown": "reliability_post_cutoff_report.md",
        },
    }
    write_jsonl(output_dir / "predictions.jsonl", predictions)
    if args.write_feature_rows:
        write_jsonl(output_dir / "post_cutoff_feature_rows.jsonl", feature_rows)
    write_json(output_dir / "skipped_observations.json", skipped)
    write_json(output_dir / "reliability_post_cutoff_report.json", report)
    write_markdown_report(output_dir / "reliability_post_cutoff_report.md", report, worst_errors)
    print(f"Wrote {output_dir / 'reliability_post_cutoff_report.md'}")


if __name__ == "__main__":
    main()
