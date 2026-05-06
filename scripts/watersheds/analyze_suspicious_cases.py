from __future__ import annotations

import argparse
import json
import math
import unicodedata
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class EntryContext:
    canyon_id: int
    canyon_name: str
    entry_index: int
    latitude: float
    longitude: float
    label: str | None
    raw_upa_km2: float | None
    snapped_upa_km2: float | None
    snapped_latitude: float | None
    snapped_longitude: float | None
    snap_distance_m: float | None
    pixel_size_m: float | None
    raw_to_snapped_upa_ratio: float | None
    elevation_m: float | None
    flowdir_value: int | None


def normalize_text(value: str | None) -> str:
    if not value:
        return ""
    normalized = unicodedata.normalize("NFKD", value)
    normalized = "".join(char for char in normalized if not unicodedata.combining(char))
    normalized = normalized.lower()
    cleaned = []
    for char in normalized:
        cleaned.append(char if char.isalnum() else " ")
    return " ".join("".join(cleaned).split())


def merit_package_name(latitude: float, longitude: float) -> str:
    lat0 = math.floor(latitude / 30.0) * 30
    lon0 = math.floor(longitude / 30.0) * 30
    lat_prefix = "n" if lat0 >= 0 else "s"
    lon_prefix = "e" if lon0 >= 0 else "w"
    return f"{lat_prefix}{abs(lat0):02d}{lon_prefix}{abs(lon0):03d}"


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    radius_m = 6_371_008.8
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    delta_phi = math.radians(lat2 - lat1)
    delta_lambda = math.radians(lon2 - lon1)
    a = (
        math.sin(delta_phi / 2.0) ** 2
        + math.cos(phi1) * math.cos(phi2) * math.sin(delta_lambda / 2.0) ** 2
    )
    return 2.0 * radius_m * math.atan2(math.sqrt(a), math.sqrt(1.0 - a))


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def load_entry_contexts(selected_entries_path: Path) -> dict[tuple[int, int], EntryContext]:
    contexts: dict[tuple[int, int], EntryContext] = {}
    for canyon_result in load_json(selected_entries_path):
        canyon_id = int(canyon_result["canyonId"])
        canyon_name = str(canyon_result["canyonName"])
        for entry in canyon_result["entries"]:
            entry_index = int(entry["entry_index"])
            contexts[(canyon_id, entry_index)] = EntryContext(
                canyon_id=canyon_id,
                canyon_name=canyon_name,
                entry_index=entry_index,
                latitude=float(entry["latitude"]),
                longitude=float(entry["longitude"]),
                label=entry.get("label"),
                raw_upa_km2=entry.get("raw_upa_km2"),
                snapped_upa_km2=entry.get("snapped_upa_km2"),
                snapped_latitude=entry.get("snapped_latitude"),
                snapped_longitude=entry.get("snapped_longitude"),
                snap_distance_m=entry.get("snap_distance_m"),
                pixel_size_m=entry.get("pixel_size_m"),
                raw_to_snapped_upa_ratio=entry.get("raw_to_snapped_upa_ratio"),
                elevation_m=entry.get("elevation_m"),
                flowdir_value=entry.get("flowdir_value"),
            )
    return contexts


def build_case_index(cases: list[dict[str, Any]]) -> dict[tuple[int, tuple[int, ...]], list[dict[str, Any]]]:
    by_group: dict[tuple[int, tuple[int, ...]], list[dict[str, Any]]] = defaultdict(list)
    for case in cases:
        key = (int(case["canyonId"]), tuple(int(x) for x in case.get("entryIndexes", [])))
        by_group[key].append(case)
    return by_group


def build_cases_by_canyon(cases: list[dict[str, Any]]) -> dict[int, list[dict[str, Any]]]:
    by_canyon: dict[int, list[dict[str, Any]]] = defaultdict(list)
    for case in cases:
        by_canyon[int(case["canyonId"])].append(case)
    return by_canyon


def labels_hint_multiple_branches(labels: list[str | None]) -> bool:
    hints = {
        "alternative",
        "alternatif",
        "affluent",
        "partie",
        "parte",
        "option",
        "variant",
        "variante",
        "branch",
        "branca",
        "rio",
        "ribeira",
        "torrent",
        "amont",
        "aval",
        "superieur",
        "inferieur",
        "sup",
        "inf",
        "integrel",
        "integrale",
        "integrale",
        "guadajana",
        "palomas",
    }
    for label in labels:
        tokens = set(normalize_text(label).split())
        if tokens & hints:
            return True
    return False


def context_for(case: dict[str, Any], contexts: dict[tuple[int, int], EntryContext]) -> list[EntryContext]:
    canyon_id = int(case["canyonId"])
    result: list[EntryContext] = []
    for entry_index in case.get("entryIndexes", []):
        context = contexts.get((canyon_id, int(entry_index)))
        if context is not None:
            result.append(context)
    return result


def diagnose_case(
    case: dict[str, Any],
    *,
    contexts: dict[tuple[int, int], EntryContext],
    cases_by_group: dict[tuple[int, tuple[int, ...]], list[dict[str, Any]]],
    cases_by_canyon: dict[int, list[dict[str, Any]]],
) -> dict[str, Any]:
    code = str(case["code"])
    canyon_id = int(case["canyonId"])
    entry_indexes = [int(x) for x in case.get("entryIndexes", [])]
    group_cases = cases_by_group.get((canyon_id, tuple(entry_indexes)), [])
    group_codes = sorted({str(item["code"]) for item in group_cases})
    canyon_codes = sorted({str(item["code"]) for item in cases_by_canyon.get(canyon_id, [])})
    entry_contexts = context_for(case, contexts)
    labels = [entry.label for entry in entry_contexts]

    cause_code = "MANUAL_REVIEW"
    likely_source = "manual_review"
    confidence = "medium"
    explanation = "Cas atypique a verifier manuellement."
    action = "Verifier le point d'entree et le snap dans QGIS."

    if code == "ENTRY_OUTSIDE_UPA_RASTER":
        latitude = float(case["data"]["latitude"])
        longitude = float(case["data"]["longitude"])
        package_name = merit_package_name(latitude, longitude)
        cause_code = "MERIT_PACKAGE_NOT_DOWNLOADED"
        likely_source = "coverage_gap"
        confidence = "high"
        explanation = f"Le point d'entree tombe dans le paquet MERIT {package_name}, absent de la couverture actuelle."
        action = f"Telecharger les paquets dir/elv/upa du bloc {package_name} puis relancer le calcul."
    elif code == "CANYON_NO_VALID_ENTRY_RESULT":
        if "ENTRY_OUTSIDE_UPA_RASTER" in canyon_codes and entry_contexts:
            package_names = sorted({merit_package_name(entry.latitude, entry.longitude) for entry in entry_contexts})
            cause_code = "ALL_ENTRIES_OUTSIDE_CURRENT_COVERAGE"
            likely_source = "coverage_gap"
            confidence = "high"
            explanation = "Toutes les entrees du canyon sont hors de la couverture MERIT actuellement telechargee."
            action = f"Telecharger les paquets manquants {', '.join(package_names)} puis recalculer ce canyon."
        else:
            cause_code = "NO_VALID_ENTRY_AFTER_SCREENING"
            likely_source = "input_or_algorithm"
            confidence = "medium"
            explanation = "Aucune entree n'a pu etre retenue alors qu'au moins une partie du canyon est dans la couverture courante."
            action = "Inspecter les points et le snap, puis verifier si le rayon de recherche est trop restrictif."
    elif code == "SNAP_DISTANCE_LARGE":
        ratio = case["data"].get("rawToSnappedUpaRatio")
        if ratio is None and entry_contexts:
            ratio = entry_contexts[0].raw_to_snapped_upa_ratio
        snap_distance_m = float(case["data"]["snapDistanceM"])
        if ratio is not None and ratio < 1.1:
            cause_code = "ENTRY_OFFSET_BUT_HYDROLOGY_STABLE"
            likely_source = "gps_or_bank_offset"
            confidence = "high"
            explanation = (
                f"Le snap est long ({snap_distance_m:.1f} m) mais l'UPA change peu, ce qui suggere surtout un point GPS place sur la berge, un sentier ou un parking proche du talweg."
            )
            action = "Conserver le resultat, mais marquer le point GPS pour revision de precision si necessaire."
        elif ratio is not None and ratio >= 10.0:
            cause_code = "ENTRY_LIKELY_OFF_CHANNEL"
            likely_source = "gps_or_snap_heuristic"
            confidence = "high"
            explanation = (
                f"Le snap est long ({snap_distance_m:.1f} m) et change fortement l'UPA, ce qui suggere un point place hors du thalweg ou un snap vers un chenal voisin."
            )
            action = "Verifier visuellement le talweg et tester un rayon de snap plus petit ou une contrainte sur le gradient local."
        else:
            cause_code = "ENTRY_OFFSET_MODERATE"
            likely_source = "gps_or_resolution"
            confidence = "medium"
            explanation = (
                f"Le snap depasse le seuil ({snap_distance_m:.1f} m) avec une variation d'UPA moderee. Le point est probablement proche du bon cours d'eau, mais la precision GPS ou la resolution MERIT reste limitee."
            )
            action = "Verifier les cas les plus eloignes dans QGIS, surtout en terrain complexe ou tres encaisse."
    elif code == "SNAP_UPA_JUMP_LARGE":
        ratio = float(case["data"]["rawToSnappedUpaRatio"])
        snap_distance_m = float(case["data"]["snapDistanceM"])
        if ratio >= 100.0:
            cause_code = "ENTRY_VERY_LIKELY_OFF_MAIN_CHANNEL"
            likely_source = "gps_or_snap_heuristic"
            confidence = "high"
            explanation = (
                f"L'UPA est multipliee par {ratio:.1f} apres snap sur seulement {snap_distance_m:.1f} m. Le point initial est tres probablement sur un versant, un acces ou un affluent adjacent plutot que sur le thalweg vise."
            )
            action = "Revoir ce point manuellement; en pratique, il faut souvent corriger le GPS ou imposer une heuristique de talweg plus stricte."
        elif ratio >= 20.0:
            cause_code = "ENTRY_NEAR_PARALLEL_CHANNEL_OR_WRONG_BRANCH"
            likely_source = "gps_or_resolution"
            confidence = "medium"
            explanation = (
                f"L'UPA change fortement (x{ratio:.1f}) apres un snap relativement court ({snap_distance_m:.1f} m), signe typique d'un canyon proche d'un affluent ou d'un chenal parallele."
            )
            action = "Verifier le bon bras d'ecoulement et regarder si le point doit etre aligne plus precisement sur l'entree reellement pratiquee."
        else:
            cause_code = "ENTRY_UPA_JUMP_AMBIGUOUS"
            likely_source = "manual_review"
            confidence = "medium"
            explanation = "Le snap change assez fortement l'UPA pour remettre en question soit le point GPS, soit l'heuristique de selection de cellule."
            action = "Verifier ce cas dans QGIS avec les lignes de snap et les tuiles MERIT."
    elif code == "MULTI_ENTRY_SAME_SNAP_CELL":
        if len(entry_contexts) >= 2:
            distances = []
            for idx, left in enumerate(entry_contexts):
                for right in entry_contexts[idx + 1 :]:
                    distances.append(haversine_m(left.latitude, left.longitude, right.latitude, right.longitude))
            min_distance = min(distances) if distances else None
        else:
            min_distance = None
        if min_distance is not None and min_distance <= 120.0:
            cause_code = "MERIT_RESOLUTION_TOO_COARSE_FOR_TWO_ENTRIES"
            likely_source = "dataset_resolution"
            confidence = "high"
            explanation = (
                f"Les deux entrees sont tres proches ({min_distance:.1f} m) et se rabattent sur la meme cellule MERIT de ~90 m. Ce n'est probablement pas une erreur, mais une limite de resolution."
            )
            action = "Garder une seule entree en phase 1 ou recalculer plus tard avec un MNT plus fin."
        else:
            cause_code = "ENTRIES_COLLAPSE_ON_SAME_FLOW_CELL"
            likely_source = "dataset_resolution_or_points"
            confidence = "medium"
            explanation = "Plusieurs entrees distinctes retombent sur la meme cellule, ce qui peut indiquer une resolution MERIT insuffisante ou des points tres approximatifs."
            action = "Verifier la distance entre entrees et envisager un recalcul haute resolution."
    elif code == "MULTI_ENTRY_FLOW_DISCONNECTED":
        if labels_hint_multiple_branches(labels):
            cause_code = "MULTIPLE_VALID_BRANCHES_OR_OPTIONS"
            likely_source = "data_model_limitation"
            confidence = "high"
            explanation = "Les libelles laissent penser qu'il s'agit d'entrees alternatives, parties differentes ou affluents distincts. Le probleme vient surtout du modele '1 entree retenue par canyon'."
            action = "Marquer ce canyon comme multi-branche et conserver une revue manuelle plutot qu'une selection automatique unique."
        else:
            cause_code = "ENTRIES_ON_DIFFERENT_HYDRO_BRANCHES"
            likely_source = "gps_or_topology"
            confidence = "medium"
            explanation = "Les entrees ne paraissent pas connectees sur la meme branche d'ecoulement dans MERIT. Cela peut venir de points sur des affluents differents ou d'un snap vers le mauvais chenal."
            action = "Inspecter le canyon dans QGIS pour distinguer multi-branche reel et mauvais snap."
    elif code == "MULTI_ENTRY_SELECTION_FALLBACK":
        if "MULTI_ENTRY_FLOW_DISCONNECTED" in canyon_codes:
            cause_code = "FALLBACK_DUE_TO_DISCONNECTED_BRANCHES"
            likely_source = "data_model_limitation"
            confidence = "high"
            explanation = "La selection automatique a du retomber sur la plus petite UPA parce que les entrees ressemblent a des branches ou options distinctes."
            action = "Traiter ce canyon comme un cas multi-entrees a arbitrer manuellement ou a modeliser plus finement."
        elif "MULTI_ENTRY_SAME_SNAP_CELL" in canyon_codes:
            cause_code = "FALLBACK_DUE_TO_COLLAPSED_ENTRIES"
            likely_source = "dataset_resolution"
            confidence = "high"
            explanation = "La selection automatique est tombee en fallback parce que MERIT ne separe pas assez les entrees au niveau de sa grille."
            action = "Accepter une seule entree pour la phase 1 ou recalculer avec un MNT plus fin."
        else:
            cause_code = "FALLBACK_DUE_TO_AMBIGUOUS_UPSTREAM_ORDER"
            likely_source = "manual_review"
            confidence = "medium"
            explanation = "L'ordre amont/aval n'a pas pu etre confirme par connectivite. Le choix final repose seulement sur la plus petite UPA."
            action = "Verifier les points et la topologie locale avant de considerer le resultat comme fiable."
    elif code == "ENTRY_SNAPPED_TO_OUTLET_OR_SINK":
        flowdir_value = int(case["data"]["flowdirValue"])
        if flowdir_value == 0:
            cause_code = "COASTAL_OR_RIVER_MOUTH_SPECIAL_CASE"
            likely_source = "hydrology_special_case"
            confidence = "high"
            explanation = "Le point snappe sur une cellule 'river mouth'. Cela arrive souvent sur cascades cotieres, exutoires tres proches de la mer ou resurgences terminales."
            action = "Verifier si le canyon est littoral ou tres proche d'un exutoire; si oui, ce n'est pas un bug mais un cas special a tagger."
        else:
            cause_code = "INLAND_DEPRESSION_OR_KARST_SPECIAL_CASE"
            likely_source = "hydrology_special_case"
            confidence = "medium"
            explanation = "Le point snappe sur une depression interne, typique de zones endoreiques, karstiques ou mal representees par la topographie de surface."
            action = "Verifier le contexte karstique et decider si le bassin topographique est pertinent pour ce canyon."

    return {
        "code": code,
        "severity": case["severity"],
        "canyonId": canyon_id,
        "canyonName": case["canyonName"],
        "entryIndexes": entry_indexes,
        "groupCodes": group_codes,
        "canyonCodes": canyon_codes,
        "probableCauseCode": cause_code,
        "likelySource": likely_source,
        "confidence": confidence,
        "explanation": explanation,
        "recommendedAction": action,
        "originalCase": case,
    }


def summarize_diagnoses(diagnoses: list[dict[str, Any]]) -> dict[str, Any]:
    by_cause = Counter(item["probableCauseCode"] for item in diagnoses)
    by_source = Counter(item["likelySource"] for item in diagnoses)
    by_confidence = Counter(item["confidence"] for item in diagnoses)
    by_code = Counter(item["code"] for item in diagnoses)
    return {
        "totalDiagnoses": len(diagnoses),
        "byOriginalCode": dict(by_code),
        "byProbableCause": dict(by_cause),
        "byLikelySource": dict(by_source),
        "byConfidence": dict(by_confidence),
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Diagnose chaque suspicious case de calcul de bassin versant.")
    parser.add_argument(
        "--selected-entries",
        type=Path,
        default=Path("build/watersheds/merit-main-run/selected_entries.json"),
    )
    parser.add_argument(
        "--suspicious-cases",
        type=Path,
        default=Path("build/watersheds/merit-main-run/suspicious_cases.json"),
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("build/watersheds/merit-main-run/analysis"),
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    selected_entries_path = args.selected_entries
    suspicious_cases_path = args.suspicious_cases
    output_dir = args.output_dir

    contexts = load_entry_contexts(selected_entries_path)
    suspicious_cases = load_json(suspicious_cases_path)
    cases_by_group = build_case_index(suspicious_cases)
    cases_by_canyon = build_cases_by_canyon(suspicious_cases)
    diagnoses = [
        diagnose_case(case, contexts=contexts, cases_by_group=cases_by_group, cases_by_canyon=cases_by_canyon)
        for case in suspicious_cases
    ]
    summary = summarize_diagnoses(diagnoses)

    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "suspicious_case_diagnosis.json").write_text(
        json.dumps(diagnoses, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    (output_dir / "suspicious_case_diagnosis_summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
