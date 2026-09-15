# ALLVR × Sodium 渲染对齐路线

状态：📐 适配基础已存在，完整原生 parity 尚未验收。本文只保留实施边界和验收门槛；不是当前能力清单。

## 1. 目标

让 ALLVR 的 32³ Cube 在 Sodium 的 section 构建、可见性、上传、重载和渲染生命周期中表现得像普通 chunk，同时保留 ALLVR 自己的坐标和数据所有权。不得把 Cube 伪装成共享的 vanilla `LevelChunk`，也不得让 Sodium 持有服务端 Cube。

目标顺序：

1. section 坐标与 Y 窗口正确；
2. snapshot 与 dirty/rebuild 生命周期正确；
3. model/fluid/block entity/透明 pass 行为对齐；
4. 兼容性失败可安全回退；
5. 再优化批处理、遮挡和远景。

## 2. 当前已有组件

- `AllvrSodiumBridge`：队列化 add/remove/replace/rebuild、Cube 边界 dirty 和预算处理。
- `AllvrSodiumCoordinateSpace`：绝对 section、Cube、Cube 内 section 和 2×2×2 映射。
- `AllvrSodiumSectionSource`/`Snapshot`：从客户端 Cube cache 提供构建输入。
- `AllvrSodiumSectionLifecycle`：section 注册与生命周期状态。
- `AllvrSodiumReloadListener`：资源重载后的刷新入口。
- `AllvrSodiumCompatibilityProbe`、`SodiumApi_0813_1211` 和 Sodium mixin：版本/ABI 门禁与注入点。

这些类说明“适配层存在”，不等于所有 Sodium pass 已经与原版一致。

## 3. 坐标契约

一个 Cube 覆盖 `2×2×2` 个 16³ section。Sodium 可见 Y 由 `AllvrRenderYWindow` 限制；窗口移动必须先停止旧 section 的发布，再安装新窗口，避免旧 Y 层与新层混绘。

任何 key 必须明确是：

- 绝对 block/section 坐标；
- Cube key；或
- Sodium 的 virtual section key。

不能把 Voxy virtual key、Sodium section key 和 Cube key 混用。forget、dirty、rebuild 的入参在适配边界处完成显式转换。

## 4. 计划阶段

| 阶段 | 交付 | 通过条件 |
| --- | --- | --- |
| P0 | capability/ABI 门禁 | 缺少 Sodium、版本不符或符号不全时不应用相关 mixin |
| P1 | 2×2×2 section 生命周期 | add/remove/replace/rebuild 无重复注册、悬挂 section 或漏清理 |
| P2 | 原版 block model/fluid | 普通方块、含水方块、透明模型与原版 pass 选择一致 |
| P3 | block entity/动态更新 | 方块、光照、BE 和资源重载不会使用旧 snapshot |
| P4 | 可见性/遮挡 | 相机移动、窗口跨越和边界邻居不漏绘、不双绘 |
| P5 | 性能 | 队列/worker/GPU 上传有上限，失败可重试且不无限增长 |
| P6 | 回退和发布 | 未通过探针时可回到 vanilla/client cache 路径，日志可定位原因 |

## 5. 必须先解决的风险

- Sodium 私有 ABI 变化不能只靠“mod 存在”判断；必须在 mixin 应用前完成版本和符号验证。
- section 构建输入要绑定 resource revision、Cube revision 和 level epoch。
- block model、fluid、BE 不能因缺少一个 descriptor 而全部跳过，也不能因含水而把模型与流体当成互斥分支。
- opaque、cutout、translucent、shadow、破坏覆盖要有真实 pass 归属，不能只取消 vanilla draw。
- section/remove 与 GPU handle 释放要有明确 owner；换维度、资源重载、关闭时必须等待后台结果失效。

详细未关闭问题统一见 [实现评审清单](allay-dimension-implementation-review-fix-plan.md)。

## 6. 验收矩阵

- vanilla、Sodium 正常版本、Sodium 缺失和未知版本启动。
- 中央 chunk 带、Cube 带、`Y=-128/384` 边界和窗口跨越。
- 方块放置/破坏、邻居更新、含水方块、透明方块、BE、光照和资源重载。
- 相机快速移动、视距变化、服务器延迟、换维度、退出重进。
- 与 Voxy 同时存在时确认双方只消费自己的 key 和资源；Voxy 远景另见 [Voxy 路线](allay-dimension-voxy-lod-integration-plan.md)。
