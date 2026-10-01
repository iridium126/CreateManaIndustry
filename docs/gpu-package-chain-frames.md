# 锁链包裹 GPU 父姿态内部契约

当前实现包括父姿态 GPU 展开、通用池同代导入、普通/Iris 顶点、主视角/阴影剔除、逻辑姿态射线查询，以及 Sable render/logical pose 的运行时采集。TRACK 携带稳定 parent UUID；acquisition 用 conveyor 原生坐标准备 body 和恢复检查点。开启实验配置且四个锁链通道协商成功时，客户端会发送 `CHAIN_READY` 并进入服务端握手；这不代表已通过游戏内行为或整帧性能验收。

`PackageChainGpuFrame` 每条 track 使用 96 字节：前三个 vec4 是 render 仿射行，后三个是 logical 仿射行。两种姿态必须属于相同 parent UUID 和 conveyor 局部原点；轴为正的正交缩放，允许非均匀缩放。平移先在 double 中减去共享世界物理原点，再转换为 float。此差分避免把巨大 Sable plot 原点放入 GPU 局部运动；不改变通用池现有世界 float 坐标协议。

```java
// 每个渲染提交代只采集一次不可变 render/logical 姿态；灯光请求和 GPU 上传共用快照。
chainFrames.beginFrame(frameId);
Vec3 worldLightPosition=chainFrames.worldPosition(trackIndex,nativeLocalPosition);
chainFrames.prepare(pool); // 与后续 PackagePoolGpu.stage 属于同一粒子提交代
```

Sable 子层的首次灯光探测使用 logical 父姿态将原生局部位置投影到世界，再请求周围 light section；只有快照已经进入 GPU light atlas 后，chain acquisition 才继续。普通静态 track 的 frame 是单位变换。父姿态在本次提交代内缓存，避免 acquisition 探测与渲染上传各读取一次。

track index 与 GPU origin/frame row 共用追加式命名空间。四个 source/output bank 使用 persistent mapped 上传和 fence；pool import 完成后才封存 source bank。全槽未完成时不等待、不覆盖，当前世界运行时安全回退 Create。父对象在相同 UUID 下重建时按代表 conveyor 重新绑定；父缺失、矩阵无效、帧源耗尽或 generation 失败都不能把旧帧发布成新提交。没有帧、声明数量超出实际 SSBO、非有限或奇异矩阵时拒绝该包裹 admission，不能当成静态世界姿态。GPU 验证发现此驱动中只询问数组长度、完全不读取内容的版本返回零长度；最终 selection 使用显式 vec4 行容量和实际矩阵校验，真实空表及短表仍受边界保护。

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

真实 GPU 覆盖零负载、1/63/64/65 及 131072 全容量有效 framed 包裹、混合 free/static chain/framed、唯一身份/槽位、输出哨兵、世界前后/target、原生局部恢复值、独立 logical/render pose、旋转盒假命中、缩放剔除、短表/缺失/非有限/奇异矩阵、失败 generation，以及四个 fence 全未完成时第五次提交被跳过且前四个输出仍保持独立。顶点与独立 CPU Create 姿态及逆转置/切线参考比较，绝对浮点容差为 `3e-5`；离散身份、计数、分组和哨兵精确比较。Sable bridge 使用实际 `compileOnly` 类型，不使用反射；独立 JVM 确认缺少 Sable 时 bridge 不会加载。Iris 使用实际 transformer 和真实 OpenGL transform feedback；不运行游戏内 Mixin、动态父结构拾取/交接、物品库存或多人网络循环。

2026-10-01 在 RTX 4070 Laptop / OpenGL 4.5 / NVIDIA 581.15 上，完整 `test build` 通过；本次 package GPU 全量为 24,589,175 项断言，链帧专项为 4,080,346 项，light atlas 为 886,165 项，Iris transform feedback 为 20,294 项，且无 Sable/companion 的独立 JVM 检查通过。世界碰撞和堆叠 131072 kernel 测量记录于[GPU 基准报告](benchmarks/package-world-stack-2026-10-01.md)。这些是组件计时，不是之前/之后对比，也不包含完整粒子池导入、模型绘制、Minecraft 主线程及服务端 tick；不能作为整帧 p95、实际视觉或玩法验收结果。

下一步完成游戏内移动 Sable 父结构下的链上视觉、拾取、释放/回退和资源重载验证，并补齐光照覆盖、观察客户端世界接线及 131072 活动链包裹整帧测量。网络沿用当前同步方案；进一步带宽优化及带宽倍率验收已从本次目标中排除。
