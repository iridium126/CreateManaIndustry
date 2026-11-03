# R5：目标天空与计算链首次分歧

本轮完成证据冻结、逐帧归因、目标天空分段计时和两个候选的资格检查。**没有候选通过进入生产的门槛，没有取得新的客户端优化成绩；20%目标尚无法判断。** 目标天空裁剪因历史消费者被拒绝；只读攻击上下文缓存因完整链原实现A/A不稳定，继续保持未采用。FULL、粒子数量、模拟频率、持久状态复制和玩法接口保持原实现。

## 冻结输入与同帧结论

证据目录：`build/analysis/frame-attribution-20261103-r5`。`input-sha256.json`保存三个窗口及引用选项的来源和SHA-256；`debug-sha256.json`保存调试日志摘要，原件位于`inputs/debug.log`。`analysis.json`是冻结时的统计；新增`analysis-r5.json`和三个`*-derived.csv`提供同帧差值，空单元格表示未知。`derived-evidence-sha256.json`记录派生分析和候选拒绝证据摘要；最终计算跟踪目录的`evidence.json`保存工具及冻结原实现源码摘要。原始逐帧数量、攻击合同、提交次数、相机及阶段CSV均保留，没有用旧构建的FPS差异记优化成绩。

同一进程14224、诊断构建`dfa6f6778787e2258fb28c0a3cd051fbbc4d7082eb12796085b6e42ce664cc69`，ComplementaryUnbound_r5.9.3，源码`da43d77bf336ce3b0b6e818b48cb6146bc6679bdbaa84dbd0287a150d71f2142`，402项选项哈希`2f845d3b8761`，FULL、2560×1506、相同相机和FOV70、生产OpenGL、VSync关闭、自动节流关闭。三窗均超过30秒和1000帧。

| 窗口（frames前缀） | 帧数 | 平均FPS | 20%所需节省 |
| --- | ---: | ---: | ---: |
| 有风暴 `71f87221` | 1395 | 46.4996 | 3.5843 ms |
| 有风暴 `ea0faaf5` | 1381 | 46.0033 | 3.6229 ms |
| 无风暴 `9c1cfba3` | 1761 | 58.6720 | 不作为优化对照 |

| GPU p50（ms） | 有风暴一 | 有风暴二 | 无风暴 |
| --- | ---: | ---: | ---: |
| scene.compute | 3.3249 | 3.4918 | 0.0031 |
| update.model | 1.9517 | 2.0285 | 未采集 |
| starfall.prepare.state_copy | 0.5284 | 0.5253 | 未采集 |
| world.section.solid_particles | 3.4970 | 3.4714 | 2.1115 |
| iris.translucents.begin | 3.2143 | 3.1877 | 1.8739 |
| iris.translucents.depth-copy | 0.0799 | 0.0799 | 0.0850 |
| iris.whole-stage.deferred | 1.4438 | 1.4367 | 1.4060 |
| irisveil.before-deferred.callbacks | 0.4086 | 0.4086 | 0.4116 |
| solid_particles.native.particles.render | 0.0010 | 0.0010 | 0.0010 |
| solid_particles.native.batch.all | 0.3922 | 0.3942 | 0.3953 |
| begin扣除三个已核对互斥子项的同帧余量 | 1.3404 | 1.3394 | 0.0143 |
| solid_particles扣除begin的同帧余量 | 0.2857 | 0.2806 | 0.2365 |
| submission.pack.merge-whole扣除draw.pack.merge | 0.0113 | 0.0113 | 未知 |

表中范围嵌套，不相加；差值先逐帧计算，再取分位数。两个有风暴窗口各1395/1381帧、无风暴1761帧都有完整begin余量。分析器只使用已核对的三个子区间，新增目标天空父子标签不会重复扣除。CPU/GPU执行次数不匹配的批次仍为未知。

有风暴一的live范围145480–145904（中位145711），MODEL输入65536，body/ghost可见62–540（中位149），carrier可见0–26（中位8）；窗口二live145495–145830（中位145612），MODEL输入65536，body/ghost131–557（中位223），carrier0–72（中位10）。攻击合同字符串分别57/58种，主要starfall、少量helix，不能当作固定攻击输入A/A。合同标签是`locked-contract`，没有记录更细的GPU内部攻击阶段，不能补推。无风暴全部数量为−1，继续表示未采集，不能冒充零。该窗用于定位，不能通过正式数量匹配验收。

日志与调用位置支持余量来自NativeSkyView，但旧窗口没有直接计时，**1.34ms仍不是已经独立测出的目标天空成本**。即使理想地消除它，也小于3.58–3.62ms目标预算。地形和原生粒子/批次没有解释主要增量，不继续扩大这些范围的插桩。

## 按需目标天空计时

复用ParticleFrameStages异步时间戳，仅在整帧捕获时激活；客户端不使用glFinish、阻塞查询或状态读回。每个阶段仍导出CPU/GPU执行次数，缺失或不完整保持未知。

| 标签 | 边界 |
| --- | --- |
| rift.target.whole | 整个NativeSkyView.draw，含初始状态快照和最终恢复；属于iris.translucents.begin |
| rift.target.prepare | 状态准备、尺寸检查/重建、附件清理、目标天空上下文及uniform更新 |
| rift.target.sky | 原生天空绘制 |
| rift.target.depth-copy | 目标pre-hand和pre-translucent深度复制 |
| rift.target.deferred | 目标deferred回放；不是主场景deferred |
| rift.target.clouds | 存在时的原生云绘制 |
| rift.target.composite | 存在时的目标composite回放 |
| rift.target.history-publish | 持久附件发布/复制和同帧就绪发布 |
| rift.target.restore | SkyRenderState.close及原有纹理、FBO、viewport、shader和管线状态恢复 |

whole包含所有子项，初始快照不属于prepare，不能把子项之和当完整回放。没有执行的可选云/composite标签保持缺失；本轮没有把它们记为零。保留原try-with-resources异常抑制与finally恢复顺序。

## 裁剪候选：实际选项下资格失败

离屏工具`TargetSkyMaskReplayGpuValidation`复用已有选项/环境宏回放，将本轮包与402项捕获选项严格匹配，保存补丁后实际预处理shader。Iris全局附件指令来自final等程序，不能只解析deferred1。实际末级输出为`[0,5,4,19,18]`，其中`[4,5,18,19]`均`Clear=false`。目标回放保持原有翻转结果发布到下一帧主历史；活动顶点shader还读取`texelFetch(colortex5, ivec2(viewWidth-1, viewHeight-1), 0).a`。

因此“目标输出没有跨帧历史依赖”条件失败，禁止只按当前CmiJuliaHit裁剪整个末级片元。扩大一圈过滤保护也不能保护远处历史消费者。没有缩小分辨率、减少云质量、复用过期天空、删除mipmap或历史更新；没有把不安全候选写入生产shader。

资格证据：`build/benchmarks/target-sky-r5-20261103/qualification`，含`qualification.json`、实际预处理末级/顶点/final源和`history-counterexample.csv`。可执行反例覆盖31、32、63像素尺寸，原实现A/A一致；保守过滤邻域当前颜色保持逐字一致，但保留附件和下一帧远处历史采样不同。538项检查通过表示**拒绝依据通过检查**，不是裁剪通过正确性或性能门槛。该反例是简化GL模型；实际包消费者证据来自上述预处理源码，不能把反例计时称为完整回放收益。

诊断开关默认关闭：`-PcmiNativeSkyMask=true`经runClient传为进程属性`cmi.nativeSkyMask`。目前只请求资格检查，**不会启用裁剪**；采集元数据`target_sky_visibility_at_start/end`记录requested、effective=false、retained_outputs及回退原因。无光影/其他包同样保留原实现。由于前置条件失败，没有进入空掩码/全屏/边缘/切换等完整候选画面与收益矩阵，也不安排无效开关的实机A/B。

```powershell
.\gradlew.bat -I scripts/particles/validation.init.gradle validateTargetSkyMaskReplayGpu --offline --no-configuration-cache --no-parallel -PbenchmarkOutput=build/benchmarks/新的目录/qualification
```

## 计算侧：原实现首次分歧

`ShaderEfficiencyTraceGpuValidation`在冻结R4原实现上运行dome_gather，1000/65536粒子、mode1/2，各比较三次原实现A/A。分别捕获prepare、update、contacts、depenetration、navigation、publication；检查输入池/计数、输出计数与有效输出池物理槽顺序、population、完整网格链、correction、实际攻击上下文、候选持久状态及按稳定身份归一化的粒子/事件。

新增检查曾发现无outer场景未上传的分配尾部在原始字节比较时不同（首字986624）。生产通过uStormOuterEnabled=false阻止消费该区间；诊断工具在计时外固定两bank的原始分配字节以排除干扰，未给生产添加初始化。最终证据在`build/benchmarks/readonly-r5-20261103/trace-physical-order/first-divergence.txt`；早期trace、trace-state及trace-rewound也保留。

固定后prepare全部一致；**首个原始差异实际在update的输出物理槽顺序**，按稳定身份归一化的粒子、状态和事件此时仍一致。update的并行原子分配允许压缩槽顺序不同，不能把规范化相等误称为物理输入相等。DomeBodyContacts随后用该输出重新运行gridbuild，atomicExchange链头顺序也由并行执行决定；contacts阶段首次出现按稳定身份比较的位置和持久状态差异（mode1），延续到发布。mode2本次有物理顺序/网格差异但规范化结果一致。接触遍历有768预算和顺序敏感的浮点计算。这是生产算法并行顺序不确定性的证据，未找到能消除该位置差异的已确认fixture复位遗漏。没有排序压缩池、调整邻居、归约顺序或放宽误差。

跟踪任务成功表示成功定位差异，不表示完整链严格A/A通过；其中有阻塞离屏读回，只用于诊断，不提取计时。可靠完整链门槛尚未恢复，故不重新拿失败单元证明缓存净收益、不发布缓存候选、不组合两个未通过候选。约0.53ms状态复制继续保留。

```powershell
.\gradlew.bat -I scripts/particles/validation.init.gradle traceShaderEfficiencyUpdateGpu --offline --no-configuration-cache --no-parallel -PefficiencyBaseline=build/benchmarks/readonly-r4-20261103/baseline/src/main/resources -PbenchmarkOutput=build/benchmarks/新的目录/trace
```

## 验证与下一次用户采集

串行执行：Gradle完整构建及1029项Java测试通过；Python帧工具11项测试通过；裂隙天空GPU16,346项、裂隙光影回放GPU53,397项、粒子场景GPU72,764项通过，异步时间戳标签/饱和/取消/槽复用GPU回归通过。新增分析测试覆盖同帧余量、嵌套目标标签不重复扣除、缺失子项保持未知。资格检查失败与并行分歧单独保留，未计为候选优化成功。没有启动Minecraft；无光影、其他光影包切换/资源重载、真实画面与玩法仍待用户确认。

本轮交付针对目标天空的诊断构建，构建身份`4067018795b3f53040c613b8ab8cad7da4fd0afb42f3d3849e6a003a06d26ec9`，归档与构建摘要在`build/performance-attribution-20261103/r5`，已核对归档内嵌主JAR身份。客户端开发运行不向run/mods加入归档整包：

```powershell
.\gradlew runClient --no-configuration-cache
```

1. 保持本轮Complementary选项、FULL、分辨率、相机/FOV、风暴数量及可复现攻击状态；关闭自动节流、VSync及内外部限帧。运行`/cmi particle profile backend production`和`/cmi particle profile off`，预热到编译/加载稳定。
2. 同一进程固定场景，依次执行两次`/cmi particle profile frames 60`；每次立即关聊天并保持相机，等saved后再开始下一窗，每窗至少1000帧。确认裂隙处于开启状态，并记录是否有切换/相机移动；不叠加其他捕获。
3. 保留每窗JSON、同名前缀全部CSV、引用选项JSON及debug.log，标注场景/攻击阶段。重点检查rift.target.whole与子项的同帧耗时、次数和未知比例；无需重复FULL/OFF，也无需重复无风暴窗口。

目前没有通过资格与完整链净收益门槛的具体优化，因此这不是20%收益验收。新增目标天空直接计时结果待用户采集。若后续候选通过，仍须三轮独立进程、匹配A/A与交错A/B，每窗≥30秒且≥1000帧，每轮平均FPS≥20%、超过噪声、p95/p99无稳定退化且画面/玩法通过。当前实际优化收益**未测得**，不能写成0%或宣称20%不可达。
