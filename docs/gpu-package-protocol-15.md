# GPU 包裹权威协议 15

状态：区域环境合包已实现，完整环境行为与分页 journal **尚未验收**。固定 Minecraft 1.21.1／NeoForge 21.1.236／Create 6.0.10。权威通道为 `gpu-packages-15`，观察通道保持 `gpu-package-observers-5`；服务端与权威客户端需要同时升级。

固定 20 样本窗口的 GPU 历史存储已改为按需页池，并保留不可变异步快照；这不改变 1024 B 记录、ACK 或存档格式，也不等于逐行为阶段的有序分页效果 journal 已完成。真实 GLSL 正确性专项与联合回归通过；设备端缺页统计修复了高容量饱和捕获的中位耗时回退，尾分位、增长维护和网络／整帧性能仍待验收。详见[存储布局](gpu-package-environment-14.md)及[本轮结果](benchmarks/gpu-runtime-architecture.md)。

`ServerboundPackagePacket.ENVIRONMENT` 继续使用 region／epoch／region revision 的外层信封，body 改为连续的 1～24 条 1024 B journal 记录。body 必须非空、长度是 1024 的整数倍且不超过 24576 B；不加入跨包字典、跨区域索引或隐式身份。单条记录、48 B 样本、20 条窗口和现有 ACK 格式保持原布局，详见[环境字段与存档](gpu-package-environment-14.md)。

客户端用 body 到 acquisition 的增量归属索引确定路由，逐条校验 identity／generation／lease／index／revision，然后把借用的只读捕获记录转换到区域原点，直接写入有界 staging。最多保留 8 个区域缓冲，同区域 epoch 或 revision 改变前先封包。每 24 条发送一次，poll 结束发送尾包；不会在 poll 结束后保留捕获槽引用。未发送或发送失败的记录仍由原 GPU journal 重传，只有累计 ACK 能解除确认责任。世界切换／退役清空本地待发送字节。

服务端先解码和校验整个 body，再执行第一条事务。畸形尾记录不能使前缀先产生世界副作用。随后按包内顺序处理，每个事件独立查验完整生命周期；过期事件、缺失身份或单个包裹的拒绝不跳过后面其他身份。样本和主线程时间预算沿用现有服务端限制，成功前缀使用原累计 ACK，未处理后缀由 journal 重传。合包不合并不同包裹的确认、重试或终止规则。

当前版本不新增存档字段；既有 `EnvironmentMotion`、凋零和门户字段及旧记录默认值保持原规则。**协议号 15 不代表计划第三批全部完成**：逐行为阶段／接触／生命周期的分页效果 journal、未知事务结果的隔离恢复、完整移动结构与流体差分仍未完成，后续 wire 改动必须再次审查版本兼容性。

验证入口：

```powershell
.\gradlew.bat test --offline --no-configuration-cache --no-parallel
python scripts/benchmarks/run.py benchmarkPackageEnvironmentUplink --offline --no-parallel
.\gradlew.bat runGameTestServer --offline --no-configuration-cache --no-parallel -I scripts/particles/package-output-gametest.init.gradle -I scripts/particles/validation.init.gradle
```

JVM 测试覆盖 24／25 条、交错区域、epoch／revision 更换、原点转换、满区域缓存、畸形尾记录、只读输入及传输失败后清空重试。GameTest 覆盖已有环境／机器行为，尚无真实多客户端合包网络验收。A/B 使用实际信封 codec 与 journal 解码，逐条校验所有生命周期、样本和区域顺序；不包含 sockets、压缩、服务端世界事务和 GPU，不能代替完整路径验收。结论见[运行时性能边界](benchmarks/gpu-runtime-architecture.md)。
