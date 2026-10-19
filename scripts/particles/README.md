# Particle engine GPU validation

Run from the project root on a machine with an OpenGL 4.5 driver:

```powershell
.\gradlew.bat validateHexGpu --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```

The opt-in task creates a hidden GLFW window and executes the actual shaders.
It compares pigment results against Hexcasting's `ColorProvider`/`ADPigment`,
and animated path points against `RenderLib.makeZappy`, including overlapping
paths, negative player seeds, fades, and long-running clocks. It also checks
particle reconciliation, generation rejection, compaction, moving anchors,
and the MODEL/ALPHA/HEX_PATTERN depth partitions. No world is modified.

The validation-only init script supplies cached Kotlin stdlib to the compiler
because the workspace's separate Hex JIT sources import Kotlin directly.
It does not add a production dependency. Omit `--offline` if validation
dependencies are not already cached.

In-game checks still needed when changing rendering integration: local player
first/third-person switching, crouching, frozen ticks, pigment changes during a
cast, multiple players, shader packs, resource reload and dimension changes.
The default-enabled client setting is `particles.hexPatternRedirect`.
Oversized stacks/paths or unavailable GPU programs use the original renderer.


The suite also executes production emission, update, indirect dispatch, sorting,
staging-ring, identity-damage, wave retention and atomic program reload paths.
It checks empty/tail/saturated workloads, guards, output uniqueness, generation
retention, delayed consumption and idempotent cleanup. The expected ERROR during
reload testing is injected deliberately; the final result must be PASS.

For seeded sorting and CPU submission microbenchmarks (three warmed trials):

```powershell
.\gradlew.bat validateHexGpu --offline --no-configuration-cache -I scripts/particles/validation.init.gradle -PparticleBenchmark
```

Outputs: `build/benchmarks/current/particle-sort-benchmark.csv` and
`build/benchmarks/current/particle-submit-benchmark.csv`. Variant 0 is the reference implementation,
variant 1 is production. Sorting distribution 0 mixes 768 keys; 1 uses one key.
The normal task covers small workgroup boundaries; benchmark mode covers 0,
10,000, 100,000, 1,000,000 and 2,000,000 entries. Run both after algorithm changes.
These isolated measurements do not establish whole-game FPS improvements.
See [measurement report](../../docs/particle-engine-performance.md) for results,
memory costs, coverage and remaining in-game acceptance work.

## Result management

Starfall's standalone regression also compares the production update kernel
with attacks enabled/disabled far from world zero. Nonparticipants must retain
their ordinary home position and orbital velocity before, during and after an
attack, including aborts. Run without a Minecraft client:

```powershell
.\gradlew.bat validateStarfallGpu validateDomeGpu validateHelixGpu --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```

Use `python scripts/benchmarks/run.py <task> <Gradle flags>` to capture the command,
exit status, actual driver log and samples in an independent `build/benchmarks/`
directory. Direct Gradle runs default to `build/benchmarks/current/`; override
with `-PbenchmarkOutput=build/benchmarks/<experiment>`. Historical raw data,
logs and screenshots are not tracked. See [result policy](../../docs/benchmarks/README.md)
and the [test matrix](../../docs/testing.md).


### Starfall 单柱星雨

`validateStarfallColumnGpu` 使用生产update／contact／carrier／ring内核，覆盖64柱、4096名成员、槽67、不规则帧长环绕与完整生命周期、CPU／GPU下降接缝、实际集合与触地门控、区域伤害、旧代去重、半尺寸预警粒子和持剑阴影；不启动Minecraft客户端。任务同时输出 `starfall-64-columns.csv`，固定输入、三轮、每轮40次预热和120样本，仅表示update内核和CPU提交微基准，不表示游戏帧率。

```powershell
.\gradlew.bat -I scripts/particles/validation.init.gradle validateStarfallColumnGpu validateStarfallGpu validateDomeGpu validateHelixGpu validateModelPopulationGpu validateModelShadowGpu validateModelShadowIrisGpu --offline --no-configuration-cache --console=plain -PbenchmarkOutput=build/benchmarks/starfall-columns-final
```

当前参数、协议9迁移和用户实机清单见 [单柱星雨](../../docs/starfall-columns.md)。


### 风暴专用碰撞

`validateStormCollisionGpu` 使用生产页上载器与查询代码：20,000段、9,000混合段、共享空气／实心、负坐标与ALLVR高度、扩容、128KiB上载预算、版本替换／卸载，以及生产update中的主体／尸体墙地碰撞与未知区继续运动。`validateStarfallColumnGpu` 额外覆盖30／60／144／240Hz、不规则帧长及专用域台阶／墙体，逐成员完成下降、展开与回流。

```powershell
.\gradlew.bat -I scripts/particles/validation.init.gradle validateStormCollisionGpu validateStarfallColumnGpu --offline --no-configuration-cache --console=plain -PbenchmarkOutput=build/benchmarks/storm-v9-final
```

不要同时运行会重新编译main输出的多个Gradle进程与这些GPU任务；独立Java验证进程仍需持续读取main类目录。性能与实机边界见 [协议9记录](../../docs/allay-storm-v9.md)。
