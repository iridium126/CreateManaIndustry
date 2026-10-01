# 包裹 Iris 主渲染、阴影与状态边界

本次将 `PackageShaderCompiler` 接入实际主渲染和阴影注入点。此前的顶点/编译桥接记录保留在 [顶点报告](package-shaderpack-vertices-2026-09-30.md)。代码和独立 GPU 验证已完成，真实客户端 Mixin 应用、光影视觉及整帧性能仍未验收。

## 渲染与生命周期

主渲染在 LevelRenderer 的 `blockentities` 边界调用，只绘制本帧已成功提交的 pool/admission。阴影在 Iris `draw entities` 边界调用，使用实体阴影视锥以及尚未被本帧 AFTER_SKY 更新的上一成功 generation；尊重实体和方块实体的阴影开关。主/阴影命令分别在 GPU 重新剔除和分组，不从主相机可见列表派生阴影，不读取滞后 CPU census。

普通无光影保持一次 multi-draw。Iris 下地面与链上各一次 multi-draw：每层 apply 原生程序时设置对应的 `create:package` entity ID 或 chain conveyor block entity ID，保留依赖这些 ID 的光影包 custom uniform 计算，即使 ID uniform 被优化掉也不合并。这两次调用的 CPU 成本不随包裹数量增长。box 与 rig 仍占同一个通用槽位。

顶点/片元、geometry、tessellation、原生材质 fallback 和 draw-buffer/blend directives 由原生 Iris 创建。曲面细分使用 `GL_PATCHES`、三顶点 patch，避免裸 multi-draw 绕过 Iris vanilla drawElements 的 primitive 转换。只修正每次绘制的 `iris_*` 矩阵和包裹自有参数；named pack camera/shadow uniforms 继续来自 Iris，保留其 cameraPosition 原点平移语义。

动态阴影剔除支持最多 13 个归一化平面、距离盒、Safe Zone、无剔除及全剔除。规则为 distance AND (safe OR planes)，参数每帧复制一次；GPU 使用包含 box、rig、摆动和前后插值端点的保守边界。未知未来 frustum 拒绝该路径，不能误用六平面主相机。常规 draw variant 仍为原六平面版本。

复用的 GL 状态边界保存并恢复程序、VAO、SSBO 基础/范围绑定、generic/copy/indirect buffer、TBO、基础纹理、framebuffer、patch vertex 数、depth/cull 与逐 draw-buffer blend 状态。基础 SSBO 绑定报告 SIZE=0，必须用 BindBuffersBase 恢复，不能把 0 当作合法范围长度；非零范围再单独恢复。Iris clear 后恢复 captured IDs、alpha/tessellation 状态和 RenderSystem 逻辑纹理。此边界没有 fence 等待，也不逐包裹保存状态。

同 pipeline 重载失败保留完整旧程序；跨 pipeline 不复用旧 framebuffer。主/阴影全部编译成功才替换，旧程序清理失败不撤销新 bundle。额外创建的 ModelViewMat placeholder 不属于 ShaderInstance 的 uniform 集合，现由 bundle 显式释放。失败绘制会撤销接管并恢复 Create；预览在程序不可用时停止。独立 Iris 不再要求 iris-veil，已有 MODEL 兼容路径保持原门禁。`/cmi particle stats` 增加 Package shaderpack 状态。

`PackageDrawTelemetry` 分别记录主/阴影 CPU 与 GPU p50/p95，完成 GPU 样本加入已有节流成本。仅 profiling 或自动节流开启时发查询；每个 pass 四组独立 timestamp 查询，不使用 GL_TIME_ELAPSED。满槽保留未消费样本、跳过新增 GPU 采样，CPU 仍记录；只在结束 timestamp AVAILABLE 后读取成对结果，没有等待。关闭、编译/重载边界清除旧统计，不能混合不同世界/程序的样本。这项运行时计时开销未包含在下方仅 preparePass 的微基准中。

## 独立验证与边界

机器：RTX 4070 Laptop，NVIDIA 581.15，OpenGL 4.5，隐藏 64×64 context。

`validatePackageIrisGpu` 调用当前 Iris 发布 JAR 中未经修改的 TransformPatcher.patchVanilla、transformer 3.0.0-pre3 和 IrisConfig，再由真实驱动编译/链接顶点与片元。独立 JVM 缺少 NeoForge LoadingModList，测试专用 facade 仅提供 debug 配置和 logger，位于 `scripts/particles/iris-harness`，只在此任务中优先加载，不加入 main/test source sets 或发行 JAR。这项检查不启动 Minecraft，也不验证 ShaderCreator、Mixin 实际应用或光影包资源加载。

增加实际发布包 ASM 契约检查：阴影 frustum 已准备后的注入点、字段布局、ProgramSet 构造和 private compiler invoker 描述符。真实 GPU 覆盖第 13 平面、Safe Zone/距离盒、全剔除/无剔除、成功代身份不变、外部非零 SSBO 范围和 base 绑定、纹理多目标不受损、逐目标 blend、成功/注入失败后的恢复。生产间接提交接口的 PATCHES 单层/合并层与普通渲染逐像素比较一致。

完整回归通过：Java 263 项、54 suite、0 失败；包裹 GPU 14166685 项、light GPU 886165 项、原生 Iris 转换/绘制 GPU 12851 项断言；Sable 缺席与 build 通过。随后加入独立绘制计时 ring（外部计时器隔离、四槽耗尽、失败、重复消费、关闭后重建），原生 Iris GPU 为 12870 项并再次构建。发行 JAR 包含 hook 与注入 shader，不含测试 facade、Sable/companion 或 glsl-transformer。command 的旧光影预览拒绝分支也已改为可用程序检查。

游戏内应检查纯 Iris、Iris + iris-veil、shadow 开关、实体/方块实体阴影开关、资源重载、视角切换及 shaderpack 切换。可用 `/cmi particle packagepreview 131072` 检查预览，预览没有真实库存或服务端操作。

## 组件测量

固定候选、相机、mesh、全可见负载；包裹一半为链上带 rig，一半为地面。10000/65536/131072，每种模式预热至少 1 秒、三轮各 120 样本，GPU timer 不含初始化，CPU 提交不含等待查询结果。确认全部 box/rig 实例存在，无 CPU 回退。`current_group` 含池导入/常规分组，其他模式只构建成功代的绘制 pass，因此不能据两者差值声称算法提速。

| 包裹量 | 六平面 split GPU p95 ms | 13 平面 GPU p95 ms | 13 平面+状态边界 GPU p95 ms | 含边界 CPU p95 ms |
|---:|---:|---:|---:|---:|
| 10000 | .026624–.027648 | .027648 | .027648 | .0812–.0867 |
| 65536 | .099328–.100352 | .100352 | .100352 | .0088–.0092 |
| 131072 | .183296 | .183296 | .187392–.188416 | .0094–.0116 |

10000 阶段 CPU 明显高于后续阶段，可能受 JVM 热身/阶段顺序影响；没有排除或用后续结果替代它，也不声称所有负载都达到 .01ms。13 平面与六平面的 GPU 差异在本次量化噪声内。状态边界测量包含 capture/restore 和 preparePass，不包含 shader.apply、采样光照、实际 raster、shaderpack composites 或完整游戏帧；未证明 131072 活动包裹整帧 p95 ≤16.7ms。

[逐轮汇总](package-iris-boundary-2026-09-30.csv)，[原始样本](package-iris-boundary-samples-2026-09-30.csv)。

```powershell
.\gradlew.bat validatePackageIrisGpu --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
.\gradlew.bat validatePackageGpu -PpackageIrisBoundaryBenchmark --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```

Sable 继续使用 `compileOnly "maven.modrinth:T9PomCSv:U678xqle"`，typed adapter/companion 数学调用没有反射，也不打包 Sable。Create/Sable 自由包裹的动态碰撞已在实验组件接线；移动 parent 的链轨道、移动 parent 光照、观察端增量与真实场景性能仍待完成，CHAIN_READY 继续关闭。

移动链轨道指 Sable sub level 中正常运行的链式输送机。普通 Create contraption 的链式输送机没有对应 MovementBehaviour，装配还会触发连接校验；本次只让这种 contraption 作为包裹碰撞结构，不新增其原本没有的运行中链物流。参考 `.refs/Create/.../AllMovementBehaviours.java`、`Contraption.java` 以及 `.refs/sable/neoforge/.../chain_conveyor/ChainConveyorBlockMixin.java`。
