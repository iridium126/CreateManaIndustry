# GPU 包裹测试与证据

更新：2026-10-04。当前行为见[实现总览](gpu-packages.md)和[轻量记录契约](gpu-light-package-records.md)。每个修复批次保留结构化测试摘要，完整诊断输出放在忽略的 `build` 目录。

## 最新验证

10 月 4 日审查的六项问题已按建议顺序修复，见[审查与修复记录](gpu-package-review-2026-10-04.md)。保留[验证摘要及基准索引](benchmarks/package-review-fixes-2026-10-04/tests.json)：全部 478 项单元测试、12,297,727 项完整 GPU 断言通过；光照/观察者/Iris 分别通过 886,165 / 3,548,729 / 26,730 项断言；6 项真实 Create/NeoForge GameTest 通过。正式构建成功，发布 JAR 不含 GameTest 夹具。131072 包裹的同环境修复前后基准均通过质量检查，p95 区间重叠，未观察到新增性能退化；不代表整帧或 200 tick/s 满容量达标。权威协议升级为 `gpu-packages-13`，两端需同时升级。

## 10 月 3 日验证

200 tick/s 客户端采集修复已通过单元测试、GPU 回归、隔离 Create/NeoForge GameTest 和正式构建。保留[测试摘要](benchmarks/package-client-cadence-2026-10-03/tests.json)及[用户日志复现摘录](benchmarks/package-client-cadence-2026-10-03/reproduction-excerpt.log)。修复原因和剩余实机限制见[客户端采集修复记录](gpu-package-client-cadence-fix.md)。

| 检查 | 结果 |
| --- | --- |
| 单元测试 | 446 项通过，无失败、错误或跳过 |
| 完整包裹 GPU 回归 | 12,266,419 项断言通过 |
| 光照 / 观察者 / Iris GPU 回归 | 886,165 / 3,548,729 / 20,586 项断言通过 |
| 实际 Create/NeoForge GameTest | 4 项通过 |
| 正式构建 | 成功；发布 JAR 不含 GameTest 夹具 |

设备为 Java 21、NVIDIA GeForce RTX 4070 Laptop GPU、OpenGL 4.5、驱动 581.15。上述组件测试未替代原存档联机、整帧耗时和 131072 包裹在 200 tick/s 下的性能实测。

## 历史修复批次

以下历史批次只保留测试摘要和能说明回归触发条件的日志；构建、GPU 逐项输出与完整 GameTest 日志已清理。

| 范围 | 主要覆盖 | 验证记录 |
| --- | --- | --- |
| Section 穿越与面光照 | 418 项单元测试；高速相对扫掠、相邻体素切触、覆盖重试及普通/Iris 亮度 | [摘要](benchmarks/package-section-light-fix-2026-10-02/tests.json) |
| 机器出口与材质摩擦 | 424 项单元测试；溜槽/传送带真实输出、初速度、机器分离与每步摩擦 | [摘要](benchmarks/package-machine-output-2026-10-02/tests.json) |
| 环境事件与区域切换 | 426 项单元测试、2 项 GameTest；接触坐标转换、其他区域模拟不中断及安全复用 | [摘要](benchmarks/package-exit-confirmation-2026-10-03/tests.json) |
| 连续输出 | 429 项单元测试、3 项 GameTest；连续传送带接管、低 TPS 历史计时及几何稳定 | [摘要](benchmarks/package-continuous-output-2026-10-03/tests.json)、[旧时钟复现](benchmarks/package-continuous-output-2026-10-03/old-clock-repro.log) |
| 200 tick/s 首轮 | 437 项单元测试、4 项 GameTest；该轮未覆盖真实客户端采集频率 | [摘要](benchmarks/package-200-tick-rate-2026-10-03/tests.json)、[旧时钟复现](benchmarks/package-200-tick-rate-2026-10-03/old-clock-200tps-repro.log) |

## 复现

```powershell
.\gradlew.bat -I scripts/particles/validation.init.gradle build validatePackageGpu validatePackageLightGpu validatePackageObserverGpu validatePackageIrisGpu --offline --console=plain
.\gradlew.bat -I scripts/particles/validation.init.gradle -I scripts/particles/package-output-gametest.init.gradle runGameTestServer --offline --console=plain
```

索引性能选择及完整原始 CSV 见[空间索引比较](benchmarks/package-index-2026-10-02/comparison.md)。三种索引均以相同修复物理验收，最终只在生产保留 `linked`。
