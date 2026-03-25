from __future__ import annotations

import json
import re
import unicodedata
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.parse import urljoin, urlparse
from urllib.request import Request, build_opener

from bs4 import BeautifulSoup


BASE_DIR = Path(__file__).resolve().parent.parent
ADN_INDEX_URL = "https://adncanyoning.com/mon-feedback-sur-les-canyons-que-jai-parcourus/"
USER_AGENT = "DescenteCanyonAppAdnEnricher/0.1"

FULL_CANYONS_DIR = BASE_DIR / "offline-data" / "full" / "canyons"
ROOM_IMPORT_DIR = BASE_DIR / "offline-data" / "full" / "room-import"
OPTIMIZED_DIR = BASE_DIR / "offline-data" / "full" / "optimized"
OPTIMIZED_SHARDS_DIR = OPTIMIZED_DIR / "shards"
INDEX_JSON_PATH = BASE_DIR / "offline-data" / "full" / "index.json"

TEXT_FIELDS = [
    "Cotation",
    "Lieu",
    "Parking",
    "Départ du canyon",
    "Sortie du canyon",
    "Altitude de départ",
    "Dénivelé",
    "Longueur",
    "Cours d’eau",
    "Rappel le plus haut",
    "Fractionnement",
    "Longueur de corde minimale à simple",
    "Echappatoire",
    "Temps de parcours",
    "Type de roche",
    "Couvert végétal",
    "Bassin versant",
    "Ouvrage artificiel",
    "Réglementation spécifique",
    "Période favorable",
]

DESCRIPTION_FIELDS = [
    "Approche",
    "Parcours",
    "Retour",
    "Remarques générales",
]

RAW_TYPE_BY_POINT_TYPE = {
    "ENTREE": "depart",
    "SORTIE": "arrivee",
    "PARKING_AMONT": "parking_amont",
    "PARKING_AVAL": "parking_aval",
    "POINT_REMARQUABLE": "point_externe",
}

REPRESENTATIVE_POINT_ORDER = {
    "PARKING_AMONT": 0,
    "PARKING_AVAL": 1,
    "ENTREE": 2,
    "SORTIE": 3,
    "POINT_REMARQUABLE": 4,
}

GENERIC_NAME_TOKENS = {
    "canyon",
    "canyons",
    "torrent",
    "torrente",
    "ravin",
    "ruisseau",
    "rio",
    "gorge",
    "gorges",
    "cascades",
    "cascade",
    "de",
    "des",
    "du",
    "d",
    "la",
    "le",
    "les",
    "l",
    "ou",
}

ARTICLE_OVERRIDES = {
    "frontenex": 2140,
    "la-belle-au-bois": 2137,
    "canyon-des-rots-ou-de-balme": 2145,
    "le-saut-du-moine": 22112,
    "le-sierroz": 2365,
    "reposoir": 2128,
    "ruisseau-des-lavanches": 21895,
    "torrent-de-la-ravoire": 22121,
    "canyon-de-lalloix": 2116,
    "canyon-du-furon-amont": 26,
    "canyon-du-furon-aval": 27,
    "versoud-aval": 210,
    "innersandbach": 23205,
    "piscia-di-gallu": 2334,
    "canyon-de-purcaraccia": 2311,
    "torrent-de-chichin": 2265,
    "les-oules-de-freissinieres": 2266,
    "tramouillon-inf": 2257,
    "biez-des-cruies": 2163,
    "moulin-de-vulvoz": 2210,
    "le-canyon-du-groin": 2175,
    "semine-integral": None,
    "grosdar": None,
    "page_id=784": 2113,
}

TIME_PATTERN = re.compile(r"(\d{1,2}:\d{2}|\d+\s*(?:h|min|mn))", re.IGNORECASE)
NUMBER_PATTERN = re.compile(r"-?\d+(?:[.,]\d+)?")
COORD_PATTERN = re.compile(r"!3d(-?[\d.]+)!4d(-?[\d.]+)|@(-?[\d.]+),(-?[\d.]+)")

_OPENER = build_opener()
_TEXT_CACHE: dict[str, str] = {}
_MAP_CACHE: dict[str, tuple[float, float] | None] = {}


@dataclass
class ArticleData:
    url: str
    slug: str
    title: str
    info: dict[str, str]
    description: dict[str, str]


def absolute_url(url: str) -> str:
    return urljoin(ADN_INDEX_URL, url)


def fetch_text(url: str) -> str:
    cached = _TEXT_CACHE.get(url)
    if cached is not None:
        return cached
    request = Request(url, headers={"User-Agent": USER_AGENT})
    with _OPENER.open(request, timeout=30) as response:
        content_type = response.headers.get_content_charset() or "utf-8"
        text = response.read().decode(content_type, errors="replace")
    _TEXT_CACHE[url] = text
    return text


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, payload: Any) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def normalize_text(value: str | None) -> str:
    if not value:
        return ""
    normalized = unicodedata.normalize("NFKD", value)
    normalized = "".join(ch for ch in normalized if not unicodedata.combining(ch))
    normalized = normalized.lower().replace("œ", "oe")
    normalized = re.sub(r"[^a-z0-9]+", " ", normalized)
    return " ".join(normalized.split())


def tokenize_name(value: str | None) -> list[str]:
    return [token for token in normalize_text(value).split() if token and token not in GENERIC_NAME_TOKENS]


def extract_fields(text: str, labels: list[str]) -> dict[str, str]:
    escaped = "|".join(re.escape(label) for label in labels)
    pattern = re.compile(rf"(?P<label>{escaped})\s*:\s*(?P<value>.*?)(?=(?:{escaped})\s*:|$)", re.S)
    fields: dict[str, str] = {}
    for match in pattern.finditer(text):
        label = match.group("label")
        value = " ".join(match.group("value").split())
        fields[label] = value
    return fields


def extract_time_fragment(value: str | None) -> str | None:
    if not value:
        return None
    match = TIME_PATTERN.search(value)
    return match.group(1).strip() if match else None


def parse_int(value: str | None) -> int | None:
    if not value:
        return None
    match = NUMBER_PATTERN.search(value)
    if not match:
        return None
    return int(float(match.group(0).replace(",", ".")))


def parse_article(url: str) -> ArticleData | None:
    html = fetch_text(url)
    soup = BeautifulSoup(html, "html.parser")
    content = soup.select_one(".wp-block-post-content")
    if content is None:
        return None

    blocks = [
        " ".join(node.get_text(" ", strip=True).split())
        for node in content.find_all(["p", "h2", "h3"], recursive=True)
    ]
    if not any(block.startswith("Les infos générales") for block in blocks):
        return None

    title_node = soup.select_one(".wp-block-post-title") or soup.find("h1") or soup.find("h2")
    title = " ".join(title_node.get_text(" ", strip=True).split()) if title_node else url

    info_text = ""
    description_blocks: list[str] = []
    in_description = False
    for index, block in enumerate(blocks):
        if block.startswith("Les infos générales") and index + 1 < len(blocks):
            info_text = blocks[index + 1]
        elif block.startswith("Description"):
            in_description = True
        elif in_description:
            if block.startswith("Partager"):
                break
            description_blocks.append(block)

    info_fields = extract_fields(info_text, TEXT_FIELDS)
    description_fields = extract_fields(" ".join(description_blocks), DESCRIPTION_FIELDS)

    parsed = urlparse(url)
    slug = parsed.query or parsed.path.rstrip("/").split("/")[-1]
    return ArticleData(url=url, slug=slug, title=title, info=info_fields, description=description_fields)


def fetch_article_urls() -> list[str]:
    html = fetch_text(ADN_INDEX_URL)
    soup = BeautifulSoup(html, "html.parser")
    content = soup.select_one(".wp-block-post-content")
    if content is None:
        raise SystemExit("Impossible de trouver le contenu ADN")

    urls: list[str] = []
    seen: set[str] = set()
    for anchor in content.find_all("a", href=True):
        href = absolute_url(anchor["href"])
        if not href.startswith("https://adncanyoning.com/"):
            continue
        if href == ADN_INDEX_URL:
            continue
        if href in seen:
            continue
        seen.add(href)
        urls.append(href)
    return urls


def resolve_map_coordinates(url: str | None) -> tuple[float, float] | None:
    if not url:
        return None
    cached = _MAP_CACHE.get(url)
    if cached is not None:
        return cached
    try:
        request = Request(url, headers={"User-Agent": USER_AGENT})
        with _OPENER.open(request, timeout=30) as response:
            final_url = response.geturl()
    except Exception:
        _MAP_CACHE[url] = None
        return None
    match = COORD_PATTERN.search(final_url)
    if not match:
        _MAP_CACHE[url] = None
        return None
    if match.group(1) and match.group(2):
        coords = (float(match.group(1)), float(match.group(2)))
    else:
        coords = (float(match.group(3)), float(match.group(4)))
    _MAP_CACHE[url] = coords
    return coords


def parse_point_urls(article: ArticleData) -> dict[str, tuple[float, float]]:
    point_urls: dict[str, str] = {}

    parking_value = article.info.get("Parking")
    if parking_value:
        amont = re.search(r"amont\s*:\s*(https?://\S+)", parking_value, re.IGNORECASE)
        aval = re.search(r"aval\s*:\s*(https?://\S+)", parking_value, re.IGNORECASE)
        if amont:
            point_urls["PARKING_AMONT"] = amont.group(1)
        if aval:
            point_urls["PARKING_AVAL"] = aval.group(1)

    for label, point_type in {
        "Départ du canyon": "ENTREE",
        "Sortie du canyon": "SORTIE",
    }.items():
        value = article.info.get(label)
        if not value:
            continue
        match = re.search(r"(https?://\S+)", value)
        if match:
            point_urls[point_type] = match.group(1)

    points: dict[str, tuple[float, float]] = {}
    for point_type, url in point_urls.items():
        coords = resolve_map_coordinates(url)
        if coords is not None:
            points[point_type] = coords
    return points


def choose_representative_point(points: list[dict[str, Any]]) -> dict[str, Any] | None:
    if not points:
        return None
    selected = min(
        points,
        key=lambda point: (
            REPRESENTATIVE_POINT_ORDER.get(point.get("type"), 99),
            str(point.get("label") or ""),
        ),
    )
    return {
        "type": selected.get("type"),
        "latitude": selected.get("latitude"),
        "longitude": selected.get("longitude"),
        "label": selected.get("label"),
    }


def is_missing(value: Any) -> bool:
    return value is None or value == ""


def update_if_missing(container: dict[str, Any], key: str, value: Any, changes: list[str], label: str) -> None:
    if value is None or value == "":
        return
    if is_missing(container.get(key)):
        container[key] = value
        changes.append(label)


def build_candidate_index(canyons: list[dict[str, Any]]) -> dict[int, dict[str, Any]]:
    candidates: dict[int, dict[str, Any]] = {}
    for item in canyons:
        canyon = item["canyon"]
        candidates[int(canyon["id"])] = {
            "id": int(canyon["id"]),
            "nom": canyon["identity"].get("nom"),
            "nomComplet": canyon["identity"].get("nomComplet"),
            "departement": canyon["location"].get("departement"),
            "tokens": set(tokenize_name(canyon["identity"].get("nom")))
            | set(tokenize_name(canyon["identity"].get("nomComplet")))
            | set(tokenize_name(canyon["location"].get("coursEau"))),
        }
    return candidates


def match_article_to_canyon(article: ArticleData, candidates: dict[int, dict[str, Any]]) -> int | None:
    override = ARTICLE_OVERRIDES.get(article.slug)
    if article.slug in ARTICLE_OVERRIDES:
        return override

    title_tokens = set(tokenize_name(article.title))
    if not title_tokens:
        return None

    lieu = article.info.get("Lieu", "")
    departement_hint = normalize_text(lieu.split(",")[-1]) if "," in lieu else normalize_text(lieu)

    scored: list[tuple[float, int]] = []
    for canyon_id, candidate in candidates.items():
        candidate_tokens = candidate["tokens"]
        if not candidate_tokens:
            continue
        overlap = title_tokens & candidate_tokens
        if not overlap:
            continue
        score = (2.0 * len(overlap)) / (len(title_tokens) + len(candidate_tokens))
        if departement_hint and normalize_text(candidate.get("departement")) == departement_hint:
            score += 0.25
        scored.append((score, canyon_id))

    if not scored:
        return None
    scored.sort(reverse=True)
    best_score, best_id = scored[0]
    if best_score < 0.45:
        return None
    if len(scored) > 1 and abs(best_score - scored[1][0]) < 0.05:
        return None
    return best_id


def update_canyon_from_article(canyon_doc: dict[str, Any], article: ArticleData) -> list[str]:
    canyon = canyon_doc["canyon"]
    changes: list[str] = []

    info = article.info
    description = article.description

    update_if_missing(canyon["location"], "coursEau", info.get("Cours d’eau"), changes, "coursEau")
    update_if_missing(canyon["metrics"], "altitudeDepartM", parse_int(info.get("Altitude de départ")), changes, "altitudeDepartM")
    update_if_missing(canyon["metrics"], "deniveleM", parse_int(info.get("Dénivelé")), changes, "deniveleM")
    update_if_missing(canyon["metrics"], "longueurM", parse_int(info.get("Longueur")), changes, "longueurM")
    update_if_missing(canyon["metrics"], "cascadeMaxM", parse_int(info.get("Rappel le plus haut")), changes, "cascadeMaxM")
    update_if_missing(canyon["metrics"], "cordeMiniM", parse_int(info.get("Longueur de corde minimale à simple")), changes, "cordeMiniM")
    update_if_missing(canyon["timings"], "descente", info.get("Temps de parcours"), changes, "tempsDescente")
    update_if_missing(canyon["timings"], "approche", extract_time_fragment(description.get("Approche")), changes, "tempsApproche")
    update_if_missing(canyon["timings"], "retour", extract_time_fragment(description.get("Retour")), changes, "tempsRetour")
    update_if_missing(canyon["topo"], "geologie", info.get("Type de roche"), changes, "geologie")
    update_if_missing(canyon["topo"], "periode", info.get("Période favorable"), changes, "periode")
    update_if_missing(canyon["topo"], "approche", description.get("Approche"), changes, "approche")
    update_if_missing(canyon["topo"], "descente", description.get("Parcours"), changes, "descente")
    update_if_missing(canyon["topo"], "retour", description.get("Retour"), changes, "retour")
    update_if_missing(canyon["topo"], "remarques", description.get("Remarques générales"), changes, "remarques")

    existing_types = {point.get("type") for point in canyon["geoPoints"]}
    for point_type, coords in parse_point_urls(article).items():
        if point_type in existing_types:
            continue
        canyon["geoPoints"].append(
            {
                "canyonId": canyon["id"],
                "rawType": RAW_TYPE_BY_POINT_TYPE[point_type],
                "type": point_type,
                "latitude": coords[0],
                "longitude": coords[1],
                "label": None,
            }
        )
        changes.append(f"geoPoint:{point_type}")

    if any(change.startswith("geoPoint:") for change in changes):
        canyon["geoPoints"].sort(key=lambda point: (REPRESENTATIVE_POINT_ORDER.get(point.get("type"), 99), point.get("rawType") or ""))
        representative_point = choose_representative_point(canyon["geoPoints"])
        if representative_point is not None:
            canyon["representativePoint"] = representative_point
            if "representativePoint" not in changes:
                changes.append("representativePoint")

    return changes


def update_room_import(changed_ids: set[int], canyon_docs: dict[int, dict[str, Any]]) -> None:
    canyons_rows = read_json(ROOM_IMPORT_DIR / "canyons.json")
    geo_rows = read_json(ROOM_IMPORT_DIR / "geo_points.json")
    manifest = read_json(ROOM_IMPORT_DIR / "manifest.json")
    generated_at = datetime.now(timezone.utc).isoformat()
    last_updated_ms = int(datetime.now(timezone.utc).timestamp() * 1000)

    geo_rows = [row for row in geo_rows if int(row["canyonId"]) not in changed_ids]

    for row in canyons_rows:
        canyon_id = int(row["id"])
        if canyon_id not in changed_ids:
            continue
        canyon = canyon_docs[canyon_id]["canyon"]
        row.update(
            {
                "nom": canyon["identity"]["nom"],
                "nomComplet": canyon["identity"]["nomComplet"],
                "pays": canyon["location"]["pays"],
                "region": canyon["location"]["region"],
                "departement": canyon["location"]["departement"],
                "commune": canyon["location"]["communePrincipale"] or "",
                "communes": canyon["location"]["communes"],
                "massif": canyon["location"]["massif"],
                "bassin": canyon["location"]["bassin"],
                "coursEau": canyon["location"]["coursEau"],
                "cotation": canyon["rating"]["cotation"],
                "altitudeDepart": canyon["metrics"]["altitudeDepartM"],
                "denivele": canyon["metrics"]["deniveleM"],
                "longueur": canyon["metrics"]["longueurM"],
                "cascadeMax": canyon["metrics"]["cascadeMaxM"],
                "cordeMin": canyon["metrics"]["cordeMiniM"],
                "tempsApproche": canyon["timings"]["approche"],
                "tempsDescente": canyon["timings"]["descente"],
                "tempsRetour": canyon["timings"]["retour"],
                "navette": canyon["metrics"]["navette"],
                "interet": canyon["rating"]["interet"],
                "nbVotes": canyon["rating"]["nbVotes"],
                "url": canyon["identity"]["url"],
                "accesAval": canyon["topo"]["accesAval"],
                "accesAmont": canyon["topo"]["accesAmont"],
                "approche": canyon["topo"]["approche"],
                "descente": canyon["topo"]["descente"],
                "retour": canyon["topo"]["retour"],
                "engagement": canyon["topo"]["engagement"],
                "periode": canyon["topo"]["periode"],
                "geologie": canyon["topo"]["geologie"],
                "historique": canyon["topo"]["historique"],
                "remarques": canyon["topo"]["remarques"],
                "lastUpdated": last_updated_ms,
                "hasSpecificRegulation": canyon["reglementation"]["hasSpecificRegulation"],
                "isForbidden": canyon["reglementation"].get("isForbidden", False),
            }
        )
        geo_rows.extend(
            {
                "canyonId": canyon_id,
                "type": point["type"],
                "latitude": point["latitude"],
                "longitude": point["longitude"],
                "label": point["label"],
            }
            for point in canyon["geoPoints"]
        )

    geo_rows.sort(key=lambda row: (int(row["canyonId"]), REPRESENTATIVE_POINT_ORDER.get(row["type"], 99), row["latitude"], row["longitude"]))

    manifest["generatedAt"] = generated_at
    manifest["counts"]["canyons"] = len(canyons_rows)
    manifest["counts"]["geo_points"] = len(geo_rows)

    write_json(ROOM_IMPORT_DIR / "canyons.json", canyons_rows)
    write_json(ROOM_IMPORT_DIR / "geo_points.json", geo_rows)
    write_json(ROOM_IMPORT_DIR / "manifest.json", manifest)


def update_index(changed_ids: set[int], canyon_docs: dict[int, dict[str, Any]]) -> None:
    index_payload = read_json(INDEX_JSON_PATH)
    for row in index_payload["canyons"]:
        canyon_id = int(row["id"])
        if canyon_id not in changed_ids:
            continue
        canyon = canyon_docs[canyon_id]["canyon"]
        row.update(
            {
                "nom": canyon["identity"]["nom"],
                "pays": canyon["location"]["pays"],
                "departement": canyon["location"]["departement"],
                "commune": canyon["location"]["communePrincipale"],
                "massif": canyon["location"]["massif"],
                "bassin": canyon["location"]["bassin"],
                "coursEau": canyon["location"]["coursEau"],
                "cotation": canyon["rating"]["cotation"],
                "interet": canyon["rating"]["interet"],
                "url": canyon["identity"]["url"],
                "hasSpecificRegulation": canyon["reglementation"]["hasSpecificRegulation"],
                "isForbidden": canyon["reglementation"].get("isForbidden", False),
                "representativePoint": canyon["representativePoint"],
            }
        )
    index_payload["generatedAt"] = datetime.now(timezone.utc).isoformat()
    write_json(INDEX_JSON_PATH, index_payload)
    write_json(OPTIMIZED_DIR / "search-index.json", index_payload)


def update_optimized_canyon_shards(changed_ids: set[int], canyon_docs: dict[int, dict[str, Any]]) -> None:
    for shard_path in sorted(OPTIMIZED_SHARDS_DIR.glob("canyon-details-*.json")):
        rows = read_json(shard_path)
        changed = False
        for row in rows:
            canyon_id = int(row["id"])
            if canyon_id not in changed_ids:
                continue
            canyon = canyon_docs[canyon_id]["canyon"]
            row.update(
                {
                    "identity": canyon["identity"],
                    "location": canyon["location"],
                    "rating": canyon["rating"],
                    "metrics": canyon["metrics"],
                    "timings": canyon["timings"],
                    "topo": canyon["topo"],
                    "representativePoint": canyon["representativePoint"],
                    "hasSpecificRegulation": canyon["reglementation"]["hasSpecificRegulation"],
                    "isForbidden": canyon["reglementation"].get("isForbidden", False),
                }
            )
            changed = True
        if changed:
            write_json(shard_path, rows)


def update_optimized_geo_shards(changed_ids: set[int], canyon_docs: dict[int, dict[str, Any]]) -> None:
    shard_rows: dict[Path, list[dict[str, Any]]] = {}
    target_shards: dict[int, Path | None] = {canyon_id: None for canyon_id in changed_ids}

    for shard_path in sorted(OPTIMIZED_SHARDS_DIR.glob("geo-points-*.json")):
        rows = read_json(shard_path)
        filtered = []
        present_ids: set[int] = set()
        for row in rows:
            canyon_id = int(row["canyonId"])
            if canyon_id in changed_ids:
                present_ids.add(canyon_id)
                continue
            filtered.append(row)
        for canyon_id in present_ids:
            target_shards[canyon_id] = shard_path
        shard_rows[shard_path] = filtered

    existing_shards = sorted(shard_rows)
    fallback_shard = existing_shards[-1] if existing_shards else None
    for canyon_id in changed_ids:
        shard_path = target_shards[canyon_id] or fallback_shard
        if shard_path is None:
            raise SystemExit("Aucun shard geo-point disponible")
        shard_rows[shard_path].extend(
            {
                "canyonId": canyon_id,
                "type": point["type"],
                "rawType": point["rawType"],
                "latitude": point["latitude"],
                "longitude": point["longitude"],
                "label": point["label"],
            }
            for point in canyon_docs[canyon_id]["canyon"]["geoPoints"]
        )

    manifest = read_json(OPTIMIZED_DIR / "manifest.json")
    total_geo_points = 0
    shard_descriptors = []
    for shard_path in sorted(shard_rows):
        rows = shard_rows[shard_path]
        rows.sort(key=lambda row: (int(row["canyonId"]), REPRESENTATIVE_POINT_ORDER.get(row["type"], 99), row["latitude"], row["longitude"]))
        write_json(shard_path, rows)
        total_geo_points += len(rows)
        shard_descriptors.append({"path": shard_path.name, "count": len(rows)})

    manifest["generatedAt"] = datetime.now(timezone.utc).isoformat()
    manifest["counts"]["geoPoints"] = total_geo_points
    manifest["files"]["geoPointShards"] = shard_descriptors
    write_json(OPTIMIZED_DIR / "manifest.json", manifest)


def main() -> None:
    canyon_docs = {
        int(path.stem): read_json(path)
        for path in sorted(FULL_CANYONS_DIR.glob("*.json"), key=lambda item: int(item.stem))
    }
    candidates = build_candidate_index(list(canyon_docs.values()))

    changed_ids: set[int] = set()
    unmatched: list[str] = []
    updated: list[tuple[int, str, list[str], str]] = []

    failed_articles: list[dict[str, str]] = []

    for article_url in fetch_article_urls():
        try:
            article = parse_article(article_url)
        except Exception as exc:  # noqa: BLE001
            failed_articles.append({"url": article_url, "error": repr(exc)})
            continue
        if article is None:
            continue
        canyon_id = match_article_to_canyon(article, candidates)
        if canyon_id is None:
            unmatched.append(article.url)
            continue
        canyon_doc = canyon_docs.get(canyon_id)
        if canyon_doc is None:
            unmatched.append(article.url)
            continue
        changes = update_canyon_from_article(canyon_doc, article)
        if not changes:
            continue
        changed_ids.add(canyon_id)
        write_json(FULL_CANYONS_DIR / f"{canyon_id}.json", canyon_doc)
        updated.append((canyon_id, canyon_doc["canyon"]["identity"]["nom"], changes, article.url))

    if changed_ids:
        update_room_import(changed_ids, canyon_docs)
        update_index(changed_ids, canyon_docs)
        update_optimized_canyon_shards(changed_ids, canyon_docs)
        update_optimized_geo_shards(changed_ids, canyon_docs)

    summary = {
        "updatedCanyons": len(updated),
        "changedIds": sorted(changed_ids),
        "unmatchedArticles": unmatched,
        "failedArticles": failed_articles,
        "updates": [
            {
                "canyonId": canyon_id,
                "nom": name,
                "changes": changes,
                "articleUrl": article_url,
            }
            for canyon_id, name, changes, article_url in updated
        ],
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
