from __future__ import annotations

import argparse
import http.cookiejar
import hashlib
import html as html_lib
import json
import math
import re
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import HTTPCookieProcessor, Request, build_opener, urlopen

from debit_pipeline_lib import write_json, write_jsonl


DEFAULT_BASE_URL = "https://www.opencanyon.org/en/reports"
DEFAULT_OUTPUT_DIR = "build/opencanyon/reports"
DEFAULT_USER_AGENT = "DescenteCanyonOpenCanyonDump/0.1 (+local research; respectful cache)"
REPORT_KEY_PATTERN = re.compile(r'wire:key="[^"]*\.table\.records\.(\d+)"')
TOTAL_COUNT_PATTERN = re.compile(r"Showing\s+\d+\s+to\s+\d+\s+of\s+([\d,\.]+)\s+results", re.IGNORECASE)
DATE_PATTERN = re.compile(r"^\d{2}\.\d{2}\.\d{4}$")
REPORT_LIST_COMPONENT_PATTERN = re.compile(
    r'<div\s+wire:snapshot="([^"]+)"\s+wire:effects="[^"]+"\s+wire:id="[^"]+"\s+wire:name="report-list"',
    re.DOTALL,
)
WATER_LEVELS = {"Dry", "Low", "Normal", "High", "Crazy"}
WATER_LEVEL_TO_NIVEAU = {
    "Dry": "SEC",
    "Low": "FILET",
    "Normal": "CORRECT",
    "High": "GROS",
    "Crazy": "CRUE",
}


def normalize_water_level_text(value: str | None) -> str:
    if not value:
        return ""
    normalized = unicodedata_normalize(value).lower()
    normalized = re.sub(r"[^a-z0-9+\-]+", " ", normalized)
    return re.sub(r"\s+", " ", normalized).strip()


def unicodedata_normalize(value: str) -> str:
    import unicodedata

    normalized = unicodedata.normalize("NFKD", value)
    return "".join(character for character in normalized if not unicodedata.combining(character))


def infer_water_level_from_text(value: str) -> tuple[str | None, str | None]:
    match = re.search(r"Wasserstand:\s*\"([^\"]+)\"", value, flags=re.IGNORECASE)
    if match is None:
        match = re.search(r"Water\s*level:\s*\"?([^\"\n,.;)]+)", value, flags=re.IGNORECASE)
    if match is None:
        return None, None

    raw_level = match.group(1).strip()
    normalized = normalize_water_level_text(raw_level)
    if not normalized:
        return None, raw_level

    if any(token in normalized for token in ("extrem", "sehr hoch", "flood", "crue")):
        return "Crazy", raw_level
    if any(token in normalized for token in ("dc+", "hoch", "viel", "high")):
        return "High", raw_level
    if any(token in normalized for token in ("trocken", "dry", "sec")):
        return "Dry", raw_level
    if any(token in normalized for token in ("dc-", "niedrig", "tief", "wenig", "low", "mittel niedrig", "niedrig mittel", "sehr niedrig")):
        return "Low", raw_level
    if any(token in normalized for token in ("dc", "mittel", "normal", "ideal", "ok", "angenehm")):
        return "Normal", raw_level
    return None, raw_level


def parse_completed_status(small_texts: list[str]) -> bool | None:
    for text in small_texts:
        if "Completed" not in text:
            continue
        if "✔" in text:
            return True
        if "✕" in text:
            return False
        return True
    return None


def clean_text(value: str) -> str:
    value = re.sub(r"<br\s*/?>", "\n", value, flags=re.IGNORECASE)
    value = re.sub(r"<[^>]+>", " ", value)
    value = html_lib.unescape(value)
    value = value.replace("\xa0", " ")
    value = re.sub(r"[ \t\r\f\v]+", " ", value)
    value = re.sub(r"\n\s+", "\n", value)
    return re.sub(r"\s+", " ", value).strip()


def clean_comment(value: str) -> str:
    value = re.sub(r"<br\s*/?>", "\n", value, flags=re.IGNORECASE)
    value = re.sub(r"<[^>]+>", "", value)
    value = html_lib.unescape(value)
    value = value.replace("\xa0", " ")
    value = re.sub(r"[ \t\r\f\v]+", " ", value)
    value = re.sub(r"\n{3,}", "\n\n", value)
    return value.strip()


def stable_id(*values: Any) -> str:
    normalized = "|".join("" if value is None else str(value) for value in values)
    return hashlib.sha1(normalized.encode("utf-8")).hexdigest()[:16]


def fetch_page(
    url: str,
    cache_path: Path,
    *,
    user_agent: str,
    refresh_cache: bool,
    timeout: int,
    opener: Any | None = None,
) -> tuple[str, bool]:
    if cache_path.exists() and not refresh_cache:
        return cache_path.read_text(encoding="utf-8"), True

    request = Request(url, headers={"User-Agent": user_agent, "Accept-Language": "en"})
    open_fn = opener.open if opener is not None else urlopen
    with open_fn(request, timeout=timeout) as response:  # noqa: S310 - trusted public source configured by CLI
        payload = response.read().decode("utf-8", errors="replace")
    cache_path.parent.mkdir(parents=True, exist_ok=True)
    cache_path.write_text(payload, encoding="utf-8")
    return payload, False


def page_url(base_url: str, page: int) -> str:
    separator = "&" if "?" in base_url else "?"
    return f"{base_url}{separator}page={page}"


def parse_total_count(page_html: str) -> int | None:
    text = clean_text(page_html)
    match = TOTAL_COUNT_PATTERN.search(text)
    if not match:
        return None
    return int(match.group(1).replace(",", "").replace(".", ""))


def parse_livewire_config(page_html: str) -> tuple[str, str]:
    endpoint = extract_first(r'"uri":"([^"]+)"', page_html)
    csrf = extract_first(r'"csrf":"([^"]+)"', page_html)
    if endpoint is None or csrf is None:
        raise SystemExit("Could not parse OpenCanyon Livewire config")
    endpoint = json.loads(f'"{endpoint}"')
    return endpoint, csrf


def parse_report_list_snapshot(page_html: str) -> str:
    match = REPORT_LIST_COMPONENT_PATTERN.search(page_html)
    if match is None:
        raise SystemExit("Could not parse OpenCanyon report-list Livewire snapshot")
    return html_lib.unescape(match.group(1))


def post_livewire(
    *,
    opener: Any,
    endpoint: str,
    csrf: str,
    snapshot: str,
    updates: dict[str, Any],
    calls: list[dict[str, Any]],
    user_agent: str,
    timeout: int,
) -> dict[str, Any]:
    payload = {
        "_token": csrf,
        "components": [
            {
                "snapshot": snapshot,
                "updates": updates,
                "calls": calls,
            }
        ],
    }
    request = Request(
        endpoint,
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "X-Livewire": "",
            "User-Agent": user_agent,
            "Accept-Language": "en",
        },
    )
    with opener.open(request, timeout=timeout) as response:
        body = response.read().decode("utf-8", errors="replace")
    decoded = json.loads(body)
    return decoded["components"][0]


def initialize_unfiltered_livewire_page(
    *,
    base_url: str,
    cache_path: Path,
    user_agent: str,
    records_per_page: int,
    timeout: int,
) -> tuple[Any, str, str, str, str]:
    cookie_jar = http.cookiejar.CookieJar()
    opener = build_opener(HTTPCookieProcessor(cookie_jar))
    initial_html, _ = fetch_page(
        page_url(base_url, 1),
        cache_path.parent / "initial-filtered-page.html",
        user_agent=user_agent,
        refresh_cache=True,
        timeout=timeout,
        opener=opener,
    )
    endpoint, csrf = parse_livewire_config(initial_html)
    snapshot = parse_report_list_snapshot(initial_html)
    component = post_livewire(
        opener=opener,
        endpoint=endpoint,
        csrf=csrf,
        snapshot=snapshot,
        updates={"tableRecordsPerPage": records_per_page},
        calls=[
            {"path": "", "method": "removeTableFilter", "params": ["only show done reports"]},
            {"path": "", "method": "removeTableFilter", "params": ["dont show imported reports"]},
        ],
        user_agent=user_agent,
        timeout=timeout,
    )
    page_html = component["effects"]["html"]
    cache_path.parent.mkdir(parents=True, exist_ok=True)
    cache_path.write_text(page_html, encoding="utf-8")
    return opener, endpoint, csrf, component["snapshot"], page_html


def fetch_unfiltered_livewire_page(
    *,
    opener: Any,
    endpoint: str,
    csrf: str,
    snapshot: str,
    page: int,
    cache_path: Path,
    user_agent: str,
    timeout: int,
) -> tuple[str, str]:
    component = post_livewire(
        opener=opener,
        endpoint=endpoint,
        csrf=csrf,
        snapshot=snapshot,
        updates={},
        calls=[{"path": "", "method": "gotoPage", "params": [page, "page"]}],
        user_agent=user_agent,
        timeout=timeout,
    )
    page_html = component["effects"]["html"]
    cache_path.parent.mkdir(parents=True, exist_ok=True)
    cache_path.write_text(page_html, encoding="utf-8")
    return component["snapshot"], page_html


def split_report_blocks(page_html: str) -> list[tuple[str, str]]:
    matches = list(REPORT_KEY_PATTERN.finditer(page_html))
    blocks: list[tuple[str, str]] = []
    for index, match in enumerate(matches):
        next_start = matches[index + 1].start() if index + 1 < len(matches) else len(page_html)
        blocks.append((match.group(1), page_html[match.start():next_start]))
    return blocks


def extract_first(pattern: str, value: str, flags: int = 0) -> str | None:
    match = re.search(pattern, value, flags)
    return match.group(1) if match else None


def parse_date(raw_date: str | None) -> str | None:
    if raw_date is None:
        return None
    try:
        parsed = datetime.strptime(raw_date, "%d.%m.%Y")
    except ValueError:
        return None
    return parsed.date().isoformat()


def parse_report_block(report_id: str, block: str, *, page: int, source_url: str) -> dict[str, Any] | None:
    small_texts = [
        clean_text(match.group(1))
        for match in re.finditer(r"<small\b[^>]*>(.*?)</small>", block, flags=re.DOTALL | re.IGNORECASE)
    ]
    small_texts = [text for text in small_texts if text]
    raw_date = next((text for text in small_texts if DATE_PATTERN.match(text)), None)

    canyon_match = re.search(
        r'<a\s+href="https://www\.opencanyon\.org/en/canyon/([^"]+)">([^<]+)</a>',
        block,
        flags=re.IGNORECASE,
    )
    if canyon_match is None:
        return None

    author_match = re.search(
        r'<a\s+href="https://www\.opencanyon\.org/en/profile/([^"]+)">([^<]+)</a>',
        block,
        flags=re.IGNORECASE,
    )
    water_level = None
    water_level_source = None
    source_water_level_text = None
    for text in small_texts:
        for candidate in WATER_LEVELS:
            if re.search(rf"\b{re.escape(candidate)}\b", text):
                water_level = candidate
                water_level_source = "opencanyon_badge"
                break
        if water_level:
            break

    region = None
    if raw_date in small_texts:
        date_index = small_texts.index(raw_date)
        for candidate in small_texts[date_index + 1:]:
            if candidate == canyon_match.group(2) or candidate.startswith("⭐") or candidate.startswith("📖") or candidate.startswith("⚓") or "💧" in candidate:
                continue
            region = candidate
            break

    comment_blocks: list[dict[str, str | None]] = []
    for match in re.finditer(
        r'<div\b([^>]*)class="[^"]*\bwhitespace-pre-wrap\b[^"]*"([^>]*)>(.*?)</div>',
        block,
        flags=re.DOTALL | re.IGNORECASE,
    ):
        attrs = f"{match.group(1)} {match.group(2)}"
        comment = clean_comment(match.group(3))
        if not comment:
            continue
        lang = extract_first(r'lang="([^"]+)"', attrs, flags=re.IGNORECASE)
        visibility = "translated" if "!original_text" in attrs else "original" if "original_text" in attrs else None
        comment_blocks.append({"text": comment, "lang": lang, "visibility": visibility})
    comment_texts = [str(comment["text"]) for comment in comment_blocks if comment.get("text")]
    joined_comments = "\n\n".join(comment_texts)
    import_source = extract_first(r"Automatisch importiert von\s+([^\n(]+)", joined_comments, flags=re.IGNORECASE)
    if import_source is None:
        import_source = extract_first(r"Automatically imported from\s+([^\n(]+)", joined_comments, flags=re.IGNORECASE)
    import_source = import_source.strip() if import_source else None
    is_imported = import_source is not None or "automatisch importiert" in joined_comments.lower() or "automatically imported" in joined_comments.lower()
    is_descente_canyon_import = "descente-canyon" in joined_comments.lower() or (import_source is not None and "descente-canyon" in import_source.lower())
    if water_level is None:
        water_level, source_water_level_text = infer_water_level_from_text(joined_comments)
        if water_level is not None:
            water_level_source = "import_text"
    other_users = [
        {"profileId": profile_id, "name": clean_text(name)}
        for profile_id, name in re.findall(
            r'<a\s+href="https://www\.opencanyon\.org/en/profile/([^"]+)">([^<]+)</a>',
            block,
            flags=re.IGNORECASE,
        )[1:]
    ]

    niveau = WATER_LEVEL_TO_NIVEAU.get(water_level or "")
    return {
        "source": "opencanyon",
        "sourceReportId": report_id,
        "observationId": f"oc_report_{stable_id(report_id)}",
        "sourceUrl": source_url,
        "page": page,
        "dateRaw": raw_date,
        "date": parse_date(raw_date),
        "region": region,
        "openCanyonUuid": canyon_match.group(1),
        "canyonName": clean_text(canyon_match.group(2)),
        "authorProfileId": author_match.group(1) if author_match else None,
        "author": clean_text(author_match.group(2)) if author_match else None,
        "otherUsers": other_users,
        "otherUserCount": len(other_users),
        "waterLevel": water_level,
        "waterLevelSource": water_level_source,
        "sourceWaterLevelText": source_water_level_text,
        "niveau": niveau,
        "targetThreeClass": "LOW" if niveau in {"SEC", "FILET"} else "MEDIUM" if niveau == "CORRECT" else "HIGH" if niveau in {"GROS", "CRUE"} else None,
        "completed": parse_completed_status(small_texts),
        "isImported": is_imported,
        "importSource": import_source,
        "isDescenteCanyonImport": is_descente_canyon_import,
        "comments": comment_blocks,
        "comment": next((comment["text"] for comment in comment_blocks if comment.get("lang") == "en"), None)
        or (comment_blocks[0]["text"] if comment_blocks else None),
        "rawSmallTexts": small_texts,
    }


def deduplicate_reports(reports: list[dict[str, Any]]) -> list[dict[str, Any]]:
    seen: set[tuple[Any, ...]] = set()
    result: list[dict[str, Any]] = []
    for report in reports:
        key = (
            report.get("sourceReportId"),
            report.get("date"),
            report.get("openCanyonUuid"),
            report.get("authorProfileId"),
            report.get("waterLevel"),
        )
        if key in seen:
            continue
        seen.add(key)
        result.append(report)
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description="Dump public OpenCanyon reports as JSONL")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--output-dir", default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--user-agent", default=DEFAULT_USER_AGENT)
    parser.add_argument("--request-delay-ms", type=int, default=300)
    parser.add_argument("--timeout", type=int, default=45)
    parser.add_argument("--max-pages", type=int)
    parser.add_argument("--records-per-page", type=int, choices=[10, 25, 50, 100], default=100)
    parser.add_argument("--refresh-cache", action="store_true")
    parser.add_argument(
        "--use-page-default-filters",
        action="store_true",
        help="Keep OpenCanyon page defaults: hide imported reports and show only completed reports",
    )
    parser.add_argument(
        "--include-descente-canyon-imports",
        action="store_true",
        help="Keep reports imported from descente-canyon.com instead of filtering them out",
    )
    args = parser.parse_args()

    output_dir = Path(args.output_dir)
    cache_dir = output_dir / ("html-cache-default-filters" if args.use_page_default_filters else "html-cache-all-reports")
    request_delay_seconds = max(args.request_delay_ms, 0) / 1000.0

    if args.use_page_default_filters:
        first_html, first_from_cache = fetch_page(
            page_url(args.base_url, 1),
            cache_dir / "reports-page-0001.html",
            user_agent=args.user_agent,
            refresh_cache=args.refresh_cache,
            timeout=args.timeout,
        )
        livewire_context = None
    else:
        first_html_cache_path = cache_dir / f"reports-page-{1:04d}.html"
        if first_html_cache_path.exists() and not args.refresh_cache:
            first_html = first_html_cache_path.read_text(encoding="utf-8")
            first_from_cache = True
            livewire_context = None
        else:
            opener, endpoint, csrf, snapshot, first_html = initialize_unfiltered_livewire_page(
                base_url=args.base_url,
                cache_path=first_html_cache_path,
                user_agent=args.user_agent,
                records_per_page=args.records_per_page,
                timeout=args.timeout,
            )
            first_from_cache = False
            livewire_context = {"opener": opener, "endpoint": endpoint, "csrf": csrf, "snapshot": snapshot}

    total_count = parse_total_count(first_html)
    if total_count is None:
        raise SystemExit("Could not parse OpenCanyon report count")
    records_per_page = 10 if args.use_page_default_filters else args.records_per_page
    page_count = math.ceil(total_count / records_per_page)
    if args.max_pages is not None:
        page_count = min(page_count, args.max_pages)

    reports: list[dict[str, Any]] = []
    page_summaries: list[dict[str, Any]] = []
    failures: list[dict[str, Any]] = []

    for page in range(1, page_count + 1):
        try:
            if page == 1:
                html = first_html
                from_cache = first_from_cache
            else:
                cache_path = cache_dir / f"reports-page-{page:04d}.html"
                if cache_path.exists() and not args.refresh_cache:
                    html = cache_path.read_text(encoding="utf-8")
                    from_cache = True
                elif args.use_page_default_filters:
                    html, from_cache = fetch_page(
                        page_url(args.base_url, page),
                        cache_path,
                        user_agent=args.user_agent,
                        refresh_cache=args.refresh_cache,
                        timeout=args.timeout,
                    )
                else:
                    if livewire_context is None:
                        opener, endpoint, csrf, snapshot, _ = initialize_unfiltered_livewire_page(
                            base_url=args.base_url,
                            cache_path=cache_dir / f"reports-page-{1:04d}.html",
                            user_agent=args.user_agent,
                            records_per_page=args.records_per_page,
                            timeout=args.timeout,
                        )
                        livewire_context = {"opener": opener, "endpoint": endpoint, "csrf": csrf, "snapshot": snapshot}
                    livewire_context["snapshot"], html = fetch_unfiltered_livewire_page(
                        opener=livewire_context["opener"],
                        endpoint=livewire_context["endpoint"],
                        csrf=livewire_context["csrf"],
                        snapshot=livewire_context["snapshot"],
                        page=page,
                        cache_path=cache_path,
                        user_agent=args.user_agent,
                        timeout=args.timeout,
                    )
                    from_cache = False
                if not from_cache and request_delay_seconds:
                    time.sleep(request_delay_seconds)

            page_reports = [
                report
                for report_id, block in split_report_blocks(html)
                if (report := parse_report_block(report_id, block, page=page, source_url=page_url(args.base_url, page))) is not None
            ]
            reports.extend(page_reports)
            page_summaries.append({"page": page, "reportCount": len(page_reports), "fromCache": from_cache})
            if page % 25 == 0 or page == page_count:
                print(f"OpenCanyon reports dump progress: page {page}/{page_count}, reports={len(reports)}")
        except (HTTPError, URLError, TimeoutError) as exc:
            failures.append({"page": page, "url": page_url(args.base_url, page), "error": repr(exc)})

    deduplicated_all_sources = deduplicate_reports(reports)
    deduplicated = [
        report
        for report in deduplicated_all_sources
        if args.include_descente_canyon_imports or not report.get("isDescenteCanyonImport")
    ]
    metadata = {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "baseUrl": args.base_url,
        "reportedTotalCount": total_count,
        "requestedPageCount": page_count,
        "recordsPerPage": records_per_page,
        "usePageDefaultFilters": args.use_page_default_filters,
        "parsedReportCount": len(reports),
        "deduplicatedAllSourceReportCount": len(deduplicated_all_sources),
        "deduplicatedReportCount": len(deduplicated),
        "excludedDescenteCanyonImportCount": len(deduplicated_all_sources) - len(deduplicated),
        "failureCount": len(failures),
        "waterLevelCounts": dict(sorted({level: sum(1 for report in deduplicated if report.get("waterLevel") == level) for level in WATER_LEVELS}.items())),
        "targetThreeClassCounts": {
            label: sum(1 for report in deduplicated if report.get("targetThreeClass") == label)
            for label in ("LOW", "MEDIUM", "HIGH")
        } | {"UNKNOWN": sum(1 for report in deduplicated if report.get("targetThreeClass") is None)},
        "license": "OpenCanyon public data is published under CC BY-NC-SA 4.0 according to https://www.opencanyon.org/en/legal/licenses",
        "filtersRemoved": [] if args.use_page_default_filters else ["Dont show imported reports", "Only show done reports"],
        "includedImportsExceptDescenteCanyon": not args.include_descente_canyon_imports,
        "files": {
            "reports": "opencanyon_reports.jsonl",
            "metadata": "metadata.json",
            "pageSummaries": "page_summaries.json",
        },
    }

    output_dir.mkdir(parents=True, exist_ok=True)
    write_jsonl(output_dir / "opencanyon_reports.jsonl", deduplicated)
    write_json(output_dir / "metadata.json", metadata)
    write_json(output_dir / "page_summaries.json", {"pages": page_summaries, "failures": failures})
    print(json.dumps(metadata, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
