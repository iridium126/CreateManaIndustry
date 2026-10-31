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

行为、资源、GPU／服务端及实机回归统一见
[悦灵风暴验证入口](../../docs/allay-storm-validation.md)。

```powershell
.\gradlew.bat validateStormLodGpu --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
.\gradlew.bat benchmarkStormLodGpu --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```

前者仅检查当前实现的成员身份／死亡／覆盖、硬切、透视尺寸和遮挡；
后者才采集计算、常驻OPAQUE基线、两档绘制与合成数据。
两者都不需要旧版源码或Git HEAD。已验收的固定像素对照与截图生成入口已移除，
性能选择保留在[LOD尺寸报告](../../docs/benchmarks/storm-lod-perspective-2026-10-28.md)。

Starfall两个工具分别覆盖独立星柱与共享旧路径，Dome、Helix、动画、阴影、
真实ShaderInstance及传送恢复各有独立覆盖；不能仅按名称相近删除。
不要并发启动重编译main输出的Gradle／GPU进程。

### MODEL client-log optimization (2026-10-30)

Use `validateModelDrawGpu`, `validateModelDrawTelemetryGpu`, and `validateModelNativeColorGpu` with `-I scripts/particles/validation.init.gradle --no-configuration-cache`.
`benchmarkModelDrawGpu` measures the whole indexed model and its three segments against the immutable delivered baseline. `-PmodelDrawQuick -PmodelDrawVariant=aa` measures identical-source noise. The production runtime reads none of these switches.
Run `python scripts/particles/summarize_model_draw.py OUTPUT --aa AA_OUTPUT --require-gate` to verify the full eight-pack matrix, three rounds, program counts, Photon heavy-load improvement and absence of stable regressions. See [the report](../../docs/benchmarks/model-draw-client-log-2026-10-30.md) for inputs and pending client acceptance.


## Client-log shader efficiency R2

Frozen inputs are local and ignored by Git; see [baseline locations and restore notes](baselines.md).

The immutable `.local/particle-baselines/reference/shader-efficiency-20261030-r2/` baseline includes the
previous staged keyframe change. It is checked before harness generation and
never enters the runtime JAR. Historical snapshots remain intact.

```powershell
./gradlew.bat -I scripts/particles/validation.init.gradle benchmarkShaderEfficiencyGpu -PbenchmarkOutput=build/benchmarks/shader-efficiency-covered --no-configuration-cache
./gradlew.bat -I scripts/particles/validation.init.gradle benchmarkShaderEfficiencyGpu -PmodelDrawVariant=aa -PbenchmarkOutput=build/benchmarks/shader-efficiency-covered-aa-full --no-configuration-cache
./gradlew.bat -I scripts/particles/validation.init.gradle benchmarkShaderEfficiencyGpu -PmodelDrawPack=solas -PbenchmarkOutput=build/benchmarks/shader-efficiency-solas-repeat --no-configuration-cache
./gradlew.bat -I scripts/particles/validation.init.gradle benchmarkShaderEfficiencyGpu -PmodelDrawPack=solas -PmodelDrawVariant=aa -PbenchmarkOutput=build/benchmarks/shader-efficiency-solas-repeat-aa --no-configuration-cache
./gradlew.bat -I scripts/particles/validation.init.gradle validateShaderEfficiencyGpu '-PmodelDrawPack=bsl,complementary' -PmodelLoadRounds=9 -PbenchmarkOutput=build/benchmarks/shader-efficiency-load --no-configuration-cache
./gradlew.bat -I scripts/particles/validation.init.gradle validateShaderEfficiencyGpu '-PmodelDrawPack=bsl,complementary' -PmodelDrawVariant=aa -PmodelLoadRounds=9 -PbenchmarkOutput=build/benchmarks/shader-efficiency-load-aa --no-configuration-cache
python scripts/particles/summarize_shader_efficiency.py build/benchmarks/shader-efficiency-covered --aa build/benchmarks/shader-efficiency-covered-aa-full --aa-extra build/benchmarks/shader-efficiency-solas-repeat-aa --load-aa-extra build/benchmarks/shader-efficiency-load-aa --repeat build/benchmarks/shader-efficiency-solas-repeat --require-gate
./gradlew.bat -I scripts/particles/validation.init.gradle validateUpdateDomainGpu -PbenchmarkOutput=build/benchmarks/update-domain-final --no-configuration-cache
./gradlew.bat -I scripts/particles/validation.init.gradle validateUpdateDomainGpu -PupdateDomainAa -PbenchmarkOutput=build/benchmarks/update-domain-final-aa --no-configuration-cache
```

Draw measurements now assert actual raster coverage for every input cell and
clear all particle lanes after LOD fixtures. The prior whole-model ~80% claim is
withdrawn; its harness allowed invalid emitter indices. R2 compares all MRT/depth
raw words using perspective projection, independent nonconstant sampler resources
and native shadow size/filter settings. A/A asserts identical stage sources and
covers the full A/B input matrix. It remains a synthetic fixture, not a recorded
world replay; option JSON explicitly reports client-hash mismatches.

`validateShaderEfficiencyGpu -PmodelDrawPack=bsl,complementary` also checks
advanced-material/custom-PBR variants at full-frame coverage. For warm load
rechecks use `-PmodelLoadRounds=9`; preserve these records separately from the
original matrix. `validateSimulationReadbackGpu` checks the production fenced
readback layout; `validateModelDrawTelemetryGpu` checks frame-window filtering.
The domain candidate is validation-only and has not replaced the runtime update
program; its two-dispatch screen excludes contacts/navigation/publication and
cannot establish complete-update gains. See the [R2 report](../../docs/benchmarks/shader-efficiency-client-log-r2-2026-10-30.md).

## Feather merge R3 (2026-10-31)

The immutable `.local/particle-baselines/reference/feather-merge-20261031/` snapshot includes the delivered
R2 changes and existing staged work. The default `branch` candidate keeps the
shared six-vertex entity program and specializes only proven zero-overlay
material branches in place. Eligibility follows AST structure, not pack names.

```powershell
python scripts/particles/extract_feather_client_profiles.py run/logs/debug.log build/benchmarks/feather-client-profiles.json
./gradlew.bat --offline --no-configuration-cache -I scripts/particles/validation.init.gradle benchmarkFeatherMergeGpu -PfeatherVariant=branch -PfeatherDistance=all -PfeatherModelBench -PbenchmarkOutput=build/benchmarks/feather-shared-final
./gradlew.bat --offline --no-configuration-cache -I scripts/particles/validation.init.gradle benchmarkFeatherMergeGpu -PfeatherVariant=branch -PfeatherDistance=all -PfeatherModelBench -PfeatherAa -PbenchmarkOutput=build/benchmarks/feather-shared-final-aa
./gradlew.bat --offline --no-configuration-cache -I scripts/particles/validation.init.gradle benchmarkFeatherMergeGpu -PfeatherVariant=branch -PfeatherDistance=6 -PmodelDrawPack=photon -PbenchmarkOutput=build/benchmarks/feather-shared-photon-repeat
./gradlew.bat --offline --no-configuration-cache -I scripts/particles/validation.init.gradle benchmarkFeatherMergeGpu -PfeatherVariant=branch -PfeatherDistance=6 -PmodelDrawPack=photon -PfeatherAa -PbenchmarkOutput=build/benchmarks/feather-shared-photon-repeat-aa
python scripts/particles/summarize_feather_merge.py build/benchmarks/feather-shared-final --aa build/benchmarks/feather-shared-final-aa --aa-extra build/benchmarks/feather-shared-photon-repeat-aa --repeat build/benchmarks/feather-shared-photon-repeat
./gradlew.bat --offline --no-configuration-cache -I scripts/particles/validation.init.gradle validateFeatherMergeGpu -PbenchmarkOutput=build/benchmarks/feather-final-validation
```

Both matrices include coverage checks, production feather atlas tiles with
transparent unused regions, actual instance data, all MRT raw words and depth.
`-PfeatherModelBench` adds whole-three-segment MODEL measurements. The initial
Photon same-program anomaly and independent rechecks are retained separately.
`-PfeatherLoadRounds=9` extends warm compile measurements; first-draw records are
not cold game frames. Client option hashes are read as data from the supplied
log; unmatched defaults are explicitly marked.

`-PfeatherVariant=indexed` and `thin` reproduce withdrawn validation experiments;
they are never runtime switches and their sources are never packaged. Early
experiment atlas backgrounds differ from the corrected final fixture. Do not
mix those results into the accepted benchmark. See the [R3 report](../../docs/benchmarks/feather-merge-client-log-2026-10-31.md)
for the small scope of measured benefits and pending client acceptance.
