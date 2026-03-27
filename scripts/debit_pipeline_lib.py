from __future__ import annotations

import hashlib
import json
import re
import time
import unicodedata
from json import JSONDecodeError
from datetime import date, datetime, time as datetime_time, timedelta
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from bs4 import BeautifulSoup


BASE_URL = "https://www.descente-canyon.com"
OPEN_METEO_ARCHIVE_URL = "https://archive-api.open-meteo.com/v1/archive"
DEFAULT_USER_AGENT = "DescenteCanyonDebitPipeline/0.1"
DEFAULT_ASSUMED_OBSERVATION_HOUR = 8

DEBIT_CLASS_MAP = {
    "debit1": "CRUE",
    "debit2": "TRES_GROS",
    "debit3": "GROS",
    "debit4": "CORRECT",
    "debit5": "FILET",
    "debit6": "SEC",
}

DEBIT_LEVEL_RANK = {
    "SEC": 0,
    "FILET": 1,
    "CORRECT": 2,
    "GROS": 3,
    "TRES_GROS": 4,
    "CRUE": 5,
    "INCONNU": -1,
}

GEO_POINT_PRIORITY = {
    "ENTREE": 0,
    "PARKING_AMONT": 1,
    "SORTIE": 2,
    "PARKING_AVAL": 3,
    "POINT_REMARQUABLE": 4,
    "ECHAPPATOIRE": 5,
    "UNKNOWN": 6,
}

WEATHER_SOURCE_BY_POINT_TYPE = {
    "ENTREE": "ENTRY",
    "PARKING_AMONT": "UPSTREAM_PARKING",
    "SORTIE": "EXIT",
    "PARKING_AVAL": "DOWNSTREAM_PARKING",
    "POINT_REMARQUABLE": "REMARKABLE_POINT",
    "ECHAPPATOIRE": "ESCAPE",
    "UNKNOWN": "UNKNOWN",
}

FRENCH_MONTHS = {
    "janvier": 1,
    "janv": 1,
    "fevrier": 2,
    "fevr": 2,
    "mars": 3,
    "avril": 4,
    "avr": 4,
    "mai": 5,
    "juin": 6,
    "juillet": 7,
    "juil": 7,
    "aout": 8,
    "septembre": 9,
    "sept": 9,
    "octobre": 10,
    "oct": 10,
    "novembre": 11,
    "nov": 11,
    "decembre": 12,
    "dec": 12,
}

FRENCH_LONG_REGEX = re.compile(r"(?:\w+\.?\s+)?(\d{1,2})\s+([a-z]+\.?)(?:\s+(\d{2,4}))")
DMY_SLASH_REGEX = re.compile(r"(?:\w+\.?\s+)?(\d{1,2})/(\d{1,2})/(\d{2,4})")
FRENCH_DAY_MONTH_REGEX = re.compile(r"(?:\w+\.?\s+)?(\d{1,2})/(\d{1,2})")
DMY_DASH_REGEX = re.compile(r"(\d{2})-(\d{2})-(\d{4})")

EXPLICIT_INVALID_PATTERNS = (
    "sans rapport avec le debit",
    "sans rapport avec le debit actuel",
    "nous serons en stage",
    "nous serons en formation",
    "journee de formation",
    "stage formation",
    "debit inconnu",
    "ni vu ni parcouru",
    "il risque donc d y avoir du monde",
)

STRONG_NON_HYDRO_INFO_KEYWORDS = {
    "cyclistes",
    "stationnement",
    "travaux",
    "chantier",
    "fermeture",
    "ferme",
    "fermee",
    "interdit",
    "interdite",
    "arrete",
    "propriete",
    "militaire",
    "rallye",
    "train",
}

WEAK_NON_HYDRO_INFO_KEYWORDS = {
    "circulation",
    "route",
    "vehicule",
    "vehicules",
    "voiture",
    "acces",
    "parking",
    "club",
    "groupe",
    "monde",
    "info",
    "formation",
    "stage",
    "ffcam",
    "ffme",
    "reservee",
    "reserve",
}

HYDROLOGY_SIGNAL_PATTERNS = {
    "dc shorthand": r"\bdc\b",
    "gd shorthand": r"\bgd\b",
    "top": r"\btop\b",
    "debit": r"\bdebit\b",
    "debit observe": r"\bdebit observe\b",
    "debit actuel": r"\bdebit actuel\b",
    "debit correct": r"\bdebit correct\b",
    "debit parfait": r"\bdebit parfait\b",
    "debit ok": r"\bdebit ok\b",
    "debit normal": r"\bdebit normal\b",
    "debit reel": r"\bdebit reel\b",
    "debit reserve": r"\bdebit reserve\b",
    "debit naturel": r"\bdebit naturel\b",
    "debit libre": r"\bdebit libre\b",
    "trop d eau": r"\btrop d eau\b",
    "gros debit": r"\bgros debit\b",
    "tres gros": r"\btres gros\b",
    "petit debit": r"\bpetit debit\b",
    "petit filet": r"\bpetit filet\b",
    "filet d eau": r"\bfilet d eau\b",
    "goutte a goutte": r"\bgoutte a goutte\b",
    "rien ne coule": r"\brien ne coule\b",
    "ne coule": r"\bne coule(?:nt)?\b",
    "ca coule": r"\bca coule\b",
    "en eau": r"\ben eau\b",
    "peu d eau": r"\b(?:peu|quasi pas|pas) d eau\b",
    "beaucoup d eau": r"\bbeaucoup d eau\b",
    "trace d eau": r"\btrace d eau\b",
    "sec": r"\bsec\b",
    "crue": r"\bcrue\b",
    "impraticable": r"\bimpraticable\b",
    "impassable": r"\bimpassable\b",
    "praticable": r"(?<!im)\bpraticable\b",
    "faisable": r"\bfaisable\b",
    "conditions": r"\ben conditions\b",
    "top conditions": r"\btop conditions?\b",
    "conditions ideales": r"\bconditions? ideal\w*\b",
    "vasque": r"\bvasque(?:s)?\b",
    "cascade": r"\bcascade(?:s)?\b",
    "saut": r"\bsaut(?:s)?\b",
    "toboggan": r"\btoboggan(?:s)?\b",
    "bouillonne": r"\bbouillonn\w*\b",
    "ca crache": r"\bca crache\b",
    "queue de cheval": r"\bqueue de cheval\b",
    "barrage": r"\bbarrage\b",
    "captage": r"\bcaptage\b",
    "capture": r"\bcapte\b",
    "lacher d eau": r"\blach(?:e|er|ers)? d eau\b",
    "deverse": r"\bdevers(?:e|er|ee|ement)\b",
    "turbinage": r"\bturbinage\b",
    "centrale": r"\bcentrale\b",
    "retenue": r"\bretenue\b",
    "robinet": r"\brobinet\b",
    "neige": r"\bneige\b",
    "neve": r"\bneve\b",
    "glace": r"\bglace\b",
    "avalanche": r"\bavalanche(?:s)?\b",
    "fonte": r"\bfonte\b",
    "caudal": r"\bcaudal\b",
    "portata": r"\bportata\b",
    "waterlevel": r"\bwater ?level\b",
    "too much water": r"\btoo much water\b",
    "troppa acqua": r"\btroppa acqua\b",
    "mucha agua": r"\bmucha agua\b",
    "too high": r"\btoo high\b",
    "vu du pont": r"\bvu du pont\b",
    "depuis le pont": r"\bdepuis le pont\b",
    "observation depuis": r"\bobservation(?:s)? depuis\b",
    "vu de la route": r"\bvu(?:e)? de la route\b",
    "vu depuis": r"\bvu(?:e)? depuis\b",
    "orage": r"\borage(?:s)?\b",
    "pluie": r"\bpluie(?:s)?\b",
    "juste ce qu il faut": r"\bjuste ce qu il faut\b",
    "a faire": r"\ba faire\b",
    "c est le moment": r"\bc est le moment\b",
    "au top": r"\bau top\b",
}

CURRENT_OBSERVATION_PATTERNS = {
    "aujourd hui": r"\baujourd hui\b",
    "ce jour": r"\bce jour\b",
    "actuellement": r"\bactuellement\b",
    "vu": r"\bvu(?:e)?\b",
    "observe": r"\bobserv\w+\b",
    "au parking": r"\bau parking\b",
    "au pont": r"\bau pont\b",
    "au depart": r"\bau depart\b",
    "dans le canyon": r"\bdans le canyon\b",
    "a l arrivee": r"\ba l arrivee\b",
    "ce matin": r"\bce matin\b",
    "ce soir": r"\bce soir\b",
    "debit est": r"\bdebit (?:est|etait|reste|demeure|redevient|redevenu)\b",
}


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")


def stable_id(prefix: str, *values: Any) -> str:
    normalized = "|".join("" if value is None else str(value) for value in values)
    digest = hashlib.sha1(normalized.encode("utf-8")).hexdigest()[:16]
    return f"{prefix}_{digest}"


def clean_text(value: str | None) -> str | None:
    if value is None:
        return None
    value = re.sub(r"\s+", " ", value).strip()
    return value or None


def normalize_text(value: str | None) -> str:
    if not value:
        return ""
    value = unicodedata.normalize("NFKD", value)
    value = "".join(character for character in value if not unicodedata.combining(character))
    value = value.lower()
    value = re.sub(r"[^a-z0-9]+", " ", value)
    return re.sub(r"\s+", " ", value).strip()


def unique_non_blank(values: list[str | None]) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for value in values:
        cleaned = clean_text(value)
        if cleaned is None:
            continue
        key = cleaned.casefold()
        if key in seen:
            continue
        seen.add(key)
        result.append(cleaned)
    return result


def normalize_year(value: int) -> int:
    return 2000 + value if 0 <= value <= 99 else value


def parse_observation_date(raw: str) -> str | None:
    value = clean_text(raw)
    if not value:
        return None
    value = value.replace("\u00a0", " ")
    normalized = normalize_text(value)

    for parser in (
        _try_parse_iso,
        _try_parse_french_long,
        _try_parse_dmy_slash,
        _try_parse_french_day_month,
        _try_parse_dmy_dash,
    ):
        parsed = parser(normalized)
        if parsed is not None:
            return parsed.isoformat()
    return None


def _try_parse_iso(value: str) -> date | None:
    try:
        return datetime.strptime(value, "%Y-%m-%d").date()
    except ValueError:
        return None


def _try_parse_french_long(value: str) -> date | None:
    match = FRENCH_LONG_REGEX.search(value)
    if match is None:
        return None
    day = int(match.group(1))
    month = FRENCH_MONTHS.get(match.group(2).rstrip("."))
    year = normalize_year(int(match.group(3))) if match.group(3) else datetime.now().year
    if month is None:
        return None
    try:
        return date(year, month, day)
    except ValueError:
        return None


def _try_parse_dmy_slash(value: str) -> date | None:
    match = DMY_SLASH_REGEX.fullmatch(value)
    if match is None:
        return None
    day = int(match.group(1))
    month = int(match.group(2))
    year = normalize_year(int(match.group(3)))
    try:
        return date(year, month, day)
    except ValueError:
        return None


def _try_parse_french_day_month(value: str) -> date | None:
    match = FRENCH_DAY_MONTH_REGEX.fullmatch(value)
    if match is None:
        return None
    day = int(match.group(1))
    month = int(match.group(2))
    try:
        return date(datetime.now().year, month, day)
    except ValueError:
        return None


def _try_parse_dmy_dash(value: str) -> date | None:
    match = DMY_DASH_REGEX.fullmatch(value)
    if match is None:
        return None
    day = int(match.group(1))
    month = int(match.group(2))
    year = int(match.group(3))
    try:
        return date(year, month, day)
    except ValueError:
        return None


def fetch_text(
    url: str,
    *,
    user_agent: str = DEFAULT_USER_AGENT,
    timeout: int = 30,
    delay_seconds: float = 0.0,
    cache_path: Path | None = None,
) -> str:
    if cache_path is not None and cache_path.exists():
        return cache_path.read_text(encoding="utf-8")

    last_error: Exception | None = None
    for attempt in range(3):
        try:
            if delay_seconds > 0:
                time.sleep(delay_seconds)
            request = Request(url, headers={"User-Agent": user_agent})
            with urlopen(request, timeout=timeout) as response:
                body = response.read().decode("utf-8", errors="replace")
            if cache_path is not None:
                cache_path.parent.mkdir(parents=True, exist_ok=True)
                cache_path.write_text(body, encoding="utf-8")
            return body
        except HTTPError:
            raise
        except Exception as exc:  # noqa: BLE001
            last_error = exc
            if attempt == 2:
                raise
            time.sleep(1.5 * (attempt + 1))

    assert last_error is not None
    raise last_error


def parse_html_line_breaks(container: Any) -> list[str]:
    if container is None:
        return []
    html = container if isinstance(container, str) else str(container)
    soup = BeautifulSoup(f"<div>{html}</div>", "html.parser")
    for br in soup.select("br"):
        br.replace_with("\n")
    text = soup.get_text("", strip=False)
    return [line for line in (clean_text(part) for part in text.splitlines()) if line]


def parse_observation_details(detail_row: Any) -> list[dict[str, str | None]]:
    if detail_row is None:
        return []

    users = [clean_text(user_block.select_one("b").get_text(" ", strip=True) if user_block.select_one("b") else None) for user_block in detail_row.select("div.userc")]
    comments = [clean_text(paragraph.get_text(" ", strip=True)) for paragraph in detail_row.select("p")]
    count = max(len(users), len(comments))
    details: list[dict[str, str | None]] = []
    for index in range(count):
        author = users[index] if index < len(users) else None
        comment = comments[index] if index < len(comments) else None
        if author is None and comment is None:
            continue
        details.append({"author": author, "comment": comment})
    return details


def parse_canyon_debit_page(
    *,
    canyon_id: int,
    canyon_name: str | None,
    html: str,
    source_url: str,
    assumed_observation_hour: int = DEFAULT_ASSUMED_OBSERVATION_HOUR,
) -> list[dict[str, Any]]:
    document = BeautifulSoup(html, "html.parser")
    rows = document.select("table#listedebit tbody tr")
    events: list[dict[str, Any]] = []

    for row_index, row in enumerate(rows, start=1):
        if row.select("td[colspan]"):
            continue

        row_class = " ".join(row.get("class", []))
        level = next((mapped for css_class, mapped in DEBIT_CLASS_MAP.items() if css_class in row_class), None)
        if level is None:
            continue

        cells = row.select("td")
        if len(cells) < 4:
            continue

        date_raw = clean_text(cells[0].get_text(" ", strip=True))
        date_iso = parse_observation_date(date_raw or "")

        authors = parse_html_line_breaks(cells[1].decode_contents())
        observation_icon = cells[2].select_one("span")
        observation_title = clean_text(observation_icon.get("title") if observation_icon is not None else None)
        observation_title_normalized = normalize_text(observation_title)
        if "parcouru" in observation_title_normalized and "non" in observation_title_normalized:
            is_descended = False
        elif "parcouru" in observation_title_normalized:
            is_descended = True
        else:
            is_descended = None

        remark_button = row.select_one("button.lire")
        remark_id = None
        if remark_button is not None:
            raw_id = clean_text(remark_button.get("id"))
            if raw_id and raw_id.startswith("r"):
                remark_id = raw_id[1:]

        detail_row = document.select_one(f"tr#tr{remark_id}") if remark_id else None
        observation_details = parse_observation_details(detail_row)
        detail_comments = unique_non_blank([detail.get("comment") for detail in observation_details])
        primary_comment = "\n\n---\n\n".join(detail_comments) if detail_comments else None

        timestamp_local = None
        if date_iso is not None:
            timestamp_local = datetime.combine(date.fromisoformat(date_iso), datetime_time(hour=assumed_observation_hour)).isoformat(timespec="seconds")

        event_id = stable_id(
            "obs",
            canyon_id,
            date_iso or date_raw,
            level,
            remark_id,
            "|".join(authors),
            primary_comment,
        )
        events.append(
            {
                "observationId": event_id,
                "canyonId": canyon_id,
                "canyonName": canyon_name,
                "dateRaw": date_raw,
                "date": date_iso,
                "assumedObservationHourLocal": assumed_observation_hour,
                "assumedObservationTimeLocal": timestamp_local,
                "niveau": level,
                "niveauRank": DEBIT_LEVEL_RANK.get(level, -1),
                "authors": authors,
                "authorCount": len(authors),
                "primaryAuthor": authors[0] if authors else None,
                "isDescended": is_descended,
                "waterTemperature": clean_text(cells[4].get_text(" ", strip=True)) if len(cells) > 4 else None,
                "airTemperature": clean_text(cells[5].get_text(" ", strip=True)) if len(cells) > 5 else None,
                "comment": primary_comment,
                "comments": detail_comments,
                "remarkId": remark_id,
                "observationTitle": observation_title,
                "sourceUrl": source_url,
                "rowIndex": row_index,
                "rowClass": row_class,
            }
        )

    return events


def load_manual_overrides(path: Path | None) -> list[dict[str, Any]]:
    if path is None or not path.exists():
        return []
    payload = load_json(path)
    if not isinstance(payload, list):
        raise SystemExit(f"Manual overrides must be a JSON array: {path}")
    return payload


def match_manual_override(observation: dict[str, Any], overrides: list[dict[str, Any]]) -> dict[str, Any] | None:
    authors_normalized = {normalize_text(author) for author in observation.get("authors", []) if author}
    for override in overrides:
        if override.get("canyonId") not in (None, observation.get("canyonId")):
            continue
        if override.get("date") not in (None, observation.get("date")):
            continue
        override_author = normalize_text(override.get("author"))
        if override_author and override_author not in authors_normalized:
            continue
        if override.get("remarkId") not in (None, observation.get("remarkId")):
            continue
        return override
    return None


def classify_observation(observation: dict[str, Any], overrides: list[dict[str, Any]]) -> dict[str, Any]:
    manual_override = match_manual_override(observation, overrides)
    if manual_override is not None:
        action = manual_override.get("action", "invalid")
        return {
            "qualityLabel": action,
            "qualityScore": _quality_score_for_label(action),
            "qualityReasons": [f"manual_override:{manual_override.get('reason', 'unspecified')}"] ,
            "manualOverride": True,
        }

    comment = observation.get("comment")
    normalized_comment = normalize_text(comment)
    is_descended = observation.get("isDescended")
    reasons: list[str] = []

    if observation.get("date") is None:
        reasons.append("missing_parsed_date")
        return {
            "qualityLabel": "uncertain",
            "qualityScore": _quality_score_for_label("uncertain"),
            "qualityReasons": reasons,
            "manualOverride": False,
        }

    explicit_invalid_hits = [pattern for pattern in EXPLICIT_INVALID_PATTERNS if pattern in normalized_comment]
    strong_non_hydro_hits = sorted(
        keyword
        for keyword in STRONG_NON_HYDRO_INFO_KEYWORDS
        if re.search(rf"\b{re.escape(keyword)}\b", normalized_comment)
    )
    weak_non_hydro_hits = sorted(
        keyword
        for keyword in WEAK_NON_HYDRO_INFO_KEYWORDS
        if re.search(rf"\b{re.escape(keyword)}\b", normalized_comment)
    )
    hydro_signal_hits = [
        label
        for label, pattern in HYDROLOGY_SIGNAL_PATTERNS.items()
        if re.search(pattern, normalized_comment)
    ]
    current_observation_hits = [
        label
        for label, pattern in CURRENT_OBSERVATION_PATTERNS.items()
        if re.search(pattern, normalized_comment)
    ]
    has_hydrology_comment_signal = bool(hydro_signal_hits)
    has_current_observation_context = bool(current_observation_hits)
    has_strong_non_hydro_signal = bool(strong_non_hydro_hits)
    has_weak_non_hydro_signal = bool(weak_non_hydro_hits)

    if explicit_invalid_hits and is_descended is not True:
        reasons.extend(f"explicit_invalid:{pattern}" for pattern in explicit_invalid_hits)
        return {
            "qualityLabel": "invalid",
            "qualityScore": _quality_score_for_label("invalid"),
            "qualityReasons": reasons,
            "manualOverride": False,
        }

    if is_descended is True:
        reasons.append("descended")
        if hydro_signal_hits:
            reasons.extend(f"hydrology_signal:{phrase}" for phrase in hydro_signal_hits)
        if strong_non_hydro_hits or weak_non_hydro_hits:
            reasons.append("descended_with_non_hydro_context")
        return {
            "qualityLabel": "valid",
            "qualityScore": _quality_score_for_label("valid"),
            "qualityReasons": reasons,
            "manualOverride": False,
        }

    if is_descended is False:
        if not normalized_comment:
            reasons.append("non_descended_without_comment_but_level_selected")
            return {
                "qualityLabel": "valid",
                "qualityScore": _quality_score_for_label("valid"),
                "qualityReasons": reasons,
                "manualOverride": False,
            }
        if has_hydrology_comment_signal:
            reasons.extend(f"hydrology_signal:{phrase}" for phrase in hydro_signal_hits)
            if has_current_observation_context or not has_strong_non_hydro_signal:
                reasons.append("non_descended_hydrology_observation")
                return {
                    "qualityLabel": "valid",
                    "qualityScore": _quality_score_for_label("valid"),
                    "qualityReasons": reasons,
                    "manualOverride": False,
                }
            reasons.extend(f"context_only:{keyword}" for keyword in strong_non_hydro_hits)
            reasons.append("mixed_hydrology_and_logistics")
            return {
                "qualityLabel": "uncertain",
                "qualityScore": _quality_score_for_label("uncertain"),
                "qualityReasons": reasons,
                "manualOverride": False,
            }
        if has_strong_non_hydro_signal:
            reasons.extend(f"non_hydro_info:{keyword}" for keyword in strong_non_hydro_hits)
            return {
                "qualityLabel": "invalid",
                "qualityScore": _quality_score_for_label("invalid"),
                "qualityReasons": reasons,
                "manualOverride": False,
            }
        if has_weak_non_hydro_signal:
            reasons.extend(f"context_only:{keyword}" for keyword in weak_non_hydro_hits)
            reasons.append("non_descended_context_without_hydrology_signal")
            return {
                "qualityLabel": "uncertain",
                "qualityScore": _quality_score_for_label("uncertain"),
                "qualityReasons": reasons,
                "manualOverride": False,
            }
        reasons.append("non_descended_ambiguous_comment")
        return {
            "qualityLabel": "uncertain",
            "qualityScore": _quality_score_for_label("uncertain"),
            "qualityReasons": reasons,
            "manualOverride": False,
        }

    if has_hydrology_comment_signal:
        reasons.extend(f"hydrology_signal:{phrase}" for phrase in hydro_signal_hits)
        if has_current_observation_context:
            reasons.append("hydrology_observation_without_status")
            return {
                "qualityLabel": "valid",
                "qualityScore": _quality_score_for_label("valid"),
                "qualityReasons": reasons,
                "manualOverride": False,
            }
        if has_strong_non_hydro_signal or has_weak_non_hydro_signal:
            reasons.append("mixed_hydrology_and_logistics")
            return {
                "qualityLabel": "uncertain",
                "qualityScore": _quality_score_for_label("uncertain"),
                "qualityReasons": reasons,
                "manualOverride": False,
            }
        reasons.append("default_hydrology_valid")
        return {
            "qualityLabel": "valid",
            "qualityScore": _quality_score_for_label("valid"),
            "qualityReasons": reasons,
            "manualOverride": False,
        }

    if has_strong_non_hydro_signal and not has_hydrology_comment_signal:
        reasons.extend(f"non_hydro_info:{keyword}" for keyword in strong_non_hydro_hits)
        return {
            "qualityLabel": "invalid",
            "qualityScore": _quality_score_for_label("invalid"),
            "qualityReasons": reasons,
            "manualOverride": False,
        }

    reasons.append("default_valid")
    return {
        "qualityLabel": "valid",
        "qualityScore": _quality_score_for_label("valid"),
        "qualityReasons": reasons,
        "manualOverride": False,
    }


def _quality_score_for_label(label: str) -> float:
    if label == "valid":
        return 1.0
    if label == "uncertain":
        return 0.5
    return 0.0


def deduplicate_observations(observations: list[dict[str, Any]]) -> list[dict[str, Any]]:
    seen: set[tuple[Any, ...]] = set()
    deduplicated: list[dict[str, Any]] = []
    for observation in observations:
        key = (
            observation.get("canyonId"),
            observation.get("date"),
            observation.get("niveau"),
            tuple(normalize_text(author) for author in observation.get("authors", [])),
            normalize_text(observation.get("comment")),
            observation.get("remarkId"),
        )
        if key in seen:
            continue
        seen.add(key)
        deduplicated.append(observation)
    return deduplicated


def load_canyon_lookup(canyons_path: Path) -> dict[int, dict[str, Any]]:
    rows = load_json(canyons_path)
    return {int(row["id"]): row for row in rows}


def load_geo_points_lookup(geo_points_path: Path) -> dict[int, list[dict[str, Any]]]:
    grouped: dict[int, list[dict[str, Any]]] = {}
    for row in load_json(geo_points_path):
        grouped.setdefault(int(row["canyonId"]), []).append(row)
    return grouped


def load_watershed_lookup(watersheds_path: Path) -> dict[int, dict[str, Any]]:
    rows = load_json(watersheds_path)
    return {int(row["canyonId"]): row for row in rows}


def build_weather_target(
    *,
    canyon: dict[str, Any],
    geo_points: list[dict[str, Any]],
    watershed: dict[str, Any] | None,
) -> dict[str, Any] | None:
    canyon_id = int(canyon["id"])
    if watershed is not None:
        bbox = watershed.get("bbox") or []
        if len(bbox) == 4:
            min_longitude, min_latitude, max_longitude, max_latitude = bbox
            latitude = (float(min_latitude) + float(max_latitude)) / 2.0
            longitude = (float(min_longitude) + float(max_longitude)) / 2.0
            target_id = stable_id("target", "watershed", round(latitude, 6), round(longitude, 6))
            return {
                "targetId": target_id,
                "canyonId": canyon_id,
                "canyonName": canyon.get("nom"),
                "latitude": latitude,
                "longitude": longitude,
                "source": "WATERSHED_BBOX_CENTER",
                "upstreamCatchmentAreaKm2": watershed.get("upstreamCatchmentAreaKm2"),
                "bbox": bbox,
            }

    best_point = min(geo_points, key=lambda point: GEO_POINT_PRIORITY.get(point.get("type"), 99), default=None)
    if best_point is None:
        return None
    target_id = stable_id(
        "target",
        "point",
        best_point.get("type"),
        round(float(best_point["latitude"]), 6),
        round(float(best_point["longitude"]), 6),
    )
    return {
        "targetId": target_id,
        "canyonId": canyon_id,
        "canyonName": canyon.get("nom"),
        "latitude": float(best_point["latitude"]),
        "longitude": float(best_point["longitude"]),
        "source": WEATHER_SOURCE_BY_POINT_TYPE.get(best_point.get("type"), "UNKNOWN"),
        "upstreamCatchmentAreaKm2": None,
        "geoPointType": best_point.get("type"),
        "geoPointLabel": best_point.get("label"),
    }


def build_observation_window(
    observation: dict[str, Any],
    target: dict[str, Any],
    *,
    lookback_days: int,
) -> dict[str, Any]:
    observation_time = datetime.fromisoformat(observation["assumedObservationTimeLocal"])
    start_time = observation_time - timedelta(days=lookback_days)
    end_time = observation_time
    window_id = stable_id("obswindow", observation["observationId"], target["targetId"], start_time.isoformat(), end_time.isoformat())
    return {
        "observationWindowId": window_id,
        "observationId": observation["observationId"],
        "canyonId": observation["canyonId"],
        "targetId": target["targetId"],
        "targetLatitude": target["latitude"],
        "targetLongitude": target["longitude"],
        "targetSource": target["source"],
        "windowStartLocal": start_time.isoformat(timespec="seconds"),
        "windowEndLocal": end_time.isoformat(timespec="seconds"),
        "archiveStartDate": start_time.date().isoformat(),
        "archiveEndDate": end_time.date().isoformat(),
    }


def merge_windows(
    observation_windows: list[dict[str, Any]],
    *,
    max_gap_days: int = 0,
    max_span_days: int | None = None,
) -> list[dict[str, Any]]:
    merged: list[dict[str, Any]] = []
    grouped: dict[str, list[dict[str, Any]]] = {}
    for window in observation_windows:
        grouped.setdefault(window["targetId"], []).append(window)

    max_gap = timedelta(days=max(max_gap_days, 0))
    max_span = timedelta(days=max_span_days) if max_span_days is not None else None

    for target_id, windows in grouped.items():
        ordered = sorted(windows, key=lambda item: item["windowStartLocal"])
        current: dict[str, Any] | None = None
        current_observation_ids: list[str] = []
        current_canyon_ids: set[int] = set()

        for window in ordered:
            start = datetime.fromisoformat(window["windowStartLocal"])
            end = datetime.fromisoformat(window["windowEndLocal"])
            if current is None:
                current = dict(window)
                current["mergedWindowId"] = stable_id("mergedwindow", target_id, window["windowStartLocal"], window["windowEndLocal"])
                current_observation_ids = [window["observationId"]]
                current_canyon_ids = {int(window["canyonId"])}
                continue

            current_end = datetime.fromisoformat(current["windowEndLocal"])
            current_start = datetime.fromisoformat(current["windowStartLocal"])
            merged_end = max(current_end, end)
            gap = start - current_end
            span = merged_end - current_start
            can_merge = start <= current_end or gap <= max_gap
            if can_merge and (max_span is None or span <= max_span):
                if end > current_end:
                    current["windowEndLocal"] = window["windowEndLocal"]
                    current["archiveEndDate"] = window["archiveEndDate"]
                current_observation_ids.append(window["observationId"])
                current_canyon_ids.add(int(window["canyonId"]))
                continue

            current["observationIds"] = sorted(current_observation_ids)
            current["observationCount"] = len(current_observation_ids)
            current["canyonIds"] = sorted(current_canyon_ids)
            current["fetchSpanDays"] = (
                datetime.fromisoformat(current["windowEndLocal"]) - datetime.fromisoformat(current["windowStartLocal"])
            ).days
            merged.append(current)

            current = dict(window)
            current["mergedWindowId"] = stable_id("mergedwindow", target_id, window["windowStartLocal"], window["windowEndLocal"])
            current_observation_ids = [window["observationId"]]
            current_canyon_ids = {int(window["canyonId"])}

        if current is not None:
            current["observationIds"] = sorted(current_observation_ids)
            current["observationCount"] = len(current_observation_ids)
            current["canyonIds"] = sorted(current_canyon_ids)
            current["fetchSpanDays"] = (
                datetime.fromisoformat(current["windowEndLocal"]) - datetime.fromisoformat(current["windowStartLocal"])
            ).days
            merged.append(current)

    return sorted(merged, key=lambda item: (item["targetId"], item["windowStartLocal"]))


def build_open_meteo_archive_url(
    *,
    latitude: float,
    longitude: float,
    start_date: str,
    end_date: str,
    model: str,
    hourly_variables: list[str],
    timezone: str = "auto",
) -> str:
    params = {
        "latitude": latitude,
        "longitude": longitude,
        "start_date": start_date,
        "end_date": end_date,
        "hourly": ",".join(hourly_variables),
        "timezone": timezone,
        "models": model,
    }
    return f"{OPEN_METEO_ARCHIVE_URL}?{urlencode(params)}"


def fetch_json(
    url: str,
    *,
    user_agent: str = DEFAULT_USER_AGENT,
    timeout: int = 60,
    delay_seconds: float = 0.0,
    cache_path: Path | None = None,
) -> dict[str, Any]:
    if cache_path is not None and cache_path.exists():
        return load_json(cache_path)

    if delay_seconds > 0:
        time.sleep(delay_seconds)

    request = Request(url, headers={"User-Agent": user_agent})
    with urlopen(request, timeout=timeout) as response:
        payload = json.loads(response.read().decode("utf-8"))
    if cache_path is not None:
        write_json(cache_path, payload)
    return payload


def is_retryable_weather_error(exc: Exception) -> bool:
    if isinstance(exc, HTTPError):
        return exc.code in {408, 409, 425, 429, 500, 502, 503, 504}
    return isinstance(exc, (URLError, TimeoutError, JSONDecodeError))


def flatten_open_meteo_hourly_rows(
    *,
    merged_window: dict[str, Any],
    payload: dict[str, Any],
) -> list[dict[str, Any]]:
    hourly = payload.get("hourly") or {}
    times = hourly.get("time") or []
    variable_names = [name for name in hourly.keys() if name != "time"]
    rows: list[dict[str, Any]] = []
    for index, raw_time in enumerate(times):
        row = {
            "mergedWindowId": merged_window["mergedWindowId"],
            "targetId": merged_window["targetId"],
            "timeLocal": raw_time,
            "timezone": payload.get("timezone"),
            "resolvedLatitude": payload.get("latitude"),
            "resolvedLongitude": payload.get("longitude"),
            "resolvedElevation": payload.get("elevation"),
        }
        for name in variable_names:
            values = hourly.get(name) or []
            row[name] = values[index] if index < len(values) else None
        rows.append(row)
    return rows


def extract_hourly_feature_window(hourly_rows: list[dict[str, Any]], end_local: str, hours: int) -> list[dict[str, Any]]:
    end_time = datetime.fromisoformat(end_local)
    start_time = end_time - timedelta(hours=hours)
    return [row for row in hourly_rows if start_time < datetime.fromisoformat(row["timeLocal"]) <= end_time]


def compute_precipitation_features(hourly_rows: list[dict[str, Any]], end_local: str) -> dict[str, Any]:
    features: dict[str, Any] = {}
    precipitation_key = "precipitation"

    for hours, feature_name in ((6, "precip_6h_mm"), (12, "precip_12h_mm"), (24, "precip_24h_mm"), (48, "precip_48h_mm"), (72, "precip_72h_mm"), (24 * 7, "precip_7d_mm")):
        window = extract_hourly_feature_window(hourly_rows, end_local, hours)
        features[feature_name] = round(sum(float(row.get(precipitation_key) or 0.0) for row in window), 3)

    for hours, feature_name in ((1, "max_precip_1h_mm"), (3, "max_precip_3h_mm"), (6, "max_precip_6h_mm"), (12, "max_precip_12h_mm")):
        window = extract_hourly_feature_window(hourly_rows, end_local, hours)
        rolling_values = [float(row.get(precipitation_key) or 0.0) for row in window]
        if hours == 1:
            features[feature_name] = round(max(rolling_values, default=0.0), 3)
            continue
        best = 0.0
        for index in range(len(rolling_values)):
            best = max(best, sum(rolling_values[max(0, index - hours + 1):index + 1]))
        features[feature_name] = round(best, 3)

    thresholds = ((1.0, "hours_since_precip_over_1mm"), (5.0, "hours_since_precip_over_5mm"), (10.0, "hours_since_precip_over_10mm"))
    sorted_rows = sorted(hourly_rows, key=lambda row: row["timeLocal"])
    end_time = datetime.fromisoformat(end_local)
    for threshold, feature_name in thresholds:
        last_time: datetime | None = None
        for row in sorted_rows:
            current_time = datetime.fromisoformat(row["timeLocal"])
            if current_time > end_time:
                break
            if float(row.get(precipitation_key) or 0.0) >= threshold:
                last_time = current_time
        features[feature_name] = int((end_time - last_time).total_seconds() // 3600) if last_time is not None else None

    antecedent_window = extract_hourly_feature_window(hourly_rows, end_local, 24 * 7)
    api_value = 0.0
    decay = 0.85
    for row in sorted(antecedent_window, key=lambda item: item["timeLocal"]):
        api_value = api_value * decay + float(row.get(precipitation_key) or 0.0)
    features["antecedent_precipitation_index"] = round(api_value, 3)
    return features
