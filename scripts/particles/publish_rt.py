"""Publish the developer-built DLL; ordinary Gradle builds only verify/embed it."""
import hashlib
import json
import re
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "src/main/native/particle_rt"
DESTINATION = ROOT / "src/main/native/prebuilt/windows-x86_64"


def main():
    version = re.search(r'#define CMI_RT_VERSION "([0-9.]+)"', (SOURCE / "Version.h").read_text()).group(1)
    binary = ROOT / ".gradle/particle-rt/build/CmiParticleRt.dll"
    if not binary.is_file():
        raise SystemExit("Build the native module with scripts/particles/build_rt.ps1 first")
    DESTINATION.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(binary, DESTINATION / binary.name)
    contract = json.loads((ROOT / "src/main/gpu/particle-resources.json").read_text(encoding="utf-8"))["constants"]
    (DESTINATION / "manifest.json").write_text(json.dumps({
        "version": version, "platform": "windows-x86_64",
        "layout": {key: value for key, value in contract.items()
                   if key == "ABI" or key.startswith(("STORM_", "SPATIAL_", "RT_"))},
        "dll_sha256": hashlib.sha256(binary.read_bytes()).hexdigest(),
        "embedded_device_programs": True,
    }, indent=2) + "\n", encoding="utf-8")
    print("Published Particle RT " + version + " for SDK-free distribution builds")


if __name__ == "__main__":
    main()
