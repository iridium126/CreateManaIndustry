# GPU 包裹空间索引比较

测试设备：NVIDIA GeForce RTX 4070 Laptop GPU，OpenGL 4.5，NVIDIA 581.15。

每个规模的五个场景等权；规模权重 10000=25%、65536=25%、131072=50%。每组预热 50 步、记录 200 步，轮换运行顺序。完整步计时包含输入推力、高速场景重置、碰撞、承载和环境检测/事件捕获；探针读回位于计时区间外。

全部 225 组、45000 个采样通过质量门槛：有效包裹数等于输入数、无暂停/缺失/非有限值，包裹穿透 <0.002 方块、地形穿透 <0.0001 方块；活动场景 >99% 包裹推进。

按各场景各轮 GPU p95 的中位数归一化，计算加权几何平均，分数越低越好。

| 模式 | GPU 分数 | CPU 提交 p95 分数 |
|---|---:|---:|
| linked | 1.005505 | 1.004876 |
| bounded_linked | 1.015969 | 1.035642 |
| exact_ranges | 1.315400 | 1.817284 |

胜者：**linked**。前三轮差距不足 3%，追加两轮后 GPU 前两名仍相差 1.0407%；按相同权重汇总的 CPU 提交 p95，linked 更低，因此胜出。 共完成 5 轮。生产源码仅保留该索引，其他实现与 shader 变体仅存于独立 benchmark 参考目录，不进入 mod JAR。

| 131072 场景 | linked GPU p95 中位数（ms） | CPU 提交 p95 中位数（ms） |
|---|---:|---:|
| aligned_stack | 10.184704 | 0.198500 |
| staggered_stack | 10.312704 | 0.192100 |
| continuous_force | 10.459136 | 0.188200 |
| fast_vs_stationary | 9.807872 | 0.187600 |
| moving_platform | 9.097216 | 0.204000 |

原始数据：summary-5.csv 与 samples-5.csv。前三轮还单独保存在 summary-3.csv 与 samples-3.csv。统计脚本：scripts/particles/compare_package_indexes.py。复现命令：

```powershell
.\gradlew.bat -I scripts/particles/validation.init.gradle benchmarkPackageIndexes
.\gradlew.bat -I scripts/particles/validation.init.gradle benchmarkPackageIndexes -PpackageIndexBenchmarkExtra
python scripts/particles/compare_package_indexes.py
```

benchmark 使用 scripts/particles/index-reference 中冻结的三模式实现和相同 shader；独立编译到 build/package-index-reference，覆盖测试进程的类路径，不参与生产 classes/resources/jar。此任务会重新写入 CSV，应先保存当前实验数据。重新采样前三轮时应先移走旧的五轮数据，再决定是否追加两轮，避免统计脚本混用旧实验。

数据代表该 GPU 与这些合成场景。尚不包含真实游戏的区块加载、多人网络延迟、机器库存或驱动之间的比较；不能作为这些路径已经通过实测的证据。
