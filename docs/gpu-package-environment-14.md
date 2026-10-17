# GPU 自由包裹：空 section 与环境批次（协议 14）

当前实现状态：**空 section 的独立覆盖与惰性 atlas 已实现；环境行为是分阶段适配，尚未通过“全部对齐”验收。** 当前行为与差分基线为实际依赖 Create 6.0.10、Minecraft 1.21.1 / NeoForge 21.1.236。审计读取实际解析 JAR 的 Git-Hash 对应源码提交，`.refs/Create` 工作树的 6.0.11 仅供参考；未改动依赖或扩大 Mixin 版本许可。

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

本页保留协议 14 引入的单事件字段与环境布局；当前权威通道已因[区域合包](gpu-package-protocol-15.md)升级为 `gpu-packages-15`，观察协议保持 `gpu-package-observers-5`。服务端与权威客户端须一起升级。

环境样本仍为 48 字节，事件仍为 1024 字节、最多 20 个样本。新增 `contact=32` 的字段含义如下；旧合成测试使用的 0 / 1 / 2 / 4 / 8 / 16 类型保留旧解码规则。

| 字段 | 原生批次含义 |
|---|---|
| contact.xyz | 三个速度 float 的原始位，单位 blocks/s；换 region 原点时不偏移 |
| timeline.xy | 全局逻辑步号 |
| timeline.z | 低 27 位：最终 3×3×3 接触掩码；27：着地；28：非零落距着地；29：浆果移动伤害条件；30：依赖服务端的接触；31：蜂蜜边缘滑落 |
| timeline.w | 落距 float 原始位 |
| position.xyz | 包裹中心，使用权威 region 原点 |
| position.w | 包裹自身逻辑 age 的原始位；原生批次 duration 固定为 1，不按 age 进行重放或预算计费 |

环境 samples SSBO 现在使用具名 `PackageEnvironmentLayout`：每个身份保留 48 B 运动工作区和一个 48 B 内联历史样本，其余 19 个历史槽按需取得一页。固定元数据包含分配器、待处理位图和空闲页栈，不增加 SSBO 绑定。首次分配最多 512 页；异步捕获头报告缺页需求后按二次幂扩容并 GPU copy，旧存储保留到 fence 完成。131072 容量的该缓冲初始实际申请 16 MiB，全页池高水位申请 128 MiB，逻辑布局不超过单块 128 MiB 限制；这些是离线缓冲字节，不能作为实际显存驻留量。当前物理存储只增长，页会回收复用，不主动收缩。

首条未确认历史可以保存在内联槽；继续模拟前必须取得历史页。页不足只阻塞该包裹，其他包裹仍可推进。部分 ACK 保留页及剩余历史；完整 ACK 或生命周期退役才归还页，运动状态纠正保留页映射。页的分配和回收位于分开的 dispatch，使用 SSBO barrier 排序。额外工作区和页映射不进入网络事件。落距另以每包裹 4 字节镜像保存在已有 Links 绑定中，约束 kernel 保持最多 8 个 SSBO。环境 uniform 位置在构造时缓存；同一物理步内不重复绑定环境 SSBO。

环境待处理身份以位图保存在 samples 存储的分配器与空闲栈之间，不占用额外 SSBO 绑定。每 128 个身份使用一个 48 B 存储记录的 contact 区；首次生成未确认 journal 时置位，累计 ACK 覆盖全部后清除。捕获只遍历位图命中的身份，先新记录后重传，并按字轮转；退役清除旧位，捕获仍校验 header，不会导出旧生命周期。全量生命周期上传保守播种前缀，首次捕获剔除空位。131072 容量的位图为 48 KiB；内部布局变化不改变 wire。

捕获槽保持不可变：环境 kernel 直接写入四槽环的空闲存储，省去最大输出区的额外 GPU copy。共享 `GpuReadbackBuffer` 使用 coherent 持久只读映射；只有零等待 fence 轮询确认完成后，才读取 16 B 头并复制有效记录到 CPU 暂存区。头四个 uint 分别为尝试输出数、缺页身份数、捕获时页容量和空闲页数；CPU 由此计算增长需求，不逐身份维护镜像。空输出只读 16 B。传输背压保留已读槽，重试不重复读取；满环不启动捕获。

缺页数在第一遍捕获中累积到设备端分配器的独立 scratch 字段，第二遍经 dispatch barrier 后一次取出并清零，写入快照头。避免对持久映射头逐字原子累加，正常路径不增加 dispatch、上传、读回或 clear 命令。若捕获提交被中断，下次可用捕获前清除残留统计；它不确认、删除或修改历史记录。精确统计专项覆盖字／工作组尾部、终止与确认等待排除、重复重传、四槽饱和和残留清理。

快照持有独立字节，不保留原历史页引用；GPU 捕获在 ACK／退役／扩容之前由 barrier 排序，后续回收不能改写在途快照。消费回调抛出异常时，不推进该槽的完成屏障，保留原快照供重试。公共缓冲删除前通知所有活动计算／绘制状态快照清除旧 GL 名称，避免退休名称被重新绑定或误指向复用后的新对象。以上实现的是固定 20 样本窗口的存储分页；逐行为身份与阶段的有序效果 journal 仍待实施，不能据此标记第三批完成。

OFFER / FINAL / ENVIRONMENT_ACK 携带冻结、落距、陷入乘数、插入延迟和 age。火焰计数保留原生负冷却值（最低 -20）。原生 ACK 可以带权威姿态和运动状态；只在当前最新且正在等待服务端结果的生命周期上纠正，迟到 ACK 不重写已继续运行的运动。

环境 ACK 的 wire 字段保持不变。客户端每帧使用独立预算处理最多 512 条 ACK，再处理最多 64 条控制消息；控制预算不再顺带消费 ACK。GPU 用有界 128 B 行列表执行，一次 dispatch 处理独立 body，同 body 的多条记录通过前向链依次执行，保留后缀重放、负火焰冷却、权限和依赖结果等待规则。应用时除 CPU 校验外，再核验完整 identity / generation / lease / index / revision，排队后槽复用也不能改写新生命周期。ACK GPU 存储首次非空提交时才创建，空队列不上传、不 dispatch。

环境归属由 acquisition 在分配和释放 body 时增量登记；捕获记录只查该索引，并继续校验完整生命周期，不逐事件扫描区域。替换一个区域只移除其自身的归属；关闭／退役不会清除其他区域的映射。跨事件网络合包已按权威区域接入，见[协议 15](gpu-package-protocol-15.md)；有序分页 journal 及完整网络／世界事务验收仍未完成。

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
.\gradlew.bat preparePackageNativeAudit --offline --no-configuration-cache
python scripts/particles/audit_package_environment.py --check
.\gradlew.bat test validatePackageGpu --offline --no-configuration-cache -I scripts/particles/validation.init.gradle -PpackageRepairOnly
.\gradlew.bat runGameTestServer --offline --no-configuration-cache -I scripts/particles/package-output-gametest.init.gradle -I scripts/particles/validation.init.gradle
python scripts/particles/prepare_package_baseline.py
.\gradlew.bat validatePackageGpu --offline --no-configuration-cache -I scripts/particles/validation.init.gradle -I scripts/particles/environment-benchmark.init.gradle
```

GPU 专项开关：`packageEmptySections`、`packageMaterials`、`packageWorldHistory`、`packageUploadFairness`、`packageHistoryPages`。分页专项覆盖一页争用与局部背压、部分 ACK、20 槽环绕、不可变在途快照、旧生命周期 ACK、有效历史扩容及 131072 个唯一页；当前性能限制和最终联合回归状态见[运行时续作报告](benchmarks/gpu-runtime-architecture.md)。GameTest 夹具不进入发布 source set，须通过专用 init script 启用。
