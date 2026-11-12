# Hex 图案 GPU 接管

状态：🟡 世界环绕图案、Iris 阴影、玩家预览和施法 UI 已实现；自动回归通过，游戏内视觉与兼容组合仍由用户验收。

## 配置与范围

- `particles.enabled` 与 `hexcasting.hexPatternRedirect` 同时开启才接管；关闭任意一个，原生调用直接透传。
- `hexcasting.hexPatternGpuBudgetMiB` 默认 16，范围 1–256。计入 Hex 源描述、GPU 点缓存、实例、计算任务、间接命令、bank 和扩容后尚未释放的旧 buffer；TBO backing 只计一次，不含 shader program 和普通粒子池。
- 阴影还遵守 `particles.shaderPackIntegration`、Iris 实体/仅玩家阴影开关及父实体准入。只安装 Iris 也可接入，不要求 iris-veil。
- 覆盖玩家世界环绕图案、第一人称自身的阴影、玩家模型预览、施法 UI 历史/WIP 图案、节点及引导网格点；石板、卷轴、工具提示继续原生。

## 绘制与资源契约

`HexPatternService` 使用 `WORLD_MAIN`、`IRIS_SHADOW`、`PLAYER_PREVIEW`、`SPELL_UI` 四个上下文。取消原生需要当前 pass 的成功接管凭据，不能从 UUID 或普通粒子 `frameArmed` 推断。

世界实例已迁出通用粒子池，不参与普通粒子的更新、keygen、排序或计数。主相机外层作用域捕获 camera、DeltaTracker、view/projection；提前 Iris 入口与 AFTER_SKY 最多准备一次，AFTER_LEVEL 在 ALPHA 后、ADDITIVE 前提交。Hex-only 不依赖普通粒子提交。世界按玩家锚点远到近绘制，同一玩家保持栈内顺序；仅取消本帧实际到达原生入口的玩家。普通第一人称自身仍不进入主画面列表。

阴影使用独立候选集、实例变换和命令，不复用主视锥列表。入口是 Iris 第二次 `Frustum.prepare` 后、原生实体绘制前，成功提交后才发布阴影凭据。阴影 shader 以实际 `VCDrawHelper.Basic` 提交行为为准，使用 `SHADOW_TEX_COLOR` + Hex 白纹理，保留原生透明测试和 shaderpack fragment/geometry/tessellation/directives。

玩家预览在原生调用位置立即提交，继承调用方 PoseStack、RenderSystem 矩阵、FBO、viewport 和 scissor，不要求世界帧 armed；嵌套玩家模型调用单独处理，嵌套阴影调用保持原生。施法 UI 收集原生引导点、历史/WIP 回调，在原生文字之前一次提交；历史布局按图案序号缓存，平移/GUI 缩放只改矩阵，WIP 鼠标末段改任务参数而不重传历史拓扑。

`HexGpuBackend` 缓存不可变源描述；几何键包含形状 identity、噪声 seed、时间和 WIP 末端参数。噪声点与转角在 compute 中生成，三角形由 vertex pulling 生成，同一时间版本的几何在世界、阴影和预览之间复用。每批最多 4,096 实例、16 个按需增长的 bank；缓存采用 LRU，当前批次及在途几何不可淘汰；只零超时查询 fence，未就绪时回退，不等待、不下载 GPU 结果。

整批准备失败则保留或重放原生，重放带重入保护。预算、配置、世界切换、重载和 shaderpack pipeline 变化使对应旧凭据失效。每批保存/恢复 program、VAO、indirect、SSBO base/range、TBO、sampler、FBO、viewport、scissor、blend/depth/cull、颜色写入掩码、patch 数及 RenderSystem shader 引用；阴影还恢复 Iris alpha/tessellation 状态。GL 对象在第一次实际 pass 时初始化，配置/诊断类加载无需 GL 上下文。

## 验证与验收边界

真实 OpenGL 4.5 上下文的联合回归：

```powershell
.\gradlew.bat -I scripts/particles/validation.init.gradle build validateHexGpu validatePackageGpu validatePackageIrisGpu validateModelShadowIrisGpu validateHexSharedIrisGpu validateHexSharedGpu -PhexSharedBenchmark -PhexSharedLongBenchmark --no-parallel --max-workers=1
```

覆盖原生噪声/重复路径/时间/seed 对照、WIP 末段无源重传、在途版本不覆盖、framebuffer 像素对照、真实 Iris shader 转换，以及预算回退、bank 无等待回退、实例边界、空批次、sampler/TBO/SSBO/FBO 与状态恢复。像素对照允许每组最多 12 个像素超过 2 色阶差异，不是逐像素完全相同的声明。基准只对照各 consumer 的独立后端，不含整个 Minecraft 帧、实际 shaderpack 开销或跨 pass 缓存收益，不能外推为游戏内倍率。

用户实机验收覆盖：世界图案与其他玩家/第三人称、第一人称及仅玩家阴影、玩家预览的 FBO/viewport/scissor、施法 UI 的引导网格与历史/WIP、生命周期（暂停、切维度、F3+T、shaderpack 切换、配置关闭），以及无 Iris／仅 Iris／无 iris-veil／当前 Photon 的兼容与回退。可用 `/cmi particle profile on` 查看各 pass 的 CPU、分配、异步 GPU 时间及预算/回退原因；验收必须重启加载最终构建。

## 代码入口

- `client/hex/pattern/HexPatternService`：帧与 pass 凭据、缓存、收集、原生回退。
- `HexGpuBackend/HexShape/HexUiLayout`：资源预算、bank/LRU、不可变拓扑、WIP 与布局复用。
- `HexPassState/HexMetrics`：GL/RenderSystem 边界与可选测量。
- `client/particles/shaderpack/HexIrisHook`、Hex/Iris 相关 Mixin：`Iris-only` shader 合并与独立阴影。
- `hex_shared_prepare.comp`、`chunks/hex_shared_vertex.glsl`、`hex_shared.vsh/fsh`。

普通透明排序恢复为 MODEL/ALPHA 两分区、512 桶、六条 indirect command；`PACKAGE material=5/type=6` 等现有编号保持稳定。`validateHexGpu` 依赖共享 Hex 后端验证，资源缺席检查防止旧 pool 实现重新进入产物。
