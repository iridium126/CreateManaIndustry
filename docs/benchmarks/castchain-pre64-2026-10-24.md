# CastChain pre-64 快照与 VM 边界优化

日期：2026-10-24。Hexcasting `0.12.0-devel-pre-64`，NeoForge `21.1.236`，JDK 21，隔离 GameTest 服务端，最大堆 3 GiB。本次比较的基线是改造前已迁移到 pre-64 的实现；两轮使用同一 JVM 配置、同一机器和相同负载，均关闭 Hex JIT，以隔离链桥接自身的开销。

## 实现变化

- `CastChainSnapshot` 保存完整、有序、不可变的效果数据；Hex 组件和 Trickster Endec 都采用结构化字段。组件保留 `transient=false`。没有旧存档读取或迁移分支。
- 运行时链只在序列化字段实际改变时失效快照缓存。owners、服务器引用变化不创建新快照；未变目标列表也可复用。
- VM 按服务器、玩家和 UUID 查找 canonical chain，命中后不恢复临时链，不将旧 image 覆盖到存活链上。共享命运和结界的恢复检查继续执行。
- 在 `postCast` 前刷新延迟效果并发布 image；回调接收与 VM 相同的 image。回调内重入与后续修改不会改变已发布快照，返回前补发实际变化。终止或异常释放 owners，并清除属于该链的保存组件。
- 保存服务只替换 CMI 的组件，不解析整份 VM，也不原地修改之前的链快照。Hexcasting 专用来源捕获逻辑独立于来源数据类型，支持缺少 Hexcasting 的类加载。

## 测量方法

`scripts/castchain/benchmark.init.gradle` 启用独立夹具。快照负载预热 20,000 次，每个样本读取 10,000 次；手杖负载预热 2,000 次，每个样本执行 1,000 次 VM 入口。每种负载均记录 7 个样本，表格为中位数。分配量由 `ThreadMXBean.getThreadAllocatedBytes` 测量当前服务端线程，计时使用 `System.nanoTime`。

快照基线使用原有字段将链物化为 NBT，包括 UUID 字符串、目标列表、来源 JSON 和可选结界；优化版本读取缓存的结构化快照。此项测量的是取得可持久化状态的开销，不是编码吞吐量。手杖负载保持一个非空栈，执行无副作用 iota，保留同一会话，分别使用 0 和 32 个真实且在范围内的目标实体。

| 负载 | 目标数 | 基线 ns/op | 优化 ns/op | 基线 B/op | 优化 B/op | 分配减少 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 未变快照读取 | 0 | 341.33 | 20.33 | 1103.48 | 0.00 | 100.00% |
| 未变快照读取 | 32 | 1011.90 | 5.14 | 4840.00 | 0.00 | 100.00% |
| 连续手杖 VM 入口 | 0 | 8299.50 | 6115.70 | 8568.42 | 2968.51 | 65.36% |
| 连续手杖 VM 入口 | 32 | 19028.60 | 5963.10 | 16142.45 | 3000.87 | 81.41% |

手杖入口耗时在本轮分别减少 26.31% 和 68.66%。这是单次、顺序执行的微基准，耗时受 JIT 编译和系统调度影响；快照的两个目标数之间也不宜作耗时比较。零分配仅指预热后的未变快照读取，不包含状态变化、序列化或夹具构造；这些数字不代表整场游戏的帧率变化。

最终实现另以相同预热、样本数和负载运行一次复核，增加了“连续 VM 调用后仍复用原快照对象”的断言，并通过测试。复核结果如下：

| 负载 | 目标数 | 复核 ns/op | 复核 B/op |
| --- | ---: | ---: | ---: |
| 未变快照读取 | 0 | 37.40 | 0.00 |
| 未变快照读取 | 32 | 25.14 | 0.00 |
| 连续手杖 VM 入口 | 0 | 32421.60 | 2968.31 |
| 连续手杖 VM 入口 | 32 | 21994.70 | 2848.21 |

两轮分配结果一致支持消除重复快照构造和恢复：手杖入口分配相对基线减少约 65%–82%。耗时结果差异明显，复核轮还出现相对基线变慢；未控制的系统负载和 JVM 编译因素尚未分离，**不能据此认定稳定的耗时改善**。保留两轮原始结果，不以首轮较好的耗时作为验收结论。复核日志为 `build/castchain-benchmark-verification.log`。

## 复现和证据

优化后执行：

```powershell
.\gradlew.bat runGameTestServer --offline --no-configuration-cache --console=plain -I scripts/castchain/benchmark.init.gradle -PcastChainBenchmarkLabel=optimized
```

本地原始记录：`build/castchain-baseline/benchmark.log`、`build/castchain-optimized-benchmark.log`；汇总：`build/castchain-benchmark-summary.json`。基线源码另存于 `build/castchain-baseline/`，其中 SHA-256：

- `CastChain.java`：`5ebb324dbe6b329fe2d66ba3b277203932eb6cc6832cec8bc101536b41aeff03`
- `HexCastingChainMixin.java`：`4657655d51614cc2dd33bdf47e8159dbe7767c90b2e4963e05672e3058353ddc`

回归覆盖快照缓存与隔离、NBT/网络/Endec 往返、OFF/AUTO 回调、终止与异常清理、同一 VM 重入、陈旧 image、玩家归属、保存字段保留，以及缺少 Hexcasting 或 Trickster 的独立启动。完整测试结果另见本地构建与 GameTest 日志。

最终验证：标准 `build` 成功；818 项单元测试通过，无失败、错误或跳过；完整 Hex JIT 差分与参考法术套件的 4 项 GameTest 通过；Hex/Trickster 的 111 项 GameTest 通过；缺少 Hexcasting、缺少 Trickster 的两个独立启动夹具各通过 1 项测试。构建后的嵌入 mod jar 包含新快照、保存服务、Endec 适配和 Hex 来源捕获类，不包含 GameTest 类。测试日志分别位于 `build/castchain-final-build.log`、`build/castchain-hex-full.log`、`build/castchain-trickster.log`、`build/castchain-no-hexcasting.log` 和 `build/castchain-no-trickster.log`。
