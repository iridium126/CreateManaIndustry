# GPU world-section prefetch benchmark (2026-10-01)

## Setup

Measured on an NVIDIA GeForce RTX 4070 Laptop GPU, OpenGL 4.5, NVIDIA driver 581.15. Each size used 30 warm-up submissions followed by three runs of 60 samples. Sizes were 10,000, 65,536 and 131,072 free-body records. Bodies were distributed through a small, fully resident synthetic 3×3×3 section fixture; the .5-second swept bounds remained covered, so this measures the scan and asynchronous usage/handback feedback without emitting missing-section requests. It does not measure Minecraft world capture, dense contacts, package drawing, network work, or full-frame time.

The GPU timer includes clearing the request header, 512-byte atlas-row usage bitmap and 16,384-byte free-body handback bitmap; dispatching the 64-lane-per-workgroup sweep scan; marking touched atlas rows; and copying the fixed 82,448-byte result into the independent readback ring. CPU capture is Java/OpenGL submission time; CPU poll includes the zero-timeout fence poll, request de-duplication, atlas-row-to-section resolution, refreshing both cache protection sets, and scanning the handback bitmap. Each table entry is the median of the three runs' per-run p50 or p95. Values are milliseconds.

| Bodies | GPU p50 | GPU p95 | CPU capture p50 | CPU capture p95 | CPU poll p50 | CPU poll p95 |
|---:|---:|---:|---:|---:|---:|---:|
| 10,000 | 0.0379 | 0.0481 | 0.0052 | 0.0205 | 0.0116 | 0.0316 |
| 65,536 | 0.0379 | 0.0512 | 0.0026 | 0.0083 | 0.0095 | 0.0229 |
| 131,072 | 0.0369 | 0.0553 | 0.0021 | 0.0071 | 0.0087 | 0.0172 |

The updated safety pass remains a small component in the fully covered synthetic case at the planned 131,072-body capacity. This run adds a 16 KiB per-body handback bitset and decodes it; the older active-section-only numbers are not an apples-to-apples baseline. The timer's short duration is near the driver's resolution, so microsecond differences should not be treated as stable scaling trends. This is a component measurement and gives no evidence that the complete frame or active-package workload meets the 16.7 ms target.

## Correctness and limits

The separate real-GL validation exercises zero input, request generation for an uncovered section, workgroup-tail handling, per-workgroup request de-duplication, exact touched-section feedback at 131,072 bodies, previous solver handback sentinels, a revoked collision-table view, and skipping capture when all four staging slots are pending. Its overflow fixture emits 4,097 logical requests across 64 distinct missing sections repeated in separate 64-thread workgroups: the bounded result retains the 64 unique section keys, records one overflow, and marks only the body whose request exceeded the 4,096-entry capacity for local handback. A stale view marks only live package bodies; retired and prepared records remain ignored. Java tests validate the bounded result header, all retained records and body indices before dispatching owner-thread work, plus ensure full live cache sets refuse new demand instead of evicting their active collision sections. Both checks passed on the same GPU/driver with the project validation command.

The pass asks for at most one missing section per body and de-duplicates within each 64-body workgroup. It also marks every resident section intersected by each valid body sweep in a fixed bitmap indexed by the immutable world-table row. The same snapshot carries a 131,072-body handback bitset. A body is marked when the 0.15-second safety sweep reaches uncaptured geometry, its world view is unavailable, its physics state already contains the solver's local handback sentinel, or its unique prefetch request cannot fit in the bounded output. The render thread resolves the body index through the active acquisition map, queues RELEASE for only that package, and leaves the frozen pose drawable under the internal `HANDBACKABLE` flag until the retired pool generation commits. Stale table feedback cannot pin rows after atlas replacement. A current feedback snapshot replaces the CPU-cache and GPU-atlas protected sets. Demand LRU may recycle unprotected entries; if the entire atlas is protecting active sections, it rejects the new upload. Requests cross a four-slot, nonblocking readback ring and only enqueue numeric section identities; the existing client-tick budget remains the only path that reads mutable Minecraft world state. A full ring skips a scan; it never waits. If no current atlas view exists, the free physics step is skipped while the asynchronous handback scan is submitted.

The collision cache is capped at 1,024 sections and the GPU atlas at 256. Current GPU sweep feedback protects resident sections used by live free packages; the LRU can recycle only sections absent from the last successfully decoded usage set, and atlas payload slots remain retired until all table references pass their fences. Admission coverage re-requests missing or CPU-ready-but-not-uploaded sections, so an earlier atlas-capacity miss is retryable. Capture requests do not yet sort by time-to-contact; when active use exceeds either fixed capacity, new coverage is refused and Create remains responsible for the rejected package. The 0.5-second lookahead and 0.15-second safety horizon at 20 Hz have not been validated in a real game recording or through Iris/shadow rendering.

Run the component benchmark with:

```powershell
.\gradlew.bat validatePackageGpu --offline --no-configuration-cache -I scripts/particles/validation.init.gradle -PpackageWorldPrefetchBenchmark
```

The raw run summaries and per-sample data are emitted under `build/package-world-prefetch-benchmark.csv` and `build/package-world-prefetch-benchmark-samples.csv`.
