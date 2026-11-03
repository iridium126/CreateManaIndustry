# 实机第二轮归因与世界阶段诊断

本轮实现数据冻结、世界渲染范围补全和状态准备细分。没有新增已证明提速的生产算法，20% FPS 目标尚未达成。未启动 Minecraft；实机操作由用户执行。

## 输入证据

四窗口及其全部同名前缀文件、实际选项 JSON 已复制至 `build/analysis/frame-attribution-20261103-r2/inputs`，SHA-256 清单为同目录上级 `input-sha256.json`，逐帧归因汇总为 `analysis.json`。原始输入未改写。可通过 `scripts/particles/freeze_frame_attribution.py` 将显式指定窗口冻结到新的目录，已有目录拒绝覆盖。

| 窗口 UUID 前缀 | 档位 | 帧数 | 平均 FPS | GPU p50 | compute p50 | 模型投影阴影 p50 |
|---|---|---:|---:|---:|---:|---:|
| 0b658ff7 | FULL | 1332 | 44.37 | 22.479 ms | 4.092 ms | 0.301 ms |
| 29fd4d0f | FULL | 1399 | 46.61 | 21.337 ms | 3.753 ms | 0.109 ms |
| 3b03db9c | OFF | 1379 | 45.95 | 21.643 ms | 3.873 ms | 未执行 |
| 54f48911 | OFF | 1409 | 46.96 | 21.153 ms | 3.593 ms | 未执行 |

四窗均约30秒，无 invalid_reason、无 GPU 跳样，分辨率2560×1506，同进程、相机姿态和选项摘要一致，模拟MODEL均为65529。实际限帧240、VSync关闭，没有接近限帧上限。

FULL有效路径为投影阴影开启、原生接收；OFF路径明确为投影阴影关闭、Complementary原生接收回退。因此开关确实生效，但省去的是约0.1–0.3ms模型投影范围，不是整个光影包阴影处理。

FULL两窗可见身体中位数1750和257，OFF为358和298；四窗攻击契约分别出现63、55、56、57种，活动波次计数中位数39、39、41、38。场景在推进，不能把FULL/OFF差值当作受控单因素收益。OFF缺失投影提交读回仍是未知值-1，不能把CSV中的未知值冒充已读回的零数量。

FULL同帧扣除compute、主合并绘制和模型阴影后的余量p50分别17.354、17.188ms。此余量包含世界渲染、光影处理、未插桩CMI工作及等待；不能全归给光影包。第二FULL窗平均FPS提升20%需节省约3.58ms，而MODEL更新约2.12ms、输入状态准备约0.62ms，不能承诺只优化其中一个子项就能达标。

## 新增诊断范围

- `world.render`：GameRenderer世界渲染调用总范围，在既有主场景帧入口后开始；其Mixin随Iris存在启用。无Iris时缺失此范围保留未知。
- `world.section.<name>`：LevelRenderer本体各次`popPush`之间的顺序提交区间，包含entities、blockentities及后续flush等分段；名称不代表该类对象独占GPU成本。第一次边界前的工作归入未覆盖余量。
- `world.terrain.solid/cutout_mipped/cutout/translucent/tripwire/other`：地形层调用，可能嵌套于顺序区间。Sodium/Iris最终运行时hook生效仍需用户客户端验证。
- `iris.shadow.whole`：整个Iris阴影入口，包含CMI模型投影子范围。
- `world.effect.whole.<id>`：CMI世界效果整个调用，包括准备及状态恢复；保留原`world.effect.<id>`内层绘制计时。
- `starfall.prepare.ensure/upload/clear/gather_copy/state_copy`：分别标记资源准备和初始清理、版本化上传、每帧恢复scratch/完成位清零、gather输入复制、持久状态复制及少量控制复位。

全部新增时间查询仅在frames观察器活动时提交，不计入自动节流预算。`cpu_ranges/gpu_ranges`保留同标签执行次数；递归进入相同手动scope时合并在外层，避免重入计时冲突。未采集时保留原功能操作。

元数据新增`stage_hierarchy`解释父子关系。分析器只从整帧减去`world.render`得到世界外时间，或从世界总时间减去已报告的顺序section，绝不将地形、阴影和世界效果子范围再加到section上。缺失section或hook不能当成零工作，负余量需检查边界而非静默修正。

## 准备阶段候选审查

本轮没有找到可以仅凭当前攻击类型安全跳过的持久区间：

| 操作 | 消费者／保留要求 | 本轮决策 |
|---|---|---|
| COLUMN_ARC与完成位清零 | column_motion、column_recovery、dome_completion；失败帧可重复代次，必须清理候选scratch和完成位 | 保留；两段虽相邻，可融合减少一次GL提交，但尚无完整链净收益证据，不作为生产优化交付 |
| GATHER_SOURCE到GATHER_COUNTS复制 | 更新和Dome收集阶段的当前输入／计数初始化 | 保留，不能跨代复用旧输入 |
| DOME_FLOW_STATE复制 | 跨帧流状态及四槽身份检查，发生槽变化还会覆盖对应输入 | 保留，不用CPU攻击类型替代槽状态 |
| DOME_PATH_BASE至OUTER_ARRIVALS复制 | dome_path请求、NAV_EXTRA、storm_depenetration队列和调度控制；存在跨帧积压及尸体／恢复消费者 | 保留，不能仅因当前看似没有Dome就丢弃历史任务 |
| OUTER_ARRIVALS | 外围成员到达状态 | 保持既有hasOuter条件，未扩大省略范围 |

这些是审查结果，不是候选性能测试成绩。没有部署未经完整链A/B证明的省略初始化方案，因此不宣称覆盖了新算法的物理差分验证；现有功能操作顺序、范围和barrier保持原样。先通过细分计时找到prepare内部真实成本，再决定是否值得实现稀疏复制或其他更复杂的布局。

## 用户下一轮

使用当前源码启动：

```powershell
.\gradlew runClient --no-configuration-cache
```

保持FULL、Complementary水平场景、autoThrottle=false、VSync关闭，帧率滑块设“不限制”；预热后执行`/cmi particle profile backend production`、`/cmi particle profile off`。固定相机、初态和攻击过程，采两次`/cmi particle profile frames 60`，每次关闭聊天并等待saved后再采下一次。不足1000帧则延长；不再重复OFF实验。

返回两个窗口所有同名前缀文件、引用选项快照和debug.log。重点检查`world.render`、`world.section.*`、`iris.shadow.whole`和`starfall.prepare.*`是否出现。缺失hook需先修复诊断，不能据此判断该阶段免费。新增诊断开销与上一轮不同，不把本轮FPS变化作为优化收益。

收到数据后按实际瓶颈选择等价优化。最终20%仍需三轮独立进程、匹配A/A和A/B、尾帧及画面玩法验收。当前限制是主要整帧成本尚未细分，不是已证明粒子引擎无法达到目标。
