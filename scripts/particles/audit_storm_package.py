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
    mismatches, templates = [], {}
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
            for package in ("client/allaystorm", "client/particles/engine"):
                classes += list((base/"com/iridium126/createmanaindustry"/package).rglob("*.class"))
            classes.append(base/"com/iridium126/createmanaindustry/CreateManaIndustry.class")
            for path in classes:
                verify(path, path.relative_to(base).as_posix())
            for template in ("dome-template-v4.bin", "helix-template-v1.bin"):
                name = "data/createmanaindustry/"+template
                verify(Path("src/main/resources")/name, name)
                templates[template] = digest(mod.read(name))
                baseline = Path("build/cold-escape/baseline/resources")/name
                if baseline.exists() and digest(baseline.read_bytes()) != templates[template]:
                    mismatches.append("baseline template: "+name)
            fixtures = [name for name in names if re.search(r"GameTests|GpuValidation|/gametest/|allaystorm/.*certificate", name)]
    protocol = '.versioned("18")' in Path("src/main/java/com/iridium126/createmanaindustry/CreateManaIndustry.java").read_text()
    result = dict(jar=str(jar.resolve()), sha256=digest(jar.read_bytes()), bytes=jar.stat().st_size,
                  shaders_verified=len(shaders), classes_verified=len(classes), templates=templates,
                  protocol18=protocol, mismatches=mismatches, bundled_validation_fixtures=fixtures)
    output=Path("build/cold-escape/package-audit.json");output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2))
    if mismatches or fixtures or not protocol:
        raise SystemExit("Distribution audit failed")


if __name__ == "__main__":
    main()
