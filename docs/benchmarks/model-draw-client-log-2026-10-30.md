# 根据客户端记录优化 MODEL 着色器

> **2026-10-30 更正：本报告原有完整 MODEL 性能结论已撤回。** 压力夹具复用 LOD 数据后未清除粒子 `p3.w` 发射器索引，部分绘制没有产生有效片元，所以下面的 69,120 对样本、约 80% 收益和替换 PASS 不能作为完整可见模型的性能证据。保留原始记录供审计，不再引用这些数字。关键帧实现及其数值正确性验证保留；本轮已加入逐场景覆盖查询并重新测量，见 [修正后的下一轮报告](shader-efficiency-client-log-r2-2026-10-30.md)。

## 实机证据与实现

输入为 `run/logs/debug.log` 中 2026-10-30 19:23:05 的 240 帧采集，Photon 1.4，RTX 4070 Laptop / NVIDIA 581.15。身体 GPU p50/p95 为 5.912/5.965 ms，透明外层为 1.844/1.869 ms，持剑仅 0.007/0.008 ms。状态记录的尺寸为 2560×1506。邻近统计约 14 万存活粒子并非可见 MODEL 数量；最新 GPU 阶段值与分段百分位不属于同帧统计，不能相加或换算 FPS。

最终生产改动只保留关键帧按需读取：单色读取一项，插值读取相邻两项，替换先构造八项局部数组的路径；整数钳制、索引、`floor`、`fract`、`mix` 和运算顺序保持原样。合并与独立绘制均应用，没有新旧配置开关，也没有新增运行时程序。原生片元、alpha test、材质编码、混合、透明排序和阴影规则保持原语义。

逐项 Photon 预检中，几何打包、提前读取动画和攻击上下文缓存均未显示超过噪声的收益，已从生产代码撤回。它们的冻结实验输入留在 `scripts/particles/experiments/model-draw/`，仅供验证工具生成对照类；不进入 JAR。主要收益已定位到顶点侧关键帧读取路径；未采集硬件指令或寄存器计数，不断言具体缓存或寄存器溢出原因。

当前交付版的编译器、模型几何与粒子 GLSL 冻结在 `.local/particle-baselines/reference/model-draw-20261030/`，manifest 记录源提交及 SHA-256。上一轮 `.local/particle-baselines/reference/model-merge/` 基线保留。验证任务检查快照校验和，不刷新快照。

## 验证和测量方法

独立 NVIDIA OpenGL 4.5 上下文使用生产合并顶点、真实网格、TBO、图集和 mip 过滤，身体近到远，透明外层远到近，启用生产背面剔除。有效动画插值、星落、螺旋、穹顶及混合攻击均进入实际着色器分支；持剑约占成员的 1/16。基线与候选均按源码判定共享持剑程序，完整三段计时包含相同的程序与纹理提交。基准不运行游戏世界或模拟，不代表客户端整帧 FPS。

输入矩阵：八包 × 1920×1080／2560×1506 × 无动画／正常动画／星落／螺旋／穹顶／混合 × 稀疏／密集／遮挡 × 0／1000／10000／50000／131072 模型。八包使用默认选项，未重建日志中没有记录的用户选项。固定相机、时间和选项，12 次交错预热，三轮各 16 对样本，轮内交错新旧顺序。记录完整三段 GPU、每段 GPU、CPU 提交、驱动编译／链接与程序数。A/A 使用同一着色源码，八包在 5 万模型的两种动画负载和三个空间场景各采集三轮。判定同时要求超过该包 A/A 相对波动和 0.002 ms，且三轮方向一致。

正确性比较全部八个片元输出槽及深度的原始位，比实际低精度附件容差更严格。覆盖透明阈值、mipmap 边缘、遮挡、透明重叠、六种剑、受伤与尸体、动画插值和攻击阶段边界，以及非白关键帧、0／1／2／4／8／9 项关键帧计数及生命期端点；额外验证羽毛、两档 LOD、geometry、独立绘制、重载和程序生命周期。未知或失败源码继续沿现有独立渲染回退。

初始完整矩阵中四个共享持剑程序包的基线夹具多切换了一次同源码程序。发现后补测这四包并整包替换原记录，未混合单个分组或挑选单轮结果；`provenance.json` 记录每包来源和 CSV 校验和。复现时直接使用当前夹具运行完整矩阵即可。

## 历史结果（性能结论已撤回）

最终门槛、八包表及 A/A 噪声以 `build/benchmarks/model-draw-delivery/summary.md`、`summary.json` 为准；原始样本在同目录 CSV，来源在 `provenance.json`。最终替换门槛 **PASS**：69,120 对样本、1440 个输入分组；576 个重负载分组均三轮改善超过噪声；稳定退化为 0，没有分组连续三轮变慢。

下表为 2560×1506、5 万模型、混合攻击、密集重叠，完整三段 GPU 绘制。变化是三轮配对比率中位数，不能直接用合并后的 p50 相除替代。

| 光影包 | 基线 p50/p95 ms | 优化 p50/p95 ms | 配对变化 | 三轮变化 |
|---|---:|---:|---:|---|
| BSL_v10.1.1.zip | 5.4595/5.6699 | 1.2314/1.2431 | -78.32% | -78.33%, -77.89%, -78.32% |
| Bliss_v2.1.2_(Chocapic13_Shaders_edit).zip | 5.7641/5.8245 | 1.2411/1.2524 | -78.88% | -78.88%, -78.34%, -79.05% |
| ComplementaryUnbound_r5.9.3.zip | 5.4687/5.5480 | 1.3015/1.3128 | -76.76% | -76.81%, -76.17%, -76.76% |
| Sildur's Vibrant Shaders v2.02 Extreme.zip | 5.8086/6.0355 | 1.2692/1.2810 | -79.01% | -79.09%, -78.28%, -79.01% |
| Solas Shader V3.8.zip | 5.3468/5.5818 | 1.0604/1.0670 | -80.88% | -81.09%, -80.27%, -80.88% |
| Sundial Lite v1.2.0.zip | 4.3566/4.5537 | 0.9068/0.9165 | -79.34% | -78.75%, -79.34%, -79.85% |
| iterationT 3.2.0.zip | 5.4482/5.6719 | 1.2063/1.2206 | -78.03% | -78.87%, -78.03%, -77.99% |
| photon_v1.4.zip | 5.3484/5.5624 | 1.0824/1.0895 | -80.46% | -80.70%, -79.85%, -80.46% |

Photon 三段 GPU p50 分别为身体 3.8784→0.8079 ms、持剑 0.4946→0.0676 ms、透明外层 0.9667→0.2048 ms。分段中位数不相加作为整段时间。

最终构建与 985 项常规单元测试、2 项八包源码测试通过。当前交付基线差分完成 258,441,340 项检查；另对上一轮历史基线完成 268,173,408 项检查，包含 BSL advanced-materials、Complementary custom-PBR 选项。独立 MODEL 顶点完成 528,000 个逐位输出比较；原生阴影 4,133,192、Iris 阴影 4,133,608、场景帧 72,764、程序生命周期 694、模型成员 1015、动画 11,719 项 GPU 回归通过，计时标签／队列满／取消／复用验证通过。

GPU 缓冲容量的新增仅为原有四个 readback 槽各预留 160 B，最多 640 B；没有新增几何缓存、姿态缓存或运行时程序。具体显存驻留由驱动决定。构建后的分发 JAR 已检查：新着色器在嵌套核心 JAR 中，冻结源码和实验类均未打包。

可直接使用 `build/libs/createmanaindustry-0.2.7.jar`；原始 CSV、A/A、元数据、校验和和回归摘要另汇总为 `build/benchmarks/model-draw-delivery-results.zip`。

编译表记录独立驱动环境中的阶段处理／编译／链接，未控制驱动磁盘缓存，不能视为完整客户端冷加载时间。程序数保持每包原有的 2 或 3 个，未通过额外程序换取绘制收益。

## 复现与客户端待验收

```powershell
./gradlew.bat -I scripts/particles/validation.init.gradle validateModelDrawGpu validateModelDrawTelemetryGpu validateModelNativeColorGpu --no-configuration-cache -PbenchmarkOutput=build/benchmarks/model-draw-check
./gradlew.bat -I scripts/particles/validation.init.gradle benchmarkModelDrawGpu --no-configuration-cache -PbenchmarkOutput=build/benchmarks/model-draw-reproduce
./gradlew.bat -I scripts/particles/validation.init.gradle benchmarkModelDrawGpu -PmodelDrawQuick -PmodelDrawVariant=aa --no-configuration-cache -PbenchmarkOutput=build/benchmarks/model-draw-reproduce-aa
python scripts/particles/summarize_model_draw.py build/benchmarks/model-draw-reproduce --aa build/benchmarks/model-draw-reproduce-aa --require-gate
```

逐项实验使用 `-PmodelDrawPack=photon -PmodelDrawQuick -PmodelDrawVariant=Geometry`，另外三项为 `Animation`、`Color`、`Context`。这些参数只被验证工具读取。

详细 profile 新增渲染尺寸、包／源码／有效选项摘要、采集帧范围，六个 MODEL 分段 GPU 采样保留原提交帧与代次。模型、持剑、透明层、羽毛、LOD1、LOD2 的 indirect 实例数通过原有 fence 快照异步取得，并标注自己的帧和代次；没有新增同步等待。计时分别显示 skipped、pending、discarded、unpublished。切包、选项／尺寸改变、换世界后刷新采集窗口。子段不重复进入节流预算，`shaderpack status` 不启动采集或编译。

以下实机验收均待用户确认。每包检查：光照与材质、受伤与尸体、攻击姿态及六种剑、透明重叠与遮挡、阴影和 LOD 切换；再检查切包、F3+T 与换维度。用 `/cmi particle shaderpack status details` 确认实际路径，用同一场景的新 `profile details` 记录对照身体和透明层耗时。

| 光影包 | 用户实机验收 |
|---|---|
| Bliss 2.1.2 | 待确认 |
| BSL 10.1.1 | 待确认 |
| Complementary Unbound 5.9.3 | 待确认 |
| iterationT 3.2.0 | 待确认 |
| Photon 1.4 | 待确认 |
| Sildur’s Vibrant 2.02 Extreme | 待确认 |
| Solas 3.8 | 待确认 |
| Sundial Lite 1.2.0 | 待确认 |
