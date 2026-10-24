# ALLVR（Allay Dimension）开发说明

状态：R1–R8 修复及 tick、坐标、保存与光照边界整理已实现。自动化结果和用户客户端验收项目见 [验收记录](allay-dimension-validation.md)。客户端实机验收由用户执行。

ALLVR 的目标是在一个普通 NeoForge 1.21.1 维度中提供可扩展的三维 Cube 存储和超出原版高度的游玩空间。代码命名统一使用 `Allvr`；资源 id 为 `createmanaindustry:allay_dimension`。

## 1. 坐标与世界范围

| 范围 | 存储/生成 | 说明 |
| --- | --- | --- |
| `-128 <= Y < 384` | 原版 `LevelChunk`，32 个 section | 使用原版 noise、结构、carver、feature、光照和高度图 |
| `Y < -128` 或 `Y >= 384` | 32³ `AllvrCube` | 由 Cube 生成/缓存/网络/持久化路径处理 |

逻辑边界：XZ 为 `±29,999,984`，Y 为 `±30,000,000`。`BlockPos.asLong()` 不能作为跨高度存储 key；Cube 使用 `AllvrCubePos`，Cube 内方块使用 `AllvrCoords` 的局部索引。

绝对 section 使用 `AllvrSectionCoord(int x, int y, int z)`。实体管理器按 XZ 列、完整 section Y 索引，并使用不复用的 opaque handle 对接原版管理器；handle 禁止作为 SectionPos 解码。Sodium 与 Voxy 在转换到各自局部窗口后才使用原版 packed key。

核心坐标入口：`dimension/cube/AllvrCoords`、`AllvrCubePos`、`AllvrDimensionLimits`。

## 2. 数据与生命周期

服务端的 `AllvrCubeMap` 负责：

- 按玩家订阅范围生成或加载 Cube，并通过 `ClientboundAllvrCubePacket` 发送快照。
- 接收方块修改，维护 block entity、ticker、实体分区和 dirty 状态。
- 远距卸载 Cube；卸载前交给异步 `AllvrCubeIoWorker` 保存。
- 在玩家退出或换维度时清理订阅。

协调职责分拆为 `AllvrCubeBroadcaster`（订阅、广播、重同步）、`AllvrSimulationTickets`（模拟范围）、`AllvrCubeTicking`（计划与随机 tick）、`AllvrRetryQueue` 和 `AllvrUnloadQueue`（有界维护）。稳定玩家不重复扫描全量 Cube；玩家位置、视距、模拟距离和旁观状态变化才刷新票据。

客户端 `AllvrClientCubeCache` 异步解码 section/NBT，在主线程发布、创建 BE、调用 `handleUpdateTag` 和 NeoForge fresh BE 生命周期。完整包、增量和 forget 都有 watch generation；完整包与增量另有独立 network revision。解码期间缓冲增量，发布后按 revision 重放；缺口、解码失败或 4096 条缓冲溢出会请求受预算和订阅校验约束的完整重同步。持久化 mutation/light version 与网络排序无关。

服务端更新在 `ServerChunkCache.blockChanged` 接管外围位置，覆盖原版 `sendBlockUpdated` 与 Create `sendData`。同 tick 同位置合并，BE 增量保留 `getUpdatePacket/onDataPacket` 语义。客户端预测确认后再应用对应 BE 标签；移除、forget 和换维度清理旧状态。

当前网络协议为 **18**，客户端和服务端必须一致。

网络入口：`dimension/net/ClientboundAllvrCubePacket`、`ClientboundAllvrBlockUpdatePacket`、`ClientboundAllvrForgetCubePacket`；事件入口：`AllvrServerHandler`、`AllvrClientBlockHook`。

持久化格式和恢复约束集中在 [allay-dimension-persistence-plan.md](allay-dimension-persistence-plan.md)。

## 3. 世界生成

`AllvrChunkGenerator` 只负责中央原版高度带，要求 noise settings 覆盖 `[-128, 384)`；外围 Cube 由 `AllvrTerrainSource`/`AllvrIslandFieldGenerator` 提供。完整数据包文件、密度场和默认岛屿布局见 [allay-dimension-worldgen.md](allay-dimension-worldgen.md)。

不要把历史上的自定义密度场描述当作中央原版带的现状；当前中央带是 datapack/vanilla noise 路径。

## 4. 光照、碰撞和实体

- Cube 光照由 `AllvrLightEngine` 提供。方块光使用 2048 格核心加上下各 16 格 halo；halo 读取真实邻居，包括中央带。中央原版方块光引擎通过不可变 halo 快照读取外围边界；天空光不使用这个适配器。
- 天空光按 2048 格分带独立计算，并在 `-128/384` 切开。中央带独立，上层岛屿不遮蔽其他高度带；跨带连续性只保证方块光。halo 没有方块、tick 或存档所有权。
- `AllvrScheduledTicks` 分别保存 block/fluid 队列，按模拟票据推进本地时钟；暂停、卸载与离线时间不消耗剩余延迟。优先级、位置/类型去重及同刻 sub-tick 排序保留。
- 随机 tick 每个随机 ticking section 按原版 `randomTickSpeed` 采样，分别执行 block/fluid random tick。计划 tick、随机 tick 和 BE tick 都遵守 simulation distance 与服务器 freeze；中央带继续走原版规则。
- 最后一名玩家离开即停止外围模拟、取消不需要的任务；保存与有预算的卸载继续。保存关闭、快照失败和 pending IO 分别有保留计数，清洁 Cube 正常卸载。
- `AllvrBlockCollisionsMixin` 和 Cube 查询共同保证超出原版高度的碰撞。
- 非玩家实体所在 Cube 未加载时会冻结 tick，避免读到 air 后坠落；玩家移动在边界处由 ALLVR mixin 约束。
- `AllvrPredictionHandlerMixin` 维护高 Y 窗口下客户端方块预测的真实位置映射。

这些是跨坐标空间的安全边界，不应通过简单放宽 vanilla `BlockPos` 或 height accessor 解决。

## 5. 客户端渲染现状

当前已落地的是 Cube 缓存、Y 窗口、光照采样以及 Sodium section bridge 的基础设施：

- `AllvrRenderYWindow` 维护 Sodium 可见的有限 Y 窗口，并处理跨窗口切换。
- `AllvrSodiumBridge` 将 Cube 的 2×2×2 section 映射到 Sodium section，提供 snapshot、dirty、add/remove/rebuild 队列。
- `AllvrSodiumCompatibilityProbe` 和版本专用 API 类负责能力/ABI 门禁。
- `AllvrVoxyClientIngest` 队列保留完整 section 坐标，快照后映射到 `AllvrVoxyYSlab` 的局部坐标；Voxy 负责远景 ingest、层级和持久化，near forget 保留远景数据。

Sodium 方案见 [allay-dimension-sodium-rendering-parity-plan.md](allay-dimension-sodium-rendering-parity-plan.md)，Voxy 方案见 [allay-dimension-voxy-lod-integration-plan.md](allay-dimension-voxy-lod-integration-plan.md)。两份文档中的未来阶段不能视为已完成能力。

## 6. 配置、注册与验证

- 客户端总 LOD 开关：`allvr.lod`，定义于 `ClientConfig`。
- 维度/类型：`AllvrDimensions`；维度 JSON：`data/createmanaindustry/dimension/allay_dimension.json`。
- 服务端保存目录和区域文件由 `AllvrRegionCubeStorage` 决定；不复制 CubicChunks/regionlib 文件格式。

建议验证顺序：

1. 创建维度并在 `Y=-128/384` 两侧穿越，确认方块、碰撞和客户端不崩溃。
2. 修改带方块实体的 Cube，离开加载范围后重新进入，确认保存、恢复和 ticker。
3. 两个客户端同时观察同一 Cube，确认 full snapshot、block update、forget 顺序。
4. Sodium **0.8.13+mc1.21.1** 是必需依赖，缺失/未知 ABI 应明确拒绝；Voxy **0.2.15-beta+1.21.1** 可选，分别验证存在与缺失的场景。
5. 进行资源重载、换维度、退出重进和服务器停止，确认 epoch、线程和 GL 资源清理。

## 7. 验收边界

本次不恢复已删除的 Tier B/C 自研渲染路线。历史 F01–F36 清单保留作历史背景，当前修复和测试以 [R1–R8 验收记录](allay-dimension-validation.md) 为准。单元测试和 dedicated GameTest 不能替代 Sodium/Voxy 实机、两客户端网络时序及 GPU 资源验收。

## 8. 入口索引

- 公共：`dimension/AllvrDimensions`、`AllvrDimensionLimits`、`dimension/cube/*`、`dimension/gen/*`。
- 持久化：`dimension/storage/*`。
- 客户端：`client/dimension/*`、`client/dimension/render/sodium/*`、`client/dimension/lod/voxy/*`。
- Mixins：`mixin/allvr/*`、`mixin/sodium/*`、`mixin/voxy/*`。
