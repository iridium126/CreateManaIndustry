# 双螺旋贯击：验证与固定输入基准

> 历史测量结论；原始运行产物不随仓库保存。重新测量与结果管理见 [性能结果规则](../README.md)。

Java: {'tests': 605, 'failures': 0, 'errors': 0, 'skipped': 0}
MODEL population GPU: 1015 assertions passed
Helix GPU: 6879381 assertions passed
Starfall GPU: 1207030 assertions passed
Shared Hex GPU: 5287 assertions passed
Shared Hex GPU: 5290 assertions passed
Dome GPU: 8755139 assertions passed
MODEL shadow GPU: 4133222 assertions passed
Package shaderpack GPU: 26733 assertions passed
PASS: 89153 assertions
Package GPU: 14675334 assertions passed
Helix GPU: 6934061 assertions passed
MODEL shadow GPU: 4133222 assertions passed

CPU/GPU 原生六臂：131072 身份 × 3 seed 对照通过。
当前帧 MODEL/Iris 三种攻击姿态、非零 SSBO 26–28 range/TBO 14、池压缩身份与扫掠安全通过。
GPU: RTX 4070 Laptop / NVIDIA 581.15 / OpenGL 4.5.
48 runs: 4 participant counts × 2 pool sizes × 2 attack modes × 3 rounds. 5760 measured samples.
每轮至少40次且1秒预热，再120样本。两种模式均为当前后端，同人数星冠/HELIX。

## 200 万混合池：三轮 P95 中位数（ms）

| 人数 | 星冠更新 | HELIX更新 | HELIX接触 | HELIX主列表 | HELIX阴影准备 | HELIX CPU wall |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 512 | 1.0465 | 1.0476 | 0.0102 | 0.6318 | 0.0154 | 0.0332 |
| 2048 | 1.0977 | 1.1121 | 0.0092 | 0.6287 | 0.0266 | 0.0327 |
| 8192 | 1.0455 | 1.1028 | 0.0102 | 0.6328 | 0.0236 | 0.0378 |
| 16384 | 1.0793 | 1.0803 | 0.0102 | 0.6328 | 0.0236 | 0.0336 |

P50/P95、分配、thread CPU、累计上传、域显存与逐次样本保存在 CSV/gzip。
更新包含gridbuild/update；列表包含reset/keygen/prepare，不含完整radix；接触和阴影准备单独计时。
固定输入离屏微基准，未串为完整游戏帧；不含模型片元/光影过绘、完整状态边界、服务器选员/A*/通道、网络和碰撞后台构建。
ThreadMXBean 单次 Windows 分辨率不足，零样本不能解释成无实际 CPU 时间。
双 bank 共享域仍为3,158,912 B，无新增HELIX固定buffer。窗口就绪改变仅上传4 B/bank；静态贯击不上传几何。
本次包含 precise hash 修复；既有 GPU 随机计算现在与 CPU 身份臂推导一致。

## 复现

```powershell
.\gradlew.bat build -I scripts/particles/validation.init.gradle validateHexGpu validateModelPopulationGpu validateHelixGpu validateDomeGpu validateStarfallGpu validateModelShadowIrisGpu validatePackageIrisGpu validateHexSharedIrisGpu validatePackageGpu
.\gradlew.bat -I scripts/particles/validation.init.gradle validateHelixGpu -PhelixBenchmark
python scripts/benchmarks/run.py validateHelixGpu -PhelixBenchmark
```

JAR: build\libs\createmanaindustry-0.2.6.jar
SHA256: 0b845f541af8ab4a76578339615bf7f5d89b344a469af401293a9f017627cc01

核心协议4，两端同时升级。未自动暂存或提交。实机视觉/复杂地形/多人战斗/完整帧性能由用户完成。
