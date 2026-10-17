# GPU 包裹碰撞、历史与调度契约

状态：已实现并有单元及真实 OpenGL 回归；用户原存档、多人环境及完整帧性能仍需实机验收。当前源码和测试优先于历史修复报告。

## 代码入口

`client/packages/world/` 下的 `PackageCollisionCaptureSchedule`、`PackageCollisionCache`、`PackageMovingCollisionCache`、`PackageWorldRuntime`、`PackageFrameBudget` 和 `PackageFreezeReport` 管理采集、版本、上传、历史与诊断；五域调用归属见[第四批报告](gpu-runtime-stage4.md)。生产着色器位于 `assets/createmanaindustry/shaders/particles/packages/`。真实 GPU 场景集中在 `scripts/particles/PackageGpuValidation.java`。

## 世界采集与局部更新

- 结构发现先于可选光照工作，防止光照耗尽预算后留下不可回放的结构历史空缺。静态、移动和光照共用帧预算，并为后台工作保留进度。
- 零剩余几何预算时仍采样移动结构姿态、存活状态及 revision，并领取已完成的不可变 worker 结果；不读取方块、不打开新捕获、不等待 worker。真实姿态读取失败仍撤销覆盖。
- 已知局部通知先比较碰撞盒、材料及标志。数值不变时保留版本；未知、异常、邻域变化、字典溢出或完整上下文变化继续失效。局部更新复用完整基线，保留小数形状精度。
- 局部快速发布需要完整已知输入：最多 27 单元、64 个变化形状，CPU 总形状不超过 8192，GPU 字典不超过 1024，沿用既有形状边界。每捕获周期共享 1 ms 软额度，超过额度在发布前回退；单次不可抢占操作不构成硬时间保证。
- 首次局部发布失败后，捕获阶段每 section 每轮可重试一次；重新读取基线以来的全部脏单元，完成已有 requiredRevision。零预算不重试。失败的普通捕获可复用本轮已核对输入。
- 旧 worker 不阻塞当前快照的局部恢复。过期结果只供精确历史输入使用，不能替换已发布当前快照；保留全局 worker 数量与后台并发限制。

## 上传、历史和安全复用

- 活跃 section 优先采集和上传，并保留后台进度。移动 BVH 轮流分片，不能让一个大结构耗尽整轮额度而饿死小结构。
- 实时碰撞表与历史表分别维护版本。仅变化历史引用不能使仍有效的实时占用扫描失效；相同表内容不轮换银行，但保留读取引用和 fence 保护。
- 已完整上传的旧 revision 按未消费输入引用保留；进行中的旧上传也进入有界历史队列。旧队列最多 atlas capacity 项，双份 GPU slot 保持原容量。没有所需版本时暂停，不能用其他 revision 或空气替代。
- 连续局部通知最多保留 8 份纯数值上传证明，包含基线 revision、快照身份、字典前缀及变化单元并集，不形成旧快照对象链。
- 原 slot 局部更新需要 GPU 当前 revision 和快照身份同时匹配证明，且旧历史引用、GPU 表 fence、读取 lease 已全部解除。保护未解除、证明不兼容或字典压缩后使用完整 staging 上传。
- 碰撞 atlas 的表头状态跨 GPU 资源重建保留，避免新 cell 节点引用尚未重建的表头。完整发布前不把部分上传视为可用几何。
- `particles.packageCollisionMaxSections` 为各客户端独立容量，默认 256、范围 1–1024；进入世界时分配，并受显卡单个 SSBO 容量限制。光照容量独立，诊断区分配置容量与实际容量。

## 碰撞行为

- 成对 CCD 按相对运动和路径上的有向位移扩展候选，覆盖交叉高速轨迹。密集场景的廉价查询耗尽预算后进入有界分层回退；未知几何或无法确认安全路径时仍暂停。
- 移动结构扫掠依据实际分离轴处理平移、旋转及缩放，不把始终分离的擦边轨迹冻结，也不能仅以无碰撞终点跳过中途碰撞。保留 32 次保守推进预算及原精度规则。
- 已知静态墙面上的携带运动沿未阻塞分量滑动；有效世界接触修正与移动支撑共同生效。几何缺失、覆盖不支持或无法确认时保留冻结保护。
- 支撑列遇到已知天花板安全截停；相反世界约束只尝试经过扫掠且终点无穿透的外部纠正，最多 4 块，无安全出口仍冻结。
- 风场上行预取同时考虑持续风力范围及重力轨迹，不改变风扇强度。每包裹每次最多请求一个最近缺失 section，保留有界扫描及去重。
- 环境 journal 和 ACK 有独立捕获/处理额度，环境回读优先于增量回读。记录不足时保留待确认内容；燃烧、生命值及机器事务不因睡眠或几何等待重复执行。

## 时间与权限边界

客户端采集最多 20 Hz 时，200 tick/s 把真实采集区间拆成十个独立单调游戏步，移动姿态按区间插值，每对姿态只使用一次。首次从第一个可用区间开始，保留一个区间的显示延迟；成功提交 GPU 步后才消费输入。完整输入条件下补算不丢步，缺失或过期历史不能伪造。

自由与锁链各自使用时钟和插值；自由域等待输入不会阻止独立锁链推进。环境确认允许同一步零位移更新，但不允许重复位移、旧步号或重复序号。

同一玩家继续负责目的区域，或目的区域未指定其他玩家且订阅仍覆盖时，已激活自由 lease 跨空间边界保持控制。通道原点、index、lease 和确认基线不变；新包裹的初始申请仍需处于申请区域。目的 owner/订阅变化、原区域关闭和真正退出继续交接。兴趣区域使用 GPU 完成的占用扫描及负坐标 floorDiv，不能通过遍历全量姿态替代。

## 冻结诊断

每条采样包括精确 id/generation/lease、body/index/revision、64 位步号、世界中心与半高、reason/stage，以及所需 section 或 moving revision。`stillFrozen=false` 表示回读前已恢复；采样记录数不是独立包裹数，也不能当作同时冻结数。

`center` 是中心，减去 `halfHeight` 才是脚底。`SECTION_VERSION` 区分 capture、upload、superseded-or-unavailable；metadata-only 行仍表示缺少精确几何。`MOVING_GEOMETRY` 区分 capture、worker、upload、historical-version 和 unsupported。

`captureCause` 保存所需版本的来源；`observed*` 字段表示输出时观察到的状态。局部捕获模式及首次具体回退原因用于区分预算、未知输入、形状或字典限制。它们不能反推旧事件时的完整世界状态。

诊断默认低频有界采样，每次最多 16 条明细，重复签名抑制，持续冻结约每 10 秒汇总。忙时跳过、不阻塞 GPU。退休/复用清空诊断侧表，旧事件不能归属新生命周期。

`ENV_CONFIRMATION` 是等待服务端环境事务的局部同步点；written / acknowledged 持续推进且 pending 保持 1 不能被当作死锁。环境阶段创建等待时就写入原因，相同 pending / 生命值不因正常确认序号变化而重复输出；部分 ACK 仍须保留后续未确认事务的等待，详见[环境批次](gpu-package-environment-14.md)。

权威关闭可能发生在预阴影 compute 的 GL 状态快照内部。资源退役后立即从所有尚未恢复的 compute 快照中移除失效的缓冲区、程序、VAO 和纹理名，然后恢复仍有效的外部绑定及 SSBO 范围；避免重绑已删除缓冲导致 `GL_INVALID_OPERATION`。有效性查询仅在退役路径执行，普通帧不新增逐绑定查询。

## 验证

核心 GPU 回归覆盖历史版本隔离、旧上传保留、局部证明与 worker 竞态、零预算姿态、静态/移动跨界、拥挤回退、天花板、旋转擦边及真实中途碰撞。单元测试验证调度、快照、生命周期和协议边界。

```powershell
python scripts/benchmarks/run.py validatePackageGpu -PpackageWorldHistory --offline
python scripts/benchmarks/run.py validatePackageGpu -PpackageKnownContact --offline
python scripts/benchmarks/run.py validatePackageGpu -PpackageMovingAdvance --offline
python scripts/benchmarks/run.py validatePackageGpu -PpackageZeroBoundary --offline
```

完整测试矩阵见 [验证入口](gpu-package-repair-validation.md)，性能范围见 [性能与诊断](gpu-package-performance.md)。历史逐次修复日志由 Git 历史保留。
