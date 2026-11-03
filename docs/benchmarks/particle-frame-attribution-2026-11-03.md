# GPU 粒子整帧归因与用户采集（2026-11-03）

状态：第一阶段诊断工具已实现。尚未实施新的生产性能优化，20% FPS 目标待用户实测。客户端启动、操作和画面验证均由用户执行。

## 最近三次提交为何未形成 FPS 收益

| 提交 | 实际作用 | 失败原因与证据边界 |
|---|---|---|
| `d7db888c` | 加入显式准入的 CUDA／RT／COPY 等实验后端和捕获工具 | 默认仍为 production OpenGL。实验路径增加跨 API 同步、结果消费和 continuation；不能用专用硬件局部速度代替完整生产链速度。 |
| `dca558cf` | 优化实验路径的通知、分页与排序 | R8 报告记录完整合成物理链均未超过生产门槛；局部比较的基线经常是旧实验实现。默认生产未替换，实机没有收益与现有证据一致。 |
| `09b6fce0` | 七档阴影、几何剔除、深度内核和接收适配框架 | 生产深度档仍几何回退。Complementary 未完成免接收适配，OFF 不移除其原生接收着色或全局光影阶段。模型投影阴影本身也可能很便宜。 |

用户 FULL/OFF 无 FPS 差异作为事实保留；尚无匹配窗口证明具体原因。需要同时检查实际投影提交数、有效接收路径及完整 GPU 帧时间，不能推断为开关失效，也不能推断整个光影包无成本。

第一轮修改前完成 `09b6fce0` 构建，并保存原始可安装包：

`build/performance-attribution-20261103/baseline/createmanaindustry-09b6fce0.jar`

SHA-256：`52f1bf25215040fa464a13579a052840e23f3b328516378b17ed7a53e940511d`。
该原始包没有本轮新增元数据，保留作回退；正式候选比较以新增诊断的 baseline 构建为起点，双方采用相同采集方法。

## 现有记录重新分析

对历史生产窗口 `frames-348ffc22-57ac-492d-a79f-0eff947b03ed` 执行新分析器，输出保存在 `build/performance-attribution-20261103/historical-production.json`。输入位于 `run/logs/cmi-particle-frames`，日期为 2026-11-01，Complementary Unbound 5.9.3，1,395 帧。

| 指标 | 数值 |
|---|---:|
| 平均 FPS | 46.4675 |
| 达到 +20% 所需平均帧时间节省 | 3.5867 ms |
| scene.compute p50 | 3.6946 ms |
| MODEL 更新 p50（compute 子范围） | 2.1524 ms |
| 合并主绘制 p50 | 0.2908 ms |
| 模型阴影 p50 | 0.0952 ms |
| 同帧扣除 compute、合并绘制、模型阴影后的余量 p50 | 17.3507 ms |

余量不是“光影包独占时间”，还包含其余世界渲染、未插桩 CMI 工作及等待。该窗口没有新阴影档身份，不能用它证明 FULL/OFF 因果，也不能作为新优化成绩。它支持优先排查 compute 外的成本，而非继续只优化约 0.1 ms 的模型阴影。

## 用户现在执行的诊断

本轮可安装诊断包：`build/performance-attribution-20261103/createmanaindustry-0.2.7-frame-diagnostics-20261103.jar`。
JAR SHA-256：`4c044e98a3753f17f4b72b90ab639aab086b2a904f185e297a6d3f8e8027c4bf`。
包内构建输入身份：`3e2fa5e6367f007f85c0bb4b479f102c96f339feb7c7bf81918997d40f295725`。
清单在同目录 `delivery.json`；该包是后续候选的诊断基线，不是已经达到 FPS 目标的优化包。

1. 使用本轮诊断包；独立实例替换原 CMI 包，保留备份。开发客户端使用当前源码的正常 `runClient` 入口，不向 `run/mods` 放整包 JAR，不使用旧 R8 诊断覆盖入口。
2. 固定 Complementary Unbound 5.9.3 水平重负载，相机、FOV、分辨率、光影选项、风暴数量、攻击状态、其他模组和电源模式一致。将 `particles.autoThrottle=false`，VSync 关闭，原版帧率滑块拉至“不限制”（导出值 260）；检查外部限帧也关闭。
3. 阴影设为 FULL；运行 `/cmi particle profile backend production` 和 `/cmi particle profile off`。预热至编译、加载与风暴状态稳定。每条采集命令后立即关闭聊天，不打开菜单、不移动相机，等待日志显示 saved 后再发下一条。
4. 连续执行两次 `/cmi particle profile frames 60`，分别等待完成。若不足 1,000 帧，改用 120 或 300 秒重新采集，不拼接短窗口。
5. 仅把阴影切为 OFF，确认生效并等待稳定，再采两次相同长度窗口。之后恢复 FULL。OFF 是单独归因，不计作性能优化收益；重置风暴至相同状态，避免时间推进混淆攻击阶段。
6. 将本轮四个 `frames-*.json` 和同名前缀的所有 CSV、JSON 引用的 `profile_context_file`、`debug.log` 一起交回，并标注 FULL／OFF 对应文件。不要只提供 FPS 截图。

采集无须同时开启 `profile on`，不要与 `capture`、`querysequence` 重叠。窗口本身包含诊断插桩，其开销必须在基线与候选间保持一致；它不是无插桩运行的 FPS 保证。

## 工具和接口

`frames` 命令不变。窗口新增 `build_sha256`（运行时源码、资源和构建配置的稳定 SHA-256 输入身份，不是 JAR 文件哈希）、`measurement_mode=instrumented-frame-window`、`shadow_quality`、`shadow_path_at_start/end`。原有 `engine_source_sha256`、实际选项文件、模拟／可见／阴影提交数保留。阴影路径是端点状态，不能替代逐帧提交证据；未知数量仍为 -1，缺失阶段为未知。

```powershell
python scripts/particles/analyze_client_frame_budget.py '某窗口.json' '新的归因结果.json'
```

分析器检查同帧匹配、绘制代次和重复阶段，分别输出全部阶段分位数。只用三个已核对互斥范围 `scene.compute`、`draw.pack.merge`、`draw.pack.model-shadow` 计算逐帧余量，缺任一范围不推算总余量；单独保留 compute 外时间。不会将 update 子范围或 body／ghost 与其父范围相加。

收到本轮数据后，按关键路径成本选定等价优化：计算热点优先试只读上下文复用及重复准备削减；绘制热点使用实际选项、附件和输入回放减少冗余计算；余量主导时继续细分对应世界／Iris 阶段。每个候选独立测完整链并通过正确性比较后才保留。保持粒子数量、频率、画质、邻居预算和玩法语义。

## 后续 +20% 验收

基线和候选各自记录元数据中的完整构建／shader 摘要。使用 `compare_client_frames.py`，manifest 新增：

```json
{
  "comparison": "production-builds",
  "builds": {
    "baseline": {"build_sha256": "基线64位摘要", "engine_source_sha256": "基线shader64位摘要"},
    "candidate": {"build_sha256": "候选64位摘要", "engine_source_sha256": "候选shader64位摘要"}
  },
  "rounds": [
    {"aa": ["基线1.json", "基线2.json"], "candidate_aa": ["候选1.json", "候选2.json"], "ab": ["基线1.json", "候选1.json"]}
  ]
}
```

示例一轮不足以通过，需三轮，每轮使用新客户端进程，基线和候选必须分别启动，按轮次交替先后顺序。所有窗口 FULL、固定场景、每窗至少 30 秒／1,000 帧；A/A 窗口可同时作为该轮 A/B 输入。两侧源码允许不同但必须与显式身份完全匹配；原硬件比较模式保留其准入要求。

```powershell
python scripts/particles/compare_client_frames.py 'manifest.json' '新的验收结果.json'
```

三轮平均 FPS 均至少 +20%、超过双方 A/A 波动且 p95／p99 无超出 A/A 波动的稳定退化才通过数值门槛。正式验收使用优化完成后单独采集的窗口，不复用诊断选型数据。用户还需验证画面、碰撞、伤害、事件、切包、换维度和资源重载。通过数值门槛不会自动授权替换生产路径。

当前结果：尚无法判断 +20% 是否可达。下一阶段依赖用户新采集数据；没有用历史局部加速或离屏回归冒充 FPS 收益。

## 已执行验证

- 原始基线构建通过；修改后完整构建及 1,027 项 Java 测试通过，零失败／跳过。
- Python 帧工具测试 7 项通过，覆盖不同生产构建、20% 门槛、质量／设置／身份不匹配、重复 A/A 文件、尾帧退化和缺失／嵌套阶段。
- 隐藏 OpenGL 场景帧回归 `validateSceneFrameGpu` 通过 72,764 项断言；未启动 Minecraft。
- 检查分发包嵌套主 JAR 包含构建输入身份，复制原始包和诊断包分别保留；`git diff --check` 通过。
- 一次并行运行完整构建和 GPU 验证时出现 36 个类缺失测试失败；停止并行后串行完整构建全部通过，记录此执行问题，不把第一次运行记作通过。后续 Gradle 任务按顺序执行。
