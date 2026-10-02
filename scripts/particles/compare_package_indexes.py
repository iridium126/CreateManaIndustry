"""Score quality-qualified, rotated GPU index runs using the agreed weights."""
import csv, math, statistics
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
DIRECTORY = ROOT / "docs/benchmarks/package-index-2026-10-02"
ROUNDS = 5 if (DIRECTORY / "summary-5.csv").exists() else 3
rows = list(csv.DictReader((DIRECTORY / f"summary-{ROUNDS}.csv").open()))
samples = list(csv.DictReader((DIRECTORY / f"samples-{ROUNDS}.csv").open()))
if ROUNDS == 5:
    first_rows = list(csv.DictReader((DIRECTORY / "summary-3.csv").open()))
    first_samples = list(csv.DictReader((DIRECTORY / "samples-3.csv").open()))
    assert rows[:len(first_rows)] == first_rows and samples[:len(first_samples)] == first_samples, "Five-round data does not extend the current first three rounds"
MODES = ("linked", "exact_ranges", "bounded_linked")
SCENES = ("aligned_stack", "staggered_stack", "continuous_force", "fast_vs_stationary", "moving_platform")
WEIGHTS = {10000: .25, 65536: .25, 131072: .5}
runs = sorted({int(r["run"]) for r in rows})
assert runs in ([1, 2, 3], [1, 2, 3, 4, 5]), runs
assert len(rows) == 3 * 3 * 5 * len(runs)
for r in rows:
    assert int(r["quality_pass"]) == 1 and int(r["nonfinite"]) == 0 and int(r["min_effective"]) == int(r["count"]), r
    raw = [s for s in samples if all(s[k] == r[k] for k in ("count", "scenario", "index", "run"))]
    assert len(raw) == 200 and {int(s["sample"]) for s in raw} == set(range(200)), r
    assert all(int(s["effective"]) == int(r["count"]) and int(s["nonfinite"]) == 0 and float(s["overlap_max"]) < .002 and float(s["terrain_penetration_max"]) < 1e-4 for s in raw), r
medians = {}
def score(field):
    result = {m: 0.0 for m in MODES}
    for n, weight in WEIGHTS.items():
        for scene in SCENES:
            values = {m: statistics.median(float(r[field]) for r in rows if int(r["count"]) == n and r["scenario"] == scene and r["index"] == m) for m in MODES}
            minimum = min(values.values())
            for m in MODES:
                result[m] += weight / 5 * math.log(values[m] / minimum)
                medians[(field, n, scene, m)] = values[m]
    return {m: math.exp(x) for m, x in result.items()}
gpu = score("gpu_p95_ms")
cpu = score("cpu_submit_p95_ms")
order = sorted(MODES, key=gpu.get)
gap = gpu[order[1]] / gpu[order[0]] - 1
if gap < .03 and len(runs) == 3:
    raise SystemExit("Top two are within 3%; run benchmarkPackageIndexes -PpackageIndexBenchmarkExtra before selecting.")
winner = order[0]
if gap < .03:
    winner = min(order[:2], key=lambda m: (cpu[m], sum(int(r["index_extra_bytes"]) for r in rows if r["index"] == m)))
decision = (f"前三轮差距不足 3%，追加两轮后 GPU 前两名仍相差 {gap * 100:.4f}%；按相同权重汇总的 CPU 提交 p95，{winner} 更低，因此胜出。"
            if gap < .03 else f"GPU 第一名相对第二名的优势为 {gap * 100:.4f}%，达到 3% 门槛。")
report = ["# GPU 包裹空间索引比较", "", "测试设备：NVIDIA GeForce RTX 4070 Laptop GPU，OpenGL 4.5，NVIDIA 581.15。", "", "每个规模的五个场景等权；规模权重 10000=25%、65536=25%、131072=50%。每组预热 50 步、记录 200 步，轮换运行顺序。完整步计时包含输入推力、高速场景重置、碰撞、承载和环境检测/事件捕获；探针读回位于计时区间外。", "", f"全部 {len(rows)} 组、{len(samples)} 个采样通过质量门槛：有效包裹数等于输入数、无暂停/缺失/非有限值，包裹穿透 <0.002 方块、地形穿透 <0.0001 方块；活动场景 >99% 包裹推进。", "", "按各场景各轮 GPU p95 的中位数归一化，计算加权几何平均，分数越低越好。", "", "| 模式 | GPU 分数 | CPU 提交 p95 分数 |", "|---|---:|---:|"]
for m in order:
    report.append(f"| {m} | {gpu[m]:.6f} | {cpu[m]:.6f} |")
report += ["", f"胜者：**{winner}**。{decision} 共完成 {len(runs)} 轮。生产源码仅保留该索引，其他实现与 shader 变体仅存于独立 benchmark 参考目录，不进入 mod JAR。", "", f"| 131072 场景 | {winner} GPU p95 中位数（ms） | CPU 提交 p95 中位数（ms） |", "|---|---:|---:|"]
for scene in SCENES:
    report.append(f"| {scene} | {medians[('gpu_p95_ms',131072,scene,winner)]:.6f} | {medians[('cpu_submit_p95_ms',131072,scene,winner)]:.6f} |")
report += ["", f"原始数据：summary-{len(runs)}.csv 与 samples-{len(runs)}.csv。前三轮还单独保存在 summary-3.csv 与 samples-3.csv。统计脚本：scripts/particles/compare_package_indexes.py。复现命令：", "", "```powershell", ".\\gradlew.bat -I scripts/particles/validation.init.gradle benchmarkPackageIndexes", ".\\gradlew.bat -I scripts/particles/validation.init.gradle benchmarkPackageIndexes -PpackageIndexBenchmarkExtra", "python scripts/particles/compare_package_indexes.py", "```", "", "benchmark 使用 scripts/particles/index-reference 中冻结的三模式实现和相同 shader；独立编译到 build/package-index-reference，覆盖测试进程的类路径，不参与生产 classes/resources/jar。此任务会重新写入 CSV，应先保存当前实验数据。重新采样前三轮时应先移走旧的五轮数据，再决定是否追加两轮，避免统计脚本混用旧实验。", "", "数据代表该 GPU 与这些合成场景。尚不包含真实游戏的区块加载、多人网络延迟、机器库存或驱动之间的比较；不能作为这些路径已经通过实测的证据。"]
(DIRECTORY / "comparison.md").write_text("\n".join(report) + "\n", encoding="utf-8")
print(winner, gpu, "gap", gap)
