# 共享锁链轨道及节点事件微基准（2026-09-30）

硬件为 NVIDIA GeForce RTX 4070 Laptop GPU，OpenGL 4.5，驱动 581.15。固定 16 条共享循环轨道、90 度/秒、0.875 方块半径、固定初始相位；各组至少预热一秒和 30 个物理步，测量 120 步，重复三次。物理步为 0.05 秒，基准按最快速度执行，不是以现实 20 Hz 或 60 FPS 提交游戏帧。

`legacy_loop` 是原有每包裹链路记录的运动、摆动和插值历史。`shared_loop` 增加共享轨道、稳定事件身份、节点状态检查、事件筛选和 ACK。`shared_ports` 每轨道另有四个可预告端口。两个 shared 组使用 GPU 对整批候选作**模拟服务端 ACK**，保留所有包裹的推进；没有执行库存、地址匹配、路由或网络。测量结束逐一验证每个对象的有限状态、未回退标记和实际位置变化，禁止静止或退休对象替代活动负载。

下表是三次重复各自 p95 的最小/最大值，单位 ms：

| 包裹数 | 路径 | GPU p95 | CPU 提交 p95 |
|---:|---|---:|---:|
| 10000 | legacy_loop | 0.012288 / 0.012288 | 0.0009 / 0.0015 |
| 10000 | shared_loop | 0.031744 / 0.031744 | 0.0019 / 0.0021 |
| 10000 | shared_ports | 0.031744 / 0.031744 | 0.0019 / 0.0025 |
| 65536 | legacy_loop | 0.040960 / 0.040960 | 0.0008 / 0.0012 |
| 65536 | shared_loop | 0.064512 / 0.064512 | 0.0018 / 0.0024 |
| 65536 | shared_ports | 0.070656 / 0.070656 | 0.0020 / 0.0024 |
| 131072 | legacy_loop | 0.072704 / 0.072704 | 0.0009 / 0.0012 |
| 131072 | shared_loop | 0.133120 / 0.135168 | 0.0019 / 0.0022 |
| 131072 | shared_ports | 0.162816 / 0.163840 | 0.0016 / 0.0024 |

GPU 使用 `GL_TIME_ELAPSED`，CPU 只计提交阶段，不计 query 结果等待、初始化、测试读回及 Java 断言。GPU 时间约以 0.001024 ms 量化，不能将细小差值解释为收益。首次仅预热 30 步时出现明显 GPU 时钟/冷启动离群点，因此改用上述预热并重测；报告与 CSV 保存的是重测结果。

shared 路径执行了 legacy 没有的可靠事件流程，成本更高。此表只证明新增 GPU 流程的绝对内核开销较小，**不构成等价算法提速证据**，不能替换 Create CPU 与完整 GPU 世界路径的同机前后报告。没有改变无节点的 legacy 预览默认路径。

131072 容量时，单个 chain solver 与事件组件的显式 GPU 缓冲约 81.5 MiB，其中事件组件约 52.5 MiB（包括四个 8 MiB 输出 bank）；这只是按分配公式计算，不是游戏显存实测，不含通用池、模型、mixed publication、Iris、世界碰撞或驱动程序缓存。轨道和节点规模分别为 16 和 64。

本次未测实际渲染、Iris/阴影、网络 RTT/带宽、端口拒绝、多人所有权迁移、完整 server tick 或游戏整帧。真实服务端链上接管与端口/路由事务仍待接入，当前不发送 CHAIN_READY。视觉及客户端测试由用户执行。131072 活动包裹的最终验收仍未完成。

后续加入的 `PackageChainEventChannel` 使用真正的 header/body 四槽读回、后台压缩编码和可靠传输 journal。该 CPU/复制/网络路径未包含在以上样本中；其真实 GPU 回归使用 mock 传输，验证零负载、工作组边界、跨 1MiB 分片、超过四帧 worker 延迟、发送拒绝和 ACK 竞争。这里的 81.5 MiB 也不包括该 channel 的 staging 存储及 CPU journal。不要据此估算真实网络或完整事件提交耗时。

运行入口：

```powershell
.\gradlew.bat benchmarkPackageChain --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```

[三次测量汇总 CSV](package-chain-tracks-2026-09-30.csv) · [全部 3240 个样本](package-chain-tracks-samples-2026-09-30.csv.gz)

PhysX 的自由包裹后端对照见[独立报告](package-physics-backends-2026-09-30.md)；本次链上微基准未将 PhysX 作为同语义链路/节点后端。
