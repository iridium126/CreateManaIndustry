# 历史自由包裹交还审计（清理前快照）

本文件记录清理前的代码位置与可达性，下面链接可能指向已删除文件。2026-10-02 清理已删除这些旧入口；当前结果、保留边界与验证见 [GPU 包裹旧代码清理](gpu-package-cleanup.md)。

# 剩余自由包裹交还路径审计

2026-10-02，静态审计当前工作区的 src/main Java、Mixin 配置及包裹 shader，并核对全仓库调用方和测试/benchmark 引用。本轮仅查找，不修改生产代码。

结论：没有找到当前正常生产流程将轻量记录恢复为服务端自由 PackageEntity、再开启 CPU 自由运动的完整可达路径。不过上一轮清理保留了旧交还辅助类、客户端原生实体兼容分支、交还标志及过期注释；不能说交还相关代码已全部删除。

## 1. 原生恢复缓存、原生 teleport 和恢复实体姿态

- [PackageAuthorityClient.java:224](../src/main/java/com/iridium126/createmanaindustry/client/particles/packages/PackageAuthorityClient.java#L224)：收到 RELEASED 仍调用 PackageRenderOwnership.serverReleased。
- [PackageRenderOwnership.java:42](../src/main/java/com/iridium126/createmanaindustry/client/particles/packages/PackageRenderOwnership.java#L42)：serverReleased 为当前 claim 创建 PackageFreeNativeRecovery，记录旧交还状态。
- [NativePackagePacketMixin.java:20](../src/main/java/com/iridium126/createmanaindustry/mixin/packages/NativePackagePacketMixin.java#L20)：原生 teleport 处理完成后仍调用 PackageRenderOwnership.recovered；该 Mixin 仍在 client Mixin 列表中。
- [PackageRenderOwnership.java:49](../src/main/java/com/iridium126/createmanaindustry/client/particles/packages/PackageRenderOwnership.java#L49)：recovered 按实体 ID、UUID 和恢复缓存记录原生 teleport 姿态。
- [PackageRenderOwnership.java:55](../src/main/java/com/iridium126/createmanaindustry/client/particles/packages/PackageRenderOwnership.java#L55)：restoreNativeRecovery 调用 entity.lerpTo、setPos、更新旧姿态及 onGround，保留由原生运动包提供的速度。
- [PackageFreeNativeRecovery.java:8](../src/main/java/com/iridium126/createmanaindustry/client/particles/packages/PackageFreeNativeRecovery.java#L8)：完整旧恢复状态容器仍在生产源码中，测试也仍验证它。

可达性：RELEASED 到缓存创建的入口仍可执行；当前轻量 claim 不登记 byEntity，因此 teleport 部分无法匹配它。全仓库没有 restoreNativeRecovery 的调用方，恢复实体姿态链未接通。这是明确可删除的旧交还残留。

## 2. 保留实体接管、撤销拦截后恢复原生客户端运动/显示

- [PackageAuthorityClient.java:72](../src/main/java/com/iridium126/createmanaindustry/client/particles/packages/PackageAuthorityClient.java#L72)：activated 仍优先查找现有 PackageEntity 并调用 claimAfterAdmission，否则才走轻量 claim。
- [PackageRenderOwnership.java:30](../src/main/java/com/iridium126/createmanaindustry/client/particles/packages/PackageRenderOwnership.java#L30)：claimAfterAdmission 和 byEntity 的原生实体权威登记仍保留。
- [PackageRenderOwnership.java:88](../src/main/java/com/iridium126/createmanaindustry/client/particles/packages/PackageRenderOwnership.java#L88)：released 清除 byEntity/byIdentity；clear 同时清除所有实体 claim。调用入口分别为退役回调、无 acquisition 的 RELEASED，以及 PackageAuthorityClient.closeAll。
- [PackageClientSimulationMixin.java:19](../src/main/java/com/iridium126/createmanaindustry/mixin/packages/PackageClientSimulationMixin.java#L19)：只有 renderedByGpu 为真才取消 PackageEntity.travel。清除实体 claim 后这个条件变假，若实体还在，就恢复原生客户端 travel。
- PackageLivingEntityOwnershipMixin 同理解除客户端 pushEntities 拦截；PackageRendererOwnershipMixin、PackageEntityRenderOwnershipMixin 恢复实体渲染、阴影和名称等入口。
- [PackageVisualOwnershipMixin.java:24](../src/main/java/com/iridium126/createmanaindustry/mixin/packages/PackageVisualOwnershipMixin.java#L24)：GPU 拥有时把 Flywheel 实例置零；取消 claim 后不再拦截 animate，原生视觉可恢复。类注释仍明确以 handback 为目的。

可达性：这些 Mixin 和 clear/released 回调仍注册和执行，但当前服务器所有自由目标的 entityId 都为 -1，创建时取消实体加入，因此正常轻量自由包裹没有可以恢复运动的实体。该路径属于与当前创建流程断开的旧实体兼容分支，不是服务端 CPU 后备运动。

## 3. 旧原生观察流退役后归还原生客户端运动/显示

- [PackageNativeObserverClient.java:170](../src/main/java/com/iridium126/createmanaindustry/client/particles/packages/PackageNativeObserverClient.java#L170)：admitted 使用 claimNativeAfterAdmission 隐藏现有原生实体并取消其客户端 travel。
- [PackageNativeObserverController.java:180](../src/main/java/com/iridium126/createmanaindustry/client/particles/packages/PackageNativeObserverController.java#L180)：成员退役 admission 后回调 lifecycle.released。
- [PackageNativeObserverClient.java:172](../src/main/java/com/iridium126/createmanaindustry/client/particles/packages/PackageNativeObserverClient.java#L172)：released 调用 releaseNative；closed 调用 clearNative，解除上述实体运动和显示拦截。
- 成员移除、资格改变、OFFER、原生 passengers/remove 包和流关闭仍有进入退役的代码；NativePackagePacketMixin 和 NativePackageLifecycleMixin 仍调用这个旧适配器。

可达性：当前 src/main 没有 new PackageNativeObserverClient。PackageWorldRuntime.java:292 实际创建 PackageLightObserverClient，字段名称 nativeObservers 容易误导。旧静态回调的 live() 返回 null，旧原生观察流在当前生产初始化中不启用。Controller、Client、commands/patch 和 shader 支持仍保留。

## 4. GPU 终止 handback 状态与 HANDBACKABLE 渲染分支

- [PackagePoolGpu.java:21](../src/main/java/com/iridium126/createmanaindustry/client/particles/packages/PackagePoolGpu.java#L21)：HANDBACKABLE=16 仍是生产 metadata 标志。
- [PackagePoolGpu.java:222](../src/main/java/com/iridium126/createmanaindustry/client/particles/packages/PackagePoolGpu.java#L222)：setHandbackable 仍暴露旧交还 API。
- [PackageFreeAcquisitionGpu.java:211](../src/main/java/com/iridium126/createmanaindustry/client/particles/packages/PackageFreeAcquisitionGpu.java#L211)：自由包裹激活置该位，退役清该位；visible admission 及 activeOffer、拾取队列仍依赖此标志。
- [pool_select.comp:38](../src/main/resources/assets/createmanaindustry/shaders/particles/packages/pool_select.comp#L38)：保留 previousSleep.w==-1 且带标志 16 的终止 body，注释仍说等到 native Create renderer 恢复。
- state.glsl:7 仍把 -1 定义为 handback。history.comp:14、若干验证断言也继续使用旧 handback 术语。

可达性：标志的设置和校验实际执行，不能直接删除标志而不同时修改 admission/拾取规则。自由物理失败现已写 -4（COLLISION_FROZEN），未找到自由物理生产内核写入 -1 的路径；-1 的现有生产写入来自锁链异常及观察流过期。该渲染分支本身没有恢复实体或 CPU 运动的副作用，但属于仍未裁掉的旧 handback 状态支持。

## 5. 自由 GPU 检查点到 Create 姿态的旧转换接口

- [PackageFreeUpload.java:19](../src/main/java/com/iridium126/createmanaindustry/client/particles/packages/PackageFreeUpload.java#L19)：retainedCheckpoint 校验 GPU 身份并转换前后姿态、onGround 和 Create 每 tick 速度；注释仍允许 active emergency/retired 检查点恢复。
- [PackageFreeCheckpointGpu.java:7](../src/main/java/com/iridium126/createmanaindustry/client/particles/packages/PackageFreeCheckpointGpu.java#L7)：自由域检查点整理/读回封装仍位于生产源码。

可达性：src/main 没有 retainedCheckpoint 的自由调用方，也没有 new PackageFreeCheckpointGpu。当前仅正式测试和独立 GPU 验证/benchmark 使用，未接回实体。这组恢复辅助 API 可以裁掉，通用 PackagePoseCheckpointGpu 与锁链调用不能一起删。

## 6. 未建模外力的旧 release 钩子

- [PackageExternalImpulseMixin.java:24](../src/main/java/com/iridium126/createmanaindustry/mixin/packages/PackageExternalImpulseMixin.java#L24)：Entity.push(DDD) 的旧路径仍调用 PackageAuthorityManager.release(box)，注释要求 materialize checkpoint。
- [PackageAuthorityManager.java:455](../src/main/java/com/iridium126/createmanaindustry/content/logistics/gpupackage/PackageAuthorityManager.java#L455)：simulated(PackageEntity) 恒为 false，release(PackageEntity) 是空方法。

可达性：Mixin 仍注册。服务端 simulated 为 false，不触发 release；客户端历史实体可能进入钩子，但空方法没有任何交还效果。这是失效的旧入口，不是当前外力处理能力。

## 7. 服务器原生下行 rebase 辅助类

- [PackageNativeDownlink.java:10](../src/main/java/com/iridium126/createmanaindustry/content/logistics/gpupackage/PackageNativeDownlink.java#L10)：仍保存原生下行抑制状态，退出可见 GPU 权威后相对位置包返回 REBASE，以恢复原生跟踪基线。

可达性：PackageNativeDownlinkMixin 已删除，也不在 Mixin 配置中。src/main 没有该类的构造/调用方，仅 PackageNativeDownlinkTest 使用；不能实际发送 teleport 或恢复服务器实体运动。属于应删除的旧交还网络辅助代码。

## 已确认不是自由物理交还的 RELEASE/restore 路径

- PackageFreeAcquisitionGpu.requestReleaseBody 和 PackageAuthorityClient.requestReleaseBody 仍存在，但 src/main 没有调用入口；独立 GPU 验证仍调用。
- acquisition 的资源不足、编码失败、final/activation 覆盖丢失、admission 拒绝会发送 RELEASE 并退役 GPU 槽位。服务端 PackageAuthorityRegion.release 的 target.apply 只调用 PackageLightStore.update，target.released 仅清 lease、加入重新发现并发送 RELEASED，记录仍保存。这些是暂停/重选流程，不恢复实体。
- GPU delta 的 RELEASE 标记、服务器 lease 超时、关闭 GPU、掉线、区块卸载、跨区/维度、历史重建、消费记录同样到上述轻量释放流程。PackageLease.restored 把枚举设回 CREATE_OWNED，名字过时，但没有启动 Create 物理的代码。
- PackageWorldRuntime.collisionUsage.unsafeBody（第 73 行）已是空回调；未知几何和候选不足走 GPU -4 局部暂停，不调用 requestReleaseBody。仅 world_prefetch/测试仍保留 unsafe/handback 术语。
- PackageAuthorityClient.closeAll 清理 GPU 所有权及资源、通知 capabilities(0)；PackageWorldRuntime.dispose 没有自由实体姿态恢复或实体创建，只保留记录显示并等待重建。
- PackageFreeInteractionClient.java:187 的正常轻量分支直接发送稳定身份交互包。第 210/217 行仍临时移动并还原旧现有实体，以执行原生 interactAt；这是遗留实体交互兼容分支，不是交还物理，当前 entityId=-1 的正常自由路径先 return，不进入它。
- PackageLightGameplay.destroy 只为箱内物品生成 ItemEntity/生成蛋生物，不生成自由 PackageEntity。直接机器库存接口、部分接收和传送门记录迁移也没有自由实体恢复。
- PackageLightNativeCollisionMixin 仍为原生 PackageEntity 补充记录碰撞形状，但不创建实体；当前本项目服务端自由实体在 onJoin 即被取消。
- PackageRenderState.restore 恢复 OpenGL 绑定、混合和绘制状态，与包裹物理无关。光照 fallback、空移动碰撞缓冲也不执行 CPU 包裹运动。
- PackageChainClientOwnership、PackageChainAuthority 和锁链容器的 restore/materialize 属于明确保留的锁链 CPU 物流切换，不能按自由包裹交还直接删除。

## 历史材料

scripts/particles/index-reference 的三模式参考、旧 GPU 验证函数/断言，以及 docs/benchmarks/package-free-query-recovery-2026-10-01.md、package-native-downlink-2026-10-01.md 等旧报告仍描述交还；它们不构成当前生产调用入口。主说明已经将这些设计标注为历史阶段。

后续清理应先删除上面的原生自由恢复/观察辅助链和不可达实体兼容分支，再把仍承担 active/admission 资格的 HANDBACKABLE 改成准确语义并去掉 -1 自由终止渲染支持，最后更新旧测试和注释。服务端暂停/迁移/消费/槽位退役必须保留，锁链最小 CPU 物流必须保留。
