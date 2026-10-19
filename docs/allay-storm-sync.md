# Allay Storm 同步协议

当前运动、8192人Dome、集结反馈与协议11布局见 [协议11交付说明](allay-storm-v11.md)。历史版本段落用于追溯。

状态：🟡 当前核心协议11；服务端权威、持久化和客户端GPU镜像已实现，客户端与服务端须同步更新。多客户端实机与长期压力由用户验收。

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
  -> ClientboundStormStatePacket（定义、dead、dimension、epoch）
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
| S→C | `ClientboundStormWavePacket` | 天穹／双螺旋名单、目标、路径和阶段时间 |
| S→C | `ClientboundStormColumnPacket` | 独立单柱：dimension、epoch、ID、冻结落点、集合／阶段时间和64名成员 |
| 双向 | `StormAttackCompletedPacket` | dimension／epoch／攻击ID／成员rank完成位图，指定反馈客户端合并提交，服务端幂等确认；观察者快照补发 |
| C→S | `ServerboundStormPositionsPacket` | authority client 的位置快照 |
| C→S | `ServerboundStormHitPacket` | GPU 命中候选：dimension、epoch、member、attackSeq、绝对 contact.xyz；不携带伤害等战斗值 |
| C→S | `ServerboundStormWaveContactPacket` | dimension、epoch、攻击ID、采样时间、成员及绝对位置；单柱仅绽开，其他攻击沿用各自有效阶段 |

包处理必须在主线程执行；收到的 storm/member index、count、HP、坐标范围和玩家归属都要校验。近战候选由服务端验证活动身份、活成员、有限数和递增序号，保留每tick一次／滚动20tick最多12次的有界处理；移除采样时效、历史眼位、重复距离和方块射线检查。客户端显示与点击使用最新已完成快照保存的射线及命中距离，并保留一次本地遮挡检查；伤害、暴击、击退、附魔和冷却使用服务端状态。成员近战仍沿用无成员无敌帧的既有设计，风暴对玩家的伤害遵守原版无敌帧。

## 4. 位置与 authority

首个激活/具备可用 GPU 读回的客户端成为 authority；服务端记录其玩家归属。authority 按 `stormCorrectionHz` 发送 near-player 成员位置，不发送全量常规 tick 数据。authority 失效后服务端重新选择并通过 state/center 重新绑定，不能继续接受旧玩家的快照。

客户端位置校正是 soft correction，不应瞬移成员；校正丢失时继续按确定性轨道运行。快照只携带明确的 generation/成员身份，旧快照不能覆盖新 storm。

## 5. 持久化

storm 作为每个 `ServerLevel` 的 attachment 保存。持久化字段包括 active、anchor、count、seed、growth law、创建时间、dead index 列表和稀疏 HP；位置不保存。重启后从同一 seed、count、clock 重建视觉，dead/HP 继续一致。

## 6. 配置与命令

服务端文件为 `config/createmanaindustry-server.toml`，相关键：

`allay_storm.stormCorrectionHz`、`stormMaxCount`、`stormGrowthPerSecond`、`stormHelixInterval`、`stormDomeInterval`、`stormStarfallInterval`、`stormStarfallChance`、`stormStarfallColumnMembers`、`stormStarfallMaxActiveColumns`、`stormWaveDamage`、`stormWaveRange`、`stormChaseY`、`stormDomeChance`、`stormDomeMemberFraction`、`stormDomeMaxMembers`、`stormHelixChance`、`stormHelixThrustSpeed`。

命令（权限 2）：

```text
/cmi particle allaystorm [count]
/cmi particle allaystorm stop
/cmi particle allaystorm status
```

硬上限为 131072 个成员；count 是初始数量，storm 可按 growth rate 增长至 ceiling。

## 7. 验证重点

- 单客户端、双客户端和 authority 离开/重选。
- 重启、停止/重开、换维度、退出重进。
- 伤害、死亡广播、HP 恢复、重复 packet、越界 member index。
- 高延迟/丢包下位置平滑，不出现旧 storm 或重复成员。
- GPU 不可用时命令和网络状态仍安全，且不会阻塞服务端 tick。

星冠落阵 AI 和路径见 [allay-storm-ai.md](allay-storm-ai.md)；GPU 模型粒子见 [particle-engine-dev.md](particle-engine-dev.md)。

## 协议9：当前实现（2026-10-18）

核心注册版本8→9，必须同时升级两端。`storm_hit` 的原sampleTick字段改为VarLong attackSeq（从1递增），其余风暴消息字段保留。客户端已完成GPU快照不再因镜头角差／4帧读回差被拒绝，不用新镜头重构旧命中点，身份失效时清理，成功消费一次。

风暴分支调用本地双参数swing预测，绕开LocalPlayer发送挥手包的单参数重载。服务端先读冷却并结算武器／附魔／事件，再重置冷却及广播挥手。Allay代理按玩家／维度延迟复用，攻击前清理临时战斗状态，离开跟踪时释放。

区域接触的sampleSec保留用于诊断，不作为服务端准入条件。Starfall／Helix／Dome信任GPU实际触地和接触阶段候选；服务端验证活动维度／epoch、攻击ID、成员名单／存活、有限坐标和按玩家／成员／攻击去重，已中止或已释放攻击拒绝。不再维护玩家历史、不重复扫描地形、轨迹包络或遮挡。仍调用原版hurt处理护甲、盾牌、吸收、图腾和无敌帧。圆环128粒子完整性仅用于视觉统计，不再决定伤害资格。

专用碰撞域与弧长／高斯缓存是客户端本地数据，不增加逐帧位置网络流。详见 [协议9实现与验收](allay-storm-v9.md)。以下协议4／6／7／8是历史记录，其中历史校验与旧轨迹已由本节替代。

## 历史星冠 / 天穹 / 双螺旋协议（2026-10-11）

核心网络注册版本从 3 升至 4，服务端与客户端需同时更新。波次包仍使用 `storm_wave`，事件分为 START / UPDATE / LOCK / ABORT；开始和新观察者快照包含完整、按显式组边界排序的身份名单，目标/路径更新与锁定仅发送紧凑参数，不重发名单。

世界坐标为 float 绝对坐标；名单为有界 VarInt 身份数组，路径最多 6 点、星柱最多 32 柱、每柱最多 4096 人，校验有限数、尺寸与重复身份。代标识由持久化创建时间和 storm seed 共同生成，同 tick 重建也不共享旧事件。客户端锁定后拒绝滞后的 UPDATE。包内显式携带攻击类型和阶段；天穹使用六瓣加顶冠七组、中心、朝向、顺逆时针和同步时刻，观察者不从星冠落点数组猜测锁定状态。

`storm_wave_contact` 现在包含 wave ID、64 位代标识、采样秒数、member index 和绝对 contact.xyz；不再以移动中心重构接触坐标。服务端使用有界 41 tick 位置历史接受最多两秒延迟，星冠仅对自身目标结算，天穹对冻结区域内所有接触玩家结算；按受击玩家、波次、成员去重，通过校验后才执行伤害。天穹只接受当前收拢瓣，不使用星冠圆环的准入条件。其他位置/伤害/近战包的原有坐标约定保持原样。

GPU 成员归属表与参数使用双 bank，身份阶段使用同代双 bank；compute/native 使用 SSBO，Iris 模型使用同 backing 的 TBO。圆环还有客户端资源代令牌，旧圆环即使碰巧与新攻击 wave ID/slot 相同也只会淡出。帧准备失败不发布新代。天穹复用同一域，未增加固定 buffer 或 sampler；实现与证据见 [天穹合围](gpu-dome-2026-10-11.md)。

客户端激活仍由现有服务端扫描维护。除风暴中心范围外，锁定天穹的固定区域也能激活/维持附近观察者；原目标离开、风暴中心移走后，区域快照和接触上报仍有效。没有新增世界监听器。

HELIX=2 使用两组真实臂名单、冻结曲线和 48 点弧长表；开始/观察者补同步带名单，锁定后不更新路线。区域激活与按玩家去重也覆盖 HELIX，接触包坐标为实际扫掠交点、时间为插值采样时刻。ABORT 携带原因，碰撞未就绪或逃生搬离不伤害。数据域维持 312 words/波次及原 SSBO/TBO 绑定；就绪变化仅更新 4 字节 header。见 [双螺旋记录](gpu-helix-2026-10-11.md)。

## 协议 6 与实现审查修复（2026-10-18）

该次修复将核心注册版本从5升级至 **6**（当前为9）；客户端和服务端必须一起更新，旧协议不能混连。

- `storm_state` 的四种动作均携带 dimension + 64 位 epoch。ACTIVATE 只匹配客户端当前维度，其他动作同时匹配当前活动身份；离开旧维度的服务端 track 直接移除，不向新维度发送清理。
- `storm_hit` 只发送命中候选，不再发送伤害、击退、暴击或粒子参数。位置采用绝对 double 世界坐标，时间采用原始 game tick，避免移动中心重构偏移。服务端保留 41 tick 眼部位置，接受最近 40 tick 和最多提前 2 tick 的采样；距离上限为服务端交互距离 + 0.5 格，检测方块遮挡，同一采样不能重放，同玩家每 tick 最多一次、滚动 20 tick 最多 12 次。
- `storm_damage` 携带 dimension + epoch、绝对 double 命中位置，以及服务端计算的有限 float 伤害/击退。攻击者也从确认包生成战斗粒子，伤害音效由服务端发送；客户端只预测挥手。请求必须先于挥手发出，因为该版本 ServerPlayer.swing 会重置攻击冷却。服务端调用真实物品及 NeoForge 攻击/暴击钩子，耐久、附魔后置效果、统计和饥饿消耗仅在结算路径执行一次。
- NBT 的 CreationRadius 按半径恢复；成长完成不丢弃原冻结增长参数。非法/缺失增长参数按保存规模冻结并在加载时重新建立相位起点，成员身份、死亡集合和 HP 保留。
- 客户端槽表优先回收过期槽、使用空槽，满槽时按编号回收 ABORT 槽；不驱逐有效攻击。未回收的中止波次仍淡出 0.25 秒。替换时重新建立客户端表现与反馈身份。

这次没有新增服务端成员运动模拟：服务端仍不能独立证明客户端所报成员实际处于命中点；该候选信任边界保留。稀疏位置校正包的格式不属于本次修改范围。

自动验证与用户客户端验收步骤见 [四项修复记录](allay-storm-review-fixes.md)。

## 单柱星雨协议7（2026-10-18）

新增 `storm_column` 独立生命周期消息，携带dimension／epoch，开始与观察者同步含64名精确成员，中止为紧凑事件。4个大招槽与64个星柱槽独立；GPU归属和圆环身份编码扩展，所有原生／阴影／Iris路径读取同代提交，取消按32位掩码寻址星柱。

接触包新增dimension。单柱是冻结区域攻击，触发者离开不取消，附近每名活动玩家报告自己的接触。仅GPU确认实际集合、触地及绽开，碰撞可用且预警圈完整时产生候选；服务端验证名单、存活、时间、4格接触边界、抛物线包络、历史玩家位置、地形和遮挡，再按玩家／成员／柱去重结算。没有增加服务端成员位置模拟。

详细迁移及验收见 [单柱星雨](starfall-columns.md)。以上历史协议4／6段落保留供排查旧版本，不代表当前Starfall调度。

## 环绕星柱协议8（2026-10-18）

核心版本由7升至8，两端同步升级。消息字段布局保留，新的环绕轨迹、每成员0.06秒名义错峰与接触时间窗口必须一致，拒绝旧版混连。GPU在实际接地后立即开始该成员的独立抛物面展开，不等待名义抵达时刻；服务端以集合高度和6格／秒速度给出物理最早接触下界，继续验证2秒采样时效、地形、4格范围及抛物面。GPU实际位置与触地仍为既定候选信任边界。

连续渲染时钟和稳定MODEL飞行姿态仅用于客户端运动与表现，不新增成员位置同步、服务端成员位置模拟或额外GPU状态域。每成员记录仍为16 words，触地点占用原保留字段。

## 协议10（2026-10-19）

Helix线数据包含独立单位轴向量及模板版本，避免远坐标相减造成方向抖动，并验证固定4096名单、64格长度与32格外包络；targetArc表示冻结终止平面的轴向距离，支持任意三维轴线。完成位图不含位置，按身份和成员当前归属幂等释放；客户端本地立即交给普通归队，服务端反馈丢失以同步最晚截止时间回收。独立调度、0.5秒短回流及配置迁移详见 [协议10交付](allay-storm-v10.md)。

## 协议11（2026-10-19）

Dome改为单组8192名单，包含模板版本与冻结中心。新增C2S `storm_attack_gathered(dimension,epoch,id)`，权威GPU连续三帧就绪后报告；服务器在有效身份下立即LOCK，超时中止。完成位图最多128个long，覆盖rank8191；GPU大招256 words/槽、星柱2 words/槽，共1152 words。Dome同样逐成员释放并普通归队，观察者快照和独立碰撞覆盖保留。详见 [协议11交付](allay-storm-v11.md)。
