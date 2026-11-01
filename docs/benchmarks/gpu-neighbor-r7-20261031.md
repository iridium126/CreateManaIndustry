# R7 专用硬件实验记录

状态：已实现隔离 RT／Tensor 查询原型、共享 COPY 桥及诊断扩展；**整套 R7 尚未完成**。生产查询边界 continuation、原生工作线程的失败退休、完整物理与八包画面准入尚未完成，因此客户端只注册 `production`。没有实机 FPS 提升结论，也没有因为离屏稍慢而淘汰客户端候选。

## 基线与范围

`build/benchmarks/neighbor-r7/baseline/manifest.json` 冻结了 R7 开始时的工作区、暂存补丁和客户端输入，共 2,645 个文件；与 R4–R6 分开保存。包括本轮四个完整帧时窗口、四份快照、两条 64 帧连续序列、光影实际选项及 debug.log。交付脚本再次检查暂存补丁和生产着色器摘要。

相机朝向由用户确认：低可见为水平、地形负载高；高可见为向上、地形负载小；零可见为向下、地形负载小。三个朝向各自匹配测量，不将跨朝向差值归因于粒子。

旧快照是 GENERIC 前的输入，不覆盖完整更新链。两条旧序列只有读池位置、速度、身份和池索引。R7 几何实验按固定半径 1、每帧前 128 个稳定身份查询、全部 65,527 个成员建索引；没有伪装成实际局部查询或完整物理回放。

## 可运行实现

| 路线 | 当前实现及证据 | 客户端准入 |
|---|---|---|
| RT AABB | 保留自定义 AABB／intersection 对照，支持重建与 refit；总时间包含 SM 计算 | 未通过完整物理与画面门槛 |
| RT triangle | 静态三角形 BLAS、动态实例 IAS、固定 +X 射线；原 FP32 球距离复核 | 同上 |
| Tensor | 保守哈希 tile 对、共同局部原点和 INT8 量化，WMMA INT8×INT8→INT32，FP32 最终复核 | 同上 |
| Tensor control | 相同分块、量化、阈值及输出，点积改为标量 CUDA | 归因对照，不计作专用单元收益 |
| CUDA control | 完整网格查询；远坐标范围过大时完整枚举，显式溢出 | 归因对照 |
| COPY | D3D12 DEFAULT 共享缓冲／Fence，三槽资源，GL／CUDA／D3D12 按 LUID 和 node mask 核对 | 仅隔离交接探针，尚未替换生产状态复制 |
| 光流 | `nvofapi64.dll` 驱动 API 存在；未发现可替换的权威三维粒子工作 | 不用二维图像运动估计替代模拟或碰撞 |
| NVENC／NVDEC | 驱动 API DLL 存在；没有适配本轮权威粒子工作的固定媒体功能 | 录像或压缩不记作粒子加速 |

驱动 API 存在不等于已查询具体格式、分辨率等能力；媒体行不宣称完成运行时硬件会话测试。RTX 4070 Laptop 的 CUDA capability 为 8.9，报告一个 async engine；不能据此推导实际重叠或 SM 释放比例。

### 三角形代理的覆盖边界

每个成员使用半径 `R + padding` 的保守实例尺度；静态三角形覆盖单位盒的 +X 出口面。投影顶点为 `(-3,-1),(1,-1),(1,3)`，覆盖 `[-1,1]^2`；保守膨胀使真实球内点落在覆盖内侧。固定 +X 射线只需要这一出口面，不建立十二个面片并依赖重复命中去重。使用单 primitive、instance ID 和 `REQUIRE_SINGLE_ANYHIT_CALL`，再过滤自身并执行原 FP32 判定。

远坐标膨胀、射线长度和实例变换仍有 FP32 误差；已有面／边／角、ULP 邻域、重合、负坐标与 ±远坐标实测。它们证明已测输入上的集合覆盖，不代替生产物理证明。源码明确使用 TRIANGLES primitive；raygen 与 any-hit 仍执行 SM 工作。

### Tensor 的保守性与代价

每个 query tile 为 16 个查询，逐桶流式生成候选点 tile，不分配全池 N² 矩阵。哈希桶去重，真实距离用各查询自己的 FP32 原点和半径计算；支持输入点数组中的 owner 索引，与 query 序号解耦。生产接入时仍须显式保留点数组索引与原池索引的映射，不能混用。量化误差界向外扩大阈值，clamp 时放行；过大的格范围走完整枚举。两遍计数／扫描／写入及所有打包都计时，溢出时不写部分 CSR。

保存的 `tensor.cu.ptx` 含 `wmma.mma.sync.aligned.row.col.m16n16k16.s32.s8.s8.s32`。没有 SASS／硬件计数器或 SM 占用证据，不宣称释放某个比例的常规计算资源。Tensor Core 仍位于 SM 内，且三维点积需要补齐，打包和桶遍历仍有常规 CUDA 成本。

### COPY 与错误证据

17 字节、4 KiB、1 MiB、16 MiB 的三槽复用经过 GL GPU 暂存读取和 CUDA 读取的逐字节核对。保存 COPY 队列时间、GL 完整交接区间和 CPU 提交时间。外部缓冲直接重复 CPU 回读曾出现旧值，而 CUDA 与 GPU 暂存读取一致；保留 v3 的四份字节转储，不据此推断生产读回接口已经合格。

最初原生探针还遇到当前 MSVC／运行库的 mutex 初始化崩溃；改用 Windows SRWLOCK 并复测，原始 `hs_err` 保留。不升级驱动或全局运行库。

现有共享探针正常路径可运行；异常设备、超时隔离和异步工作线程通知尚未达到生产契约。512 MiB 限制目前约束显式原生缓冲，驱动内部资源未精确计量，因此不宣称满足完整原生资源预算准入。

## 诊断接口

```text
/cmi particle profile hardware status
/cmi particle profile backend production
/cmi particle profile on
/cmi particle profile frames 30
/cmi particle profile capture comp_horizontal_r7
/cmi particle profile querysequence comp_motion_r7 64
```

没有注册 `rt-aabb|rt-triangle|tensor|tensor-control|cuda-control|copy` 的客户端切换，避免把仅几何正确的原型当成已验证的模拟后端。默认不加载原生模块；`hardware status` 显式探针才尝试加载。

帧时 schema 3 增加相机位置、yaw／pitch、实际投影推导的垂直 FOV；增加合并绘制父范围和 MODEL 阴影准备／绘制子范围、带来源帧及代次的阴影数量。未知计数保留 -1，不借用旧值。`iris.whole-stage.begin|prepare|deferred|composite` 来自 Iris 的实际 CompositePass；旧 `sky.pack-stage.*` 是整个 CompositeRenderer.renderAll，不能解释成云层独占时间。

输入准备下增加状态准备、分配器、reset、damage 和 grid 子范围，不能与父范围相加。状态准备内复制与清空尚未独立计时；原生准备／BVH／count／scan／write 的离屏原始时间另外保存。

连续捕获 schema 2 保留命令语法，扩展到每成员 64 字节：读池位置／身份、速度／池索引、实际分离原点／半径、查询 owner／身份／flags／位置阶段。查询在局部 Column 恢复之后的原始边界记录；Dome 跳过早期分离有单独 flag。仅采集期间懒编译一个完整统一 update，仍按 GENERIC → MODEL 调度，不拆域。三槽 GPU 读回与两项 CPU 队列保持有界，缺帧或溢出使整段失效。

此格式**尚未覆盖 Dome post-integration 查询或完整链初始状态**。新旧序列解析兼容；旧记录不会被补造原点、半径或阶段。

## 验证与结果

基础回归：1,006 项 Java 测试、15 项 Python 测试、23,635 项统一捕获变体检查、72,764 项 scene-frame 检查和 MODEL draw telemetry 回归通过。最终非空全查询矩阵共 4,550,699 项检查，覆盖 1,000／10,000／50,000／65,535／65,536／131,072 个成员全部发出非空查询；另外包含空池、工作组边界、密集、重合、溢出、远坐标及不同查询 owner／半径。

两条 64 帧序列已完成三轮独立进程，各 64 对交错 A/A、A/B。下表为三轮的每序列帧平均几何 GPU 时间范围，单位 ms；不是 65,527 个成员全部查询、完整链或客户端帧时。

| 路径 | motion | dome |
|---|---:|---:|
| CUDA control | 0.840–0.847 | 0.563–0.569 |
| AABB 每帧重建 | 1.017–1.027 | 0.848–0.857 |
| AABB 每 4 帧重建 | 0.691–0.700 | 0.525–0.531 |
| AABB 每 8 帧重建 | 0.637–0.645 | 0.474–0.480 |
| Triangle 每 8 帧重建 | 1.569–1.583 | 1.178–1.191 |
| Tensor control | 2.938–2.961 | 2.346–2.364 |
| Tensor | 2.776–2.796 | 2.198–2.214 |

AABB 4／8 周期的局部收益三轮均超过匹配 A/A 噪声及 0.5%／0.002 ms 下限。Tensor 指令相对同算法标量对照节省约 0.147–0.165 ms，但完整几何查询仍慢于直接 CUDA 网格。Triangle 的 IAS 准备／维护和 count／write 查询阶段都更贵，不能将任一总时间解释为 RT Core 专属工作。原始每帧 p95／p99 另存于 `sequence-summary-tail.json`；没有完整链及其他负载尾部准入结论。旧约 9 ms refit 失败证据继续保留。

COPY 并发探针另完成三进程、64 对 A/A／A/B：复制真实快照的 25,234,080 字节状态，配合四次独立 2560×1506 光栅绘制，比较 GL copy、D3D12 COPY 立即等待及消费者处等待。全部字节／像素核对通过。匹配 A/B 的收益约 -0.006 至 +0.003 ms，未超过对应 A/A 噪声；延迟等待没有稳定净收益或实际重叠证明。分开比较 GL 的 A/A 段与后面的 COPY A/A 段会产生约 0.2 ms 的假象，不能采用这种跨段比较。

显式 COPY 队列证明了 API 传输路径，不证明原 GL copy 使用 SM，也不提供物理引擎利用率。没有硬件时间线／计数器时不报告 SM 释放比例。这里的光栅负载是独立探针，不是 Complementary 地形。

所有收益判断仍限制在实际测量范围；独立离屏收益不作为客户端硬性准入，慢于 CUDA 的专用硬件路径也不因此提前淘汰。值得优先接入完整物理验证的是 AABB 4／8 周期；其余路径保留实验记录，不能跳过正确性接入，也没有候选获得 FPS 结论。

序列计时范围澄清：GPU event 从传统 GL map 之后开始；completed wall 包含 map／unmap 所有权同步，可能包含尚未完成的测试输入 GL 上传等待。CPU 的测试上传调用本身在两者之外。因此原始 metadata 的“上传已排除”描述仅适用于 GPU event，不能据此把 completed wall 当成纯查询时间；汇总保留这项更正，不改写历史样本。

## 尚未完成的 R7 工作

1. 分离与 Dome 的实际生产 continuation、同语义无截断 GL 后备与完整事件发布事务。
2. 原生工作线程及三槽交接的超时隔离、设备错误和未退休资源处理；实机 Iris 阴影地形重叠窗口。
3. Dome 正确位置阶段的连续输入及完整链初始资源／CPU 配置捕获。
4. 多帧伤害、穿透、完成事件、动画槽及发布代次验证；八包／两分辨率全部附件与深度、新轨迹画质验证。
5. 满足以上正确性准入后开放实验开关，再按固定相机场景三进程 30 秒／1,000 帧 A/A、A/B 裁决实机收益。

这些未完成项不能由几何集合检查、COPY 字节一致或旧客户端帧时替代。目前没有候选替换默认生产路径。

## 构建与复现

原生独立构建：`scripts/particles/build-hardware-offload.ps1`，沿用缓存 OptiX 9.0／CUDA 12.8 NVRTC，普通 Gradle 构建不调用它。隔离 GPU 任务位于 `scripts/particles/validation.init.gradle`：`validateR7HardwareGpu`、`validateR7CaptureGpu`、`validateR7SequenceGpu`、`validateR7CopyConcurrencyGpu`。`run_r7_sequences.ps1` 默认三进程、64 对交错样本，已完成输出保留，失败日志使用独立名称。

硬件语义依据：[NVIDIA Ada 调优指南](https://docs.nvidia.com/cuda/ada-tuning-guide/)、[OptiX 自定义／三角形求交说明](https://developer.nvidia.com/blog/how-to-get-started-with-optix-7/)、[Win32 外部对象规范](https://raw.githubusercontent.com/KhronosGroup/OpenGL-Registry/main/extensions/EXT/EXT_external_objects_win32.txt)、[CUDA 12.8 互操作文档](https://docs.nvidia.com/cuda/archive/12.8.1/cuda-c-programming-guide/index.html)、[光流接口](https://docs.nvidia.com/video-technologies/optical-flow-sdk/nvofa-programming-guide/index.html)、[NVENC 文档](https://docs.nvidia.com/video-technologies/video-codec-sdk/13.0/nvenc-video-encoder-api-prog-guide/index.html)。
