from __future__ import annotations

import argparse
import json
import math
import random
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from debit_pipeline_lib import DEBIT_DERIVED_MODEL_FEATURE_NAMES, with_debit_derived_model_features, write_json


NUMERIC_FEATURES = [
    "month",
    "monthSin",
    "monthCos",
    "altitudeDepartM",
    "deniveleM",
    "longueurM",
    "cascadeMaxM",
    "upstreamCatchmentAreaKm2",
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
    "watershedReliefProxyM",
    "watershedReliefPerLengthProxyMPerKm",
    "watershedReliefPerDiagonalProxyMPerKm",
    "watershedSlopeProxyPercent",
    "watershedSlopeDiagonalProxyPercent",
    "watershedReliefAreaRatioMPerKm2",
    "watershedKirpichTimeProxyMinutes",
    "watershedFlashinessProxy",
    "watershedShapeReliefInteraction",
    "watershedCellCount",
    "watershedValidCellCount",
    "watershedNoDataFraction",
    "basinAreaRasterKm2",
    "demResolutionM",
    "watershedQualityScore",
    "watershedDescriptorForcedByReview",
    "watershedDescriptorOk",
    "climateDescriptorOk",
    "soilDescriptorOk",
    "hydroLakesDescriptorOk",
    "gdwDescriptorOk",
    "geologyDescriptorOk",
    "imperviousDescriptorOk",
    "glacierDescriptorOk",
    "osmRegulationDescriptorOk",
    "hasWatershedDescriptors",
    "hasClimateDescriptors",
    "hasSoilDescriptors",
    "hasRegulationDescriptors",
    "hasGeologyDescriptors",
    "hasImperviousDescriptors",
    "hasGlacierDescriptors",
    "dominantRegulationTypeIsNone",
    "dominantRegulationTypeIsHydropower",
    "dominantRegulationTypeIsBarrier",
    "dominantRegulationTypeIsReservoir",
    "dominantRegulationTypeIsDiversion",
    "dominantRegulationTypeIsMixed",
    "basinMinElevationM",
    "basinMeanElevationM",
    "basinMedianElevationM",
    "basinMaxElevationM",
    "basinElevationStdM",
    "basinReliefM",
    "fractionAbove1500m",
    "fractionAbove2000m",
    "fractionAbove2500m",
    "meanSlopeDeg",
    "medianSlopeDeg",
    "p90SlopeDeg",
    "maxSlopeDeg",
    "fractionSlopeOver10Deg",
    "fractionSlopeOver20Deg",
    "fractionSlopeOver30Deg",
    "aspectNorthFraction",
    "aspectEastFraction",
    "aspectSouthFraction",
    "aspectWestFraction",
    "terrainRuggednessIndex",
    "maxFlowPathLengthKm",
    "mainFlowLengthKm",
    "mainChannelSlopePercent",
    "timeOfConcentrationKirpichMin",
    "timeOfConcentrationGiandottiMin",
    "meltonRuggedness",
    "outletElevationM",
    "outletSnapDistanceM",
    "streamExtractionThresholdKm2",
    "streamCellCount",
    "streamFrequencyPerKm2",
    "drainageDensityKmPerKm2",
    "streamLinkCount",
    "streamSegmentCount",
    "junctionCount",
    "strahlerOrder",
    "firstOrderLengthFraction",
    "totalStreamLengthKm",
    "meanAnnualPrecipMm",
    "meanMonthlyPrecipSeasonality",
    "meanAnnualTemperatureC",
    "meanWinterTemperatureC",
    "meanSnowFractionClimatology",
    "potentialEvapotranspiration",
    "aridityIndex",
    "continentalityProxy",
    "oceanicityProxy",
    "hypsometricIntegral",
    "topographicWetnessIndexMean",
    "topographicWetnessIndexP90",
    "handMeanM",
    "handMedianM",
    "handP90M",
    "meanPlanCurvature",
    "meanProfileCurvature",
    "valleyConfinementIndex",
    "channelConfinementRatio",
    "flowAccumulationP50Km2",
    "flowAccumulationP90Km2",
    "flowAccumulationP99Km2",
    "landCoverValidFraction",
    "forestFraction",
    "shrubFraction",
    "grassFraction",
    "croplandFraction",
    "urbanFraction",
    "bareRockFraction",
    "snowIceFraction",
    "permanentWaterFraction",
    "wetlandFraction",
    "mangroveFraction",
    "mossLichenFraction",
    "waterPatchCount",
    "wetlandPatchCount",
    "forestPatchCount",
    "urbanPatchCount",
    "largestForestPatchFraction",
    "landCoverFragmentationIndex",
    "riparianForestFraction",
    "imperviousConnectivityProxy",
    "soilValidFraction",
    "meanClayTopsoilPct",
    "medianClayTopsoilPct",
    "p90ClayTopsoilPct",
    "meanSandTopsoilPct",
    "medianSandTopsoilPct",
    "lowPermeabilitySoilFraction",
    "highInfiltrationSoilFraction",
    "runoffPotentialIndex",
    "coarseFragmentFraction",
    "subsoilClayFraction",
    "subsoilSandFraction",
    "soilDepthMeanCm",
    "soilDepthShallowFractionLt100Cm",
    "bedrockDepthCm",
    "availableWaterCapacityMm",
    "saturatedHydraulicConductivityCmPerDay",
    "lakeFraction",
    "lakeCount",
    "reservoirCountUpstream",
    "regulatedLakeCountUpstream",
    "damCountUpstream",
    "majorReservoirDamCountUpstream",
    "reservoirAreaUpstreamKm2",
    "reservoirAreaFraction",
    "reservoirStorageUpstreamMcm",
    "largestUpstreamReservoirAreaKm2",
    "largestUpstreamReservoirStorageMcm",
    "regulatedCatchment",
    "gdwBarrierCountUpstream",
    "gdwReservoirCountUpstream",
    "gdwHydropowerBarrierCountUpstream",
    "gdwReservoirAreaUpstreamKm2",
    "gdwReservoirStorageUpstreamMcm",
    "gdwLargestReservoirStorageMcm",
    "gdwLargestReservoirAreaKm2",
    "gdwMaxUpstreamDorPct",
    "gdwNewestUpstreamDamYear",
    "gdwMaxDamHeightM",
    "gdwRegulatedCatchment",
    "distanceToNearestRegulationUpstreamKm",
    "regulatedAreaFraction",
    "interbasinTransferLikely",
    "waterIntakeDensity",
    "hydropowerCascadeCount",
    "regulationSeverityIndex",
    "geologyValidFraction",
    "carbonateFraction",
    "unconsolidatedFraction",
    "crystallineFraction",
    "volcanicFraction",
    "evaporiteFraction",
    "karstIndicator",
    "sinkholeDensityProxyPerKm2",
    "springDensityProxyPerKm2",
    "losingStreamProxy",
    "resurgenceProxy",
    "karstConnectivityProxy",
    "imperviousValidFraction",
    "imperviousBuiltSurfaceFraction",
    "meanBuiltSurfaceM2PerCell",
    "imperviousProxyFraction",
    "glacierFraction",
    "glacierCount",
    "largestGlacierAreaKm2",
    "osmRegulationPresent",
    "osmDamCountUpstream",
    "osmWeirCountUpstream",
    "osmReservoirCountUpstream",
    "osmCanalCountUpstream",
    "osmPenstockCountUpstream",
    "osmHydropowerPlantCountUpstream",
    "osmOperatorEdfCountUpstream",
    "osmLikelyHydropowerScheme",
    "osmRegulationConfidence",
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
    *DEBIT_DERIVED_MODEL_FEATURE_NAMES,
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


def numeric_feature_value(value: Any) -> float | None:
    if value is None:
        return None
    if isinstance(value, bool):
        return 1.0 if value else 0.0
    try:
        numeric_value = float(value)
    except (TypeError, ValueError):
        return None
    return numeric_value if math.isfinite(numeric_value) else None


def is_canyon_history_feature(feature_name: str) -> bool:
    return (
        feature_name == "canyonPastObsCount"
        or feature_name == "canyonHistoryConfidence"
        or feature_name in {"canyonHighPriorLift", "canyonLowPriorLift", "canyonPriorEntropy", "highLowPriorSpread"}
        or feature_name.startswith("canyonPrior")
        or feature_name.endswith("Canyon")
        or feature_name.startswith("historically")
    )


def neutralize_canyon_history_row(row: dict[str, Any]) -> dict[str, Any]:
    neutralized = dict(row)
    for feature_name in NUMERIC_FEATURES:
        if is_canyon_history_feature(feature_name):
            neutralized[feature_name] = 0.0
    return neutralized


def apply_canyon_history_dropout(
    rows: list[dict[str, Any]],
    *,
    dropout_rate: float,
    random_seed: int,
) -> list[dict[str, Any]]:
    if dropout_rate < 0.0 or dropout_rate > 1.0:
        raise SystemExit("--canyon-history-dropout-rate must be between 0 and 1")
    if dropout_rate == 0.0:
        return rows

    rng = random.Random(random_seed)
    return [
        neutralize_canyon_history_row(row) if rng.random() < dropout_rate else row
        for row in rows
    ]


def build_feature_coverage_report(
    rows: list[dict[str, Any]],
    feature_names: list[str],
    *,
    mostly_missing_threshold: float = 0.95,
    unique_sample_limit: int = 4096,
) -> dict[str, Any]:
    total = len(rows)
    summaries: list[dict[str, Any]] = []
    for feature_name in feature_names:
        missing_count = 0
        invalid_count = 0
        present_count = 0
        unique_values: set[float] = set()
        unique_sample_capped = False

        for row in rows:
            value = row.get(feature_name)
            if value is None:
                missing_count += 1
                continue
            numeric_value = numeric_feature_value(value)
            if numeric_value is None:
                invalid_count += 1
                continue
            present_count += 1
            if len(unique_values) < unique_sample_limit:
                unique_values.add(numeric_value)
            else:
                unique_sample_capped = True

        unavailable_count = missing_count + invalid_count
        missing_fraction = unavailable_count / total if total else 1.0
        constant_non_missing = present_count > 0 and not unique_sample_capped and len(unique_values) == 1
        summaries.append(
            {
                "feature": feature_name,
                "presentCount": present_count,
                "missingCount": missing_count,
                "invalidCount": invalid_count,
                "missingFraction": round(missing_fraction, 6),
                "sampledUniqueCount": len(unique_values),
                "uniqueSampleCapped": unique_sample_capped,
                "allMissing": present_count == 0,
                "mostlyMissing": present_count > 0 and missing_fraction >= mostly_missing_threshold,
                "constantNonMissing": constant_non_missing,
                "constantValue": next(iter(unique_values)) if constant_non_missing else None,
            }
        )

    all_missing = [summary["feature"] for summary in summaries if summary["allMissing"]]
    constant = [
        {"feature": summary["feature"], "value": summary["constantValue"]}
        for summary in summaries
        if summary["constantNonMissing"]
    ]
    mostly_missing = [
        {
            "feature": summary["feature"],
            "missingFraction": summary["missingFraction"],
            "presentCount": summary["presentCount"],
        }
        for summary in summaries
        if summary["mostlyMissing"]
    ]
    return {
        "rowCount": total,
        "featureCount": len(feature_names),
        "mostlyMissingThreshold": mostly_missing_threshold,
        "allMissingFeatureCount": len(all_missing),
        "constantFeatureCount": len(constant),
        "mostlyMissingFeatureCount": len(mostly_missing),
        "allMissingFeatures": all_missing,
        "constantFeatures": constant,
        "mostlyMissingFeatures": mostly_missing,
        "features": summaries,
    }


def select_model_features(
    feature_names: list[str],
    feature_coverage_report: dict[str, Any],
    *,
    keep_uninformative_features: bool = False,
) -> list[str]:
    if keep_uninformative_features:
        return list(feature_names)

    constant_feature_names = {
        item["feature"]
        for item in feature_coverage_report.get("constantFeatures", [])
    }
    dropped_feature_names = set(feature_coverage_report.get("allMissingFeatures", [])) | constant_feature_names
    return [feature_name for feature_name in feature_names if feature_name not in dropped_feature_names]


def row_to_numeric_vector(row: dict[str, Any], feature_names: list[str] | None = None) -> list[float]:
    selected_features = feature_names or NUMERIC_FEATURES
    vector: list[float] = []
    for feature_name in selected_features:
        value = numeric_feature_value(row.get(feature_name))
        vector.append(value if value is not None else -9999.0)
    return vector


def sample_weights(rows: list[dict[str, Any]]) -> list[float] | None:
    weights: list[float] = []
    has_weight = False
    for row in rows:
        value = numeric_feature_value(row.get("sampleWeight"))
        if value is None or value <= 0.0:
            weights.append(1.0)
        else:
            has_weight = True
            weights.append(value)
    return weights if has_weight else None


def top_feature_importances(feature_names: list[str], importances: list[float], limit: int = 20) -> list[dict[str, Any]]:
    return sorted(
        [
            {"feature": feature_name, "importance": float(importance)}
            for feature_name, importance in zip(feature_names, importances)
        ],
        key=lambda item: item["importance"],
        reverse=True,
    )[:limit]


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


def probabilities_to_predictions(probabilities: list[list[float]], labels: list[str]) -> list[str]:
    return [labels[max(range(len(labels)), key=lambda index: probs[index])] for probs in probabilities]


def probability_by_label(probabilities: list[list[float]], labels: list[str], label: str) -> list[float]:
    if label not in labels:
        return [0.0 for _ in probabilities]
    label_index = labels.index(label)
    return [float(probs[label_index]) for probs in probabilities]


def threshold_high_predictions(probabilities: list[list[float]], labels: list[str], threshold: float) -> list[str]:
    if "HIGH" not in labels:
        return probabilities_to_predictions(probabilities, labels)
    high_index = labels.index("HIGH")
    non_high_indices = [index for index, label in enumerate(labels) if label != "HIGH"]
    predictions: list[str] = []
    for probs in probabilities:
        if probs[high_index] >= threshold:
            predictions.append("HIGH")
            continue
        best_non_high_index = max(non_high_indices, key=lambda index: probs[index]) if non_high_indices else high_index
        predictions.append(labels[best_non_high_index])
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
            current_score = (1 if current_recall >= 0.40 else 0, current_precision, current_recall, metrics["balancedAccuracy"])
            best_score = (1 if best_recall >= 0.40 else 0, best_precision, best_recall, best_result["balancedAccuracy"])
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


def main() -> None:
    parser = argparse.ArgumentParser(description="Train a calibrated débit baseline model from generated features")
    parser.add_argument("--features-path", default="build/debit-pipeline/training-features/training_features.jsonl")
    parser.add_argument("--output-dir", default="build/debit-pipeline/model-baseline")
    parser.add_argument("--model", choices=["random_forest", "catboost"], default="random_forest")
    parser.add_argument("--target", choices=["three", "six"], default="three")
    parser.add_argument("--calibration-method", choices=["none", "sigmoid", "isotonic"], default="sigmoid")
    parser.add_argument("--calibration-fraction", type=float, default=0.10)
    parser.add_argument("--test-fraction", type=float, default=0.20)
    parser.add_argument("--n-estimators", type=int, default=300)
    parser.add_argument("--max-depth", type=int, default=12)
    parser.add_argument("--min-samples-leaf", type=int, default=3)
    parser.add_argument(
        "--canyon-history-dropout-rate",
        type=float,
        default=0.0,
        help="Randomly neutralize canyon-specific history on this fraction of training rows",
    )
    parser.add_argument(
        "--keep-uninformative-features",
        action="store_true",
        help="Keep features that are all missing or constant in the training corpus",
    )
    parser.add_argument(
        "--ignore-sample-weights",
        action="store_true",
        help="Ignore sampleWeight values present in the feature rows",
    )
    args = parser.parse_args()

    try:
        from sklearn.calibration import CalibratedClassifierCV
        from sklearn.ensemble import RandomForestClassifier
        from sklearn.metrics import (
            accuracy_score,
            balanced_accuracy_score,
            brier_score_loss,
            classification_report,
            confusion_matrix,
            f1_score,
            log_loss,
        )
    except ImportError as exc:  # pragma: no cover
        raise SystemExit(
            "scikit-learn is required for this script. Install it with `python -m pip install scikit-learn`."
        ) from exc

    try:
        from sklearn.frozen import FrozenEstimator  # type: ignore
    except ImportError:  # pragma: no cover
        FrozenEstimator = None

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
    filtered = [
        with_debit_derived_model_features(row)
        for row in rows
        if target_mapper(row.get("niveau")) is not None and row.get("date")
    ]
    filtered.sort(key=lambda row: row["date"])
    if len(filtered) < 200:
        raise SystemExit("Not enough rows to train and calibrate a baseline model")

    feature_coverage = build_feature_coverage_report(filtered, NUMERIC_FEATURES)
    active_feature_names = select_model_features(
        NUMERIC_FEATURES,
        feature_coverage,
        keep_uninformative_features=args.keep_uninformative_features,
    )
    if not active_feature_names:
        raise SystemExit("No usable numeric features left after coverage filtering")

    train_rows, calibration_rows, test_rows = split_temporal_rows(
        filtered,
        calibration_fraction=args.calibration_fraction,
        test_fraction=args.test_fraction,
    )

    training_feature_rows = apply_canyon_history_dropout(
        train_rows,
        dropout_rate=args.canyon_history_dropout_rate,
        random_seed=42,
    )

    x_train = [row_to_numeric_vector(row, active_feature_names) for row in training_feature_rows]
    y_train = [target_mapper(row["niveau"]) for row in train_rows]
    x_calibration = [row_to_numeric_vector(row, active_feature_names) for row in calibration_rows]
    y_calibration = [target_mapper(row["niveau"]) for row in calibration_rows]
    x_test = [row_to_numeric_vector(row, active_feature_names) for row in test_rows]
    y_test = [target_mapper(row["niveau"]) for row in test_rows]
    train_sample_weights = None if args.ignore_sample_weights else sample_weights(training_feature_rows)
    calibration_sample_weights = None if args.ignore_sample_weights else sample_weights(calibration_rows)

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
            n_estimators=args.n_estimators,
            max_depth=args.max_depth,
            min_samples_leaf=args.min_samples_leaf,
            random_state=42,
            n_jobs=-1,
            class_weight="balanced_subsample",
        )

    fit_kwargs = {"sample_weight": train_sample_weights} if train_sample_weights is not None else {}
    model.fit(x_train, y_train, **fit_kwargs)
    calibrated_model: Any = model
    if args.calibration_method != "none":
        if FrozenEstimator is not None:
            calibrated_model = CalibratedClassifierCV(FrozenEstimator(model), method=args.calibration_method, cv=None)
        else:
            calibrated_model = CalibratedClassifierCV(model, method=args.calibration_method, cv="prefit")
        calibration_fit_kwargs = {"sample_weight": calibration_sample_weights} if calibration_sample_weights is not None else {}
        calibrated_model.fit(x_calibration, y_calibration, **calibration_fit_kwargs)

    labels = list(calibrated_model.classes_) if hasattr(calibrated_model, "classes_") else sorted(set(y_train) | set(y_test))
    calibration_probabilities = calibrated_model.predict_proba(x_calibration)
    test_probabilities = calibrated_model.predict_proba(x_test)
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

    calibration_high_probs = probability_by_label(calibration_probabilities, labels, "HIGH")
    test_high_probs = probability_by_label(test_probabilities, labels, "HIGH")
    probability_metrics = {
        "logLoss": log_loss(y_test, test_probabilities, labels=labels),
        "brierHigh": brier_score_loss([1 if label == "HIGH" else 0 for label in y_test], test_high_probs),
        "meanHighProbability": sum(test_high_probs) / len(test_high_probs) if test_high_probs else None,
    }

    threshold_policies: dict[str, Any] = {}
    if args.target == "three" and "HIGH" in labels:
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

    if args.model == "catboost":
        importances = list(model.get_feature_importance())
    else:
        importances = list(model.feature_importances_)
    feature_importance = top_feature_importances(active_feature_names, importances)

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    write_json(
        output_dir / "metrics.json",
        {
            "schemaVersion": 1,
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "model": args.model,
            "targetMode": args.target,
            "calibrationMethod": args.calibration_method,
            "canyonHistoryDropoutRate": args.canyon_history_dropout_rate,
            "usesSampleWeights": train_sample_weights is not None,
            "featureCount": len(active_feature_names),
            "features": active_feature_names,
            "droppedFeatureCount": len(NUMERIC_FEATURES) - len(active_feature_names),
            "featureCoverage": feature_coverage,
            "trainRowCount": len(train_rows),
            "calibrationRowCount": len(calibration_rows),
            "testRowCount": len(test_rows),
            "validationRowCount": len(test_rows),
            "trainClassCounts": dict(sorted(Counter(y_train).items())),
            "calibrationClassCounts": dict(sorted(Counter(y_calibration).items())),
            "testClassCounts": dict(sorted(Counter(y_test).items())),
            "validationClassCounts": dict(sorted(Counter(y_test).items())),
            "accuracy": argmax_metrics["accuracy"],
            "balancedAccuracy": argmax_metrics["balancedAccuracy"],
            "macroF1": argmax_metrics["macroF1"],
            "weightedF1": argmax_metrics["weightedF1"],
            "precisionHigh": argmax_metrics["precisionHigh"],
            "recallHigh": argmax_metrics["recallHigh"],
            "f1High": argmax_metrics["f1High"],
            "classificationReport": argmax_metrics["classificationReport"],
            "labels": labels,
            "confusionMatrix": argmax_metrics["confusionMatrix"],
            "probabilityMetrics": probability_metrics,
            "thresholdPolicies": threshold_policies,
            "topFeatureImportances": feature_importance,
        },
    )


if __name__ == "__main__":
    main()
