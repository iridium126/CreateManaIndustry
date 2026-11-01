# R7 客户端验证操作

本包提供显式实验后端，默认仍为 `production`。离屏测量没有证明完整链加速；本次需要验证的是地形竞争下的完整客户端帧时。不要把查询时间、CUDA 控制路径的收益或不同相机朝向之间的差值当成 FPS 收益。

## 安装与启动

关闭客户端，解压 `cmi-r7-client-20261101.zip`。从解压目录运行：

```powershell
.\Install-R7.ps1 -GameDirectory 'D:\MAINFILE\MCMOD\CreateManaIndustry-1.21.1-Neoforge-branch\run'
```

这只安装可选原生运行时、固定版本头文件和路径配置，不启动游戏、不更新驱动。已有配置会备份，历史 R7 文件保留。

普通启动器用户先将旧 CMI JAR 移出 `mods`，再给上述命令增加 `-InstallMod`。开发环境通过 Gradle 加载 main 时不要再把分发 JAR 放进 `run/mods`，改用显式资源覆盖：

```powershell
.\gradlew.bat -I scripts/particles/r7-client.init.gradle '-Pr7DiagnosticJar=build/distributions/createmanaindustry-0.2.7-hardware-diagnostics-r7-20261101.jar' runClient
```

普通构建不包含准入回执、不加载原生模块、不编译实验程序。诊断回执绑定已验证的 GPU／驱动、编译类、展开源码、DLL 和 SDK 文件；不匹配会保留生产路径并报告原因。

实验前在游戏目录的 `config/createmanaindustry-client.toml` 中设置并重新启动：

```toml
[particles]
autoThrottle = false
```

这是为了维持数量和装饰效果，并开放前阴影提交窗口。实验后端会拒绝自动节流模式；恢复原设置前切回生产路径。

## 后端命令

```text
/cmi particle profile hardware status
/cmi particle profile backend production
/cmi particle profile backend rt-aabb
/cmi particle profile backend rt-triangle
/cmi particle profile backend tensor
/cmi particle profile backend tensor-control
/cmi particle profile backend cuda-control
/cmi particle profile backend copy
```

切换在完整帧边界生效，并使正在采集的 FPS 窗口失效。切换后等待初始化及预热完成，再执行一次 `hardware status`，确认 `actual`、`failure`、`result`、源码摘要和退休资源数量。不要在 FPS 窗口中执行能力探针；该操作会使窗口失效。

`cuda-control`、`tensor-control` 是常规 CUDA 对照，不能记作专用单元收益。Tensor 和 COPY 在离屏完整链中有明显额外成本，保留开关用于实机裁决，不推荐默认启用。迟到、超容或无效结果使用完整参考查询，可能很慢；不会截断候选或使用上一帧结果。

## 第一优先级：Complementary 水平场景

使用此前的固定存档、初态、相机位置、FOV、粒子数量、攻击、选项和附件格式。水平、竖直向上、竖直向下各自单独比较；水平场景地形重，向上和向下场景地形轻。不要在朝向之间相减估算粒子成本。

先测 `rt-aabb`，再测 `rt-triangle`；之后分别测控制、Tensor 和 COPY。每个候选、每个固定场景需要三轮独立重启客户端。每轮分别获得生产 A1／A2、候选 B1／B2；第一、三轮按 A1→B1→A2→B2，第二轮按 B1→A1→B2→A2，以降低顺序偏差。每个窗口前恢复同一场景初态和攻击安排、预热，保持功耗模式一致。

```text
/cmi particle profile off
/cmi particle profile backend production
```

等待预热，检查状态，关闭聊天窗口后采集：

```text
/cmi particle profile frames 60
```

等待日志出现 `[CMI frames] saved`，记录输出 UUID。再切到候选、预热、检查状态，重复 `profile frames 60`。同样取得 A2、B2。采集期间不打开菜单、不移动相机、不切包、不 F3+T、不更改限帧或垂直同步。

每窗必须至少 30 秒、1,000 帧，且 `invalid_reason` 为空。低 FPS 可延长到 120／180／300 秒；缺帧、队列容量耗尽、场景不匹配或样本不足的窗口不得进入成绩。保持两侧限帧与垂直同步一致；若限帧已经掩盖帧时差，不宣称 FPS 提升。

输出在 `logs/cmi-particle-frames`：

- `frames-UUID.json`／`.csv`：完整主帧间隔、平均 FPS、主帧 GPU 区间、CPU 提交墙钟及数量。
- `-stages.csv`：同帧阶段范围。父子范围可重叠，不能相加中位数。
- `-hardware.csv`：实际查询任务的 N／Q、容量、位置阶段、资源代次、消费与回退。
- `-camera.csv`／`-submissions.csv`：实际相机与同代绘制／阴影提交量。未知值保持未知。

为一个候选、一个朝向创建比较清单，填入实际文件路径：

```json
{
  "rounds": [
    {"aa":["r1-A1.json","r1-A2.json"],"candidate_aa":["r1-B1.json","r1-B2.json"],"ab":["r1-A1.json","r1-B1.json"]},
    {"aa":["r2-A1.json","r2-A2.json"],"candidate_aa":["r2-B1.json","r2-B2.json"],"ab":["r2-A1.json","r2-B1.json"]},
    {"aa":["r3-A1.json","r3-A2.json"],"candidate_aa":["r3-B1.json","r3-B2.json"],"ab":["r3-A1.json","r3-B1.json"]}
  ]
}
```

```powershell
python scripts/particles/compare_client_frames.py comp-horizontal-rt-aabb.json comp-horizontal-rt-aabb-result.json
```

脚本拒绝相机、选项、代码回执、数量、攻击阶段或进程不匹配的比较。三轮整帧收益均须超过两侧 A/A 噪声、1% 和 0.1 ms，且 p95／p99 无稳定退化。通过这个脚本仍不代表已通过画面与生命周期验收。

## 补齐真实回放输入

在 FPS 窗口之外、生产后端下采集；等待各次 `saved` 后再进行下一项：

```text
/cmi particle profile backend production
/cmi particle profile capture R7_comp_horizontal_full
/cmi particle profile querysequence R7_comp_motion 64
/cmi particle profile querysequence R7_comp_dome 64
```

`capture` 现在输出 schema 3 完整 GPU 命令记录：初始资源、别名、CPU 上传、uniform、复制、清空、调度、屏障和发布。`querysequence` 输出 schema 3：读取池、实际局部分离查询，以及 post-MODEL Dome 点与 solve／apply 原点、半径和阶段。序列必须完整连续，缺帧或溢出整段无效。旧 schema 1 序列不能还原实际查询；旧更新快照不能冒充完整链。

```powershell
python scripts/particles/validate_chain_capture.py '<完整捕获目录>'
.\gradlew.bat -I scripts/particles/validation.init.gradle replayR7ChainGpu '-Pr7Capture=<完整捕获目录>' '-Pr7ReplayBackend=rt-aabb' '-PbenchmarkOutput=build/benchmarks/my-r7-replay' --no-configuration-cache
python scripts/particles/prepare_r7_actual_sequence.py '<序列目录>' '<新输出目录>' --stage ordinary --queries 128
python scripts/particles/prepare_r7_actual_sequence.py '<Dome序列目录>' '<新Dome输出目录>' --stage dome-solve --queries 128
```

重建／refit 工具使用声明的实际查询半径；不把这个几何成绩当作完整物理或 FPS。只有完整、稳定拓扑的序列可用于每帧、每 4／8 帧重建周期比较。

## 画面、兼容与释放

在单独的非 FPS 窗口检查原生及八包、两种分辨率，重点核对群体形态、平滑度、聚散、接近目标、受伤、尸体、六剑、混合攻击和装饰效果。相同输入的离屏附件／深度回归已执行，新轨迹仍需目视验收。

再覆盖 Photon、iterationT 三种朝向，以及其余六包的正常显示。分别测试切包、F3+T、换维度、退出／重进世界、生产↔实验切换；确认没有旧代粒子／阴影、错伤、穿透、错误完成或动画槽错配。正常切回生产后，待退休环应归零。失败隔离的未退休资源可以保留至进程退出，不能强行释放或复用。

初始化失败检查可在关闭客户端后，暂时把可选 `cmi-hardware/config.json` 的原生路径改为不存在目录，重启并请求实验后端；应报告失败并保持生产可用。恢复配置、重启后再测试。无需主动重置驱动。

请保留完整 `debug.log`、帧时文件、选项上下文、完整捕获、连续序列、比较清单及画面／生命周期检查结论。实际 FPS 与生产替换均仍待这些实机数据裁决。
