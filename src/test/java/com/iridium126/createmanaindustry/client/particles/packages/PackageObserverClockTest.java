package com.iridium126.createmanaindustry.client.particles.packages;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PackageObserverClockTest {
    @Test void arrivalJitterDoesNotRemapStoredStateAndQueueDelayCounts() {
        var clock=new PackageObserverClock();long tick=9_000_000_000_000_000L,nanos=100_000_000;
        clock.observe(tick,nanos,20_000_000);float receipt=clock.receipt(tick);
        assertEquals(16,receipt);assertEquals(15.95f,clock.receipt(tick-1));
        assertEquals(16.05f,clock.now(nanos+30_000_000),.00001f);
        clock.observe(tick+1,nanos+50_000_000,30_000_000);
        assertEquals(receipt,clock.receipt(tick));assertEquals(16.08f,clock.now(nanos+50_000_000),.00001f);
        clock.observe(tick+1,nanos+51_000_000,0);
        assertEquals(16.08f,clock.now(nanos+51_000_000),.00001f);
        assertTrue(clock.now(nanos+151_000_000)-clock.receipt(tick+1)>.1f);
    }
    @Test void oldStationaryBaselineAndNanoTimeWrapAreBounded() {
        var clock=new PackageObserverClock();clock.observe(10000,Long.MAX_VALUE-10,0);
        assertEquals(0,clock.receipt(0));assertEquals(16,clock.now(Long.MIN_VALUE+20));
        clock.observe(10000,Long.MIN_VALUE+20,0);assertEquals(16,clock.now(Long.MIN_VALUE+20));
    }
    @Test void futureStateClockReversalAndPrecisionExhaustionRequireRenewal() {
        var clock=new PackageObserverClock();assertThrows(IllegalStateException.class,()->clock.now(0));
        clock.observe(1000,0,0);
        assertThrows(IllegalArgumentException.class,()->clock.receipt(1001));
        assertThrows(IllegalArgumentException.class,()->clock.observe(999,1,0));
        assertThrows(IllegalArgumentException.class,()->clock.observe(1000,-1,0));
        assertThrows(IllegalArgumentException.class,()->clock.now(-1));
        assertThrows(IllegalStateException.class,()->clock.now(3_600_000_000_000L));
        assertThrows(IllegalArgumentException.class,()->clock.observe(1000,1,-1));
    }
}
