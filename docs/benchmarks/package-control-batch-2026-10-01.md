# 包裹接管控制消息批处理（2026-10-01）

本轮测量完成后，用户将网络带宽优化及 1.5 倍验收从当前目标移至后续版本。以下数据归档已有实现，不继续开展新的带宽优化；历史证据缺失/超支不改写成通过。运行时已撤除带宽阻塞，双端实验 opt-in、协议协商、资源覆盖、身份和可见提交检查保留，配置默认关闭。选用原 relative 增量、批量 ACK/控制和原生观察者复制；CPU p95 收益不稳定的 predicted 仍为内部比较路径。

`gpu-packages-7` 新增 `CONTROL_BATCH=10`，将相邻、同 namespace、同阶段 PREPARED/FINAL_READY/RELEASE/VISIBLE_READY 合并，每包至多 256 条。单条仍用原编码，不因等待凑满增加延迟。排序只发生在本组；阶段和 namespace 顺序保持 FIFO。包内索引差分、有符号完整 64 位稳定 ID 差分及 generation/lease/revision 复用都是无损编码，不跨包依赖，不提交姿态、物品或地址。

客户端复用分组和编码暂存；最多 4096 项待办，发送拒绝保留相同已准备消息，暂停后续增量。100ms 超时或容量失败明确交还 Create。server 对整包长度、变长整数、字段及尾部完成验证后，才逐条执行既有身份/lease/阶段资格检查。预算耗尽或回调/发送失败撤销 region；不会静默丢掉已发送的终止事件。满队列/大量回退不用于性能验收。

单元测试覆盖完整 long 身份、极端差分、畸形尾部零回调、非法计数、处理预算、阶段/区域顺序、131072 条唯一确认、单条立即发送、传输拒绝重试、超时和清空。真实 GPU 新 fixture 使用 65 个包裹和稀疏 server 索引，逐阶段检查隐藏/最终/可见 admission；失败提交和未轮询的 fence 不产生控制，成功完成后才合并完整身份确认，槽位唯一、绘制数量一致。完整 GPU 回归通过 **17414764** 项断言；撤除带宽门禁后完整构建及 **79 套 383 项单元测试**通过，无 Sable/companion 的独立 JVM 检查通过。不代替游戏启动注入、实际库存或多人验证。

CPU fixture 使用生产 queue、`ServerboundPackagePacket.STREAM_CODEC` 和精确身份 map 接收器；JDK 21.0.8，同机 32 次预热，三组各 60 样本，逐样本交替旧单条/新批处理顺序。分配量使用 JDK ThreadMXBean。测量合计 queue/编码/解码/身份查找，没有世界资格判定、socket、压缩、GPU；不能据此推断主线程 tick 或 render p95。四种控制动作、共享/不同版本、1/64/256/4096 记录，共 192 组汇总行见 [CPU CSV](package-control-batch-2026-10-01.csv)。

共享版本、VISIBLE_READY 的三组 p95 范围：

| 记录数 | 单条 CPU p95 ms | 批处理 CPU p95 ms | 单条→批处理分配 B | payload B | 发送调用 |
| --- | --- | --- | --- | --- | --- |
| 1 | 0.0017–0.0040 | 0.0021–0.0028 | 328→376 | 30→30 | 1→1 |
| 64 | 0.0151–0.0156 | 0.0108–0.0111 | 16928→1872 | 1920→225 | 64→1 |
| 256 | 0.0740–0.0940 | 0.0247–0.0497 | 40992→5240 | 7808→802 | 256→1 |
| 4096 | 0.6572–0.8098 | 0.2118–0.2465 | 655392→82352 | 126848→12847 | 4096→16 |

单条保留线格式和一次发送，但队列 envelope 多 48B 分配，CPU 差异在微秒级。4096 条不同版本/跳跃 ID 的 VISIBLE 样本：单条 p95 0.3584–0.8045ms，批处理 0.2673–0.6667ms，分配 655392→327472B，payload 112449→61823B；不能把共享版本的压缩收益外推给每个场景。

网络 fixture 使用实际 queue、payload codec、Minecraft play packet ID、zlib 和帧长度；每个合并确认解码后逐条核对身份及版本。位置仍是默认 relative，GPU 上行节拍 20Hz、原生位置间隔 3 tick、三秒。fixture 不运行世界或真实连接，路由前提仍是已成功可见确认；只计位置、ACK、一次 VISIBLE 和保留的其他客户端位置帧，不含初始/OFFER/FINAL/ACTIVE/其他控制、迁移、内容、交互、恢复、velocity/rotation/teleport 或传输开销。原 v6 数据保留，新 [网络 CSV](package-control-batch-network-2026-10-01.csv) 共 192 行，历史严格工具判定 186 项 INCOMPLETE、6 项 OVER_BUDGET、0 项 PASS。

131072 个异速 XYZ 包裹，256 条控制一包：

| 压缩阈值 | 客户端 | 原生位置帧 B | 本组件总帧 B | 比例 | 历史判定 |
| --- | --- | --- | --- | --- | --- |
| 256 | 1 | 33748520 | 39092751 | 1.158355 | INCOMPLETE |
| 256 | 4 | 134994080 | 140338311 | 1.039589 | INCOMPLETE |
| 禁用 | 1 | 31127080 | 49528070 | 1.591157 | OVER_BUDGET |
| 禁用 | 4 | 124508320 | 142909310 | 1.147789 | INCOMPLETE |

单客户端、阈值 256 的一次 VISIBLE 帧从 7978880B 降到 43326B；上行仍 39045106B、ACK 4319B。64 条一包时 VISIBLE 为 526078B、组件 1.172659；真实每帧转换预算决定成组大小，不能保证世界运行时总能凑满 256。未压缩的同场景上行本身已超过此前上限。这些结果是后续版本的比较起点，不作为本次带宽验收要求。

已完成基准的复现命令（仅留给后续比较，本次不继续优化）：

```powershell
.\gradlew.bat benchmarkPackageControls benchmarkPackageBatchNetwork -PpackageNetworkFraming --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```

正确性验证命令：

```powershell
.\gradlew.bat test validatePackageGpu build --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```
