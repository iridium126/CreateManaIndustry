"""Package first-party evidence, without redistributing extracted shader-pack sources."""
import hashlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET
from zipfile import ZipFile, ZIP_DEFLATED


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    root = Path.cwd()
    result = root/"build/shadow-quality"
    summary = json.loads((result/"summary.json").read_text(encoding="utf-8"))
    assert summary["native"]["regressions"] == 0 and summary["depth_qualified"] is False
    baseline = root/".local/particle-baselines/reference/shadow-quality-20261102"
    manifest = json.loads((baseline/"manifest.json").read_text(encoding="utf-8"))
    assert all(sha(baseline/name) == value for name, value in manifest["files"].items())
    source_jar = root/"build/libs/createmanaindustry-0.2.7.jar"
    with ZipFile(source_jar) as outer:
        with ZipFile(io.BytesIO(outer.read("META-INF/jars/createmanaindustry.jar"))) as inner:
            names = set(inner.namelist())
            assert any(n.endswith("/ShadowVertexContext.class") for n in names)
            assert any(n.endswith("/ParticleShadowQuality.class") for n in names)
            assert any(n.endswith("/model_shadow_groups_write.glsl") for n in names)
            assert not any("GpuValidation" in n or "experiments/shadow-quality" in n for n in names)
            for name in ("model_shadow_collect.comp", "model_shadow_prepare.comp", "keygen.comp"):
                resource = "assets/createmanaindustry/shaders/particles/"+name
                assert inner.read(resource) == (root/"src/main/resources"/resource).read_bytes()
    distribution = root/"build/distributions"
    distribution.mkdir(parents=True, exist_ok=True)
    artifact = distribution/"createmanaindustry-0.2.7-shadow-quality-20261102.jar"
    shutil.copy2(source_jar, artifact)
    tracked = subprocess.check_output(["git", "diff", "--name-only"], text=True).splitlines()
    untracked = subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard"], text=True).splitlines()
    sources = {p: sha(root/p) for p in sorted(set(tracked+untracked)) if (root/p).is_file()}
    tests = [ET.parse(p).getroot() for p in (root/"build/test-results/test").glob("TEST-*.xml")]
    totals = {key: sum(int(t.attrib.get(key, 0)) for t in tests) for key in ("tests", "failures", "errors", "skipped")}
    assert totals["tests"] > 0 and totals["failures"] == totals["errors"] == 0
    stage_hashes = {str(p.relative_to(result)): sha(p) for p in result.glob("draw-seven-*/*/shadow-*-*.glsl")}
    evidence = {"schema": 1, "artifact_sha256": sha(artifact), "baseline_manifest_sha256": sha(baseline/"manifest.json"),
                "baseline_files_verified": len(manifest["files"]), "junit": totals,
                "changed_source_sha256": sources, "compiled_native_stage_sha256": stage_hashes,
                "client_tested": False, "depth_qualified": False,
                "scope": "Hidden OpenGL kernels/native shadow draws; client acceptance belongs to user."}
    provenance = distribution/"shadow-quality-20261102-provenance.json"
    provenance.write_text(json.dumps(evidence, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    archive = distribution/"shadow-quality-20261102-results.zip"
    with ZipFile(archive, "w", ZIP_DEFLATED) as out:
        out.write(provenance, "provenance.json")
        out.write(baseline/"manifest.json", "baseline-manifest.json")
        for relative in sources:
            out.write(root/relative, "source/"+relative)
        out.write(result/"summary.json", "results/summary.json")
        for directory in ("qualified-0", "qualified-1", "qualified-2", "batch-0", "batch-1", "batch-2",
                          "draw-seven-0", "draw-seven-1", "draw-seven-2", "storage", "regression", "final-publication",
                          "draw-0", "draw-final-0", "draw-correctness"):
            for p in sorted((result/directory).glob("*")):
                if p.is_file() and p.suffix in (".csv", ".json"):
                    out.write(p, "results/"+str(p.relative_to(result)).replace("\\", "/"))
        out.write(result/"storage/update-packed.comp", "results/storage/update-packed.comp")
        for p in (root/"build/test-results/test").glob("TEST-*.xml"):
            out.write(p, "junit/"+p.name)
    print(json.dumps({"artifact": str(artifact), "artifact_sha256": evidence["artifact_sha256"],
                      "evidence": str(archive), "evidence_sha256": sha(archive), "junit": totals}, indent=2))


if __name__ == "__main__":
    main()
