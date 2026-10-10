# Hex 图案 GPU 接管（2026-10-10）

状态：🟡 世界环绕图案、Iris 阴影、玩家预览和施法 UI 已实现；自动回归与真实驱动基准见本文。游戏内视觉验收由用户完成，尚未把全部兼容组合标记为已验收。

## 配置与范围

- `particles.enabled` 与 `particles.hexPatternRedirect` 同时开启才接管。关闭任意一个，原生调用直接透传。
- `particles.hexPatternGpuBudgetMiB` 默认 16，范围 1–256。计入 Hex 源描述、GPU 点缓存、实例、计算任务、间接命令、bank 和扩容后尚未释放的旧 buffer；TBO backing 只计一次，不计 shader program 和普通粒子池。
- 阴影还遵守 `particles.shaderPackIntegration`、Iris 实体/仅玩家阴影开关及父实体准入。只安装 Iris 也可接入，不要求 iris-veil。
- 覆盖玩家世界环绕图案、第一人称自身的阴影、玩家模型预览、施法 UI 历史/WIP 图案、节点及引导网格点。石板、卷轴、工具提示继续原生。

## 绘制与资源契约

`HexPatternService` 使用 `WORLD_MAIN`、`IRIS_SHADOW`、`PLAYER_PREVIEW`、`SPELL_UI` 四个上下文。取消原生需要当前 pass 的成功接管凭据，不能从 UUID 或普通粒子 `frameArmed` 推断。

世界实例已经迁出通用粒子池，不参与普通粒子的更新、keygen、排序或计数。主相机外层作用域捕获 camera、DeltaTracker、view/projection；提前 Iris 入口与 AFTER_SKY 最多准备一次，AFTER_LEVEL 在 ALPHA 后、ADDITIVE 前提交。Hex-only 不依赖普通粒子提交。世界按玩家锚点远到近绘制，同一玩家保持栈内顺序；仅取消本帧实际到达原生入口的玩家，未到达的命令禁用。普通第一人称自身仍不进入主画面列表。

阴影使用独立候选集、实例变换和命令，不复用主视锥列表。入口是 Iris 第二次 `Frustum.prepare` 后、原生实体绘制前，成功提交后才发布阴影凭据。实体模式按实际 `entitiesForRendering` 和 dispatcher 阴影视锥准入；仅玩家模式遵守原生本地玩家及乘客/载具组规则。包含第一人称自身，动画使用同帧时间和原生冻结实体 tick delta。

玩家预览在原生调用位置立即提交，继承调用方 PoseStack、RenderSystem 矩阵、FBO、viewport 和 scissor，不要求世界帧 armed。嵌套玩家模型调用单独处理；嵌套阴影调用保持原生。

施法 UI 收集原生的引导点、历史/WIP 回调，在原生文字之前一次提交。历史布局按图案序号缓存，避免引导点数量变化破坏缓存；平移/GUI 缩放只改矩阵。WIP 鼠标末段改任务参数，不重新上传历史拓扑。Ctrl、颜色、线宽、节点、原生细分和生命周期保持对应公式。背景、文字与输入逻辑继续原生。

`HexGpuBackend` 缓存不可变源描述；几何键包含形状 identity、噪声 seed、时间和 WIP 末端参数。噪声点与转角在 compute 中生成，三角形由 vertex pulling 生成。同一时间版本的匹配几何在世界、阴影和预览之间复用。每批最多 4,096 实例，16 个按需增长的参数 bank。缓存采用 LRU，当前批次及在途几何不可淘汰；只零超时查询 fence，未就绪时回退，不等待、不下载 GPU 结果。

整个玩家栈/施法 UI 批次准备失败则保留或重放原生。重放带重入保护，在恢复调用方状态后执行一次；预算、配置、世界切换、重载和 shaderpack pipeline 变化使对应旧凭据失效。诊断输出包含缓存命中/生成、源与参数上传、提交次数、预算占用和最近回退原因。

每批保存/恢复 program、VAO、indirect、SSBO base/range、TBO、完整 sampler 槽、FBO、viewport、scissor、blend/depth/cull、颜色写入掩码、patch 数及 RenderSystem shader 引用；阴影还恢复 Iris alpha/tessellation 状态。GL 对象在第一次实际 pass 时初始化，配置/诊断类加载无需 GL 上下文。

### 阴影 shader 选择的依赖修正

计划中的 `SHADOW_BASIC_COLOR` 对应的是 `ClientRenderHelper` 外层设置。但当前实际 Hexcasting **1.21.1-0.12.0-devel-pre-53** 的 `VCDrawHelper.Basic` 在提交线条时会选择 PositionTexColor，并绑定白纹理。因此最终使用 **`SHADOW_TEX_COLOR` + Hex 白纹理**，保留实际原生透明测试和 shaderpack fragment/geometry/tessellation/directives。已用解析后的依赖字节码及真实 Iris TransformPatcher 验证；不能用 `.refs` 的单一外层 shader 设置代替实际最终提交行为。

## 自动验证

复现命令（真实 OpenGL 4.5 上下文）：

```powershell
.\gradlew.bat -I scripts/particles/validation.init.gradle build validateHexGpu validatePackageGpu validatePackageIrisGpu validateModelShadowIrisGpu validateHexSharedIrisGpu validateHexSharedGpu -PhexSharedBenchmark -PhexSharedLongBenchmark --no-parallel --max-workers=1
```

- Java：571 项，0 失败/错误/跳过；检查实际依赖的 GUI/玩家/阴影入口、阴影提交先于原生实体、通用池生产路径不再生成 Hex、预算、布局及嵌套 sampler 预留。
- 原有完整粒子 GPU：135,778 项；包括 additive/ALPHA/OPAQUE/MODEL 实际像素、晚绘制可见性和资源状态恢复。
- 包裹 GPU：14,675,334 项；包裹 Iris：26,733 项；MODEL 阴影 Iris：4,133,200 项。
- 共享 Hex：原生噪声/重复路径/时间/seed 对照、WIP 末段无源重传、在途版本不覆盖、原生端帽/渐变/六边形节点 framebuffer 对照、真实 Iris shader 转换与编译链接。
- 新增资源边界：有效形状触发 1 MiB 硬预算回退、失败后恢复、16 个全部持有 bank 的无等待回退、1/63/64/65/4,096 实例、空批次、高 sampler/TBO、非零 SSBO range、FBO/viewport/scissor、全局及分目标颜色/混合状态、tessellation 恢复。

像素对照在 64×64 FBO 上允许每组最多 12 个像素超过 2 色阶差异，以容纳 native 查表三角函数与 GLSL 浮点栅格边界差异；不是逐像素完全相同的声明。Harness 仅隔离 HexAPI 注册初始化与 Screen Ctrl 输入，几何 oracle 使用实际依赖 `RenderLib.makeZappy/drawLineSeq/drawSpot` 和真实 BufferBuilder，未替换几何算法。

完整日志、汇总 CSV 和压缩逐次样本存放于 [benchmarks/hex-shared-takeover-2026-10-10](benchmarks/hex-shared-takeover-2026-10-10/)。

## 性能数据口径

硬件：RTX 4070 Laptop GPU，NVIDIA 581.15。每档三轮，每轮每条路径预热至少 30 次且至少 1 秒，随后 120 样本。覆盖 10/100/1,000 简单图案，以及 100 个长度为简单图案五倍的长图案。

原生基线包括实际 makeZappy、原生网格构建、上传和绘制。GPU 包括完整 HexPassState 边界、prepare、上传和 multi-draw。四个 pass 使用对应几何风格；这是各 consumer 的独立后端基准，未包含整个 Minecraft 帧、实际 shaderpack、服务层玩家遍历/矩阵/施法 UI 参数收集，也未把跨 pass 缓存复用带来的收益加入基准。

测试用 glFinish 放在 CPU 提交计时区间之外，以排空样本；生产代码不等待。GPU TIME_ELAPSED 包含 CPU 提交造成的 GPU 空档，不应解释为纯几何着色器加速比例。Windows thread CPU 的 15.625 ms 粒度会让短 GPU 提交报告 0，不能据此称渲染线程 CPU 成本为零。上传量包含实例、任务和命令；源缓存稳定后的上传为 0 不代表每帧全部上传为 0。

最终数值见同目录 CSV；下表采用三轮 P95 的中位数，单位 ms/批，分配为 B/批。

### 简单图案（100 个）

| pass | 原生 CPU P50/P95 | GPU CPU P50/P95 | 原生分配 P95 | GPU 分配 P95 | GPU elapsed P95 | GPU buffer KiB |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| world | 3.690/4.757 | 0.072/0.102 | 3685536 | 28920 | 0.105 | 174.0 |
| shadow | 4.008/4.682 | 0.074/0.105 | 3685536 | 28920 | 0.105 | 174.0 |
| preview | 3.931/4.393 | 0.073/0.140 | 3685536 | 28920 | 0.105 | 174.0 |
| spell_ui | 8.692/9.397 | 0.071/0.102 | 7072896 | 28920 | 0.154 | 302.0 |

### 长图案（100 个，路径长度五倍）

| pass | 原生 CPU P50/P95 | GPU CPU P50/P95 | 原生分配 P95 | GPU 分配 P95 | GPU elapsed P95 | GPU buffer KiB |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| world | 14.870/148.463 | 0.072/0.103 | 16891376 | 28920 | 0.285 | 566.0 |
| shadow | 13.642/18.528 | 0.069/0.093 | 16891376 | 28920 | 0.279 | 566.0 |
| preview | 13.129/13.786 | 0.069/0.089 | 16891376 | 28920 | 0.269 | 566.0 |
| spell_ui | 43.305/146.872 | 0.110/0.170 | 32427248 | 28920 | 0.652 | 1094.0 |

默认 16 MiB 下，10/100/1,000 简单图案和上述长图案均无 prepare 回退。100 个简单图案每批上传 30,400 B（实例/任务/命令），源描述预热后不再上传；multi-draw 提交为每批 1 次，原生 world/shadow/preview 为约 600 次，spell_ui 为约 1,800 次。真实世界服务在禁用未到达的原生玩家命令时还上传最终命令表。

长图案原生路径存在大量分配和明显的 GC/调度尾延迟，个别轮次双方也出现抖动；应结合逐次原始数据看待 P95，不能把这些极值外推为游戏内稳定倍率。基准以共同的路径/风格进行后端对照，不包含引导点采集、玩家遍历、原生 GUI 布局或实际光影包开销。

## 用户实机验收清单

本节全部为待用户确认，不以源码/离屏测试替代。

| 组别 | 操作与观察 |
| --- | --- |
| 世界 | 其他玩家、第三人称自身；快速移动/转向；重叠透明图案；普通第一人称主画面不出现自身环绕图案 |
| 阴影 | 第一人称自身、第三人称/其他玩家；仅玩家阴影；主视锥外但阴影准入；实体阴影关闭；确认无重复或一帧错位 |
| 玩家预览 | 背包及其他玩家模型预览；离屏 FBO、不同 viewport、GUI 缩放、scissor；退出 GUI 后世界状态正常 |
| 施法 UI | 引导网格、历史/WIP、鼠标末段、重复路径、Ctrl 开关、成功/错误颜色、节点、动画；文字/背景/交互正常 |
| 生命周期 | 暂停、空场景、Hex-only、嵌套视角；切维度、F3+T、shaderpack 切换和配置关闭/预算调整 |
| 兼容/回退 | 无 Iris、仅 Iris、无 iris-veil、当前 Photon；容量/预算超限回退无缺图/重复；混合包裹/MODEL/additive/textured 仍可见 |

可用 `/cmi particle profile on` 开启测量，`/cmi particle profile` 查看各 Hex pass 的 CPU wall、thread CPU、分配及异步 GPU 时间；检查预算/回退原因。核心场景是约 100 简单图案、默认 16 MiB，预热后应无资源回退。运行过的开发客户端日志只能证明当时 Mixin 应用及 `cmi_hex_shadow` 创建；最终代码修正了阴影入口与状态恢复，验收必须重新启动加载最终构建，不能用旧进程结果替代。

## 代码入口

- `client/particles/hex/HexPatternService`：帧与 pass 凭据、缓存、收集、原生回退。
- `HexGpuBackend/HexShape/HexUiLayout`：资源预算、bank/LRU、不可变拓扑、WIP 与布局复用。
- `HexPassState/HexMetrics`：GL/RenderSystem 边界与可选测量。
- `client/particles/shaderpack/HexIrisHook`：Iris-only 原生 shader 合并和独立阴影。
- `mixin/hexcasting/HexWorldFrameMixin/HexEntityContextMixin/HexPlayerRenderContextMixin/HexSpellUiMixin/HexPatternRedirectMixin`、`mixin/iris/HexShadowRenderMixin`。
- `hex_shared_prepare.comp`、`chunks/hex_shared_vertex.glsl`、`hex_shared.vsh/fsh`。

后续清理已移除旧 Hex pool Java、shader、SSBO 声明、旧材质/类型及回归夹具；普通透明排序恢复为 MODEL/ALPHA 两分区、512 桶、六条 indirect command。PACKAGE material=5/type=6 及其他现有类型编号保持稳定。`validateHexGpu` 现在依赖共享 Hex 后端验证；资源缺席检查防止旧实现重新进入产物。普通 conjure/spray 的 HexSpecs 与共享噪声仍保留。详见 [清理结果和 GPU 优化路线](gpu-hex-cleanup-model-efficiency-2026-10-10.md)。
