# Particle engine GPU validation

R7 client backend switch fix: `docs/benchmarks/gpu-neighbor-r7-capacity-fix-20261101.md`.
Use the `capacity-fix` diagnostic distribution; the earlier client integration
left late-compiled update programs at `uCapacity=0`. Regression replay uses
`validateR7RealContinuationGpu -Pr7ClientBindings=true` to clear that uniform
and restore it through the actual client pool binding before dispatch.

R7 continuation delivery (2026-11-01): see
`docs/benchmarks/gpu-neighbor-r7-20261101.md` for implementation, correctness,
costs and failures, and `docs/benchmarks/gpu-neighbor-r7-client-20261101.md` for
installation and actual-client commands. This supersedes the historical R5/R6
admission/capture limitations below: qualified diagnostic distributions expose
explicit hardware backends; ordinary builds keep production only. Full capture
is now schema 3 GPU-command-chain, and contiguous query sequences now include
actual ordinary and post-MODEL Dome query boundaries. Older captures retain
their declared scope. No client FPS gain or production replacement is claimed.

```powershell
python scripts/particles/validate_chain_capture.py '<capture-directory>'
python scripts/particles/prepare_r7_actual_sequence.py '<sequence-directory>' '<new-output>' --stage dome-solve --queries 128
.\gradlew.bat -I scripts/particles/validation.init.gradle validateR7SequenceGpu '-Pr7Inputs=<new-output>' -Pr7Samples=64 '-PbenchmarkOutput=<fresh-results-directory>' --no-configuration-cache
python scripts/particles/summarize_r7_chain.py build/benchmarks/neighbor-r7-continuation-20261101/chain-measurements-v2
python scripts/particles/summarize_r7_copy_chain.py build/benchmarks/neighbor-r7-continuation-20261101
```

The client ZIP contains the optional locked native runtime and installer. The
development receipt overlay is opt-in with `scripts/particles/r7-client.init.gradle`;
do not place the distribution JAR in development `run/mods`. Do not run the client
or multiple GPU validations concurrently with these measurements.

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

## Read-only attack experiments R4 (2026-10-31)

The [R4 report](../../docs/benchmarks/shader-efficiency-r4-2026-10-31.md)
records rejected compute/draw candidates. No new runtime shader optimization
was retained. Do not combine their local improvements with earlier accepted results.
The immutable `.local/particle-baselines/reference/shader-efficiency-20261031-r4/`
snapshot has a mandatory manifest; the evidence archive also contains its full inputs.
Use new output directories for every run.

```powershell
python scripts/particles/prepare_shader_efficiency_candidates.py .local/particle-baselines/reference/shader-efficiency-20261031-r4 build/benchmarks/r4-reproduction-candidates
./gradlew.bat --offline --no-configuration-cache -I scripts/particles/validation.init.gradle validateShaderEfficiencyUpdateGpu -PefficiencyBaseline=.local/particle-baselines/reference/shader-efficiency-20261031-r4/src/main/resources -PefficiencyCandidate=build/benchmarks/r4-reproduction-candidates/update '-PefficiencyCounts=1000,65536' '-PefficiencyScenes=column,recovery' -PefficiencyFull=true -PefficiencySamples=64 -PbenchmarkOutput=build/benchmarks/r4-reproduction-recovery
python scripts/particles/run_readonly_draw_screen.py .local/particle-baselines/reference/shader-efficiency-20261031-r4/src/main/resources build/benchmarks/r4-reproduction-candidates/draw build/benchmarks/r4-reproduction-draw
python -m unittest discover -s scripts/particles -p test_update_efficiency.py
```

Use `--draw-mode carry` with a separate candidate directory to reproduce the
narrower withdrawn carry experiment. `efficiencyCandidate` can select `contacts`
instead of `update`. Omitting `efficiencyFull` measures kernels only; full mode
adds the production contact, depenetration, navigation, feedback and publication
chain. The `recovery` case first generates a real column snapshot and checks
that it contains valid state before replay. Each invocation is an independent
process; use three A/B and three A/A runs (candidate path equal to baseline for
A/A), with 64 samples per cell. `summarize_update_efficiency.py --help` describes
the timing gate and explicit expected-cell JSON. Missing groups/samples, failed
equivalence or kernel-only data cannot pass. Loading/resources/client acceptance
are additional gates. The known Dome-gather A/A mismatch is retained as a failure.

Detailed profiling exports active session snapshots under
`run/logs/cmi-particle-profiles/`. For the R4 draw harness, pass
`-PreadonlyDraw`, `-PreadonlyBaseline=<frozen resource root>`,
`-PreadonlyCandidate=<candidate draw root>` and optionally
`-PdrawSnapshots=<snapshot directory>` to `validateShaderEfficiencyGpu` or
`benchmarkShaderEfficiencyGpu`. Snapshots replay option values and environment
macros, and require matching pack/source/effective-option hash/main-world context
before native attachment formats are used for performance measurements. Without
snapshots, the fixture remains synthetic FP32 MRT. Correctness comparisons always
use strict FP32 MRT. `drawRounds` and `drawSamples` control sampling; three separate
processes with `drawRounds=1`, `drawSamples=64` provide independent rounds.

`validateNativeAttachmentReplayGpu` checks effective option/macro replay, mismatch
rejection, RGBA8/RGBA16F and D24S8 allocation using an explicitly synthetic test
snapshot. It is not client capture evidence. Querying profile/status does not
export snapshots or start compilation.

### Neighbor R5: complete-query experiments and whole-client captures

`/cmi particle profile frames 30` collects whole runTick intervals, CPU wall submission
before display exchange, and non-blocking whole-frame GPU timestamps. Close chat/menus
and keep dimension, window size, shader options, VSync and limiter fixed. JSON/CSV pairs
are written under `run/logs/cmi-particle-frames`; invalid/short windows cannot pass the
client gate. Delayed particle counts retain their source frame and generation; `-1`
means unavailable. Contract stages are CPU packet stages, not inferred GPU member states.

`/cmi particle profile capture photon_dense` freezes bound update inputs asynchronously
under `run/logs/cmi-neighbor-inputs`. These are instrumented samples; overlapping frame
windows are invalidated. A committed manifest includes SSBOs, samplers/images, uniforms,
generations and file hashes. Above 512 MiB, the entire capture is rejected. Replay with:

```powershell
python scripts/particles/replay_neighbor_capture.py run/logs/cmi-neighbor-inputs/<capture> build/replay-new --radius 1 --queries 64
```

The replay tool supplies a complete conservative candidate oracle with bounded batches;
it does not implement production attack/contact/event replay. `--contact` excludes corpses;
ordinary separation retains them. Use the actual radius/sweep horizon required by the rule.

The isolated native experiment is optional and never enters the ordinary Gradle graph:

```powershell
./scripts/particles/build-neighbor-rt.ps1
./gradlew.bat -I scripts/particles/validation.init.gradle validateNeighborQueryGpu --no-configuration-cache
./gradlew.bat -I scripts/particles/validation.init.gradle validateNeighborQueryGpu -PneighborBenchmark --no-configuration-cache
./gradlew.bat -I scripts/particles/validation.init.gradle validateNeighborCaptureGpu --no-configuration-cache
```

MSVC x64/CMake/JDK are required. Checksum-locked OptiX 9.0 and CUDA 12.8 Update 1
NVRTC/support headers are cached in `.gradle/neighbor-rt`; no driver/global install occurs.
Repeat benchmark invocations in three separate processes. Raw output is UUID named under
`build/benchmarks/neighbor-r5`. GL grid, CUDA grid, OptiX build and refit are complete radius
queries with explicit capacity failure. Native output is temporary; no live physics backend
or production switch is exposed. `summarize_neighbor_queries.py` only grants a query screen.

`prepare_complete_neighbor_reference.py` generates an isolated streaming GL reference with
no visit cap, hash-cell duplicate filtering and stable identity contact ties.
`validateNeighborCompleteUpdateGpu` measures initial grid preparation, staged preparation,
recovery, ordinary/MODEL updates, contacts, depenetration, navigation and feedback. It also
records the existing production A/A state differences, without requiring new trajectories
to reproduce old truncation. One-frame invariants are preliminary; no multi-frame or visual
qualification is claimed. Run one Gradle process at a time against the shared build directory.

`prepare_neighbor_clock.py` / `validateNeighborClockGpu` instrument detached production
fixtures through an unused image unit. Ticks are invocation-local, categories may nest,
and outputs/register pressure differ. Never convert them to milliseconds or FPS.
`compare_client_frames.py` checks three independent controlled A/A and A/B rounds,
30 seconds/1,000 frames per window, matched context and p50/p95/p99 thresholds; it cannot
grant physics/visual qualification. Current production captures identify as `production-opengl`.

R5 observations, rejection reasons and the remaining client tests are documented in
`docs/benchmarks/neighbor-r5-2026-10-31.md`. Production shaders and default backend remain
unchanged because no candidate passed all replacement gates.

### R6 real-capture experiments (production unchanged)

See `docs/benchmarks/neighbor-r6-2026-10-31.md` for results and the explicit remaining
qualification gaps. Generate isolated unified candidates from the frozen R6 baseline:

```powershell
python scripts/particles/prepare_neighbor_r6.py build/benchmarks/neighbor-r6/baseline build/benchmarks/neighbor-r6/my-candidates
.\gradlew.bat -I scripts/particles/validation.init.gradle validateR6CaptureReplayGpu -Pr6Candidates=build/benchmarks/neighbor-r6/my-candidates -Pr6Variant=grid100 -PbenchmarkOutput=build/benchmarks/neighbor-r6/my-update-run --no-configuration-cache
.\gradlew.bat -I scripts/particles/validation.init.gradle validateR6FullChainGpu -Pr6Candidates=build/benchmarks/neighbor-r6/my-candidates -Pr6Variant=grid100 -PbenchmarkOutput=build/benchmarks/neighbor-r6/my-full-run --no-configuration-cache
```

The first task restores actual update inputs, GENERIC then MODEL; the second is a
synthetic full-chain fixture. Never merge their scopes or overwrite output paths.
Use `-Pr6Trace=true -Pr6Samples=0` for an instrumented query run, then
`validate_r6_query_traces.py` (FP64 force reference) and
`validate_r6_candidate_sets.py` (sampled exact sets and raw duplicate counts).
`prepare_r6_capture_clock.py` is localization only; its clock outputs are not force
traces or timing samples. Native GPU prototypes load only in explicit offline tasks.

Client `profile frames` now exports tagged stage/submission sidecars. New command
`/cmi particle profile querysequence <name> <frames>` accepts 16–128 frames and is
mutually exclusive with FPS and single-input captures. Validate the entire segment:

```powershell
python scripts/particles/r6_query_sequence.py run/logs/cmi-particle-query-sequences/NAME-UUID
python scripts/particles/export_r6_native_inputs.py run/logs/cmi-particle-query-sequences/NAME-UUID build/benchmarks/neighbor-r6/my-sequence --sequence
.\gradlew.bat -I scripts/particles/validation.init.gradle validateR6NativeReplayGpu -Pr6NativeInputs=build/benchmarks/neighbor-r6/my-sequence -PbenchmarkOutput=build/benchmarks/neighbor-r6/my-native-run --no-configuration-cache
```

Periodic RT replay requires contiguous scene frames and generations with the same
stable membership. Missing frames invalidate the segment; separated snapshots are
static tests. Current compact sequence records are read-pool geometry, not all
locally recovered query/physical state. Schema-2 single captures also declare their
limited boundary coverage explicitly; real complete-chain capture is still pending.
No experimental client backend is exposed because no candidate passed the gates.


### R8 dedicated hardware handoff and full actual queries

Production GLSL and the default backend remain unchanged. See
`docs/benchmarks/gpu-neighbor-r8-20261101.md` and the R8 client guide for results,
qualification boundaries and the recovered original full capture.

```powershell
python scripts/particles/prepare_r7_actual_sequence.py CAPTURE OUTPUT --stage ordinary --queries all
python scripts/particles/prepare_r7_actual_sequence.py CAPTURE OUTPUT_DOME --stage dome-solve --queries all
.\gradlew.bat -I scripts/particles/validation.init.gradle validateR8SequenceGpu '-Pr8Sequence=OUTPUT' '-PbenchmarkOutput=FRESH_RESULTS' --no-configuration-cache
.\gradlew.bat -I scripts/particles/validation.init.gradle validateR8RealChainGpu '-Pr7Capture=FULL_CAPTURE' '-PbenchmarkOutput=FRESH_FULL_CHAIN_RESULTS' --no-configuration-cache
```

Full sequence exports retain variable Q, actual origins/radii/dt and stage, not a
cross-frame intersection. Geometry uses a common event wait to isolate algorithms;
`-Pr8ConsumerStrategyWait=true` instead compares legacy poll/park with each fixed
policy's event consumption. Never merge either scope with synthetic full physics
or client FPS. `run_r8_release.py`, `run_r8_measurements.py`,
`run_r8_completion.py`, `run_r8_notifications.py`, `run_r8_combinations.py` run
serial fresh-process regressions; existing output directories are never replaced.

ABI 8 binds native files, classes, source and fixed policy in the diagnostic
receipt. Page pool / CSR overflow selects a complete reference for the whole phase.
Detailed traces are versioned and unknown fields stay -1. New client variants are
admitted only when the actual full-chain gate passes; the client receipt lists exactly the qualified fixed policies. The R8 packager
without `--real-chain` delivers production capture tooling and offline candidates;
ordinary builds contain no admission or mandatory CUDA/OptiX dependency.
