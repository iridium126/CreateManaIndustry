package com.iridium126.createmanaindustry.client.gpu.profile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GpuQueueAccountingTest {
    @AfterEach void stop(){GpuQueueAccounting.collecting(false);}

    @Test void disabledInputsAndPreviousSessionsCannotEnterANewCapture() throws Exception {
        for(boolean concurrent:new boolean[]{false,true}) {
            GpuQueueAccounting.collecting(false);
            var queue=new GpuQueueAccounting(concurrent);
            queue.accepted(100);queue.deferred(50);queue.rejected(20);
            GpuQueueAccounting.collecting(true);
            Runnable inputs=()->{for(int i=0;i<100;i++)queue.accepted(1);queue.deferred(2);queue.rejected(3);};
            if(concurrent){var producer=new Thread(inputs);producer.start();producer.join();}else inputs.run();
            var ledger=new GpuBudgetLedger();ledger.begin(1);
            try(var scope=ledger.prepare()){queue.measure(7);}
            assertEquals(100,ledger.snapshot().accepted());assertEquals(2,ledger.snapshot().deferred());
            assertEquals(3,ledger.snapshot().rejected());assertEquals(7,ledger.snapshot().backlog());
            queue.accepted(999);
            GpuQueueAccounting.collecting(false);queue.accepted(500);
            GpuQueueAccounting.collecting(true);queue.accepted(1);ledger.begin(2);
            try(var scope=ledger.prepare()){queue.measure(0);}
            assertEquals(1,ledger.snapshot().accepted());assertEquals(0,ledger.snapshot().deferred());
            assertEquals(0,ledger.snapshot().rejected());
        }
    }
}
