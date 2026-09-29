# Create GPU 包裹：实现进度与内部契约

## 当前状态（2026-09-29）

**通用槽位与模型绘制已接入实际粒子引擎，可用显式开发预览检查。真实 Create 包裹尚未接管。** 尚未修改 Create 的实体 tick、锁链 tick、普通 Renderer 或 Flywheel visual；因此真实包裹仍完全由 Create 处理。没有声称达到 131072 包裹整帧 60 FPS。

已实现：

- `PackageLease`：稳定身份、权威 epoch、接管基线修订号、两 tick 超时、过期提交拒绝、连续事务序号及回退检查点。休眠心跳无需重复发送位置。此类只负责协议状态，不执行库存事务。
- `PackageDeltaCodec`：有界批次、排序 ID 差分、字段掩码、位置/速度/偏航量化、变长整数编码、缺失基线与非法值检查。未注册为 Minecraft 网络 payload。
- `PackageCollisionCache`：主线程分批捕获、250µs 默认软预算、分区修订号、不可变后台输入、最多四个后台任务、非阻塞完成轮询及陈旧结果丢弃。
- `PackageWorldCollisionSource`：从已加载世界提取实际 AABB、摩擦、水/岩浆/火标记；不向后台传递世界或区块对象。移动活塞、细雪、脚手架目前返回不可用，禁止将其解释为空气。尚未连接区块/方块失效事件。
- `PackagePhysicsGpu`：GPU 空间哈希、静态 AABB 扫掠、四轮并行 Jacobi 接触、锁链进度及摆动；缓存 uniform 位置并使用批量绑定。
- `PackageReadbackRing`：四个独立 staging buffer 和 fence；满槽拒绝提交，不阻塞等待；按序消费并检查 epoch，清空时用新存储替换仍在执行的旧拷贝。
- Java 单元测试、真实 OpenGL 验证及可选计算内核基准。
- `PACKAGE` 内部类型（稳定类型 ID 6、材质 ID 5）：普通更新跳过寿命、死亡链与运动，后续 GPU 导入物理状态；普通 keygen 排除包裹，交给专用模型分组。
- `PackagePoolGpu`：GPU 有效候选筛选、按实际通用池余量预留槽位、稳定身份 admission 表、模型计数/前缀/实例分组、箱体与吊具的间接绘制。每个包裹只占一个通用槽位。绘制、身份与插值附加数据双缓冲，和粒子池一起成功提交；失败保留上次完整提交。
- `PackageModelCache`：提取当前加载的 Create / addon 包裹 PartialModel 的实际顶点、UV、颜色、法线与 shade 标记。缺失模型、tint 或不支持的顶点布局留给 Create；尚未验证所有第三方动态模型。
- GPU 物理历史缓冲：每步保存上一位置、偏航和链上吊点，帧插值不读取 CPU 包裹姿态。链路初始吊点也由 GPU 生成。
- `/cmi particle packagepreview <0..131072>`：显式合成链上包裹预览，使用实际 Create 模型、20Hz GPU 运动及帧插值。0 或 `clear` 停止；清空、换世界及资源重载废止预览。当前仅无 shaderpack；打开 shaderpack 时停止预览。

实际依赖 `curse.maven:create-328085:7963363` 的 jar 声明版本 **6.0.10**，`.refs/Create` 是 **6.0.11**。当前已编译验证模型 API，并核对 6.0.10 ChainConveyorRenderer 字节码中的姿态运算、吊点偏移、±25° 限制与吊具翻转。未据此认定所有物流或实体玩法行为都已核对。

## 开发预览与内部导入接口

```text
/cmi particle packagepreview 10000
/cmi particle packagepreview 65536
/cmi particle packagepreview 131072
/cmi particle packagepreview 0
```

数量受当前通用粒子池容量限制。预览是固定种子、固定模型选择的合成圆轨道阵列；没有 Create 物流对象、库存、拾取或网络，不用于完整玩法验收，也不能以只有少量可见包裹的阵列代替同屏活动负载。第一次建立预览包含模型烘焙和批量初始化，必须预热后再测量。

阵列从相机前方开始，并沿执行命令时的水平视线展开。`stats` 的 `live` 包含成功导入的包裹槽位，但来自异步 GPU 快照，可短暂滞后；持续为 0 并且没有包裹可见不属于正常启动。`stats` 另显示 queued / active / 初始化失败 / shaderpack 停止状态，初始化失败会通知聊天并保留日志。

已修复运行时着色器加载误用文件系统路径的问题：Minecraft ResourceManager 接收 `createmanaindustry:shaders/particles/...`，不能接收 `createmanaindustry:assets/createmanaindustry/shaders/particles/...`。内部入口统一使用 `loadParticlePlain("packages/...")`。驱动验证现在从处理后的运行时资源加载，复用生产 `ParticleShaderSource` 的路径及 include 解析，覆盖此前直接读源码文件而遗漏的接线问题。Java 接线修改需要重新构建并重启客户端，仅按 F3+T 不会替换旧 Java 类。

`CMIParticleEngine.packageParticles()` 是项目内部 render-thread 接口，首次申请时分配资源；未使用该接口时不创建包裹 GPU 缓冲。附加表按最大 131072 个包裹分配，不随通用池数百万槽位放大。

接管适配器需先上传模型 ranges、包裹 metadata，再指定 solver 的状态、链路与历史 buffer 及区域原点。metadata 每项 80 字节：

| 行 | 内容 |
|---|---|
| uvec4 0 | 稳定 long ID 与 long lifecycle generation，均按低/高 uint 保存 |
| uvec4 1 | body index、箱体 mesh、吊具 mesh（-1 无吊具）、CHAIN/FLIPPED 标记 |
| vec4 2 | 保留的接管基线字段；当前插值从 GPU history 读取 |
| vec4 3 | 保留、保留、hook distance、原生 packed-light 位模式 |
| vec4 4 | Create ground nudge xyz、保留 |

导入只接受有效物理 body 和完整模型；静态 collider、回退标记与容量之外候选不会分配通用槽位。未知 mesh/body index 在 GPU 解引用前检查。每个成功提交的 admission 表包含完整稳定身份及当前 `slot+1`，0 表示未分配；表项顺序对应候选序号，槽位顺序可能随 GPU 筛选改变。

**admission 仅证明本次绘制槽位已分配，不是服务端接管确认。** 适配器必须通过独立 staging ring 读取已完成代次，并核对 region/epoch/基线；不能直接用 CPU 请求数量取消 Create 渲染。metadata 上传用于初始化/变更，目前还没有为真实对象实现脏区间整理器。Box / rig 共享状态，输出的实例索引同时携带候选索引与部件位，避免依赖厂商 draw-parameters 扩展。

绘制计算由普通粒子发射后的 GPU counter 控制；成功后才发布 admission、commands 与附件 bank。shader 重建先验证全部包裹程序，编译失败保留旧程序。GPU 求解器应在普通引擎 pass 外更新，进入引擎边界时恢复状态；包裹导入和绘制会使引擎绑定缓存失效。

### 绘制提交与进一步合并

所有包裹 mesh（包括箱体和吊具）已经用一次 `glMultiDrawArraysIndirect` API 调用提交；内部仍有多个 mesh 子命令。MODEL 的实体、手持物和透明外壳已用一次 `glMultiDrawElementsIndirect` 提交。调用次数不随包裹或 MODEL 粒子数量线性增长。

无光影时，包裹与 MODEL 可以在统一几何寻址、实例记录、着色器及纹理访问后进一步合并，但目前二者使用不同 VAO、非索引/索引几何、block/allay atlas、姿态和光照路径。包裹关闭混合；MODEL 透明外壳使用混合并写深度。跨类型合并必须保留这些行为，不能仅拼接 indirect buffer。

OPAQUE 与包裹可考虑统一不透明 pass；ALPHA 与 Hex 使用相近混合状态，但还必须保留各自深度写入和透明排序顺序。ADDITIVE 采用不同混合因子，继续保留独立 pass。Iris 的 MODEL 主渲染/阴影插入点也要求单独验证。没有启用尚未通过正确性和同机性能对比的统一路径。

`/cmi particle profile on` 后预热，再运行 `/cmi particle profile`，现在可见 `package_physics`、`package_import_group`、`draw_packages`、`draw_models`、各 sprite / Hex 绘制阶段的异步 GPU 时间，以及粒子引擎跟踪到的绘制 API 提交次数。multi-draw 计为一次；零实例间接命令仍计为提交，不等于有可见工作。该统计不包含 Minecraft/Create 自己的所有 draw call。

## 数据与执行契约

### 身份、接管及网络

服务端身份使用 `(long id, long generation)`，lease 的 epoch 为 long。网络增量中的 int ID 是**区域基线局部索引**，不是粒子池索引；后续 payload 必须携带区域、完整 epoch、基线修订号及序号，并用基线映射回稳定身份。

`ready` 只接受当前基线修订及匹配的当前位置，不能用旧 GPU 基线接管已经移动的 Create 对象。实际接管适配器仍需实现最终检查点上传和交接时序；禁止仅收到一次 ready 消息就取消实体 tick。

量化候选值为位置 1/4096 方块、速度 1/1024 方块/秒、偏航 360/65536 度。这些尚未通过游戏内视觉对照，不能视为最终默认协议。超出局部坐标或速度范围时编码器拒绝，后续网络层必须使用全状态逃逸记录或回退，不允许饱和截断。

编码批次最多 2048 个记录。增量基线只能在确认后更新。读回成功不等于服务端已提交，更不等于库存事务已提交。事务序号门禁不能代替库存操作的原子提交与错误恢复。

### 碰撞快照

每个 section 有 4096 个 cell，顺序为 `x | z << 4 | y << 8`。快照中的形状坐标相对 section 原点。捕获每次最多处理 32 个 cell，并按剩余时间轮转；后台只打包不可变的 cell 和 AABB。

预算在每次原始查询之前检查。Java 无法中断某个模组提供的耗时 `getCollisionShape`，所以这是软预算；`lastCaptureNanos` 和 `overrunCount` 如实记录超限。后续接入时必须在区块、方块及影响邻居形状的上下文变化时撤销覆盖，不能仅依靠定时刷新。

未知/动态实体上下文的碰撞形状仍需要资格检查。当前 adapter 的空碰撞上下文不能被当作所有第三方方块的完整兼容实现。

### GPU 物理

每个附加物理记录 64 字节：

| vec4 | 字段 |
|---|---|
| 0 | 中心位置 xyz、逆质量；零逆质量表示静态碰撞体 |
| 1 | 速度 xyz、支撑标记 |
| 2 | 半尺寸 xyz、偏航 |
| 3 | 上一步中心 xyz、保留状态；负 w 表示请求回退 |

这是包裹专用附加状态，通过 `PackagePoolGpu` 映射到普通池的一包裹一槽位。另有每 body 32 字节插值历史。静态碰撞体只占附加碰撞数据，不分配通用粒子槽位。

当前宽阶段要求各半尺寸不超过 cellSize/2；大的静态形状需要先分割。每轮 Jacobi 都重建网格，没有固定邻居截断，也不依赖浮点原子操作或厂商 subgroup。

静态扫掠超过 1024 个网格单元或任一方向超过 32 格时输出回退标记并保持原位置。动态高速包裹之间尚未实现连续碰撞；休眠、活动列表、密集接触局部回退和分区覆盖检查也尚未接入。因此不能直接将该 solver 应用于所有游戏内包裹。

当前自由运动初始常数为 32 方块/秒² 重力和按 20Hz 换算的阻尼，需用游戏实际轨迹校准。锁链在 20Hz 下沿用 Create 的摆动递推；其他更新频率的插值、姿态及视觉一致性尚未验收。

物理及历史 pass 重用绑定点 0–5，导入/分组重用 0–9，不扩展通用引擎的绑定数量要求。分别使用 SSBO、indirect-command、vertex-attribute 与 buffer-update barrier。调用者必须在外部渲染边界恢复 GL 状态，并使通用引擎绑定缓存失效。

## 验证命令

```powershell
.\gradlew.bat test --tests '*PackageProtocolTest' --tests '*PackageCollisionCacheTest' --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
.\gradlew.bat validatePackageGpu --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
.\gradlew.bat validatePackageGpu --offline --no-configuration-cache -I scripts/particles/validation.init.gradle -PpackageBenchmark
```

基准输出 `build/package-gpu-kernels.csv`。当前 7 项 Java 测试、19550 项真实 GPU 断言通过；已有 Hex 全套回归通过 135498 项断言。包裹测试覆盖空输入、64 线程尾部边界、静态支撑、重合包裹分离、高速静态碰撞、过大扫掠回退、锁链批次、四槽耗尽、代次失效、满容量 admission、完整 long 身份、缓冲哨兵、模型分区、实例唯一性及失败帧回滚。新增运行时路径/include 检查及 65536 个链上候选的完整导入、65536 个唯一槽位、131072 个箱体/吊具实例命令验证。该大批次检查不代表实际 Create 模型游戏内同屏验收。

增加真实 indirect draw 像素检查（包括 baseInstance）、失败/成功 shader 重建，以及 GPU vertex transform-feedback 对照 Create 参考矩阵。光照额外覆盖 Create SBB 双方向 diffuse 和 Flywheel chunk diffuse（含恒定环境光、未着色面），RGB 容差为 2e-5。姿态 xyz 容差 `2e-5` 方块；80 步 20Hz 链上递推对照的位置/偏航容差 `1e-4` 方块/度。测试里的阻塞 GPU 读取和计时结果等待仅用于验证；生产读回类只使用零超时 fence 轮询。上述数值验证不能替代游戏内视觉录像和光照/阴影验收。

## 首轮内核测量

RTX 4070 Laptop，OpenGL 4.5，NVIDIA 581.15；20 次预热，三组各 30 步，GPU timer query。每组报告平均单步耗时，下面为三组中位数，**不是帧时间，也不是 p95**。

| 数量 | 分散自由运动、静态扫掠及四轮接触 | 锁链运动 |
|---:|---:|---:|
| 10000 | 0.1741 ms | 0.0081 ms |
| 65536 | 0.9826 ms | 0.0368 ms |
| 131072 | 1.7525 ms | 0.0570 ms |

分散自由运动基准中没有静态世界碰撞体或密集接触，结果只能说明计算内核在该输入分布下的开销。没有绘制、读回、网络、Minecraft 世界处理，也没有与 Create 实际同场景比较。详见 `benchmarks/package-kernels-2026-09-29.csv`。

增加 GPU 插值历史保存后的新基线，三组中位数如下：

| 数量 | 分散自由运动 | 锁链运动（含历史保存） |
|---:|---:|---:|
| 10000 | 0.1819 ms | 0.0128 ms |
| 65536 | 1.0026 ms | 0.0484 ms |
| 131072 | 1.9994 ms | 0.0740 ms |

这次增加的是正确插值所需的工作，不是提速结果。131072 自由运动的三次结果为 2.1005 / 1.9994 / 1.7893 ms，波动明显，不能用该组均值/中位数宣布性能回退或收益。新测量仍不包含模型绘制、世界碰撞和网络；原始结果见 `benchmarks/package-kernels-history-2026-09-29.csv`。

## 剩余交付工作

1. 完成实际 Create 6.0.10 玩法行为核对，接管/回退适配器、区域权威选举、最终基线握手及服务端物品事务。
2. 将已实现的通用槽位/完整身份 admission 接到真实对象生命周期、确认读回和容量回退，整理变更上传；避免生产批量初始化集中阻塞。
3. 将已实现的模型缓存与绘制连接到真实包裹，确认后抑制原 Renderer/Flywheel，完成 Iris/阴影接入及整个资源集合的事务替换。
4. 碰撞快照的事件失效、预取、上传、覆盖及第三方动态形状资格检查；休眠、活动调度和动态连续碰撞。
5. GPU 脏状态压缩、网络 payload 注册、确认基线、区域订阅及连续事件的持久保留。
6. 游戏内视觉对照录像、玩法/多人回归，以及包含真实绘制和网络的 131072 活动包裹完整性能验收。

上述事项完成前，不得抑制 Create 原有模拟或渲染，也不得把本阶段内核测量当作方案验收结果。
