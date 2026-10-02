# Create GPU 包裹：当前实现与内部契约

更新：2026-10-02。自由包裹在创建或旧实体加载时转换为服务端轻量记录，保存库存、附加 NBT、身份、尺寸、姿态和环境状态；取消原生自由实体进入运动生命周期。只有 GPU 执行自由包裹物理。无就绪 GPU 时保留最后确认状态并暂停，锁链继续最小 CPU 物流。配置默认关闭，关闭 GPU 不恢复自由包裹实体。

## 权威、暂停和退役

`PackageAuthorityManager` / `PackageAuthorityRegion` / `PackageLease` 管理服务端权威。lease 的 `IDLE` 表示无 GPU 权威，`finishRelease` 结束权威撤销，不执行物理恢复。掉线优先迁移给其他就绪客户端，否则暂停；关闭、资源重建、历史缺失和区块卸载保留库存及确认状态。通知和重新接管采用有界批处理。

`PackageWorldRuntime` 在引擎 GL 边界管理模型、共享 free/chain/observer solver 与至多八个自由区域。网络入口只排队。最终基线、可见 admission 和 ACTIVE 精确匹配后，`PackageRenderOwnership` 按轻量身份排除重复显示；该类不再关联原生实体或恢复缓存。`PackageFreeInteractionClient` 的 GPU 点击结果直接发送稳定身份交互请求。

GPU 槽位退役、模拟暂停、权威迁移和服务端消费分别处理。body、pool、delta、journal 及观察槽位只有在退役 admission、旧 flight、编码任务、读回和 fence 都结束后才可复用；只缩减末尾空闲范围，不移动其他活动索引。

## 物理、环境和历史

生产只保留 `linked` 索引。相对 CCD 在任一方高速时使用共同预测状态与质量加权修正；候选预算耗尽或未知几何使受影响包裹局部暂停，继续显示最后有效 GPU 姿态，分帧重试。没有 CPU 后备运动或自由实体交还。

水、火、熔岩和机器接触参与实际 GPU 扫掠。环境状态随模拟步推进；有界可靠 journal 上报生命周期、步号及接触。服务端重新验证身份、当前记录、相关方块和伤害资格，保存确认后的生命值和燃烧状态，执行一次库存消费、内容掉落及音效。ACK 延迟、重连和队列积压不丢失待确认销毁。

每 tick 保存不可变力源、移动结构姿态、几何版本和控制输入，保留一秒历史。每帧最多补算四个 50ms 步进，仅在成功提交后消费时钟；缺失历史先暂停并重新取得确认基线。单调模拟步号参与服务端位移预算，客户端时间不能延长 lease。

碰撞预取只下载有界 section 请求与 atlas 使用位图。旧逐包裹交还位图已删除，每份快照少读回 16 KiB。`PackageCollisionPacketMixin` 保留方块实体更新后的碰撞失效 hook，`PackageAirCurrentMixin` 只捕获风机输入；其他原生自由实体运动、渲染和包恢复 hook 已删除。

## 机器、库存和锁链

机器直接查询轻量记录，调用现有过滤与库存插入接口。阻塞或拒绝保留库存；部分接收保存实际剩余量；完全接收原子消费记录并通知 GPU 退役。拾取、攻击、箭矢、爆炸及传送门处理记录，不触发 CPU 自由物理。

锁链仍用 Create 的服务端包裹对象完成端口、连接和存取事务。无 GPU 时按服务器 tick 推进进度，跳过逐包裹视觉位置、摆动、光照和剔除，只在查询、交接、保存及脱落时求逻辑位置。GPU/CPU 切换以确认进度和事务序号为界，保留链 checkpoint 与容器恢复。锁链脱落直接进入自由记录创建入口。

Sable 使用独立可选 typed adapter 捕获父结构姿态、移动几何和风机变换。普通/Iris 渲染通过共享池，链父姿态、查询和光照继续使用各自的坐标边界。

## 观察与协议

当前协商版本为 `gpu-packages-9` / `gpu-package-observers-4`。自由观察通过 `PackageLightObserverClient` 接收已确认记录姿态，GPU 合并字段并预测/校正显示；不再消费原生自由实体运动包。旧原生成员订阅动作与省略 pose 的 flag 均拒绝，详情见[观察者契约](gpu-package-observers.md)。

带宽进一步优化及总流量对照仍属于后续工作。已有相对基线编码、批量 ACK 和控制合并保留，组件结果不代表完整网络或整帧性能通过验收。

## 验证与参考

- [轻量记录说明](gpu-light-package-records.md)
- [修复验收与游戏待验项目](gpu-package-repair-validation.md)
- [旧代码清理与本轮验证](gpu-package-cleanup.md)
- [空间索引原始数据与比较](benchmarks/package-index-2026-10-02/comparison.md)：五轮选择 linked；两种落选实现仅在独立 benchmark 目录，生产没有模式选择接口。
- [独立索引 benchmark 使用方式](../scripts/particles/index-reference/README.md)
- [锁链权威与坐标边界](gpu-chain-authority.md)、[链父姿态](gpu-package-chain-frames.md)

此前 benchmarks 报告保留为历史测量，不描述已删除的原生观察/交还设计仍在运行。真实游戏 Mixin 启动、机器库存、多人与跨维度守恒、驱动间差异及 131072 包裹完整服务器 tick/客户端帧耗时仍需游戏验证。
