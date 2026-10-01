package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Historical comparison utility, outside the render/network hot paths. Further network
 * optimization and ratio acceptance are deferred for this release; this utility is not called
 * by runtime admission. It counts measured bytes only and never subtracts hypothetical traffic. */
public final class PackageNetworkComparison {
    public static final double MAX_RATIO=1.5;
    public enum Direction { UP, DOWN }
    public enum Coverage {
        MOTION, STATE, CONTENT, SPAWN_DESTROY, BASELINES, ACK_CONTROL, INTERACTIONS,
        MIGRATION_RECOVERY, OBSERVER_MEMBERSHIP, CHAIN_STATE, EXTERNAL_FORCE_SOURCES, TRANSPORT
    }
    public enum Scope { PROTOCOL_FRAMES, CONNECTION_WIRE }
    public enum Verdict { PASS, OVER_BUDGET, INCOMPLETE, WORKLOAD_MISMATCH }

    /** Scenario identifies the fixed trajectory/seed/camera/quality/world and subscriber set.
     * requiredActivePackageTicks excludes warmup and must be measured for the same window on
     * both paths. GPU exposure counts actual GPU simulation, never a Create fallback. */
    public record Workload(String scenario, int packages, int clients, long ticks,
                           long requiredActivePackageTicks) {
        public Workload {
            if (scenario == null || scenario.isBlank() || packages <= 0 || clients <= 0 || ticks <= 0
                    || requiredActivePackageTicks <= 0
                    || requiredActivePackageTicks > Math.multiplyExact((long) packages, ticks))
                throw new IllegalArgumentException("Network comparison workload");
        }
    }
    public record Measurement(Workload workload, Scope scope, long upBytes, long downBytes,
                              long activePackageTicks, Set<Coverage> coverage) {
        public Measurement {
            Objects.requireNonNull(workload); Objects.requireNonNull(scope);
            if (upBytes < 0 || downBytes < 0 || activePackageTicks < 0
                    || activePackageTicks > Math.multiplyExact((long) workload.packages(), workload.ticks()))
                throw new IllegalArgumentException("Network comparison measurement");
            Math.addExact(upBytes, downBytes);
            coverage = Set.copyOf(coverage);
        }
        public long totalBytes() { return Math.addExact(upBytes, downBytes); }
        public boolean complete() {
            return scope == Scope.CONNECTION_WIRE
                    && coverage.containsAll(EnumSet.allOf(Coverage.class))
                    && activePackageTicks >= workload.requiredActivePackageTicks();
        }
    }
    public record Result(Verdict verdict, long nativeBytes, long gpuBytes,
                         long excessBytes, double ratio) {
        public boolean passed() { return verdict == Verdict.PASS; }
    }
    /** The user's hard 3/2 ceiling is exact, including odd byte counts; no timing-noise allowance.
     * Incomplete evidence that already exceeds the reference remains visibly over budget. */
    public static Result compare(Measurement nativeCreate, Measurement gpu) {
        Objects.requireNonNull(nativeCreate); Objects.requireNonNull(gpu);
        long nativeBytes = nativeCreate.totalBytes(), gpuBytes = gpu.totalBytes();
        double ratio = nativeBytes == 0 ? (gpuBytes == 0 ? 1 : Double.POSITIVE_INFINITY)
                : (double) gpuBytes / nativeBytes;
        Verdict verdict;
        // Avoid overflowing 3*native or converting the exact integer comparison to double.
        long extra=gpuBytes>nativeBytes?gpuBytes-nativeBytes:0;
        long excess=Math.max(0,extra-nativeBytes/2);
        if (!nativeCreate.workload().equals(gpu.workload()) || nativeCreate.scope() != gpu.scope())
            verdict = Verdict.WORKLOAD_MISMATCH;
        else if (excess > 0) verdict = Verdict.OVER_BUDGET;
        else if (!nativeCreate.complete() || !gpu.complete()) verdict = Verdict.INCOMPLETE;
        else verdict = Verdict.PASS;
        return new Result(verdict, nativeBytes, gpuBytes, excess, ratio);
    }
    private PackageNetworkComparison() {}
}
