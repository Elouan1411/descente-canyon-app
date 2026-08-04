#!/usr/bin/env python3

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import time
import unicodedata
import warnings
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlencode, urljoin, urlparse, urlunparse
from urllib.request import Request, urlopen

from bs4 import BeautifulSoup, FeatureNotFound, XMLParsedAsHTMLWarning


BASE_URL = "https://www.descente-canyon.com"
DEFAULT_FEED_URLS = [
    f"{BASE_URL}/forums/feed",
    f"{BASE_URL}/forums/feed/topics_active",
]
DEFAULT_USER_AGENT = "DescenteCanyonForumUserExtractor/0.1"
SKIPPED_USERNAMES = {
    "",
    "utilisateur non enregistre",
    "utilisateur non inscrit",
    "anonyme",
}


@dataclass
class UserRecord:
    username: str
    normalized_username: str
    forum_user_id: int | None = None
    profile_url: str | None = None
    has_forum_activity: bool = False
    has_debit_activity: bool = False
    forum_post_count: int = 0
    debit_observation_count: int = 0
    last_forum_post_at: str | None = None
    last_forum_post_url: str | None = None
    last_debit_observation_at: str | None = None
    last_debit_observation_url: str | None = None
    sources: set[str] = field(default_factory=set)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Extract forum_users.json from debit observations and public forum pages.")
    parser.add_argument("--room-import-dir", type=Path, default=Path("offline-data/full/room-import"))
    parser.add_argument("--output", type=Path)
    parser.add_argument(
        "--debit-observations-path",
        type=Path,
        default=Path("build/debit-pipeline/observations/all_debit_observations.jsonl"),
    )
    parser.add_argument("--skip-debits", action="store_true")
    parser.add_argument("--skip-forum", action="store_true")
    parser.add_argument("--forum-active-topic-pages", type=int, default=20)
    parser.add_argument("--forum-author-id-min", type=int)
    parser.add_argument("--forum-author-id-max", type=int)
    parser.add_argument("--forum-feed-url", action="append", dest="forum_feed_urls")
    parser.add_argument("--cache-dir", type=Path, default=Path("build/forum-user-extractor/cache"))
    parser.add_argument("--refresh-cache", action="store_true")
    parser.add_argument("--request-delay-ms", type=int, default=250)
    parser.add_argument("--user-agent", default=DEFAULT_USER_AGENT)
    return parser.parse_args()


def clean_text(value: str | None) -> str | None:
    if value is None:
        return None
    value = re.sub(r"\s+", " ", value).strip()
    return value or None


def normalize_username(value: str | None) -> str:
    value = unicodedata.normalize("NFKD", value or "")
    value = "".join(char for char in value if not unicodedata.combining(char))
    value = value.lower()
    value = "".join(char if char.isalnum() else " " for char in value)
    return re.sub(r"\s+", " ", value).strip()


def is_valid_username(username: str | None) -> bool:
    normalized = normalize_username(username)
    return bool(normalized) and normalized not in SKIPPED_USERNAMES


def sanitize_url(value: str | None) -> str | None:
    if not value:
        return None
    absolute = urljoin(BASE_URL, value)
    parsed = urlparse(absolute)
    query = parse_qs(parsed.query, keep_blank_values=True)
    query.pop("sid", None)
    return urlunparse(parsed._replace(query=urlencode(query, doseq=True)))


def extract_forum_user_id(url: str | None) -> int | None:
    if not url:
        return None
    values = parse_qs(urlparse(url).query).get("u")
    if not values:
        return None
    try:
        return int(values[0])
    except ValueError:
        return None


def is_newer(candidate: str | None, current: str | None) -> bool:
    if candidate is None:
        return False
    if current is None:
        return True
    return candidate > current


def user_key(username: str) -> str:
    return normalize_username(username)


def get_or_create(users: dict[str, UserRecord], username: str) -> UserRecord | None:
    username = clean_text(username) or ""
    if not is_valid_username(username):
        return None
    key = user_key(username)
    existing = users.get(key)
    if existing is not None:
        return existing
    record = UserRecord(username=username, normalized_username=key)
    users[key] = record
    return record


def record_debit_author(
    users: dict[str, UserRecord],
    *,
    username: str,
    observed_at: str | None,
    source_url: str | None,
) -> None:
    record = get_or_create(users, username)
    if record is None:
        return
    record.has_debit_activity = True
    record.sources.add("DEBIT")
    record.debit_observation_count += 1
    if is_newer(observed_at, record.last_debit_observation_at):
        record.last_debit_observation_at = observed_at
        record.last_debit_observation_url = sanitize_url(source_url)


def record_forum_author(
    users: dict[str, UserRecord],
    *,
    username: str,
    forum_user_id: int | None = None,
    profile_url: str | None = None,
    post_count: int = 1,
    posted_at: str | None = None,
    post_url: str | None = None,
) -> None:
    record = get_or_create(users, username)
    if record is None:
        return
    record.has_forum_activity = True
    record.sources.add("FORUM")
    record.forum_post_count = max(record.forum_post_count, 0) + max(post_count, 0)
    if forum_user_id is not None:
        record.forum_user_id = forum_user_id
    if profile_url:
        record.profile_url = sanitize_url(profile_url)
    if is_newer(posted_at, record.last_forum_post_at):
        record.last_forum_post_at = posted_at
        record.last_forum_post_url = sanitize_url(post_url)


def cache_path_for_url(cache_dir: Path, url: str) -> Path:
    digest = hashlib.sha256(url.encode("utf-8")).hexdigest()
    return cache_dir / f"{digest}.html"


def fetch_text(
    url: str,
    *,
    cache_dir: Path,
    refresh_cache: bool,
    user_agent: str,
    delay_seconds: float,
) -> str:
    cache_path = cache_path_for_url(cache_dir, url)
    if cache_path.exists() and not refresh_cache:
        return cache_path.read_text(encoding="utf-8")

    if delay_seconds > 0:
        time.sleep(delay_seconds)
    request = Request(url, headers={"User-Agent": user_agent})
    with urlopen(request, timeout=30) as response:
        body = response.read().decode("utf-8", errors="replace")
    cache_path.parent.mkdir(parents=True, exist_ok=True)
    cache_path.write_text(body, encoding="utf-8")
    return body


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            stripped = line.strip()
            if stripped:
                rows.append(json.loads(stripped))
    return rows


def collect_debit_users(users: dict[str, UserRecord], observations_path: Path) -> int:
    if not observations_path.exists():
        print(f"Debit observations missing: {observations_path}", file=sys.stderr)
        return 0
    observations = read_jsonl(observations_path)
    for observation in observations:
        observed_at = observation.get("assumedObservationTimeLocal") or observation.get("date")
        source_url = observation.get("sourceUrl")
        for author in observation.get("authors") or []:
            record_debit_author(users, username=str(author), observed_at=observed_at, source_url=source_url)
    return len(observations)


def collect_feed_users(
    users: dict[str, UserRecord],
    *,
    url: str,
    cache_dir: Path,
    refresh_cache: bool,
    user_agent: str,
    delay_seconds: float,
) -> int:
    html = fetch_text(
        url,
        cache_dir=cache_dir,
        refresh_cache=refresh_cache,
        user_agent=user_agent,
        delay_seconds=delay_seconds,
    )
    try:
        soup = BeautifulSoup(html, "xml")
    except FeatureNotFound:
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", XMLParsedAsHTMLWarning)
            soup = BeautifulSoup(html, "html.parser")
    count = 0
    for entry in soup.find_all("entry"):
        author = entry.find("author")
        name = clean_text(author.find("name").get_text(" ", strip=True) if author and author.find("name") else None)
        uri = clean_text(author.find("uri").get_text(" ", strip=True) if author and author.find("uri") else None)
        link = entry.find("link")
        post_url = link.get("href") if link is not None else None
        date_node = entry.find("updated") or entry.find("published") or entry.find("id")
        posted_at = clean_text(date_node.get_text(" ", strip=True) if date_node is not None else None)
        if name:
            record_forum_author(
                users,
                username=name,
                forum_user_id=extract_forum_user_id(uri),
                profile_url=uri,
                posted_at=posted_at,
                post_url=post_url,
            )
            count += 1
    return count


def collect_active_topic_users(
    users: dict[str, UserRecord],
    *,
    page_count: int,
    cache_dir: Path,
    refresh_cache: bool,
    user_agent: str,
    delay_seconds: float,
) -> int:
    count = 0
    for page_index in range(max(page_count, 0)):
        start = page_index * 25
        url = f"{BASE_URL}/forums/search.php?search_id=active_topics&start={start}"
        html = fetch_text(
            url,
            cache_dir=cache_dir,
            refresh_cache=refresh_cache,
            user_agent=user_agent,
            delay_seconds=delay_seconds,
        )
        soup = BeautifulSoup(html, "html.parser")
        for link in soup.select("a[href*='memberlist.php?mode=viewprofile'][href*='u=']"):
            username = clean_text(link.get_text(" ", strip=True))
            profile_url = sanitize_url(link.get("href"))
            container = link.find_parent(["li", "tr", "div"]) or soup
            time_node = container.select_one("time[datetime]") if container else None
            posted_at = clean_text(time_node.get("datetime") if time_node is not None else None)
            post_link = container.select_one("a[href*='viewtopic.php']") if container else None
            record_forum_author(
                users,
                username=username or "",
                forum_user_id=extract_forum_user_id(profile_url),
                profile_url=profile_url,
                posted_at=posted_at,
                post_url=post_link.get("href") if post_link is not None else None,
            )
            count += 1
    return count


def collect_author_id_scan_users(
    users: dict[str, UserRecord],
    *,
    author_id_min: int,
    author_id_max: int,
    cache_dir: Path,
    refresh_cache: bool,
    user_agent: str,
    delay_seconds: float,
) -> int:
    count = 0
    for author_id in range(author_id_min, author_id_max + 1):
        url = f"{BASE_URL}/forums/search.php?author_id={author_id}&sr=posts&sk=t&sd=d"
        try:
            html = fetch_text(
                url,
                cache_dir=cache_dir,
                refresh_cache=refresh_cache,
                user_agent=user_agent,
                delay_seconds=delay_seconds,
            )
        except Exception as exc:  # noqa: BLE001
            print(f"author_id={author_id} failed: {exc!r}", file=sys.stderr)
            continue
        soup = BeautifulSoup(html, "html.parser")
        profile_link = soup.select_one(f"a[href*='memberlist.php?mode=viewprofile'][href*='u={author_id}']")
        if profile_link is None:
            continue
        username = clean_text(profile_link.get_text(" ", strip=True))
        result_text = clean_text(soup.select_one("h2").get_text(" ", strip=True) if soup.select_one("h2") else None) or ""
        match = re.search(r"(\d+)\s+result", result_text)
        result_count = int(match.group(1)) if match else len(soup.select("div.search.post"))
        first_post = soup.select_one("div.search.post")
        post_link = first_post.select_one("h3 a[href]") if first_post is not None else None
        date_node = first_post.select_one("dd.search-result-date") if first_post is not None else None
        record_forum_author(
            users,
            username=username or "",
            forum_user_id=author_id,
            profile_url=profile_link.get("href"),
            post_count=max(result_count, 1),
            posted_at=clean_text(date_node.get_text(" ", strip=True) if date_node is not None else None),
            post_url=post_link.get("href") if post_link is not None else None,
        )
        count += 1
        if count % 50 == 0:
            print(f"Forum author scan: found={count} latestAuthorId={author_id}", file=sys.stderr)
    return count


def source_label(record: UserRecord) -> str:
    has_forum = record.has_forum_activity
    has_debit = record.has_debit_activity
    if has_forum and has_debit:
        return "FORUM_AND_DEBIT"
    if has_forum:
        return "FORUM"
    return "DEBIT"


def to_output_rows(users: dict[str, UserRecord], generated_at: str) -> list[dict[str, Any]]:
    rows = []
    for record in users.values():
        rows.append(
            {
                "username": record.username,
                "normalizedUsername": record.normalized_username,
                "forumUserId": record.forum_user_id,
                "profileUrl": record.profile_url,
                "source": source_label(record),
                "hasForumActivity": record.has_forum_activity,
                "hasDebitActivity": record.has_debit_activity,
                "forumPostCount": record.forum_post_count,
                "debitObservationCount": record.debit_observation_count,
                "lastForumPostAt": record.last_forum_post_at,
                "lastForumPostUrl": record.last_forum_post_url,
                "lastDebitObservationAt": record.last_debit_observation_at,
                "lastDebitObservationUrl": record.last_debit_observation_url,
                "updatedAt": generated_at,
            }
        )
    return sorted(rows, key=lambda item: item["normalizedUsername"])


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def update_manifest(room_import_dir: Path, generated_at: str, user_count: int) -> None:
    manifest_path = room_import_dir / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else {}
    tables = dict(manifest.get("tables", {}))
    counts = dict(manifest.get("counts", {}))
    versions = dict(manifest.get("versions", {}))
    tables["forum_users"] = "forum_users.json"
    counts["forum_users"] = user_count
    versions["forum_users"] = generated_at
    manifest.update(
        {
            "schemaVersion": max(int(manifest.get("schemaVersion", 0)), 3),
            "generatedAt": generated_at,
            "tables": tables,
            "counts": counts,
            "versions": versions,
        }
    )
    write_json(manifest_path, manifest)


def main() -> int:
    args = parse_args()
    delay_seconds = max(args.request_delay_ms, 0) / 1000.0
    generated_at = datetime.now(timezone.utc).isoformat()
    output_path = args.output or (args.room_import_dir / "forum_users.json")
    users: dict[str, UserRecord] = {}

    debit_observation_count = 0
    if not args.skip_debits:
        debit_observation_count = collect_debit_users(users, args.debit_observations_path)
        print(f"Debit observations processed: {debit_observation_count}", file=sys.stderr)

    forum_event_count = 0
    if not args.skip_forum:
        for feed_url in args.forum_feed_urls or DEFAULT_FEED_URLS:
            forum_event_count += collect_feed_users(
                users,
                url=feed_url,
                cache_dir=args.cache_dir,
                refresh_cache=args.refresh_cache,
                user_agent=args.user_agent,
                delay_seconds=delay_seconds,
            )
        forum_event_count += collect_active_topic_users(
            users,
            page_count=args.forum_active_topic_pages,
            cache_dir=args.cache_dir,
            refresh_cache=args.refresh_cache,
            user_agent=args.user_agent,
            delay_seconds=delay_seconds,
        )
        if args.forum_author_id_min is not None and args.forum_author_id_max is not None:
            forum_event_count += collect_author_id_scan_users(
                users,
                author_id_min=args.forum_author_id_min,
                author_id_max=args.forum_author_id_max,
                cache_dir=args.cache_dir,
                refresh_cache=args.refresh_cache,
                user_agent=args.user_agent,
                delay_seconds=delay_seconds,
            )
        print(f"Forum author events processed: {forum_event_count}", file=sys.stderr)

    rows = to_output_rows(users, generated_at)
    write_json(output_path, rows)
    update_manifest(args.room_import_dir, generated_at, len(rows))

    summary = {
        "generatedAt": generated_at,
        "output": str(output_path),
        "userCount": len(rows),
        "forumUserCount": sum(1 for row in rows if row["hasForumActivity"]),
        "debitUserCount": sum(1 for row in rows if row["hasDebitActivity"]),
        "forumAndDebitUserCount": sum(1 for row in rows if row["source"] == "FORUM_AND_DEBIT"),
        "debitObservationCount": debit_observation_count,
        "forumEventCount": forum_event_count,
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2), file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
