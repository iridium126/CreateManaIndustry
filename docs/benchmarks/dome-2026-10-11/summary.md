# 天穹合围：验证与固定输入基准

Java: {'tests': 596, 'failures': 0, 'errors': 0, 'skipped': 0}
Dome GPU: 8,755,139 assertions (benchmark adds bookkeeping assertions).
Starfall GPU: 1,207,030; MODEL population: 1,015; particle GPU: 89,153.
MODEL/Iris: 4,133,216; package GPU: 14,675,334; package/Iris: 26,733.
Shared Hex: 5,287; Shared Hex/Iris: 5,290.
Nonzero SSBO ranges 26/27/28 and TBO unit 14 restore; native/Iris STARFALL/DOME poses agree.
Hardware: NVIDIA RTX 4070 Laptop, driver 581.15, OpenGL 4.5.
60 runs, 3 warmup rounds per scenario/mode, 120 measured samples each: 7,200 samples.

## 200 万混合池：三轮 P95 中位数（ms）

| 人数 | 更新星冠 | 更新天穹 | 天穹主列表 | 天穹阴影准备 | 天穹 CPU wall |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 768 | 1.0834 | 1.0885 | 0.6328 | 0.0236 | 0.0297 |
| 4096 | 1.0885 | 1.0916 | 0.6328 | 0.0246 | 0.0330 |
| 8192 | 1.0404 | 1.0701 | 0.6328 | 0.0256 | 0.0257 |
| 16384 | 1.0701 | 1.0701 | 0.6339 | 0.0287 | 0.0259 |
| 24576 | 1.1213 | 1.1274 | 0.6339 | 0.0317 | 0.0239 |

独立场景的 pool_live 等于参战人数；其余为 200 万混合池。P50/P95、分配、thread CPU、域上传和占用均在 CSV。
更新 = gridbuild + update；主列表 = reset + keygen + prepare（不含完整 radix）；阴影 = MODEL 独立列表准备。
固定输入微基准不包括完整游戏帧、实际模型片元/光影包过绘、CPU 选员/A*/出口/网络/状态边界。
单次 ThreadMXBean 的 Windows 精度不足，很多样本为零；不能解释成实际 CPU 时间为零。
域显存为复用的 3,158,912 B；未增加 Dome 固定 buffer。carrier 仍按活动人数增长。
静态帧没有攻击参数/几何上传；CSV 的上传项为各 bank 预热和攻击模式切换累计值。

## 复现

```powershell
.\gradlew.bat build -I scripts/particles/validation.init.gradle validateHexGpu validateModelPopulationGpu validateDomeGpu validateStarfallGpu validateModelShadowIrisGpu validatePackageIrisGpu validateHexSharedIrisGpu validatePackageGpu
.\gradlew.bat -I scripts/particles/validation.init.gradle validateDomeGpu -PdomeBenchmark
python scripts/particles/archive_dome_results.py
```

JAR: build\libs\createmanaindustry-0.2.6.jar
SHA256: d9cfae82f1472eaca1ef847c9a7a40ae29b349dada9f5fee17507d3e0ff247f8

实机美术、复杂地形、多人战斗和完整游戏帧性能由用户完成。未自动提交，已有星冠暂存保持。
