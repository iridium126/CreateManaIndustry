# GPU 自由包裹：渲染线程 CPU 开销与进一步优化空间（2026-10-08）

结论：现有 GPU 物理会占用渲染线程 CPU 时间。位置积分、CCD、接触求解和支撑传播在 GPU shader 中执行；调度、OpenGL 命令提交、世界视图构造、上传、异步结果下载与解析仍有 CPU 工作。正常帧没有显式等待全部物理完成的 `glFinish`，但不能因此认定渲染线程零开销或永不受 GPU 队列影响。

2026-10-09：六项已推进实施，见[实现、验证和测量结果](benchmarks/package-priority-optimization-2026-10-09.md)。下面保留优化前的分析语境。

本报告依据当时源码和已有基准。没有新增游戏内 CPU 分项实测；优化优先级是由代码行为推断，尚未量化收益。

## 调用链与线程

`CMIParticleEngine.beginFrame → runCompute → PackageCollisionRuntime.pumpGpu → PackageWorldRuntime.prepare → applyFreeForces / stepFreeMoving → publish`。

- `PackageWorldRuntime.prepare` 开头调用 `RenderSystem.assertOnRenderThread()`，物理步就在它内部提交。
- 客户端 tick 回调读取方块、实体、风扇和移动结构。`PackageForceClient` 的 BVH 构建、碰撞几何不可变快照的打包与 delta 编码已有后台 worker。
- 主线程世界读取不能直接交给普通 worker；worker 的输入应继续是不可变数字快照。
- 同一 OpenGL context 中提交 compute 和 draw，不是已有独立的异步计算队列。把 Java 调用简单搬到 worker 并不能消除 GPU 依赖，而且会破坏当前 context/资源所有权。

关键位置：`CMIParticleEngine.java:1143`、`PackageWorldRuntime.java:150` / `:235` / `:245` / `:264`、`PackageForceClient.java:39` / `:101`。

## 正常帧有哪些 CPU 成本

| 环节 | 当前工作 | 成本性质 |
| --- | --- | --- |
| 世界采集 | 实体/移动结构发现、方块形状读取、光照采集、历史版本引用维护 | 主线程遍历、对象分配；几何打包部分已后台化 |
| 世界上传 | atlas 预算调度、mapped buffer 写入、bank/fence 管理 | 渲染线程 CPU 拷贝和 GL 提交 |
| 每帧准备 | 队列消费、ACK、观察者、交互、预取结果解析与新预取 | 即使该帧没有到期物理步，也可能执行 |
| 每个物理步 | 建历史视图、force/moving bank、uniform/buffer 绑定、dispatch/barrier | Java 与驱动提交；GPU 求解本身另计 |
| 结果同步 | 零超时 fence 检查后 `glGetBufferSubData`，journal/环境解析与发送 | fence 不等完成，但已完成数据的下载和 CPU 解析仍有成本 |
| 发布 | GPU buffer copy；自由、锁链、观察者组合成一个可见 bank | CPU 提交和 GPU 带宽 |
| 初始化/容量变化 | 编译 shader、分配/重分配 buffer、查询实际分配大小 | 可能造成偶发长帧，稳态预热基准未覆盖 |

`PackageReadbackRing.java:67` 使用 `glClientWaitSync(..., 0)`；未完成时直接结束本次轮询。`:77` 仍调用 `glGetBufferSubData` 把已完成片段复制到 CPU。正常物理热路径没有 `glFinish`，未就绪输入/上传 bank 以稍后重试处理。

`glMemoryBarrier` 表达 GPU 访问间的可见性与顺序依赖，不等同于 CPU 调用 `glFinish`。驱动处理、命令队列反压和资源同步仍可能增加某次 GL 调用的墙钟耗时。[Khronos 同步说明](https://wikis.khronos.org/opengl/Sync_Object)、[内存屏障说明](https://wikis.khronos.org/opengl/GLAPI/glMemoryBarrier)。

## 现有测量能说明什么

上一轮 `sparsePhysicsBenchmark` 的 CPU 区间仅包住 `gpu.step(0.005f)`，预热后 P95 约 0.001–0.004 ms。它没有完整世界支撑路径、`PackageWorldRuntime.prepare` 的输入处理、atlas 上传、网络/环境读回、观察者、发布或游戏内绘制。因此不能用它声称整条 GPU 自由包裹链路仅消耗几微秒 CPU。

`ParticleDiagnostics` 的 CPU 指标来自 `System.nanoTime`，实际是渲染线程提交区间的墙钟耗时，包含可能发生的驱动等待；其 `ThreadMXBean` 当前用于分配字节数，没有记录 active thread CPU 时间。

`package_physics` marker 是 GPU timestamp 的区间名字，包住整个 `PackageWorldRuntime.prepare`，不是独立 solver 的 CPU 计时。`pumpGpu` 在此 marker 之前；客户端 tick 的采集也在粒子 frame 的测量区间外。现有 tracked GL call 数没有覆盖 solver 的每条 GL 调用。

## 命令数量与补步

生产自由物理始终使用世界路径、支撑投影、4 轮接触及额外 4 轮 cleanup。无移动结构、无内置静态 body、环境检测启用时，每步约有 `27 + ceil(log4(count))` 次 compute dispatch API 提交；65–131,072 个槽位对应约 31–36 次。支撑无边时，间接 dispatch 的 group count 可以为零，CPU 仍提交命令；不能把 dispatch 次数直接当作实际 GPU 工作量。

每个移动结构另有 prepare、carry 和三轮 contacts，增加约 5 次 dispatch；非空力场另有一次 force pass。预取、环境 capture、delta、观察者和最终绘制不包含在上述计数中。

`PackageTickTiming.stepsPerFrame` 允许默认 20 tick/s 最多 4 次补步；200 tick/s 最多 40 次。40 步是补步上限，不代表正常每帧都执行。渲染阻塞后集中补步会放大渲染线程命令提交和 GPU 负载，不能用单步耗时代替帧尖峰。

## 优先优化顺序

1. **先把 CPU 测量拆开。** 分别记录 tick capture、upload、prepare/poll、historical/moving/force view、solver submit、readback decode、publish 的 wall P50/P95/P99、thread CPU、分配字节数，同时记录本帧步数、存活数/索引上界、移动结构数、下载字节和输入等待原因。没有这些数据，无法断定 solver 提交就是最大 CPU 热点。

2. **协调采集与上传的总帧预算。** 默认 `packageMainThreadBudgetMs=10` 分别供客户端 tick 采集与渲染上传使用；不是二者合计 10 ms，更不覆盖整个 prepare/solver/publish。采集或上传积压时，同一显示帧可能承担两个预算及额外工作。优化方向是共享帧级 deadline、保留世界/移动/光照的公平份额，并优先保障将到期物理输入。直接降低预算可能增加几何等待和包裹暂停，应配合历史年龄和队列指标。

3. **减少同一物理步内重复 GL 状态提交。** `PackagePhysicsGpu.bind` 重复绑定 state/grid；`PackageCollisionGpu.View.bind` 重复提交 world 的不变参数和屏障。可在明确拥有的 pass 边界内缓存参数/绑定，预计算 drag/friction，再按真实数据依赖合并屏障。不能跨未知的 force/moving/draw 绑定变化盲目复用缓存；也不能把 Jacobi 的依赖屏障直接删掉。

4. **按发布 bank 和 domain 版本跳过无变化的复制。** `PackageMixedPhysicsGpu.publish` 只要任何域的总版本变了，就复制自由、锁链及观察者的全部当前范围。观察者时间采样可在无新自由物理步的渲染帧改变总版本，从而复制未变化的自由状态。应在两个目标 bank 各自记录 domain 版本；只看上一次全局版本会让切回旧 bank 时读到过期状态。自由状态+历史为每索引 96 字节，131,072 个索引每次提交复制 12 MiB，有针对性削减空间，实际收益取决于各域负载。

5. **让预取随输入/姿态/世界版本变化。** `worldPrefetch.capture` 当前在自由包裹活跃时每渲染帧都尝试，含没有新物理步的帧。可使用物理姿态版本、世界 table token、力/移动输入版本及重试期限决定是否重新采集。需要保留对未覆盖区域、几何就绪及 LRU usage 的及时重试，不能只按 free simulation step 去重。

6. **大规模 GPU 优化：活动集、睡眠和移动结构候选。** 当前 `previousSleep.w` 主要是生命周期/暂停标记；正常 body 即使静止也继续积分、求解并扫描邻居。`count` 还是稳定索引上界，内部退役空洞也有 dispatch 与 ping-pong 写回成本。可建立保留 body ID 的 GPU 活动索引和间接调度，而非移动租约槽位；进一步引入睡眠 island。唤醒必须覆盖新邻居、支撑移除、环境、风扇、移动结构与版本变化。移动结构当前逐结构对整个 body 范围执行 pass，可先生成空间候选列表。两项收益潜力较大，但都应以 CCD、堆叠稳定性、移动支撑及物流事务回归作为门禁。

较小的 CPU 改善包括缓存 freeze diagnostic 的 uniform location；该 capture 约每秒一次，优先级低于上述逐帧/逐步工作。`forceCapture.prepare` 在 tick 和 render 被调用多次，但 `needsCapture` 已阻止同一输入反复实体扫描，不宜仅依据调用次数推算重复世界扫描开销。

当前证据支持先完善 CPU 分项诊断、处理预算与重复提交、减少无变化的复制/预取；是否需要独立 GL 线程，应在这些测量之后再判断。真正需要把 CPU 调用与渲染解耦时，共享 context、资源交接、fence 与最后完成姿态的渲染契约都需要重新设计，属于架构改动。