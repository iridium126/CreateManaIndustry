# Allay Dimension 修复与验收记录

日期：2026-10-24。范围：R1–R8，以及随机 tick、独立计划 tick、光照边界和 Region 提交回滚。保留中央原版区块带、32³ Cube、Sodium 近景及可选 Voxy 远景。

## 当前契约

- 网络协议 **18**；完整包、增量、forget 均携带 watch generation，完整包和增量另有 network revision。网络排序不使用持久化 mutation/light version。
- Cube schema **3**。旧格式明确拒绝，没有迁移、自动删除或重新生成。region 文件格式仍为 1。
- 保留完整 ±30,000,000 逻辑高度。绝对 section 使用 `AllvrSectionCoord`；实体 opaque handle 仅用于管理器内部。Sodium/Voxy 转换到安全局部坐标后才 pack。
- 方块光窗口为 2048 格核心、上下各 16 格 halo；读取实际邻居和中央带，跨窗口连续。天空光独立分带，并在中央带 -128/384 切开；跨带不传播天空遮挡。
- Cube 分别拥有 block/fluid scheduled tick 队列；只在模拟票据有效且服务器正常推进时消耗剩余延迟。实体 NBT 仍由原版 XZ 列保存。halo 不拥有方块、tick 或存档。

## 修复与证据

| 阶段 / 编号 | 实现 | 自动化证据 |
| --- | --- | --- |
| 1 / R1 | `ServerChunkCache.blockChanged` 路由外围更新，保留 `sendBlockUpdated` 导航通知；同 tick 同位置合并 | dedicated GameTest 中高 Y 告示牌涂蜡及直接 `blockChanged`，无 ChunkHolder 越界 |
| 1 / R2 | generation/revision 屏障、异步解码缓冲重放、旧结果拒绝、受预算限制的重同步；BE full/delta/预测确认分别处理 | `AllvrStreamStateTest` 覆盖首次 full 后增量、较新 full 抢先、forget/re-enter、缺口及溢出；GameTest 验证 full BE 标签在发布时应用 |
| 2 / R3 | 完整 Y 实体 section 索引、单调 handle、完整坐标 section 事件；Voxy 队列保存完整坐标；空地形列也请求实体文件 | 实体索引单元测试覆盖 ±8,388,608、±16,777,216、±30,000,000、AABB 隔离和映射回收；GameTest 实际实体加入/移动/移除，以及同 XZ 不同高度玩家的独立模拟票据 |
| 3 / R4 | 保存开关同时控制快照和 IO；save-off 等待进行中的提交，暂停后续提交，save-on 保存最新状态 | worker 的 in-flight latch 屏障、暂停关闭、最新快照恢复测试；GameTest 执行真实 save-off/save-on 命令和 flush |
| 3 / R5 | 独立 block/fluid 队列，优先级、位置/类型去重、同刻排序、查询/清理/复制路由；schema 3 保存剩余延迟 | 队列 NBT 往返、去重和排序单元测试；GameTest 红石按钮、熔岩传播、冻结与恢复；连续两次专用服务器启动验证磁盘恢复 |
| 3 / R7 | 20/40/80/160/200 tick 退避队列；票据丢失取消；损坏记录隔离；发布失败回滚 | `AllvrMaintenanceTest` 验证静止时重试和取消；schema 拒绝测试、存储异常重试测试 |
| 3 / 存储回滚 | 候选 payload/header 成功后发布；失败恢复 slot 和分配状态；保护两份有效 header 引用的 sector，保留不确定写入的分配 | partial header、payload 部分/完整写入后失败、live 读取、重试、重新打开、旧 header 恢复及有界回收测试 |
| 4 / R6 | 普通创建、磁盘恢复和客户端发布进入 NeoForge fresh BE 队列；替换/卸载结束生命周期和 ticker | GameTest 自定义计数 Chest：重复装载只调用一次 onLoad，重复卸载只结束一次；full BE 回调在发布前不运行 |
| 4 / R8 | 最后一名玩家离开即撤销模拟并取消任务；保存、卸载继续；保存关闭、快照失败、pending IO 分别记录保留数 | 空服 GameTest：清洁 Cube 卸载、save-off dirty Cube 保留、save-on + flush 后全部可释放 Cube 归零 |
| 4 / 随机 tick | 每个随机 ticking section 按 randomTickSpeed 采样，分别执行 block/fluid；服从模拟距离和 freeze | GameTest 支撑农田上的小麦随机生长；计划 tick 冻结不消费延迟 |
| 5 / 光照 | 独立 block/sky 引擎；真实 halo；边界修改、Cube/原版 chunk 加载和卸载失效 | GameTest 覆盖 2047/2048、4095/4096、负向窗口、±16,777,216、383/384、-129/-128 的传播、移除、源 Cube 卸载、原版 chunk 卸载/重载，以及跨带天空光独立 |

每项先保留能暴露缺陷的用例再修复。光照回归实际发现并修复了两种减光遗漏：重新扫描只会增光，以及移除 section 后 padding 仍保留旧光源。测试等待原版光照 mailbox 屏障，避免用固定逻辑 tick 数猜测异步线程完成时间。

## 自动化执行

维度单元测试：**88 项，20 个测试类，0 failure / 0 error**，包含原有 71 项及新增 17 项。报告：`build/reports/tests/test/index.html`；机器结果：`build/test-results/test/TEST-*.xml`。

```powershell
python scripts/prepare-allvr-worldgen-test.py
.\gradlew.bat -I scripts/allvr-regression.init.gradle test --tests '*dimension*' runGameTestServer --offline
```

专用服务器 GameTest：**8 项全部通过**。连续两次启动均通过，第二次明确输出 `scheduled queues restart VERIFIED (block=37, fluid=53)`。

专用服务器 suite 在 `build/gametest/allvr-regression/world` 使用独立世界。重启用例第一遍写入 block=37、fluid=53 的剩余延迟；第二遍启动须出现 `scheduled queues restart VERIFIED`，而且全部 required tests 通过：

```powershell
.\gradlew.bat -I scripts/allvr-regression.init.gradle runGameTestServer --offline
.\gradlew.bat -I scripts/allvr-regression.init.gradle runGameTestServer --offline
```

组合回归日志：`build/allvr-final-server-tests.log`。中央 chunk 卸载/重载扩展回归：`build/allvr-gametest.log`。跨进程重启日志：`build/allvr-restart-seed.log`、`build/allvr-restart-verify.log`。测试不读取或修改 `run/saves`。

客户端验收工具已编译；按用户要求，客户端试跑已停止，**没有将启动日志或编译成功当作实机验收通过**。

## 性能与资源边界

本次记录实现约束和自动化结果；没有实机 FPS、GPU 时间或长期探索的测量结论。

| 工作 | 当前约束 / 变化 |
| --- | --- |
| 稳定玩家 | 不重复全量 shell 扫描；实体列请求队列对相同 XZ 多玩家去重，稳定票据不重复提交 |
| full 发送 | 每玩家每 tick 总预算 24；重同步最多 4 个，仍共享 full 总预算；待重同步集合每玩家最多 256 |
| 客户端解码 | 1–2 worker，队列 256，饱和请求重同步；发布最多 64 / 2 ms；单 watch 增量缓冲 4096 |
| 后台请求 | generation / persisted load 各最多 256；完成发布最多 16 / 2 ms；失败重试每 tick 最多 16 |
| 维护 | 快照最多 8 / 1 ms；卸载增量扫描最多 256、处理最多 64 / 1 ms；实体列加载最多 16 / tick |
| 光照 | 核心 2048，halo 16；仅相邻窗口失效；缓存 column view；均匀 light layer 压缩为共享单字节表示，变化后才标 dirty |
| 映射回收 | 空实体 section 同时移除坐标、handle、XZ 列和空间索引；客户端会话、异步 epoch、decode queue、重试与卸载队列均有清理入口 |
| 双 header | 保留两个有效恢复世代和一个候选的 sector 空间；回收测试验证反复覆盖不会随提交次数持续增长 |

预算限制的是工作入口及批次数；单次原版 light-engine propagation、单个 mod BE 回调和一个快照不能被这些软时间预算中途抢占。没有宣称每次调用都能严格在 1 ms 内结束。

## 交给用户的客户端实机验收

必需 Sodium **0.8.13+mc1.21.1**；Voxy **0.2.15-beta+1.21.1-neoforge** 可选。使用独立目录启动，默认由用户操作，不自动关闭：

```powershell
.\gradlew.bat -I scripts/allvr-client-validation.init.gradle runClient --offline
# 独立的 Voxy 对照场景
.\gradlew.bat -I scripts/allvr-client-validation.init.gradle runClient -PallvrClientRunDir=build/allvr-client-voxy-validation -PallvrWithVoxy --offline
```

脚本复制专用 GameTest 世界到自身 `build/.../saves/AllvrValidation`，安装固定 Sodium 及所需测试依赖。测试源集不进入发行 jar。测试命令 `/allvr_validation setup 500` 创建平台、告示牌和盔甲架并传送玩家；`/allvr_validation update` 修改方块、告示牌文字和蜡状态。可用 `setup` 依次切换到 ±8,388,608、±16,777,216、29,999,936 和 -29,999,936 附近。工具只编译，场景尚未由代理完成实机验收。

| 待验收场景 | 判定标准 |
| --- | --- |
| 网络时序 | 延迟/乱序 full 解码，首次 full 后立即修改，forget/re-enter、换维度、缓冲溢出：最终状态正确且重同步有界 |
| BE 与预测 | Y=500 告示牌改字/涂蜡、Create 机器真实 sendData、客户端破坏/放置及预测 ACK：NBT 正确、不复活旧方块、生命周期不重复 |
| 两玩家实体 | 同 XZ、不同极端高度：查询/移动/同步无串层；离开、重新加入及服务器重启后恢复正确 |
| 近景与远景 | Sodium section 可见，Voxy 存在/缺失两组；跨窗口、跨 slab、资源重载、forget/re-enter 无洞、残留或旧结果覆盖 |
| 生长与流体 | 作物、树苗、叶子、红石、水、熔岩；冻结、失票据、卸载、重启和多 Cube 同刻排序符合预期 |
| 光照视觉 | 四组边界两侧放置/移除光源及遮挡；卸载/重载后不残留；天空光符合独立高度带规则 |
| 长期资源 | 长距离探索、多人离开、退出世界：任务、可卸载 Cube、section 映射和渲染资源可回收；记录 CPU/GPU 时间与显存变化 |

真实断电/操作系统崩溃、磁盘写满或权限变更尚未进行实机注入；当前故障证据来自确定性的 payload/header 写入异常测试。单元测试和 GameTest 不替代上述未完成的游戏/GPU 验收。
