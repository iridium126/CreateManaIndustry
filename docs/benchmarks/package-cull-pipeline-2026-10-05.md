# GPU 包裹主相机剔除优化

按距离统一、Iris 去重、粒子扫描边界、共享判定、导入融合的顺序实施。

## 最终路径

- 自由包裹主相机距离使用 `CMIParticleEngine.renderDistanceBlocks()`，即 `particleFadeDistance + 24`，默认 120 格。无光影和 Iris 都在 GPU 上做距离平方比较；Iris 主相机不再使用包裹尺寸与 `Entity.getViewScale()` 决定距离。包裹仍为原有不透明绘制，未新增渐隐。
- 链包裹保留原有可见性语义：无光影使用保守几何范围；Iris 主相机按所属传送带的可见性掩码。Iris 自由包裹仍保留膨胀 AABB 视锥测试。
- 无光影：`select → reserve → import/count → prefix → scatter`。融合不可用时使用独立 `draw_count`，仍缓存可见性供 scatter 使用。
- Iris staging：`select → reserve → import-only`，不清空或生成无人消费的基础绘制命令。主绘制边界独立准备 GBUFFER；阴影仍使用上一成功 generation，独立准备 SHADOW，保留原生阴影距离与视锥策略。
- `basicDrawReady` 随 generation 提交。只导入数据的 generation 拒绝暴露基础 command/instance bank；同帧关闭光影时跳过基础绘制，下一帧重新准备，防止消费旧命令。

`pool_reserve` 把普通粒子上界 `base` 临时写入当前 counter 的 word 1。`prepare_dispatch` 与 `keygen` 在 `uPackageStaged` 为真时使用它；总 live count 仍包含包裹。帧末 `capture` 将 word 1 覆盖为原有 translucent census，再执行 snapshot/fence，所以读回布局与语义不变。没有包裹 staging 的帧使用原来的总粒子上界。

`chunks/frustum_culling.glsl` 共用距离、球、插值端点范围和 AABB 的平面测试。`packages/draw_cull.glsl` 共用包裹半径、父级缩放和几何可见性，避免导入版本与独立剔除版本漂移。普通和 Iris scatter 均消费本次计数生成的可见性；融合 scatter 通过 reservation/selection 只访问本次已接受候选，避免容量不足、隐藏或空 generation 遗留的标记进入绘制。

融合导入额外使用 SSBO 11–13，要求 compute shader 至少支持 14 个 storage blocks；低于该能力时保留独立计数版本。Iris 使用独立编译的精简 import-only 程序，避免为融合计数承担额外的 shader 资源开销。所有 13 个程序完整编译后才替换旧 bundle。

## 测量

RTX 4070 Laptop，NVIDIA 581.15，OpenGL 4.5 隐藏 context。10000/65536/131072 个候选，一半自由、一半带 rig 的链包裹，两个 mesh。每种模式至少 30 次且 1 秒预热，三轮各 120 次；查询等待只存在于基准外壳，生产不新增等待或数量读回。

`all` 为全部可见，`narrow` 使用相机 X 方向 ±64 的平面，每个规模保留相同的 68 个部件实例。各对照使用相同的候选、材质分组和可见实例数量；距离参数在此基准中关闭，以隔离流水线变化。

- `unfused_main`：本次共享判定和可见性缓存后的独立计数版本。
- `fused_main`：无光影导入/计数融合版本。
- `iris_duplicate`：独立导入/基础计数，然后准备同代 GBUFFER。
- `iris_import_only`：精简导入，然后准备同代 GBUFFER。

三轮 GPU p95 的中位数（ms）：

| 候选数 | 可见性 | 独立计数 | 融合计数 | Iris 重复准备 | Iris 只导入 |
|---:|---|---:|---:|---:|---:|
| 10000 | all | .036864 | .032768 | .059392 | .049152 |
| 10000 | narrow | .035840 | .031744 | .051200 | .034816 |
| 65536 | all | .076800 | .065536 | .167936 | .153600 |
| 65536 | narrow | .065536 | .060416 | .079872 | .072704 |
| 131072 | all | .297984 | .283648 | .477184 | .447488 |
| 131072 | narrow | .283648 | .274432 | .303104 | .283648 |

131072 全可见时，融合计数 GPU p95 减少约 4.8%，Iris 去重减少约 6.2%。无光影 CPU 提交 p50 从约 .0028–.0031 ms 增至 .0032 ms，因此这些结果不能概括为所有指标均提升。基准不包含绘制、光照采样、物理、传送带 CPU 掩码、真实光影包状态边界或整帧；也没有单独量化距离替换和 keygen 缩小扫描的收益。

[逐轮汇总](package-cull-pipeline-2026-10-05.csv)，[8640 次采样](package-cull-pipeline-samples-2026-10-05.csv.gz)。

## 验证

真实 GPU 覆盖：距离替换与实体 view scale 解耦、主/阴影距离隔离、只导入数据的稳定候选身份与姿态、主/阴影命令隔离、光影切换恢复、普通粒子 0/1/63/64/65 的 dispatch 与 shader 上界、总 live count 不变、capture 恢复 census，以及融合/独立版本的候选与 mesh/prefix 对照、隐藏/空候选与陈旧可见性。

完整回归通过：Java 489 项、97 suites、零失败；包裹 GPU 13614540 项断言、光照 GPU 886165 项、Iris 原生 shaderpack GPU 26733 项、Hex/粒子 GPU 135498 项；Sable 缺席检查与 build 通过。GPU 断言数量受部分异步轮询测试影响。真实 Minecraft 内的光影切换与视觉验收尚未执行。

随后补充同帧关闭光影的防护，最终绘制 GPU 验证通过 1885549 项断言，并再次构建。

```powershell
.\gradlew.bat test validatePackageGpu validatePackageLightGpu validatePackageIrisGpu validateHexGpu validatePackageSableAbsent build --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
.\gradlew.bat validatePackageGpu -PpackageCullBenchmark --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```
