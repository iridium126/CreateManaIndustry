# 开发验证入口

## 快速检查

```powershell
.\gradlew.bat test assemble --offline
```

普通测试位于 `src/test/java/`。优先保留行为、生命周期、持久化和编解码测试。Mixin 字节码及发行资源契约有独立作用；源码字符串检查只有在对应行为尚未由真实场景覆盖时才保留。测试数量不是覆盖度或性能证明。

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

性能测量按需添加 benchmark 参数；正确性断言必须通过才能接受测量结果。运行器把命令、版本元数据、退出状态、样本和日志放到独立的 `build/benchmarks/` 目录。直接 Gradle 入口仍兼容，详见 [性能结果规则](benchmarks/README.md) 和 [GPU 工具](../scripts/particles/README.md)。

## 维护与发布

- 新增回归优先扩展已有场景或参数化测试，共享夹具；不为每个修复复制整套启动脚本。
- 行为测试覆盖后再移除对应实现细节断言；不能以文件数为依据删除测试。
- CI 的普通构建执行 JVM 测试；GameTest、真实 GPU 和原生模型探针按环境单独执行。
- 发行检查包括内嵌 mod JAR，确认没有 GameTest 类、测试结构或开发探针。
- 当前结果读取实际 JUnit XML、GameTest/GPU 日志；文档不维护不断增长的历史断言总数。
