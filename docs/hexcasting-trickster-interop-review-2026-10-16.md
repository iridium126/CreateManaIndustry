# Hexcasting / Trickster 互操作整改报告

初审：2026-10-16；整改更新：2026-10-17。原报告的七组工作已落实到代码、结构契约、自动回归和性能对照。真实客户端连接与显示验收按用户要求交由用户执行，状态为待验收，不能据服务端测试标为通过。

本次以原先暂存的四项数据修复为起点，保留工作区和索引，不重置、不编写旧 CMI 存档迁移。实现约定见[开发契约与客户端验收清单](hexcasting-trickster-interop-dev.md)，复现命令见[测试入口](testing.md)。

## 范围和依赖证据

核对主源码、注册入口、Mixin、指南、发行包和真实依赖字节码。参考来自本地 `.refs/HexMod`、`.refs/trickster`、`.refs/neoforge-21.1.227`；参考仓库不代替当前发行依赖的 ABI。实际验证组合为 Minecraft 1.21.1、NeoForge 21.1.236、Hexcasting 0.12.0-devel-pre-53、Trickster 2.0.0-beta.56（Curse 文件 8947077），Java 21.0.8。

Hex JIT 只验证受桥接影响的正确性子集，未将其全部算法、GPU 渲染或第三方任意内部实现列为本次完整审计范围。

## 已确认且保留的玩法

- 绑定圆固定调用时石板的世界、位置、朝向、范围；玩家提供身份、背包、副手与 Anima。玩家在线跨维度仍允许原石板施法。
- 玩家离线或来源区块未加载时暂停，不推进指令、延迟或付款；同身份重新加载后恢复，无自动超时，可手动取消。来源拆除、替换则结束相应调用。
- 无绑定圆和组构台的桥接队列仍为临时队列，每来源五槽，卸载、重启取消，后台最终结果丢弃。
- 跨语言累计预算；Trickster 当刻额度耗尽可续跑，Hex 总操作额度或跨语言深度超限结束对应调用。
- 反向 Hex 和伟大戏法继承圆上下文；其他世界的玩家身体、哨卫坐标不扩展圆范围。实体操作保留各模组原生前置检查。
- 未适配第三方片段继续不透明包装，隐藏引用无法完整检查作为已确认兼容性例外。
- 费用、兑换倍率、null/garbage → void、列表内 void → NullIota、普通桥接失败后继续 Hex、组构台权限与事故惩罚规则保持现状；不增加自动解包或返回结果协程。

## 1. 真实来源与续执行恢复：已实现

[CircleSourceDescriptor](../src/main/java/com/iridium126/createmanaindustry/compat/hextrick/execution/CircleSourceDescriptor.java) 保存来源维度、促动石、调用石板和原顺序供能石板的坐标、方块实体类型及 UUID。保存信息只有标识，没有可花费的魔力副本。石板/促动石 UUID 随正常 NBT 保存恢复；新放置分配新身份；更换晶结不改变石板身份，余额和容量读取当前物品。

[BridgeSpellExecutor](../src/main/java/com/iridium126/createmanaindustry/compat/hextrick/execution/BridgeSpellExecutor.java) 在原生管理器传入玩家 SpellSource 后仍先解析来源，再重建 AnchoredCircleCastEnv / CircleSpellSource。当前 executor 格式保存来源、circle 标记及根预算；不接受不受支持的 delegate，也不在恢复损坏时回退到玩家库存。CircleSlateManaPool 的 detached 数值池持久化供能路径已删除。

READY / WAITING / INVALID 在执行前处理。WAITING 及离线不推进 delay、不执行指令、不扣款；发现已加载但被替换的来源优先判 INVALID，释放对应 wrapper 和 delegate owner，独立链不受影响。解析不加区块票、不隐式加载来源区块。

验证覆盖真实原生 executor ENDEC、方块 NBT 身份、当前晶结替换、暂停再保存、相同 NBT 来源恢复、替换终止、在线跨维度、离线暂停、取消和损坏元数据。另执行了两进程真实专服重启账目探针，证据见下表。

## 2. 跨语言共享预算：已实现

[BridgeExecutionContext](../src/main/java/com/iridium126/createmanaindustry/compat/hextrick/execution/BridgeExecutionContext.java) 将预算根与 CastChain 效果所有权分离，维护 root UUID、Hex 累计操作、实际环境上限、Trickster 当刻计数、互调状态及同步嵌套深度。恢复根通过服务端规范表合并，同一玩家的独立根保持隔离；弱引用清理与服务端停止清理负责运行时生命周期。

原生 DefaultSpellExecutor、TickData 和 Hex VM 挂点共享根。递归进入前预留指令，原生增量在返回时消耗预留，避免递归先于计数或重复扣额。保留双方原生计数的可观察行为，包括原生非尾调用在子执行器交接前尚未递增父计数的情况。

Trickster 仅进入新 tick 恢复额度，保存和新建 TickData 不能刷新额度；Hex 累计数跨 VM、续跑、保存保持。配置 `hexcasting.bridgeMaxDepth` 默认 32，范围 1–64。原生单语言不增加调度器或返回协程。

验证包括真实有界多层互调、原生尾调用替换状态、非尾子执行器、同刻保存恢复、下一 tick 结果、根隔离、共享额度、深度异常展开、队列满与取消 owner 清理。有限互调在 Hex 上限前被截断，不依赖无限递归触发 JVM 栈溢出。

## 3. 受限解码与数据所有权：已实现

[BridgeFragmentCodec](../src/main/java/com/iridium126/createmanaindustry/compat/hextrick/data/BridgeFragmentCodec.java) 为片段 NBT、网络、Inline、HexParse 和快照复制提供共同受限入口。按当前上游 compact 协议 3–8 解析；未知协议、截断、错误类型/注册项、CRC、压缩尾随数据明确失败，没有无界 fallback。

| 资源 | 默认上限 |
| --- | --- |
| 编码输入 | 2 MiB |
| 一次完整解析累计解压 | 8 MiB，嵌套压缩共享 |
| 结构深度 | 256 |
| 节点 | 64,000 |

集合、字符串、字节和 NBT 数组在分配前检查。BoundedIotaNbt 替换 unlimited NBT accounter 路径；实体名称的字符串化 JSON 在原生组件解析前检查结构预算；药水片段内层 gzip 计入同一解析预算。编码压缩输出也在写入时限制。Hex 自身 iota size/depth 限制独立执行。

DecodeFailure 提供稳定分类。NBT 使用 garbage 渠道；网络抛解码错误；Inline 返回红色损坏占位；HexParse 返回导入错误。损坏数据不成为有效空法术。

快照仍隔离 SpellPart、列表、向量和 byte[] 的可变所有权，缓存 size/depth/hash 与不可变叶引用描述。显式 visitChildren 遍历已知结构并重建实际变化项，未变化保持对象身份，不额外清除 Trickster 原生实体引用。

回归包括高压缩比、累计嵌套压缩、两份大药水共享额度、深层实体名称 JSON、深层/循环结构、广告超长 NBT 集合、截断、协议、校验和及内外层尾随字节，各入口的失败渠道和正常往返。

## 4. 统一施法上下文：已实现

[CastingOrigin](../src/main/java/com/iridium126/createmanaindustry/compat/hextrick/execution/CastingOrigin.java) 与 CircleSpellSource 将玩家身份、空间锚点、供能及物品访问分别建模。AnchoredCircleCastEnv 继承原生圆范围、图案规则、动力源媒质、颜料及事故环境，后台法术不跟随玩家或之后的新调用移动。

EvalIota、读写 iota、反转范围、同命和分区使用来源上下文。StoreEntity 的费用距离及 TinctureShaping 的 Hex 环境也继承来源。世界判定不直接用玩家当前世界替代施法世界。

同命与分区保存效果来源；同命事故通过关系所属世界定位目标。SharedFate 同步 Pose 增加 anchored 标记，石板端点不使用玩家实体插值。CMI payload 版本调整到 5；客户端和服务端需使用同一构建。自动观察者/服务端回归通过，实际显示与重连仍由用户验收。

## 5. 事务、诊断和范围策略：已实现

[InvertedPayments](../src/main/java/com/iridium126/createmanaindustry/compat/trickster/casting/InvertedPayments.java) 使用原生媒质发现顺序和直接资源提取，在事务中仅预留资源；完整快照恢复数量、默认组件及预留期间新增/移除的组件。累计预留健康额度不能重复使用。统计、进度、伤害与同步由 MediaPaymentEffects 在最终提交执行一次。

保留原生公式、媒质优先级和透支资格，包括小额透支的最低伤害与浪费统计。覆盖模拟、取消、提交、最后一件物品、装备媒质、多次预留和透支的资源/外部副作用账目。

CastingRanges 集中范围策略；PartitionFees 用原生注册 ID 和显式 handler 代替类简单名。桥接结果携带稳定 failureReason；玩家、圆和组构台沿现有消息渠道诊断。正常失败继续 Hex 的语义保留，等待不逐 tick 输出错误，重复桥接日志已移除。

## 6. 包结构和扩展契约：已实现

双模组实现迁入 `compat.hextrick.data / execution / conversion / bootstrap`，保留 Hex-only、Trickster-only gate。Mixin 继续只负责原生挂点。拆出 ChainExecutorCodecs、MediaPaymentEffects、PartitionIndex、PartitionSynchronizer，CastChains 按所属运行时释放；关键生命周期使用命名处理方法，并整理相关多语句单行和全限定名。

[FragmentStructures](../src/main/java/com/iridium126/createmanaindustry/compat/hextrick/data/FragmentStructures.java) 的注册期固定 adapter 共用 children/rebuild/units/references/visitReferences。转换注册表也在 LoadComplete 冻结。SpellPart、列表、映射与已知引用的转换、统计、扫描和显式变换复用结构契约；未知片段不自动展开。

保留 `createmanaindustry:trick`、`iota`、现有图案、executor 注册 ID 及文本入口。自定义执行元数据、来源格式和必要网络版本使用当前未发布格式，不写旧 CMI 迁移分支。

HexInteropContracts / HexInteropAbiTest 记录当前支持版本并检查关键方法、字段及指令调用点。Mixin 必需注入失败明确报错，禁止无提示部分失效。依赖升级必须审计这些契约，当前测试不构成未来所有版本兼容保证。

## 7. 测量与优化

修改前基线保存于 `build/benchmarks/hexinterop-baseline`，包含 classes、resources、distribution.jar、HEAD 和原暂存 patch。基准脚本切换 main 输出，运行同一套 probe、输入和依赖，不用新实现重建旧基线。

最终数据在 `build/benchmarks/hexinterop-baseline-final/{data,scenes}.json` 与 `build/benchmarks/hexinterop-final/{data,scenes}.json`；对照保留各轮范围和中位数，环境说明为 `environment.json`。Windows 10、Java 21.0.8、3 GiB 堆、每项预热 1000 次、7 轮 × 1000 次。CPU 计时存在 Windows 量化，不用它推导小操作的 CPU 收益。

输入包括 129 叶法术的快照、复制、NBT/网络/Inline/HexParse 双向编解码及真名扫描；三个世界共 60 个原生延迟任务、32 条同命关系、32 个分区、8 个模拟观察者及 288 个活动执行链。观察者是 packet sink，任务是原生延迟路径；这些微基准不是整服吞吐、实际网络或客户端帧率证明。

已实施不可变统计/叶引用缓存、后台任务按世界分区、链释放直接定位所属运行时，并精简受限字段解析的临时包装。SharedFate / Partition 同步没有测得应采用脏标记优化的收益证据，因此保持事件及观察者语义。

| 探针 | 基线 µs/次 | 最终 µs/次 | 耗时变化 | 基线 B/次 | 最终 B/次 | 分配变化 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| snapshot_129_leaves | 85.75 | 103.34 | +20.5% | 105488 | 137032 | +29.9% |
| snapshot_copy | 18.75 | 24.17 | +28.9% | 66160 | 90552 | +36.9% |
| nbt_encode | 67.95 | 72.73 | +7.0% | 99304 | 126272 | +27.2% |
| nbt_decode | 89.38 | 101.23 | +13.3% | 173808 | 229728 | +32.2% |
| network_encode | 0.62 | 0.53 | -14.2% | 1360 | 1360 | +0.0% |
| network_decode | 179.05 | 101.76 | -43.2% | 172024 | 227944 | +32.5% |
| true_name_scan | 32.25 | 1.51 | -95.3% | 72184 | 1256 | -98.3% |
| inline_encode | 116.61 | 48.23 | -58.6% | 32304 | 34752 | +7.6% |
| inline_decode | 19.21 | 23.33 | +21.5% | 67376 | 91840 | +36.3% |
| hexparse_encode | 96.40 | 91.41 | -5.2% | 100496 | 127336 | +26.7% |
| hexparse_decode | 96.10 | 126.48 | +31.6% | 175744 | 231616 | +31.8% |
| background_one_world_of_three_20_jobs | 7.01 | 5.54 | -21.0% | 5240 | 4429 | -15.5% |
| background_three_worlds_60_jobs | 6.30 | 8.89 | +41.2% | 13976 | 12272 | -12.2% |
| fate_refresh_32_links | 11.10 | 13.20 | +18.9% | 17752 | 17752 | +0.0% |
| fate_sync_32_links_8_observers | 35.99 | 37.21 | +3.4% | 37944 | 37944 | +0.0% |
| partition_sync_32_domains_8_observers | 22.65 | 23.63 | +4.3% | 29928 | 29928 | +0.0% |
| chain_attach_release_with_288_live | 4.00 | 2.85 | -28.8% | 1376 | 1304 | -5.2% |

最后一次字段作用域优化在相同输入下，将快照每次分配从 160264 B 降到 137032 B，NBT 解码从 276088 B 降到 229728 B。优化前的最终输入样本保留在 `hexinterop-final/*-before-field-scope-optimization.json`；原始修改前基线仍独立保留。

有界解析承担额外检查成本，报告保留增加的耗时和分配，不宣称整体性能提升。单世界/三世界结果存在 JIT、GC 和进程间波动，只有被测输入的中位数及分配可用于这一对照。更高关系规模、实际客户端网络和服务器负载应在后续真实采样后再决定同步优化。

## 验证证据

| 检查 | 实际结果 | 证据 |
| --- | --- | --- |
| JVM | 652 项，0 失败、0 错误、0 跳过 | `build/test-results/test/TEST-*.xml` |
| Trickster / 互操作 | 100 / 100 必需场景通过 | `build/hexinterop-final-trickster.log` |
| 资源转换 | 11 / 11 通过 | `build/hexinterop-verified-conversion.log` |
| 缺少 Hexcasting | 1 / 1，通过服务端启动与单模组路径 | `build/hexinterop-verified-absent-hex.log` |
| 缺少 Trickster | 1 / 1 通过 | `build/hexinterop-verified-absent-trickster.log` |
| HexParse | 1 / 1，正常往返与损坏拒绝通过 | `build/hexinterop-verified-hexparse.log` |
| 缺少 HexParse | 1 / 1 通过 | `build/hexinterop-verified-hexparse-absent.log` |
| Hex JIT 受影响子集 | 1 / 1 综合回归通过（stage 5） | `build/hexinterop-verified-hexjit.log` |
| 基线及最终微基准 | 各 2 / 2 行为夹具通过，完整采样完成 | `build/hexinterop-verified-benchmark{,-baseline}.log` |
| 真实专服两进程重启 | WRITE_READY、正常 stop、RESTART_PASS，最终 37000 | `build/hexinterop-verified-live-{write,read}.log` |
| 最终构建 | `test assemble --offline` 通过 | `build/hexinterop-verified-build.log` |
| 发行包排除检查 | 外层 18 项、内嵌 mod 2568 项；无 GameTest、benchmark、live probe 类或测试结构 | `build/benchmarks/hexinterop-final/verification.json` |
| 真实客户端连接/UI/显示及真实玩家完整流程 | 待用户执行 | 开发契约的验收清单 |

专服 write 阶段记录初始 64000、实际扣款 7000、保存余额 57000；正常 stop 后 read 阶段从真实世界 NBT 和原生 executor 二进制恢复，身份 READY、余额 57000，继续产生光源并扣款 20000，最终 37000。原始证据在 `build/hexinterop-live/evidence/{origin.json,executor.bin,write.json,restart.json}`。这是实际两进程来源/供能和 executor 恢复验证，不冒充完整真实玩家客户端登出流程。

既有 Shared Fate / Partition 不稳定夹具修复为：强制目标实际所在区块，并等待原生 EntityFragment.isValidEntity，再保留真实绑定与即时分区刷新断言；未改成忽略行为或只验证不抛异常。Potion 的玩家消息夹具改用原生 FakePlayer 与初始化的模拟网络连接，避免借用不完整连接导致随机失败。

Hex JIT 的 OFF/AUTO 最终 image 比较使用相同 CMI 链 ID 作为夹具输入，避免比较随机会话 UUID；原有栈、操作、声音和结果行为断言保留。发行检查同时查看外层和嵌入 mod JAR，不能只看顶层引导包。

## 待用户验收及下一步

真实客户端的 Inline、镜面 UI、专服连接、同命/分区显示、跨维度、重连，以及真实玩家存档整条流程仍待用户执行；[清单和隔离专服命令](hexcasting-trickster-interop-dev.md#用户执行的真实客户端验收)已提供。本轮没有把这些未执行项目标为通过。

下一步先收集这份清单的实际结果，再按观察到的负载研究解码成本或大量关系同步。第三方复合片段需要作者注册 adapter 才能完整遍历隐藏引用；未知自定义 decoder 不在完整资源保证内，继续作为已确认兼容性例外。新增费用、解包、权限或其他玩法改动须另询问用户。
