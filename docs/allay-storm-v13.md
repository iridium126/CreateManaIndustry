# AllayStorm 协议13：交错贯心星幕

客户端与服务端必须同步升级到核心协议13；Dome模板v3。保留12288成员、16384存活门槛、95%就绪规则、独立调度、成员预留及现有伤害配置。Starfall、Helix玩法规则不变。

## 运动与美术

Dome以6格／秒汇入16–32格上半球厚壳的水平环流。移动目标与前馈速度使用同一攻击时钟；预测截获从上一帧时间出发，运动积分结束于当前帧时间。到达环流后继续飞行。最高初始参考位置31.550格，极冠12名成员在半径1.32格圆上，模型保持原尺寸。

LOCK捕获环流相位，按环循环排列映射攻击通道，稳定成员身份不变；只冻结就绪名单，其余立即普通归队。入口从实际位置和速度建立一次C1补偿，避免跳回模板、等待或超速赶进度。正常参考运动按三维弧长6格／秒推进；成员实际位置仍受世界与局部身体碰撞约束。

十二组星幕各1024条通道，其中64条贯心，共768条。最终资源中，贯心通道最近水平距离均不超过0.897格；外围通道均进入中心周围7.798格以内。启动0.5秒时最小参考向心速度5.238格／秒，取消旧外圈盘旋及中心外5.9格结束条件。穿过实际近心区域后冻结穿出方向，最终编码从全程三维最近点起至少继续飞行3.019格，再逐成员关闭攻击并普通归队；最长参考时长20.127秒，保留60秒故障回收。

按用户允许，642条通道（约5.22%）低于中心平面，0.01秒密采样最低参考高度−4.398格；外围通道保留高度约束。仅攻击阶段、模型身体中心距有效玩家身体中心小于6格时持钻石剑并允许接触。实际伤害仍用现有扫掠、护甲、盾牌、无敌帧与按玩家／成员／攻击去重。

## 离线分离与运行时职责

同一次攻击的内部时空分离烘焙在最终编码资源中。离线过程先以共同细时间步生成定速流，约束顶部、贯心、外围范围和穿出段；随后拟合C1 Hermite曲线。量化后重新计算弧长，再在各成员实际6格／秒时钟上修补稀疏接近，避免独立重参数化破坏原始避让结果。

独立发布检查按0.01秒联合采样12288条最终解码轨迹，最小中心间距0.429格；对可能接近的区间按12格／秒相对速度上界自适应细分，连续间距保守下界0.400格。此结果只证明同一次攻击的参考轨迹，不代表实际世界碰撞、实际交接误差或不同攻击之间已经分离。允许翅膀、剑和模型边缘轻微接触，不以完整翅膀1.4格包络作为硬约束。

普通风暴保留原单次空间网格与有界邻居避让。Dome正常跟随者在实际上一帧位置及本帧位置均接近烘焙参考、身份匹配且碰撞有效时标记为跟随者，同次攻击的跟随者之间跳过内部避让。零距离用稳定成员身份产生相反分离方向。

动态兜底采用紧凑GPU异常队列：每帧候选网格只构建一次，只有实际接触候选进入有界处理，不再逐轮重建全池网格；正常内部参考运动不执行额外多轮分离。空队列以零工作组间接派发跳过求解，不读回成员位置。查询中心优先，并按实际查询范围裁剪，避免预算先消耗在远处单元。

局部修正保持在成员本帧6×dt运动球内，允许碰撞降低实际速度，重新发布实际速度、姿态与持剑标记；不会通过额外位置位移超速脱嵌。世界未知或实心位置不强行推出，修正帧不产生伤害扫掠。持续严重挤压以无伤害普通归队兜底，不添加悬停等待阶段。普通非风暴MODEL与其他粒子不进入此队列。

## 资源、同步与碰撞

最终资源46,987,536字节，2,912,144个16字节节点，单通道最多1335节点；读取上限4096，二分查找最多12步并提前退出。节点存量化位置、累计弧长、每分量10位切向速度及毫秒参数。自适应拟合误差不超过0.012格；零导数节点在发布前修正，CPU与GPU采用同一C1曲线及有界弧长反解。

资源与发布证书一起打包，SHA256：`0d07d9d2558be40d96bd1830cd56afd4209e908197f225b17186fd878a442774`。对最终资源重新编码后哈希一致。服务端只加载384KiB环流元数据，不加载轨迹进行位置模拟。

客户端后台预读、全局共享一份TBO，每帧最多512KiB上载，全部发布后才开放集结就绪与攻击。首次Dome时延迟扩容交接与异常队列状态，保留既有Starfall状态；稳态不逐帧上传成员位置。

复用START.startSec及LOCK.lockSec同步环流与交接时钟。ready/eligible与完成包仍最多192个long；GPU每大招384个uint。维度、epoch、攻击ID及原始rank隔离保留；观察者从共享时钟恢复，旧反馈不释放新攻击成员。原生MODEL、阴影、Iris读取同一实际阶段与持剑标记。

碰撞覆盖包括32格厚壳、完整星幕、中心下方12格、穿出段与普通归队。成员释放后沿用独立普通归队覆盖，不占用攻击资源。

## 工具与验收命令

离线生成依赖NumPy、SciPy。可选Windows MSVC加速器仅用于烘焙，不随模组打包，不在Minecraft中加载；在x64 Developer PowerShell中编译。没有MSVC时可省略编译步骤及`--native`，使用较慢的Python路径：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/allaystorm/build_dome_baker.ps1
python scripts/allaystorm/generate_dome_template.py --native build/dome-v13/dome_bake.dll
python scripts/allaystorm/validate_dome_template.py
```

生成器只有在最终解码时空检查通过后才发布资源及证书。`--draft`只允许写入build目录；`--flow-cache`复用相同初始布点的离线流，`bake_dome_contacts.py`修补最终编码轨迹。原始缓存及本机加速库均在build目录。

```powershell
.\gradlew.bat -I scripts/particles/validation.init.gradle validateParticleProgramsGpu validateDomeGpu validateDome30Hz validateDome60Hz validateDome144Hz validateDome240Hz validateDomeIrregular validateStarfallColumnGpu validateHelixGpu validateStarfallGpu validateStormCollisionGpu validateModelPopulationGpu validateModelShadowGpu validateModelShadowIrisGpu validatePackageGpu --offline --no-configuration-cache
.\gradlew.bat -I scripts/particles/validation.init.gradle -I scripts/allaystorm/gametest.init.gradle runGameTestServer '-PdomeEvidence=build/dome-v13/gpu-evidence.json' --offline --no-configuration-cache
.\gradlew.bat test check build --offline --no-configuration-cache
```

生产GPU夹具从主体运动开始，经过START、移动集结、95%反馈、LOCK、贯心、逐成员释放，导出实际位图和中心接触候选；服务端回放校验资源哈希，再走真实反馈、LOCK与伤害去重处理。未提供`domeEvidence`时，服务端该项不会完成GPU回放，不能据此声称整链验收通过。

## 自动验证与性能记录

最终自动回归日志为`build/dome-v13/release-optimized.log`（20／30／60Hz）及`build/dome-v13/release-resumed.log`（中断后续跑144／240Hz、不规则帧长及其余检查）。单元测试761项通过，`check`通过；生产GPU夹具覆盖移动集结、95%反馈、LOCK、实际向心运动、中心静止玩家扫掠与逐成员释放。GPU与服务端以资源哈希和实际位图／接触证据分阶段回放，不等同于真实双客户端网络联调。

独立几何检查还覆盖百万格坐标平移、时钟环绕、未生成成员、94%／96%边界、全实心、未知覆盖、资源分帧发布、完全重合分离、普通MODEL隔离及空异常队列。身体接触修正不进入未知碰撞区域。原生MODEL、阴影和Iris检查包括最高Dome名单rank 12287。

### 固定输入性能记录

RTX 4070 Laptop，OpenGL 4.5／NVIDIA 581.15；总成员131072、4个大招（2集结、2攻击）、64柱、100名玩家。GPU计时包括网格、运动及v3异常碰撞处理；不含完整绘制、世界采集和网络，不能换算为游戏帧率。

| 指标 | v2：旧参考姿态 | v3：相同字节姿态 | v3：正常跟随参考姿态 |
|---|---:|---:|---:|
| 稳态GPU P50 | 1.713ms | 3.096ms | 2.403ms |
| 稳态GPU P95 | 2.163ms | 3.897ms | 3.018ms |
| CPU提交P95 | 0.135ms | 0.178ms | 0.179ms |
| CPU玩家索引P95 | 0.056ms | 0.057ms | 0.057ms |
| 首次GPU派发 | 8.703ms | 17.015ms | 11.314ms |
| 风暴域GPU存储 | 51,627,600字节 | 65,344,480字节 | 65,344,480字节 |
| 首次累计风暴上载 | 43,721,712字节 | 49,049,920字节 | 49,049,920字节 |
| 稳态风暴上载 | 0字节 | 0字节 | 0字节 |
| 单次资源上载CPU P95／最大 | 0.173／0.259ms | 0.214／0.261ms | 0.181／0.238ms |

前两列使用同一`benchmark-input.bin`，SHA256为`635356e7d33901874fbbdf12ceb43b6cbbbe736b765dd7054ffa3e51b8959762`。旧姿态在v3中偏离新参考路线，会触发额外兜底；因此另列正常跟随输入，第三列不能用于声称相对v2的优化百分比。v3增加了可靠贯心、真实交接和身体碰撞职责，成本高于旧版。存储为风暴元数据／状态／共享轨迹，不是整游戏显存。

本轮将距离过滤移至邻居身份和状态读取之前：同一v3压力输入的GPU P95由6.838降至3.897ms，正常跟随输入由5.911降至3.018ms。无Dome的Helix对照负载P95为1.722ms、CPU提交P95为0.126ms，保持Dome状态延迟分配；其风暴域为10,361,504字节。

首次运行修改后的着色器，工具CPU准备总计23.808秒；缓存命中的后续进程约0.908秒。前者包含首次驱动编译与工具初始化，后者不能称为无缓存冷启动。初次资源发布分别总计32.420／50.054／40.163ms，工具将多帧上载集中执行；实际运行每帧上载最多512KiB。离线烘焙不进入游戏运行成本。

原始CSV分别位于`build/benchmarks/storm-v13-baseline-final/`、`storm-v13-filtered/`、`storm-v13-coherent-filtered/`；优化前证据为`storm-v13-final/`及`storm-v13-coherent-before/`。v2基线内嵌模组JAR保存在`build/dome-v13/baseline-main-v12.jar`，SHA256为`28a0cfa74730bc504d52eda5eb1882b2d4048653a1a7728d61948e483b16b0bd`。不同路线、缺少身体约束的旧版与当前版本不能只按耗时判定等价。

### 最终回归与分发

最终`test`、`check`、`build`通过，761项JUnit无失败／错误，32项服务端GameTest全部通过，包含生产GPU位图触发LOCK及中心接触伤害只结算一次。Starfall单柱、旧Starfall、Helix、专用碰撞、MODEL容量、原生阴影、Iris和自由包裹GPU回归均通过。

| 生产核步频 | 集结时间 | 参战成员 | 攻击阶段最小体心间距 | 中心实际接触候选 |
|---|---:|---:|---:|---:|
| 20Hz | 8.900s | 11699 | 0.322格 | 786 |
| 30Hz | 8.933s | 11694 | 0.326格 | 784 |
| 60Hz | 9.050s | 11687 | 0.327格 | 782 |
| 144Hz | 9.139s | 11676 | 0.333格 | 785 |
| 240Hz | 9.088s | 11680 | 0.336格 | 787 |
| 不规则60Hz | 9.737s | 11729 | 0.324格 | 805 |

各场景最终均释放12288名，包括LOCK时未参战者；参战比例符合95%门槛。步频是测试模拟步频，不是测得的游戏帧率。接触候选数不等于扣血次数，服务端仍保留去重、防御和原版无敌帧。上述实际间距是攻击阶段观测值，不能替代所有地形、所有集结起始状态下的实机验证。

初始化修复后的分发文件`build/libs/createmanaindustry-0.2.7.jar`，49,032,293字节，SHA256为`d233c0845812b2a92f5a9a3464dc7bfca05cb50189776be88e8a3b4fc5679442`。Dome v3模板和轨迹未改变；已逐字节核对内嵌ParticlePrograms类与本次编译结果一致、模板与源文件一致，未打包GPU测试类。原Dome回归记录在`build/dome-v13/final-results.json`；本次修复分发记录在`build/particle-init-fix/distribution.json`。

### 2026-10-20 粒子引擎初始化修复

21:41的客户端日志显示RTX 4070／OpenGL 4.6，但提示粒子引擎不可用。根因是完整程序集合编译成功后，`rebuildWithCompiler`遗漏转交新增的`domeContacts`句柄，临时集合在finally中将其删除，活动集合的`ready()`始终为false。反复F3+T也会重复此过程。

修复将该程序与其余程序一起转交并清空临时所有权。未修改轨迹、调度、碰撞算法或协议13，没有增加逐帧处理。新增`validateParticleProgramsGpu`使用真实驱动编译完整生产着色器，走实际候选、提交与清理逻辑；遍历全部程序句柄，覆盖首次失败、成功初始化、Dome／图形程序失败时保留旧集合、成功重载退休旧集合、删除及重复删除。

修复前该测试在“Successful compilation left the engine unavailable”处失败，证据为`build/particle-init-fix/before.log`。修复后354项生命周期检查、1015项MODEL GPU检查及761项JUnit通过，`test/check/build`通过；日志为`build/particle-init-fix/after.log`。这是Java句柄交接修复，需替换客户端JAR并重启客户端；单独重载资源不会替换旧Java类。客户端实机复验仍由用户完成。

## 用户实机验收

以下未进行真实客户端验收，由用户完成：

- 远景完整穹顶、极冠、十二重贯心星幕、少量下方通道与穿出散归的可读性。
- 集结到攻击的连续观感、集结中的身体分离、模型姿态、翅膀及剑的轻微交叠程度。
- 中心静止、防御、坡地、台阶、墙体、动态障碍和未知覆盖恢复。
- 观察者中途加入、反馈延迟、换维度、中止、槽位复用、远坐标及较大世界时钟。
- 四个大招与64柱并存时的完整CPU/GPU帧时间、复杂地形与多人帧率。

固定输入工具、GPU夹具和GameTest均不能替代这些实机项目。信任边界保持：GPU提供运动、触地及接触候选，服务端管理生命、生命周期和最终战斗结算。
