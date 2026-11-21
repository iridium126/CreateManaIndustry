# NVIDIA DLSS 超分辨率

Windows x64 客户端在渲染 Minecraft 的 RTX 显卡上执行 DLSS。支持无光影的原版／Sodium 路径和下表列出的八个 Iris 样本。 [NVIDIA DLSS SDK 310.6.0](https://github.com/NVIDIA/DLSS/releases/tag/v310.6.0)

## 启用和检查

```toml
[dlss]
enabled = true
renderScale = 0.6666667
```

`renderScale` 是宽、高各自的比例。`1.00` 使用原生尺寸和 DLAA。`0.50–1.00` 内的请求尺寸需通过驱动查询。在 2560×1600、默认比例下，场景目标为 1707×1067。日志中的 `[CMI/DLSS] NGX` 记录输入、输出和渲染设备。状态不支持时继续走原有渲染。

与 XeSS-FG 同开时，先重建世界颜色，再执行原生分辨率后处理、发布 HUD-less 并绘制 HUD。Arc 继续使用原有低分辨率几何和稀疏 HUD。Iris 复用包的栅格 jitter。Bliss 与 Photon 的 TAAU 设置生效时，DLSS 读取其实际渲染子区域并接管积累，后续阶段仍按原选项读取辅助纹理。Bliss 截图、Sundial 外部超分和未识别的源码契约沿用包的原有路径。 [XeSS-FG 采集指南](xess-fg.md)

## 构建与探针

发行包将 `CmiDlssSr.dll`、NVIDIA 签名的 `nvngx_dlss.dll`、许可证和 SHA-256 清单作为独立资源打包。NVIDIA 组件适用同目录的 NVIDIA 许可。

```powershell
cmake -S src/main/native/dlss -B build/dlss-native -A x64 -DDLSS_SDK_DIR=build/dlss-310.6.0
cmake --build build/dlss-native --config Release
.\gradlew.bat --offline -PrequireDlssBundle -I scripts/particles/validation.init.gradle validateDlssGpu test jar
.\gradlew.bat --offline -PrequireDlssBundle -PdlssArc -I scripts/particles/validation.init.gradle validateDlssGpu
.\gradlew.bat --offline -PdlssIris -I scripts/particles/validation.init.gradle validateNativeRiftPacks
```

修改 JNI 后，用 Release 产物替换 `src/main/native/prebuilt/windows-x86_64/dlss/CmiDlssSr.dll`，同步其清单哈希。模型采用 SDK 的 Windows Release 版本。记录 JAR 的 SHA-256 对齐客户端采集。

RTX 4070 Laptop 探针验证三槽重用、奇数尺寸、DLAA、手部遮挡、历史重置和 Iris 附件切换。1707×1067 重建到 2560×1600 的 NGX GPU 平均耗时约 1.9–2.1 ms。与 Arc A750 串联时，8 次真实帧提交均按序退役，6 次报告两次呈现，8 次 HUD 传输均采用稀疏复制。Iris 探针使用真实的预处理器、着色器转换器和 RTX 驱动编译各包的重建程序。客户端画质和上屏 P99 由下方采集验收。

## 客户端验收

固定场景、视角、Storm 负载、分辨率与呈现模式。先跑 A/A，再交替采集原生＋2× 和 DLSS＋2×，每组 60 秒，至少三组。固定刷新与 VRR 分开判定。有规律地使用相同输入动作以提供输入延迟样本。

```powershell
.\scripts\particles\capture-xessfg-presentmon.ps1 -GamePid <PID> -Seconds 70 -RefreshHz 240
```

在游戏中运行 `/cmi particle profile frames 60`，关闭聊天菜单并等待完成。VRR 采集省略 `-RefreshHz`。比较 `display_interval_ms.p99`、`real_display_interval_ms.p99`、`real_input_to_display_ms.p95` 和 `generation_coverage`，并核对输入延迟样本数。阶段 CSV 记录 `dlss.iris.world`、`dlss.inputs.geometry`、`dlss.inputs.iris.color`、`dlss.submit`、`dlss.ngx`、`dlss.iris.promote`、`dlss.iris.post` 和 `dlss.iris.history`。无光影路径使用 `dlss.world`、`dlss.inputs.color` 和 `dlss.scene.output`。

所有路径都检查细纹理、轮廓和遮挡边缘，以及实体、手部、Create 机械、粒子、动态顶点和 HUD。切包、重载、传送和改尺寸各检查一次，核对日志中的实际输入尺寸、包名和运行路径。以下画质和性能项目待客户端验收。

| 路径 | 重建入口 | 补充检查 |
|---|---|---|
| 无光影原版／Sodium | 世界绘制与 HUD 之间 | 原生屏幕和叠层 |
| Bliss | `dimensions/composite5`，Iris `composite6` | TAAU 子区域、云和截图模式切入／切出 |
| BSL | `composite7`，关闭 TAA 时进入 `final` 前 | 水下扭曲、TAA 两种采样模式 |
| ComplementaryUnbound | `composite6` | 手部摆动、颜色和最终扭曲 |
| IterationT | `composite7` | 主世界、下界、末地及景深 |
| Photon | `c4_taa_exposure`，Iris `composite4` | 曝光过渡、TAAU 和之后的运动模糊 |
| Sildur | `composite2` | 最终色路由、雨滴和水下折射 |
| Solas | `composite15` | 星空、高反差边缘和运动模糊 |
| Sundial Lite | `Composite9`，Iris `composite9` | 曝光、后续镜头畸变和外部超分切入／切出 |
