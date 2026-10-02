# GPU 包裹修复验收记录

本页保留此前修复阶段的验证数字。本次旧代码清理已完成，最新测试数、GPU 结果及 JAR 检查见 [清理验证](gpu-package-cleanup.md)。

日期：2026-10-02。对应当前工作区源码；实际行为及接口见 [轻量记录说明](gpu-light-package-records.md)。

## 已执行验证

| 项目 | 结果 | 证据 |
| --- | --- | --- |
| 包裹正式单元测试 | 62 个测试类、337 项测试通过，零失败/跳过 | [tests.json](benchmarks/package-validation-2026-10-02/tests.json) |
| 全项目构建及单元测试 | 91 个测试类、442 项测试通过，零失败/跳过，JAR 构建成功 | [all-tests.json](benchmarks/package-validation-2026-10-02/all-tests.json)、[构建日志](benchmarks/package-validation-2026-10-02/build.log) |
| GPU 物理、接管、事件及槽位回归 | 11,765,762 项断言通过 | [GPU 验证日志](benchmarks/package-validation-2026-10-02/validation.log) |
| GPU 观察流回归 | 3,548,729 项断言通过 | 同上 |
| 三种空间索引 benchmark | 225 组、45000 个正式采样全部通过质量门槛，保留 linked | [比较报告与原始 CSV](benchmarks/package-index-2026-10-02/comparison.md) |
| 131072 条记录暂停/退役/重接管微基准 | 三轮，CPU 运动调用均为 0，处理批次不超过 64 | [汇总](benchmarks/package-pause-2026-10-02/summary.csv)、[逐批原始数据](benchmarks/package-pause-2026-10-02/samples.csv) |

OpenGL 设备为 NVIDIA GeForce RTX 4070 Laptop GPU，驱动 581.15，OpenGL 4.5。GPU 回归包括高速撞静止的双向 CCD、流体实际扫掠与水面上方不误接触、水中终止、持续火焰、离火倒计时、熔岩、免疫资格变化、延迟 ACK 重放、队列积压与重试、机器接触边界、候选预算耗尽及恢复、历史几何版本局部隔离和移动结构。

容量 4 的自由、锁链和观察槽位分别周转 64 个生命周期，超过十倍容量，注入旧 ACK、环境 ACK、final/retire 控制和旧提交代交互结果；确认旧结果被拒绝、退役完成后可复用、空闲尾部不搬移活动项。GPU 检查点也按提交代过滤旧读回。正式时钟测试覆盖 5、9、15、20、30、60 FPS 的十秒完整输入，验证 200 次步进，无累计丢步；覆盖输入延迟、暂停、历史缺口、重建基线以及后台不可变力源。

正式测试包含库存/NBT 和身份持久化、旧环境字段默认值、区域增量四步补算/旧步骤拒绝/服务器 lease、原生自由运动及后备入口删除的字节码契约、锁链服务端跳过逐 tick 视觉姿态以及交接时才求逻辑位置。环境事件非零及负区域坐标转换也有独立测试。

最终 JAR 为 `build/libs/createmanaindustry-0.2.6.jar`。已检查 JAR 不包含 `PackageNativeDownlinkMixin`、两个落选索引实现的 shader 或独立 benchmark 参考类。`clean` 在 Windows 的 JVM 性能临时目录上遇到文件锁；编译、资源、JAR、测试输出已被清除后重新运行 build 成功，残留目录仅为 JVM 临时目录。构建日志记录实际编译和测试任务，不将 clean 失败称为成功。

## 索引选择

每规模五场景等权，三个规模权重为 25% / 25% / 50%。三轮前两名差距不足 3%，追加两轮后仍相差约 1.04%，按约定比较 CPU 提交 p95：linked 加权分数 1.004876，小于 bounded_linked 的 1.035642，因此选择 linked。额外索引显存也更少。生产删除两个落选实现、模式 API 和 shader 变体；独立参考目录只用于可重复比较，不进入生产 JAR。

所有采样有效推进数量等于输入数量，没有暂停、缺失或非有限值；包裹穿透小于 0.002 方块，地形穿透小于 0.0001 方块，活动场景超过 99% 包裹推进。131072 规模 linked 的五场景 GPU p95 中位数为 9.10–10.46ms。计时包括完整物理、环境检测和捕获，质量探针读回位于计时区间外。

暂停微基准直接调用实际 `PackageAuthorityRegion` 的关闭及有界退役接口，构造 131072 个轻量测试目标，不启动 Minecraft 世界。三轮暂停调用最高 0.0259ms，64 项退役批次 p95 为 0.0179–0.1231ms，重接管批次 p95 为 0.0299–0.1660ms。它证明该组件路径有界且不调用目标运动写入，不能等同于真实服务器 tick、网络通知、存档或客户端整帧性能。

## 复现命令

```powershell
.\gradlew.bat -I scripts/particles/validation.init.gradle test --tests '*packages.*' --tests '*gpupackage.*' validatePackageGpu validatePackageObserverGpu build --offline
.\gradlew.bat -I scripts/particles/validation.init.gradle benchmarkPackageIndexes --offline
.\gradlew.bat -I scripts/particles/validation.init.gradle benchmarkPackageIndexes -PpackageIndexBenchmarkExtra --offline
python scripts/particles/compare_package_indexes.py
.\gradlew.bat -I scripts/particles/validation.init.gradle benchmarkPackagePause --offline
.\gradlew.bat -I scripts/particles/validation.init.gradle build --offline
```

`validation.init.gradle` 仅为现有 Hex 依赖补充独立验证所需 Kotlin 运行库，并定义测试/benchmark 任务。重新运行 benchmark 前先保存现有 CSV；五轮文件必须扩展同一份前三轮数据，统计脚本拒绝混合不同实验。

## 尚需真实游戏验证

以下项目尚未实测，不能由上述独立 GPU/单元测试结果推定通过：

- Create/NeoForge 实际启动时的 Mixin 应用；玩家丢出、所有机器输出、锁链脱落和旧存档实体加载立即成为记录，不出现重复实体、旧位置阴影或碰撞。
- 各漏斗、移动漏斗、溜槽、传送带、置物台及弹射置物台的过滤、阻塞和部分接收；双人同时拾取、攻击和爆炸；逐项核对物品数量及箱内 NBT 守恒，重复事件不得再次插入或掉落。
- 水中破包、持续着火、离火和熔岩在非原点区域的游戏效果；长延迟、断线重连和销毁队列积压后内容只掉落一次。
- 保存重载、区块卸载/加载、跨维度及各传送门的状态和物品守恒；暂停期间无 CPU 自由运动，其他就绪客户端可重新接管。
- 无观察者时锁链完成真实物流，重新观察与确认逻辑进度一致；CPU/GPU 切换期间无重复端口交接，移动父结构及连接拓扑变化正常。
- 游戏内 5–60 FPS、worker 延迟、移动结构、历史缺口和恢复；在 131072 包裹掉线、关闭 GPU 和重新接管时采集完整服务器 tick 与客户端帧耗时，确认不会出现由通知、资源销毁或存档导致的突发工作。

自由包裹无 GPU 时暂停是本次明确行为，不保证无观察者时自由包裹继续移动。锁链物流则必须继续推进。其他模组若要求自由 `PackageEntity` 对象，需要记录接口适配；当前验证未覆盖所有第三方实体扩展。
