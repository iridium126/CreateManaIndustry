# 移动结构扫掠与几何发布引起的单体冻结

## 本轮日志

`run/logs/debug.log` 中有 315 条 `MOVING_GEOMETRY` 样本（20 条采样时仍冻结）、123 条 `MOVING_SWEEP`（5 条仍冻结）、282 条 `SECTION_MISSING`（1 条仍冻结）。这些是限流采样的事件记录，不是独立包裹数量；`stillFrozen=false` 表示该历史事件在采样时已经恢复。

本轮没有上一轮的 `SUPPORT_CEILING` 或 `WORLD_OPPOSING`。移动扫掠失败全部报告 `iterationLimit=32`，例如结构 4 的节点 9 在 step 3436 分别停在归一化时间 `0.11183567`、`0.74553436`。结构 1 的节点 0 也出现同类失败，因此不能只归因于某一个 Sable 方块。

日志没有记录当时的完整结构姿态和碰撞盒，不能逐条精确重放原存档。下面的夹具复现了对应的算法失效机制，原游戏场景仍需复测。

## 旋转擦边路径

旧的 `movingFaceAdvance` 把整个剩余时间内的最坏导数变化加到当前闭合速度。高速包裹沿旋转面运动时，线性速度与面旋转可以相互抵消；接近路径最小间距时，这个过大的界限导致每次仅前进极小时间，32 次迭代仍不能确认完整路径。

无碰撞回归使用一块 `192 × 256 × 1` 的面板、绕 Y 轴旋转 0.1 弧度和半宽 0.3125 的自由包裹。路径与旋转面的最小间距为约 0.0004 块。旧实现在 32 次后只到达 `t=0.064403415`，尽管独立的双精度 SAT 参考检查确认整条路径没有碰撞。

修复使用局部二次保守界限 `gap - closing*h - curvature*h*h/2` 求可安全推进的时间。包裹投影的导数在法向分量不会过零时保留符号，在绝对值拐点附近保留上界；同时保留四元数插值速率误差、平移、旋转和缩放项。仍使用原来的 32 次预算和 0.9 推进系数，没有增加 dispatch、Body 字段或同步回读。不能确认路径时仍冻结。

回归也把最小间距改为 -0.0004，验证中途的真实碰撞能够被发现，而不是直接接受无碰撞终点。完整物理管线验证三个夹具均不冻结，终点不穿透；无碰撞路径保持原有自由位移。

## 已完成的 BVH 发布

`PackageMovingCollisionCache.tick` 原来只在有几何捕获预算的循环里领取 worker 结果。静态 section 捕获先用完预算时，即使移动 BVH 已构建完毕，当前 snapshot 仍保持空，包裹继续报告 `MOVING_GEOMETRY`。

修复在每个来源采样当前 revision 后，领取已经完成的不可变 worker 结果。该过程不等待 worker、不读取方块，也不重新开启捕获。过期 revision 的结果继续丢弃，真实几何更改仍要求重新捕获和上传。尚未捕获完成的来源或新上传数据仍可能产生暂时的几何等待；本次没有改变方块事件的失效规则。

单元回归先完成 worker，再以零预算执行 tick，检查 snapshot 立即发布且没有额外捕获。随后在结果完成与发布之间再次修改来源 revision，检查旧结果不能发布，之后的新结果可以恢复。

## 验证

针对性 GPU 检查包括上述擦边和真实碰撞、实际包裹物理管线、相同姿态不产生虚假角速度，以及 384 个固定随机种子的旋转/平移/缩放安全推进样本；后 224 个使用三轴旋转。每个有效安全区间使用独立双精度 SAT 参考检查最多 129 个时间点。针对性检查通过 50,524 项断言。

```powershell
.\gradlew.bat test --tests '*PackageMovingCollisionCacheTest' --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
.\gradlew.bat validatePackageGpu -PpackageMovingAdvance --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
.\gradlew.bat test validatePackageGpu build --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```

复现失败日志：`build/moving-advance-before.log`、`build/moving-cache-before.log`。修复验证日志：`build/moving-freeze-targeted.log`、`build/moving-freeze-safety.log`、`build/moving-freeze-validation.log`。真实驱动为 NVIDIA GeForce RTX 4070 Laptop GPU / OpenGL 4.5 / 581.15。

最终完整验证通过：506 项单元测试（101 个套件，无失败、错误或跳过）、14,306,393 项 GPU 断言、无 Sable 桥接检查及正式构建。发布包为 `build/libs/createmanaindustry-0.2.6.jar`，其内嵌主 JAR 中的 `moving.glsl` 与 `PackageMovingCollisionCache.class` 已逐字节哈希核对匹配本轮源文件/编译输出。未在原存档中复测，也未测量整帧耗时。
