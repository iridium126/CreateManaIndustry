# GPU 包裹与 Create 原生同步 payload 对照（2026-10-01）

本报告保留 v2 原绝对位置流的结果；随后 v3 引入 GPU 确认基线残差与包内编码，结果及异向输入未通过项见[后续报告](package-batch-network-2026-10-01.md)。旧表不会被新格式的部分结果覆盖。

**历史测量：原 20Hz 自定义绝对位置协议未通过当时的“总带宽不大于 Create 原生”门槛。** 即使假设完全停止原生重复同步，此次 131072 个持续移动包裹仍产生原生的 3.34–3.43 倍 payload，保留原生同步时更高。用户后来将带宽优化及倍率验收移出本次目标；`PackageNetworkBudget` 已改为状态诊断，不再阻止权威就绪或观察订阅。当前沿用后续验证的 relative 增量、批量 ACK/控制和原生观察者方案；此处数字仅作为未来带宽专项的历史基线。

## 方法

Create 源码 `.refs/Create/src/main/java/com/simibubi/create/AllEntityTypes.java` 将 PACKAGE 注册为 `updateFrequency=3`，注册 helper 调用 `setUpdateInterval(updateFrequency)`。对照直接执行 Minecraft `ClientboundMoveEntityPacket.Pos.STREAM_CODEC`，新增流直接执行生产 `ServerboundPackagePacket` 和带时间的 `ClientboundPackageObserverPacket` codec；没有用手算替代实际编码。Create 的额外即时速度/落地/传送等更新未包含，三 tick 不是所有状态变化的固定发送规则。

输入为 10000、65536、131072 个成员，连续 60 tick（3 秒），速度恒为 1 block/s，ground/yaw/velocity 不变，区域内只有 X 坐标每 tick 变化。原生每三 tick 发送一次相对位置 short；自定义权威上行每 tick 发送 POSITION 绝对量化值，每批 2048；观察下行每 tick 每批 64，与服务端轮询批次相同，使用 v2 公共时间/共享 age 模式。包裹实体 ID 为 1..N，区域局部索引为 0..N-1。比较总量覆盖一个权威客户端加 0/1/3 个观察客户端。每个新增字节与保留原生流都纳入相应列，不把上行成本转移后忽略。

这是一组可重复的**未压缩 payload body 字节对照**，不是 socket 测量或时延基准。它不含 packet ID、framing、Netty/Minecraft 压缩、初次 spawn/接管基线、ACK、心跳、订阅/迁移控制、速度变化、玩法事件和锁链；不声称是实际带宽倍率。压缩会改变比较，完整验收必须重新测实际连接。原始[CSV](package-network-payloads-2026-10-01.csv)保留全部 9 组结果。

## 结果

下表为一个权威客户端和一个观察客户端，十进制 MB/s；总计两条方向，除以相同的三秒逻辑时长。

| 包裹数 | 原生到两个客户端 | GPU 上行 | GPU 观察者下行 | GPU 总量（假设原生完全停止） | GPU + 保留原生 | 假设停止原生的倍率 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 10000 | 1.198 | 2.198 | 2.310 | 4.508 | 5.706 | 3.76 |
| 65536 | 8.518 | 14.406 | 15.149 | 29.555 | 38.073 | 3.47 |
| 131072 | 17.256 | 28.811 | 30.304 | 59.115 | 76.371 | 3.43 |

前述 3.34–3.43 倍对应 131072 的 0–3 观察客户端；较小 ID 的原生包更短，10000/65536 组的倍率更高。仅去除重复同步不足以解决当前差距。GPU 上传从 128B 缩减到 64B 的收益是 CPU→GPU 成本，不代表这些网络 payload 减半。

## 下一版和验收

优先评估把原生追踪下行直接消费到观察者 GPU 状态，复用实体生成、身份、数据和位置协议；新增上行需以已确认基线编码相对轴变化，并按原生发送阈值/节奏及预测误差选择记录。物理频率独立于网络频率。任何 cadence、阈值和校正参数均需视觉和玩法验证；交互、停止、速度、生命周期、精确事务不能因压缩或位置未变被遗漏，也不能为减少字节超过额外两 tick 延迟。锁链继续采用轨道/速度/时间基准，不使用全成员逐 tick 位置下行。

门禁开启前必须在同场景、相同玩家数量/可见范围/包裹行为及压缩设置下测量：两端 socket 总字节、每连接上下行、冷启动/迁移基线与控制、保留原生流，以及平均和峰值速率。覆盖活动地面、密集堆叠、链上和混合负载，10000/65536/131072/容量边界，预热后三次；静止或大量回退不能替代活动验收。总量须不高于原生，同时满足视觉、库存守恒、精确身份、额外交接处理延迟和全帧/tick 性能标准。

```powershell
.\gradlew.bat benchmarkPackageNetworkPayloads --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```
