# MarkovJunior 世界生成

`crystal_bloom_plains` 的 `minecraft:trees_plains` 已替换为
`createmanaindustry:natural_small_tree`，保留原版平原树的频率和 placement filters。
本次兼容范围按确认覆盖 NaturalSmallTree 的全部执行语义，并提供额外节点的编译扩展接口。

## 开发者配置

修改 `src/main/resources/data/createmanaindustry/worldgen/configured_feature/natural_small_tree.json`
的 `config.palette`，或通过数据包覆盖相同资源位置。每个字符映射一个完整 Minecraft `BlockState`：

```json
"palette": {
  "N": { "Name": "minecraft:birch_log", "Properties": { "axis": "y" } },
  "D": { "Name": "minecraft:birch_wood", "Properties": { "axis": "y" } },
  "G": { "Name": "minecraft:birch_leaves", "Properties": { "persistent": "false" } },
  "E": { "Name": "minecraft:azalea_leaves", "Properties": { "persistent": "false" } },
  "g": { "Name": "minecraft:flowering_azalea_leaves", "Properties": { "persistent": "false" } }
}
```

- `N/D` 是两种木质体素；`G/E/g` 是普通、阴影和高光叶片体素。上面的配置只是示例；当前内置树配置使用晶莹木和两种本模组叶片。Minecraft 的默认树叶着色不复刻原版体素调色板的三种明暗。
- 模组方块也可以使用，注册 ID 和属性必须有效。
- `crystal_log` 系列以及 `aventurine_edified_leaves_1/2` 只在安装 Hexcasting 时注册；默认树配置使用这两种本模组叶片。叶片无对应物品，剪刀或精准采集会掉落 Hexcasting 的 `hexcasting:aventurine_edified_leaves`。
- 依赖这组方块的 `crystal_bloom_plains` 生物群系、Allay 维度和树特征也带有相同条件；未安装 Hexcasting 时不会加载这些引用。
- 第一个值 `B` 是空体素，不配置映射，也不会清除地形。临时符号不需要映射；执行结束仍有未映射字符会报错。
- 非空体素必须映射为非空气、非流体方块。
- 带 `distance` 属性的树叶会根据 `minecraft:logs` 标签中的实际映射方块重新计算支撑距离。自定义木质方块应加入该标签；也可把装饰叶片设为 `persistent=true`。
- 这是世界生成注册表配置，重启服务端或重新打开世界后生效；已有区块不会自动重种。

其他字段：`model` 为模型资源 ID；`width/depth/height` 默认为 `19/19/18`；
`max_steps` 是执行轮次保护预算，默认为 1000，超出会报错且不放置部分结果。
当前树模型需要至少 `19×19×18`，推荐保持默认尺寸。适配器尺寸上限为 `31×31×64`。

XML 原样打包在 `src/main/resources/data/createmanaindustry/markov/natural_small_tree.xml`。
`model` 从模组 classpath 的 `/data/<namespace>/markov/<path>.xml` 读取并按 ID 和尺寸缓存编译结果。
XML 是开发者打包资源，**不由数据包资源重载器替换**；修改 XML 后需重新构建并重启。
游戏运行不需要 `.refs`、.NET、外部进程或预生成树模板。

## 执行与放置

- 原版 XML 空格分层从上到下；执行器内部 Z 向上。
- 映射到世界时 `(Markov X, Y, Z) → (Minecraft X, Z, Y)`，水平中心对齐放置点。
- 原版 seeded `System.Random` 序列、匹配枚举顺序、`all` 洗牌及冲突消解、`prl` 同步写入、概率抽样顺序均保留。同一 32 位种子生成相同体素。
- 每次世界生成从 Minecraft 的 `RandomSource.nextInt()` 取得模型种子，编译结果可跨线程共享；状态、随机数和匹配缓存属于单次执行。
- 保留 `one/all` 的增量匹配。规则提前编译为扁平数组和偏移；VonNeumann 卷积只累积所需材料的六邻接计数，省去原版每格完整调色板计数表。
- 先检查全部非空体素的写入范围、流体和可替换方块，再放置。树干、树冠遇到障碍或高度越界时整棵跳过，不裁切树形。
- 支持正常树叶衰减，生成时计算有效支撑距离。土壤检查和草地转换在适配器中完成。

## 兼容边界与扩展

已支持嵌套 `sequence`、`one`、`all`、`prl`、非周期 `VonNeumann convolution`，
包括多规则、通配符、概率和步骤限制。当前 profile 要求根节点明确声明 `symmetry="()"`。
未实现 WFC、Map、Path、搜索/field/observe、union、其他对称群和外部图案文件。
未支持的节点或属性会明确报错，避免静默执行出不同结果。

`MarkovModel.load(xml, x, y, z, Map<String, NodeCompiler>)` 接受额外节点编译器。
编译器获得 DOM 元素及含网格尺寸、字符查询、子节点编译方法的 `Compiler`；
返回不可变 `Operation`。运行时通过 `Execution` 访问本次体素、随机数与 `step()` 预算。
扩展操作应把可变数据保存在本次执行中，以保证跨线程使用安全。
世界生成配置的 classpath 加载路径当前使用内置 profile；要接入自定义节点，需要在
`MarkovTreeConfiguration.compiledModel()` 的加载调用中传入对应编译器集合。

## 验证与复现

2026-09-15 本机验证结果（Windows 10，Java 21.0.8，.NET 9.0.14）：

| 检查 | 结果 |
| --- | --- |
| 原版逐格对照 | 261 个不同种子全部一致，树形约束全部通过 |
| Java 回归测试 | 4 项通过 |
| 隔离 GameTest | 1 项综合集成测试通过 |
| 原版 Release 执行中位数 | 2.7710 ms / 棵 |
| Java 执行中位数 | 1.6870 ms / 棵 |
| 吞吐比（原版耗时 / Java 耗时） | 1.64× |
| 正式 JAR | 模型、配置、许可均已打包；无测试类或测试结构 |

纯 Java 回归测试（不依赖参考仓库）：

```powershell
./gradlew.bat test --tests '*MarkovModelTest' --offline
```

覆盖独立原版结果的 SHA256、正负及边界种子、多线程确定性、预算保护、语法拒绝、节点扩展。

原版逐格对照与性能门槛（需要 `.refs/MarkovJunior`、其 `bin/SixLabors.ImageSharp.dll`、.NET 9 SDK 和 JDK 21）：

```powershell
./scripts/markov/validate.ps1
```

脚本直接编译参考仓库的原始 C# 源码，开启 Release 优化，不修改参考源码。
261 个不同种子做逐格对比，并检查树高 6–15、落地、六邻接连通、无临时符号、无边界裁切、底部三格无叶。
随后两端分别预热 1000 次、执行 5 轮每轮 1000 次，以每棵树耗时的中位数比较。
计时不含 XML 加载、图像/VOX 导出或磁盘读写，Java 耗时高于原版时脚本失败。
C# 侧通过反射调用原始 Interpreter；包含很小的反射调用开销，保留其跨树内部缓存。
Java 侧每次执行分配独立状态，不缓存种子对应的结果。
输出位于 `build/markov-validation/`。
性能结论针对当前模型、网格和测量环境；不代表尚未移植节点或不同硬件的普遍保证。

隔离的游戏集成测试：

```powershell
python scripts/markov/prepare-gametest.py
./gradlew.bat -I scripts/markov/gametest.init.gradle runGameTestServer --offline
```

覆盖真实注册表/配置解码、逐体素方块与坐标映射、叶片距离、替换为桦木、石头阻挡后的完整回滚预检、
水中拒绝和高度越界拒绝。测试存档只使用 `build/markov-test-run`，测试类不进入正常构建。

参考版本 `42aaf24bcf54ae164fba49c0a59348297904a676`；模型 SHA256：
`2105d7aa16fc830e90b149419b1250f4577d57421dff632e6cd295853f15392e`。
原版 MIT 许可随 JAR 打包在 `META-INF/licenses/MarkovJunior.txt`。
