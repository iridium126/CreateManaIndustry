package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageNetworkComparison.*;

class PackageNetworkComparisonTest {
    private static final Workload WORK = new Workload("active-divergent-seed-7-no-shaders", 131072, 1, 60, 131072L * 60);
    private static Measurement full(long up, long down) {
        return new Measurement(WORK, Scope.CONNECTION_WIRE, up, down,
                WORK.requiredActivePackageTicks(), EnumSet.allOf(Coverage.class));
    }
    @Test void bothDirectionsAndAllTrafficCountWithoutNoiseAllowance() {
        var nativeCreate = full(10, 90);
        assertEquals(Verdict.PASS, compare(nativeCreate, full(75, 25)).verdict());
        assertEquals(Verdict.PASS, compare(nativeCreate, full(74, 25)).verdict());
        assertEquals(Verdict.PASS, compare(nativeCreate,full(75,75)).verdict());
        var over = compare(nativeCreate, full(75, 76));
        assertEquals(Verdict.OVER_BUDGET, over.verdict());
        assertEquals(1, over.excessBytes()); assertEquals(1.51, over.ratio());
        assertFalse(over.passed());
    }
    @Test void everyOmittedTrafficFamilyPreventsAcceptanceEvenAtZeroBytes() {
        for (var omitted : Coverage.values()) {
            var measured = EnumSet.allOf(Coverage.class); measured.remove(omitted);
            var gpu = new Measurement(WORK, Scope.CONNECTION_WIRE, 0, 0, WORK.requiredActivePackageTicks(), measured);
            assertEquals(Verdict.INCOMPLETE, compare(full(1, 99), gpu).verdict(), omitted.toString());
            assertEquals(Verdict.INCOMPLETE, compare(gpu, full(0, 0)).verdict(), omitted.toString());
        }
    }
    @Test void framedComponentsCannotPassEvenWhenRetainedPredictedStreamFits() {
        var coverage = EnumSet.of(Coverage.MOTION, Coverage.ACK_CONTROL);
        var nativeCreate = new Measurement(WORK, Scope.PROTOCOL_FRAMES, 0, 33748520, WORK.requiredActivePackageTicks(), coverage);
        var retained = new Measurement(WORK, Scope.PROTOCOL_FRAMES, 9647704, 33748520 + 4319, WORK.requiredActivePackageTicks(), coverage);
        assertEquals(Verdict.INCOMPLETE, compare(nativeCreate, retained).verdict());
        var relative=new Measurement(WORK,Scope.PROTOCOL_FRAMES,39045106,33748520+4319,WORK.requiredActivePackageTicks(),coverage);
        assertEquals(Verdict.OVER_BUDGET,compare(nativeCreate,relative).verdict());
        var hypothetical = new Measurement(WORK, Scope.PROTOCOL_FRAMES, 9647704, 4319, WORK.requiredActivePackageTicks(), coverage);
        assertEquals(Verdict.INCOMPLETE, compare(nativeCreate, hypothetical).verdict());
    }
    @Test void fallbackDisabledTakeoverAndStaticSubstitutesCannotCertifyActiveTarget() {
        for (long exposure : new long[]{0, 1, WORK.requiredActivePackageTicks() - 1}) {
            var gpu = new Measurement(WORK, Scope.CONNECTION_WIRE, 0, 0, exposure, EnumSet.allOf(Coverage.class));
            assertEquals(Verdict.INCOMPLETE, compare(full(0, 100), gpu).verdict());
        }
    }
    @Test void differentWindowSubscribersOrScopeCannotBeCompared() {
        for (var work : new Workload[]{new Workload("other-scene",131072,1,60,131072L*60),
                new Workload(WORK.scenario(),131072,4,60,131072L*60),
                new Workload(WORK.scenario(),131072,1,61,131072L*60)}) {
            assertEquals(Verdict.WORKLOAD_MISMATCH, compare(full(0,100),
                    new Measurement(work,Scope.CONNECTION_WIRE,0,0,work.requiredActivePackageTicks(),EnumSet.allOf(Coverage.class))).verdict());
        }
        assertEquals(Verdict.WORKLOAD_MISMATCH, compare(full(0,100),
                new Measurement(WORK,Scope.PROTOCOL_FRAMES,0,0,WORK.requiredActivePackageTicks(),EnumSet.allOf(Coverage.class))).verdict());
    }
    @Test void zeroNativeBytesPermitOnlyZeroGpuBytesWithCompleteEvidence() {
        assertTrue(compare(full(0,0),full(0,0)).passed());
        var over = compare(full(0,0),full(0,1));
        assertEquals(Verdict.OVER_BUDGET, over.verdict()); assertEquals(Double.POSITIVE_INFINITY, over.ratio());
    }
    @Test void oddAndMaximumByteCountsUseAnExactOverflowSafeCeiling() {
        assertTrue(compare(full(0,3),full(0,4)).passed());
        assertEquals(1,compare(full(0,3),full(0,5)).excessBytes());
        assertTrue(compare(full(0,Long.MAX_VALUE),full(0,Long.MAX_VALUE)).passed());
        long nativeBytes=Long.MAX_VALUE/3*2;
        assertTrue(compare(full(0,nativeBytes),full(0,nativeBytes+nativeBytes/2)).passed());
        assertEquals(1,compare(full(0,nativeBytes),full(0,nativeBytes+nativeBytes/2+1)).excessBytes());
    }
    @Test void rejectNegativeCountsOverflowAndMutableCoverage() {
        assertThrows(IllegalArgumentException.class,()->full(-1,1));
        assertThrows(ArithmeticException.class,()->full(Long.MAX_VALUE,1));
        assertThrows(ArithmeticException.class,()->new Workload("overflow",131072,1,Long.MAX_VALUE,1));
        assertThrows(IllegalArgumentException.class,()->new Workload("",1,1,1,1));
        assertThrows(IllegalArgumentException.class,()->new Measurement(WORK,Scope.CONNECTION_WIRE,0,0,WORK.requiredActivePackageTicks()+1,Set.of()));
        var mutable=EnumSet.allOf(Coverage.class);
        var gpu=new Measurement(WORK,Scope.CONNECTION_WIRE,0,100,WORK.requiredActivePackageTicks(),mutable);mutable.clear();
        assertTrue(compare(full(0,100),gpu).passed());
    }
}
