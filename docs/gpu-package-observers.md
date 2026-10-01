# 包裹观察者增量协议（内部，2026-10-01）

## 状态与范围

已实现**自由包裹的服务端下行协议、提交日志、协议参考状态器、GPU 观察者组件和有界 wire→GPU controller**。GPU 组件可以合并字段、预测/校正姿态，并通过 `PackageMixedPhysicsGpu` 的观察者域和 `PackagePoolGpu` 进入共享通用粒子池，每个可见包裹占一个槽位。原生路径已接入实际包 hook、成员订阅、当前 generation 的可见 admission 和 Renderer/Flywheel 所有权；自定义姿态 controller 仍仅供内部验证。按用户最新要求，带宽优化与完整对照移至后续版本，已撤除带宽门禁；双端实验配置、资源和所有权检查仍必需。Create 原生实体同步、锁链 BE 同步及未接管的观察者渲染仍保留。不能把这个阶段视为多人观察者优化或 131072 活动包裹验收完成。

该协议通过独立的可选 registrar `gpu-package-observers-3` 协商；权威协议现为 `gpu-packages-7`，FREE_READY/CHAIN_READY 位定义不变。v3 增加原生成员模式，观察者 v1/v2 不可按 v3 解码；权威 v6 及更早不能协商 v7 的控制批处理。v3 的包内/相对位置动作及 v4 精确区间 ACK 保留；服务端 tick 结束时只合并成功序号，客户端确认路径仍核对完整身份和 flight stamp，详见[ACK 报告](benchmarks/package-ack-network-2026-10-01.md)。v5 上行预测只改变 wire 整数，成功提交后观察日志仍是精确绝对位置；默认未选择预测路径，成本与限制见[v5 报告](benchmarks/package-predicted-network-2026-10-01.md)。订阅不赋予权威能力，不接受物品、地址、拾取或库存操作。后续锁链观察者将采用轨道和时间基准，不能用自由包裹的逐位置下行代替。

带宽进一步优化不属于本次目标。现有默认使用相对已确认基线的位置残差、批量 ACK/控制，观察者只订阅精确成员变化并复用原生姿态包；只有权威可见接管确认后才抑制该连接的重复原生运动下行。实验 predicted 无损位移编码仍供内部比较，未切换默认。历史完整流量对照尚未通过，原始方法与限制见[原生 payload 对照](benchmarks/package-network-payloads-2026-10-01.md)及[控制批处理报告](benchmarks/package-control-batch-2026-10-01.md)。

v3 残差 + v4 ACK 的后续[实际压缩帧对照](benchmarks/package-network-framing-2026-10-01.md)仍未通过：131072 异速运动、20Hz、阈值 256，单客户端保留原生位置流后的组件合计为原生的 2.157 倍；没有以假设停止重复流替代生产接线，也未以组件数字代替全部连接字节。

## 提交与身份

`PackageAuthorityRegion` 仅在 FINAL_READY 成功、lease 已 GPU_OWNED 后向 `PackageObserverFeed` 登记成员；OFFER 和准备期间的冻结状态不进入观察者视图。增量的所有服务端 adapter 与 lease 提交成功后才更新日志；非法、旧序号或回滚批次不发布位置前缀。失败后释放 lease 的精确退休事件仍会发布。正常释放、超时、区块/世界卸载均使旧成员退休。

每个下行 envelope 包含维度、空间区域、authority epoch、区域 revision、独立 observer stream epoch 及序号。新成员基线包含服务器局部索引、完整 `(long id, long generation)`、lease epoch、基线 revision、量化 pose、实体 ID/UUID、模型和尺寸。后续索引始终指向这份身份基线，不是通用粒子槽位。接管可能乱序完成；局部索引在一个 authority epoch 中不重用。

位置、速度、yaw、ground/sleep 标志独立发送。日志复用原有 1/4096 位置与 1/1024 速度量化规则，不在本轮改变精度。静止且字段不变的成员无位置记录，也不重复发送模型或物品 NBT。拾取、内容、地址和库存仍由 Create 的服务端对象及已有精确事务管理。

v2 envelope 带 `serverTick`；每条基线/变化携带服务端成功确认该状态的 `stateTick`。wire 用相对 age 表示：全批同一时刻只写一个共享 age，否则每记录写 age；不可出现未来或倒退状态时间。未变化成员刷新确认时间但不生成位置日志。时间是**服务端确认时刻**，尚不是原权威 GPU 模拟时刻。不能据此声称消除了全部网络延迟。

## 订阅与有界调度

v3 的 `SUBSCRIBE_NATIVE` 和 `MEMBERSHIP_ONLY` 模式是世界运行时选择的路径。服务端仅在 GPU_OWNED 成功提交后发布身份基线；原生模式省略全部位置、速度、yaw 和 flags 字段，变化只允许精确退休。它有独立的有界日志，运动提交不会增加日志 serial 或挤掉身份事件。零成员变化时没有下行批次；姿态继续消费 Create/Minecraft 原生包。自定义 pose 订阅仍用于离线对照，生产服务端拒绝它以避免重复流。

`PackageNativeMembershipRegistry` 在整个有界批次通过维度、已订阅区域、authority/stream、连续序号、容量及稳定身份/实体 ID/UUID 唯一性检查后才调用 adapter；退休与重新接管使用不同服务端索引。adapter 的权威排除键包含区域、epoch、索引、完整身份和 lease epoch，迟到释放不能清除一次新的接管。初始兴趣为玩家附近八个固定区域；不存在成员流的区域每 40 tick 重试，已有流不重复订阅。服务端已有订阅限额与 0.25ms 调度预算保持不变。客户端按完整包处理，0.25ms 软预算、每帧最多 1024 记录，积压超过 100ms 触发恢复。

带有存活成员的 stream 关闭/替换仍要求世界资源重建；槽位和 metadata 高水位回收、动态兴趣迁移尚未完成。不能把该安全恢复当作大规模活动包裹的默认解法或验收收益。相关代码与真实 GPU/协议验证见[接管接线报告](benchmarks/package-native-observer-acquisition-2026-10-01.md)。

`ServerboundPackageObserverPacket` 只有 SUBSCRIBE 和 UNSUBSCRIBE。服务端检查当前世界、区域可见范围、非旁观模式和协商通道；忽略权威所有者对自身区域的观察订阅。每玩家最多八个区域，控制消息共享现有每 tick 512 条限额。UNSUBSCRIBE 必须命中精确 stream epoch，旧关闭请求不能撤销新流。

初始快照按有序索引逐批读取当前已提交状态，不集中复制或扫描整个区域。调度每批最多 64 个工作记录，每 tick 全部观察者共享 0.25ms 软预算及最多 64 次轮询；忙流轮转，空闲流一轮后停止。预算在批次边界检查，一批工作可能越过截止点，不能据此声称采集或 tick p95 已达标。

快照与运动日志各自获得工作份额，持续运动不能无限阻止基线发现。每个快照索引区间记住读取时的日志 serial；更旧的排队增量不会覆盖该区间的新基线。一个批次中的同索引更新合并为最新绝对字段，输出排序后使用共享 `PackageDeltaCodec`。常见单调序号路径直接使用日志列表，只有重复/乱序时建立合并树。

日志默认容纳 262144 个成员变化记录，可容纳两波全部 131072 成员的变化；首次订阅时才分配，最后订阅退出时释放。它不是无限积压队列。慢观察者日志被覆盖时使用全新 stream epoch 重新发送基线；模拟不等待观察者或网络 ACK。该容量与预算尚不能保证 131072 持续活动成员的实时下行吞吐，必须继续优化并测量。

网络包最多 128 个总记录，增量正文最多 8192 字节，模型路径最长 256 字符；解码在分配记录前检查剩余数量预算和尾部字节。编码复用线程局部暂存空间，不向 payload 暴露可变缓冲。

## 客户端协议参考与后续 GPU 接线

`PackageObserverReplica` 验证完整基线、顺序、退休、容量、身份唯一性和字段标志，然后原子提交。普通姿态批次使用预分配 scratch 与原地索引更新；不复制全部成员表，也不为每次位置更新重建身份集合。成员变更只按受影响记录建立临时覆盖表。遗漏序号、未知索引、重复引入、超容量要求重新订阅；迟到 stream 或精确旧 CLOSE 不改变有效新状态。

它仍是协议参考状态器及 CPU 基准消费端，不逐包裹写实体位置。生产 GPU 组件 `PackageObserverGpu` 使用 144 字节专用状态、128 字节完整引入/变化命令，以及 64 字节变化/退休命令。完整命令含稳定身份；紧凑命令以完整 authority epoch、stream 和不重用的 server index 定位已确认身份，GPU 检查本地映射、生命周期及完整序号后才能修改。紧凑命令不能引入身份或重写模型尺寸。`PackageObserverPatch` 只复制整数和字段掩码，不在 CPU 上合并、预测或计算姿态。

`observer_validate` 检查整批范围、身份/namespace、字段、重复目的地及单调时间/序号；barrier 后 `observer_apply` 才修改。任一非法记录令整批和后续批次停止修改，sticky 错误只能通过新的 namespace 和资源重建恢复，不能靠 shader 重编译清除。`observer_sample` 在 GPU 上预测、平滑校正、判断移动状态过期并输出现有 64 字节 body 和 32 字节 history。history 两端是相同的当前呈现姿态，普通/Iris 顶点不重复进行网络插值；观察者不进入权威接触网格，也不执行库存副作用。0.05s 校正及 0.1s 预测目前是内部初始参数，仍需用户视觉验证。

输入使用四个独立上传缓冲及 fence，只零 timeout 检查。满槽时 `tryApply`/`tryApplyCompact` 返回 false，计数、版本和 GPU 状态不变，adapter 必须保留字段和精确退休事件重试。`PackageObserverFeedbackGpu` 使用独立四槽 16 字节控制快照，保留 namespace epoch、来源对象、publication 和该提交的槽位数；满槽不确认 dirty publication。结果只在 fence 完成后读取，拒绝、过期或超过 0.1s 的反馈积压需交还 Create。换世界/stream 时 `invalidate` 丢弃旧结果并替换未完成 storage，不等待 GPU。两个 controller 已消费这些结果；原生世界 adapter 在匹配健康反馈与当前提交的可见 admission 后才关闭原生渲染，失败时清除相应所有权。

四个 compute 程序全部编译成功后才替换；失败保留原程序及状态。新增 `observer_retire` 以完整 64 位 authority/stream 在一个 GPU pass 退休成员，保留其他区域和不可重用身份，不逐包裹上传关闭 pose。布局和浮点校正的 GPU 验证使用真实驱动，紧凑/完整路径按状态、body、history 逐字节比较。带工作组 barrier 的采样 kernel 使用有效标记处理尾部，所有线程参与 barrier，不依赖专属 subgroup。固定 uniform 在创建时设置，动态 uniform 缓存；引擎拥有的 pass 中批量绑定。

`PackageObserverGpuController` 支持至多八个区域，1024 个包/131072 记录的有界输入，完整维度/authority revision/epoch/stream/序号与身份检查，四槽上传重试及精确退休保留。同包新成员与变化使用不同 GPU 操作序号；资源或容量不支持的成员保持 Create。CPU 只处理身份和资源，不合并当前 pose。`PackageObserverClock` 使用稳定服务器 tick 坐标，队列等待计入呈现时间，到达抖动不重写历史 receipt；网络单程估计显式传入。队列/反馈超过额外 100ms、失败帧或非法批次调用一次回退。只在完整引擎提交成功后 `committed` 捕获反馈；生命周期回调只能暂存隐藏 metadata，不能等同可见 admission。域达到高水位、迁移竞态及一小时钟窗仍需重建/回退，索引回收尚未完成。

启用前还必须完成：当前 generation 的可见 admission 和实际世界恢复、订阅迁移与重新进入、原生观察同步复用及权威重复同步安全抑制、原模拟状态时刻与网络延迟采样，以及锁链轨道时间基准和 Sable 父结构坐标。共享 GPU 域和池导入已通过组件验证，世界运行时现使用原生观察者域和混合发布构造器，且仍受带宽门禁保护。带宽门禁尚未通过，不能仅因 controller 测试成功开启。

## 验证与性能限制

### 原生实体下行的 GPU 消费组件

`PackageObserverGpu` 可显式传入 `NativeOrigin` 构造原生域；`PackageMixedPhysicsGpu` 对应重载将其接入原有 body/history 发布。自定义协议域和原生域不能混用输入。原生域保留不可重用的本地 visual ID/generation、完整资源 epoch、signed entity ID 和 64 位序号；这些 ID **不作为服务端物品或库存身份**。UUID/实体 ID 复用的世界绑定与 committed admission 已在原生 adapter 中实现。

`PackageNativeObserverPatch` 直接复制 double 基线、原生 signed short 位移、raw /8000 速度和 signed byte yaw。普通命令为 64 字节，引入为 128 字节；不在 Java 上合并位置、求姿态或重新量化。每成员新增 64 字节 double 侧缓冲，保留绝对 codec base 和 per-tick velocity。GPU 按实际 `VecDeltaCodec` 的 Math.round 规则处理相对位移；零位移轴保留原始 double（包括非网格坐标和 signed zero）。速度更新按 Create `PackageEntity.lerpMotion` 的旧/新速度平均规则处理。传送精确替换 codec base，朝向和 onGround 独立修改；速度/旋转包不延长位置的新鲜度。

`observer_native_validate` 整批校验命名空间、生命周期、单调序号、时间、位置范围、raw 字段和重复目的地，然后 `observer_native_apply` 才修改两份状态。失败整批不发布前缀，错误保持 sticky。沿用四槽 fenced 上传和一组全部编译后替换的程序。内部初始平滑为 0.15s、预测为 0.2s，适应原生三 tick 插值/包间隔；这是**待视觉验证的参数**，还未复刻 Create 刚出生包裹的客户端碰撞校正，不能宣称轨迹视觉已对齐。

同一实体的多个原生相对包不能简单求和或只保留最新包。`PackageNativeObserverCommands` 用预分配直接缓冲与整数链表维护有界队列；每波每成员最多一条，后续波保持原顺序，不做 CPU pose merge。最多配置四波容量；GPU 槽忙则保留完整 staged 波。队列满返回 false，adapter 必须恢复该原生所有权，不能丢位移、退休或交接事件。队列单线程使用；原生 handler 返回后在客户端线程收集 raw 命令，GL 上传与提交确认只在引擎边界执行。

这条路径消费**已经存在的原生包**，组件本身不新增网络 payload。新增成员订阅只含身份与退休，运动不发送重复 pose。v6 已补入可见确认后的权威客户端原生运动停发；观察者连接仍收到原来的包。失去跟踪撤销 lease，交还以绝对位置/速度及下一条原生位置的重同步恢复，保留实体的拾取和失败恢复已接线，但游戏验证仍缺失。自动订阅仍被带宽门禁阻止，不能把“观察组件新增网络包为零”当成“GPU 权威总带宽已达标”。见[权威下行报告](benchmarks/package-native-downlink-2026-10-01.md)。

GPU 入口：

```powershell
.\gradlew.bat validatePackageNativeObserverGpu --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```

加 `-PpackageNativeObserverBenchmark` 测量原生命令上传、合并、采样与共享发布，见[原生消费组件报告](benchmarks/package-native-observer-gpu-2026-10-01.md)。真实驱动对照包含 0/1/63/64/65/131072、实际 Minecraft `VecDeltaCodec`、半整数舍入边界、世界边缘双精度、零轴、signed ID/yaw、速度平均、传送、原子失败、退休复用、尾部哨兵、失败重编译及超过四帧的满槽重试；有界队列也直接驱动 GPU 验证相对包及退休的顺序。

JUnit 覆盖 131072 基线分批导入、乱序完成接管、持续运动中的基线发现、订阅期间增删、独立字段合并、日志溢出、慢/快观察者并存、原子失败、完整 long 身份、高维度坐标、迟到关闭、局部索引不重用及有界解码。真实服务端提交组件还验证准备期不可见、非法前缀不发布及 adapter 回滚只发布退休事件。游戏内的多人、实际网络、GPU admission 和原生恢复尚未验证。

独立入口：

```powershell
.\gradlew.bat benchmarkPackageObservers --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```

它测量 10000、65536、131072 个成员、0/1%/100% 字段变化，预热后三次重复，记录 CPU p50/p95、线程分配、实际编码字节、批次数和初始基线大小。服务端日志、实际 packet 编解码和参考状态器在同一 JVM 顺序运行，消费者排空整波数据；不模拟 0.25ms 预算、socket、服务端实体回调、GPU 或整帧耗时。结果及限制见[下行基准报告](benchmarks/package-observers-2026-10-01.md)。

GPU 入口 `validatePackageObserverGpu`；加 `-PpackageObserverBenchmark` 测量完整/紧凑变化上传、GPU 合并、采样和共享发布，见[GPU 报告](benchmarks/package-observer-gpu-2026-10-01.md)。原 CPU packet 解码/日志成本并未因此消失，两份微基准不可直接相减作为整帧收益。
