# 悦灵原点：立体壁道、吊桥与巨根

## 地形与兼容性

保留此前确认的地形尺度：半径90格的平台地表方块为Y=95，与世界树第一层Y=96接合；500格内缓坡，500～700格恢复原地形。环谷基准内/外半径100/200格，最低基底Y=-5。岩壁仍采用多尺度溶蚀、岩层、纵向沟槽与崖脚堆积。

保持现有地表策略：环谷内外岸的Y=95地表不额外铺设专用花草/小树；原点200格范围的表面岩浆排除逻辑不变。壁道、根系和洞内的装饰不受该地表限制。

仅新生成区块使用新模型，不重写玩家存档。原点通常已经生成，完整检查请使用新世界。中央带外Cube逻辑不变。

## 三维交通网络

参考《通道和吊桥设计.md》：外围入口→分层壁道→悬崖平台/吊桥→根系内部和表面→下层壁道。旧的同高放射石桥与贯底石柱已移除。

- 十处外围缓降入口接入上层壁道。
- 内外壁各三层廊道，可行走顶面基准约Y=17、46、75，随角度缓慢起伏。廊道在岩层内外穿行，包含开放栈道和短隧道。
- 八条层间坡道先离开廊道，再嵌入岩壁升降；使用独立的回折根系通道避免头顶冲突。
- **十座吊桥**：六座跨谷主桥、四座根间桥。主桥约100格跨度，桥面约5～7格宽，下垂7格；根间桥随根的弯曲位置变化，桥面约3～4格宽，下垂4格。
- 木质桥塔、主索、扶索、吊杆及少量发光节点分开构造。跨谷桥塔由木斜撑和背索锚入岩壁；根间桥锚入粗根。保留悬索桥的荷载传递外观，不做有限元结构计算。
- 路面使用完整方块与下半砖形成高差，主通行区域预留净空。分段曲线按空间距离自适应采样，避免急弯处出现体素断口。
- 两条支路进入岩壁中的原版紫晶洞穴；额外平台用于眺望。

## 曲折根系

参考 `.refs/MarkovJunior/tree-validation/models/TallRainforestTree.xml` 的阶段：不规则基部扩张、偏移生长轴、向外的骨架芽点、渐细分枝及细端的面连接。没有直接把整棵雨林树旋转进峡谷。

十条主根从半径约81格、Y=34的内壁地下出发，总体向半径222格、Y=-1的外壁地下生长，位于环谷下半部。主根轴叠加不同相位的低频弯折和垂向变化，截面纵向压扁，逐渐收尖，表面带随弯曲延伸的树皮脊沟及少量苔藓。

每条主根另有四处分叉侧根和四条细根：侧根沿主根生长方向分出，再弯向谷底或外壁；细根末端进入土岩，采用数字折角维持面连接。依根路径和根间吊桥读取同一根轴，因此根形改变时锚点随之移动。

## MarkovJunior 模型与几何适配

`src/main/resources/data/createmanaindustry/markov/karst_causeways.xml` 使用 **7×60×7** 三维网格，坐标依次为径向、周向、竖向。

借鉴 `.refs/MarkovJunior/models/Apartemazements.xml` 与 `resources/tilesets/Paths.xml` 的Line、Up/Down、交汇节点和后续结构细化思路。当前使用项目已经支持的原版 `all`、`one`、`prl` 重写节点，**没有移植或声称执行其WFC节点**。

模型生成三层内外壁连接格网，分别选取八个坡道模块、六个主桥模块、四个根间桥模块、两个紫晶洞支路及眺望平台。Java `SanctuaryNetwork` 将模型输出映射为曲线、半砖路面、悬索和支撑；用稀疏16³体素页保存结果。植被、原版洞穴之后均尊重路径净空。模型和几何场按世界种子确定，不使用区块遍历顺序作为随机输入。

## 原版紫晶洞

使用注册表中的 `minecraft:amethyst_geode` configured feature，并确认其生成器是原版 `Feature.GEODE`。保留原版紫晶、紫晶母岩、晶簇、方解石与平滑玄武岩配置，不自行模拟球壳。

原版特征在受限的私有WorldGenRegion中运行，输入为同种子的环谷岩体。生成写入被保存成只读映射，各区块仅应用属于自身的部分，避免相邻区块后续整形截断洞穴。之后再应用入口通道；入口切掉母块时清除失去支撑的晶芽。私有区域不会请求或修改玩家世界的邻近区块。

## 开发者材质配置

文件：`src/main/resources/data/createmanaindustry/markov/sanctuary_palette.json`。

| 配置项 | 用途 |
| --- | --- |
| `path` / `path_slab` | 壁道、坡道、根系路径的完整地砖/半砖 |
| `bridge_deck` / `bridge_slab` | 吊桥木板与半砖踏步 |
| `timber` | 桥塔和木斜撑 |
| `rope` / `light` | 主索、扶索、吊杆 / 发光标记 |
| `support` | 壁道悬臂承托 |
| `root_bark` / `root_core` / `root_moss` | 主根与分根树皮、内部木质、表面苔藓 |
| `wall_base` / `wall_light` / `wall_dark` | 喀斯特岩壁和谷底的主材、浅色层、深色层 |

每项是完整Minecraft BlockState，支持模组命名空间和方块属性，例如：

```json
"root_bark": { "Name": "minecraft:dark_oak_wood", "Properties": { "axis": "y" } }
```

所有项必须存在，未知键或无法解码的方块会明确报错。禁止空气、流体及方块实体；结构材质要求完整碰撞立方体，两个`*_slab`项必须是下半砖。绳索和发光节点允许非完整立方体。

与现有世界树调色板一致，这是开发者打包的classpath资源，修改后重新构建并重启生效，不是运行时数据包重载。紫晶洞内部材料保留原版配置，不通过岩壁调色板替换。

## 验证

```powershell
./scripts/markov/validate-causeways.ps1
./gradlew.bat test --tests '*AllvrSanctuaryTest' --offline

python scripts/prepare-allvr-worldgen-test.py
New-Item -ItemType Directory -Force build/sanctuary-test-run/mods | Out-Null
Get-ChildItem -LiteralPath run/mods -Filter 'owo-lib-neoforge-*.jar' | Copy-Item -Destination build/sanctuary-test-run/mods
./gradlew.bat -I scripts/sanctuary-test.init.gradle runGameTestServer --offline

./gradlew.bat build --offline
```

原版C#与Java对比种子0、1、42、137、2026、-1及32位整数上下界。单元测试检查平台与过渡、三维模块数量、吊桥下垂、坡度、根的方向与下半区范围，以及多个种子的实际路面方块四邻接可达性和净空。

两个隔离GameTest覆盖真实原版紫晶/母岩/晶簇、跨区块反向生成一致性、桥索与根材质、路径净空、跨命名空间材质替换及非法配置拒绝。

`build/causeway-validation/network.png`、`cutaway.png`、`top.png` 从实际结构体素与地形场导出。网络图刻意隐藏岩体以显示埋藏通道；这些图不含植被、世界树主干、原版紫晶洞和游戏光照，不能替代客户端观感验收。

### 本次实测

2026-09-17：原版MarkovJunior与Java的8个种子逐格一致；最终两个GameTest通过，共比较1,332,992个方块。采样区块中含865个紫晶块、69个紫晶母岩、8个晶芽/晶簇、8,299个根材质方块及811个索链方块。这是验证样本计数，不是整个环谷的材料总量。
