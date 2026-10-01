# 包裹 v3 GPU 残差与包内编码对照（2026-10-01）

**已降低上行字节，但尚未通过总带宽和完整游戏验收。** 新增 `PackageBatchDeltaCodec` 与 GPU 相对已确认基线的位置残差，实际服务端原子提交、观察日志、GPU ACK 均已接入。量化、20Hz 物理、发送时机、64B 读回记录、稳定身份及精确事务不变。当前 `PackageNetworkBudget` 仍关闭两端权威就绪和观察订阅，不能将本报告的部分输入当作带宽验收。

## 编码与同步契约

可选权威 registrar 升为 `gpu-packages-3`；保留参考 DELTA=5，新增 BATCH_DELTA=6 和 RELATIVE_DELTA=7。v2 对端不能协商 v3。5 使用原 absolute codec；6 使用包内预测的 absolute 值；7 的 POSITION 是相对该成员最后服务器确认基线的整数差，再做包内预测。其他字段仍是 absolute。释放没有 pose，不与位置掩码组合。

包内预测器每包清零，只看本包前一条带对应字段的记录。连续局部 ID 隐式，稀疏 ID 精确逃逸；header 位声明位置轴，速度另有三位轴选择器；只写不同轴的 signed residual。包括整数极值在内的差值使用 long 计算，解码检查范围、canonical varint、声明数量、非法 mask/axes 和 short 溢出。不量化或截断，也不依赖前一包的呈现状态。各包可独立解码；顺序/epoch/身份仍由原服务器和 ACK 门禁控制。

`PackageDeltaGpu(..., true)` 在原 dirty 判断后计算位置差。flight 固定原确认基线，ACK shader 仅在完整身份和 stamp 匹配时加回，绝不从后来移动的 body 取状态。取消不改基线；旧 ACK 不得二次加回或释放新 flight。uniform 模式在 epoch 内固定，程序重建先全部验证，用 program-uniform 初始化而不切换外部当前程序。channel 核对 GPU/transport 模式；worker 复用 primitive writer 和排序缓冲，仍不按包裹创建 Java pose 对象。每 512 条最坏保守 wire 分配 20482B，低于 envelope 24576B 限制。

服务端 `deltaRelative` 先按各成员的精确 acknowledged state 恢复绝对值，再执行原资格、区域、速度、位移及整批 apply/rollback/lease 提交。失败后缀不发布前缀；退休索引的迟到记录不阻断其他合法成员。观察日志转换为绝对字段，不能把残差发送给旧观察者。物品内容、地址和副作用规则未修改。

## 原生字节对照

真实 Minecraft `ClientboundMoveEntityPacket.Pos` 和新旧 CMI envelope codec，60 tick、10000/65536/131072、每包 512。CMI epoch 是完整 64 位值，序号按真实逐包数量增长；每包解码核对所有位置字段。场景包括规则单轴/三轴、分散但同向三轴、分散且各成员具有不同三轴速度；后者防止一致运动带来虚假的普遍结论。三 tick cadence 仅作未启用的对照，默认仍每 tick；当前 lease 时序/交互和预测还未适配三 tick，不能直接改默认值。

以下为 131072 个成员，三秒总 payload MB（十进制），原生一份位置下行为 **25.8842MB**。未包括 packet ID/framing/压缩、ACK/心跳/基线/迁移、观察者、新生成/玩法事件和变化的速度/yaw；不是 socket 带宽，不能证明硬门槛。若未来复用原生观察者下行并安全停止给权威的重复下行，上行应与所替代的原生那一份比较；这些接线尚未实现。

| 场景 | cadence tick | 原 codec 上行 | 包内绝对预测 | GPU 残差 + 包内预测 | 残差上行 / 原生一份 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 规则单轴 | 1 | 86.717 | 24.458 | 8.261 | 0.319 |
| 规则三轴 | 1 | 86.717 | 24.458 | 8.323 | 0.322 |
| 分散同向三轴 | 1 | 86.570 | 77.352 | 8.323 | 0.322 |
| 分散异向三轴 | 1 | 86.843 | 76.576 | 48.591 | **1.877** |
| 分散同向三轴 | 3 | 28.861 | 25.784 | 2.774 | 0.107 |
| 分散异向三轴 | 3 | 28.948 | 25.526 | 17.667 | 0.683 |

20Hz 残差版在同向输入缩小约 90%，异向输入约 44%；异向输入仍超过原生，当前专用观察下行及原生重复同步也未去除。初次引入和控制更不能漏算。下一步需按服务器预测误差调度、合并 ACK/控制并直接复用原生观察数据；不能丢速度/停止/生命周期/精确事件或牺牲额外两 tick 处理期限。

[全部 24 组结果](package-batch-network-2026-10-01.csv)。[仅包内预测的早期对照](package-batch-network-packet-local-before-2026-10-01.csv)使用短 epoch/每波序号，保留供回溯，不与最终 envelope 字节直接相减。原完整 20Hz 下行/保留原生对照见[旧协议报告](package-network-payloads-2026-10-01.md)。

## 实际 GPU→编码→ACK 管线

RTX 4070 Laptop，NVIDIA 581.15，JDK21.0.8。生产 detector、四槽分片、后台 raw encoder、不可变 envelope 构造和 loopback ACK，512 条/包，两个 worker、最多四任务。GPU 合成源每波移动全部成员，不使用静止或回退。10000/65536/131072，每路径每次 15 波预热/30 波采样，三次反转顺序。原 codec、包内 absolute、GPU residual 都采用后台构包和直接 ACK，避免把先前构包线程变化或 ACK 批量优化算作本次收益。

CPU total 是渲染线程多次 capture/pump 的累积提交时间；不包含帧其他工作。worker work 为两个 encoder task 的 wall time 累加，不是其并行关键路径或纯线程 CPU。原始 source 上传、harness 的 1ms polling sleep 均在 CPU total 外；latency 包含这些轮询。没有网络 socket、服务端实体 apply、观察者 GPU、世界物理、实际 raster/Iris 或整帧。此输入是规则排列和同向小幅往返，不代表异向网络输入的 CPU。

131072 的三次范围：

| 路径 | 渲染 CPU p50 ms | 渲染 CPU p95 ms | worker work mean ms | 渲染分配 KiB/波 | wire body MB/波 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 原 codec | 2.207–2.319 | 2.692–3.290 | 3.988–4.092 | 59.617–59.639 | 1.424 |
| 包内 absolute | 2.246–2.294 | 2.552–2.722 | 3.561–3.638 | 59.626–59.642 | 0.409 |
| GPU residual | 2.200–2.346 | 2.578–3.736 | 3.174–3.486 | 59.610–59.640 | 0.138 |

残差 wire body 约减少 90.3%，worker work 降低，渲染分配基本相同。残差渲染 CPU p95 有两次升高，第三次降低，**不认定稳定渲染提交提速，也不据此启用默认生产接管**。完整版本仍读回 8MiB/满波、256 包和 256 次 ACK dispatch；压缩不会自动降低 GPU→CPU 读回或这些 GL 调用。需要继续批次/控制优化并复测。初次初始化费用被预热排除，实际游戏必须单独统计。

[管线汇总](package-delta-batch-pipeline-2026-10-01.csv)、[逐样本](package-delta-batch-pipeline-samples-2026-10-01.csv)。正确性普通 GPU 回归通过 **14,169,968 项断言**，含旧/包内/相对三路径的 65/131072 通道、完整身份、释放和 velocity-only；独立残差测试含负差值、非零基线、容量拆分、飞行中继续移动、旧 ACK、编译失败保留模式。计时轮次连同附加校验共 14,173,667。JVM 60 套件/303 项测试通过，Sable 缺席门禁通过。视觉和实际多人/压缩网络由后续游戏测试证明。

```powershell
.\gradlew.bat benchmarkPackageBatchNetwork --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
.\gradlew.bat validatePackageGpu -PpackageBatchPipelineBenchmark --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```
