# GPU 运行时：用户执行的客户端验收

状态：**待用户执行**。客户端画面、交互、Mixin 接管及 shaderpack 组合由用户验收；JVM、GLFW／真实 GLSL、Iris transformer 和 GameTest 结果不替代本表。固定 Minecraft 1.21.1／NeoForge 21.1.236／Create 6.0.10／Hex `0.12.0-devel-pre-53`，不使用 `.refs/Create` 的 6.0.11。

## 隔离启动

从项目根目录执行；准备任务本身不打开客户端：

```powershell
.\gradlew.bat prepareGpuClientValidation --offline --no-configuration-cache -I scripts/particles/validation.init.gradle -I scripts/particles/client-validation.init.gradle
.\gradlew.bat runClient --offline --no-configuration-cache -I scripts/particles/validation.init.gradle -I scripts/particles/client-validation.init.gradle
```

目录固定为忽略的 `build/client-gpu-validation/`，可用 `-PgpuClientRunDir=build/<新的独立目录>` 改为另一目录。准备任务把实际解析的 Sodium distribution、Trickster 以及 `run/mods` 中其余顶层 JAR 复制到隔离目录，不复制存档、配置、`.connector` 缓存或 `.backup`。Sodium／Trickster 从对应已解析配置取得，排除原目录中的同名候选，避免仅有 Sodium 编译 core 时无法启动；其余可选模组保持现有运行环境。更新模组版本时使用新的隔离目录，复制任务不删除已有文件。

首次准备写入该目录的 CMI 客户端／服务器默认配置：关闭自动装饰节流，开启客户端包裹准入和服务器权威许可；后续准备保留已有配置。创建专用测试世界后确认两端 opt-in 生效，开启命令，固定种子、时间、天气、相机、视距和画质。服务器事务计时已通过启动参数启用。shaderpack／资源包由用户在该隔离环境自行选择。原 `run/` 中的世界及配置不会被加载或修改。

此前临时隔离启动只复制 Trickster，因缺少 Sodium 0.8.13 在模组加载阶段失败；没有取得可验收画面，不能算客户端通过。修正后的准备入口已自动核对输出，实际启动和下列操作仍由用户执行。

## 场景记录

每项记录实际加载的模组／shaderpack 版本、有效数量、相机和输入操作；通过前保持未验收。功能入口全集见[架构报告第 2.1 节](gpu-runtime-architecture-plan.md)。

| 场景 | 操作与严格检查 |
| --- | --- |
| 空引擎与普通预设 | 逐一使用 `/cmi particle emit` 的补全源，检查 Sprite、透明／additive、光照、碰撞、淡出、动画与持续发射取消；用 `clear` 清场。不要把预设同名当作已接管原版粒子。 |
| 方块常驻源 | 发光藤蔓五个附着面、出入视锥、加载／卸载、源表增长；离屏无补发，增长不重置分数相位。 |
| MODEL 与 Storm | 普通 fly／dance／hold、持有物、剑档位、拾取、近战、遮挡、尸体；混入 STARFALL／DOME／HELIX，检查身份、攻击、反馈和有效预警。 |
| Shared Fate／Partition | 各构形、主体／节点／溶解与透明混合；检查首帧、容量不足、退役后复用及暂停。 |
| Hex 粒子 | 直接 Conjure 255／256／257、超过 128 种动态 pigment、满队列／满 bank；未知 provider 的原生入口、颜色出生冻结、寿命、精灵、限制器和距离；失败回放无双发或跨世界。 |
| Hex 图案四上下文 | 世界、阴影、玩家预览、法术 UI；包括 Hex-only、嵌套预览、同批重复路径、尾点更新、原生回放、第一／第三人称和暂停。 |
| GPU 包裹 | 空气／地面、满历史／缺页局部背压、部分库存接收、拾取／投放、等待确认、槽复用；加入水／熔岩／雨火／冻结／机器／移动结构，分别记录当前缺失行为。不能把已知缺口记为通过。 |
| 生命周期和渲染边界 | 无光影／Iris；主画面／阴影；资源重载、重载失败、暂停、换维度、断线重进和存档恢复。检查当前帧提交、旧资源引用、模型／物品和 UI 状态。 |

包裹行为的逐来源未完成项见[覆盖表](package-environment-coverage.json)与[环境说明](gpu-package-environment-14.md)。实机画面正常不自动消除原版差分缺口。

## 性能与反馈

```text
/cmi particle profile on
/cmi particle stats
/cmi particle profile
/cmi particle shaderpack status
```

先关闭自动节流测绝对成本，再单独测策略。基线／候选必须使用同驱动、世界、数量和相机，各五轮充分预热后采样。记录 CPU p50／p95／p99、GPU 阶段、完整帧、分配、上传／读回、dispatch／draw、确认延迟和服务器 tick 尾延迟；事务日志在服务器停止时输出。公共 timestamp 为异步查询，不将缺少样本当作零耗时。

普通空场景／地面中位回退上限 5%；算法候选另须完整路径中位改善至少 5%、p95 回退不超过 5%。保持存活、可见、逻辑 tick 和攻击规则，不能靠丢弃工作达标。显存只使用外部系统／驱动工具，游戏内不新增字节统计。

原始日志、截图和样本放在忽略的 `build/benchmarks/`，反馈至少包含：场景与步骤、版本／数量／相机、期望与实际、通过或失败、日志及样本位置。仅提交结论和复现命令。当前没有任何此表的用户执行结果；未报告的场景保持待验收。
