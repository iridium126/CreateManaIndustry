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

`validateAllayAnimationGpu` compares GPU Allay part and held-item matrices with
the actual Minecraft `AllayModel`, checks the shared eye/feet/centre player table,
and exercises animation cadence, observation, death, compaction and candidate
isolation. `validateModelAnimationStorageGpu` additionally exercises stable MODEL
slots, the hard capacity, independent animation publication, aborted candidates,
retirement/reuse, package overwrites, real radix payloads and GPU write counters.
It also calls the production MODEL publication boundary while an emitter,
external compute program or no program is current, then checks pool admission,
draw counts and next-frame retention for ordinary, block and analytic Storm births.
Run the attack, navigation and MODEL/Iris suites alongside both tasks.

`benchmarkModelAnimationStorage` compares the complete storage/update path against
an explicitly retained pre-change shader snapshot, supplied with
`-PmodelAnimationBaseline=<particles directory>`. The default is
`build/model-animation-before/particles`; missing sources fail the task. It writes
`model-animation-storage.csv`, `model-animation-storage-samples.csv` and
`model-animation-storage-memory.csv`, with three alternating warmed trials and
separate animation/list/allocator costs. See the [Allay animation report](../../docs/benchmarks/allay-animation-2026-10-25.md)
for allocation costs, measurement scope and remaining in-game acceptance.

Use `python scripts/benchmarks/run.py <task> <Gradle flags>` to capture the command,
exit status, actual driver log and samples in an independent `build/benchmarks/`
directory. Direct Gradle runs default to `build/benchmarks/current/`; override
with `-PbenchmarkOutput=build/benchmarks/<experiment>`. Historical raw data,
logs and screenshots are not tracked. See [result policy](../../docs/benchmarks/README.md)
and the [test matrix](../../docs/testing.md).

## Shader startup and resource reload

```powershell
.\gradlew.bat validateParticleStartupGpu validateParticleProgramsGpu --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
.\gradlew.bat validateParticleStartupGpu -PparticleStartupCold --offline --no-configuration-cache -I scripts/particles/validation.init.gradle -PbenchmarkOutput=build/benchmarks/particle-startup-cold
```

The startup harness calls the production incremental compiler with expanded
production sources, polls window events and clears the framebuffer while linking.
It checks atomic publication, unchanged-source reuse, compile/link failures,
in-flight cancellation and cleanup. Injected ERROR logs are expected. The cold
option changes only an unused macro in `update.comp`, using a unique value per
run; it does not delete the user's driver cache. Initial compilation can still
take minutes in the background. `-PparticleStartupProbe` separately measures cold
submission/completion waits for update, Dome contacts and Dome path shaders.
Both diagnostics require `GL_ARB_parallel_shader_compile`; unsupported drivers
must not be reported as passing the nonblocking contract. See the
[startup report](../../docs/benchmarks/particle-shader-startup-2026-10-23.md).


### Allay Storm

攻击、姿态、阴影、Iris、碰撞、资源及服务端验证统一见
[Allay Storm 验证说明](../../docs/allay-storm-validation.md)。
独立星柱使用 `validateStarfallColumnGpu`；`validateStarfallGpu` 保留共享旧路径、
圆环和未参战主体不漂向原点的生产核回归，不能因名称相近删除。
不要并发运行会重编译 main 输出的多个 Gradle／GPU 进程。
