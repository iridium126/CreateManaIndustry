# Create Mana Industry 开发文档

本目录只记录实现契约、当前状态、入口文件和验证方式。详细历史讨论、重复的评审过程和已否决方案不再作为开发依据。

## 阅读顺序

1. 先看本文档和对应主题文档的“状态”部分。
2. 行为以当前源码、资源和测试为准；文档只解释代码不易表达的协议和边界。
3. 标记含义：✅ 已实现；🟡 已实现但仍需游戏内验证；📐 设计/待实施；⚠ 已知缺口。

## 文档索引

| 文档 | 内容 | 状态 |
| --- | --- | --- |
| [building.md](building.md) | Gradle职责、依赖范围、缓存、重映射、打包与验证 | ✅ |
| [gpu-runtime-architecture-plan.md](gpu-runtime-architecture-plan.md) | GPU 引擎全部现有功能的架构、优化方向、分批实施状态及验收边界 | 🚧 |
| [gpu-runtime-client-acceptance.md](gpu-runtime-client-acceptance.md) | 用户执行的隔离客户端启动、全功能检查清单与性能反馈格式 | 🚧 |
| [hexcasting-trickster-interop-review-2026-10-16.md](hexcasting-trickster-interop-review-2026-10-16.md) | 全部整改条目、自动回归、真实专服重启与性能对照；客户端待用户验收 | 🟡 |
| [hexcasting-trickster-interop-dev.md](hexcasting-trickster-interop-dev.md) | 来源、根预算、编解码及 adapter 契约，真实客户端验收清单 | 🟡 |
| [particle-engine-dev.md](particle-engine-dev.md) | GPU 粒子池、发射器、排序、碰撞、Hexcasting 与 shaderpack | 🟡 |
| [gpu-hex-shared-takeover-2026-10-10.md](gpu-hex-shared-takeover-2026-10-10.md) | Hex 世界/阴影/玩家预览/施法 UI 独立 GPU 后端、预算、基准与用户验收 | 🟡 |
| [gpu-package-model-pool-decision-2026-10-10.md](gpu-package-model-pool-decision-2026-10-10.md) | 包裹/MODEL 分池历史决策与测量范围 | 🟡 |
| [gpu-hex-cleanup-model-efficiency-2026-10-10.md](gpu-hex-cleanup-model-efficiency-2026-10-10.md) | 旧 Hex 清理、保留实体行为的 MODEL/Storm GPU 优化路线与物理优先项 | 🟡 |
| [gpu-model-rendering.md](gpu-model-rendering.md) | 当前帧/晚阶段绘制、独立阴影、同代活动索引与精确交互 | 🟡 |
| [gpu-packages.md](gpu-packages.md) | GPU 包裹当前实现总览与运行边界 | 🟡 |
| [gpu-package-protocol-15.md](gpu-package-protocol-15.md) | 权威环境区域合包、生命周期及未完成的 journal 边界 | 🚧 |
| [gpu-light-package-records.md](gpu-light-package-records.md) | 自由包裹记录、物理历史、环境与库存事务 | 🟡 |
| [gpu-package-observers.md](gpu-package-observers.md) | GPU 包裹观察者协议和姿态流 | 🟡 |
| [gpu-chain-authority.md](gpu-chain-authority.md) / [gpu-package-chain-frames.md](gpu-package-chain-frames.md) | 锁链权威、事务及父姿态坐标边界 | 🟡 |
| [gpu-package-collision.md](gpu-package-collision.md) | 碰撞、精确历史、采集/上传调度和冻结诊断 | 🟡 |
| [gpu-package-performance.md](gpu-package-performance.md) | 线程、计时边界、当前优化和性能诊断 | 🟡 |
| [gpu-package-repair-validation.md](gpu-package-repair-validation.md) | 回归场景矩阵与当前验证入口 | 🟡 |
| [gpu-block-emitters.md](gpu-block-emitters.md) | GPU 方块粒子发射器 | 🟡 |
| [particle-engine-performance.md](particle-engine-performance.md) | GPU 粒子性能数据和验收限制 | 🟡 |
| [allay-dimension-dev.md](allay-dimension-dev.md) | ALLVR 维度总览、坐标、加载、客户端渲染入口 | 🟡 |
| [allay-dimension-worldgen.md](allay-dimension-worldgen.md) | ALLVR 世界生成和 Y 分带 | ✅ |
| [allay-dimension-persistence-plan.md](allay-dimension-persistence-plan.md) | Cube 持久化格式、IO 和恢复约束 | 🟡 |
| [allay-dimension-sodium-rendering-parity-plan.md](allay-dimension-sodium-rendering-parity-plan.md) | Sodium 原生 section 适配路线 | 📐 |
| [allay-dimension-voxy-lod-integration-plan.md](allay-dimension-voxy-lod-integration-plan.md) | Voxy 远景接入路线 | 📐 |
| [allay-dimension-implementation-review-fix-plan.md](allay-dimension-implementation-review-fix-plan.md) | 未关闭的实现评审问题 | ⚠ |
| [allay-storm-sync.md](allay-storm-sync.md) | Allay Storm 服务端权威与客户端 GPU 同步 | 🟡 |
| [allay-storm-ai.md](allay-storm-ai.md) | Allay Storm 当前攻击、参数与配置 | 🟡 |
| [allay-storm-validation.md](allay-storm-validation.md) | 资源重建、单元／GPU／服务端验证和用户验收 | 🟡 |
| [allay-storm-art-direction-plan.md](allay-storm-art-direction-plan.md) | 悦灵风暴“天穹灵潮”美术设计、百万粒子预算、天空／光影适配与分批验收方案 | 📐 |
| [allay-storm-sky-rift-plan.md](allay-storm-sky-rift-plan.md) | 主世界风暴16384名后撕裂天幕、共用悦灵天空、高空传送条件与安全到达方案 | 📐 |
| [allay-storm-visual-development-order.md](allay-storm-visual-development-order.md) | 美术与裂口方案统一开发顺序、逐项依赖、交付边界和验证节奏 | 📐 |
| [shaderpack-integration.md](shaderpack-integration.md) | 通用光影会话、原子彩光候选、八包矩阵与顺序1～2探针 | 🟡 |
| [allay-particle-vanilla-alignment.md](allay-particle-vanilla-alignment.md) | 原版粒子视觉对齐范围和验收项 | 🟡 |
| [g-self-design.md](g-self-design.md) | G-self/Iris shaderpack 的 MODEL 粒子接入 | 🟡 |
| [trickster-stock-ticker-fix.md](trickster-stock-ticker-fix.md) | 晶结组件相等性与实际仓库出库回归 | ✅ |
| [testing.md](testing.md) | 单元、GameTest、GPU 和 benchmark 运行入口 | 🟡 |

`benchmarks/` 只保留影响实现选择的 Markdown 结论。原始数据、截图、测试报告和完整日志统一输出到忽略的 `build/benchmarks/`；运行器、元数据及复现规则见 [性能结果规则](benchmarks/README.md)。日期报告表示当时的测量，当前验证以实际运行结果为准。

## 维护规则

- 一个事实只保留一个主文档；其他文档只链接过去。
- “已实现”和“计划”必须分开写，计划不能描述成现状。
- 改动文档时至少更新：状态、代码入口、配置/命令、验证结果。
- 配置键以 `ClientConfig`/`ServerConfig` 和生成的 TOML 为准；`particles.hexParticleRedirect` 控制粒子接管，`particles.hexPatternRedirect` 控制图案渲染，两者是独立设置。
- 历史结论只在能解释现有约束时保留一句，不保留逐条问答和重复时间线。
