# Frozen MODEL merge reference

Origin: Git `cfd12512a3cff878c69ca1049e6409619b9cca08`, captured before optimization on 2026-10-30.

The compiler snapshot is compiled only into the validation harness, under the name `FrozenModelProgramCompiler`. It is not a runtime alternative or distributed dependency. The harness checks that its generated vertex input matches the production ABI, then compares the frozen merge with `ModelShaderOptimization`.

The material snapshot records the original preprocessing, overlay and Iris transform procedure. The current `ModelMaterialGpuValidation.java` remains unchanged and supplies those helpers.

SHA-256 of UTF-8 text normalized to LF:

| Snapshot | SHA-256 |
|---|---|
| `ShaderPackProgramCompiler.java.txt` | `fbf7cd930dd4953e6637300b7d7eae48e3a7aafd3647d19333620a3bce93fcd1` |
| `ModelMaterialGpuValidation.java.txt` | `ddec08af97186ed0c4aa4e703e6e1c08155e54239c39ff2532ed65f74390afa6` |

Production GLSL chunks, geometry and atlas inputs are shared deliberately to isolate merge optimization from animation, textures, simulation and the buffer ABI. Third-party shaderpacks remain local inputs in `run/shaderpacks`.
