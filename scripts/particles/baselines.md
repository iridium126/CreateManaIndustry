# 本地冻结基线

冻结输入位于项目根目录的 `.local/particle-baselines/`，由 `.gitignore`
忽略，独立于会被 Gradle clean 删除的 `build/`。它们不是运行时资源，不进入
分发 JAR。新 clone 不包含这些输入；复现历史 A/B 前须复制原快照或解压独立
备份，不能用当前源码重新生成后继续冒充旧基线。

| 相对本地目录 | 用途 |
|---|---|
| `reference/shadow-quality-20261102/` | 七档阴影优化前的 2780 文件冻结基线，附逐文件 SHA-256 manifest |
| `reference/model-merge/` | 原合并编译器与材质验证参考 |
| `reference/model-draw-20261030/` | 关键帧、几何及动画实验的交付基线 |
| `reference/shader-efficiency-20261030-r2/` | 实体比较折叠与计算域实验基线 |
| `reference/feather-merge-20261031/` | 羽毛合并路径实验基线 |
| `reference/shader-efficiency-20261031-r4/` | 只读攻击／邻居／绘制复用实验，1960 项冻结输入；本轮候选均未接入生产 |
| `reference/radix_*.comp` | 历史排序与扫描候选 |
| `index-reference/` | 包裹三模式索引比较输入 |

2026-10-31 迁移保留了 reference 下 665 个文件和 index-reference 下 58 个文件，
逐文件 SHA-256 未变。原已暂存的新基线从索引移除，原已跟踪的历史基线
记录为删除；其他索引条目保持。迁移清单在本地
`build/baseline-migration-20261031.json`，独立备份为
`build/distributions/particle-baselines-20261031.zip`。

验证脚本继续从根目录运行；各 prepare 任务读取新目录并校验原 manifest，
只向 `build/` 生成改名后的验证类。`scripts/particles/experiments/` 保留实验
生成逻辑和撤回候选，独立于这些历史基线。日常 `build` 不需要本地冻结输入；
依赖基线的历史差分任务需要它们。

现有历史结果和分发 provenance 保持原样，其旧路径描述的是当时的采集环境。
重新运行时，元数据记录新路径；不要覆盖旧采样来隐藏输入迁移。

本轮历史包裹索引 smoke 的编译和资源读取成功，但首个 `65/aligned_stack/linked`
场景质量断言失败，有效数为 0。冻结文件未改写，根因尚未定位；该入口不能视为
当前版本已验证的性能基准。详细记录见审阅文档与本地迁移验证日志。

R5 邻居实验的新冻结基线位于 `.local/particle-baselines/reference/neighbor-r5-20261031`。
`manifest.json` 保留实验开始时的工作区字节摘要；`initial-index-tree` 与对应 manifest
保留原暂存树的 Git blob 表示，`initial-staged.patch` 用于核对暂存内容未改变。
包含当前 5 窗口日志、上一份 13 窗口日志及 11 份实际光影上下文快照。
实验结果单独位于 `build/benchmarks/neighbor-r5`，打包后进入新的 R5 分发文件；
不覆盖 R4 的失败记录或任何历史基线。R5 的流式完整查询参考不以旧截断前缀作为正确性标准。
