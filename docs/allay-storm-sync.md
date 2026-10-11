# Allay Storm 同步协议

状态：🟡 服务端权威、持久化和客户端 GPU 镜像已实现；多客户端进服、延迟、断线和长期压力仍需实测。

## 1. 权威边界

服务端 `AllayStormData`/`AllayStormManager` 拥有：

- storm 是否激活、anchor、seed、已生成 count；
- growth law（final count、growth rate、创建时间）；
- dead member 集合和受伤但未死亡成员的 HP；
- 波次目标、伤害结算和生命周期。

客户端 `AllayStormRuntime`/GPU 拥有高频位置、速度、姿态和绘制。成员位置不逐 tick 从服务端广播，而是由 `stormSeed + memberIdx + shared clock` 确定性重建；authority client 只上传稀疏位置校正，其他客户端接收校正并平滑应用。

健康成员默认 20 HP；dead index 永不重新生成。storm radius 和角速度由同步的 count/seed 按同一公式推导，不作为独立状态传输。

## 2. 生命周期

```text
/cmi particle allaystorm [count]
  -> ServerLevel attachment 创建/更新
  -> ClientboundStormStatePacket（定义、dead、HP）
  -> 客户端建立 GPU MODEL 粒子
  -> growth / wave / damage / correction
/cmi particle allaystorm stop
  -> Server 停止并广播清理
```

重新执行命令会移动 anchor；已有 storm 的 identity/HP 语义以 `AllayStormManager` 为准。换维度、退出服务器和客户端 level change 必须清空旧 GPU pool 与 runtime，不得把旧 anchor 留在新世界。

## 3. 数据包

| 方向 | 包 | 作用 |
| --- | --- | --- |
| S→C | `ClientboundStormStatePacket` | 初始/重连时的完整定义与稀疏状态 |
| S→C | `ClientboundStormCenterPacket` | 当前追击中心/配置化 chase Y |
| S→C | `ClientboundStormDamagePacket` | 按 member index 广播伤害、死亡/战斗视觉 |
| S→C | `ClientboundStormPositionsPacket` | authority 的稀疏位置校正 |
| S→C | `ClientboundStormWavePacket` | 星冠名单、目标、路径、阶段时间和锁定落点 |
| C→S | `ServerboundStormPositionsPacket` | authority client 的位置快照 |
| C→S | `ServerboundStormHitPacket` | 客户端 GPU hit readback 的伤害事件 |
| C→S | `ServerboundStormWaveContactPacket` | 本地目标与当前下降成员的接触报告 |

包处理必须在主线程执行；收到的 storm/member index、count、HP、坐标范围和玩家归属都要校验。客户端报告只提供候选事实，服务端再次验证距离、状态、冷却和 invulnerability frame。

## 4. 位置与 authority

首个激活/具备可用 GPU 读回的客户端成为 authority；服务端记录其玩家归属。authority 按 `stormCorrectionHz` 发送 near-player 成员位置，不发送全量常规 tick 数据。authority 失效后服务端重新选择并通过 state/center 重新绑定，不能继续接受旧玩家的快照。

客户端位置校正是 soft correction，不应瞬移成员；校正丢失时继续按确定性轨道运行。快照只携带明确的 generation/成员身份，旧快照不能覆盖新 storm。

## 5. 持久化

storm 作为每个 `ServerLevel` 的 attachment 保存。持久化字段包括 active、anchor、count、seed、growth law、创建时间、dead index 列表和稀疏 HP；位置不保存。重启后从同一 seed、count、clock 重建视觉，dead/HP 继续一致。

## 6. 配置与命令

服务端文件为 `config/createmanaindustry-server.toml`，相关键：

`allay_storm.stormCorrectionHz`、`stormMaxCount`、`stormGrowthPerSecond`、`stormWaveInterval`、`stormStarfallMembersPerColumnStep`、`stormStarfallMaxColumns`、`stormStarfallMembersPerColumn`、`stormWaveDamage`、`stormWaveRange`、`stormChaseY`、`stormDomeChance`、`stormDomeMemberFraction`、`stormDomeMaxMembers`。

命令（权限 2）：

```text
/cmi particle allaystorm [count]
/cmi particle allaystorm stop
```

硬上限为 131072 个成员；count 是初始数量，storm 可按 growth rate 增长至 ceiling。

## 7. 验证重点

- 单客户端、双客户端和 authority 离开/重选。
- 重启、停止/重开、换维度、退出重进。
- 伤害、死亡广播、HP 恢复、重复 packet、越界 member index。
- 高延迟/丢包下位置平滑，不出现旧 storm 或重复成员。
- GPU 不可用时命令和网络状态仍安全，且不会阻塞服务端 tick。

星冠落阵 AI 和路径见 [allay-storm-ai.md](allay-storm-ai.md)；GPU 模型粒子见 [particle-engine-dev.md](particle-engine-dev.md)。

## 星冠 / 天穹协议（2026-10-11）

核心网络注册版本从 2 升至 3，服务端与客户端需同时更新。波次包仍使用 `storm_wave`，事件分为 START / UPDATE / LOCK / ABORT；开始和新观察者快照包含完整、按显式组边界排序的身份名单，目标/路径更新与锁定仅发送紧凑参数，不重发名单。

世界坐标为 float 绝对坐标；名单为有界 VarInt 身份数组，路径最多 6 点、星柱最多 32 柱、每柱最多 4096 人，校验有限数、尺寸与重复身份。代标识由持久化创建时间和 storm seed 共同生成，同 tick 重建也不共享旧事件。客户端锁定后拒绝滞后的 UPDATE。包内显式携带攻击类型和阶段；天穹使用六瓣加顶冠七组、中心、朝向、顺逆时针和同步时刻，观察者不从星冠落点数组猜测锁定状态。

`storm_wave_contact` 现在包含 wave ID、64 位代标识、采样秒数、member index 和绝对 contact.xyz；不再以移动中心重构接触坐标。服务端使用有界 41 tick 位置历史接受最多两秒延迟，星冠仅对自身目标结算，天穹对冻结区域内所有接触玩家结算；按受击玩家、波次、成员去重，通过校验后才执行伤害。天穹只接受当前收拢瓣，不使用星冠圆环的准入条件。其他位置/伤害/近战包的原有坐标约定保持原样。

GPU 成员归属表与参数使用双 bank，身份阶段使用同代双 bank；compute/native 使用 SSBO，Iris 模型使用同 backing 的 TBO。圆环还有客户端资源代令牌，旧圆环即使碰巧与新攻击 wave ID/slot 相同也只会淡出。帧准备失败不发布新代。天穹复用同一域，未增加固定 buffer 或 sampler；实现与证据见 [天穹合围](gpu-dome-2026-10-11.md)。

客户端激活仍由现有服务端扫描维护。除风暴中心范围外，锁定天穹的固定区域也能激活/维持附近观察者；原目标离开、风暴中心移走后，区域快照和接触上报仍有效。没有新增世界监听器。
