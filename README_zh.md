# 机械动力：魔法工业

[English](./README.md)

机械动力：魔法工业是一款面向 **Minecraft 1.21.1** 与 **NeoForge** 的机械动力附属模组。它增加了机械设备、魔法流体处理，以及连接动能与魔法系统的模组联动。项目还包含仍在开发中的 GPU 渲染、物流与超高维度系统。

## 特性

### 机械设备、流体与配方

- **动力注魔台**将机械动力应力转化为魔力，可用方块上的滑动框调整每 RPM 的应力消耗，为法术组构台与充能阵列中的晶结充能。
- **动力雾化器**将管道输入的支持流体转化为雾场。雾场半径随转速变化，浓度随距离衰减；自定义配方可以要求雾场，也可以排放雾作为副产物。
- **冷凝管**通过独立冷却液管路，从附近雾场中回收对应流体，并将产物流入下方的动力排液口。
- **悦灵燃烧室**以被捕获的悦灵为核心，是机械动力的加热源；可燃烧紫水晶类材料或液态媒质，支持唱片播放，并在燃烧时排放液态灵魂雾。工作盆配方可指定 `allayheated` 热需求。
- 新增液态魔力、液态媒质、液态魔源、液态灵魂、冷却液、熔融玫瑰石英与熔融海晶石英。流体可由机械动力管道和储罐运输；可选魔法模组会提供对应的资源桥接。
- 新增加热冲压、雾化冲压/搅拌、汽化与汽相沉积配方，涵盖紫水晶制媒质、紫水晶和玫瑰石英镀层板等流程。
- 内置的加热搅拌配方会将 250 mB 液态媒质转为 125 mB 液态魔力；加热雾化搅拌配方会将 250 mB 液态魔力转为液态媒质雾。冰可通过冲压制成冷却液。
- 新增可连接成组的熔盐燃料罐，以及燃料棒多方块结构。

### 咒法学（Hexcasting）

安装咒法学后，可用机械动力自动生产杂件（Cypher）、缀品（Trinket）、造物（Artifact）与媒质之瓶：机械手写入图案，注液器注入液态媒质，动力冲压机完成定型并保留物品数据。媒质之瓶也可通过机械动力流体网络填充和抽取。

其他联动包括：为非卓越法术动态添加石板切石配方；读取置于置物台或置物板上物品中的 Iota；点燃烈焰人和悦灵燃烧室；以及查询法术环内玩家等功能。服务端还提供可配置的 Hex JIT，对频繁执行的调用点进行编译；默认模式为 `AUTO`，可在服务端配置中调整。

游戏内指南以帕秋莉手册提供，包含英文与简体中文文本。

### Trickster、Bits 'n' Bobs 与 Ars Nouveau

- Trickster 联动增加晶结装配与灌注、机械臂对晶结和法术构装台的操作、显示链接器目标、临时动能应力戏法，以及媒质/魔力转换戏法。
- **动力法术核心**可将机械动力与 Create: Bits 'n' Bobs 的齿轮链应力提供给 Trickster 法术核心。
- Ars Nouveau 联动提供液态魔源和源质罐流体处理。魔源浆果相关配方仅在安装 Ars Nouveau 时生效。

### 实验性系统

以下系统已包含在代码中，但尚未视为完成全面游戏内验证。[开发文档索引](./docs/README.md)列出了实现说明与已知缺口。

- **ALLVR 悦灵维度**新增自定义维度：标准高度范围内使用常规地形，范围之外使用 Cube 存储。维度渲染、方块实体同步和部分原版 tick 行为仍在开发中；客户端必须安装 Sodium。
- **悦灵风暴**是由服务端管理状态、客户端 GPU 驱动的群体遭遇，平衡与行为仍需实战验证。管理员可用以下命令控制（需要 2 级权限）：
  - `/cmi particle allaystorm [count]` 开启。
  - `/cmi particle allaystorm stop` 停止。
- **GPU 粒子引擎**用于高数量级粒子、模型粒子与咒法学特效。客户端配置提供帧耗时预算和自动限流；支持着色包集成，具体兼容性取决于渲染器版本。
- **GPU 机械动力包裹模拟**是可选实验功能，默认关闭，且需要客户端与服务端使用匹配设置。

## 依赖

需要 Java 21。

| 依赖 | 要求 |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.236 或更高版本 |
| 机械动力（Create） | 6.0.10 或更高版本 |
| Sodium | 每个客户端均需 0.8.13；专用服务器不需要 |

以下模组均为可选依赖；安装后会启用对应联动：

| 模组 | 最低版本 | 联动内容 |
|---|---:|---|
| [Trickster](https://modrinth.com/mod/trickster) | 2.0.0-beta.56 | 晶结、法术、构装台与动能链接 |
| [Create: Bits 'n' Bobs](https://modrinth.com/mod/create-bits-n-bobs) | 2.1.9-beta | 动力法术核心的齿轮链数据 |
| [Hexcasting](https://modrinth.com/mod/hexcasting) | 0.12.0-devel-pre-64 | 施法物品自动化、媒质存储与自定义动作 |
| [Veil](https://modrinth.com/mod/veil) | 4.1.4 | 体积雾及相关渲染效果 |
| [Iris Veil Compat](https://github.com/leon-o/iris-veil-compat) | 0.4.0-alpha | 雾效与燃料棒辉光的光影包适配 |
| [Ars Nouveau](https://modrinth.com/mod/ars-nouveau) | 5.13.0 | 液态魔源与源质罐处理 |

## 配置

NeoForge 会在配置目录中生成 `createmanaindustry-server.toml` 与 `createmanaindustry-client.toml`。玩法与平衡参数位于服务端配置，渲染和粒子选项位于客户端配置。每个配置项都带有说明注释。

常用服务端配置：

| 配置项 | 默认值 | 用途 |
|---|---:|---|
| `fluid.manaPerBucket` | 2048 | 一桶液态魔力所代表的魔力值 |
| `fluid.mediaPerBucket` | 400000 | 一桶液态媒质所代表的媒质值 |
| `kinetics.manaPerStress` | 0.001 | 每 tick 每单位应力产生的魔力 |
| `mist.mistMaxRadius` | 16 | 雾化器的最大雾场半径（格） |
| `mist.mistFluidPerTick` | 8 | 256 RPM 时雾化器的基础流体消耗（mB/tick） |
| `mist.condenseEfficiency` | 5.0 | 每浓度单位每 tick 的冷凝基础产量（mB） |
| `fuel_tank.fuelTankCapacity` | 8 | 每个燃料罐方块的容量（桶） |
| `hexcasting.jit.mode` | `AUTO` | Hex JIT 模式：`OFF`、`PROFILE` 或 `AUTO` |

服务端配置还包括悦灵燃烧室燃料、燃料棒结构限制、Hexcasting 物品容量和悦灵风暴行为。客户端配置控制雾场辉光、GPU 粒子上限与限流、咒法学粒子重定向和 ALLVR LOD。GPU 包裹权威模拟在客户端和服务端均默认关闭。

## 构建

使用 Java 21，执行：

```bash
./gradlew build
```

Windows 下执行 `.\gradlew.bat build`。可安装的模组文件生成于 `build/libs/createmanaindustry-0.2.6.jar`。开发运行使用的 bootstrap 专用 jar 位于 `build/internal/`，不需要作为独立文件安装。

安装 Connector 时，内置的早期启动服务会识别并修补经过验证的 Trickster `2.0.0-beta.56`，确保在 Connector 扫描 Fabric 模组前完成处理。服务会校验目标类哈希，将原文件以 `.jar.backup` 后缀保存在旁边；不认识或已修改过的 jar 会保持原样。在开发运行中，Gradle 会在启动前把 Trickster 依赖复制到 `run/mods/`，供同一服务处理。

## 开发文档

开发文档索引、实现状态和已知缺口见 [docs/README.md](./docs/README.md)，内容涵盖 GPU 粒子与包裹、ALLVR、悦灵风暴和着色包集成。

## 许可证

MIT License
