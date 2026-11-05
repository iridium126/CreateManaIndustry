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
    mismatches, resources = [], {}
    native = {}
    with zipfile.ZipFile(jar) as outer:
        with zipfile.ZipFile(io.BytesIO(outer.read("META-INF/jars/createmanaindustry.jar"))) as mod:
            names = set(mod.namelist())
            def verify(path, name):
                if name not in names or digest(mod.read(name)) != digest(path.read_bytes()):
                    mismatches.append(name)
            shaders = list(Path("src/main/resources/assets/createmanaindustry/shaders/particles").rglob("*"))
            shaders = [path for path in shaders if path.is_file()]
            for path in shaders:
                verify(path, path.relative_to("src/main/resources").as_posix())
            classes = []
            base = Path("build/classes/java/main")
            for package in ("client/allaystorm", "content/allaystorm", "client/gpu", "client/particles/engine", "client/particles/shaderpack", "mixin/allaystorm"):
                classes += list((base/"com/iridium126/createmanaindustry"/package).rglob("*.class"))
            classes.append(base/"com/iridium126/createmanaindustry/CreateManaIndustry.class")
            for path in classes:
                verify(path, path.relative_to(base).as_posix())
            release = Path("native/prebuilt/windows-x86_64")
            metadata = json.loads((release / "manifest.json").read_text(encoding="utf-8"))
            module = "native/particle-rt/windows-x86_64/"
            verify(release / "CmiParticleRt.dll", module + "CmiParticleRt.dll")
            properties = dict(line.split("=", 1) for line in mod.read(module + "release.properties").decode("utf-8").splitlines() if "=" in line)
            if properties.get("version") != metadata["version"] or properties.get("abi") != str(metadata["abi"]):
                mismatches.append(module + "release.properties")
            if metadata.get("dll_sha256") != digest(mod.read(module + "CmiParticleRt.dll")) or not metadata.get("embedded_device_programs"):
                mismatches.append("native release manifest")
            native = dict(version=properties.get("version"), abi=properties.get("abi"), embedded_device_programs=metadata.get("embedded_device_programs"), bytes=len(mod.read(module + "CmiParticleRt.dll")))
            for template in ("dome-template-v4.bin", "helix-template-v1.bin", "quadratic-c050.bin", "quadratic-c050-fit.json"):
                name = "data/createmanaindustry/"+template
                verify(Path("src/main/resources")/name, name)
                resources[template] = digest(mod.read(name))
            fixtures = [name for name in names if re.search(r"GameTests|GpuValidation|/gametest/|\.validation\.json$|dome-v4-source|allaystorm/.*certificate", name)]
    protocols = re.findall(r'\.versioned\("([^\"]+)"\)', Path("src/main/java/com/iridium126/createmanaindustry/CreateManaIndustry.java").read_text())
    protocol = protocols[0] if len(protocols) == 1 else None
    result = dict(jar=str(jar.resolve()), sha256=digest(jar.read_bytes()), bytes=jar.stat().st_size,
                  shaders_verified=len(shaders), classes_verified=len(classes), resources=resources,
                  protocol=protocol, native=native, mismatches=mismatches, bundled_validation_fixtures=fixtures)
    output=Path("build/benchmarks/current/storm-package-audit.json");output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2))
    if mismatches or fixtures or not protocol:
        raise SystemExit("Distribution audit failed")


if __name__ == "__main__":
    main()
