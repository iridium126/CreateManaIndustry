# 安装与选择粒子引擎后端

运行 `./gradlew build`，从 `build/libs` 安装本次生成的单个分发 JAR。JAR 内含匹配的原生模块和设备程序。Windows 上的原生 RT 模拟需要 NVIDIA CUDA Driver 和 OptiX。同卡 RT 使用 GL/D3D12 共享，显式分卡使用异步显示快照传输。

在客户端配置中设置：

- `allayStorm.simulationBackend = "AUTO"` 默认优先用 RT。初始化或运行故障会切到 GL。
- `"GL"` 使用 OpenGL 模拟。`"RT"` 强制使用 RT，故障时冻结最后一次有效发布。
- `allayStorm.simulationGpu = "minecraft"` 使用 Minecraft 当前的 GPU。配置页以短型号显示默认选项和可选 CUDA 设备。显式选择存为 `GPU-<UUID>|<型号>`，仅适用于 `AUTO` 和 `RT`。在双卡机器上，可以让 Windows 为游戏 Java 选择 Arc，再在项目配置中选择 RTX。
- `particles.rtCameraOcclusion` 控制主视图 RT 遮挡。
- `allayStorm.shaderPackShadowQuality` 可选 `FULL`、`COARSE`、`RT`、`OFF`。RT 投影需要通过验证的光影包与有效选项。
- `particles.receiveShadows` 独立控制粒子接收阴影。部分光影包继续原生接收，状态会显示原因。

使用 `/cmi particle profile hardware status` 查看渲染设备、请求及实际模拟设备、显示交接方式和故障原因。`/cmi particle profile frames 30` 采集整帧和分卡传输数据。切换模拟配置会在风暴结束后的身份边界应用。

普通构建校验并嵌入预编译模块。原生源码修改后用本机工具链运行 `pwsh -File scripts/particles/build_rt.ps1` 更新预编译件，再运行普通构建。重启客户端以加载新模块。
