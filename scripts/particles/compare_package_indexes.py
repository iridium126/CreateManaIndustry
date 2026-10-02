"""Score quality-qualified, rotated GPU index runs using the agreed weights."""
import csv
import math
import argparse
import statistics
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("directory", nargs="?", default="docs/benchmarks/package-index-2026-10-02")
DIRECTORY = (ROOT / parser.parse_args().directory).resolve()
MODES = ("linked", "exact_ranges", "bounded_linked")
SCENES = ("aligned_stack", "staggered_stack", "continuous_force", "fast_vs_stationary", "moving_platform")
WEIGHTS = {10000: .25, 65536: .25, 131072: .5}

if (DIRECTORY / "summary-5.csv").exists():
    rounds, summary_path, samples_path = 5, DIRECTORY / "summary-5.csv", DIRECTORY / "samples-5.csv"
elif (DIRECTORY / "summary-3.csv").exists():
    rounds, summary_path, samples_path = 3, DIRECTORY / "summary-3.csv", DIRECTORY / "samples-3.csv"
else:
    raise SystemExit("No completed three- or five-round index benchmark was found.")

rows = list(csv.DictReader(summary_path.open(encoding="utf-8")))
samples = list(csv.DictReader(samples_path.open(encoding="utf-8")))
runs = sorted({int(row["run"]) for row in rows})
assert runs == list(range(1, rounds + 1)), runs
assert len(rows) == 3 * 3 * 5 * rounds
for row in rows:
    assert int(row["quality_pass"]) == 1 and int(row["nonfinite"]) == 0 and int(row["min_effective"]) == int(row["count"]), row
    raw = [sample for sample in samples if all(sample[key] == row[key] for key in ("count", "scenario", "index", "run"))]
    assert len(raw) == 200 and {int(sample["sample"]) for sample in raw} == set(range(200)), row
    assert all(int(sample["effective"]) == int(row["count"]) and int(sample["nonfinite"]) == 0
               and float(sample["overlap_max"]) < .002 and float(sample["terrain_penetration_max"]) < 1e-4 for sample in raw), row

medians = {}


def score(field, source_rows, save_medians=False):
    result = {mode: 0.0 for mode in MODES}
    for count, weight in WEIGHTS.items():
        for scene in SCENES:
            values = {mode: statistics.median(float(row[field]) for row in source_rows
                                                if int(row["count"]) == count and row["scenario"] == scene and row["index"] == mode)
                      for mode in MODES}
            minimum = min(values.values())
            for mode in MODES:
                result[mode] += weight / 5 * math.log(values[mode] / minimum)
                if save_medians:
                    medians[(field, count, scene, mode)] = values[mode]
    return {mode: math.exp(value) for mode, value in result.items()}


gpu = score("gpu_p95_ms", rows, True)
cpu = score("cpu_submit_p95_ms", rows, True)
order = sorted(MODES, key=gpu.get)
gap = gpu[order[1]] / gpu[order[0]] - 1
initial_gap = None
if rounds == 5:
    initial_rows = [row for row in rows if int(row["run"]) <= 3]
    initial_gpu = score("gpu_p95_ms", initial_rows)
    initial_order = sorted(MODES, key=initial_gpu.get)
    initial_gap = initial_gpu[initial_order[1]] / initial_gpu[initial_order[0]] - 1
    assert initial_gap < .03, f"Five rounds were run although the first-three-round gap was {initial_gap:.4%}."
elif gap < .03:
    raise SystemExit("Top two are within 3%; run benchmarkPackageIndexes -PpackageIndexBenchmarkExtra, then compare again.")

winner = order[0]
if gap < .03:
    winner = min(order[:2], key=lambda mode: (cpu[mode], sum(int(row["index_extra_bytes"]) for row in rows if row["index"] == mode)))

if rounds == 5:
    decision = (f"前三轮 GPU 前两名差距为 {initial_gap * 100:.4f}%，因此追加两轮；五轮后差距为 {gap * 100:.4f}%。"
                + (f"按约定以 CPU 提交 p95 决胜，{winner} 的加权分数更低。" if gap < .03 else "五轮 GPU 结果达到 3% 优势门槛。"))
else:
    decision = f"三轮 GPU 第一名相对第二名的优势为 {gap * 100:.4f}%，达到 3% 门槛。"

report = [
    "# GPU 包裹空间索引比较", "",
    "测试设备：NVIDIA GeForce RTX 4070 Laptop GPU，OpenGL 4.5，NVIDIA 581.15。", "",
    "每个规模的五个场景等权；规模权重 10000=25%、65536=25%、131072=50%。每组预热 50 步、记录 200 步，轮换运行顺序。完整步计时包含输入推力、高速场景重置、碰撞、承载和环境检测/事件捕获；探针读回位于计时区间外。", "",
    f"全部 {len(rows)} 组、{len(samples)} 个采样通过质量门槛：有效包裹数等于输入数、无暂停/缺失/非有限值，包裹穿透 <0.002 方块、地形穿透 <0.0001 方块；活动场景 >99% 包裹推进。", "",
    "按各场景各轮 GPU p95 的中位数归一化，计算加权几何平均，分数越低越好。", "",
    "| 模式 | GPU 分数 | CPU 提交 p95 分数 |", "|---|---:|---:|"]
for mode in order:
    report.append(f"| {mode} | {gpu[mode]:.6f} | {cpu[mode]:.6f} |")
report += ["", f"胜者：**{winner}**。{decision} 共完成 {rounds} 轮。生产源码仅保留该索引，其他实现与 shader 变体仅存于独立 benchmark 参考目录，不进入 mod JAR。", "",
           f"| 131072 场景 | {winner} GPU p95 中位数（ms） | CPU 提交 p95 中位数（ms） |", "|---|---:|---:|"]
for scene in SCENES:
    gpu_p95 = medians[('gpu_p95_ms', 131072, scene, winner)]
    cpu_p95 = medians[('cpu_submit_p95_ms', 131072, scene, winner)]
    report.append(f"| {scene} | {gpu_p95:.6f} | {cpu_p95:.6f} |")

report += ["", f"原始数据：summary-{rounds}.csv 与 samples-{rounds}.csv。五轮文件含前三轮，原始 summary-3.csv 与 samples-3.csv 另行保留。统计脚本：scripts/particles/compare_package_indexes.py。复现命令：", "",
           "```powershell", ".\\gradlew.bat -I scripts/particles/validation.init.gradle benchmarkPackageIndexes", "python scripts/particles/compare_package_indexes.py build/package-index-comparison",
           ".\\gradlew.bat -I scripts/particles/validation.init.gradle benchmarkPackageIndexes -PpackageIndexBenchmarkExtra # 仅当前三轮差距不足 3% 时运行", "python scripts/particles/compare_package_indexes.py build/package-index-comparison", "```", "",
           "benchmark 使用 scripts/particles/index-reference 中冻结的三模式实现和相同 shader；独立编译到 build/package-index-reference，覆盖测试进程的类路径，不参与生产 classes/resources/jar。新采样写入 build/package-index-comparison，不覆盖此目录的历史数据。追加两轮读取同一输出目录的 summary-3.csv / samples-3.csv。以 -PpackageIndexOutput=目录 指定新的实验目录；评分脚本接受该目录作为参数。裁剪后的入口只保留索引 fixture，环境程序与非索引公共资源使用当前生产实现；三模式使用同一物理与环境逻辑。", "",
           "数据代表该 GPU 与这些合成场景。尚不包含真实游戏的区块加载、多人网络延迟、机器库存或驱动之间的比较；不能作为这些路径已经通过实测的证据。"]
(DIRECTORY / "comparison.md").write_text("\n".join(report) + "\n", encoding="utf-8")
print(winner, gpu, "gap", gap, "initial_gap", initial_gap)
