# GPU 自由包裹：空 section 与环境批次（协议 14）

当前实现状态：**空 section 的独立覆盖与惰性 atlas 已实现；环境行为是分阶段适配，尚未通过“全部对齐”验收。** 原版参考为 `.refs/Create` 6.0.11、Minecraft 1.21.1 / NeoForge 21.1.236。当前实际依赖和 GameTest 使用 Create 6.0.10；本次没有改动依赖或扩大 Mixin 的版本许可。

## 空 section

- 世界覆盖上限固定为 1024 个坐标，哈希表 2048 行，四个不可变视图 bank，使用位图按 2048 行计算。非空驻留单独受 `packageCollisionMaxSections` 限制。
- 已加载 FULL chunk 的 `hasOnlyAir()` section 直接发布空快照。未加载、正在采集和未知坐标不会被当成空气。共享空快照没有每 section 的 4096 格数组或工作线程任务。NeoForge 使用 isEmpty 计数，Create 标记为 air 的粉碎轮控制器仍不是空格子；GameTest 同时核对原生计数与全部注册状态，禁止自定义空覆盖掩盖环境入口。
- 空覆盖使用 `EMPTY=-4` 元数据，不申请几何槽、不产生几何上传、不驱逐非空驻留。已知无碰撞但有水、火、机器或其他环境材料的 section 保留数据。
- GLSL 查询区分 UNKNOWN、WAITING（确切版本未就绪）、EMPTY 和 DATA。EMPTY 直接合成空格子，碰撞、环境、力源遮挡和预取均保留其覆盖状态。
- 空 → 非空与非空 → 空保留版本身份。非空历史槽受输入历史引用、视图引用和异步 fence 保护；空历史只有元数据。
- 几何 SSBO 初始为 16 字节的最小绑定缓冲，按排队槽需求一次预留到二次幂容量。扩容通过 GPU copy，旧缓冲保留到 fence 完成；CPU 映射写入在复制完成前延期。
- 实际占用低于分配量四分之一持续 120 个渲染帧后，在没有在途读者时压缩并收缩。帧计数独立于同帧多次上传调用。生产路径没有 `glFinish`、阻塞 fence 等待或 CPU 几何回读。
- 诊断分别显示 ready coverage、dense、empty、history、retired。游戏内不统计或显示显存占用；实际 GL 缓冲字节只在独立验收工具中测量，用户可通过游戏外系统工具查看显存。

## 环境实现

静态采集缓存方块状态分类和不可变形状模板。细雪、脚手架采用潜在几何，由 GPU 根据当前包裹状态选择。Create 金属脚手架的 BOTTOM 分支保留自己的原生形状。水规则单独包含普通含水台阶、栅栏等状态，不能被伤害免疫过滤掉。

材料 ID 位于格子字的 16..23 位，状态参数位于 24..31 位。section 摘要让普通空气和地面跳过逐格特殊材料处理。web / berry 的下一次移动乘数与陷入落距清零、攀爬限速和碰墙后的攀爬速度、蜂蜜滑动、黏液和床反弹、灵魂沙 / 蜂蜜速度、气泡柱以及冻结计数在现有 predict / environment pass 中计算，不新增每包裹 CPU 碰撞查询。

NeoForge 的完整包围盒梯子选项在创建运行时时上传一次，启用时按 Y / X / Z 遍历，普通无特殊材料的 section 直接跳过。活板门委托下方方块的扩展回调，包含 Create 梯子；脚手架的 BOTTOM 与 CLIMBABLE 参数相互独立。

实际内部接触使用最终包围盒，按 X 外层、Y 中层、Z 内层遍历；不把高速扫掠经过的所有方块当作 `checkInsideBlocks`。整数上边界使用 `ceil(max)-1`，避免 float 减去 `1e-7` 后仍为同一个整数而多报接触。

每逻辑 tick 的有序回调折叠为一个原生批次。服务端先处理已实现的 tick / 落地 / 踩踏阶段，再按掩码顺序执行方块回调，随后执行 `PackageEntity.onInsideBlock` 的直接水移除；终止立即停止。库存、伤害、随机和世界修改均由服务端实际接触处理。依赖结果的包裹局部等待累计 ACK；纯运动材料可以继续，队列满时仍有背压。

压力板在原有实体计数后加入轻量记录，沿用原生信号和延迟释放逻辑。绊线保留原有实体结果和原生重检查。粉碎轮控制器以 identity / generation 保存处理对象，维护占用、库存互斥、沿轴陷入乘数、停机持有、伤害和下一 tick 释放；没有创建临时 `PackageEntity`。

Create 移动结构使用原生空碰撞上下文采集，不额外执行静态 `entityInside`。其黏液 / 床碰撞反弹使用 `.8` / `.528` 和原生速度阈值，只在每步运动阶段执行一次。Sable 保留已有结构坐标和动态几何接入策略。

## 权威协议与存档

权威协议升级为 `gpu-packages-14`，观察协议保持 `gpu-package-observers-5`。服务端与权威客户端须一起升级。

环境样本仍为 48 字节，事件仍为 1024 字节、最多 20 个样本。新增 `contact=32` 的字段含义如下；旧合成测试使用的 0 / 1 / 2 / 4 / 8 / 16 类型保留旧解码规则。

| 字段 | 原生批次含义 |
|---|---|
| contact.xyz | 三个速度 float 的原始位，单位 blocks/s；换 region 原点时不偏移 |
| timeline.xy | 全局逻辑步号 |
| timeline.z | 低 27 位：最终 3×3×3 接触掩码；27：着地；28：非零落距着地；29：浆果移动伤害条件；30：依赖服务端的接触；31：蜂蜜边缘滑落 |
| timeline.w | 落距 float 原始位 |
| position.xyz | 包裹中心，使用权威 region 原点 |
| position.w | 包裹自身逻辑 age 的原始位；原生批次 duration 固定为 1，不按 age 进行重放或预算计费 |

环境 sidecar 每包裹从 20×48 增至 21×48 字节，额外 48 字节保存运动状态。131072 容量下 samples SSBO 为 126 MiB，仍低于 128 MiB 的单块限制。额外 sidecar 不进入网络事件。落距另以每包裹 4 字节镜像保存在已有 Links 绑定中，约束 kernel 保持最多 8 个 SSBO。环境 uniform 位置在构造时缓存；同一物理步内不重复绑定环境 SSBO。

OFFER / FINAL / ENVIRONMENT_ACK 携带冻结、落距、陷入乘数、插入延迟和 age。火焰计数保留原生负冷却值（最低 -20）。原生 ACK 可以带权威姿态和运动状态；只在当前最新且正在等待服务端结果的生命周期上纠正，迟到 ACK 不重写已继续运行的运动。

先前纯材料批次的部分 ACK 可以推进累计确认序号，但如果其后已经产生依赖服务端结果的事务，必须保留该包裹的确认等待；只有覆盖当前等待后缀的 ACK 才能恢复模拟。部分 ACK 不改写等待中的姿态，也不能让包裹再推进一个环境 tick。

环境批次创建服务端确认等待时立即记录 `ENV_CONFIRMATION / environment`，保留刚完成的姿态与速度；不依赖下一次 predict 才补写原因，避免诊断采样得到 `UNKNOWN` 或之前的碰撞原因。此等待是库存、伤害和世界事务的局部同步点。诊断对同一生命周期、相同 pending 数及生命值的连续确认去重，正常推进的 written / acknowledged 序号不触发重复详情；短暂确认完成不会重置该去重。新的原因、pending 数、生命值或槽位生命周期仍可报告，冻结汇总继续反映实际等待数量。该改动不改变 journal、ACK 或运动执行规则。

新增存档字段 `EnvironmentMotion.{Frozen,Fall,StuckX,StuckY,StuckZ,Age}`、`WitherTicks`、`WitherEffect` 和 `PendingPortal`。凋零保存原生效果的等级、无限时长与隐藏效果栈；门户保存接触位置、类别、方向与逻辑 age。缺少新增字段时优先从已有原生 NBT 恢复冻结、落距、负火焰冷却和凋零，否则默认零；陷入和 age 默认零，插入延迟缺失仍为 30。捕获、退役、槽复用、跨维迁移和恢复都处理这些状态。

## 审计与验收边界

机器可读清单见 [native inventory](package-environment-native-inventory.json) 和 [逐来源覆盖表](package-environment-coverage.json)。148 个来源类（包含 NeoForge 扩展默认回调）包含环境声明、继承相关入口、主动查询与持有实体引用的机器；每项有显式条件、阶段 / GPU / 服务端职责及测试关联。覆盖表的测试名也必须指向实际存在的测试。新增来源、方法、查询或源码散列变化会使 `audit_package_environment.py --check` 失败。真实 GameTest 还枚举了 1703 个 Minecraft / Create 方块、58393 个状态和声明继承链，防止新增特殊入口被普通分类隐藏。

`--check` 证明清单没有漂移，**不证明语义完全一致**。`--acceptance` 是严格的完整对齐门禁，目前应失败。下列工作仍未完成或未有完整原版差分证据：

- Entity / LivingEntity 的完整前后阶段对照，尤其移动前流体、熔岩伤害、流体推力 / travel、降雨与火焰组合、窒息、全部活动效果和游戏事件的实体上下文。
- 门户已实现接触后下一次 base tick 处理、300 tick 冷却刷新、末地交叠和折跃门冷却，并有差分和存档测试；下界跨维出口、门户移除等完整组合矩阵仍未通过。journal 尚未为每个行为附带独立的不可变行为身份 / 阶段编号。
- 座椅乘坐、活塞 / 蜂蜜推送、潜影盒推送、机械手实体交互、压块及风扇全部实体处理分支。静态帆反弹已适配；弹射置物台仅适配原版对自由包裹执行的 nudge，原版直接实体发射分支明确排除 PackageEntity。
- Create 移动交互器、移动座椅、倾斜面的完整滑动与攀爬。当前移动结构适配不能被标记为完整 Create 运动行为对齐。
- 所有无效类型分支的真实负例；所有免疫 / 无敌、组合接触、随机、库存部分接收、存档重载和 200 tick/s 的原生差分矩阵。
- 特殊场景的完整性能测量、雨水区块列共享输入和机器动态参数按源位置合并采集。普通空气只在 GPU 推进 age；两次环境批次之间的存档 / 断线仍须补齐运动 sidecar 与姿态检查点的同步，不能假设服务端已持有最新 age / 落距。

这些缺口在覆盖表中显式保留为 pending / partial，不作为默认普通方块的验收结果。

## 已运行验证

612 项单元测试、14,679,411 条完整 GPU 回归断言、475 条材料专项断言均通过。原版环境 / 输出 GameTest 共 25 项，另有 1 项隔离恢复测试通过。普通场景五轮对照的 GPU 中位回退最高 +2.67%，CPU 提交耗时下降 73.20%～85.28%；环境 journal 读回为零。测量范围、原始样本和未完成项目见 [性能报告](benchmarks/package-environment-2026-10-15/README.md)。

## 复现

```powershell
python scripts/particles/audit_package_environment.py --check
.\gradlew.bat test validatePackageGpu --offline --no-configuration-cache -I scripts/particles/validation.init.gradle -PpackageRepairOnly
.\gradlew.bat runGameTestServer --offline --no-configuration-cache -I scripts/particles/package-output-gametest.init.gradle -I scripts/particles/validation.init.gradle
python scripts/particles/prepare_package_baseline.py
.\gradlew.bat validatePackageGpu --offline --no-configuration-cache -I scripts/particles/validation.init.gradle -I scripts/particles/environment-benchmark.init.gradle
```

GPU 专项开关：`packageEmptySections`、`packageMaterials`、`packageWorldHistory`、`packageUploadFairness`。GameTest 夹具不进入发布 source set，须通过专用 init script 启用。
