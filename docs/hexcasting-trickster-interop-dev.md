# Hexcasting / Trickster 互操作开发契约

更新：2026-10-17。实现及自动验证见[整改报告](hexcasting-trickster-interop-review-2026-10-16.md)。真实客户端验收由用户执行，未标为通过。

## 分层及可选依赖

双模组实现位于 `com.iridium126.createmanaindustry.compat.hextrick`：

| 包 | 职责 |
| --- | --- |
| `data` | 不可变法术快照、TrickIota / IotaFragment、Inline、结构 adapter、有界编解码 |
| `execution` | 双向调用、来源恢复、预算、范围、executor codec、付费提交副作用 |
| `execution.circle` | 当前石板晶结访问、事务和交互 |
| `conversion` | 值转换、媒质与魔力兑换及整数规则 |
| `bootstrap` | 注册、启动、Mixin ABI 契约 |

`compat.hexcasting` 保留只依赖 Hex 的实现，`compat.trickster` 保留 Trickster 原生能力和伟大戏法。入口及 Mixin 继续按模组存在性 gate；不要让单模组注册类静态引用双模组类型。药水片段仅在存在当前桥接解码预算时使用共同的 NBT 边界，Trickster 单模组仍可加载。

Mixin 负责挂接原生调用点，业务规则放在上述运行时或对应单模组服务中。Partition 的查询索引和观察者同步分别由 `PartitionIndex`、`PartitionSynchronizer` 管理。CastChains 管效果所有权；预算根不能复用 CastChain ID。

## 来源、物品与恢复

`CastingOrigin` 冻结一次圆调用的世界、石板位置、朝向、范围和绑定玩家 UUID。`CircleSourceDescriptor` 保存维度，以及促动石、调用石板、有序供能石板的坐标、方块实体注册 ID 和 UUID。持久数据不含活动玩家、方块实体、Storage 引用或可花费余额。

石板/促动石 UUID 正常保存及加载保持不变；放置事件为重新放置的方块分配新 UUID，包括携带复制 NBT 的方块。晶结替换不改变石板身份，容量及余额始终读取当前物品。

`BridgeSpellExecutor` 包装原生 DefaultSpellExecutor，并保存 circle 标记、来源和预算。原生玩家管理器传入 PlayerSpellSource 后，包装器仍在运行前重建 `AnchoredCircleCastEnv` / `CircleSpellSource`，不会转向玩家背包供能。

| 来源状态 | 行为 |
| --- | --- |
| READY 且玩家在线 | 在冻结的圆上下文运行；玩家提供身份、物品访问和 Anima |
| WAITING 或绑定玩家离线 | 不执行指令、不推进 delay、不扣款；保存仍保持等待状态，无自动超时 |
| INVALID | 终止这一 executor，释放自身和 delegate 的效果 owner，独立调用继续 |
| 元数据损坏 | 拒绝恢复，不使用玩家来源兜底 |

玩家在线跨维度时允许原石板运行。身体和哨卫坐标只在同世界时参与圆的范围判定；实体专用操作保留原生限制。反向 Hex 继承圆的范围、图案策略、促动石媒质、颜料和事故环境。伟大戏法保存相同来源，客户端端点区分实体和石板。

无绑定玩家的圆及组构台桥接仍使用临时方块队列，每源五槽，卸载/重启取消，最终后台结果丢弃。不要误将此队列改为持久队列。

## 根预算与所有权

`BridgeExecutionContext` 的根预算含 root UUID、Hex 累计数及实际环境上限、Trickster 当前 tick 计数、是否发生互调和瞬时跨语言深度。保存恢复及兄弟 executor 通过服务端根注册表复用预算；独立根调用互不混用。预算注册表使用弱引用并在服务端停止时清理。

Trickster 在进入新服务端 tick 时恢复当刻额度；Hex 累计额度不随 VM、executor、交接或恢复清零。原生 TickData 的计数仍可观察。递归发生前预留一次原生指令，再扣除结果中的预付部分，防止递归先于计数和包装层重复计费。Hex 总操作或跨语言深度超限结束相应调用；Trickster 当刻额度耗尽使用原生续跑。

配置 `hexcasting.bridgeMaxDepth` 默认 32，可配置 1–64。Hex / Trickster 原生上限继续取各自配置。纯语言执行不添加桥接调度器，不为跨语言结果建立返回协程。

新挂点必须检查原生指令位置、保存字段、取消/队列满/异常释放和尾递归。`HexInteropContracts` 及 `HexInteropAbiTest` 针对 Hexcasting `0.12.0-devel-pre-53`、Trickster `2.0.0-beta.56`；升级依赖时先审计契约，禁止静默禁用部分挂点。

## 有界数据入口

`BridgeFragmentCodec` 是片段 NBT、网络、Inline、HexParse 和快照复制的共同入口，按当前原生协议 3–8 解析，不对未知协议回退到无界解析。压缩完整性、CRC、长度及尾随数据均检查。

| 资源 | 一次完整解析上限 |
| --- | --- |
| 编码输入 | 2 MiB |
| 累计解压 | 8 MiB，嵌套压缩共享 |
| 结构深度 | 256 |
| 节点 | 64,000 |

集合、字符串、字节及 NBT 数组在分配前检查长度。`BoundedIotaNbt` 解析内层 NBT；已知实体名称的字符串化 JSON 在原生组件 codec 前检查同一结构预算；药水的内层 gzip 也累计在同一预算。Hex 自身 iota size/depth 检查独立保留。编码输出也在写入时限制。

错误类别为 ENCODED_LIMIT / DECOMPRESSED_LIMIT / STRUCTURE_LIMIT / UNSUPPORTED_PROTOCOL / TYPE_MISMATCH / CORRUPT_DATA。入口适配：NBT 返回 garbage，网络抛解码错误，Inline 显示损坏占位，HexParse 返回导入错误。损坏数据不能变成有效空法术。

第三方未知片段按不透明叶保留并原样往返。外层仍有资源限制；其自定义 decoder、隐藏子结构及真名引用不在完整检查保证内。这是已确认的兼容性例外。

## 扩展结构与转换

在注册期调用 `FragmentStructures.register(Class, Adapter)`，实现 `children`、`rebuild`，按需实现 `units`、`references` 和 `visitReferences`。SpellPart 的 glyph/subParts、列表、映射键值和已知引用共用这一结构契约。未知类型不自动展开。

`FragmentConverter.registerIota` / `registerFragment` 注册值转换。两类注册表在 LoadComplete 冻结，重复或过晚注册明确失败。结构 adapter 精确匹配类型；转换保留先精确后可赋值匹配顺序。扩展不要在 tick 中修改注册表。

显式 `visitChildren` 只在子项实际改变时重建；未改变保持对象身份。不额外执行 Trickster 的 ephemeral 清理，不自动解包未知片段。快照缓存统计与叶引用描述，返回法术及字节均为独立对象。

保留注册 ID 和文本交换入口。自定义来源、executor 元数据和必要同步协议为当前格式；项目未发布，不提供旧 CMI 格式迁移。

## 付费及诊断

`InvertedPayments` 只在事务内预留物品/媒质/健康额度；快照保留完整组件、数量和累计预留。最终提交由 `MediaPaymentEffects` 一次执行统计、进度、伤害和同步；回滚不发布这些副作用。保留原生媒质优先级、透支资格和公式。

范围统一由 `CastingRanges` 和来源上下文判断，分区费用由注册 ID 对应的显式 handler 决定。桥接结果提供稳定 failureReason；玩家、圆和组构台使用现有消息渠道。WAITING 不逐刻输出日志；普通桥接失败继续后续 Hex 的语义保持不变。

## 用户执行的真实客户端验收

以下为待执行项目；自动 GameTest、模拟观察者和专服重启探针不能替代它们。

1. 从项目目录启动本地专服：`.\gradlew.bat runServer -I scripts/hextrick/live.init.gradle -PinteropPhase=write --offline --no-configuration-cache`。服务仅监听 `127.0.0.1:25576`，隔离存档在 `build/hexinterop-live/server`。看到 WRITE_READY 后保持运行供客户端连接；write 会重置测试坐标处的晶结和账目，请只使用此隔离存档。独立的重启账目验证才采用 write、正常 stop、read 的顺序，每次重新验证均从 write 开始。
2. 另一个终端启动客户端：`.\gradlew.bat runClient -I scripts/hextrick/live.init.gradle --offline --no-configuration-cache`，自动连接。开发脚本补齐客户端 Sodium 和 Owo；未执行图形端验收，若启动失败保留日志。
3. 在服务端控制台执行 `op InteropAcceptance`，登录后等待场景生成，必要时 `/cmiinterop scene`。检查聊天中的正常 Inline 法术树和损坏占位、镜面物品及镜面 UI，往返编辑/保存后仍能查看并执行。
4. 检查同命牛与石板端点、分区边界显示；执行 `/cmiinterop cross` 与 `/cmiinterop back`，确认仍以原石板为端点，没有跨世界玩家错误连线。重连后重复检查。
5. 在正常玩法中启动一个带延迟的绑定圆互调：离线再上线、卸载再加载来源、更换晶结、拆除并重放来源、手动取消。核对暂停无扣款、同身份恢复、替换终止、新晶结按当前余额结算；独立法术不受取消影响。
6. 持有未完成法术时正常 `stop` 后重启，检查原槽位和账目；再验证无绑定圆的临时队列确实取消。记录客户端/服务端日志、实际余额与观察结果，不把尚未运行的项勾为通过。

自动验证、性能和重启探针命令见[测试入口](testing.md)。性能数据只解释被测输入，不能推导真实客户端帧率或整服吞吐。
