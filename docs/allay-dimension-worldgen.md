# Allay Dimension 数据包地形生成

本实现替代旧的圆角盒密度场。世界仍由遍布三维空间的大型浮空岛组成；岛上地形来自 Minecraft 的噪声生成器，而不是按群系名称硬编码的方块表。

## 默认行为

- 水平岛间距 2816 格，主体直径约 1560–2200 格；相邻高度层水平错位，岛中心另有独立的位置扰动。
- 原版 384 格生成高度对应 640 格岛层间距。更高的数据包生成窗口会自动扩大层距。
- 岛面保留源地形的山峰、谷地、河流、岩层、洞穴、含水层和群系装饰；岛底按不规则轮廓裁出收尖石质底部。
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

1. `AllvrChunkGenerator` 只生成空的原版列壳，关闭列壳结构；真正方块仍由 32³ Cube 持有。
2. `AllvrTerrainSource` 使用服务器加载后的注册表与种子建立独立 `RandomState`，运行原版插值噪声、含水层、矿脉和 `SurfaceSystem`。
3. 群系 carver 按原版半径 8、种子与调用顺序执行。邻域只需群系元数据，不加载真实主世界区块。
4. 原版 `applyBiomeDecoration` 在私有 `WorldGenRegion` 上执行群系的 placed/configured features。每个生成起点独立记录半径 1 内的修改，再按固定全局顺序合并，跨区块结果不依赖 Cube 请求顺序或缓存驱逐。相邻起点的装饰互相独立，这是与主世界原生邻域流水线的差异。
5. 完整源列缓存被近景 Cube 和远景共用，随后统一裁切岛底。缓存有数量上限，Cube/LOD 在同一维度共享缓存；不会复制真实主世界的玩家建筑。
6. Cube 的 4×4×4 section 群系调色板正常保存和同步，服务端与客户端 `getNoiseBiome` 绕过空列壳。生成的方块实体重定位，发光方块索引重建。
7. LOD 不再把岛内区域武断判为实心；噪声洞穴、完整装饰、水体和植被状态进入远景采样。逐体素群系 ID 传到 Voxy，草、树叶和水的颜色使用对应群系。LOD section 协议升为 4，客户端和服务端需要同一模组版本。

## 边界与存档

- 结构集/结构起点（村庄、要塞等）尚未接入 Cube 系统；它们与群系的 features/carvers 是不同管线。
- 任意第三方 Java feature 若绕过 `WorldGenLevel` 直接操作实际 `ServerLevel`，不属于这里验证的标准数据包放置路径。
- 岛底裁切会露出地下岩层和洞穴；边缘处的装饰也按同一轮廓裁切。远景仍受体素分辨率和现有近似照明影响。
- 数据包加载和冷缓存首次生成比旧的简单数学场昂贵。测试会记录耗时；这里没有声称已经完成客户端帧率或首次进入延迟验收。
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

检查项包括：真实 Terralith 群系源、装饰请求顺序稳定性、不加载真实主世界采样区块、约 2560 万格 Y 的 Cube 生成和群系调色板，以及包含水体和多群系的 LOD 数据往返。

客户端美术验收仍需进入新世界检查：森林树冠与远景交界、山峰/雪线、河流/海岸、洞口与岛底、负 Y 岛层，以及自定义群系色彩。自动生成测试不能替代这些视觉检查。
