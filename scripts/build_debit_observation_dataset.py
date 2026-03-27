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


DEFAULT_HTML_CACHE_DIR = Path("build/debit-pipeline/cache/debit-html")
NON_RAW_OBSERVATION_KEYS = {"qualityLabel", "qualityScore", "qualityReasons", "manualOverride"}


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            stripped = line.strip()
            if stripped:
                rows.append(json.loads(stripped))
    return rows


def strip_quality_fields(observation: dict[str, Any]) -> dict[str, Any]:
    return {key: value for key, value in observation.items() if key not in NON_RAW_OBSERVATION_KEYS}


def scrape_single_canyon(
    *,
    canyon_id: int,
    canyon_name: str | None,
    html_cache_dir: Path,
    user_agent: str,
    request_delay_seconds: float,
    assumed_observation_hour: int,
    refresh_html_cache: bool,
) -> dict[str, Any]:
    source_url = f"{BASE_URL}/canyoning/canyon-debit/{canyon_id}/observations.html"
    cache_path = html_cache_dir / f"{canyon_id}.html"
    used_html_cache = cache_path.exists() and not refresh_html_cache
    if refresh_html_cache and cache_path.exists():
        cache_path.unlink()
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
        "usedHtmlCache": used_html_cache,
        "observations": observations,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Build a cleaned debit observation dataset for hydrology modelling")
    parser.add_argument("canyon_ids", nargs="*", type=int)
    parser.add_argument("--all", action="store_true", dest="use_all")
    parser.add_argument("--canyons-path", default="offline-data/full/room-import/canyons.json")
    parser.add_argument("--output-dir", default="build/debit-pipeline/observations")
    parser.add_argument("--html-cache-dir", help="Persistent HTML cache reused across runs")
    parser.add_argument("--refresh-html-cache", action="store_true", help="Ignore cached canyon HTML and re-download")
    parser.add_argument(
        "--reuse-observations-path",
        help="Reuse a previous all_debit_observations.jsonl file and only recompute labels",
    )
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
    legacy_html_cache_dir = output_dir / "html-cache"
    if args.html_cache_dir:
        html_cache_dir = Path(args.html_cache_dir)
        html_cache_source = "cli"
    else:
        default_cache_count = sum(1 for _ in DEFAULT_HTML_CACHE_DIR.glob("*.html")) if DEFAULT_HTML_CACHE_DIR.exists() else 0
        legacy_cache_count = sum(1 for _ in legacy_html_cache_dir.glob("*.html")) if legacy_html_cache_dir.exists() else 0
        if legacy_cache_count > default_cache_count:
            html_cache_dir = legacy_html_cache_dir
            html_cache_source = "legacy_output_dir"
        else:
            html_cache_dir = DEFAULT_HTML_CACHE_DIR
            html_cache_source = "default_persistent"
    manual_overrides = load_manual_overrides(Path(args.manual_overrides) if args.manual_overrides else None)
    request_delay_seconds = max(args.request_delay_ms, 0) / 1000.0
    cache_hit_count = sum(1 for canyon_id in canyon_ids if (html_cache_dir / f"{canyon_id}.html").exists())

    results: list[dict[str, Any]] = []
    failures: list[dict[str, Any]] = []
    reused_observations_path = Path(args.reuse_observations_path) if args.reuse_observations_path else None
    reuse_existing_observations = reused_observations_path is not None
    total = len(canyon_ids)

    if reuse_existing_observations:
        print(f"Reusing parsed observations from {reused_observations_path}...", file=sys.stderr)
        source_rows = read_jsonl(reused_observations_path)
        filtered_rows = [
            strip_quality_fields(row)
            for row in source_rows
            if int(row["canyonId"]) in set(canyon_ids)
        ]
        raw_observations = filtered_rows
    else:
        print(f"Scraping debit observations for {total} canyon(s)...", file=sys.stderr)
        print(
            f"Using HTML cache: {html_cache_dir} ({html_cache_source}) | cached={cache_hit_count}/{total} | refresh={args.refresh_html_cache}",
            file=sys.stderr,
        )
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
                    refresh_html_cache=args.refresh_html_cache,
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
        "reuseExistingObservations": reuse_existing_observations,
        "reusedObservationsPath": str(reused_observations_path) if reused_observations_path else None,
        "htmlCacheDir": str(html_cache_dir),
        "htmlCacheSource": html_cache_source,
        "cachedHtmlCountBeforeRun": cache_hit_count,
        "usedHtmlCacheCount": sum(1 for result in results if result.get("usedHtmlCache")),
        "downloadedHtmlCount": sum(1 for result in results if not result.get("usedHtmlCache")),
        "refreshHtmlCache": args.refresh_html_cache,
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
                        "usedHtmlCache": result.get("usedHtmlCache"),
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
