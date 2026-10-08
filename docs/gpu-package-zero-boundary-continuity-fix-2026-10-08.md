# GPU 自由包裹 X=0 边界短暂停顿修复（2026-10-08）

## 现象与证据

用户确认：只有一个包裹短暂停顿，其他包裹继续移动；包裹悬空且没有接触方块或其他包裹；边界是 X=0 的竖直平面。当前 debug.log 没有自由包裹碰撞冻结诊断，最后的权限暂停记录是退出世界。

X=0 同时属于 16 格 section 边界和 64 格 PackageRegion 权限区域边界。旧 PackageAuthorityRegion.deltaStepped 在提交跨界位置后立即 release，随后等待 OFFER → FINAL_BASELINE → ACTIVE 重新取得 GPU 控制权。这个过程会退役单体 GPU 状态，而不是设置 COLLISION_FROZEN，因此现有碰撞冻结日志不会报告它。

新增真实 GPU 回归使用两个互不接触的悬空包裹、已上传的空 section，并向服务器提交实际 GPU 位置。使用旧释放策略时，测试在 X=0 失败：
“X=0 released a same-owner package without a collision marker”。
采用连续控制策略后，正负两个方向共 106 项断言通过。这验证了代码中的停顿路径；尚未在用户原存档中重跑，日志本身也不能追溯确认当时的释放事件。

## 修改

- 生产服务器允许已激活的 lease 在目的区域仍由同一玩家负责、或尚未指定其他玩家且当前玩家订阅覆盖该区域时继续模拟。跨过普通空间边界不再重新申请控制权。
- 通道的区域原点、index、lease 和确认基线保持稳定，继续使用现有坐标编解码格式。初始 OFFER、最终基线和就绪阶段仍要求包裹处于申请区域。
- 目的区域已由其他玩家负责、目的订阅失效、原区域关闭、心跳或包裹退出等正常释放流程继续有效。先提交跨界位置，再执行真实交接。
- 力场兴趣区域包含原控制区域及 GPU 完成的 section 占用扫描所覆盖的实际区域，负坐标使用 floorDiv；排序、去重避免映射迭代顺序造成历史输入重置。不会遍历全部包裹或同步读回 GPU。
- 跨出原区域后的实际释放增加 DEBUG 日志 “free package authority handoff”，包含 reason、id、generation、lease、source、destination、owner、pose。reason 区分 destination_owner_changed、destination_subscription_unavailable、source_region_closed 和 explicit_release_outside_anchor。
- 另外修复历史碰撞表内容不变时，仅因未驻留需求或引用集合变化而轮换表银行的问题。只有实际 slot、revision、active 或 flags 内容改变才重写；相同内容复用仍保留 GPU 引用与 fence 保护。它是独立的等待风险，不作为本次单体停顿的主因。

## 回归验证

定向单元测试覆盖：跨 X=0、后续 section 和另一个 64 格区域保留身份及库存；目的权限变化提交后释放；连续控制不能允许申请区域外的新包裹；实际力场区域的负坐标、去重和清理。

定向 GPU 命令：
```powershell
.\gradlew.bat validatePackageGpu -PpackageZeroBoundary --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```
旧策略复现日志：build/zero-boundary-before.log。
修复策略通过日志：build/zero-boundary-after.log（106 assertions）。
碰撞表需求变化定向日志：build/table-content-targeted.log（213 assertions）。

完整验证命令：
```powershell
.\gradlew.bat test validatePackageGpu build --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```
完整结果见 build/zero-boundary-validation.log。

完整验证通过：106 个测试套件、554 个单元测试（0 failures / 0 errors）；真实 GPU 14,308,931 项断言通过；Sable 缺失环境检查及 build 通过。
产物：build/libs/createmanaindustry-0.2.6.jar；已核对内嵌运行时中 62 个相关编译类与 JAR 字节一致。
SHA-256：507104A144704CEB202F5D445789057D1B5A225A210FC6BFCBEC8BFF43B27BE0。
