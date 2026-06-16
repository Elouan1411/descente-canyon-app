#!/usr/bin/env python3

import argparse
import json
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Split runtime lookups into core and canyon-specific assets.")
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output-core", required=True, type=Path)
    parser.add_argument("--output-canyons", required=True, type=Path)
    parser.add_argument("--output-index", required=True, type=Path)
    return parser.parse_args()


def write_minified_json(path: Path, payload) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")


def write_canyon_lookup_file(path: Path, canyons: dict[str, object]) -> dict[str, dict[str, int]]:
    path.parent.mkdir(parents=True, exist_ok=True)
    index: dict[str, dict[str, int]] = {}

    with path.open("wb") as handle:
        handle.write(b"{")
        first = True
        for canyon_id, payload in canyons.items():
            if not first:
                handle.write(b",")
            first = False

            key_bytes = json.dumps(canyon_id, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            value_bytes = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            handle.write(key_bytes)
            handle.write(b":")
            value_start = handle.tell()
            handle.write(value_bytes)
            index[canyon_id] = {
                "start": value_start,
                "length": len(value_bytes),
            }

        handle.write(b"}")

    return index


def main() -> int:
    args = parse_args()
    payload = json.loads(args.input.read_text(encoding="utf-8"))

    core = {
        key: value
        for key, value in payload.items()
        if key != "canyons"
    }
    canyons = payload.get("canyons", {})

    write_minified_json(args.output_core, core)
    index = write_canyon_lookup_file(args.output_canyons, canyons)
    write_minified_json(args.output_index, index)

    print(
        f"Generated runtime lookup assets: core={args.output_core.name}, canyons={args.output_canyons.name}, index={args.output_index.name}, canyonRows={len(index)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
