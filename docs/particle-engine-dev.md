# GPU 粒子引擎开发说明

状态：🟡 主流程已实现；shader 编译、shaderpack、Hexcasting 和大型数量级仍需按版本做游戏内验证。

## 1. 范围与原则

`CMIParticleEngine` 是客户端自托管 OpenGL 粒子引擎，不依赖 Veil。粒子状态、更新、剔除、排序和间接绘制尽量留在 GPU；CPU 只负责发射请求、资源绑定、少量读回和生命周期。

当前材质分为：

| 材质 | 用途 | 绘制 |
| --- | --- | --- |
| `OPAQUE` | 不透明/裁剪精灵 | 深度写入，atlas 采样 |
| `ALPHA` | 半透明精灵 | 深度排序后绘制 |
| `ADDITIVE` | 光效、Hexcasting | 加法混合，最后绘制 |
| `MODEL` | Allay、带动画模型 | 与透明粒子共同排序 |
| `HEX_PATTERN` | 持续法术图案 | 独立几何，与透明材质分区排序 |

视觉对齐要求：新增原版替代路径必须先记录原版的生命周期、速度、颜色、尺寸、光照、混合和碰撞语义，再放入对应材质；不要为了复用而改变这些语义。

## 2. 一帧的数据流

```text
CPU 脏 header / 发射队列 / 公共帧参数
  -> prepare_dispatch：上一提交代有效数 -> 更新工作组
  -> reset / update：积分、碰撞、死亡链，写入下一代池
  -> emit / block_emit / hex_reconcile：追加新粒子
  -> prepare_dispatch：钳制申请计数 -> 剔除工作组
  -> keygen：可见材质索引与透明 key
  -> prepare_dispatch：实际可见透明数 -> 排序工作组
  -> radix_hist / scan / scatter：材质分区及 256 深度带
  -> capture：完成计数与 indirect draw
  -> 提交 generation，并在空闲 staging 槽复制快照
  -> AFTER_LEVEL：OPAQUE / MODEL / ALPHA / HEX_PATTERN / ADDITIVE 绘制
```

`beginFrame` 在 `AFTER_SKY` 执行 compute 并提交 generation；shaderpack 合并路径在同一 `renderLevel` 使用该 generation；`endFrame` 在 `AFTER_LEVEL` 绘制。Iris 阴影轨道读取前一 generation，这是有意的时序约束。

GPU 资源由 `ParticleBuffers` 管理，程序由 `ParticlePrograms` 管理，shader 在 `assets/createmanaindustry/shaders/particles/`。每个发射器共享一个 20-`vec4` header；发射命令包含位置、速度、数量和发射器参数；各材质使用自己的 indirect command，避免把其他材质的实例数带进 draw。

## 3. 原版粒子与 Hexcasting

### 3.1 Hexcasting 覆盖范围

`particles.hexParticleRedirect` 开启且 GPU 引擎可用时，以下两个入口都重定向：

- `HexConjureParticleRedirectMixin` 覆盖 `ClientLevel.addParticle` 的普通和 limiter-aware 两个 overload，识别 `ConjureParticleOptions`，对应直接 `conjure_particle`。
- `HexSprayRedirectMixin` 覆盖 `MsgCastParticleS2C.Handler.handle`，把 `ParticleSpray` 采样后交给 GPU；服务端施法、法术阵、mishap 和 CMI 自己的 spray 共用此入口。

引擎未初始化、GL/shader 失败或配置关闭时保留原版路径。Mixin 回调必须使用真实 `CallbackInfo`；不要手动调用注入方法或把回调参数设成可空，否则会重现 `ci == null` 崩溃。

### 3.2 Hex 视觉契约

`HexSpecs` 使用独立 `spawnStyle`/`colorMode`：

| 路径 | style | color | 关键语义 |
| --- | ---: | ---: | --- |
| spray | 4 | 4 | 8 个 pigment wheel 颜色，按速度方向取样 |
| direct `conjure_particle` | 5 | 5 | 生成时冻结 ARGB，保留每粒子的重力位 |

两者共用加法混合、软六边形云形、fullbright、无碰撞、`0.96/tick` 摩擦、原版重力方向和生命周期换算。喷雾颜色在客户端生成时一次采样，避免 GPU 随时间重新改变原版已经冻结的颜色。

## 4. Allay Storm 与模型粒子

Allay Storm 的成员使用 `MODEL` 粒子表示，身份、生命值、姿态、攻击和死亡链由 `AllayStormRuntime` 与 shader 共同维护。模型几何由 `AllayModelGeometry` 构建，手持物由 `HeldItemGeometry` 提供；`allay_pose.glsl` 是 shader 与 Java 姿态约定的共享实现。

服务端只同步 storm 状态、成员事件、伤害和稀疏校正；客户端 GPU 负责高频位置积分。协议细节见 [allay-storm-sync.md](allay-storm-sync.md)，AI/波次细节见 [allay-storm-ai.md](allay-storm-ai.md)。

## 5. shaderpack 接入

`shaderPackIntegration` 只保证 `MODEL` 粒子接入 shaderpack 的实体/阴影轨道；`ADDITIVE`、`ALPHA`、`OPAQUE` 仍由本项目自己的粒子 pass 绘制。入口为：

- `ParticleVertexInjector`：注入粒子顶点所需的 TBO/实例数据访问。
- `ShaderPackProgramCompiler`：编译/回退合并后的 MODEL 程序。
- `CMIPackEntityMergeHook`：在 shaderpack entity 路径消费当前 generation。
- `MixinIrisShadowRenderer`、`MixinProgramSamplers`：阴影轨道和 sampler 绑定。

任何合并失败都必须回退到本项目 MODEL shader，并在 `/cmi particle shaderpack status` 暴露状态。

## 6. 配置与调试

客户端文件为 `config/createmanaindustry-client.toml`：

| 键 | 默认值 | 作用 |
| --- | ---: | --- |
| `particles.enabled` | `true` | 总开关 |
| `particles.maxParticles` | `2000000` | GPU 粒子容量 |
| `particles.frameBudgetMs` | `15.0` | 自动节流预算 |
| `particles.autoThrottle` | `true` | 超预算时降低发射 |
| `particles.fadeDistance` | `96` | 距离淡出起点 |
| `particles.shaderPackIntegration` | `true` | MODEL shaderpack 接入 |
| `particles.hexParticleRedirect` | `true` | Hexcasting 两类入口重定向 |

客户端粒子命令：

```text
/cmi particle emit <preset|amethyst|uuid|rainbow> <amount> [<seconds>|forever]
/cmi particle anim <preset> <fly|dance|hold>
/cmi particle stats
/cmi particle profile on
/cmi particle profile
/cmi particle profile off
/cmi particle budget <ms>
/cmi particle shaderpack status
/cmi particle clear
```

`emit` 不带时长时，preset 的 `amount` 是单次粒子数，pigment 的 `amount` 是 Hex 喷发数；带时长时，preset 的 `amount` 是每秒速率，秒数范围为 `0.1..3600`，`forever` 表示持续到清除。Pigment 不支持持续发射。preset 单次数量上限为 4,000,000，流速上限为 1,000,000/s；pigment 喷发上限为 2,000。Hex pigment 仅在 Hexcasting 已加载时提供。

服务端管理命令（权限 2）：

```text
/cmi particle allaystorm [count]
/cmi particle allaystorm stop
/cmi hexjit status
/cmi hexjit clear
```

## 7. 验证清单

- 无 shaderpack：五种材质均能出现，`/cmi particle stats` 无持续 GL 错误。
- Iris/shaderpack：MODEL 的位置、姿态、深度和阴影轨道正确；失败时可回退。
- Hexcasting：直接 `conjure_particle`、网络 `ParticleSpray`、两个 `addParticle` overload 都验证；关闭键后原版仍出现。
- Storm：多客户端进入/离开、dimension change、死亡广播、波次接触和断线清理。
- 性能：分别测试零透明、透明排序和大量 additive，确认 budget 不导致粒子池越界或误清空。

## 8. 主要入口

- Java：`client/particles/engine/CMIParticleEngine`、`ParticleBuffers`、`ParticlePrograms`、`HexSpecs`。
- Mixin：`mixin/hexcasting/HexConjureParticleRedirectMixin`、`HexSprayRedirectMixin`。
- GLSL：`shaders/particles/{emit,update,keygen,radix_*,capture}.comp`、`{textured,additive,model}.{vsh,fsh}`。
- 配置：`config/ClientConfig.java`。

## 9. 内部类型扩展

`ParticleTypes` 将稳定类型 ID 与绘制材质分开。必须在首次 shader 编译前注册，之后目录冻结；这是项目内部接口，不承诺第三方兼容性，也不新增资源包 JSON 协议。`EmitterSpec` 保存类型作为唯一来源，默认使用 ADDITIVE；其他材质通过 `.type(ParticleTypes.standard(...))` 选择内建类型。

例如复用 ADDITIVE 的上升火花：

```java
static final ParticleTypes.Type RISING_SPARK = ParticleTypes.register(
    new ParticleTypes.Type(1000, "rising_spark", ParticleTypes.Material.ADDITIVE,
        "chunks/examples/rising_spark_spawn.glsl",
        "chunks/examples/rising_spark_update.glsl", Set.of()));

EmitterSpec spec = EmitterSpec.builder().type(RISING_SPARK).build();
```

示例模块已包含在 shader 目录，生产环境不会自动注册；真实 GPU 测试会注册并执行它。模块定义 `cmi_rising_spark_spawn` / `cmi_rising_spark_update`，签名如下：

```glsl
void cmi_rising_spark_update(uint header,
    inout vec4 p0, inout vec4 p1, inout vec4 p2, inout vec4 p3) {
    p1.y += uDt;
}
```

标准生成先初始化随机数、生命值和身份，然后执行 spawn hook；update hook 在基础更新后执行，所以上述速度变化影响下一步位置积分。不要覆盖 emitter ID、身份、类型或寿命字段的既有约定。新功能依赖在 Type.features 中声明；当前 features 是描述元数据，不会自动生成世界资源或跳过现有通用功能。

编译器展开 `#pragma cmi_types spawn/update`，沿用 `#pragma cmi_include`，生成 GLSL 类型分派。没有逐粒子的 Java 回调。内建基础运动、碰撞查询/扫掠、Storm 运动/导航、Hex 更新、伤害与死亡链各有独立 chunk；修改公式需要参考测试，不能仅以编译成功判断兼容。

## 10. 布局、同步与职责

粒子仍为 4 vec4 / 64 B；header 仍为 20 vec4 / 320 B，首 vec4.x 的原保留位承载类型 ID。`ParticlePrograms` 从 Java 常量生成绑定点、间接命令及 Hex 布局 prelude；布局测试检查尺寸和类型映射。局部 MODEL 身份放在独立 sidecar，不修改 shaderpack 读取的粒子结构；内部伤害队列记录扩为 32 B，不改变网络包。

`ParticleDispatch` 准备有界间接工作组；`ParticleEmitterUploads` 合并 header 脏区；`ParticleReadbacks` 管理四个独立 staging 槽；`ParticleDiagnostics` 负责可关闭计时；引擎的 `DrawPipeline` 负责绘制状态恢复。绑定缓存仅在引擎 pass 内有效，在 Minecraft/Iris 边界失效。碰撞烘焙继续使用既有后台执行方式，不将世界访问迁移到其他线程。

计数是阶段内的申请总数，容量钳制后才作为有效数进入读取和 indirect dispatch。CPU census 仅供显示及保守空闲判断，不决定 GPU 遍历范围。SSBO 写后使用 STORAGE barrier；间接命令加 COMMAND，TBO 读取加 TEXTURE_FETCH；复制、清除与 staging 使用 BUFFER_UPDATE。fence 覆盖快照复制，只以零超时轮询；槽满不覆盖、不等待。

清空、换维度和成功重建使身份 epoch 失效。编译失败保留原程序及其待消费结果；成功编译整组程序后才替换。粒子池、排序排列和绘制命令按 generation 配对，阴影仍读取前一提交代。波次队列仅在成功复制后确认清除，位置快照不会在等待期间覆盖。

测试说明见 [scripts/particles/README.md](../scripts/particles/README.md)。性能数据、显存成本和未验收事项见 [测量记录](particle-engine-performance.md)。
