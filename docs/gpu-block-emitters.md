# GPU 方块粒子发射器

发光藤蔓已从 CPU stream 切换到通用方块发射器。每个附着面对应一个 GPU 记录，沿用原来的绿色色轮、面内分布、尺寸、寿命、风和重力；每面目标速率仍为 100 粒子/秒。其他方块可通过 `BlockParticleEmitters.register` 注册。

## 数据与生命周期

- `BlockParticleEmitterClient` 按区块保存方块到发射器槽位的索引。客户端初次进入世界或摄像机跨越区块边界、渲染距离改变时调度发现窗口。区块完整数据包会使旧索引失效；区块加载事件补充尚未发现的区块。静止时不轮询区块窗口或读取活跃方块。
- 每 tick 最多开始 4 个区块、扫描 8 个包含目标方块的 section。section 调色板的 `maybeHas` 先排除无关 section，因此空 section 不消耗逐方块扫描预算。首次发现是渐进的；已经发现的发射器在每个计算帧响应摄像机位置。
- 普通方块更新通过现有 `Level.setBlock` 客户端 mixin 更新索引；方块移除、藤蔓附着面改变立即移除或替换对应记录。完整区块重传走 `ClientLevel.onChunkLoaded`，不会遗留旧记录。
- 离开 GPU 粒子渲染距离（配置的 fadeDistance + 24）或视锥时 GPU 停止发射，保留不足一个粒子的累计值；重新进入后正常发射，不补发离开期间的粒子。视锥使用可配置的效果包围球，藤蔓半径为 8 格，避免只剔除方块导致附近粒子突然断流。
- 区块卸载和发现窗口裁剪释放相应槽位；维度切换和退出世界清空索引与发射器表，现有粒子由引擎的维度切换钩子同步清除。关闭客户端时释放 SSBO。

## GPU 路径

`BlockEmitterTable` 使用稳定槽位，每个记录 32 字节：世界坐标、速率、共享 EmitterSpec ID、GPU 累计值、剔除半径和保留字。邻接脏记录合并上传；未改变记录不上传，尤其不会覆盖其 GPU 累计值。一万个单面方块上传约 320 KB，一万块五面藤蔓约 1.6 MB，均为初次发现或改变时的流量。

表最多容纳 262,140 个面发射器，不占用传统的每帧 emit command 队列。不同位置复用相同的效果头；五种藤蔓附着面只占五个 EmitterSpec 头，而非五万个。表使用约 8 MiB CPU staging 和最多约 8 MiB GPU 存储。

计算顺序为 `reset → update → 普通 emit → block_emit → keygen → 后续排序/绘制`。每个 64 线程工作组处理四个发射器，每个发射器使用 16 个线程；一个线程进行距离/视锥测试、维护累计值并批量申请槽位，其余线程协作初始化粒子。每个有产出的发射器每帧只执行一次全局原子申请，无逐粒子命令二分搜索、storm/Hex 发射分支或可见性回读。共享 `classic_spawn.glsl` 保留原有形状算法；方块路径在编译时去掉 MODEL 和光照模式分支。

CPU 通过常数时间计算的保守发射上界维护已有异步计数系统，不额外读取 GPU 发射结果。上界饱和到池容量，粒子池写入也有边界保护。每帧轮换发射器处理起点，缓解粒子池接近满载时固定位置长期抢不到槽位的问题。发射速率遵循引擎自动节流比例。

目前使用距离和视锥剔除，没有接入深度遮挡剔除：此计算阶段在当帧地形深度准备之前，直接采样旧深度会在移动相机、切换光影或切换维度时错误剔除。

## 注册其他方块

在客户端初始化、首次区块发现之前注册；提供方块状态到零个或多个 `Source` 的映射。`Source` 的 x/y/z 是相对方块最小角的发射偏移，半径应覆盖粒子运动包围范围。复用不可变 Source/EmitterSpec，避免在工厂中为每块方块创建效果头。

```java
var source = new BlockParticleEmitters.Source(
        EmitterSpec.builder()
                .shape(EmitterShape.BOX).size(0.4)
                .speed(0.05, 0.1).life(1.0, 2.0)
                .material(EmitterSpec.Material.ADDITIVE)
                .collide(EmitterSpec.CollideMode.NONE)
                .build(),
        0.5f, 0.5f, 0.5f, 20f, 3f);
BlockParticleEmitters.register(MY_BLOCK.get(), (state, sink) -> sink.accept(source));
```

这一批量路径接受 ADDITIVE、无碰撞、全亮效果，支持现有五种发射形状；每记录速率须在 `(0, 1024]`。透明排序、模型实体、光照采样和带碰撞效果继续使用原有接口。注册表应在开始发现后保持不变；动态修改注册后可在客户端线程调用 `BlockParticleEmitterClient.clear()` 重新发现。

表容量与存活粒子池容量独立。一万块单面藤蔓满速需要每秒约一百万粒子，原寿命对应约 373 万稳定存活粒子；五面时约为五倍。因此默认两百万粒子池或自动节流可能降低实际发射量，支持一万个方块不等于保证所有硬件都能在满速、原寿命下实时绘制全部粒子。GPU 写入始终受现有池容量限制。

## 验证

```powershell
.\gradlew.bat build --offline
python scripts/validate-block-emitters.py --gradle-cache 'D:/Program Files/Gradle/cache'
```

验证脚本使用缓存中的 GLFW DLL 创建隐藏 OpenGL 4.5 上下文，不安装 Python 依赖。它编译真实 `emit.comp`/`block_emit.comp`，执行 1、3、10,000、50,000 个发射器的测试，覆盖尾工作组、旋转调度、粒子数据、范围进出、视锥、移除、低速累计、零速率比例、最大时间步与池溢出。随后运行实际 Java 表实现，验证 50,000 条记录上传、稀疏删除/复用、GPU 累计值不被邻接更新覆盖、维度清空及容量限制。

2026-09-20，RTX 4070 Laptop GPU 的隐藏上下文测试：在 60 Hz、100 粒子/秒/发射器、全部可见条件下，发射 kernel 的 GPU 中位耗时约 0.015 ms / 10,000 记录，0.05 ms / 50,000 记录。测量排除了预热，使用 GL_TIME_ELAPSED 查询。此结果仅验证发射计算成本，不包含 Minecraft 区块发现、数百万粒子的更新、绘制和光影开销；未进行游戏内一万方块场景的完整帧率测试。

同次完整 Gradle 构建的编译、资源处理和打包通过，73 项测试中 71 项通过、2 项失败：`AllvrSanctuaryTest.sideVinesRejectHalfSlabFaces` 在 Minecraft 注册表初始化时报告 `Not bootstrapped`，`timberAnchorTowersReachARealSupport` 报告 `(104,165)` 处支撑柱悬空。本次没有修改圣域生成器或这些测试；完整 `build` 因它们未通过而返回失败。
