"""Current particle tools. Run from any directory; evidence never implies client acceptance."""
from __future__ import annotations
import argparse
import csv
import hashlib
import json
import math
import statistics
import subprocess
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
INIT = ROOT / "scripts/particles/gpu.init.gradle"

def digest(path):
    result=hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda:stream.read(1<<20),b""): result.update(chunk)
    return result.hexdigest()

def write(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

def gradle(tasks, output, properties=()):
    output = Path(output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    command = [str(ROOT / "gradlew.bat"), "-I", str(INIT), *tasks,
               f"-PparticleOutput={output}", *properties, "--no-configuration-cache", "--console=plain"]
    with (output / "gradle.log").open("w", encoding="utf-8") as log:
        subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True)

def verify(args):
    output = args.output.resolve()
    gradle(["test", "verifyParticleGpu"], output,
           ["-PparticleGlOnly"] if args.gl_only else [])
    for suite in args.suite:
        if not suite.isidentifier():
            raise ValueError("Invalid fixture class")
        gradle(["runParticleSuite"], output / suite, [f"-PparticleSuite={suite}"])
    report = json.loads((output / "gpu.json").read_text(encoding="utf-8"))
    if not report["passed"]:
        raise ValueError("GPU checks failed")
    print(f"Passed GPU checks: {report['checks']}; evidence: {output}")

def replay(args):
    manifest = json.loads((args.capture / "manifest.json").read_text(encoding="utf-8"))
    if manifest.get("kind") != "gpu-command-chain" or not manifest.get("complete"):
        raise ValueError("A complete command journal is required")
    gradle(["replayParticleGpu"], args.output, [f"-PparticleCapture={args.capture.resolve()}", f"-PparticleSamples={getattr(args,'samples',32)}"])
    print(args.output.resolve() / "replay.json")

def bench(args):
    # Three independent JVMs for each cell, with adjacent identical A controls.
    # Each journal retains its own recorded algorithm and frequency. No shader substitution.
    runs = []
    for round_index in range(3):
        order = [("A", args.a), ("AA", args.a), ("B", args.b)]
        if round_index % 2:
            order.reverse()
        for label, capture in order:
            output = args.output / f"round-{round_index + 1}-{label}"
            replay(argparse.Namespace(capture=capture, output=output, samples=args.samples))
            report = json.loads((output / "replay.json").read_text(encoding="utf-8"))
            runs.append({"round": round_index + 1, "label": label,
                         "capture": str(capture.resolve()), "timing_ms": report["timing_ms"], "distribution": report["distribution"]})
    write(args.output / "bench.json", {"scope": "recorded GL compute/copy/upload chain; excludes unrecorded draws and asynchronous exports",
        "client_acceptance": "pending", "rt_overlap_proven": False, "runs": runs, "comparison": journal_comparison(runs),
        "qualification": "This journal timing alone cannot qualify whole-frame RT adoption."})

def journal_comparison(runs):
    comparisons=[]
    for round_index in sorted({r["round"] for r in runs}):
        cells={r["label"]:r for r in runs if r["round"]==round_index}
        base,repeat,candidate=(cells[key]["timing_ms"][0] for key in ("A","AA","B"))
        noise=abs(base-repeat)
        threshold=max(noise,base*.01,.1)
        tails=all(cells["B"]["distribution"][key]<=max(cells["A"]["distribution"][key],cells["AA"]["distribution"][key]) for key in ("gpu_p95_ms","gpu_p99_ms"))
        comparisons.append({"round":round_index,"baseline_mean_ms":base,"repeat_mean_ms":repeat,"candidate_mean_ms":candidate,
            "aa_noise_ms":noise,"threshold_ms":threshold,"gain_ms":base-candidate,"tails_within_controls":tails,
            "journal_gate":base-candidate>threshold and tails})
    return {"rounds":comparisons,"all_journal_gates":all(r["journal_gate"] for r in comparisons),
            "whole_frame_qualification":False,"reason":"Recorded GL scopes only; client rendering and asynchronous RT are excluded."}

def quantile(values, probability):
    values = sorted(v for v in values if math.isfinite(v) and v > 0)
    if not values:
        raise ValueError("No finite positive frame samples")
    return values[max(0, math.ceil(len(values) * probability) - 1)]

def frame_window(path):
    path = Path(path)
    metadata = json.loads(path.with_suffix(".json").read_text(encoding="utf-8"))
    if metadata.get("invalid_reason"):
        raise ValueError(f"Invalid frame window: {path}: {metadata['invalid_reason']}")
    if metadata.get('spatial_unretired_at_export', 0):
        raise ValueError(f'Spatial lifecycle receipts did not drain: {path}')
    backend = str(metadata.get('backend') or '')
    if 'active=rt-' in backend or ('requested=rt' in backend and 'active=rt ' not in backend):
        raise ValueError(f'RT backend was not active for qualification: {backend}')
    if 'active=rt ' in backend:
        from spatial_audit import audit
        spatial = path.with_name(path.stem + '-spatial.csv')
        if not spatial.is_file() or not audit(spatial, True)['passed']:
            raise ValueError(f'Incomplete RT lifecycle evidence: {path}')
    with path.open(encoding="utf-8-sig", newline="") as stream:
        rows = list(csv.DictReader(stream))
    # The schema is selected explicitly; missing samples are never replaced by zero.
    key = next((k for k in ("interval_ms", "frame_ms", "frame_interval_ms") if rows and k in rows[0]), None)
    if key is None:
        raise ValueError(f"Unknown frame CSV schema: {path}")
    values = [float(row[key]) for row in rows]
    if any(not math.isfinite(v) or v <= 0 for v in values):
        raise ValueError("Incomplete frame intervals")
    if len(values) < 1000 or sum(values) < 30_000:
        raise ValueError("Each client window requires 30 seconds and 1,000 frames")
    stages={};duration=sum(values)
    for row,ms in zip(rows,values):
        stage=row.get("attack_contract_stage")
        if stage: stages[stage]=stages.get(stage,0)+ms/duration
    camera_path=path.with_name(path.stem+"-camera.csv")
    camera=None
    if camera_path.exists():
        with camera_path.open(encoding="utf-8-sig",newline="") as stream: camera_rows=list(csv.DictReader(stream))
        keys=("x","y","z","yaw_degrees","pitch_degrees","vertical_fov_degrees")
        camera={key:[min(float(r[key]) for r in camera_rows),max(float(r[key]) for r in camera_rows)] for key in keys}
        if any(not math.isfinite(v) for span in camera.values() for v in span): camera=None
    settings={key:metadata.get(key) for key in ("context","dimension","size","fps_limit","vsync","shadow_quality","rift_config","gpu","driver")}
    if settings["context"] is not None:
        settings["context"]=";".join(part for part in settings["context"].split(";") if not part.strip().startswith(("backend=","engine=")))
    live=[int(row["live"]) for row in rows if row.get("live") and int(row["live"])>=0]
    return {"file": str(path.resolve()), "mean_ms": statistics.fmean(values),
            "fps": 1000 / statistics.fmean(values), "p95_ms": quantile(values, .95),
            "p99_ms": quantile(values, .99), "samples": len(values),
            "process": (metadata.get("process_id"), metadata.get("process_started")),
            "settings":settings,"camera":camera,"stages":stages,"live_mean":statistics.fmean(live) if live else None,
            "display_interpolation":metadata.get("display_interpolation"),"query_budget":metadata.get("query_budget"),
            "context": metadata.get("context"), "backend": metadata.get("backend")}

def mismatch(a,b):
    reasons=[]
    if any(value is None for value in a["settings"].values()) or a["settings"]!=b["settings"]: reasons.append("missing/different settings provenance")
    if not a["camera"] or not b["camera"]: reasons.append("missing camera trace")
    else:
        for key,span in a["camera"].items():
            other=b["camera"][key];tolerance=.001 if key in ("x","y","z") else .01
            if max(span[1]-span[0],other[1]-other[0],abs(span[0]-other[0]),abs(span[1]-other[1]))>tolerance: reasons.append("camera "+key)
    if not a["stages"] or a["stages"].keys()!=b["stages"].keys() or any(abs(a["stages"].get(k,0)-b["stages"].get(k,0))>.05 for k in a["stages"]): reasons.append("attack stage coverage")
    if a["live_mean"] is None or b["live_mean"] is None or abs(a["live_mean"]-b["live_mean"])>max(1,a["live_mean"]*.01): reasons.append("particle population")
    if a["display_interpolation"]!=b["display_interpolation"] or a["query_budget"]!=b["query_budget"]: reasons.append("display/query policy differs; attribute separately")
    return reasons

def analyze(args):
    a, aa, b = [[frame_window(path) for path in paths] for paths in (args.a, args.aa, args.b)]
    if any(len(group) != 3 for group in (a, aa, b)):
        raise ValueError("Three A, AA and B windows are required")
    for group in (a, aa, b):
        if len({tuple(w["process"]) for w in group}) != 3 or any(None in w["process"] for w in group):
            raise ValueError("Three independent processes with process provenance are required")
    if len({tuple(w["process"]) for group in (a,aa,b) for w in group})!=9:
        raise ValueError("A, AA and B must each come from independent process launches")
    matching=[{"round":i+1,"aa":mismatch(x,y),"b":mismatch(x,z)} for i,(x,y,z) in enumerate(zip(a,aa,b))]
    noise = max(abs(x["mean_ms"] - y["mean_ms"]) for x, y in zip(a, aa))
    rounds = [{"gain_ms": x["mean_ms"] - y["mean_ms"],
               "threshold_ms": max(noise, .1, x["mean_ms"] * .01),
               "tail_regression": y["p95_ms"] > x["p95_ms"] + noise or y["p99_ms"] > x["p99_ms"] + noise}
              for x, y in zip(a, b)]
    passed = all(r["gain_ms"] > r["threshold_ms"] and not r["tail_regression"] for r in rounds) and all(not m["aa"] and not m["b"] for m in matching)
    write(args.output / "analysis.json", {"a": a, "aa": aa, "b": b, "matched_noise_ms": noise,
        "rounds": rounds, "matching":matching, "performance_gate": passed,
        "adoption": "pending visual/gameplay acceptance and matching scene/camera/settings evidence"})
    print(f"Performance gate: {passed}; visual/gameplay acceptance remains pending")

def topology(args):
    from topology_summary import summarize
    if args.capture is None:
        gradle(['runParticleSuite'], args.output,
               ['-PparticleSuite=RtTopologyReplayGpu', f'-PparticleReplaySeconds={args.seconds}', f'-PparticleReplayMembers={args.members}'])
    report = summarize(args.capture or args.output)
    write(args.output / 'topology-analysis.json', report)
    print(f"Topology control: {report['passed']}; long control: {report['long_control_complete']}; whole-frame qualification: pending")
    if not report['passed']:
        raise SystemExit(1)


def lifecycle(args):
    from spatial_audit import audit
    report = audit(args.capture, args.require_drained)
    write(args.output / "lifecycle.json", report)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if not report['passed']:
        raise SystemExit(1)


def package(args):
    baseline = ROOT / "build/particle-engine/baseline"
    manifest = json.loads((baseline / "manifest.json").read_text(encoding="utf-8"))
    for name, expected in manifest["files"].items():
        if digest(baseline / name) != expected:
            raise ValueError(f"Frozen baseline changed: {name}")
    current_freeze = ROOT / 'build/particle-engine/rt-single-step-baseline'
    current_manifest = current_freeze / 'manifest.json'
    if current_manifest.is_file():
        for name, expected in json.loads(current_manifest.read_text(encoding='utf-8'))['files'].items():
            if digest(current_freeze / name) != expected:
                raise ValueError(f'Frozen current candidate changed: {name}')
    gradle(["writeParticlePackage"], args.output / "build")
    artifact=Path(json.loads((args.output/"build/artifact.json").read_text(encoding="utf-8"))["jar"])
    if not artifact.is_file() or not artifact.resolve().is_relative_to((ROOT/"build").resolve()):
        raise ValueError(f"Invalid Gradle distribution artifact: {artifact}")
    args.output.mkdir(parents=True, exist_ok=True)
    current_rollback = current_freeze / 'rollback' / artifact.name
    rollback = current_rollback if current_rollback.is_file() else next(p for p in baseline.glob('*.jar') if 'diagnostics' not in p.name)
    files = {"mods/" + artifact.name: artifact, "rollback/" + artifact.name: rollback}
    if not args.gl_only:
        for name in ("CmiParticleRt.dll", "spatial.fatbin", "spatial.optixir"):
            files["cmi-particle-rt/" + name] = args.modules / name
    for name in ("particle-engine-dev.md", "particle-engine-performance.md", "particle-engine-install.md", "rt-single-step-status.md"):
        files["docs/" + name] = ROOT / "docs" / name
    files['docs/benchmarks/rt-single-step-2026-11-04.md'] = ROOT / 'docs/benchmarks/rt-single-step-2026-11-04.md'
    for name in ('particle_tool.py', 'spatial_audit.py', 'topology_summary.py'):
        files['analysis/' + name] = ROOT / 'scripts/particles' / name
    for name in ("rt-single-step-verification.json","cleanup-dependencies.json","validation-preserved-tasks.json"):
        path=ROOT/"build/particle-engine"/name
        if path.exists(): files["evidence/"+name]=path
    receipt = {"abi": 12, "default": "gl", "auto_rt_qualified": False,
               "implementation_complete": False, "native_motion_owner": False, "online_rt_visibility": False,
               "acceptance": "工程检查候选；核心迁移尚未完成；客户端实机验收由用户执行",
               "files": {name: digest(path) for name, path in files.items()}}
    target = args.output / "particle-engine-candidate.zip"
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, path in files.items():
            archive.write(path, name)
        archive.writestr("manifest.json", json.dumps(receipt, ensure_ascii=False, indent=2))
    write(args.output / "manifest.json", receipt)
    previous_package = current_freeze / 'previous-client-package.zip'
    with zipfile.ZipFile(args.output / 'particle-engine-rollback.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
        rollback_files = {}
        if previous_package.is_file():
            with zipfile.ZipFile(previous_package) as previous:
                for name in previous.namelist():
                    if name.startswith('mods/') or (not args.gl_only and name.startswith('cmi-particle-rt/')):
                        data = previous.read(name);archive.writestr(name, data);rollback_files[name] = hashlib.sha256(data).hexdigest()
        else:
            name = 'mods/' + artifact.name;archive.write(rollback, name);rollback_files[name] = digest(rollback)
        archive.writestr('manifest.json', json.dumps({'scope': 'previous frozen candidate; restore matched modules together', 'files': rollback_files}, indent=2))
    # Source and historical evidence are separate from the small install/rollback package.
    source=args.output/"particle-engine-source.zip"
    source_files=subprocess.check_output(["git","ls-files","-z","--cached","--others","--exclude-standard"],cwd=ROOT).decode("utf-8").split("\0")
    with zipfile.ZipFile(source,"w",zipfile.ZIP_DEFLATED) as archive:
        for name in source_files:
            path=(ROOT/name).resolve()
            if path.is_file() and path.is_relative_to(ROOT): archive.write(path,name)
    evidence={"candidate-source.zip":source}
    for path in baseline.iterdir():
        if path.is_file(): evidence["frozen-baseline/"+path.name]=path
    if current_freeze.is_dir():
        for path in current_freeze.rglob('*'):
            if path.is_file(): evidence['frozen-current-candidate/'+path.relative_to(current_freeze).as_posix()]=path
    for path in (ROOT/"build/particle-engine").rglob("*"):
        if not path.is_file() or path.is_relative_to(args.output.resolve()) or path.is_relative_to(baseline) or path.is_relative_to(current_freeze):continue
        if path.suffix in (".json",".csv",".log") and path.stat().st_size<16<<20:
            evidence["current/"+path.relative_to(ROOT/"build/particle-engine").as_posix()]=path
    for path in (ROOT/"build/test-results/test").glob("TEST-*.xml"): evidence["junit/"+path.name]=path
    evidence_receipt={"acceptance":"pending","files":{name:digest(path) for name,path in evidence.items()}}
    with zipfile.ZipFile(args.output/"particle-engine-evidence.zip","w",zipfile.ZIP_DEFLATED) as archive:
        for name,path in evidence.items(): archive.write(path,name)
        archive.writestr("manifest.json",json.dumps(evidence_receipt,ensure_ascii=False,indent=2))
    write(args.output/"evidence-manifest.json",evidence_receipt)
    print(target.resolve())

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("verify", "replay", "bench", "analyze", "lifecycle", "topology", "package"):
        p = sub.add_parser(name)
        p.add_argument("--output", type=Path, default=ROOT / "build/particle-engine" / name)
        p.set_defaults(action=globals()[name])
        if name == "verify":
            p.add_argument("--gl-only", action="store_true")
            p.add_argument("--suite", action="append", default=[])
        elif name == "replay":
            p.add_argument("--capture", type=Path, required=True)
        elif name == "lifecycle":
            p.add_argument("--capture", type=Path, required=True)
            p.add_argument("--require-drained", action="store_true")
        elif name == "topology":
            p.add_argument("--capture", type=Path)
            p.add_argument("--seconds", type=int, default=900)
            p.add_argument("--members", type=int, default=65536)
        elif name in ("bench", "analyze"):
            for label in ("a", "b") + (("aa",) if name == "analyze" else ()):
                p.add_argument("--" + label, type=Path, nargs="+" if name == "analyze" else None, required=True)
        else:
            p.add_argument("--modules", type=Path, default=ROOT / ".gradle/particle-rt/build")
            p.add_argument("--gl-only",action="store_true")
        if name in ("replay","bench"): p.add_argument("--samples",type=int,default=32)
    args = parser.parse_args()
    args.action(args)

if __name__ == "__main__":
    main()
