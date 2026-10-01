# 原生包观察者接管与成员同步（2026-10-01）

接入实际原生包 hook、世界资源、GPU 提交确认和只传成员变化的订阅。它复用现有原生姿态流，减少新增协议的重复数据；本报告没有新整帧或连接带宽测量。**带宽优化与比例验收现已延期**，本轮继续沿用此同步方案；实际游戏内接管、整帧和多人验证仍需完成。

## 接管和同步边界

- 六种原生 handler 的 RETURN hook 保留 vanilla 的位置 codec、插值和交互实体。速度使用 typed accessor 直接复制 raw int，不反射、不重新量化；Create spawn/setBox/insertion 生命周期钩子防止错误基线和重复渲染。
- 只有服务端确认 GPU_OWNED 的成员才能进入观察域；溜槽、机器、其他原生场景不因收到位置包而接管。首次引入捕获不可变基线；后续 raw 包复用队列，不在 CPU 合并姿态。既有原生包解析/实体插值的 CPU 成本仍保留。
- baseline 先追加隐藏 metadata；健康反馈与隐藏 admission 都成功后才能提交可见 metadata，下一次有效可见 admission 才取得 Renderer/Flywheel 所有权。每次 admission 对齐同一 mixed source、origin 和 engine generation，失败帧不确认。
- 上传、反馈和 admission 使用独立 fenced ring，只零 timeout 消费。新基线上传银行忙不阻止旧成员采样；未准备成员的命令不阻塞已准备前缀。退休队列有界，未准备的新成员不能挡住活动成员交还。普通粒子发射不能挤掉已确认的活动包裹：池预留先满足 active count。
- 权威排除及退休使用完整区域/epoch/索引/身份/lease epoch；实体 ID 复用也检查 UUID。资源 epoch 的 visual ID 只用于客户端渲染，不作为库存身份。

观察者 registrar 升为 `gpu-package-observers-3`。`SUBSCRIBE_NATIVE` 选择独立成员日志，`MEMBERSHIP_ONLY` 基线不编码 21B pose，变化只能是精确退休。运动不生成成员记录，不覆盖该日志，也不发送重复下行 pose。保持自定义姿态模式作为离线对照；生产不接受其订阅。128 个引入的 codec 验证确认原始正文少 2688B，这是字段省略量，**不是实际压缩连接的总带宽收益**。

客户端每包先完整验证维度、订阅、epoch/revision/stream、连续序号、总容量和三种身份键，成功后才调用接管回调；非法批次不发布前缀。同包已接管又退休的成员不取得渲染所有权。初始固定八区域的请求、首次完整身份基线、退休、重试和关闭都必须纳入后续总带宽统计。

## 验证

真实 NVIDIA 驱动原生观察者验证 **5,251,497 项断言**。新增 controller 验证 0/1/65/131072 成员的隐藏→可见确认、通用槽位唯一性、普通粒子满负载竞争、raw 相对包的 `VecDeltaCodec` 精确结果、UUID 复用、精确旧退休和资源关闭。另强制四个上传银行不消费，验证 busy baseline 下旧成员继续采样、退休不被新成员阻塞、恢复后命令和释放仍生效。

最终回归：JUnit **330 测试、66 suites，0 失败**；自定义观察者 GPU 3,547,962 项、包裹主 GPU 16,667,575 项断言通过。build 与 Sable 缺席门控检查通过，完整命令耗时 71s。原生微基准未重新作为世界性能测量。

131072 引入 fixture 是静止成员，仅少量成员随后运动；它验证身份、容量和接管时序，不能代替 131072 活动包裹的性能或视觉验收。原组件基准的 GPU p95 1.5288–1.8893ms 不包含本次队列、成员处理、vanilla hooks 或 admission，不可作为新世界管线的 p95。

新增 JUnit 覆盖完整 131072 成员分批消费、跨区域实体/UUID/库存身份冲突、缺序和未知退休不发布前缀、精确关闭、引入后立即退休、原生日志在大量运动下仍空闲、真正成员事件溢出，以及 native wire 不携带 pose。实际 resolved class 的 ASM 契约检查验证 handler 描述符、线程检查、motion raw 字段和 Create 生命周期函数；它不证明运行游戏中的 Mixin 应用或视觉行为。

## 尚未完成的验收工作

权威客户端重复原生下行仍保留；安全抑制还需要交互位置查询、恢复检查点与生命周期验证。高水位槽位/metadata 回收、满容量时观察者→权威角色迁移、动态兴趣区域和带存活成员的 stream 替换仍需实现。上述路径当前会保持 Create 或重建资源；不能靠这些回退满足活动包裹验收。

成员采集、队列、admission 的实际 CPU/GPU p50/p95、处理延迟和分配尚需测量。成员包的初次订阅、迁移、控制、所有保留原生流以及双向权威/ACK 必须加入实际连接计量。Iris、多人、换维度、动态结构和视觉检查由用户在游戏内执行。完整带宽门槛通过前，不开启默认接管。

```powershell
.\gradlew.bat test validatePackageObserverGpu validatePackageNativeObserverGpu validatePackageGpu build --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```
