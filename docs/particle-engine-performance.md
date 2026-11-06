# 性能验证与客户端采集

当前默认使用 AUTO 模拟并开启独立的主视图 RT 遮挡。离屏检查范围见 [重构状态](rt-single-step-status.md)。整帧门槛使用受控客户端窗口。

冻结基线位于 `build/particle-engine/baseline/manifest.json`，包括修改前源码、可运行包、实际捕获与历史实机结果，逐文件 SHA-256。清理依赖记录在同级 `cleanup-dependencies.json`。归档不替代当前验证，也不改写旧实机记录。旧五个窗口同进程且攻击阶段不同，只能说明 Dome 卡顿与回退连锁，不能作为三轮对照验收。

## 工具

唯一公开 GPU 工具入口：

```powershell
python scripts/particles/particle_tool.py verify --gl-only
python scripts/particles/particle_tool.py verify --suite DomeGatherGpuValidation --suite StormCollisionGpuValidation --suite AllayAnimationGpuValidation
python scripts/particles/particle_tool.py replay --capture <完整捕获目录>
python scripts/particles/particle_tool.py bench --a <基线捕获> --b <候选捕获>
python scripts/particles/particle_tool.py analyze --a <三份帧CSV> --aa <三份重复基线CSV> --b <三份候选CSV>
python scripts/particles/particle_tool.py topology --seconds 900 --members 65536
python scripts/particles/particle_tool.py lifecycle --capture <spatial.csv> --require-drained
python scripts/particles/particle_tool.py package
```

`verify` 覆盖固定时钟、身份、显示不连续状态、独立 FP64 扫掠接触、GL/RT 最近距离、65536 成员、ABI 错配、NaN、不完整输入及 10/50/500 ms 原生延迟。Dome 生产审核、地形、动画、阴影、物流和 Hex 继续由共享夹具验证；测试通过不代表视觉和所有密集初态已经验收。

`replay` 只接受校验完整的 schema 3 命令日志，保留记录时的真实程序和资源。回放范围为 GL compute/copy/upload/clear/barrier，排除未记录的世界绘制及外部 CUDA 任务；RT 活动时命令捕获明确作废，避免遗漏原生成本。正式 RT 比较使用整帧采集。`bench` 使用三轮独立进程交错 A/A、A/B；它不能仅凭 GL 日志证明 GPU 重叠或 RT 整帧收益。

`frames` 的帧间隔包含交换与限帧；GPU 范围为 runTick HEAD 到 updateDisplay 前。缺失 GPU 样本是未知，不能填零。`-spatial.csv` 将原生 ABI 12 与统计 schema 2 分列；原生完成后才读取事件计时，分别报告外部输入等待、验证及实例整理、IAS、探针和发布。等待不能全部归为互操作成本，也不能把 GL 与 CUDA 区间相加声称整帧时间。

`topology` 仅做串行 AB/BA 固定输入查询对照，refit 是离屏参考入口。它不包含完整 Storm 模拟、姿态、材质和世界绘制，不能代替完整场景长时回放或整帧性能门槛。`lifecycle` 审计提交/消费/退休；窗口停止后仍收齐该窗口提交的退休回执，导出时报告未退休数。

## 固定客户端场景

客户端启动和操作由用户执行，不自动启动游戏。每个进程从同一份世界/初态备份开始，固定相机坐标、朝向、FOV、分辨率、风暴数量、光影和电源模式。正常飞行、Dome 集结、释放与结束后的恢复分别采集；不要把不同阶段混为一组。

测试规模：65536 Storm 成员、约146000 总粒子；Dome 12288 成员。另检查交叉流、轻微重叠、出生死亡、尸体恢复、传送、区块变动和未知地形。攻击次数、人数和伤害配置保留；按新的批次/目标去重及 Dome 计划截止 tick 核对结算。正常可达场景必须完成攻击；病态密集初态报告残留重叠，不无界提高预算。

固定场景加载和数量仍使用现有服务器业务命令与同一世界备份，避免测试工具直接修改 HP、攻击或结算。客户端共同命令：

```text
/cmi particle profile off
/cmi particle profile hardware status
/cmi particle profile frames 60
```

在客户端配置中选择 GL 或 RT 以固定模拟所有者。采集期间关闭聊天并固定相机、光影和设置。关闭 VSync、帧率限制及自动粒子节流，先预热编译和加载。每窗至少 30 秒且 1000 帧。每轮记录基线 A/A、候选 A/A 及 A/B，每个窗口使用独立进程。保留同前缀 JSON、CSV、profile context 和 debug.log，注明场景初态及攻击输入。

使用 `python scripts/particles/compare_client_frames.py manifest.json result.json`。清单设 `"comparison":"particle-engine"`，`"factor"` 选择 `common`、`simulation`、`camera` 或 `light`。每个 `rounds` 项的 `aa`、`candidate_aa`、`ab` 分别保存两个采集 JSON 路径，A/B 顺序为基线和候选。

完整链的 p50 收益须超过匹配 A/A 噪声，同时达到 1% 和 0.1 ms，p95/p99 须保持在各自的噪声范围内。未匹配相机、初态、阶段或配置的窗口只用于诊断。

## 当前离屏结果与限制

`verify-final/gpu.json` 和 `verify-portable/gpu.json` 分别验证原生及无原生路径，具体检查数、设备、驱动和案例记录在交付证据中。500 ms 注入时提交、环满和重载不得等待任务完成；旧资源安全退休后才能重用。模型阴影、Dome 生产集结、地形和动画夹具保留独立结果。

`baseline-aa/bench.json` 为冻结实际捕获的九个独立进程控制实验，三轮 A、AA、B 全部使用相同输入和旧程序。A/AA 均值差约为 0.004、0.074、0.205 ms，整体门槛未通过；其中单轮假性改善不构成采用证据。此结果校准回放噪声，不是新旧引擎性能对比，也不能换算客户端 FPS。

主视图 RT 查询可以在 GL 或 RT 模拟下剔除已证明完全遮挡的实例。RT 投影仍需逐包逐选项的完整阴影差分资格。两个既有窗口的视角和攻击阶段不同，只用于诊断。受控对照和客户端验收由用户执行。
