# Allay Dimension 数据包地形生成

## 高度路由

- `-128 <= Y < 384`：正式维度高度为 512，方块由真实 `LevelChunk` 的 32 个 section 持有。复用原版 ChunkStatus、结构、噪声、地表、carver、features、光照、高度图、POI、方块实体、随机刻、计划刻、区块票据、网络包和 Anvil 保存管线。
- `Y < -128`：仍由 32³ cube 持有，新生成区域全部为深板岩，使用单值 section 调色板，不运行浮岛噪声或装饰。
- `Y >= 384`：原有浮岛 cube 生成和 `terrain` 配置保持不变。

边界与 section/cube 对齐，中间区域不创建或同步 cube，不把 chunk 方块复制到 cube。Level 方块读写在中间区域直接继续执行原版方法，区间内的碰撞保留原版缓存 chunk 快速路径。玩家视距及一格 chunk 预取余量不再接触中央区间时，原版 `skipPlayer` 票据路径移除该玩家的中央列票据，tracking view 清空；靠近中央区间时由原版重新建立，避免高空玩家强制生成几千万格下方的地形。强加载票据和其他模组显式请求 chunk 仍正常工作。

`AllvrChunkGenerator` 继承 `NoiseBasedChunkGenerator`，所有生成阶段直接复用父类，不做底层收尾或额外方块替换；底层深板岩允许被原版矿石和其他 features 正常替换。`ChunkMap` 按原版 `instanceof NoiseBasedChunkGenerator` 路径初始化 RandomState。默认 `createmanaindustry:allay` 噪声设置基于 `.refs/neoforge-21.1.227/data/minecraft/worldgen/noise_settings/overworld.json`：生成范围扩大至 `[-128, 384)`，地表规则中的 bedrock 方块换为 deepslate，其余主世界噪声、结构和群系阶段由原版执行。

### 中间区域自定义群系

维度生成器可增加独立 `chunks` 字段，使用原版噪声生成器的字段：

```json
"chunks": {
  "settings": "createmanaindustry:allay",
  "biome_source": { "type": "minecraft:fixed", "biome": "my_pack:allay_forest" }
}
```

也支持 `minecraft:multi_noise` 或已注册的自定义 biome source；不会按群系名称硬编码生成方块。省略 `chunks` 时采用主世界 multi-noise 预设。自定义 noise settings 必须为 `min_y=-128, height=512`；若需要无基岩，应在自定义地表规则中同样使用深板岩。高空 `terrain` 的默认主世界继承逻辑不受 `chunks` 配置影响。

### 兼容性与性能范围

中央区块实际运行原版实现，不再进行独立噪声采样、装饰重放或 cube 调色板复制。靠近中央区间时渲染原点固定为 0，中间非边界 section 使用 Sodium 原生 `LevelSlice.prepare` 和缓存。边界 section 组合真实 chunk 与 cube 快照；高空仍使用已有虚拟 Y 窗口。Voxy 从真实 chunk 或 cube 的对应 section 摄取。

这里的性能目标是消除替代管线的额外生成成本；512 格区块比主世界默认 384 格多 8 个 section，加上可见的外围 cube，不能仅凭复用实现断言任意场景的总耗时不高于主世界。需要同硬件、种子、视距、模组和冷/热缓存的运行基准验证。原版光照窗口与 cube 光照窗口分别维护，边界外部光源不会自动作为原版 chunk 光照源传播。第三方模组若假定所有可建造 Y 都有原版 section，超出中央区间仍受 cube 原有兼容限制。

### 已有世界

中央区域从此读取 Anvil chunk，旧 `region3d` 中中央高度的 cube 不会被加载，也不会被删除或自动迁移；旧玩家建筑仍保留在旧 cube 记录内，需要单独迁移工具才能在新中央 chunk 中出现。上下两段已持久化 cube 继续优先恢复，低空已保存区域不会强行重生为深板岩。建议用新测试世界验证生成。

以下为高空浮岛管线说明。

## 默认行为

- 水平岛间距 2816 格，主体直径约 1560–2200 格；相邻高度层水平错位，岛中心另有独立的位置扰动。
- 原版 384 格生成高度对应 640 格岛层间距。更高的数据包生成窗口会自动扩大层距。
- 岛面保留源地形的山峰、谷地、河流、岩层、洞穴、含水层和按确定性预算采样的群系装饰；岛底按不规则轮廓裁出收尖石质底部。
- 每个岛把自身海平面映射到生成配置的 `sea_level`。雪线、垂直密度梯度、地表规则及高度放置器均在源坐标执行，最后平移到实际 Y；没有把 2560 万格高处直接传给主世界高度梯度。
- 岛屿拥有稳定的水平采样偏移，不会在不同高度重复同一块主世界地形。

默认维度定义为：

```json
{
  "type": "createmanaindustry:allay",
  "generator": { "type": "createmanaindustry:allay_islands" }
}
```

省略 `terrain` 时，读取服务器实际主世界生成器的群系源和噪声设置。因此 Terralith 对 `minecraft:overworld` 群系分布、`noise_settings`、`density_function` 和 `noise` 的修改会一并生效。超平坦主世界保留其群系源，并使用注册表中的主世界噪声设置。

## 独立的数据包配置

覆盖 `data/createmanaindustry/dimension/allay_dimension.json` 即可为 Allay Dimension 单独配置地形，不必修改主世界：

```json
{
  "type": "createmanaindustry:allay",
  "generator": {
    "type": "createmanaindustry:allay_islands",
    "terrain": {
      "settings": "my_pack:sky_terrain",
      "biome_source": {
        "type": "minecraft:multi_noise",
        "biomes": [
          {
            "biome": "my_pack:highland",
            "parameters": {
              "temperature": 0.0,
              "humidity": 0.0,
              "continentalness": 0.0,
              "erosion": 0.0,
              "depth": 0.0,
              "weirdness": 0.0,
              "offset": 0.0
            }
          }
        ]
      }
    }
  }
}
```

`terrain` 使用原版 `NoiseBasedChunkGenerator` 的 `settings` 与 `biome_source` 字段，不带 `type`。`settings` 可以引用数据包自己的噪声设置；群系源也可使用 `fixed`、`multi_noise` 或其他已注册的群系源。添加一个 `worldgen/biome/*.json` 并不会自动把它分配到世界上，仍须在群系源中引用它，这与原版一致。

Terralith 2.5.8 的关键点：其 `data/minecraft/dimension/overworld.json` 使用内联 `multi_noise.biomes` 列表。只引用 `minecraft:overworld` 群系预设会漏掉该列表，因此默认路径继承的是实际生成器对象。也可以将示例包的 `generator.biome_source` 和 `generator.settings` 原样填入上述 `terrain`。

## 生成与渲染路径

1. 高空真正方块由 32³ Cube 持有；中央原版 chunk 独立生成且包含结构。
2. `AllvrTerrainSource` 使用服务器加载后的注册表与种子建立独立 `RandomState`，运行原版插值噪声、含水层、矿脉和 `SurfaceSystem`。
3. 群系 carver 按原版半径 8、种子与调用顺序执行。邻域只需群系元数据，不加载真实主世界区块。
4. 原版 `applyBiomeDecoration` 在私有 `WorldGenRegion` 上执行群系的 placed/configured features。每个源区块都执行完整特征阶段，并记录半径 1 内的跨区块修改，结果不依赖 Cube 请求顺序或缓存驱逐。所有源区块仍使用数据包群系、噪声和地表规则。
5. 完整源列缓存被近景 Cube 和远景共用，随后统一裁切岛底。缓存有数量上限，Cube/LOD 在同一维度共享缓存；不会复制真实主世界的玩家建筑。
6. Cube 的 4×4×4 section 群系调色板正常保存和同步，服务端与客户端仅在 cube 高度上路由 `getNoiseBiome`。生成的方块实体重定位，发光方块索引重建。
7. LOD 不再把岛内区域武断判为实心；噪声洞穴、采样装饰、水体和植被状态进入远景采样。逐体素群系 ID 传到 Voxy，草、树叶和水的颜色使用对应群系。LOD section 协议升为 4，客户端和服务端需要同一模组版本。

## 边界与存档

- 结构集/结构起点（村庄、要塞等）尚未接入 Cube 系统；它们与群系的 features/carvers 是不同管线。
- 任意第三方 Java feature 若绕过 `WorldGenLevel` 直接操作实际 `ServerLevel`，不属于这里验证的标准数据包放置路径。
- 岛底裁切会露出地下岩层和洞穴；边缘处的装饰也按同一轮廓裁切。远景仍受体素分辨率和现有近似照明影响。
- 数据包加载和冷缓存首次生成仍比旧的简单数学场昂贵；完整特征阶段会在空岛 Cube 上跳过全部噪声与群系工作。测试会记录耗时。
- 当前玩家所在 Cube 保持同步可用，周边 Cube 由有界后台线程池生成并在主线程安装；队列上限为 256，玩家离开区域后会取消不再需要的任务，避免快速移动时积压无限世界生成工作。
- 地形缓存保存为独立的调色板字节快照，Cube 与 LOD 后台线程不会并发写入同一个 `PalettedContainer`；确定性生成的 Cube 会标记为 clean，保存时只序列化玩家修改过的覆盖数据。
- 特征装饰固定使用完整密度，与 vanilla/CubicChunks 的完整特征密度一致。
- 已保存 Cube 优先恢复，玩家修改不会被重新生成覆盖。未保存的过程地形使用新算法，旧保存区域边缘可能出现接缝。需要完全一致的新地形时使用新测试世界。
- 与原版一样，修改世界生成注册表后应重新打开世界；普通 `/reload` 不是世界生成注册表热重载。
- 原有 Cube 随机刻、计划刻和实体模拟限制不由此次地形重构改变。

## 验证

纯 JVM 测试：

```powershell
.\gradlew.bat test --offline
```

运行原版与 Terralith 集成测试（工作目录为仓库根目录）：

```powershell
python scripts/prepare-allvr-worldgen-test.py
.\gradlew.bat runGameTestServer -I scripts/allvr-worldgen-test.init.gradle --offline --no-configuration-cache

python scripts/prepare-allvr-worldgen-test.py --terralith .refs/Terralith_1.21_v2.5.8
.\gradlew.bat runGameTestServer -PallvrTestPack=terralith -I scripts/allvr-worldgen-test.init.gradle --offline --no-configuration-cache
```

测试源码位于 `src/worldgenTest/java`，仅由该 init script 加入，不进入普通构建。测试世界和生成的测试资源均位于 `build/allvr-worldgen-test-*`。脚本只读取 Terralith 示例并在测试世界中打包，不把 Terralith 的资源嵌入发行模组。

检查项包括：真实 Terralith 群系源、装饰请求顺序稳定性、不加载真实主世界采样区块、约 2560 万格 Y 的 Cube 生成和群系调色板，中央 chunk 路由及边界方块实体写入、无基岩生成和低空深板岩。

客户端美术验收仍需进入新世界检查：森林树冠与远景交界、山峰/雪线、河流/海岸、洞口与岛底、负 Y 岛层，以及自定义群系色彩。自动生成测试不能替代这些视觉检查。
