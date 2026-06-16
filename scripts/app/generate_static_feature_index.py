#!/usr/bin/env python3

import argparse
import json
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate byte offsets for canyon static feature entries.")
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    return parser.parse_args()


def build_index(raw: bytes) -> dict[str, dict[str, int]]:
    if not raw:
        return {}

    index: dict[str, dict[str, int]] = {}
    length = len(raw)
    cursor = 0

    def skip_whitespace(position: int) -> int:
        while position < length and chr(raw[position]).isspace():
            position += 1
        return position

    def parse_json_string(position: int) -> tuple[str, int]:
        if raw[position] != ord('"'):
            raise ValueError(f"Expected string at byte {position}")
        position += 1
        chars: list[str] = []
        while position < length:
            byte = raw[position]
            if byte == ord('\\'):
                position += 2
                continue
            if byte == ord('"'):
                return json.loads(raw[position - len(chars) - 1:position + 1].decode("utf-8")), position + 1
            chars.append(chr(byte))
            position += 1
        raise ValueError("Unterminated string")

    def find_value_end(position: int) -> int:
        depth = 0
        in_string = False
        escape = False
        while position < length:
            byte = raw[position]
            if in_string:
                if escape:
                    escape = False
                elif byte == ord('\\'):
                    escape = True
                elif byte == ord('"'):
                    in_string = False
            else:
                if byte == ord('"'):
                    in_string = True
                elif byte in (ord('{'), ord('[')):
                    depth += 1
                elif byte in (ord('}'), ord(']')):
                    depth -= 1
                    if depth == 0:
                        return position + 1
                elif byte == ord(',') and depth == 0:
                    return position
            position += 1
        raise ValueError("Unterminated JSON value")

    cursor = skip_whitespace(cursor)
    if raw[cursor] != ord('{'):
        raise ValueError("Static feature payload must be a JSON object")
    cursor += 1

    while cursor < length:
        cursor = skip_whitespace(cursor)
        if cursor >= length or raw[cursor] == ord('}'):
            break
        key, cursor = parse_json_string(cursor)
        cursor = skip_whitespace(cursor)
        if raw[cursor] != ord(':'):
            raise ValueError(f"Expected ':' after key {key}")
        cursor += 1
        cursor = skip_whitespace(cursor)
        value_start = cursor
        value_end = find_value_end(cursor)
        index[key] = {
            "start": value_start,
            "length": value_end - value_start,
        }
        cursor = skip_whitespace(value_end)
        if cursor < length and raw[cursor] == ord(','):
            cursor += 1

    return index


def main() -> int:
    args = parse_args()
    raw = args.input.read_bytes()
    index = build_index(raw)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(index, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Generated {args.output} with {len(index)} indexed canyon rows")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
