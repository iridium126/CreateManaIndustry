# Mixed free/chain package GPU microbenchmark (2026-09-30)

`PackageMixedPhysicsGpu` runs free-body contacts and Create-style chain motion in independent GPU domains. A fixed chain body offset preserves indices while either domain grows. After both steps, five GPU buffer copies publish bodies, chain links and interpolation history into the inactive combined bank; a successful publication flips the bank. The combined buffers feed the existing one-slot-per-package pool importer and free-package delta detector. This does not yet enable live Create entity takeover.

Run on NVIDIA GeForce RTX 4070 Laptop GPU, driver OpenGL 4.5.0 NVIDIA 581.15, with `gradlew benchmarkPackageMixed --offline --no-configuration-cache -I scripts/particles/validation.init.gradle`. Each population has half free and half chain packages, 30 warmup steps then 40 measured 20 Hz steps, repeated three times. Free bodies occupy a sparse grid with no active pair contacts; chain anchors occupy an independent sparse grid. Each measured step times free simulation, chain simulation and combined publication separately with GL elapsed-time queries. CPU submit time ends before `glFinish`. The rows below are medians of the three runs' p95 values.

| Packages | Free GPU p95 | Chain GPU p95 | Publish GPU p95 | Sum GPU p95 | CPU submit p95 |
|---:|---:|---:|---:|---:|---:|
| 10,000 | 0.170 ms | 0.011 ms | 0.172 ms | 0.344 ms | 0.038 ms |
| 65,536 | 0.480 ms | 0.030 ms | 0.276 ms | 0.782 ms | 0.035 ms |
| 131,072 | 0.855 ms | 0.042 ms | 0.387 ms | 1.266 ms | 0.007 ms |

The 131,072-case combined banks reserve 40 MiB, excluding both solver domains and the common particle pool. The submitted copies transfer about 16 MiB per 20 Hz step. The p95 sum is the sum of three per-sample GPU timings; it excludes the importer, draw, collision section uploads, dynamic geometry, networking, game tick and Iris/shadow passes. Sparse free bodies do not exercise dense contact quality or cost. The first-run CPU p95 was 0.018 ms at 131,072; this microbenchmark's very low submission times do not include model lookup, Create lifecycle work or buffer preparation. It cannot establish the requested whole-frame 16.7 ms target.

The full raw data is in [summary CSV](package-mixed-2026-09-30.csv) and [per-step samples](package-mixed-samples-2026-09-30.csv.gz). `validatePackageGpu` also checks workgroup-boundary append, stable mixed indices, double-bank publication, delta detection and common-pool box/rig admission on the real GPU. A separate full-reservation fixture verifies body index 131072 in both the pool importer and delta detector while the total active population remains capped at 131072.
