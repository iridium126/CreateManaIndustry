# Intel XeSS 帧生成实机验证

XeSS-FG 接入目前为实验功能。Windows x64 发行包内包含 XeSS SDK 3.0.2 的 XeSS-FG、XeLL 和本项目 JNI 模块。运行库要求 Microsoft Visual C++ 2015–2022 x64 运行时。SDK 会检查所选设备和驱动的支持情况。[Intel 开发指南](https://github.com/intel/xess/blob/v3.0.2/doc/xess_fg_developer_guide_english.md)

## 启用

在模组配置界面打开 **Intel XeSS 帧生成**，启用补帧并选择 **Arc A750** 或 **自动**。也可以在客户端 TOML 中设置后重启。

```toml
[xessFg]
enabled = true
gpu = "auto"
```

首次测试使用笔记本内屏、SDR 和窗口或无边框模式。启动日志应显示 OpenGL 渲染设备为 RTX 4070 Laptop，并在进入世界后出现 `[CMI/XeSS-FG]` 记录。检查 `render=RTX 4070 Laptop`、`generation=Arc A750`、`PCI 8086:56a1`、`transport=cross-adapter`。`XMX-capable Arc=true` 表示设备型号具备 XMX 能力。实际推理应配合 XeSS Inspector 的有效呈现记录和能够按适配器采样的 GPU 分析工具确认。[XeSS Inspector](https://www.intel.com/content/www/us/en/developer/articles/technical/intel-xess-inspector.html)

DXGI 使用 Minecraft 窗口客户区里的子呈现表面，原 GLFW 窗口继续处理焦点、键盘和鼠标。跨卡模式在深度与速度导出后启动第一组搬运，最终颜色导出后提交第二组。Arc 复制队列把数据搬入本地缓冲，呈现队列使用本地缓冲上传纹理并调用 XeSS-FG。四个完整传输槽独立于三个交换链背缓冲，满载时在帧开始等待最早完成的 fence，并处理窗口事件。

## 对照测试

1. 使用同一存档快照、窗口大小、光影设置、视角路径及 Storm 模拟阶段。等光影编译和区块加载稳定后先进行两轮关闭补帧的 A/A 验证，再交替采集开关各至少三轮。窗口失焦、菜单、独占全屏和首帧历史重置时，预期只显示真实帧。
2. 用 XeSS Inspector 检查每个呈现 ID 的颜色、无 HUD 颜色、深度、运动矢量和矩阵。检查序列是否为真实帧 A、中间帧、真实帧 B。观察准星、文字、半透明 HUD 和快速转视角时的前景轮廓。
3. 在 Sodium 无光影及八款现有光影包中重复测试。额外覆盖 Photon TAAU、Bliss 包内缩放、BSL 和 Complementary 水下、Sildur 水下及雨滴、Sundial 镜头畸变。验证切包、传送、最小化、恢复和调整窗口尺寸。
4. 用 `/cmi particle profile frames 10` 分别采集关闭和开启补帧的整帧数据。设置生效后，在相同场景、视角、分辨率、光影和风暴模拟配置下等待渲染稳定，再关掉聊天和菜单开始采样。
5. 用两份统计 JSON 的 `interval_ms.p95` 计算真实帧间隔增幅，公式是 `(开启 P95 / 关闭 P95 - 1) × 100%`，目标为 5% 以内。实显 FPS、显示帧间隔和呈现延迟由 PresentMon 等显示侧工具采集。[PresentMon](https://github.com/GameTechDev/PresentMon)

## XeSS-FG 逐帧数据

采集目录 `logs/cmi-particle-frames` 中的 `frames-…-xessfg.csv` 用 `frame` 列与整帧 CSV 对齐。`next_tick_interval_ns` 从本帧开始计到下一次客户端帧循环开始，包含呈现和限速。最后一行没有下一帧样本。空白表示阶段未执行或本次采样没有收到结果。

- `xell_sleep_ns`、`early_slot_ns`、`slot_wait_ns` 区分帧节奏、槽位申请总时间和满载等待。用 `slots_in_use` 查看尝试申请前的占用，用 `early_slot` 查看分配到的槽。`disabled_checks` 的位值 1 表示帧开始时游戏条件不符，8 表示深度或速度未就绪，16 表示无 HUD 输入未就绪，32 表示导出时游戏条件不符。
- `*_cpu_ns` 和 `*_gl_ns` 分别记录 OpenGL 输入准备的 CPU 与 GPU 时间。`geometry_publish_ns` 包含提前向 GPU 发布几何组的开销，`color_submit_ns` 记录颜色组 JNI 提交。`cross_geometry_bytes` 和 `cross_color_bytes` 记录各组搬运量。
- `producer_*_gpu_ns`、`arc_*_dma_gpu_ns` 和 `arc_texture_upload_gpu_ns` 对应 RTX 复制、Arc 搬运和 Arc 本地纹理上传，各队列的耗时可能重叠。采集只在 fence 完成后读取时间戳，复制队列时间戳需驱动支持。 [D3D12 时间戳查询](https://learn.microsoft.com/en-us/windows/win32/api/d3d12/ne-d3d12-d3d12_query_heap_type)
- `native_present_ns` 是整个 JNI 呈现调用。`fg_toggle_ns` 与 `dxgi_present_ns` 区分 SDK 启停和实际代理交换链调用。记录还包含背缓冲索引、同步参数、传输字节、呈现 ID、历史重置和 SDK 返回值。`queued` 是呈现链路的提交计数。SDK 内部推理时间可在 XeSS Inspector 中按呈现 ID 核对。[XeSS Inspector](https://www.intel.com/content/www/us/en/developer/articles/technical/intel-xess-inspector.html)
- 采集格式 3 增加 `present_enter_qpc`、`present_exit_qpc`、`arc_dma_ready_qpc` 和 `arc_inputs_ready_qpc`。前两列记录代理调用边界。后两列分别记录 Arc COPY 颜色组完成和 DIRECT 输入纹理就绪。JSON 内的 `xessfg_clock_samples` 按 `session_id` 保存校准点，每项依次为 QPC 频率、COPY 的 GPU tick／QPC／频率、DIRECT 的 GPU tick／QPC／频率。队列缺少校准或查询时，对应时间留空。
- 格式 4 增加最终颜色发布后进入 JNI 的 `color_submit_qpc`、RTX 颜色组复制首尾及 Arc DMA 开始时间。校准样本末尾追加 RTX COPY 的 GPU tick／QPC／频率。将 `color_gl_fence_at_exit` 和 `color_transfer_fence_at_exit` 分别与 `color_fence_token` 比较，可以判断 `Present` 返回时 GPU 是否完成 OpenGL 发布和 RTX 跨卡写入。合并 CSV 直接输出 `publish_to_rtx_start_ms`、`rtx_copy_ms` 和 `rtx_end_to_arc_start_ms`。
- 格式 5 保留两张 GPU 上几何组复制的首尾时间戳。合并 CSV 把 `rtx_end_to_arc_start_ms` 划为 `arc_wait_before_geometry_ms`、`arc_wait_overlapping_geometry_ms` 和 `arc_wait_after_geometry_ms`。另外在代理 `Present` 的调用边界读取 Arc 导入的跨卡 fence。`arc_wait_after_observed_fence_ms` 表示几何结束且 CPU 已在 Arc 侧观测到颜色 fence 完成后，颜色 DMA 开始前的等待下界。未观测到 fence 完成时该列留空。

## PresentMon 联合采集

在项目目录打开 PowerShell，启动 Minecraft 并进入测试场景，再运行以下命令。脚本默认使用本项目 `run/.cache/presentmon` 中的 PresentMon 2.6.0 x64 便携控制台。其他工作区可从官方发行页获取同版本，或用 `-PresentMonExe` 指定路径。运行目录不同可传 `-GameDirectory`，打开多个客户端时传 `-GamePid`。 [PresentMon 2.6.0](https://github.com/GameTechDev/PresentMon/releases/tag/v2.6.0)

```powershell
.\scripts\particles\capture-xessfg-presentmon.ps1
```

脚本开始 30 秒 ETW 捕获后，切回 Minecraft，执行 `/cmi particle profile frames 10` 并关闭聊天。结束后在 `logs/cmi-particle-frames` 查看 `presentmon-*.csv` 和 `frames-…-xessfg-timeline.csv`。合并脚本需要 Python 3。Windows 如果拒绝 ETW 访问，可从管理员 PowerShell 执行。两份原始文件始终会保留，可通过 `-FrameCapture <统计 JSON> -PresentMonCsv <CSV>` 重新合并。

时间线用同 ID 且落在代理 `Present` 区间内的真实或生成帧验证实际交换链，再按 `AppFrameId` 分别匹配真实帧和生成帧。真实帧的底层 DXGI 调用可以在下一个代理调用期间出现。歧义行保留为 `unmatched_app` 或 `unmatched_display`，脚本打印匹配覆盖率与生成帧显示比例。 `inputs_minus_present_ms` 为正表示 Arc 本地输入在进入 `Present` 后才就绪。显示侧没有报告显示时刻的帧保留空值。 [PresentMon CSV 字段](https://github.com/GameTechDev/PresentMon/blob/main/README-ConsoleApplication.md)

启用插帧时，代理交换链用 `SyncInterval=1` 呈现生成帧和真实帧，避免较新的翻转按间隔 0 的规则替换排队帧。复测时保留上次的 Minecraft 垂直同步设置，验证 XeSS-FG CSV 的 `dxgi_sync_interval=1`，再比较脚本报告的生成帧显示比例、实际显示 FPS 和真实帧 P95。同步呈现的节奏受当前显示刷新率约束。 [Microsoft DXGI Present](https://learn.microsoft.com/en-us/windows/win32/api/dxgi/nf-dxgi-idxgiswapchain-present)

在本机配置原生构建时传入 `-DCMI_XESSFG_BUILD_PROBE=ON`，可用 `ctest -C Release -R xessfg_pipeline_probe` 复验双卡传输数据、四槽满载、部分帧退役、尺寸变化和设备丢失。

当前运动矢量来自深度、相机和已识别的光影坐标变换。实体动画、手部、Create 机械、粒子和动态顶点的独立运动尚待接入。Bliss 截图模式、Sundial 外部超分路径及未匹配的渲染配置会走真实帧。首版的实机画质、三适配器呈现和性能目标仍待验证。
