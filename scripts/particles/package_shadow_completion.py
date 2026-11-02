"""Candidate distribution and first-party evidence. No third-party shader sources are redistributed."""
import hashlib
import io
import json
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def git(*args):
    return subprocess.check_output(["git", *args])


def main():
    root = Path.cwd()
    baseline = root / ".local/particle-baselines/reference/shadow-completion-20261102"
    manifest = json.loads((baseline / "manifest.json").read_text(encoding="utf-8"))
    assert all(sha(baseline / name) == digest for name, digest in manifest["files"].items())
    old_index = (baseline / "index.patch").read_bytes().replace(b"\r\n", b"\n")
    assert git("diff", "--cached", "--binary").replace(b"\r\n", b"\n") == old_index, "User's frozen index changed"
    source_jar = root / "build/libs/createmanaindustry-0.2.7.jar"
    with ZipFile(source_jar) as outer:
        with ZipFile(io.BytesIO(outer.read("META-INF/jars/createmanaindustry.jar"))) as inner:
            for cls in ("ShadowDepthPyramid", "ShadowHistoryGpu", "ShadowReceiverContract", "UpdateFrameInputs", "StormShadowSnapshot"):
                assert any(n.endswith("/" + cls + ".class") for n in inner.namelist()), cls
            for resource in ("model_shadow_collect.comp", "shadow_depth_reduce.comp", "shadow_history_validate.comp", "chunks/shadow_depth_test.glsl"):
                name = "assets/createmanaindustry/shaders/particles/" + resource
                assert inner.read(name) == (root / "src/main/resources" / name).read_bytes(), resource
    tests = [ET.parse(p).getroot() for p in (root / "build/test-results/test").glob("TEST-*.xml")]
    totals = {key: sum(int(t.attrib.get(key, 0)) for t in tests) for key in ("tests", "failures", "errors", "skipped")}
    assert totals["tests"] > 1000 and totals["failures"] == totals["errors"] == 0
    result = root / "build/shadow-completion"
    summary = json.loads((result / "summary.json").read_text(encoding="utf-8"))
    assert all(section["cells"] for section in summary.values())
    changed = git("diff", "HEAD", "--name-only").decode().splitlines()
    untracked = git("ls-files", "--others", "--exclude-standard").decode().splitlines()
    sources = {name: sha(root / name) for name in sorted(set(changed + untracked)) if (root / name).is_file()}
    distribution = root / "build/distributions"
    distribution.mkdir(parents=True, exist_ok=True)
    artifact = distribution / "createmanaindustry-0.2.7-shadow-completion-candidate-20261102.jar"
    shutil.copy2(source_jar, artifact)
    evidence = dict(schema=1, artifact_sha256=sha(artifact), baseline_manifest_sha256=sha(baseline / "manifest.json"),
                    baseline_files_verified=len(manifest["files"]), frozen_index_unchanged=True, junit=totals,
                    changed_source_sha256=sources, client_tested=False, eight_pack_receiving_complete=False,
                    production_depth_qualified=False, production_layout_changed=False,
                    scope="Partial implementation; docs/particle-shadow-completion.md lists unfinished acceptance requirements.")
    provenance = distribution / "shadow-completion-candidate-20261102-provenance.json"
    provenance.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    archive = distribution / "shadow-completion-candidate-20261102-results.zip"
    with ZipFile(archive, "w", ZIP_DEFLATED) as out:
        out.write(provenance, "provenance.json")
        out.write(baseline / "manifest.json", "baseline-manifest.json")
        for name in ("index.patch", "worktree.patch", "status.txt"):
            out.write(baseline / name, "baseline-state/" + name)
        for name in sources:
            out.write(root / name, "source/" + name)
        for path in sorted(result.rglob("*")):
            if path.is_file() and path.suffix in (".csv", ".json", ".txt", ".log", ".comp"):
                out.write(path, "results/" + path.relative_to(result).as_posix())
        for path in (root / "build/model-material-validation").glob("*/receiver-status.txt"):
            out.write(path, "receiver-status/" + path.parent.name + ".txt")
        for path in (root / "build/model-material-validation").glob("*/receiver-samples.csv"):
            out.write(path, "receiver-samples/" + path.parent.name + ".csv")
        for path in (root / "build/test-results/test").glob("TEST-*.xml"):
            out.write(path, "junit/" + path.name)
    print(json.dumps(dict(artifact=str(artifact), sha256=evidence["artifact_sha256"], evidence=str(archive), junit=totals,
                         eight_pack_receiving_complete=False), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
