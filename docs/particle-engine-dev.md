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

视觉对齐要求：新增原版替代路径必须先记录原版的生命周期、速度、颜色、尺寸、光照、混合和碰撞语义，再放入对应材质；不要为了复用而改变这些语义。

## 2. 一帧的数据流

```text
CPU 发射队列
  -> emit.comp：写入双缓冲粒子池
  -> update.comp：积分、寿命、碰撞、死亡链
  -> keygen.comp：视锥剔除并生成 ADDITIVE/透明索引
  -> radix_*：透明粒子按深度排序
  -> capture.comp：统计后续 dispatch 上界
  -> AFTER_LEVEL：OPAQUE / MODEL / ALPHA / ADDITIVE 间接绘制
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

任何合并失败都必须回退到本项目 MODEL shader，并在 `/cmip shaderpack status` 暴露状态。

## 6. 配置与调试

客户端文件为 `config/createmanaindustry-client.toml`：

| 键 | 默认值 | 作用 |
| --- | ---: | --- |
| `particles.enabled` | `true` | 总开关 |
| `particles.maxParticles` | `2000000` | GPU 粒子容量 |
| `particles.frameBudgetMs` | `16.6` | 自动节流预算 |
| `particles.autoThrottle` | `true` | 超预算时降低发射 |
| `particles.fadeDistance` | `96` | 距离淡出起点 |
| `particles.shaderPackIntegration` | `true` | MODEL shaderpack 接入 |
| `particles.hexParticleRedirect` | `true` | Hexcasting 两类入口重定向 |

客户端调试命令：

```text
/cmip spawn <preset> [count]
/cmip stream <preset> <rate> [seconds]
/cmip anim <preset> <fly|dance|hold>
/cmip spray <amethyst|uuid|rainbow> [count]
/cmip bench <count>
/cmip stats
/cmip budget <ms>
/cmip shaderpack status
/cmip clear
```

## 7. 验证清单

- 无 shaderpack：四种材质均能出现，`/cmip stats` 无持续 GL 错误。
- Iris/shaderpack：MODEL 的位置、姿态、深度和阴影轨道正确；失败时可回退。
- Hexcasting：直接 `conjure_particle`、网络 `ParticleSpray`、两个 `addParticle` overload 都验证；关闭键后原版仍出现。
- Storm：多客户端进入/离开、dimension change、死亡广播、波次接触和断线清理。
- 性能：分别测试零透明、透明排序、大量 additive 和 `bench`，确认 budget 不导致粒子池越界或误清空。

## 8. 主要入口

- Java：`client/particles/engine/CMIParticleEngine`、`ParticleBuffers`、`ParticlePrograms`、`HexSpecs`。
- Mixin：`mixin/hexcasting/HexConjureParticleRedirectMixin`、`HexSprayRedirectMixin`。
- GLSL：`shaders/particles/{emit,update,keygen,radix_*,capture}.comp`、`{textured,additive,model}.{vsh,fsh}`。
- 配置：`config/ClientConfig.java`。
