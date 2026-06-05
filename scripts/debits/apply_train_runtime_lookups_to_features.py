from __future__ import annotations

import argparse
import json
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any

from evaluate_post_cutoff_app_model import temporal_history_lookup_features
from export_mobile_embedded_model import LOOKUP_FEATURE_NAMES, compact_runtime_lookup_payload
from export_runtime_lookups import build_runtime_lookup_payload
from pipeline_lib import compute_debit_derived_model_features, write_json, write_jsonl


DEFAULT_TRAIN_FEATURES_PATH = "build/debit-pipeline/stratified-holdout-strict/train_features_strict/training_features.jsonl"
DEFAULT_INPUT_FEATURES_PATH = "build/debit-pipeline/stratified-holdout-descente-reviewed/test_features.jsonl"
DEFAULT_OUTPUT_DIR = "build/debit-pipeline/stratified-holdout-strict/test_features_strict"


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            stripped = line.strip()
            if stripped:
                rows.append(json.loads(stripped))
    return rows


def numeric_map(row: dict[str, Any]) -> dict[str, float]:
    output: dict[str, float] = {}
    for key, value in row.items():
        if isinstance(value, bool):
            output[key] = 1.0 if value else 0.0
        elif isinstance(value, (int, float)):
            output[key] = float(value)
    return output


def resolve_lookup(row: dict[str, Any], compact_payload: dict[str, Any]) -> tuple[str, dict[str, float]]:
    canyon_id = str(int(row["canyonId"]))
    unknown = compact_payload.get("unknownKeys") or {}
    region_key = row.get("region") or unknown.get("region") or "__UNKNOWN_REGION__"
    massif_key = row.get("massif") or unknown.get("massif") or "__UNKNOWN_MASSIF__"
    canyon_entry = (compact_payload.get("canyons") or {}).get(canyon_id)
    region_values = (compact_payload.get("regions") or {}).get(region_key) or {}
    massif_values = (compact_payload.get("massifs") or {}).get(massif_key) or {}
    values: dict[str, float] = {}
    for source in (
        compact_payload.get("defaults") or {},
        compact_payload.get("global") or {},
        region_values,
        massif_values,
        canyon_entry or {},
    ):
        values.update(numeric_map(source))
    source_name = "CANYON" if canyon_entry else "MASSIF" if massif_values else "REGION" if region_values else "GLOBAL"
    return source_name, values


def apply_lookup(row: dict[str, Any], lookup_values: dict[str, float]) -> dict[str, Any]:
    output = dict(row)
    for feature_name in LOOKUP_FEATURE_NAMES:
        if feature_name in lookup_values:
            output[feature_name] = lookup_values[feature_name]
        else:
            output.pop(feature_name, None)
    target_date = date.fromisoformat(str(output["date"]))
    output.update(temporal_history_lookup_features(lookup_values, target_date))
    # Derived history fields depend on the replaced priors/counts, so recompute them.
    for key in (
        "canyonHistoryConfidence",
        "massifHistoryConfidence",
        "regionHistoryConfidence",
        "canyonHighPriorLift",
        "canyonLowPriorLift",
        "massifHighPriorLift",
        "regionHighPriorLift",
        "canyonPriorEntropy",
        "highLowPriorSpread",
    ):
        output.pop(key, None)
    output.update(compute_debit_derived_model_features(output))
    return output


def main() -> None:
    parser = argparse.ArgumentParser(description="Apply train-only runtime lookups to holdout feature rows")
    parser.add_argument("--train-features-path", default=DEFAULT_TRAIN_FEATURES_PATH)
    parser.add_argument("--input-features-path", default=DEFAULT_INPUT_FEATURES_PATH)
    parser.add_argument("--output-dir", default=DEFAULT_OUTPUT_DIR)
    args = parser.parse_args()

    train_rows = read_jsonl(Path(args.train_features_path))
    input_rows = read_jsonl(Path(args.input_features_path))
    runtime_payload, runtime_metadata = build_runtime_lookup_payload(train_rows)
    compact_payload = compact_runtime_lookup_payload(runtime_payload)

    output_rows: list[dict[str, Any]] = []
    lookup_sources: dict[str, int] = {}
    for row in input_rows:
        source, lookup_values = resolve_lookup(row, compact_payload)
        lookup_sources[source] = lookup_sources.get(source, 0) + 1
        output = apply_lookup(row, lookup_values)
        output["strictLookupSource"] = source
        output_rows.append(output)

    output_dir = Path(args.output_dir)
    write_jsonl(output_dir / "test_features.jsonl", output_rows)
    write_json(output_dir / "runtime_feature_lookups_train_only.json", compact_payload)
    write_json(
        output_dir / "metadata.json",
        {
            "schemaVersion": 1,
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "trainFeaturesPath": args.train_features_path,
            "inputFeaturesPath": args.input_features_path,
            "rowCount": len(output_rows),
            "lookupSources": dict(sorted(lookup_sources.items())),
            "runtimeLookupMetadata": runtime_metadata,
            "files": {
                "testFeatures": "test_features.jsonl",
                "runtimeLookups": "runtime_feature_lookups_train_only.json",
            },
        },
    )
    print(f"Wrote {output_dir / 'test_features.jsonl'} ({len(output_rows)} rows)")


if __name__ == "__main__":
    main()
