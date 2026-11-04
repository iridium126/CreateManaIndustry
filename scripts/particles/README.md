# Particle GPU tools

当前引擎、性能验证和安装回退分别见 [引擎](../../docs/particle-engine-dev.md)、[验证](../../docs/particle-engine-performance.md)、[安装](../../docs/particle-engine-install.md)。历史 R6–R9、羽毛和阴影专用交付命令已进入冻结源码归档，不再作为当前操作入口。

```powershell
python scripts/particles/particle_tool.py verify
python scripts/particles/particle_tool.py verify --gl-only
python scripts/particles/particle_tool.py verify --suite DomeGatherGpuValidation --suite ModelShadowGpuValidation
python scripts/particles/particle_tool.py replay --capture <完整命令捕获目录>
python scripts/particles/particle_tool.py bench --a <基线捕获目录> --b <候选捕获目录>
python scripts/particles/particle_tool.py analyze --a <三份CSV> --aa <三份CSV> --b <三份CSV>
python scripts/particles/particle_tool.py package
python scripts/particles/particle_tool.py topology --seconds 900 --members 65536
python scripts/particles/particle_tool.py lifecycle --capture <spatial.csv> --require-drained
powershell -File scripts/particles/build_rt.ps1
```

GPU 夹具使用隐藏 GLFW 窗口，不启动 Minecraft；顺序运行，避免相互干扰计时。`gpu.init.gradle` 按当前入口编译共享夹具，`validation.init.gradle` 保留物流、Hex、阴影和光影适配等业务的兼容任务。普通构建不需要原生 SDK。

`package` 分开生成候选与回退包、当前源码及冻结证据归档，并提供 SHA-256 清单。实际采集、日志和存档不参与清理。缺少新架构客户端整帧数据时，结果保持待验收；离屏探针与复制计时不能换算 FPS。

本轮仍是[基础修复候选](../../docs/rt-single-step-status.md)，原生运动所有权和在线 RT 剔除尚未接入。`topology` 为固定输入查询控制实验，`--capture <结果目录>` 仅分析已存在结果。候选包携带的 `analysis/` 工具支持 CSV/JSON 分析；构建及 GPU 回放需要源码归档。失败尝试与通过结果都保留在证据目录，不将短时试跑记为 15 分钟完成。
