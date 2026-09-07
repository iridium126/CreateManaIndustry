# Allay Dimension 近景地形渲染器选型与实施计划

> 文档类型：实现计划，不包含实现代码  
> 修订基线：Create: Mana Industry `46ac70c`；Minecraft 1.21.1 / NeoForge 21.1.236 / Java 21  
> 对照基线：`.refs/sodium` `mc1.21.1-0.8.13-2-gb3ddb22d`  
> 本次决定：Voxy 集成视为已完成；删除 legacy LOD；Voxy 是唯一远景后端；未安装、被禁用或运行失败时，只渲染 ALLVR 近景，不提供远景替代。  
> 文档关系：本计划覆盖 `allay-dimension-voxy-lod-integration-plan.md` 中“保留 legacy fallback”“新旧网格协议并存”和“等待一个发布周期再删除”的旧安排；其 Voxy section、虚拟 Y 窗口和生命周期设计仍作为现状依据。

## 1. 决策摘要

在两个候选方案中，选择方案 1：继续完善专用 ALLVR 近景渲染器；Sodium 作为渲染语义、调度、透明排序和性能基线，不作为 Allay 数据的运行时接收器。原版渲染器只保留为对照，不建设 Allay 数据桥。

选择理由不是“自研必然比 Sodium 快”，而是 Allay 的数据和坐标约束使方案 2 需要先构造一个成本很高的虚拟 vanilla 世界：

- Allay 权威数据是独立的 32³ Cube，实际 Y 可到约 ±3000 万；当前 vanilla `ClientLevel` 只是 384 高度的空壳。
- Sodium 的 `LevelSlice.prepare`、`ClonedChunkSectionCache` 和 `RenderSectionManager.onSectionAdded` 都从 `ClientLevel -> LevelChunk -> LevelChunkSection[]` 取数，并用 `SectionPos`、正式 build height 和 chunk tracker 管理生命周期。它没有 Voxy `WorldSection` 那样面向外部世界数据的稳定注入边界。
- 一个 32³ Cube 虽然已经由 8 个 `LevelChunkSection` 组成，但仍需拆到 4 个 X/Z vanilla chunk column、映射 2 层 section、补齐光照/方块实体/ModelData，并把绝对 Y 放入会反复 rebase 的虚拟高度窗口。
- 近景垂直直径最坏超过当前 384 高度空壳。若复用当前 `ClientLevel`，必须裁剪近景或改写 build-height 假设；若另建 facade level，又要让 Sodium/原版 renderer、颜色、模型、光照和方块实体全部认识这个 facade。
- 每次 Y rebase 都会改变 section key、render section、邻接图和 GPU allocation 的身份，导致全窗口重新建图、网格化和上传。ALLVR 原生路径可始终使用绝对整数 key，只在提交矩阵时做 camera-relative 变换，没有该峰值。

方案 2 的真实优势是短期功能覆盖和成熟度：Sodium 已经完整处理模型、流体、AO、材质 pass、透明排序、动画纹理、方块实体、构建预算和显存区域。若只看普通高度、稳定视角和复杂方块，Sodium bridge 很可能比尚未补全的 ALLVR 更快、更可靠；原版 bridge 的兼容性较好，但 CPU 构建、上传和 draw-call 成本明显更高。

最终策略：

1. 先彻底删除 legacy LOD，缩小系统状态空间。
2. 保留现有 Voxy section 协议、L0～L3 生成/请求、虚拟 Y 窗口和 Voxy adapter；无 Voxy 时关闭远景请求。
3. 把 ALLVR 重构为只负责近景完整分辨率的会话级 renderer，不再维护自己的远景节点树。
4. 按 Sodium 的职责和可见结果补齐 Minecraft 渲染语义，但使用 Allay 原生 Cube/cell 数据通路。
5. 默认后端为跨厂商 GPU MDI；保留普通索引批绘制；`GL_NV_mesh_shader` 仅作为可选 NVIDIA 加速层。

## 2. 固定范围与所有权

### 2.1 最终画面所有权

```text
0 ～近景边界：ALLVR full-resolution renderer
近景边界～服务器远景半径：Voxy，且仅当兼容 Voxy backend 实际可用
Voxy 不可用：近景边界外不绘制地形，以天空/雾自然结束视距
```

- 不再存在 `LegacyAllvrLodBackend`、服务端 LOD quad、客户端 LOD descriptor draw 或 `AUTO -> legacy`。
- Voxy 不接管近景模型精确渲染；ALLVR 不再生成任何远景 LOD mesh。
- 方块实体、精确流体和动态透明只在近景保证；Voxy 远景继续采用既有降采样语义。
- 近远景交界由单一 ownership 状态机管理，不能仅靠两个 renderer 恰好使用相同距离常量。

### 2.2 保留的 Voxy 路径

以下均为已经完成的远景基础设施，不在本次重做：

- `AllvrLodSnapshot`、`AllvrLodSectionData`、`AllvrLodSectionCodec`；
- `ClientboundAllvrLodSectionPacket`、bitmap、request、forget 协议；
- `VoxyLodBackend`、`AllvrVoxySectionWriter`、`AllvrVoxyNodeRegistry`、`AllvrVoxyYWindow`；
- L0～L3 距离分带、服务端 surface bitmap、generation/失效和 Voxy rebase；
- Voxy/Sodium/Iris 的既有条件加载与渲染 hook。

保留不等于维持双后端协议。请求能力应收敛为“Voxy voxel section 可用”或“无远景请求”，不再协商 legacy mesh。

### 2.3 近景 renderer 的非目标

- 不复制 Sodium 源码或绑定 Sodium 私有类。
- 不修改 Cube 网络/存档格式；32³ 仍是数据所有权单位。
- 不为近景引入虚拟 Y rebase。
- 不重新实现远景 mip、远景层级树或自研 LOD shader。
- 不承诺所有光影包像素级相同；必须保证正确 framebuffer、depth、shadow 和降级行为。

## 3. 当前实现与 Sodium 的关键差异

| 能力 | Sodium 0.8.13 | 当前 ALLVR | 本计划处理 |
|---|---|---|---|
| 数据单位 | 16³ vanilla section | 32³ Cube/34³ snapshot | 渲染内部拆成 16³ cell，数据所有权不变 |
| 完整方块 fast path | 紧凑 chunk mesh | 8 B/quad 贪心 descriptor | 保留并严格认证 |
| 任意 `BakedModel` | 支持 culled/unculled、多 quad、随机、offset | 只收六面完整立方体，每面只取第一 quad | 增加通用顶点流 |
| 流体 | 独立 tessellator | 不渲染 | 补齐近景流体 |
| pass | solid/cutout/translucent | 单 opaque | 三 pass 与材质表 |
| tint/AO/light/normal | 逐顶点 | 面中心近似、无 AO、仅轴向 normal | 对齐可见语义 |
| 透明排序 | section 分类与动态排序 | 无 | 分级排序、预算化重排 |
| 动画纹理 | 可见 sprite 激活 | 无 | 按可见 cell 激活 |
| 方块实体 | 构建期分类、可见列表 | Cube 中存在且 tick，但未进入 terrain 可见列表 | ALLVR 自有 BE 列表 |
| 调度 | 多 worker、优先级、取消、上传预算 | 单 worker FIFO、裸 pending | revision/epoch、多队列和预算 |
| 显存 | region arena、批量 staging | 单全局 arena、频繁 `glBufferSubData` | region + staging ring + generation handle |
| 可见性 | section graph、frustum、occlusion | GPU frustum/Hi-Z/MDIC | 保留 GPU 优势，补 connectivity 与稳健回退 |
| 高 Y | 依赖 vanilla section/build height | 原生绝对整数 Cube key | ALLVR 原生路径保留优势 |
| 远景 | 非 Sodium 核心职责 | legacy + Voxy 双路径 | 只保留 Voxy |
| 低能力 GPU | 成熟兼容路径 | 能力不足时可能整片停用 | 普通索引批绘制保底 |
| 生命周期 | world/section/task 状态完整 | 在途结果、失败 pending、GL close 有漏洞 | M2 先修复 |

## 4. 两种方案的性能比较

### 4.1 方案 1：完善 ALLVR

优势：

- 数据路径最短。Cube packet 解码后的 `LevelChunkSection[8]` 可直接被 16³ cell snapshot 使用，不需要合成 `LevelChunk`、触发另一个 chunk tracker 或维护镜像世界。
- 近景全程使用绝对整数 identity；相机相对坐标在 CPU/GPU 边界拆分，不发生 Y rebase 全量重建。
- 完整立方体和规则表面可继续使用 8 B descriptor 与贪心合并。Allay 岛屿的大面积规则地形有机会显著降低顶点带宽和上传量。
- 可见性、命令生成和 draw submission 可保持 GPU-driven；在高端 GPU 和大可见表面上，性能上限高于原版，也可能高于 Sodium 的 CPU section list 路径。
- 只为实际到达的 Cube/cell 建立节点，不必维护 vanilla 正式高度内的空 shell column/section 图。
- Voxy 负责远景后，不再需要 ALLVR 的 LOD node、LOD quad arena 和远景阴影遍历，近景 renderer 的显存和复杂度都能下降。

劣势：

- 在模型、流体、AO、透明和 BE 补齐前，画面不完整；在补齐初期，通用模型路径的构建吞吐通常会落后于成熟 Sodium。
- 双几何流、三 pass、透明排序、资源重载和 Iris patch 都由本项目维护，验证面大。
- 当前 single worker、全局 arena 和上传方式必须重构，否则 descriptor 的理论优势会被调度和停顿抵消。
- Mesh Shader 只能优化支持 `GL_NV_mesh_shader` 的设备；不能作为默认性能承诺。
- 低端/集成显卡上，成熟 Sodium 的 CPU cull 和 region batching 可能比自研 compute/Hi-Z 更稳定，因此必须保留轻量兼容后端。

### 4.2 方案 2A：把 Allay 数据输入 Sodium

可行形态只有两种，都不是复用 Voxy adapter 就能完成：

1. **Synthetic chunk facade**：把每个 32³ Cube 映射到 4 个 X/Z `LevelChunk` column 和每 column 2 个 Y section，让 Sodium 继续从 `ClientLevel` 读取。
2. **内部 build-context 注入**：绕过正常 chunk tracker，直接构造 Sodium 的 `ClonedChunkSection`/`ChunkRenderContext` 并把任务送入 `RenderSectionManager`。

优势：

- 立即复用成熟的任意模型、流体、AO/light、material pass、透明排序、动画 sprite、BE 收集和错误上下文。
- 立即复用优先级、取消、worker、upload budget、region allocator 和 renderer debug 指标。
- 在复杂方块密集场景中，短期 CPU 构建吞吐和稳定性最可能领先 ALLVR。
- 在普通高度且窗口不移动时，steady-state 画面和性能容易接近已验证的 Sodium 行为。

性能与架构代价：

- synthetic facade 至少复制 chunk/section identity、光照、BE 和 dirty 生命周期；Sodium 构建时仍会把邻域 clone/unpack 到 `LevelSlice`。即使复用已有 `LevelChunkSection` palette，也无法消除双份管理和 build snapshot。
- 当前 384 高度 shell 无法覆盖最坏约 512+ 方块的近景垂直窗口。扩大正式 build height 会影响整个 `ClientLevel`；滑动窗口则会定期重建所有 synthetic columns。
- `SectionPos`、render section key、光照 key、BE 坐标和模型随机种子都必须在虚拟 Y 与绝对 Y 之间转换。热路径算术本身便宜，rebase 引起的图、mesh、upload 全量失效很贵。
- 若近景桥与 Voxy 共用 512 对齐 Y origin，一次 rebase 会同时冲击近景与远景；若不共用，近远景 seam、camera transform 和调试坐标会出现两套空间。
- facade 需要阻止 synthetic 数据被玩法、粒子、实体、声音、寻路或其他 mod 当作真实 vanilla chunk 使用；隔离检查会进入大量世界访问热路径。
- 直接注入 Sodium 内部 context 可少建部分 facade 对象，但会绑定 `RenderSectionManager`、`ClonedChunkSectionCache`、region/task output 等私有 ABI。Sodium 更新后的失配很可能表现为构建结果丢失或 native/GPU 生命周期错误，而不只是安全禁用。
- Allay 当前还需保留 Sodium 对 Voxy 的 draw hook，同时禁止其空 shell terrain。把同一 Sodium renderer 又变成近景 owner，会使 hook 顺序、section tracker 和 Voxy ownership 更难隔离。

结论：Sodium bridge 的稳定视角性能可能很好，复杂模型阶段甚至明显优于早期 ALLVR；但它把最危险的成本集中到垂直移动、传送、reload 和版本升级。对本项目的超高 Y 目标，峰值帧时间、内存复制和维护风险高于其 steady-state 收益。

### 4.3 方案 2B：把 Allay 数据输入原版 renderer

优势：

- 不依赖 Sodium 安装与内部 ABI；大部分 vanilla/NeoForge 模型和 render type 行为自然可用。
- 适合作为视觉正确性参考，调试某个模型是否应被渲染。

劣势：

- 同样需要 synthetic `ClientLevel`/`LevelChunk`、虚拟 Y、光照和 BE 桥，无法避开方案 2 的根本数据适配成本。
- `ViewArea`/section dispatcher 围绕固定 build height 和 chunk column 工作，空 section bookkeeping 更多。
- CPU tessellation、BufferBuilder 分配、上传和 draw-call 合批能力均弱于 Sodium；高速流送和 rebase 时更容易产生长尾卡顿。
- 不提供 ALLVR 当前 GPU command generation、Hi-Z 和紧凑 descriptor 的性能上限。

结论：原版 bridge 在性能上是三个方案中最弱的生产选择。它可以用于离屏/测试对照，不应成为 Allay 近景后端。

### 4.4 汇总判断

下表是基于当前代码路径的工程判断，必须由 M0 基准验证，不代表尚未测量的绝对 FPS：

| 指标 | 完成后的 ALLVR | Sodium bridge | 原版 bridge |
|---|---|---|---|
| 短期功能完整度 | 低→高，需实施 | 高 | 高 |
| 规则岛屿 steady-state CPU/GPU | 潜力最佳 | 好 | 较差 |
| 复杂模型构建吞吐 | 初期较差，成熟后接近 | 最佳/最稳 | 较差 |
| 单方块更新 | 16³ cell 精确 dirty，无桥接 | section dirty + bridge/cache 失效 | section dispatcher rebuild |
| 内存 | 单份 Cube + render snapshot/GPU scene | Cube + synthetic world + Sodium clone/scene | Cube + synthetic world + vanilla buffers |
| 高速上下移动/传送 | 无 near rebase，最佳 | near 全窗口 rebase 峰值最大 | rebase 峰值大 |
| 高 Y 正确性 | 原生 | 依赖完整虚拟化 | 依赖完整虚拟化 |
| 高端 GPU 上限 | MDI/Hi-Z/可选 Mesh Shader | 成熟但受 Sodium 管线约束 | 最低 |
| 低端 GPU 可预测性 | 需专门建设 compat | 最佳 | 可用但慢 |
| Sodium 版本升级成本 | 只维护门禁/Voxy hook | 高，内部 ABI 适配 | 无 Sodium ABI |
| 无 Sodium 安装 | 完整近景 | 必须再准备原版 fallback | 可用 |

决策门：若 M4 完成后，ALLVR 在“完整模型+流体”场景的构建 p95 仍比同画质 Sodium 慢 35% 以上，且 profile 证明瓶颈来自不可复用的模型 tessellation 而非调度/上传，则重新评估一个限时 Sodium build-context 原型。该原型不得先引入 synthetic gameplay chunks，也不得改变本计划删除 legacy LOD 的决定。

## 5. 目标架构

```text
AllvrClientCubeCache（32³、绝对坐标、唯一近景数据源）
        │ cube generation / block dirty / forget
        ▼
AllvrRenderWorld（每个 ClientLevel 一个 epoch，唯一资源所有者）
        ├─ AllvrRenderCell：16³；一个 Cube 对应 8 cell
        ├─ AllvrBuildScheduler：优先级、取消、预算、多 worker
        ├─ AllvrModelCompiler
        │    ├─ 规则 descriptor 流：认证后的轴向可合并面
        │    └─ 通用 vertex/index 流：任意模型、流体、overlay
        ├─ AllvrMaterialRegistry：solid / cutout / translucent
        ├─ AllvrGpuScene：region arena、staging ring、generation handle
        ├─ AllvrVisibility：frustum、connectivity、Hi-Z、pass lists
        ├─ AllvrBlockEntityLists
        └─ AllvrTerrainBackend
             ├─ GPU MDI/MDIC（默认）
             ├─ 普通索引批绘制（兼容保底）
             └─ NV task/mesh shader（可选）

远景（独立生命周期）
AllvrLodClientState -> VoxyLodBackend -> Voxy WorldSection/render pipeline
```

### 5.1 坐标规则

- CPU identity 始终使用绝对整数 cube/cell/block 坐标。
- cell mesh 内只保存 0～16 局部坐标；scene node 保存绝对 cell origin 的整数高低位。
- 每帧把 `nodeOriginInt - cameraBlockInt` 与 camera fraction 分开提交，shader 不接收 ±3000 万绝对 float。
- Voxy 的虚拟 Y origin 只属于远景 adapter，不能渗入 `AllvrRenderWorld`。
- 模型随机 seed、offset、biome tint 和 BE 逻辑使用绝对 `BlockPos` 语义。

### 5.2 双几何流

规则 descriptor 流只接受资源重载时通过认证的状态：轴向 quad、UV 可安全平铺、材质/pass 一致、无位置相关模型变化。任何不满足条件的 quad 自动进入通用流，不允许继续标记 `NON_RENDERABLE` 后静默丢失。

通用流保存真实 position、UV、color/tint、sky/block light、AO、normal、material id 和 index。两条流共享 cell bounds、pass、visibility、upload 和生命周期，不建立两套 renderer。

### 5.3 近远景交界

为每个边界 32³ coverage cell 维护以下状态：

- `VOXY_READY`
- `ALLVR_BUILDING_WITH_VOXY_FALLBACK`
- `ALLVR_READY`
- `VOXY_BUILDING_WITH_ALLVR_FALLBACK`
- `NEAR_ONLY_NO_VOXY`

进入近景时先发布 8 个 ALLVR cell，再在下一帧边界提交前忘记对应 Voxy 节点；离开近景时先请求/注入 Voxy section，确认 backend residency 后再回收 ALLVR mesh。若 Voxy API 无法报告 GPU-ready，只能使用保守帧延迟与雾，不把“section 已写入”误当成“mesh 已可见”。所有切换带 1～2 cube hysteresis，防止在边界来回抖动。

无 Voxy 时不等待远景 readiness：Cube 离开近景缓存后正常释放；远处保持不绘制。雾距离必须依据实际近景覆盖，而不是服务器 LOD 半径。

## 6. Legacy LOD 删除计划

该删除作为第一个独立里程碑完成并单独验证，禁止留下一条“不可配置但仍可触发”的隐藏 mesh 路径。

### 6.1 客户端后端与配置

- 删除 `LegacyAllvrLodBackend.java`。
- `AllvrLodBackendManager` 只选择 Voxy 或 Disabled：`AUTO` 为“兼容 Voxy 可用则启用，否则 Disabled”；`VOXY` 不可用时也进入 Disabled 并给出一次清晰提示；`OFF` 始终 Disabled。
- 移除 `legacyActive()`、legacy fallback、legacy wire capability 和相关日志。
- `ClientConfig.AllvrLodBackendMode` 收敛为 `AUTO / VOXY / OFF`。读取旧配置时把 `LEGACY` 迁移为 `AUTO` 并警告；若无 Voxy，实际结果自然是无远景。不得因未知枚举让配置加载失败。
- 评估合并旧 `allvrLod` boolean 与 `lodBackend=OFF`；若为兼容保留，必须定义唯一优先级并在下一配置保存时规范化。
- `DisabledLodBackend` 保留为明确的无远景状态；状态面板显示“near-only”，不称为 renderer failure。

### 6.2 网络协议与服务端生成

- 删除 `ClientboundAllvrLodMeshPacket.java` 及 `CreateManaIndustry` 中的 payload 注册。
- 删除 `ServerboundAllvrLodRequestPacket.CAPABILITY_LEGACY_MESH` 和 mesh 分支。协议升级后请求只表达 voxel section 版本；Disabled 客户端根本不发 LOD 请求。
- `AllvrLodMap` 删除服务端 `AllvrMesher.build`、mesh result、`long[]` cache、mesh packet send 和双 encoder 分支；只缓存/发送带 generation 的 section payload。
- 保留 bitmap、section、forget、LOD snapshot、overlay 合并、请求预算和失效，因为它们仍服务 Voxy。
- 更新协议版本并明确拒绝旧客户端/服务端组合，不能把旧 capability 0 猜成新 section 格式。

### 6.3 客户端请求状态

- `AllvrLodClientState` 删除 `applyMesh()`、`remapQuads()`、mesh packet import 和 legacy 首包日志。
- `meshed` 改名为 `resident/accepted`，语义为“Voxy backend 已拥有该 section”，不再暗示 ALLVR mesh。
- 删除对 `AllvrRenderer.lodGate()` 和 GPU capability 的依赖。远景请求门只看 Voxy backend 是否 active、rebase 是否 freeze、窗口是否接受该 cell。
- Voxy 缺失或中途失效时立即停止新请求、取消 pending、清空 accepted/ownership；释放 Voxy 引用后进入 near-only，不重试 legacy。
- `viewExtentBlocks()`、雾和 debug distance 使用实际 backend 状态：Voxy active 才报告远景半径，否则报告近景可见半径。

### 6.4 ALLVR renderer 内的 LOD 数据

- `AllvrRenderer` 删除 `lodCubes[4]`、`applyLodMesh`、`forgetLod`、`hasLodMesh`、LOD deferred upload/回收循环和 LOD shadow command 遍历。
- `AllvrNodeStore` 删除 `lodByCubeKey`、`setLodMesh` 和 level-based node scale；近景 node 始终是一层 render cell。
- `AllvrBuffers`、terrain/shadow shader 和 GPU cull shader 删除只服务 legacy LOD 的 level/scale/parent-child 字段；共用于近景的 slot、descriptor、command 和 Hi-Z 逻辑保留到后续重构替换。
- 删除旧 LOD 专用测试、统计、注释和文档；不要误删近景 `AllvrMesher`，它仍是规则 descriptor fast path 的来源。

### 6.5 删除验收

- 全仓库不存在 `LegacyAllvrLodBackend`、`ClientboundAllvrLodMeshPacket`、`CAPABILITY_LEGACY_MESH`、`applyLodMesh`、`setLodMesh` 的可执行引用。
- Voxy 可用：近景 ALLVR、远景 Voxy，网络只出现 section payload。
- Voxy 缺失/禁用/ABI 不匹配：维度可进入，近景可见，远景不请求、不生成、不渲染，无错误重试洪泛。
- Voxy 运行中失败：释放远景资源后继续近景，不 crash、不复活 legacy。
- 服务端不再为任何玩家执行 LOD server meshing，CPU profile 中无该调用。

## 7. 近景 ALLVR 补全计划

### 7.1 生命周期、安全与结果新鲜度

- 新建 `AllvrRenderWorld`，绑定单个 `ClientLevel` 和 64-bit epoch；level unload 后所有旧任务、upload 和 GPU handle 自动失效。
- 每个 cell 使用 `contentRevision / scheduledRevision / publishedRevision`。构建中再次 dirty 只递增 revision；旧结果销毁后立即按最新 revision 重排。
- worker 结果必须为 `SUCCESS / CANCELLED / FAILED_RETRYABLE / FAILED_FATAL`，任何结果都结算 pending，避免异常后永久卡死。
- 资源 reload 发布 `resourceRevision`；worker 只读取主线程生成的不可变 model/material snapshot，不从后台线程访问可变 `Minecraft` model manager。
- 所有 cube packet、BE/emitter、bitmap、section payload 都保留精确元素/字节上限、checked arithmetic、generation 和尾随数据检查。
- GL 对象由会话唯一所有者显式关闭；初始化和 backend 切换事务化，任何异常都恢复 FBO/program/buffer/texture/blend/depth/cull 状态。

### 7.2 16³ render cell 与调度

- 一个 Cube 映射 2×2×2 个 cell；cube packet 到达批量建立，单块更新通常只 dirty 一个 cell，边界更新精确通知相邻 cell。
- 快照使用池化 18³ state/occlusion 邻域；光照所需更大邻域以共享只读 context 提供，不为每 cell 复制 27 cube。
- 调度优先级：屏幕内首次 mesh > 近相机首次 mesh > 可见更新 > seam handoff > 不可见更新。
- 多 worker 数由 CPU 核心和实际主线程占用限制；任务支持取消。snapshot、build、apply、upload 分别有每帧时间/字节预算。
- 新 mesh 上传成功前保留旧 mesh；不能先 free 再 build 造成更新闪洞。

### 7.3 模型、流体与材质

- 模型编译遍历所有 direction-cull quad 与 unculled quad，保留多 quad/overlay、位置随机、state offset、真实 UV、tint、shade、normal 和 render type。
- 对接 NeoForge ModelData、模型扩展和自定义 render type；单个坏模型隔离为可观察错误占位，不杀死 worker。
- 流体编译覆盖四角高度、流向 UV、顶/底/侧面、遮挡、overlay、双面、水体 tint、岩浆和自定义 fluid。
- 建立 SOLID、CUTOUT、TRANSLUCENT 三 pass，material 记录 alpha cutoff、mipmap、cull、emissive、depth write、blend 和 sprite。
- 资源重载重新认证 descriptor fast path；无法证明与通用流等价的 state 一律走通用流。

### 7.4 光照、AO、颜色与动画

- 以逐顶点 sky/block light 和 AO 取代面中心常量；区分 flat/smooth、direction shade 与 emissive。
- 生物群系 tint 使用绝对 world position 和配置的 blend radius；缓存按 biome cell/color resolver 分层。
- 客户端维护增量 light brick 或等价共享光照缓存，跨 cell/cube 传播；方块光不得继续无视遮挡做简单曼哈顿扩散。
- 通用流保存 packed normal；descriptor 流从 axis/face 恢复，Iris patch 得到一致的 lightmap、normal、tint 和 material id。
- 构建结果记录 animated sprite；每帧只为可见 cell/pass mark active。

### 7.5 透明、方块实体和破坏效果

- 透明先按 cell back-to-front 和 quad plane 分类，随后只对需要动态排序的几何维护可复用 sort data；相机跨排序平面时才重排。
- 排序也受预算约束，但预算不足时保留上一有效顺序，不能临时丢透明几何。
- cell 构建期收集普通/global BE；按 ALLVR 可见列表调用 vanilla dispatcher，支持 off-screen、crumbling、outline 和高 Y camera-relative transform。
- 明确 Create/Flywheel block entity 的所有权：可进入实例系统的交给实例 renderer；否则走 vanilla fallback，禁止双绘。
- 方块破坏 overlay 与正在替换的旧 terrain mesh 同步，不能依赖空 vanilla shell section。

### 7.6 GPU scene、上传与可见性

- 用 region arena 替代单全局 first-fit arena；descriptor、vertex、index、metadata 可独立分配，handle 带 generation 防 ABA。
- persistently mapped staging ring + fence 批量上传；node/material/command dirty 合并连续 range，每帧受时间和字节双预算限制。
- 显存不足先驱逐不可见和最远近景 cell；仍不足时切 compat backend/缩小近景距离并显示原因，禁止 silent drop。
- 16³ cell 构建六面 connectivity mask。CPU graph 生成候选，GPU frustum/Hi-Z 做细剔除；Hi-Z 使用保守上一帧重投影，不再要求相机完全静止。
- command buffer 物理分段并在 GPU 写入前 clamp；overflow 异步反馈并在下一帧恢复，不能写越界或永久隐形。
- 阴影使用 light-frustum 可见列表；solid/cutout 投影，透明仅在 pack 明确支持时参与。

### 7.7 渲染后端

| Tier | 条件 | 路径 |
|---|---|---|
| A | `GL_NV_mesh_shader` 和完整依赖能力 | task/mesh shader 展开 descriptor/meshlet；仅可选 |
| B | compute + SSBO + draw parameters + indirect/MDI(C) | 默认 GPU-driven 后端 |
| C | 缺少完整 GPU-driven 能力或驱动被禁用 | CPU render list + 普通紧凑 vertex/index 批绘制 |

Tier C 只保证近景；Voxy 远景是否可见仍由 Voxy 自身能力决定。任何 Tier 失败都不得关闭整个 Allay 近景。

Mesh Shader 实施约束：

- descriptor 与通用流都先切为受硬件上限约束的 meshlet；fragment/material/Iris 接口与 MDI 共用。
- task stage 可做 frustum、normal cone 和可选 Hi-Z；透明顺序仍来自 CPU/GPU sort list，不能依赖 invocation 顺序。
- 运行时查询最大 vertices/primitives/workgroup，不写死 GPU 型号。
- 只有目标设备族的 GPU p95 相比 MDI 改善至少 10%，并通过长稳与驱动矩阵，AUTO 才启用；否则保持实验开关。

## 8. 分阶段实施顺序

### M0：基线和选型证据

- 冻结四组录制：规则空岛、复杂模型展台、洞穴+流体+透明+BE、高 Y 高速垂直移动。
- 记录 current ALLVR near、普通维度 Sodium 和原版的 build/snapshot/upload/render CPU p50/p95/max、GPU pass、mesh bytes、显存和 draw count。
- 为现有 Voxy 集成记录 section 注入、mesh backlog、远景 GPU/CPU 和 rebase 峰值；后续确保删除 legacy 不回退该结果。
- 建立离屏视觉基线和 RenderDoc capture；不把普通维度 Sodium FPS 直接当作 bridge FPS。

完成条件：指标可重复，CPU/GPU 时间边界清楚，后续每阶段能与同场景比较。

### M1：删除 legacy LOD

按第 6 节完整删除客户端、协议、服务端 meshing、renderer LOD node 和配置入口；协议升级一次完成，不维持双版本热路径。

完成条件：Voxy 组合远景正常；无 Voxy near-only 正常；服务端和客户端 profile 都不再出现 legacy mesh。

### M2：生命周期、安全和兼容保底

- world/resource epoch、cell revision、任务四态、取消与过期过滤。
- 网络精确上限、GL 完整 close、事务初始化、状态恢复。
- 建立 Tier C，使能力不足或 Tier B shader 失败时近景仍可见。

完成条件：压力编辑不丢更新；100 次维度往返、50 次 reload/resize 无 GL/native 资源增长；故意让模型/worker失败后其他 cell 继续。

### M3：16³ cell、调度器和原子发布

- 拆 cell、18³ snapshot、多 worker 优先级、预算和统计。
- 新旧 mesh 原子交换；seam handoff 请求纳入优先级。

完成条件：单块更新通常只构建一个 cell；高速飞行时屏幕内首次 mesh 不被后台工作饿死；主线程 snapshot/apply/upload 长尾受预算控制。

### M4：完整模型、流体、光照和三 pass

- 双几何流、ModelData、流体、逐顶点 tint/AO/light/normal。
- solid/cutout/translucent material pipeline 和资源重载认证。
- registry coverage scan：所有可见 BlockState 都必须有渲染路径或明确白名单原因。

完成条件：模型展台与 Sodium 视觉基线没有系统性缺面；楼梯、栅栏、植物、火把、草 overlay、机械模型、水/岩浆均可见。

此阶段执行第 4.4 节决策门；只有明确未达性能门且 profile 指向模型 tessellation，才讨论 Sodium 内部原型。

### M5：透明、动画、BE 和 Iris

- 分级透明排序、sprite activation、BE/global BE、crumbling、outline、Flywheel ownership。
- Iris opaque/cutout/translucent targets、blend/depth、TAA 和 shadow patch 全部接通。

完成条件：多层透明移动无稳定错误翻转；动画不冻结；BE 在高 Y 正确显示、破坏和发光；代表性光影包无 framebuffer/depth 污染。

### M6：region 显存、上传和可见性

- region arena、staging ring、fence、generation handle、预算驱逐和碎片统计。
- connectivity + frustum + 保守 Hi-Z；阴影 GPU list；overflow 恢复。

完成条件：随机编辑/高速飞行 30 分钟无不可恢复碎片、空洞或 backlog；上传 p95 达门；持续移动时 Hi-Z 有可测收益且无闪洞。

### M7：Voxy seam 和无 Voxy 行为闭环

- 实现第 5.3 节 ownership state machine、readiness、hysteresis 和原子帧边界切换。
- Voxy reload/rebase/异常时只退 near-only；雾和 debug distance 跟随实际 coverage。
- 验证 Sodium terrain 门禁不会阻止 Voxy draw hook，也不会维护 Allay 空 shell。

完成条件：进出 256 方块边界、高速转向、传送和 Voxy rebase 无长期洞/双绘；无 Voxy 场景没有远景请求和错误日志洪泛。

### M8：可选 Mesh Shader 与发布清理

- meshlet 化、NV task/mesh backend、与 MDI 逐帧截图/GPU timestamp A/B。
- 删除被新模块替代的 singleton、旧 allocator、旧 pending 协议和失效注释。
- 更新 Allay/Voxy 开发文档和配置说明，明确“Voxy 唯一远景、无 Voxy near-only”。

完成条件：Mesh Shader 达不到收益门时保持关闭也不阻塞发布；所有硬件/模组/光影包/长稳门通过。

## 9. 文件落点

### 9.1 删除或收缩

| 文件/模块 | 计划 |
|---|---|
| `LegacyAllvrLodBackend.java` | 删除 |
| `ClientboundAllvrLodMeshPacket.java` | 删除 |
| `ServerboundAllvrLodRequestPacket.java` | 删除 legacy capability/branch，只保留 section 协议 |
| `AllvrLodMap.java` | 删除服务端 mesh build/cache/send，保留 section 生成 |
| `AllvrLodClientState.java` | 删除 quad remap/renderer mesh 接口，改为 Voxy residency 请求状态 |
| `AllvrLodBackendManager.java` | 只剩 Voxy/Disabled；失败为 near-only |
| `AllvrRenderer.java` | 最终只做事件适配和 `AllvrRenderWorld` 调用；删除 legacy LOD 与资源大杂烩 |
| `AllvrNodeStore.java` | 删除 LOD 层级，迁移为平坦、generation 化的 near cell scene node |
| `AllvrBuffers.java` | 由 region/GPU scene 逐步取代 |
| `AllvrMesherWorker.java` | 由 scheduler 取代 |
| `AllvrMesher.java` | 保留为规则 descriptor fast path |
| `AllvrRenderStateMap.java` | 由 resource-revision model/material snapshot 取代 |
| `AllvrLightBaker.java` | 迁移到共享增量 light cache |
| `AllvrSodiumTerrainMixin.java` | 保留 Voxy draw 入口；禁止 Sodium 维护空 shell，精确版本门禁 |

### 9.2 建议新增包

```text
client/dimension/render/world       会话、cell、revision、ownership、BE list
client/dimension/render/build       snapshot、scheduler、model/fluid compiler
client/dimension/render/material    material、pass、sprite activity
client/dimension/render/gpu         region、allocator、upload ring、handles
client/dimension/render/backend     compat、MDI、NV mesh shader
client/dimension/render/visibility  connectivity、Hi-Z、pass lists
client/dimension/render/debug       overlay、counter、capture hooks
```

## 10. 性能预算与发布门

所有指标在固定录制、release build、1080p、至少 10,000 帧下统计，并报告硬件、驱动、Sodium/Voxy/Iris 版本；不能只报平均 FPS。

- 稳定视角：ALLVR near terrain render-thread slice p95 ≤ 0.75 ms，不允许同步 GPU readback。
- 持续流送：snapshot + result apply + upload 合计 p95 ≤ 2.0 ms；单帧由预算限制在 4 ms 内。
- 单块更新：通常只重建 1 个 16³ cell；边界只重建受影响邻居集合，不重建整个 32³ Cube。
- 构建吞吐：至少为当前单 worker 路径的 3 倍；主线程不得因 cache lock 等待超过 2 ms。
- Tier B：同等可见几何和画质下，GPU/terrain p95 不比 Sodium 普通世界对照慢 15% 以上；差异必须拆分为额外视觉功能、像素量或 renderer overhead。
- Tier A：仅在比 Tier B 改善 ≥10% 时对该设备族自动启用。
- 上传：render-thread upload p95 < 1 ms，任何大批量结果可跨帧完成。
- 显存：预算内稳定；碎片率和 deferred allocation 不连续增长；驱逐后可回收。
- Voxy：删除 legacy 后，section 网络量、服务端 section build、client injection、mesh backlog 和 rebase p95 不得回退超过 10%。
- near-only：无 Voxy 时不产生 LOD bitmap walk、section 请求、服务端 section build 或 Voxy probe 重试开销。
- 长稳：30 分钟飞行+随机编辑、100 次维度往返、50 次资源重载/resize，无空洞、worker 死亡、GL error 洪泛或资源增长。

若门槛未通过，必须保留 capture 和归因；不允许通过静默丢模型、关闭透明排序、伪造雾距离或降低正确性来达标。

## 11. 测试矩阵

### 11.1 数据与坐标

- Y=0、±1,000,000、±29,999,900；负 X/Y/Z；15↔16、31↔32 cell/cube 边界。
- cube 首包、更新、forget、重发；构建中重复 dirty；旧 epoch/revision/generation；worker 失败。
- Voxy absolute↔virtual Y round-trip、连续 rebase、旧 epoch 包、超窗 cell 拒绝。
- 合法最大 packet 与短数组、超长数组、溢出、无效 state、尾随字节 fuzz。

### 11.2 渲染内容

- 完整立方、random/weighted、overlay、unculled、offset、multipart、ModelData、自定义 render type。
- solid、cutout、cutout-mipped、tripwire、translucent、emissive、双面。
- 静水、流水、瀑布、含水、自定义流体及玻璃/叶子交界。
- 露天、洞口、封闭洞穴、遮墙光源、跨 cell/cube 光源、昼夜。
- 普通/global BE、crumbling、outline、Create/Flywheel。
- animated sprite、资源包 reload、shader reload、窗口 resize。

### 11.3 运行组合

| Sodium | Iris | Voxy | 必须结果 |
|---|---|---|---|
| 无 | 无 | 无 | ALLVR 完整近景；边界外无地形 |
| 有 | 无 | 无 | Sodium 不维护空 shell；ALLVR 近景与无 Sodium 一致 |
| 有/无 | 有 | 无 | ALLVR 三 pass/depth/shadow 正确；边界外无地形 |
| 有 | 无 | 有 | ALLVR 近景 + Voxy 远景，单一 ownership |
| 有 | 有 | 有 | ALLVR/Voxy/Iris targets、depth、TAA、shadow 正确 |
| 任意 | 任意 | ABI 不支持/运行失败 | 自动 near-only，不 crash、不启用 legacy |

至少测试一组低能力 compat GPU、一组主流 AMD/Intel Tier B、一组 NVIDIA Tier B；NV Mesh Shader 设备另测 Tier A。Voxy 的实际依赖组合按已支持矩阵执行，不假设任意“无 Sodium + Voxy”都能工作。

### 11.4 必增自动测试

- `AllvrRenderRevisionTest`：重复 dirty、乱序结果、跨 epoch、失败结算。
- `AllvrPacketBoundsTest`：最大合法值、溢出、短 bitmap、尾随字节和 fuzz。
- `AllvrRenderCellBoundaryTest`：六边邻居 dirty 与未知邻居。
- `AllvrModelCoverageTest`：registry 扫描与 descriptor/general 分类。
- `AllvrAllocatorPropertyTest`：随机 alloc/free/compact、generation ABA。
- `AllvrFarTerrainModeTest`：Voxy/Disabled 选择、旧 LEGACY 配置迁移、无请求行为。
- `AllvrVoxyOwnershipTest`：near/far ready handoff、rebase、失败降 near-only。
- `AllvrTranslucentSortTest`：相机跨平面、退化/相交 quad、稳定 tie-break。
- 离屏/RenderDoc golden：三 pass、Iris targets、shadow distortion、高 Y 精度和 seam。

## 12. 风险与控制

| 风险 | 控制 |
|---|---|
| 删除 legacy 后 Voxy 故障没有远景 | 这是明确产品行为；near-only 必须稳定，状态和雾准确，不伪装成故障恢复 |
| ALLVR 功能补全周期长 | 按 M2～M5 逐阶段建立 coverage/golden；每阶段保持可运行，不同时重写全部模块 |
| 通用模型显著增加 mesh bytes | descriptor fast path + 通用流；先保证 coverage，再以测量扩大认证集合 |
| 透明排序成本高 | 分级分类、sort data 复用、触发式重排和预算；预算不足保留旧顺序 |
| 光照传播成本高 | 共享增量 light brick 与 dirty propagation，禁止每个任务重复全邻域扫描 |
| GPU-driven 在低端设备表现差 | Tier C 正确性保底；AUTO 基于 capability、smoke test 和会话失败锁存 |
| Mesh Shader 厂商锁定/驱动问题 | 只做可选 Tier A；MDI/compat 始终存在；收益和长稳双门禁 |
| Iris 私有接口变化 | 集中 version adapter、签名探测、完整 GL 状态保护；失败降级而非半初始化 |
| Sodium mixin 版本变化 | 仅维护空 shell 门禁和 Voxy hook；精确版本探测失败时保留最终 draw guard |
| Voxy seam readiness 不可观察 | 扩展最小 residency 查询；做不到时使用保守帧延迟、hysteresis 和雾，不恢复 legacy |
| 方案 2 被低估 | 保留 M4 量化决策门；只有实测明确失败才做限时内部 context 原型 |

## 13. 完成定义

同时满足以下条件才算完成：

- legacy LOD 的配置、协议、服务端 meshing、客户端 mesh、GPU node/shader 分支和测试均已删除。
- Voxy 是唯一远景后端；无 Voxy 或 Voxy 失败时，只渲染近景，且没有远景请求/生成/错误重试。
- 所有可见近景 BlockState、fluid、solid/cutout/translucent、tint、AO/light、normal、animated sprite、BE 和 crumbling 均有明确、通过测试的路径。
- 每个构建、上传和 GPU handle 都有 epoch/revision/generation；旧世界、reload、乱序结果不能复活旧几何。
- 所有网络渲染载荷有精确上限和结构校验。
- 任意目标 GPU 至少能用 Tier C 正确显示近景；Tier B/A 失败可在本会话安全降级。
- ALLVR near 与 Voxy far 由单一 ownership 状态机切换，无长期洞、双绘或 z-fighting。
- Sodium 在 Allay 维度不重复维护空 shell，其他维度行为不变，Voxy draw hook仍工作。
- Iris on/off、目标光影包、resize/reload、阴影、透明、高 Y 和长稳矩阵通过。
- 第 10 节性能门全部通过，且没有以静默丢几何换取指标。

完成后的系统定位是：ALLVR 是 Allay 超高 Y Cube 世界的完整近景 renderer；Voxy 是唯一可选远景 renderer；Sodium 是实现质量和性能的对照标准，而不是需要虚拟化整个 Allay 世界才能使用的运行时数据后端。
