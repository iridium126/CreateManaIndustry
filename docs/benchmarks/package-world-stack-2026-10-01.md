# 包裹世界碰撞与堆叠 GPU 基准

环境：RTX 4070 Laptop GPU、OpenGL 4.5、NVIDIA 581.15。命令：

```text
.\gradlew.bat validatePackageGpu -PpackageWorldBenchmark -PpackageStackBenchmark --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```

每个规模 10k、65536、131072 均预热后独立运行三次。世界碰撞每次运行 20 个预热步和 30 个计时步；堆叠每次运行 50 个预热步和 40 个计时步。表中 GPU p95 与 CPU submit p95 是各自三轮输出的范围，CPU 值只计 Java dispatch/提交部分。

131072 包裹的世界 kernel 数据：

| 场景 | GPU p95 范围 | CPU submit p95 范围 | 说明 |
|---|---:|---:|---|
| `world_air` | 2.237–2.248 ms | 0.0043–0.0076 ms | 粗粒度覆盖、无碰撞 |
| `world_dense_contacts` | 4.605–4.616 ms | 0.0010–0.0012 ms | 密集接触诊断；邻近重叠指标约 0.454，不能单独作为质量通过 |

生产路径调用 `stepFreeMoving`，即启用四轮 support projection。131072 的 `support4` 堆叠场景数据：

| 场景 | GPU p95 范围 | CPU submit p95 范围 | 额外 GPU 缓冲 | 质量检查 |
|---|---:|---:|---:|---|
| `aligned_still` | 5.329–5.339 ms | 0.0037–0.0053 ms | 4,194,336 B | 三轮均通过 |
| `staggered_driven` | 5.565–5.603 ms | 0.0041–0.0060 ms | 4,194,336 B | 三轮均通过，131024 个运动体且无 coverage fallback |

在同一 `staggered_driven` 场景，`jacobi4` 的 GPU p95 为 7.165–7.201 ms 且质量检查未通过；`jacobi16` 为 20.838–20.973 ms 且质量检查未通过。当前生产的 `support4` 对应项通过了该基准的重叠、地形穿透和完整覆盖检查。基准合成了固定地面和运动序列，并不覆盖实体推力、鼓风机、动态 Create/Sable 结构、链轨、灯光上传、包裹网格绘制或多人同步。

这份报告只记录当前机器的组件数据，没有同一负载的旧版对照，也不是整帧测量。尚未据此声称 131072 活动包裹达到 16.7 ms 整帧目标；游戏内视觉、服务端 tick 和实际结构/实体混合负载仍待验收。
