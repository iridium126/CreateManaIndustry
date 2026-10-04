# 光影可见性与 CMI packages 日志修复

检查对象为用户提供的 `run/logs/debug.log`，日志保持原样。修复不改变 GPU 物理求解器和网络协议。

## 主相机锁链包裹

原主相机掩码遍历 `LevelRenderer.visibleSections/globalBlockEntities`。日志使用 Sodium 0.8.13；Sodium 使用自己的区段、全局方块实体集合，原版集合不能代表实际绘制的传动轮。GPU 包裹已从 Create 的原生渲染列表移除，空掩码会把包裹和悬挂架全部隐藏。

已对照本地 Create 依赖：`ChainConveyorRenderer.getViewDistance()` 为 256，`shouldRenderOffScreen()` 为 true，`renderBox()` 没有逐包裹视锥检查。主相机改为遍历已有接管索引，每个所属传动轮调用其实际渲染器的 `shouldRender`；离屏渲染器直接准许所属候选，其他渲染器再检查原生渲染边界。Sable 距离检查使用现有父结构渲染帧转换后的局部相机，复用帧缓存。阴影继续使用 Iris 自身的可见列表，与主相机掩码独立。

GPU 回归覆盖主相机视锥完全拒绝时锁链包裹和悬挂架仍按父掩码准许、隐藏身份不泄漏、32 位掩码边界以及阴影不覆盖主相机命令。未新增逐包裹 CPU 物理或 GPU 同步读回。

## 日志问题

- 9 条 `input gap` 均在 `available-next=20` 时触发，说明达到历史过期阈值。修复可复现的计时缺陷：客户端输入突发捕获后，仅按墙钟预约会保留永久欠账；现在允许已捕获的较旧输入在既有每帧预算内追赶，保留一个采集区间用于插值。20/200 tick/s 突发捕获回归通过。真实超过保留窗口的历史仍不能补算，继续保留 GPU 状态并重建输入基线。日志补充帧间隔和上一帧等待原因，修复旧日志总显示 `waiting=none` 的诊断盲点。
- 4 条 `rejected environment` 都是机器接触。修复可复现的稀疏姿态缺陷：静止包裹不发送位置变化，旧姿态步数超过历史窗口后，不应拒绝后来同位置的接触。对这种向前的静止区间只给予一步扫掠范围；保留未来步、向后历史、身份、方块和不重叠区间校验。长期静止、远距离伪接触、未来步和释放后旧事件均有回归。
- 旧拒绝日志没有分支原因、样本持续时间、已确认姿态或包裹尺寸，无法逐条证明这 4 次拒绝都由同一缺陷引起。新日志区分持续时间越界、重叠区间、未来区间、姿态不可达、区块未加载、接触边界不相交和方块类型变化，并附带姿态和尺寸。几何校验容差维持 0.0021，没有用扩大容差掩盖问题。
- `Particle identities reset` 与 `Package world unloaded` 仍关闭对应资源命名空间。传动轮移除/变形由服务端逐轨道发出 `RELEASED`；客户端立即撤销该轮的渲染准入并使用服务端确认姿态退役，不再把局部容器变化升级为全局 GPU 故障。其他传动轮和自由包裹继续模拟。

## 验证

- 480 项单元测试，无失败、错误或跳过。
- 真实 NVIDIA 驱动：主相机/阴影绘制 568,781 项断言，环境反馈/光照 886,165 项断言，Iris 着色器集成 26,730 项断言。
- 6 项真实 Create/NeoForge GameTest 通过；正式构建成功，发布 JAR 不含 GameTest 夹具。
- 组件回归没有替代用户原存档与具体光影包下的画面复测；旧日志不足以排除真实长帧、机器方块变化造成的正常拒绝。本批次没有进行性能基准，不能宣称整帧提速。

诊断输出保留在忽略的 `build/package-review/shader-log-fixes-*.log`。GameTest 与正式构建串行运行，避免测试夹具被正常资源任务移除。

```powershell
.\gradlew.bat -I scripts/particles/validation.init.gradle -I scripts/particles/package-output-gametest.init.gradle runGameTestServer --offline --console=plain
.\gradlew.bat -I scripts/particles/validation.init.gradle build validatePackageGpu validatePackageLightGpu validatePackageIrisGpu -PpackageDrawPassOnly --no-parallel --offline --console=plain
```
