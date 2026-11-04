# 安装、选择后端与回退

状态：[计划尚未完整实现](rt-single-step-status.md)，包仅供工程检查；默认 GL，尚未允许 `auto` 启用 RT。

1. 关闭客户端，备份当前 CMI JAR。候选包的 `mods/` 中仅安装一个 CMI JAR，避免旧诊断包重复加载。不要修改世界存档。
2. GL 不需要安装原生模块，适用于现有支持的显卡。
3. RT 候选：把包内整个 `cmi-particle-rt/` 放在游戏工作目录下，与 `mods/` 并列。三个模块必须来自同一份清单；旧 DLL 不兼容。需要 NVIDIA 设备及可用的 OptiX、CUDA Driver 和外部 GL/D3D12 共享能力；初始化或执行失败停止 RT 新提交并报告状态；需要明确选择 GL 才切换，不自动回退。
4. 进入固定测试世界后执行 `/cmi particle profile backend gl` 或 `/cmi particle profile backend rt`。用 `/cmi particle profile hardware status` 检查实际后端、ABI、退休和失败原因。切换在模拟边界生效，已有性能采集会作废。
5. 回退：关闭客户端，使用独立的 `particle-engine-rollback.zip`，一起恢复其中的 JAR 和匹配的原生模块。候选包 `rollback/` 也保留旧 JAR，单独使用它时须移出当前 ABI 12 原生模块并选择 GL。清单可校验候选和回退文件；无需删除日志、捕获或存档。

可选 JVM 参数：

```text
-Dcmi.particle.backend=gl
-Dcmi.particle.rt.path=<三个模块所在绝对目录>
-Dcmi.particle.noInterpolation=true
-Dcmi.particle.wideBudget=true
```

仅在比较时单独改变一个参数。`noInterpolation` 关闭一 tick 的显示插值延迟；模拟仍为 20 Hz。宽预算须单独测量。`auto` 不等于强制 RT，当前与 GL 相同。

开发构建：

```powershell
.\gradlew.bat build
# 仅构建可选原生模块时需要 SDK / MSVC；不属于普通构建。
powershell -File scripts/particles/build_rt.ps1
python scripts/particles/particle_tool.py verify
python scripts/particles/particle_tool.py package
```

原生依赖由 `prepare_rt.py` 的固定版本清单准备，构建入口为 `build_rt.ps1`。分发包不包含 SDK、NVRTC、客户端源码编译器或驱动安装器。重启客户端后才能替换已加载模块。
