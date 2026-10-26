# 序号3～6：实施状态与验收清单

日期：2026-10-26。**摘要、共用天空、八包原生裂口、整组安全传送和到达过渡已接通代码闭环，自动入口已启用。新增裂口、多人及大型载具的真实客户端验收尚未完成，因此不能标记序号6整体验收完成。** 用户本轮确认的既有客户端视觉及八包体积雾原生路径验收保留。

## 实现与入口规则

| 部分 | 实际行为 |
| --- | --- |
| 摘要 | `StormRiftServer / StormRiftSnapshot`：XZ 512／576订阅滞回，10tick更新；首次订阅、开放、STOP及目标可用状态变化立即发送；双精度中心、epoch、递增序号；独立于成员模拟与authority。 |
| 开放与资格 | 仅主世界活动风暴，生成count≥16384、触发玩家存活、水平距离严格<64、脚点Y严格>来源世界maxBuildHeight+64。首次开放时间持久化，成熟旧数据直接稳定开放。 |
| 共用天空 | `AllaySkyProfile / Context / Provider`与`allay_sky.glsl`共用目标时钟、天气和方向；首版为低亮蓝青天空与稀疏方向星标，无第二个客户端世界。 |
| 裂口 | 基面Y=H+64，最终192×160包络，首帧包含半径64中心，外围2秒展开；双面、无碰撞，不推迟传送资格；仅替换天空，保留世界与前景云。原版路径不依赖Veil或粒子开关。 |
| 八包原生天空 | `NativeRiftPatchPlan`按完整包内路径、结构锚点与实际include图选择program；与彩光补丁独立预检、原子启用；会话校验有效program标记及加载代次，失败至多重试一次原包。仅主视角启用局部裂口，不写sky-map、SH或反射缓存。 |
| 运输组 | 根载具、嵌套乘客及全部同行玩家；Create稳定子结构与座位；Sable连接、加载依赖、反向actor及绳索闭包，含plot内实体及确认附着实体。入口请求记录原载具关联，准备期间重新核对成员与连接。 |
| 安全落点 | 请求建立时捕获风暴XZ；原XZ优先，32格圆形邻域按距离、X、Z排序；检查真实实体及结构碰撞cuboid、乘客净空、世界边界、危险方块、其他实体／运动结构和到达预留；只支持[-128,384)。普通乘骑需安全地面，船可用安全水面，飞行结构需整组净空。无位置则整组不动，不造平台、不送原点。 |
| 调度 | 全服务端每tick共享最多2个新加载请求、1ms协作预算；区块与实体存储非阻塞轮询。原生单实体／单chunk codec为不可抢占工作单元，1ms不是其硬实时上限。冷准备不冻结，准备60秒超时、失败2秒后重试。锁定后不新增冷加载，保留已预热目标票据至事务结束。 |
| 原生提交 | `RiftTransferCoordinator`在服务端安装；收集→准备→锁定／快照→全成员旅行事件预检→标准DimensionTransition/changeDimension→恢复挂载与座位→校验。先结构及非玩家，再玩家；解除递归乘客迁移，事务内一次性复用旅行许可。 |
| 结构桥接 | Sable 2.0.6与当前Simulated/Aeronautics 1.3.2 ABI；源世界字段不改写，原生序列化后分配目标plot，重映射BE、关节、网络位置及计划tick；跨维度section数组索引按原生minY修正；保留UUID、库存、姿态、座位、工作状态、原生强加载票据与跟踪点。目标逐块、BE、库存、连接、计时及质量数据校验后才提交。未知连接codec、固定世界锚点、不完整依赖或UUID／plot冲突整组拒绝。 |
| 冻结 | 仅锁定运输组：实体／玩家移动与骑乘、AI、结构物理、BE写入及计划tick；物理边界精确固定姿态，保持机械计时。临时空结构不进入原生破坏清理；新结构分配与首chunk／质量数据属于同一工作单元，避免Rapier访问空质心。 |
| 日志与恢复 | 工作线程仅接收分离NBT；压缩、SHA-256、force与原子替换；PREPARED先于破坏性迁移，COMMITTED先于来源清理；原生实体区域、玩家文件、结构holding／plot／占用图与元数据检查点后再RELEASED。启动先读日志并锁定成员，PREPARED回源，COMMITTED／CHECKPOINTED向目标恢复；不完整恢复保留日志并隔离，已释放记录只保留小型持久化决定，不复活后来死亡的实体，也不无限保留旧库存快照。 |
| 持久化边界 | managed载具只存一次原生实体区域，玩家文件保存小型挂载意图，不递归复制RootVehicle；离线玩家恢复文件，登录后恢复嵌套挂载与Create座位。原生实体区块先就绪再由其服务端所有者写盘，避免工作线程读改写旧磁盘副本。占用图及元数据异步写后重新取样，避免覆盖同时发生的其他结构分配。日志不能撤销第三方事件对组外造成的任意副作用。 |
| 到达 | 成功清零平移速度、刚体角速度与fallDistance，保留结构旋转、工作状态和创造飞行权限。先结构／实体跟踪，再挂载，再发同行玩家的事务清单；客户端等待实体、结构和挂载后确认，仅用于解除冻结和视觉过渡。正常维度加载界面保留，约0.6秒青白淡出，峰值alpha≤0.35；失败不发成功过渡。 |
| 协议 | 必需协议19，摘要／天空／整组到达／就绪载荷；就绪消息不能建立传送请求。未升级任何模组依赖。光影包结构适配与可选模组ABI分别管理。 |

## 八包新增原生天空矩阵

名称及版本只标识验证样本。以下为实现及自动验证路径，最后一列必须由真实客户端填写。

| 验证样本 | 原生主视角接入 | 缓存边界 | 新增客户端验收 |
| --- | --- | --- | --- |
| Complementary Unbound r5.9.3 | deferred1天空、天体、Unbound／Reimagined云 | 主视角局部启用 | 待验 |
| Photon v1.4 | deferred4场景天空、独立云阶段与历史失效 | d0 sky-map／SH不写局部裂口 | 待验 |
| Bliss v2.1.2 Chocapic13 edit | 实际composite2入口、背景与分离云层 | 全局deferred天空缓存保留 | 待验 |
| BSL v10.1.1 | deferred1天空／天体与2D／体积云 | 云采样按实际交点裁剪 | 待验 |
| iterationT 3.2.0 | deferred3屏幕天空、NUBIS及平面云 | sky-map／SH分支不启用局部裂口 | 待验 |
| Sildur’s Vibrant v2.02 Extreme | 传统deferred天空与2D／3D云 | panorama缓存不写局部裂口 | 待验 |
| Solas V3.8 | deferred1天空、平面云及体积云 | 前景云保留，背面云／极光裁剪 | 待验 |
| Sundial Lite v1.2.0 | Composite4主天空及实际Cloud库 | SSRComposite0不改写 | 待验 |

悦灵维度无显式包内维度映射时复用经结构验证的world0管线；显式映射仍由实际program校验决定是否启用。未知结构安全拒绝原生天空增强，不能把提示或源码识别当成八包完整显示验收。

## 自动验证记录

- 最终完整JUnit 910项通过，无失败、错误或跳过；八包源码变体矩阵与发行构建通过。日志：build/rift-final-release-validation.log。
- 原版天空／云实际GPU：24,657项断言通过。
- 八包原生裂口：真实Iris include预处理、TransformPatcher／AST、驱动编译及链接，147项断言通过，含主天空program有效裂口与天空上下文uniform；日志`build/rift-pack-gpu-run.log`。
- 真实服务端：多人嵌套乘骑及箱船库存、Create双座位、两座Sable结构／Simulated弹簧连接／plot内载具／库存／原生强加载票据均通过。旅行取消与部分原生迁移失败补偿通过；补齐马与矿车后，7项实际运输／故障场景通过；普通GameTest运行报告共8项，另1项为未启用故障模式时直接结束的重启入口。最终日志`build/rift-final-durable-vehicles.log`。
- 普通实体组真实进程中断／重启：PREPARING、SNAPSHOTTING、PREPARED、PARTIAL、COMMITTED、CHECKPOINTED六阶段均通过，包含两位离线同行玩家与库存、嵌套挂载。
- Sable／Simulated连接组真实进程中断／重启：PREPARED、PARTIAL、COMMITTED、CHECKPOINTED四阶段均通过；两座结构的库存、关节坐标及两位离线乘客的嵌套挂载保持正确。日志为build/rift-sable-restart-audit-*-recover.log；最终预算与精简日志收尾后，PARTIAL／CHECKPOINTED再次通过，见build/rift-sable-final-durable-*-recover.log。

```powershell
.\gradlew.bat test validateShaderPackSamples build --offline --no-configuration-cache
.\gradlew.bat -I scripts/particles/validation.init.gradle validateRiftSkyGpu validateNativeRiftPacks --offline --no-configuration-cache
.\gradlew.bat -I scripts/allaystorm/rift-gametest.init.gradle runGameTestServer -PriftSable -PriftRun=rift-client-precheck --offline --no-configuration-cache
.\scripts\allaystorm\validate-rift-restarts.ps1
.\scripts\allaystorm\validate-rift-restarts.ps1 -Phases PREPARED,PARTIAL,COMMITTED,CHECKPOINTED -Sable
```

测试专用源集不打入模组jar。所有服务端故障注入仅运行于`build/gametest`独立进程，使用新目录，不读写`run/saves`。自动测试、字节码ABI检查、隐藏窗口GPU结果均不替代真实客户端验收。

## 客户端验收清单

- [ ] 无Iris、Iris无包及八包逐一检查高空首次上线、512／576滞回、退出196／260成员追踪后仍可见裂口。
- [ ] 共用天空的目标时间、天气和方向一致；下方、侧面与上方观察；前景实心／云遮挡、后方主世界云及天体裁剪；主世界反射／sky-map／SH不出现局部裂口。
- [ ] 首帧中心包含入口圆，边缘展开2秒不推迟传送；粒子关闭、暂停、F3+T、切包、选项变化、STOP重建、换维度和断线清理。
- [ ] 船、矿车、马、嵌套乘客、多人载具、Create、当前Sable／Aeronautics大型连接载具逐项到达；座位、相对姿态、库存、BE、关节和机械状态正确。
- [ ] 任一玩家达标即可整组触发；其他乘客未达高度／距离、多玩家同时触发、侧入与下降均按严格入口边界处理。
- [ ] 整组过高／无安全点、世界锚点、UUID／plot冲突、成员事件取消、重建或挂载失败与各阶段重启；无复制、丢失、半组放行或票据泄漏。
- [ ] 所有同行玩家看到结构与挂载后才解除冻结；正常加载界面与0.6秒／0.35青白过渡；失败无成功淡出。

正式云冠、百万装饰、招式美术及返回入口仍按后续开发顺序推进。
