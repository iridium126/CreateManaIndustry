"""Publish the developer-built particle DLL and its distribution manifest."""
import argparse
import hashlib
import json
import re
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
NATIVE = ROOT / "src/main/native"
MODULES = {
    "optix": ("CmiParticleOptix.dll", "Version.h", "CMI_OPTIX_VERSION"),
    "dxr": ("CmiParticleDxr.dll", "StormDxrVersion.h", "CMI_DXR_VERSION"),
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("backend", choices=MODULES)
    backend = parser.parse_args().backend
    dll, header, version_macro = MODULES[backend]
    source = NATIVE / f"particle_{backend}" / header
    version = re.search(rf'#define {version_macro} "([0-9.]+)"', source.read_text())[1]
    binary = ROOT / f".gradle/particle-{backend}/build" / dll
    contract = json.loads((ROOT / "src/main/gpu/particle-resources.json").read_text(encoding="utf-8"))["constants"]
    release = NATIVE / "prebuilt/windows-x86_64"
    shutil.copyfile(binary, release / dll)
    manifest = {
        "dll": dll, "platform": "windows-x86_64", "version": version, "abi": contract["ABI"],
        "dll_sha256": hashlib.sha256(binary.read_bytes()).hexdigest(),
        "embedded_device_programs": True,
    }
    (release / f"particle-{backend}.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Published {dll} {version} for SDK-free distribution builds")


if __name__ == "__main__":
    main()
