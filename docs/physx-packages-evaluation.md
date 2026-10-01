# PhysX 与包裹密集堆叠：候选后端评估

初稿日期：2026-09-29。以下为当时对 `.refs/PhysX/physx` **5.11.0** 的源码/API 可行性评估。**2026-09-30 已新增官方匹配 SDK 的实际 native GPU/CPU 对照**，见 [同机测量报告](benchmarks/package-physics-backends-2026-09-30.md)。131072 活动箱体下 PhysX 候选没有同时通过性能与接触质量门槛，因此未接入生产路径；本页初稿里的候选建议不可当作已验证的优势。

## 结论

**GPU PhysX 值得作为自由运动包裹的接触解算候选，不能据此认定它更快或已满足 131072 活动包裹验收。** TGS、持久接触流形和摩擦约束比当前四轮位置平均 Jacobi 提供了更完整的刚体接触机制；预期改善密集堆叠质量是推断，仍需同场景检验。单纯增加通用刚体引擎也会增加 native 对象管理、内存、约束准备和图形互操作的成本。

当前合成基准中四轮 Jacobi 的 131072 包裹相邻对最大重叠为 0.7037 方块，不能启用真实接管，详见 [世界碰撞报告](benchmarks/package-world-2026-09-29.md)。新实验性的垂直支撑传播通过了 1/32/63/64/65/129 层单列堆叠，但还不是不规则密集接触、侧向冲击或持续活动场景的验收。实验开关默认关闭。这两条路径可作为 PhysX 对照，不能用单列堆叠通过代替完整视觉验收。

后续 OpenGL 原型已增加相对速度、各轴接触极值及进入面修正，并完成完整覆盖下的规则/错位/持续推力合成检查；131072推力场景单步 GPU p95 三组中位数5.5470ms，零回退、最大穿透为零，详见 [堆叠报告](benchmarks/package-stack-2026-09-29.md)。当前回归为包裹 GPU 3313503 项、Hex GPU 135498 项断言及149项 Java 测试，构建通过；实验仍默认关闭。这些结果不属于 PhysX 验证，也不是游戏内视觉或整帧性能验收。

## 本地源码确认的能力与限制

| 项目 | 证据与对本项目的影响 |
|---|---|
| GPU 解算器 | `physx/include/PxSceneDesc.h` 的 `PxSolverType::eTGS`；`physx/source/gpusolver/src/CUDA/solverMultiBlockTGS.cu` 有 GPU TGS 接触/摩擦求解。需要显式选择 TGS，场景默认是 PGS。 |
| GPU 管线 | 同时设置 `eENABLE_GPU_DYNAMICS`、`broadPhaseType=eGPU` 和 CUDA context。CUDA 后端适用于兼容 NVIDIA GPU，RTX 4070 属于候选硬件；其他客户端仍需 OpenGL/Create 路径，不能让 131072 包裹静默转入 CPU PhysX。 |
| 批量状态访问 | `PxDirectGPUAPI.h` 可按 GPU index 批量读取位姿/速度至 CUDA device buffer，避免每帧逐 actor Java/JNI 读取。第一次模拟之后才可用；CPU 的对应 getter/setter 此后不能用作最新状态接口。 |
| **Direct GPU API 与休眠冲突** | 本地 `PxSceneDesc.h` 和 `CHANGELOG.md` 明确要求 `eDISABLE_SLEEPING`，场景会自动开启该标志。不能同时承诺 Direct GPU API 避免状态读回和 PhysX 原生休眠节省静止堆叠成本。自建活动区/冻结机制涉及支撑占位、唤醒和动态/静态转换，需要独立验证。 |
| 异步 API | `getRigidDynamicData()` 的 `finishEvent=NULL` 会等待拷贝结束。必须提供完成事件，禁止在主线程/渲染线程照抄同步示例。官方 snippet 同时使用 `fetchResults(true)` 和全量 `memcpyDtoH`，它是演示，不符合本项目提交要求。 |
| OpenGL 接入 | `PxCudaContextManagerDesc.graphicsDevice` 可指定图形 context。CUDA 与 OpenGL 需使用匹配的设备/context、明确共享缓冲所有权及同步；PhysX 不能直接消费现有 GLSL 世界碰撞 atlas 或在 64 字节通用粒子结构上原地解算。 |
| 接触容量 | `PxGpuDynamicsMemoryConfig` 中 contact、patch、found/lost 等容量需要预先配置，部分 buffer 可增长，不能假定所有 buffer 均可增长。`PxgNarrowphaseCore.cpp` 明确报告接触/patch/碰撞栈溢出和接触丢弃。接触容量取决于接触密度，而不只是刚体数。 |
| GPU 路径边界 | 官方文档说明自定义几何、contact modification、场景查询，以及部分 CCD/trigger 工作仍走 CPU。不能用 PhysX 自定义体素几何回调逐包裹查询 Minecraft 世界，然后宣称全部碰撞在 GPU 完成。Direct GPU 模式的高速碰撞支持必须针对所固定的 SDK 验证，不能从普通 CPU 模式能力推定。 |
| **Direct GPU API 与扫掠 CCD 冲突** | 本地 `PxSceneDesc::isValid()` 在 Direct GPU API 与场景 `eENABLE_CCD` 同时开启时直接返回 false。不能通过加一个 CCD 标志解决高速包裹穿透；需另测子步/外部 GPU 扫掠路径。Speculative CCD 的组合与覆盖范围也须验证，不从普通模式推定可用。 |
| 稳定性选项 | `eENABLE_AVERAGE_POINT` 在 GPU 上仅对指定凸体窄阶段生效，不能假定所有箱体/地形接触都受益。TGS 的每迭代外力选项会改变自由落体距离，必须重新校准，不能直接沿用默认重力/摩擦。 |

官方参考：[GPU Simulation](https://nvidia-omniverse.github.io/PhysX/physx/latest/docs/GPURigidBodies.html)、[CUDA Graphics Interoperability](https://docs.nvidia.com/cuda/cuda-driver-api/cuda_driver_api/group__CUDA__GRAPHICS.html)、[CUDA OpenGL Interoperability](https://docs.nvidia.com/cuda/cuda-driver-api/cuda_driver_api/group__CUDA__GL.html)。线上文档与本地源码可能存在版本差异，集成时应固定本地 revision 和 SDK/native 库版本。GPU 接触容量溢出在本地实现中也已确认。

## 可行的内部接入方式

1. 建立可选 C++/JNI 物理后端，只接管自由运动包裹。锁链仍使用现有参数化 GPU 运算，渲染、通用槽位、身份协议和服务端物流事务继续复用。
2. 首次准备/增删使用批量 native 命令；运行期间在专用模拟调度线程维护 PhysX scene。主线程只提交已确认的世界快照和交互命令，渲染线程只消费已完成的桥接缓冲。任何 GPU/worker 未完成均不能阻塞这两个线程。
3. 静态地形复用不可变 section 快照的采集与版本规则，但另行转换为 PhysX GPU 支持的几何。比较合并 box 与 GPU cooked mesh 的成本；mesh 须启用 `buildGPUData`，否则会走 CPU 接触生成。后台只处理快照，不读取可变 Minecraft 世界。未知覆盖区域仍拒绝接管/提前交还。
4. 每个包裹仍占一个通用粒子槽；PhysX actor、GPU index 和历史状态为附加数据。稳定 ID/generation/epoch 与 PhysX GPU index、通用池索引分别管理，删 actor/压缩/重用索引后必须废止旧映射，完成事件前不能重用仍被引用的数据。
5. GPU 批量取位姿/速度 → CUDA 转换为当前 body/历史布局 → 环形 OpenGL 桥接缓冲 → 现有剔除、间接绘制与 delta 筛选。禁止全量 GPU→CPU→GPU 状态搬运。CUDA map 期间 OpenGL 不得访问同一 buffer；map/unmap 具有跨 API 同步语义，不能当作零成本或非阻塞的证明。另测多缓冲和可用时的 external memory/semaphore 路径，未验证扩展前保留兼容路径。
6. 自由运动的物理碰撞箱与 Create 的 AABB 语义对齐。初始测试禁用物理角旋转，视觉 yaw 独立保留，避免 PhysX 的箱体旋转/翻滚造成行为差异。重力、阻尼、摩擦和高速子步按抛出/滑动/推挤对照校准，不能把 SI 默认值直接套到方块单位。
7. 增量同步仍从 GPU 与已确认基线比较。只异步读回变化字段和玩法候选事件，服务端仍独占库存/掉落/拾取提交。后端切换不得改变事务身份、ACK 基线和迟到 epoch 拒绝规则。
8. 容量溢出、CUDA abort、旧 section 或过期结果必须使本代结果失效，恢复上次完整提交状态并按现有时限交还 Create。不得发布丢弃部分接触后的结果，不能依赖 PhysX 自动 CPU 回退掩盖后端失败。

## 对照验收

先做独立 native 物理基准，再做 CUDA/OpenGL 桥接，最后做游戏内接管，分别统计每一项新增成本。

- 同场景对照当前四轮 Jacobi、支撑传播实验与 PhysX GPU TGS；比较 20/30/60Hz 和解算迭代数，在相同视觉质量下比较耗时。
- 10000、65536、131072 及容量边界；包括规则/错位高堆叠、多个支撑、抽掉底层、侧向冲击、高速撞击、动态地形和持续活动混合负载。
- 预热后三次独立重复，记录 CPU 提交/模拟调度/interop、各 GPU 阶段 p50/p95、显存与 pinned host 内存、接触/patch/pair 峰值、上传/读回量和回退率。
- 检查所有接触对的穿透、堆叠高度、抖动、异常能量和校正跳变；录像对照 Create。只测试静止、禁用碰撞、遗漏接触或大量 Create 回退都不能算作 131072 活动包裹验收。
- 真实整帧与服务端 tick、多人 authority 迁移、两 tick 交接预算和库存守恒仍独立验收。PhysX 不负责改善服务端物流对象的 tick、存档和网络成本。

目前建议将 PhysX 定位为 **NVIDIA 可选后端和质量对照**，先证明接触质量与总成本，再决定默认策略；尚未增加生产依赖、JNI 库或 PhysX 接管入口。
