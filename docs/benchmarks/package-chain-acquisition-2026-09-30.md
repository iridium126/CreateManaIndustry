# 锁链包裹客户端接线验证（2026-09-30）

本次完成客户端 chain upload/acquisition、世界时钟/传输/成功提交接线，以及原生成员和 Renderer/Flywheel 子集桥接。它是接管计划的阶段进度，不是目标性能验收；CHAIN_READY 仍关闭，正常锁链由 Create 继续管理。

设备与真实 GL 验证：RTX 4070 Laptop GPU，OpenGL 4.5，NVIDIA 581.15。使用现有 `validation.init.gradle`，无 shaderpack；不在实际 Minecraft 场景中运行该驱动验证。

| 验证 | 结果 |
|---|---|
| 完整 package GPU 回归 | 13,338,332 项断言通过（满池补充之前） |
| 锁链 GPU 回归及 acquisition | 158,518 项断言通过 |
| Java 单元测试 | 217 项通过，0 failures / errors |
| 无 Sable / companion 的独立 JVM | `validatePackageSableAbsent` 通过 |
| 构建 | `createmanaindustry-0.2.6.jar` 生成 |

GPU acquisition 使用真实 body/chain/pool、admission fence 和 event readback，不以 CPU 模拟这些资源。覆盖 prepared 不运动/不绘制、FINAL 资格和 body 局部重写、旧 identity/epoch 和 premature ACTIVE 拒绝、可见确认后才发布所有权、箱体/吊具各一个实例且共用一个槽位、FLIPPED、失败 pool 帧不发布、终止与未完成隐藏 admission 竞争、容量耗尽时拒绝新增对象。预置一个自由 body，使 chain candidate、generic candidate、fixed-offset body index 不同，验证网络 PREPARED 只能使用前者。实际世界和原生库存使用模拟 transport，不能以本测试替代库存守恒、用户拾取、路由与多人验收。

原生容器快照测试确认 worker 可遍历 Create 子集、快照不可变、未变化子列表复用、GPU-owned 对象不进入热遍历；ASM 验证解析的 Create 6.0.10 渲染字段和 Flywheel recycler 清理调用。公共生命周期 hook 不链接 Minecraft 客户端类或反射 API。尚未运行实际游戏 Mixin 启动测试。

Sable 继续使用精确 `compileOnly "maven.modrinth:T9PomCSv:U678xqle"` 和直接 API；同发布版 companion 仅作编译/数学测试，不嵌入 CMI。碰撞采集仍只在所属线程预算内读取世界，worker 仅接收不可变数值快照。

CPU/GPU p50/p95、显存、真实网络带宽、收集/上传/读回及 fallback 比例没有本轮新测量。以往 kernel/container 微基准保持原报告，不能推算本轮端到端收益。最新摆动回退、GPU 交互选择、移动光照、观察客户端和 Iris/阴影尚未完成；还保留完整 vanilla BE 复制和 epoch 内不回收的索引。不得宣称达到 131072 活动包裹整帧 p95 ≤16.7ms 或服务器 tick p95 ≤50ms。
