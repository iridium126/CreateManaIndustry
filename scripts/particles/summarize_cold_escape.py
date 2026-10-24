"""Summarize the recorded cold-compiler and identical-input GPU measurements."""
import csv
import json
from pathlib import Path
import re
import statistics
import sys


def rows(path):
    with path.open(encoding="utf-8-sig", newline="") as source:
        return list(csv.DictReader(source))


def compiler(records):
    groups, slowest, programs = [], [], {}
    for run in range(3):
        selected = [r for r in records if r["cold"] == "true" and int(r["round"]) == run]
        costs = [sum(float(r[k]) for k in ("expand_ms", "compile_ms", "link_ms")) for r in selected]
        groups.append(sum(costs))
        slowest.append(max(costs))
        for row, cost in zip(selected, costs):
            programs.setdefault(row["program"], []).append(cost)
    return dict(programs=len(programs), group_ms=groups, slowest_ms=slowest,
                group_median_ms=statistics.median(groups), slowest_median_ms=statistics.median(slowest),
                program_median_ms={name: statistics.median(values) for name, values in programs.items()},
                warm_group_ms=warm(records))


def warm(records):
    selected = [r for r in records if r["cold"] == "false"]
    last = max(int(r["round"]) for r in selected)
    return sum(sum(float(r[k]) for k in ("expand_ms", "compile_ms", "link_ms"))
               for r in selected if int(r["round"]) == last)


def main():
    root = Path(sys.argv[1] if len(sys.argv) > 1 else "build/cold-escape")
    before = compiler(rows(root/"before-cold/shader-cold.csv") + rows(root/"before-shadow-cold/shader-cold.csv"))
    after = compiler(rows(root/"after-final-cold/shader-cold.csv"))
    for label, target in (("before", before), ("after", after)):
        cache = root/f"{label}-warm/shader-cold.csv"
        if cache.exists():
            target["warm_group_ms"] = warm(rows(cache))
    result = {"compiler": {"before": before, "after": after,
              "group_change_percent": (after["group_median_ms"] / before["group_median_ms"] - 1) * 100,
              "slowest_change_percent": (after["slowest_median_ms"] / before["slowest_median_ms"] - 1) * 100},
              "performance": {}}
    fields = ("steady_grid_update_gpu_p50_ms", "steady_grid_update_gpu_p95_ms", "steady_submit_p95_ms")
    for coverage in ("original", "known-air"):
        for mode in ("coherent", "pressure"):
            case = {}
            for variant in ("before", "after"):
                samples, diagnostics = [], []
                for run in range(1, 4):
                    directory = root/"final-performance"/coverage/f"{mode}{run}"/variant
                    samples.append(rows(directory/"storm-dome-combined.csv")[0])
                    matches = re.findall(r"Dome fixture active=.*", (directory/"run.log").read_text(encoding="utf-8-sig"))
                    diagnostics.append(matches[-1])
                case[variant] = {key: [float(s[key]) for s in samples] for key in fields}
                case[variant]["median"] = {key: statistics.median(case[variant][key]) for key in fields}
                case[variant]["diagnostics"] = diagnostics
            assert case["before"]["diagnostics"] == case["after"]["diagnostics"], (coverage, mode, "workload changed")
            key = "steady_grid_update_gpu_p95_ms"
            case["gpu_p95_change_percent"] = (case["after"]["median"][key]/case["before"]["median"][key]-1)*100
            result["performance"][coverage+"/"+mode] = case
    result["passed"] = result["compiler"]["group_change_percent"] <= -50 and result["compiler"]["slowest_change_percent"] <= -50 and all(c["gpu_p95_change_percent"] <= 5 for c in result["performance"].values())
    (root/"summary.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2))
    if not result["passed"]:
        raise SystemExit("Recorded targets have not all passed; inspect summary.json")


if __name__ == "__main__":
    main()
