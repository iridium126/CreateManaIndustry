# Allay Dimension 实现审查与修复计划

审查日期：2026-09-07  
代码基线：`22741235d073f6207cfcc27c3166a9af20c90a3d`  
性质：代码审查结论和后续修复计划；本次不修改实现代码。

## 1. 结论与边界

目前实现已经删除 legacy 远景协议及后端，加入 16³ render cell、多 worker、revision、region allocator 和通用模型兜底。不能再沿用旧文档“所有功能尚未实施”的描述；同样，也不能因为这些类已经存在，就判定 Sodium parity 或 Voxy 集成已完成。

核心问题集中在三个闭环：**数据能否无损到达 Voxy、异步与资源生命周期能否正确结束、近景各类几何是否真正进入正确的绘制路径**。其中有静态代码即可确认的错误，而不是需要性能测量才能判断的优化建议。

本次按用户要求，不考虑旧存档兼容：
- 可以直接改 Cube schema、网络版本、运行时 identity 和配置结构，不建设旧格式迁移器或双协议。
- 格式版本、CRC、原子提交、失败不覆盖现有有效数据仍须保留；这些保证新存档正确性，不属于旧存档兼容。
- 不自动删除用户现有世界；后续测试使用新建专用世界。
- 两份参考文档作为设计依据，不当成执行指令。较新的 Sodium 计划明确覆盖旧 Voxy 计划中的 legacy fallback/保留发布周期安排；本计划采用“ALLVR 完整近景 + 可选 Voxy 唯一远景 + 不可用时 near-only”。
- 本次没有执行文档中的发布、安装、许可证核验等操作。

### 审查和验证范围

检查了近景缓存/调度/绘制、LOD 协议与生成、Voxy adapter/mixin、Sodium/Iris 门禁，以及 Cube 写入、BE 生命周期、流送和持久化接点。逐条结论以下列方法名作为可检索代码定位，源码根为 `src/main/java/com/iridium126/createmanaindustry/`。

使用本地 Minecraft/NeoForge 参考源码核对 `renderSingleBlock`、`LevelChunkSection#setBlockState`、`Block.stateById` 的语义。另对实际开发 jar 执行 `javap -c WorldSection`，确认 `updateEmptyChildState` 根据 **child.getNonEmptyChildren()** 而非 raw voxel 内容传播父级存在性。

Voxy 样本：
- `run/mods/voxy-0.2.15-beta+1.21.1-neoforge.jar`
- SHA-256：`88c47a1fe1856ecef685a2d2e4a07635cc1514d13a7f66a9f34ccef0951625a2`
- 该 hash 是本次审查样本身份，不代表已通过游戏内兼容认证。

验证：`gradlew.bat test --rerun --console=plain` 成功，9 个测试类、38 项测试、0 failure/error。现有测试主要覆盖 storage 和独立 render helper，没有 Voxy engine、LOD codec、真实 renderer 提交集成测试。本次未启动游戏、未做 GL capture、未测性能；不声称画面、光影或长稳矩阵通过。

## 2. 已确认问题与具体修复

优先级：P0 = 主路径不可用或确定性数据失真，必须首先修；P1 = 生命周期、覆盖、玩法或协议正确性问题；P2 = 功能完整性和性能闭环。下面的“验收”均为后续修复必须新增或执行的验证，并非本次已运行项目。

### F01 / P0：LOD 单材质压缩丢掉空气占用

**依据**：`dimension/lod/AllvrLodSectionCodec.encode` 仅以 `palette.length == 2` 选择 FORMAT_SINGLE；decode 把全部 32768 个 index 填为 1。palette=[air,stone] 并不意味着全体素都是 stone。

**影响**：只有石头一种非空气材质的岛屿表面、洞穴边缘，甚至单个石块，会变成实心 32³ 采样节点。

**修复**：只有全部 indices 都等于同一非空气 index 时才能使用 uniform；air+一种材质仍编码 occupancy/index 流。协议直接升级，不兼容旧错误编码。

**验收**：单石块、半空石头、棋盘 air/stone、全石头、全空气逐体素 round-trip；light 同时一致。

### F02 / P1：LOD bit width 编解码契约不一致

**依据**：同文件 `bitWidth` 输出任意 ceil(log2(paletteSize))；decode 只接收 1/2/4/8/16。例如含空气共 5 个 palette entry 会编码为 3，却被自身 decoder 拒绝。

**修复**：统一采用连续位宽或统一取整到约定位宽；pack 中移位操作显式使用 long，避免以后扩展位宽时发生 int 移位截断。以 palette 总大小定义唯一上限。

**验收**：覆盖 palette size 2、3、4、5、8、9、17、257 以及最大合法值，随机 indices 往返完全一致。

### F03 / P1：LOD payload 验证不足，128 KiB 上限也不覆盖声明的合法域

**依据**：codec 不检查尾随字节、light flag 仅“0/其他”二分、decode 后不检查 index < palette.length；`AllvrLodSectionData` 构造器没有结构校验且数组公开可变。`Block.stateById` 对未知 ID 返回 AIR，所以检查 null 不会拒绝坏 ID。32767 个非空气 state ID + 最坏 indices + light 可超过 `ClientboundAllvrLodSectionPacket.MAX_PAYLOAD_BYTES=128 KiB`。

**修复**：构造只读、已验证 payload；严格检查 format、flag、位宽、palette、indices、ID、position、剩余长度。直接查 registry 并拒绝不存在的 ID。依据真正支持的 palette/索引域推导最大字节数，或明确缩小格式域并提供合法编码退路，不能服务端产生客户端必拒收的包。

**验收**：最大合法编码能接收；坏索引、非法 ID、负数、尾随数据、未知 flag、短包均在注入前拒绝。补 bounded fuzz。

### F04 / P0：Tier C 普通 draw 实际提交零条命令，且顶点属性未启用

**依据**：`client/dimension/render/backend/AllvrCompatBackend.appendDrawEntry` 使用 absolute put(n,...)，不移动 position；`draw(n)` 随后 flip()，得到 limit=0，没有按 n 设置有效范围。`ensure` 配置了 attribute 0/1 的 pointer，却没有 glEnableVertexAttribArray。

**修复**：提交时 position(0)、limit(n)，或完整改为 relative put；创建 VAO 时启用 attribute 0/1。用真实 GL smoke 验证命令数及顶点来源。

**验收**：强制 Tier C，分别提交 1/2/N 个 cell，实际 draw count=N，坐标和材质正确；空帧后恢复、多帧数量增减均正确。

### F05 / P1：Tier C 仍依赖 Tier B 初始化；失败回退存在提前返回

**依据**：`AllvrRenderer.initialize` 无条件调用 `buffers.ensure`，后者分配 SSBO/indirect buffer；Tier C readiness 仍依赖 buffers.ready。Tier B capability 只查 draw-parameters/indirect-parameters，没有完整能力集合。`onRenderStage` 在 terrain program 不 ready 时先 return，可能根本到不了 latchTierC。initialize 提前设置 initialized=true，失败无事务回滚。

**修复**：分离通用材质资源与 B 专属资源；C 只初始化其实际支持的 GL 资源。完整探测版本、extensions、limits；任何 B 初始化/主 shader/compute 失败均进入统一事务回退，成功后再标 initialized。会话切换明确重置 failure latch。

**验收**：GL 3.3/C 强制模式、B 主 shader 错误、compute 错误、分配失败分别运行，近景始终可见，GL 调用不越级。

### F06 / P1：没有 descriptor 时，通用模型、流体和 BE 一并被跳过

**依据**：`AllvrRenderer.compatDraw` 在 quadsUsed==0 返回；`gpuDraw` 在 nodes.highWater()==0 返回。fallback 和 BE 只在后续 drawTerrain 中执行。

**修复**：按 descriptor/general/fluid/BE 各自的可见集合提交；descriptor 空不能跳过整个 terrain frame。即使无 descriptor，仍生成相机/frustum context 并绘制其他流。

**验收**：新场景只放水、火把、花、箱子或部分模型，不放任何 descriptor 方块，全部可见。

### F07 / P1：descriptor 认证不足，非 solid 还可能双绘

**依据**：`AllvrRenderStateMap.resolveEntry` 只查六方向各一 quad、没有 unculled 和动画；没验证顶点是否全单位面、真实 UV/旋转、normal、颜色、render type、ModelData、weighted/offset 等。`CLIENT_CODEC` 只判断 renderable；worker 的 fallback 判断却额外检查 solid。

**影响**：具有六面 quad 的玻璃等 state 可能同时进 opaque descriptor 与透明 fallback；旋转 UV、非满块面和位置相关模型也可能错误扁平化。

**修复**：建立统一认证结果，供两条流共同消费。首版仅白名单或严格证实等价的静态 solid 完整面进入 descriptor；其他全部通用编译。不能只看 quad 数量证明等价。

**验收**：玻璃/冰、旋转原木、资源包 UV、weighted、offset、multipart、自定义 RenderType/ModelData；每个 quad 只有一个 owner。

### F08 / P1：含水方块的模型与流体被当成互斥分支

**依据**：`AllvrMesherWorker.collectFallbackBlocks` 以 fluid=true 标记整个 block；`drawFallbackBlocks` 的 if(fluid) 只 renderLiquid，else 才 renderSingleBlock。

**影响**：含水楼梯、栅栏等无法通过 descriptor 的模型，本体消失。

**修复**：一个 state 分别生成 model geometry 和 fluid geometry；两者独立 pass、材质和排序，不共用互斥 boolean。

**验收**：含水楼梯、台阶、栅栏、箱子与普通水；本体和水各绘制一次。

### F09 / P1：renderSingleBlock 不是世界模型编译路径

**依据**：`drawFallbackBlocks` 每帧调用 renderSingleBlock；本地 NeoForge 实现使用 null world/pos 取 tint，并将 chunk render type 转成 entity render type，调用 renderModel，而非按邻域进行世界 tessellation。

**影响**：不能保证绝对位置随机、offset、逐顶点 AO/light/tint、相邻剔面；静态复杂模型每帧重新构建。

**修复**：实现有 world snapshot、绝对 BlockPos、ModelData 的通用 cell compiler；产出持久 vertex/index/material streams。按 render type 遍历 culled/unculled quads 和确定性 random seed，复用正确的世界 tessellation 语义。

**验收**：与同 seed/同模型/同光照的原版或 Sodium 展台对比；相机不动时不得逐帧 tessellate 静态 cell。

### F10 / P1：透明、阴影、破坏覆盖尚未形成真实 pass 管线

**依据**：`drawFallbackBlocks` 每帧按 block center 排序，并经公共 BufferSource.endBatch 绘制；缺少 quad sort data/跨平面触发及 cell 内排序。`drawShadowPass` 只使用 descriptor commands；没有通用模型/流体对应阴影路径。terrain/BE 没有独立 crumbling/outline 提交。

**修复**：solid/cutout/translucent 分开提交和排序；复用稳定 quad sort data，预算不足保留上一顺序。为支持的材质生成独立 shadow list；加入 destruction overlay 与 outline，正确匹配发布 revision。

**验收**：玻璃交叠、水墙、cutout 植物阴影、破坏裂纹、发光 BE；Iris off/on 各测。仅排序 block center 不能作为完成标准。

### F11 / P1：Iris/GL 失败路径会选错 shader，状态恢复不完整

**依据**：`drawTerrain` 的 Tier B 分支把 prog 覆盖成 patchedTerrain，若 patched/albedo 都为 0，仅日志提示 fallback，实际 prog 仍为 0并返回。beginFrame 失败时即使 Tier C 也改选 shaders.terrain()。恢复逻辑依赖可变 mode，且没有外层 try/finally；模型异常也会跳过 popPose/FBO 恢复。常规路径只重置部分状态到固定值，不等于恢复调用前状态。

**修复**：把已验证的 backend/pass/program/target 选择封装为不可变 draw context；失败明确退到该 Tier 的有效 shader。统一 scoped GL state guard，finally 恢复 framebuffer（read/draw）、viewport、program、VAO、绑定、blend/depth/cull 等实际修改状态；每次 pushPose 有 finally pop。

**验收**：patch 编译失败、target 失效、模型异常注入后，下一 pass/下一帧无状态污染；用 capture 检查 pass target，而非只看日志。

### F12 / P1：近景异步结果存在同 key 重建 ABA

**依据**：`forgetCell` 删除 RenderCell 后，新对象 revision 从 0 重新开始；旧任务和重载回来的 cell 可有相同 key/epoch/revision。`pumpResults` 在检查 epoch 之前移除 pending。scheduler 按 key 复用取消 flag，submit 又把它设 false，能解除旧任务的取消。

**修复**：引入唯一 build ticket = worldEpoch/resourceEpoch/cellIncarnation/contentRevision/jobId；pending 只按相同 ticket 结算。每任务独立 cancellation token，不能复用重置。forget/reapply 增加 incarnation，旧任务不能匹配新 cell。

**验收**：用 latch 控制“旧任务运行→forget→同 key 重到→新任务完成→旧任务完成”，旧结果既不能发布，也不能删除新 pending。

### F13 / P1：deferred mesh 没有 revision，能覆盖新结果

**依据**：`applySuccess` 只保存 deferredQuads/deferredFallbackBlocks；`retryDeferred` 不验 revision 并按当前 contentRevision 发布。fallback-only 成功分支没有清除旧 deferredQuads。

**修复**：deferred 保存完整 ticket 和所有几何流，重试前校验；任意新成功结果，包括空和 fallback-only，都原子替换或清除旧 deferred。发布失败保持原 handle，不先修改当前 allocation 字段。

**验收**：强制 arena 满→revision A 延迟→编辑至 B（含空/通用-only）→释放空间，A 永不复活。

### F14 / P1：resource reload 复用 ID，旧 mesh 与新材质表不匹配

**依据**：`onResourceReload` 清空并重建静态 AllvrRenderStateMap，却保留旧 GPU mesh；worker 仍分步调用 idOf/entryOf，可能跨越清表。resourceRevision 没有随 job/snapshot 传递。

**修复**：不可变 resource snapshot 与 build ticket 绑定；旧 mesh 保留对应材质版本直到替换完成，或采用原子整批切换。worker 不读取正在清空/增长的全局表。资源准备失败必须可恢复。

**验收**：在构建中反复 F3+T、改变 texture/model/render type；无数组越界、错贴图、旧材质 ID 解释为新 state。

### F15 / P1：运行时 identity 与调度状态持续增长，任务关闭不完整

**依据**：`AllvrRenderCellKey.BY_ID/BY_COORDINATES` 为永不回收的静态 map；邻居查询也会创建 key。`AllvrBuildScheduler.cancellations` 正常完成不移除；clear 不取消已有 token；close 不等待 worker 结束，仍可能产生结果。`AllvrRenderWorld` 并未真正拥有 scheduler/GPU 资源。

**修复**：直接使用不可变坐标三元组作为 CPU identity，或会话 registry+引用管理；不存在的邻居查询不分配永久 handle。scheduler 有界队列、每任务 token、完成释放、关闭等待/隔离迟到结果；资源由唯一 session owner 关闭。

**验收**：30 分钟飞行及 100 次维度切换后，identity/token 数量随活动窗口回落；关闭后无结果写入新会话。

### F16 / P2：worker 快照、重试、上传预算没有覆盖实际成本

**依据**：buildCell 的输入只是 key，稍后读取全局 cache；block snapshot 与 light capture 是两次独立获取。`AllvrCellLightBaker.capture` 在 LOCK 内逐 cell 扫约 4.7 万天空样本，所有 worker 串行争锁。`prepareCube` 主线程扫完整 32³；`retryDeferred` 无预算。`applyFailure` 对 retryable 也不自动重试。`Priority` 没有距离重排，进入场景的普通任务都为 NEAR_CAMERA。

**修复**：捕获同版本的 blocks/light/model data；共享增量 light brick，缩短锁内工作。为 snapshot/apply/expanded C upload/deferred 重试共同计时计字节；worker/result 队列背压和按距离/可见性重排。retryable 有上限退避，fatal 隔离。

**验收**：故障恢复无需再次编辑；多 worker 确有吞吐提升；测锁等待、主线程 p95、队列年龄，不能只统计 descriptor 8B/quad。

### F17 / P1：近景光照仍穿墙，边界失效不全

**依据**：`AllvrCellLightBaker.block` 直接按曼哈顿距离衰减，不传播遮挡；天空只有 128 高窗口的 0/15 判定。`AllvrCellMesher` 每个合并面采一个中心光值。`onBlockChanged` 只在 emission 变化时 dirty 周边光照；遮挡变化不会完整更新侧向 block light，普通 border dirty 也不覆盖 AO 对角依赖。`forgetCell` 不 dirty 留存邻居。

**修复**：共享跨 cell/cube 光照缓存，处理透光率、遮挡和动态 emission；逐顶点 light/AO，限制合并条件。以真实依赖范围 dirty；cube 到达/替换/忘记均通知依赖邻居。

**验收**：隔墙火把、破墙/补墙、跨 cell 发光、洞口、斜角 AO、邻居 forget；新旧网格无永久残面或错误光照。

### F18 / P1：近景垂直覆盖与 LOD 排除区直接矛盾

**依据**：`AllvrCubeMap` SEND_XZ_RADIUS=8、SEND_Y_RADIUS=4，forget Y=6；`AllvrLodClientState.walkLevel` 对三轴用同一 minDist，L0 从 cube 距离 9 才请求。

**影响**：静止玩家正上/正下相对 cube 5～8 没有近景首包，也不请求 LOD；即使曾加载，Y 超过 6 后会回收。near-only 雾固定 320 也不是保证覆盖距离。

**修复**：短期把近景 send Y 统一为 8，并按实际 cell/AABB 计算 fog；正式方案让客户端、服务端共享 coverage 定义，支持各向异性时必须按轴排除。单独评估增大 Y 半径的流送成本。

**验收**：沿六轴逐 cube 扫 coverage；上下看和连续升降没有未分配 owner 的区域。

### F19 / P1：分带计算不一致且没有 readiness handoff

**依据**：客户端按玩家 cell 的 Chebyshev index 选择，服务端 `AllvrLodMap.chebyshevToNode` 按节点 AABB 最短距离校验；bitmap 使用半开区间，而客户端内边界统一加1。确定性例子：玩家位于(0,0,0)，L0 bitmap X为[-16,15]，正X最多覆盖到511；L1要求cell距离至少9，正X从576开始，因此正X轴[512,575]没有任何一层负责。更外层同类边界也需穷举。resident 的外侧25%滞回还允许前一细级与后一粗级共存；near forget 与 far resident 都没有 GPU-ready 交接。

**修复**：定义统一 coverage/AABB 判定库和确定性 cell ownership；替换硬编码 band 常量推断。接入 near-building/far-fallback/near-ready/far-building 状态机与帧边界切换；没有 Voxy GPU-ready API 时只可采用明确标注的保守延迟与 fog。

**验收**：所有层级的正负边界及玩家局部偏移 0/1/15/31；不持续 request/forget 循环，不长期空洞或双绘。

### F20 / P1：Voxy 普通 forget 的 key 编码错误

**依据**：`VoxyLodBackend.forget` 入参是绝对 AllvrCubePos long；writer.Forget 原样传给 `AllvrVoxyEngineOps.forgetNode`，后者参数语义是 virtualKey，使用 Voxy decoder 和 registry key 查找。

**修复**：外部接口只接受 AbsoluteLodPos；单写者使用当前窗口将其转为 VoxySectionKey。内部 detach 单独接受强类型 virtual key，禁止两种 long 混用。

**验收**：非零 X/Z、负 Y、非零 origin 下 inject→普通 forget 后 node/parent/引用全部清理，邻居及时重建。

### F21 / P1：Voxy section acquire/release 不平衡，leave 不释放 registry

**依据**：`writeSection` 每次 acquire 后成功路径不 release；registry.register 遇到已有 entry 只更新字段，没有吸收/释放新引用。chainAncestors 的已有祖先发生 mask 改变时也可能重复持有；detachFromParent 的继续向上分支缺少临时 acquire 释放。`VoxyLodBackend.releaseAll` 实际只 writer.clear，leave 随即丢弃 registry。

**修复**：用明确引用协议：每 entry 恰有一个 owner ref，每临时 acquire 都 finally release；重复写使用既有 owner 或释放临时 ref。leave 按细到粗销毁，dead engine 路径仅执行安全的引用释放，不向已销毁 engine 写 dirty。

**验收**：重复写同节点 1000 次、祖先增删、rebase、退出和 engine recreation，引用差为零；失败中途也平衡。

### F22 / P0：孤立 L1～L3 几何无法建立可达祖先链

**依据**：`writeSection` 只有 L0 调 updateLvl0State；更高层填 raw data 后 children 仍为 0。`chainAncestors` 调 updateEmptyChildState。实际 jar 的该方法只看 child.getNonEmptyChildren()!=0，因此孤立 L2 有体素也不会给 L3/L4 设置可达位。registry 没有参与 Voxy 消费端 leaf 判断的 hook。

**修复**：回到原计划 P0 gate，针对目标 jar 实现“data-owned leaf”与“topology children”分离的最小桥接。父级存在性来自实际 owned leaf/descendant；不能伪造全 children=255 制造不存在子节点。L4 topology 生命周期也必须明确拥有或有可验证的 engine 持有保证。

**验收**：只注入一个孤立 L1/L2/L3，不注入 L0，能显示、更新、删除；再测粗细共存与切换。

### F23 / P1：forget 粗节点会掐断仍存活的细节点

**依据**：`forgetNode` 直接 registry.take 并 clearSectionData，后者清零 child mask；没有检查该节点是否仍有 owned descendants。

**修复**：分别维护 dataOwned 与 topologyOwned；forget 仅撤销自身数据，有子节点时保留 topology entry/ref/mask。最后一个后代移除后才向上级撤销位。

**验收**：粗节点+两个细子节点，删除粗节点后两子节点仍可达；逐个删子节点后祖先最后回收。

### F24 / P1：Voxy rebase 只有阶段名，没有 epoch 闭环

**依据**：beginRebase 不清 writer queue；Inject/Forget 无 windowEpoch；requestsOpen 只判断 active 非 DISABLED，DETACH 仍开放请求；MOVE 后客户端 resident/empty/pending 不清。旧队列在新 origin 下执行，已被 detach 的 resident 节点不会重请求。REFILL 固定60 tick内不检查新 rebase；dead engine 若发生于 DETACH，releaseAll 清掉 detachQueue 却不重置 state。

**修复**：FREEZE 先递增 epoch、关闭请求、取消旧队列/回包 ownership；DETACH 真正细到粗并有时间预算；MOVE 原子发布 origin；重置对应 resident 并 REFILL。连续移动可中断旧 refill，engine replacement 强制新 epoch 和全状态重置。

**验收**：队列有积压时 rebase、REFILL 中传送百万 Y、DETACH 中 engine 销毁；无旧节点复活、空指针和永久空窗。

### F25 / P1：enqueue 被当成 resident；写失败和 forget 背压被静默吞掉

**依据**：backend.apply 返回 enqueue 是否成功，客户端立即 resident.add；writer 忽略 writeSection 返回值，异常仅日志。forget 使用相同 QUEUE_CAP，调用方忽略 enqueue false。

**修复**：拆 REQUESTED/QUEUED/INJECTED/RENDER_READY，writer 结果回调按 ticket 确认；拒绝/异常归还请求并有退避。forget/epoch cancellation 不得因数据队列满而丢失，使用独立控制队列或可合并墓碑。预算按耗时+体素量而非固定8条。

**验收**：强制 queue 满、超窗、mapper 异常；失败不会被永久 resident，forget 必达且无日志洪泛。

### F26 / P1：requestId/generation 没有真正参与客户端归属校验

**依据**：`AllvrLodClientState` pending 只是 key set，applySection 不检查 requestId/generation；C2S request 没有客户端 requestId，cache hit 返回 -1。忘记后重请求同 key，旧应答能消费新 pending。server PrepJob 也没有保存请求 identity；drainSectionResults 只比 gen、不比 Request.id。

**修复**：客户端生成 requestId 并随 request/session epoch 发送；服务端共享 build 与每个订阅者 ticket 分离，缓存响应回填原 ticket。section/empty/forget/reject 都包含同一版本上下文；客户端只接受当前 ticket 和非旧 generation；PrepJob 和结果严格匹配 server build id。

**验收**：旧包晚到、旧 forget 晚到、cache hit、同 key 重新请求、两个玩家共享构建、编辑中旧 prep 完成，均不影响新请求。

### F27 / P1：服务端 build 失败永久占用 pending

**依据**：`AllvrLodMap.runSectionJob` catch 后直接 return，不移除 requests、不发失败结算；客户端 pending 没有超时。单层达到256失败请求后停止请求新节点。

**修复**：worker 总是产出 terminal result，主线程用精确 ticket 释放并发槽并返回 retryable/fatal；客户端 timeout + 退避，服务端超时取消、每玩家公平限额。

**验收**：强制生成器/codec 一次异常后自动恢复；持续错误不占死全层，不产生无限重试。

### F28 / P1：编辑过的天然非 surface 区域永远不进入远景

**依据**：`refreshBitmaps` 仅调用程序化 `AllvrLodField.compute`；没有把 persisted/edited 节点并入 bitmap。`onBlockChanged` 只 forget 和 cache invalidation，不改 bitmap。客户端只请求 surface bit=1 的节点。

**修复**：维护 edited coverage 空间索引，bitmap=自然候选∪编辑候选；编辑、载入索引、删除 overlay 均更新 bitmap revision。不得只在玩家移动达到阈值后才发送更新。

**验收**：虚空建造、岛屿内部挖洞、重启后未加载的编辑区，走出近景后仍正确显示。

### F29 / P1：LOD 邻域依赖未参与 invalidation

**依据**：startCapture 为边界/光源读取 stride+16 padding；onBlockChanged 仅使包含修改块的每级一个节点失效。

**修复**：根据几何采样、光照范围和 overlay padding 计算受影响节点集合，批量合并 dirty；相关 bitmap 一并更新。不能只依赖客户端六邻居重网格弥补服务端过期 light/体素 payload。

**验收**：每级边界修改、节点外侧光源增删和遮挡变化，相邻节点 generation/payload 更新。

### F30 / P1：Voxy 缺失/禁用/故障的 near-only 未端到端生效

**依据**：manager.requestsOpen/farTerrainActive 只判断后端类型，engine=null/DETACH/内部失败仍算启用。select 未使用 allvrLod 总开关；tick 的总开关早退不清后端。`AllvrLodMap.refreshBitmaps` 对所有玩家生成和发送 bitmap，没有 near-only capability 订阅。

**修复**：唯一 mode + 有效 runtime availability；客户端握手通知服务端 LOD subscription，OFF/不可用取消订阅和请求。engine 未就绪只有限等待，故障锁存或有节制重连；所有 failure 路径同步 fog、resident 和诊断。

**验收**：Voxy 缺失、用户关 Voxy、allvrLod=false、运行中失败时，server bitmap/build 数量为0，远景无残留；恢复订阅能重新填充。

### F31 / P1：版本门禁晚于 mixin 应用，ingest 门禁看错对象

**依据**：`CMIMixinPlugin` 对 .voxy./.sodium. 只检查 mod 存在；Voxy probe 稍后才检查 version.startsWith，且只查部分符号、不查返回类型/field type/injection 点。未知 ABI 可能先因 mixin 启动失败。`AllvrVoxyIngestMixin` 忽略 identifier，按当前 mc.level 全局判断。

**修复**：bootstrap 用元数据/字节码资源进行精确 artifact+descriptor+注入点预检，避免提前加载正在转换的目标类；不支持则关闭整组兼容 hook。ingest 按传入 WorldIdentifier 判定；版本档案列齐 Sodium/Iris 组合及实际 hash。

**验收**：目标 jar、同前缀不同 ABI、Voxy 缺失；未知组合安全 near-only。切维度期间其它世界 ingest 不受 Allay 误封，Allay 迟到 ingest 不被放行。

### F32 / P2：Sodium 只取消 draw，没有停止空 shell 构建维护

**依据**：唯一 terrain mixin 目标为 DefaultChunkRenderer.render；没有 section add/dirty/build tracker 门禁。注释仍引用0.6.x，而参考计划使用0.8.13基线。

**修复**：针对实际固定版本取消 Allay 空壳的 section build/维护，保留 drawChunkLayer 本身和 Voxy hook；明确可验证的注入位置与失败保底，不进行近景 Sodium 数据桥。

**验收**：Allay 内 Sodium build/task/cached shell section 指标不随飞行增长；Voxy render hook 每帧执行；其它维度不变。

### F33 / P1：Cube setBlock 缺少 block 生命周期回调，且同状态判断错误

**依据**：`AllvrCube.setBlockState` 直接调用 LevelChunkSection.setBlockState，后者总返回旧 state，不会因相同 state 返回 null；map/client cache 却只用 oldState==null 判断无变化。服务端写入没有 LevelChunk 等价的 old.onRemove/new.onPlace。

**影响**：重复确认也 dirty、递归通知和存盘；容器移除、放置初始化、需要回调的红石/机器逻辑不能保证正确。

**修复**：写入前比较旧新 state；按 vanilla/NeoForge 顺序实现必要回调和递归再读取，正确传 isMoving/flags，BE 移除/保留顺序一致；禁止把 LevelChunkSection 的返回契约当 LevelChunk。

**验收**：相同 state 写入不增加 mutationVersion；拆容器内容、放置机器、状态切换及回调中再次 setBlock 行为正确。

### F34 / P1：BE 客户端同步和销毁缺失，不能仅补 renderer

**依据**：full cube 包携带初始 tag，block update 明确不带 BE 数据；markBlockEntityDirty 只持久化。`AllvrClientCubeCache.clear/forgetCube/applyCube` 不对旧 Cube 执行 onUnload。server/client BE 显示依赖的 NBT 更新、原版窄 Y 包、sendBlockUpdated 等尚未建立 Allay 完整通道。`drawBlockEntities` 按 cell frustum 一律剔除，未处理 off-screen/global renderer/Flywheel owner。

**补充依据**：`AllvrCube.updateBlockEntity/rebindTicker` 保留同类型 BE 时只换 ticker，没有调用 be.setBlockState(newState)，因此 BE 自身缓存的朝向/状态可落后于 Cube state。

**修复**：增加 Cube+localIndex 的 BE update/event 通道和 revision；接通机器更新通知，处理 chunk 外高 Y。同类型BE保留NBT但更新自身BlockState及相关派生数据。替换/忘记/退出时销毁旧 BE、释放 capabilities/实例资源。BE visibility 尊重 renderer bounds/off-screen；明确 Flywheel 与 vanilla 唯一 owner。

**验收**：高 Y 箱子/牌子/燃烧器/动态机器数据更新、reload/forget/refill、超 cell 范围 BE、Flywheel 开关；无 stale NBT、幽灵实例和双绘。

### F35 / P1：随机 tick 和 Cube scheduled tick 尚未实现

**依据**：`AllvrServerLevelMixin.tickChunk` 主动取消原版随机 tick，注释明确 Cube random/scheduled ticking 待完成；AllvrCubeMap.tick 仅补 BE tick，没有 Cube 方块/流体 tick 所有权和持久化。

**修复**：建立 Cube 原生 scheduled block/fluid tick 队列（绝对整数坐标、唯一 tick identity、due time/priority），接通 Level 调度入口；在模拟范围执行 random tick。当前格式直接新增 tick 列表，无旧格式迁移要求。

**验收**：水/岩浆传播、作物、树叶、红石延迟、tick 卸载再加载/重启；超高 Y 无 key 截断。将玩法缺口与“流体能画出来”分开验收。

### F36 / P2：GPU 预算、handle 与可见性模块只是部分落地

**依据**：AllvrBuffers 接收 RegionArena.Handle 后只返回 offset，generation 未进入 GPU metadata；free 不校验 handle。上传仍 glBufferSubData，growArena 复制全 high-water 数据。allocator满后等待 deferred，没有驱逐/紧缩闭环；达到512MiB cap仍可能反复创建同容量 buffer并复制。`connectivityMask` 只存储，未用于 graph；其6 bit边界开放性也不是 face-pair 连通关系。Hi-Z 只在相机/矩阵完全不变时启用。GPU 写入已有 clamp，但无溢出恢复机制。

**修复**：先保留正确性的 frustum 路径；GPU scene 持有完整 generation handle，真正 fence staging、预算驱逐和避免无效 grow；满容量时能有限恢复。需要 connectivity 时实现 flood-fill face-pair graph并接入候选；移动 Hi-Z 必须保守重投影，失败退 frustum。异步反馈 overflow并分批恢复，不能永久丢 geometry。

**验收**：随机 alloc/free/ABA、容量耗尽、编辑风暴、移动剔除、overflow；上限内显存与 backlog回落，无错复用/闪洞。NV mesh shader属于可选项，不阻塞这些修复。

## 3. 额外审查约束与需要补证的风险

以下内容不能凭静态分析宣称游戏内已复现，必须列入后续验证：

1. **Voxy并发读取一致性**：EngineOps直接写共享 raw array。单写者仅防止多个CMI writer，不代表Voxy mesher读不到半更新。需检查目标jar的read/copy/lock协议，采用上游支持的安全写入方式，并用并发快照测试证明整段一致。
2. **Iris generic/BE targets与全局uniform所有权**：当前公共BufferSource可能在具体RenderType setup时重绑目标；共享hook又按Voxy“安装”而非有效availability让出。需逐pass capture，验证Voxy被禁用/不支持但Iris存在的组合；不应直接推断所有光影包都会黑屏。
3. **超高Y玩法边界**：现有C2S还原只处理部分交互包，原版BE更新、block event、破坏进度、声音/粒子和其它mod使用BlockPos.asLong的路径不能据此视为全覆盖。按实际用到的包逐项审计，必要时直接设计Allay专用协议，不做旧协议兼容。
4. **持久化长期正确性**：现有storage测试通过，未发现足以要求重写region格式的证据。但新tick/BE格式必须补异步写入失败、重启、损坏记录、save-all flush测试；审查未进行真实掉电测试。
5. **生命周期清理遗漏**：AllvrRenderer.close没有dropIrisState；dropLevel不清sawFirstMesh，不重置全部session latch；修复F14/F15/F11时一并归入唯一资源owner，验证FBO引用和新session行为。
6. **服务端预算与公平性**：LOD dispatchPrep在无async overlay分支continue，绕过尾部deadline检查，REQUESTS_PER_TICK常量未用于实际dispatch；结果drain无预算。near流送全玩家共享deadline，前序玩家可耗尽预算，玩家为空时提前返回也跳过unload扫描。后续改成分阶段预算、轮转玩家、即便无人仍维护回收，测多人尾延迟。
7. **动画/ModelData变更**：把动画state排除descriptor并不等于完成可见sprite activation；BE model-data请求也未证明会dirty对应ALLVR cell。需追踪当前Sodium/NeoForge实际hook，补可见sprite和模型数据变更测试。

## 4. 实施顺序与交付拆分

不建议直接按旧文档M0～M8重新从头做。先修当前已落地主路径的确定性错误，再完成渲染语义；每个批次可独立review，但存在以下依赖。

| 批次 | 工作与问题映射 | 交付物 | 退出条件 |
|---|---|---|---|
| R0 协议正确性 | F01～F03、F26、F27 | 新版LOD codec、client ticket/server shared-build协议、有界失败结算 | occupancy/全位宽/恶意包/乱序/失败测试通过 |
| R1 Voxy可达性与释放 | F20～F25、F31 | 强类型坐标、引用ledger、leaf拓扑桥、epoch rebase、bootstrap probe | 单个L0～L3 inject/update/forget闭环；所有引用平衡 |
| R2 近景可靠保底 | F04～F06、F11～F15 | 可运行Tier C、统一build ticket、版本化资源、事务GL生命周期 | 纯通用场景可见；旧结果不复活；B失败可回退C |
| R3 coverage与订阅 | F18、F19、F28～F30、F32 | 统一coverage、编辑bitmap、near/far handoff、capability订阅 | 六向/各层无长期空洞或双绘，near-only服务端零LOD工作 |
| R4 完整世界渲染 | F07～F10、F17、F34渲染部分 | 通用vertex/index编译、model/fluid双流、光照、三pass、BE/破坏/Iris | 模型/流体/透明/BE展台与基线无系统性缺失 |
| R5 Cube玩法与新格式 | F33～F35 | block回调、BE协议、random/scheduled ticks、新schema | 新世界机器与方块行为、tick/BE重启恢复正确 |
| R6 性能与清理 | F16、F36及第3节预算项 | 有界调度、GPU上传/驱逐、统计、可见性；更新旧文档 | 达性能门及长稳门，身份表/显存/队列回落 |

R0与R2可在工程上独立推进，但本计划不要求创建并行agent。R1通过前不把Voxy声明为已完成；R3需要R0/R1/R2提供可靠状态。R5的BE数据语义是R4复杂机器验收的前置条件，可先完成其协议部分。

### 建议的具体接口边界

- `AbsoluteLodPos(level,x,y,z)` 与 `VoxySectionKey` 为不同类型；禁止对外暴露无语义long参数。
- `BuildTicket(worldEpoch,resourceEpoch,cellIncarnation,contentRevision,jobId)` 贯穿scheduler、result、deferred、publish。
- `LodRequestTicket(sessionEpoch,windowEpoch,requestId,position)` 为每客户端独立请求；server buildId不代替客户端ticket。
- `LodResidency` 至少区分requested、queued、injected、renderReady、failed；不能再使用一个resident集合表达所有阶段。
- `RenderResourceSnapshot` 不可变，持有材质/model数据版本；GPU allocation引用对应snapshot。
- `CoverageMap` 输出空间owner与handoff状态，供near cache回收、LOD请求、渲染与fog共同消费。
- `AllvrRenderWorld.close` 拥有取消、worker结果隔离、GPU/frameTarget释放、identity释放的唯一职责；AllvrRenderer收缩为事件适配。

这些是落实修复的方法，不要求为了命名机械拆包；接口边界和不变量优先于新增类数量。

## 5. 回归测试清单

### 自动化测试

- Codec：F01/F02/F03所有往返及坏包案例，含payload最大值。不能只测air或常量stone。
- Protocol：通过可控消息队列模拟旧section/forget晚到、同key重新请求、两个玩家共享build、失败与超时。
- Render lifecycle：真正驱动scheduler→renderer发布接点；现有单独AllvrRenderCell测试不足以覆盖pending/deferred问题。
- Voxy fake engine：每次acquire/release计数、data/topology所有权、ancestor mask；另用实际jar做孤立高层leaf集成测试。
- Coverage：穷举玩家cell内偏移、负坐标、六轴、各band边界、垂直半径，验证没有无owner区及server/client判定一致。
- World：同state写、回调重入、容器移除、BE sync、tick保存/恢复。
- Allocator：随机序列与旧handle释放；输出所有活区无重叠、generation失效和容量上限不被突破。

### 游戏内与GL矩阵

固定记录：
1. 常规空岛与虚空人造结构。
2. 楼梯/栅栏/草overlay/weighted/ModelData/动画模型展台。
3. 水、岩浆、含水、玻璃、多层透明、洞穴与遮墙光源。
4. 普通/global BE、Create/Flywheel机器、破坏与outline。
5. Y=0、±4095/4096、±1,000,000、±29,999,900，以及负X/Z与cell/cube边界。

组合：无Sodium/Iris/Voxy；Sodium only；Sodium+Voxy；Sodium+Iris；三者齐全；Voxy缺失/禁用/错误ABI/engine重建。强制C、主流AMD/Intel B、NVIDIA B分别测；支持的shaderpack版本固定并记录。

动作：静止、连续上下飞、跨band往返、传送、死亡重生、disconnect/rejoin、F3+A、F3+T、shader reload、resize。GL capture确认generic/descriptor/BE每pass的framebuffer/depth和实际draw count，不能只看是否报GL error。

### 性能门

沿用参考计划的目标作为**待实测门槛**，不写成既有成绩：
- snapshot+apply+upload流送p95 ≤2ms，单帧预算目标≤4ms。
- 稳定near terrain render-thread slice p95 ≤0.75ms；记录generic编译和透明排序成本。
- Voxy注入p95≤2ms；rebase单帧停顿不超过100ms，并记录refill可见空窗。
- 30分钟飞行+随机编辑、100次维度切换、50次reload/resize，native/GPU/引用/identity不持续增长。
- 记录p50/p95/max、硬件/驱动/模组版本、样本数及排队延迟。未达门先归因，不以丢模型、跳透明或虚报fog覆盖换取通过。

## 6. 完成定义与文档修订

完成后同时满足：
- F01～F35的正确性/功能问题都有对应测试或明确完成的游戏内证据；F36和预算项达到门槛或有经评估的保守正确路径。
- Voxy故障只影响远景，不破坏近景；no-Voxy时没有远景订阅、bitmap生成和反复probe。
- 新存档支持本次定义的方块、BE和tick语义；不保留旧格式读取/迁移分支。
- 新协议版本直接拒绝不匹配构建；内部没有“猜格式”的兼容逻辑。
- 更新两份旧计划的状态，删除“Voxy已完成”“所有模型已通用兜底”“Tier C保证可见”等尚未经验证的完成声明；以实际测试/capture附件支撑新状态。
- 不把性能类stub、注释中的承诺或helper单元测试通过，当成整条实际渲染管线完成。
