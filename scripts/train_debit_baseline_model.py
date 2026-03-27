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
    "altitudeDepartM",
    "deniveleM",
    "longueurM",
    "cascadeMaxM",
    "upstreamCatchmentAreaKm2",
    "precip_prev_day_mm",
    "precip_3d_mm",
    "precip_7d_mm",
    "precip_14d_mm",
    "max_daily_precip_7d_mm",
    "days_since_precip_over_1mm",
    "days_since_precip_over_5mm",
    "days_since_precip_over_10mm",
    "antecedent_precipitation_index_daily",
    "temperature2mAtObservation",
    "temperature2mMeanPrevDay",
    "rain_prev_day_mm",
    "snowfall_prev_day_cm",
    "rainAtObservationDay",
    "snowfallAtObservationDay",
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


def main() -> None:
    parser = argparse.ArgumentParser(description="Train a simple débit baseline model from generated features")
    parser.add_argument("--features-path", default="build/debit-pipeline/training-features/training_features.jsonl")
    parser.add_argument("--output-dir", default="build/debit-pipeline/model-baseline")
    parser.add_argument("--target", choices=["three", "six"], default="three")
    parser.add_argument("--validation-fraction", type=float, default=0.2)
    args = parser.parse_args()

    try:
        from sklearn.ensemble import RandomForestClassifier
        from sklearn.metrics import accuracy_score, balanced_accuracy_score, classification_report, confusion_matrix
    except ImportError as exc:  # pragma: no cover
        raise SystemExit(
            "scikit-learn is required for this script. Install it with `python -m pip install scikit-learn`."
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
    feature_importance = sorted(
        [
            {"feature": feature_name, "importance": float(importance)}
            for feature_name, importance in zip(NUMERIC_FEATURES, model.feature_importances_)
        ],
        key=lambda item: item["importance"],
        reverse=True,
    )

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    write_json(
        output_dir / "metrics.json",
        {
            "schemaVersion": 1,
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "targetMode": args.target,
            "trainRowCount": len(train_rows),
            "validationRowCount": len(validation_rows),
            "trainClassCounts": dict(sorted(Counter(y_train).items())),
            "validationClassCounts": dict(sorted(Counter(y_validation).items())),
            "accuracy": accuracy_score(y_validation, predictions),
            "balancedAccuracy": balanced_accuracy_score(y_validation, predictions),
            "classificationReport": report,
            "labels": label_order,
            "confusionMatrix": confusion.tolist(),
            "topFeatureImportances": feature_importance[:20],
        },
    )


if __name__ == "__main__":
    main()
