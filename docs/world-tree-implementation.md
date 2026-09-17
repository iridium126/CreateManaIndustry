# EpicRedwood3072 世界树：镂空针叶与古藤

悦灵世界树使用 `src/main/resources/data/createmanaindustry/markov/epic_redwood_3072.xml`。
宏观生成规则来自 `.refs/MarkovJunior/redwood-validation`，本次保留 map 之前的结构程序、随机数调用和两级比例，修改 map 材料及细化器。
它不再与旧版实心叶团的最终体素相同；独立参考实现是 MarkovJunior 解释器加 `scripts/markov/reference` 中的新版细化扩展。

## 外形与生成

- 模型种子仍是 `(int) worldSeed`，没有新增 salt 或区块随机数。
- 主体 `81×81×192` → `162×162×768` → `648×648×3072`。原来的主干、枝层、冠形包络与垂藤位置不重新生成。
- 坐标仍是 `(mx,my,mz) → (mx-324,mz+64,my-324)`，高度为 Minecraft Y=64..3135。
- 第一级重建圆整的木质、树冠和藤蔓密度包络；第二级在包络内生成细枝、成对斜生且末端渐尖的针叶。叶簇按世界坐标与种子改变朝向和高度，簇间与针叶间留空，不再填满叶团。
- 藤蔓由双股扭曲木质藤、交替侧生的下垂尖叶及少量花节点组成。藤叶和木质藤分开着色，花节点使用开花杜鹃叶，避免大片饱和色。
- 树梢包络内保留连续细木质主轴，根部接地与顶端占用高度保留。树干继续使用原来的纵向纹理、沟槽和苔藓。
- 两级均不再调用全树六邻接连通性裁剪，避免删除斜接针叶和细藤。细节体素不保证全部六邻接连通；原有粗结构保持不变。
- 稀疏 16³ 分页、同种子共享只读模型、同步/异步 Chunk/Cube 放置逻辑不变。B 不写入，也不清空地形。

## 方块映射

开发者配置：`src/main/resources/data/createmanaindustry/markov/epic_redwood_palette.json`。
全部 14 个实体材料必须配置，B 不可配置。只允许 `minecraft` / `create` 命名空间的非空气、无流体、无方块实体的完整 BlockState。

| 符号 | 用途 | 默认方块 |
| --- | --- | --- |
| D / N / n | 深色树皮 / 树皮 / 浅色木纹 | 深色橡木 / 云杉木 / 去皮云杉木 |
| G / E / g | 普通 / 阴影 / 高光针叶 | 云杉叶 / 深色橡树叶 / 杜鹃叶 |
| H | 针叶末端 | 白桦树叶 |
| t | 针叶细枝、顶梢主轴 | 横向云杉木 |
| J / K | 古藤表皮 / 藤芯 | 红树根 / 去皮深色橡木 |
| V / L | 藤叶末端 / 藤叶阴影 | 橡树叶 / 红树树叶 |
| F | 稀疏花节点 | 开花杜鹃叶 |
| M | 树皮苔藓 | 苔藓块 |

全部叶片使用 `persistent=true`；木藤采用无需邻接支撑的实体方块。资源是 classpath 配置，修改后需重新构建并重启。
生成版本为 4。已有 Chunk/Cube 不自动重种，也不会覆盖玩家修改；验收整棵新版树请使用新世界。

## 2026-09-17 验证

种子 137 的结构阶段 SHA-256 仍为
`99386e637128db3c5ae0c5a9ed42b3281dcad036b517421c89ceb0b7f86f147f`，与修改前完全相同。
实测 D/N/n/M 的数量分别为 3,136,739 / 9,396,267 / 2,620,110 / 132,967，与修改前一致。

| 指标（种子 137） | 新版 |
| --- | --- |
| 实体体素 | 21,280,409（旧版 60,509,461） |
| 材料种类 | 14，全部实际出现 |
| 实体范围 | X=55..592，Y=55..571，Z=0..3071 |
| 高度 | 3072，每层都有实体 |
| 最终稀疏页存储 | 108,400,640 字节（约 103.38 MiB） |
| XZ / YZ 双向剪影 99% 位移 | 2.24 / 2.83 格 |
| XZ / YZ 最大局部位移 | 7.21 / 6.40 格 |
| XZ / YZ 逐行外包络交并比 | 94.38% / 94.21% |

镂空会降低原始投影填充率，原始剪影 IoU 约 76.5%，不能以“实心剪影完全一致”衡量针叶镂空。主干和枝冠布局由不变的结构程序锁定，细化差异限制在原包络附近。

以下种子逐字节比较完整的 1,289,945,088 格画布（包括空气），Java 与 MarkovJunior + 新版 C# 扩展完全相同：

| 种子 | 最终体素 SHA-256 |
| --- | --- |
| 137 | `454974f89cdc48381f851f43ec0016d331fc5f087410d219814ada47a96999f7` |
| 0 | `51aeb895acd14144c45114f9af9cd2f79f997ba4cf55b498a9255776b1ee76dc` |
| -1 | `a9f7c71af787eb4daf65a405b436297e269d0894473a24dfa05d1e1dd084f3b6` |

Java 在 `-Xmx512m` 下完成，生成加三阶段完整哈希约 18–22 秒（非隔离性能基准）。首次生成仍有明显开销。
`build --offline` 成功，59 项单元测试通过；生产 JAR 的模型、映射与源码一致，未包含测试类或测试结构。
隔离 GameTest 1/1 通过，覆盖方块配置解码、383/384 高度接缝、32³ 同步/异步放置、负坐标树梢、Create 映射及玩家编辑后的存档往返。
预览由真实生成体素投影得到，颜色仅近似材料，不是客户端材质、光照或光影包截图；尚未做客户端视觉验收。

## 复现

```powershell
# 独立 C# 对照；默认另含 int.MinValue/int.MaxValue
./scripts/markov/validate-redwood.ps1 -Seeds 137,0,-1

# 同时导出实际体素的整树、针叶、藤蔓 PNG 与数量统计
./scripts/markov/validate-redwood.ps1 -Seeds 137 -Preview

# 常规构建和单元测试
./gradlew.bat build --offline

# 隔离游戏测试，不使用玩家存档
python scripts/prepare-allvr-worldgen-test.py
New-Item -ItemType Directory -Force build/epic-redwood-test-run/mods | Out-Null
Get-ChildItem -LiteralPath run/mods -Filter 'owo-lib-neoforge-*.jar' | Copy-Item -Destination build/epic-redwood-test-run/mods
./gradlew.bat -I scripts/epic-redwood-test.init.gradle runGameTestServer --offline
```

对照需要 .NET 9、JDK 21 和 `.refs/MarkovJunior`；游戏运行不需要这些参考文件或外部进程。
`scripts/markov/RedwoodUpstream.csproj` 使用原解释器，仅替换细化器并加入 botanical 扩展，不修改被忽略的参考仓库。
`-Preview` 的输出位于 `build/redwood-preview/<seed>`，局部预览是有裁切边界的体素窗口。
单元测试覆盖独立 C# 三阶段哈希、连续顶梢、稀疏针叶密度、材料出现、同种子确定性和异种子变化。
