# 包裹压缩帧与保留原生同步对照（2026-10-01）

**当前网络方案仍未满足总带宽不大于 Create 的要求，门禁继续关闭。** 此测量补齐之前 payload 对照缺少的 custom payload 标识、PLAY packet ID、长度帧及连接压缩。131072 异速运动在 20Hz、压缩阈值 256 下，即使假设停止向权威客户端发送原生位置包，新增上行与 ACK 的合计仍为原生单客户端位置帧的 1.157 倍。实际上尚未停止原生同步；保留它后的合计为 2.157 倍。不能仅凭 ACK 或同速轨迹的压缩收益开启接管。

## 方法与边界

在既有 `PackageBatchNetworkBenchmark` 加入 `-PpackageNetworkFraming`，沿用 10000/65536/131072、四种轨迹、固定整数样本、60 逻辑 tick、512 项批次、完整 epoch 和单调序号。原始 payload CSV 与之前逐行一致。使用 20Hz（当前发送策略）及三 tick（仅假设，不启用）两个变体。v4 ACK 每个成功发送 tick 合并一个精确区间；假设即时确认且所有批次均在同一 tick 接受，没有网络 RTT、拒绝或跨 tick 分片。

从实际 classpath 的 `GameProtocols.class` 读取 PLAY 注册顺序，包括 bundle delimiter 的位置，不初始化未启动的世界 registry。当前解析得到原生 Pos ID=46、serverbound custom ID=18、clientbound custom ID=25。遇到不支持的注册方式应拒绝运行，不猜测 ID。custom payload 使用真实 `CustomPacketPayload.codec` 与显式已知类型，写入完整 payload ResourceLocation 和生产 envelope。

随后调用实际 Minecraft `CompressionEncoder`、`Varint21LengthFieldPrepender`，分别测试关闭压缩（-1）及阈值 256。每个批量帧核对帧长度、解压长度和逐字节正文；小于阈值的原生 Pos 使用精确大小计算，针对每种实体 ID 编码长度实际调用 handler 验证。采用原生默认 zlib 行为，不借用跨包字典。没有 socket 或网络耗时，未模拟 NeoForge 启动协商、其他模组修改 encoder 或打包协议。

CSV 列包含原生位置帧总量、GPU 相对基线上行、ACK、保留所有原生位置流的总量，以及**仅供估算**的“停止权威客户端原生位置下行”总量。分别计算一个/四个客户端，权威只有一个，其余客户端假设继续消费原生位置流。这个停止同步方案尚未实现，不能作为现有代码效果。

本表仍没有接管基线、能力、心跳、迁移、释放、实体生成/销毁、yaw/velocity/内容更新、观察者额外协议、链上 BE/NBT、bundle delimiter 或 TCP/IP/加密开销。实际 Create urgent 更新并非一律三 tick。它是稳态位置流组件对比，**不是总网络验收**；表中的保留流总量也只是这些组件的合计。完整验收必须记录同场景实际连接双方的全部字节与事件。

## 131072 个包裹结果

三秒，一个客户端；GPU 上行 20Hz，原生参考为每 3 tick 的普通位置更新。单位 B；总列包含本次测得的原生位置帧、上行及 ACK。2026-10-01 重跑确认与归档 CSV 相同，表中原生分母已经使用 3 tick，无需重新缩放。

| 轨迹 | 压缩阈值 | 原生位置帧 | GPU 上行 + ACK | 保留原生后的总量/原生 | 假设停止权威原生位置流/原生 |
|---|---:|---:|---:|---:|---:|
| 同向 X | -1 | 31127080 | 8772591 | 1.282 | 0.282 |
| 分散同速 XYZ | -1 | 31127080 | 8834031 | 1.284 | 0.284 |
| 异速 XYZ | -1 | 31127080 | 49101639 | 2.577 | 1.577 |
| 同向 X | 256 | 33748520 | 1123371 | 1.033 | 0.033 |
| 分散同速 XYZ | 256 | 33748520 | 1138731 | 1.034 | 0.034 |
| 异速 XYZ | 256 | 33748520 | 39049425 | 2.157 | 1.157 |

四客户端、20Hz、异速 XYZ、阈值 256：原生位置帧总量 134994080B，保留原生后的合计 174043505B（1.289 倍）；即使停止权威位置下行也为 140294985B（1.039 倍）。更多观察者不能证明协议达标。

三 tick 假设变体的异速 XYZ、阈值 256：单客户端 GPU 上行 + ACK 14809077B（原生位置帧的 0.439 倍），但保留原生后的合计仍是 1.439 倍。该 cadence 超过现有运动状态两 tick 期限，不能直接启用；必须另行实现预测、确认时间、及时交互和精确回退，再做视觉与实际网络测试。

原始 96 行数据：[CSV](package-network-framing-2026-10-01.csv)。完整 GPU 14,171,539 项断言、312 个 JUnit 测试和 build 通过；新增 framing 入口单独通过，所有压缩帧内容检查成功。此处不报告 CPU/GPU 性能收益。

后续实现应复用原生观察者位置流，安全停止权威重复下行，并根据预测误差降低上行。仍需同步速度/yaw/lifecycle 与精确交接，不能仅跳过位置或降低频率后忽略服务端玩法状态。

```powershell
.\gradlew.bat benchmarkPackageBatchNetwork -PpackageNetworkFraming --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```
