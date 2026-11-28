# 机械动力：魔法工业

[English](./README.md)

机械动力：魔法工业是一款面向 **Minecraft 1.21.1** 与 **NeoForge** 的机械动力附属模组。它加入了把旋转动力转化为魔力与媒质的机械设备、魔法流体与体积雾场，以及连接机械动力、Trickster、咒法学与 Ars Nouveau 的资源联动。项目还包含实验性的 GPU 渲染与超分/补帧、GPU 驱动的机械动力包裹模拟、悦灵维度和悦灵风暴。

## 特性

### 机械设备、流体与配方

- **动力注魔台**消耗旋转应力；安装 Trickster 后，可为相邻的充能阵列或法术组构台中的晶结充能。滑动框可把每 RPM 应力设为 4–256（默认 4）；每 tick 充入的魔力为 `应力 × 转速 × manaPerStress`。
- **魔力齿轮**由雾场驱动：液态魔力雾使其以 256 RPM 旋转，液态媒质雾则以 −256 RPM 反向旋转；应力容量为 8 × 转速绝对值 SU，离开雾场后停止输出动力。
- **动力雾化器**把管道输入的非熔融流体转化为体积雾场。雾场半径随转速变化，浓度随距离衰减。浓度高于 10% 的水雾可滋润耕地；熔融流体的雾会点燃并灼伤生物。
- **冷凝管**沿轴线通入冷却液，按 1:1 消耗冷却液并从附近雾场回收对应流体，产物流入下方的动力排液口。另有铜质风化与涂蜡变种。
- **悦灵燃烧室**以被捕获的悦灵为核心，是机械动力的加热源；可燃烧紫水晶碎片、咒法学的紫水晶粉或充能紫水晶，以及液态媒质。它支持唱片播放，燃烧时排放液态灵魂雾，并提供 `allayheated` 热需求。
- 新增液态魔力、液态媒质、液态魔源、液态灵魂、冷却液与熔融玫瑰石英。
- 新增 `heated_compacting`、`mist_mixing`、`vaporizing` 与 `vapor_deposition` 配方类型：紫水晶材料可加热冲压为液态媒质；加热搅拌把 250 mB 液态媒质转为 125 mB 液态魔力；超级加热雾化搅拌把 250 mB 液态魔力转为液态媒质雾；玻璃在液态媒质雾与 `allayheated` 热量下制成紫水晶块；冰可冲压为冷却液。
- 在工作盆上方放置**边框玻璃活板门**后会转化为**气相沉积盖**；关闭活板门即可封闭工作盆。封闭的工作盆可执行 `vapor_deposition` 配方：紫水晶雾或玫瑰石英雾可在铁、铜、金、黄铜板上形成镀层；安装咒法学后，玻璃还可制成探知透镜。
- Create 的 **Ponder（思索）**场景涵盖悦灵燃烧室、雾场、魔力齿轮、气相沉积，以及 Trickster 与咒法学生产线。

### 咒法学（Hexcasting）

安装咒法学后，可用机械动力自动生产杂件、缀品、造物与媒质之瓶：机械手把载有 Iota 的物品上的图案复制到未完成施法物品上；注液器注入液态媒质；动力冲压机完成定型，并保留图案、Iota、媒质与染料。媒质之瓶由制作试剂瓶卷轴与玻璃瓶制成，可通过机械动力流体网络注入或抽出液态媒质；注液器可用液态灵魂填充其 Anima 储量。

其他联动包括：为非卓越法术动态添加石板切石配方；读取置于置物台或置物板上物品中的 Iota；点燃烈焰人和悦灵燃烧室；以及查询法术环内玩家。

游戏内指南包含扩展 Trickster 指南书的 Lavender 分类，以及咒法学帕秋莉手册中的“魔法工业”分类，均提供英文与简体中文文本。

### Trickster、Bits 'n' Bobs 与 Ars Nouveau

- Trickster 联动增加晶结自动生产与灌注、机械臂对晶结和组构台的操作、显示链接器目标，以及临时动能应力戏法。机械手可用玻璃与材料生产晶结；较高阶晶结会先形成未完成品，由注液器注入液态魔力后自动完成；完成后的晶结无需冲压，冲压机可另行制作裂纹变种。
- 安装咒法学后，**卓越戏法**提供六边形点阵编辑器与 Anima 费用：灵质反转交换施法距离以及媒质/魔力费用，同命传递事故，酊剂赋形施加储存的药水效果，分域则以立方边界分隔空间。
- **Anima** 是卓越戏法的灵魂费用。注液器可用液态灵魂填充咒法学媒质之瓶的 Anima 储量（默认每桶 32 Anima）；液态灵魂由燃烧中的悦灵燃烧室排放的灵魂雾经冷却液冷凝获得。
- 通过 Trickster 戏法读取的**药水**片段会完整保留药水效果的类型、剩余时长、等级与标志，供后续使用。
- **动力法术核心**是由 Create: Bits 'n' Bobs 齿轮链驱动的 Trickster 法术核心。链速决定执行预算，每个核心为链控制器增加 4 × 转速绝对值 SU 的负载。
- Ars Nouveau 联动提供液态魔源和源质罐流体处理。魔源浆果相关配方仅在安装 Ars Nouveau 时生效。

### 实验性系统

以下系统正在开发。

- **悦灵风暴**是由服务端管理状态、客户端 GPU 驱动的群体BOSS，包含星柱、魔力束、螺旋与穹顶攻击。生成成员达到 16,384 时，主世界的风暴可在建筑高度上限之上开启天空裂口，把符合条件的玩家传送至悦灵维度。管理员可用 `/cmi particle allaystorm [count]`、`/cmi particle allaystorm status` 与 `/cmi particle allaystorm stop` 测试悦灵风暴。Windows 上的原生 RT 后端使用 CUDA/OptiX，失败时回退 OpenGL。
- **GPU 粒子引擎**用于高数量级粒子、模型粒子、常驻方块发射器与咒法学特效。客户端配置提供容量、帧耗时预算和自动限流；支持光影包集成，具体兼容性取决于渲染器版本。
- **Windows x64 超分辨率与帧生成**：支持的 RTX 显卡可使用 NVIDIA DLSS 超分辨率，支持的 Arc 显卡可使用 Intel XeSS 帧生成，默认关闭。

## 依赖

需要 Java 21。

| 依赖 | 要求 |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.236 或更高版本 |
| 机械动力（Create） | 6.0.10 或更高版本 |
| Sodium |  0.8.13 或更高版本 |

以下模组均为可选依赖；安装后会启用对应联动：

| 模组 | 最低版本 | 联动内容 |
|---|---:|---|
| [Hexcasting](https://modrinth.com/mod/hexcasting) | 0.12.0-devel-pre-64 | 施法物品自动化、媒质存储与自定义图案 |
| [Trickster](https://modrinth.com/mod/trickster) | 2.0.0-beta.56 | 晶结、法术、组构台与动能链接 |
| [Veil](https://modrinth.com/mod/veil) | 4.1.4 | 体积雾 |
| [Iris Veil Compat](https://github.com/leon-o/iris-veil-compat) | 0.4.0 | 雾效的光影包适配 |
| [Ars Nouveau](https://modrinth.com/mod/ars-nouveau) | 5.13.0 | 液态魔源 |
| [Create: Bits 'n' Bobs](https://modrinth.com/mod/create-bits-n-bobs) | 2.1.9-beta | 动力法术核心 |

## 配置

NeoForge 会在配置目录中生成 `createmanaindustry-server.toml` 与 `createmanaindustry-client.toml`。玩法与平衡参数位于服务端配置，渲染和粒子选项位于客户端配置。每个配置项都带有说明注释。

常用服务端配置：

| 配置项 | 默认值 | 用途 |
|---|---:|---|
| `fluid.manaPerBucket` | 2048 | 一桶液态魔力所代表的魔力值 |
| `fluid.mediaPerBucket` | 400000 | 一桶液态媒质所代表的媒质值 |
| `fluid.animaPerBucket` | 32 | 一桶液态灵魂所代表的 Anima |
| `kinetics.manaPerStress` | 0.001 | 每 tick 每单位应力产生的魔力 |
| `mist.mistMaxRadius` | 16 | 雾化器的最大雾场半径（格） |
| `mist.mistFluidPerTick` | 8 | 256 RPM 时雾化器的基础流体消耗（mB/tick） |
| `mist.condenseEfficiency` | 5.0 | 每浓度单位每 tick 的冷凝基础产量（mB） |

服务端配置还包括悦灵燃烧室燃料消耗与雾场、咒法学物品容量、电池 Anima 容量、悦灵风暴规模与攻击，以及 Hex JIT。客户端配置控制雾场辉光与阴影调试、GPU 粒子容量/预算/限流、悦灵风暴模拟后端与 LOD、DLSS 与 XeSS-FG、咒法学粒子与图案重定向、卓越戏法点阵吸附，以及包裹预算。

## 构建

使用 Java 21。首次 `build` 会在 Hexal 与 Voxy 缓存缺失时自动准备固定版本的依赖，缓存齐备时跳过该步骤。

```bash
bash ./gradlew build
```

Windows 下使用 `.\gradlew.bat`。

为了在 Neoforge 上加载 Trickster 模组，内置的早期启动服务会在 Connector 扫描 Fabric 模组前校验 Trickster 目标类的哈希。匹配后应用补丁，并将原文件以 `.jar.backup` 后缀保存在旁边。

## 致谢

- **xm1221**：编写部分咒法学手册
- **YukkuriC**：HexParse 方便测试法术
- **拟故喵喵**：提出部分美术设计
- **七七**：提供复杂测试环境
- **cang_hai_yi_su**：绘制媒质原电池纹理
- **C8N16O32**：提供测试法术

## 许可证

MIT License
