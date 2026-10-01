# 锁链包裹客户端紧急检查点

## 本轮结果

新增成功帧之后的 GPU 全量姿态整理及独立四槽 persistent READ/coherent staging。客户端保存 materialize、断线、关闭及资源重载优先恢复最近已完成检查点，普通退休交还仍使用精确退休查询。这是客户端姿态恢复组件；服务端 pendulum 恢复、观察客户端校正及实际游戏生命周期尚未验收，CHAIN_READY 仍关闭。

实际依赖继续使用 `compileOnly "maven.modrinth:T9PomCSv:U678xqle"`。Sable adapter 使用直接 API；同分发包 companion 只作为 compile/test classpath，不打入交付 jar。无 Sable/companion 的进程通过 presence gate 验证。当前 jar 包含检查点 shader，0 个 Sable/companion 条目。

## 实现与同步

- 每个候选的检查点为 128B，复用 query_state 的前后物理状态语义，不改动通用粒子/header/shaderpack ABI。
- GPU 检查 metadata/admission 的完整 64bit ID 和 generation，活跃状态采用已提交 admission 标志。隐藏、prepared、free、身份失配均不成为链恢复记录；退休只能读取已清除槽位且身份仍匹配的状态。
- 仅成功提交、物理版本或候选数变化时捕获。Java 不按帧遍历包裹、复制全池或分配每包裹结果；按需 native materialize 才解码一个候选。
- 四个独立 staging bank，最新完成 bank 保留；通常另有三个可在途，首次无完成结果时四槽均可用。满槽跳过，version token 保留到下次成功提交。零 timeout fence 轮询，没有 glFinish、阻塞 wait、glGetBufferSubData 或新的映射操作。
- SSBO 写后用 buffer-update barrier 进入复制；coherent mapping 的 CPU 读取仍须完成 fence。客户端映射屏障后放 fence，仅已完成 bank 可读取。依据 [Khronos ARB_buffer_storage 规范](https://github.com/KhronosGroup/OpenGL-Registry/blob/main/extensions/ARB/ARB_buffer_storage.txt)。
- GPU fence 超过 100ms 撤销运行时；关闭只再尝试一次零 timeout 轮询，随即恢复并释放。epoch 不能复用，映射 view 不向外借出。
- 当前结果是最近已完成快照，不能保证失败瞬间的最新 GPU 数据。没有结果或校验失败仍用 ACTIVE 基线，不等待设备。服务端库存/路由事务没有因本地检查点而被 ACK。

## 同机传输比较

机器：RTX 4070 Laptop GPU，NVIDIA 581.15，OpenGL 4.5。使用真实驱动隐藏 GL context；固定 canonical body/chain/history 和完整身份，不渲染 Minecraft 世界。负载为 10000、65536、131072 候选。每模式至少 30 次且 1 秒预热，三轮各 120 次。

两种模式均执行相同 GPU 整理：DEVICE_COPY 先写设备 scratch，再 GPU copy 到 mapped staging；DIRECT 直接由 compute 写 staging。计时包含整理、复制（若有）与 fence 提交；显式等待仅在基准外壳读取 query，用于测量。CPU poll 单列；CPU emergency decode 遍历全部候选并读取当前位置、前一位置及速度校验 checksum。它不包含 native physicsDataCache、Java 对象重绑、成员恢复或实际 renderer/Flywheel 发布。

最终着色器的三轮 GPU p95（ms）：

| 候选数 | DEVICE_COPY | DIRECT |
| ---: | --- | --- |
| 10000 | 0.212992 / 0.203776 / 0.216064 | 0.229376 / 0.225280 / 0.228352 |
| 65536 | 0.804864 / 0.796672 / 0.799744 | 1.417216 / 1.426432 / 1.412096 |
| 131072 | 1.605632 / 1.612800 / 1.610752 | 2.781184 / 5.392384 / 5.391360 |

131072 默认 DEVICE_COPY：GPU p50 为 1.572864–1.579008ms，CPU submit p95 为 0.0057–0.0059ms，CPU poll p95 为 0.0024–0.0027ms，紧急全池 decode p95 为 4.0233–4.1583ms。解码发生在交还/保存，不加入正常逐帧 CPU 工作。

初次试测的 DEVICE_COPY p95 为 2.058240–2.467840ms，DIRECT 为 4.422656–4.533248ms。两次试测均支持 DEVICE_COPY 在大负载下更快；绝对耗时受运行环境影响，不能只取较好的第二次结果作为性能上限。默认选择 DEVICE_COPY，DIRECT 仅作为内部 benchmark 对照。

131072 时每次捕获 16MiB；按 20Hz 连续物理变化相当于 320MiB/s 的本地传输。默认明确申请 80MiB GL storage（四个 16MiB staging 加一个 scratch），DIRECT 64MiB。实际显存/驱动映射 backing 未独立测量。此成本属于新增恢复保证，不能宣称相比原来无检查点的路径降低 GPU 总时间；后续仍有紧凑格式与脏片段传输的优化空间。

原始数据：[最终汇总](package-chain-checkpoint-2026-09-30.csv)、[2160 次最终采样](package-chain-checkpoint-samples-2026-09-30.csv)、[初次试测](package-chain-checkpoint-pilot-2026-09-30.csv)、[初次采样](package-chain-checkpoint-pilot-samples-2026-09-30.csv)。未验证 131072 活动包裹整帧 p95、服务器 tick、网络带宽或全量 native handback 时间。

## 验证与重现

Java 255 项测试、0 失败；无 Sable 验证和 build 通过。真实检查点 GPU 正确性加基准共 269277 项断言，完整包裹 GPU 回归 13603927 项断言通过。覆盖 0/1/63/64/65/131072、尾线程、反向 body 映射、完整 long 身份及历史、未完成结果拒绝、四槽耗尽、保留 bank、source 改写、旧 generation、混合 free/chain、隐藏/未提交标志、prepared/retired、失败 shader rebuild 保留原程序、超时及新 epoch 空快照。

```powershell
.\gradlew.bat test validatePackageGpu validatePackageSableAbsent build -PpackageCheckpointBenchmark --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
.\gradlew.bat validatePackageGpu --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```

仅检查点正确性使用 `-PpackageCheckpointOnly`。游戏客户端视觉测试由用户执行；首次 snapshot 缺失、实际断线、重载、保存及失败帧交接仍需录像观察。
