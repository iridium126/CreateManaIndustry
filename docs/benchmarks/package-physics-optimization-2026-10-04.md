# GPU 包裹物理性能优化

环境：RTX 4070 Laptop GPU、OpenGL 4.5、NVIDIA 581.15。测试命令：

```text
.\gradlew.bat validatePackageGpu -PpackageStackBenchmark --offline --no-configuration-cache -I scripts/particles/validation.init.gradle --console=plain
```

在相同验证程序的连续运行中，对 131072 个包裹的生产 `support4` 步进各测三轮，每轮预热后记录 GPU 时间分位数。表格采用三轮各自 p95 的中位数，避免个别样本左右结论。

| 场景 | 优化前 p95 | 优化后 p95 | 变化 |
|---|---:|---:|---:|
| `aligned_still` | 9.881 ms | 9.875 ms | 基本持平 |
| `staggered_driven` | 10.187 ms | 9.927 ms | -2.6% |

主要改动是将支撑树的两轮指针跳跃合并成一个 compute pass。合并 pass 会根据输入代重算父节点的一轮摘要，再按原来的组合次序更新当前节点，因此高度、间距、速度及无效父索引的处理保持原语义。131072 个包裹原先需要 17 次全量指针跳跃 dispatch；现在是 9 次，减少 8 次全量 dispatch、输出写入和全局 SSBO barrier。约 131k 的堆栈高度仍逐层通过验证，支撑移除、混合质量和运动堆栈 fixture 也通过。

Java 侧缓存各 compute program 的 `uCount`。包裹数量不变时不再为每次 `bind` 重发相同 uniform；数量变化后每个 program 在下次使用时更新一次。CPU submit 计时约为几微秒，样本抖动与测量粒度相近，当前数据不足以声称 CPU 用时有显著下降；这项改动能确定减少重复 LWJGL/驱动调用。

也测过把快速扫掠的 cell 标记合并进空间哈希 pass。虽然少了一次全量 dispatch，但 131072 个驱动包裹的 GPU p95 中位数升至 10.175 ms，因此该改法未保留。最终验证共通过 12,299,275 项断言。

这组结果来自 RTX 4070 Laptop 的离线 fixture，不等于游戏实景整帧性能；它不涵盖真实 Create 结构、实体推力、鼓风机、灯光上传和 GPU 绘制。
