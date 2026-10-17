# 第四批：调度、业务所有权与预算隔离

更新：2026-10-17。适用 Minecraft 1.21.1／NeoForge 21.1.236／Create **6.0.10**／Hex `0.12.0-devel-pre-53`。第四批职责、直接调用、包迁移、资源生命周期、帧事务和五类记账已实现；自动验证及性能范围见下文。真实客户端混合渲染和整帧性能由用户执行，未取得结果前仍待验收。第三批完整环境语义、第五批算法选择均未完成。

## 调用归属

| 调用方／职责 | 实际所有者 | 边界 |
| --- | --- | --- |
| 渲染事件、Iris 提前准备、重载、世界切换、关闭 | `client/render/scene/ClientGpuScene` | 固定组合根拥有帧上下文、协调器、发布记录、计时与预算；没有插件注册表 |
| 公开一次／持续发射、取消、动画、Hex 发射准入 | `particles/engine/CMIParticleEngine.INSTANCE` | 保留实际准入与入队方法，删除场景、业务状态、资源、uniform 和诊断转发 |
| 通用池、程序、输入 staging、GPU pass | `particles/engine/ParticleRuntime` 及各实际资源所有者 | 直接实现发射／MODEL／Storm／包裹窄操作接口；没有 `StormParticleAccess` 或持有整个引擎的替代适配器 |
| 客户端攻击／使用事件、拾取 Mixin、伤害输入、战斗表现 | `particles/interaction/ModelInteractionRuntime` | 拾取快照、合成目标、原版 use 回放和本地近战规则独立；与 Storm 只共享命中数据 |
| Storm 网络状态、时钟、身份、波次 | `allaystorm/AllayStormRuntime` | 网络直接应用到状态所有者，生成游标失败可重试，逻辑时钟独立 |
| Storm 位置、纠正、波次／命中反馈 | `allaystorm/StormFeedback` | 发送、去重、读回确认归反馈域；普通 MODEL 不取得 Storm 伤害权威 |
| Storm GPU 波次资源与 uniform | `allaystorm/StarfallGpuBuffers` | 自己分配、绑定、提交、关闭；不通过引擎取得数组或裸资源 |
| 包裹网络、准入、输入、区域路由 | `packages/authority` | 服务端包结构不变，各域保留原确认／重试／丢弃规则 |
| 包裹物理、链、姿态与 pool 导入 | `packages/simulation`、`packages/render` | `PackageRuntime` 拥有组合、预览和绘制状态；GPU 绑定留在资源所有者与 pass |
| 包裹几何、力源、移动结构输入、光照 | `packages/world` | 事件／光照调用直接进入对应所有者；服务端记录 `queryLight`／`consumeLight` 原位保留 |
| 包裹读回、观察、环境上行 | `packages/feedback` | 共享异步字节读回机制，保留独立业务确认责任 |
| Great Trick、Hex、方块源、命令与诊断 | 各自业务／资源所有者 | 内部调用、测试夹具、Javadoc 同步迁移；不借用引擎业务入口 |

包裹客户端类从 `client/particles/packages` 迁至五个域；shader 资源路径、64 B 粒子记录、20 vec4 规格头保持原约定。公开发射重载仍在原入口实现准入和写入，命令语法、配置键不变。

## 帧与资源事务

固定阶段为输入 → 模拟 → 生成 → 剔除排序 → 发布 → 绘制。`FrameCandidate` 是通用池的一次候选事务，场景逐阶段执行；它不承担包裹、Hex 或 Storm 的逻辑时钟。

发布前完成全部 GPU 提交、版本核验、读回快照复制／fence 和不可变绘制参数构造。随后提升池、population 与附属域版本，协调器发布成功后才消费输入。发布元组包含帧令牌、池、排列、commands、counter、population 和实际 Storm／包裹版本。场景核对实际版本，不要求不同业务的逻辑步相同。失败清除候选发布、释放未发布快照，保留输入重试责任；不通过恢复计数掩盖已部分写入的候选。

Iris 在安装阴影相机前准备当前主相机帧；同帧只能尝试一次，失败不能在 AFTER_SKY 重试或消费旧帧。主画面和阴影读取同一份当前发布。普通 ALPHA → Hex 世界图案 → ADDITIVE 顺序及 Hex-only 补充提交保留。Hex UI／预览／阴影各有上下文，独立于普通池容量与世界粒子时钟。

公共 `client/gpu` 只含能力、源码／编译、uniform、缓冲／退休、异步字节读回、timestamp、记账和 GL 状态机制，不导入业务域。自有程序统一通过公共编译器创建；Iris 管理的程序仍由 Iris 创建和关闭。程序删除先原地失效上下文 uniform 缓存和活动状态快照；绑定阶段复用该缓存，避免重复检查上下文；缓冲、纹理、VAO 删除同步遗忘捕获名称，缓冲退休直接通知活动绑定缓存遗忘旧名称。业务资源由自己的所有者关闭，场景负责调用其生命周期。

共享输出预约检查代次、存储 epoch、容量与输出区间。单记录 GPU 追加使用原子计数，拒绝输出时饱和计数；计数在生产 dispatch 完成及 barrier 后才可被消费，不将中间分配游标发布。整体输出保留 CAS 的全收或不收语义。Hex 运动和 MODEL 死亡表现也使用同一计数保护。任意共享写入异常都会使整个候选失效；包裹独立事务和 Hex 原生回放依照各域既有规则处理，不因共享绘制失败重复执行事务或跨世界回放。

## 五类预算

| 类别 | 记账内容 | 汇总规则 |
| --- | --- | --- |
| CPU 准备 | 场景资源准备、输入／提交、绘制提交；包裹 prepare、Iris 等细分边界 | 同一账本嵌套 scope 只加最外层耗时 |
| GPU 模拟／绘制 | 异步 timestamp 完成样本 | 每个完成回调只加一次；未完成不是 0；Iris MODEL 阴影一次覆盖准备与实际绘制，失败样本丢弃；细分明细不再重复加入总数 |
| 上传 | 实际主机到设备 buffer／texture 有效载荷字节 | 只分配容量或设备内 copy 不当作主机上传 |
| 读回 | fence 完成后实际下载／映射读取的有效字节 | 不按最大输出区或申请容量收费 |
| 队列 | 接收、延期、拒绝及当前积压 | 按实际队列／staging 的条目数记录，单个批量命令不按展开粒子数收费；邮箱用常量时间积压计数，帧间累积后归账 |

队列汇总是经过各存储边界的条目流量，同一责任跨越邮箱和 staging 会分别记为边界接收，不表示唯一粒子数或唯一事务数。

这些是独立测量维度，不新增五个硬限额或配置。沿用现有阈值、EMA 和装饰节流；包裹事务、Storm 身份／有效预警、MODEL 战斗输入不会乘装饰比例，Hex UI 使用自己的账本。物理容量拒绝仍执行既有准入／重试规则，不能用低装饰比例代替必要工作。`/cmi particle stats` 显示五类账目；上传／读回字节是传输量，未新增显存字节统计。

## 自动验证与复现

原始输入、基线、日志、metadata 和样本在忽略的 `build/benchmarks/gpu-runtime-stage4-20261017/`。修改前冻结实际工作区源码及运行时，而非使用参考目录或随后重新编译的候选。基线 HEAD 为 `92157ea5d39fe04bbcea7da77860511b382d05d2`；源码快照 SHA-256 为 `c1d78afa50a9e2c58878f56fde391ab01675205aff7921f9106377108faf3ad4`。原 index 补丁 SHA-256 为 `c596e3fd1e504d4243eea8b2ac7bdcd5c2fac7554199a62743f11156666549ce`，保留暂存内容。

```powershell
.\gradlew.bat test validateSceneFrameGpu validateSharedFateGpu validateHexGpu validateModelPopulationGpu validateModelShadowGpu validateStarfallGpu validateHelixGpu validateDomeGpu validatePackageGpu validatePackageLightGpu validatePackageObserverGpu validatePackageIrisGpu validateHexSharedIrisGpu validateModelShadowIrisGpu jar --offline --no-configuration-cache --no-parallel -I scripts/particles/validation.init.gradle -PpackageRepairOnly -PbenchmarkOutput=build/benchmarks/stage4-validation
python -B scripts/particles/verify_gpu_distribution.py build/libs/createmanaindustry-0.2.7.jar

python -B scripts/particles/prepare_package_baseline.py --ref INDEX --output build/stage4-package-index-baseline
.\gradlew.bat test compilePackageEnvironmentBaseline runGameTestServer --offline --no-configuration-cache --no-parallel -I scripts/particles/validation.init.gradle -I scripts/particles/environment-benchmark.init.gradle -I scripts/particles/package-output-gametest.init.gradle -PpackageEnvironmentBaseline=build/stage4-package-index-baseline
```

结构门禁检查公共 GPU 依赖、业务字段／方法参数、已删除引擎入口、窄端口和旧包。状态测试覆盖阶段、重复准备、嵌套上下文、失效与独立版本；预算测试覆盖嵌套不重算和独立 UI；字节码门禁禁止必要业务域及 Hex UI 读取装饰发射比例。真实 GLSL 夹具覆盖容量 0／1／255／256／257、批量预约拒绝、输出哨兵、写入后异常、失败候选实际绘制为零、成功重试无残留、退休 epoch 和重载失败。发行包检查递归检查内嵌 JAR，拒绝夹具与旧桥接类。

已运行 694 项 JVM 测试、普通／Hex／MODEL／三类 Storm／Great Trick／包裹／Iris 真实驱动回归，以及实际 Create 6.0.10 的 25 项环境／输出 GameTest。148 类源码审计只证明分类库存完整，不能替代严格环境差分验收。具体性能结果、计时范围和未通过项见[性能报告](benchmarks/gpu-runtime-architecture.md)；实机矩阵继续见[用户验收清单](gpu-runtime-client-acceptance.md)。

本批不改变权威 `gpu-packages-15`、观察 `gpu-package-observers-5` 或存档字段。第三批缺失项继续保留在[行为覆盖表](package-environment-coverage.json)和[协议说明](gpu-package-protocol-15.md)，不将架构完成标为语义完全对齐。
