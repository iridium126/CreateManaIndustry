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

Outputs: `build/particle-sort-benchmark.csv` and
`build/particle-submit-benchmark.csv`. Variant 0 is the reference implementation,
variant 1 is production. Sorting distribution 0 mixes 768 keys; 1 uses one key.
The normal task covers small workgroup boundaries; benchmark mode covers 0,
10,000, 100,000, 1,000,000 and 2,000,000 entries. Run both after algorithm changes.
These isolated measurements do not establish whole-game FPS improvements.
See [measurement report](../../docs/particle-engine-performance.md) for results,
memory costs, coverage and remaining in-game acceptance work.
