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


def normalize_state(raw_state: object) -> dict[str, object]:
    if not isinstance(raw_state, dict):
        return {"currentPage": 0, "reviews": []}

    try:
        current_page = max(0, int(raw_state.get("currentPage", 0)))
    except (TypeError, ValueError):
        current_page = 0

    raw_reviews = raw_state.get("reviews")
    reviews: list[dict[str, object]] = []
    if isinstance(raw_reviews, list):
        for raw_review in raw_reviews:
            normalized_review = normalize_review(raw_review)
            if normalized_review is not None:
                reviews.append(normalized_review)

    reviews.sort(key=lambda review: int(review["canyonId"]))
    return {
        "currentPage": current_page,
        "reviews": reviews,
    }


def load_saved_reviews() -> list[dict[str, object]]:
    candidate_paths = [REVIEW_FILE, ROOT_DIR / "build" / "watershed-review" / "watershed-review.json"]
    for path in candidate_paths:
        if not path.exists():
            continue
        try:
            raw_reviews = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            continue
        if not isinstance(raw_reviews, list):
            continue
        reviews = []
        for raw_review in raw_reviews:
            normalized_review = normalize_review(raw_review)
            if normalized_review is not None:
                reviews.append(normalized_review)
        reviews.sort(key=lambda review: int(review["canyonId"]))
        return reviews
    return []


def load_saved_state() -> dict[str, object]:
    reviews = load_saved_reviews()
    if not STATE_FILE.exists():
        return {"currentPage": 0, "reviews": reviews}

    try:
        raw_state = json.loads(STATE_FILE.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return {"currentPage": 0, "reviews": reviews}

    normalized_state = normalize_state(raw_state)
    normalized_state["reviews"] = reviews
    return normalized_state


def write_state(state: dict[str, object]) -> None:
    STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
    REVIEW_FILE.parent.mkdir(parents=True, exist_ok=True)
    STATE_FILE.write_text(json.dumps(state, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
    REVIEW_FILE.write_text(json.dumps(state["reviews"], ensure_ascii=True, indent=2) + "\n", encoding="utf-8")


class ReviewRequestHandler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT_DIR), **kwargs)

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path == "/api/review-state":
            self._send_json(load_saved_state())
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

        state = normalize_state(payload)
        write_state(state)
        self._send_json(
            {
                "ok": True,
                "currentPage": state["currentPage"],
                "reviewCount": len(state["reviews"]),
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
