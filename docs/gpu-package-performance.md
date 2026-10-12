# GPU 包裹性能与诊断

状态：分项诊断、共享预算、稀疏物理及版本驱动发布已实现。历史微基准不代表当前版本整帧性能，实际多人、驱动与大规模游戏场景仍需测量。

## 线程与计时范围

物理入口为 `CMIParticleEngine → PackageCollisionRuntime → PackageWorldRuntime.prepare → solver/publish`。世界读取在客户端线程进行，几何打包、力源 BVH 和编码 worker 使用不可变数字快照；compute 和 draw 的 GL 提交属于渲染线程。

`glMemoryBarrier` 表达 GPU 依赖，不等于 `glFinish`。零超时 fence 轮询避免等待未完成任务，已完成数据的下载、解析与驱动提交仍有 CPU 成本。不能由单步 solver 的 CPU 计时推断完整采集、上传、网络及绘制成本。

## 当前优化约束

- `PackageFrameBudget` 让两次渲染上传之间的采集与下次上传共享几何额度；多次 catch-up tick 不重复获得完整额度。这是软预算，不覆盖所有 solver、readback 与 publish 工作。
- 在明确拥有的物理步中缓存 buffer/uniform 绑定，结束时清除缓存；保留真实跨 dispatch 依赖，不跨外部 force/query/draw 边界猜测 GL 状态。
- 两个输出 bank 分别记录自由、锁链和观察者 domain 版本，仅复制落后于源版本的域。切回旧 bank 也必须追上版本。
- 预取按姿态、世界、力与移动输入版本变化调度，同时保留缺失覆盖及上传完成后的重试。
- 稳定活动索引减少空洞工作，整场景睡眠与 moving 候选减少静止场景开销。新输入、支撑移除、力、检查点及生命周期变化必须唤醒；环境计时仍推进。
- 碰撞临时表按当前索引范围缩放并保留滞回；绘制准备复用可见性与不变轮掩码，只保存实际修改的 GL 状态。

## 定位慢帧

游戏中执行 `/cmi particle profile on`，再执行 `/cmi particle stats`。先看 `CAPTURE`、`UPLOAD`、`PREPARE` 和 `SOLVER` 的 P95/P99，再结合 steps、live/indices、moving、downloaded 和 waiting 判断采集、补步、读回或提交成本。

`PackageCpuDiagnostics` 记录 wall、thread CPU 与分配。phase 为包含子项的累计值，不能相加当总时间；wall 包含驱动及调度等待，thread CPU 不支持时为 unavailable。关闭 profile 时不启用额外诊断采样。

20 tick/s 和 200 tick/s 的每帧补步上限分别为 4 和 40；上限不增加 GPU 服务能力。初始化、shader 编译、容量变化及冷启动需要独立测量。

## 可重跑实验

```powershell
python scripts/benchmarks/run.py validatePackageGpu -PpackagePriorityBenchmark --offline
python scripts/benchmarks/run.py validatePackageGpu -PpackageSparsePhysicsBenchmark --offline
python scripts/benchmarks/run.py validatePackageGpu -PpackageCullBenchmark --offline
python scripts/benchmarks/run.py benchmarkPackagePause --offline
```

历史结论包括 [稀疏物理](benchmarks/package-sparse-physics-optimization-2026-10-08.md)、[发布与活动集](benchmarks/package-priority-optimization-2026-10-09.md)、[绘制](benchmarks/package-render-optimization-2026-10-04.md) 和 [空间索引选择](benchmarks/package-index-2026-10-02/comparison.md)。生产保留 `linked`；三模式比较源码保留在独立 index-reference 工具中，不进入 mod JAR。

原始数据和完整日志只放在 `build/benchmarks/`。结果管理、版本元数据和质量门槛见 [性能结果规则](benchmarks/README.md)。
