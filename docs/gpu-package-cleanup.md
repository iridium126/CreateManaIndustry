# GPU 包裹旧代码清理

日期：2026-10-02。清理后，生产自由包裹只有轻量记录 + GPU 权威路径；没有原生自由实体交还、CPU 自由运动或原生观察流。暂停、权威撤销/迁移、服务端库存消费和 GPU 退役继续保留。

## 删除与简化

- 删除 `PackageFreeNativeRecovery`、原生 pose/velocity 恢复与 claim 缓存、原生实体观察 Client/Controller/Commands/Patch、成员 Registry 和下行 REBASE 辅助代码。
- 删除自由实体运动/渲染/拾取过滤、原生包及生命周期恢复 Mixin、失效外力 hook 和服务端空 `simulated/release` API。方块实体碰撞失效 hook 独立为 `PackageCollisionPacketMixin`；风机 hook 只保留当前输入捕获。
- 删除自由紧急 checkpoint、转换 helper 与通用 checkpoint 的 FREE 分支；链 checkpoint 合并为独立 `PackageChainCheckpointGpu`，仍支持查询、交接、保存及 CPU 物流迁移。
- 删除点击时临时移动旧实体和只对现有实体起效的 hover 读回。GPU 点击直接发送轻量身份请求，普通/锁链输入仍由各自入口处理。
- 删除三个 `observer_native_*` shader、native 双精度状态缓冲和构造/API 变体，以及服务端从不创建的仅原生成员流/日志。旧订阅动作 2、flag 8 均拒绝；正常完整/紧凑姿态编码不变，协商仍为 `gpu-packages-9` / `gpu-package-observers-4`。
- `HANDBACKABLE` 改为 `ACTIVE_AUTHORITY`，删除 free body 的 -1 交还渲染分支；-4 局部暂停仍可显示。lease `CREATE_OWNED/restored` 改为 `IDLE/finishRelease`，表达权威生命周期，不意味着 CPU 物理恢复。
- 删除预取中的逐包裹 unsafe 位图、CPU 解码/回调及 shader 原子写入，每份快照由 82448 字节降到 66064 字节（少 16 KiB）。section 请求、溢出统计、atlas 使用反馈和 GPU 局部暂停保留。
- CPU 观察参考器移到 test source set。旧原生验证入口和对应过期测试删除；当前机器、库存、GPU、协议、碰撞及锁链契约测试保留，并增加已删除类/shader/Mixin 的构建资源检查及旧观察模式拒绝测试。
- 三模式 benchmark 的复制版全套验证缩为 326 行索引 fixture，删除重复 mixed solver 和 40 个无需独立保留的参考资源（含共享环境程序的重复副本）。编译与资源同步目录分离，避免旧 Copy 输出遮蔽当前 shader。落选索引仍只在独立比较目录，生产只有 linked。

历史索引 CSV 未改动，检查结果包含工作副本/原 Git blob 哈希与 Git checkout 换行差异说明。评分脚本接受实验目录；新采样默认写入 build/package-index-comparison，不覆盖历史原始数据。

## 本轮验证

设备：NVIDIA GeForce RTX 4070 Laptop GPU，OpenGL 4.5，NVIDIA 581.15。

| 验证 | 结果 |
|---|---|
| 正式 JUnit | 417 项，包裹相关 312 项，0 失败/错误/跳过 |
| 完整 GPU 物理/环境/增量/锁链/查询/绘制回归 | 11,478,364 断言通过 |
| GPU 观察者完整/紧凑/生命周期回归 | 3,548,729 断言通过 |
| 碰撞预取专项 | 117 断言通过 |
| GPU 光照 | 886,165 断言通过 |
| 三模式索引 smoke | 全部 15 组质量通过，2,276 断言；65 包裹、50 步预热、3 步采样 |
| 保留的观察 CPU / 网络编码 benchmark | 独立编译通过 |
| 正式 build | 成功生成 createmanaindustry-0.2.6.jar |
| JAR 内容检查 | 无旧交还/原生观察类及 shader、CPU 参考器或落选索引 |

smoke 只验证裁剪后的复现入口，不重新评选性能。历史五轮完整数据重新评分仍选择 linked，数据及选型结论未改变。预取专项曾保留旧“invalidate 立即使 view 不可用”的断言，已改为明确 clear 撤销的 fixture；等待替换期间保留已确认几何的行为保持不变。

原始成功日志、测试统计、smoke CSV、JAR SHA256/包裹条目清单与历史 CSV 哈希见 [本轮验证目录](benchmarks/package-cleanup-2026-10-02)。复现命令：

```powershell
.\gradlew.bat -I scripts/particles/validation.init.gradle build validatePackageGpu validatePackageLightGpu
.\gradlew.bat -I scripts/particles/validation.init.gradle test validatePackageObserverGpu validatePackageGpu -PpackageWorldPrefetch
.\gradlew.bat -I scripts/particles/validation.init.gradle compilePackageBenchmarkTools benchmarkPackageIndexes -PpackageIndexSmoke -PpackageIndexOutput=build/package-index-smoke-final
python scripts/particles/compare_package_indexes.py
```

历史文件继续保存为测量/审计快照。当前契约见 [实现说明](gpu-packages.md)、[轻量记录](gpu-light-package-records.md)、[观察者](gpu-package-observers.md)。真实游戏的 Mixin 应用、机器/多人/跨维度库存守恒、视觉、131072 包裹整帧和服务器 tick 性能仍需游戏验证；本轮未声称这些项目已完成。
