# 自由包裹外部受力（2026-10-01）

新增常规实体推挤及 Create `AirCurrent` 鼓风机受力 GPU pass，接入 `PackageWorldRuntime` 的 20Hz 自由域。锁链和观察者域不执行此 pass，通用粒子 64B ABI 不变。它只修改包裹速度；随后既有 GPU 预测、扫掠及 Jacobi 接触处理运动和碰撞。没有把其他实体的模拟或玩法回调迁到客户端。

`PackageForceClient` 通过加入/离开事件维护非包裹实体集合，避开 131072 个包裹的 CPU 姿态扫描。client owner 线程每 tick 复制实体边界、位置、睡眠/碰撞条件及 Create 已计算的气流边界、方向、距离和速度。后台只读取这些不可变值，构建无栈 BVH；render 线程只消费已完成 future，不等待 worker。初始力源未准备前不得确认接管，快照陈旧超过一 tick、超过 4096 力源或全部上传 bank 忙时明确交还 Create。

BVH 节点 32B，力源 64B；仅临时 pass 使用 binding 4。实体树按空间排序，风机树保留 tick 顺序，一次遍历过滤相关力源。风机更新是顺序相关的，不能用空间排序或不加区分的求和替换。四个独立持久映射写 bank 与 fence 使用零超时观察，禁止覆盖未完成 bank。没有位置/速度读回或浮点原子；空力源与零包裹不提交额外 compute。`/cmi particle stats` 的世界状态附带累计 force upload bytes。

实体受力参考 `Entity.push(Entity)` 的水平 absMax、平方根归一化及 0.05 blocks/tick 系数；考虑普通实体/生物的回调次数，以及 Create `PackageEntity.push` 对另一实体脚高度的限制。服务端原生 pair 回调、另一实体的反向响应和 `tossedBy` 清理仍执行，只替换已活动 GPU 包裹的标准 pair velocity kick。最终基线等待期不跳过原生力。未建模的直接 `push(DDD)`（例如外部模组的特殊冲量）先恢复检查点再交还 Create，而不是静默吞掉；有 scoreboard team 或 crouch 的特殊包裹继续由 Create 管理。

气流按 Create 的脚位置到风机中心距离、`abs(speed)/512`、`maxDistance`、各轴 ±5 blocks/tick clamp 及 1/8 速度插值计算，包括吹风、吸风、沿气流以外方向的速度阻尼。零距离使用有限 epsilon，避免 `0 * infinity` 引入 NaN。普通风避免服务端的逐包裹受力数学和 Vec3 临时对象；必要的处理类型、火焰/伤害等仍走 Create 原方法及服务端所有权回退。普通未接管实体和无 GPU owner 的气流直接执行原方法。

力源复用既有原生实体与风机 BE 同步，未注册新的力源/逐包裹受力网络消息。完整联网验收仍要统计这些既有流以及所有控制、恢复消息；验收上限已更新为 Create 原生 **1.5 倍**。不能以“没有新 packet 类型”推断完整网络达标。

本报告记录常规世界坐标力源的初次接线与测量。后续已加入 Sable plot 中实体和移动风机的 typed 世界姿态投影及旋转气流边界，验证与独立测量见 [Sable 受力报告](package-forces-sable-2026-10-01.md)。特殊实体 override、多人 native 另一实体响应、气流变化/阻挡/处理类型以及视觉对照仍需游戏测试。生产接管门禁继续关闭，未绕过此前的网络/交还/订阅缺口；这里不宣称整个包裹计划完成。

真实 RTX 4070 Laptop GPU、OpenGL 4.5、NVIDIA 581.15 的新增受力验证为 432720 项断言。包括 0/1/63/64/65/1025 边界，准备/静态状态排除，feet 条件、吹/吸风、偏轴阻尼、零距离、重叠相反风顺序，快照超时、四槽积压与恢复，以及 10000/65536/131072 **全部活动受风**、不遗漏也不回退。速度公式容差为 1e-5；全规模位置量化样本为 1e-4；未修改的 pose/extent/lifecycle 使用 float bit 精确比较。BVH 单元测试检查 4096 力源无重复/遗漏、逃逸索引和高坐标局部原点；ASM 检查实际 MC/Create 调用点，不替代游戏启动的 Mixin 检查。

完整包裹 GPU 回归通过 17388633 项，Hex 回归通过 135498 项，保留原 Hex 断言并覆盖此前新增范围。完整构建与不安装 Sable 的直接桥门禁测试通过。

新增 pass 微基准采用同机、固定来源和包裹位置，32 次预热、60 个计时样本、每组重复三次。所有包裹均运行 force kernel，气流覆盖全部包裹；没有 CPU 回退。时间只包括受力，不包括后续碰撞/绘制、实际世界复制、原生实体 broad phase 或网络。查询结果的同步等待仅在 harness 中、在 CPU 提交计时结束之后执行，生产不等待。worker bake 列是不可变输入的构树计时，不是主线程采集计时。

| 131072 包裹场景 | GPU p95，三次范围 | CPU 上传/提交 p95，三次范围 | worker bake p95，三次范围 | 每步上传 |
| --- | --- | --- | --- | --- |
| 单气流 | 0.0225–0.0236ms | 0.0066–0.0078ms | 0.0022–0.0023ms | 96B |
| 4095 实体 + 1 气流 | 0.1198–0.1290ms | 0.0308–0.0398ms | 1.3177–1.5179ms | 524256B |
| 64 道重叠相反气流 | 0.3318–0.3379ms | 0.0080–0.0119ms | 0.0131–0.0159ms | 8160B |

原始[三次测量 CSV](package-forces-2026-10-01.csv)包含 10000、65536、131072 和所有场景。该微基准没有原生 Create 主线程前后对照，不能认定整帧 p95≤16.7ms、服务器 tick≤50ms 或视觉不可分辨已达标。

```powershell
.\gradlew.bat validatePackageGpu -PpackageForcesOnly --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
.\gradlew.bat validatePackageGpu -PpackageForcesBenchmark --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```
