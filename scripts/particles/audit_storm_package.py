"""Verify the actual distribution and nested mod against the tested outputs."""
import hashlib
import io
import json
from pathlib import Path
import re
import sys
import zipfile


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    version = re.search(r"^mod_version=(.+)$", Path("gradle.properties").read_text(), re.M)[1].strip()
    jar = Path(sys.argv[1] if len(sys.argv) > 1 else f"build/libs/createmanaindustry-{version}.jar")
    mismatches = []
    native = {}
    with zipfile.ZipFile(jar) as outer:
        with zipfile.ZipFile(io.BytesIO(outer.read("META-INF/jars/createmanaindustry.jar"))) as mod:
            names = set(mod.namelist())
            def require(name):
                if name not in names:
                    mismatches.append(name)
            shaders = [path for path in Path("src/main/resources/assets/createmanaindustry/shaders/particles").rglob("*") if path.is_file()]
            for path in shaders:
                require(path.relative_to("src/main/resources").as_posix())
            classes = []
            base = Path("build/classes/java/main")
            for package in ("client/allaystorm", "content/allaystorm", "client/gpu", "client/particles/engine", "client/particles/shaderpack", "mixin/allaystorm"):
                classes += list((base/"com/iridium126/createmanaindustry"/package).rglob("*.class"))
            classes.append(base/"com/iridium126/createmanaindustry/CreateManaIndustry.class")
            for path in classes:
                require(path.relative_to(base).as_posix())
            metadata = json.loads(Path("src/main/native/prebuilt/windows-x86_64/manifest.json").read_text(encoding="utf-8"))
            module = "native/particle-rt/windows-x86_64/"
            bundled = mod.read(module + "CmiParticleRt.dll") if module + "CmiParticleRt.dll" in names else b""
            require(module + "CmiParticleRt.dll")
            if digest(bundled) != metadata["dll_sha256"]:
                mismatches.append(module + "CmiParticleRt.dll")
            properties = dict(line.split("=", 1) for line in mod.read(module + "release.properties").decode("utf-8").splitlines() if "=" in line) if module + "release.properties" in names else {}
            if properties.get("version") != metadata["version"]:
                mismatches.append(module + "release.properties")
            if not metadata.get("embedded_device_programs"):
                mismatches.append("native release manifest")
            native = dict(version=properties.get("version"), embedded_device_programs=metadata.get("embedded_device_programs"), bytes=len(bundled))
            for template in ("dome-template-v4.bin", "helix-template-v1.bin", "quadratic-c050.bin", "quadratic-c050-fit.json"):
                require("data/createmanaindustry/" + template)
            fixtures = [name for name in names if re.search(r"GameTests|GpuValidation|/gametest/|\.validation\.json$|dome-v4-source|allaystorm/.*certificate", name)]
    protocols = re.findall(r'\.versioned\("([^\"]+)"\)', Path("src/main/java/com/iridium126/createmanaindustry/CreateManaIndustry.java").read_text())
    protocol = protocols[0] if len(protocols) == 1 else None
    result = dict(jar=str(jar.resolve()), bytes=jar.stat().st_size,
                  shaders_verified=len(shaders), classes_verified=len(classes),
                  protocol=protocol, native=native, mismatches=mismatches, bundled_validation_fixtures=fixtures)
    output=Path("build/benchmarks/current/storm-package-audit.json");output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2))
    if mismatches or fixtures or not protocol:
        raise SystemExit("Distribution audit failed")


if __name__ == "__main__":
    main()
