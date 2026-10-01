# 包裹 v4 精确批量 ACK 对照（2026-10-01）

已将服务端逐个增量包的 ACK 合并为精确序号区间，减少确认包数量及 payload。**这是历史组件对照，不是总网络带宽验收。** 异速运动的 v3 上行在 20Hz 对照中仍约为原生单客户端位置 payload 的 1.877 倍；该结果留作后续版本参考。本轮已将带宽优化移出验收范围，沿用当前同步方案。

## 协议与实现

权威可选 registrar 升为 `gpu-packages-4`，新增 `ClientboundPackageAckPacket`。v3 不能协商 v4。上行 DELTA/BATCH_DELTA/RELATIVE_DELTA 编码、物理频率、位置精度及 64B GPU 记录不变。

每个 ACK 只带一份维度、区域、完整 authority epoch 和 revision，随后为精确的 `[start,end]` 序号区间。空洞不会被确认；它不是累积 watermark。序号保留完整非负 63 位。单包最多 128 个区间、2048 个实际序号，先检查解码分配及遍历预算。Builder 使用复用的 primitive 数组，满时先成功发送已有快照，再重试新序号，不能静默截断。复用池最多保留 64 个 builder。

服务端只收集成功原子提交或精确重复的已接受批次，在 `LevelTickEvent.Post` 发送；溢出提前发送，不扫描包裹池。无法提交 ACK 时关闭区域并交还 Create。通常新增等待不到一个逻辑 tick，但真实网络延迟、100ms 积压期限及服务端实际 tick 尚未测量，不能宣称游戏延迟达标。

客户端检查世界和完整 namespace，只将 journal 中确实 SENT、仍待确认的序号放入有界 mailbox。GL 工作仍在粒子引擎边界执行；原有 GPU ACK 继续核对完整身份、候选和 flight stamp，读取该批固定快照更新基线。合并网络 ACK **不改变默认 GPU ACK dispatch 数量、正文读回量或物理计算量**。

## 字节测量

`benchmarkPackageAckNetwork` 使用真实 Minecraft/NeoForge `STREAM_CODEC` 编解码旧单条 ACK 和新批量 ACK。60 个逻辑 tick（3 秒），每个上行包对应最多 512 个包裹；分别测试一个/八个区域，以及连续/隔一个序号的确认流。使用完整 64 位 epoch、递增序号，逐批检查新协议往返一致。原始数据：[CSV](package-ack-network-2026-10-01.csv)。

| 包裹数 | 区域 | 确认序号 | 旧包数 → 新包数 | 旧 payload → 新 payload | 新/旧 |
|---:|---:|---|---:|---:|---:|
| 10000 | 1 | 连续 | 1200 → 60 | 43072 → 2213 B | 5.14% |
| 65536 | 1 | 连续 | 7680 → 60 | 276352 → 2279 B | 0.82% |
| 131072 | 1 | 连续 | 15360 → 60 | 552832 → 2279 B | 0.41% |
| 131072 | 1 | 空洞 | 15360 → 120 | 560064 → 35095 B | 6.27% |
| 131072 | 8 | 连续 | 15360 → 480 | 551936 → 17728 B | 3.21% |
| 131072 | 8 | 空洞 | 15360 → 480 | 552448 → 47504 B | 8.60% |

只测 ACK payload；没有 socket、协议外层、压缩、上行姿态、握手、迁移、下行观察者或原生实体/BE 同步。不能将这张表直接标记为“GPU 总带宽不高于 Create”。它也不是 CPU 或 GPU 耗时测量。若一波包跨多个服务端 tick 完成，新协议会产生更多 ACK 包；真实调度尚需记录。

## 验证

完整真实 GPU 验证通过 14,171,539 项断言；JUnit 62 个 suite、312 个测试无失败，`build` 和无 Sable 独立加载检查通过。新增测试覆盖完整 long、乱序插入/合并、区间与序号上限、满槽重试、不可变快照、逐字节截断、非法分配/溢出，以及 65/131072 个候选的实际 ACK 编解码→mailbox→GPU 基线更新。

1025 个候选分成三个 GPU 捕获批次，精确确认第 0 和第 2 批，验证第 1 批 flight 保留、其成员不重发、已确认成员重采样；随后确认第 1 批，所有新变化仍能输出。成员按实际 GPU reservation 结果核对，不能假定工作组输出与候选索引排序一致。旧 namespace、重复 ACK 和未发送序号不推进基线。

后续仍需复用原生观察者流、预测误差调度、权威重复同步安全抑制及实际连接的全部方向总字节对比。下行额外协议和原生同步同时存在时，即使单独上行很小，也不能通过总带宽门槛。

```powershell
.\gradlew.bat validatePackageGpu benchmarkPackageAckNetwork test build --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```
