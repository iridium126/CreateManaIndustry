package com.iridium126.createmanaindustry.client.gpu.frame;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GpuWriteReservationsTest {
    @Test void wholeOutputIsAcceptedOrRejectedWithoutMovingThePrefix() {
        var writes=new GpuWriteReservations();writes.begin(8,12,257);
        assertEquals(255,writes.reserve(255,true).count());
        assertEquals(0,writes.reserve(3,true).count());
        var tail=writes.reserve(2,true);assertEquals(255,tail.first());assertEquals(2,tail.count());
        assertEquals(0,writes.reserve(1,false).count());writes.requireCommittable();
    }
    @Test void physicalNameReuseDoesNotReviveAnOldGenerationOrEpoch() {
        var writes=new GpuWriteReservations();writes.begin(1,10,256);var old=writes.appendDomain();
        writes.retire();writes.begin(2,11,256);
        assertThrows(IllegalStateException.class,()->writes.validate(old,11));
        assertThrows(IllegalStateException.class,writes::requireCommittable);
        writes.begin(3,12,256);var current=writes.appendDomain();writes.validate(current,12);
        writes.fail();assertThrows(IllegalStateException.class,writes::requireCommittable);
    }
    @Test void corruptRangesAndRetiredWritersAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new GpuWriteReservations.Range(1,0,Integer.MAX_VALUE,2,256));
        var writes=new GpuWriteReservations();assertThrows(IllegalStateException.class,()->writes.reserve(1,true));
        writes.begin(1,0,0);assertEquals(0,writes.reserve(1,true).count());writes.retire();
        assertThrows(IllegalStateException.class,writes::appendDomain);
    }
}
