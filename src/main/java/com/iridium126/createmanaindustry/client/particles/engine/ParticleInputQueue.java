package com.iridium126.createmanaindustry.client.particles.engine;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import com.iridium126.createmanaindustry.client.gpu.profile.GpuQueueAccounting;

/** Actual cross-thread input mailbox, with constant-time backlog and render-scope accounting. */
final class ParticleInputQueue {
    private final ConcurrentLinkedQueue<Object> inputs=new ConcurrentLinkedQueue<>();
    private final AtomicInteger backlog=new AtomicInteger();
    private final GpuQueueAccounting accounting=new GpuQueueAccounting(true);
    void add(Object input) {
        java.util.Objects.requireNonNull(input);backlog.incrementAndGet();inputs.add(input);accounting.accepted(1);
    }
    Object poll() {var input=inputs.poll();if(input!=null)backlog.decrementAndGet();return input;}
    void clear() {while(poll()!=null){} }
    void deferred(long count){accounting.deferred(count);}
    void measure(int retry){accounting.measure((long)backlog.get()+retry);}
}
