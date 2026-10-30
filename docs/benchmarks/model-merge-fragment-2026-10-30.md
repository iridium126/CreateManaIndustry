# 悦灵风暴合并路径：片元优化与状态整理

日期：2026-10-30。自动验证通过；客户端实机测试由用户执行，尚未记录视觉验收。

## 实现与适用范围

运行时直接使用新的合并编译器，没有新旧路径开关，也不预编译两套实现。旧编译器冻结在 `scripts/particles/reference/model-merge/`，仅由验证任务编译，并检查生成的顶点输入与生产 ABI 一致。

`ModelShaderOptimization` 用 GLSL AST 处理 Iris 已展开、预处理的源码，不重复运行包源码预处理器。固定上下文可转为常量；身体、羽毛、LOD 使用有效悦灵身份，持剑使用中性身份。两种上下文源码相同时复用程序及原有 apply 区间，不额外切换；不同时才编译独立持剑程序。

保留 geometry/tessellation、原生裁剪、逐附件混合、打包材质、受伤覆盖和阴影。AST 输出保留 `DRAWBUFFERS`／`RENDERTARGETS` 指令，未知结构或解析失败保留输入。失败和重载释放已创建程序，别名只释放一次；补齐注入占位 uniform 的释放。F3+T、换世界、关闭清理合并程序和源码缓存。

最初全面常量化使 Complementary 密集片元场景约退化 11%。隔离实验后，保留主函数实体分类和片元纹理尺寸的原生调度，只对适用结构消除固定上下文。运行时依据源码结构，不使用包名白名单。

安全裁剪移动仅跨越独立、无导数、无副作用的局部 ALU，不能跨越采样、用户函数、分支、循环、全局写入或 alpha 依赖。八包默认源码没有应用此规则，不将其计作收益；不注入统一纹理 alpha 阈值或强制 early fragment tests。

## 测量方法与结果

环境：RTX 4070 Laptop GPU，OpenGL 4.5，NVIDIA 581.15。隐藏 GLFW 上下文、1920×1080，固定输入、相机、时间、选项，不运行模拟和节流。图集使用实际悦灵／六种剑资源、最大 mip 4 和生产一致的 `NEAREST_MIPMAP_LINEAR`。

每场景交替预热 20 次，三轮各 24 对，逐对交错新旧顺序。GPU 时间不包含目标清理或静态 uniform 准备。完整模型另记 CPU 绘制提交时间。覆盖空、1千／1万／5万成员，稀疏、密集、完全遮挡；远景片元使用 131072 项。

片元压力使用合成实例表面和实际包片元；完整模型使用生产合并顶点、实际网格、TBO、身体／持剑／透明三段，并模拟旧两程序与新两或三程序提交。两组均不包含完整游戏管线，不能换算 FPS。

以下为 5 万项密集透明片元。百分比为每轮配对耗时比例的中位数，再取三轮中位数；p50 为全部样本的中位数，两者可能因漂移不完全相等。

| 光影包 | 旧 p50 ms | 新 p50 ms | 配对变化 |
|---|---:|---:|---:|
| Bliss | 0.7050 | 0.7045 | -0.22% |
| BSL | 1.4710 | 1.4746 | +0.21% |
| Complementary Unbound | 0.6779 | 0.6779 | 0.00% |
| iterationT | 0.4966 | 0.4966 | 0.00% |
| Photon | 0.0881 | 0.0799 | **-8.72%** |
| Sildur’s Vibrant | 0.4946 | 0.4946 | 0.00% |
| Solas | 0.6943 | 0.6799 | **-2.06%** |
| Sundial Lite | 0.1628 | 0.1628 | 0.00% |

相同源码 A/A 对照得到片元噪声带 0.86%、完整模型噪声带 0.66%。片元有 18 个重负载分组在三轮中均超过噪声带改善，两组均无三轮稳定超出噪声带的退化。

完整模型三段绘制未测出超出噪声的稳定提升，夹具以顶点、网格和 TBO 工作为主。替换依据为**目标片元效率可重复改善，完整模型无稳定退化**，并非已证明整帧或完整模型显著加速。早期未启用生产 mip 过滤时较大的 Solas 收益不作为最终结论。

主视角程序由 2 个变为 2～3 个：Bliss、Complementary、Photon、Solas 上下文不同，使用 3 个；其余默认样本复用为 2 个。阴影数量不变。`startup.csv` 记录实例变换／链接和 AST 耗时，受驱动缓存影响，不是游戏冷启动基准。例如一次暖缓存验证中，Complementary 旧阶段合计 70.986 ms，新阶段 97.772 ms，另有 AST 优化 78.878 ms；不能声称加载更快。

本机原始性能数据在 `build/benchmarks/model-merge-final-mips/`：`draw.csv`、`production.csv`、`compile.csv`、`summary.json`、`summary.md` 和任务元数据。最终冻结基线验证及 `startup.csv` 在 `build/benchmarks/model-merge-frozen-final/`。生成数据不进入分发 JAR。

## 正确性与复现

八包比较所有 MRT 和深度的原始位，浮点通道也不以误差容忍放过打包数据。覆盖阈值、mip 边缘、重叠、遮挡、六种剑、正常／受伤／尸体、生产羽毛顶点和两档原生 LOD 的 131072 成员。另测 BSL 高级材质与 `ALPHA_BLEND=1`、Complementary `RP_MODE=2`。

983 项常规 JUnit、2 项八包源码矩阵通过；八包合并 GPU 约 1.157 亿项逐位检查通过，LOD 2,230,519 项、MODEL 阴影 4,133,608 项、场景帧 72,764 项、程序生命周期 694 项通过。GPU 数量包含大量逐像素字，不代表相同数量的独立场景。重载日志中的 ERROR 是预期失败注入。

```powershell
.\gradlew.bat test validateShaderPackSamples check build --offline --no-configuration-cache
.\gradlew.bat benchmarkModelMergeGpu --offline --no-configuration-cache -I scripts/particles/validation.init.gradle -PbenchmarkOutput=build/benchmarks/model-merge
python scripts/particles/summarize_model_merge.py build/benchmarks/model-merge --require-gate
.\gradlew.bat validateStormLodGpu validateModelShadowIrisGpu validateSceneFrameGpu validateParticleProgramsGpu --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```

`--require-gate` 要求八包数据完整、片元收益三轮可重复，片元／完整模型均无稳定退化。高级材质仅作正确性测试，性能表为默认选项。不推断其他驱动、GPU 或任意选项的收益。

## 状态输出与用户实机检查

`/cmi particle shaderpack status` 按 Pack、MODEL、World、Sky/Cloud、Packages、Hex、Probe 输出摘要，省略未安装的可选模块。`status details` 展开会话、源码摘要、有效程序、优化规则、附件／编码、身份、图集、提交代次、裂口／云和异常。统一状态和毫秒，未采样显示 `unmeasured`。查询不编译、不扫描源码、不启动或轮询 GPU 查询；缺少 Veil 时仍显示独立天空路径。

`/cmi particle profile on` 后，`profile`／`profile details` 可查看 BODY、CARRIER、GHOST、FEATHER、LOD1、LOD2 的 CPU/GPU p50/p95。子区间仅供诊断，预算只计外层合并时间，关闭详细采集后不提交这些查询。

客户端检查由用户执行：

1. 重启加载本次 JAR，逐一切换八包，执行 `status` 和 `status details`；确认实际包名、MODEL 路径、规则和阴影状态，检查高级材质及 BSL 混合选项。
2. 发起风暴，检查近景身体、六种剑、受伤／尸体、重叠披肩／翅膀、羽毛；以天空与地形为背景检查亮度、材质、发光、透明、遮挡及阴影。
3. 跨越近景／LOD1／LOD2 边界，检查尺寸、姿态、颜色、遮挡和成员完整性。
4. 执行 F3+T、切包、关闭光影、换维度，再查询状态；确认没有旧会话、旧错误或失效程序，回退原因可读。
5. 关闭节流，固定相机和画质后开启 profile，分别比较近景密集和远景。这是游戏内性能验收，自动 GPU 夹具不代替此步骤。
