# 开发验证入口

## 快速检查

```powershell
.\gradlew.bat test assemble --offline
```

普通测试位于 `src/test/java/`。优先保留行为、生命周期、持久化和编解码测试。Mixin 字节码及发行资源契约有独立作用；源码字符串检查只有在对应行为尚未由真实场景覆盖时才保留。测试数量不是覆盖度或性能证明。

构建脚本改动另外运行 `python scripts/testing/validate_gradle.py`，验证惰性API提取、配置缓存、重映射选择及失败时输出保留；构建职责与缓存说明见[Gradle维护](building.md)。

## GameTest

```powershell
.\gradlew.bat runGameTestServer -I scripts/trickster/gametest.init.gradle --offline --no-configuration-cache
.\gradlew.bat runGameTestServer -I scripts/particles/package-output-gametest.init.gradle --offline --no-configuration-cache
# 同一套件的原生恢复模式
.\gradlew.bat runGameTestServer -I scripts/particles/package-output-gametest.init.gradle -PpackageRecoveryGameTest --offline --no-configuration-cache
```

各套件保留小型初始化入口，共用 [common.gradle](../scripts/testing/common.gradle) 的 `gameTest` source set、NeoForge 模组绑定、工作目录、Owo 依赖准备和空结构夹具。准备脚本只生成资源或数据包，无需再向运行目录复制 Owo。测试类及夹具不会进入 main 输出或发行 JAR，包括同一次调用同时执行 `assemble` 的情况。

| 范围 | 初始化脚本 | 额外准备/选项 |
| --- | --- | --- |
| Trickster 出库 | `scripts/trickster/gametest.init.gradle` | 无 |
| Create 包裹输出/恢复 | `scripts/particles/package-output-gametest.init.gradle` | `-PpackageRecoveryGameTest` 切换恢复模式 |
| 大范围碰撞 | `scripts/collision/gametest.init.gradle` | 无 |
| 资源转换 | `scripts/conversion/gametest.init.gradle` | `-PconversionAbsent=hexcasting` 或 `trickster` |
| HexParse | `scripts/hexparse/gametest.init.gradle` | 缺席用 `scripts/hexparse/absent.init.gradle` |
| Hex JIT | `scripts/hexjit/gametest.init.gradle` | 正确性子集追加 `-I scripts/hexjit/regression.init.gradle` |
| Allay Storm | `scripts/allaystorm/gametest.init.gradle` | 可选 `-PdomeEvidence=<生产GPU证据JSON>`；见 [验证说明](allay-storm-validation.md) |
| ALLVR 世界生成 | `scripts/allvr-worldgen-test.init.gradle` | 先运行 `scripts/prepare-allvr-worldgen-test.py`；可选 `-PallvrTestPack=terralith` |
| Sanctuary/Epic Redwood | `scripts/sanctuary-test.init.gradle` / `scripts/epic-redwood-test.init.gradle` | 先准备 vanilla 世界生成夹具 |
| Markov | `scripts/markov/gametest.init.gradle` | 先运行 `scripts/markov/prepare-gametest.py` |
| YSM | `scripts/ysm/gametest.init.gradle` | 本地 YSM 运行时及模型；见 [YSM 验证](../scripts/ysm/README.md) |

一次运行选择一个 GameTest 套件。运行目录为 `build/gametest/<套件>/`；生成的结构在相邻 `<套件>-resources/`。世界生成及模型夹具仍由对应准备脚本生成。缺席探针保留各自依赖控制，不把所有可选模组加入统一运行环境。

## GPU 验证与性能

```powershell
python scripts/benchmarks/run.py validatePackageGpu --offline
python scripts/benchmarks/run.py validatePackageLightGpu validatePackageObserverGpu validatePackageIrisGpu --offline
python scripts/benchmarks/run.py validateHexGpu validateHexSharedIrisGpu --offline
python scripts/benchmarks/run.py validateModelPopulationGpu validateModelShadowIrisGpu --offline
python scripts/benchmarks/run.py validateStarfallGpu validateDomeGpu validateHelixGpu --offline
```

实际驱动验证需要 OpenGL 4.5，使用隐藏 GLFW 窗口。Iris 测试使用实际 transformer 和受控启动 facade，仍需游戏内 shaderpack/Mixin 验收。GPU 不可用时报告环境限制，不能把跳过当作通过。

客户端进入世界卡顿的着色器编译回归使用 `validateParticleStartupGpu`；追加 `-PparticleStartupCold` 用每次运行唯一的无语义宏生成冷源码变体，验证分钟级后台链接期间仍能推进渲染循环。覆盖首次失败、热重载回退、在途取消、源码未变复用及原子发布，日志中的注入编译错误是预期结果。命令、驱动计时及限制见[启动编译修复记录](benchmarks/particle-shader-startup-2026-10-23.md)。此项需要 `GL_ARB_parallel_shader_compile`，不能用已缓存的秒级运行替代冷编译验收。

真实客户端验收由用户执行，保持待验收状态；隔离目录的准备／启动命令、全部功能场景及反馈要求见[用户客户端验收清单](gpu-runtime-client-acceptance.md)。`prepareGpuClientValidation` 只准备依赖和配置，不启动客户端；实际画面、交互及实机 shaderpack 结果不由自动回归代签。

性能测量按需添加 benchmark 参数；正确性断言必须通过才能接受测量结果。运行器把命令、版本元数据、退出状态、样本和日志放到独立的 `build/benchmarks/` 目录。直接 Gradle 入口仍兼容，详见 [性能结果规则](benchmarks/README.md) 和 [GPU 工具](../scripts/particles/README.md)。

## Hexcasting / Trickster 整改验证

先顺序运行 Trickster、资源转换、两种模组缺席、HexParse 及其缺席、受影响的 Hex JIT 正确性子集。不要并发启动 GameTest：各入口复用同一 `gameTest` 编译输出。

```powershell
.\gradlew.bat test runGameTestServer -I scripts/trickster/gametest.init.gradle --offline --no-configuration-cache
.\gradlew.bat runGameTestServer -I scripts/conversion/gametest.init.gradle --offline --no-configuration-cache
.\gradlew.bat runGameTestServer -I scripts/conversion/gametest.init.gradle -PconversionAbsent=hexcasting --offline --no-configuration-cache
.\gradlew.bat runGameTestServer -I scripts/conversion/gametest.init.gradle -PconversionAbsent=trickster --offline --no-configuration-cache
.\gradlew.bat runGameTestServer -I scripts/hexparse/gametest.init.gradle --offline --no-configuration-cache
.\gradlew.bat runGameTestServer -I scripts/hexparse/absent.init.gradle --offline --no-configuration-cache
.\gradlew.bat runGameTestServer -I scripts/hexjit/gametest.init.gradle -I scripts/hexjit/regression.init.gradle --offline --no-configuration-cache
.\gradlew.bat test assemble --offline
```

性能入口使用 opt-in source set，不进发行包。相同输入每项预热 1000 次、采样 7 × 1000 次，输出时间、线程分配和 CPU 计时。旧短样本只作调试，不与最终长样本混合。

```powershell
# 修改前需自行保存 main classes/resources；此快照不能靠修改后的源码重建
.\gradlew.bat runGameTestServer -I scripts/hextrick/benchmark.init.gradle -PinteropBaseline -PbenchmarkOutput=build/benchmarks/hexinterop-baseline-final --offline --no-configuration-cache
.\gradlew.bat runGameTestServer -I scripts/hextrick/benchmark.init.gradle -PbenchmarkOutput=build/benchmarks/hexinterop-final --offline --no-configuration-cache
python scripts/hextrick/compare-benchmarks.py
```

保留的基线位于 `build/benchmarks/hexinterop-baseline/{classes,resources,distribution.jar,head.txt,staged.patch}`。基线入口停用 main 编译并切换其输出，基准源码、依赖和场景与当前版本相同。汇总保留中位数和各轮范围；微基准不代表整服吞吐、真实客户端网络或帧率。

真实两进程重启探针：先运行 `.\gradlew.bat runServer -I scripts/hextrick/live.init.gradle -PinteropPhase=write --offline --no-configuration-cache`，看到 WRITE_READY 后在控制台 `stop` 正常保存；再运行同命令 `-PinteropPhase=read`，等待 RESTART_PASS 后 `stop`。隔离目录 `build/hexinterop-live`，证据在 `evidence`，账目为 64000 − 7000 − 20000 = 37000。探针以真实方块 NBT 和原生 executor ENDEC 验证恢复，不替代客户端连接或玩家存档完整流程。每轮必须从 write 开始，read 会实际扣款，不能对已扣款世界重复 read。

真实客户端交由用户执行，操作清单见[互操作开发契约](hexcasting-trickster-interop-dev.md)。自动探针及夹具只能通过初始化脚本启用。

## 维护与发布

- 新增回归优先扩展已有场景或参数化测试，共享夹具；不为每个修复复制整套启动脚本。
- 行为测试覆盖后再移除对应实现细节断言；不能以文件数为依据删除测试。
- CI 的普通构建执行 JVM 测试；GameTest、真实 GPU 和原生模型探针按环境单独执行。
- 发行检查包括内嵌 mod JAR，确认没有 GameTest 类、测试结构或开发探针。
- 当前结果读取实际 JUnit XML、GameTest/GPU 日志；文档不维护不断增长的历史断言总数。

GPU 环境审计先运行 `.\gradlew.bat preparePackageNativeAudit --offline --no-configuration-cache`，再运行 `python scripts/particles/audit_package_environment.py --check`。专用 GPU／GameTest 入口自动准备实际 Create 二进制身份；源码以该 JAR 记录的 Git 提交为准。

权威协议 15 的区域合包使用 `PackageEnvironmentBatchCodecTest`、`PackageEnvironmentUplinkTest` 与 `PackagePacketTest` 验证布局、整包拒绝、路由、容量及传输失败；CPU 路径 A/B：`python scripts/benchmarks/run.py benchmarkPackageEnvironmentUplink --offline --no-parallel`。此入口逐条校验数据，不包含 GPU／网络 sockets／世界事务。完整协议边界见 [协议 15](gpu-package-protocol-15.md)。

`/cmi particle profile on` 额外记录包裹 prepare 的 CPU／异步 GPU p50／p95／p99，以及反馈路由／封包的 CPU 阶段。Hex 四上下文与包裹绘制复用公共 timestamp 环；关闭或世界重置会退休相应查询，不占用 `GL_TIME_ELAPSED`。服务端以 JVM 参数 `-Dcmi.profilePackageTransactions=true` 开启环境解码、校验、APPLY 和整包壁钟 p50／p95／p99，停止时写入日志；需要实际权威客户端发送环境包才能取得这些阶段样本。GameTest 的直接回调不冒充网络事务测量。

GPU 运行时公共机制／发射写入器／环境待处理集合专项：

```powershell
.\gradlew.bat test validateHexGpu validatePackageGpu --offline --no-configuration-cache --no-parallel -I scripts/particles/validation.init.gradle -PpackageJournalFairness
python -B scripts/particles/prepare_package_baseline.py
.\gradlew.bat validatePackageGpu --offline --no-configuration-cache --no-parallel -I scripts/particles/validation.init.gradle -I scripts/particles/environment-benchmark.init.gradle -PpackageEnvironmentFeedbackBenchmark
```

后者仅测环境 capture／不可变快照提交／fence 后有效范围读取及回调解码；不包含网络、服务器事务与整帧绘制。原始 CSV 在忽略的 `build/package-environment-feedback*.csv`。五轮预热后的五次试验交替基线／当前顺序。验收边界见 [GPU 运行时当前报告](benchmarks/gpu-runtime-architecture.md)。

性能 JavaExec 的 metadata 记录 GPU Java、shader、验证脚本和生成基线的 SHA-256，包含未提交／未跟踪源码；不能只用相同 HEAD 标识区分候选。

环境 ACK 有界批处理专项包含在 `-PpackageRepairOnly` 和 `-PpackageJournalFairness` 中：逐条／批量逐字结果、512／513 边界、同 body 长链、乱序旧 ACK、完整生命周期字段和槽位复用、姿态／运动纠正，以及区域退休的增量归属清理。ACK 与控制预算隔离另有 JVM 测试。

```powershell
python -B scripts/particles/prepare_package_baseline.py
.\gradlew.bat validatePackageGpu --offline --no-configuration-cache --no-parallel -I scripts/particles/validation.init.gradle -I scripts/particles/environment-benchmark.init.gradle -PpackageEnvironmentAckBenchmark
```

此 A/B 比较 HEAD 的逐条 uniform／dispatch 与当前有界列表；含列表初始化／上传／提交及 GPU 应用，不含网络、服务器事务、碰撞或整帧绘制。每次预热 5×20 后测 40 个样本，五次试验交替顺序；输出为忽略的 `build/package-environment-ack*.csv`。每个样本逐身份校验 ACK 结果与 dispatch 数，不能以丢弃 ACK 换取收益。

环境历史分页与资源退休专项：

```powershell
.\gradlew.bat test validatePackageGpu --offline --no-configuration-cache --no-parallel -I scripts/particles/validation.init.gradle -PpackageHistoryPages
```

`pagedEnvironmentHistory` 验证页争用、局部背压、部分 ACK、20 槽环绕、运动恢复、生命周期复用、在途快照和消费失败重试；`pagedEnvironmentGrowth` 验证带有效历史的 GPU copy 和扩容；`pagedEnvironmentMaximum` 验证 131072 个唯一页、全量 ACK 回收和最小 128 MiB SSBO 边界；`growingBufferRetirementBoundary` 验证嵌套计算／绘制快照不会恢复已退休的缓冲名称。前两项和退休测试也纳入 `packageRepairOnly`／`packageJournalFairness`；最大容量专项仅由 `packageHistoryPages` 启用。Hex／Iris 的嵌套边界恢复另由 `validateHexSharedIrisGpu` 验证。

`prepare_package_baseline.py --ref INDEX` 可在修改前保存已暂存的实现，默认仍为 `HEAD`，也可指定不可变 Git ref。INDEX 描述包含 HEAD 与暂存二进制差分的 SHA-256，脚本不写 index 或 Git 对象。必须先保存基线，再编辑待比较实现；输出放在忽略的 `build/package-environment-baseline`。本轮分页比较使用 INDEX 基线，两边均恢复相同 native motion checkpoint。普通步进和反馈捕获分别测量，不包含首次分配、页增长、网络／服务器或整帧绘制；页增长正确性不能冒充增长性能测量。

隔离后续修复可先用 `--ref WORKTREE --output build/<独立基线目录>` 保存工作区源码，包含未跟踪的 shader 和分页布局；生成基线使用不同 Java 名称。仅允许输出到忽略的 `build/` 内，不写 Git 对象或 index。A/B 的 init script 通过 `-PpackageEnvironmentBaseline=build/<独立基线目录>` 选择对应源码／资源，metadata 同时记录内容摘要与基线标识；不要重新生成已经作为历史结果依据的基线。

`captureDemandStatistics` 已纳入分页／修复／公平性专项，覆盖第一遍设备端累计、第二遍发布清零、重传统计、终止／依赖等待过滤、四槽满环和残留 scratch 清理。反馈 A/B 在计时前逐条验证生命周期／序号及身份唯一性；采样期间继续校验每轮记录数，校验环节不替代 GPU 正确性专项。
