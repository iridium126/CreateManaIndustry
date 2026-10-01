# 锁链包裹 GPU 父姿态内部契约

当前实现了父姿态的 GPU 导入、普通/Iris 顶点、主视角/阴影剔除和逻辑姿态射线查询。世界运行时尚未提供实际 Sable render/logical pose 上传和 acquisition 坐标接线，`CHAIN_READY` 继续关闭。本契约不表示移动锁链已经接管，也不声明 131072 活动包裹的整帧性能通过。

`PackageChainGpuFrame` 每条 track 使用 96 字节：前三个 vec4 是 render 仿射行，后三个是 logical 仿射行。两种姿态必须属于相同 parent UUID 和 conveyor 局部原点；轴为正的正交缩放，允许非均匀缩放。平移先在 double 中减去共享世界物理原点，再转换为 float。此差分避免把巨大 Sable plot 原点放入 GPU 局部运动；不改变通用池现有世界 float 坐标协议。

```java
// render/logical 是已复制的不可变 PackageChainSpace.Frame。
// staging 的当前窗口必须恰好是 96 字节，write 不改变调用方 position/limit。
new PackageChainGpuFrame(render, logical).write(staging, worldOriginX, worldOriginY, worldOriginZ);
pool.chainFrames(immutableFrameSsbo, trackCount);
```

调用方必须维护原生 track index 到 frame row 的一致映射，并保证源缓冲在 import 完成前不被覆写、缩小或删除。生产接线须使用独立非阻塞 bank/fence；当前内部接口没有自行上传或等待 fence。没有帧、声明数量超出实际 SSBO、非有限或奇异矩阵时拒绝该包裹 admission，不能当成静态世界姿态。GPU 验证发现此驱动中只询问数组长度、完全不读取内容的版本返回零长度；最终 selection 使用显式 vec4 行容量和实际矩阵校验，真实空表及短表仍受边界保护。

`FRAMED=8` 只允许与 `CHAIN=1` 同时设置。framed body、历史、target 和普通链路物理始终以本 track 的 conveyor 中心为原点，保留 Create 的进度、局部重力、摆动、反向、hook distance 及吊具姿态。pool import 在 GPU 上把前后 body 和 target 投影到 render 世界空间，箱体和吊具共用一个通用槽位；查询和检查点仍返回 native-local 状态。交还时须由精确 track 身份恢复原生原点，禁止加共享自由域世界原点。

粒子仍为 64 字节、header 仍为 20 vec4；没有增加 Iris sampler。内部 attachment 每候选由 32 扩为 176 字节（11 vec4），两个 commit bank 在 131072 容量共 44 MiB，比原布局增加 36 MiB。原来的两 vec4 前缀仍连续存放于 `2*candidate`，framed previous target 的 xyz 是本轨道局部值。扩展从 `2*capacity+9*candidate` 开始：

| 扩展 vec4 | 内容 |
|---|---|
| 0–2 | render 仿射行 |
| 3–5 | logical 仿射行 |
| 6 | 当前 local body |
| 7 | 上一 local body |
| 8 | 当前 local target |

扩展数据、身份、通用池和绘制命令一起 commit；abort 不替换已提交矩阵。矩阵源随后变化不影响旧 bank 的顶点、阴影或拾取。查询绑定的是已提交 attachment；缺失/短 attachment 返回未命中，所有尾部线程仍参与工作组归约屏障。

顶点先执行原生局部 pendulum，再乘 render 线性部分，以已导入世界 body 为锚点。法线使用逆转置，切线使用正向矩阵，非均匀缩放后分别归一化；零 shade normal 继续保留无阴影 quad 语义。剔除球半径按最大父尺度放大。拾取把世界射线两端逆变换到 logical pose，使用 Create 原生 `.45/.70/.45` target 盒；旋转后扩大世界 AABB 的角落不算命中。

验证入口：

```text
.\gradlew.bat test validatePackageGpu -PpackageChainFramesOnly --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
.\gradlew.bat validatePackageGpu validatePackageIrisGpu build --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```

真实 GPU 覆盖零负载、1/63/64/65 及 131072 全容量有效 framed 包裹、混合 free/static chain/framed、唯一身份/槽位、输出哨兵、世界前后/target、原生局部恢复值、独立 logical/render pose、旋转盒假命中、缩放剔除、短表/缺失/非有限/奇异矩阵及失败 generation。顶点与独立 CPU Create 姿态及逆转置/切线参考比较，绝对浮点容差为 `3e-5`；离散身份、计数、分组和哨兵精确比较。Iris 使用实际 transformer 和真实 OpenGL transform feedback；不运行游戏内 Mixin、光照覆盖、动态父结构拾取/交接、物品库存或多人网络循环。

2026-10-01 在 RTX 4070 Laptop / OpenGL 4.5 / NVIDIA 581.15 上，最后完整构建通过：81 套 391 项单元测试、21,488,488 项包裹 GPU 断言、20,294 项 Iris GPU 断言，以及无 Sable/companion 的独立 JVM 检查。未进行本路径前后性能测量；GPU 数量边界和姿态正确性不能作为整帧 p95、服务端 tick、实际视觉或玩法验收结果。

下一步补齐每 track 的 typed Sable 客户端姿态采集和非阻塞上传、OFFER/FINAL 的局部原点绑定、退休/紧急恢复、光照提前覆盖、父生命周期失效，以及观察客户端世界接线，再进行实际视觉和整帧测试。网络继续使用当前相对已确认基线增量、批量确认和原生观察者方案；进一步带宽优化及原生 1.5 倍限制已从本次目标中排除。
