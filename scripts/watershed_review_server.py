from __future__ import annotations

import argparse
import json
import sys
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse


ROOT_DIR = Path(__file__).resolve().parent.parent
DEFAULT_REVIEW_FILE = ROOT_DIR / "watershed-review" / "watershed-review.json"
DEFAULT_STATE_FILE = ROOT_DIR / "build" / "watershed-review" / "watershed-review-state.json"
STALE_LEGACY_REVIEW_FILE = ROOT_DIR / "build" / "watershed-review" / "watershed-review.json"
WATERSHED_RUNS_DIR = ROOT_DIR / "watershed-results" / "runs"

REVIEW_FILE = DEFAULT_REVIEW_FILE
STATE_FILE = DEFAULT_STATE_FILE


def normalize_review(raw_review: object) -> dict[str, object] | None:
    if not isinstance(raw_review, dict):
        return None

    try:
        canyon_id = int(raw_review["canyonId"])
    except (KeyError, TypeError, ValueError):
        return None

    status = str(raw_review.get("status", "")).lower()
    if status not in {"good", "bad", "pending"}:
        return None

    gps_payload = raw_review.get("gps")
    gps: dict[str, float] | None = None
    if isinstance(gps_payload, dict):
        try:
            latitude = float(gps_payload["latitude"])
            longitude = float(gps_payload["longitude"])
            gps = {
                "latitude": round(latitude, 6),
                "longitude": round(longitude, 6),
            }
        except (KeyError, TypeError, ValueError):
            gps = None

    admin_placed = bool(raw_review.get("adminPlaced", False))
    if status in {"bad", "pending"} and gps is not None and "adminPlaced" not in raw_review:
        admin_placed = True

    return {
        "canyonId": canyon_id,
        "status": status,
        "gps": gps,
        "adminPlaced": admin_placed,
        "pointType": raw_review.get("pointType"),
        "label": raw_review.get("label"),
    }


def normalize_review_list(raw_reviews: object) -> list[dict[str, object]]:
    reviews: list[dict[str, object]] = []
    if not isinstance(raw_reviews, list):
        return reviews
    for raw_review in raw_reviews:
        normalized_review = normalize_review(raw_review)
        if normalized_review is not None:
            reviews.append(normalized_review)
    reviews.sort(key=lambda review: int(review["canyonId"]))
    return reviews


def normalize_id_list(raw_ids: object) -> list[int]:
    if not isinstance(raw_ids, list):
        return []
    values: set[int] = set()
    for raw_id in raw_ids:
        try:
            values.add(int(raw_id))
        except (TypeError, ValueError):
            continue
    return sorted(values)


def normalize_state(raw_state: object, existing_state: dict[str, object] | None = None) -> dict[str, object]:
    if not isinstance(raw_state, dict):
        raw_state = {}

    existing_state = existing_state or {}

    try:
        current_page = max(0, int(raw_state.get("currentPage", 0)))
    except (TypeError, ValueError):
        current_page = 0

    reviews = normalize_review_list(raw_state.get("reviews"))
    if "baselineReviews" in raw_state:
        baseline_reviews = normalize_review_list(raw_state.get("baselineReviews"))
    else:
        baseline_reviews = normalize_review_list(existing_state.get("baselineReviews"))

    if "batchLabel" not in raw_state:
        batch_label = existing_state.get("batchLabel")
    else:
        raw_batch_label = raw_state.get("batchLabel")
        batch_label = str(raw_batch_label)

    if "queueName" not in raw_state:
        queue_name = existing_state.get("queueName")
    else:
        raw_queue_name = raw_state.get("queueName")
        queue_name = str(raw_queue_name)

    if "completedCanyonIds" in raw_state:
        completed_canyon_ids = normalize_id_list(raw_state.get("completedCanyonIds"))
    else:
        completed_canyon_ids = normalize_id_list(existing_state.get("completedCanyonIds"))

    return {
        "currentPage": current_page,
        "reviews": reviews,
        "baselineReviews": baseline_reviews,
        "batchLabel": batch_label,
        "queueName": queue_name,
        "completedCanyonIds": completed_canyon_ids,
    }


def load_reviews_file(path: Path) -> tuple[list[dict[str, object]], float] | None:
    if not path.exists():
        return None
    try:
        raw_reviews = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return None
    if not isinstance(raw_reviews, list):
        return None
    reviews = []
    for raw_review in raw_reviews:
        normalized_review = normalize_review(raw_review)
        if normalized_review is not None:
            reviews.append(normalized_review)
    reviews.sort(key=lambda review: int(review["canyonId"]))
    return reviews, path.stat().st_mtime


def load_reviews_from_state_file(path: Path) -> tuple[list[dict[str, object]], float] | None:
    if not path.exists():
        return None
    try:
        raw_state = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return None
    normalized_state = normalize_state(raw_state)
    reviews = normalized_state.get("reviews")
    if not isinstance(reviews, list):
        return None
    return reviews, path.stat().st_mtime


def load_saved_reviews() -> list[dict[str, object]]:
    sources: list[tuple[str, list[dict[str, object]], float]] = []
    review_source = load_reviews_file(REVIEW_FILE)
    if review_source is not None:
        sources.append(("truth", review_source[0], review_source[1]))
    state_source = load_reviews_from_state_file(STATE_FILE)
    if state_source is not None:
        sources.append(("state", state_source[0], state_source[1]))
    if not sources:
        return []

    primary_name, primary_reviews, _ = max(sources, key=lambda item: item[2])
    merged: dict[int, dict[str, object]] = {int(review["canyonId"]): review for review in primary_reviews}
    for source_name, reviews, _ in sources:
        if source_name == primary_name:
            continue
        for review in reviews:
            merged.setdefault(int(review["canyonId"]), review)
    normalized = [merged[canyon_id] for canyon_id in sorted(merged)]
    return normalized


def load_saved_state() -> dict[str, object]:
    reviews = load_saved_reviews()
    if not STATE_FILE.exists():
        return {
            "currentPage": 0,
            "reviews": reviews,
            "baselineReviews": [],
            "batchLabel": None,
            "queueName": None,
            "completedCanyonIds": [],
        }

    try:
        raw_state = json.loads(STATE_FILE.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return {
            "currentPage": 0,
            "reviews": reviews,
            "baselineReviews": [],
            "batchLabel": None,
            "queueName": None,
            "completedCanyonIds": [],
        }

    normalized_state = normalize_state(raw_state)
    normalized_state["reviews"] = reviews
    return normalized_state


def write_state(state: dict[str, object]) -> None:
    STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
    REVIEW_FILE.parent.mkdir(parents=True, exist_ok=True)
    review_payload = json.dumps(state["reviews"], ensure_ascii=True, indent=2) + "\n"
    STATE_FILE.write_text(json.dumps(state, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
    REVIEW_FILE.write_text(review_payload, encoding="utf-8")
    STALE_LEGACY_REVIEW_FILE.unlink(missing_ok=True)


def to_web_path(path: Path) -> str:
    return "/" + str(path.relative_to(ROOT_DIR)).replace("\\", "/")


def find_latest_world_run() -> Path | None:
    world_runs_dir = WATERSHED_RUNS_DIR / "full"
    if not world_runs_dir.exists():
        return None
    candidates = [
        path for path in world_runs_dir.iterdir()
        if path.is_dir() and (path / "import_ready_catchments.json").exists() and (path / "import_ready_watersheds.json").exists()
    ]
    if not candidates:
        return None
    return max(candidates, key=lambda path: (path.stat().st_mtime, path.name))


def list_reference_runs(world_run_dir: Path | None) -> list[Path]:
    reference_runs: list[Path] = []
    if not WATERSHED_RUNS_DIR.exists():
        return reference_runs

    for country_dir in WATERSHED_RUNS_DIR.iterdir():
        if not country_dir.is_dir() or country_dir.name == "full":
            continue
        full_dir = country_dir / "full"
        if not full_dir.exists() or not full_dir.is_dir():
            continue
        for run_dir in full_dir.iterdir():
            if not run_dir.is_dir():
                continue
            if world_run_dir is not None and run_dir.resolve() == world_run_dir.resolve():
                continue
            if not (run_dir / "import_ready_catchments.json").exists():
                continue
            if not (run_dir / "import_ready_watersheds.json").exists():
                continue
            reference_runs.append(run_dir)

    reference_runs.sort(key=lambda path: (path.parent.parent.name, path.name))
    return reference_runs


def build_review_context() -> dict[str, object]:
    world_run_dir = find_latest_world_run()
    if world_run_dir is None:
        raise FileNotFoundError("No world watershed run found in watershed-results/runs/full")

    reference_runs = list_reference_runs(world_run_dir)
    return {
        "world": {
            "label": world_run_dir.name,
            "catchmentsUrl": to_web_path(world_run_dir / "import_ready_catchments.json"),
            "watershedsUrl": to_web_path(world_run_dir / "import_ready_watersheds.json"),
            "summaryUrl": to_web_path(world_run_dir / "summary.json"),
            "statusIndexUrl": to_web_path(world_run_dir / "canyon_status_index.json"),
        },
        "referenceRuns": [
            {
                "label": run_dir.name,
                "country": run_dir.parent.parent.name,
                "catchmentsUrl": to_web_path(run_dir / "import_ready_catchments.json"),
                "watershedsUrl": to_web_path(run_dir / "import_ready_watersheds.json"),
            }
            for run_dir in reference_runs
        ],
    }


class ReviewRequestHandler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT_DIR), **kwargs)

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path == "/api/review-state":
            self._send_json(load_saved_state())
            return
        if parsed.path == "/api/review-context":
            try:
                self._send_json(build_review_context())
            except FileNotFoundError as error:
                self.send_error(HTTPStatus.NOT_FOUND, str(error))
            return
        super().do_GET()

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path != "/api/review-state":
            self.send_error(HTTPStatus.NOT_FOUND, "Unknown API endpoint")
            return

        length_header = self.headers.get("Content-Length")
        if length_header is None:
            self.send_error(HTTPStatus.LENGTH_REQUIRED, "Content-Length required")
            return

        try:
            length = int(length_header)
        except ValueError:
            self.send_error(HTTPStatus.BAD_REQUEST, "Invalid Content-Length")
            return

        raw_body = self.rfile.read(length)
        try:
            payload = json.loads(raw_body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            self.send_error(HTTPStatus.BAD_REQUEST, "Invalid JSON payload")
            return

        existing_state = load_saved_state()
        state = normalize_state(payload, existing_state=existing_state)
        write_state(state)
        self._send_json(
            {
                "ok": True,
                "currentPage": state["currentPage"],
                "reviewCount": len(state["reviews"]),
                "baselineReviewCount": len(state["baselineReviews"]),
                "batchLabel": state.get("batchLabel"),
                "queueName": state.get("queueName"),
                "reviewFile": str(REVIEW_FILE.relative_to(ROOT_DIR)).replace("\\", "/"),
                "stateFile": str(STATE_FILE.relative_to(ROOT_DIR)).replace("\\", "/"),
            },
            status=HTTPStatus.OK,
        )

    def log_message(self, format: str, *args) -> None:
        sys.stdout.write("%s - - [%s] %s\n" % (self.address_string(), self.log_date_time_string(), format % args))

    def _send_json(self, payload: object, status: HTTPStatus = HTTPStatus.OK) -> None:
        body = json.dumps(payload, ensure_ascii=True, indent=2).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Serveur local pour la review des points watershed.")
    parser.add_argument("port", nargs="?", type=int, default=8124)
    parser.add_argument("--review-file", type=Path, default=DEFAULT_REVIEW_FILE)
    parser.add_argument("--state-file", type=Path, default=DEFAULT_STATE_FILE)
    return parser.parse_args()


def main() -> None:
    global REVIEW_FILE, STATE_FILE

    args = parse_args()
    REVIEW_FILE = args.review_file.resolve()
    STATE_FILE = args.state_file.resolve()

    httpd = ThreadingHTTPServer(("127.0.0.1", args.port), ReviewRequestHandler)
    print(f"Watershed review server listening on http://127.0.0.1:{args.port}")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()


if __name__ == "__main__":
    main()
