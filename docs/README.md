# Create Mana Industry 开发文档

只记录实现契约、当前状态、入口文件与验证方式。行为以当前源码、资源和测试为准；历史讨论、评审过程、逐版本测量和已否决方案不作为开发依据，需要时从 Git 历史提取。

状态：✅ 已实现；🟡 仍需游戏内验证；🚧 进行中；📐 设计/待实施。

## 入口

| 文档 | 内容 |
| --- | --- |
| [构建与 Gradle 职责](building.md) | 依赖、缓存、重映射、打包 |
| [测试与验证入口](testing.md) | 单元、GameTest、GPU 与 benchmark 命令 |
| [性能结论与复现](benchmarks/README.md) | 测量规则；原始 CSV/日志只写入忽略的 `build/benchmarks/` |
| [隔离客户端验收清单](gpu-runtime-client-acceptance.md) | 由用户执行的全功能检查 🚧 |

## 主题索引

| 主题 | 文档 |
| --- | --- |
| GPU 粒子引擎 | [当前引擎](particle-engine-dev.md) 🟡、[安装与回退](particle-engine-install.md)、[性能](particle-engine-performance.md) 🟡、[阴影质量](particle-shadow-quality.md) 🟡、[阴影补全](particle-shadow-completion.md) 🟡、[光影包](shaderpack-integration.md) 🟡、[G-self](g-self-design.md) 🟡 |
| 粒子视觉 | [悦灵对齐](allay-particle-vanilla-alignment.md)、[樱花对齐](cherry-particle-vanilla-alignment.md)、[羽毛](allay-feather-particles.md)、[金粒子](allay-gold-particles.md) |
| GPU 运行时 | [架构与分批状态](gpu-runtime-architecture-plan.md) 🚧、[当前性能报告](benchmarks/gpu-runtime-architecture.md)、[第四批状态](gpu-runtime-stage4.md)、[RT 单步状态](rt-single-step-status.md) 🚧、[Hex 图案接管](hex-pattern-gpu.md) 🟡 |
| GPU 包裹 | [总览](gpu-packages.md)、[协议 15](gpu-package-protocol-15.md) 🚧、[轻量记录](gpu-light-package-records.md)、[观察者](gpu-package-observers.md)、[锁链权威](gpu-chain-authority.md)、[父姿态帧](gpu-package-chain-frames.md)、[碰撞与历史](gpu-package-collision.md)、[性能诊断](gpu-package-performance.md)、[环境存储](gpu-package-environment-14.md)、[方块发射器](gpu-block-emitters.md)、[MODEL 绘制](gpu-model-rendering.md) |
| ALLVR 维度 | [总览](allay-dimension-dev.md) 🟡、[世界生成](allay-dimension-worldgen.md) ✅、[持久化](allay-dimension-persistence-plan.md) 🟡、[Sodium 适配](allay-dimension-sodium-rendering-parity-plan.md) 📐、[Voxy LOD](allay-dimension-voxy-lod-integration-plan.md) 📐、[验证](allay-dimension-validation.md) |
| 悦灵风暴 | [服务端同步](allay-storm-sync.md)、[AI 与参数](allay-storm-ai.md)、[验证入口](allay-storm-validation.md)、[裂口](allay-storm-rift.md)、[LOD](allay-storm-lod.md) ✅、[后续美术](allay-storm-visual-development-order.md) 📐、[庇护所](allay-sanctuary.md) |
| 集成与玩法 | [Hexcasting/Trickster 契约](hexcasting-trickster-interop-dev.md) 🟡、[Markov Junior](markov-junior.md)、[世界树设计](world-tree-design.md)、[世界树实现](world-tree-implementation.md)、[Ponder](ponder-workshop.md)、[燃料罐](fuel-tank.md) |

## 维护规则

- 一个事实只保留一个主文档；其它文档只链接过去。
- 「已实现」与「计划」分开写，计划不能描述成现状。
- 改动文档时至少更新状态、代码入口、配置/命令与验证结果。
- 配置键以 `ClientConfig`/`ServerConfig` 及生成的 TOML 为准；`hexcasting.hexParticleRedirect` 与 `hexcasting.hexPatternRedirect` 是独立设置。
- 历史结论只在能解释现有约束时保留一句，不保留逐条问答与时间线。
- 测量数据重新采集后更新对应主文档，不新增日期日志文档。
