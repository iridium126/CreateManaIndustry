# 包裹观察者 GPU 合并与呈现微基准（2026-10-01）

## 结论及边界

观察者的整数基线合并、运动预测、校正及共享包裹池发布已由真实 GPU 验证。新增紧凑变化记录把每条上传从 128 字节降至 64 字节，不在 Java 中合并或计算姿态，也不增加展开 dispatch 或缓冲。131072 个成员全部持续运动和改变位置时，三次测量的 GPU p95 为 **1.585–1.995ms**，相同最终程序的完整记录路径为 **3.269–3.446ms**；CPU GL 提交 p95 为 **0.986–1.174ms**，完整路径为 **1.519–2.698ms**。目标规模的收益超过本次三次重复之间的差异，紧凑入口可供后续 controller 使用。10000 的 GPU p95 范围重叠，不声称所有负载均有明显 GPU 加速。

这不是整个游戏帧、网络链路或服务端 tick 的测量。世界运行时仍未自动订阅，也未接通观察者可见 admission、Renderer/Flywheel 所有权和原生同步抑制。原协议的 Java packet 解码和服务端日志成本仍存在，不能把此前 CPU 参考链路的 38.9–45.4ms 与这里的 GPU 数值直接相减。131072 活动包裹整帧 p95 ≤16.7ms、服务端 tick p95 ≤50ms、多人玩法和视觉验收仍未完成。

## 方法与数据

RTX 4070 Laptop GPU，NVIDIA 581.15，OpenGL 4.5，JDK 21.0.8；隐藏 GLFW context，真实生产 compute shader 和 `PackageMixedPhysicsGpu`。每组 10000、65536、131072 个成员均有非零速度，所有成员在每个逻辑 20Hz 波次改变位置；不使用静止、CPU 物理或 Create 回退代替活动负载。呈现时刻为提交时间加 0.025s。每组至少 0.5s/30 波预热，120 个样本，重复三次；检查 active=成员数、stale=0、invalid=0。此规则模拟连续状态输入，不运行真实 socket、服务器时钟或世界碰撞。

三个场景分别为：

- `sample_publish_motion`：计时 GPU 采样和 shared body/history 复制。每波完整变化上传及合并在计时区间外，仍实际执行，不能把表中的 0 字节理解为总上传为零。
- `delta_sample_publish_motion`：计时完整 128 字节记录上传、整批验证、合并、采样和共享复制。
- `compact_delta_sample_publish_motion`：同上，使用 64 字节变化记录；完整身份在 GPU 上按已确认且不重用的 namespace 定位。初始化基线仍用 128 字节记录。

CPU prepare 单独测量修改复用命令缓冲的整数位置、序号及 receipt，不含网络 packet 解码、成员索引维护或模型处理。CPU submit 是计时区域的 GL 调用成本；GPU 使用 TIME_ELAPSED query。离线工具在计时区间外同步获取结果和校验缓冲，生产代码不等待 query、worker 或未完成 fence。本报告不含实际栅格化、光照、Iris/阴影、碰撞、玩家交互、库存回调或 GPU 反馈的计时。

- [最终汇总](package-observer-gpu-2026-10-01.csv)、[最终逐样本](package-observer-gpu-samples-2026-10-01.csv)。
- [紧凑路径前的四槽完整记录汇总](package-observer-gpu-expanded-ring-before-2026-10-01.csv)、[逐样本](package-observer-gpu-expanded-ring-before-samples-2026-10-01.csv)。
- [早期单上传缓冲汇总](package-observer-gpu-single-upload-before-2026-10-01.csv)、[逐样本](package-observer-gpu-single-upload-before-samples-2026-10-01.csv)。

上传改四槽主要保证不覆盖 GPU 仍借用的数据；前后 GPU 范围相近，不为 ring 单独声称明显性能收益。完整和紧凑路径在最终版本内比较，均经过相同预热和实际 shader；未以此前单缓冲版本作为严格提速比例分母。

## 最终三次重复范围

单位 ms，上传 MB 为十进制。CPU prepare 与 submit 的 p95 不能相加作为端到端 p95。

| 成员数 | 路径 | CPU prepare p95 | CPU submit p95 | GPU p95 | 计时区间上传 MB/波 |
|---:|---|---:|---:|---:|---:|
| 10000 | 采样/发布 | 0.021–0.126 | 0.0037–0.0048 | 0.113–0.132 | 0（变化上传在区间外） |
| 10000 | 完整变化 | 0.0143–0.0274 | 0.172–0.220 | 0.215–0.270 | 1.280 |
| 10000 | 紧凑变化 | 0.0146–0.0148 | 0.0316–0.0317 | 0.204–0.256 | 0.640 |
| 65536 | 采样/发布 | 0.0969–0.146 | 0.0026–0.0034 | 0.221–0.239 | 0（变化上传在区间外） |
| 65536 | 完整变化 | 0.110–0.765 | 0.969–1.155 | 1.781–1.840 | 8.389 |
| 65536 | 紧凑变化 | 0.0924–0.1053 | 0.502–0.519 | 0.905–1.121 | 4.194 |
| 131072 | 采样/发布 | 0.284–1.172 | 0.0025–0.0026 | 0.384–0.397 | 0（变化上传在区间外） |
| 131072 | 完整变化 | 0.234–1.256 | 1.519–2.698 | 3.269–3.446 | 16.777 |
| 131072 | 紧凑变化 | 0.196–0.839 | 0.986–1.174 | 1.585–1.995 | 8.389 |

131072 紧凑全变化按 20Hz 推算 CPU→GPU 上传约 167.77MB/s；它不是 socket 字节数，尚不是最终压缩网络流直接 GPU 解码。紧凑格式只缩小变化上传，四个输入 bank 仍按完整基线最大值分配，故并未减半显存。单观察者组件申请 GPU buffer 共 `852*capacity+16` 字节，131072 为 111.67MB；混合发布的双 bank 另增加 `320*capacity` 字节，总计约 153.62MB，不含独立 solver、粒子池、驱动和程序开销。以上是由 storage 定义计算的申请量，不是实测显存驻留或峰值。反馈独立增加四个 16 字节 staging buffer，每个成功快照读回 16 字节；计时区间内未读回。

## 正确性证据与运行方式

计时版本 `validatePackageObserverGpu` 通过 3,538,527 项真实 GPU 断言；之后新增实际 wire→controller 往返、同包新成员与变化、精确 namespace 退休、完整 64 位 fence 高字、65 线程尾部和第四个程序编译失败保留旧 bundle，最新共 **3,547,962 项断言**。未重测本报告计时，不能把 controller、时钟或退休成本归入原数值。工作组和容量覆盖 0、1、63、64、65、127、128、129、10000、65536、131072。浮点参考容差 4e-5；完整/紧凑路径的 GPU 状态、body 和 history 对所有掩码及满规模退休逐字节相等。

真实 shared pool 测试证明一个权威自由包裹和一个观察者包裹各占一个唯一通用槽位；观察者不参与权威碰撞网格，时间变化单独产生的新呈现姿态能进入共享发布。上传 ring 混合完整/紧凑格式并延迟八帧，满槽不修改计数/版本，退休事件重试后生效。反馈四槽同样延迟八帧，保留每份提交原有槽位数，检查拒绝和过期；换 epoch 后未完成旧结果不回调，不同 GPU 来源不能借用同一 namespace。

计时版本 `test build` 为 58 个套件/286 项测试；时间协议/controller 补充后最新为 59 个套件/293 项测试，0 失败/错误。Sable/companion 缺席时直接 bridge 门禁通过。观察者 GPU 当前含四个 compute 与一个 include，另有 clock/controller/feedback 组件；没有将 Sable、companion 或离线 benchmark harness 打包。依赖仍为指定的 compileOnly Sable，没有新增反射。

入口：

```powershell
.\gradlew.bat validatePackageObserverGpu test build --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
.\gradlew.bat validatePackageObserverGpu -PpackageObserverBenchmark --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```

wire→GPU controller 和服务器确认时钟已通过组件验证，实际世界订阅、成功 generation 的可见 admission、原模拟时间、网络延迟测量及安全所有权切换仍待完成。用户已将带宽优化与比例验收移至后续版本，本轮沿用当前同步方案；此前 20Hz 自定义流的[实际 payload 对照](package-network-payloads-2026-10-01.md)仅作历史参考，不再作为运行时就绪条件。锁链观察者使用轨道/时间参数，并处理 Sable 父结构变换。当前 0.05s 校正和 0.1s 预测参数仍需用户做视觉对照，不能以这些微基准代替该验收。
