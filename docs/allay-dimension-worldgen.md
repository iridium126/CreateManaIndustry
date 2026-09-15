# ALLVR 世界生成说明

状态：✅ 数据包和生成器已实现；需要在实际世界中验证跨 Y 分带、结构/特性和长距离 Cube 生成。

## 1. 生成分工

ALLVR 不是把整个世界强行塞进 `LevelChunk`。生成器按 Y 分带：

| Y | 实现 | 数据来源 |
| --- | --- | --- |
| `-128 <= Y < 384` | 原版 `NoiseBasedChunkGenerator` | `createmanaindustry:smooth` |
| `Y < -128`、`Y >= 384` | Cube 生成/地形源 | `AllvrTerrainSource`、`AllvrIslandFieldGenerator` |

`AllvrChunkGenerator` 继承原版 noise generator，中央带固定为 `min_y=-128`、`height=512`。构造时会校验范围，防止 datapack 配置与客户端/存储边界不一致。

## 2. 入口资源

- 维度：`data/createmanaindustry/dimension/allay_dimension.json`
- 维度类型：`data/createmanaindustry/dimension_type/allay.json`
- chunk generator 注册：`createmanaindustry:allay_islands`
- 中央带 noise settings：`data/createmanaindustry/worldgen/noise_settings/allay.json`
- Cube/平滑地形 noise：`worldgen/noise/`、`worldgen/density_function/`
- 生物群系：`worldgen/biome/crystal_bloom_plains.json`

维度 generator 同时携带两套 noise generator：`chunks` 用于中央原版带，`terrain` 供外围地形源使用；两者使用固定的 `crystal_bloom_plains` biome source，避免当前阶段因生物群系采样产生额外分支。

## 3. 中央带约束

中央带沿用原版 chunk pipeline：noise、aquifer、surface rule、carver、结构、feature、光照、高度图、POI、方块实体和原版存档。其上下边界是半开区间 `[-128, 384)`，不要写成包含 384。

默认 allay noise settings 使用石头/水、原版 overworld noise router 和 deepslate 底部 surface rule；具体数值以 JSON 为准，文档不复制大型 noise 树。

## 4. Cube/岛屿层

外围层由 `AllvrIslandLayout` 提供确定性布局，由种子和 Cube 坐标得到岛屿/空域结果；`AllvrIslandFieldGenerator` 负责把布局转换为方块。Cube 大小为 32³，跨边界时由 `AllvrCubeMap` 生成或从磁盘恢复。

生成路径必须满足：

- 同一种子和 Cube 坐标重复生成结果稳定。
- 未加载 Cube 不通过 `BlockPos.asLong()` 伪造方块存储。
- 生成、网络和持久化使用同一个 `AllvrCubePos` 坐标约定。
- 中央带由 chunk 管理，外围由 Cube 管理，边界不重复写入。

## 5. 修改生成参数

修改 noise settings、surface rule 或 biome 前，必须同时检查 `AllvrChunkGenerator` 的范围校验、客户端 Y 窗口、Cube 边界和现有世界兼容性。已生成的 chunk/Cube 不会因资源重载自动重写；需要新世界或明确的数据迁移。

不应在本文件记录每次实验参数。若参数成为稳定契约，写入 JSON 和本节；若只是实验，放在 issue/提交说明中。

## 6. 验证

1. 新世界分别在 `Y=-129/-128/383/384` 观察方块类型、碰撞和光照。
2. 在中央带验证原版 cave、ore、surface、feature 和 mob spawn 没有被 Cube 路径截断。
3. 在外围移动跨越多个 Cube，重启后检查种子一致性。
4. 检查结构/POI/方块实体是否只属于中央 chunk pipeline，Cube 内容是否走 [持久化说明](allay-dimension-persistence-plan.md)。
