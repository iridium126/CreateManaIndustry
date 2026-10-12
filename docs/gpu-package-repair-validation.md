# GPU 包裹验证矩阵

状态：具备 JVM、真实 OpenGL、实际 Create/NeoForge GameTest 及可选模组缺席检查。历史运行通过不等于当前修改已验证；本次结果以实际日志及报告为准。

## 回归职责

| 验证层 | 主要行为 | 入口 |
| --- | --- | --- |
| JVM | 生命周期、身份、租约、ACK、编解码、库存事务、时钟、版本与预算 | `gradlew test` |
| 真实 GPU | 碰撞/CCD、支撑、移动姿态、历史版本、局部上传、槽位安全复用 | `validatePackageGpu` |
| GPU 光照/观察者 | 有界反馈、完整/紧凑姿态、乱序、旧身份、银行/fence 和回收 | `validatePackageLightGpu validatePackageObserverGpu` |
| Iris | 实际 shader transformer、主相机/阴影、状态边界和姿态一致性 | `validatePackageIrisGpu` |
| Create GameTest | 溜槽/传送带/漏斗实际输出、物品与生命状态、重复接管和恢复 | `runGameTestServer` 配合 packages 初始化脚本 |
| 可选依赖缺席 | Sable 未安装时桥接不解析相关运行时 | `validatePackageSableAbsent` |
| benchmark | 稀疏/密集/驱动场景、网络与暂停成本、索引选型 | 对应 `benchmark*` 或 benchmark 参数 |

## 按改动选择验证

碰撞、历史与采集改动先运行对应 JVM 测试，再运行 GPU 子集；物理/协议共享路径发生变化时运行完整 GPU 套件。库存和机器交互需要实际 GameTest；Iris 代码需要实际 transformer 及游戏内画面验证。

```powershell
.\gradlew.bat test assemble --offline
python scripts/benchmarks/run.py validatePackageGpu --offline
python scripts/benchmarks/run.py validatePackageLightGpu validatePackageObserverGpu validatePackageIrisGpu --offline
.\gradlew.bat validatePackageSableAbsent --offline
.\gradlew.bat runGameTestServer -I scripts/particles/package-output-gametest.init.gradle --offline --no-configuration-cache
.\gradlew.bat runGameTestServer -I scripts/particles/package-output-gametest.init.gradle -PpackageRecoveryGameTest --offline --no-configuration-cache
```

## 验收边界

独立 GPU 断言不能证明真实多人库存守恒、驱动差异、原存档行为或整帧性能。部分场景有大量逐体断言，断言总数不等于独立场景数量。实际游戏仍需覆盖多人订阅迁移、机器阻塞/部分接收、断线与资源重建、跨维度、配置切换、完整 tick/帧耗时和 shaderpack 画面。

当前语义见 [轻量记录](gpu-light-package-records.md)、[碰撞与历史](gpu-package-collision.md)、[观察者](gpu-package-observers.md) 和 [性能诊断](gpu-package-performance.md)。原始结果不进入 Git，复现及保留规则见 [性能结果规则](benchmarks/README.md)。全部套件和依赖准备见 [测试入口](testing.md)。
