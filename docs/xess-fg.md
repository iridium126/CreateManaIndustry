# Intel XeSS 帧生成实机验证

XeSS-FG 接入目前为实验功能。Windows x64 发行包内包含 XeSS SDK 3.0.2 的 XeSS-FG、XeLL 和本项目 JNI 模块。运行库要求 Microsoft Visual C++ 2015–2022 x64 运行时。SDK 会检查所选设备和驱动的支持情况。[Intel 开发指南](https://github.com/intel/xess/blob/v3.0.2/doc/xess_fg_developer_guide_english.md)

## 启用

在模组配置界面打开 **Intel XeSS 帧生成**，启用补帧并选择 **Arc A750** 或 **自动**。也可以在客户端 TOML 中设置后重启。

```toml
[xessFg]
enabled = true
gpu = "auto"
transport = "rgb24"
surface = "child"
```

首次测试使用笔记本内屏、SDR 和窗口或无边框模式。启动日志应显示 OpenGL 渲染设备为 RTX 4070 Laptop，并在进入世界后出现 `[CMI/XeSS-FG]` 记录。默认配置下检查 `render=RTX 4070 Laptop`、`generation=Arc A750`、`PCI 8086:56a1`、`transport=rgb24 (cross-adapter)`。`XMX-capable Arc=true` 表示设备型号具备 XMX 能力。实际推理应配合 XeSS Inspector 的有效呈现记录和能够按适配器采样的 GPU 分析工具确认。[XeSS Inspector](https://www.intel.com/content/www/us/en/developer/articles/technical/intel-xess-inspector.html)

DXGI 默认使用 Minecraft 窗口客户区里的子呈现表面，原 GLFW 窗口继续处理焦点、键盘和鼠标。深度与运动矢量按输出半宽半高生成。Bliss 和 Photon 的无扭曲包内超采样路径使用包的渲染尺寸和 jitter。XeSS-FG 负责上采样和膨胀，颜色继续按输出分辨率提交。

NVIDIA 渲染、Arc A750 生成并使用 RGB24 时，`Arc shared inputs=true` 表示 Arc 从共享缓冲直接准备本地纹理。无扭曲时 Arc 重建相机运动，RTX 只需传输原始深度和可选局部运动覆盖。2560×1600 的半尺寸几何组为 4.096 MB，仅在有覆盖时需要 8.192 MB。对照旧版的 16.384 MB 仅深度路径。传输的实际字节量见 `cross_geometry_bytes`。已识别的屏幕扭曲由 OpenGL 产生两张低分辨率输入。

HUD-less RGB24 基底通过独立的缓冲和 fence 提前发布。最终色差分以 128×8 像素 tile 存放，块遮罩精确到四个像素。复制 worker 提前提交遮罩回读，在 OpenGL 发布颜色后才启动半个刷新周期的回读预算，期间可处理已发布的几何。超过回读预算、遮罩映射失败、差分稠密或复制范围过多时使用整面差分复制。Arc 复用无差异区域的基底 RGB。

原生呈现线程按顺序呈现真实帧，最多一个待呈现作业。可选传输槽连续两帧满载、待呈现作业跨过一个刷新周期，或连续两帧观察到入队受阻或补帧作业执行超龄时，下一帧使用仅最终色的输入。共享 RGB24 的仅真实帧路径最多允许两份尚未完成 OpenGL 发布的最终色。恢复时按 Arc fence 上实际退役的呈现计数，观察到八次进展且待呈现作业未超龄后重置补帧历史，再恢复 2×。实际入队受阻、传输槽满载或待呈现作业超龄且退役停滞会重置恢复计数。`fg_skip_reason` 区分容量压力（1）、待呈现作业超龄（2）、持续呈现背压（3）和上一张颜色等待预算耗尽（4）。ETW 的 `FrameAdmission` 记录恢复计数、退役序列和队列压力。

### 独立翻转对照

- 在同一构建中，分别设置 `xessFg.surface = "borderless_child"` 和 `"borderless_popup"`，每次重启客户端。进入存档后从窗口模式按 F11 切至无边框全屏。两种模式都使用当前显示器的桌面分辨率。第一种沿用子窗口，第二种在相同位置使用由 GLFW 主窗口拥有的顶层呈现窗口。退出 F11 后使用子窗口。原 GLFW 窗口负责输入。
- 保持 RGB24、同一显示器和刷新率、相同的游戏设置及 Storm 模拟阶段。每种设置在游戏获得焦点后用常规 PresentMon 脚本配合 `/cmi particle profile frames 10` 采集。检查 `proxy_top_level` 是否分别为 0 和 1，再比较 PresentMon 报告的实际呈现模式。顶层窗口是否获得 `Hardware: Independent Flip` 由 Windows 和显示链路决定。 [Microsoft DXGI 翻转模型](https://learn.microsoft.com/en-us/windows/win32/direct3ddxgi/for-best-performance--use-dxgi-flip-model)
- 比较真实帧 P95、实际显示 FPS 及生成帧上屏率。复查 F11、Alt+Tab、最小化与恢复、鼠标捕获和键盘操作。

## 对照测试

1. 使用同一存档快照、窗口大小、光影设置、视角路径及 Storm 模拟阶段。等光影编译和区块加载稳定后先进行两轮关闭补帧的 A/A 验证，再交替采集开关各至少三轮。窗口失焦、菜单、独占全屏和首帧历史重置时，预期只显示真实帧。
2. 用 XeSS Inspector 检查每个呈现 ID 的颜色、无 HUD 颜色、深度、运动矢量和矩阵。检查序列是否为真实帧 A、中间帧、真实帧 B。观察准星、文字、半透明 HUD 和快速转视角时的前景轮廓。
3. 在 Sodium 无光影及八款现有光影包中重复测试。额外覆盖 Photon TAAU、Bliss 包内缩放、BSL 和 Complementary 水下、Sildur 水下及雨滴、Sundial 镜头畸变。验证切包、传送、最小化、恢复和调整窗口尺寸。
4. 用 `/cmi particle profile frames 10` 分别采集关闭和开启补帧的整帧数据。设置生效后，在相同场景、视角、分辨率、光影和风暴模拟配置下等待渲染稳定，再关掉聊天和菜单开始采样。
5. 比较合并生成的 `*-xessfg-pacing.json`。以 `real_display_interval_ms.p99` 为主指标，使用 `midpoint_error_ms` 的正负值分辨生成帧偏早或偏晚。固定刷新稳态目标为绝对中点误差 P95 不超过半个刷新周期加 0.5 ms，生成覆盖率较基线下降不超过 1 个百分点。客户端帧循环 P99 用于定位 GPU 和排队问题。[PresentMon](https://github.com/GameTechDev/PresentMon)
6. 用同一构建测试关闭补帧、RGBA8、RGB24 和 YCbCr 4:2:2。三种开启组合比较传输格式。旧版同步 RGBA8 可用于匹配场景的跨构建对照。4:2:2 检查细字、准星、半透明 HUD 和高对比彩色边缘。

## XeSS-FG 逐帧数据

固定刷新采集或重新合并时，给 `capture-xessfg-presentmon.ps1` 传入显示器的实际刷新率，如 `-RefreshHz 240`。`fixed_refresh.splits` 统计真实帧 A 到生成帧再到真实帧 B 的刷新周期分布，`2:2` 和 `3:1` 对应四刷新区间。偏离整数刷新周期超过 10% 的间隔记入 `unquantized_intervals`。时间线中的 `arc_inputs_to_generated_ms` 和 `arc_inputs_to_midpoint_ms` 分别表示 Arc 输入就绪到生成帧上屏及到真实帧中点的时间。VRR 采集使用毫秒指标。

采集目录 `logs/cmi-particle-frames` 中的 `frames-…-xessfg.csv` 用 `frame` 列与整帧 CSV 对齐。`next_tick_interval_ns` 从本帧开始计到下一次客户端帧循环开始，包含呈现和限速。最后一行没有下一帧样本。空白表示阶段未执行或本次采样没有收到结果。

- `xell_sleep_ns`、`early_slot_ns`、`slot_wait_ns` 区分帧节奏、槽位申请总时间和满载等待。用 `slots_in_use` 查看尝试申请前的占用，用 `early_slot` 查看分配到的槽。`disabled_checks` 的位值 1 表示帧开始时游戏条件不符，8 表示深度或速度未就绪，16 表示无 HUD 输入未就绪，32 表示导出时游戏条件不符。
- `*_cpu_ns` 和 `*_gl_ns` 分别记录 OpenGL 输入准备的 CPU 与 GPU 时间。`geometry_publish_ns` 包含提前向 GPU 发布几何组的开销，`color_submit_ns` 记录颜色组 JNI 提交。`cross_geometry_bytes` 和 `cross_color_bytes` 记录各组搬运量。
- `producer_*_gpu_ns`、`arc_*_dma_gpu_ns` 和 `arc_texture_upload_gpu_ns` 对应 RTX 复制、Arc 搬运和 Arc 本地纹理上传，各队列的耗时可能重叠。采集只在 fence 完成后读取时间戳，复制队列时间戳需驱动支持。 [D3D12 时间戳查询](https://learn.microsoft.com/en-us/windows/win32/api/d3d12/ne-d3d12-d3d12_query_heap_type)
- 格式 6 的 `native_present_ns` 与 `worker_present_duration_ns` 记录同一段呈现线程耗时。`fg_toggle_ns` 与 `dxgi_present_ns` 区分 SDK 启停和实际代理交换链调用。记录还包含背缓冲索引、同步参数、传输字节、呈现 ID、历史重置和 SDK 返回值。`queued` 是呈现链路的提交计数。SDK 内部推理时间可在 XeSS Inspector 中按呈现 ID 核对。[XeSS Inspector](https://www.intel.com/content/www/us/en/developer/articles/technical/intel-xess-inspector.html)
- 采集格式 3 增加 `present_enter_qpc`、`present_exit_qpc`、`arc_dma_ready_qpc` 和 `arc_inputs_ready_qpc`。前两列记录代理调用边界。后两列分别记录 Arc COPY 颜色组完成和 DIRECT 输入纹理就绪。JSON 内的 `xessfg_clock_samples` 按 `session_id` 保存校准点，每项依次为 QPC 频率、COPY 的 GPU tick／QPC／频率、DIRECT 的 GPU tick／QPC／频率。队列缺少校准或查询时，对应时间留空。
- 格式 4 增加最终颜色发布后进入 JNI 的 `color_submit_qpc`、RTX 颜色组复制首尾及 Arc DMA 开始时间。校准样本末尾追加 RTX COPY 的 GPU tick／QPC／频率。将 `color_gl_fence_at_exit` 和 `color_transfer_fence_at_exit` 分别与 `color_fence_token` 比较，可以判断 `Present` 返回时 GPU 是否完成 OpenGL 发布和 RTX 跨卡写入。合并 CSV 直接输出 `publish_to_rtx_start_ms`、`rtx_copy_ms` 和 `rtx_end_to_arc_start_ms`。
- 格式 5 保留两张 GPU 上几何组复制的首尾时间戳。合并 CSV 把 `rtx_end_to_arc_start_ms` 划为 `arc_wait_before_geometry_ms`、`arc_wait_overlapping_geometry_ms` 和 `arc_wait_after_geometry_ms`。另外在代理 `Present` 的调用边界读取 Arc 导入的跨卡 fence。`arc_wait_after_observed_fence_ms` 表示几何结束且 CPU 已在 Arc 侧观测到颜色 fence 完成后，颜色 DMA 开始前的等待下界。未观测到 fence 完成时该列留空。
- 格式 6 记录有效 `transport`、`pending_present_at_enqueue`、`present_enqueue_ns` 和队列满载时的 `present_backpressure_ns`。`present_queue_delay_ns` 是帧在 FIFO 中的等待，`worker_present_duration_ns` 由原生呈现线程记录。`cross_bytes` 为实际跨卡搬运量，`arc_color_unpack_gpu_ns` 对应 Arc 解包。
- 格式 7 使用 Arc 准备队列的时钟校准输入时间戳，新增 `arc_input_prepare_start_qpc`。合并时间线的 `dma_to_prepare_ms` 测量颜色搬运完成后到解包或上传开始的等待，`prepare_to_inputs_ms` 测量准备阶段。跨卡 RGB24 和 4:2:2 的几何纹理上传计入 `arc_geometry_dma_gpu_ns`，`arc_texture_upload_gpu_ns` 留空，Arc 输入就绪时间取自颜色解包完成时间。`slots_in_use` 和 `slot_wait_ns` 对应独立的传输槽。复测无损 RGB24 时，同场景比较这几列、真实帧 P95、实显 FPS 和生成帧上屏率。
- 格式 8 增加 `presenter_thread_id` 和 `proxy_present_thread_cpu_ns`，分别记录呈现线程 ID 及代理调用前后累计线程 CPU 时间的差值。合并时间线用 `proxy_to_first_dxgi_ms` 标出代理入口到本轮首个底层调用的间隔，`proxy_dxgi_overlap_ms` 与 `proxy_outside_dxgi_ms` 按同一底层交换链的 API 区间并集划分代理耗时。底层调用可能属于前一呈现 ID。代理内未被底层调用覆盖的时间还需结合 CPU 等待栈和 Arc GPU 队列归因。
- 格式 9 增加 `proxy_top_level`，记录每帧使用子窗口（0）或顶层窗口（1）。合并脚本按实际显示的真实帧与生成帧统计各自的 `pm_present_mode`。
- 格式 10 增加 `producer_wait_ns`，记录渲染线程在帧开始等待先前 OpenGL 颜色完成的时间。`slot_wait_ns` 继续记录传输槽等待。
- 格式 11 增加 `fg_gameplay_eligible`、`fg_skip_reason`、几何尺寸及 `producer_hudless_gpu_ns`，分别记录跳补帧与独立颜色基底复制。
- 格式 12 增加 `hud_copy_route`，记录稀疏复制（`sparse`）及整面回落原因（`deadline`、`dense`、`range_limit`、`map_failed`）。合并摘要中的 `hud_copy_routes` 按真实帧统计各路径。
- 格式 13 对共享 RGB24 在 HUD-less fence 就绪后提前解包基底，并在最终色 fence 就绪后仅更新有差异的 RGB24 块。`arc_hud_base_unpack_gpu_ns` 和 `arc_hud_base_ready_qpc` 记录基底阶段，`arc_color_unpack_gpu_ns` 记录最终色阶段。`arc_inputs_ready_qpc` 仍对应完整输入就绪。`fixed_refresh.four_refresh_midpoint_fraction` 的分母包括所有四刷新真实帧区间，也计入期间未显示生成帧的区间。
- `-TracePresent` 启用 Intel XeSS-FG ETW provider。CMI 的 `ArcProxyColorCopy` 记录按 Present ID 校准的 Arc 背缓冲 GPU 拷贝首尾。呈现命令分配器退役并再次使用时读取，采集尾部未再次使用的槽可能缺失。

## PresentMon 联合采集

在项目目录打开 PowerShell，启动 Minecraft 并进入测试场景，再运行以下命令。脚本默认使用本项目 `run/.cache/presentmon` 中的 PresentMon 2.6.0 x64 便携控制台。其他工作区可从官方发行页获取同版本，或用 `-PresentMonExe` 指定路径。运行目录不同可传 `-GameDirectory`，打开多个客户端时传 `-GamePid`。 [PresentMon 2.6.0](https://github.com/GameTechDev/PresentMon/releases/tag/v2.6.0)

```powershell
.\scripts\particles\capture-xessfg-presentmon.ps1
```

脚本开始 30 秒 ETW 捕获后，切回 Minecraft，执行 `/cmi particle profile frames 10` 并关闭聊天。结束后在 `logs/cmi-particle-frames` 查看 `presentmon-*.csv` 和 `frames-…-xessfg-timeline.csv`。合并脚本需要 Python 3。Windows 如果拒绝 ETW 访问，可从管理员 PowerShell 执行。两份原始文件始终会保留，可通过 `-FrameCapture <统计 JSON> -PresentMonCsv <CSV>` 重新合并。

排查代理 `Present` 内部等待时，从管理员 PowerShell 运行 `.\scripts\particles\capture-xessfg-presentmon.ps1 -TracePresent`，并在游戏内执行同一条采集命令。额外生成的 `xessfg-present-*.etl` 含 CPU 和 GPU 轨迹。用 WPA 的 Generic Events 查看 `CreateManaIndustry.XeSSFG` 的 `ProxyPresentStart` 与 `ProxyPresentStop`，按 `PresentId` 或 `CaptureFrame` 对齐 CSV，然后用 CPU Usage (Precise) 检查呈现线程的等待和唤醒，用 GPU Activity 对照 Arc 工作区间。带 WPR 的样本用于归因，性能对照使用常规采集。 [WPR 命令行](https://learn.microsoft.com/en-us/windows-hardware/test/wpt/wpr-command-line-options) · [WPA 线程等待分析](https://learn.microsoft.com/en-us/troubleshoot/windows-server/support-tools/support-tools-xperf-wpa-wpr)

脚本优先使用本机版本较新的 WPR，可用 `-WprExe` 显式指定 Windows Performance Toolkit 中的 `wpr.exe`。WPR 停止失败时，脚本尝试用同一工具包的 Xperf 停止该次采集的命名会话，并合并有数据的原始 ETL。可用管理员 PowerShell 执行 `.\scripts\particles\capture-xessfg-presentmon.ps1 -RecoverTrace <采集时间戳-进程ID>` 重试恢复。原始 ETL 保留在采集目录，同名合并文件存在时会使用 `-recovered` 后缀。PresentMon 和整帧 CSV 可独立使用。 [WPR 停止错误](https://devblogs.microsoft.com/performance-diagnostics/wpr-start-and-stop-commands/) · [Xperf 轨迹合并](https://learn.microsoft.com/en-us/previous-versions/windows/desktop/xperf/merging-traces)

时间线用同 ID 且落在代理 `Present` 区间内的真实或生成帧验证实际交换链，再按 `AppFrameId` 分别匹配真实帧和生成帧。真实帧的底层 DXGI 调用可以在下一个代理调用期间出现。歧义行保留为 `unmatched_app` 或 `unmatched_display`，脚本打印匹配覆盖率与生成帧显示比例。 `inputs_minus_present_ms` 为正表示 Arc 本地输入在进入 `Present` 后才就绪。显示侧没有报告显示时刻的帧保留空值。 [PresentMon CSV 字段](https://github.com/GameTechDev/PresentMon/blob/main/README-ConsoleApplication.md)

启用插帧时，代理交换链用 `SyncInterval=1` 呈现生成帧和真实帧，避免较新的翻转按间隔 0 的规则替换排队帧。复测时保留上次的 Minecraft 垂直同步设置，验证 XeSS-FG CSV 的 `dxgi_sync_interval=1`，再比较脚本报告的生成帧显示比例、实际显示 FPS 和真实帧 P95。同步呈现的节奏受当前显示刷新率约束。 [Microsoft DXGI Present](https://learn.microsoft.com/en-us/windows/win32/api/dxgi/nf-dxgi-idxgiswapchain-present)

在本机配置原生构建时传入 `-DCMI_XESSFG_BUILD_PROBE=ON`。运行 `ctest --test-dir build/xessfg-native -C Release -R '^xessfg_' --output-on-failure -V`。调度探针检查背压进入及八帧恢复，传输探针检查双卡低分辨率输入、运动覆盖合并、HUD tile、槽位退役、尺寸变化和设备丢失。代理探针独立启动 XeSS SDK，在交互桌面提交六张合成真实帧，检查固定刷新间隔、顺序、真实帧直通和重建历史。交互桌面上可在 SDK `bin` 已加入 PATH 的环境运行 `CmiXessFgPipelineProbe.exe --display`，此模式要求至少一帧实际进入生成队列。

### 客户端 A/B 与画质

可先用 `.\gradlew.bat --no-configuration-cache -I scripts/particles/validation.init.gradle validateXessFgMotionGpu validateParticleProgramsGpu validateNativeRiftPacks validateModelMergeGpu -PxessTerrain`。合成场景检查低分辨率网格上的目标速度、加法和透明覆盖、jitter、遮挡与历史重置。光影探针编译链接八个样本的默认 Sodium 地形和水面程序，合批探针还检查项目粒子的着色器与原有 MRT 输出。

用提交 `a987720f` 的独立构建作为基线。相同机器、显示器、显示模式、光影设置、存档、视角路径及 Storm 负载下，先做两轮基线 A/A，再交替做至少三组优化前后对照。每次用 `.\scripts\particles\capture-xessfg-presentmon.ps1 -Seconds 80` 启动采集，并在游戏内执行 `/cmi particle profile frames 60`。分别采集稳态和有背压的场景。固定刷新与启用 VRR 的结果分开比较。

| 验收场景 | 检查项 |
|---|---|
| 无光影、Sodium | 固定相机观察实体走动和挥臂、挥手换物、齿轮转动、构件平移和原版粒子。再移动相机验证合成运动。 |
| Bliss、Photon | 同样测试五类运动，并检查包内缩放、jitter 和细轮廓。另检查 Bliss 截图模式。 |
| BSL、Complementary、Sildur | 同样测试五类运动，额外覆盖水下、风摆和 Sildur 雨滴。 |
| IterationT、Solas、Sundial Lite | 同样测试五类运动，并检查风摆、Sundial 畸变和外部超分切换。 |

当前覆盖经 BufferBuilder 提交的实体、手部、方块实体、原版粒子和 Flywheel 1.0.6 实例。原版透明粒子按图集与顶点 alpha 筛选覆盖。项目 GPU 粒子的面片、模型、羽毛和远距 LOD 在原绘制中记录实际顶点，支持自绘和 Iris 合批。加法火花按亮度筛选，透明贴图粒子按 alpha 筛选。粒子覆盖只为可见样本写运动，导出时保留深度输入的采样值。

八个已识别光影包使用 Iris 变换后的 Sodium 程序记录地形和水面实际形变。IterationT 通过地形几何阶段转发原三角形的历史，Sildur 的不透明地形使用其实际回落的 textured 程序。BSL 手部曲率和 Complementary 手部摆动在已识别源码及选项开启时按对应帧的参数求值。GPU 采集目前要求 NVIDIA 64 位 SSBO 原子操作。包专用的其他实体变形以及额外生成的几何或细分顶点仍需接入。历史在提交插帧输入后推进。Bliss 截图模式、Sundial 外部超分和未知渲染配置呈现真实帧。

### 本次探针结果

RTX 4070 Laptop 与 Arc A750 通过三项原生探针。代理为六张合成真实帧分别排入 1、2、2、1、1、2 个呈现，其中第 4 帧走真实帧专用槽，第 5 帧重建历史，第 6 帧恢复生成。PresentMon 记录到真实帧和生成帧上屏。短采样仅含一个完整的真实帧、生成帧、真实帧区间，其中点误差为 +4.17 ms。

RTX 隐藏窗口深度导出探针中，2560×1600 到 1280×800 的深度有效载荷由 16.384 MB 降至 4.096 MB，一次采集的导出 GPU P99 由 0.043 ms 降至 0.016 ms。真实帧上屏 P99、生成覆盖率和双卡稳态 GPU 时间由客户端采集确认。
