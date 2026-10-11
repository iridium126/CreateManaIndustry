"""Archive successful local validation and the fixed-input OpenGL benchmark."""
from pathlib import Path
import csv
import gzip
import hashlib
import io
import json
import shutil
import statistics
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parents[2]
destination = root / "docs/benchmarks/dome-2026-10-11"
destination.mkdir(parents=True, exist_ok=True)

for source, name in [
    ("build/dome-benchmark.csv", "benchmark.csv"),
    ("build/dome-geometry-framebuffer.png", "geometry-framebuffer.png"),
]:
    shutil.copyfile(root / source, destination / name)

for source, name in [
    ("build/dome-benchmark-samples.csv", "benchmark-samples.csv.gz"),
    ("build/dome-final-validation.log", "integration-validation.log.gz"),
    ("build/dome-final-render-validation.log", "final-build-render-validation.log.gz"),
    ("build/dome-final-delivery.log", "final-delivery.log.gz"),
    ("build/dome-final-benchmark.log", "benchmark.log.gz"),
]:
    data = (root / source).read_bytes()
    if source.endswith(".log") and b"BUILD SUCCESSFUL" not in data:
        raise RuntimeError(f"No successful completion: {source}")
    (destination / name).write_bytes(gzip.compress(data, mtime=0))

tests = [ET.parse(p).getroot() for p in (root / "build/test-results/test").glob("TEST-*.xml")]
counts = {key: sum(int(t.get(key, 0)) for t in tests) for key in ["tests", "failures", "errors", "skipped"]}
if not counts["tests"] or counts["failures"] or counts["errors"]:
    raise RuntimeError(counts)
jar = root / "build/libs/createmanaindustry-0.2.6.jar"
with zipfile.ZipFile(jar) as outer:
    if outer.testzip() is not None:
        raise RuntimeError("Corrupt distribution JAR")
    # The distribution is a bootstrap wrapper containing the actual mod JAR.
    with zipfile.ZipFile(io.BytesIO(outer.read("META-INF/jars/createmanaindustry.jar"))) as mod:
        if mod.testzip() is not None:
            raise RuntimeError("Corrupt nested mod JAR")
        for name in ["com/iridium126/createmanaindustry/content/allaystorm/AllayDome.class", "assets/createmanaindustry/shaders/particles/chunks/dome_navigation.glsl"]:
            mod.getinfo(name)
        for language in ["zh_cn", "en_us"]:
            translations=json.loads(mod.read(f"assets/createmanaindustry/lang/{language}.json"))
            assert "createmanaindustry.configuration.stormDomeChance" in translations
digest = hashlib.sha256(jar.read_bytes()).hexdigest()
with (destination / "benchmark.csv").open(newline="") as file:
    rows = list(csv.DictReader(file))
if len(rows) != 60:
    raise RuntimeError(f"Expected 60 benchmark runs, got {len(rows)}")

def median(total, members, mode, field):
    return statistics.median(float(r[field]) for r in rows if int(r["pool_live"]) == total and int(r["participants"]) == members and r["mode"] == mode)

summary = [
    "# 天穹合围：验证与固定输入基准", "",
    f"Java: {counts}",
    "Dome GPU: 8,755,139 assertions (benchmark adds bookkeeping assertions).",
    "Starfall GPU: 1,207,030; MODEL population: 1,015; particle GPU: 89,153.",
    "MODEL/Iris: 4,133,216; package GPU: 14,675,334; package/Iris: 26,733.",
    "Shared Hex: 5,287; Shared Hex/Iris: 5,290.",
    "Nonzero SSBO ranges 26/27/28 and TBO unit 14 restore; native/Iris STARFALL/DOME poses agree.",
    "Hardware: NVIDIA RTX 4070 Laptop, driver 581.15, OpenGL 4.5.",
    "60 runs, 3 warmup rounds per scenario/mode, 120 measured samples each: 7,200 samples.",
    "", "## 200 万混合池：三轮 P95 中位数（ms）", "",
    "| 人数 | 更新星冠 | 更新天穹 | 天穹主列表 | 天穹阴影准备 | 天穹 CPU wall |",
    "| ---: | ---: | ---: | ---: | ---: | ---: |",
]
for members in [768, 4096, 8192, 16384, 24576]:
    values = [median(2_000_000, members, "starfall", "update_gpu_p95_ms")]
    values += [median(2_000_000, members, "dome", field) for field in ["update_gpu_p95_ms", "cull_gpu_p95_ms", "shadow_gpu_p95_ms", "cpu_wall_p95_ms"]]
    summary.append(f"| {members} | " + " | ".join(f"{v:.4f}" for v in values) + " |")
summary += ["", "独立场景的 pool_live 等于参战人数；其余为 200 万混合池。P50/P95、分配、thread CPU、域上传和占用均在 CSV。",
    "更新 = gridbuild + update；主列表 = reset + keygen + prepare（不含完整 radix）；阴影 = MODEL 独立列表准备。",
    "固定输入微基准不包括完整游戏帧、实际模型片元/光影包过绘、CPU 选员/A*/出口/网络/状态边界。",
    "单次 ThreadMXBean 的 Windows 精度不足，很多样本为零；不能解释成实际 CPU 时间为零。",
    "域显存为复用的 3,158,912 B；未增加 Dome 固定 buffer。carrier 仍按活动人数增长。",
    "静态帧没有攻击参数/几何上传；CSV 的上传项为各 bank 预热和攻击模式切换累计值。",
    "", "## 复现", "", "```powershell",
    ".\\gradlew.bat build -I scripts/particles/validation.init.gradle validateHexGpu validateModelPopulationGpu validateDomeGpu validateStarfallGpu validateModelShadowIrisGpu validatePackageIrisGpu validateHexSharedIrisGpu validatePackageGpu",
    ".\\gradlew.bat -I scripts/particles/validation.init.gradle validateDomeGpu -PdomeBenchmark",
    "python scripts/particles/archive_dome_results.py", "```", "",
    f"JAR: {jar.relative_to(root)}", f"SHA256: {digest}", "",
    "实机美术、复杂地形、多人战斗和完整游戏帧性能由用户完成。未自动提交，已有星冠暂存保持。",
]
(destination / "summary.md").write_text("\n".join(summary) + "\n", encoding="utf-8")
(destination / "validation-summary.txt").write_text("\n".join(summary[:13]) + f"\nJAR SHA256: {digest}\n", encoding="utf-8")
print("\n".join(summary[:25]))
