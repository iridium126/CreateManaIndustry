# Allay Dimension：Sodium 原生近景渲染接入最终实施计划

> 文档类型：最终实现计划；本次只修订文档，不包含实现代码
>
> 修订日期：2026-09-08
>
> 仓库基线：Create: Mana Industry `b774b41`；Minecraft 1.21.1 / NeoForge 21.1.236 / Java 21
>
> 对照版本：Sodium NeoForge `0.8.13+mc1.21.1`；Iris NeoForge `1.8.14-beta.1+mc1.21.1`；Voxy `0.2.15-beta+1.21.1-neoforge`
>
> 最终决定：Sodium 成为本项目的**客户端硬依赖**；Allay 近景地形只接入 Sodium，不再建设原版地形渲染后端；Iris 与 Voxy 保持可选。
>
> 文档关系：本计划取代本文档旧版“继续完善自研 ALLVR 近景渲染器”的决定。`allay-dimension-voxy-lod-integration-plan.md` 继续负责 Voxy 远景数据、虚拟 Y 和生命周期细节。

## 1. 最终结论

采用单一生产路径：

```text
ALLVR 32³ Cube 数据
        │
        ▼
ALLVR→Sodium section 数据源与虚拟坐标适配
        │
        ▼
Sodium 原生 ChunkBuilderMeshingTask / RenderSectionManager
        │
        ├── 无 Iris：Sodium 原生 terrain shader
        └── 有 Iris：Iris 自动替换 Sodium terrain program/framebuffer/shadow pass

远景：ALLVR LOD section → Voxy → Voxy 自己的渲染与 Iris shader-pack 接口
```

具体选择如下：

1. 放弃继续建设自研 ALLVR 近景网格、材质、流体、透明排序、显存分配和 terrain shader。
2. 不建设原版 `ViewArea` / `SectionRenderDispatcher` 后端，也不维护“无 Sodium 时回退原版”的双实现。
3. Sodium 在客户端为 required dependency；未安装或版本不匹配时，由 NeoForge 在启动阶段明确报依赖错误，而不是运行到维度内再静默降级。
4. Iris 仍为可选依赖。安装并启用光影包时，近景地形通过 Sodium 的标准 terrain pass 自动进入 Iris；本项目只维护坐标/生命周期接入和兼容性验证，不再维护一套 ALLVR 专用近景光影 shader。
5. Voxy 仍为可选的唯一远景后端。Voxy 不走 Sodium 的标准近景 terrain pass，因此 Voxy 的 `voxy.json`、虚拟 Y、采样器、uniform、framebuffer 与 shader-pack 适配仍需保留。
6. 旧自研 renderer 在迁移期间只作为开发回滚路径存在；新路径达到全部发布门后删除，不作为发布版可选后端长期维护。

这不是“把 block state 交给 Sodium 就完全零适配”。需要主动适配一次 Sodium 的数据源、section 生命周期和虚拟 Y；完成后，模型、流体、材质 pass、透明排序、批处理以及 Iris 近景光影由 Sodium/Iris 维护。

## 2. 对两个关键问题的直接回答

### 2.1 把地形数据输入 Sodium 后，光影包能否自动生效

**可以，但前提是地形完整进入 Sodium 的标准地形管线。**

必须同时满足以下条件：

- 网格由 Sodium 的 `ChunkBuilderMeshingTask` 及其 block/fluid renderer 生成；
- 结果使用 Sodium 的 chunk vertex format、material 和 `TerrainRenderPass`；
- section 由 Sodium 的 `RenderSectionManager` 参与可见性、排序、上传和绘制；
- 实际 draw 仍从 Sodium 的 `ShaderChunkRenderer` / `renderLayer` 发出；
- solid、cutout、translucent 和 shadow 都沿用 Sodium/Iris 已知的 pass 时序。

Iris 的 Sodium 兼容层会在 `MixinShaderChunkRenderer` 中为每个 `TerrainRenderPass` 绑定光影包对应的 framebuffer 和 `SodiumPrograms`，并对 Sodium render lists 建立 shadow pass。只要 ALLVR section 与普通 Sodium section 在这一层不可区分，Photon 等光影包会自动处理近景地形，不需要本项目再为每个光影包生成 ALLVR 专用 gbuffer/shadow shader。

下列做法**不会**自动获得 Iris 支持：

- 只借用 Sodium 的 mesher，之后把顶点复制到 ALLVR 自有 VBO 并在自定义事件中 draw；
- 继续使用 `assets/.../shaders/allvr/terrain.*`，仅让 Sodium 提供 block state；
- 使用自定义 framebuffer、pass 顺序、vertex format 或独立 shadow draw；
- 在 Sodium terrain pass 结束后叠加 ALLVR 地形。

因此本计划的硬边界是：**ALLVR 只替换 Sodium 的 section 数据来源与坐标解释，不替换 Sodium 从构建结果到最终 draw 的后半条管线。**

仍需由本项目负责的 Iris 相关工作只有：

- 确认 virtual Y 下 Sodium terrain 的 camera-relative 顶点位置、Iris `cameraPosition` 和光影包 world-position 重建一致；
- 验证阴影视锥、TAA jitter、translucent 和 framebuffer 切换没有因虚拟坐标产生偏移；
- 对受支持的 Sodium/Iris 版本做 ABI 与行为门禁；
- 保留非 terrain 功能自身需要的 Iris/Veil 适配；
- 保留 Voxy 远景的独立 Iris 适配。

这些属于一次性的管线接入和版本兼容，不是逐光影包重写近景 renderer。

### 2.2 把地形映射到原版后，能否自动获得 Sodium 支持

**理论上可以，简单映射不可以。**

如果 ALLVR 数据被构造成真正的 `ClientLevel -> LevelChunk -> LevelChunkSection[]`，并完整触发原版 chunk ready、section add/remove、dirty、light、block entity 和 resource reload 生命周期，那么 Sodium 替换 `LevelRenderer` 后通常会像处理普通世界一样自动接管。此时原版与 Sodium 可以共用同一个 synthetic chunk facade。

但当前 ALLVR 的 `Level#getBlockState` 拦截和 32³ Cube cache 不满足这个条件：

- Sodium `RenderSectionManager.onSectionAdded` 直接取得 `level.getChunk(x,z).getSections()[index]` 判断 section；
- `RenderSectionManager.createRebuildTask` 固定调用 `LevelSlice.prepare(level, sectionPos, sectionCache)`；
- `ClonedChunkSectionCache.clone` 再次直接从 `LevelChunk` 和 `LevelChunkSection[]` 克隆数据；
- section 图与越界判断使用 `SectionPos` 和 `ClientLevel` 的正式 build height。

所以仅把 `getBlockState` 指向 Cube、向原版 renderer 发 dirty 事件，Sodium 仍会读到 384 高度空壳 chunk，而不是 ALLVR Cube。

要让“映射到原版”真正自动工作，必须维护一个隔离的 synthetic vanilla 世界：

- 每个 32³ Cube 拆为 4 个 X/Z column、每 column 2 层 16³ section；
- 为超高 Y 建立滑动虚拟高度窗口；
- 镜像 palette、biome、light、ModelData、block entity 和 auxiliary light；
- 维护 chunk tracker、section 生命周期、邻接和 rebase；
- 防止玩法、实体、粒子或其他模组把 synthetic chunk 当作权威世界数据。

这已经不是“少量映射”，而是维护第二个客户端世界模型。它既没有消除 Sodium 私有 ABI 接入，也增加了数据复制和跨模组泄漏风险。因此本计划不采用 synthetic vanilla facade，而是直接建设窄边界的 Sodium section source adapter。

## 3. 为什么选择 Sodium 单后端

当前 5 FPS 的根因不是 draw-call 入口本身，而是自研 renderer 尚未覆盖 Minecraft 的通用地形语义。大量不满足“六面完整 SOLID 立方体”条件的方块落入逐方块 fallback；fallback 每帧收集、排序、调用 `renderSingleBlock` / `renderLiquid` 并上传，导致 CPU 和 GPU 都重复处理近景地形。继续完善自研方案意味着还要长期维护：

- 任意 `BakedModel`、multipart、random/weighted、ModelData 和自定义 render type；
- 流体 tessellation、AO、逐顶点光照、biome tint、动画 sprite；
- solid/cutout/translucent pass 和动态透明排序；
- section 调度、取消、上传预算、region allocator、显存回收；
- Iris gbuffer、shadow、TAA、pack material id 和版本变化。

Sodium 已经承担这些职责。直接接入虽然需要处理高 Y，但总体只维护“数据源 + 坐标 + 生命周期”三个边界，工作量和长期风险都小于同时维护自研、原版与 Sodium 三套行为。

| 维度 | 继续自研 | synthetic 原版映射 | 直接 Sodium bridge（选定） |
|---|---|---|---|
| 近景模型/流体完整度 | 需自行补齐 | 原版/Sodium 可复用 | Sodium 原生复用 |
| Iris 近景适配 | ALLVR 专用 | 自动，前提是完整 facade | 自动，前提是标准 Sodium draw |
| 高 Y | 原生绝对 key | 必须滑动窗口 | 必须 renderer-private 虚拟 Y |
| 数据复制 | 较少 | 最大，维护第二世界 | snapshot 所需最小复制 |
| 运行时后端数 | 自研 + 兼容路径 | 原版 + Sodium 分支 | 仅 Sodium |
| 性能成熟度 | 当前约 5 FPS | 原版较弱、Sodium 较好 | Sodium 最佳 |
| 升级成本 | 本项目全部承担 | Minecraft + Sodium 双边 | 集中的 Sodium version adapter |
| 第三方状态泄漏 | 低 | 高 | 低 |

## 4. 依赖和兼容策略

### 4.1 依赖级别

- **Sodium：客户端硬依赖。** `neoforge.mods.toml` 增加 `modId="sodium"`、`type="required"`、`side="CLIENT"` 和受支持版本范围。
- **Iris：客户端可选依赖。** 无 Iris 时使用 Sodium 原生 shader；有 Iris 时走 Iris 的 Sodium 兼容层。
- **Voxy：客户端可选依赖。** 可用时提供远景；缺失或失败时稳定退化为 Sodium 近景 + 雾，不恢复 legacy LOD。
- **服务端：不要求安装 Sodium。** required dependency 的 side 必须为 `CLIENT`，避免专用服务器加载客户端渲染模组。

开发首版固定验证 Sodium `0.8.13+mc1.21.1`。由于计划需要接入 `RenderSectionManager`、`LevelSlice` 和 cloned section 内部路径，不能一开始声明开放式 `[0.8,)`。先使用精确版本或窄范围；新增版本必须经过编译、mixin audit、冒烟、视觉和性能矩阵后再扩展。

### 4.2 构建与发布

实施时应：

1. 在 `build.gradle` 增加可复现的 Sodium compile dependency，并把同版 Sodium 加入开发运行环境；不把 Sodium jar 打入 CMI jar。
2. 在 `src/main/templates/META-INF/neoforge.mods.toml` 增加 CLIENT required dependency。
3. 保留 Iris/Voxy 为 `compileOnly` 或隔离的 version adapter，避免把可选模组打包进 CMI。
4. CI 增加“仅 Sodium”“Sodium+Iris”“Sodium+Voxy”“Sodium+Iris+Voxy”四个运行组合。
5. 启动时记录一次 Sodium adapter 版本和签名探测结果；签名不匹配应中止加载并给出明确版本信息，禁止半初始化后黑屏。

## 5. 固定架构边界

### 5.1 数据所有权

- 服务端、网络、存档、玩法、碰撞、实体和 block entity 的权威坐标始终是 ALLVR 绝对坐标。
- `AllvrClientCubeCache` 仍是客户端近景唯一数据源；不把 synthetic chunks 注册进 `ClientChunkCache`。
- `AllvrCube` 的 8 个 `LevelChunkSection` 继续复用，逻辑单位仍为 32³ Cube。
- Sodium bridge 只生成不可变的构建 snapshot；worker 不直接持有会被 packet/update 修改的 live palette。
- 渲染 section 使用 16³，故一个 Cube 映射为 2×2×2 个 Sodium render section。

### 5.2 画面所有权

```text
0 ～ 近景边界：Sodium render sections（唯一 owner）
近景边界 ～ 远景上限：Voxy（仅在 backend 实际可用时）
无 Voxy：近景边界外由雾过渡到天空
block entity / dynamic instance：现有绝对坐标路径，单独做 ownership 去重
```

- 自研 ALLVR terrain draw 与 Sodium terrain 不得同时启用。
- 迁移阶段的 legacy 开关仅为开发回滚，默认关闭，不能向普通用户暴露为长期后端选项。
- Voxy seam 必须使用 readiness + hysteresis；不能仅凭距离让两个 renderer 同时覆盖同一体素。

### 5.3 不做的事情

- 不扩大全局 `ClientLevel` build height。现有注释已证明这会破坏 Sodium 的 section array 边界保护。
- 不伪造完整 vanilla `LevelChunk` 世界。
- 不 fork 或复制 Sodium renderer。
- 不在 Sodium draw 后叠加 ALLVR 专用 terrain pass。
- 不让 Voxy 取代近景精确模型渲染。
- 不承诺任意未来 Sodium/Iris 版本无需验证即可兼容。

## 6. Renderer-private 虚拟 Y

### 6.1 坐标定义

Sodium 的 section graph、`SectionPos` 和 `BlockPos` 必须工作在安全的小范围 Y 内；ALLVR 的权威世界仍使用约 ±3000 万绝对 Y。定义：

```text
originBlockY   = 512 对齐的窗口原点
originSectionY = originBlockY >> 4

virtualBlockY   = absoluteBlockY   - originBlockY
virtualSectionY = absoluteSectionY - originSectionY
absoluteBlockY  = virtualBlockY    + originBlockY
```

X/Z 保持原坐标。Sodium section identity、render region、可见性与透明排序使用 virtual Y；模型查询、随机种子、biome tint、光照和 block entity 使用转换后的 absolute Y。

### 6.2 相机与 shader 规则

- 传入 Sodium section culling、region offset、mesh sort 的 terrain camera Y 必须是 `cameraY - originBlockY`。
- section vertex 与 terrain camera 同减一个 origin，得到的 camera-relative 几何位置与绝对世界完全相同。
- Iris 的全局 `cameraPosition` 继续表达绝对相机坐标；光影包把 camera-relative position 加回 camera position 后仍得到绝对世界位置。
- 禁止把 ±3000 万 absolute Y 直接编码进 Sodium chunk vertex float。
- 非 terrain renderer 不接收虚拟 camera，避免实体、天空、粒子和后处理整体错位。

### 6.3 窗口与 rebase

首版复用 Voxy 已验证的 512-block 对齐与触发距离，并抽取共享 `AllvrRenderYWindow`：

- 玩家距 origin 达 512 blocks 时触发新 origin；
- near cube 请求半径和 forget hysteresis 必须保证所有 resident section 落在 `SectionPos` / `BlockPos` 安全范围；
- Sodium 与 Voxy 共享 origin/epoch，避免两个空间定义产生 seam 漂移；
- Voxy 不可用时同一 origin 管理器仍服务 Sodium。

rebase 采用严格状态机：

1. `STEADY`：旧 epoch 正常构建与绘制。
2. `FREEZE`：停止接收会进入旧 virtual key 的新任务，`windowEpoch++`；迟到结果全部丢弃。
3. `DETACH`：分帧移除旧 Sodium sections 和 Voxy nodes；释放/取消由 generation 保护。
4. `MOVE`：在帧边界原子发布新 origin；terrain camera 和 section mapping 必须同帧切换。
5. `REFILL`：中心近景优先重新注册，屏幕内 section 优先构建；Voxy 从粗层向细层回填。
6. `STEADY`：所有 ownership 和 backlog 回到正常策略。

首版不同时维护两个 Sodium renderer。rebase 空窗使用旧帧保留、雾和分帧 refill 掩护；禁止为了无缝而让旧/新 epoch 同帧 draw，否则会重现地形叠帧覆盖天空的问题。

## 7. Sodium 接入设计

### 7.1 单一适配层

新增集中包，例如：

```text
client/dimension/render/sodium/
    AllvrSodiumBridge
    AllvrSodiumSectionSource
    AllvrSodiumSectionSnapshot
    AllvrSodiumSectionLifecycle
    AllvrSodiumCoordinateSpace
    AllvrSodiumCompatibilityProbe
    SodiumApi_0813_1211
```

其他 ALLVR 模块不得散落引用 Sodium private classes。所有内部 ABI 调用集中在 `SodiumApi_0813_1211`，以便版本升级时替换一个 adapter，而不是全仓修改。

### 7.2 Section source

`AllvrSodiumSectionSource` 按 absolute section 坐标读取 `AllvrClientCubeCache`：

1. 通过 floor division 定位 32³ Cube。
2. 通过 `AllvrCube.sliceIndex(ssx, ssy, ssz)` 取得对应 16³ `LevelChunkSection`。
3. 获取构建所需的 3×3×3 section 邻域；未知/未加载邻居按空气处理，但必须在邻居到达时 dirty 边界。
4. 克隆 palette/biome 为不可变 snapshot，附带 absolute origin、virtual render key、cube generation、content revision、resource revision 和 window epoch。
5. 提供 Sodium block/fluid model 构建所需的 light、biome tint、ModelData 与 auxiliary light 查询。

snapshot 只能在短锁或版本校验下生成。worker 完成时必须同时校验 world epoch、window epoch、content revision 和 resource revision；任一过期即丢弃，不能上传旧几何。

### 7.3 生命周期

Cube 事件映射如下：

| ALLVR 事件 | Sodium 行为 |
|---|---|
| cube packet 首次到达 | 注册 8 个非空/待判定 render sections，调度 initial build |
| cube 内 block update | invalid cloned snapshot；dirty 本 section及受遮挡/光照影响的边界邻居 |
| 邻接 cube 到达 | dirty 双方边界 section，消除先前空气 seam |
| cube forget | 取消任务，移除 8 section，释放 mesh/BE ownership |
| dimension unload | destroy bridge，epoch++，拒绝全部迟到结果 |
| resource reload | resourceRevision++，清 snapshot/cache，预算化重建 visible sections |
| Y rebase | 按第 6.3 节 detach/move/refill；旧 virtual key 不得复活 |

不能直接依赖 Sodium 的 vanilla `ChunkTracker`，因为 ALLVR Cube 不在 `ClientChunkCache` 中。bridge 必须主动驱动 section add/remove/dirty，并绕开 `onSectionAdded` 当前对 vanilla `LevelChunkSection[]` 的硬读取。

### 7.4 必要的 Sodium hook

对 Sodium 0.8.13，最小 hook 集合是：

1. `RenderSectionManager.onSectionAdded`：ALLAY 维度从 `AllvrSodiumSectionSource` 判断 air/non-air 并建立 render section，不索引空壳 `LevelChunkSection[]`。
2. `RenderSectionManager.createRebuildTask` 或 `LevelSlice.prepare`：ALLAY 维度构造 cube-backed `ChunkRenderContext`，普通维度保持原逻辑。
3. `ClonedChunkSectionCache.acquire/clone/invalidate`：将 ALLAY section 克隆重定向到 source；若选择在 `LevelSlice.prepare` 上层完全替换 context，则此处只做防误读断言。
4. camera/viewport 输入：只对 ALLAY terrain manager 把 Y 转换为 virtual；不能修改全局 Camera。
5. section key、dirty、visibility/debug 查询：外部 absolute Y 进入 manager 前统一转换为 virtual Y。
6. task publish/upload：附加 ALLVR epoch/revision 校验，旧任务不能覆盖新 section。

优先使用接口注入和窄 redirect；不得复制整个 `RenderSectionManager` 方法。每个 hook 都要有 `require`/签名检查和普通维度回归测试。

### 7.5 Sodium 原生能力必须完整保留

bridge 的成功标准不是“能看到草方块”，而是使用 Sodium 原生能力：

- 任意 `BakedModel`、culled/unculled quad、multipart、weighted/random model；
- NeoForge ModelData 和平台 model hooks；
- block 与 fluid renderer；
- AO、sky/block light、biome tint、normal、shade；
- solid、cutout、translucent pass 与透明排序；
- animated sprite activation；
- build priority、worker、取消、upload budget、region allocator；
- frustum、section graph、occlusion 与 shadow render lists；
- Sodium debug counters 和错误上下文。

若某阶段通过把复杂模型退回逐方块 immediate renderer 才显示正确，该阶段不算完成，因为这会恢复当前 5 FPS 的核心瓶颈。

## 8. 方块实体、光照和动态内容

### 8.1 方块实体

首版 terrain bridge 不把 synthetic `LevelChunk` 暴露给 Sodium。方块实体继续由 ALLVR 绝对坐标列表交给 vanilla/NeoForge/Flywheel dispatcher：

- Sodium mesh snapshot 只收集静态 block/fluid geometry；
- ALLVR 每帧按 near visibility 提交普通/global BE；
- Create/Flywheel 可实例化的 BE 交给实例 renderer，其余走 vanilla dispatcher；
- 一个 BE 只能有一个 owner，禁止 Sodium built-section list 与 ALLVR list 双绘；
- crumbling、outline、off-screen/global BE 和 unload 生命周期单独覆盖。

后续只有在 Sodium 提供稳定 external BE list 边界且实测有收益时，才迁移 BE ownership；它不是 terrain cutover 的前置条件。

### 8.2 光照与 biome

- 模型构建查询以 absolute `BlockPos` 为语义，避免随机 seed、offset 与 tint 随 rebase 改变。
- `AllvrLightSampler`/增量 light cache 向 Sodium build context 提供 sky/block light；邻域未就绪时使用明确 provisional 状态，邻居/光源到达后 dirty。
- biome palette 来自 Cube section；blend radius 查询跨 cube 时使用 absolute position。
- emissive、auxiliary light 和 modded light hooks 必须进入同一个 snapshot contract。

### 8.3 更新粒度

- 单方块更新通常只重建 1 个 16³ section。
- 位于 section 边界的遮挡、AO、流体或光照变化只通知实际相邻集合。
- 新 mesh 上传成功前保留旧 mesh；失败或预算延期不得先释放旧结果形成闪洞。
- 重建请求合并 revision，不为同一 section 堆积重复任务。

## 9. Iris 自动接入与验证边界

### 9.1 可删除的近景专用适配

在 Sodium cutover 完成且验证通过后，可删除仅服务自研近景 terrain 的：

- `assets/createmanaindustry/shaders/allvr/terrain.vsh` 与 `terrain.fsh`；
- `AllvrShaderCache`、ALLVR terrain program patch/compile 分支；
- 为自定义 terrain framebuffer、gbuffer 输出和 shadow draw 添加的 Iris mixin；
- 自研 terrain VBO/IBO/descriptor、fallback immediate draw 和自定义 pass 状态。

删除前逐项审计 `mixin/allvriris` 与 `client/dimension/iris`。名字含 ALLVR 不代表只服务 terrain；粒子、雾、Veil、Voxy sampler/uniform 和其他效果仍有独立用途，不得连带删除。

### 9.2 必测内容

- Iris 关闭：Sodium 的 solid/cutout/translucent 与主世界一致。
- Iris 开启但无 shader pack：行为与普通 Sodium/Iris 世界一致。
- Photon：gbuffer 位置、法线、lightmap、material id、depth、shadow、TAA 均正确。
- 至少再测一套 Complementary 系光影包，防止仅适配 Photon 的隐含假设。
- 快速转动、resize、shader reload、dimension switch 后无上一帧地形残留。
- virtual Y rebase 前后 world-position 相关效果不跳变；阴影不偏移，天空不被旧深度覆盖。

### 9.3 “自动生效”的验收定义

只有满足以下三点才可以删除 ALLVR 近景 shader 适配：

1. RenderDoc 中近景 ALLVR section 和普通 Sodium section 使用同类 terrain program、vertex format、pass 和 framebuffer。
2. shader pack on/off 都没有额外 ALLVR terrain draw call。
3. ALLVR 代码不再读取 shader-pack 源码、不再拼接 Photon/Complementary 专用近景 shader。

## 10. Voxy 远景保持独立

Voxy 即使依赖/协同 Sodium，也不是 Sodium 的普通 `RenderSectionManager` terrain section。它使用自己的 `WorldSection`、LOD mesh、viewport、存储和 shader-pack contract。因此：

- 保留 `VoxyLodBackend`、`AllvrVoxySectionWriter`、`AllvrVoxyNodeRegistry` 和 version adapter；
- 保留 Voxy virtual Y、viewport、top-level range、LOD threshold 和 storage/ingest mixin；
- 保留光影包 `voxy.json` contract、Voxy sampler/uniform、framebuffer 和 patch 数据；
- Sodium 近景与 Voxy 远景共享 origin/epoch，但各自维护 section/node residency；
- seam 状态机必须先确认新 owner ready，再在下一帧撤销旧 owner；
- Voxy 失败后只退化到 Sodium near-only，不启动自研或原版远景 fallback。

## 11. 参考 CubicChunks 的方式

参考 `.refs/CubicChunks` 的是数据与 renderer 解耦模式，而不是照搬其旧版本类：

- 用 3D cube cache 作为权威客户端数据，而非强行压入固定高度 column；
- renderer build cache 从 cube 数据按需提供邻域；
- 相机跨 cube 时增量维护 3D view/lifecycle；
- 玩法坐标与 renderer 内部坐标分离。

不照搬的部分：

- 旧 Minecraft 版本的 `ViewFrustum`、`RenderChunk` 和固定 OpenGL backend；
- 把现代 Sodium 当作原版 renderer 的透明替换而忽略其 cloned section / `LevelSlice`；
- 为兼容旧接口创建可被所有模组访问的 synthetic gameplay chunks。

本计划相当于把 CubicChunks 的 `RenderCubeCache` 思路落到 Sodium 的 `ChunkRenderContext` 输入边界。

## 12. 分阶段实施

### P0：冻结基线与删除条件

- 固定可复现场景：规则岛屿、草/植物/楼梯/栅栏展台、流体+玻璃、Create/Flywheel BE、高 Y 垂直飞行。
- 记录现状 CPU/GPU p50/p95/max、section 数、fallback block 数、draw count、upload bytes、显存、worker backlog。
- 保存 shader off、Photon on 的截图和 RenderDoc capture。
- 为旧自研 renderer 增加仅开发可用的总开关与计数，明确最终删除清单。

退出标准：5 FPS 瓶颈可重复，所有后续阶段都能与同一录制比较。

### P1：Sodium 硬依赖和 compatibility probe

- 增加 CLIENT required metadata 和可复现编译依赖。
- 建立 `SodiumApi_0813_1211` 与签名/版本 probe。
- 移除“无 Sodium 时正常进入 Allay 并使用自研 renderer”的产品承诺。
- 普通维度保持 Sodium 原生行为，Allay 尚未切换时仍由 dev legacy 临时显示。

退出标准：缺失/错误版 Sodium 在启动期明确失败；正确版本进入主世界无回归。

### P2：共享虚拟 Y 与 section source

- 抽取 `AllvrRenderYWindow`，统一 Sodium/Voxy origin、epoch 和转换函数。
- 建立 cube→8 sections 的 key 映射、snapshot、邻域和 revision contract。
- 为 absolute↔virtual 坐标、负数 floor division、cube/section 边界和 rebase 写纯 JVM 测试。
- 禁止 client build-height widening；增加越界断言。

退出标准：Y=0、±1,000,000、±29,999,900 的 key round-trip 和邻域查询全部通过。

### P3：Sodium native meshing 最小原型

- 在 Allay 维度注册 cube-backed render sections。
- 替换 `LevelSlice.prepare`/cloned source，使 Sodium worker 直接看到 Cube snapshot。
- 先覆盖 full cube、草方块、植物、楼梯和静态水，确认全部由 Sodium chunk mesher 产生。
- 使用 Sodium 原生 upload、render list、terrain pass 和 shader；禁止 ALLVR terrain draw。

退出标准：RenderDoc 证明没有自定义 ALLVR terrain pass；shader off 时位置、深度和转动正确；性能明显脱离逐方块 fallback。

### P4：完整生命周期与资源安全

- cube add/update/neighbor/forget 到 section add/dirty/remove 完整映射。
- world/window/content/resource 四类 revision 和迟到结果过滤。
- 任务取消、失败结算、旧 mesh 原子替换、dimension unload 和 resource reload。
- 100 次进出维度、50 次 reload/resize、30 分钟高速流送无资源增长。

退出标准：无旧 section 复活、永久洞、重复几何、worker 死亡或 GL error 洪泛。

### P5：模型、流体、光照和 ModelData 完整度

- 接通 Sodium/NeoForge model、fluid、ModelData、tint、AO/light 和 animated sprite 所需的 level/snapshot 查询。
- registry coverage scan：所有可见 BlockState 必须由 Sodium native mesh 覆盖或有书面非地形原因。
- 完成 absolute seed/tint/light 语义和边界 dirty。

退出标准：展台与普通 Sodium 世界没有系统性缺面；fallback immediate terrain block 数恒为 0。

### P6：Iris 自动接入

- 验证 Sodium `TerrainRenderPass` 到 Iris `SodiumPrograms` 的标准路径。
- 完成 virtual terrain camera 与 absolute Iris camera uniform 的一致性测试。
- 覆盖 main、shadow、translucent、TAA、reload 和 resize。
- 删除已证实冗余的 ALLVR 近景 terrain shader/patch；保留 Voxy 和非 terrain Iris 代码。

退出标准：Photon 与第二套 shader pack 通过第 9 节；近景无 ALLVR 特制 shader/draw。

### P7：Voxy seam、BE 与 rebase

- Sodium near / Voxy far ownership state machine 与 hysteresis。
- Voxy 缺失、reload、运行失败时 near-only 闭环。
- BE/Flywheel/crumbling/outline 单一 ownership。
- 多次向上/向下跨 512 对齐点，验证 freeze/detach/move/refill。

退出标准：边界和 rebase 无长期洞、双绘、z-fighting、旧深度或天空覆盖伪影。

### P8：性能收口与移除自研 renderer

- 用 profiler 和 GPU timestamp 对照主世界 Sodium、Allay Sodium bridge 和旧 renderer。
- 优化只允许发生在 snapshot、dirty 合并、调度预算和 seam；不 fork Sodium mesh/draw。
- 删除旧 terrain shader、mesher worker、fallback per-block draw、allocator、node store 和配置入口。
- 更新开发文档、依赖说明和故障诊断。

退出标准：第 14 节全部通过，发布构建中不存在第二套近景 terrain renderer。

## 13. 计划中的文件落点

### 13.1 修改

| 文件/区域 | 计划 |
|---|---|
| `build.gradle` | 增加 Sodium compile/dev runtime dependency；Iris/Voxy 保持可选且不打包 |
| `src/main/templates/META-INF/neoforge.mods.toml` | 增加 Sodium CLIENT required 依赖与窄版本范围 |
| `createmanaindustry.mixins.json` / mixin plugin | 新增 Sodium bridge hooks；普通维度保持原逻辑；按阶段移除旧 terrain Iris hooks |
| `AllvrClientCubeCache` | 发出 section source 所需的 add/update/neighbor/forget/revision 事件 |
| `AllvrVoxyYWindow` | 迁移到共享 `AllvrRenderYWindow`，保留 Voxy 特有范围检查 |
| `AllvrLodBackendManager` | 与共享 window/epoch 同步；失败仍为 near-only |
| `AllvrSodiumTerrainMixin` | 从“取消 Sodium terrain”改为 bridge 入口；最终不得取消标准 Sodium draw |
| ALLVR BE/render hooks | 仅保留 BE、动态内容和非 terrain 效果的绝对坐标渲染 |

### 13.2 新增

```text
client/dimension/render/AllvrRenderYWindow.java
client/dimension/render/sodium/AllvrSodiumBridge.java
client/dimension/render/sodium/AllvrSodiumSectionSource.java
client/dimension/render/sodium/AllvrSodiumSectionSnapshot.java
client/dimension/render/sodium/AllvrSodiumSectionLifecycle.java
client/dimension/render/sodium/AllvrSodiumCoordinateSpace.java
client/dimension/render/sodium/AllvrSodiumCompatibilityProbe.java
client/dimension/render/sodium/SodiumApi_0813_1211.java
```

### 13.3 达标后删除或收缩

| 模块 | 最终处理 |
|---|---|
| `AllvrRenderer` | 删除 terrain draw；若仍承担 BE/事件编排则改名并收缩 |
| `AllvrRenderWorld` | 删除自研 mesh/GPU scene；仅保留 lifecycle/ownership 时改名 |
| `AllvrCellMesher` / `AllvrMesherWorker` / `AllvrBuildScheduler` | 删除自研 terrain 构建路径 |
| `AllvrFallbackBlock` / immediate fallback | 删除；native Sodium coverage 必须为 100% |
| `AllvrBuffers` / `AllvrRegionArena` / `AllvrNodeStore` | 删除自研 terrain GPU 资源 |
| `AllvrShaderCache` / `assets/.../shaders/allvr/terrain.*` | 删除自研近景 terrain shader |
| ALLVR terrain-specific Iris mixins | 验证无其他用途后删除 |
| `AllvrLightSampler` 等 | 若仍为 Sodium build context 提供数据则保留并接口化 |
| Voxy adapter 与 Voxy/Iris patch | 保留 |

## 14. 性能、正确性与发布门

所有性能数据使用 release build、固定录制、相同视距和分辨率，至少 10,000 帧；报告 p50/p95/max、CPU 和 GPU 时间，不只报告平均 FPS。

### 14.1 性能门

- 稳定近景：Allay Sodium bridge 的 terrain CPU/GPU p95 不得比相同可见 section/几何量的普通 Sodium 对照慢 20% 以上。
- 目标设备、无光影包、1080p 固定场景：平均 FPS 至少达到同设备普通 Sodium 世界的 70%；若世界像素/几何量不同，必须同时给出 terrain GPU ms 归一化结果。
- Photon 场景：与同可见几何量的普通 Sodium+Iris 对照相比，额外 bridge CPU p95 ≤ 1 ms，额外 GPU terrain p95 ≤ 15%。
- 稳定视角不允许每帧重建、重新排序全部 blocks、重复上传静态 mesh 或同步 GPU readback。
- 单块更新通常只重建一个 16³ section；主线程 snapshot/apply/upload 合计 p95 ≤ 2 ms，单帧受 4 ms 预算硬限制。
- rebase 清理/回填必须分帧；主线程单帧峰值 < 50 ms，且不能产生持续重复 draw。
- 30 分钟高速飞行后 section、snapshot、native buffer 和 Voxy node 数量回到稳定区间。

### 14.2 正确性门

- shader off/on 时相机转动、平移、FOV 改变和窗口 resize 均无屏幕固定地形。
- 没有上一帧地形叠加、旧 depth、天空覆盖、双绘或 z-fighting。
- solid/cutout/translucent、流体、AO/light/tint、动画纹理与 Sodium 语义一致。
- Y=0、±1,000,000、±29,999,900 位置稳定，无 float 抖动、key alias 或随机模型跳变。
- shader reload、resource reload、dimension switch 和 Y rebase 不复活旧 epoch。
- Voxy on/off/失败均保持明确的 single-owner 近远景画面。

### 14.3 测试矩阵

| Sodium | Iris | Shader pack | Voxy | 预期 |
|---|---|---|---|---|
| 缺失/错误版本 | 任意 | 任意 | 任意 | 启动阶段明确依赖失败 |
| 正确 | 无 | 无 | 无 | Sodium 近景，边界外雾/天空 |
| 正确 | 有 | 关闭 | 无 | 与 Sodium 无 Iris 视觉一致 |
| 正确 | 有 | Photon | 无 | Sodium/Iris 近景、gbuffer/shadow/TAA 正确 |
| 正确 | 有 | 第二套代表包 | 无 | 无 pack-specific ALLVR 近景补丁 |
| 正确 | 无 | 无 | 有 | Sodium 近景 + Voxy 远景 |
| 正确 | 有 | Photon | 有 | Sodium/Iris 近景 + Voxy/Iris 远景，seam 单 owner |
| 正确 | 任意 | 任意 | ABI 错误/运行失败 | Sodium near-only，不 crash、不恢复 legacy |

至少覆盖 AMD、Intel、NVIDIA 各一组；性能门以用户报告问题的同一设备为首要基线。

## 15. 自动测试清单

- `AllvrSodiumCoordinateSpaceTest`：正负坐标、边界、absolute↔virtual round-trip。
- `AllvrRenderYWindowTest`：512 对齐、触发、epoch、连续 rebase。
- `AllvrSodiumSectionMappingTest`：一个 Cube 到 8 sections、邻域和负数 floor division。
- `AllvrSodiumRevisionTest`：乱序 build、更新中 dirty、forget、reload、跨 epoch。
- `AllvrSodiumLifecycleTest`：add/update/neighbor/remove 的准确 section 集合。
- `AllvrSodiumModelCoverageTest`：registry 中所有可见 state 不进入 immediate fallback。
- `AllvrSodiumDependencyTest`：metadata 只在 CLIENT required，支持范围与 adapter 一致。
- `AllvrNearFarOwnershipTest`：Sodium/Voxy ready、hysteresis、失败和 rebase。
- 离屏/RenderDoc golden：shader off、Photon、第二套 pack、shadow、translucent、高 Y。

## 16. 主要风险与控制

| 风险 | 控制 |
|---|---|
| Sodium 无稳定外部 section API | 所有 private ABI 集中在单 version adapter；精确版本门禁和 CI mixin audit |
| virtual Y 导致 cull/draw/shader 空间不一致 | 单一坐标类型；terrain camera 同源转换；RenderDoc + 高 Y golden；非 terrain 禁用 virtual camera |
| rebase 全窗口重建卡顿 | 512 对齐、稀有触发、freeze/detach/move/refill、中心优先、分帧预算和雾 |
| 旧/新 epoch 同帧双绘 | frame-boundary 原子 origin；generation/revision 检查；不维护双 renderer |
| synthetic 数据污染玩法或其他模组 | 不注册进 `ClientChunkCache`；只通过 Sodium build context 暴露不可变 snapshot |
| 模型/光照查询仍落回 live Level | adapter 覆盖所有 build-context 查询；调试构建启用绝对/虚拟坐标断言 |
| Iris 自动路径被自定义 draw 绕开 | RenderDoc 验收；发布构建中禁止 ALLVR terrain draw/program |
| 删除 Iris 代码时误删 Voxy/雾/粒子功能 | 按调用图逐项审计，只删 terrain-specific 分支 |
| Voxy 远景被误认为也会自动兼容 Iris | 保留既有 Voxy shader contract 和测试，文档明确双边界 |
| Sodium 升级破坏内部 hook | 不自动放宽版本；新版本单独 adapter、基准与完整矩阵后启用 |

## 17. 回滚策略

迁移期间保留仅开发可见的 `legacyAllvrTerrain` 开关，用于对照和定位，规则如下：

- 同一帧只能有一个 near terrain owner；切换必须 destroy 当前资源并重建，不能叠加。
- legacy 不承担正式兼容承诺，不接收新功能。
- P6 通过后停止维护 legacy Iris path；P8 性能门通过后彻底删除。
- 若某一阶段失败，回滚该阶段的 Sodium bridge 变更，不恢复“原版后端”新支线。

## 18. 完成定义

全部满足才算完成：

- Sodium 是声明清楚、版本受控、仅客户端要求的硬依赖。
- Allay 近景 block/fluid geometry 全部由 Sodium native mesher、buffer、render list 和 terrain pass 处理。
- Iris 开启时近景经 Iris 的 Sodium 兼容层自动进入 gbuffer/shadow；不存在 ALLVR 专用近景 shader/draw。
- 原版 synthetic chunk renderer 和原版 fallback 从未成为生产后端。
- Voxy 仍是唯一可选远景后端，且其 Iris 适配保留。
- virtual Y、rebase、revision、resource reload 和 dimension lifecycle 没有旧结果复活或双绘。
- fallback immediate terrain block 数恒为 0；旧自研 terrain 构建/上传/绘制代码已删除。
- 第 14 节性能与正确性门、完整运行组合和长稳测试全部通过。

最终系统定位是：**ALLVR 负责超高 Y Cube 世界的数据与绝对坐标语义；Sodium 是唯一近景地形渲染器；Iris 自动接管 Sodium 近景 shader pipeline；Voxy 独立负责可选远景。**
