from __future__ import annotations

import argparse
import json
import os
import stat
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from typing import Any


DEFAULT_MERGED_WINDOWS_PATH = "build/debit-pipeline/weather-planning-reviewed-official/merged_weather_windows.jsonl"
DEFAULT_OUTPUT_DIR = "build/debit-pipeline/weather-archive-reviewed-official"
DEFAULT_ENV_FILE = ".env-proton"


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            stripped = line.strip()
            if stripped:
                rows.append(json.loads(stripped))
    return rows


def load_completed_window_ids(path: Path) -> set[str]:
    if not path.exists():
        return set()
    return {row["mergedWindowId"] for row in read_jsonl(path) if row.get("mergedWindowId")}


def parse_env_file(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            stripped = line.strip()
            if not stripped or stripped.startswith("#") or "=" not in stripped:
                continue
            key, value = stripped.split("=", 1)
            values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def write_gluetun_env_file(*, source_env_path: Path, server_countries: str) -> Path:
    values = parse_env_file(source_env_path)
    user = values.get("PROTON_USER")
    password = values.get("PROTON_PASS")
    if not user or not password:
        raise SystemExit(f"Missing PROTON_USER or PROTON_PASS in {source_env_path}")
    fd, raw_path = tempfile.mkstemp(prefix="gluetun-openmeteo-", suffix=".env", dir="/tmp/opencode")
    path = Path(raw_path)
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        handle.write("VPN_SERVICE_PROVIDER=protonvpn\n")
        handle.write(f"OPENVPN_USER={user}\n")
        handle.write(f"OPENVPN_PASSWORD={password}\n")
        handle.write(f"SERVER_COUNTRIES={server_countries}\n")
    path.chmod(stat.S_IRUSR | stat.S_IWUSR)
    return path


def run(command: list[str], *, check: bool = True, stdout: Any = None, stderr: Any = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, check=check, text=True, stdout=stdout, stderr=stderr)


def remove_container(name: str) -> None:
    run(["docker", "rm", "-f", name], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def start_vpn_container(*, name: str, env_file: Path, image: str) -> None:
    remove_container(name)
    run(
        [
            "docker",
            "run",
            "-d",
            "--name",
            name,
            "--cap-add",
            "NET_ADMIN",
            "--env-file",
            str(env_file),
            image,
        ],
        stdout=subprocess.DEVNULL,
    )


def wait_for_vpn_health(name: str, *, timeout_seconds: int) -> None:
    deadline = time.monotonic() + timeout_seconds
    last_status = "unknown"
    while time.monotonic() < deadline:
        result = subprocess.run(
            ["docker", "inspect", "-f", "{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}", name],
            check=False,
            text=True,
            capture_output=True,
        )
        last_status = result.stdout.strip() or result.stderr.strip() or "unknown"
        if last_status == "healthy":
            return
        time.sleep(3)
    raise SystemExit(f"VPN container {name} did not become healthy in time (last status: {last_status})")


def start_healthy_vpn_container(*, name: str, env_file: Path, image: str, timeout_seconds: int, attempts: int) -> None:
    last_error: BaseException | None = None
    for attempt in range(1, max(attempts, 1) + 1):
        start_vpn_container(name=name, env_file=env_file, image=image)
        try:
            wait_for_vpn_health(name, timeout_seconds=timeout_seconds)
            return
        except SystemExit as exc:
            last_error = exc
            print(f"VPN health attempt {attempt}/{attempts} failed: {exc}", file=sys.stderr)
            remove_container(name)
            time.sleep(3)
    raise SystemExit(last_error or f"VPN container {name} did not become healthy")


def public_ip(name: str) -> str:
    result = subprocess.run(
        [
            "docker",
            "run",
            "--rm",
            "--network",
            f"container:{name}",
            "python:3.11-slim",
            "python",
            "-c",
            "import urllib.request; print(urllib.request.urlopen('https://api.ipify.org', timeout=30).read().decode())",
        ],
        check=False,
        text=True,
        capture_output=True,
    )
    return result.stdout.strip() or "unknown"


def run_fetch_chunk(*, args: argparse.Namespace) -> None:
    command = [
        "docker",
        "run",
        "--rm",
        "--network",
        f"container:{args.vpn_container_name}",
        "-v",
        f"{Path.cwd()}:/work",
        "-w",
        "/work",
        args.python_image,
        "python",
        "scripts/debits/fetch_open_meteo_archive.py",
        "--merged-windows-path",
        args.merged_windows_path,
        "--output-dir",
        args.output_dir,
        "--model",
        args.model,
        "--workers",
        str(args.fetch_workers),
        "--max-batch-targets",
        str(args.fetch_max_batch_targets),
        "--request-delay-ms",
        str(args.request_delay_ms),
        "--timeout-s",
        str(args.timeout_s),
        "--max-attempts",
        str(args.max_attempts),
        "--base-backoff-ms",
        str(args.base_backoff_ms),
        "--max-windows-to-process",
        str(args.chunk_size),
    ]
    if args.fallback_single_on_batch_failure:
        command.append("--fallback-single-on-batch-failure")
    if args.abort_on_rate_limit:
        command.append("--abort-on-rate-limit")
    if args.abort_on_transient_failure:
        command.append("--abort-on-transient-failure")
    run(command)


def completion_counts(*, merged_windows_path: Path, output_dir: Path) -> tuple[int, int]:
    total = len(read_jsonl(merged_windows_path))
    completed = len(load_completed_window_ids(output_dir / "weather_window_manifest.jsonl"))
    return completed, total


def main() -> None:
    parser = argparse.ArgumentParser(description="Run Open-Meteo archive fetch in small chunks through a dedicated Gluetun VPN container")
    parser.add_argument("--merged-windows-path", default=DEFAULT_MERGED_WINDOWS_PATH)
    parser.add_argument("--output-dir", default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--env-file", default=DEFAULT_ENV_FILE)
    parser.add_argument("--vpn-container-name", default="dc-openmeteo-vpn")
    parser.add_argument("--gluetun-image", default="qmcgaw/gluetun")
    parser.add_argument("--python-image", default="python:3.11-slim")
    parser.add_argument("--server-countries", default="Germany")
    parser.add_argument("--chunk-size", type=int, default=10)
    parser.add_argument("--fetch-max-batch-targets", type=int, default=1)
    parser.add_argument("--fetch-workers", type=int, default=1)
    parser.add_argument("--fallback-single-on-batch-failure", action="store_true")
    parser.add_argument("--abort-on-rate-limit", action="store_true")
    parser.add_argument("--abort-on-transient-failure", action="store_true")
    parser.add_argument("--max-chunks", type=int, help="Stop after this many VPN chunks")
    parser.add_argument("--vpn-health-timeout-s", type=int, default=120)
    parser.add_argument("--vpn-start-attempts", type=int, default=4)
    parser.add_argument("--request-delay-ms", type=int, default=1200)
    parser.add_argument("--timeout-s", type=int, default=300)
    parser.add_argument("--max-attempts", type=int, default=2)
    parser.add_argument("--base-backoff-ms", type=int, default=15000)
    parser.add_argument("--model", default="era5_land")
    args = parser.parse_args()

    if args.chunk_size <= 0:
        raise SystemExit("--chunk-size must be positive")
    if args.fetch_max_batch_targets <= 0:
        raise SystemExit("--fetch-max-batch-targets must be positive")
    if args.fetch_workers <= 0:
        raise SystemExit("--fetch-workers must be positive")
    env_file = write_gluetun_env_file(source_env_path=Path(args.env_file), server_countries=args.server_countries)
    completed, total = completion_counts(merged_windows_path=Path(args.merged_windows_path), output_dir=Path(args.output_dir))
    print(f"Starting VPN chunked fetch: completed={completed}/{total} chunk_size={args.chunk_size}", file=sys.stderr)
    chunk_index = 0
    try:
        while completed < total:
            if args.max_chunks is not None and chunk_index >= args.max_chunks:
                break
            chunk_index += 1
            start_healthy_vpn_container(
                name=args.vpn_container_name,
                env_file=env_file,
                image=args.gluetun_image,
                timeout_seconds=args.vpn_health_timeout_s,
                attempts=args.vpn_start_attempts,
            )
            ip = public_ip(args.vpn_container_name)
            print(f"Chunk {chunk_index}: VPN ready ip={ip} completed={completed}/{total}", file=sys.stderr)
            try:
                try:
                    run_fetch_chunk(args=args)
                except subprocess.CalledProcessError as exc:
                    if (args.abort_on_rate_limit or args.abort_on_transient_failure) and exc.returncode == 75:
                        print(f"Chunk {chunk_index}: transient fetch failure, rotating VPN", file=sys.stderr)
                    else:
                        raise
            finally:
                remove_container(args.vpn_container_name)
            completed, total = completion_counts(merged_windows_path=Path(args.merged_windows_path), output_dir=Path(args.output_dir))
            print(f"Chunk {chunk_index}: after fetch completed={completed}/{total}", file=sys.stderr)
    finally:
        remove_container(args.vpn_container_name)
        env_file.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
