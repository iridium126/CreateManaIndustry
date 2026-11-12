# 开发与验证入口

从仓库根目录运行。复用 main／GameTest 输出的 Gradle 进程串行执行；GPU 不可用或跳过不计通过。当前结果以实际 JUnit XML、GameTest／GPU 日志为准，文档不维护历史断言总数。

## 快速检查

```powershell
.\gradlew.bat test assemble --offline
```

普通测试位于 `src/test/java/`，优先保留行为、生命周期、持久化和编解码测试。构建脚本改动另运行 `python scripts/testing/validate_gradle.py`，验证惰性 API 提取、配置缓存、重映射选择与失败输出保留；详见 [Gradle 维护](building.md)。

## 验证职责

| 验证层 | 主要行为 | 入口 |
| --- | --- | --- |
| JVM | 生命周期、身份、租约、ACK、编解码、库存事务、时钟、预算 | `gradlew test` |
| 真实 GPU | 碰撞/CCD、支撑、姿态、历史版本、局部上传、槽位复用 | `validatePackageGpu` |
| GPU 光照/观察者 | 有界反馈、姿态、乱序、旧身份、fence 与回收 | `validatePackageLightGpu validatePackageObserverGpu` |
| Iris | 实际 transformer、主相机/阴影、状态边界 | `validatePackageIrisGpu` |
| Create GameTest | 溜槽/传送带/漏斗输出、物品与生命状态、接管与恢复 | `runGameTestServer` + packages 初始化脚本 |
| 可选依赖缺席 | Sable 未安装时桥接不解析相关运行时 | `validatePackageSableAbsent` |
| benchmark | 稀疏/密集场景、网络与暂停成本、索引选型 | `benchmark*` 或 benchmark 参数 |

碰撞、历史与采集改动先跑对应 JVM 测试，再跑 GPU 子集；物理/协议共享路径变化时跑完整 GPU 套件；库存与机器交互需要实际 GameTest；Iris 代码需要真实 transformer 与游戏内画面。独立 GPU 断言不证明多人库存守恒、驱动差异、原存档行为或整帧性能；实际游戏仍需覆盖多人订阅迁移、机器阻塞、断线与资源重建、跨维度、配置切换及 shaderpack 画面。

## GameTest

```powershell
.\gradlew.bat runGameTestServer -I scripts/trickster/gametest.init.gradle --offline --no-configuration-cache
.\gradlew.bat runGameTestServer -I scripts/particles/package-output-gametest.init.gradle --offline --no-configuration-cache
# 同一套件的原生恢复模式
.\gradlew.bat runGameTestServer -I scripts/particles/package-output-gametest.init.gradle -PpackageRecoveryGameTest --offline --no-configuration-cache
```

各套件共用 [common.gradle](../scripts/testing/common.gradle) 的 `gameTest` source set、NeoForge 模组绑定、工作目录、Owo 依赖准备和空结构夹具。准备脚本只生成资源或数据包；测试类及夹具不会进入 main 输出或发行 JAR，包括同一调用同时执行 `assemble` 的情况。

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

一次运行选择一个 GameTest 套件。运行目录为 `build/gametest/<套件>/`；生成的结构在相邻 `<套件>-resources/`。世界生成及模型夹具由对应准备脚本生成，缺席探针保留各自依赖控制。

其余 opt-in 套件按各自初始化脚本单独运行：`scripts/mist/gametest.init.gradle`（Mist 灌溉）、`scripts/allvr-regression.init.gradle`（ALLVR 回归）、`scripts/castchain/optional.init.gradle`（`-PcastChainAbsent=hexcasting|trickster`）、`scripts/castchain/benchmark.init.gradle` 与 `scripts/hextrick/benchmark.init.gradle`（基准场景）、`scripts/allvr-client-validation.init.gradle`（隔离客户端准备）。

## GPU 验证与性能

```powershell
.\gradlew.bat validatePackageSableAbsent --offline
python scripts/benchmarks/run.py validatePackageGpu --offline
python scripts/benchmarks/run.py validatePackageLightGpu validatePackageObserverGpu validatePackageIrisGpu --offline
python scripts/benchmarks/run.py validateHexGpu validateHexSharedIrisGpu --offline
python scripts/benchmarks/run.py validateModelPopulationGpu validateModelShadowIrisGpu --offline
python scripts/benchmarks/run.py validateStarfallGpu validateDomeGpu validateHelixGpu --offline
```

实际驱动验证需要 OpenGL 4.5，使用隐藏 GLFW 窗口。客户端进入世界卡顿的着色器编译回归使用 `validateParticleStartupGpu`；追加 `-PparticleStartupCold` 生成每次唯一的无语义宏冷源码变体，需要 `GL_ARB_parallel_shader_compile`，不能用已缓存的秒级运行替代冷编译验收。正确性断言必须通过才能接受测量结果；微基准不代表整服吞吐或帧率。命令、版本元数据、退出状态、样本与日志保存到忽略的 `build/benchmarks/`，规则见 [性能结果](benchmarks/README.md) 与 [GPU 工具](../scripts/particles/README.md)。

真实客户端观感、复杂地形与多人帧率由用户实机验收；隔离目录准备／启动命令与全功能场景见 [客户端验收清单](gpu-runtime-client-acceptance.md)。`prepareGpuClientValidation` 只准备依赖和配置，自动回归不代签。

## Hexcasting / Trickster 整改验证

按顺序运行以下套件，不要并发启动 GameTest：

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

性能入口使用 opt-in source set，不进发行包；相同输入预热 1000 次、采样 7 × 1000 次。基线对比用 `scripts/hextrick/benchmark.init.gradle -PinteropBaseline`（修改前保存 main classes/resources）与 `python scripts/hextrick/compare-benchmarks.py`。真实两进程重启探针用 `scripts/hextrick/live.init.gradle -PinteropPhase=write|read`，每轮必须从 write 开始，read 会实际扣款。契约与客户端清单见 [互操作开发契约](hexcasting-trickster-interop-dev.md)。

## GPU 环境与协议专项

协议 15 的区域合包由 `PackageEnvironmentBatchCodecTest`、`PackageEnvironmentUplinkTest` 与 `PackagePacketTest` 验证布局、整包拒绝、路由、容量及传输失败；边界见 [协议 15](gpu-package-protocol-15.md)。

```powershell
.\gradlew.bat preparePackageNativeAudit --offline --no-configuration-cache
python scripts/particles/audit_package_environment.py --check
python -B scripts/particles/prepare_package_baseline.py
.\gradlew.bat test validatePackageGpu --offline --no-configuration-cache --no-parallel -I scripts/particles/validation.init.gradle -PpackageJournalFairness
.\gradlew.bat validatePackageGpu --offline --no-configuration-cache --no-parallel -I scripts/particles/validation.init.gradle -I scripts/particles/environment-benchmark.init.gradle -PpackageEnvironmentFeedbackBenchmark
.\gradlew.bat validatePackageGpu --offline --no-configuration-cache --no-parallel -I scripts/particles/validation.init.gradle -I scripts/particles/environment-benchmark.init.gradle -PpackageEnvironmentAckBenchmark
.\gradlew.bat test validatePackageGpu --offline --no-configuration-cache --no-parallel -I scripts/particles/validation.init.gradle -PpackageHistoryPages
```

`packageRepairOnly`／`packageJournalFairness` 覆盖 ACK 有界批处理、环境历史分页、在途快照与资源退休；`packageHistoryPages` 另含 131072 页与 128 MiB 边界。环境 A/B 只测 capture／不可变快照提交／fence 后读取与回调解码，不含网络、服务端事务或整帧绘制；每个样本逐身份校验结果，不能以丢弃 ACK 换取收益。基线由 `prepare_package_baseline.py` 保存到忽略的 `build/package-environment-baseline`，支持 `--ref INDEX|WORKTREE` 与 `--output build/<独立基线目录>`；必须先保存基线再编辑实现。验收边界见 [GPU 运行时当前报告](benchmarks/gpu-runtime-architecture.md)。

## 维护与发布

- 新增回归优先扩展已有场景或参数化测试，共享夹具；不为每个修复复制整套启动脚本。
- 行为测试覆盖后再移除实现细节断言；不能以文件数为依据删除测试。
- CI 的普通构建执行 JVM 测试；GameTest、真实 GPU 与原生模型探针按环境单独执行。
- 发行检查包括内嵌 mod JAR，确认没有 GameTest 类、测试结构或开发探针。
- 配置/命令变更时同步更新本文与对应主题文档；历史测量数据不写入 Git。
