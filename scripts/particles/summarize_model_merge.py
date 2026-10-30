"""Summarize interleaved GPU samples without confusing pooled drift with paired speedup."""
import argparse
import csv
import json
import math
import statistics as stats
from collections import defaultdict
from pathlib import Path


def percentile(values, fraction):
    values = sorted(values)
    return values[max(0, math.ceil(len(values) * fraction) - 1)]


def read(path):
    with path.open(encoding="utf-8-sig", newline="") as stream:
        return list(csv.DictReader(stream))


def summarize(rows, unchanged):
    groups = defaultdict(list)
    for row in rows:
        groups[(row["pack"], row.get("segment", "three segments"), int(row["scene"]), int(row["count"]))].append(row)
    metrics = []
    for (pack, segment, scene, count), samples in sorted(groups.items()):
        assert {int(row["round"]) for row in samples} == {0, 1, 2}, (pack, segment, scene, count)
        assert len(samples) == 72, (pack, segment, scene, count, len(samples))
        a = [float(row["baseline_ms"]) for row in samples]
        b = [float(row["candidate_ms"]) for row in samples]
        deltas = []
        for round_index in range(3):
            paired = [float(row["candidate_ms"]) / max(float(row["baseline_ms"]), 1e-6) - 1
                      for row in samples if int(row["round"]) == round_index]
            deltas.append(stats.median(paired))
        metrics.append(dict(pack=pack, segment=segment, scene=scene, count=count,
                            baseline_p50=stats.median(a), baseline_p95=percentile(a, .95),
                            candidate_p50=stats.median(b), candidate_p95=percentile(b, .95),
                            paired_delta=stats.median(deltas), round_deltas=deltas))
    # Source-identical controls include program switching, driver scheduling and
    # clock drift. Use the largest observed control-round deviation, not a
    # post-hoc per-pack tolerance. A 0.5% floor avoids claims below practical noise.
    controls = [abs(delta) for item in metrics if item["pack"] in unchanged and item["count"] >= 10000
                for delta in item["round_deltas"]]
    noise = max([.005] + controls)
    improvements = [item for item in metrics if item["count"] >= 10000 and item["scene"] != 2
                    and all(delta < -noise for delta in item["round_deltas"])]
    regressions = [item for item in metrics if item["count"] >= 10000
                   and all(delta > noise for delta in item["round_deltas"])]
    return dict(noise_band=noise, improvements=improvements, regressions=regressions, metrics=metrics,
                passed=bool(improvements) and not regressions)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    parser.add_argument("--require-gate", action="store_true")
    args = parser.parse_args()
    compile_rows = read(args.directory / "compile.csv")
    unchanged_production = {row["pack"] for row in compile_rows if row["context"] == "0" and not row["applied"]}
    unchanged_fragment = {row["pack"] for row in compile_rows if row["context"] == "0" and "fragment:" not in row["applied"]}
    result = {"fragment": summarize(read(args.directory / "draw.csv"), unchanged_fragment),
              "production": summarize(read(args.directory / "production.csv"), unchanged_production)}
    packs = {row["pack"] for row in compile_rows}
    # The task targets fragment efficiency: require a repeatable improvement
    # there and reject repeatable regressions in either isolated fragments or
    # complete model draws. Do not claim a whole-model speedup when geometry
    # dominates and its improvement is below the measured A/A noise band.
    result["passed"] = len(packs) >= 8 and result["fragment"]["passed"] and not result["production"]["regressions"]
    (args.directory / "summary.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    lines = ["# MODEL merge paired GPU benchmark", "", "Controlled hidden-context measurements; not in-game FPS.", "",
             f"Fragment improvement + whole-model non-regression gate: {'PASS' if result['passed'] else 'NOT MET'}.", ""]
    for name in ("fragment", "production"):
        data = result[name]
        lines += [f"## {name}", "", f"A/A noise band: {data['noise_band'] * 100:.2f}%. "
                  f"Reproducible improving scenarios: {len(data['improvements'])}; regressions: {len(data['regressions'])}.", "",
                  "50,000 instances, dense scene; fragment table uses the blended segment.", "",
                  "| Pack | Baseline p50/p95 ms | Candidate p50/p95 ms | Paired change | Three rounds |",
                  "|---|---:|---:|---:|---|"]
        for item in data["metrics"]:
            if item["count"] != 50000 or item["scene"] != 1 or name == "fragment" and item["segment"] != "ghost":
                continue
            rounds = ", ".join(f"{v * 100:+.2f}%" for v in item["round_deltas"])
            lines.append(f"| {item['pack']} | {item['baseline_p50']:.4f}/{item['baseline_p95']:.4f} | "
                         f"{item['candidate_p50']:.4f}/{item['candidate_p95']:.4f} | {item['paired_delta'] * 100:+.2f}% | {rounds} |")
        lines += ["", "Change is the median of per-round medians of paired ratios; pooled percentiles may move differently under drift.", ""]
        for item in data["regressions"]:
            lines.append(f"Regression: {item['pack']} {item['segment']} scene={item['scene']} count={item['count']}: "
                         f"{item['paired_delta'] * 100:+.2f}%")
    (args.directory / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Performance gate: {'PASS' if result['passed'] else 'NOT MET'}; "
          f"fragment noise={result['fragment']['noise_band']*100:.2f}%, production noise={result['production']['noise_band']*100:.2f}%")
    if args.require_gate and not result["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
