"""Validate three independent runs and summarize isolated shadow experiments.

This does not qualify shader-pack depth semantics, scene-shadow reception or client FPS.
"""
import argparse
import collections
import csv
import hashlib
import itertools
import json
import math
from pathlib import Path
from statistics import median

PACKS = {
    "Bliss_v2.1.2_(Chocapic13_Shaders_edit).zip", "BSL_v10.1.1.zip",
    "ComplementaryUnbound_r5.9.3.zip", "iterationT 3.2.0.zip", "photon_v1.4.zip",
    "Sildur's Vibrant Shaders v2.02 Extreme.zip", "Sundial Lite v1.2.0.zip",
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def rows(path):
    with path.open(encoding="utf-8-sig", newline="") as stream:
        return list(csv.DictReader(stream))


def receipt(directory, task):
    records = [json.loads(p.read_text(encoding="utf-8")) for p in directory.glob("*metadata.json")]
    matching = [r for r in records if r["task"] == task]
    require(len(matching) == 1 and matching[0].get("completed") is True, f"incomplete/ambiguous run: {directory}")
    return matching[0]


def grouped(records, keys, samples=64):
    groups = collections.defaultdict(list)
    for row in records:
        groups[tuple(row[k] for k in keys)].append(row)
    for key, group in groups.items():
        require(len(group) == samples and {int(r["pair"]) for r in group} == set(range(samples)), f"missing/duplicate samples: {key}")
        for row in group:
            for name, value in row.items():
                if name.endswith("_ms"):
                    require(math.isfinite(float(value)) and float(value) > 0, f"invalid timing: {key}")
    return groups


def quantile(group, name, q):
    data = sorted(float(r[name]) for r in group)
    return median(data) if q == .5 else data[int((len(data)-1)*q)]


def comparison(group, a="a_ms", b="b_ms", aa=None, control=None):
    result = {}
    for name, q in (("p50", .5), ("p95", .95)):
        av, bv = quantile(group, a, q), quantile(group, b, q)
        noise = abs(quantile(group, aa, q)-av) if aa else 0
        if control:
            noise = max(noise, abs(quantile(control, a, q)-quantile(control, b, q)))
        threshold = max(.002, .005*av, noise)
        result[name] = {"a_ms": av, "b_ms": bv, "delta_ms": bv-av,
                        "percent": (bv/av-1)*100, "threshold_ms": threshold,
                        "decision": "regression" if bv-av > threshold else "gain" if av-bv > threshold else "noise"}
    return result


def native(root):
    data, receipts, hashes, stages = [], [], [], []
    for round_id in range(3):
        directory = root/f"draw-seven-{round_id}"
        receipts.append(receipt(directory, "validateShadowDrawGpu"))
        contracts = rows(directory/"shadow-draw-contracts.csv")
        require({c["pack"] for c in contracts} == PACKS | {"Solas Shader V3.8.zip"}, "eight-pack contract matrix incomplete")
        require(all(c["coverage"] == "true" for c in contracts if c["pack"] in PACKS), "native output probe missed geometry")
        solas = next(c for c in contracts if c["pack"] not in PACKS)
        require(solas["active_images"] == "true" and solas["coverage"] == "false", "Solas fallback contract changed")
        hashes.append(hashlib.sha256((directory/"shadow-input-candidate.glsl").read_bytes()).hexdigest())
        # Broad Java metadata can change when unrelated runtime diagnostics change.
        # Compare every actual compiled pack/CMI stage, not an unrelated aggregate hash.
        compiled = {str(p.relative_to(directory)): hashlib.sha256(p.read_bytes()).hexdigest()
                    for p in directory.glob("*/shadow-*-*.glsl")}
        require(len(compiled) >= 32, "missing compiled native stage snapshots")
        stages.append(compiled)
        current = rows(directory/"shadow-draw-samples.csv")
        require(all(int(r["round"]) == round_id for r in current), "round label mismatch")
        data += current
    for field in ("shaderSha256",):
        require(len({r[field] for r in receipts}) == 1, f"native inputs changed across rounds: {field}")
    require(len(set(hashes)) == 1, "CMI candidate source changed across rounds")
    require(stages[0] == stages[1] == stages[2], "compiled native stage sources changed across rounds")
    groups = grouped(data, ("pack", "count", "workload", "scene", "round"))
    expected = set(itertools.product(PACKS, ("65536", "131072"), ("1", "5"), ("0", "1", "2"), ("0", "1", "2")))
    require(set(groups) == expected, "native timing matrix incomplete")
    result = [{"key": k, **comparison(g, aa="aa_ms")} for k, g in sorted(groups.items())]
    return {"cells": len(result), "samples": len(data), "source_sha256": hashes[0], "results": result,
            "regressions": sum(any(r[q]["decision"] == "regression" for q in ("p50", "p95")) for r in result),
            "scope": "native shadow model draw; seven raster contracts; Solas original context; no client FPS claim"}


def kernels(root, prefix, kinds, task):
    data = []
    for round_id in range(3):
        directory = root/f"{prefix}-{round_id}"
        receipt(directory, task)
        records = rows(directory/"samples.csv")
        require({r["kind"] for r in records} == set(kinds), f"missing experiment: {directory}")
        # CPU timing contains three local windows in each independent process.
        for r in records:
            if r["kind"] == "STATE_CPU":
                r["scenario"] += f"/process-{round_id}"
            else:
                require(int(r["round"]) == round_id, "kernel round label mismatch")
        data += records
    groups = grouped(data, ("kind", "count", "scenario", "round"))
    if prefix == "batch":
        expected = set(itertools.product(kinds, ("65536", "131072"), ("body", "carrier", "half"), ("0", "1", "2")))
    else:
        expected = set(itertools.product(("AA", "FULL_AB", "GROUP_AB"), ("65536", "131072"), ("visible", "hidden", "mixed"), ("0", "1", "2")))
        expected |= set(itertools.product(("HIZ_COST",), ("65536", "131072"), ("63x65", "1024x1024", "2048x2048"), ("0", "1", "2")))
        expected |= set(itertools.product(("STATE_CPU",), ("0",), ("boundary/process-0", "boundary/process-1", "boundary/process-2"), ("0", "1", "2")))
    require(set(groups) == expected, f"kernel timing matrix incomplete: {prefix}")
    results = []
    for key, group in sorted(groups.items()):
        kind, count, scene, round_id = key
        control_kind = {"FULL_AB": "AA", "GROUP_AB": "AA", "BATCH_AB": "BATCH_AA"}.get(kind)
        control = groups[(control_kind, count, scene, round_id)] if control_kind else None
        results.append({"key": key, **comparison(group, control=control)})
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path, nargs="?", default=Path("build/shadow-quality"))
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    report = {"native": native(args.root),
              "quality": kernels(args.root, "qualified", ("AA", "FULL_AB", "GROUP_AB", "HIZ_COST", "STATE_CPU"), "validateShadowQualityGpu"),
              "reservation": kernels(args.root, "batch", ("BATCH_AA", "BATCH_AB"), "validateShadowCollectGpu"),
              "depth_qualified": False, "client_accepted": False}
    output = args.output or args.root/"summary.json"
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    print(f"native: {report['native']['cells']} cells, {report['native']['samples']} samples, {report['native']['regressions']} regressions beyond matched A/A and max(0.5%, 0.002 ms)")
    print(f"wrote {output}; depth and client qualification remain false")


if __name__ == "__main__":
    main()
