from __future__ import annotations

import argparse
import json
import math
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import numpy as np


DEFAULT_INPUT = Path("build/watersheds/batch-run/import_ready_watershed_descriptors.json")
DEFAULT_OUTPUT_DIR = Path("build/watershed-descriptor-audit")
MAX_ISSUE_EXAMPLES = 10

FAMILY_BY_COLUMN = {
    "canyonId": "metadata",
    "canyonName": "metadata",
    "sourceName": "metadata",
    "pointType": "metadata",
    "forcedByReview": "metadata",
    "descriptorStatus": "status",
    "soilDescriptorStatus": "status",
    "hydroLakesStatus": "status",
    "gdwStatus": "status",
    "geologyDescriptorStatus": "status",
    "imperviousDescriptorStatus": "status",
    "glacierDescriptorStatus": "status",
    "osmRegulationStatus": "status",
}

ALTITUDE_COLUMNS = {
    "basinMinElevationM",
    "basinMeanElevationM",
    "basinMedianElevationM",
    "basinMaxElevationM",
    "basinElevationStdM",
    "basinReliefM",
    "fractionAbove1500m",
    "fractionAbove2000m",
    "fractionAbove2500m",
}

SLOPE_COLUMNS = {
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
}

FLOW_COLUMNS = {
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
    "streamSegmentCount",
    "junctionCount",
    "strahlerOrder",
    "firstOrderLengthFraction",
    "totalStreamLengthKm",
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
}

CLIMATE_COLUMNS = {
    "meanAnnualPrecipMm",
    "meanMonthlyPrecipSeasonality",
    "meanAnnualTemperatureC",
    "meanWinterTemperatureC",
    "meanSnowFractionClimatology",
    "potentialEvapotranspiration",
    "aridityIndex",
    "continentalityProxy",
    "oceanicityProxy",
    "climateDescriptorStatus",
}

LAND_COVER_COLUMNS = {
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
}

SOIL_COLUMNS = {
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
    "soilDepthMean",
    "soilDepthShallowFraction",
    "bedrockDepth",
    "availableWaterCapacity",
    "saturatedHydraulicConductivity",
}

REGULATION_COLUMNS = {
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
    "osmExampleNames",
    "distanceToNearestRegulationUpstreamKm",
    "regulatedAreaFraction",
    "dominantRegulationType",
    "interbasinTransferLikely",
    "waterIntakeDensity",
    "hydropowerCascadeCount",
    "regulationSeverityIndex",
}

GEOLOGY_COLUMNS = {
    "geologyValidFraction",
    "carbonateFraction",
    "unconsolidatedFraction",
    "crystallineFraction",
    "volcanicFraction",
    "evaporiteFraction",
    "dominantLithologyCode",
    "karstIndicator",
    "sinkholeDensity",
    "springDensity",
    "losingStreamIndicator",
    "resurgenceIndicator",
    "karstConnectivityIndex",
}

GLACIER_COLUMNS = {
    "glacierFraction",
    "glacierCount",
    "largestGlacierAreaKm2",
}

QUALITY_COLUMNS = {
    "watershedCellCount",
    "watershedValidCellCount",
    "watershedNoDataFraction",
    "basinAreaRasterKm2",
    "demResolutionM",
    "watershedQualityScore",
}


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def resolve_input_path(path: Path) -> Path:
    if path.is_file():
        return path
    direct = path / "import_ready_watershed_descriptors.json"
    if direct.exists():
        return direct
    matches = sorted(path.glob("**/import_ready_watershed_descriptors.json"))
    if matches:
        return matches[-1]
    return direct


def infer_family(column_name: str) -> str:
    if column_name in FAMILY_BY_COLUMN:
        return FAMILY_BY_COLUMN[column_name]
    if column_name in ALTITUDE_COLUMNS:
        return "altitude"
    if column_name in SLOPE_COLUMNS:
        return "slope_aspect"
    if column_name in FLOW_COLUMNS:
        return "flow_network"
    if column_name in CLIMATE_COLUMNS:
        return "climate"
    if column_name in LAND_COVER_COLUMNS:
        return "land_cover"
    if column_name in SOIL_COLUMNS:
        return "soil"
    if column_name in REGULATION_COLUMNS:
        return "regulation"
    if column_name in GEOLOGY_COLUMNS:
        return "geology_karst"
    if column_name in GLACIER_COLUMNS:
        return "glacier"
    if column_name in QUALITY_COLUMNS:
        return "quality"
    if column_name.endswith("Status"):
        return "status"
    return "misc"


def is_numeric_value(value: Any) -> bool:
    return isinstance(value, (int, float, bool)) and not isinstance(value, str)


def summarize_numeric_column(values: list[float]) -> dict[str, Any]:
    arr = np.asarray(values, dtype=float)
    if arr.size == 0:
        return {}
    summary = {
        "min": float(np.min(arr)),
        "p01": float(np.percentile(arr, 1)),
        "p10": float(np.percentile(arr, 10)),
        "median": float(np.median(arr)),
        "mean": float(np.mean(arr)),
        "p90": float(np.percentile(arr, 90)),
        "p99": float(np.percentile(arr, 99)),
        "max": float(np.max(arr)),
        "std": float(np.std(arr)),
        "zeroFraction": float(np.mean(arr == 0)),
        "negativeFraction": float(np.mean(arr < 0)),
        "uniqueCount": int(np.unique(arr).size),
    }
    if arr.size >= 4:
        q1, q3 = np.percentile(arr, [25, 75])
        iqr = q3 - q1
        if iqr > 0:
            lower = q1 - 1.5 * iqr
            upper = q3 + 1.5 * iqr
            summary["tukeyOutlierCount"] = int(np.sum((arr < lower) | (arr > upper)))
            summary["tukeyOutlierFraction"] = float(np.mean((arr < lower) | (arr > upper)))
    return summary


def add_issue(issues: dict[str, dict[str, Any]], issue_key: str, descriptor: str, canyon_id: Any, value: Any, detail: str | None = None) -> None:
    bucket = issues.setdefault(issue_key, {"count": 0, "examples": []})
    bucket["count"] += 1
    if len(bucket["examples"]) < MAX_ISSUE_EXAMPLES:
        example = {
            "descriptor": descriptor,
            "canyonId": canyon_id,
            "value": value,
        }
        if detail is not None:
            example["detail"] = detail
        bucket["examples"].append(example)


def validate_row(row: dict[str, Any], issues: dict[str, dict[str, Any]]) -> None:
    canyon_id = row.get("canyonId")
    for key, value in row.items():
        if value is None or not is_numeric_value(value):
            continue
        numeric_value = float(value)
        if (key.endswith("Fraction") or key.endswith("ProxyFraction")) and not (0.0 <= numeric_value <= 1.0):
            add_issue(issues, "fraction_out_of_range", key, canyon_id, numeric_value)
        if key.endswith("Indicator") and not (0.0 <= numeric_value <= 1.0):
            add_issue(issues, "indicator_out_of_range", key, canyon_id, numeric_value)
        if key.endswith("Count") and numeric_value < 0:
            add_issue(issues, "negative_count", key, canyon_id, numeric_value)
        if key.endswith(("Km", "Km2", "M", "Mcm", "Min", "Pct", "Score")) and key not in {"meanAnnualTemperatureC", "meanWinterTemperatureC", "meanPlanCurvature", "meanProfileCurvature"} and numeric_value < 0:
            add_issue(issues, "negative_non_expected", key, canyon_id, numeric_value)

    min_elev = row.get("basinMinElevationM")
    mean_elev = row.get("basinMeanElevationM")
    median_elev = row.get("basinMedianElevationM")
    max_elev = row.get("basinMaxElevationM")
    if all(value is not None for value in (min_elev, mean_elev, max_elev)):
        if not (float(min_elev) <= float(mean_elev) <= float(max_elev)):
            add_issue(issues, "elevation_mean_out_of_bounds", "basinMeanElevationM", canyon_id, mean_elev)
    if all(value is not None for value in (min_elev, median_elev, max_elev)):
        if not (float(min_elev) <= float(median_elev) <= float(max_elev)):
            add_issue(issues, "elevation_median_out_of_bounds", "basinMedianElevationM", canyon_id, median_elev)

    aspect_keys = ["aspectNorthFraction", "aspectEastFraction", "aspectSouthFraction", "aspectWestFraction"]
    aspect_values = [row.get(key) for key in aspect_keys]
    if all(value is not None for value in aspect_values):
        aspect_sum = sum(float(value) for value in aspect_values)
        if not (0.95 <= aspect_sum <= 1.05):
            add_issue(issues, "aspect_fraction_sum_off", "aspect*", canyon_id, round(aspect_sum, 6))

    land_cover_keys = [
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
    ]
    land_cover_values = [row.get(key) for key in land_cover_keys if row.get(key) is not None]
    land_cover_valid_fraction = row.get("landCoverValidFraction")
    if land_cover_values and land_cover_valid_fraction is not None:
        land_cover_sum = sum(float(value) for value in land_cover_values)
        if land_cover_sum - float(land_cover_valid_fraction) > 0.05:
            add_issue(
                issues,
                "land_cover_sum_exceeds_valid_fraction",
                "landCoverValidFraction",
                canyon_id,
                round(land_cover_sum, 6),
                detail=f"validFraction={land_cover_valid_fraction}",
            )


def compute_strong_correlations(rows: list[dict[str, Any]], numeric_columns: list[str], threshold: float, min_shared_count: int) -> list[dict[str, Any]]:
    correlations: list[dict[str, Any]] = []
    arrays_by_column = {
        column: np.array([row.get(column) if row.get(column) is not None else np.nan for row in rows], dtype=float)
        for column in numeric_columns
    }
    filtered_columns = [
        column
        for column, values in arrays_by_column.items()
        if np.count_nonzero(np.isfinite(values)) >= min_shared_count and np.nanstd(values) > 0
    ]
    for index, left in enumerate(filtered_columns):
        left_values = arrays_by_column[left]
        for right in filtered_columns[index + 1:]:
            right_values = arrays_by_column[right]
            valid = np.isfinite(left_values) & np.isfinite(right_values)
            shared_count = int(np.count_nonzero(valid))
            if shared_count < min_shared_count:
                continue
            corr = float(np.corrcoef(left_values[valid], right_values[valid])[0, 1])
            if math.isnan(corr) or abs(corr) < threshold:
                continue
            correlations.append(
                {
                    "left": left,
                    "right": right,
                    "pearson": round(corr, 6),
                    "sharedCount": shared_count,
                }
            )
    correlations.sort(key=lambda item: abs(item["pearson"]), reverse=True)
    return correlations


def render_markdown_report(report: dict[str, Any]) -> str:
    lines: list[str] = []
    lines.append("# Watershed Descriptor Audit")
    lines.append("")
    metadata = report["metadata"]
    lines.append(f"- Generated at: `{metadata['generatedAt']}`")
    lines.append(f"- Input: `{metadata['inputPath']}`")
    lines.append(f"- Rows: `{metadata['rowCount']}`")
    lines.append(f"- Columns: `{metadata['columnCount']}`")
    lines.append(f"- Numeric columns: `{metadata['numericColumnCount']}`")
    lines.append(f"- Categorical/status columns: `{metadata['categoricalColumnCount']}`")
    lines.append("")

    lines.append("## Family Coverage")
    lines.append("")
    for item in report["familySummary"]:
        lines.append(
            f"- `{item['family']}`: `{item['columnCount']}` columns, average coverage `{item['averageCoverage']:.3f}`"
        )
    lines.append("")

    lines.append("## Status Columns")
    lines.append("")
    for column_name, counts in report["statusColumns"].items():
        top_counts = ", ".join(f"`{key}`={value}" for key, value in counts.items())
        lines.append(f"- `{column_name}`: {top_counts}")
    lines.append("")

    lines.append("## Lowest Coverage Columns")
    lines.append("")
    for item in report["lowestCoverageColumns"][:20]:
        lines.append(f"- `{item['column']}` (`{item['family']}`): coverage `{item['coverage']:.3f}`")
    lines.append("")

    lines.append("## Top Issue Counts")
    lines.append("")
    for issue_key, issue_value in report["issues"].items():
        lines.append(f"- `{issue_key}`: `{issue_value['count']}`")
    lines.append("")

    lines.append("## Strong Correlations")
    lines.append("")
    for item in report["strongCorrelations"][:30]:
        lines.append(
            f"- `{item['left']}` <-> `{item['right']}`: `{item['pearson']}` on `{item['sharedCount']}` rows"
        )
    lines.append("")
    return "\n".join(lines)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Audit watershed descriptor quality before training")
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT, help="Descriptor JSON file or run directory")
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--correlation-threshold", type=float, default=0.90)
    parser.add_argument("--min-correlation-shared-count", type=int, default=200)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    input_path = resolve_input_path(args.input)
    if not input_path.exists():
        raise FileNotFoundError(f"Descriptor file not found: {input_path}")

    rows = load_json(input_path)
    if not isinstance(rows, list):
        raise SystemExit(f"Descriptor file must contain a JSON list: {input_path}")

    columns = sorted({key for row in rows if isinstance(row, dict) for key in row.keys()})
    family_by_column = {column: infer_family(column) for column in columns}
    numeric_columns: list[str] = []
    categorical_columns: list[str] = []
    column_reports: dict[str, Any] = {}
    issues: dict[str, dict[str, Any]] = {}

    for row in rows:
        if isinstance(row, dict):
            validate_row(row, issues)

    for column in columns:
        values = [row.get(column) for row in rows if isinstance(row, dict)]
        non_null_values = [value for value in values if value is not None]
        coverage = len(non_null_values) / len(rows) if rows else 0.0
        family = family_by_column[column]
        if non_null_values and all(is_numeric_value(value) for value in non_null_values):
            numeric_columns.append(column)
            numeric_values = [float(value) for value in non_null_values]
            column_reports[column] = {
                "family": family,
                "type": "numeric",
                "nonNullCount": len(non_null_values),
                "coverage": round(coverage, 6),
                "summary": summarize_numeric_column(numeric_values),
            }
        else:
            categorical_columns.append(column)
            counts = Counter(str(value) for value in non_null_values)
            column_reports[column] = {
                "family": family,
                "type": "categorical",
                "nonNullCount": len(non_null_values),
                "coverage": round(coverage, 6),
                "topValues": counts.most_common(20),
            }

    family_to_columns: dict[str, list[str]] = defaultdict(list)
    for column, family in family_by_column.items():
        family_to_columns[family].append(column)

    family_summary = []
    for family, family_columns in sorted(family_to_columns.items()):
        coverages = [column_reports[column]["coverage"] for column in family_columns]
        family_summary.append(
            {
                "family": family,
                "columnCount": len(family_columns),
                "averageCoverage": sum(coverages) / len(coverages) if coverages else 0.0,
                "columns": family_columns,
            }
        )

    status_columns = {
        column: dict(column_reports[column].get("topValues", []))
        for column in columns
        if family_by_column[column] == "status"
    }

    lowest_coverage_columns = sorted(
        [
            {
                "column": column,
                "family": family_by_column[column],
                "coverage": column_reports[column]["coverage"],
            }
            for column in columns
        ],
        key=lambda item: item["coverage"],
    )

    strong_correlations = compute_strong_correlations(
        [row for row in rows if isinstance(row, dict)],
        numeric_columns,
        threshold=args.correlation_threshold,
        min_shared_count=args.min_correlation_shared_count,
    )

    report = {
        "metadata": {
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "inputPath": str(input_path),
            "rowCount": len(rows),
            "columnCount": len(columns),
            "numericColumnCount": len(numeric_columns),
            "categoricalColumnCount": len(categorical_columns),
            "correlationThreshold": args.correlation_threshold,
            "minCorrelationSharedCount": args.min_correlation_shared_count,
        },
        "familySummary": family_summary,
        "statusColumns": status_columns,
        "lowestCoverageColumns": lowest_coverage_columns,
        "issues": dict(sorted(issues.items(), key=lambda item: item[1]["count"], reverse=True)),
        "strongCorrelations": strong_correlations,
        "columns": column_reports,
    }

    output_dir = args.output_dir
    output_dir.mkdir(parents=True, exist_ok=True)
    write_json(output_dir / "descriptor_audit_report.json", report)
    (output_dir / "descriptor_audit_report.md").write_text(render_markdown_report(report), encoding="utf-8")

    print(
        json.dumps(
            {
                "input": str(input_path),
                "outputDir": str(output_dir),
                "rows": len(rows),
                "columns": len(columns),
                "numericColumns": len(numeric_columns),
                "issues": {key: value["count"] for key, value in report["issues"].items()},
                "strongCorrelations": len(strong_correlations),
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
