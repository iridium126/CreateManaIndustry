# 自由包裹动态扫掠碰撞（2026-10-01）

新增的 `dynamic_sweep` GPU pass 在自由包裹预测后、Jacobi 接触前运行。它从当前端点空间索引查询有界扫掠包围盒，以两个包裹的步前/步后中心做相对 Minkowski AABB sweep，选最早的接触，去除进入法线的相对剩余位移和法向速度。普通低速运动当单步分量不超过自己的半尺寸时跳过 CCD；该条件下两包裹的相对位移不会大于接触厚度。继续由 Jacobi 处理静态重叠、堆叠和慢速接触。算法沿用现有 AABB 碰撞近似，不处理包裹转动扫掠。

查询按最大邻居单步位移一 cell 扩大包围盒；单体更长的运动只在静态世界扫掠已经截停时保留该路径。超过 32 格任一轴、1024 查询 cell 或 8192 个候选访问时，恢复该包裹的步前位置和速度并标记 handback。邻居步长超过一 cell 会由其自己的调用触发 handback并排除于相对 sweep。该限制使空间与候选工作有上限，不截断查询结果。

## 真实 GPU 正确性

RTX 4070 Laptop GPU / NVIDIA 581.15 / OpenGL 4.5。专用夹具以两只 AABB 包裹从 x=−1.25/+1.25 以 ±25 blocks/s 对撞，在 20Hz 单步内本会互相穿过；LINKED、EXACT_RANGES、BOUNDED_LINKED 均保持正确顺序、至少一个完整接触厚度、法向速度归零且没有触发回退。±10000 blocks/s 超限用例恢复步前位置与速度并标记回退。静态高速碰撞的既有扫掠断言保持通过。

验证命令：

```powershell
.\gradlew.bat -I scripts/particles/validation.init.gradle validatePackageGpu -PpackagePhysicsOnly --offline --no-configuration-cache
.\gradlew.bat -I scripts/particles/validation.init.gradle validatePackageGpu -PpackageMovingOnly --offline --no-configuration-cache
.\gradlew.bat -I scripts/particles/validation.init.gradle validatePackageGpu --offline --no-configuration-cache
```

本轮分别通过 6,688 项物理断言、4,147 项移动结构断言和 21,488,593 项完整包裹 GPU 断言。覆盖还包括 support projection 1024 包裹堆栈移除支撑后持续下落且无 handback、满 131072 活动移动结构负载、通用池、身份/读回、锁链和绘制路径。

## 131072 同场景物理计时

固定场景为 64×64 错层堆叠，20Hz、support4 + LINKED、静态地面、同一 GPU 推力 probe；每轮 50 步预热、40 个 GPU timer 样本，共三轮。GPU timer 覆盖 probe 与物理调用，CPU 样本记录提交时间；不包括绘制、灯光、网络或整帧时间。质量要求全量有效、零 handback/非有限值、最大包裹重叠 <0.002、地形穿透 <1e−4、活动比例 >99%。

| 数量 | 轮次 | GPU p50 (ms) | GPU p95 (ms) | CPU 提交 p95 (ms) | 最大重叠 | 回退 | 质量 |
|---:|---:|---:|---:|---:|---:|---:|:---:|
| 131072 | 1 | 5.458 | 5.511 | 0.042 | 0 | 0 | PASS |
| 131072 | 2 | 5.458 | 5.508 | 0.030 | 0 | 0 | PASS |
| 131072 | 3 | 5.546 | 5.626 | 0.040 | 0 | 0 | PASS |

三轮 GPU p95 中位数为 5.511ms，p95 区间 5.508–5.626ms。此前同场景 support4 报告为 5.565–5.603ms；两组区间重叠，本测量没有显示 CCD 引入可辨识的整步回退。新样本和 CSV 由 `-PpackageCcdBenchmark` 输出到 `build/package-dynamic-ccd-benchmark*.csv`。这些数字是物理组件证据，不证明目标 60 FPS 整帧、多玩家或真实 Create 物流场景达标；游戏视觉和客户端测试由用户执行。
