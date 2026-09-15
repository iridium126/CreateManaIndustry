# Create Mana Industry 开发文档

本目录只记录实现契约、当前状态、入口文件和验证方式。详细历史讨论、重复的评审过程和已否决方案不再作为开发依据。

## 阅读顺序

1. 先看本文档和对应主题文档的“状态”部分。
2. 行为以当前源码、资源和测试为准；文档只解释代码不易表达的协议和边界。
3. 标记含义：✅ 已实现；🟡 已实现但仍需游戏内验证；📐 设计/待实施；⚠ 已知缺口。

## 文档索引

| 文档 | 内容 | 状态 |
| --- | --- | --- |
| [particle-engine-dev.md](particle-engine-dev.md) | GPU 粒子池、发射器、排序、碰撞、Hexcasting 与 shaderpack | 🟡 |
| [allay-dimension-dev.md](allay-dimension-dev.md) | ALLVR 维度总览、坐标、加载、客户端渲染入口 | 🟡 |
| [allay-dimension-worldgen.md](allay-dimension-worldgen.md) | ALLVR 世界生成和 Y 分带 | ✅ |
| [allay-dimension-persistence-plan.md](allay-dimension-persistence-plan.md) | Cube 持久化格式、IO 和恢复约束 | 🟡 |
| [allay-dimension-sodium-rendering-parity-plan.md](allay-dimension-sodium-rendering-parity-plan.md) | Sodium 原生 section 适配路线 | 📐 |
| [allay-dimension-voxy-lod-integration-plan.md](allay-dimension-voxy-lod-integration-plan.md) | Voxy 远景接入路线 | 📐 |
| [allay-dimension-implementation-review-fix-plan.md](allay-dimension-implementation-review-fix-plan.md) | 未关闭的实现评审问题 | ⚠ |
| [allay-storm-sync.md](allay-storm-sync.md) | Allay Storm 服务端权威与客户端 GPU 同步 | 🟡 |
| [allay-storm-ai.md](allay-storm-ai.md) | Allay Storm 波次、追击和伤害规则 | 🟡 |
| [allay-particle-vanilla-alignment.md](allay-particle-vanilla-alignment.md) | 原版粒子视觉对齐范围和验收项 | 🟡 |
| [g-self-design.md](g-self-design.md) | G-self/Iris shaderpack 的 MODEL 粒子接入 | 🟡 |

## 维护规则

- 一个事实只保留一个主文档；其他文档只链接过去。
- “已实现”和“计划”必须分开写，计划不能描述成现状。
- 改动文档时至少更新：状态、代码入口、配置/命令、验证结果。
- 配置键以 `ClientConfig`/`ServerConfig` 和生成的 TOML 为准；当前 Hexcasting 客户端键为 `particles.hexParticleRedirect`，不保留旧键兼容说明。
- 历史结论只在能解释现有约束时保留一句，不保留逐条问答和重复时间线。
