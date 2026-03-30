from __future__ import annotations

import argparse
import json
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from debit_pipeline_lib import write_json


NUMERIC_FEATURES = [
    "month",
    "monthSin",
    "monthCos",
    "altitudeDepartM",
    "deniveleM",
    "longueurM",
    "cascadeMaxM",
    "upstreamCatchmentAreaKm2",
    "precip_prev_day_mm",
    "precip_2d_mm",
    "precip_3d_mm",
    "precip_5d_mm",
    "precip_7d_mm",
    "precip_10d_mm",
    "precip_14d_mm",
    "precip_21d_mm",
    "precip_30d_mm",
    "max_daily_precip_3d_mm",
    "max_daily_precip_7d_mm",
    "max_daily_precip_14d_mm",
    "wet_days_7d",
    "wet_days_14d",
    "wet_days_30d",
    "days_since_precip_over_1mm",
    "days_since_precip_over_5mm",
    "days_since_precip_over_10mm",
    "antecedent_precipitation_index_daily",
    "antecedent_precipitation_index_daily_70",
    "antecedent_precipitation_index_daily_85",
    "antecedent_precipitation_index_daily_93",
    "temperature2mAtObservation",
    "temperature2mMeanPrevDay",
    "temperature2mMinPrevDay",
    "temperature2mMaxPrevDay",
    "temperature2mMean_3d",
    "temperature2mMean_7d",
    "temperature2mMean_14d",
    "temperature2mMinAtObservationDay",
    "temperature2mMaxAtObservationDay",
    "positive_degree_days_3d",
    "positive_degree_days_7d",
    "positive_degree_days_14d",
    "rain_prev_day_mm",
    "rain_3d_mm",
    "rain_7d_mm",
    "snowfall_prev_day_cm",
    "snowfall_3d_cm",
    "snowfall_7d_cm",
    "snowfall_14d_cm",
    "rainAtObservationDay",
    "snowfallAtObservationDay",
    "precipitation_hours_3d",
    "precipitation_hours_7d",
    "precipitation_hours_14d",
    "precipitationHoursAtObservationDay",
    "hasWatershed",
]


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            stripped = line.strip()
            if stripped:
                rows.append(json.loads(stripped))
    return rows


def target_three_classes(level: str) -> str | None:
    if level in {"SEC", "FILET"}:
        return "LOW"
    if level == "CORRECT":
        return "MEDIUM"
    if level in {"GROS", "TRES_GROS", "CRUE"}:
        return "HIGH"
    return None


def target_six_classes(level: str) -> str | None:
    return level if level in {"SEC", "FILET", "CORRECT", "GROS", "TRES_GROS", "CRUE"} else None


def row_to_numeric_vector(row: dict[str, Any]) -> list[float]:
    vector: list[float] = []
    for feature_name in NUMERIC_FEATURES:
        value = row.get(feature_name)
        vector.append(float(value) if value is not None else -9999.0)
    return vector


def top_feature_importances(feature_names: list[str], importances: list[float], limit: int = 20) -> list[dict[str, Any]]:
    return sorted(
        [
            {"feature": feature_name, "importance": float(importance)}
            for feature_name, importance in zip(feature_names, importances)
        ],
        key=lambda item: item["importance"],
        reverse=True,
    )[:limit]


def main() -> None:
    parser = argparse.ArgumentParser(description="Train a simple débit baseline model from generated features")
    parser.add_argument("--features-path", default="build/debit-pipeline/training-features/training_features.jsonl")
    parser.add_argument("--output-dir", default="build/debit-pipeline/model-baseline")
    parser.add_argument("--model", choices=["random_forest", "catboost"], default="random_forest")
    parser.add_argument("--target", choices=["three", "six"], default="three")
    parser.add_argument("--validation-fraction", type=float, default=0.2)
    args = parser.parse_args()

    try:
        from sklearn.ensemble import RandomForestClassifier
        from sklearn.metrics import accuracy_score, balanced_accuracy_score, classification_report, confusion_matrix, f1_score
    except ImportError as exc:  # pragma: no cover
        raise SystemExit(
            "scikit-learn is required for this script. Install it with `python -m pip install scikit-learn`."
        ) from exc

    CatBoostClassifier = None
    if args.model == "catboost":
        try:
            from catboost import CatBoostClassifier  # type: ignore
        except ImportError as exc:  # pragma: no cover
            raise SystemExit(
                "catboost is required for `--model catboost`. Install it with `python -m pip install catboost`."
            ) from exc

    rows = read_jsonl(Path(args.features_path))
    target_mapper = target_three_classes if args.target == "three" else target_six_classes
    filtered = [row for row in rows if target_mapper(row.get("niveau")) is not None and row.get("date")]
    filtered.sort(key=lambda row: row["date"])
    if len(filtered) < 50:
        raise SystemExit("Not enough rows to train a baseline model")

    split_index = max(1, int(len(filtered) * (1.0 - args.validation_fraction)))
    train_rows = filtered[:split_index]
    validation_rows = filtered[split_index:]
    if not validation_rows:
        raise SystemExit("Validation split is empty; lower --validation-fraction")

    x_train = [row_to_numeric_vector(row) for row in train_rows]
    y_train = [target_mapper(row["niveau"]) for row in train_rows]
    x_validation = [row_to_numeric_vector(row) for row in validation_rows]
    y_validation = [target_mapper(row["niveau"]) for row in validation_rows]

    if args.model == "catboost":
        model = CatBoostClassifier(
            iterations=500,
            depth=8,
            learning_rate=0.05,
            loss_function="MultiClass",
            eval_metric="TotalF1",
            random_seed=42,
            verbose=False,
            auto_class_weights="Balanced",
        )
    else:
        model = RandomForestClassifier(
            n_estimators=300,
            max_depth=12,
            min_samples_leaf=3,
            random_state=42,
            n_jobs=-1,
            class_weight="balanced_subsample",
        )

    model.fit(x_train, y_train)
    predictions = model.predict(x_validation)

    label_order = sorted(set(y_train) | set(y_validation))
    report = classification_report(y_validation, predictions, labels=label_order, output_dict=True, zero_division=0)
    confusion = confusion_matrix(y_validation, predictions, labels=label_order)
    if args.model == "catboost":
        importances = list(model.get_feature_importance())
    else:
        importances = list(model.feature_importances_)
    feature_importance = top_feature_importances(NUMERIC_FEATURES, importances)
    macro_f1 = f1_score(y_validation, predictions, labels=label_order, average="macro", zero_division=0)
    weighted_f1 = f1_score(y_validation, predictions, labels=label_order, average="weighted", zero_division=0)
    precision_high = report.get("HIGH", {}).get("precision")
    recall_high = report.get("HIGH", {}).get("recall")
    f1_high = report.get("HIGH", {}).get("f1-score")

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    write_json(
        output_dir / "metrics.json",
        {
            "schemaVersion": 1,
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "model": args.model,
            "targetMode": args.target,
            "trainRowCount": len(train_rows),
            "validationRowCount": len(validation_rows),
            "trainClassCounts": dict(sorted(Counter(y_train).items())),
            "validationClassCounts": dict(sorted(Counter(y_validation).items())),
            "accuracy": accuracy_score(y_validation, predictions),
            "balancedAccuracy": balanced_accuracy_score(y_validation, predictions),
            "macroF1": macro_f1,
            "weightedF1": weighted_f1,
            "precisionHigh": precision_high,
            "recallHigh": recall_high,
            "f1High": f1_high,
            "classificationReport": report,
            "labels": label_order,
            "confusionMatrix": confusion.tolist(),
            "topFeatureImportances": feature_importance,
        },
    )


if __name__ == "__main__":
    main()
