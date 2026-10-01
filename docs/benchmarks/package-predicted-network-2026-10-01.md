# 包裹 v5 无损运动差分预测（2026-10-01）

新增 GPU 位移预测编码、服务端精确还原与 ACK 历史推进，已通过真实 GPU/协议/提交回归。131072 异速匀速运动的三秒压缩上行从 39045106B 降为 9647704B（减少约 75.3%）。**这不是总网络验收，也未启用生产接管。** 保留原生位置下行后的组件合计仍超出原生；渲染线程 p95 收益未稳定复现，因此预测模式保留为显式内部比较，世界运行时默认仍选上一版相对编码。

## 精确协议

可选权威 registrar 为 `gpu-packages-5`，新增 `PREDICTED_DELTA=8`；v4 不能协商 v5。5/6/7 的旧动作与 v4 精确区间 ACK 不变。新动作复用现有有界包内 codec、envelope 和字段 mask。

对每个身份，令 `B` 为最后已确认位置，`D` 为上一个成功 POSITION 相对其前一基线的精确整数位移，当前位置 `Q` 编码为 `E=Q-B-D`。服务端还原 `Q=B+D+E`；验证整批后才令 `D=Q-B`、`B=Q`。只预测 wire 整数，不预测服务端物理或近似姿态。无需时钟、速度或跨设备浮点一致；更新间隔不等或碰撞突变只影响压缩率，不改变位置。

GPU 使用 16B/candidate sidecar，131072 容量为 2MiB，绑定 6；仍是一包裹一通用槽位，64B 粒子、20 vec4 header、64B 原始增量/ACK 记录均不变。模式在资源创建时固定，默认 false，要求 relativePositions=true。追加和准备期 rebase 清零相应历史；初始化清零全部，资源重编译保留历史。三个程序全部验证后替换，fixed uniform 使用不改变当前 GL program 的初始化。

GPU 仍按绝对量化状态比较脏字段，**零预测残差的移动包裹仍发送 POSITION**。POSITION/VELOCITY/YAW/FLAGS/RELEASE 语义、20Hz 物理、发送时机与两 tick 期限均不变。速度或姿态单独变化不会清除位移历史。无变化时不读取 predictor；工作组尾部继续参与所有 barrier。

捕获锁定基线与历史。只有匹配身份、局部索引、candidate、flight stamp 的真实服务端 ACK 才从固定快照还原位置并推进历史；body 继续移动、取消、读回完成、旧/重复 ACK 和终止通知不推进历史。服务端拒绝/回滚的前缀也不推进。退休索引不能重用；新成员历史为零。服务端观察日志始终发布还原后的绝对字段。

内部使用 `new PackageDeltaGpu(capacity, sources, true, true)`，transport 必须同时声明 batchEncoded/relativePositions/predictedPositions；模式不一致在 channel 构造时拒绝。生产 authority transport 支持选择 action 8，但世界默认继续采用 relative 模式。此实验不作为当前方案；带宽门禁已按后续范围调整撤除，网络表现仅作后续优化参考。

## 字节对照

沿用既有四种固定轨迹、10000/65536/131072、60 tick、512 项批次、完整 epoch/sequence，新增第八动作。每包编解码及整数还原精确核对；原相对编码的历史 payload/frame CSV 与本次逐行一致。初始预测位移为零，第一波也计入。压缩/framing 使用真实 Minecraft handler，方法见[上一版帧报告](package-network-framing-2026-10-01.md)。

131072，异速 XYZ，20Hz，三秒，一个权威客户端：

| 组件 | 上一版 relative | 新 predicted |
|---|---:|---:|
| 上行 payload | 48590500B | 20227596B |
| 无压缩上行帧 | 49097380B | 20734476B |
| 阈值 256 上行帧 | 39045106B | 9647704B |
| 阈值 256 上行 + ACK | 39049425B | 9652023B |
| 保留原生位置流后的合计 | 72797945B | 43400543B |
| 合计/原生位置帧（33748520B） | 2.157 | 1.286 |

假设停止权威重复位置下行时，新上行 + ACK 为原生位置帧的 0.286 倍；**该停止同步尚未实现**。四客户端保留原生位置流后的合计仍为原生的 1.072 倍。基线、迁移、心跳、生成/销毁、内容、yaw/velocity、观察者新增协议、链 BE、TCP/IP/加密和实际 socket 均未计入。ACK 假设即时同 tick 接受；变动 RTT/捕获间隔、反弹或高频碰撞可能使预测残差变大。本报告是位置编码组件对照，不能将匀速轨迹结果作为完整活动包裹验收。

原始数据：[payload CSV](package-predicted-network-2026-10-01.csv)、[压缩帧 CSV](package-predicted-network-framing-2026-10-01.csv)。三 tick 变体仍只是估算，没有降低默认发送频率。

## 真实 GPU 与 CPU 成本

RTX 4070 Laptop GPU、NVIDIA 581.15、OpenGL 4.5、JDK 21.0.8。

GPU 内核测量含 detect/finalize/cancel，预先建立相同确认历史，30 次预热，三轮各 60 个 timer 样本，交替模式次序。131072 全量变化 p95：relative 三轮均 0.075776ms，predicted 均 0.076800ms；无变化两者 p50 0.034816–0.035840ms，p95 区间重叠。没有着色器提速结论；新增约一个 timer 量化单位的全量成本。只测组件，不含 ACK 上传、网络或整帧。[内核汇总](package-prediction-kernels-2026-10-01.csv)、[全部样本](package-prediction-kernel-samples-2026-10-01.csv)。

另用实际 GPU capture→四槽正文读回→两个后台 encoder→immutable packet→loopback ACK 比较。异速三轴匀速输入，所有包裹持续变化；模拟输入上传在计时外，生产由 solver 产生。15 次预热，三轮各 30 波，交替次序，均使用默认 CPU 原始 ACK 上传，不借 GPU journal 减少调用。

131072：

| 指标 | relative 三轮 | predicted 三轮 |
|---|---|---|
| CPU 累计提交 p50 | 2.558 / 2.247 / 2.277ms | 2.336 / 2.306 / 2.280ms |
| CPU 累计提交 p95 | 2.966 / 4.044 / 2.769ms | 2.944 / 4.397 / 3.430ms |
| worker 工作均值（线程墙钟之和） | 3.915 / 3.942 / 4.584ms | 3.523 / 3.766 / 4.090ms |
| 未压缩正文/波 | 约 458KB | 约 367KB |
| 正文读回/波 | 8MiB | 8MiB |
| 包数及 GPU ACK dispatch/波 | 256 / 256 | 256 / 256 |
| 渲染线程分配/波 | 约 57.5–57.6KiB | 约 57.6–57.9KiB |

新模式正文减少约 19.8%，编码工作均值下降；但 CPU p95 一轮相近、两轮更差，不认定渲染线程提速，不切换默认模式。两份字节基准的 body 排序/采样不同，不应直接相减：网络模型按连续 ID 打包，实际 GPU reservation 及 1MiB 分片没有这种排序保证；真实管线还包含浮点物理坐标量化。loopback 队列/worker/driver 抖动不代表真实服务器网络 RTT。[管线汇总](package-delta-predicted-pipeline-2026-10-01.csv)、[全部样本](package-delta-predicted-pipeline-samples-2026-10-01.csv)。

## 验证和下一步

完整 GPU 回归通过 16,667,575 项断言；JUnit 62 个 suite、314 个测试通过，build 和 Sable 缺席加载通过。新增覆盖 1/63/64/65/131072 个候选、六次不同位移与独立字段变化、超量分段、取消、不等运动步、旧 ACK、body 继续运动、资源成功/失败重建、初始化清空及精确 Java decoder 对照。65 候选通过实际服务端原子提交→观察日志路径；131072 通过实际 channel/journal/mailbox/GPU ACK。确认网络组件限额时按最多 16384 个 GPU ACK 记录分片，不能用测试直接提交更大的快照。

下一步完成原生观察者位置消费与权威重复流抑制、预测误差调度及真实连接全方向对照；继续解决真实管线 p95。视觉/游戏客户端验收由用户执行，尚无整帧 131072 活动包裹结论。

```powershell
.\gradlew.bat validatePackageGpu benchmarkPackageBatchNetwork -PpackageNetworkFraming test build --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
.\gradlew.bat benchmarkPackagePredictionPipeline --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```
