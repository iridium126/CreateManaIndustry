# Allay Dimension：Voxy LOD 运行时接入分析与实施计划

> 状态：设计完成，待实施  
> 范围：仅替换 Allay dimension 的远景 LOD 数据传输与渲染后端；既有方块持久化、近景 32³ cube 流送、碰撞/实体逻辑不在本计划内。  
> 结论：选择方案 2——保留 ALLVR 的世界数据与 LOD 调度，在运行时把 ALLVR 的分层体素 section 注入用户单独安装的 Voxy，由 Voxy 渲染。方案 1 不实施。

## 1. 决策摘要

方案 2 胜出，但应把“兼容 Voxy”定义得足够严格：

- CMI 不复制、改写或打包 Voxy 的源码、shader、资源和二进制；Voxy 仍是用户单独安装的可选依赖。
- ALLVR 继续拥有绝对坐标、程序化地形、持久化修改、LOD 距离分带、节点请求和失效规则。
- 当前服务端 LOD 不能原样转交 Voxy。现有 `ClientboundAllvrLodMeshPacket` 发送的是已经由 `AllvrMesher` 生成的 `long[] quads`，而 Voxy 的稳定数据边界是带 BlockState、biome、light 的体素 `WorldSection`。因此必须把网络协议边界从“网格”前移到“32³ 体素 section”。
- ALLVR 的 L0～L3 节点与 Voxy 的同级 `WorldSection` 在空间上完全对齐：两者每轴都是 32 个采样，level L 都覆盖 `32 << L` 个方块。这允许保留当前 256/512/1024/2048 距离分带，不需要把远景全部还原成 L0 全分辨率数据。
- Voxy 的 section key 给 Y 只留了有符号 8 bit。L0 section 为 32 方块，所以可直接表达的基础 Y 区间是 `[-4096, 4095]`。使用以玩家为中心、按 512 方块对齐的虚拟 Y 窗口；Voxy 看到虚拟 Y，ALLVR 始终保存绝对 Y。
- 方案 2 会移除当前自研 LOD renderer 的大部分错误面，但不能承诺“自动解决所有渲染错误”。坐标重映射、section 拓扑、材质/光照编码、近远景交界、生命周期和 Voxy 版本适配仍由 CMI 负责。

推荐的交付形式是“精确版本适配器 + 运行时能力探测 + 旧 LOD 后端暂时保底”。首个支持目标应固定为一个实际可运行、经过测试的 1.21.1 Voxy 构建，而不是宽泛声明兼容所有社区移植版。

## 2. 本次分析依据

### 2.1 现有 ALLVR LOD

- `AllvrLodPos` 定义 L0～L3，节点覆盖 32、64、128、256 方块，每个节点均采样为 32³。
- `AllvrLodSnapshot` 已能在服务端构建带一圈 padding 的 34³ BlockState/遮挡快照，并把已持久化但未加载的修改叠加到程序化地形上。
- `AllvrLodMap` 当前在服务端调用 `AllvrMesher.build(...)`，缓存并发送 `long[] quads`。
- `AllvrLodClientState` 当前接收 quads、把 vanilla state id 重映射为自研 renderer id，再交给 `AllvrRenderer`。
- 当前分带为：近景 cube 0～256，L0 256～512，L1 512～1024，L2 1024～2048，L3 2048～服务器配置半径；最大配置半径 4096。
- `AllvrSodiumTerrainMixin` 已在 Allay dimension 禁止 Sodium 的普通 chunk terrain，但 Voxy 是挂在 Sodium terrain 调用尾部的独立绘制，因此该入口仍可作为 Voxy 调度点；必须做组合回归测试。

### 2.2 本地 Voxy 参考实现

- `.refs/voxy/LICENSE.md` 和 `.refs/voxy-backport/voxy/LICENSE.md` 都明确写有 “All rights reserved” 和 “Do not redistribute”。直接复制或随 CMI 分发其实现不应作为工程方案。
- `.refs/voxy` 当前目标是 Minecraft 26.2、Voxy 0.2.19-beta，不是 1.21.1 依赖。
- `.refs/voxy-backport/voxy` 标称 Voxy 0.2.15-beta 的 1.21.1 回移，但 `gradle.properties` 仍带未解决的 Git conflict markers。它可以用于理解 API，不能仅凭该目录就认定为可发布、可靠的依赖。
- Voxy `WorldEngine.getWorldSectionId` 把 Y 编码为有符号 8 bit；`WorldSection` 是 32³；`MAX_LOD_LAYER` 是 4。
- Voxy `VoxyRenderSystem` 从当前 `WorldEngine` 构造模型、网格、层级节点、遮挡遍历和 render pipeline；`Viewport.setCamera` 直接接收 double camera XYZ，适合只对 Voxy 的 cameraY 做同源重映射。
- Voxy 默认用 Minecraft 客户端的正式 build-height 初始化顶层 Y 节点。Allay 客户端刻意保留 384 方块的正式窗口，所以若不打补丁，Voxy 不会创建覆盖整个虚拟 `[-4096, 4095]` 的顶层节点。
- Voxy 自带 `MemoryStorageBackend` 和 `SectionSerializationStorage`。Allay 的虚拟 Y key 不得写入普通 Voxy 世界数据库，应为 Allay 的 Voxy engine 改用会话级内存存储。

## 3. 两个方案比较

| 维度 | 方案 1：完全对齐/重做 Voxy | 方案 2：运行时接入 Voxy |
|---|---|---|
| 渲染可靠性 | 初始可参考成熟设计，但复制后就是独立 fork；后续 bugfix、Iris/Sodium 适配仍要自行追赶 | 直接运行被选定 Voxy 构建的 renderer、mesher、GPU 资源和 shaderpack 管线，能持续复用其修复 |
| 许可证 | 高风险。参考目录明确 ARR 且禁止再分发；“完全对齐”很容易演变为复制受保护实现 | 较低风险。CMI 只实现互操作层并要求用户单独安装 Voxy；仍需发布前做许可证/法务复核 |
| 初始工作量 | 极高：存储、mip、mesher、模型烘焙、GPU allocator、层级遍历、遮挡、Iris 全要维护 | 中等：协议改造、坐标窗口、section 注入、生命周期和少量精确 mixin |
| 长期维护 | 极高，Voxy 与 Sodium/Iris 任一变化都要手动移植 | 中等偏高，主要风险变成内部 ABI；通过版本适配器和能力探测控制 |
| 超高 Y | 可自行扩展 key，但需重做并验证全管线 | Voxy key 不变；用玩家附近的虚拟 Y 窗口解决，绝对 Y 留在 ALLVR |
| 当前 LOD 资产复用 | 可能最终重写大部分现有代码 | 保留服务端快照、持久化 overlay、bitmaps、请求限流、分带与失效；替换网格协议和客户端 renderer |
| 性能风险 | CPU/GPU/显存问题均由 CMI 独立承担 | Voxy 渲染性能成熟；新增风险集中在 section 转码、拓扑更新和 rebase 峰值 |
| 故障隔离 | 与 CMI 核心强耦合 | 可通过 backend SPI、版本白名单和旧后端回退隔离 |
| 发布可行性 | 在未取得授权前不建议 | 可行，但不得捆绑 Voxy，且只声明已验证版本 |

### 决策

采用方案 2。方案 1 的唯一明显优势是可以从根上扩展 Y key，但代价是许可证风险、重复实现和长期追赶成本；虚拟 Y 窗口已经能在不修改 Voxy key 格式的前提下覆盖玩家周围的有效 LOD，所以该优势不足以改变结论。

若首阶段技术验证证明指定 Voxy 构建无法安全接收外部同级 WorldSection，处理顺序是：先寻求 Voxy 官方/移植维护者提供小型公共接入 API或授权，其次继续保留现有 LOD 后端；不得自动转向复制 Voxy 源码。

## 4. 目标架构

```text
绝对坐标世界（服务端权威）
  AllvrIslandFieldGenerator + 已持久化 overlay
                │
                ▼
  AllvrLodSnapshot：L0～L3 的 32³ BlockState + 每格光照
                │ 调色板压缩、仍使用绝对 (level, cellX, cellY, cellZ)
                ▼
  ClientboundAllvrLodSectionPacket
                │
                ▼
  AllvrLodClientState / AllvrLodBackend
                │ 绝对 Y → 玩家相对的虚拟 Y
                ▼
  VoxyLodBackend ──► Voxy Mapper ──► Voxy WorldSection(L0～L4)
       │                                │
       └── 节点所有权/父子 mask ────────┘
                                            │
                                            ▼
                              Voxy mesh / hierarchy / cull / Iris render

近景 0～256：仍由 AllvrClientCubeCache + AllvrRenderer 的 full-res 路径拥有
远景 256～R：只由 VoxyLodBackend 拥有，旧自研 LOD draw 不再同时执行
```

### 4.1 所有权边界

ALLVR 负责：

- 绝对 `(x, y, z)` 和 ±30,000,000 Y 的游戏语义；
- 程序化地形与持久化修改合并；
- L0～L3 采样规则、距离分带、surface bitmap、节点请求、缓存和失效；
- Voxy 版本选择、Y 窗口、节点所有权、rebase、fallback；
- 跨网络 BlockState/光照数据。

Voxy 负责：

- BlockState/biome 到渲染模型的映射与烘焙；
- section 网格生成；
- GPU geometry 资源；
- 层级节点遍历、frustum/Hi-Z/遮挡剔除；
- opaque/translucent、depth、Iris shaderpack/Sodium render-stage 集成。

禁止把 Voxy 的内部 `long mapping id` 或 GPU quad 格式作为网络协议。这些 id 只在某个客户端 Voxy engine 内有效，跨版本、跨客户端和跨会话都不稳定。

## 5. 虚拟 Y 窗口

### 5.1 精确映射

定义：

- `MAX_VOXY_LEVEL = 4`；
- `ALIGN_BLOCKS = 32 << MAX_VOXY_LEVEL = 512`；
- `originBlockY` 始终是 512 的整数倍；
- 对 level L 的 ALLVR 节点：

```text
virtualCellY(L) = absoluteCellY(L) - (originBlockY >> (5 + L))
voxyCameraY      = absoluteCameraY - originBlockY
```

X/Z 不重映射。Voxy 的 X/Z section key 各有有符号 24 bit，足以覆盖当前 ±29,999,984 方块；保留绝对 X/Z 也避免同时引入三个轴的 cache identity 问题。

### 5.2 窗口参数

- Voxy L0 的硬范围：section Y `[-128, 127]`，即 block Y `[-4096, 4095]`。
- 第一版 active vertical radius 固定为 3072 方块；水平半径仍按服务器 `allvrLodDistance`，最大 4096。
- 当 `abs(playerY - originBlockY) >= 512` 时，把 origin 移到离玩家最近的 512 对齐位置。
- 这样 active 区域即使在 rebase 前也至多触及约 ±3584，距硬边界仍保留至少 512 方块保护带。
- 请求 bitmap 和节点筛选必须额外裁剪到 active vertical radius；不可让超窗 cell 经 `& 0xFF` 静默别名到另一高度。

3072 是首版安全值，不是 Voxy 的新世界高度。以后若要增大，应先通过边界、父级对齐和 rebase 压测，不能直接设为 4096。

### 5.3 Rebase 状态机

使用单调递增 `windowEpoch`，流程固定为：

1. `STEADY`：正常请求、接收和注入；每个 pending 请求记录 epoch。
2. `FREEZE`：触发 rebase 后停止发新请求，`windowEpoch++`；旧 epoch 的迟到包只能丢弃，不能复活节点。
3. `DETACH`：从细到粗撤销所有 owned section 的父子 mask，向 Voxy 发空几何/dirty 事件并释放持有引用。
4. `MOVE`：更新 `originBlockY`；把 Voxy viewport 的 cameraY 和所有后续 cellY 同时切换到新坐标系。
5. `REFILL`：根据最新 bitmap 重新请求；先装入粗节点，再装入细节点，期间用 Allay fog 遮住缺口。
6. `STEADY`：至少一个可见粗层完成后恢复正常预算。

首版允许 rebase 时短暂出现受雾遮挡的 LOD 空窗，不尝试同时维护两个 Voxy engine。双 engine 会复制 GPU 资源并侵入 Voxy renderer 生命周期，不值得作为第一阶段目标。

## 6. 新网络数据契约

### 6.1 替换 mesh packet

新增 `ClientboundAllvrLodSectionPacket`，逻辑字段为：

```text
protocolVersion
requestId
level
absoluteCellLong
generation
palette[BlockState]
packedPaletteIndices[32 * 32 * 32]
packedLight[32 * 32 * 32]  // 低 4 bit sky，高 4 bit block
```

要求：

- 位置仍是绝对 `AllvrCubePos` 21-bit/cell key；绝不能在服务端写入虚拟 Y。
- BlockState 使用当前连接 registry 可解码的状态表示；不得发送 Voxy block id。
- palette index bit width 由 palette size 决定；全 air、单一材质走常量特例。
- 第一版只保留当前 LOD 的 full-occluder 材质策略，先保证与旧画面语义一致；透明/流体/特殊模型作为后续独立扩展。
- light 直接复用 `AllvrLodSnapshot.light()`，在服务端按 32³ core cells 采样并打包。
- 服务端设置解压上限、palette 上限和固定 cell count，客户端拒绝畸形包，避免内存放大攻击。
- packet 带 `requestId + generation`；客户端同时检查 request ownership、window epoch 和本地 generation，解决 forget、修改、rebase 与异步完成之间的竞态。

旧 `ClientboundAllvrLodMeshPacket` 在迁移期保留，仅供 legacy backend；Voxy backend 不接收 quads，也不运行 `remapQuads()`。

### 6.2 服务端生成与缓存

把 `AllvrLodMap.buildJob` 分成两层：

1. `buildSectionData`：复用 `AllvrLodSnapshot.create/fill/light`，产出不可变的 `AllvrLodSectionData`。
2. backend encoder：
   - 新路径调色板压缩并缓存 section payload；
   - 旧路径在迁移期可继续把同一 section data 交给 `AllvrMesher`。

共享缓存从 `long[] quads` 改为按协议版本区分的不可变 payload，并继续使用总字节预算 LRU。修改任意 cube 时仍失效其所在的每级节点；持久化 overlay 的读取规则不变。

## 7. Voxy 运行时适配层

### 7.1 后端接口

新增不引用任何 Voxy 类型的核心接口：

```java
interface AllvrLodBackend {
    Availability probe();
    void enter(ClientLevel level);
    void apply(AllvrLodSectionData data, long epoch);
    void forget(int level, long absoluteCellLong, long epoch);
    void tick(CameraState camera);
    void leave();
    LodBackendStats stats();
}
```

实现：

- `VoxyLodBackend`：仅在受支持 Voxy 构建存在时加载。
- `LegacyAllvrLodBackend`：迁移期保留当前 renderer，用于无 Voxy、ABI 不匹配和 A/B 对比。
- `DisabledLodBackend`：用户明确关闭 LOD 时使用。

任何带 `me.cortex.voxy.*` 方法签名的类只能放在 `compat.voxy` 包中，且必须经过 mixin/plugin 和运行时版本门禁；否则在未安装 Voxy 时 JVM 可能仅因类验证就崩溃。

### 7.2 使用现有 Voxy engine，而不是自建 renderer

- 通过 Voxy 的 `IGetVoxyRenderSystem.getNullable()`/当前 level renderer 找到当前 Voxy render system。
- 通过受版本控制的 accessor 取得它绑定的 `WorldEngine`；必须验证该 engine 对应当前 Allay `ClientLevel`。
- 不自行复制 `VoxyRenderSystem`、`NodeManager` 或 render pipeline；否则方案会重新退化为维护 fork。
- 进入 Allay dimension 时，拦截 `VoxyClientInstance.createStorage(WorldIdentifier)`，只对 Allay identifier 返回 Voxy 自带的 `SectionSerializationStorage(new MemoryStorageBackend())`。
- Allay 的虚拟 section 和 Voxy mapper 映射均为会话级；退出维度即释放。绝不写入世界的 `voxy/` 或 `.voxy/saves`，从根源避免不同绝对 Y 窗口复用同一虚拟 key 造成持久化串层。
- 拦截 Voxy 原生 chunk ingest，只对 Allay identifier 禁止。否则正式 384 高度内的 vanilla column section 会与 ALLVR 注入的虚拟 section 互相覆盖。

### 7.3 写入同级 WorldSection

ALLVR level L 节点直接写入 Voxy level L `WorldSection`：

1. 验证 L 在 0～3、virtual Y 合法且 `(x, y, z, level)` 可无损 round-trip 通过 Voxy key。
2. `WorldEngine.acquire(level, cellX, virtualCellY, cellZ)`；owned section 在可见期间额外持有一份引用，防止无磁盘存储时从 cache 淘汰。
3. 把 packet palette 映射到 Voxy `Mapper`：`BlockState -> blockId`，取 Allay biome id，并组合 `light + biome + block`。映射结果只存在客户端。
4. 一次性填充 section 的 32³ raw data；写完后再发布 dirty 事件，不能让 renderer 看见半写入数组。
5. L0 设置准确的 `nonEmptyBlockCount` 并调用 Voxy 的 L0 state 更新；L1～L3 的 `nonEmptyChildren` 表示当前已注入的更细子 section。
6. 用 `UPDATE_TYPE_DONT_SAVE` 发布 block/child dirty 事件；即使存储拦截失效，也不允许虚拟数据进入磁盘。
7. 相邻面发生变化时按六方向计算 neighbor mask，触发 Voxy 重建边界 section，防止裂缝/残面。

不要调用或复制 Voxy 的私有 mesh 格式。适配器只写世界数据并发 dirty 事件，后续 BuiltSection、模型、GPU 数据全由 Voxy 生成。

### 7.4 稀疏层级拓扑

标准 Voxy 假定 L1～L4 都由更细数据逐级生成；ALLVR 则按距离只请求某一级。因此需要一个很小但明确的拓扑桥：

- `AllvrVoxyNodeRegistry` 记录所有 owned `(level, virtualKey)`、是否有几何、直接子节点 bit mask、generation 和持有的 section 引用。
- 注入任意 L0～L3 section 时，向上确保 L+1～L4 的 topology-only ancestor 存在，并设置父节点相应 child bit。
- topology-only ancestor 的几何可为空，但 child mask 必须正确，使 Voxy 从 L4 root 能发现实际节点。
- 某个粗节点和它的细子节点在距离分带切换期间可暂时共存：先发布细节点与 child mask，待 Voxy 完成至少一轮构建后再忘记粗节点。Voxy 可用父几何作 child 未就绪时的 fallback，降低裂缝和闪洞。
- 忘记时从细到粗清理；只有最后一个 owned descendant 消失时才移除 ancestor 对应 bit。
- Voxy 的 `_unsafeSetNonEmptyChildren` 不是稳定 API。首版允许在版本适配层调用，但必须由单写者队列串行化、随后发送 child dirty；不得从任意 packet handler 线程直接写。
- P0 必须验证“高层 section 自身有几何但 child mask 为 0”能被指定 Voxy 构建稳定视为 leaf。若不成立，只在 `NodeManager` 的 child-existence 消费点加入基于 registry 的最小 mixin；禁止复制整个 NodeManager。

### 7.5 三个必要的坐标/范围补丁

仅在当前 level 是 Allay dimension 时生效：

1. **Viewport cameraY**：在 `VoxyRenderSystem.setupViewport` 把传入 Y 改为 `absoluteCameraY - originBlockY`。相机和 section 必须读取同一个原子 window snapshot。
2. **顶层 Y roots**：把 `RenderDistanceTracker` 的 L4 Y 范围从正式 384 高度推导值改为 `[-8, 7]`，恰好覆盖 L0 `[-128, 127]`；其他维度保持 Voxy 默认值。
3. **原生 ingest 门禁**：Allay level 的 vanilla chunk/section 不进入该 engine，只有 `VoxyLodBackend` 有写权限。

每个 mixin 都要 `require = 1` 并由精确版本白名单启用。若注入点不匹配，应在 renderer 创建前判定 backend unavailable 并回退，不能让游戏在半兼容状态继续。

### 7.6 近景、远景与 shaderpack

- 近景 `< 256` 继续由 `AllvrRenderer` 的 full-res cube path 绘制；Voxy backend 只接收当前 `AllvrLodBands` 允许的节点。
- 关闭 `AllvrRenderer` 的 `applyLodMesh/forgetLod` 和自研 LOD draw，但不能关闭 full-res draw。
- 节点边界与分带边界均为 32/64/128/256 的整倍数；继续保持“一个空间位置只有一个权威级别”。过渡期父子短暂重叠由深度与 Voxy hierarchy 接管，不允许两个独立 renderer 长期重叠。
- Voxy 已拥有 VOXY define、vx uniforms/samplers、Iris render targets 时，现有 `.allvriris.shared.` 门禁继续让出全局 surface；删除自研 LOD shader 之前，先确认 full-res ALLVR shader 仍能独立工作。
- 组合测试必须覆盖：Sodium terrain 被 ALLVR 取消、但 `SodiumWorldRenderer.drawChunkLayer` 及 Voxy 尾部 hook 仍执行；Iris on/off 都要验证 depth、shadow、translucency 和 fog。

## 8. 版本与许可证策略

### 8.1 支持矩阵

第一版只支持一个经过构建和游戏内验证的 1.21.1 Voxy artifact，记录：

- mod id；
- semantic version；
- artifact SHA-256；
- Voxy commit；
- Minecraft、loader、Sodium、Iris 版本；
- 被访问的类、字段、方法 descriptor；
- mixin probe 测试结果。

不同社区移植若 descriptor 不同，分别实现 `VoxyApi_0215_1211` 一类的薄适配器和 mixin config，不能在热路径用大量反射猜测。未知构建默认 unavailable，并给出清晰日志与配置界面提示。

`.refs/voxy-backport` 当前有未解决 conflict markers，所以 P0 不能直接把它加入发行构建；必须先取得维护者实际发布的 jar，或在明确获得许可的内部环境中生成仅用于兼容测试的 artifact。

### 8.2 发布边界

- CMI jar 中不得包含 Voxy class、shader、资源、反编译片段或改名后的实现。
- 构建依赖必须是 `compileOnly`/开发运行依赖；发布元数据把 Voxy 标为可选兼容依赖或明确的推荐依赖。
- mixin、accessor、adapter 只表达互操作所需的符号和 CMI 自己的逻辑。
- 文档注明 Voxy 的作者、项目和许可证；不要暗示 Voxy 属于 CMI。
- 这是一项工程风险判断，不是法律意见。正式分发前应复核所选社区移植本身是否有权再分发、其 jar 是否沿用上游限制，以及 runtime patch 的发布条款。

## 9. 预计代码改动

### 9.1 核心、无 Voxy 类型

- `dimension/lod/AllvrLodSectionData.java`：不可变 32³ palette/light 数据。
- `dimension/lod/AllvrLodSectionCodec.java`：调色板、bit-pack、上限校验。
- `dimension/net/ClientboundAllvrLodSectionPacket.java`：新 S2C 数据包。
- `dimension/net/ServerboundAllvrLodRequestPacket.java`：加入 protocol/backend capability、requestId 或窗口代次字段。
- `dimension/lod/AllvrLodMap.java`：缓存 section payload，不在主 Voxy 路径服务端网格化。
- `client/dimension/lod/AllvrLodBackend.java`：后端 SPI。
- `client/dimension/lod/AllvrLodBackendManager.java`：选择、切换、生命周期和统计。
- `client/dimension/AllvrLodClientState.java`：pending/bitmap 逻辑保留，输出从 renderer quads 改为 backend section。

### 9.2 Voxy 专用且条件加载

- `client/dimension/lod/voxy/VoxyLodBackend.java`
- `client/dimension/lod/voxy/VoxyCompatibilityProbe.java`
- `client/dimension/lod/voxy/AllvrVoxyYWindow.java`
- `client/dimension/lod/voxy/AllvrVoxyNodeRegistry.java`
- `client/dimension/lod/voxy/AllvrVoxySectionWriter.java`
- `client/dimension/lod/voxy/VoxyApi_0215_1211.java`
- `mixin/voxy/VoxyRenderSystemAccessor.java`
- `mixin/voxy/AllvrVoxyStorageMixin.java`
- `mixin/voxy/AllvrVoxyViewportMixin.java`
- `mixin/voxy/AllvrVoxyTopLevelRangeMixin.java`
- `mixin/voxy/AllvrVoxyIngestMixin.java`
- 必要时才增加 `mixin/voxy/AllvrVoxyLeafTopologyMixin.java`。

### 9.3 配置和资源

- `CMIMixinPlugin`：新增 `.voxy.` 包门禁，并校验目标 mod/version；不能只检查 `mod id=voxy`。
- `createmanaindustry.mixins.json`：登记专用 mixin。
- Gradle：加入所选 jar 的 compileOnly 坐标与不打包校验。
- Client config：`AUTO / VOXY / LEGACY / OFF`，默认 AUTO；开发期增加窗口、epoch、owned section、queue、rebase 次数调试信息。
- 旧 ALLVR LOD shader、GPU cull 和 buffer 代码在迁移期保留；达到删除门槛后再做单独清理提交，避免一次变更同时失去回退和可比较基线。

## 10. 分阶段实施

### P0：兼容性与可行性探针（必须先通过）

1. 固定一个真实可启动的 1.21.1 Voxy jar 和完整版本矩阵。
2. 做独立开发探针：在普通测试 world 的 Voxy engine 中创建一个单色 32³ L2 section，建立 L4→L3→L2 topology，确认可见、可更新、可忘记。
3. 分别验证 child mask=0 的高层 leaf、空 topology ancestor、neighbor rebuild。
4. 验证把 viewport cameraY 与 sectionY 同时平移 512 后，画面位置不变且 frustum/Hi-Z 正确。
5. 验证内存 storage 和 `DONT_SAVE` 后磁盘没有新增 Allay section。
6. 记录所有必要 descriptor。若必须 patch Voxy 大段核心算法，P0 判失败，不进入后续实现。

退出标准：无需复制 Voxy 实现，只靠 section 写入、dirty callback、4～5 个小 mixin 即可稳定画出/更新/删除测试节点。

### P1：后端隔离与能力握手

1. 引入 `AllvrLodBackend`，把 `AllvrLodClientState` 与 `AllvrRenderer` 解耦。
2. 建立客户端 capability，在登录/配置同步时告诉服务端使用 section protocol 还是 legacy mesh protocol。
3. 实现精确 Voxy probe、错误日志和 fallback；无 Voxy时当前行为不变。
4. 添加 backend 统计，不改变画面。

退出标准：AUTO 能在受支持构建选 Voxy，在缺失/未知构建无崩溃地选 legacy。

### P2：体素 section 协议

1. 把 `AllvrLodSnapshot` 的 32³ core BlockState 与 sky/block light 提取为不可变数据。
2. 实现 palette/bit-pack codec、大小上限、fuzz/unit tests。
3. `AllvrLodMap` 按客户端 capability 返回 section 或旧 mesh，并把 cache 预算改为实际 payload bytes。
4. 用记录回放比较新 section 经旧 mesher生成的 quads 与原路径结果，确保数据语义未改变。

退出标准：同一节点的新旧路径材质占用、边界面和光照一致；畸形包均被拒绝。

### P3：Voxy section 注入与稀疏拓扑

1. 实现 BlockState/biome/light 到 Voxy Mapper 的本地映射缓存。
2. 实现单写者 section writer、owned 引用和 dirty/neighbor 事件。
3. 实现 L4 topology ancestor 与父子 mask 增删。
4. 先在固定 Y=0、Iris 关闭时显示 L3，再逐级启用 L2/L1/L0。
5. 加入 generation/forget race 测试和队列背压；每帧注入预算不能无限 drain。

退出标准：移动穿过 256/512/1024/2048 分带无持续洞、无重复地形、无旧节点复活。

### P4：虚拟 Y 窗口

1. 实现 512 对齐 origin、3072 active radius、严格 key round-trip 检查。
2. patch viewport cameraY 与顶层 L4 Y `[-8, 7]`。
3. 实现 epoch rebase 状态机与 fog 掩护。
4. 禁止 Allay 的 Voxy 原生 ingest；改用内存 storage。
5. 测试 Y=0、±4095、±4096、±1,000,000、±29,999,999，以及连续上下飞行多次 rebase。

退出标准：同一 virtual key 在不同 absolute Y epoch 不串数据；任何超窗节点都不会因 8-bit 截断出现在错误高度。

### P5：Sodium/Iris 与画质闭环

1. 验证 Sodium terrain cancel 后 Voxy hook 仍每帧执行。
2. Iris off：opaque、cutout、translucent、depth、fog、云/天空顺序。
3. Iris on：至少测试 Complementary Reimagined 和一个最小 shaderpack；验证 shadow、colortex、TAA/temporal 路径。
4. resource reload、shader reload、F3+A、窗口缩放、切换维度、退出重进。
5. 对比旧 backend 截图和深度/法线 debug view，修正材质、光照和交界，不在 CMI 内复制 Voxy shader 修复。

退出标准：受支持组合无 OpenGL error、黑屏、悬浮残影、深度穿透和稳定可复现的边界裂缝。

### P6：性能、故障恢复与默认启用

1. 记录 section payload p50/p95、压缩比、服务端生成时间、客户端转码时间、Voxy mesh queue、CPU/GPU frame time、显存和 rebase 峰值。
2. 以现有请求限流为基线增加客户端 writer 背压；过载时优先粗层与近距离，取消旧 epoch。
3. 做 30 分钟高速飞行和反复 rebase soak test；确认 owned refs、WorldSection、GPU geometry 不增长。
4. 注入一次 Voxy renderer reload/异常，验证 backend 能清空 ownership 后重连，不能继续向已 free 的 engine 写入。
5. 达到验收门槛后把 AUTO 下的受支持 Voxy设为首选；legacy 至少保留一个发布周期。

### P7：可选清理

只有满足以下条件才删除旧 LOD renderer：

- 至少一个完整发布周期没有必须回退的严重问题；
- 已决定 Voxy 是硬依赖，或接受无 Voxy 时没有远景 LOD；
- 协议不再需要服务端 quads；
- license/分发结论完成；
- 性能与兼容矩阵达到门槛。

删除时只删除 LOD 专用路径；`AllvrRenderer` 的 full-res cube、block entity 与其他 Allay 渲染职责必须保留。

## 11. 测试矩阵

### 11.1 数据正确性

- 程序化自然节点、已编辑且已加载 cube、已编辑但未加载的持久化 overlay。
- air、stone、dirt、草地、发光方块；第一版不支持的透明/特殊 block 明确降级。
- 节点六面邻居的加载、忘记、修改；负坐标和 floor/shift 边界。
- 同一绝对节点重复包、乱序包、forget 先到、旧 generation、旧 epoch。
- L0～L3 cell 与 Voxy key round-trip；L4 synthetic ancestor mask。

### 11.2 空间与生命周期

- Y：0、±512n、±3584、±4095/4096、±1,000,000、±30,000,000 边界。
- X/Z：0、负数、region/section 边界、接近 ±29,999,984。
- 传送超过一个窗口、持续上下飞行、死亡重生、断线重连、维度往返。
- server view distance 512/2048/4096；客户端图形距离变化。
- resource reload、renderer recreation、Voxy on/off、Iris on/off。

### 11.3 组合兼容

- 目标 1.21.1 Voxy jar + 固定 Sodium/Iris 版本。
- Voxy 缺失、Voxy 被禁用、未知 Voxy 版本、Voxy renderer 创建失败。
- shaderpack off、Complementary Reimagined、另一个代表性 pack。
- 单人内置服务器与多人服务器；两种环境的 storage 都不能落虚拟 section。

### 11.4 性能门槛

- 普通帧 section 转码/注入不超过 2 ms p95；超出时必须延后，不阻塞 render thread。
- rebase 不产生大于 100 ms 的单帧主线程停顿；清理和重填必须分帧。
- 稳态 Voxy mesh backlog 有上限并能回落；无无限 pending。
- 30 分钟飞行后 CPU heap、native buffer、GPU geometry 和 owned section 数量回到与当前活动窗口相符的范围。
- 新 section payload 的网络 p95 与旧 mesh payload进行实测比较；若放大超过 2 倍，优先改进 palette/RLE/压缩和请求复用，而不是回传 Voxy 内部 id。

## 12. 验收标准

功能：

- 在受支持 Voxy 构建下，Allay 远景只由 Voxy renderer 绘制；近景仍由 ALLVR full-res renderer 绘制。
- 玩家位于任意合法绝对 Y 时，至少能看到垂直半径 3072 内、水平服务器半径内的 LOD。
- 放置/破坏方块会使相应 L0～L3 节点更新；重启后持久化修改仍进入 LOD。
- 分带移动与 Y rebase 后无稳定存在的洞、重复面、错误高度残影。

可靠性：

- Voxy 缺失或 ABI 不支持时不会 crash；AUTO 明确回退 legacy。
- 旧 packet、旧 build job 和旧 epoch 都不能复活已忘记节点。
- 退出维度、renderer reload、断线后 owned ref 和队列全部释放。
- Allay 虚拟 section 不写入 Voxy 磁盘数据库。

渲染：

- Iris on/off 均无 GL error、黑屏和 framebuffer/depth 污染。
- 近远景交界无长期 z-fighting；Voxy 自己的 frustum/Hi-Z/temporal 结果在 rebase 后正确。
- camera-relative 计算只使用虚拟 Y，shader/GPU 不接收 ±30M 的绝对 Y float，从而避免高 Y 精度抖动。

合规：

- CMI 发行物中没有 Voxy 源码、shader、资源或二进制。
- 每个引用的 Voxy 内部符号都有互操作用途、版本门禁和出处记录。
- 所选 1.21.1 移植 artifact 的再分发与使用条件已经单独核实。

## 13. 主要风险与控制

| 风险 | 控制措施 |
|---|---|
| Voxy 内部 API 快速变化 | 固定 artifact hash；每版本一个薄 adapter；启动时 descriptor probe；未知版本回退 |
| 社区 1.21.1 移植本身不稳定 | P0 必须使用真实发布 jar 做 smoke/soak；本地带 conflict marker 的源码不作为质量证明 |
| ARR/禁止再分发 | 只做互操作、不捆绑、不复制；发布前核实上游和移植版许可 |
| 高层稀疏 leaf 与 Voxy 标准拓扑不同 | P0 单独验证；owned registry 维护父子 mask；只在必要消费点做最小 mixin |
| virtual Y key 串层 | 内存 storage、DONT_SAVE、epoch、严格范围检查、rebase 先清再复用 |
| rebase 卡顿或空窗 | 512 对齐、分帧清理/重填、粗层优先、fog 掩护；首版不做双 engine |
| native ingest 覆盖 ALLVR 数据 | Allay identifier 全面禁用 Voxy 原生 chunk ingest；engine 单写者 |
| 材质/光照不一致 | 网络传标准 BlockState + 8-bit light；客户端通过当前 engine Mapper 映射；新旧路径回放对比 |
| “使用 Voxy”被误解为解决全部 bug | 验收按数据层、坐标层、拓扑层、renderer 层分别归因；Voxy 只接管 renderer-owned 问题 |

## 14. 最终建议

立即按 P0→P4 顺序推进方案 2，并把 P0 视为真正的 go/no-go gate。不要先删除当前 LOD，也不要先大规模改 shader。先证明一个外部 L2 section 能通过指定 1.21.1 Voxy 构建完成“注入、显示、更新、忘记、Y 平移、无磁盘残留”的闭环；闭环成立后再迁移网络协议和全部距离分带。

这一顺序能最大化利用 Voxy 已验证的渲染能力，同时把许可证风险、社区移植质量、内部 ABI 和 4096 Y 限制都限制在一个可测试、可回退的兼容层内。
