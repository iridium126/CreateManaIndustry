# Withdrawn feather candidates

Validation only; no source here belongs in a runtime JAR.

`unconditional-overlay-optimizer.java.txt` freezes the attempted additional
program optimizer. `FeatherExperimentalSources.java` supplies the attempted
thin vertex adapter. `prepareFeatherMergeReference` generates renamed classes
for the standalone harness. Use `-PfeatherVariant=thin` or `indexed` only with
the feather validation tasks. Both candidates failed the replacement gate.

The accepted shared-program branch candidate is the default and does not
compile either experimental class at runtime. Early experiment measurements
used a checker background in unused atlas space; the final fixture clears it
to transparent, so old and new measurements are not interchangeable.

See `docs/benchmarks/feather-merge-client-log-2026-10-31.md` and the retained raw
records for regressions and loading costs. Do not promote local stage wins to
whole-frame or client performance claims.
