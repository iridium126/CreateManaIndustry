# Allay Dimension Cube 生成与加载性能

本轮优化保持存档格式、岛屿布局和特性应用顺序不变。范围为中央原版高度带之外的 Cube 生成与服务端加载调度。

## 发现与修改

| 路径 | 原有开销或问题 | 修改 |
| --- | --- | --- |
| `AllvrIslandFieldGenerator.generateAsync` | Future 并不保证异步：发现依赖时创建源区块、填充 biome；缓存全部命中时还会内联执行 Cube 填充 | 在 Minecraft 后台执行器上准备依赖，异步执行最终填充；仍通过服务端完成队列发布 Cube |
| `AllvrTerrainSource` | 普通 continuation 可内联运行昂贵的地表、特性、源区块合并，并串行占用完成上游任务的线程 | 使用显式后台 continuation；源区块合并的依赖集合明确包含基础区块，避免在遗漏的依赖上阻塞 `join` |
| `FeatureRegion` | 每次 decoration 预先复制全部 3×3 区块，恢复状态调色板并重建高度图 | 只提前复制中心；首次访问邻区块时，才从已经完成的不可变源数据复制。未访问的邻区块不再付出复制成本 |
| Cube biome 填充 | 最多 512 个 biome 单元分别重新构造岛屿候选集合 | 过滤当前 Cube 已有候选，保留原候选顺序及 nearest 回退 |
| `AllvrCubeMap` 加载扫描 | 256 个异步请求槽满后继续越过未接收的请求，扫描结束后可能不再加载这些位置 | 在首个因容量限制未入队的位置保留游标，后续 tick 继续；已有请求、空域及隔离的损坏记录不因此阻塞扫描 |

## 性能预期与边界

主要收益是减少加载请求对服务端 tick 的占用、减少邻区块复制和 biome 布局计算，并让站立玩家的加载在队列腾出容量后继续推进。按需复制的收益取决于特性实际访问范围；如果访问全部邻区块，复制次数仍相同。后台调度也有队列开销。

这些是代码路径上的改进，不代表已测得端到端吞吐率或 FPS 的提升百分比。磁盘解压、客户端解码、光照、网络发送和网格构建仍可能限制最终加载速度。未改变同步游戏写入入口、持久化优先于生成的规则及已有并发上限。

## 验证

2026-09-16 本地验证：52 项维度 JUnit 全部通过；vanilla 测试预设的 2 项 GameTest 全部通过（测试阶段 29.80 秒，不含服务器启动）。本次单次源区块计时为首次 3990 ms、邻接请求 1003 ms；没有同条件旧版本基线，不能据此计算加速比。详细运行日志位于 `build/allvr-performance-validation.log`。

- 维度 JUnit：覆盖存储、I/O、光照、坐标和布局；新增测试逐一比较 Cube 候选过滤与原始单点查询，覆盖多个种子、正负极端高度和岛屿边界。
- 世界生成 GameTest：检查源区块生成顺序一致性、中央带和外围 API；新增 4 个并发热缓存 Cube 与同步结果的全部方块、生物群系和 section 实例隔离检查。
- GameTest 的 API 用例移至 Y=448，避免相邻测试结构在 Y=384 共用同一 Cube，使“访问前未加载”的前置条件失效。

复验命令：

```powershell
python scripts/prepare-allvr-worldgen-test.py
.\gradlew.bat -I scripts/allvr-worldgen-test.init.gradle test --tests '*dimension*' runGameTestServer --offline --no-configuration-cache --console=plain
```

实际性能对比应使用相同种子、视距、飞行路径和硬件，分别测量新区域生成、已保存区域重载、热缓存移动及多人重叠视野的 tick 耗时和 Cube 到达速度。队列满后保持玩家静止、验证周围继续加载，是本轮调度修复需要保留的实机回归场景。Terralith 和客户端渲染性能另行验证。
