"""Inventory native environment entry points without importing or starting Minecraft.

Run after createMinecraftArtifacts. --check fails on source/entry-point drift; it is
deliberately separate from gameplay acceptance (an inventory is not a parity proof).
"""
import argparse
import hashlib
import json
import re
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[2]
METHODS = r"entityInside|stepOn|fallOn|updateEntityAfterFallOn|getCollisionShape|getSpeedFactor|getJumpFactor|handleEntityCollision|isLadder|makesOpenTrapdoorAboveClimbable|isScaffolding|isBurning|getFriction|collisionExtendsVertically|addLandingEffects|addRunningEffects|getSoundType|canEntityDestroy|onCaughtFire|isEmpty"
DECL = re.compile(r"(?:public|protected|default)\s+(?:(?:public|static)\s+)?[\w<>.?]+\s+(" + METHODS + r")\s*\(")
QUERIES = re.compile(r"\b(getEntitiesOfClass|getEntities|getEntityCount|processingEntity|startCrushing)\b")


def declarations(text):
    # Inventory/container isEmpty() has no relationship to block emptiness.
    return sorted({match.group(1) for match in DECL.finditer(text)
                   if match.group(1) != "isEmpty" or re.match(r"\s*BlockState\b", text[match.end():])})


def inventory():
    jars = sorted((ROOT / "build/moddev/artifacts").glob("neoforge-*-sources.jar"))
    if len(jars) != 1:
        raise SystemExit("Expected one generated NeoForge source archive; run createMinecraftArtifacts first")
    sources = {}
    with ZipFile(jars[0]) as archive:
        for path in archive.namelist():
            if path.startswith("net/minecraft/world/level/block/") and path.endswith(".java"):
                sources[path] = archive.read(path).decode("utf-8")
        for name in ("Entity", "LivingEntity"):
            path = f"net/minecraft/world/entity/{name}.java"
            sources[path] = archive.read(path).decode("utf-8")
        # NeoForge adds default block callbacks outside Minecraft's class tree.
        path = "net/neoforged/neoforge/common/extensions/IBlockExtension.java"
        sources[path] = archive.read(path).decode("utf-8")
    create = ROOT / ".refs/Create/src/main/java"
    if not create.is_dir():
        raise SystemExit("Missing .refs/Create reference source")
    for path in create.rglob("*.java"):
        text = path.read_text(encoding="utf-8")
        if declarations(text) or QUERIES.search(text) or path.name == "PackageEntity.java":
            sources[path.relative_to(create).as_posix()] = text
    entries = []
    for path, text in sorted(sources.items()):
        methods = declarations(text)
        queries = sorted(set(QUERIES.findall(text)))
        if not methods and not queries and not path.endswith(("/Entity.java", "/LivingEntity.java", "/PackageEntity.java")):
            continue
        entries.append({"source": path, "entries": methods, "entity_queries": queries,
                        "sha256": hashlib.sha256(text.encode()).hexdigest()})
    return {"minecraft": "1.21.1", "reference_create": "6.0.11", "sources": entries}


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--acceptance", action="store_true", help="Also require every semantic row to pass native differential acceptance")
    args = parser.parse_args()
    output = ROOT / "docs/package-environment-native-inventory.json"
    data = json.dumps(inventory(), ensure_ascii=False, indent=2) + "\n"
    if args.check:
        if not output.exists() or output.read_text(encoding="utf-8") != data:
            raise SystemExit("Native environment inventory changed; audit new entries before updating the manifest")
    else:
        output.write_text(data, encoding="utf-8")
    result = json.loads(data)
    reviews = json.loads((ROOT / "docs/package-environment-coverage.json").read_text(encoding="utf-8"))
    expected = {row["source"]: row for row in result["sources"]}
    actual = reviews["sources"]
    fixtures = []
    for root in (ROOT / "src/test", ROOT / "scripts/particles"):
        fixtures.extend(path.read_text(encoding="utf-8") for path in root.rglob("*.java"))
    fixture_names = set(re.findall(r"\b(?:class\s+|void\s+)(\w+)\s*[({]", "\n".join(fixtures)))
    for profile, definition in reviews["profiles"].items():
        missing = set(definition["cases"]) - fixture_names
        if missing:
            raise SystemExit(f"Environment review refers to missing tests ({profile}): {missing}")
    if expected.keys() != actual.keys():
        raise SystemExit(f"Environment review missing/new rows: {expected.keys() ^ actual.keys()}")
    for source, row in expected.items():
        review = actual[source]
        if review["entries"] != row["entries"] or review["entity_queries"] != row["entity_queries"]:
            raise SystemExit(f"Environment review entry drift: {source}")
        if review["profile"] not in reviews["profiles"] or not review["condition"] or not review["verification"]:
            raise SystemExit(f"Incomplete explicit environment review: {source}")
    if args.acceptance:
        pending = [source for source, row in actual.items() if row["verification"] != "native-differential-complete"]
        if pending:
            raise SystemExit(f"Environment parity NOT accepted: {len(pending)} rows still lack complete native differential evidence")
    print(f"Native environment inventory: {len(result['sources'])} source classes; {'verified' if args.check else 'written'}")


if __name__ == "__main__":
    main()
