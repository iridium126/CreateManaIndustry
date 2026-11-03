# R4：无风暴对照、只读缓存筛选与定向提交诊断

状态：无风暴对照已冻结并分析；只读攻击上下文候选未通过完整链状态一致性门槛，不进入生产。新增提交边界诊断，客户端运行和画面验证待用户执行。当前没有新的实机优化成绩，20%目标尚未达成，也尚不能宣布不可达。

## 对照证据与方向修正

新增窗口 `4000b0e2-8473-4c67-a4e5-0158708987dd` 和有风暴第二窗 `0add3c84-1ede-489e-a747-b725b6c4694e` 的原始文件、引用选项已复制到 `build/analysis/frame-attribution-20261103-r4/inputs`。`input-sha256.json` 保留原路径与逐文件SHA-256；`analysis.json` 是冻结时的分析，`comparison-complete-ranges.json` 是加入查询完整性检查后的同设置对照。有风暴第一窗保留在R3冻结目录，不覆盖历史证据。

两窗构建输入身份均为 `d2584c0d3688fc5a39028d73216b43b5a95343f50562aa775cce0d960e7796f0`，Complementary Unbound 5.9.3、FULL、2560×1506、同一选项摘要。相机位置、俯仰和FOV相同；偏航分别为−537.24560546875°和−177.24560546875°，相差360°。比较工具现在按圆周差比较偏航，并在单窗口内展开偏航，跨越±180°不会被误判为真实转向。真实转向仍拒绝配对。

| 范围 | 有风暴第二窗 | 无风暴 |
| --- | ---: | ---: |
| 平均FPS | 45.4474 | 58.9522 |
| 世界GPU p50 ms | 21.8235 | 16.7342 |
| terrain顺序区间 | 8.2084 | 8.6364 |
| sky顺序区间 | 5.1272 | 1.2872 |
| solid_particles顺序区间 | 3.1508 | 1.7705 |
| 粒子计算（嵌套范围） | 3.4990 | 0.0020 |
| 整个Iris阴影 | 1.4746 | 1.1274 |
| deferred | 1.467 | 1.415 |
| composite | 2.491 | 2.482 |

这些范围存在嵌套，不相加。无风暴1769帧、有风暴1364帧，各约30秒，无风暴来自另一进程。其数量字段全部为−1，仍表示未知，不能凭此证明粒子数为零，也不能通过正式收益比较工具的人口匹配门槛。窗口名称“无风暴”来自用户场景说明。

**修正R3的研究方向：目前没有证据表明地形是风暴引起掉帧的主要原因。** 地形耗时未随风暴移除而下降。重点改为约3.50ms计算及 `solid_particles` 顺序区间约1.38ms增量；后者不代表CMI粒子独占。平均帧时间关联差额为5.0406ms，达到20% FPS需节省3.6672ms，约为该差额的73%。这是定位预算，不是严格因果成本、可消除比例或性能上限。

## 已实施的定向诊断

沿用 `frames` 命令及同帧异步时间戳，不新增阻塞读回、`glFinish` 或生产预算扣款。新增范围只在整帧采集激活时发出查询：

| 标签 | 边界及包含关系 |
| --- | --- |
| `solid_particles.native.particles.render` | 该顺序区间内真实NeoForge五参数 `ParticleEngine.render`；不把其他顺序区间的粒子提交混入 |
| `solid_particles.native.batch.all` | 该顺序区间内 `BufferSource.endBatch()` |
| `solid_particles.native.batch.type` | 该顺序区间内 `endBatch(RenderType)`；可能嵌套于all，不与all相加 |
| `iris.translucents.begin` | 整个Iris `beginTranslucents`，包含深度复制和既有deferred计时范围 |
| `iris.translucents.depth-copy` | `RenderTargets.copyPreTranslucentDepth()`；是上一范围子项 |
| `irisveil.before-deferred.callbacks` | IrisVeil桥实际 `Frame.renderCallbacks`，包含其注册回调；不假定它与CMI模型绘制互斥 |
| `submission.pack.merge-whole` | CMI模型合并入口，覆盖状态快照及恢复；既有 `draw.pack.merge` 是其子范围，不相加 |

批次诊断仅在 `world.section.solid_particles` 活跃时执行，复用其边界；重复调用的时间戳环为128槽，普通范围保持4槽。环满时继续正常渲染并保留CPU/GPU执行次数；如果有CPU计数而GPU计数不匹配，导出和分析均将该阶段GPU总时间视为未知，不能拿部分总和计算余量。原先仅GPU采样的生产标签（CPU次数为0）继续有效。旧窗口中 `world.terrain.other` 存在部分查询，修正后的分析不再把这些部分和当完整时间；上表关键区间完整，结论未改变。

元数据记录包含关系和次数规则。诊断范围的generation为−1，不声称拥有发布资源；原有绘制标签继续校验同帧发布代次。没有把新增父范围加入互斥成本求和。

审查合并路径后未发现已证明可删的重复绘制：成功合并通过 `markHookModelDrawn` 阻止后续MODEL重复提交；失败路径仍需回退。`ModelRenderState`、天空/MRT和纹理边界恢复不同类别状态，后续世界/Iris提交仍会消费它们。原合并计时终点确实早于外层状态恢复，所以先补完整范围，不直接删除恢复操作。是否存在实际重复回调、复制或异常同步，要结合新次数和同帧成本判断。

## 只读攻击上下文候选与拒绝原因

冻结原实现位于 `build/benchmarks/readonly-r4-20261103/baseline`，使用已有候选生成器生成 `candidates/update`，没有覆盖生产shader。缓存限于单次MODEL调用的只读攻击分配/类型查询，不缓存跨状态修改边界的可变状态；生产OpenGL及持久状态复制保持原实现。

执行完整准备、两次更新、接触、脱嵌、导航、反馈和发布链，1000／65536粒子，starfall、helix、dome_gather、dome_attack、corpse、recovery六场景，生产与候选交错采样，每组4对。比较稳定身份及输出状态；结果在 `build/benchmarks/readonly-r4-20261103/full-gate`，冻结输入有独立摘要。

完整链验证失败：`validation-failures.txt` 有4项，两个数量的 `dome_gather` 均出现原实现A/A及原实现/候选A/B位置字差异。`finished.txt` 记录执行2,398,396次检查，**不代表全部检查通过**；任务返回失败。原实现自己尚不能稳定复现，此时无法将A/B差异归咎于缓存，也无法证明候选等价。候选不进入生产，不提取失败单元的局部计时宣称收益，不安排该候选实机收益验收。

复现命令：

```powershell
python scripts/particles/freeze_shader_efficiency.py build/benchmarks/新的目录/baseline
python scripts/particles/prepare_shader_efficiency_candidates.py build/benchmarks/新的目录/baseline build/benchmarks/新的目录/candidates
.\gradlew.bat -I scripts/particles/validation.init.gradle validateShaderEfficiencyUpdateGpu --offline --no-configuration-cache -PefficiencyBaseline=build/benchmarks/新的目录/baseline/src/main/resources -PefficiencyCandidate=build/benchmarks/新的目录/candidates/update -PefficiencyFull=true -PefficiencyCounts=1000,65536 -PefficiencyScenes=starfall,helix,dome_gather,dome_attack,corpse,recovery -PefficiencySamples=4 -PbenchmarkOutput=build/benchmarks/新的目录/full-gate
```

约0.53ms持久状态复制仍保留。导航任务、恢复与脱嵌有跨帧消费者，不能按当前CPU攻击类型省掉历史状态。没有用减少粒子、频率或FULL画质填补目标差额。

## 用户下一轮：一次针对solid_particles的诊断

完整Iris/Complementary世界提交不能由当前离屏计算fixture复现。本轮交付定向诊断；无需重做FULL/OFF，也不是三轮优化收益验收。

最终诊断构建输入SHA-256为 `dfa6f6778787e2258fb28c0a3cd051fbbc4d7082eb12796085b6e42ce664cc69`。归档包与交付摘要位于 `build/performance-attribution-20261103/r4`；已检查嵌套主JAR包含该身份。开发客户端直接使用下述源码入口，不向run/mods添加归档整包。

```powershell
.\gradlew runClient --no-configuration-cache
```

1. 保持原Complementary选项、FULL、分辨率、固定相机和FOV，关闭自动节流、VSync和内外部限帧。运行 `/cmi particle profile backend production`、`/cmi particle profile off`，预热到编译/加载稳定。
2. 固定可复现的风暴数量和攻击状态，采两次 `/cmi particle profile frames 60`，每次立即关聊天且保持视角，等saved后再采下一窗；每窗至少1000帧。不要叠加其他capture或profile on。
3. 同一客户端进程、同样视角和选项，移除风暴后等效果消退，另采一次同长度窗口，供新增边界的归因对照。记录场景复位方式，不把移除风暴当优化。
4. 保留本轮JSON和同名前缀全部CSV、所引用选项JSON及debug.log，标注有/无风暴窗口。重点检查新范围执行次数、查询完整性、beginTranslucents与deferred/深度复制的包含关系，以及完整合并范围与既有合并范围的同帧差。

待存在通过正确性及完整链收益门槛的具体候选，再进行三轮独立进程的匹配A/A和A/B、每窗≥30秒且≥1000帧验收。每轮平均FPS至少20%、超过噪声、p95/p99无超出噪声的稳定退化、画面和玩法检查通过的要求不变。

## 验证范围

Gradle和离屏任务串行执行。完整构建及1027项Java测试通过；Python帧工具11项测试通过，覆盖等价偏航、真实转向、未知数量、嵌套范围与不完整查询。场景帧GPU回归通过72,764项断言；时间戳GPU回归通过标签、饱和、取消和槽重用，并验证129次同帧调用中128槽饱和时CPU/GPU次数准确。运行中未启动Minecraft。

本轮曾因把合并入口拆成包装方法触发现有生命周期字节码契约测试失败，已恢复原入口内的资源保护结构并重跑构建通过。新mixin的目标方法及IrisVeil目标签名已核对实际依赖字节码；这不等于已验证客户端变换应用、无光影/其他光影包或画面状态恢复，仍待用户运行确认。计算候选的完整链失败作为拒绝证据单独保留，未记作回归通过。
