# 权威包裹原生下行停发与交还（2026-10-01）

新增 `gpu-packages-6` 的 `VISIBLE_READY=9`，仅由客户端成功可见 admission 的回调、发布匹配的 ACTIVE 所有权之后发送。服务端验证连接、区域 owner/epoch、稳定身份/generation、候选索引、lease epoch、最终基线 revision 与当前有效 GPU lease。资源准备和 FINAL_READY 不触发停发；提前、退休、超时及重新接管前的迟到确认均被拒绝。此消息只确认渲染接管，不提交物品或玩法操作。

`PackageNativeDownlinkMixin` 包装实际 `ChunkMap$TrackedEntity.broadcast` 的每个 `ServerPlayerConnection.send`，仅过滤该实体的原生 Pos/PosRot/Rot、Teleport 和 velocity 包。其他观察者、metadata、contents、lifecycle 和交互消息仍原样发送；不取消全局广播，不改变 `ServerEntity` 的共享 `VecDeltaCodec`。状态绑定当前 tracker 和连接对象，断线后的同 UUID 新连接不能复用。常见的单 owner 用一个引用保存，无逐实体哈希表；只有并存的旧 owner 迁移才惰性分配附加 identity set。重新配对/退出跟踪清理对应记录，不扫描所有包裹。

服务端释放 lease 后立即向原可见 owner 发送原生绝对位置和速度，恢复最后已提交状态。该即时位置可能发生在原生两次广播之间，因此单独发送一次 teleport **不足以**修复共享相对编码基线。tracker 保留此连接的偏离标记，将它下一条原生 Pos/PosRot 替换为当前 trackingPosition 的绝对 teleport；原生广播随后推进共享基线，之后才恢复原增量。原生 teleport 本身也能完成这次对齐。Rot/velocity/metadata 不提前清除偏离；发送失败不清除。authority runtime 或配置关闭后 tracker 状态仍保留到对齐/解除配对，不因删除区域丢失。

原生 `removePlayer`、`updatePlayer` 和 `broadcastRemoved` 的 removePairing 调用均已接线。权威连接失去实体跟踪时撤销该实体 lease，后续重配对重新准备；这时不发送无用的即时恢复运动包，原生实体删除/后续生成处理基线。观察者解除配对不撤销其他连接的权威。

正常 GPU 退休现在与失败关闭一样，在移除渲染 claim 之前恢复最近已完成、匹配身份的 GPU 检查点，避免保留实体长期未收到运动包后退回旧接管位置。若已收到精确 RELEASED 后的原生恢复 teleport，优先使用服务端位置并保留其 motion，不让旧 GPU 缓存覆盖；接管确认前仍在途的原生 teleport 不被误认成恢复。pending recovery 只为正在交还的 claim 惰性创建，退休/世界关闭/身份替换清理。所有恢复均不等待新的 fence/worker，不修改 vanilla packet-position codec。

单元验证用实际 `VecDeltaCodec` 展示并修复“立即 teleport 后再应用旧基线增量”的偏移，2048 次大坐标运动/轮换 owner 检查无漂移，永远原生的观察者解码逐项完全相等。另覆盖 metadata、Rot/velocity、失败发送、同时等待的旧 owner、断线重连和清理；真实 region 验证精确身份及 final lease、重复确认、退休/再次接管与心跳不能掩盖过时运动。原生恢复缓存验证精确终止身份及版本，GPU 实际接管 fixture 验证正常退休先恢复再交还、重复关闭不重复恢复，并保留失败帧的旧提交恢复验证。ASM 针对已解析的 Minecraft tracker 验证四个调用点及广播后 codec 更新顺序，检查 claim 先于确认、原生恢复先于 GPU fallback、关闭时 claim 晚于恢复清理；不能代替实际启动的 Mixin 应用和多人游戏验证。

最终完整构建通过；76 套共 370 项单元测试零失败，RTX 4070 Laptop 的完整包裹 GPU 回归通过 17413828 项断言。无 Sable/companion 的独立 JVM 门禁检查通过，`git diff --check` 无空白错误。这些通过项没有运行真实游戏连接，不构成整帧或总带宽验收。

`benchmarkPackageBatchNetwork -PpackageNetworkFraming` 新增路由 CSV，保留之前的 relative/predicted/framing 和假设停发数据。新 fixture 对每个包裹/每次原生位置包执行生产路由类，显式提供已经确认的可见 owner；验证其被 DROP，而观察者的编码字节数仍等于原生。计入一次完整身份的 v6 VISIBLE_READY 包。使用实际协议序列化及 Minecraft zlib/帧长度处理；没有运行世界 lease、游戏网络连接或 GPU 活动场景，不能将该 fixture 的确认前提当作实际接管成功。

131072 包裹、异速 XYZ、三秒、**GPU 20Hz / 原生位置间隔 3 tick**，默认 relative 编码：

| 压缩阈值 | 客户端 | 原生位置帧合计 | GPU 上行 + ACK + 可见确认 + 保留观察者位置帧 | 组件比例 | 判定 |
| --- | --- | --- | --- | --- | --- |
| 256 | 1 | 33748520B | 47028305B | 1.393492 | INCOMPLETE |
| 256 | 4 | 134994080B | 148273865B | 1.098373 | INCOMPLETE |
| 禁用 | 1 | 31127080B | 56949447B | 1.829579 | OVER_BUDGET |
| 禁用 | 4 | 124508320B | 150330687B | 1.207395 | INCOMPLETE |

单客户端、阈值 256：原生重复 owner 位置下行为 0；上行 39045106B、ACK 4319B、一次可见确认 7978880B。旧保留原生流的 relative 位置组件为 72797945B / 2.157071。新 component 降到 1.393492，但还没有覆盖其余 OFFER/PREPARED/FINAL/ACTIVE、能力/心跳、订阅/迁移、物品、受力源、互动/恢复、velocity/rotation/native teleport、bundler、实际连接和传输；不能认定总带宽已通过 1.5 倍。

所有 96 组路由案例为 93 个 INCOMPLETE、3 个 OVER_BUDGET、0 个 PASS；其中 cadence=3 仅保留先前内部比较，不是默认发送频率。未执行真实 GPU 的 fixture 不计活动包裹时间，不用大量 Create 回退、关闭接管或假设压缩节省认定通过。原始数据见 [CSV](package-native-downlink-2026-10-01.csv)。可见确认当前仍逐包裹发送，其控制开销需纳入冷启动/迁移窗口；完整网络采集和控制批处理仍有待完成。

生产门禁保持关闭。后续客户端检查需要覆盖多人中一名 authority 与其他 observer、拾取/伤害/机器交还、region/world/资源重载、断线和同 UUID 重连、从视距退出再进入及协议 v5 对端。整帧/server tick/网络 RTT 不由此序列化微基准推断。

```powershell
.\gradlew.bat test benchmarkPackageBatchNetwork -PpackageNetworkFraming validatePackageGpu build --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```
