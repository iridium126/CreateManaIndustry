# 旧 Hex 清理与 GPU 效率优化路线（2026-10-10）

本次已完成旧 Hex 图案清理。MODEL/包裹部分是后续优化分析，尚未部署活动索引、分离更新核、局部睡眠或新运动模式。

后续已实现同代 MODEL/STORM 活动索引及攻击目标修复，见 [实现与验证](gpu-model-rendering.md)。本文其余优化仍为路线分析。

约束：大混合池、MODEL 低占比是主要场景。所有 MODEL，包括未来使用普通重力/风力/阻力/碰撞运动的 MODEL，继续保留 HP、回血、受击、尸体和永久生命周期；只区分运动方式。包裹优化以物理 GPU 成本为重点，完整模拟池拆分不是当前优先方向。

## 已完成的清理

- 删除 `HexPatternRuntime`、`HexPatternBuffers` 及其生命周期/准入/旧绘制方法。
- 删除旧 reconcile、prepare、vertex/fragment 与 pattern state/color/update GLSL，共七个资源；保留共享后端的 `hex_noise.glsl` 和 `hex_shared_*`。
- 普通 update 不再声明/访问旧 Hex 输入和 live SSBO；普通程序集不再编译/持有两套旧 compute 与一个旧图案 render program。共享后端按需独立编译。
- 删除旧 HEX_PATTERN 材质、type 和透明计数；PACKAGE material=5、type=6 保持原值，不重编号现有类型。
- 通用排序只保留 MODEL/ALPHA 两分区，桶数 1,024 → 512；间接命令七条 → 六条，原 0–5 的偏移不变。参数、metadata、哨兵测试改为从 Java 常量生成尺寸。
- 删除仅服务旧图案的 GPU pigment 适配器/反射；普通 HexSpecs 继续调用实际 ColorProvider，保持同样的颜色采样/通道转换。
- 移除旧图案专属测试，`validateHexGpu` 依赖当前 `validateHexSharedGpu`；普通粒子的压缩、死亡链、身份、重载、像素/状态恢复及排序测试保留。新增 class/resource 缺席与稳定 ABI 检查。

布局缩减可以确定少分配 4,096 B histogram/offset backing（各减少 512×4 B），减少 20 B/通用 command bank 与 MODEL shadow command buffer；不宣称这就产生显著显存收益。删除三个冗余 program 及旧 update 绑定是更直接的资源/编译改善。512 桶减少无用的扫描工作，但没有与相同 512-key 输入的旧 1,024 桶做严格性能 A/B，不报告加速倍率。

## MODEL：保留共池状态，按访问需求与运动分类

### 优先级 1：同代 MODEL 与 STORM 活动索引

当前 shadow collect、hit、stormpos、wavecontact 扫描当前提交池，gridbuild 扫描上一提交池；大多数线程读出 header 后发现自己不是目标类型。已测得：200 万存活记录、100 MODEL 的阴影准备 GPU P95 由全池 0.530432 ms 降为预建索引 0.020480 ms，查询端少约 0.510 ms；CPU 几乎不变。此数据未包含索引维护，也不是最终绘制时间。

采用两个共享集合：

| 集合 | 收集对象 | 消费者 |
| --- | --- | --- |
| MODEL_ALL | 全部 MODEL，包括死亡动画中的尸体 | 阴影、命中查询（再过滤 HP）、MODEL 专属诊断 |
| STORM_ALL | header 开启 Storm 的 MODEL，保持当前尸体成员语义 | gridbuild、stormpos、wavecontact、Storm 专属运动/成员查询 |

不按材质重新复制 64 B 模拟记录。列表记录 pool slot，并绑定所指 pool bank、generation 和精确 GPU count；不能用主视锥列表。正常 MODEL 不加入 STORM_ALL，但仍加入 MODEL_ALL，保留实体行为。

实现顺序建议：

1. 在现有 update/emit 写出槽位时顺带登记角色；覆盖 GPU 新生、block_emit/扩展类型、压缩、死亡/退役和失败提交。不新增每帧独立全池分类扫描。
2. 两代索引随两代 pool 发布。gridbuild 使用读池索引；shadow/hit/stormpos/wavecontact 使用当前已提交索引。出生帧必须可见，失败帧不能发布半成品。
3. 用现有 prepare_dispatch/capture 的合适阶段整理角色计数和 indirect args；只有在所有输出写入之后发布。多个查询共用索引，并保持零数量/溢出的安全路径。
4. 稠密时连续扫描回退。已测全为 MODEL 时索引 GPU 0.638976 ms，连续扫描 0.607232 ms，索引约慢 5.2%；密度阈值需补测，不能把索引当作所有场景默认最快。

若第一阶段为降低迁移风险选择在 keygen 登记当前 MODEL，必须放在视锥裁剪之前；这种索引还需覆盖未执行 keygen 的成功提交，且不足以单独解决下一帧 gridbuild 的 STORM 访问。最终维护成本和多消费者总收益必须一起测量。

### 优先级 2：轻量公共更新与 Storm 专用运动核

当前 update 同时包含基础积分、MODEL HP/伤害/尸体、Storm servo、27-cell separation、玩家排斥、wave corridor、whisker/collision navigation、网络纠偏等。即使少数线程执行 Storm，编译后的寄存器/局部内存占用仍可能限制所有线程的 occupancy；这是源码推断，需要实际 kernel/编译指标验证。

候选结构：

- 公共轻量 kernel 更新普通粒子和普通运动 MODEL，保留 MODEL 的回血、伤害、尸体拖曳、永久生命周期、姿态与身份。
- Storm kernel 按 STORM_ALL 处理 Storm MODEL，复用相同的 HP/伤害/死亡写出逻辑，增加其专属运动。普通 kernel 对这些输入快速跳过，避免重复推进。
- 两者读取同一不可变上一代 pool，共用输出分配器，把结果写入同一输出池。全部运动/死亡链完成后，再执行 emit、剔除和绘制提交；不得双重推进 age、HP 或应用两次伤害/摩擦。

这是按计算负载分 kernel，不是按渲染材质建立完整新模拟池。所有 MODEL 继续使用现有实体生命周期；未来普通运动 MODEL 不需要另建有限寿命/无 HP 模式。当前非 Storm MODEL 已通过 `basic_motion.glsl` 执行重力、加速度、风力和阻力，只是公共 kernel 仍带有 Storm 重路径。

判定是否启用应比较现有单核与分核的完整 GPU 时长，包括新增 dispatch/barrier、角色登记、空间索引和所有出生/死亡输出。CPU 提交可能略增，因此仍共用帧调度与状态保存边界。没有 Storm 的帧不能提交空重型链路；不要增加 CPU 数量读回来判断是否有 Storm。

### 优先级 3：Storm 邻域局部性和重复查询

当前 grid 是 16,384 桶的 pool-slot 链表，cell size=2.5，逐成员查询 27 个邻接 cell；每成员最多访问 256 个节点。哈希碰撞会引入远处成员和随机跳转，并消耗访问预算。

候选优化为只对 STORM_ALL 建紧凑空间索引：成员按 cell/hash 分组，bucket 有连续区间，并保存真实 cell 坐标，减少无关碰撞与跨池跳转。不能简单删除落在不同真实 cell 的节点而改变原访问预算下的采样顺序；密集/碰撞场景必须验证 separation 与视觉稳定性。先比较直方图/散射构建与 linked-list 的全链路成本，不直接移植普通透明 radix 排序。

在索引后再考虑融合 stormpos 与 wavecontact 等小型查询，让一次读取服务多个结果队列。它们的周期、容量、独立确认和回读 ring 语义不同；融合必须保留各自事件，不以“减少一个 dispatch”替代正确性。hit/model shadow 的尺寸计算可复用共同函数，但影子列表、命中结果和主排序必须保持独立。

### 优先级 4：MODEL 专属辅助容量与可见绘制

阴影列表当前按普通池 capacity 分配；200 万容量约 15.38 MiB，即使 MODEL 很少也保留。可按安全 MODEL 峰值按需增长，保留 GPU 在途 bank 与溢出扫描/回退；这无需移动模拟记录。STORM next/map 等 scratch 也可逐步改为 Storm 紧凑容量，保留成员 ID → pool slot 的独立映射。

主 MODEL/ALPHA 已分区，body/ghost/carrier 已有各自有效范围。不要期待拆池自动减少 MODEL 顶点或透明排序。若实际计时显示顶点/片元成为主成本，再评估无效 carrier/ghost 工作、共享姿态计算、mesh LOD 或远距离动画策略；当前任务没有授权简化视觉，这些需要明确效果约束后再采用。

## 包裹：优化物理，不优先搬渲染记录

已有独立物理域、稳定 ID 活动集、整场景睡眠、移动结构候选及按 domain 版本发布。它们继续保留。65,536 个持续驱动 body 的完整物理夹具 GPU P95 约 7.96 ms，而已证明静止场景睡眠约 0.256 ms；这些是不同夹具与完整物理步骤的测量，不能当作当前游戏整帧份额或直接推算 FPS。

| 优先方向 | 当前限制 | 建议与正确性条件 |
| --- | --- | --- |
| 局部睡眠/活动 island | 目前只在整个场景静止时跳过重求解；少数运动会让其他静止 body 继续求解 | 独立接触/支撑 island 的保守睡眠；新 body、支撑移除、几何/force/移动结构变化及扫入的运动体必须唤醒。环境、burn/health、journal/ACK、history 仍推进 |
| 移动结构空间候选 | 已有候选，但每个结构仍分类活动 body 范围，结构多时存在 N×M 扫描 | 一次空间 join 生成 body–moving pairs，并按结构复用 carry/contact 候选；保留 swept bounds、未知姿态安全回退与父结构规则 |
| grid/接触/支撑局部性 | 每轮接触及四轮 cleanup 重建 grid，支撑修正能引入新接触 | 先测各阶段，再评估缓存/增量候选与连续 cell 范围；必须覆盖 solver 位移边界，不能直接复用已经过期的 grid，也不直接减少迭代/CCD |
| domain/scratch 带宽 | stable slots/state/history 及中间结果重复读写 | 测量各 pass 的 read/write、原地更新合法性和未变化 domain；维护 bank、稳定 ID、未选中体与支撑祖先，不为省复制破坏 Jacobi 依赖 |

独立包裹渲染快照仍可作为后续候选，但不是物理 GPU 成本的解决方案。暂不开展完整渲染池迁移。

## 推荐实施顺序与验收

先实现并测量 **MODEL_ALL/STORM_ALL 同代索引**，再验证 **公共轻量更新 + Storm 专用核**；随后按 GPU 分项测量决定 Storm 空间索引/融合查询。包裹单独按实际局部静止率与 moving 数量选择 island 睡眠或空间 pair 优化，不把这两条路线绑定成一次大改。

基准固定总有效存活与 MODEL/Storm 比例：普通-only、MODEL-only、Storm-only、2M普通+100/10K MODEL、含普通运动 MODEL 的混合、Storm+非Storm MODEL、MODEL 高占比回退；包裹覆盖静止/驱动混合、稀疏稳定 ID、多移动结构与堆叠/高速 CCD。记录维护成本、整个 update/query/阴影/绘制 GPU P50/P95、CPU wall/thread CPU、寄存器/局部 spill（工具可用时）、分配/上传和显存。

正确性门禁包含出生、压缩、失败提交、HP/回血/击退、永久生存、尸体/poof、稳定身份、同帧主/阴影、主视锥外、carrier、wave 去重/ACK、网络纠偏、重载/切世界。普通运动 MODEL 的实体行为必须与当前非 Storm MODEL 相同，只有运动参数/算法可以变化。实机视觉验收继续由用户完成。

## 本次清理验证

Java 572 项，零失败/错误/跳过；共享 Hex GPU 5,287 项、普通完整粒子 GPU 89,135 项、包裹 GPU 14,675,334 项、Iris 包裹 26,733 项、Iris MODEL 4,133,200 项、共享 Hex Iris 5,290 项全部通过。旧图案专属断言移除，因此总数降低不代表关闭了保留路径的检查。大规模排序覆盖至 2,000,000 记录，检查顺序、payload、唯一性、尾部哨兵与单桶/混合分布；结果见同目录日志和 CSV。

证据：[benchmarks/hex-legacy-cleanup-2026-10-10](benchmarks/hex-legacy-cleanup-2026-10-10/)。模型索引测量依据见 [分池实测报告](gpu-package-model-pool-decision-2026-10-10.md)。
