package com.iridium126.createmanaindustry.client.gpu.profile;

import java.util.concurrent.atomic.AtomicLong;

/** Optional queue diagnostics. Admission/backpressure counters belong to the queue itself. */
public final class GpuQueueAccounting {
    private static volatile Object session;
    private final boolean crossThread;
    private volatile Counters counters;
    private static final class Counters {
        final Object session;
        final AtomicLong[] concurrent;
        long accepted,deferred,rejected;
        Counters(Object session,boolean crossThread) {
            this.session=session;
            concurrent=crossThread?new AtomicLong[]{new AtomicLong(),new AtomicLong(),new AtomicLong()}:null;
        }
    }
    public GpuQueueAccounting(){this(false);}
    public GpuQueueAccounting(boolean crossThread){this.crossThread=crossThread;}
    /** Supplied at a business frame boundary; no config or particle dependency. */
    public static void collecting(boolean enabled){if(!enabled)session=null;else if(session==null)session=new Object();}
    private Counters current() {
        Object current=session;
        if(current==null)return null;
        var result=counters;
        if(result==null||result.session!=current) {
            synchronized(this) {
                if(session!=current)return null;
                result=counters;
                if(result==null||result.session!=current)counters=result=new Counters(current,crossThread);
            }
        }
        return result;
    }
    public void accepted(long count){var c=current();if(c==null)return;count=nonnegative(count);if(c.concurrent==null)c.accepted+=count;else c.concurrent[0].addAndGet(count);}
    public void deferred(long count){var c=current();if(c==null)return;count=nonnegative(count);if(c.concurrent==null)c.deferred+=count;else c.concurrent[1].addAndGet(count);}
    public void rejected(long count){var c=current();if(c==null)return;count=nonnegative(count);if(c.concurrent==null)c.rejected+=count;else c.concurrent[2].addAndGet(count);}
    public void measure(long backlog) {
        var c=current();if(c==null)return;
        if(c.concurrent==null){GpuBudgetLedger.queued(c.accepted,c.deferred,c.rejected,nonnegative(backlog));c.accepted=c.deferred=c.rejected=0;}
        else GpuBudgetLedger.queued(c.concurrent[0].getAndSet(0),c.concurrent[1].getAndSet(0),c.concurrent[2].getAndSet(0),nonnegative(backlog));
    }
    private static long nonnegative(long value){if(value<0)throw new IllegalArgumentException("Queue count");return value;}
}
