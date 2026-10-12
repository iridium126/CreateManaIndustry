"""Archive successful HELIX validation, fixed-input benchmark and distribution integrity."""
from pathlib import Path
import csv, gzip, hashlib, io, json, re, shutil, statistics, zipfile
import xml.etree.ElementTree as ET

root=Path(__file__).resolve().parents[2]
out=root/"docs/benchmarks/helix-2026-10-11"
out.mkdir(parents=True,exist_ok=True)
for source,name in [("build/helix-benchmark.csv","benchmark.csv"),("build/helix-geometry-framebuffer.png","geometry-framebuffer.png")]:
    shutil.copyfile(root/source,out/name)
for source,name in [("build/helix-benchmark-samples.csv","benchmark-samples.csv.gz"),("build/helix-integration-validation.log","integration-validation.log.gz"),("build/helix-final-delivery.log","final-delivery.log.gz"),("build/helix-benchmark.log","benchmark.log.gz")]:
    data=(root/source).read_bytes()
    if source.endswith(".log") and b"BUILD SUCCESSFUL" not in data: raise RuntimeError(source)
    (out/name).write_bytes(gzip.compress(data,mtime=0))
tests=[ET.parse(p).getroot() for p in (root/"build/test-results/test").glob("TEST-*.xml")]
totals={k:sum(int(t.get(k,0)) for t in tests) for k in ["tests","failures","errors","skipped"]}
if not totals["tests"] or totals["failures"] or totals["errors"]:raise RuntimeError(totals)
jar=root/"build/libs/createmanaindustry-0.2.6.jar"
with zipfile.ZipFile(jar) as outer:
    assert outer.testzip() is None
    with zipfile.ZipFile(io.BytesIO(outer.read("META-INF/jars/createmanaindustry.jar"))) as mod:
        assert mod.testzip() is None
        for name in ["com/iridium126/createmanaindustry/content/allaystorm/AllayHelix.class","com/iridium126/createmanaindustry/content/allaystorm/StormAttack.class","assets/createmanaindustry/shaders/particles/chunks/helix_navigation.glsl","assets/createmanaindustry/shaders/particles/chunks/helix_rules.glsl"]:mod.getinfo(name)
        for locale in ["zh_cn","en_us"]:assert "createmanaindustry.configuration.stormHelixChance" in json.loads(mod.read(f"assets/createmanaindustry/lang/{locale}.json"))
digest=hashlib.sha256(jar.read_bytes()).hexdigest()
rows=list(csv.DictReader((out/"benchmark.csv").open(newline="")))
assert len(rows)==48
def median(n,mode,column):return statistics.median(float(r[column]) for r in rows if r["pool_live"]=="2000000" and int(r["participants"])==n and r["mode"]==mode)
text=["# 双螺旋贯击：验证与固定输入基准","",f"Java: {totals}"]
for logfile in ["build/helix-integration-validation.log","build/helix-final-delivery.log"]:
    log=(root/logfile).read_text(encoding="utf-8",errors="replace")
    text.extend(dict.fromkeys(re.findall(r"[^\n]*(?:assertions passed|PASS: \d+ assertions)[^\n]*",log)))
text += ["","CPU/GPU 原生六臂：131072 身份 × 3 seed 对照通过。",
"当前帧 MODEL/Iris 三种攻击姿态、非零 SSBO 26–28 range/TBO 14、池压缩身份与扫掠安全通过。",
"GPU: RTX 4070 Laptop / NVIDIA 581.15 / OpenGL 4.5.",
"48 runs: 4 participant counts × 2 pool sizes × 2 attack modes × 3 rounds. 5760 measured samples.",
"每轮至少40次且1秒预热，再120样本。两种模式均为当前后端，同人数星冠/HELIX。","",
"## 200 万混合池：三轮 P95 中位数（ms）","",
"| 人数 | 星冠更新 | HELIX更新 | HELIX接触 | HELIX主列表 | HELIX阴影准备 | HELIX CPU wall |",
"| ---: | ---: | ---: | ---: | ---: | ---: | ---: |"]
for n in [512,2048,8192,16384]:
    values=[median(n,"starfall","update_gpu_p95_ms")]+[median(n,"helix",k) for k in ["update_gpu_p95_ms","contact_gpu_p95_ms","cull_gpu_p95_ms","shadow_gpu_p95_ms","cpu_wall_p95_ms"]]
    text.append(f"| {n} | "+" | ".join(f"{v:.4f}" for v in values)+" |")
text += ["","P50/P95、分配、thread CPU、累计上传、域显存与逐次样本保存在 CSV/gzip。",
"更新包含gridbuild/update；列表包含reset/keygen/prepare，不含完整radix；接触和阴影准备单独计时。",
"固定输入离屏微基准，未串为完整游戏帧；不含模型片元/光影过绘、完整状态边界、服务器选员/A*/通道、网络和碰撞后台构建。",
"ThreadMXBean 单次 Windows 分辨率不足，零样本不能解释成无实际 CPU 时间。",
"双 bank 共享域仍为3,158,912 B，无新增HELIX固定buffer。窗口就绪改变仅上传4 B/bank；静态贯击不上传几何。",
"本次包含 precise hash 修复；既有 GPU 随机计算现在与 CPU 身份臂推导一致。","",
"## 复现","","```powershell",
".\\gradlew.bat build -I scripts/particles/validation.init.gradle validateHexGpu validateModelPopulationGpu validateHelixGpu validateDomeGpu validateStarfallGpu validateModelShadowIrisGpu validatePackageIrisGpu validateHexSharedIrisGpu validatePackageGpu",
".\\gradlew.bat -I scripts/particles/validation.init.gradle validateHelixGpu -PhelixBenchmark",
"python scripts/particles/archive_helix_results.py","```","",
f"JAR: {jar.relative_to(root)}",f"SHA256: {digest}","",
"核心协议4，两端同时升级。未自动暂存或提交。实机视觉/复杂地形/多人战斗/完整帧性能由用户完成。"]
(out/"summary.md").write_text("\n".join(text)+"\n",encoding="utf-8")
(out/"validation-summary.txt").write_text("\n".join(text[:20])+f"\nJAR SHA256: {digest}\n",encoding="utf-8")
print("\n".join(text[:30]))
