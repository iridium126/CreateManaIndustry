# Create 锁链包裹权威边界

这是项目内部实现契约，不是第三方稳定 API。服务端与客户端 chain acquisition、可见 admission、世界时钟及 Renderer/Flywheel 的接线已加入当前构建。GPU 射线拾取、精确身份拾取事务、正常退休姿态及客户端紧急关闭检查点已接线。光照提前预取和覆盖期限、移动 parent、观察客户端增量及 Iris/阴影仍未完成。因此当前客户端仅发送 FREE_READY，不发送 CHAIN_READY；没有就绪世界资源时收到 chain OFFER 会在冻结前明确 RELEASE。正常游戏锁链继续由 Create 管理，不能据此声明真实锁链 GPU 接管已可用。

2026-10-01 坐标边界补充：`PackageChainSpace` 为每条链路复制不可变父结构仿射姿态，以 conveyor 中心为局部原点，避免巨大 Sable plot 坐标的浮点差分。直接 compileOnly `PackageSableChainSpace` 读取当前 logical pose；公共/服务端 adapter 无客户端类和反射，未安装 Sable 不加载适配器。服务端权威选举、跟踪范围使用当前世界区域；拾取保留原 loaded/权限/range 检查，将世界射线两端逆变换后与原生局部拾取盒求交，不能用扩大后的世界 AABB 代替。父结构 UUID 改变、删除、变换失效会废止旧 track/lease，正常平移/旋转不会重写本地路由、进度、物品和基线协议。

该边界已与真实 Sable companion 的旋转点、任意旋转、正非均匀缩放和巨大 plot/world 坐标对照，明确容差 1e-6；immutable capture 不被后续 mutable pose 修改。静态正负分区边界精确比较；旋转拾取盒拒绝世界 AABB 角落的假命中。客户端 GPU 已增加父姿态表导入、普通/Iris 顶点、缩放剔除及 logical pose 拾取，详见[GPU 父姿态契约](gpu-package-chain-frames.md)。**世界运行时姿态采集/上传、acquisition 与退休原点、光照和父生命周期尚待接线，不能据此开启 CHAIN_READY 或声明移动锁链接管完成。** contraption 与 Sable 是不同结构来源；此次是 Sable 活跃 block entity 的坐标边界，不为 Create contraption 虚拟 BE 声明不存在的链轨道模拟。

Java 布局与原点验证已增加到 81 套 391 项单元测试；无 Sable/companion 的独立 JVM 检查通过。公共坐标/manager 字节码检查无外部 Sable 或客户端类型，typed adapter 无反射。已加入 131072 全容量父姿态的真实 GPU 验证和 Iris transform feedback 对照；本轮没有性能对照，不据此认定提速、实际 Sable 游戏注入、交接和库存验证完成。

进一步带宽优化按用户最新要求延期，现有同步继续采用；历史带宽基准不是本次接管验收门槛。

## 服务端流程

`ChainIdentityLifecycleMixin` 在首次实际 tick 中登记 conveyor，成功的原生 add 方法登记新包裹。初始化枚举与 offer 调度共用每世界每 tick 250µs 软预算，每轮最多 64 项；后台不访问世界。没有 chain-ready peer 时不消费初始化队列。原生完整存档仍须在写入时分配缺失身份，不能把普通 tick 的预算当作保存整张库存的硬时间上限。

`PackageChainAuthorityManager` 使用现有包裹权威 peer 和区域订阅条件选举，且要求独立 chain payload 已协商。每个选中的客户端拥有独立世界 session epoch。`PackageNativeChainPlan` 捕获每条链路共享几何、端口顺序、地址过滤条件和路由结构；过滤字符串、物品栈和内容仅留在服务端。正常运动无逐对象位置包，服务端共享心跳也不遍历活动包裹。

流程为 TRACK → OFFER → PREPARED → FINAL → FINAL_READY → ACTIVE：

1. TRACK 只在新增链路描述时发送，OFFER 引用 track index/revision，包含稳定 ID、generation、lease epoch、基线修订号、进度/时间基准、模型和尺寸。
2. 客户端必须先完成模型、sidecar 和隐藏通用槽位 admission，才能发送 PREPARED，并登记其 chain candidate index。此索引只用于当前 session 的候选关联，不能作为物品身份。
3. 服务端重新捕获当前 Create 进度，调用真实 `PackageChainAccess` 冻结对应原对象，发送 FINAL。准备资源期间 Create 一直模拟；只在最终基线窗口冻结。
4. FINAL_READY 必须匹配完整身份、lease epoch、精确修订号和当前冻结 pose。服务端确认后发送 ACTIVE。客户端还须等可见池提交及 admission 确认才能隐藏 Create 渲染。
5. 重复 PREPARED/FINAL_READY 不重复冻结、重启时钟或撤销已确认 owner。错身份、旧 epoch/revision 的控制不改变当前所有权；被冻结但未完成确认的对象独立在两 tick 后退回，心跳不能延长这个窗口。

链路描述只在原生 stats 已准备且换向处理完成后捕获。管理器不能提前调用 `prepareStats` 消耗 Create 的反向变化。保存、交互和退回使用已捕获几何按需推导进度；遇到尚未确认的资格节点会钳制，不能凭时间推导越过库存事务。停止时保留 Create 原有 reversed 状态。

## 客户端提交与原生所有权

`PackageChainUpload` 将精确 OFFER/FINAL 写入调用方复用的 64 字节 body、64 字节 tracked chain、80 字节通用池 metadata、32 字节事件身份。模型箱体和吊具共用一个通用槽位；hook distance 来自实际原生 PackageItem。局部 Create 摆动状态存在时保留其位置、每 tick motion 和 yaw，不能将该 motion 当作 blocks/second。所有输入校验在写入前完成；shared track/node 数据使用追加全局节点偏移。

`PackageChainAcquisitionGpu` 在渲染线程按队列处理转换，每帧最多 64 项；活动对象不参与逐对象 Java 更新。隐藏 OFFER admission 确认才发送 PREPARED；FINAL 只替换该 prepared body、插值历史和节点资格；ACTIVE 必须精确匹配最终 pose、进度和资格掩码，再解冻和切换可见 metadata。可见 admission 核对完整 ID/generation、CHAIN/FLIPPED 标志及当前成功 publication 后，才接管原生对象。PREPARED 使用 chain-local candidate，不能误用 generic pool candidate 或 fixed-offset body index。释放先退休 body、节点事件和 metadata，再确认已从提交中移除；迟到 admission/ACTIVE 不复活对象。通用池容量不足则拒绝新 OFFER，Create 保持所有权。

`PackageWorldRuntime` 为协商了 chain payload 的连接预留共享物理链域，首次 TRACK 创建同一 epoch 的轨道、channel 和 acquisition。网络入口只排队；20Hz 更新、publication 和 admission/event 捕获使用引擎 GL 边界与成功提交钩子。包裹总数限制为 131072，不因两域各自的预留或吊具部件翻倍。正常链运动无位置包；每 session 每 tick 一个心跳，精确 ACK 后可重新采样被旧 flight 遮住的耐久事件，暂停时也不能丢弃。关闭/换 epoch 的当前策略是整个世界资源回退重建。

`PackageChainClientOwnership` 只在原生资源索引已准备好时通过覆盖门禁，索引构建按 250µs 软预算分批。可见确认后的 typed `PackageChainAccess` 接管原包裹，常规 native tick 和视觉 tick 使用 Create 子集。成员变更按 conveyor 批量发布不可变 render snapshot；普通 Renderer 与 Flywheel beginFrame 只读取该快照，保留链、轮轴、guard 和 SmartRecycler discardExtra。单元测试检查快照可从 worker 遍历、旧快照不被修改、未变化列表复用，ASM 检查针对实际解析的 Create 6.0.10。

客户端原生 BE read 替换 Java 包裹对象时，保留逻辑身份、按完整 ID/generation 和 connection 重绑；缺失身份或超过两 tick 的重绑撤销运行时。终止消息使对应缺失成为预期，但不能在 GPU 退休提交确认前提前发布渲染交还。原生移除/变换、清空或失败时先恢复成员，再销毁 ring/solver。当前仍使用 Create 完整 BE 同步，巨大完整快照和重新索引的代价尚未通过目标场景测量；此路径还没有通过实际游戏 Mixin/网络循环测试。

### GPU 姿态查询与正常交还

`PackagePoseQueryGpu` 从一次成功 publication 的 body、chain、history 和通用池 admission 查询。完整 ID/generation 必须同时匹配 metadata 与已提交 admission；不能因 metadata 已更新而提前采用其可见标记。射线命中选择在 GPU 上以 64 线程工作组归约，再归约各工作组结果；尾部使用无效候选参加全部 barrier。射线使用 Create 的 targetPos 拾取盒和 Minecraft AABB.clip 的正入口语义，工作组不依赖 subgroup 扩展。

一次拾取只返回一个 128 字节结果；按需姿态 gather 每批最多 256 个完整身份，含当前位置/速度/偏航、轨道进度和前一物理步的位置/偏航/目标。四个独立上传/输出 bank 与四槽 staging ring 不覆盖未完成读回；槽满返回 false，调用者保留脏请求。程序重建先编译全部三个 kernel，失败保留原程序。ring 属于不可复用的 session epoch，世界关闭直接废弃未完成结果。

正常释放先将 body 退休、事件和 metadata 隐藏；只有成功 pool commit 后才提交允许退休状态的姿态 gather。交还须同时获得退休 admission 和完整身份/轨道/body 的姿态确认，未成功帧不提交查询。四槽耗尽后保留请求到下次成功提交；超过 100ms 则撤销世界运行时，没有 fence 等待。其他包裹继续模拟时不改写退休对象的 history；首次上传/重写范围仍初始化 history。native bridge 恢复真实局部原点、Create 每 tick motion、chainPosition、hook、当前与前一步摆动，并重新登记 stock physicsDataCache，最后交还成员和渲染所有权。

### 客户端紧急检查点

`PackageChainCheckpointGpu` 使用独立四槽 persistent READ/coherent 映射，不扩大交互查询的 32KiB ring。仅在成功 pool/admission 提交后、物理 publication 或候选数改变时，GPU 按候选整理 128B 前后姿态；普通 free、prepared、未提交/隐藏或身份不符记录输出全零。退休对象只在完整身份仍匹配且 admission 槽位已清除时保留。GPU 整理到设备 scratch，再复制到未引用、无在途 fence 的 staging bank。所有 fence 以零 timeout 轮询，平时不下载、遍历或解码整个包裹池。

保留最新已完成 bank 时，其他三个 bank 可在途；首次尚无完成结果时四个 bank 均可提交。槽满保留调用方 version token，下一成功帧重试，不能覆盖已完成备用姿态或未完成复制。消费更新时只推广更大的成功提交 generation；超过 100ms 的在途检查点撤销运行时。停止/暂停且物理版本未变时复用完成结果，不因没有新运动反复传输。

原生 claim 记录稳定候选映射；保存 materialize 和紧急关闭按完整 ID/generation、轨道及方向校验所保留的姿态，恢复进度、hook、当前/前一步摆动、Create 每 tick motion 和原生缓存。普通退休仍须等待其独立退休 admission/pose，紧急检查点不 ACK 玩法事务。关闭先尝试一次非阻塞轮询、恢复原生成员，再销毁映射；世界和 epoch 重建直接废弃旧 bank，没有映射 view 逃逸到调用者。

这里保证的是**最近已完成的客户端检查点**，不能称为失败瞬间的最新 GPU 状态。首次可见提交尚未完成、记录缺失或校验失败时仍退回 ACTIVE 基线；严重设备失败也不能等待未完成命令。服务端紧急恢复的 pendulum/观察客户端校正尚未接线，真正断线、两个渲染时钟交接及全量 native handback 仍需游戏测试。传输比较及成本见[检查点报告](benchmarks/package-chain-checkpoint-2026-09-30.md)。

### 实际拾取与服务端确认

客户端 `ChainPackageGpuPickingMixin` 注入实际解析 Create 6.0.10 的 `ChainPackageInteractionHandler.onUse()Z`。它位于原生 package 阶段，保留前面的胶水、链连接、蛙港、工具等优先处理。没有就绪 runtime 或活动可见链包时完整使用 Create。GPU-owned 存在时串行保存一个 use；世界/player、原 vanilla hit、主手组件和栏位只用于未命中后的重放资格，不进入网络。

`PackageChainUseQueue` 只保存一个操作和单调事务号。成功 publication 后，输入在退休 gather 之前取得一次四槽捕获机会；槽满保留同一个 QUEUED 操作，没有每帧扫描活动包裹。结果必须属于原 Use 对象，且只有当前 ACTIVE、已确认可见的完整 ID/generation/body/track 才能构造拾取请求。CPU 处理/查询上限 100ms；未命中或已退休目标，在 client tick 的 GPU pass 外以 bypass 重放 vanilla use。换世界、换 player、屏幕打开或主手上下文变化不重放旧操作。已经发送的请求永不转成原生第二次拾取；ACK 单独使用 5 秒网络确认上限，记录 send→ACK 的往返时间（包括网络、服务端处理及客户端派发，不是纯线路时延）。`/cmi particle` stats 的 world runtime 字符串显示最新该值。

独立可选 `gpu-chain-pickup-1` payload 的上行携带 epoch、完整身份、lease/baseline/track revision、事务号和精确 float 进度，不携带池索引、物品、地址或客户端世界位置。下行确认身份、epoch、事务及结果。缺少任一拾取上下行 channel 时，不预留 chain acquisition 域；旧或未协商客户端继续 Create。

服务端通过 epoch 和完整身份直接定位目标。调用者 UUID 来自服务器连接，可以与物理 owner 不同；观察客户端的基线和 GPU 显示接线仍待实现。服务端复核 lease、revision、track、原物品和成员、Create spectator/adventure/loaded/range 权限，以及基于服务端轨道重建的拾取射线。可达距离受已确认基线时间和两 tick 容差限制，不能越过未确认的合资格库存/路由节点。库存按 Create 原有主手为空则放主手、否则放回库存的方式提交，删除精确原对象，避免按进度扫描整条链。

`PackageChainAuthority.pickup` 每个调用者保留最后一个串行请求：精确重复已接受请求返回 DUPLICATE；改变正文、旧序号、错身份/修订和被拒后重新利用序号不会再触发回调。在原生副作用前认领事务，拒绝重入；回调失败使 epoch 失效，不重放部分事务。终止消息延迟到库存回调后发送，再发送合并的 BE 更新；发送失败不会在原生删除和发放之间打断回调，失效 session 在下一 server tick 回退。单元测试使用模拟库存，实际 ItemStack 内容/地址和游戏库存守恒仍需游戏测试。

真实 GPU 接线测试发现并修复了 history 初始化把 tracked 模式 2/3 当旧 start/radius 布局重算目标的错误：tracked 初始化保留 FINAL 精确 target，旧模式仍重算。验证覆盖第一次模拟前的吊点、失败 publication、查询早于可见确认、精确 ACTIVE 租约及退休后的旧命中拒绝。详见[拾取接线报告](benchmarks/package-chain-pickup-2026-09-30.md)。

**就绪门禁仍关闭。** 紧急关闭姿态、光照提前预取与覆盖期限、观察客户端增量、Iris/阴影，以及移动 parent 下的链路坐标变换仍待完成；实际 Mixin、输入重放、库存与多人网络循环还未运行游戏验证。光照采样和缺区反馈已接线，支持保留最后确认亮度、哈希碰撞/超量/满 ring 重试；成本及范围见[覆盖反馈报告](benchmarks/package-light-feedback-2026-09-30.md)。查询正确性、原始样本和组件性能见[姿态查询报告](benchmarks/package-pose-query-2026-09-30.md)。

## 节点与库存事务

`PackageChainEventCodec` 的 64 字节 GPU 记录和现有链路 channel 保持不变。独立 `gpu-chain-packages-1` payload 携带 session epoch、顺序号和最多 256 项、16386 字节正文。TRACK 和基线使用有符号三轴坐标，避免 vanilla BlockPos 打包格式截断高 Y；物品、内容和地址不进入协议。

`PackageChainAuthority` 完成整批解码及身份、candidate、track/revision、unsigned step、资格掩码和原生条件校验后，才允许玩法回调。真实适配器还校验单步运动范围和自上次确认基准可达到的距离。只有完全相同的最后一包可获得重复 ACK；相同序号但不同正文会被拒绝。退休索引保留完整身份，当前 epoch 内不复用；迟到记录不能操作新包裹。

`PackageNativeChainPlan` 按 Create 的端口、出口、链尾顺序提交：

- 端口重新检查原物品地址，使用 typed Mixin invoker 调用 `notifyPortToAnticipate` 或 `exportToPort`，由 Create 检查蛙港动画和堵塞并执行物品操作。
- 出口重新检查当前路由和目标容量，复用原生 `addTravellingPackage`，保留同一个物品栈和物流对象。
- 链尾复用目标原生 `addLoopingPackage` 和 Create 的转入角度；目标缺失时退回 Create，避免重复产生到达候选。

普通预告不会停止运动，也不会更改物品。GPU 未确认的旧预告可合并进后来的实际候选；服务端只校验和通知新增预告位。每批事务在副作用前认领顺序号；原生回调异常会废止整个 session，不能重放已执行的副作用。此机制不把库存回滚当作恢复策略；真实游戏库存守恒仍需客户端/服务端玩法测试。

原生 append 和 notifyUpdate 在已验证的事务批次内合并，每个触及的 conveyor 在物品移动完成后发布一次原生 BE 更新。失败后也发布已发生的原生状态；不会逐包裹发布中间快照。该优化仍使用 Create 原生 BE 复制，观察客户端专用增量还未接线，不能据此认定网络带宽目标达成。

## 回退与生命周期

清空、读档、移除、断链及结构变换使原对象退出对应 lease。暂停只有模拟列表读取变化，容量、保存、拾取和库存仍访问原完整对象。原生列表删除的回调不能把已转移/取走的包裹恢复回旧列表。再次接管使用新 generation，稳定 ID 不变，旧 GPU flight 保留自己的旧 generation；native netId 不作为网络身份。每个 session 记住已使用 generation 的水位，读入旧存档后必须提高该水位，不能向仍保留旧身份的 sidecar 追加相同身份；同一稳定 ID 也不能同时拥有两个活动 generation。

速度、方向、连接、端口过滤或路由结构变化使相关 track 的 lease 退回；按 track 索引直接定位所属对象，不扫描其他链路的全部包裹。当前策略重建描述并分配新 track index，没有无缝拓扑迁移或索引回收。最多 32 个节点的掩码、候选/track 容量及资源不足均保持 Create 所有权。整体 session 关闭只发送 epoch CLOSE，避免为每个对象重复发送终止包。

服务端不接受客户端物品操作或地址修改。无准备好的 authority、协商失败、掉线、越出订阅、超时或异常都会保留/恢复 Create 管理。客户端资源关闭、GL 失败和资源重载先撤销其 session/原生成员，再销毁 ring/solver；门禁开启前仍须补齐服务端/观察端恢复及真实生命周期测试。

## 验证范围

新增协议/服务端状态测试覆盖完整 long 身份、高 Y、uint 掩码、正文长度、两阶段及重复控制、最终基线超时、4096 个活动对象无逐对象心跳读取、整批拒绝、精确重复 ACK、旧身份/退休索引、预告竞争、回调异常和按 track 失效。ASM 额外核对实际 Create 6.0.10 的原生导出、预告、append 和通知描述符。

新增真实 GPU acquisition 测试覆盖完整握手、共享 free/chain 索引差异、实际模型部件计数、反转吊具、失败帧、重放/旧消息、隐藏 admission 在途释放、prepared 资格重写和满池拒绝。查询及紧急检查点另覆盖零负载、工作组边界、131072 候选、四槽耗尽、提交失败、退休交还、精确身份、前后姿态及换 epoch。它使用真实 GL 提交和模拟 transport，不运行实际 Minecraft 库存、Mixin 注入或网络循环。查询和检查点组件微基准均未测量整帧/服务器 tick/网络吞吐；之前的 GPU 内核和 CPU 容器微基准也不能证明 131072 活动包裹验收。Iris 主/阴影已经接线，见 [hook 报告](benchmarks/package-iris-hooks-2026-09-30.md)，仍需游戏内验收。接下来补齐光照提前覆盖、服务端/观察端增量，测量真实事件批次及带宽。历史记录见[客户端接线报告](benchmarks/package-chain-acquisition-2026-09-30.md)、[姿态查询报告](benchmarks/package-pose-query-2026-09-30.md)及[检查点报告](benchmarks/package-chain-checkpoint-2026-09-30.md)。
