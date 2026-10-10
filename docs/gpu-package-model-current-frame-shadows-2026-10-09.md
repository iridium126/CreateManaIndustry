# 包裹与 MODEL：当前帧阴影和独立剔除（2026-10-09）

2026-10-10：修正帧 scope 提前在 LevelRenderer 返回时清理、导致 AFTER_LEVEL 粒子不绘制的问题，并补齐 SSBO 0–31 恢复。见[后续修复与可见性回归](gpu-particle-late-draw-visibility-fix-2026-10-10.md)。下文保留当时的实现与测量记录。

实现已完成，并通过 Java、实际 OpenGL 驱动、完整粒子/包裹 GPU 和原生 Iris GLSL 转换回归。游戏内的快速移动、转向、挥动物品、F3+T 与光影包切换尚未进行视觉验收，不能据此声称已经实机确认无错位。

## 帧提交

`ParticleLevelFrameMixin` 包围主相机的 `GameRenderer → LevelRenderer.renderLevel` 调用，保存 Camera、DeltaTracker、主视图和投影矩阵。帧令牌按主调用递增，嵌套与辅助视角不能领取计算；外围 finally 清理上下文，异常时也结束诊断。

Iris pipeline 的 `renderShadows` HEAD 在管线初始化后、阴影相机状态安装前调用 `prepareCurrentFrame`。它覆盖启用集成的所有粒子帧，不检查包裹是否存在或 CPU liveDisplay，因此 MODEL-only 和新出生 MODEL 不会错过提交。每个令牌只允许一次准备；AFTER_SKY 复用结果。无光影或集成关闭时，原 AFTER_SKY 准备路径继续工作；AFTER_LEVEL 负责剩余绘制与计时收尾。

失败不会重试同帧准备，frameArmed 与 ready 均保持失败状态，包裹和 MODEL 阴影入口跳过该帧。成功入口记录各自 frame、generation、age=0；MODEL 状态不再覆盖主画面 permutation 年龄。物理 dt、补步、权威与网络协议未修改。

提前计算的状态边界保存 program、VAO/element、SSBO 0–15 的非零 range、indirect/copy buffer、0–15 纹理单元的 2D/3D/TBO、RenderSystem shader texture、active texture，以及 unpack PBO/布局。边界内初始化固定从单元 0 开始，避免 Iris 在高位采样单元进入时影响该单元。MODEL 首次 shader 构建、列表准备与绘制也置于状态恢复边界，另恢复 blend/depth/cull、Iris alpha test 和 tessellation 状态。

## MODEL 阴影列表

`ModelShadowGpu` 从当前已提交 pool 与 lastGoodSlot 对应的精确 GPU counter 读取全部存活粒子，筛选 MODEL。三个 compute pass 分别清空 commands 并生成间接 dispatch、收集可见 MODEL/carrier、写入同代 metadata 并整理 body/carrier/ghost 的 commands。没有 CPU 存活数下载，没有复用主视锥裁剪后的 permutation，也没有透明排序。

独立实例 buffer 为 `(capacity + CARRIER_CAP + 1) × 8` 字节；独立 command/dispatch buffer 为 156 字节。body 与 ghost 使用同一列表；carrier 在这份列表中由主路径共享的 `cmiModelCarrier` 谓词选择，最终计数钳制至 CARRIER_CAP。主画面排序、计数、carrier 及提交池均作为只读输入保留。

MODEL 与包裹共同调用 `PackageShadowPolicy.copy`，使用 Iris 的实体阴影视锥和 Camera，支持 13 平面、安全区、距离盒、NonCulling、CullEverything。未知类型跳过该 pass，并报告类名。

当前 MODEL 尺寸由与普通/合并顶点路径共用的 `cmiModelSize` 计算：life easing、size multiplier、storm identity 特例完全一致。原生悦灵的 width/height 乘 `2 × size / MODEL_ABOVE_FEET`，随后执行与包裹相同的实体距离公式、足部 AABB inflate(0.5)、距离盒与“安全区或阴影平面”。MODEL 不使用包裹固定宽高。

合并顶点程序新增 cmi_ShadowPass。阴影路径仅关闭粒子 fade-distance 早退；主画面仍维持原淡出。阴影使用当前池、当前姿态/动画时间与 Iris 阴影矩阵，保留实体阴影开关、原生 alpha test、翅膀/披风和持有物。阴影不会设置主画面的已绘制标志。

回归还发现原有 #version 正则的可选 profile 会跨行吞掉下一行的 varying；现改为只匹配同行的 core/compatibility/es。这修复了 MODEL 原生 Iris 合并测试中的接口冲突。

## 验证

实际驱动：NVIDIA GeForce RTX 4070 Laptop GPU，OpenGL 4.5，NVIDIA 581.15，隐藏 GLFW context。

| 检查 | 结果 / 覆盖 |
| --- | --- |
| Java build/test | 564 tests，0 failures/errors/skips；含帧去重、失败禁止重试、嵌套/辅助隔离、finally 平衡及实际 Minecraft/Iris ABI |
| 完整粒子 GPU | validateHexGpu 通过，含全粒子 kernel 编译、MODEL carrier/动画与 Hex 路径 |
| 完整包裹 GPU | 14,675,334 断言通过，含自由物理、锁链/父结构、独立 shadow pass 和状态恢复 |
| 原生 Iris 包裹 | 26,733 断言通过；真实 Iris transformer 后驱动编译/链接 |
| 原生 Iris MODEL | 4,133,200 断言通过；实际 merged source、两个 overlay 变体、原生转换、驱动编译/链接、transform feedback 验证主 fade 保留和 shadow fade 绕过 |
| MODEL 列表 | 0/1/63/64/65/131072/4000000（此驱动的配置有效上限），GPU 存活数减少，同代 meta，carrier 溢出与完整尾部哨兵 |
| MODEL 剔除 | 阴影平面独立、6 平面、13th plane、安全区并集、距离盒约束、NonCulling/CullEverything、entity-distance 内外边界、微小/巨大尺寸、life easing、原生宽度与 inflate、storm identity、活动 wave 与尸体持有物 |
| 主列表隔离 | 源 pool、emitter、exact counter 与仍绑定的主排序/计数/carrier 哨兵区域，准备前后逐字节一致 |
| compute 状态 | 非零 SSBO range、dispatch/copy、unpack PBO/layout、VAO 与高位 active texture 恢复 |
| Sable 缺席 | validatePackageSableAbsent 通过；已安装 Sable 的父结构 GPU/Java 夹具亦通过 |

400 万容量补测（含 metadata/哨兵、carrier 溢出、6/13 平面与原生 Iris）通过，最终 MODEL GPU 共 4,133,212 断言。见 [容量补测日志](benchmarks/model-current-frame-shadow-2026-10-09/capacity-validation.log)。最后生产 Java 调整后的 [最终 build 日志](benchmarks/model-current-frame-shadow-2026-10-09/final-build.log) 也通过。

完整日志与原始样本见 [回归日志](benchmarks/model-current-frame-shadow-2026-10-09/regression.log)、[汇总 CSV](benchmarks/model-current-frame-shadow-2026-10-09/measurements.csv)、[逐样本 CSV.gz](benchmarks/model-current-frame-shadow-2026-10-09/samples.csv.gz)。

可复现命令（避免 GPU 测量并发）：

```powershell
.\gradlew.bat -I scripts/particles/validation.init.gradle build validateHexGpu validatePackageGpu validatePackageIrisGpu validateModelShadowIrisGpu validateModelShadowGpu -PmodelShadowBenchmark --no-parallel --max-workers=1 --console=plain
```

这些 GPU/ABI 夹具不能代替 Minecraft 中实际 mixin 应用、真实光影包和画面观察。仍需游戏内覆盖 MODEL-only/包裹-only/混合与新出生、暂停/重复阴影、实体阴影关闭、无 Iris/iris-veil、F3+T/光影切换，以及快速移动、转向与挥动物品的视觉检查。

## 新增成本

每档三个独立 run，每 run 至少 30 次且 1 秒预热，再采 120 次。每档均全部 MODEL 可见、全部非 storm carrier 候选；carrier 溢出受到上限约束。下面是三个 run 各自 P50/P95 的中位数，单位 ms。

| 存活 MODEL / capacity | 纯准备 CPU P50/P95 | 含 compute 状态边界 CPU P50/P95 | 纯准备 GPU P50/P95 | 含边界 GPU P50/P95 | 新增 buffer 字节 |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 10,000 | 0.0006 / 0.0013 | 0.0085 / 0.0150 | 0.019456 / 0.026624 | 0.019456 / 0.026624 | 211,236 |
| 65,536 | 0.0041 / 0.0099 | 0.0084 / 0.0143 | 0.024576 / 0.029696 | 0.024576 / 0.027648 | 655,524 |
| 131,072 | 0.0041 / 0.0094 | 0.0084 / 0.0143 | 0.028672 / 0.035840 | 0.028672 / 0.031744 | 1,179,812 |
| 2,000,000 | 0.0041 / 0.0094 | 0.0084 / 0.0140 | 0.601088 / 0.607232 | 0.601088 / 0.607232 | 16,131,236 |

默认 capacity=2,000,000 时固定新增 buffer 为 16,131,236 B（约 15.38 MiB），即使当帧存活数更少也保留该分配。此驱动有效上限 capacity=4,000,000 时为 32,131,236 B（约 30.64 MiB）。容量测试同时验证满场景与精确 live count 减少后的列表更新。

CPU 为渲染线程提交区间的墙钟时间，含对应边界的 GL 查询/恢复。GPU 为 elapsed query；这些测量包含三个剔除 kernel、barriers/间接调度，**不包含阴影几何绘制、首次 shader 编译、完整粒子/物理准备或 Minecraft 世界处理**。实际 MODEL shadow 状态使用异步 timestamp ring 报告准备 CPU/GPU，生产路径不等待 query 完成。

个别 run 有较大调度尾部（10,000 档含边界首轮 GPU P95 为 0.37888 ms）；汇总表取跨 run 中位数，原始值完整保留，不能视为严格上界，也不能由此推算游戏 FPS。
