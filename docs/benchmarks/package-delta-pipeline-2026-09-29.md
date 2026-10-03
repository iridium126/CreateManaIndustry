# 包裹增量分片管线测量（2026-09-29）

本报告比较新建组件的提交方式，不是 Create 与 GPU 包裹的整场景前后报告。尚未进行 Minecraft 实体、库存、网络服务器、实际模型绘制、Iris 或 131072 个活动包裹的整帧验收。

## 方法

- RTX 4070 Laptop，OpenGL 4.5，NVIDIA 581.15，JDK 21；隐藏的真实 OpenGL context。
- 每种负载 15 次预热、30 个测量样本，重复三组；第二组反向执行对比路径。测试前保留全套包裹 GPU 正确性验证。
- 固定身份和分散位置；每次捕获所有候选的位置均改变，速度保持 0.25 方块/秒。每包 512 条，位置精度 1/4096。没有用大量静止对象减少本次管线的工作量。
- 使用生产 `PackageDeltaGpu`、四槽 header/payload ring、journal、两个后台编码线程和共享四任务限制。CPU 测量包含捕获提交、完成槽轮询、有效正文读取、journal、不可变 packet 构造及 ACK 提交。
- 传输接收不可变的 `ServerboundPackagePacket`，模拟立即 ACK。没有 Netty、实际网络、服务端 pose 提交或广播。每次循环之间由验证程序 sleep 1ms，sleep 不计入 CPU 总量；端到端延迟包含此轮询间隔，不能当成游戏帧调度结果。
- 合成 body 的 CPU 上传、初始化、shader 编译和协议类初始化不计入预热后的提交样本。实际物理应直接在 GPU 修改 body。第一次协议类初始化约 423ms，发生在预热前；正式接管必须先完成资源准备。
- `cpu_total` 为一次完整捕获至确认期间各 capture/pump 调用的 CPU 耗时之和；`cpu_peak_call` 为其中最大单次调用耗时，都不是整帧耗时。后台编码工作单独求和，不加入渲染线程耗时。
- 分配量为 ThreadMXBean 观测到的渲染线程 Java 分配；不包含 worker、Netty、Java 堆存量、native buffer 或显存。

## 采用的默认路径

默认采用后台排序、直接整数编码和不可变 packet 构造；ACK 仍使用匹配的原始 512 条记录。下面取三组各分位数的中位数。

| 候选数量 | 渲染线程构造包 CPU 总量 p50/p95 | 后台构造包 CPU 总量 p50/p95 | 后台构造包端到端 p50/p95 |
|---:|---:|---:|---:|
| 10000 | 0.5130 / 0.6916 ms | 0.3202 / 0.4573 ms | 7.940 / 9.478 ms |
| 65536 | 1.3714 / 2.1710 ms | 1.2226 / 1.5821 ms | 8.795 / 10.506 ms |
| 131072 | 2.8199 / 3.8827 ms | 2.4241 / 2.7241 ms | 12.832 / 15.298 ms |

131072 的后台路径三组 CPU p95 为 2.6118 / 2.7241 / 2.9010ms，对照为 3.8827 / 8.6019 / 2.8492ms。第三组没有可确认的 CPU p95 收益，第二组对照波动很大；不能把分位数中位数转换为承诺的帧率提升比例。

渲染线程分配量下降更稳定：131072 对照约 2869–2879KiB/完整捕获，后台路径约 60–70KiB/完整捕获。后台仍会分配不可变 packet 的正文副本，整体分配没有消失；本改动将它移出渲染线程。当前默认路径一轮全量变化需要 256 个网络包及 256 次 ACK dispatch。

| 候选数量 | 原始有效读回正文 | 编码正文均值（约） | 包数 |
|---:|---:|---:|---:|
| 10000 | 640000 B | 100747 B | 20 |
| 65536 | 4194304 B | 707773 B | 128 |
| 131072 | 8388608 B | 1423880 B | 256 |

编码正文不含区域/epoch/序号 envelope、网络 framing、压缩和服务端广播；数值随 GPU 输出排序略有变化。上述全部对象每次移动的输入仍需要较高带宽，不能以“只发变化”宣称带宽目标已完成。真实采样频率、视觉阈值、链路参数同步、观察者订阅及停止重复 vanilla 同步仍需验证。

原始摘要：[package-delta-pipeline-2026-09-29.csv](package-delta-pipeline-2026-09-29.csv)；全部 810 个预热后样本：[package-delta-pipeline-samples-2026-09-29.csv.gz](package-delta-pipeline-samples-2026-09-29.csv.gz)。

## 未进入默认路径的 ACK 合并

第一版在 CPU 拼接同 stamp 的原始 ACK 记录后上传 1MiB 批次。131072 的 CPU 总量 p95 中位数由 3.7628ms 增至 4.3575ms，虽然 dispatch 从 256 次降为 8 次，实际提交回退；该实现已移除。结果保留在 [upload-batch 摘要](package-delta-pipeline-upload-batch-2026-09-29.csv) 和 [逐次样本](package-delta-pipeline-upload-batch-samples-2026-09-29.csv.gz)。

第二版将原始记录额外复制到 GPU journal，ACK 只选择匹配的 GPU 区间；同 stamp、连续槽合并，满容量时 8 次 dispatch，额外显存最多 8MiB。在相同后台 packet 路径上，131072 的 CPU 总量 p95 为 2.7840 / 4.0387 / 3.3227ms，对应逐包 ACK 为 2.6118 / 2.7241 / 2.9010ms。收益没有复现，因此仅保留内部显式构造开关和验证，不进入默认接管路径。

GPU journal 的第一次比较没有后台 packet 构造，用于隔离该尝试：[首次摘要](package-delta-pipeline-gpu-journal-first-2026-09-29.csv)、[逐次样本](package-delta-pipeline-gpu-journal-first-samples-2026-09-29.csv.gz)。最终三路径比较见上面的当前摘要。不能把减少 GL API 次数本身当作实际加速。

## 正确性和资源边界

新增驱动测试覆盖 65 和 131072 条完整端到端记录、12 帧后台任务延迟、逆序完成、部分传输拒绝、原始 body 在等待 ACK 时继续改变、GPU journal 环形回绕、满 bank、错误 ACK 命名空间、旧 generation、重复终止通知和关闭未读 epoch。

默认四个 GPU 不可变输出 bank 保留约 32MiB 正文；读回 staging 约 4MiB；journal 原始 CPU direct records 最多 8MiB。GPU metadata、确认基线、flight、ACK 暂存、Java 编码空间及物理/绘制缓冲另外占用资源。这里列的是显式缓冲分配，尚未采集游戏进程整体显存 p95 或内存峰值。

50 项 Java 测试、811146 项包裹 GPU 断言及 135498 项 Hex 断言通过；包含本报告管线测量时包裹驱动验证为 814845 项断言。纯协议目标模拟不等同真实物品守恒、保存加载和多人交接验收。

## 重跑

```powershell
$env:JAVA_HOME='D:\Program Files\Java\jdk-21'
$env:GRADLE_USER_HOME='D:\Program Files\Gradle\cache'
.\gradlew.bat validatePackageGpu --offline --no-configuration-cache -I scripts/particles/validation.init.gradle -PpackageDeltaPipelineBenchmark
```

保持正常两 tick/100ms 准备超时；没有为了基准延长回退时间。输出在 `build/package-delta-pipeline.csv` 及 `build/package-delta-pipeline-samples.csv`。生产资源门禁目前未开放，游戏中真实包裹仍由 Create 管理。
