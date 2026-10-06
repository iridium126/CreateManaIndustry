# 复杂邻接 section 的水平跨界停顿修复

日期：2026-10-06。布局为 b 北侧的 a 存在 contraption，b 存在 Sable 结构中的鼓风机，b 东侧的 c 存在装分散网的鼓风机；自由包裹从 c 向 b 水平运动。

## 定位与复现

已上传的静态 section 本身不需要重新上传，但物理步进还依赖当前输入 tick 的结构发现和姿态历史。旧 `PackageCollisionRuntime.tick` 在结构发现之前处理光照失效通知，并在每三个 tick 中的一个 tick 优先采集光照。

跨水平 section／列时，光照请求或失效通知可能用尽共享预算。随后结构发现拿到已过期的 deadline，`movingAvailable` 变为 false；即使 contraption 和 Sable 的姿态、几何及目标 section 都已准备好，`captureHistory` 仍会留下不可回放的空缺。物理运行时在这个输入 tick 等待结构历史，造成停顿。这与上一批零预算姿态漏采、竖直上行预取范围不足是不同的触发路径。

确定性回归直接调用生产调度器和真实 `PackageLightCache`、`PackageMovingCollisionCache`，同时保留 kind=0 的 contraption 与 kind=1 的 Sable 源。模拟 250 的共享预算及 300 的光照采样耗时：旧顺序稳定产生空历史，修复后两个源的历史均完整。另一个分支覆盖光照失效通知本身耗尽预算。测试同时验证真正未完成的结构发现仍阻止历史回放。

## 修改

引入可独立验证的 `PackageCollisionCaptureSchedule`，先完成有 deadline 限制的结构发现，再处理光照通知、光照采集和碰撞几何。仍使用同一个绝对 deadline；静态和移动几何的轮换、光照优先级轮换及零剩余几何预算下的姿态采样均保留。没有提高主线程预算，没有把未知结构当成空场景，也没有改变风场强度或碰撞着色器。

结构发现诊断现在只记录发现阶段自身的耗时，避免把此前的光照采集计入发现耗时。

## 组合 GPU 回归

`complexHorizontalSectionProgress` 按 a=(0,0,-1)、b=(0,0,0)、c=(1,0,0) 布置旋转 contraption、Sable 风场与分散网。静态碰撞 section、结构 BVH 和精确历史版本全部先上传；包裹从 x=19 向西跨过 x=16。

- 覆盖 Sable 水平风场和用户此前确认的竖直风场；c 的分散网视线在跨界后经过 b/c 两个 section。
- 每一步都持续向西移动、没有碰撞冻结或异常侧向接触；已驻留 section 没有产生额外预取请求。
- GPU 夹具验证完整输入下的组合碰撞与风场路径；旧调度的失败由上述预算回归复现，不将正常 GPU 解算误判为缺陷。

| 检查 | 结果 |
| --- | --- |
| 单元测试 | 493 项通过，98 个套件，无失败、错误或跳过 |
| 完整包裹 GPU 回归 | 13,615,496 项断言通过 |
| 正式构建及无 Sable 桥接检查 | 通过 |

设备：Java 21、NVIDIA GeForce RTX 4070 Laptop GPU、OpenGL 4.5、驱动 581.15。验证覆盖确定性共享预算复现和真实 OpenGL 夹具，尚未复测用户原存档画面，也未测量整帧性能。

```powershell
.\gradlew.bat test --tests '*PackageCollisionCaptureScheduleTest' --offline --no-configuration-cache
.\gradlew.bat validatePackageGpu build --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```
