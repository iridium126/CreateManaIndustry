# 包裹接触后端对照（2026-09-30）

**后续状态（2026-10-01）：** 当前 `PackageWorldRuntime` 通过 `stepFreeMoving` 使用 support4 compute；131072 错位持续推力场景三轮质量检查通过，GPU p95 为 5.565–5.603ms，见[当前世界堆叠基准](package-world-stack-2026-10-01.md)。本页 PhysX 与 compute 数字来自不同的计时路径，不能直接当作精确倍率；PhysX 在该规模仍有接触质量失败，且其 4/16 轮完成步远慢于 compute kernel，**没有显示足以接入生产的性能或精度优势**。因此 NVIDIA PhysX 不进入本版运行时，也不增加以 PhysX 能力选举 authority 的协议；所有支持环境沿用 OpenGL compute，未有 PhysX 的环境自然走同一 compute 管线。若后续版本的同口径基准证明 PhysX 有优势，再引入能力协商与后端选择。

同机为 RTX 4070 Laptop GPU（8 GiB），NVIDIA 581.15/OpenGL 4.5，CUDA driver API 13000，Windows x64。通过 `scripts/particles/build-physx.ps1` 在项目缓存中获取官方 ovphysx 0.6.3、匹配的 OVStage 0.2.0.377349；PhysX SDK 为 5.11.0。仅独立基准链接 native SDK，模组运行时依赖没有增加。初次尝试将 `.refs/PhysX` 的独立 host 源码链接官方 GPU DLL，首次模拟发生 native 访问异常；这两个二进制不再混用。

夹具为 64×64 平面错层的单位 AABB，地面顶面 y=1，初始速度 (0,-1,0)，重力 32，水平逐层脉冲 `(.08 cos(t*.15+layer*.12), 0, .06 sin(...))`，禁用箱体角运动。compute 候选使用支撑传播 `supportProjection=true`、四轮 Jacobi 和 linked 网格。该路径在 9 月 30 日测试时属于实验配置；10 月 1 日已接入 `PackageWorldRuntime` 的 `stepFreeMoving`，后续状态和不同场景的测量见本页开头链接，不能将两份数据当作同一场景或同一计时口径。50 步预热、40 步测量、每组独立重复三次；下表是三次各自 p95 的中位数，单位 ms。质量从预热结束及每 8 个测量步的状态检查取峰值/最低活跃数；判据为最大箱间 AABB 穿透 <0.002 方块、地面穿透 <0.0001 方块、运动箱体 >99%、无非有限值及无回退。接触/patch 溢出使该轮直接失败，不会把缺失接触算成加速。

| 数量 | 频率 | compute linked：GPU p95 | PhysX GPU TGS 4轮：完成步 p95 | PhysX CPU TGS 4轮：完成步 p95 | 质量 |
|---:|---:|---:|---:|---:|---|
| 10000 | 20Hz | 1.081 | 3.689 | 13.869 | compute 3/3；两种 PhysX 均 0/3 |
| 65536 | 20Hz | 3.241 | 37.333 | 132.083 | compute 3/3；两种 PhysX 均 0/3 |
| 131072 | 20Hz | 5.894 | 131.331 | 309.225 | compute 3/3；两种 PhysX 均 0/3 |
| 131072 | 60Hz | 5.710 | 55.592 | 未测 | compute 3/3；PhysX GPU 0/3 |

对照 30Hz 和其余数量均在 CSV 中。131072 箱体 PhysX GPU 60Hz/16轮 p95 为 101.157ms，最大箱间穿透 0.03885、地面穿透 0.000307；64轮 p95 为 318.569ms，最大箱间穿透 0.07847，均 0/3。10000 箱体 60Hz/16轮达到当前数值筛查（3/3，最大穿透 0.000318），但完成步 p95 为 6.349ms；同规模 compute linked GPU p95 为 1.072ms。131072/20Hz/4轮的 PhysX GPU 场景内部堆内存为 5.85 GB，60Hz/4轮为 3.58 GB；不含游戏渲染、OpenGL 桥接及其他资源。初始较小的 GPU contact/patch 配额曾报告溢出并被判失败，随后容量提高至每体 64/16 项才取得上表数据。

compute 列是 OpenGL GPU timer 的物理 pass 时间，完成步 p95 另列在原始 CSV；PhysX 列是独立工作线程从施加脉冲到 `fetchResults(true)` 完成的墙钟时间，不是 CUDA kernel 独占时间。它们足以判断这些候选配置未显示可复现的总成本优势，不能代替 GPU 内部分阶段计时。独立测试的完成等待和全量状态读回仅用于测量/质量检查，不允许搬到客户端主线程或渲染线程。当前 shader 接触筛查通过也不等于与 Create 的视觉对齐；游戏内录像与客户端行为验证由用户执行。

**本节记录 2026-09-30 基准完成时的项目状态。** PhysX GPU 在 131072 活动箱体下未达到性能或质量门槛，不接入生产，亦不因 NVIDIA 品牌优先成为权威客户端。支撑传播 compute 随后接入 `PackageWorldRuntime.stepFreeMoving`，但仅凭该微基准仍不能证明 Create 视觉或完整游戏负载通过。默认配置及生产资源验收门禁仍关闭；真实包裹只会在双方显式实验配置且逐包裹 admission 条件满足时转入 GPU，否则继续由 Create 管理。Iris/阴影画面、普通/Flywheel 绘制停用、观察客户端、链路增量同步及整帧/服务端 tick 测量仍待游戏验证；总带宽比例验收已按最新范围移至后续版本。

可复现命令：

```powershell
.\gradlew.bat benchmarkPackageCompute --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
.\scripts\particles\benchmark-physx.ps1 -Backends gpu -Output build/package-backend-physx-gpu
.\scripts\particles\benchmark-physx.ps1 -Frequencies 20 -Backends cpu -Output build/package-backend-physx-cpu
.\scripts\particles\benchmark-physx.ps1 -Counts 10000,131072 -Frequencies 60 -Iterations 16,64 -Backends gpu -Output build/package-backend-physx-gpu-iterations
.\scripts\particles\benchmark-physx.ps1 -Counts 0,1,63,64,65 -Frequencies 20 -Backends gpu -Warmup 5 -Samples 2 -Trials 1 -Output build/package-physx-boundaries
```

原始数据：[compute 汇总](package-backend-compute-2026-09-30.csv)、[compute 样本](package-backend-compute-samples-2026-09-30.csv.gz)、[PhysX 汇总](package-backend-physx-2026-09-30.csv)、[PhysX 样本](package-backend-physx-samples-2026-09-30.csv.gz)。PhysX 边界 0/1/63/64/65 均通过一次实际 GPU 模拟与读回；后续加入输出缓冲 64B 哨兵并重复测试，均未越界。native 质量探针还对这些规模及 129 项与朴素全对检查交叉验证。Gradle 构建通过，160 个 Java 测试 0 失败，Sable 缺失运行检查通过；Hex 及游戏内视觉没有在本轮重测。
