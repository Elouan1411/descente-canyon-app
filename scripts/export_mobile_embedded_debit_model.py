from __future__ import annotations

import argparse
import json
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from debit_pipeline_lib import load_canyon_lookup, load_watershed_lookup, write_json
from export_debit_runtime_lookups import build_runtime_lookup_payload
from train_debit_baseline_model import NUMERIC_FEATURES


COMPUTED_FEATURE_NAMES = {"month", "monthSin", "monthCos"}
STATIC_FEATURE_NAMES = {
    "altitudeDepartM",
    "deniveleM",
    "longueurM",
    "cascadeMaxM",
    "upstreamCatchmentAreaKm2",
    "hasWatershed",
    "watershedHasGeometry",
    "watershedPerimeterKm",
    "watershedCompactnessCoefficient",
    "watershedCircularityRatio",
    "watershedBboxWidthKm",
    "watershedBboxHeightKm",
    "watershedBboxDiagonalKm",
    "watershedBboxAreaKm2",
    "watershedAreaToBboxRatio",
    "watershedLengthProxyKm",
    "watershedWidthProxyKm",
    "watershedElongationRatio",
    "watershedFormFactor",
    "watershedShapeFactor",
    "watershedGeometryVertexCount",
}
LOOKUP_FEATURE_NAMES = {
    "globalPastObsCount",
    "regionPastObsCount",
    "massifPastObsCount",
    "canyonPastObsCount",
    "globalPriorLow",
    "globalPriorMedium",
    "globalPriorHigh",
    "regionPriorLow",
    "regionPriorMedium",
    "regionPriorHigh",
    "massifPriorLow",
    "massifPriorMedium",
    "massifPriorHigh",
    "canyonPriorLow",
    "canyonPriorMedium",
    "canyonPriorHigh",
    "historicalRegulatedSignalCountCanyon",
    "historicalSnowmeltSignalCountCanyon",
    "historicalRegulatedSignalRatioCanyon",
    "historicalSnowmeltSignalRatioCanyon",
    "historicalRegulatedSignalRatioMassif",
    "historicalSnowmeltSignalRatioMassif",
    "historicalRegulatedSignalRatioRegion",
    "historicalSnowmeltSignalRatioRegion",
    "historicallyRegulatedCanyon",
    "historicallySnowmeltCanyon",
    "historicallyAtypicalCanyon",
}
TARGET_LABELS = ["LOW", "MEDIUM", "HIGH"]


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            stripped = line.strip()
            if stripped:
                rows.append(json.loads(stripped))
    return rows


def target_three_classes(level: str | None) -> str | None:
    if level in {"SEC", "FILET"}:
        return "LOW"
    if level == "CORRECT":
        return "MEDIUM"
    if level in {"GROS", "TRES_GROS", "CRUE"}:
        return "HIGH"
    return None


def split_temporal_rows(rows: list[dict[str, Any]], calibration_fraction: float, test_fraction: float) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
    total = len(rows)
    test_count = max(1, int(total * test_fraction))
    calibration_count = max(1, int(total * calibration_fraction))
    train_count = total - test_count - calibration_count
    if train_count < 50:
        raise SystemExit("Not enough rows left for the training split; lower --calibration-fraction or --test-fraction")
    train_rows = rows[:train_count]
    calibration_rows = rows[train_count:train_count + calibration_count]
    test_rows = rows[train_count + calibration_count:]
    return train_rows, calibration_rows, test_rows


def feature_source(feature_name: str) -> str:
    if feature_name in COMPUTED_FEATURE_NAMES:
        return "computed"
    if feature_name in STATIC_FEATURE_NAMES:
        return "static"
    if feature_name in LOOKUP_FEATURE_NAMES:
        return "lookup"
    return "weather"


def feature_default(feature_name: str) -> float:
    if feature_name.startswith("historically") or feature_name == "hasWatershed":
        return 0.0
    if feature_name.endswith("Count"):
        return 0.0
    if "Prior" in feature_name or feature_name.endswith("RatioCanyon") or feature_name.endswith("RatioMassif") or feature_name.endswith("RatioRegion"):
        return 0.0
    if feature_name in {"monthSin", "monthCos"}:
        return 0.0
    return -9999.0


def feature_spec_payload(labels: list[str]) -> dict[str, Any]:
    features = [
        {
            "name": feature_name,
            "source": feature_source(feature_name),
            "default": feature_default(feature_name),
        }
        for feature_name in NUMERIC_FEATURES
    ]
    return {
        "schemaVersion": 1,
        "labels": labels,
        "features": features,
        "staticFeatureNames": [feature_name for feature_name in NUMERIC_FEATURES if feature_name in STATIC_FEATURE_NAMES],
        "lookupFeatureNames": [feature_name for feature_name in NUMERIC_FEATURES if feature_name in LOOKUP_FEATURE_NAMES],
        "dynamicFeatureNames": [feature_name for feature_name in NUMERIC_FEATURES if feature_name not in STATIC_FEATURE_NAMES and feature_name not in LOOKUP_FEATURE_NAMES],
    }


def row_to_vector(row: dict[str, Any]) -> list[float]:
    values: list[float] = []
    for feature_name in NUMERIC_FEATURES:
        raw_value = row.get(feature_name)
        value = feature_default(feature_name) if raw_value is None else raw_value
        values.append(float(value))
    return values


def probabilities_to_predictions(probabilities: list[list[float]], labels: list[str]) -> list[str]:
    return [labels[max(range(len(labels)), key=lambda index: probs[index])] for probs in probabilities]


def threshold_high_predictions(probabilities: list[list[float]], labels: list[str], threshold: float) -> list[str]:
    high_index = labels.index("HIGH")
    non_high_indices = [index for index, label in enumerate(labels) if label != "HIGH"]
    predictions: list[str] = []
    for probs in probabilities:
        if probs[high_index] >= threshold:
            predictions.append("HIGH")
        else:
            predictions.append(labels[max(non_high_indices, key=lambda index: probs[index])])
    return predictions


def evaluate_predictions(
    *,
    y_true: list[str],
    predictions: list[str],
    labels: list[str],
    accuracy_score_fn: Any,
    balanced_accuracy_score_fn: Any,
    classification_report_fn: Any,
    confusion_matrix_fn: Any,
    f1_score_fn: Any,
) -> dict[str, Any]:
    report = classification_report_fn(y_true, predictions, labels=labels, output_dict=True, zero_division=0)
    return {
        "accuracy": accuracy_score_fn(y_true, predictions),
        "balancedAccuracy": balanced_accuracy_score_fn(y_true, predictions),
        "macroF1": f1_score_fn(y_true, predictions, labels=labels, average="macro", zero_division=0),
        "weightedF1": f1_score_fn(y_true, predictions, labels=labels, average="weighted", zero_division=0),
        "precisionHigh": report.get("HIGH", {}).get("precision"),
        "recallHigh": report.get("HIGH", {}).get("recall"),
        "f1High": report.get("HIGH", {}).get("f1-score"),
        "classificationReport": report,
        "confusionMatrix": confusion_matrix_fn(y_true, predictions, labels=labels).tolist(),
    }


def find_high_threshold(
    *,
    y_true: list[str],
    probabilities: list[list[float]],
    labels: list[str],
    policy: str,
    accuracy_score_fn: Any,
    balanced_accuracy_score_fn: Any,
    classification_report_fn: Any,
    confusion_matrix_fn: Any,
    f1_score_fn: Any,
) -> dict[str, Any]:
    candidate_thresholds = [round(step / 100.0, 2) for step in range(5, 91, 1)]
    best_result: dict[str, Any] | None = None
    for threshold in candidate_thresholds:
        predictions = threshold_high_predictions(probabilities, labels, threshold)
        metrics = evaluate_predictions(
            y_true=y_true,
            predictions=predictions,
            labels=labels,
            accuracy_score_fn=accuracy_score_fn,
            balanced_accuracy_score_fn=balanced_accuracy_score_fn,
            classification_report_fn=classification_report_fn,
            confusion_matrix_fn=confusion_matrix_fn,
            f1_score_fn=f1_score_fn,
        )
        metrics["threshold"] = threshold
        if best_result is None:
            best_result = metrics
            continue

        current_precision = metrics.get("precisionHigh") or 0.0
        current_recall = metrics.get("recallHigh") or 0.0
        current_f1 = metrics.get("f1High") or 0.0
        current_balanced = metrics["balancedAccuracy"]

        best_precision = best_result.get("precisionHigh") or 0.0
        best_recall = best_result.get("recallHigh") or 0.0
        best_f1 = best_result.get("f1High") or 0.0
        best_balanced = best_result["balancedAccuracy"]

        if policy == "prudent":
            current_score = (1 if current_recall >= 0.40 else 0, current_precision, current_recall, current_balanced)
            best_score = (1 if best_recall >= 0.40 else 0, best_precision, best_recall, best_balanced)
        elif policy == "safety_first":
            current_score = (1 if current_precision >= 0.25 else 0, current_recall, current_f1, current_balanced, -threshold)
            best_score = (1 if best_precision >= 0.25 else 0, best_recall, best_f1, best_balanced, -best_result["threshold"])
        else:
            current_score = (current_f1, current_balanced, current_precision, current_recall)
            best_score = (best_f1, best_balanced, best_precision, best_recall)

        if current_score > best_score:
            best_result = metrics
    assert best_result is not None
    return best_result


def build_canyon_static_features(canyons_path: Path, watersheds_path: Path) -> dict[str, dict[str, float | None]]:
    canyon_lookup = load_canyon_lookup(canyons_path)
    watershed_lookup = load_watershed_lookup(watersheds_path)
    from debit_pipeline_lib import compute_watershed_morphology_features

    result: dict[str, dict[str, float | None]] = {}
    for canyon_id, canyon in sorted(canyon_lookup.items()):
        watershed = watershed_lookup.get(canyon_id)
        row = {
            "altitudeDepartM": float(canyon.get("altitudeDepart")) if canyon.get("altitudeDepart") is not None else None,
            "deniveleM": float(canyon.get("denivele")) if canyon.get("denivele") is not None else None,
            "longueurM": float(canyon.get("longueur")) if canyon.get("longueur") is not None else None,
            "cascadeMaxM": float(canyon.get("cascadeMax")) if canyon.get("cascadeMax") is not None else None,
            "upstreamCatchmentAreaKm2": float(watershed.get("upstreamCatchmentAreaKm2")) if watershed and watershed.get("upstreamCatchmentAreaKm2") is not None else None,
            "hasWatershed": 1.0 if watershed is not None else 0.0,
        }
        row.update(compute_watershed_morphology_features(watershed))
        result[str(canyon_id)] = row
    return result


def compact_runtime_lookup_payload(payload: dict[str, Any]) -> dict[str, Any]:
    global_feature_names = [
        "globalPastObsCount",
        "globalPriorLow",
        "globalPriorMedium",
        "globalPriorHigh",
    ]
    region_feature_names = [
        "regionPastObsCount",
        "regionPriorLow",
        "regionPriorMedium",
        "regionPriorHigh",
        "historicalRegulatedSignalRatioRegion",
        "historicalSnowmeltSignalRatioRegion",
    ]
    massif_feature_names = [
        "massifPastObsCount",
        "massifPriorLow",
        "massifPriorMedium",
        "massifPriorHigh",
        "historicalRegulatedSignalRatioMassif",
        "historicalSnowmeltSignalRatioMassif",
    ]
    canyon_feature_names = [
        "regionKey",
        "massifKey",
        "globalPastObsCount",
        "regionPastObsCount",
        "massifPastObsCount",
        "canyonPastObsCount",
        "globalPriorLow",
        "globalPriorMedium",
        "globalPriorHigh",
        "regionPriorLow",
        "regionPriorMedium",
        "regionPriorHigh",
        "massifPriorLow",
        "massifPriorMedium",
        "massifPriorHigh",
        "canyonPriorLow",
        "canyonPriorMedium",
        "canyonPriorHigh",
        "historicalRegulatedSignalCountCanyon",
        "historicalSnowmeltSignalCountCanyon",
        "historicalRegulatedSignalRatioCanyon",
        "historicalSnowmeltSignalRatioCanyon",
        "historicalRegulatedSignalRatioMassif",
        "historicalSnowmeltSignalRatioMassif",
        "historicalRegulatedSignalRatioRegion",
        "historicalSnowmeltSignalRatioRegion",
        "historicallyRegulatedCanyon",
        "historicallySnowmeltCanyon",
        "historicallyAtypicalCanyon",
    ]
    defaults = {
        feature_name: payload["global"].get(feature_name, feature_default(feature_name))
        for feature_name in LOOKUP_FEATURE_NAMES
    }
    defaults.update(
        {
            "regionKey": payload["unknownKeys"]["region"],
            "massifKey": payload["unknownKeys"]["massif"],
        }
    )
    return {
        "schemaVersion": payload["schemaVersion"],
        "generatedAt": payload["generatedAt"],
        "targetMode": payload["targetMode"],
        "labels": payload["labels"],
        "unknownKeys": payload["unknownKeys"],
        "lookupFeatureNames": list(LOOKUP_FEATURE_NAMES),
        "defaults": defaults,
        "global": {name: payload["global"].get(name, defaults.get(name)) for name in global_feature_names},
        "regions": {
            key: {name: row.get(name, defaults.get(name)) for name in region_feature_names}
            for key, row in payload["regions"].items()
        },
        "massifs": {
            key: {name: row.get(name, defaults.get(name)) for name in massif_feature_names}
            for key, row in payload["massifs"].items()
        },
        "canyons": {
            key: {name: row.get(name, defaults.get(name)) for name in canyon_feature_names}
            for key, row in payload["canyons"].items()
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Export an embedded debit model and mobile runtime artifacts")
    parser.add_argument("--features-path", default="build/debit-pipeline/training-features-v22/training_features.jsonl")
    parser.add_argument("--output-dir", default="modele_statistique")
    parser.add_argument("--canyons-path", default="offline-data/full/room-import/canyons.json")
    parser.add_argument("--watersheds-path", default="offline-data/full/room-import/watersheds.json")
    parser.add_argument("--calibration-fraction", type=float, default=0.10)
    parser.add_argument("--test-fraction", type=float, default=0.20)
    parser.add_argument("--n-estimators", type=int, default=400)
    parser.add_argument("--max-depth", type=int, default=12)
    parser.add_argument("--min-samples-leaf", type=int, default=3)
    parser.add_argument("--target-opset", type=int, default=17)
    args = parser.parse_args()

    try:
        import numpy as np
        from skl2onnx import to_onnx
        from sklearn.ensemble import RandomForestClassifier
        from sklearn.metrics import accuracy_score, balanced_accuracy_score, classification_report, confusion_matrix, f1_score
    except ImportError as exc:  # pragma: no cover
        raise SystemExit(
            "This export requires numpy, scikit-learn and skl2onnx. Install them with `python -m pip install numpy scikit-learn skl2onnx onnx`."
        ) from exc

    rows = read_jsonl(Path(args.features_path))
    runtime_lookup_payload, runtime_lookup_metadata = build_runtime_lookup_payload(rows)
    filtered = [row for row in rows if target_three_classes(row.get("niveau")) is not None and row.get("date")]
    filtered.sort(key=lambda row: row["date"])
    if len(filtered) < 200:
        raise SystemExit("Not enough rows to export an embedded model")

    train_rows, calibration_rows, test_rows = split_temporal_rows(
        filtered,
        calibration_fraction=args.calibration_fraction,
        test_fraction=args.test_fraction,
    )

    x_train = [row_to_vector(row) for row in train_rows]
    y_train = [target_three_classes(row["niveau"]) for row in train_rows]
    x_calibration = [row_to_vector(row) for row in calibration_rows]
    y_calibration = [target_three_classes(row["niveau"]) for row in calibration_rows]
    x_test = [row_to_vector(row) for row in test_rows]
    y_test = [target_three_classes(row["niveau"]) for row in test_rows]

    model = RandomForestClassifier(
        n_estimators=args.n_estimators,
        max_depth=args.max_depth,
        min_samples_leaf=args.min_samples_leaf,
        random_state=42,
        n_jobs=-1,
        class_weight="balanced_subsample",
    )
    model.fit(x_train, y_train)

    labels = list(model.classes_)
    calibration_probabilities = model.predict_proba(x_calibration)
    test_probabilities = model.predict_proba(x_test)
    argmax_predictions = probabilities_to_predictions(test_probabilities, labels)
    argmax_metrics = evaluate_predictions(
        y_true=y_test,
        predictions=argmax_predictions,
        labels=labels,
        accuracy_score_fn=accuracy_score,
        balanced_accuracy_score_fn=balanced_accuracy_score,
        classification_report_fn=classification_report,
        confusion_matrix_fn=confusion_matrix,
        f1_score_fn=f1_score,
    )

    threshold_policies: dict[str, Any] = {}
    for policy_name in ("balanced", "prudent", "safety_first"):
        calibration_metrics = find_high_threshold(
            y_true=y_calibration,
            probabilities=calibration_probabilities,
            labels=labels,
            policy=policy_name,
            accuracy_score_fn=accuracy_score,
            balanced_accuracy_score_fn=balanced_accuracy_score,
            classification_report_fn=classification_report,
            confusion_matrix_fn=confusion_matrix,
            f1_score_fn=f1_score,
        )
        threshold = calibration_metrics["threshold"]
        test_predictions = threshold_high_predictions(test_probabilities, labels, threshold)
        test_metrics = evaluate_predictions(
            y_true=y_test,
            predictions=test_predictions,
            labels=labels,
            accuracy_score_fn=accuracy_score,
            balanced_accuracy_score_fn=balanced_accuracy_score,
            classification_report_fn=classification_report,
            confusion_matrix_fn=confusion_matrix,
            f1_score_fn=f1_score,
        )
        threshold_policies[policy_name] = {
            "threshold": threshold,
            "calibrationMetrics": calibration_metrics,
            "testMetrics": test_metrics,
        }

    sample_input = np.array([row_to_vector(train_rows[0])], dtype=np.float32)
    onnx_model = to_onnx(model, sample_input, target_opset=args.target_opset)

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "model.onnx").write_bytes(onnx_model.SerializeToString())

    write_json(output_dir / "feature_spec.json", feature_spec_payload(labels))
    write_json(output_dir / "canyon_static_features.json", build_canyon_static_features(Path(args.canyons_path), Path(args.watersheds_path)))
    write_json(output_dir / "runtime_feature_lookups.json", compact_runtime_lookup_payload(runtime_lookup_payload))
    write_json(
        output_dir / "thresholds.json",
        {
            "schemaVersion": 1,
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "labels": labels,
            "defaultPolicy": "safety_first",
            "policies": {
                policy_name: {"highThreshold": payload["threshold"]}
                for policy_name, payload in threshold_policies.items()
            },
        },
    )
    write_json(
        output_dir / "metrics.json",
        {
            "schemaVersion": 1,
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "model": "random_forest_mobile_embedded_full",
            "targetMode": "three",
            "labels": labels,
            "featureCount": len(NUMERIC_FEATURES),
            "features": NUMERIC_FEATURES,
            "trainRowCount": len(train_rows),
            "calibrationRowCount": len(calibration_rows),
            "testRowCount": len(test_rows),
            "trainClassCounts": dict(sorted(Counter(y_train).items())),
            "calibrationClassCounts": dict(sorted(Counter(y_calibration).items())),
            "testClassCounts": dict(sorted(Counter(y_test).items())),
            "argmaxMetrics": argmax_metrics,
            "thresholdPolicies": threshold_policies,
            "topFeatureImportances": [
                {
                    "feature": feature_name,
                    "importance": float(importance),
                }
                for feature_name, importance in sorted(
                    zip(NUMERIC_FEATURES, model.feature_importances_),
                    key=lambda item: item[1],
                    reverse=True,
                )[:20]
            ],
            "runtimeLookupMetadata": runtime_lookup_metadata,
            "files": {
                "model": "model.onnx",
                "featureSpec": "feature_spec.json",
                "canyonStaticFeatures": "canyon_static_features.json",
                "runtimeLookups": "runtime_feature_lookups.json",
                "thresholds": "thresholds.json",
            },
        },
    )


if __name__ == "__main__":
    main()
