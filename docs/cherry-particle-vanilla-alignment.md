# GPU cherry_leaves 原版对齐检查

结论：**尚未完全对齐**。预设的 `faithfully replicated` 注释不能代表实际行为；目前是共享 GPU 粒子管线中的近似实现。

检查范围：当前项目的 `EmitterPresets.CHERRY_LEAVES`，与 `.refs/neoforge-21.1.227` 中 Minecraft 1.21.1 的 `CherryParticle` 及其父类、材质、资源进行静态对照。项目配置为 Minecraft 1.21.1 / NeoForge 21.1.236；本报告的原版依据是用户指定的本地 `.refs`。本次仅新增检查报告，未修改运行代码。

## 已一致的部分

- `cherry_0.png` 至 `cherry_11.png` 的 SHA-256 逐张一致。
- 零初速度、初始 roll 为 0、两档尺寸的 50/50 选择意图一致，但实际绘制尺寸有下述二倍差异。
- 重力 `0.00075 blocks/tick²` 换算成 `0.3 blocks/s²` 正确；无摩擦衰减对应原版 `friction = 1`。
- 横向加速度的 `2 * f^1.25`、每粒子固定的 0–60° 方向范围及单位换算正确，但采样时刻、积分和随机源不同。
- 寿命参数对应 300 tick / 常规 20 TPS 下的 15 秒，但时钟和寿命边界并非逐 tick 等价。
- 使用 OPAQUE、关闭混合、写入深度、纹理 alpha 小于 0.1 时裁剪，与原版材质的主要状态一致。
- 使用真实 lightmap；粒子颜色为白色、alpha 不随年龄渐隐；朝向相机和纹理 V 方向的约定一致。
- 实现了检测到向下碰撞时在写出前删除的意图，但检测本身不等价。

## 尚未对齐的行为

### 1. GPU 图形边长只有原版一半

原版 `SingleQuadParticle.renderRotatedQuad()` 的角点为 `±1`，再乘 `quadSize`。GPU `textured.vsh` 第 184 行使用 `quadCorner(...) - 0.5`，角点为 `±0.5`；第 205 行直接乘同样的尺寸，没有补偿二倍比例。

| quadSize / GPU size | 原版完整边长 | GPU 完整边长 |
| --- | --- | --- |
| 0.05 | 0.10 格 | 0.05 格 |
| 0.075 | 0.15 格 | 0.075 格 |

因此两档 GPU 叶片的边长均为原版 50%，面积为 25%。预设数值相同不意味着最终尺寸一致。

依据：`EmitterPresets.java:104`、`chunks/classic_spawn.glsl:80`、`textured.vsh:184`；原版 `SingleQuadParticle.java:50`。

### 2. 缺少碰墙后的销毁条件

原版 `CherryParticle.java:58` 不仅判断 `onGround`，还在 `lifetime < 299` 时判断 `xd == 0 || zd == 0` 并删除。即从第二次更新起，水平任一速度分量被碰撞清零也会删除。

GPU 的水平碰撞只清零速度；`update.comp:401` 只根据 `landed` 删除。叶片撞墙后可以继续下落或沿另一轴滑动，后续 flutter 也可以重新增加被清零的速度。

依据：`chunks/collision_sweep.glsl:165`、`update.comp:401`；原版 `CherryParticle.java:58`、`Particle.java` 的 `move()`。

### 3. 碰撞几何和世界更新精度不同

原版叶片有 `setSize(f, f)` 创建的有限体积 AABB，使用世界中的实际碰撞形状。GPU 精灵的 `marginXZ` / `marginYUp` 均为 0，按位置点探测；`CollisionBake` 将任何非空碰撞形状都烘焙为完整的一格实心体素。

这会改变墙边、台阶、半砖、栅栏等位置的接触时刻。GPU 普通碰撞体积为每片 48×32×48 格、最多 8 片，定期约每 20 game tick 异步重建；未建好或离开所有体积时无碰撞。原版没有这一局部烘焙覆盖限制，也不会将半砖统一当作完整方块。

依据：`CollisionBake.java:49`、`:368`，`chunks/collision_sweep.glsl:159`、`chunks/collision_queries.glsl:3`；原版 `CherryParticle.java:28`、`Particle.java` 的 `move()`。

### 4. 时间步、flutter 采样和插值不同

原版随游戏 tick 更新；暂停或世界冻结时 `Minecraft` 不调用 `particleEngine.tick()`。渲染时 `SingleQuadParticle` 对旧、新位置及 roll 做 partial-tick 插值。

GPU 的 `ParticleRuntime.clampDelta()` 使用 `getRealtimeDeltaTicks()`，限制到 0.001–0.25 秒，每次提交的渲染帧积分一次；通用叶片路径没有原版的暂停/冻结门控，也没有旧、新 tick 位置的插值。寿命随这套时间推进，不能保证暂停、变速和帧率变化下与原版一致。

原版先递减 lifetime，再用 `(300 - lifetime) / 300` 计算 flutter，所以第一 tick 的横向加速度非零。GPU 使用更新前的 `age / maxLife`，第一步的 flutter 为 0；即使强制 GPU 20 FPS，横向轨迹仍不相同。原版执行 300 次有效运动，第 301 次 tick 删除；GPU 在帧开始判断 `age >= maxLife`，并受浮点累加影响。

依据：`ParticleRuntime.java:1256`、`chunks/basic_motion.glsl:20`、`update.comp:258`、`:302`、`textured.vsh:205`；原版 `CherryParticle.java:42`、`SingleQuadParticle.java:32`、`Minecraft.java:1915`。

### 5. 空间光照固定在出生点

原版 `Particle.getLightColor()` 在绘制时根据粒子的**当前位置**取方块光和天空光。GPU 只在发射命令生成时用 `specSpawnLight()` 取出生点光照，保存在 `p2.w`，绘制一直使用它。

GPU 仍会读取随时间更新的 lightmap 纹理，所以并非完全冻结最终 RGB；冻结的是空间上的 blockLight / skyLight 等级。叶片由室外飘入室内、从火把旁落到暗处等场景会不同，15 秒寿命使这一差异更容易显现。

依据：`ParticleRuntime.java:1232`、`:1242`、`textured.vsh:155`；原版 `Particle.java` 的 `getLightColor()`、`SingleQuadParticle.java:56`。

### 6. 贴图选择分布不同

原版实际调用 `spriteSet.get(random.nextInt(12), 12)`。默认 `MutableSpriteSet.get(age, maxAge)` 的实现是整数计算 `age * (sprites.size() - 1) / maxAge`。

对默认 12 张贴图，随机输入 0–11 实际映射为：

```text
0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10
```

因此原版默认资源下 `cherry_0` 概率为 2/12，`cherry_1` 至 `cherry_10` 各为 1/12，`cherry_11` 不被选中。GPU 使用 `floor(hash * 12)`，设计上将全部 12 张作为候选，没有复现该整数映射。

此外 GPU atlas 读取模组命名空间中的复制贴图，没有读取原版的 `minecraft:particles/cherry_leaves.json` 和对应贴图；修改原版樱花粒子的资源包不会自动同步到 GPU 版本。

依据：`textured.vsh:165`、`ParticleAtlas.java:113`；原版 `CherryParticle.java:20`、`ParticleEngine.java:632`、`assets/minecraft/particles/cherry_leaves.json`。

### 7. 旋转积分缺少离散项

原版每 tick 先 `rotSpeed += spinAcceleration / 20`，再 `roll += rotSpeed / 20`。设初始转速为 `v0` 度/秒、加速度为 `a` 度/秒²，第 n tick、`t = n / 20` 时的理想公式为：

```text
rollVanilla(t) = v0*t + 0.5*a*t*t + a*t/40
rollGPU(t)     = v0*t + 0.5*a*t*t
```

GPU 少了 `a*t/40`；`a = +5`、t=15 秒时少转 1.875°，`a = -5` 时符号相反。原版还在两个 tick 的 roll 间线性插值，GPU 每帧用年龄计算连续二次函数。两者的初始转速/加速度档位正确，但并非精确等价。

依据：`textured.vsh:186`；原版 `CherryParticle.java:54`、`SingleQuadParticle.java:35`。

### 8. 没有原版雾效

原版 `particle.vsh` / `particle.fsh` 使用 `FogShape`、`FogStart`、`FogEnd`、`FogColor` 计算 `linear_fog`。GPU `textured.fsh` 的 OPAQUE 分支直接输出贴图乘颜色，没有上述雾参数或混色。

GPU 另外有配置控制的距离剔除（`textured.vsh:87`）；它不能代替原版的雾。水下、熔岩中、浓雾和远景场景的显示会不同。

## 数值与资源验证

执行了 12 对 PNG 的 SHA-256 对照，全部一致；也按实际公式独立复算了运动与旋转。下表固定相同的 flutter 方向为 0°，无碰撞，重力 -0.3，旋转正初速/正加速度，累计模拟 15 秒。采用理想双精度算术展示积分差异，忽略 Java/GLSL 的 float 舍入、随机源和渲染插值；不是实机 GPU 读回或视觉验收。

| 路径 | X 位移（格） | Y 位移（格） | roll（度） |
| --- | ---: | ---: | ---: |
| 原版，300 tick | 62.207154296 | -33.862500000 | 1014.375 |
| GPU 公式，20 FPS | 61.537986089 | -33.862500000 | 1012.500 |
| GPU 公式，60 FPS | 61.538410281 | -33.787500000 | 1012.500 |
| GPU 公式，144 FPS | 61.538452809 | -33.765625000 | 1012.500 |

没有执行客户端并排视觉验证、shaderpack 验证或专用 GPU 轨迹读回测试。当前 `src/test` 和 `scripts/particles` 中未找到 cherry 专用对齐测试；其他粒子的测试通过不能证明这些语义一致。

修复优先级建议：先处理尺寸、碰墙删除和时间步；再处理光照与雾；随后明确碰撞精度目标、复现贴图映射和离散旋转。尺寸/材质/碰撞都经过共享管线，实施时需限定行为范围，避免影响其他预设；碰撞和光照方案需遵守项目性能优先的原则。
