from __future__ import annotations

import os
import shutil
from pathlib import Path


def resolve_executable(value: str, *, extra_candidates: list[str] | None = None) -> str:
    candidates = [value]
    if extra_candidates:
        candidates.extend(extra_candidates)

    for candidate in candidates:
        if not candidate:
            continue
        candidate_path = Path(candidate)
        if candidate_path.exists():
            return str(candidate_path)
        found = shutil.which(candidate)
        if found:
            return found

    raise SystemExit(
        f"Executable not found: {value}. Checked PATH and candidates: {', '.join(candidates)}"
    )


def default_gdalbuildvrt() -> str:
    if os.name == "nt":
        return r"C:\Program Files\GDAL\gdalbuildvrt.exe"
    return "gdalbuildvrt"


def default_gdal_translate() -> str:
    if os.name == "nt":
        return r"C:\Program Files\GDAL\gdal_translate.exe"
    return "gdal_translate"


def default_gdalwarp() -> str:
    if os.name == "nt":
        return r"C:\Program Files\GDAL\gdalwarp.exe"
    return "gdalwarp"


def default_ogr2ogr() -> str:
    if os.name == "nt":
        return r"C:\Program Files\GDAL\ogr2ogr.exe"
    return "ogr2ogr"


def gdal_env(executable: str) -> dict[str, str]:
    env = os.environ.copy()
    if env.get("GDAL_DATA"):
        return env
    exe_path = Path(executable)
    candidates = []
    if os.name == "nt":
        candidates.append(exe_path.parent / "gdal-data")
        candidates.append(Path(r"C:\Program Files\GDAL\gdal-data"))
    else:
        candidates.extend([
            exe_path.parent.parent / "share" / "gdal",
            Path("/usr/share/gdal"),
            Path("/usr/local/share/gdal"),
        ])
    for candidate in candidates:
        if candidate.exists():
            env["GDAL_DATA"] = str(candidate)
            return env
    return env


def default_7zip() -> str:
    if os.name == "nt":
        return r"C:\Program Files\7-Zip\7z.exe"
    return "7z"
