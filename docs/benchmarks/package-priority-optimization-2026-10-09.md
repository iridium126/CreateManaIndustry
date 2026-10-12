# GPU 自由包裹逐项优化（2026-10-09）

> 历史测量结论；原始运行产物不随仓库保存。重新测量与结果管理见 [性能结果规则](README.md)。

按 [CPU 开销分析](../gpu-package-performance.md) 的六项顺序推进。物理提交仍由渲染线程拥有，GPU 姿态没有转为 CPU 求解或逐体读回。

## 六项实现

1. **分项诊断。** 新增 `PackageCpuDiagnostics`，接入已有 `/cmi particle profile on` 和 `/cmi particle stats`。记录几何/力采集、上传、整个 prepare、poll、历史/移动/力 view 准备、solver 提交、readback 和 publish 的 wall P50/P95/P99、thread CPU P95、Java 分配 P95；记录本帧提交步数、存活数/稳定索引上界、移动结构数、包裹 compute 提交数、下载字节数和等待输入原因。采集含客户端 tick 的工作，不再只有粒子 engine 的 compute 区间。诊断默认随现有 profile 开关关闭，无额外 GPU timer 或同步等待。

   各 phase 是 inclusive totals，`PREPARE` 包含其子环节，`READBACK` 也可包含在 `POLL`；不能相加当总 CPU 时间。wall 包含调度和驱动等待；thread CPU 表示当前线程执行时间，不支持时显示 unavailable。ThreadMXBean 时间精度依赖 JVM/平台，诊断本身也有开销。

2. **共享几何帧预算。** `PackageFrameBudget` 让两个渲染上传之间的客户端采集与下一次 atlas 上传共享配置额度。采集最多使用一半，剩余给上传；无采集时上传可使用全部。多次 catch-up tick 不再各拿一个完整额度；上传前的资源/历史准备时间计入剩余额度。World/Moving/Light 原有公平份额和零预算 metadata/pose 路径继续保留，预算耗尽不丢弃输入 tick。

   这是 soft geometry budget：单个不可拆分操作可超时，力/实体读取、solver、读回和 publish 分项记录而未全部纳入该额度。降低采集份额可能延长复杂世界的几何就绪时间，需实机观察等待原因和历史年龄。默认仍为 10 ms，配置说明已改为共享帧额度。

3. **减少 GL 重复提交。** 在 solver 拥有的 immutable step 内缓存 state/grid 绑定、world 参数和 world 绑定；缓存每个程序的不变 float uniform，按原公式预计算 contact friction；缓存 indirect-buffer 绑定。步结束清除绑定边界，外部 force/query/draw 不共享该缓存。保留跨 dispatch 的 shader-storage、buffer-update 和 command 依赖。Freeze capture 的 uniform location 改为构造时缓存。

4. **按目标 bank/domain 版本发布。** 两个输出 bank 分别记录 free、chain、observer 版本，仅复制落后于对应源版本的域。首轮进入每个 bank 都正常填充，checkpoint/append/retire 后两个 bank 各自追上；避免仅对比上一个 bank 导致回切旧数据。新增复制字节计数供验证。回归证明：两个 bank 填充后，只有锁链推进时不再复制自由域，替换自由 checkpoint 后所有后续 bank 都保留新姿态。

5. **版本驱动预取。** 按 free state version、世界 table token、输入版本决定立即重试；未变化时 50 ms 刷新一次 coverage/usage。上传 bank/读回 ring 拒绝时不记成功，不抑制下一次重试；无活跃自由区域时重置。世界/输入变化会立即重试，不依赖是否刚好有新的物理步。

6. **稳定 ID 活动集、保守睡眠、移动候选。** 活动索引放在 links buffer 的独立尾部，不搬动 body、history、租约或协议 ID。退役槽位退出重型自由求解；生命周期更新同步两份 state，避免未执行的 ping-pong 槽位复活。满密度直接使用原线程索引。哈希表容量和支撑 pointer-jump 轮数按存活数计算；祖先仍引用稳定 body ID，传播深度按活动树规模界定。

   睡眠采用**整场景**证明，不是局部 island：所有存活 body 连续 16 个已完成物理步处于 solver 精度内的静止状态（速度绝对值 ≤1e-6、每轴位移 ≤1e-5），且世界就绪、无移动 scene、无非空 force input，才将重型 pass 的 indirect group count 置零。进入睡眠前同步 state bank。几何 token、checkpoint、追加/回收、激活、force 或外部 pose/velocity 写入使证明失效。环境检测、燃烧/伤害计时、journal/ACK、模拟步号和历史继续推进。

   `stateBuffer()` 的外部 pose/velocity 写入方需调用 `externalGpuMutation()`；生产力输入与生命周期路径已自动处理，验证工具的 GPU drive 也已接入。局部静止、局部移动的混合场景继续求解，不以不完整的唤醒规则跳过 island。

   每次 moving carry/contact 前生成保守候选，包含 swept AABB 命中、有既存 moving support 标签或 pose 不可用的 body；候选密集时回退连续/活动顺序。carry/contact 只修改自己的 body，改为原地执行，未选中的 body 无需复制。候选数按 workgroup 汇总，避免逐体全局 atomic 热点。计数和 indirect command 同放 links 尾部，不新增 shader SSBO block；驱动接口回归确认关闭可选诊断后，各 kernel 实际引用的 compute SSBO 不超过 8。

## 真实驱动基准

NVIDIA GeForce RTX 4070 Laptop GPU，OpenGL 4.5，NVIDIA 581.15。基线是本轮开始的暂存版本，包含上一轮低占用哈希表优化。前后用相同完整世界/支撑物理夹具，固定预留 131,072 个槽位，`dt=0.05`，三轮，每轮至少预热 60 步并持续 1 秒，再测 60 步。atlas 为 448 sections 的隐藏 GLFW context 夹具，未运行真实 Minecraft 世界。

`resting` 为规则堆叠，`driven` 由 GPU drive 持续注入运动，`holes` 保留每 16 个稳定索引中的一个，其余明确退役。驱动写入在新 solver 上使睡眠失效。每体有限性/生命周期检查、非 holes 场景的全对 penetration 与 terrain 质量门禁通过。读回质量检查在计时区间之外。

下表为三轮各自 P95 的中位数，单位 ms。CPU 区间包住 drive（若有）、world view 创建、完整 step 和 view 关闭，仍不是整个游戏运行时的 CPU 成本。

| 索引上界 | 场景 | GPU P95：前 → 后 | GPU 变化 | CPU submit P95：前 → 后 |
| ---: | --- | ---: | ---: | ---: |
| 1,024 | 静止堆叠 | 0.677888 → 0.214016 | -68.4% | 0.026800 → 0.020200 |
| 1,024 | 持续驱动 | 0.746496 → 0.686080 | -8.1% | 0.019700 → 0.029400 |
| 1,024 | 1/16 存活槽位 | 0.507904 → 0.210944 | -58.5% | 0.015100 → 0.035900 |
| 10,000 | 静止堆叠 | 2.287616 → 0.238592 | -89.6% | 0.013600 → 0.022200 |
| 10,000 | 持续驱动 | 2.363392 → 2.051072 | -13.2% | 0.018300 → 0.021100 |
| 10,000 | 1/16 存活槽位 | 1.054720 → 0.215040 | -79.6% | 0.012599 → 0.018900 |
| 65,536 | 静止堆叠 | 7.694336 → 0.256000 | -96.7% | 0.012400 → 0.013800 |
| 65,536 | 持续驱动 | 7.769088 → 7.960576 | +2.5% | 0.018100 → 0.022700 |
| 65,536 | 1/16 存活槽位 | 2.513920 → 0.222208 | -91.2% | 0.018400 → 0.033900 |

静止和稀疏夹具 GPU 收益显著；65,536 档持续驱动 GPU P95 增加约 2.5%，这是当前活动映射/新调度的实际代价，不能宣称所有场景都更快。CPU submit 仍约 0.01–0.04 ms，部分档 P95 上升，未证明稳定的整体 CPU 提交收益。减少 domain copy、预取频率和共享预算的游戏内 CPU 收益，需用新增分项诊断测量；不能从睡眠的 GPU 收益推算 FPS。

这份基准不包含 force BVH/world discovery、真实多人网络、Iris 最终绘制或容量变化的冷启动。高占用 moving 场景需额外测量分类开销；密集回退保持语义，尚未单独量化性能。

活动/candidate links 为每预留槽位增加 8 B（131,072 个槽位为 1 MiB），另有 32 B candidate header 和最多 32 B sleep control；CPU 活动上传 scratch 为每预留槽位 4 B。小存活场景继续回收 hash scratch；不把 GPU 时间收益表述为全部内存收益。

汇总：修改前（本地运行结果）、修改后（本地运行结果）。逐步样本：修改前（本地运行结果）、修改后（本地运行结果）。

## 验证和使用

专项 GPU 门禁通过 1,264 项断言，覆盖双 bank/domain 版本、稀疏工作组尾部与高速 CCD、回收、稳定 body 的 freeze 诊断、睡眠后 force/checkpoint/支撑移除/未知 moving 输入、睡眠中的 burn/health/ACK 以及 base SSBO 接口容量。新增 Java 测试覆盖共享额度、多 tick、公平上传保留、超时/配置下降、预取依赖和拒绝重试、诊断关闭路径与帧上下文。

最终完整 GPU 回归通过 14,675,334 项断言；Java 测试、构建和 Sable 缺席门禁全部通过。验证日志为 `build/package-priority-final-validation.log`。

```powershell
.\gradlew.bat test build validatePackageSableAbsent validatePackageGpu --offline --no-configuration-cache -I scripts/particles/validation.init.gradle --console=plain
.\gradlew.bat validatePackageGpu -PpackagePriorityOnly --offline --no-configuration-cache -I scripts/particles/validation.init.gradle --console=plain
.\gradlew.bat validatePackageGpu -PpackagePriorityBenchmark --offline --no-configuration-cache -I scripts/particles/validation.init.gradle --console=plain
```

游戏内开启 `/cmi particle profile on`，再执行 `/cmi particle stats` 查看 package CPU phases。先看 `CAPTURE`/`UPLOAD`/`PREPARE` 与 `SOLVER` 的 P95/P99，再结合 steps、live/indices、moving、downloaded 和 waiting 判断尖峰来自采集、补步、读回还是提交。不同驱动、连续资源重建及真实多人物流仍需实机验证。
