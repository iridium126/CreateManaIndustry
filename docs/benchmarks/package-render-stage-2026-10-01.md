# 包裹间接绘制准备阶段基准

环境：RTX 4070 Laptop GPU、OpenGL 4.5、NVIDIA 581.15。命令：

```text
.\gradlew.bat validatePackageGpu -PpackageDrawPassOnly -PpackageDrawPassBenchmark --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```

每个规模先预热至少 30 次且至少 1 秒，再进行三轮、每轮 120 次提交。GPU p95 与 CPU submit p95 是三轮结果范围。131072 个包裹对应 196608 个 box/hook 实例。结果：

| 模式 | GPU p95 | CPU submit p95 |
|---|---:|---:|
| `legacy_group_reference` | 0.291840–0.294912 ms | 0.0029–0.0040 ms |
| `current_group` | 0.292864–0.293888 ms | 0.0038–0.0040 ms |
| `split_pass` | 0.183296–0.188416 ms | 0.0030–0.0035 ms |

GPU 时间测量包裹剔除与间接命令准备；该 fixture 验证了全部 box/hook 实例仍进入命令缓冲。它没有调用最终 mesh draw，也没有测量顶点/片元着色、阴影、Iris、场景深度复杂度或完整帧。`split_pass` 在这个隔离 fixture 中更快，但仍须以游戏内主渲染和阴影画面确认 pass 覆盖及总整帧收益；单独的 kernel 数据不足以认定最终 draw-call 合并或整帧目标完成。
