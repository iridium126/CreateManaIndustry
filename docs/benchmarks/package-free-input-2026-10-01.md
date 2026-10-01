# 自由包裹原生输入桥接（2026-10-01）

自由包裹 GPU 拾取已接到 Minecraft 的右键和攻击入口。点击只创建一个有界请求，成功 pool/admission 提交后执行 GPU 射线查询；完成的结果在客户端 tick、引擎 GL pass 之外交给真实保留的 PackageEntity。Create/Minecraft 的原生交互包、物品与攻击逻辑、NeoForge 事件、双手选择、挥手和物品使用仍由原调用执行。没有新增逐包裹位置包，也不在客户端发放包裹或直接改变服务端库存。

一个结果必须同时匹配活动 acquisition 的完整身份/generation、candidate/body、可见 active 状态、真实实体 ID/UUID、世界、region/epoch、leaseEpoch 和箱体尺寸。仅凭旧粒子池索引不能攻击或拾取。排队时保存双手物品、选择槽和相机；世界、相机、物品类型/组件、选择槽或屏幕变化时不回放旧输入。准备、退休、隐藏、纯视觉观察者或旧 generation 不能进入原生回调。队列在调用 Minecraft 前先取走操作，迟到的完成数据不能再次回放。

原生 GameRenderer 射线过滤掉已由 GPU 接管的自由包裹旧 AABB，其余实体、Create 管理的包裹和原生观察者保持原过滤器。点击射线受正常命中/障碍距离和实体交互距离限制。GPU 额外返回实际显示的中心和最短角路径 yaw；只在回调期间临时设置一个选中实体，使 interactAt 得到正确的相对命中向量。不会改写 vanilla VecDeltaCodec。结束时恢复独立原生位置；模组回调已经移除、移动实体或换世界时不撤销其操作。

混合自由/锁链右键在同一个输入预算内提交两个 GPU 查询，分别使用实体和 Create 锁链的原射线范围。Create 原 package 交互阶段直接消费已经完成的链结果，保留其先前工具/胶水/锁链处理顺序，不再串接第二次异步查询。链未命中继续原 Create-owned 成员处理与自由实体交互。被接管的链成员此前已从原 physicsDataCache 中移除，避免旧位置候选。链自身的未命中回放也不会重入自由 GPU 输入。服务端链拾取继续使用已有幂等事务；自由实体使用原生 ServerboundInteractPacket，由服务端的真实实体和原有 lease 释放回调处理。

单次自由输入读回 128B，混合右键 256B；没有人口规模的姿态下载、CPU 窄阶段扫描或每帧输入查询。完整 GPU 输入处理预算仍为 100ms，排队、等待两个结果和主线程回放共同使用该期限；超时撤销接管后尝试原输入，不等待 worker/fence。客户端原有攻击 missTime、手忙和持续破坏条件仍检查；等候查询时不会对暂时选中的方块继续破坏。网络等待与原生服务端处理不算 GPU 查询时间。

此桥接仍依赖保留的原生实体同步和服务端已提交 GPU 检查点。它没有完成停发权威位置下行后的交互/绝对恢复握手，也没有把协议组件比例变成完整网络通过证据。生产网络门禁保持关闭。持续准星悬停/提示、实际 Mixin 应用、快速运动下的原生服务端范围检查、多人和模组交互仍需游戏内验证；本轮没有整帧或渲染线程性能结论。

自动化验证覆盖：队列满时保留原输入、相同操作只取走一次、迟到/跨 epoch 完成、零命中、100ms 的排队/在途/已完成超时、结果类型和可见状态；解析当前已解析 Minecraft 字节码检查输入方法、missTime、GameRenderer 原生射线调用、client-only Mixin 配置与原生实体包入口。真实 GPU 验证实际 acquisition→admission→拾取→活动 lease 绑定、旧 generation/退休拒绝，以及 free/chain 同代查询的独立结果、256B 读回和 359→1 度显示插值。字节码检查不等同于实际游戏 Mixin 启动测试。

RTX 4070 Laptop / NVIDIA 581.15 / OpenGL 4.5 完整 GPU 回归通过 16955919 项断言。GPU oracle 的完整身份/离散结果精确比较，显示中心与命中坐标沿用 1e-4 容差；角度环绕固定样例精确比较。此处没有提交耗时或实际连接带宽测量。

同时修正服务端原生 `push` 的所有权处理：同世界、同客户端权威且双方都完成 FINAL/ACTIVE 的包裹由共同自由 GPU solver 解算，取消重复原生 impulse，避免接触本身把密集堆叠全部交还 Create。不同权威/世界、准备中、过期、不再符合接管条件或其他实体接触继续原生处理；包裹之间的外部原生 impulse 会修改双方，因此先交还双方的精确租约。挤压伤害、普通伤害和其他生命周期回调保持原有路径。

接触资格测试覆盖跨区域同权威、不同权威、冻结 FINAL 尚未就绪、旧 generation、退休、过期运动和资格失效。**原生 LivingEntity.pushEntities 的候选查询与挤压计数仍执行**；本次只消除错误交还和重复响应，不是服务端宽阶段 GPU 化或 131072 包裹 tick 性能通过证据。后续需批量处理外部实体接触与挤压规则，再验证主线程成本。

最终全部 JUnit 为 70 个 suite、348 个测试、零失败；build 与 Sable 缺席加载通过。

```powershell
.\gradlew.bat test validatePackageGpu build --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```
