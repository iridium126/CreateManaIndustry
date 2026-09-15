# ALLVR（Allay Dimension）开发说明

状态：🟡 数据层、世界生成、Cube 生命周期和基础网络已实现；客户端原生渲染 parity、Voxy 远景闭环和部分原版 tick/方块实体同步仍需验证或补齐。

ALLVR 的目标是在一个普通 NeoForge 1.21.1 维度中提供可扩展的三维 Cube 存储和超出原版高度的游玩空间。代码命名统一使用 `Allvr`；资源 id 为 `createmanaindustry:allay_dimension`。

## 1. 坐标与世界范围

| 范围 | 存储/生成 | 说明 |
| --- | --- | --- |
| `-128 <= Y < 384` | 原版 `LevelChunk`，32 个 section | 使用原版 noise、结构、carver、feature、光照和高度图 |
| `Y < -128` 或 `Y >= 384` | 32³ `AllvrCube` | 由 Cube 生成/缓存/网络/持久化路径处理 |

逻辑边界：XZ 为 `±29,999,984`，Y 为 `±30,000,000`。`BlockPos.asLong()` 不能作为跨高度存储 key；Cube 使用 `AllvrCubePos`，Cube 内方块使用 `AllvrCoords` 的局部索引。

核心坐标入口：`dimension/cube/AllvrCoords`、`AllvrCubePos`、`AllvrDimensionLimits`。

## 2. 数据与生命周期

服务端的 `AllvrCubeMap` 负责：

- 按玩家订阅范围生成或加载 Cube，并通过 `ClientboundAllvrCubePacket` 发送快照。
- 接收方块修改，维护 block entity、ticker、实体分区和 dirty 状态。
- 远距卸载 Cube；卸载前交给异步 `AllvrCubeIoWorker` 保存。
- 在玩家退出或换维度时清理订阅。

客户端 `AllvrClientCubeCache` 负责接收、替换、forget Cube，驱动客户端方块实体 tick，并把变更通知 Sodium/Voxy 适配层。所有异步结果必须检查 level epoch/revision，不能把旧维度或旧资源的结果发布到当前缓存。

网络入口：`dimension/net/ClientboundAllvrCubePacket`、`ClientboundAllvrBlockUpdatePacket`、`ClientboundAllvrForgetCubePacket`；事件入口：`AllvrServerHandler`、`AllvrClientBlockHook`。

持久化格式和恢复约束集中在 [allay-dimension-persistence-plan.md](allay-dimension-persistence-plan.md)。

## 3. 世界生成

`AllvrChunkGenerator` 只负责中央原版高度带，要求 noise settings 覆盖 `[-128, 384)`；外围 Cube 由 `AllvrTerrainSource`/`AllvrIslandFieldGenerator` 提供。完整数据包文件、密度场和默认岛屿布局见 [allay-dimension-worldgen.md](allay-dimension-worldgen.md)。

不要把历史上的自定义密度场描述当作中央原版带的现状；当前中央带是 datapack/vanilla noise 路径。

## 4. 光照、碰撞和实体

- Cube 光照由 `AllvrLightEngine` 提供，中央带仍使用原版光照。
- `AllvrBlockCollisionsMixin` 和 Cube 查询共同保证超出原版高度的碰撞。
- 非玩家实体所在 Cube 未加载时会冻结 tick，避免读到 air 后坠落；玩家移动在边界处由 ALLVR mixin 约束。
- `AllvrPredictionHandlerMixin` 维护高 Y 窗口下客户端方块预测的真实位置映射。

这些是跨坐标空间的安全边界，不应通过简单放宽 vanilla `BlockPos` 或 height accessor 解决。

## 5. 客户端渲染现状

当前已落地的是 Cube 缓存、Y 窗口、光照采样以及 Sodium section bridge 的基础设施：

- `AllvrRenderYWindow` 维护 Sodium 可见的有限 Y 窗口，并处理跨窗口切换。
- `AllvrSodiumBridge` 将 Cube 的 2×2×2 section 映射到 Sodium section，提供 snapshot、dirty、add/remove/rebuild 队列。
- `AllvrSodiumCompatibilityProbe` 和版本专用 API 类负责能力/ABI 门禁。
- `AllvrVoxyClientIngest`、`AllvrVoxyYSlab` 已提供 Voxy 数据导入和 Y slab 的边界模型，但完整远景 resident/forget/重基准闭环仍属计划。

Sodium 方案见 [allay-dimension-sodium-rendering-parity-plan.md](allay-dimension-sodium-rendering-parity-plan.md)，Voxy 方案见 [allay-dimension-voxy-lod-integration-plan.md](allay-dimension-voxy-lod-integration-plan.md)。两份文档中的未来阶段不能视为已完成能力。

## 6. 配置、注册与验证

- 客户端总 LOD 开关：`allvr.lod`，定义于 `ClientConfig`。
- 维度/类型：`AllvrDimensions`；维度 JSON：`data/createmanaindustry/dimension/allay_dimension.json`。
- 服务端保存目录和区域文件由 `AllvrRegionCubeStorage` 决定；不复制 CubicChunks/regionlib 文件格式。

建议验证顺序：

1. 创建维度并在 `Y=-128/384` 两侧穿越，确认方块、碰撞和客户端不崩溃。
2. 修改带方块实体的 Cube，离开加载范围后重新进入，确认保存、恢复和 ticker。
3. 两个客户端同时观察同一 Cube，确认 full snapshot、block update、forget 顺序。
4. 启用/禁用 Sodium、Voxy 以及不兼容版本，确认门禁回退到可用路径。
5. 进行资源重载、换维度、退出重进和服务器停止，确认 epoch、线程和 GL 资源清理。

## 7. 当前明确缺口

- 原版随机 tick、scheduled tick 的 Cube 所有权尚未完整建立。
- Cube block entity 的完整客户端 NBT 同步、销毁和所有特殊渲染器仍需补齐。
- Sodium 完整材质/pass parity、Voxy 远景层级与 forget/ack 背压尚未达到验收标准。
- LOD/渲染审查中的问题统一列在 [allay-dimension-implementation-review-fix-plan.md](allay-dimension-implementation-review-fix-plan.md)，不要在本文件重复维护问题详情。

## 8. 入口索引

- 公共：`dimension/AllvrDimensions`、`AllvrDimensionLimits`、`dimension/cube/*`、`dimension/gen/*`。
- 持久化：`dimension/storage/*`。
- 客户端：`client/dimension/*`、`client/dimension/render/sodium/*`、`client/dimension/lod/voxy/*`。
- Mixins：`mixin/allvr/*`、`mixin/sodium/*`、`mixin/voxy/*`。
