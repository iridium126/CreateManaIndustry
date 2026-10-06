# 自由包裹跨 section 时的机械结构姿态丢帧修复

日期：2026-10-05。用户场景为存在 Create contraption 时，自由包裹跨 section 边界轻微停顿。

## 原因与修复

`PackageCollisionRuntime.tick` 在同一主线程预算内交替安排静态 section 与机械结构碰撞采集。包裹预取新的 section 后，静态采集可能用尽预算，随后调用 `movingCache.tick(0)`。旧实现先增加姿态帧号，然后直接返回，使已存在机械结构的 `poseFrame` 落后于当前帧。

`captureHistory` 因姿态不完整记录空缺；该历史不可覆盖。物理运行时无法获得这一输入 tick 的机械结构姿态，后续自由包裹步进被阻塞，直到时钟历史缺口恢复。这也会影响没有接触机械结构的包裹。没有机械结构时，空集合的姿态覆盖检查仍成功，所以新 section 采集不会触发这条丢帧路径。

修复使每 tick 的轻量姿态、存活状态与几何版本采样始终执行。剩余预算为零时，不打开几何捕获游标、不消费方块碰撞形状、不提交或轮询 BVH 工作。真实姿态读取失败仍会撤销覆盖；缺失静态 section 或机械结构几何仍按既有规则冻结相关包裹。预取算法、碰撞着色器和主相机剔除无需修改。

每个零预算 tick 增加的是当前已发现结构的轻量姿态读取（结构数量受缓存容量限制）；没有新增 GPU dispatch、同步等待或全量世界查询。这是保证输入历史连续所需的主线程工作，实际耗时仍计入采集诊断。

## 验证

- 修复前，`staticSectionCaptureCannotStarveMovingInputHistory` 稳定失败：静态采集用掉 300 个模拟时间单位，超过 250 的预算，机械结构姿态丢帧。
- 修复后，上述同一场景获取新的 previous/current 姿态，保留完整几何，并且不增加几何采集工作。另一个回归验证零预算下的源版本变化和几何缺失；姿态异常仍撤销覆盖。
- GPU 回归覆盖 X/Y/Z 三轴、两个方向，跨越 0 和 16 的 section 边界；交替使用零剩余预算。平台携带包裹连续前进，自由运动包裹每步与无机械结构场景的对照误差小于 `1e-4`，均无冻结。
- 全部 491 项单元测试通过（97 个测试套件，无失败、错误或跳过）。完整包裹 GPU 回归通过 13,615,386 项断言，涵盖真实缺失几何的冻结与恢复。正式 `build` 及无 Sable 环境桥接检查通过。

设备：Java 21、NVIDIA GeForce RTX 4070 Laptop GPU、OpenGL 4.5、驱动 581.15。上述确定性预算复现及真实 OpenGL 验证没有替代用户原存档的游戏画面复测。

```powershell
.\gradlew.bat test --tests '*PackageMovingCollisionCacheTest' --offline --no-configuration-cache
.\gradlew.bat validatePackageGpu build --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```
