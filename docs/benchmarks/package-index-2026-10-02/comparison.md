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

胜者：**linked**。前三轮 GPU 前两名差距为 0.9022%，因此追加两轮；五轮后差距为 1.0407%。按约定以 CPU 提交 p95 决胜，linked 的加权分数更低。 共完成 5 轮。生产源码仅保留该索引，其他实现与 shader 变体仅存于独立 benchmark 参考目录，不进入 mod JAR。

| 131072 场景 | linked GPU p95 中位数（ms） | CPU 提交 p95 中位数（ms） |
|---|---:|---:|
| aligned_stack | 10.184704 | 0.198500 |
| staggered_stack | 10.312704 | 0.192100 |
| continuous_force | 10.459136 | 0.188200 |
| fast_vs_stationary | 9.807872 | 0.187600 |
| moving_platform | 9.097216 | 0.204000 |

原始数据：summary-5.csv 与 samples-5.csv。五轮文件含前三轮，原始 summary-3.csv 与 samples-3.csv 另行保留。统计脚本：scripts/particles/compare_package_indexes.py。复现命令：

```powershell
.\gradlew.bat -I scripts/particles/validation.init.gradle benchmarkPackageIndexes
python scripts/particles/compare_package_indexes.py build/package-index-comparison
.\gradlew.bat -I scripts/particles/validation.init.gradle benchmarkPackageIndexes -PpackageIndexBenchmarkExtra # 仅当前三轮差距不足 3% 时运行
python scripts/particles/compare_package_indexes.py build/package-index-comparison
```

benchmark 使用 scripts/particles/index-reference 中冻结的三模式实现和相同 shader；独立编译到 build/package-index-reference，覆盖测试进程的类路径，不参与生产 classes/resources/jar。新采样写入 build/package-index-comparison，不覆盖此目录的历史数据。追加两轮读取同一输出目录的 summary-3.csv / samples-3.csv。以 -PpackageIndexOutput=目录 指定新的实验目录；评分脚本接受该目录作为参数。裁剪后的入口只保留索引 fixture，环境程序与非索引公共资源使用当前生产实现；三模式使用同一物理与环境逻辑。

数据代表该 GPU 与这些合成场景。尚不包含真实游戏的区块加载、多人网络延迟、机器库存或驱动之间的比较；不能作为这些路径已经通过实测的证据。
