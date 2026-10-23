# Allay Storm：开发与验收入口

当前协议17、Dome v4、Helix v1。[当前行为](allay-storm-ai.md)维护攻击参数，[同步契约](allay-storm-sync.md)维护数据所有权。本页统一验证方式，不逐版本复制测试总数、日志或发行JAR哈希。历史结果从Git查阅，原始数据写入忽略的 `build/`。

## 按改动选择检查

从仓库根目录运行，不并发启动复用main或GameTest输出的Gradle进程。GPU任务需要实际OpenGL 4.5驱动，使用隐藏GLFW窗口，不启动Minecraft客户端；GPU不可用报告环境限制，跳过不算通过。

```powershell
# 行为／编解码／持久化与完整构建
.\gradlew.bat test check build --offline --no-configuration-cache
# 只运行风暴单元测试
.\gradlew.bat test --tests '*allaystorm*' --offline --no-configuration-cache
# 独立数学与编解码oracle
python -m unittest discover -s scripts/allaystorm -p test_dome_v4_tools.py
# 专用服务端生命周期及武器／事件钩子
.\gradlew.bat -I scripts/allaystorm/gametest.init.gradle runGameTestServer --offline --no-configuration-cache
```

| 范围 | 测试／工具 |
| --- | --- |
| 存档、增长相位、命令 | `AllayStormPersistenceTest`、`AllayStormCommandTreeTest` |
| 调度、人数与成员互斥 | `DomeGatherStateTest`、`MajorAttackScheduleTest`、`AllayHelixTest`、`AllayDomeTest`、`StarfallColumnTest` |
| 身份、包、反馈、槽复用 | `*PacketTest`、`StormWaveSlotsTest`、`StormGatherRulesTest` |
| GPU资源／客户端契约 | `DomeTemplateTest`、`StormClientContractTest`、客户端碰撞测试 |
| 武器与区域伤害 | `src/test/gametest/allaystorm/`；含剑、附魔、暴击、重锤、冷却与副作用恰好一次 |
| 编码与连续证书 | `scripts/allaystorm/`，见[工具说明](../scripts/allaystorm/README.md) |

旧人口阶梯、多柱选员和历史眼位辅助类的孤立测试已删除，不再当作现行需求。仍被共享生产路径调用的旧归属编码、圆环、转向输出和原点漂移回归保留。按功能扩展已有测试，不新建版本号测试集合。

## GPU回归

Dome集结专项入口：`validateDomeGatherGpu`在真实身体求解开启时重现密集归队邻居、访问768次耗尽和接触后仍运动合格的情况；位置／速度失配必须重新开始三帧，另测未知、脱嵌、冻结、新归属、双bank回滚和位图／计数一致。`DomeGatherRulesTest`及该GPU工具检查100%→90%整数边界、时钟环绕与截止tick，服务端GameTest回放实际位图，不以计划时钟冒充实际运动。

`DomeOverlapTest`覆盖上半球与主体包络边界、上／下方分离、径向和角点；生产GPU采样主体home公式检查包络覆盖，GameTest验证重叠目标不占槽、不取攻击ID、不预留、不绕过调度重试。矩阵中的重叠案例必须由真实服务端拒绝，不能注入空位图伪装为集结成功。自动化与性能范围见[空地集结报告](benchmarks/dome-gather-2026-10-23.md)。

`validateDomeTerrainGpu`读取`-PdomeTerrainPlans=...`指定的生产服务端START矩阵；空地8组包含高差−16～128、16384／65536／131072人口，其他四类地形各8组。可加`-PdomeFps=10/20/60/144/240`（分别执行）、`-PdomeJitter`或`-PdomeReturningTraffic`（在半球播种普通归队成员，后续完全由生产GPU移动）。`-PdomeTerrainFilter=Air-.*`只跑空地。实际误差、速度差和连续帧数写入`terrain/*-unready.csv`，逐帧计数／队列写入同目录CSV。`-PdomeBodyDiagnostic`仅用于隔离失败来源，不是保留真实碰撞的验收证据。

统一使用 [benchmark运行器](../scripts/benchmarks/run.py)，记录命令、退出状态、元数据及输出：

```powershell
# 三种攻击、姿态、专用碰撞；旧Starfall入口还覆盖共享路径
python scripts/benchmarks/run.py validateStarfallColumnGpu validateStarfallGpu validateHelixGpu validateDomeGpu validateDomePoseGpu validateStormCollisionGpu --offline
# 旋转／舞蹈边界、星柱下降入口与Dome高速截获／LOCK交接
python scripts/benchmarks/run.py validateStormMotionGpu --offline
# 修改共享绘制／状态边界时追加
python scripts/benchmarks/run.py validateParticleProgramsGpu validateModelPopulationGpu validateModelShadowGpu validateModelShadowIrisGpu validatePackageGpu validatePackageIrisGpu --offline
# 修改Dome运动／反馈时追加步频、延迟、环绕矩阵（默认Dome含20Hz）
python scripts/benchmarks/run.py validateDome30Hz validateDome60Hz validateDome144Hz validateDome240Hz validateDomeIrregular validateDomeDelayed validateDomeClockWrap --offline
```

`validateDomeGpu`包含斜坡与台阶集结回归，可用`validateDomeSlopeGpu`单独重跑；截止取生产共享公式，不再使用固定90秒。检查固定流线相位、逐成员完成／归队、实际就绪与同帧暂停／就绪快照，原始数据为`build/dome-v15/slope-gather.json`。

地形矩阵先由真实服务端计划器导出START，再运行实际GPU更新／接触／寻路并回放反馈：

```powershell
.\gradlew.bat -I scripts/allaystorm/gametest.init.gradle runGameTestServer '-PdomeTerrainPlans=build/dome-navigation/production-plans.json' --offline --no-configuration-cache
python scripts/benchmarks/run.py validateDomePathGpu validateDomeTerrainGpu --offline
.\gradlew.bat -I scripts/allaystorm/gametest.init.gradle runGameTestServer '-PdomeTerrainEvidence=build/dome-navigation/terrain-evidence.json' --offline --no-configuration-cache
```

矩阵覆盖空地、少量障碍、坡地／台阶、厚地形及长墙，各8组确定性计划、方向和主体相位，混合16384／65536／131072人口。保留真实计划截止、实际完成与流线状态；服务端重放验证原截止前LOCK或安全中止，不以单独计划时钟测试代替运动。`terrain-evidence.json`保存rank位图、冻结时刻和队列指标，benchmark目录的`terrain/*.csv`保存逐帧结果。`-PdomeTerrainFilter=.+-0`仅供定位单组，不算完整矩阵通过。

`validateDomePathGpu`使用生产BFS验证128在途、8192节点上限、跨帧前沿、1200请求排队与四槽公平、已知／未知／局部无路及提交回滚；用生产最终接触审计验证占位计时、排队／搜索不误退出、未知／脱嵌清窗和有效推进不退出。新增阶段三轮实际GPU时间、内存、节点及上传结果写入`dome-path-performance.csv`；空队列P95目标0.02ms、饱和P95目标0.3ms，必须报告实测及未达项，不能用节点上限替代性能证据。

本轮地形修复的自动化、完整管线增量与冷启动成本见[地形修复实测](benchmarks/dome-terrain-2026-10-23.md)。798项单元测试、42项必需GameTest及`test check build`通过；40组计划中32组按原截止发动，8组长墙安全中止，另验证全封闭及零有效成员。成功和失败场景均保存实际就绪／分母位图，服务端回放不得将失败位图视作已集结。真实双客户端和实际光影包仍单独待验收。

`validateHelixGpu`使用真实`update.comp`和风暴碰撞页验证地面受阻（20Hz／60Hz／0.25秒低帧率）、墙面横滑、坡面有效推进、短暂擦碰恢复、未知覆盖、脱嵌、起步、死亡、新攻击和身份重置。检查0.5秒窗口、当帧完成／收剑／无接触，以及下一帧普通归队；另以生产判定函数覆盖裁剪累计0.1秒、预期推进0.05格、实际推进10%和非连续裁剪。候选帧回滚不得发布观察进度，Helix不得分配完整Dome状态。`AllayHelixTest`与`StormMajorAttackGameTests`验证固定12／32速度预算、不同终止距离／时钟下1秒截止余量、初始／瞄准／LOCK一致、提前全完成回收、截止释放与重复反馈隔离。

`validateStormMotionGpu`补充共享舞蹈函数的`0.2R`开区间及原36秒节奏；Starfall真实集结末段、下降首帧和后续速度记录到`starfall-entry-velocity.csv`，直接检查保存的解析初切向3、垂直`−√27`、合速度6，不以整个下降阶段水平恒速3验收。未就位者保留实际入射方向，正常初始化无额外入口阶段；`validateStarfallColumnGpu`检查地形／未知碰撞、四阶段恢复与时钟环绕，下降快照保存方向不被重新匹配。Dome生产更新及最终接触裁剪验证集结可超过6但不超过8，LOCK首帧保留8→6交接；攻击参考仍为6。Helix从实际成员位置连续推进，覆盖60Hz、20Hz与0.25秒步长的旋进方向和32速度上限；接触回归使用0.25秒跨越8格的真实扫掠。模板点间距检查提高至1.4格。

舞蹈当前阈值为`0.2R`；仍以共享边界用例及当前整体验证为准，不引用旧`0.1R`结果。

Dome生产生命周期从普通主体初始状态出发，经过移动集结、95%反馈、服务端LOCK、中心接触与实际穿出结束平面，不能直接注入LOCK代替集结。检查rank唯一、旧反馈隔离、速度／姿态连续、死亡／中止／观察者及归队。证据在 `build/dome-v14/gpu-evidence-*.json`；联验实际候选与服务器时，先运行60Hz，再回放：

```powershell
.\gradlew.bat -I scripts/allaystorm/gametest.init.gradle runGameTestServer '-PdomeEvidence=build/dome-v14/gpu-evidence-60hz.json' --offline --no-configuration-cache
```

归队验证继续运行到全部12288名成员进入各自普通目标2格内，之后观察60秒；目标保持正常旋转，覆盖主体缓慢平移和增长。`validateDomeReturnStatic`补充静止主体，`validateDomeReturnFar`补充百万格坐标。`build/dome-v15/return-*.json`记录到达时间、末帧误差和稳态上传；同名CSV每秒最多采样12名成员的位置、速度、普通目标、攻击身份、阶段及姿态有效位，采样仅存在于验证工具。`validateDomePoseGpu`补充未参战／完成／中止释放、仍在活动的旧槽位、重叠Dome中一部分成员归队而另一部分继续集结、姿态缓存开关与新Dome／Helix／Starfall接管后旧反馈隔离。

`validateDomeExitGpu`覆盖路线耗尽但未记中心经过、首次外缘汇入、32格精确边界、垂直／远坐标／旋转中心、原3格出口、受阻、整场截止及新身份隔离，检查当帧完成位、零持剑实例和零接触报告。原始失败回归在`build/dome-exit-diagnostic/regression-before.log`。`validateDomeExitLifecycle`从真实主体／集结／就绪反馈／LOCK开始，在攻击阶段向一名成员注入球外偏移并清除中心经过标记；必须同帧退出，所有存活成员随后归队并观察60秒。该项是明确的故障注入，不把它描述成自然地形触发。证据为`build/dome-v14/gpu-evidence-60hz-boundary.json`；服务端GameTest使用此文件可追加验证边界完成释放预留、迟到接触不结算及旧完成不释放新归属。

Iris回归经过实际`buildMergedSource → ParticleVertexInjector AST → Iris TransformPatcher → 驱动编译链接`，使用依赖包内的Jcpp与glsl-transformer。覆盖两种受伤覆盖变体、主绘制／阴影uniform路径、交替缓存及资源重载，确认注入源与最终渲染源不含`cmiDomeFlowWord`。这不替代用户实际光影包测试。

普通GameTest可独立运行，但不替代GPU证据链。MODEL／Iris工具使用真实着色器和transformer，仍不代替游戏内光影包／Mixin验收。

## 资源与性能

```powershell
# 编码→结束平面裁剪→解码检查→运动／间距／环流连续证书；候选只写build
python scripts/allaystorm/rebuild_dome_v4.py
# 同机同输入，分别保存基线和当前输出，至少三轮
python scripts/benchmarks/run.py benchmarkStormDome -PstormCoherent --offline
python scripts/benchmarks/run.py benchmarkStormDome --offline
python scripts/benchmarks/run.py benchmarkStormHelix --offline
```

输出 `storm-{dome,helix}-combined*.csv`。`-PstormFixedInput=<文件>`给Dome固定输入，比较时验证输入哈希；`-PstormProfile`按需拆分GPU阶段，不在正常验证启用探针。`validateDomePerformance`提供正常／压力各三轮，目录由 `-PdomePerformanceDirectory=...`指定。正常用各版本参考跟随输入，压力使用相同字节输入。

本次最小运动改动的RTX4070 Laptop／581.15同机三轮记录：Helix与Starfall原始基线在`build/benchmarks/20261022T081611Z-f3edc369/`，当前Helix在`20261022T083424Z-aa94b59a/`、当前Starfall在`20261022T083755Z-6115d493/`。下表均为三轮中位数，不是游戏帧率：

| 固定负载 | GPU P50基线→当前（ms） | GPU P95基线→当前（ms） |
| --- | --- | --- |
| Helix 4096参与者 | 0.212992→0.205824 | 0.217088→0.211968 |
| Helix共享池200万／4096参与者 | 1.226752→1.215488 | 1.235968→1.233920 |
| Starfall 64柱／4096成员 | 0.045056→0.045056 | 0.045056→0.045056 |
| Dome正常131072成员／4槽／64柱／100玩家 | 1.645568→1.679360 | 1.667072→1.693696 |
| Dome压力相同规模 | 2.159616→2.171904 | 2.733056→2.194432 |

Dome前后各三轮使用相同字节输入，SHA-256逐轮校验；基线着色器取本次改动前Git暂存区快照，保存于`build/benchmarks/motion-baseline/`及其manifest，Java驱动与状态布局相同。原始结果为`motion-before/`和`motion-after/`，汇总`motion-comparison.json`。正常P95约增加1.6%；压力第三轮当前P95仍达2.695168ms，不据中位数宣称所有尾部改善。Helix与Starfall状态域保持11,774,752字节，完整Dome域保持44,390,880字节；所有这些场景新增稳态GPU主机上传为0，仅参数变化的头部每bank多4字节。未增加网络消息或稳态位置上报。

Starfall一次性4096名弧长初始化P50约0.361472→0.375808ms。初轮改后曾记录稳态0.177152ms、初始化0.951296ms，后续独立重跑未重现；异常日志仍保留在`build/benchmarks/20261022T082251Z-3541ff56/`，不能把单次复测等同所有设备保证。Helix资源本次离线4,194,256次尝试完成，编码后最小间距约1.400002格，资源仍49,160字节；不在运行时生成。

额外使用`validateDomePerformance -PstormFastNeighbors`保留Helix曾出现后的32格／秒邻居上界，仍逐轮匹配相同输入；`build/benchmarks/motion-fast-neighbors/`各三轮正常P50／P95中位数1.480704／1.804288ms，压力2.512896／3.193856ms。相较当前8预算压力P95增加约45.5%，相较修改前压力基线增加约16.9%；这是扩大高速预测范围的可见代价，不宣称性能零影响。正常路径仍有调度波动，不能把更低P50解释为全局加速。该场景状态域仍44,390,880字节、稳态上传0；节点访问上界不变。生产接触用例另检查32格／秒Helix及其释放后成员不会被Dome漏检或改写。

Dome 240Hz动态LOCK交接曾出现最小中心间距约0.3464格、46帧低于0.4格，仍高于现有0.34预测接触阈值；原始证据保留，不能据自动化通过承诺所有动态交接均无剑翼交叠。真实绕转观感、动态交叠与多人同步单独实机验收。

Helix受阻退出的同机三轮微基准使用`validateHelixGpu -PhelixBenchmark`，修改前／后原始数据分别在`build/benchmarks/20261022T070212Z-0233783f/`与`build/benchmarks/20261022T070727Z-a4ba1229/`。RTX4070 Laptop、4096参与者下，更新GPU P50中位数0.209920→0.212992ms，P95中位数0.215040→0.218112ms；共享池200万粒子／4096参与者时，P50中位数1.206272→1.212416ms，P95中位数1.213440→1.228800ms（修改后一轮P95达到1.406976ms，保留波动，不据此宣称所有负载改善）。状态域11,250,464→11,774,752字节，双bank新增512KiB；Helix不分配完整Dome扩展。新增窗口无稳态主机上传，逐帧场景对此设有断言。上述仅为隐藏窗口生产内核微基准，不是实机游戏帧率。

当前32格退出修复的同机对照：RTX4070 Laptop／驱动581.15、131072成员／4个Dome槽／64柱／100玩家，每类前后三轮且输入SHA-256相同。基线为本轮修改前的协议15着色器快照，Java运行时与输入一致。正常GPU批次P95中位数1.687→1.658ms，符合正常路径增量≤5%的预算；压力P95为2.473→2.733ms（约+10.5%），压力P50为2.174→2.146ms。压力尾部耗时没有改善，保留原始结果供复测，不能据此声称所有负载均提升。显存保持43,866,592字节、稳态上传0。原始数据在`build/benchmarks/dome-boundary-{before,after}/`及`dome-boundary-comparison.json`，基线源码在`dome-boundary-baseline/`。初始化与资源发布成本记录于同目录CSV；首次驱动编译和真实客户端冷启动未单独隔离。固定负载GPU批次耗时不能视为游戏帧率。

地形寻路修复前的斜坡与台阶场景曾用宽松截止，20Hz约30.2秒达到可达成员95%门槛，不能据此视为通过真实服务端截止。现行验证使用生产截止与逐成员完成反馈，见[地形修复实测](benchmarks/dome-terrain-2026-10-23.md)；上限升高不保证所有受限轨迹更快就绪。

这些只包含网格、更新与Dome接触，不是完整游戏帧时间或服务器吞吐。新的性能改动需重测；历史数据不作当前构建验收。见[性能结果规则](benchmarks/README.md)。

最终编码资源连续证书验证体心间距≥0.50格、加速度≤18、jerk≤120、转向≤180°／秒、0.5秒向心速度≥3格／秒及穿出3格。证书不覆盖任意初始相位交接、动态地形、多攻击交叠或全部动画顶点，分别由生产GPU场景和用户实机补充。

## 用户实机与交付

Iris的未定义流线函数已在实际AST／驱动链复现并修复。Dome残留攻击链也已在生产GPU内核定向复现：缺失中心经过标记后，成员在距中心43–46格仍保持阶段4并在玩家靠近时持剑；32格兜底使其当帧退出。正常模板全部能经过中心，具体实机场景中导致偏移或漏记的过程仍由用户复测核实，不以故障注入代替真实地形验收。

真实客户端由用户验收，自动化不能代签：

- Dome顶部覆盖、错落集结、贯心可读性、静止中心玩家受击、身体分离和剑翼轻微交叠。
- 主体种子手性与拖曳图案方向、`0.2R`舞蹈范围；Helix外缘下集结、95%后立即冻结并匹配0.75圈旋进；Starfall绕柱3格／秒、立即加入下降分量、逐成员触地展开与短回流，主体不漂向原点。
- 坡地、台阶、屋顶、动态障碍、未知／卸载碰撞、死亡／中止；多客户端换维度、观察者及authority离开。
- 实际Iris光影包的合并路径、重载后MODEL／阴影一致，持续外飞成员是否恢复正常归队，密集多人场景完整CPU／GPU帧时间、显存、上传量和冷启动成本。

当前分发JAR从 `build/libs/` 获取。离线证书在 `src/test/resources/allaystorm/`，只用于校验生产模板，不属于运行时资源。更新模板时同时更新测试证书。发布检查包含内嵌mod JAR：保留当前二进制模板，排除验证证书、旧模板、离线控制点、测试类及GameTest夹具。两端替换并重启，存档与当前配置保留。

## 身份与恢复回归入口

`StormStreamClockTest`覆盖旧维度／epoch及倒退时间；`StormPositionsPacketTest`覆盖双向±6／8／12／18／32速度、身份和非法输入。`StarfallSnapshotSyncTest`验证观察者合并、指定来源、超时轮换、断开和旧响应隔离；`StarfallSnapshotPacketTest`验证固定64名／8KiB边界、阶段和非有限值。

`StormMajorAttackGameTests`追加Dome集结／发射后死亡回收及无人模拟星柱清理。`validateHelixGpu`追加真实更新内核中参考进度已通过但实际位置仍在平面前，以及实际过平面当帧完成的对照。`validateStarfallColumnGpu`从生产集结／下降／实际触地／展开／短回流过程中采集快照，在另一套GPU状态bank恢复，检查阶段、位置、进度、重复安装、延迟追赶和恢复帧无剑标记；同时保留六种步频／地形与64柱性能场景。GPU工具验证不代替实际双客户端的接入、换维度与authority交接验收。

Helix受阻退出本轮自动化通过：`test check build`与完整Helix／Dome／姿态／碰撞／Iris／程序生命周期／旧Starfall回归在`build/benchmarks/20261022T071034Z-cfc24b87/`，最终Helix场景在`build/benchmarks/20261022T071250Z-dbf268bc/`（所有受阻步骤均断言尚未达到实际1秒余量截止），星柱恢复及性能联验在`build/benchmarks/20261022T070727Z-a4ba1229/`，服务端回收GameTest日志在`build/storm-review/helix-blocking-gametest.log`。未执行真实双客户端验收；地形视觉、多人接入与authority交接仍由实机单独确认。

本次最小运动方案自动化通过：796项单元测试、38项服务端GameTest（回放本轮60Hz实际GPU证据）、`test check build`，日志为`build/storm-review/motion-{gametest,final-build}.log`。Helix／旧Starfall／原生绘制／阴影／Iris／程序生命周期在`build/benchmarks/20261022T083424Z-aa94b59a/`，完整Dome／姿态／碰撞在`20261022T083203Z-96aed1c2/`，星柱恢复和30／60／144Hz在`20261022T083755Z-6115d493/`，240Hz／不规则／延迟在`20261022T084317Z-32006981/`，时钟环绕最终回归在`20261022T084937Z-13886f8c/`。最终运动专项在`20261022T084847Z-40d87a86/`，按稳定成员身份对应压紧池，独立重算实际初始化曲线的离散参考速度；Dome交接验收读取实际捕获初态和GPU精确时钟，不以有精度差异的计划时钟代替。工具中的旧硬6断言、压紧池索引和vec4读回问题已修正，失败日志保留；这些是验证工具修复，不增加生产阶段或状态。

分发JAR及内嵌mod资源检查记录为`build/storm-review/motion-package-audit.json`：协议17、新Helix v1内容和共享着色器存在，验证证书和GameTest夹具未混入。真实双客户端、实际光影包及绕转观感未运行，仍单独待验收。
