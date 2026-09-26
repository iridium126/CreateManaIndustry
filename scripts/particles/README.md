# Hex pattern GPU validation

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
