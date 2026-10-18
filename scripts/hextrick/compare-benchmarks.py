"""Compare identical interop probes; retain medians and spread, never infer throughput."""
import argparse
import json
from collections import defaultdict
from pathlib import Path
from statistics import median


def load(path):
    document = json.loads(path.read_text(encoding="utf-8"))
    grouped = defaultdict(list)
    for sample in document["samples"]:
        grouped[sample["probe"]].append(sample)
    return document, grouped


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path, nargs="?", default=Path("build/benchmarks/hexinterop-baseline-final"))
    parser.add_argument("current", type=Path, nargs="?", default=Path("build/benchmarks/hexinterop-final"))
    args = parser.parse_args()
    results = []
    for filename in ("data.json", "scenes.json"):
        before_doc, before = load(args.baseline / filename)
        after_doc, after = load(args.current / filename)
        for key in ("java", "heap", "os", "seed"):
            if before_doc.get(key) != after_doc.get(key):
                raise ValueError(f"Different environment/input: {filename}: {key}")
        if before.keys() != after.keys():
            raise ValueError(f"Different probes: {filename}")
        for probe, old_samples in before.items():
            new_samples = after[probe]
            if sorted(s["iterations"] for s in old_samples) != sorted(s["iterations"] for s in new_samples):
                raise ValueError(f"Different sample lengths: {probe}")
            entry = {"probe": probe, "rounds": len(new_samples), "iterationsPerRound": new_samples[0]["iterations"]}
            for metric in ("nsPerOp", "bytesPerOp", "cpuNsPerOp"):
                old = [s[metric] for s in old_samples]
                new = [s[metric] for s in new_samples]
                old_median, new_median = median(old), median(new)
                entry[metric] = {"baseline": old_median, "current": new_median,
                                 "deltaPercent": (new_median / old_median - 1) * 100 if old_median else None,
                                 "baselineRange": [min(old), max(old)], "currentRange": [min(new), max(new)]}
            results.append(entry)
    output = {"baseline": str(args.baseline), "current": str(args.current), "probes": results,
              "limitations": "Microbenchmarks, seven medians; Windows CPU timer is quantized. Scene observers are packet sinks, not clients."}
    (args.current / "comparison.json").write_text(json.dumps(output, indent=2), encoding="utf-8")
    lines = ["| Probe | Baseline µs/op | Current µs/op | Time Δ | Baseline B/op | Current B/op | Allocation Δ |",
             "| --- | ---: | ---: | ---: | ---: | ---: | ---: |"]
    for entry in results:
        time, allocation = entry["nsPerOp"], entry["bytesPerOp"]
        lines.append(f"| {entry['probe']} | {time['baseline']/1000:.2f} | {time['current']/1000:.2f} | {time['deltaPercent']:+.1f}% | "
                     f"{allocation['baseline']:.0f} | {allocation['current']:.0f} | {allocation['deltaPercent']:+.1f}% |")
    markdown = "\n".join(lines) + "\n"
    (args.current / "comparison.md").write_text(markdown, encoding="utf-8")
    print(markdown)


if __name__ == "__main__":
    main()
