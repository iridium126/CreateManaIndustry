# GPU 粒子原生硬件实验

默认后端为 `production`。当前原生接口为 **ABI 10**，旧 ABI 8／9 DLL 不可混用。Dome 灾难性回退已修复；新的异步避让仍是未准入实验，不能据此宣称客户端 FPS 改善。

## Dome 卡顿与修复

五个客户端窗口、日志、旧源码和 DLL 冻结在 `build/benchmarks/rt-avoidance-20261103/baseline`；清单包含 SHA-256 和原暂存区摘要。`scripts/particles/analyze_rt_dome.py` 按 `scene_frame` 关联 Dome、continuation、首个消费者和超时。所有窗口来自同一进程，攻击阶段不同，不能充当三轮正式验收。

旧 R9 的“GL Dome”错误地运行了全池参考枚举；普通查询超时后又走全池分离参考，造成约 35–45 ms 接触、50 ms 消费等待和约 500 ms continuation 的连锁长帧。修复后，策略位 16 直接使用生产 contact/control/apply/audit 程序；普通查询失败执行生产空间网格分离。完整枚举只通过离屏 `buildReference` 入口运行。回放也分别选择真实生产 Dome 和完整枚举参考，避免两侧使用同一个参考程序。

迟到任务不会解释为无邻居，也不会立即复用缓冲。前一个原生查询仍在运行时，后续帧跳过重复提交并选择生产网格；退出实验后继续退休旧任务。未退休的资源阻止再次创建原生查询环。首次失败保留槽位、任务、阶段、资源代次和退休状态。修复后的 R9 仍有同帧等待预算，不能称为完全异步。

硬件 CSV 的 `native_abi` 现在写 10；旧文件中该字段的 8 实际是统计格式号，不能据此认定旧 DLL 是 ABI 8。trace 格式仍为 8。

## 异步避让实验边界

`AvoidanceStage` 使用独立三槽输入／输出及退休 fence；当前 GL 模拟不等待 RT。已完成历史结果必须匹配目标模拟代次、独立场景身份、资源代次、成员身份、生命周期 token、阶段、攻击及位移包络，GPU 逐成员复核后才提供运动偏好。出生、身份复用、传送或未知覆盖拒绝不兼容历史。没有兼容结果时使用当前 GL 局部避让。原生包头与行内容在 GPU 验证，正常路径不读回封套。

原生部分使用静态闭合三角盒 GAS、逐成员 IAS 变换、opaque 最近命中和背面剔除，每次提交更新 IAS。默认四探针、0.10 秒预测、25° 前向锥；静止成员跳过探针。RT Core 负责三角形遍历／求交，实例整理、IAS 维护和探针着色仍占普通计算资源。独立能力回执和着色器摘要用于阻止旧实现混入。

当前 GL 修正为 0.5 格网格、最多 128 次访问、保留最近 16 项和两次 Jacobi；总修正不超过 0.03 格，并限制在本帧原有运动预算内。地形未知或扫掠不安全时拒绝修正。0.34 格接触代理和 0.40 格软间距分别处理。溢出、未知覆盖及最终残留重叠保留 GPU 状态，Dome 审核拒绝据此新增就绪或缩减集结分母；接触、伤害及发布继续使用当前玩法状态。

**尚未完成、因此未准入的部分：** 独立 `ModelRenderPublication` 及主绘制／阴影同代的一帧显示延迟；相同四探针算法的高效 GL 对照；完整交叉流／多帧拥塞质量与玩法验收；带真实渲染竞争的重叠证据和完整异步更新链三进程比较。`gl-local-avoidance` 目前仅是无历史探针的局部修正实验，不能作为 RT 同算法性能对照。诊断客户端包不开放这两个新名称，实验包交付源码和离屏工具。不得将单独的探针或局部修正时间当作整帧收益。

## NVENC／NVDEC

保留 HEVC 无损离屏往返结果，不接入在线避让。1 MiB 坐标的最佳重复帧往返约 3.69 ms，同字节 GL 复制约 0.095 ms，当前没有需要压缩的跨设备数据流。粒子字节放入 NV12 平面，不经过 RGB/YUV 转换；截断、损坏和错代会话拒绝发布。量化坐标仅用于有界粗筛研究，权威字段保持精确。

原结果在 `build/benchmarks/hardware-r9-20261103/final-components/media.json`。整理、量化等尚未实现在线完整消费者的工作不能计作已获得收益。接口依据 [NVENC](https://docs.nvidia.com/video-technologies/video-codec-sdk/13.0/nvenc-video-encoder-api-prog-guide/index.html) 与 [NVDEC](https://docs.nvidia.com/video-technologies/video-codec-sdk/13.0/nvdec-video-decoder-api-prog-guide/index.html) 文档。

## 离屏复现

普通构建无需 CUDA／视频 SDK。开发用原生依赖来自固定本地缓存，执行以下命令不会启动 Minecraft 客户端。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/particles/build-hardware-offload.ps1
python scripts/particles/run_dome_repair.py --output build/benchmarks/dome-repair-recheck
```

输出目录必须新建，失败证据不覆盖。测试分别保留真实捕获、独立 FP64 力校验、三槽延迟／错代／关闭、大规模生产 Dome 回退及局部约束的范围。旧力的 FP64 一致性只适用于修复的 R9，不作为新近似算法质量验收。

三进程完整捕获测量包含生产更新命令、原生维护、交接和同步，排除 fixture 复位；尚不包含世界／阴影绘制竞争。筛选要求收益超过匹配噪声、1% 和 0.1 ms，且 p95/p99 无稳定退化。未过筛的后端只能作为显式诊断实验。

## 安装和回退

退出客户端，将旧 CMI JAR 移出 `mods`，解压修复诊断包：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\Install-DomeRepair.ps1 -GameDirectory '游戏目录' -InstallMod
```

开发实例安装到本项目 `run` 时不加 `-InstallMod`；不得将整包放入 `mods`。安装器校验完整清单，按 DLL 摘要使用独立目录，备份原硬件配置。诊断回执绑定当前 Java 类、着色器、原生文件和 GPU／驱动，普通 JAR 不含实验准入回执。

```powershell
.\gradlew.bat --no-configuration-cache -I scripts/particles/r8-client.init.gradle runClient '-Pr8DiagnosticJar=build/distributions/DOME_REPAIR_DIAGNOSTIC_JAR'
```

默认仍为 production。恢复时执行 `/cmi particle profile backend production`；完全回退须退出客户端、换回原普通 JAR，再恢复备份的 `cmi-hardware/config.json`。原生目录可保留，普通构建不会自动加载。切换不同 DLL 必须重启进程。

## 客户端固定场景

使用测试存档副本，服务端风暴上限 65,536。恢复同一世界快照、风暴 seed／身份、玩家坐标和朝向；相同相机、FOV、分辨率和光影设置。每轮独立进程，分别测无 Dome、12,288 成员 Dome 集结和集结结束恢复，禁止把不同攻击阶段混成同一个对照。

退出游戏设置 `[particles] autoThrottle=false`，VSync 关闭，帧率“不限制”（导出 260），阴影 FULL；此前窗口使用 2560×1506、ComplementaryUnbound_r5.9.3.zip。预热后逐条执行，每条等待结束：

```text
/cmi particle profile off
/cmi particle profile hardware status
/cmi particle profile backend production
/cmi particle profile frames 60
/cmi particle profile frames 60
/cmi particle profile backend rt-aabb-r9-force
/cmi particle profile frames 60
/cmi particle profile frames 60
/cmi particle profile backend production
```

GPU 输出验证候选使用 `rt-aabb-r9-force-gpu-result`。切换后复位场景和预热；测试出生死亡、Dome、接触伤害、恢复、传送、换维度、重载和生产→实验→生产连续切换。请求后端失败的窗口按实际 production 归类。

每个候选三轮独立进程，每轮生产 A/A 两窗、候选 A/A 两窗、生产／候选 A/B 两窗。每窗至少 30 秒且 1,000 帧，保留相邻 CSV、相机 CSV、JSON 和 debug.log，填入包内清单后执行：

```powershell
python analysis/compare_r9_client.py client-manifest.json client-result.json --backend rt-aabb-r9-force
```

近似候选只有三轮平均 FPS 均提升至少 20%、超过匹配 A/A 波动、p95/p99 无稳定退化，且用户确认画面和玩法后才考虑采用。当前客户端修复结果标为 **待验收**，未授权自动替换 production。
