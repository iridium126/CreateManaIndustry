"""Publish the developer-built DLL; ordinary Gradle builds only verify/embed it."""
import hashlib
import json
import re
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "native/particle_rt"
DESTINATION = ROOT / "native/prebuilt/windows-x86_64"


def source_digest():
    digest = hashlib.sha256()
    sources = [p for p in SOURCE.rglob("*") if p.is_file()] + [
        ROOT / "gpu/particle-resources.json",
        ROOT / "src/main/resources/assets/createmanaindustry/shaders/particles/chunks/storm_home.glsl",
    ]
    for path in sorted(sources, key=lambda p: p.relative_to(ROOT).as_posix()):
        digest.update(path.relative_to(ROOT).as_posix().encode())
        digest.update(b"\0")
        digest.update(path.read_bytes().replace(b"\r\n", b"\n"))
        digest.update(b"\0")
    return digest.hexdigest()


def main():
    version = re.search(r'#define CMI_RT_VERSION "([0-9.]+)"', (SOURCE / "Version.h").read_text()).group(1)
    binary = ROOT / ".gradle/particle-rt/build/CmiParticleRt.dll"
    if not binary.is_file():
        raise SystemExit("Build the native module with scripts/particles/build_rt.ps1 first")
    DESTINATION.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(binary, DESTINATION / binary.name)
    contract = json.loads((ROOT / "gpu/particle-resources.json").read_text(encoding="utf-8"))["constants"]
    (DESTINATION / "manifest.json").write_text(json.dumps({
        "abi": contract["ABI"], "version": version, "platform": "windows-x86_64",
        "layout": {key: value for key, value in contract.items()
                   if key == "ABI" or key.startswith(("STORM_", "SPATIAL_", "RT_"))},
        "source_sha256": source_digest(),
        "dll_sha256": hashlib.sha256(binary.read_bytes()).hexdigest(),
        "embedded_device_programs": True,
    }, indent=2) + "\n", encoding="utf-8")
    print("Published Particle RT " + version + " for SDK-free distribution builds")


if __name__ == "__main__":
    main()
