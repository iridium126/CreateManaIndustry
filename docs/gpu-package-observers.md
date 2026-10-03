# GPU 包裹观察者内部契约

更新：2026-10-03。生产只使用轻量记录姿态流。原生实体观察 adapter、命令/patch 队列、双精度侧缓冲、shader 变体和仅成员订阅日志已删除。旧 `SUBSCRIBE_NATIVE=2` 与 `MEMBERSHIP_ONLY=8` 输入拒绝；普通订阅和完整姿态编码保持现有 wire 格式。协商版本为 `gpu-package-observers-5`，权威为 `gpu-packages-12`。

## 服务端记录与调度

`PackageAuthorityRegion` 只在最终基线及 GPU 权威确认后向 `PackageObserverFeed` 登记成员。整批增量成功提交后才发布确认字段；OFFER、未确认位置及回滚前缀不发布。暂停或迁移使旧成员精确退役。库存、地址及物品 NBT 不进入观察流。

每个 envelope 携带维度、区域、authority epoch/revision、observer stream epoch 和序号。新成员包括服务器局部索引、完整 long 身份/生命周期、lease epoch/revision、量化姿态、UUID、模型和尺寸。自由记录的实体 ID 为 -1；wire 字段保留用于稳定格式，并不恢复实体。服务器索引不是通用粒子槽位，同一 authority epoch 内不复用。

位置、速度、偏航及 ground/sleep 标志独立合并；位置保持 1/4096、速度保持 1/256 的量化精度。未变字段不产生重复 pose。基线/变化携带服务端确认状态的 tick，共同时刻用共享 age 编码。此时间是确认时刻，不能视为消除了全部网络延迟。

初始兴趣为附近八个区域，订阅及 journal 遍历有界。慢观察者超过有限日志窗口时取得新 stream 和基线；不阻塞权威模拟。离开最后一个观察者后释放日志存储。服务端仅维护一份姿态/生命周期日志，没有旧原生成员专用日志。

## GPU 呈现与安全复用

`PackageLightObserverClient` 用 `PackageObserverGpuController` 处理完整身份、区域、时钟、序号和资源状态。`PackageObserverGpu` 整批验证后合并完整或 64 字节紧凑变化，在 GPU 上预测/校正；观察域不参与物理 contact grid。当前平滑窗口 0.05s、预测上限 0.1s，实际网络与游戏视觉仍待验。

四个上传缓冲以真实 fence 保护；忙时保留原 batch 和精确退役重试。16 字节有界反馈只在成功引擎提交后捕获，CPU 不扫描群体姿态。namespace 关闭通过一个 GPU pass 退役成员。非法批次、反馈错误或不可恢复的积压使流关闭并重新获取确认基线，不创建自由包裹实体。

观察 body/history 与权威域一起发布到 `PackagePoolGpu`，可见记录占一个通用槽位。精确 retired admission、旧上传/反馈/读回引用和 GPU fence 完成后才回收局部槽位；复用重置姿态、光照、状态和资格，迟到旧 stream 或 generation 不影响新成员。权威持有者按 `PackageRenderOwnership` 的记录身份排除重复显示。

`PackageObserverReplica` 是 CPU 测试参考器，已移到 test source set，不进入 mod JAR；独立 CPU/编码 benchmark 显式依赖 testClasses。

## 验证入口与边界

```powershell
.\gradlew.bat -I scripts/particles/validation.init.gradle test validatePackageObserverGpu
.\gradlew.bat -I scripts/particles/validation.init.gradle benchmarkPackageObservers
```

GPU 回归包含工作组边界、131072 成员、完整/紧凑路径对照、乱序/旧身份/时钟、原子失败、上传及反馈积压、退休和安全复用。CPU benchmark 测量 journal、实际编码及参考器，GPU benchmark 测量上传/合并/呈现；两者不代表完整网络或游戏帧成本。

[当前实现总览](gpu-packages.md)、[测试与原始证据](gpu-package-repair-validation.md)、[轻量记录契约](gpu-light-package-records.md)。旧[原生消费报告](benchmarks/package-native-observer-gpu-2026-10-01.md)与[权威下行报告](benchmarks/package-native-downlink-2026-10-01.md)只作为历史测量保留，对应运行代码已删除。多人订阅迁移、实际延迟、视觉和完整流量仍需游戏验证。
