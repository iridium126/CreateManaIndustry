package com.iridium126.createmanaindustry.client.gpu.profile;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GpuBudgetLedgerTest {
    @Test void disabledAccountingReusesScopesAndRetainsTheLastCapture() {
        var ledger=new GpuBudgetLedger();ledger.begin(1);
        try(var scope=ledger.prepare()){GpuBudgetLedger.uploaded(64);}
        ledger.gpu(2);var captured=ledger.snapshot();ledger.begin(2,false);
        var first=ledger.prepare();var second=ledger.prepare();assertSame(first,second);
        GpuBudgetLedger.uploaded(128);GpuBudgetLedger.downloaded(32);GpuBudgetLedger.queued(1,2,3,4);
        ledger.gpu(10);ledger.queue(1,2,3,4);first.close();second.close();
        assertEquals(captured,ledger.snapshot());
        ledger.begin(3,true);assertEquals(0,ledger.snapshot().uploadedBytes());
        try(var scope=ledger.prepare()){GpuBudgetLedger.uploaded(8);}
        assertEquals(8,ledger.snapshot().uploadedBytes());
    }
    @Test void fiveCategoriesAndNestedCpuAreCountedOnce() {
        var ledger=new GpuBudgetLedger();ledger.begin(17);
        try(var outer=ledger.prepare()) {
            try(var detail=ledger.prepare()) {
                GpuBudgetLedger.uploaded(64);GpuBudgetLedger.downloaded(16);GpuBudgetLedger.queued(3,2,1,7);
            }
            assertEquals(0,ledger.snapshot().cpuNanos(),"Nested detail must not add to the enclosing span");
        }
        ledger.gpu(2.5);var snapshot=ledger.snapshot();
        assertEquals(17,snapshot.frame());assertTrue(snapshot.cpuNanos()>0);assertEquals(2.5,snapshot.gpuMillis());
        assertEquals(64,snapshot.uploadedBytes());assertEquals(16,snapshot.readbackBytes());
        assertEquals(3,snapshot.accepted());assertEquals(2,snapshot.deferred());assertEquals(1,snapshot.rejected());assertEquals(7,snapshot.backlog());
        GpuBudgetLedger.uploaded(500);assertEquals(64,ledger.snapshot().uploadedBytes());
    }
    @Test void ledgerCannotIntroduceThrottleOrMergeAnIndependentContext() {
        var world=new GpuBudgetLedger();var ui=new GpuBudgetLedger();world.begin(1);ui.begin(200);
        try(var frame=world.prepare()) {
            GpuBudgetLedger.queued(12,0,0,12);
            try(var independent=ui.prepare()){GpuBudgetLedger.uploaded(128);GpuBudgetLedger.queued(256,0,0,0);}
            GpuBudgetLedger.uploaded(64);
        }
        assertEquals(12,world.snapshot().accepted());assertEquals(256,ui.snapshot().accepted());
        assertEquals(64,world.snapshot().uploadedBytes());assertEquals(128,ui.snapshot().uploadedBytes());
        world.begin(2);assertEquals(0,world.snapshot().uploadedBytes());assertEquals(128,ui.snapshot().uploadedBytes());
    }
    @Test void invalidMeasurementsAndOpenScopesFailExplicitly() {
        var ledger=new GpuBudgetLedger();ledger.begin(1);
        try(var span=ledger.prepare()){assertThrows(IllegalStateException.class,()->ledger.begin(2));}
        assertThrows(IllegalArgumentException.class,()->ledger.gpu(Double.NaN));
        assertThrows(IllegalArgumentException.class,()->ledger.queue(-1,0,0,0));
    }
}
