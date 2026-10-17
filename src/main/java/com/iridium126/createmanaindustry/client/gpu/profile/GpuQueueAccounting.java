package com.iridium126.createmanaindustry.client.gpu.profile;

import java.util.concurrent.atomic.AtomicLong;

/** Queue-owner counters accumulate between frames, including network/tick inputs outside a render scope. */
public final class GpuQueueAccounting {
    private final AtomicLong[] concurrent;
    private long accepted,deferred,rejected;
    public GpuQueueAccounting(){concurrent=null;}
    public GpuQueueAccounting(boolean crossThread){concurrent=crossThread?new AtomicLong[]{new AtomicLong(),new AtomicLong(),new AtomicLong()}:null;}
    public void accepted(long count){count=nonnegative(count);if(concurrent==null)accepted+=count;else concurrent[0].addAndGet(count);}
    public void deferred(long count){count=nonnegative(count);if(concurrent==null)deferred+=count;else concurrent[1].addAndGet(count);}
    public void rejected(long count){count=nonnegative(count);if(concurrent==null)rejected+=count;else concurrent[2].addAndGet(count);}
    public void measure(long backlog) {
        if(concurrent==null){GpuBudgetLedger.queued(accepted,deferred,rejected,nonnegative(backlog));accepted=deferred=rejected=0;}
        else GpuBudgetLedger.queued(concurrent[0].getAndSet(0),concurrent[1].getAndSet(0),concurrent[2].getAndSet(0),nonnegative(backlog));
    }
    private static long nonnegative(long value){if(value<0)throw new IllegalArgumentException("Queue count");return value;}
}
