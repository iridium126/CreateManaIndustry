# R7 后端切换粒子消失修复（2026-11-01）

## 原因与日志证据

新日志 11:14:51 请求 rt-aabb，11:15:05 初始化成功，没有原生错误；首个 separation 任务统计为 `N=65536, Q=0, live=108171`。随后 11:16:50 的存活数只有 65，11:17:42 为 49；11:18:09 切回生产后装饰恢复，但 MODEL 已从已发布粒子池中丢失。

根因在客户端接入。`ParticlePrograms.configureStatic()` 只初始化固定生产程序的 `uCapacity`，运行时后编译的统一 continuation 程序没有收到该值。默认 0 令 GENERIC 和 MODEL 都在 `idx >= min(prevCounter.live, uCapacity)` 提前返回，随后空输出仍被正常发布。普通装饰还会继续产生，但其更新也被相同条件丢弃，所以整体不可见。切回生产后装饰可重新产生；已移出池的 MODEL 没有凭空恢复，CPU 风暴生成进度也不会因这个错误自动回退。

此前离屏夹具直接给候选设置了容量，验证了内核却未覆盖客户端后编译程序的初始化，掩盖了接入缺陷。本次增加实际客户端绑定回归，不能把旧离屏通过当成该缺陷不存在的证据。

## 修复

在每次更新选择实际程序后、任何普通/MODEL 调度前，调用 `ParticleBuffers.bindUpdateCapacity(program)`，按实际 GPU 池容量设置目标程序的 `uCapacity`。绑定使用 DSA，不依赖其他程序的 uniform 值，也覆盖恢复和后编译诊断变体。没有分配池时直接失败，不能把 0 当作合法容量继续更新。

生产着色器、原生 DLL、RT/Tensor 算法、数量、物理规则和发布布局均不改动。普通构建仍为生产路径。诊断回执重新绑定当前类，并补入 `ParticleBuffers`，旧实验回执不能用于新构建。

## 验证

`build/benchmarks/r7-backend-disappearance-20261101` 保存本次日志、初始暂存/工作区差异、复现和最终回归。GPU 工具先把真实输入的容量设为 0 验证故障，再通过与客户端相同的实际池绑定恢复容量；不允许直接从捕获 uniform 的正确值绕过这条绑定。

四份历史真实输入分别有 145,561 / 145,916 / 145,362 / 145,498 个粒子，漏绑定均得到输出 live=0。修复后五条原生路线共 20 次回放检查稳定身份、逐位粒子多重集、计数、状态、成员映射、动画分配器及动画槽配对，与完整参考一致。rt-aabb 的输出分类如下，其余路线一致：

| 快照 | MODEL | 羽毛 | 金粒子 | 输出 live |
|---|---:|---:|---:|---:|
| dense | 65,527 | 59,884 | 20,066 | 145,477 |
| high | 65,527 | 60,118 | 20,222 | 145,867 |
| low | 65,527 | 59,722 | 20,055 | 145,304 |
| zero | 65,527 | 59,889 | 20,043 | 145,459 |

每次原生执行后，同一快照重新绑定实际生产着色器，检查其输出等于切换前的生产输出。这个检查覆盖程序/绑定恢复；它不是当前世界多帧客户端验收，也没有声称已恢复旧进程中丢失的状态。另增加字节码调用顺序回归，约束真实客户端必须在选定程序后、更新调度前绑定容量。

最终构建、Java 测试、GPU 回归与包摘要记录由修复包 provenance 保存。本轮不重做性能裁决，也不宣称 FPS 提升。旧 R7 证据和发布文件保留，不覆盖。

## 安装和复测

关闭客户端，使用 `cmi-r7-client-20261101-capacity-fix.zip`。其中原生运行时与上一版相同；已有运行时无需重建或更新驱动。普通启动器必须移出旧 CMI JAR 后再安装，避免重复模组；开发环境不要把分发 JAR 放进 `run/mods`。

开发环境在项目根目录启动：

```powershell
.\gradlew.bat -I scripts/particles/r7-client.init.gradle '-Pr7DiagnosticJar=build/distributions/createmanaindustry-0.2.7-hardware-diagnostics-r7-20261101-capacity-fix.jar' runClient
```

完整安装说明在新 ZIP 的 `R7-client-guide.md`。保持 `[particles] autoThrottle=false`。重启后进入测试世界，确认风暴模型正常，再执行：

```text
/cmi particle profile backend rt-aabb
/cmi particle profile hardware status
/cmi particle profile backend production
/cmi particle profile hardware status
```

在生产→rt-aabb→生产之间分别等待几秒，核对 MODEL、羽毛、金粒子持续存在、数量未被清空。初次 JIT 会有初始化等待，不能计作稳定 FPS。若重新进入世界后没有活动风暴，再按既有固定场景创建风暴；不要指望旧进程的模型凭切换后端找回。

本次错误影响的旧后端窗口不能进入性能成绩。确认切换和画面正常后，再按原三轮 A/A、A/B 指南采集 `profile frames 60`。实机画面与完整玩法仍由用户复测。
