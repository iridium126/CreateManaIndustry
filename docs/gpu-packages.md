# Create GPU 包裹：实现进度与内部契约

## 当前状态（2026-09-29）

**通用槽位、模型绘制、权威协议和世界碰撞准备已实现，真实 Create 包裹尚未启用 GPU 接管。** 服务端已有稳定身份、最终基线握手、受确认 lease 控制的 travel 暂停和玩法回退钩子；客户端目前不声明生产资源就绪，收到接管请求会明确拒绝。因此实际包裹仍由 Create 模拟和渲染。普通 Renderer、Flywheel visual 与锁链物流 tick 尚未抑制，没有声称达到 131072 活动包裹整帧 60 FPS。

已实现：

- `PackageLease`：稳定身份、权威 epoch、接管基线修订号、两 tick 超时、过期提交拒绝、连续事务序号及回退检查点。休眠心跳无需重复发送位置。此类只负责协议状态，不执行库存事务。
- `PackageDeltaCodec`：有界批次、排序 ID 差分、独立字段掩码、位置/速度/偏航量化、变长整数编码、缺失基线与非法值检查。
- `PackageAuthorityRegion` / `PackageAuthorityManager`：服务端区域选举、身份映射、两阶段最终基线握手、整批先校验后提交、每 tick 累计位移限制、回退检查点和 O(1) 区域心跳。运动包裹不能用心跳掩盖超过两 tick 的状态积压。
- `PackageIdentityData` 和 Create mixin：持久化的服务器全局 ID/epoch 分配器；自由实体使用 NeoForge persistent data，链上包裹保留原存档并追加完整身份。实际 jar 6.0.10 已核对方法描述符；尚未进行启动后的 mixin 注入与真实保存/加载验收。
- `gpu-packages-2` 可选 payload：接管 offer、资源 prepared、最终基线 ACK、活动/回退通知、区域心跳、增量及提交 ACK。新增增量 RELEASE 掩码；客户端正文上限 24576 字节，codec 单批最多 2048 条，GPU journal 每包至多 512 条。内容和地址不进入位置协议；服务端没有接受客户端库存操作。
- `PackageCollisionCache`：主线程分批捕获、250µs 默认软预算、分区修订号、不可变后台输入、最多四个后台任务、非阻塞完成轮询及陈旧结果丢弃。
- `PackageWorldCollisionSource`：从已加载世界提取实际 AABB、摩擦、水/岩浆/火标记；不向后台传递世界或区块对象。移动活塞、细雪、脚手架及缺失邻块上下文返回不可用，禁止解释为空气。
- `PackageCollisionRuntime`：惰性创建，两条 daemon worker；客户端 tick 按 250µs 软预算捕获。方块修改立即撤销涉及的相邻 section，区块加载/卸载撤销九个关联 chunk 列的已请求高度，换世界直接失效。请求只加入 section 身份，不能同步扫描全区域。待办队列与删除为常数时间；重复编辑在尚未重新捕获时合并。
- `PackagePhysicsGpu`：GPU 空间哈希、静态 AABB 扫掠、四轮并行 Jacobi 接触、锁链进度及摆动；缓存 uniform 位置并使用批量绑定。
- `PackageReadbackRing`：四个独立 staging buffer 和 fence；满槽拒绝提交，不阻塞等待；按序消费并检查 epoch，清空时用新存储替换仍在执行的旧拷贝。支持指定源偏移/有效长度的 ≤1MiB 分片；传输 journal 满时保留已完成槽和 CPU 拷贝，后续帧不重复下载正文。
- `PackageDeltaGpu`：自由包裹的 GPU 量化、脏字段比较、共享内存工作组压缩及有界输出。四个不可变输出 bank；读回不推进基线，只有完整身份、候选、服务器局部索引及 stamp 都匹配的 ACK 才确认。溢出候选保持脏状态，取消发送不会丢弃回退事件。支持不重置已有 flight 的身份追加及服务端终止身份通知。
- `PackageDeltaChannel` / `PackageDeltaJournal`：先读 16 字节计数，再以四槽 ≤1MiB 分片复制有效正文；有界后台排序、编码和不可变 payload 构造，全客户端最多四个未完成编码任务。网络队列拒绝时保留记录，ACK 后才更新 GPU 基线并复用 journal。实际 ACK 入口与主帧调度已接线；生产资源门禁、观察客户端和链路参数同步仍待完成。
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

`packagecollision capture` 排队捕获玩家周围约一 section 半径的真实世界形状，后续 tick 执行预算化采集。无参数显示就绪 section、最近采集耗时、最近 128 次捕获的 p50/p95 和软预算超限次数；`clear` 撤销覆盖并关闭 worker。此入口用于检查方块/区块失效和主线程预算，不移动真实包裹，也不代表数据已上传 GPU。缓存最多 1024 个 section；缺失/不支持的数据保持未就绪。

阵列从相机前方开始，并沿执行命令时的水平视线展开。`stats` 的 `live` 包含成功导入的包裹槽位，但来自异步 GPU 快照，可短暂滞后；持续为 0 并且没有包裹可见不属于正常启动。`stats` 另显示 queued / active / 初始化失败 / shaderpack 停止状态，初始化失败会通知聊天并保留日志。

已修复运行时着色器加载误用文件系统路径的问题：Minecraft ResourceManager 接收 `createmanaindustry:shaders/particles/...`，不能接收 `createmanaindustry:assets/createmanaindustry/shaders/particles/...`。内部入口统一使用 `loadParticlePlain("packages/...")`。驱动验证现在从处理后的运行时资源加载，复用生产 `ParticleShaderSource` 的路径及 include 解析，覆盖此前直接读源码文件而遗漏的接线问题。Java 接线修改需要重新构建并重启客户端，仅按 F3+T 不会替换旧 Java 类。

`CMIParticleEngine.packageParticles()` 是项目内部 render-thread 接口，首次申请时分配资源；未使用该接口时不创建包裹 GPU 缓冲。附加表按最大 131072 个包裹分配，不随通用池数百万槽位放大。

接管适配器需先上传模型 ranges、包裹 metadata，再指定 solver 的状态、链路与历史 buffer 及区域原点。metadata 每项 80 字节：

| 行 | 内容 |
|---|---|
| uvec4 0 | 稳定 long ID 与 long lifecycle generation，均按低/高 uint 保存 |
| uvec4 1 | body index、箱体 mesh、吊具 mesh（-1 无吊具）、CHAIN/FLIPPED 标记 |
| vec4 2 | 保留的接管基线字段；当前插值从 GPU history 读取 |
| vec4 3 | 保留、保留、hook distance、原生 packed-light 位模式 |
| vec4 4 | Create ground nudge xyz、保留 |

导入只接受有效物理 body 和完整模型；静态 collider、回退标记与容量之外候选不会分配通用槽位。未知 mesh/body index 在 GPU 解引用前检查。每个成功提交的 admission 表包含完整稳定身份及当前 `slot+1`，0 表示未分配；表项顺序对应候选序号，槽位顺序可能随 GPU 筛选改变。

**admission 仅证明本次绘制槽位已分配，不是服务端接管确认。** 适配器必须通过独立 staging ring 读取已完成代次，并核对 region/epoch/基线；不能直接用 CPU 请求数量取消 Create 渲染。metadata 上传用于初始化/变更，目前还没有为真实对象实现脏区间整理器。Box / rig 共享状态，输出的实例索引同时携带候选索引与部件位，避免依赖厂商 draw-parameters 扩展。

绘制计算由普通粒子发射后的 GPU counter 控制；成功后才发布 admission、commands 与附件 bank。shader 重建先验证全部包裹程序，编译失败保留旧程序。GPU 求解器应在普通引擎 pass 外更新，进入引擎边界时恢复状态；包裹导入和绘制会使引擎绑定缓存失效。

### 绘制提交与进一步合并

所有包裹 mesh（包括箱体和吊具）已经用一次 `glMultiDrawArraysIndirect` API 调用提交；内部仍有多个 mesh 子命令。MODEL 的实体、手持物和透明外壳已用一次 `glMultiDrawElementsIndirect` 提交。调用次数不随包裹或 MODEL 粒子数量线性增长。

无光影时，包裹与 MODEL 可以在统一几何寻址、实例记录、着色器及纹理访问后进一步合并，但目前二者使用不同 VAO、非索引/索引几何、block/allay atlas、姿态和光照路径。包裹关闭混合；MODEL 透明外壳使用混合并写深度。跨类型合并必须保留这些行为，不能仅拼接 indirect buffer。

OPAQUE 与包裹可考虑统一不透明 pass；ALPHA 与 Hex 使用相近混合状态，但还必须保留各自深度写入和透明排序顺序。ADDITIVE 采用不同混合因子，继续保留独立 pass。Iris 的 MODEL 主渲染/阴影插入点也要求单独验证。没有启用尚未通过正确性和同机性能对比的统一路径。

`/cmi particle profile on` 后预热，再运行 `/cmi particle profile`，现在可见 `package_physics`、`package_import_group`、`draw_packages`、`draw_models`、各 sprite / Hex 绘制阶段的异步 GPU 时间，以及粒子引擎跟踪到的绘制 API 提交次数。multi-draw 计为一次；零实例间接命令仍计为提交，不等于有可见工作。该统计不包含 Minecraft/Create 自己的所有 draw call。

## 数据与执行契约

### 身份、接管及网络

服务端身份使用 `(long id, long generation)`，lease 的 epoch 为 long。网络增量中的 int ID 是**区域基线局部索引**，不是粒子池索引；后续 payload 必须携带区域、完整 epoch、基线修订号及序号，并用基线映射回稳定身份。

配置 `gpuPackages.authorityEnabled` 默认 false，即使开发者启用，当前客户端也不会发送 FREE_READY / CHAIN_READY。服务端 discovery 限制 64 次/250µs 每 tick，选举只按区域进行；目前开关需在包裹加载前配置，不会扫描已加载世界补注册。

初始 offer 期间 Create 继续运动。资源准备完成的 PREPARED 请求使服务端捕获**当前**位置、速度、yaw 和 ground state，并仅暂停 travel；客户端必须上传最终检查点，再回 ACK 当前修订。最终基线窗口独立两 tick 超时，不能通过区域心跳延期。存活/伤害/库存回调仍执行原 Create 逻辑；机器插入、交互、伤害、外部推挤、内容变更和保存前先回退。客户端只能提交姿态，不能提交物品操作。当前保留普通实体生命周期和 vanilla 位置同步，不能据此承诺服务端或网络目标已达成。

量化候选值为位置 1/4096 方块、速度 1/1024 方块/秒、偏航 360/65536 度。这些尚未通过游戏内视觉对照，不能视为最终默认协议。超出局部坐标或速度范围时编码器拒绝，后续网络层必须使用全状态逃逸记录或回退，不允许饱和截断。

codec 编码批次最多 2048 个记录；GPU journal 使用 512 条/包，每包最坏正文 17410 字节。后台直接编码原始整数记录，复用排序和输出空间，不逐包裹创建 `Entry` / `Quantized` Java 对象。不可变网络包的复制也在 worker 完成，渲染线程只将已准备的包交给可靠有序传输。增量基线只能在确认后更新。读回成功不等于服务端已提交，更不等于库存事务已提交。事务序号门禁不能代替库存操作的原子提交与错误恢复。

RELEASE 使用单独的掩码 16，不携带量化位置，也不能与姿态字段混合。服务端验证整包后按最后提交检查点交还 Create；同包中的其他对象正常提交。拾取与在途增量发生竞争时，已退休且从未复用的区域基线索引视为无操作并确认，未曾发出的索引仍拒绝。终止通知检查完整身份和 epoch，只调用一次客户端生命周期回调；旧 generation 或迟到 ACK 不能停用新对象。

`PackageDeltaGpu` 输入为每候选 32 字节身份/选择表与 32 字节确认整数基线；输出每条 64 字节，包含完整身份、候选索引、服务器局部 ID、掩码、回退标记和量化状态。工作组只做一次全局区间预留，所有尾部线程参与共享扫描屏障。输出不保证按网络 ID 排序，由 worker 按每包 ID 排序并编码。零变化仅需要读 16 字节计数 header；131072 条变化的有效记录为 8MiB，需要分片而非扩大单槽同步读取。每个 immutable bank 在全部正文已复制入有界 journal 前禁止释放；满 bank 跳过检测，模拟继续运行。

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

静态扫掠超过 1024 个网格单元或任一方向超过 32 格时输出回退标记并保持原位置。动态高速包裹之间尚未实现连续碰撞；休眠、活动列表、密集接触局部回退和分区覆盖检查也尚未接入。因此不能直接将该 solver 应用于所有游戏内包裹。

当前自由运动初始常数为 32 方块/秒² 重力和按 20Hz 换算的阻尼，需用游戏实际轨迹校准。锁链在 20Hz 下沿用 Create 的摆动递推；其他更新频率的插值、姿态及视觉一致性尚未验收。

物理及历史 pass 重用绑定点 0–5，导入/分组重用 0–9，不扩展通用引擎的绑定数量要求。分别使用 SSBO、indirect-command、vertex-attribute 与 buffer-update barrier。调用者必须在外部渲染边界恢复 GL 状态，并使通用引擎绑定缓存失效。

## 验证命令

```powershell
.\gradlew.bat test --tests '*Package*Test' --tests '*CMIParticleCommandTreeTest' --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
.\gradlew.bat validatePackageGpu --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
.\gradlew.bat validatePackageGpu --offline --no-configuration-cache -I scripts/particles/validation.init.gradle -PpackageBenchmark
.\gradlew.bat validatePackageGpu --offline --no-configuration-cache -I scripts/particles/validation.init.gradle -PpackageDeltaPipelineBenchmark
```

基准输出 `build/package-gpu-kernels.csv`；分片管线另输出 `build/package-delta-pipeline.csv` 和逐次样本文件。当前 **45 项包裹 Java 测试及 5 项命令测试、811146 项真实包裹 GPU 断言**通过（带管线基准为 814845 项）；Hex 全套回归通过 **135498 项断言**。Java 覆盖完整 payload 编解码、非法长度、保存计数水位、4096 个静止对象的 O(1) 心跳、最终基线超时、整批拒绝/回滚、身份/epoch、独立字段、每 tick 累计位移、负坐标/高 Y、碰撞邻居失效及旧 worker 丢弃。另覆盖有界 journal 延迟超过四帧、整分片编码/包构造失败、传输拒绝、准备超时、退休索引竞争及幂等 RELEASE。物品/地址守恒目前在纯协议 mock 中检查，不等同真实游戏库存验收。

GPU 测试保留空输入、64 线程尾部边界、静态支撑、重合包裹分离、高速静态碰撞、过大扫掠回退、锁链批次、四槽耗尽、代次失效、满容量 admission、完整 long 身份、缓冲哨兵、模型分区、实例唯一性及失败帧回滚。另覆盖 131072 个脏候选的完整身份、精确整数基线/掩码、位置未变而速度变化、输出溢出重试、取消后再发、ACK 子批次、错误 stamp/旧 generation、回退事件保留、空载 bank 及程序替换失败。量化检查包括正负 yaw 半整数、大 yaw、位置 ties-to-even、区域边界进位及向量速度上限；GLSL yaw 的常量除法和乘加均保持 Java double 语义。运行时路径/include 检查、65536 个链上候选完整导入、65536 个唯一槽位、131072 个箱体/吊具实例命令仍通过。这些大批次检查不代表真实 Create 同屏玩法或帧率验收。

增加真实 indirect draw 像素检查（包括 baseInstance）、失败/成功 shader 重建，以及 GPU vertex transform-feedback 对照 Create 参考矩阵。光照额外覆盖 Create SBB 双方向 diffuse 和 Flywheel chunk diffuse（含恒定环境光、未着色面），RGB 容差为 2e-5。姿态 xyz 容差 `2e-5` 方块；80 步 20Hz 链上递推对照的位置/偏航容差 `1e-4` 方块/度。测试里的阻塞 GPU 读取和计时结果等待仅用于验证；生产读回类只使用零超时 fence 轮询。上述数值验证不能替代游戏内视觉录像和光照/阴影验收。

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

1. 完成实际 Create 6.0.10 启动/mixin 和玩法核对；将已有服务端选举/握手/回退接到客户端最终检查点与链路交接，补齐幂等物品事务。
2. 将已实现的通用槽位/完整身份 admission 接到真实对象生命周期、确认读回和容量回退，整理变更上传；避免生产批量初始化集中阻塞。
3. 将已实现的模型缓存与绘制连接到真实包裹，确认后抑制原 Renderer/Flywheel，完成 Iris/阴影接入及整个资源集合的事务替换。
4. 碰撞快照的速度预取、分批上传、实际 GPU 覆盖及第三方动态形状资格检查；休眠、活动调度和动态连续碰撞。方块/区块事件失效已实现。
5. 将已验证的 GPU 压缩、非阻塞分片/journal 和最终确认接到实际物理提交；补齐观察客户端订阅、链路参数化同步和跨区域唯一接触所有者，停止重复 vanilla 同步。
6. 游戏内视觉对照录像、玩法/多人回归，以及包含真实绘制和网络的 131072 活动包裹完整性能验收。

生产客户端资源门禁完成前，不得启用 Create 模拟/渲染抑制，也不得把本阶段内核测量当作方案验收结果。当前受 lease 控制的 travel 钩子只为已验证资源的后续客户端准备，不会被预览或未准备的客户端触发。
