from __future__ import annotations

import argparse
import concurrent.futures
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from debit_pipeline_lib import (
    BASE_URL,
    DEFAULT_ASSUMED_OBSERVATION_HOUR,
    deduplicate_observations,
    fetch_text,
    load_canyon_lookup,
    load_manual_overrides,
    parse_canyon_debit_page,
    classify_observation,
    write_json,
    write_jsonl,
)


def scrape_single_canyon(
    *,
    canyon_id: int,
    canyon_name: str | None,
    html_cache_dir: Path,
    user_agent: str,
    request_delay_seconds: float,
    assumed_observation_hour: int,
) -> dict[str, Any]:
    source_url = f"{BASE_URL}/canyoning/canyon-debit/{canyon_id}/observations.html"
    cache_path = html_cache_dir / f"{canyon_id}.html"
    html = fetch_text(
        source_url,
        user_agent=user_agent,
        delay_seconds=request_delay_seconds,
        cache_path=cache_path,
    )
    observations = parse_canyon_debit_page(
        canyon_id=canyon_id,
        canyon_name=canyon_name,
        html=html,
        source_url=source_url,
        assumed_observation_hour=assumed_observation_hour,
    )
    return {
        "canyonId": canyon_id,
        "canyonName": canyon_name,
        "sourceUrl": source_url,
        "observationCount": len(observations),
        "observations": observations,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Build a cleaned debit observation dataset for hydrology modelling")
    parser.add_argument("canyon_ids", nargs="*", type=int)
    parser.add_argument("--all", action="store_true", dest="use_all")
    parser.add_argument("--canyons-path", default="offline-data/full/room-import/canyons.json")
    parser.add_argument("--output-dir", default="build/debit-pipeline/observations")
    parser.add_argument("--html-cache-dir")
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--request-delay-ms", type=int, default=250)
    parser.add_argument("--user-agent", default="DescenteCanyonDebitPipeline/0.1")
    parser.add_argument("--assumed-observation-hour", type=int, default=DEFAULT_ASSUMED_OBSERVATION_HOUR)
    parser.add_argument(
        "--manual-overrides",
        default="scripts/debits/observation_overrides.json",
        help="JSON array of manual quality overrides",
    )
    args = parser.parse_args()

    canyon_lookup = load_canyon_lookup(Path(args.canyons_path))
    if args.use_all:
        canyon_ids = sorted(canyon_lookup.keys())
    else:
        canyon_ids = sorted(set(args.canyon_ids))
    if not canyon_ids:
        parser.error("Provide canyon ids or use --all")

    output_dir = Path(args.output_dir)
    html_cache_dir = Path(args.html_cache_dir) if args.html_cache_dir else output_dir / "html-cache"
    manual_overrides = load_manual_overrides(Path(args.manual_overrides) if args.manual_overrides else None)
    request_delay_seconds = max(args.request_delay_ms, 0) / 1000.0

    results: list[dict[str, Any]] = []
    failures: list[dict[str, Any]] = []
    total = len(canyon_ids)

    print(f"Scraping debit observations for {total} canyon(s)...", file=sys.stderr)
    with concurrent.futures.ThreadPoolExecutor(max_workers=max(args.workers, 1)) as executor:
        future_map = {
            executor.submit(
                scrape_single_canyon,
                canyon_id=canyon_id,
                canyon_name=canyon_lookup.get(canyon_id, {}).get("nom"),
                html_cache_dir=html_cache_dir,
                user_agent=args.user_agent,
                request_delay_seconds=request_delay_seconds,
                assumed_observation_hour=args.assumed_observation_hour,
            ): canyon_id
            for canyon_id in canyon_ids
        }
        completed = 0
        for future in concurrent.futures.as_completed(future_map):
            canyon_id = future_map[future]
            completed += 1
            try:
                results.append(future.result())
            except Exception as exc:  # noqa: BLE001
                failures.append({"canyonId": canyon_id, "error": repr(exc)})
            if completed % 50 == 0 or completed == total:
                print(f"Progress {completed}/{total} | failures={len(failures)}", file=sys.stderr)

    raw_observations = [
        observation
        for result in sorted(results, key=lambda item: item["canyonId"])
        for observation in result["observations"]
    ]
    deduplicated = deduplicate_observations(raw_observations)

    labelled: list[dict[str, Any]] = []
    valid: list[dict[str, Any]] = []
    invalid: list[dict[str, Any]] = []
    uncertain: list[dict[str, Any]] = []
    for observation in deduplicated:
        quality = classify_observation(observation, manual_overrides)
        labelled_observation = dict(observation)
        labelled_observation.update(quality)
        labelled.append(labelled_observation)
        label = labelled_observation["qualityLabel"]
        if label == "valid":
            valid.append(labelled_observation)
        elif label == "invalid":
            invalid.append(labelled_observation)
        else:
            uncertain.append(labelled_observation)

    metadata = {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "requestedCanyonCount": total,
        "scrapedCanyonCount": len(results),
        "failureCount": len(failures),
        "rawObservationCount": len(raw_observations),
        "deduplicatedObservationCount": len(deduplicated),
        "validObservationCount": len(valid),
        "invalidObservationCount": len(invalid),
        "uncertainObservationCount": len(uncertain),
        "assumedObservationHourLocal": args.assumed_observation_hour,
        "manualOverrideCount": len(manual_overrides),
        "files": {
            "all": "all_debit_observations.jsonl",
            "valid": "valid_debit_observations.jsonl",
            "invalid": "invalid_debit_observations.jsonl",
            "uncertain": "uncertain_debit_observations.jsonl",
            "scrapeReport": "scrape_report.json",
        },
    }

    output_dir.mkdir(parents=True, exist_ok=True)
    write_jsonl(output_dir / "all_debit_observations.jsonl", labelled)
    write_jsonl(output_dir / "valid_debit_observations.jsonl", valid)
    write_jsonl(output_dir / "invalid_debit_observations.jsonl", invalid)
    write_jsonl(output_dir / "uncertain_debit_observations.jsonl", uncertain)
    write_json(output_dir / "metadata.json", metadata)
    write_json(
        output_dir / "scrape_report.json",
        {
            "schemaVersion": 1,
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "canyons": sorted(
                [
                    {
                        "canyonId": result["canyonId"],
                        "canyonName": result["canyonName"],
                        "observationCount": result["observationCount"],
                        "sourceUrl": result["sourceUrl"],
                    }
                    for result in results
                ],
                key=lambda item: item["canyonId"],
            ),
            "failures": sorted(failures, key=lambda item: item["canyonId"]),
        },
    )

    print(json.dumps(metadata, ensure_ascii=False, indent=2), file=sys.stderr)


if __name__ == "__main__":
    main()
