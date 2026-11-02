"""Summarize isolated candidate stages; deliberately does not issue production qualification."""
import csv
import hashlib
import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path("build/shadow-completion")
FIRST_PROCESS = int(sys.argv[1]) if len(sys.argv) > 1 else 0


def median(values):
    return statistics.median(values)


def summarize(kind, filename, keys, a, b):
    reports = []
    hashes = []
    for process in range(FIRST_PROCESS, FIRST_PROCESS + 3):
        path = ROOT / f"{kind}-{process}" / filename
        hashes.append({"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
        cells = defaultdict(lambda: defaultdict(list))
        for row in csv.DictReader(path.open(encoding="utf-8")):
            cells[tuple(row[k] for k in keys)][row["comparison"]].append(row)
        for key, comparisons in sorted(cells.items()):
            aa, ab = comparisons["AA"], comparisons["AB"]
            if len(aa) != 64 or len(ab) != 64:
                raise ValueError(f"Incomplete paired cell {path} {key}")
            baseline = median([float(r[a]) for r in ab])
            candidate = median([float(r[b]) for r in ab])
            noise = abs(median([float(r[b]) - float(r[a]) for r in aa]))
            threshold = max(baseline * .005, .002, noise)
            reports.append(dict(process=process, cell=dict(zip(keys, key)), baseline_ms=baseline,
                                candidate_ms=candidate, delta_ms=candidate-baseline, aa_bias_ms=noise,
                                gate_ms=threshold, stage_regression=candidate-baseline > threshold))
    return dict(samples=hashes, cells=reports,
                decision="NOT QUALIFIED: isolated stages only; full production chain and pack raster measurements required")


if __name__ == "__main__":
    report = {
        "inputs_gpu": summarize("inputs", "samples.csv", ["count", "mode", "variant"], "a_gpu_ms", "b_gpu_ms"),
        "inputs_cpu": summarize("inputs", "samples.csv", ["count", "mode", "variant"], "a_cpu_ms", "b_cpu_ms"),
        "native_depth_collect": summarize("depth", "native-depth-collect.csv", ["count", "size"], "baseline_ms", "candidate_ms"),
    }
    (ROOT / "summary.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    for name, section in report.items():
        print(name, len(section["cells"]), "process/cells;", section["decision"])
