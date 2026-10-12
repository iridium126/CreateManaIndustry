# GPU 自由包裹低占用物理优化（2026-10-08）

> 历史测量结论；原始运行产物不随仓库保存。重新测量与结果管理见 [性能结果规则](README.md)。

本轮优化 `PackagePhysicsGpu` 的碰撞临时哈希表，改善大量预留槽位、少量实际包裹时的固定 GPU 成本。使用真实 NVIDIA GeForce RTX 4070 Laptop GPU、OpenGL 4.5、驱动 581.15 的隐藏 GLFW context 测量。自由包裹池固定预留 131,072 个槽位。

## 定位与实现

原实现根据预留容量分配 `heads` 和 `fastCells`，每次重建接触网格都清空整个 `heads`，每步 CCD 都清空整个 `fastCells`。基准中的四轮 Jacobi 求解每步重建六次网格，两个表的清空写入量合计约 7 MiB；少量包裹同样承担这部分开销。

现在只调整这两个可重建的 scratch buffer。初始桶数取预留表上限与 65,536 的较小值；随后按稳定 body 索引上界 `count`、每 body 16 桶的余量和二次幂向上取整扩容，并受原预留表上限约束。只有数量下降到当前桶数的 1/64 以下时才缩容，避免在扩容阈值附近反复分配。包含退役空洞的 `count` 保持稳定索引，不需要读回 GPU 姿态。

所有相关程序分别缓存并更新 `uTableMask`，包括 CCD 的写表与读表程序。新表在使用前完整清空并重建；body、chain、history buffer 不重分配，历史和租约不移动。GPU 分配大小仍做实际查询验证。

直接按数量使用较紧的小表会增加哈希别名遍历，实测中间数量档退化，因此最终保留桶余量和最小容量。65,536 桶只是大容量池的下限；较小池继续使用原表大小。

## 前后测量

每个数量档三轮，每轮至少预热 30 步且持续 1 秒，再测 120 步。每步 `dt=0.005` 秒；包裹按三格间距排列，无接触且持续受重力运动。GL 时间查询计整个自由物理步；CPU 提交单独记录。质量门禁检查每个包裹未暂停、姿态有限且没有横向意外位移。

表中为三轮 P95 的中位数，单位毫秒。

| 实际数量 | 修改前 GPU P95 | 修改后 GPU P95 | 变化 |
| ---: | ---: | ---: | ---: |
| 65 | 0.141312 | 0.116736 | −17.39% |
| 1,024 | 0.147456 | 0.126976 | −13.89% |
| 10,000 | 0.190464 | 0.191488 | +0.54% |
| 65,536 | 0.879616 | 0.876544 | −0.35% |
| 131,072 | 2.332672 | 2.343936 | +0.48% |

后三档使用与原实现相同的表大小，结果基本持平。CPU 提交 P95 约 0.001–0.004 ms，未据此声称有稳定改善。低占用时两个 scratch 表从 4.5 MiB 减至 2.25 MiB，每个求解器减少 2.25 MiB；基准步中的清空写入量从约 7 MiB 减至约 3.5 MiB。

汇总 CSV：修改前（本地运行结果）、修改后（本地运行结果）。原始逐步样本：修改前（本地运行结果）、修改后（本地运行结果）。

## 验证与复现

新增 GPU 生命周期回归在同一个求解器中反复增长、收缩和再次增长，检查 CCD 双向碰撞、速度响应、无关包裹姿态、实际存储回收、跨阈值 append、尾部 retire/recycle 和空索引重建。独立物理 GPU 回归通过 396,899 项断言；完整 GPU 回归通过 14,674,073 项断言；Java 测试、构建和 Sable 缺席验证通过。

```powershell
.\gradlew.bat validatePackageGpu -PpackageSparsePhysicsBenchmark --offline --no-configuration-cache -I scripts/particles/validation.init.gradle --console=plain
.\gradlew.bat validatePackageGpu -PpackagePhysicsOnly --offline --no-configuration-cache -I scripts/particles/validation.init.gradle --console=plain
.\gradlew.bat validatePackageGpu --offline --no-configuration-cache -I scripts/particles/validation.init.gradle --console=plain
.\gradlew.bat test build validatePackageSableAbsent --offline --no-configuration-cache -I scripts/particles/validation.init.gradle --console=plain
```

基准生成 `build/package-sparse-physics-benchmark.csv` 和 `build/package-sparse-physics-benchmark-samples.csv`。扩缩容的首步分配成本不包含在预热后的样本中；生命周期回归覆盖其正确性。

这些数字是分散自由包裹的独立物理步耗时，不代表整帧 FPS、服务器 tick 或真实密集堆叠收益。密集场景仍需处理每轮网格重建、候选接触遍历、支撑投影及移动结构碰撞；世界几何采集、同步和最终绘制也各有独立成本，本轮没有量化它们。游戏内大量创建/销毁导致的扩缩容帧耗时、多人实际物流和其他显卡驱动仍需实机测量。