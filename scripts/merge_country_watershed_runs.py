from __future__ import annotations

import argparse
import json
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def parse_country_run(value: str) -> tuple[str, Path]:
    if "=" not in value:
        raise argparse.ArgumentTypeError("Format attendu: Pays=chemin/vers/run")
    country, run_dir = value.split("=", 1)
    country = country.strip()
    if not country:
        raise argparse.ArgumentTypeError("Le pays ne peut pas etre vide")
    return country, Path(run_dir.strip())


def load_run(run_dir: Path) -> dict[str, Any]:
    return {
        "selected": {int(item["canyonId"]): item for item in load_json(run_dir / "selected_entries.json")},
        "cases": load_json(run_dir / "suspicious_cases.json"),
        "summary": load_json(run_dir / "summary.json") if (run_dir / "summary.json").exists() else {},
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Fusionne plusieurs runs de bassin versant avec priorite par pays."
    )
    parser.add_argument(
        "--canyons-json",
        type=Path,
        default=Path("offline-data/full/room-import/canyons.json"),
    )
    parser.add_argument(
        "--country-run",
        type=parse_country_run,
        action="append",
        default=[],
        help="Associe un run a un pays, ex: France=build/watersheds/ign-france-run",
    )
    parser.add_argument(
        "--fallback-run",
        type=Path,
        action="append",
        default=[],
        help="Run(s) utilises si aucun run par pays ne fournit de resultat",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("build/watersheds/hybrid-run"),
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()

    canyons = {int(item["id"]): item for item in load_json(args.canyons_json)}

    run_cache: dict[Path, dict[str, Any]] = {}

    def get_run(run_dir: Path) -> dict[str, Any]:
        if run_dir not in run_cache:
            run_cache[run_dir] = load_run(run_dir)
        return run_cache[run_dir]

    country_to_runs: dict[str, list[Path]] = defaultdict(list)
    for country, run_dir in args.country_run:
        country_to_runs[country].append(run_dir)

    selected_entries: list[dict[str, Any]] = []
    suspicious_cases: list[dict[str, Any]] = []
    source_counts = Counter()
    unresolved = []

    for canyon_id in sorted(canyons):
        canyon = canyons[canyon_id]
        country = canyon.get("pays")

        candidate_run_dirs = list(country_to_runs.get(country, [])) + list(args.fallback_run)
        chosen_run_dir: Path | None = None
        chosen_payload: dict[str, Any] | None = None

        for run_dir in candidate_run_dirs:
            run = get_run(run_dir)
            payload = run["selected"].get(canyon_id)
            if payload is None:
                continue
            if payload.get("selectedEntryIndex") is not None:
                chosen_run_dir = run_dir
                chosen_payload = payload
                break
            if chosen_payload is None:
                chosen_run_dir = run_dir
                chosen_payload = payload

        if chosen_payload is None:
            unresolved.append(
                {
                    "canyonId": canyon_id,
                    "canyonName": canyon.get("nomComplet") or canyon.get("nom"),
                    "country": country,
                }
            )
            continue

        payload_copy = dict(chosen_payload)
        payload_copy["sourceRun"] = str(chosen_run_dir)
        selected_entries.append(payload_copy)
        source_counts[str(chosen_run_dir)] += 1

        run_cases = get_run(chosen_run_dir)["cases"]
        for case in run_cases:
            if int(case["canyonId"]) != canyon_id:
                continue
            case_copy = dict(case)
            case_copy["sourceRun"] = str(chosen_run_dir)
            suspicious_cases.append(case_copy)

    summary = {
        "canyonsMerged": len(selected_entries),
        "canyonsUnresolved": len(unresolved),
        "sourceCounts": dict(source_counts),
    }

    output_dir = args.output_dir
    write_json(output_dir / "selected_entries.json", selected_entries)
    write_json(output_dir / "suspicious_cases.json", suspicious_cases)
    write_json(output_dir / "summary.json", summary)
    write_json(output_dir / "unresolved_canyons.json", unresolved)

    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
