# R8 客户端诊断与验收

默认生产 OpenGL 不变，当前没有 R8 客户端 FPS 提升结论。客户端实机测试由用户执行。请关闭并重启客户端后安装，旧 R7 DLL 不能与 ABI 8 混用。

现有真实完整捕获已恢复执行：匹配 GL 4.6 core＋forward-compatible 后，19 个原始程序二进制全部加载；完整无截断参考 A/A、稳定身份下的有效状态、动画槽、反馈和代次验证通过。无需为本轮准入重新采集完整捕获。实际 Complementary 402 项选项快照及八包基础附件/深度回归也已通过。

本包准入以下固定策略，实际可用列表还须以包内摘要绑定的回执为准：

| 后端 | 固定策略 |
|---|---|
| `rt-aabb-r8-merged` | 合并查询/输出完成通知 |
| `rt-aabb-r8-event` | 原生完成事件消费等待 |
| `rt-aabb-r8-paged` | 单遍查询、32 索引页暂存、压平 CSR |
| `rt-aabb-r8-unsorted` | 保留 BVH 遍历顺序，省去行排序 |
| `rt-aabb-r8-gl-dome` | 普通查询原生，Dome 完整 GL 查询 |
| `rt-aabb-r8-combined` | 通知合并＋事件等待＋分页，保留排序 |
| `rt-aabb-r8-paged-unsorted` | 分页＋无排序 |
| `rt-aabb-r8-combined-unsorted` | 通知合并＋事件等待＋分页＋无排序 |
| `rt-triangle-r8-paged` | triangle 分页对照 |
| `cuda-control-r8-paged` | 相同分页与压平的 CUDA 对照 |

普通与 post-MODEL Dome 缓存独立；客户端强制每次重建，不开放未经实机验证的 4/8 帧周期。无排序策略通过全部 65,528 条真实普通查询的独立 FP64 力检查，误差界未放宽。真实完整捕获没有 Dome contact dispatch，Dome 的实际多帧画质/物理仍需客户端验收；离屏已有全量真实候选集合和合成多帧规则检查。

## 安装

独立游戏实例：解压 `cmi-r8-client-20261101.zip`，先将旧 CMI JAR 移出 mods，再执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\Install-R8.ps1 -GameDirectory '你的游戏目录' -InstallMod
```

项目开发客户端不要把整包 JAR 放入 run/mods。安装脚本使用项目 `run` 目录且不带 `-InstallMod`，随后使用显式回执覆盖入口：

```powershell
.\gradlew.bat -I scripts/particles/r8-client.init.gradle runClient '-Pr8DiagnosticJar=build/distributions/createmanaindustry-0.2.7-hardware-diagnostics-r8-20261101.jar'
```

包文件实际带准入摘要后缀，以解压包内指南的完整名称为准。安装器在指定实例下创建独立 R8 运行时目录并备份配置；不升级驱动。普通构建不包含准入回执，也不依赖 CUDA/OptiX。

## 第一轮优先级与命令

先固定 Complementary **水平地形重负载**，保持相机、攻击、初态、粒子数量、分辨率和光影选项一致。先测事件等待，再测通知/分页组合，最后测无排序组合；其余策略用于归因。CUDA 分页和周期 refit 不列为优先长测对象。

```text
/cmi particle profile hardware status
/cmi particle profile backend production
/cmi particle profile frames 30
/cmi particle profile frames 30
/cmi particle profile backend rt-aabb-r8-event
/cmi particle profile frames 30
/cmi particle profile backend production
/cmi particle profile frames 30
/cmi particle profile backend rt-aabb-r8-combined
/cmi particle profile frames 30
/cmi particle profile backend production
/cmi particle profile frames 30
/cmi particle profile backend rt-aabb-r8-combined-unsorted
/cmi particle profile frames 30
/cmi particle profile backend production
```

逐条执行，等前一个窗口完成后再执行下一条。切换在完整帧边界生效，会使正在采集的窗口失效；看到切换生效后再开窗。各候选需补采自身 A/A（同后端重复窗口），并与相同初态的 production 交错 A/B。上面命令是第一轮顺序示例，不代替场景复位。

每个窗口至少 30 秒且 1,000 帧；不足时用 `profile frames 60` 或 `120` 重采，不拼接。完成三轮独立客户端进程，先水平，再分别向上和向下，随后 Photon、iterationT；禁止用不同相机朝向之间的差值估算粒子成本。

保留限帧、VSync、平均 FPS、p50/p95/p99、主帧 GPU 时间、可见/模拟数量、实际 backend、选项/源码摘要及资源代次。正式收益须三轮均超过匹配噪声、1% 和 0.1 ms，p95/p99 无稳定退化。捕获、能力探针、shader clock 与 FPS 样本分开。

## 画面、规则与故障

重点检查生产→实验→生产后 MODEL、羽毛和金粒子均正常，动画槽不串号；再检查 Dome/Column/尸体/恢复、伤害与完成事件、穿透、数量、群体形态和平滑度。验证八包切换、F3+T、换维度、挂起恢复、初始化失败和释放。异常先切回 production，保存 debug.log 和整轮 profile 目录。

同输入绘制的全部附件/深度已离屏验证；新的合法轨迹仍需实机观察。没有合格实机结果就继续使用 production，CPU 等待下降和查询局部加速不能作为 FPS 收益。

## 复现与新增捕获

现有两条 64 帧连续序列已按每帧全部查询回放，无需重复采集；需要定位新场景时：

```text
/cmi particle profile backend production
/cmi particle profile capture R8_comp_horizontal_full
/cmi particle profile querysequence R8_comp_dome 64
```

捕获和序列须分别完成，不与 FPS 窗口重叠。提供完整输出目录。新捕获保存所有 compute 实际编译源码及上下文 flags/profile；二进制不可加载时只允许使用捕获自身经过摘要校验的源码。

```powershell
python scripts/particles/prepare_r7_actual_sequence.py '序列目录' '新的输出目录' --stage ordinary --queries all
python scripts/particles/validate_chain_capture.py '完整捕获目录'
.\gradlew.bat -I scripts/particles/validation.init.gradle validateR8RealChainGpu '-Pr7Capture=完整捕获目录' '-PbenchmarkOutput=新的R8完整链结果目录' --no-configuration-cache
```

原生记录包含实际 NNZ、页池用量、ABI/策略和版本化等待分段。消费时尚未发生的退休时间为 -1；离屏任务记录提供完整退休时间。GPU 外部 Fence/输入回读细分只在详细定位时开启，正式 FPS 不插桩。分页分配融合在查询内，单独扫描、压平、排序和封套另记。
