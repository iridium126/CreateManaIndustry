# GPU 自由包裹单体冻结诊断

在客户端 `run/logs/debug.log` 中搜索：

```text
[CMI packages] free package frozen
[CMI packages] free package freeze summary
```

诊断随包裹世界运行时自动启用，输出 DEBUG 记录，不需要修改存档或网络协议。这里记录单体 `PACKAGE_COLLISION_FROZEN`；整批输入等待仍使用既有 `waitingInput` / `input gap` 诊断。

## 单体记录

每条记录包含 `reason`、`stage`、`stillFrozen`、维度、包裹 id/generation/lease、GPU body、authority index/revision、冻结物理步号、世界中心位置、半高、保留的恢复速度及原因上下文。

示意格式（数值仅作字段示例）：

```text
[CMI packages] free package frozen reason=SECTION_MISSING stage=predict stillFrozen=true dimension=minecraft:overworld id=501 generation=7 lease=11 body=0 index=3 revision=909 step=1 center=8.0,8.0,8.0 halfHeight=0.5 velocity=0.0,0.0,0.0 section=0,0,0
```

`center` 是包裹中心，减去 `halfHeight` 才是脚底高度。`section` / `block` 已转为世界绝对坐标；`movingId` 为本客户端碰撞结构的编号。`stillFrozen=false` 表示回读前已经恢复，但保留了最近一次冻结信息。事件使用 GPU 捕获时的身份，CPU 不用当前 body 槽位反查，避免把旧事件归到复用后的新包裹。

| reason | 含义 / 上下文 |
| --- | --- |
| WORLD_NOT_READY | 静态世界视图不可用；提供视图原点 section |
| SECTION_MISSING | 本次查询视图缺少 section；不等同于当前驻留 atlas 一定没有上传过它 |
| WORLD_UNSUPPORTED | 扫掠区域中遇到不支持的方块；提供绝对 block 与 flags |
| WORLD_METADATA | 形状索引、摩擦或标志异常；提供 block 与 subtype |
| WORLD_BOUNDS | 非有限/超范围坐标，或单段查询超限；提供 querySize 与 subtype |
| WORLD_OPPOSING | 同一轴受到相反的世界约束；提供 opposingAxes |
| MOVING_POSE / MOVING_GEOMETRY | 移动结构姿态或几何不可用；提供 movingId |
| MOVING_BUDGET / MOVING_BVH | BVH 遍历超限或节点关系无效；提供结构、节点与遍历信息 |
| MOVING_SWEEP | 保守碰撞推进未完成；提供节点、迭代上限与推进时间 t |
| MOVING_UNSUPPORTED | 接触不支持的移动形状；提供结构、节点与标志 |
| SUPPORT_ANCESTOR / SUPPORT_HEIGHT | 支撑祖先无效或高度非有限；提供 ancestor、separation、height、supportVy |
| SUPPORT_CEILING | 旧版本的支撑抬升与天花板冲突冻结；已知接触修复后改为安全截停，该编号保留以识别旧日志 |
| ENV_BACKPRESSURE | 环境事件背压；提供 pending/written/acknowledged/health |
| ENV_WATER / ENV_HEALTH | 水触发销毁，或生命值到达终止阈值，等待服务端确认 |
| UNKNOWN | 兼容夹具或未标注的冻结路径；不伪造具体原因 |

`stage` 可为 `predict`、`solve`、`carry`、`moving`、`support`、`environment`、`resume`。恢复检查会更新当前阻塞原因，例如请求的 section 到位后仍遇到不支持的方块。

## 开销与限频

- 每 body 增加独立的 64 字节诊断侧表；131,072 容量约 8 MiB，物理 Body 的 64 字节 ABI 不变。
- 每秒至多一次 GPU 采样和约 2.2 KiB 异步快照，使用现有四槽 `PackageReadbackRing`，零超时检查 fence；队列满时直接跳过。
- 每次至多 16 条单体明细，相同原因/上下文签名不重复输出；未容纳的记录保留待后续采样。所有正在冻结的包裹仍进入原因计数，持续冻结每 10 秒输出汇总，整体恢复后输出一次零冻结汇总。
- 采样间发生多次不同冻结时保留最近一次；诊断不是可靠的全量事件日志。退休/复用清空侧表，避免旧生命周期串号。
- 没有增加服务端包裹消息、主线程世界查询或同步 GPU 等待。诊断初始化/回读失败会禁用诊断并记录一次警告，继续运行物理。
- 硬件没有额外的 binding/compute SSBO 额度时，物理着色器不编译诊断侧表，保留基础物理支持。

## 验证

专项覆盖世界原点换算、精确身份和 64 位步号、冻结前后物理一致性、重复抑制、原因改变、瞬时冻结、槽位复用、溢出重试、满回读环跳过、环境队列与销毁原因、三类支撑失败以及移动几何未上传。单元测试覆盖快照解码、借用缓冲区复用、记录上限与无效计数。

```powershell
.\gradlew.bat test --tests '*PackageFreezeReportTest' validatePackageGpu --offline --no-configuration-cache -PpackageFreezeDiagnostics -I scripts/particles/validation.init.gradle
.\gradlew.bat test validatePackageGpu build --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```

完整回归包括此前密集包裹跨 section、移动碰撞安全处理及环境事件延迟修复。尚未在用户原存档实际触发诊断日志，也未测量整帧开销。

验证结果：505 项单元测试（101 个套件，零失败、错误或跳过）、79 项诊断 GPU 专项断言、14,270,371 项完整 GPU 断言、正式构建和无 Sable 桥接检查通过。完整日志为 `build/package-freeze-diagnostics-validation.log`，发布包为 `build/libs/createmanaindustry-0.2.6.jar`。
