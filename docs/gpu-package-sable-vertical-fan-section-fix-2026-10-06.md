# Sable 竖直鼓风机跨 section 停顿修复

日期：2026-10-06。用户确认场景为 Sable 结构内无喷嘴的竖直鼓风机。

## 原因

自由包裹碰撞预取原来只取当前位置与重力预测终点的 AABB。默认预测时间为 0.5 秒，竖直终点为 `y + vy * 0.5 - 16 * 0.5²`。当包裹缓慢上升、速度低于 8 块/秒时，终点低于当前位置，预取范围便不包含继续上升的路径。

风场持续施力可以维持或提高上升速度。包裹实际到达相邻 section 的碰撞保护边界时，那个 section 才被请求；预算内的采集和异步上传尚未完成，物理按正常的缺失数据保护冻结包裹，表现为边界停顿。单纯增加姿态采样优先级无法补上这条预取路径。

## 修复

`world_prefetch.comp` 使用保持当前速度的终点作为上界，同时保留重力终点作为下界。该范围既覆盖风场维持的上升，也覆盖普通抛物线在预测时间内的最高点。完整预测与高速一 tick 回退使用相同规则。

修改只影响预取范围，不改变鼓风机强度、Sable 坐标转换、碰撞解算或冻结规则。每包裹每次仍最多请求一个最近的缺失 section；64-section 完整扫描、256-section 安全回退、定长反馈、去重和非阻塞读回限制保持不变。没有新增 GPU dispatch、SSBO 或主线程世界查询。

## 确定性复现与验证

GPU 夹具使用大坐标的 Sable plot 风场记录、结构碰撞 BVH，以及 `(8, 12.75, 8)`、初始向上速度 4 块/秒的自由包裹。相邻 section 的采集和上传模拟为请求后三个物理 tick 完成。

- 旧实现首次预取没有请求上方 section；物理在 tick 索引 5、中心高度 `14.424804` 冻结。
- 修复后，首次扫描即请求上方 section；三个 tick 后上传完成，包裹连续完成十二步并穿过高度 16，未冻结。结构姿态采集还使用零剩余几何预算，兼顾上一批姿态历史修复。
- 额外预取回归验证长水平位移触发安全回退时，弱上行速度仍请求正确的上方保护 section；131072 包裹覆盖反馈、最近 section、去重、溢出和过期表检查一并通过。

| 检查 | 结果 |
| --- | --- |
| 完整包裹 GPU 回归 | 13,615,418 项断言通过 |
| 预取专项 GPU 回归 | 122 项断言通过 |
| 单元测试 | 491 项通过，97 个套件，无失败、错误或跳过 |
| 正式构建及无 Sable 桥接检查 | 通过 |

设备为 Java 21、NVIDIA GeForce RTX 4070 Laptop GPU、OpenGL 4.5、驱动 581.15。验证为真实 OpenGL 的确定性夹具及现有 Sable API 单元回归，尚未复测用户原存档画面。

```powershell
.\gradlew.bat validatePackageGpu test build --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
.\gradlew.bat validatePackageGpu --offline --no-configuration-cache -PpackageWorldPrefetch -I scripts/particles/validation.init.gradle
```
