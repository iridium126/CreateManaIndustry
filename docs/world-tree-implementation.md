# EpicRedwood3072 世界树

悦灵维度世界树现在执行 `.refs/MarkovJunior/redwood-validation/models/EpicRedwood3072.xml`，替换原来的胶囊/椭球几何树。XML 原样打包为 `src/main/resources/data/createmanaindustry/markov/epic_redwood_3072.xml`。

## 一致性定义

- 参考实现为本仓库 `.refs/MarkovJunior`，包括其中的 `source/RedwoodRefinement.cs` 和 `Map.cs` 的 `redwoodDetail` 扩展；未包含此扩展的上游版本不会生成同一细化结果。
- 模型种子为 `(int) worldSeed`，即世界种子的低 32 位按有符号整数解释。输入 -2147483648..2147483647 时与 MarkovJunior 的相同整数种子完全相同；64 位种子相同低 32 位生成同一棵树。没有额外 salt 或区块随机数。
- `System.Random`、匹配枚举、规则对称展开、`all` 冲突处理、`prl` 抽样、卷积抽样顺序均保持参考语义。
- 主体 `81×81×192` → 第一级 `162×162×768` → 第二级 `648×648×3072`。两级各消费下一次 `Random.Next()` 作为细化种子；浮点表达式保持原版单精度运算顺序。
- 两级均进行全局六邻接 flood fill，仅保留按 X 最快、Y 次之、Z 最慢排列的首个实体体素所属连通分量。不能把此步骤替换成区块内连通性判断。
- 坐标变换为 `(mx,my,mz) → (mx-324, mz+64, my-324)`，占用高度为 Minecraft Y=64..3135。Minecraft X/Z 对应模型 X/Y，没有镜像或旋转。
- “逐体素相同”指放置前的完整模型材料体素（包括空气），经下述映射得到每个树体方块。B 不写入、不清空原有地形；周围地形和高空浮岛继续由现有地形系统生成。

模型 SHA-256：`f8e0b8860cf8258b4f62b2b5ff57cd1d8eb9a57c47f6955e18af1736b10e3baf`。
参考细化源码 SHA-256：`a6096eb686be466639e07f373708c422f76671f079ceb4a7532d1f24ea3c7dc9`。

## 开发者方块映射

修改 `src/main/resources/data/createmanaindustry/markov/epic_redwood_palette.json` 后构建并重启。该文件是开发者 classpath 资源，不是运行时数据包重载配置。每个符号对应完整 BlockState，例如：

```json
"D": {"Name": "create:andesite_casing"},
"N": {"Name": "minecraft:spruce_wood", "Properties": {"axis": "y"}}
```

| 符号 | 材料 | 默认方块 |
| --- | --- | --- |
| D | 深色木质 | 深色橡木 |
| N | 树皮/普通木质 | 云杉木 |
| n | 浅色木质 | 去皮云杉木 |
| G | 普通叶片 | 云杉树叶 |
| E | 阴影叶片 | 深色橡树树叶 |
| g | 高光叶片 | 杜鹃树叶 |
| J | 藤蔓 | 红树根 |
| V | 藤蔓叶 | 橡树树叶 |
| M | 苔藓 | 苔藓块 |

全部九个材料必须配置；B 不可配置。仅允许 `minecraft` / `create` 命名空间的非空气、无流体且无方块实体的方块。缺失或非法映射直接报错。默认叶片使用 `persistent=true`，藤蔓采用无需支撑的实体方块，避免随机刻改变原版模型表面。更换方块不会改变材料体素生成及其随机数流。

## 性能与生命周期

- 先生成主体，严格按 XML 的两级放大/细化执行。最终体素以稀疏 16³ 页保存，不分配 1,289,945,088 字节的稠密画布。
- 只细化非空父体素的一格邻域；活跃父体素使用 BitSet。连通性标记借用材料字节的高位，队列为可复用的整数环形队列。裁剪后释放空页。
- 规则匹配跳过通配符条件、优先检查非空气约束，但不改变匹配发现、写入或随机数调用顺序。
- 普通 Chunk 和 32³ Cube 生成器共享同一种子的只读模型结果。单次初始化在共享实例上同步，避免并发重复生成；静态索引持弱引用，卸载世界后不会永久持有整树。
- Cube ticket 筛选只检查边界/已完成的稀疏页，不会在筛选期间触发整树计算。首次实际生成树体范围内的新区域需要完成整树，之后仅查询和写入所在 Section。
- 种子 137：最终分页 117,764,096 字节（约 112.31 MiB），包含 60,509,461 个实体体素。独立 Java 对照工具已在 `-Xmx512m` 下完成。
- 本机种子 137 优化后“生成＋三阶段全画布哈希”约 17.2 秒；种子 0/-1 约 20.1/23.4 秒。测量期间有其他构建任务，属于实测参考，不是隔离的性能基准。首次生成仍有明显成本；不承诺实时单区块延迟。

## 游戏接入和存档

普通 Chunk 在 FEATURES 阶段放置，高空 Cube 在同步/异步地形生成结束后放置。正常高度图、Section 计数和旧方块实体移除路径继续生效。生成版本升级到 3，已有 Chunk/Cube 仍以存档内方块为准，不自动重种或覆盖玩家修改；验收完整新树应使用新世界。

原来的 `WorldTreeLayout` / `WorldTreeDescriptor` 及其几何测试保留为旧实现参考，生产生成路径不再调用它们。`world-tree-design.md` 中洞穴、探索路线和平台等概念不参与 EpicRedwood 模型改写，以保持逐体素一致。

## 验证与复现

2026-09-16 验证完成：以下 5 个种子各比较全部 1,289,945,088 个体素（包括空气），与未修改的 C# 参考解释器逐字节一致。最小/最大有符号整数种子的相同结果来自原版 seeded `System.Random` 语义。

| 种子 | 最终完整材料体素流 SHA-256 |
| --- | --- |
| 137 | `43a2fb0c68919d0c142110ad73ca2e58eed532af854ccc498f2fe283477d7e69` |
| 0 | `a536284542a6b3b2b63a0838b4b878bac3fb8543262b3b9ce46ba69b1046d038` |
| -1 | `49055ca86d1b5bebe5c716a7ff7b7e9a5b216893e58413ebec4c92ac77a87b94` |
| -2147483648 | `8594edd1420675e49bc6241c20bb067657250542e0fc58f638e6f87018b817d0` |
| 2147483647 | `8594edd1420675e49bc6241c20bb067657250542e0fc58f638e6f87018b817d0` |

`build --offline` 成功，59 项单元测试全部通过；隔离 GameTest 1/1 通过。已检查 `build/libs/createmanaindustry-0.2.5.jar`：模型字节与指定参考 XML 相同，包含方块映射，未包含 GameTest 类、测试结构或测试世界预设。未进行客户端视觉验收或多人压力测试。

```powershell
# 编译原始 C# 参考源码；对每个种子逐字节比较完整画布，包含空气
./scripts/markov/validate-redwood.ps1

# 普通测试包含种子 137 的三个独立参考哈希、稀疏/稠密连通性对照、小树回归
./gradlew.bat test --offline

# 隔离游戏测试，不使用玩家存档
python scripts/prepare-allvr-worldgen-test.py
New-Item -ItemType Directory -Force build/epic-redwood-test-run/mods | Out-Null
Get-ChildItem -LiteralPath run/mods -Filter 'owo-lib-neoforge-*.jar' | Copy-Item -Destination build/epic-redwood-test-run/mods
./gradlew.bat -I scripts/world-tree-test.init.gradle runGameTestServer --offline
```

参考对照需要 .NET 9 和 JDK 21，运行游戏本身不需要 .NET、`.refs` 或外部进程。参考解释器使用稠密数组，需额外预留内存；压缩参考文件写入 `build/redwood-validation`。游戏测试覆盖 Chunk/Cube 的 383/384 接缝、32³ 同步/异步一致、树梢和负坐标、Create 方块映射、玩家编辑后的存档往返。测试世界位于 `build/epic-redwood-test-run`，重复执行会复用它；测试类和测试结构不进入正式 JAR。
