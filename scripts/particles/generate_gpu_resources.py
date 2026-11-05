"""Developer native-build step. Normal Gradle builds generate Java/GLSL without Python."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def native_header(contract):
    lines = ["#pragma once", "// Generated from gpu/particle-resources.json."]
    lines.extend(f"#define CMI_RESOURCE_{key} {value}" for key, value in contract["constants"].items())
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    contract = json.loads((ROOT / "gpu/particle-resources.json").read_text(encoding="utf-8"))
    path = ROOT / "native/particle_rt/ResourceLayout.h"
    header = native_header(contract)
    if not path.is_file() or path.read_text(encoding="utf-8") != header:
        path.write_text(header, encoding="utf-8", newline="\n")
