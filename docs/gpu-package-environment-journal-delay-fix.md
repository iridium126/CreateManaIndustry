# 包裹环境事件排队后的错误拒绝

## 日志与定位

用户提供的 `run/logs/debug.log` 中共有 27 条 `CMI packages` 记录：26 条 `rejected environment reason=contact outside pose reach`，以及一条退出世界时的正常暂停信息。26 条拒绝均为 `contact=8` 的机器接触，集中在 `(-26,84,12)` 和 `(-18,84,17)`，此前尚未确认环境事件（`previousStep=0`）。日志中的接触中心坐标以 authority region 为原点，服务端校验前还会减去包裹半高转换为脚底位置。

`PackageAuthorityRegion.environmentReachable` 原来将比最新位置确认早超过 `historyTicks()` 的接触直接判为不可达。这把可靠环境队列的保留时间错误地限制为位置历史的时间窗口。环境日志按未确认样本数量限制容量，包裹离开机器后仍会保留旧接触；环境发送、服务端处理预算和 ACK 排队都可能让位置确认领先该接触超过 20 步。

提供的日志没有包含最新位置的确认步号，因此无法直接读取每次拒绝的时间差。用日志里的三组实际位置构造正常的较新位置确认，能确定性复现同一错误；连旧接触位置与最新位置完全相同时也会被拒绝。真实 GPU 夹具另验证接触记录保留超过 20 步的合法路径。

## 修改

超过位置历史窗口的接触不再仅因时间差被拒绝。无论接触早于还是晚于稀疏位置确认，都只授予一次现有 sweep 的空间范围：每轴 `32 + 1.25 + 0.0021` 块。延迟不会扩大距离上限，也不改变窗口内原有检查。

服务端仍检查精确 identity/lease/index/revision、连续事件序号、不重叠的模拟区间、未来时间界限、样本持续时间、包裹接触包围盒与当前机器类型；重复重传仍不重复执行已确认前缀。此修改不增加 GPU 数据、网络协议、历史缓存或每 tick 世界扫描。

拒绝日志新增 `poseStep` 和 `historyTicks`，使后续异常能直接区分接触时间与最新确认位置的时间。真正非法的事件仍保留拒绝与释放路径，没有关闭或隐藏报错。

## 验证

- 位置历史专项：旧实现 46 项测试中两项新回归失败；修复后全部通过。三组日志坐标、旧接触与最新位置相同、超距、未来模拟时间以及已释放生命周期均覆盖。
- 真实 OpenGL：包裹接触机器后离开，环境队列保留 50 步后才上报；服务端已接受第 50 步的位置确认。旧接触正确通过生产可达性检查，ACK 后队列不再重放，包裹控制权保持。
- 完整回归和正式构建使用以下命令；包括此前 20,000 包裹跨 section 修复。

```powershell
.\gradlew.bat test --tests '*PackageAuthorityRegionTest' --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
.\gradlew.bat validatePackageGpu --offline --no-configuration-cache -PpackageRepairOnly -I scripts/particles/validation.init.gradle
.\gradlew.bat test validatePackageGpu build --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```

验证日志位于 `build/package-environment-delay-before.log`、`build/package-environment-delay-after.log`、`build/package-environment-delay-gpu.log`、`build/package-environment-delay-validation.log`。真实 GPU 专项通过 967,071 项断言。尚未在用户原存档中复测。

最终结果：501 项单元测试（100 个套件，零失败、错误或跳过）、13,768,128 项完整 GPU 断言、无 Sable 桥接检查及正式构建全部通过。发布包为 `build/libs/createmanaindustry-0.2.6.jar`。
