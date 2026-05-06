from __future__ import annotations

import argparse
import json
import re
import urllib.request
from pathlib import Path
from typing import Any


BDALTI_URL = "https://geoservices.ign.fr/bdalti"
RGEALTI_URL = "https://geoservices.ign.fr/rgealti"


def fetch_text(url: str) -> str:
    with urllib.request.urlopen(url, timeout=60) as response:
        return response.read().decode("utf-8", "ignore")


def normalize_spaces(value: str) -> str:
    return re.sub(r"\s+", " ", value.replace("&nbsp;", " ")).strip()


def extract_catalog(html: str, product_keyword: str) -> list[dict[str, Any]]:
    pattern = re.compile(
        r"D[ée]partement\s+([0-9AB]{2,3})\s*-\s*([^:<]+?)\s*:</p>\s*<ul>(.*?)</ul>",
        re.S | re.I,
    )
    link_pattern = re.compile(r'href="([^"]+' + re.escape(product_keyword) + r'[^"]+)"', re.I)
    entries = []

    for department_code, department_name, block in pattern.findall(html):
        urls = [normalize_spaces(url) for url in link_pattern.findall(block)]
        if not urls:
            continue
        entries.append(
            {
                "departmentCode": department_code,
                "departmentName": normalize_spaces(department_name),
                "urls": urls,
            }
        )

    return entries


def slice_between_markers(html: str, start_marker: str, end_marker: str | None) -> str:
    start = html.find(start_marker)
    if start < 0:
        return ""
    end = html.find(end_marker, start + len(start_marker)) if end_marker else -1
    if end < 0:
        end = len(html)
    return html[start:end]


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Recupere le catalogue IGN BD ALTI / RGE ALTI.")
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("build/watersheds/ign-catalog"),
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    bdalti_html = fetch_text(BDALTI_URL)
    rgealti_html = fetch_text(RGEALTI_URL)

    bdalti = extract_catalog(bdalti_html, "BDALTIV2")
    rgealti_1m = extract_catalog(
        slice_between_markers(rgealti_html, 'id="rge-alti-1-m"', 'id="rge-alti-5-m"'),
        "RGEALTI_2-0_1M",
    )
    rgealti_5m = extract_catalog(
        slice_between_markers(rgealti_html, 'id="rge-alti-5-m"', None),
        "RGEALTI_2-0_5M",
    )

    summary = {
        "bdAltiDepartments": len(bdalti),
        "rgeAlti1mDepartments": len(rgealti_1m),
        "rgeAlti5mDepartments": len(rgealti_5m),
    }

    output_dir = args.output_dir
    write_json(output_dir / "bdalti_catalog.json", bdalti)
    write_json(output_dir / "rgealti_1m_catalog.json", rgealti_1m)
    write_json(output_dir / "rgealti_5m_catalog.json", rgealti_5m)
    write_json(output_dir / "summary.json", summary)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
