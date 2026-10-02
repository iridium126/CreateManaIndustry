# Create GPU 包裹：实现进度与内部契约


## 当前状态（2026-10-02）

自由包裹从创建起使用轻量记录，运动仅由 GPU 执行；无就绪 GPU 时保存最后确认状态并暂停。自由实体交还及轻量 CPU 后备运动已删除。水、火、熔岩与机器接触走有界 GPU 环境 journal 和服务端库存事务；补算保留一秒历史，每帧最多四步；body、pool、delta、journal 和观察槽位安全复用。锁链无 GPU 时保留最小 CPU 物流逻辑，服务端跳过逐包裹视觉姿态更新。协议为 `gpu-packages-9` / `gpu-package-observers-4`。

五轮独立 benchmark 选择 `linked`，生产源码和 shader 已裁剪为单一空间索引。当前设计见[轻量记录说明](gpu-light-package-records.md)，验证范围与尚需真实游戏完成的验收见[修复验收记录](gpu-package-repair-validation.md)。以下内容保留此前阶段记录，其中自由实体保留/恢复、100ms 窗口、追加槽位不复用及多索引选择等段落不再描述当前实现。

**最新范围调整：网络带宽优化及“总带宽 ≤ 原生 1.5 倍”验收移至后续版本，不再阻塞本次目标。** 保留当前已验证的相对已确认基线增量、批量 ACK、控制消息合并、可见确认后的权威原生重复下行抑制及原生观察者复制。未选择 CPU p95 收益不稳定的实验 predicted 模式。已移除带宽门禁；双端实验 opt-in、协议协商、模型/碰撞/力源覆盖、通用槽位、精确身份、成功可见提交、超时和回退检查仍执行。配置默认关闭。以下带宽数字是历史组件证据，不表示当前版本有完整总带宽验收结果。

移动锁链本轮补充：服务端权威选举和拾取使用 Sable 父结构世界/局部坐标边界；区域随当前姿态投影，射线逆变换到原局部盒，父 UUID 改变撤销旧 lease。不可变仿射 capture 已与实际 companion 对照，详情见[锁链坐标边界](gpu-chain-authority.md)。GPU 已实现每 track 的 96 字节父姿态表、普通/Iris 顶点、缩放剔除与 logical pose 拾取；矩阵扩展随 admission/池一起 commit，保持 64 字节粒子、20 vec4 header 及三个 Iris TBO。运行时每提交代采集一次 Sable render/logical 父姿态，同代用于链帧上传及世界灯光探测；缺少 light-atlas 覆盖时不接管。131072 全容量和独立 CPU 顶点参考已验证，见[父姿态内部契约](gpu-package-chain-frames.md)。链路 readiness 只在实验开关、双向通道、资源创建和包裹级 admission 成功时开放；实际移动结构游戏验证仍待完成。

自由包裹新增常规实体推挤和 Create 鼓风机受力 GPU pass；保持另一实体及 Create 处理类型的原生回调。非包裹力源快照后台构建 BVH，四槽上传不等待；实体空间分组、风机更新顺序及全规模 GPU 受力覆盖已验证。Sable 实体与移动风机已使用直接 compileOnly API 复制世界姿态，GPU 处理旋转气流边界和受力；普通来源使用独立内核，旋转风机才附加 64B 变换数据。131072 活动包裹的旋转气流受力 pass GPU p95 为 0.024–0.342ms，不是整帧时间。Sable 游戏注入与视觉、特殊实体冲量、实际多人仍待验证，实验配置默认关闭；带宽验收已移至后续版本。方法和数据见[原始受力报告](benchmarks/package-forces-2026-10-01.md)及 [Sable 受力报告](benchmarks/package-forces-sable-2026-10-01.md)。

2026-10-01 观察者补充：服务端基线/提交日志、GPU 字段合并/预测/校正、共享池导入、四槽反馈及有界 wire→GPU controller 已接入世界 runtime 的原生成员观察器。v2 携带服务端确认时间，稳定呈现时钟计入队列等待；namespace 关闭在一个 GPU pass 退休。权威可见确认后，该连接停止重复接收原生运动包，其他观察连接继续使用 Create 原生复制。64 字节变化入口降低微基准中的上传/提交/GPU 成本。订阅迁移、重进区域、长时索引回收和多人 131072 活动负载仍需游戏/实网验证。边界见[观察者内部契约](gpu-package-observers.md)，历史 [CPU/编码报告](benchmarks/package-observers-2026-10-01.md)和 [GPU 报告](benchmarks/package-observer-gpu-2026-10-01.md)。

此前总网络上限为原生的 1.5 倍，带宽门禁曾同时阻止客户端就绪、服务端接管和新观察订阅；现按最新范围调整撤除该阻塞。已有包内预测和 GPU 相对已确认基线的位置残差，不改量化、物理频率或默认发送时机。v6 新增精确 VISIBLE_READY：客户端可见 GPU admission 和 ACTIVE 一致后才允许停发这个权威连接的原生运动包；其他观察连接继续接收原包。交还立即发送原生绝对位置和速度，下一条相对位置对这个连接替换为绝对包，重新对齐共享编码基线；失去原生跟踪会撤销对应 lease。完整连接验收、基线/迁移/控制预算及实际交互恢复仍未完成。方法与历史字节对照见[权威下行报告](benchmarks/package-native-downlink-2026-10-01.md)，早期编码对照见[原报告](benchmarks/package-network-payloads-2026-10-01.md)及[v3 报告](benchmarks/package-batch-network-2026-10-01.md)。

v4 将成功提交的 ACK 在服务端 tick 结束时合并为精确序号区间，空洞不确认；客户端只接收当前 namespace 中已 SENT 的序号，再由原 GPU 身份/stamp 校验推进基线。131072 个包裹连续确认的三秒 payload 从 552832B 降至 2279B；总带宽仍未通过，默认 GPU ACK dispatch 和读回量不变。协议上限、积压与真实延迟限制见[ACK 对照报告](benchmarks/package-ack-network-2026-10-01.md)。

早期 Minecraft 帧/压缩测量：131072 异速运动、20Hz、阈值 256，假设停止权威原生位置下行时，上行与 ACK 为原生单客户端位置帧的 1.157 倍；保留同步时为 2.157 倍。v6 路由组件执行停发并计入一次逐包可见确认，组件比例为 1.393 倍。当前 v7 对 PREPARED、FINAL_READY、RELEASE、VISIBLE_READY 合并相邻同阶段控制，完整身份无损编码；该位置与确认组件降至 1.158355 倍，仍缺少其余接管/迁移/内容及实际连接流量，历史判定为 INCOMPLETE。未压缩时为 1.591157 倍，超出此前 1.5 倍上限；该上限已移至后续版本，不再阻塞本次目标。批处理不等待凑满，单条仍用原编码，满队列、超时或失败明确恢复；真实 GPU 可见确认仍在匹配的成功提交与 fence 完成后产生。早期数据见[压缩帧报告](benchmarks/package-network-framing-2026-10-01.md)及[权威下行报告](benchmarks/package-native-downlink-2026-10-01.md)，已有测量见[控制批处理报告](benchmarks/package-control-batch-2026-10-01.md)。

后续 v5 新增 GPU 无损位移预测：从 POSITION 中减去该成员上一段已确认位移，服务端和固定快照 ACK 精确还原；不改物理、量化、发送频率或任何玩法字段。131072 异速匀速样本压缩上行从约 39.0MB 降至 9.65MB，保留原生位置流后的合计仍为原生的 1.286 倍。真实管线 CPU p95 没有稳定收益，预测模式仅保留显式内部选择，世界默认仍用 relative。原 v3/v4 报告保留历史数字，新实现、完整断言和全部样本见[v5 报告](benchmarks/package-predicted-network-2026-10-01.md)。

2026-10-01 后端补充：已用匹配的 PhysX 5.11.0/ovphysx SDK 对 CPU TGS、GPU TGS 与 compute 接触管线做三轮比较。PhysX 在 131072 活动箱体下未同时通过接触质量筛查，且 GPU TGS 单步 p95 远高于 compute，未显示可复现优势；因此生产运行时使用 OpenGL compute，PhysX 仍只用于独立基准。当前 `stepFreeMoving` support4 + linked 在 131072 错位持续推力场景加入动态包裹扫掠后，三轮 GPU p95 为 5.508–5.626ms（中位数 5.511ms），零回退、零穿透；这是物理组件计时，不是整帧数据。与此前同场景世界堆叠数据范围重合，未显示明显计时回退。见[动态包裹扫掠报告](benchmarks/package-dynamic-ccd-2026-10-01.md)、[旧堆叠基准](benchmarks/package-world-stack-2026-10-01.md)及[后端对照](benchmarks/package-physics-backends-2026-09-30.md)。Sable 仍使用 `compileOnly "maven.modrinth:T9PomCSv:U678xqle"` 直接 API，不通过反射。完整视觉与整帧验收仍未完成。

**自由包裹与锁链包裹的客户端 GPU 路径已接入，完整计划尚未验收。** 自由包裹有最终基线、GPU 物理、提交确认、Renderer/Flywheel 所有权、native observer 与增量通道；锁链包裹有链路握手、GPU acquisition/更新/绘制、服务端事务及 Sable 父姿态。双端实验 opt-in 后，模型、碰撞/力源覆盖、通用槽位和成功可见提交仍逐项门控；不符合条件的包裹继续由 Create 管理。客户端 travel 只对已确认可见所有权的实体暂停，保留交互及 vanilla 复制生命周期。Iris 主渲染/阴影代码路径已接线，实际 shaderpack 画面、游戏 Mixin、多人与完整玩法/性能验收仍待用户执行；不据组件结果声称达到 131072 活动包裹整帧 60 FPS。

`PackageWorldRuntime` 在引擎 GL 边界惰性创建模型与共享 mixed solver，每自由区域创建 detector/journal/admission ring，最多八个区域；协商 chain payload 时预留链域，首次 TRACK 附加独立 chain channel/acquisition。GL 初始化必须预热。网络入口只排队，过量或没有就绪 runtime 的请求明确拒绝。运行时使用 20 Hz 墙钟物理与逐帧插值，一对动态结构 previous/current pose 不重复使用；物理时钟在实际有活动模拟后起算，不累计未接管阶段的初始化耗时。力源捕获在客户端 tick 更新，渲染边界仍负责初次捕获和兴趣区域变更；检查就绪时非阻塞接收已完成 BVH，区域变更使旧结果失效。力源暂未就绪时保留物理时间和位姿待下一帧重试，不使用过期快照；积压超过 100ms 仍交还 Create，记录带原因的 WARN，预期时限回退不抛出运行时故障堆栈。静态 section 与 Create/Sable 动态几何都来自已准备的版本化 GPU view，读取缺失或不支持数据时输出回退标记。清空、重载、预览、换世界、关闭主开关、shaderpack 程序不可用、失败帧及通道失败都会恢复原生成员、关闭确认/传输资源，再销毁 solver；不会等待 fence 或 worker。重试间隔五秒，区域或链 session epoch/revision 迁移当前使用全局回退重建，尚未实现无缝迁移。CHAIN_READY 由实验开关、四个链路通道和共享资源初始化结果共同决定；尚无无缝区域迁移。

共享物理 publication 仅在状态变更时复制，插值帧复用已发布 bank；增量检测只针对新 publication 或新的精确 GPU ACK。后者保证旧状态在途时被跳过的较新状态，即使暂停物理也会在 ACK 后再次检测。准备追加统一经过 `PackageDeltaChannel.append` 登记 CPU journal 与 GPU detector 的身份和候选顺序；禁止绕过 journal 直接写 detector。服务端稀疏区域索引独立于 GPU 紧凑候选索引，不以粒子槽位容量限制其数值。GPU 回归覆盖该完整运动增量与 ACK 重采样路径。

锁链新增 `PackageChainTrack` 与 `PackageChainTrackGpu`：前者按实际 Create 6.0.10 的半径、速度、连接端点/长度和偏航生成不可变 64 字节共享 header；后者按链路更新几何与控制，不逐包裹上传重复 header。节点每项 16 字节，每链路至多 32 个，资格掩码和稳定身份每包裹 32 字节；实际筛选和摆动仍在 GPU 执行。既有 64 字节 body/chain、通用粒子和 emitter header 协议不变。tracked 模式在 chain `startRadius.w` 保存精确 track 索引、`startRadius.x` 保存前一步进度，`progress.z` 使用 2/3 表示直线/循环；legacy 模式维持 0/1。共享 table 包含局部 start/radius、end/length、rate/mode/reversed/yaw、节点区间和完整 long revision。追加链路与候选不搬移旧索引，非法几何、范围、重复身份及旧 revision 在上传前拒绝。

`chain_tracked` 更新推进、目标位置、摆动与偏航；`chain_nodes` 对照 Create 的 `loopThresholdCrossed` 正反向 sign 规则，以及直线端口判定和四 tick 预告。事件每项 64 字节，携带完整身份、候选/track/revision、事件序号、实际/预告掩码与前后进度。实际跨越候选在服务端响应前停止进度，摆动继续；预告不停止运动。四个不可变输出 bank 满时保留 GPU 待办，旧 generation、旧 flight 或旧预告 ACK 均不能清除新实际候选。取消复制只释放 flight，不丢事件；拓扑变更使旧交接检查点回退。ACK 支持原始快照、CPU 记录及 GPU journal 区间，只有真实服务器接受对应事务后才允许调用，不能把读回完成当成 ACK。普通池导入 tracked 包裹时从 GPU reversed 状态生成吊具 FLIPPED 位。

组件已由真实 GPU 的零负载、工作组边界、混合预告/实际候选、延迟超过四步、容量/满 bank、身份/退休/重建及 Create 位置、速度、偏航参考测试覆盖；浮点对照容差为 1e-4，离散阈值/身份精确比较。服务端已接入 chain lease/基线、原列表停用接口、节点事务及可选 payload；客户端 chain GPU acquisition、可见 admission、世界 channel 与 Renderer/Flywheel 已接线。GPU 拾取查询与正常退休的前后姿态交还已实现，实际拾取输入与服务端稳定身份事务已接线。紧急关闭使用最近已完成的 GPU 姿态检查点；Sable render/logical 父姿态、世界灯光预覆盖和覆盖反馈已接入。CHAIN_READY 可在实验配置、所需双向通道和共享资源就绪后协商，不代表已通过游戏内交接/库存/多人验收。131072 活动链包的组件微基准及限制见[报告](benchmarks/package-chain-tracks-2026-09-30.md)，查询微基准见[姿态查询报告](benchmarks/package-pose-query-2026-09-30.md)。

`PackageChainAuthorityManager` 从实际 conveyor 生命周期及成功 append 登记原包裹，预算化枚举及 offer 调度；`PackageChainAuthority` 处理整批验证、两阶段及重复握手、独立最终基线超时、O(1) session 心跳和精确重复事务 ACK。`PackageNativeChainPlan` 保留原生端口/出口顺序，并通过 typed invoker 复用 Create 的预告、蛙港导出、链路转移及容量检查；实际物品始终只在服务端原对象中。事务中的原生 BE 更新按 conveyor 合并，整体超时发送一个 epoch CLOSE。高 Y 链路位置使用有符号三轴协议。速度、拓扑或路由结构变化按 track 索引退回相关对象；下一次接管更新 generation，旧候选索引在 epoch 内不复用。客户端资源创建与成功提交接线已加入；只有报告 `CHAIN_READY` 且订阅区域匹配的客户端才进入锁链选举，无就绪资源的 OFFER 明确拒绝。接线和限制见[权威接口文档](gpu-chain-authority.md)。真实服务器 tick、库存守恒和多人交接由用户在游戏内验证。

`PackageChainAccess` / `PackageChainContainers` 提供真实 Create 原对象的所有权边界，保留完整列表的身份、顺序、容量、存档和物品。`ChainOwnershipContainersMixin` 只在 `tick`、`updateBoxWorldPositions`、无参数 `tickBoxVisuals` 中使用 Create 子集，不取消锁链、轮轴及路由广播 tick。子集连续数组按成员变更重建，正常遍历不扫描 GPU owner；最后一个 owner 释放后恢复实际字段为普通 ArrayList。拾取、保存、掉落、断链、清空、读档及结构变换按需物化或退回检查点。批量回调核对原 owner 身份，不能误处理回调期间重新接管的新 owner。服务端只能在 PREPARED 确认隐藏 admission 后冻结最终基线；客户端必须核对 ACTIVE 与成功的 GPU 可见提交后才能隐藏 Create 模型。容器单元测试含 131072 项、10000 次随机变更、真实 Create 对象、原生 iterator 变更、回调失败与重入；ASM ABI 检查针对实际 6.0.10，实际启动注入和库存验收仍待用户游戏测试。三次 CPU 容器对照见[报告](benchmarks/package-chain-containers-2026-09-30.md)，不含真实物理、交接或整帧。

`PackageChainEventChannel` 已把节点内核接到真实 OpenGL 四槽 header/body staging ring 和有界 journal：先读 16 字节计数，再复制实际有效记录的 ≤1MiB 分片；不等待 fence、worker 或服务端 ACK。`append` 是 GPU 与 journal 身份的唯一共同登记入口。共享 `PackageDeltaJournal` 的保留/后台调度，链包协议每包 256 项，自由增量仍为 512 项；不更改自由增量协议。`PackageChainEventCodec` 在 worker 对候选排序，压缩候选 ID 差分和整数，保留完整 long 身份、track revision、uint 事件序号与掩码；进度 float 位模式精确保留。服务端可解码到复用的 64 字节记录缓冲，单包最坏上限 16386 字节；解码完成前不得执行玩法回调。包裹正常链上运动不生成逐位置记录。

传输拒绝保留不可变包；服务器明确接受整包对应候选后，才由精确 epoch/table namespace/sequence 邮箱提交 GPU ACK 并释放 journal。已完成读回不确认事件；旧预告在途时的实际交接保持 GPU 待办。读回、编码或发送队列准备超过 100ms 会关闭整个 channel，并由世界资源 owner 撤销原生成员和 GL 资源，网络 RTT 单独记录。客户端 channel 已由世界入口接线；正常 chain-ready 能力和实际网络循环仍未验收。测试覆盖零负载、65/1025 工作组边界、20000 项跨 1MiB 分片、四 bank 耗尽、worker 超过四帧延迟、部分传输拒绝、旧 epoch/重复 ACK、预告竞争、清空、编码失败及超时。当前微基准不包括新增 CPU 读回/编码/网络路径，其开销还需在完整接管后测量。

已实现：

- `PackageLease`：稳定身份、权威 epoch、接管基线修订号、未冻结资源准备 40 tick 上限、最终基线和活动状态两 tick 超时、过期提交拒绝、连续事务序号及回退检查点。准备期 Create 继续正常模拟；休眠心跳无需重复发送位置。此类只负责协议状态，不执行库存事务。
- `PackageDeltaCodec`：有界批次、排序 ID 差分、独立字段掩码、位置/速度/偏航量化、变长整数编码、缺失基线与非法值检查。
- `PackageAuthorityRegion` / `PackageAuthorityManager`：服务端区域选举、身份映射、两阶段最终基线握手、整批先校验后提交、每 tick 累计位移限制、回退检查点和 O(1) 区域心跳。运动包裹不能用心跳掩盖超过两 tick 的状态积压。
- `PackageIdentityData` 和 Create mixin：持久化的服务器全局 ID/epoch 分配器；自由实体使用 NeoForge persistent data，链上包裹保留原存档并追加完整身份。实际 jar 6.0.10 已核对方法描述符；尚未进行启动后的 mixin 注入与真实保存/加载验收。
- `gpu-packages-7` 可选 payload：接管 offer、资源 prepared、最终基线 ACK、ACTIVE、可见确认 VISIBLE_READY、回退通知、区域心跳、增量及精确区间 ACK；保留参考动作 DELTA、BATCH_DELTA（包内预测绝对位置）和 RELATIVE_DELTA（相对已确认基线，再包内预测），内部比较 PREDICTED_DELTA（再减上一段精确确认位移）。可见确认携带完整身份、region/lease epoch、索引及最终基线 revision，不接受提前、旧 lease、旧 generation 或过时运动确认。CONTROL_BATCH=10 支持同阶段 PREPARED/FINAL_READY/RELEASE/VISIBLE_READY，至多 256 条、11011 字节，包内有符号身份差分和版本复用；不跨包依赖，不包含姿态或物品。服务端先验证完整正文再执行逐条原资格校验，畸形尾部不执行有效前缀，处理/发送失败或超过每 tick 4096 条控制预算恢复 Create。客户端不扫描活动成员，只整理转换队列；同一 pump 在 admission 轮询后、增量发送前 flush，发送阻塞保留待办并暂停后续增量，超过 100ms 明确关闭 namespace。单条消息保留原编码。增量正文上限 24576 字节，codec 单批最多 2048 条，GPU journal 每包至多 512 条；ACK 最多 128 个区间/2048 个实际序号。内容和地址不进入位置协议；服务端没有接受客户端库存操作。v6 及更早对端不会协商 v7。
- `PackageCollisionCache`：主线程分批捕获、250µs 默认软预算、分区修订号、不可变后台输入、最多四个后台任务、非阻塞完成轮询及陈旧结果丢弃。
- `PackageWorldCollisionSource`：从已加载世界提取实际 AABB、摩擦、水/岩浆/火标记；不向后台传递世界或区块对象。移动活塞、细雪、脚手架及缺失邻块上下文返回不可用，禁止解释为空气。
- `PackageCollisionRuntime`：惰性创建，两条 daemon worker；客户端 tick 按 250µs 软预算捕获。方块修改立即撤销涉及的相邻 section，区块加载/卸载撤销九个关联 chunk 列的已请求高度，换世界直接失效。请求只加入 section 身份，不能同步扫描全区域。待办队列与删除为常数时间；重复编辑在尚未重新捕获时合并。
- `PackageCollisionGpu`：版本化持久映射世界碰撞表，后台去重局部形状；16KiB 上传切片、256KiB/250µs 默认帧预算，完整上传后才发布覆盖。四个不可变索引 bank 和零超时 fence 保护旧版本；相同版本复用索引表，避免 GPU 延迟时重复上传。资源不足保持缺失，禁止截断或将未知区域视为空气。
- `PackagePhysicsGpu`：GPU 空间哈希、静态 AABB 扫掠、受限相对运动 AABB 扫掠、四轮并行 Jacobi 接触、世界体素形状查询及刚性表面约束、锁链进度及摆动；缓存 uniform 位置并使用批量绑定。动态包裹的 CCD 只扫描有界扫掠单元和候选；极端步长或超过 8192 项局部候选时用步前位置/速度恢复并请求 Create handback，不静默截断。已确认且无碰撞/危险标记的空 section 使用精确粗查询快速路径；普通/世界内核分别编译。
- `PackageReadbackRing`：四个独立 staging buffer 和 fence；满槽拒绝提交，不阻塞等待；按序消费并检查 epoch，清空时用新存储替换仍在执行的旧拷贝。支持指定源偏移/有效长度的 ≤1MiB 分片；传输 journal 满时保留已完成槽和 CPU 拷贝，后续帧不重复下载正文。
- `PackageDeltaGpu`：自由包裹的 GPU 量化、脏字段比较、共享内存工作组压缩及有界输出。四个不可变输出 bank；读回不推进基线，只有完整身份、候选、服务器局部索引及 stamp 都匹配的 ACK 才确认。溢出候选保持脏状态，取消发送不会丢弃回退事件。支持不重置已有 flight 的身份追加及服务端终止身份通知。
- `PackageFreeUpload`：从 OFFER 或匹配的较新 FINAL_BASELINE 写入自由包裹 body、隐藏模型 metadata、未激活的 delta metadata 与精确量化基线。使用调用方复用的 direct buffer，不查询世界；所有校验在写入前完成。最终基线与原 OFFER 的区域、epoch、身份、服务端局部索引、lease 及修订号均须匹配。此类已通过真实 GPU 数据路径验证，并由 `PackageFreeAcquisitionGpu` 使用。
- `PackageFreeAcquisitionGpu`：已将 OFFER → 隐藏槽位 admission → PREPARED → 最终基线局部更新 → FINAL_READY → ACTIVE → 可见提交确认接成实际 GPU 适配器。最多 256 个准备转换，按帧预算消费工作队列；活动包裹不进入 CPU 逐对象扫描。消息只排队，上传和 admission 轮询在引擎 GL 边界内执行。释放也先退休 body，再确认其从成功提交中移除；旧快照、重复消息及错误身份不能恢复所有权。世界级资源创建、物理时钟及清空/重载入口已接到 `PackageWorldRuntime`；仅通过双侧实验配置与完整资源门禁才发送 FREE_READY。
- `PackageChainUpload` / `PackageChainAcquisitionGpu`：共享 track/node 追加、chain-local 事件候选与 fixed-offset body 索引、prepared body 与精确身份/资格、隐藏与可见 admission、退休确认。只处理成员转换，活动链包由 GPU 更新；箱体与吊具共用一个通用槽位，hook distance 保留原生模型语义。正常退休同时等候最新 GPU 前后姿态和 admission 移除，再交还 Create。世界入口与 worker packet transport、实际拾取、移动光照、父结构 track 上传及最近完成检查点的紧急姿态恢复已接线；自由包裹观察端增量也有独立 GPU 路径。游戏内实际 Iris/shadow 绘制、跨区域接触所有权和多人交接仍待验收。
- `PackagePoseQueryGpu`：GPU 射线归约及最多 256 个完整身份的按需姿态查询；每结果 128 字节，含前后物理状态。独立四槽 upload/output/staging 不覆盖未完成查询；满槽保留请求。真实测试覆盖身份、工作组尾部、Minecraft 拾取盒参考、失败帧、退休及程序替换。生产正常交还已使用该组件，实际拾取输入与服务端幂等事务已接入；真实游戏交互仍待验证。
- `PackageChainClientOwnership` / `PackageChainRenderAccess`：预算化原对象索引，成功可见确认后 typed 接管；native BE read 按完整身份重绑，原生移除或变换撤销，终止缺失与退休确认分开。成员变更按 conveyor 批量发布不可变 Create render 子集；正常 Renderer/Flywheel 不扫描 GPU owner。完整 BE 同步、真实 Mixin 启动、最新 GPU 回退姿态和大规模重绑仍未验收。
- `PackageDeltaChannel` / `PackageDeltaJournal`：先读 16 字节计数，再以四槽 ≤1MiB 分片复制有效正文；有界后台排序、编码和不可变 payload 构造，全客户端最多四个未完成编码任务。网络队列拒绝时保留记录，ACK 后才更新 GPU 基线并复用 journal。实际 ACK 入口、主帧调度及无光影实验资源门禁已接线；观察客户端和链路参数同步仍待完成。
- Java 单元测试、真实 OpenGL 验证及可选计算内核基准。
- `PACKAGE` 内部类型（稳定类型 ID 6、材质 ID 5）：普通更新跳过寿命、死亡链与运动，后续 GPU 导入物理状态；普通 keygen 排除包裹，交给专用模型分组。
- `PackagePoolGpu`：GPU 有效候选筛选、按实际通用池余量预留槽位、稳定身份 admission 表、模型计数/前缀/实例分组、箱体与吊具的间接绘制。每个包裹只占一个通用槽位。绘制、身份与插值附加数据双缓冲，和粒子池一起成功提交；失败保留上次完整提交。
- `PackageModelCache`：提取当前加载的 Create / addon 包裹 PartialModel 的实际顶点、UV、颜色、法线与 shade 标记。缺失模型、tint 或不支持的顶点布局留给 Create；尚未验证所有第三方动态模型。
- GPU 物理历史缓冲：每步保存上一位置、偏航和链上吊点，帧插值不读取 CPU 包裹姿态。链路初始吊点也由 GPU 生成。
- `/cmi particle packagepreview <0..131072>`：显式合成链上包裹预览，使用实际 Create 模型、20Hz GPU 运动及帧插值。0 或 `clear` 停止；清空、换世界及资源重载废止预览。当前仅无 shaderpack；打开 shaderpack 时停止预览。

实际依赖 `curse.maven:create-328085:7963363` 的 jar 声明版本 **6.0.10**，`.refs/Create` 是 **6.0.11**。当前已编译验证模型 API，并核对 6.0.10 ChainConveyorRenderer 字节码中的姿态运算、吊点偏移、±25° 限制与吊具翻转。未据此认定所有物流或实体玩法行为都已核对。

## 开发预览与内部导入接口

```text
/cmi particle packagepreview 10000
/cmi particle packagepreview 65536
/cmi particle packagepreview 131072
/cmi particle packagepreview 0
/cmi particle packagecollision capture
/cmi particle packagecollision
/cmi particle packagecollision clear
```

数量受当前通用粒子池容量限制。预览是固定种子、固定模型选择的合成圆轨道阵列；没有 Create 物流对象、库存、拾取或网络，不用于完整玩法验收，也不能以只有少量可见包裹的阵列代替同屏活动负载。第一次建立预览包含模型烘焙和批量初始化，必须预热后再测量。

`packagecollision capture` 排队捕获玩家周围约一 section 半径的真实世界形状，后续 tick 执行预算化采集，粒子主帧边界分批上传 GPU。无参数分别显示 CPU/GPU 就绪 section、采集/后台烘焙/上传 p50/p95、预算超限、上传字节和资源拒绝；`clear` 撤销覆盖并关闭 worker/GPU 资源。首次分配 GPU 存储需预热，不能计作预算化上传耗时。此入口不移动真实包裹。CPU 缓存最多 1024 个 section，GPU 默认最多 256 个；缺失、不支持或资源不足的数据保持 GPU 未就绪。Minecraft 应用 `ClientboundBlockEntityDataPacket` 后会撤销该方块及受边界形状影响的邻接 section，避免 block state 未变化时继续使用旧碰撞快照；未通过网络包发布的模组本地碰撞上下文变化仍需对应失效钩子。

阵列从相机前方开始，并沿执行命令时的水平视线展开。`stats` 的 `live` 包含成功导入的包裹槽位，但来自异步 GPU 快照，可短暂滞后；持续为 0 并且没有包裹可见不属于正常启动。`stats` 另显示 queued / active / 初始化失败 / shaderpack 停止状态，初始化失败会通知聊天并保留日志。

已修复运行时着色器加载误用文件系统路径的问题：Minecraft ResourceManager 接收 `createmanaindustry:shaders/particles/...`，不能接收 `createmanaindustry:assets/createmanaindustry/shaders/particles/...`。内部入口统一使用 `loadParticlePlain("packages/...")`。驱动验证现在从处理后的运行时资源加载，复用生产 `ParticleShaderSource` 的路径及 include 解析，覆盖此前直接读源码文件而遗漏的接线问题。Java 接线修改需要重新构建并重启客户端，仅按 F3+T 不会替换旧 Java 类。

`CMIParticleEngine.packageParticles()` 是项目内部 render-thread 接口，首次申请时分配资源；未使用该接口时不创建包裹 GPU 缓冲。附加表按最大 131072 个包裹分配，不随通用池数百万槽位放大。

接管适配器需先上传模型 ranges、包裹 metadata，再指定 solver 的状态、链路与历史 buffer 及区域原点。metadata 每项 80 字节：

| 行 | 内容 |
|---|---|
| uvec4 0 | 稳定 long ID 与 long lifecycle generation，均按低/高 uint 保存 |
| uvec4 1 | body index、箱体 mesh、吊具 mesh（-1 无吊具）、CHAIN/FLIPPED/HIDDEN 标记 |
| vec4 2 | 保留的接管基线字段；当前插值从 GPU history 读取 |
| vec4 3 | 保留、保留、hook distance、原生 packed-light 位模式 |
| vec4 4 | Create ground nudge xyz、保留 |

导入只接受有效物理 body 和完整模型；静态 collider、回退标记与容量之外候选不会分配通用槽位。未知 mesh/body index 在 GPU 解引用前检查。每个成功提交的 admission 表包含完整稳定身份及当前 `slot+1`，0 表示未分配；表项顺序对应候选序号，槽位顺序可能随 GPU 筛选改变。`pool_reserve` 将可见的已接管候选与 HIDDEN 准备候选分别计数：已接管候选优先保留槽位，必要时截断普通粒子尾部，避免它仅因普通粒子突增而丢失绘制；准备候选只能使用剩余槽位，满池时返回 admission 0 并继续交给 Create。普通粒子被裁掉的顺序仍由本帧 GPU 分配顺序决定。

**admission 仅证明本次通用槽位已分配，不是服务端接管确认。** 准备中的 Create 包裹可带 `HIDDEN` 标记参与 GPU admission，但 box/rig 绘制计数与实例流同时跳过它；切换标记在下一次成功提交后生效。`PackageAdmissionTracker` 已提供四槽独立、非阻塞的确认读回：只复制新候选的连续 32 字节记录；槽满时拒绝新快照，由调用方保留待确认请求。完成后整批核对 64 位 ID、generation、类型标志、槽位范围／唯一性及保留字段，全部通过才发布结果；任一不一致会关闭 tracker，并要求交还 Create。结果仍须与 region/epoch/最终基线及 ACTIVE 消息核对，不能直接用 CPU 请求数量取消 Create 渲染。tracker 已由 `PackageFreeAcquisitionGpu` 调用，自由包裹世界资源创建与实验启用入口已接通。`PackagePhysicsGpu.append`、`PackagePoolGpu.appendMetadata` 和已有的 `PackageDeltaGpu.append` 可把新批次写入缓冲尾部，不重传既有包裹；物理追加只初始化新 body 的插值历史，元数据追加跨批次拒绝重复稳定身份和 body index。服务端 PREPARED 捕获新的最终检查点后，`PackagePhysicsGpu.replace` 只重写对应 body/chain 区间及其插值历史，保留其他包裹状态；`PackageMixedPhysicsGpu.replaceFree/replaceChains` 在双域索引下调用它，再由下一次 `publish` 将新状态原子切入池输入。`PackageMixedPhysicsGpu` 给自由与链上包裹各自保留求解域，固定链上 body 偏移；每次两个域都完成后，使用 GPU buffer copy 发布到一组双缓冲的 body/chain/history 输入，供通用池和增量检测共用。可为两域分别预留 131072 个 body，但总活动包裹始终限制为 131072；如此无需因为两类数量比例变化而搬移已有索引，代价是较大的 sidecar 预留。链上包裹不进入自由包裹的接触网格，包裹仍各占一个通用粒子槽位。稀疏混合负载的同机 GPU 分阶段测量见[报告](benchmarks/package-mixed-2026-09-30.md)。自由域世界运行时已接通；真实混合场景仍待补齐链包交互/最新回退姿态，并完成撤销／压缩及脏区间整理器。Box / rig 共享状态，输出的实例索引同时携带候选索引与部件位，避免依赖厂商 draw-parameters 扩展。

绘制计算由普通粒子发射后的 GPU counter 控制；成功后才发布 admission、commands 与附件 bank。shader 重建先验证全部包裹程序，编译失败保留旧程序。GPU 求解器应在普通引擎 pass 外更新，进入引擎边界时恢复状态；包裹导入和绘制会使引擎绑定缓存失效。

自由包裹的服务端实体 pose 使用脚底坐标，物理 body 使用区域局部的 AABB 中心，速度单位均为 blocks/second。`PackageFreeUpload.prepared` 同时写入上述转换和原始服务端量化基线；接管准备期 metadata 带 HIDDEN，delta ACTIVE 为 0。最终检查点到达后，调用 `PackagePhysicsGpu.replace` 和 `PackageDeltaGpu.rebasePrepared` 仅替换该候选；`rebasePrepared` 不允许曾激活或已释放的候选，也不会清除其他候选的 flight。匹配的 ACTIVE 及已提交 admission 都确认后，才调用 `PackagePhysicsGpu.activatePrepared`、`PackageDeltaGpu.activate` 和下一帧池可见性切换。准备期终止通知核对完整身份和服务器局部索引后，永久撤销该候选的激活资格，直到新 epoch 初始化。GPU 测试覆盖相邻活动候选正在等待 ACK 时的最终基线替换、独立速度字段变化，以及错误 generation 和迟到 ACTIVE/FINAL_BASELINE。准备中的 body 使用现有 64 字节布局中的 `previousSleep.w = -2` 标记冻结；三种空间索引都排除它，预测、接触、移动结构和链上进度保持该检查点。HIDDEN 候选可以验证冻结 body 的真实通用槽位；未解冻 body 不能以可见 metadata 绘制。新增 GPU 测试使用 1024 个重叠准备／退休候选，验证不会影响一个活动 body 的运动或触发邻居预算回退，并覆盖局部解冻及链上准备期隔离。自由包裹实验适配器已接通；生产完整验收仍待完成。

物理 body 的静态状态现在按当前上传、追加与局部替换精确计数；移除最后一个静态 body 会关闭世界预测中的额外静态网格查询，保留其他静态 body 时继续查询。该状态只在 body 变更时由 CPU 更新，不读取 GPU 动态状态。

`PackageAuthorityClient.openFreeAcquisition` 是上述适配器与真实网络／渲染门禁的内部入口，由 `PackageWorldRuntime` 提供共享 mixed solver、模型表、delta detector、初始缓存光照及失败清理回调。适配器使用共享物理原点，量化基线继续使用各自 region 原点；阶段结束核对池实际使用的 body/chain/history 发布及原点。`CMIParticleEngine` 在成功 swap/包裹 commit 后调用 admission 与增量捕获；失败帧关闭当前 runtime，不发布部分结果。最终可见 admission 回调核对真实客户端实体 ID/UUID 后登记 `PackageRenderOwnership`，释放回调在退休提交确认后取消登记。默认配置不会宣告接管能力。

已退休 body 使用 `previousSleep.w = -3`，停止运动并从三种空间网格中排除，防止 Create 恢复运动后留下旧碰撞占位。当前适配器在同一 epoch 内保留已使用的 body、metadata 和 delta 索引；重复稳定身份明确拒绝，不会污染旧 ACK。长期运行仍需完成带身份保护的索引回收／压缩；达到索引容量后新包裹继续由 Create 管理。该限制不能代替最终持续活动负载验收。

`PackageRenderOwnership` 为普通 `PackageRenderer` 和 Flywheel `PackageVisual` 共用身份门禁，按客户端世界、entity ID 和 UUID 核对，终止通知还要核对区域、epoch 和稳定身份。Flywheel 路径把已接管实例的变换置零，释放后下一帧恢复 Create 的动画实例；重载、引擎清空和卸载世界会清除所有登记。`claimAfterAdmission` 已接到客户端适配器的最终可见提交回调；世界资源入口已调用该适配器；默认关闭的配置仍保持 Create 渲染。单帧 admission 丢失的处理仍需在适配器中保证，不可仅凭上一帧槽位长期隐藏实体。

### 绘制提交与进一步合并

所有包裹 mesh（包括箱体和吊具）已经用一次 `glMultiDrawArraysIndirect` API 调用提交；内部仍有多个 mesh 子命令。MODEL 的实体、手持物和透明外壳已用一次 `glMultiDrawElementsIndirect` 提交。调用次数不随包裹或 MODEL 粒子数量线性增长。

无光影时，包裹与 MODEL 可以在统一几何寻址、实例记录、着色器及纹理访问后进一步合并，但目前二者使用不同 VAO、非索引/索引几何、block/allay atlas、姿态和光照路径。包裹关闭混合；MODEL 透明外壳使用混合并写深度。跨类型合并必须保留这些行为，不能仅拼接 indirect buffer。

OPAQUE 与包裹可考虑统一不透明 pass；ALPHA 与 Hex 使用相近混合状态，但还必须保留各自深度写入和透明排序顺序。ADDITIVE 采用不同混合因子，继续保留独立 pass。Iris 的 MODEL 主渲染/阴影插入点也要求单独验证。没有启用尚未通过正确性和同机性能对比的统一路径。

`/cmi particle profile on` 后预热，再运行 `/cmi particle profile`，现在可见 `package_physics`、`package_import_group`、`draw_packages`、`draw_models`、各 sprite / Hex 绘制阶段的异步 GPU 时间，以及粒子引擎跟踪到的绘制 API 提交次数。multi-draw 计为一次；零实例间接命令仍计为提交，不等于有可见工作。该统计不包含 Minecraft/Create 自己的所有 draw call。

## 数据与执行契约

### 身份、接管及网络

服务端身份使用 `(long id, long generation)`，lease 的 epoch 为 long。网络增量中的 int ID 是**区域基线局部索引**，不是粒子池索引；后续 payload 必须携带区域、完整 epoch、基线修订号及序号，并用基线映射回稳定身份。

配置 `gpuPackages.authorityEnabled` 和客户端 `particles.packageGpuAuthority` 都默认 false；同时启用后，成功创建共享池、模型和物理资源的客户端发送 FREE_READY。若四个链路上下行/交互通道都已协商且资源初始化成功，客户端也报告 CHAIN_READY；shaderpack 环境还须主/阴影包裹程序编译成功。服务端 discovery 限制 64 次/250µs 每 tick，选举只按区域进行；目前开关需在包裹加载前配置，不会扫描已加载世界补注册。Java 修改需重新构建、重启客户端。游戏视觉、真实库存/交互、多人与动态结构检查交给用户执行；组件验证不代替这些验收。

初始 offer 期间 Create 继续运动，碰撞分区和模型准备最多可用 40 tick；这个期限不暂停 Create，也不计作交接额外延迟。资源准备完成的 PREPARED 请求使服务端捕获**当前**位置、速度、yaw 和 ground state，并仅暂停 travel；客户端必须上传最终检查点，再回 ACK 当前修订。最终基线窗口独立两 tick 超时，不能通过区域心跳延期；如果区域已存在活动租约，也继续使用两 tick 心跳期限。存活/伤害/库存回调仍执行原 Create 逻辑；机器插入、交互、伤害、外部推挤、内容变更和保存前先回退。客户端只能提交姿态，不能提交物品操作。当前保留普通实体生命周期和 vanilla 位置同步，不能据此承诺服务端或网络目标已达成。

量化候选值为位置 1/4096 方块、速度 1/1024 方块/秒、偏航 360/65536 度。这些尚未通过游戏内视觉对照，不能视为最终默认协议。超出局部坐标或速度范围时编码器拒绝，后续网络层必须使用全状态逃逸记录或回退，不允许饱和截断。

codec 编码批次最多 2048 个记录；GPU journal 使用 512 条/包，参考编码最坏正文 17410 字节，v3 按保守上界分配 20482 字节。后台直接编码原始整数记录，复用排序、输出及 primitive writer，不逐包裹创建 `Entry` / `Quantized` Java 对象。包内位置/速度轴预测器每包清零，连续 ID 隐式、稀疏 ID 精确逃逸；不依赖前一个网络包。不可变网络包的复制也在 worker 完成，渲染线程只将已准备的包交给可靠有序传输。增量基线只能在确认后更新。读回成功不等于服务端已提交，更不等于库存事务已提交。事务序号门禁不能代替库存操作的原子提交与错误恢复。

`PackageDeltaGpu(..., true)` 在 dirty 判定后将位置改为相对该候选已确认基线的精确整数差，velocity/yaw/flags 仍为绝对值。模式在 epoch 内不可变，channel 核对 detector/transport 一致；fixed uniform 在候选程序创建时用 program-uniform 设置，不改变外部当前程序。flight 固定基线直到对应 ACK/取消；ACK shader 只对完整身份及匹配 stamp 加回原基线，不读取当前 body。服务端 `deltaRelative` 先恢复每成员绝对位置，再执行原整批资格/位移/副作用提交，观察者日志仍发布绝对字段。旧 ACK、旧 generation、非法后缀及越界差值不会发布位置前缀。

RELEASE 使用单独的掩码 16，不携带量化位置，也不能与姿态字段混合。服务端验证整包后按最后提交检查点交还 Create；同包中的其他对象正常提交。拾取与在途增量发生竞争时，已退休且从未复用的区域基线索引视为无操作并确认，未曾发出的索引仍拒绝。终止通知检查完整身份和 epoch，只调用一次客户端生命周期回调；旧 generation 或迟到 ACK 不能停用新对象。

`PackageDeltaGpu` 输入为每候选 32 字节身份/选择表与 32 字节确认整数基线；输出每条 64 字节，包含完整身份、候选索引、服务器局部 ID、掩码、回退标记和量化状态。初始化及追加在写入 GPU 前检查同批次和既有候选的稳定身份、body index 唯一性，拒绝项不推进候选数。工作组只做一次全局区间预留，所有尾部线程参与共享扫描屏障。输出不保证按网络 ID 排序，由 worker 按每包 ID 排序并编码。零变化仅需要读 16 字节计数 header；131072 条变化的有效记录为 8MiB，需要分片而非扩大单槽同步读取。每个 immutable bank 在全部正文已复制入有界 journal 前禁止释放；满 bank 跳过检测，模拟继续运行。

journal 最多 `max(4, ceil(capacity/512))` 个网络槽（131072 候选时 256 槽），每槽保留精确 ACK 所需的原始记录；源 body 后续变化不能修改该快照。已发送但未确认的槽不覆盖，后续连续变化继续留在 GPU 当前状态，确认后再与旧基线比较。未完成 future 只检查状态，不等待；重载/清空/换世界统一关闭 epoch，旧 worker 仅完成自己的不可变数据，不发布网络包。准备超时在发布前和每个网络包前检查，超过 100ms 触发回退；已进入可靠传输的 ACK RTT 单独统计，不冒充本地准备耗时。

内部接管适配器必须先核对碰撞 GPU 覆盖、通用槽位 admission、最终基线和完整模型，再调用 `PackageAuthorityClient.open(...)` 并追加初始化 metadata/baseline。该接口转移 detector 的所有权；稳定候选和区域局部索引在 epoch 内不得复用。仅在物理状态完整提交后调用 `capture(...)`；失败帧不能捕获中间状态。已注册通道的 `pump()` 在引擎拥有的 GL 状态边界内执行，ACK/终止 payload 只填有界 mailbox。`Transport.prepare` 只能构造不可变数据，禁止网络、GL 和可变世界访问；库存及机器交接继续归服务端。

`/cmi particle profile on/off` 同时控制通道计时。`profile` 显示捕获/泵送 CPU p50/p95、每包准备时间和 ACK RTT，使用最近 128 个样本；正文/读回字节和 packet/dispatch 为 epoch 累计。分位数只在查询时排序。没有生产接管时显示 active regions=0，预览不创建网络通道。

GPU 参数 `uOriginOffset` 为物理局部原点减网络区域原点。自由包裹中心转换为 feet；位置限定在本区域 64³ 范围，速度超出 short 量化或 solver 输出回退时发出 RELEASE 候选，不饱和截断。锁链参数化同步需要独立适配器，不能用此自由运动协议发送链上每帧位置。候选索引在同 epoch 内不得复用。重建全部 delta 程序验证后才替换；失败继续旧程序集。

### 碰撞快照

每个 section 有 4096 个 cell，顺序为 `x | z << 4 | y << 8`。快照中的形状坐标相对 section 原点。捕获每次最多处理 32 个 cell，并按剩余时间轮转；后台只打包不可变的 cell 和 AABB。

预算在每次原始查询之前检查。Java 无法中断某个模组提供的耗时 `getCollisionShape`，所以这是软预算；`lastCaptureNanos` 和 `overrunCount` 如实记录超限。方块/区块失效已接入；实体相关、方块实体状态改变而不修改 block state、以及超出相邻方块范围的第三方形状上下文仍需单独适配。已有捕获快照不代表 GPU coverage；生产接管需验证上传修订号和实际扫掠范围。

未知/动态实体上下文的碰撞形状仍需要资格检查。当前 adapter 的空碰撞上下文不能被当作所有第三方方块的完整兼容实现。

### GPU 物理

每个附加物理记录 64 字节：

| vec4 | 字段 |
|---|---|
| 0 | 中心位置 xyz、逆质量；零逆质量表示静态碰撞体 |
| 1 | 速度 xyz、支撑标记 |
| 2 | 半尺寸 xyz、偏航 |
| 3 | 上一步中心 xyz、保留状态；负 w 表示请求回退 |

这是包裹专用附加状态，通过 `PackagePoolGpu` 映射到普通池的一包裹一槽位。另有每 body 32 字节插值历史。静态碰撞体只占附加碰撞数据，不分配通用粒子槽位。

当前宽阶段要求各半尺寸不超过 cellSize/2；大的静态形状需要先分割。每轮 Jacobi 都重建网格，没有固定邻居截断，也不依赖浮点原子操作或厂商 subgroup。

静态扫掠超过 1024 个网格单元或任一方向超过 32 格时输出回退标记并保持原位置。世界查询另有任一方向 16 格、总计 2048 格上限；超过上限、覆盖缺失、水/岩浆/火或不支持上下文均输出回退标记。负标记在后续步骤保持冻结，等待服务端交还 Create，不能自动恢复运动。自由包裹间新增 GPU 相对扫掠：低于各自 AABB 半尺寸的位移跳过 CCD，因为两包裹该子步的相对位移不足以完整穿越接触厚度；其余包裹查询相对轨迹并做法向速度/剩余位移修正。被静态世界扫掠截停的长路径沿用已截断轨迹；其他单体步长超过 cell size、扫掠覆盖超限或候选超过 8192 会恢复步前状态并局部请求 handback。接触仍用轴对齐盒近似，非规则堆叠、休眠/唤醒、真实动态预算和 Create 视觉仍需验证。原始四次 Jacobi 在密集堆叠中暴露明显穿透；运行时使用支撑传播，数值筛查不能替代 Create 游戏视觉对照。

当前自由运动初始常数为 32 方块/秒² 重力和按 20Hz 换算的阻尼，需用游戏实际轨迹校准。锁链在 20Hz 下沿用 Create 的摆动递推；其他更新频率的插值、姿态及视觉一致性尚未验收。

物理及历史 pass 重用绑定点 0–5，导入/分组重用 0–9，不扩展通用引擎的绑定数量要求。分别使用 SSBO、indirect-command、vertex-attribute 与 buffer-update barrier。调用者必须在外部渲染边界恢复 GL 状态，并使通用引擎绑定缓存失效。

### 世界碰撞接口与布局

`Snapshot` 仍提供 CPU 参考坐标；GPU 数据由 worker 去重生成，公开缓冲视图为只读。每个 section 的 cell 为 4096 × 16 字节：`uint shapeStart, uint shapeCount, float friction, uint flags`，顺序 `x | z<<4 | y<<8`。局部 AABB 为两行 vec4（32 字节）；GPU 使用 block 的整数局部坐标平移。单 section 默认最多 1024 个唯一 AABB，超过时整个 section 不接管；后台打包最多支持 16384 个，不能静默丢弃后续形状。任何形状超出其 block 的 `[-1,2]` 范围也拒绝 GPU 使用；一格 guard 覆盖相邻方块/section 的形状延伸。

默认 atlas 为 256 个逻辑 section、512 个物理版本槽，共 48MiB 数据及四个 16KiB 索引表。SSBO 4 是哈希索引表（32 字节：`ivec4 sectionXYZ_slot, uvec4 revision64_active_empty`）；SSBO 5 是 cell 与局部 shape 数据。`empty` 仅在完整捕获且无形状、无危险/不支持标记时为 1。上传只写未被未完成 view 引用的物理槽；失效立即撤销 CPU 覆盖，旧存储待 fence 完成后复用。四个不同版本的索引 bank 均未完成时不重写任何 bank；相同版本可以继续只读复用，并以最后一次提交的 fence 保护所有先前读取。

采集与 GPU 上传各使用独立软预算，不能相互等待。生产上传只做 CPU 到持久映射空闲区的拷贝，提交前使用 client-mapped/SSBO barrier；GPU 读取完成以零超时 fence 判断。coherent 写入可见性与已在读取的存储复用是两个契约，不能仅靠 barrier 安全覆盖旧数据，参见 [Khronos glBufferStorage](https://registry.khronos.org/OpenGL-Refpages/gl4/html/glBufferStorage.xhtml) 与 [glMemoryBarrier](https://wikis.khronos.org/opengl/GLAPI/glMemoryBarrier)。

`PackageCollisionRuntime.gpuCovered(sweptBounds)` 证明 guard 涉及的 section 当前版本已完整上传，已发现动态结构的几何/本 tick 姿态也已确认；它不证明模型、玩法回调或特殊形状接管资格。`covered` 仅证明 CPU 快照就绪。后续接管适配器应先请求速度预取范围、确认 GPU 覆盖与完整资源，再在引擎 GL 边界内调用：

```java
// Body 坐标以 originSection * 16 为原点，与 Pool 导入/增量编码的原点一致。
try (var world = atlas.view(originSectionX, originSectionY, originSectionZ)) {
    solver.stepWorld(0.05f, world); // 一个 view 可以覆盖本帧的多个子步。
}
```

view 生命周期内禁止上传或嵌套 view；CPU 失效会使原 view 下次绑定失去覆盖。solver 先做动态 Jacobi，再单独约束刚性世界表面，避免多包裹/多体素接触平均削弱地面支撑。接触修正后的覆盖也要验证；回退 body 不导入普通粒子槽位。摩擦读取支撑 cell 的参数，当前仍需 Create 游戏轨迹/材质视觉校准。`view(..., false)` 只用于内部禁用空 section 快速路径的对照验证，不改变覆盖或物理输入。

### 堆叠支撑传播与运行时选择

`stepWorld(dt, world, true, iterations)` 在接触后增加 GPU 支撑传播；无标记的重载仍使用原四轮 Jacobi。当前 `PackageWorldRuntime` 的自由包裹路径通过 `stepFreeMoving` 选择 support4 与 linked 索引；模组默认配置和生产资源验收门禁仍关闭，双端显式实验启用且逐包裹资源齐备时才会接管。额外缓冲为两个 `16*capacity` 字节记录数组及 32 字节控制块；分别在 pass 内复用 SSBO 6/7/8，不改通用粒子/header ABI。控制块前16字节为边数、修正数、拒绝数和有效输入数，后16字节为间接 dispatch 命令（XYZ及保留字段）。无边时生成零工作组。物理更新前完成可选工作空间分配，失败时清理候选；不进行逐 body Java 运算或 CPU 读回。

GPU 按严格 `(Y,index)` 顺序选择支撑、指针跳跃传播最低高度，最终只写自己的 body；所有尾部线程参与 kernel 内工作组屏障。实验接触使用各轴正负约束极值和相对速度，防止支撑移除后的悬空。世界接触保留上一步分离面的方向，防止深修正跨越体素中心后解到地面下方或误识别接缝。规则/错位/混合质量、移除支撑、131072持续推力及全部候选对检查已通过；单父节点结构仍需不规则堆叠、视觉和工作预算验证，见 [堆叠测量报告](benchmarks/package-stack-2026-09-29.md)。

### 实验性空间索引与密集预算

`stepWorld(dt, world, true, 4, IndexMode)` 可显式选择原 `LINKED`、精确 `EXACT_RANGES` 或计数 `BOUNDED_LINKED`。不带参数的旧入口仍选原链式 Jacobi；运行时 `stepFreeMoving` 显式选择 support4 和 `LINKED`，精确/计数索引仍是实验选项。精确索引在 GPU 按整数单元构建连续原 body 索引范围，CAS 插入、分层共享内存扫描、scatter 和 guard；每个已占用单元只做一次27邻域预算检查。表记录为 `(representativeIndex+1, count, start, scatterCountOrOverflow)`，不搬移 body/身份，128元素扫描的全部线程参与屏障。

计数链式在原 heads 后追加桶计数，合并哈希冲突的访问成本；接触预算融合在 kernel 内，静态积分与支撑建图前独立 guard。两种索引均在候选总量超过512时写负 sleep请求局部交还，保留静态占位；精确探测超过32槽也标记未知。高速静态扫掠另检查整个查询范围。请求交还尚需真实生命周期适配器处理，不能把负标记当成已完成 Create 交还。内部 `rebuildIndex` 只供诊断/实验，不推进时间。

131072容量下，计数链式额外需1MiB，精确单元需5259396B；工作空间只在首次使用分配。固定编译变体避免默认 shader 每粒子判断索引模式。131072活动合成场景均零最终穿透/回退，但同轮 GPU p95为原6.27ms、计数6.52ms、连续8.73ms；新路径未证明提速，保持显式实验选择，详见[空间索引测量报告](benchmarks/package-range-2026-09-29.md)。不能以预算回退代替活动容量验收。

## 验证命令

```powershell
.\gradlew.bat test --tests '*Package*Test' --tests '*CMIParticleCommandTreeTest' --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
.\gradlew.bat validatePackageGpu --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
.\gradlew.bat validatePackageGpu --offline --no-configuration-cache -I scripts/particles/validation.init.gradle -PpackageBenchmark
.\gradlew.bat validatePackageGpu --offline --no-configuration-cache -I scripts/particles/validation.init.gradle -PpackageDeltaPipelineBenchmark
.\gradlew.bat validatePackageGpu --offline --no-configuration-cache -I scripts/particles/validation.init.gradle -PpackageWorldBenchmark
.\gradlew.bat validatePackageGpu --offline --no-configuration-cache -I scripts/particles/validation.init.gradle -PpackageStackBenchmark
.\gradlew.bat validatePackageGpu --offline --no-configuration-cache -I scripts/particles/validation.init.gradle -PpackageStackStress
.\gradlew.bat validatePackageGpu --offline --no-configuration-cache -I scripts/particles/validation.init.gradle -PpackageRangeBenchmark
.\gradlew.bat validatePackageGpu --offline --no-configuration-cache -I scripts/particles/validation.init.gradle -PpackageQueryBenchmark
.\gradlew.bat validatePackageGpu --offline --no-configuration-cache -I scripts/particles/validation.init.gradle -PpackageWorldPrefetchBenchmark
```

基准输出 `build/package-gpu-kernels.csv`；分片管线另输出 `build/package-delta-pipeline.csv` 和逐次样本文件。世界碰撞输出 `build/package-world-kernels.csv`、`build/package-world-kernel-samples.csv`；堆叠另输出 `build/package-stack-kernels.csv`、`build/package-stack-kernel-samples.csv`；空间索引输出 `build/package-range-kernels.csv`、`build/package-range-kernel-samples.csv`。最近全套回归为 **301 项包裹 Java 测试、5 项命令测试及 21,488,656 项真实包裹 GPU 断言**，无失败；其中锁链 GPU 子集 158,581 项断言通过（历史带索引基准为12773247项、世界基准为6403125项、支撑传播初版为3313503项）。完整 Java 测试共 401 项通过。最近一次 Hex 全套回归通过 **135498 项断言**。Java 覆盖完整 payload 编解码、非法长度、保存计数水位、4096 个静止对象的 O(1) 心跳、最终基线超时、整批拒绝/回滚、身份/epoch、独立字段、每 tick 累计位移、负坐标/高 Y、碰撞邻居失效及旧 worker 丢弃。另覆盖有界 journal 延迟超过四帧、整分片编码/包构造失败、传输拒绝、准备超时、退休索引竞争及幂等 RELEASE。物品/地址守恒目前在纯协议 mock 中检查，不等同真实游戏库存验收。

GPU 测试保留空输入、64 线程尾部边界、静态支撑、重合包裹分离、高速静态碰撞、过大扫掠回退、锁链批次、四槽耗尽、代次失效、满容量 admission、完整 long 身份、缓冲哨兵、模型分区、实例唯一性及失败帧回滚。admission staging 另验证四槽满载与复用、无效 body 不占槽、错误身份／越界槽位／重复槽位／非零保留字段整批拒绝，不发布部分所有权；隐藏候选保留真实槽位而不进入 box/rig 绘制流，显现后的下一次提交恢复两类绘制。另覆盖 131072 个脏候选的完整身份、精确整数基线/掩码、位置未变而速度变化、输出溢出重试、取消后再发、ACK 子批次、错误 stamp/旧 generation、回退事件保留、空载 bank 及程序替换失败。量化检查包括正负 yaw 半整数、大 yaw、位置 ties-to-even、区域边界进位及向量速度上限；GLSL yaw 的常量除法和乘加均保持 Java double 语义。运行时路径/include 检查、65536 个链上候选完整导入、65536 个唯一槽位、131072 个箱体/吊具实例命令仍通过。这些大批次检查不代表真实 Create 同屏玩法或帧率验收。

增加真实 indirect draw 像素检查（包括 baseInstance）、失败/成功 shader 重建，以及 GPU vertex transform-feedback 对照 Create 参考矩阵。光照额外覆盖 Create SBB 双方向 diffuse 和 Flywheel chunk diffuse（含恒定环境光、未着色面），RGB 容差为 2e-5。姿态 xyz 容差 `2e-5` 方块；80 步 20Hz 链上递推对照的位置/偏航容差 `1e-4` 方块/度。测试里的阻塞 GPU 读取和计时结果等待仅用于验证；生产读回类只使用零超时 fence 轮询。上述数值验证不能替代游戏内视觉录像和光照/阴影验收。

世界碰撞新增部分上传、字节/时间预算、版本撤销、旧 worker、资源容量及不支持形状拒绝，半砖/台阶/跨 section 延伸形状、负局部坐标/±3200万级 Y、高速落地、材质摩擦、缺失数据和危险 cell 回退。支持位置容差 `1e-4` 方块，131072 个自由运动 body 的位置容差 `1e-5` 方块；快速/逐 cell 路径全 16 字段逐位一致。另有 12 次连续版本替换及 GPU 保存结果检查、4 个堆叠 body 的世界表面不可削弱回归；这些测试不等同密集堆叠视觉通过。

自由包裹在 GPU 上按 0.5 秒轨迹预取缺失 section，并标记活动扫掠使用的 atlas 行；0.15 秒安全扫掠进入未知区域时，GPU 通过同一结果中的逐 body 位图请求局部交还。读回使用四槽非阻塞环，满槽跳过新扫描；CPU 只消费完成快照、刷新活跃 section 保护集或排入数字分区请求，世界读取仍受已有 tick 捕获预算控制。没有有效 atlas view 时跳过自由物理步，避免把未知区域当作空气。包裹在可见 GPU ownership 提交后才取得内部 `HANDBACKABLE` 标志，冻结姿态持续绘制到 Create renderer 恢复。131072 个完全覆盖合成 body 下，新布局（固定 82,448 字节结果，含 512 字节 atlas 行位图和 16,384 字节逐 body handback 位图）的 GPU p50/p95 为 0.0369/0.0553ms，Java 提交为 0.0021/0.0071ms，零等待轮询为 0.0087/0.0172ms。与旧版结果布局不同，前后数字不作直接提速比较。测量范围和边界见[分区预取报告](benchmarks/package-world-prefetch-2026-10-01.md)，不代表整帧或完整活动碰撞负载验收。

CPU 碰撞 section cache（1024）和 GPU atlas（256）通过 GPU 使用位图保护最近一次成功扫描中仍被自由包裹扫掠到的 section；LRU 只回收未保护项，atlas 旧槽在引用它们的表 fence 完成前保持退休状态。活动 admission 覆盖重试未采集或尚未上传的 section；当全部槽均在活动使用集内时拒绝新覆盖，现有包裹继续保留碰撞数据，新包裹留在 Create。`/cmi particle packagecollision` 显示两级 LRU 回收/容量拒绝计数。

## 首轮内核测量

RTX 4070 Laptop，OpenGL 4.5，NVIDIA 581.15；20 次预热，三组各 30 步，GPU timer query。每组报告平均单步耗时，下面为三组中位数，**不是帧时间，也不是 p95**。

| 数量 | 分散自由运动、静态扫掠及四轮接触 | 锁链运动 |
|---:|---:|---:|
| 10000 | 0.1741 ms | 0.0081 ms |
| 65536 | 0.9826 ms | 0.0368 ms |
| 131072 | 1.7525 ms | 0.0570 ms |

分散自由运动基准中没有静态世界碰撞体或密集接触，结果只能说明计算内核在该输入分布下的开销。没有绘制、读回、网络、Minecraft 世界处理，也没有与 Create 实际同场景比较。详见 `benchmarks/package-kernels-2026-09-29.csv`。

增加 GPU 插值历史保存后的新基线，三组中位数如下：

| 数量 | 分散自由运动 | 锁链运动（含历史保存） |
|---:|---:|---:|
| 10000 | 0.1819 ms | 0.0128 ms |
| 65536 | 1.0026 ms | 0.0484 ms |
| 131072 | 1.9994 ms | 0.0740 ms |

这次增加的是正确插值所需的工作，不是提速结果。131072 自由运动的三次结果为 2.1005 / 1.9994 / 1.7893 ms，波动明显，不能用该组均值/中位数宣布性能回退或收益。新测量仍不包含模型绘制、世界碰撞和网络；原始结果见 `benchmarks/package-kernels-history-2026-09-29.csv`。

### 新增增量检测成本

同机 20 次预热，三组各 60 次 GPU timer 样本。下表取三组各分位数的中位数；`dirty_capture_cancel` 每次输出全部变化，再取消 flight 使下一次仍是满载变化。`unchanged_capture` 已先确认所有基线，后续仅输出零计数。没有读取 8MiB 正文、网络编码/传输、Create 实体或绘制，不能用于网络带宽或整帧验收。

| 候选数量 | 全量变化检测与取消 GPU p50/p95 | 无变化检测 GPU p50/p95 |
|---:|---:|---:|
| 10000 | 0.0184 / 0.0819 ms | 0.0164 / 0.0205 ms |
| 65536 | 0.0358 / 0.0440 ms | 0.0215 / 0.0246 ms |
| 131072 | 0.0532 / 0.0614 ms | 0.0287 / 0.0348 ms |

131072 全量变化的三组 GPU p95 均为 0.06144 ms，计时分辨率和短组件耗时限制了该数字的精度。原始报告包含独立组件 CPU 提交 p50/p95，见 `benchmarks/package-deltas-2026-09-29.csv`；这些很短的 Java/OpenGL 异步提交时间不含整个渲染线程、读回及 Minecraft 调度。此表是新组件成本基线，没有旧版同场景对照，不能据此宣布整体优化完成。带基准的验证共 807491 项断言（额外三项检查确认基线计数）。

### 分片读回与后台编码测量

已增加 GPU 检测 → header → 有效正文分片 → 后台编码/不可变 packet → loopback ACK 的真实组件基准。默认采用后台构造网络包：131072 条变化时，渲染线程每轮完整捕获的分配量由约 2.8MiB 降至 60–70KiB；CPU 总量 p50/p95 三组中位数为 2.4241/2.7241ms。这是多次 capture/pump 提交的累计成本，不是整帧或服务端 tick，且没有实际网络/绘制。

CPU 拼接 ACK 的回退版本已移除；GPU 常驻 ACK journal 收益没有稳定复现，仅保留内部显式验证开关，默认关闭。详细方法、三路径比较、波动、正文量、未采用实验及原始样本见 [分片管线测量报告](benchmarks/package-delta-pipeline-2026-09-29.md)。真实 131072 活动包裹和低带宽验收仍未完成。

## 剩余交付工作

总网络带宽优化与对照验收已按最新要求移至后续版本。本次采用既有相对增量、批量 ACK/控制和原生观察者同步。历史严格判定与未完成证据见[网络约束](benchmarks/package-network-contract-2026-10-01.md)；历史 INCOMPLETE/OVER_BUDGET 不改写成 PASS，也不再作为本次接管门禁。

自由包裹 GPU AABB 拾取原语、身份隔离的完成姿态恢复和分域发布版本已加入，右键/攻击已接到原生实体回调，混合右键并行查询自由/锁链类型。持续准星悬停现使用单在途、只在视线或物理发布变化时提交的 GPU 查询；完成结果需匹配当前 authority 身份/版本，并与当前射线及遮挡复核后才进入原生 crosshair。停发下行后仍使用原生实体交互包；服务端释放立即发送绝对位置/速度，并将该连接下一条相对位置替换为绝对包以重建 vanilla 基线。服务端接线和 packet-route 有自动契约测试，真实联机顺序、断线及恢复仍待客户端验收。接口、恢复限制见[自由查询与恢复报告](benchmarks/package-free-query-recovery-2026-10-01.md)，输入行为和验证边界见[原生输入桥接报告](benchmarks/package-free-input-2026-10-01.md)。

世界碰撞上传、查询快速路径的同机对照、原始样本和密集堆叠失败记录见 [世界碰撞测量报告](benchmarks/package-world-2026-09-29.md)。该报告明确区分内核收益、上传软预算超限和未通过的堆叠质量，不代表真实 131072 活动包裹验收。

1. 用户在实际 Create 6.0.10 客户端/服务器执行启动与 Mixin 验证，并检查拾取、库存守恒、路由、交接、回退、多人和断线恢复；组件测试无法代替游戏副作用验证。
2. 已接入通用槽位、完整身份 admission、确认读回和容量拒绝/回退；仍需在游戏中验证容量竞争、重载和生命周期边界，并用实际 profile 排查启动或区域迁移的集中成本。
3. 包裹模型、普通 Renderer/Flywheel 抑制、Iris 主渲染与阴影 hook 已接入；游戏内仍需验证材质、阴影、shaderpack directives、混合渲染边界和资源重载失败恢复。
4. 世界碰撞上传、动态 Create/Sable 几何版本与保守回退已接入，运行时使用 support4 compute；自由包裹间有界 GPU swept-AABB 已加入，并通过三种索引的快碰撞、回退和满容量移动结构组件回归。`LevelChunk.setBlockState` 客户端变更钩子覆盖 `Level.setBlock` 与模组直接写 chunk 的路径；标准 `ClientboundBlockEntityDataPacket` 仍在应用后单独失效碰撞缓存。没有方块状态变化、又不发送该数据包的自定义方块实体碰撞上下文，仍需对应模组适配器显式通知。仍需验证不规则密集堆叠的视觉稳定性、休眠/唤醒、分区预取/退订优先级和真实预算回退；目前没有完整世界/服务端负载证据。
5. 相对基线增量、分片/journal、确认和原生观察者复制已接入。仍需验证订阅迁移、锁链观察预测参数及跨区域唯一接触所有者；总网络带宽优化及比例验收已移至后续版本，不作为本次门禁。
6. 游戏内视觉对照录像、玩法/多人回归，以及包含真实绘制和网络的 131072 活动包裹完整性能验收。

生产客户端资源门禁完成前，不得启用 Create 模拟/渲染抑制，也不得把本阶段内核测量当作方案验收结果。当前受 lease 控制的 travel 钩子只为已验证资源的后续客户端准备，不会被预览或未准备的客户端触发。

## Create 动态结构与 Sable 子世界（实验入口）

新增 `PackageMovingCollisionSources`、`PackageMovingCollisionCache` 和 `PackageMovingCollisionGpu`。Create 从 loadedContraptions 索引获取结构，沿用 `toGlobalVector(local,0,true)` / `toGlobalVector(local,1,false)`；Sable 从 ClientSubLevelContainer 获取 sub level、inclusive plot bounds、已加载非空 section 及 logicalPose/lastPose。位于 plot 内的 Create 结构再组合 sub level 的前后变换。局部几何先减整数中心、姿态平移再以 double 减模拟区域原点，GPU 使用区域内 float。正交正缩放支持非均匀缩放；剪切、镜像或退化变换不可用。

Sable 使用 `compileOnly "maven.modrinth:T9PomCSv:U678xqle"`（实际发布版 2.0.5），不使用反射。发布 POM 没有 companion 传递依赖，`extractSableCompanion` 从同一发布 JAR 解包内嵌 companion 1.6.0 至 `.gradle/sable`，仅供编译和数学参考测试；Sable 与 companion 均不打入 CMI JAR，也不加入游戏运行依赖。`ModList` 检查通过后才实例化独立的 `PackageSableCollisionSources`，普通 Create 入口没有外部 Sable 类型引用。`validatePackageSableAbsent` 在独立 JVM 中排除两者并执行缺席分支，纳入 `check`。

API 不兼容、枚举超出 64 个结构、姿态捕获未完成均撤销覆盖。直接 API 的 `LinkageError` 在发现、版本/姿态读取及游标捕获阶段均使结果不可用，不能发布部分碰撞快照。Sable 普通块及 `BlockSubLevelCollisionShape` 的自定义静态形状在所属线程捕获；`BlockSubLevelDynamicCollider.buildBoxes(VoxelColliderData)` 也在所属线程直接采样，复制为最多每方块 256 个不可变局部盒后交给现有 worker 烘焙。越界、非法、超量或接口失败的动态盒转成带 `UNSUPPORTED` 标记的本地保守盒，接触时局部交还 Create；碰撞回调/脆弱块、流体、火、移动活塞、细雪、脚手架仍交还。真实 GPU 移动碰撞验证已将同一盒复制器输出送入姿态/BVH/接触 pass，确认它参与支撑解算。没有后台世界访问，也没有在 worker 中调用 Sable/Create 对象。

Sable 姿态直接按 companion 的 `transformNormal` / `transformPosition` 捕获，复用数值暂存，保留 rotationPoint 和非均匀缩放语义；避免用世界坐标相减求基向量及生成四组临时 Vec3。固定种子 300 组姿态、每组 16 点在绝对坐标 ±3000 万格与实际 companion API 比较，double 误差容差 3e-8 格。尚未测量这项 CPU 调整在实际游戏中的收益。

主线程每个结构最多捕获 16 个位置后轮转；Create 结构和 Sable sub level 的发现也纳入静态 section、动态结构共享的 250µs 软预算，单 tick 最多检查 128 个候选。扫描未完成或超时就撤销动态结构覆盖资格，不把尚未发现的结构当作空场景；缓存只在一次完整发现后删除失踪的来源。纯锁链光照需求只运行分区灯光缓存，不创建静态碰撞 atlas 或 Create/Sable 移动几何扫描；自由物理首次请求碰撞覆盖时才延迟初始化这两项资源。Sable 2.0.5 的 `LevelPlot.onBlockChange` 直接 API mixin 只撤销对应移动几何版本，不触发父世界静态区块重捕获；缺少或版本不符的 Sable 不加载该 mixin。Create 动态实体加入或离开也立即撤销覆盖资格，等待下次完整扫描。`/cmi particle packagecollision` 输出发现/对账的最近 128 次 p50/p95、末次耗时及超限次数。两个 worker 接收纯数值列表，最多四个待完成动态烘焙。精确合并同材质相邻 AABB 后构建 stackless BVH，保留空洞与异材质；最大 65536 个采集盒、4096 个合并叶子。超限拒绝整个几何，不截断。客户端 `LevelChunk.setBlockState` 改变、Allay cube 写入、区块替换/卸载、区块方块更新和方块实体数据更新都会撤销涉及的静态碰撞覆盖；Create `invalidateColliders`、动态方块/区块变化、源移除和换世界也使移动几何版本失效。已打开视图核对缓存 revision/frame。缺失区块或邻区上下文暂停捕获，未知几何从不视为空气。

GPU 几何按变化分片上传，与静态 atlas 共享 256KiB/250µs 软复制预算。每个结构拥有四个 256B 持久映射姿态 bank，包含前后正交仿射变换、局部 bounds、身份和节点数；准备 kernel 计算四元数与保守旋转覆盖。bank 只在 fence 完成后复用，旧 BVH 保留至所有引用完成。槽满、未上传、失效或不完整的数据请求交还，无等待。

`PackagePhysicsGpu.stepWorldMoving(world, iterations, indexMode, scene.views())` 是 `PackageWorldRuntime` 当前使用的有界实验运行时入口，每对前后姿态对应一次 20Hz 更新。同一 solver 不能重复消费同一来源 frame，body 重新上传时清除承载 sidecar 和时钟映射。调用者在引擎拥有的 GL 边界中获取 `PackageCollisionRuntime.movingView(...)`，在提交后关闭 scene；调用 `movingView` 不访问可变世界。所有 pass 属于调用者的模拟 generation；负 sleep 标记不能作为有效增量提交。

GPU 对包裹执行承载变换、相对表面速度摩擦、15 轴 AABB/OBB SAT。平台根摩擦在最终场景阶段每步只应用一次，保留原材质参数，不因平台接缝/接触迭代重复阻尼。恒定朝向/缩放使用解析扫掠 SAT 区间，旋转/变缩放使用有界保守推进；未知、工作预算耗尽与静态世界挤压请求交还。Jacobi 后先约束平台根高度，再传播支撑，最终重新约束结构。使用自有 16B/body 承载缓冲，不增加通用槽位、不改 64B 粒子及 20 vec4 header ABI。侧缓冲标识不是网络身份。

参考源码位于 `.refs/Create/.../AbstractContraptionEntity.java` / `Contraption.java` 和 `.refs/sable/.../SubLevel.java` / `LevelPlot.java` / `ContraptionColliderMixin.java`。Sable 指定发布 JAR 已通过直接 API 编译及 companion 数学对照；实际游戏注入、复杂旋转碰撞、反作用力、真实物流接管和视觉行为仍未验收，生产资源就绪门禁继续关闭。测量与已知限制见 [动态结构报告](benchmarks/package-moving-2026-09-29.md)，本次依赖迁移与验证见 [Sable API 报告](benchmarks/package-sable-api-2026-09-29.md)。

快速动态验证：`validatePackageGpu -PpackageMovingOnly`；基准加 `-PpackageMovingBenchmark`，均使用 `scripts/particles/validation.init.gradle`。默认完整 GPU 套件也包含动态正确性测试。

## GPU 移动光照（实验接线）

`PackageLightCache` / `PackageWorldLightSource` 在共享 250µs 采集预算内复制原生 block/sky nibble layer，缺失天空层按原生继承规则分批查询。light engine 通知只合并已请求列，后台不访问世界。`PackageLightGpu` 使用四个独立 atlas 和零 timeout fence，上传计入现有 256KiB/250µs 预算；已引用或未完成 bank 不覆盖，没有变化复用当前 bank。

`PackagePoolGpu.lightSource(atlas)` 在 draw 前以已提交 admission/pose 和当前插值每包裹采样一次。链上采样摆动位置，普通包裹采样脚底加 .85×height 的 probe；box/rig 共用结果。粒子及 header ABI 保持原样；80B package metadata 的 offset 76（nudge.w）现在保存 ground probe 高度。调用者拥有 atlas 生命周期，换世界先撤销 pool source/ownership；`reset()` 清除引用。

独立验证使用 `validatePackageLightGpu`，采样基准加 `-PpackageLightBenchmark`。131072 候选采样 pass 三轮 GPU p95 曾测得 .02048ms，只有单独采样成本；不含现在接入的覆盖反馈，也没有整帧性能证明。历史范围见[移动光照报告](benchmarks/package-light-sampling-2026-09-30.md)。

覆盖反馈现由 `PackageLightFeedbackGpu` 和 `light_requests.comp` 接线：先工作组内合并，再选全局 section 代表；独立四槽读回每次最多 256 个缺区。哈希碰撞、超量或满 ring 均由尚未登记的缺区继续重试。登记 pending key 后不重复请求同一区，避免不可用数据阻塞其他碰撞桶代表。`PackageWorldRuntime.prepare` 用复用 callback 消费完成结果，只登记数字分区，真实捕获仍按 tick 预算执行。

私有 sampled-light 的 bit 31 表示该候选已有确认亮度；暂时失效/缺区时保留确认值，首次未确认使用自己的 metadata。pool reset、source 更换及完整 metadata 替换会清除旧生命周期状态。反馈结果不携带游戏身份或裸粒子索引，不用于任何玩法操作；source 更换销毁旧 ring，旧结果不能登记到新世界。加入第 9 个程序，重建仍全部成功后替换。

随后加入三个独立材质分组 variant，当前 PackagePoolGpu 共 12 个程序；light sample/request 的既有槽位保持不变。

反馈基准加 `-PpackageLightFeedbackBenchmark`；仅用于对照的 global atomicMin 分支加 `-PpackageLightFeedbackGlobal`。131072 分散缺区压力下，工作组合并的 GPU p95 从 .053248ms 降至 .043008–.044032ms；已覆盖 p50 持平 .030720ms。这是采样、请求整理和复制组件的前后对照。移动 parent 链灯光现通过每代捕获的 logical pose 投影到世界，并在 atlas 覆盖后允许接管；提前预取、覆盖期限、缓存回收及真实视觉校准仍待完成。详见[覆盖反馈报告](benchmarks/package-light-feedback-2026-09-30.md)。

## 客户端紧急检查点

`PackageChainCheckpointGpu` 独立于拾取/退休查询，只在成功提交和物理版本变化时用 GPU 整理前后链姿态，复制到四个 persistent READ/coherent bank。最新完成 bank 保留，其他未完成 bank 不覆盖；正常帧只零 timeout 轮询，不遍历或解码全池。内部 native claim 保存 candidate 映射，按需 materialize/紧急关闭用完整 ID、generation、epoch、轨道及方向检查结果，再恢复 Create 姿态。无结果仍用 ACTIVE 基线；这不是服务端库存 ACK，也不能代替观察端增量。

接口创建与销毁均在所属线程/GL 边界，`capture` 只能放在成功 pool/admission commit 后；false 不推进版本标记。`find` 返回单条值且不暴露映射 view；关闭必须先恢复原生成员，再释放检查点资源，换世界/epoch 创建新实例。使用新类型时不能把该链姿态恢复接口当成通用玩法查询。`PackageChainUpload.checkpoint` 仍只接受退休状态，`retainedCheckpoint` 才允许为同一 retained native claim 恢复已完成可见状态。

131072 时每次 16MiB、默认 GL storage 80MiB。最终三轮复制路径 GPU p95 1.605632–1.612800ms，直接写映射路径为 2.781184–5.392384ms；默认采用复制，初次试测绝对耗时更高。此处是新增恢复组件的传输比较，不是整帧提速或活动负载验收。详见[检查点报告](benchmarks/package-chain-checkpoint-2026-09-30.md)。

## Iris 绘制接口

`preparePass(GBUFFER/SHADOW, committedPool, frustum, camera)` 只从成功 pool/admission 构建独立命令与实例，地面 solid 和链上 cutoutMipped 分为两个连续 mesh 分区；shadow 使用 Iris 实体阴影视锥，不能继承主相机已剔除的实例。普通绘制继续使用原本的一次 multi-draw，不改变粒子/header ABI。`vertex_pose.glsl` 是普通与 injected shader 共享的插值/摆动/姿态模块。

每次成功 commit 废止旧 pass，abort 保留旧成功代；reset/mesh 替换也废止。调用者必须传入该成功代的 pool id，准备完成后才能用 `passCommandBuffer/passInstanceBuffer/drawPreparedLayer`，不能把当帧新 metadata 或写侧 pool 与旧 admission 混合。layer 0 地面、layer 1 锁链各一次 indirect multi-draw，数量来自 GPU，调用者拥有程序、矩阵、纹理及外部状态恢复边界。

`bindPassTbos(kind, firstUnit, partial)` 在 shader apply 前准备光照并批量绑定三个 view（pool/attachments/sample light），返回是否使用采样亮度；单位须由调用方的 Iris sampler allocator 预留。用完调用 `unbindPassTbos`，并在返回引擎时使其绑定缓存失效。没有 view/裸映射借出。fixed capacity、mesh 及 count/bodyCount 参数的初始化/缓存属于该资源 owner，程序重建先验证 12 个候选再替换并清除缓存。

`PackageShaderHook` 已接入原生 MOVING_BLOCK / SHADOW_TERRAIN_CUTOUT 程序、主渲染与阴影注入，并支持纯 Iris。阴影使用当前 Iris 实体 frustum：最多 13 平面、距离盒、Safe Zone、无剔除及全剔除。Shaderpack gate 检查对应主/阴影程序已编译可用，失败即回退 Create；活动包裹只有在 shaderpack 程序成功准备后才可接管。GPU 组件验证及同机成本见[绘制准备报告](benchmarks/package-draw-pass-2026-09-30.md)及[Iris hook 报告](benchmarks/package-iris-hooks-2026-09-30.md)，真实游戏视觉与整帧目标仍待用户验收。
包裹专用顶点、CMI 自有 typed Iris 编译接口、原生材质 ID/custom uniform 与主/阴影状态边界已经接线；普通路径保持一次 multi-draw，Iris 为保留地面/链上材质语义各一次。扩展细节见[Iris 顶点报告](benchmarks/package-shaderpack-vertices-2026-09-30.md)和[本轮 hook 报告](benchmarks/package-iris-hooks-2026-09-30.md)。
