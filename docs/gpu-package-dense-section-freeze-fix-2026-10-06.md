# 密集高速包裹跨已上传 section 时冻结

日期：2026-10-06。用户补充：约 20,000 个包裹受 Sable 结构上安装分散网的鼓风机驱动，包裹挤在一起，在竖直 section 边界冻结；边界两侧的 section 已上传。

## 原因

`solve.comp` 的 27 格邻居查询累计访问超过 `uCandidateBudget=8192` 后，直接回退到上一位置并设置 `PACKAGE_COLLISION_FROZEN`。这个预算计算的是访问的候选数，包含不相交的包裹和哈希冲突，并不是缺失的 section 数量。密集群体跨界后集中进入同一组碰撞网格，能够在几何数据齐全时触发这一分支。

支撑查询 `world_support.comp` 存在同类问题：超预算会写入 `GRID_OVERFLOW` 祖先，经支撑指针跳跃成为无效约束，最终由 `support_apply.comp` 冻结。只修接触查询还会留下这条冻结路径。恢复检查只检查世界碰撞覆盖，不会让拥挤的邻居查询自动完成；下一步依然可能超预算并再次冻结。

提供的 `debug.log` 另有环境接触距离拒绝记录，但没有直接记录上述 GPU 查询的候选数量或冻结原因。定位依据为生产着色器的明确分支、用户补充的拥挤程度以及真实 OpenGL 复现，不能把这些环境拒绝日志直接当成该问题的证据。

## 修复

两个查询继续优先走现有网格。超过廉价查询预算时，丢弃未完成查询的接触累积或支撑候选，再对紧凑包裹数组进行一次完整查询：

- 接触查询保留原有质量权重、相对速度、刚性世界约束及移动平台速度。
- 支撑查询重新选择最高支撑与稳定的索引顺序，保留无环祖先关系。
- 接触回退排除 prepared/retired 包裹，与网格生命周期过滤一致；collision-frozen 包裹仍按现有不可动质量参与。
- 真实缺失 section、未知形状、无效结构及环境日志背压仍保留原有暂停保护。

没有增加 dispatch、GPU 缓冲区、同步读回或主线程世界查询；没有修改风力、Sable 变换、section 上传容量或客户端采集预算。超预算的完整查询开销受包裹数组容量限制；极端拥挤场景可能增加 GPU 计算时间，本修复不承诺帧率。

## 回归

`PackageGpuValidation.java` 增加以下真实 OpenGL 夹具，并接入完整回归与 repair 回归：

1. 256 个相互接触的包裹受大坐标 Sable plot 中的分散网加速，分别横向和竖直穿越边界。全部 section 和 Sable 几何在步进前上传；将廉价查询预算缩小为 64，确定性触发回退。旧接触分支在第一次跨界冻结；修复后连续三步前进，与完整网格查询的对照位置误差小于 0.01 块、速度误差小于 0.25 块/秒。独立网格中的同时 CCD 接触存在候选顺序差异，因此使用物理容差比较。
2. 20,000 个动态包裹按 40×50×10 密集排列，用 0.1 块半尺寸隔离初始穿透，所有包裹初始向上速度为 80 块/秒，再施加 Sable 分散网风力。使用未经修改的生产查询预算 8192；中心邻域超过这一候选数量。全部静态 section 与结构几何预先上传，不在步骤间请求或上传 section。检查每个包裹连续三步上行、位置有限且穿过 y=16。
3. 原来的 `localBudgetRetry` 改为 20,000 候选的实际接触检查：重复静态碰撞体不能因数量大而丢失接触，移除支撑后动态包裹正常下落。

验证命令：

```powershell
.\gradlew.bat validatePackageGpu --offline --no-configuration-cache -PpackageWorldPrefetch -I scripts/particles/validation.init.gradle
.\gradlew.bat test validatePackageGpu build --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```

设备：Java 21、NVIDIA GeForce RTX 4070 Laptop GPU、OpenGL 4.5、驱动 581.15。夹具不替代用户原存档复测，也没有测量原存档的整帧性能。

最终结果：499 项单元测试（100 个套件，零失败、错误或跳过）、967,029 项 repair GPU 断言、13,768,086 项完整 GPU 断言、正式构建及无 Sable 桥接检查全部通过。日志位于 `build/package-dense-section-repair.log`、`build/package-dense-section-final.log`；发布包为 `build/libs/createmanaindustry-0.2.6.jar`。
