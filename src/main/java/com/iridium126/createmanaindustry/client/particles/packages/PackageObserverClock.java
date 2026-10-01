package com.iridium126.createmanaindustry.client.particles.packages;

/** Stable server-tick coordinate for GPU receipt times. Arrival jitter never remaps an already
 * stored timestamp. Monotonic elapsed time includes local queue delay; estimated one-way network
 * latency is explicit, not silently zeroed by treating each packet arrival as a fresh pose.
 * Times describe SERVER CONFIRMATION, not the original authority's simulation timestamp. */
public final class PackageObserverClock {
    private static final float MARGIN=16,MAX_TIME=3600;
    private long baseTick,baseNanos,lastReceived,newestTick;
    private double initialLatency,floor;
    private float last;
    private boolean initialized;
    public void observe(long serverTick,long receivedNanos,long oneWayNanos) {
        if(serverTick<0 || oneWayNanos<0 || oneWayNanos>5_000_000_000L)throw new IllegalArgumentException("Observer clock sample");
        if(!initialized) {
            baseTick=newestTick=serverTick;baseNanos=lastReceived=receivedNanos;initialLatency=oneWayNanos*1e-9;
            floor=MARGIN+initialLatency;initialized=true;return;
        }
        if(serverTick<newestTick || receivedNanos-lastReceived<0)throw new IllegalArgumentException("Observer server/arrival clock reversed");
        newestTick=serverTick;lastReceived=receivedNanos;
        floor=Math.max(floor,tickTime(serverTick)+oneWayNanos*1e-9);
    }
    private double tickTime(long tick){return MARGIN+(tick>=baseTick?(double)(tick-baseTick)*.05:-(double)(baseTick-tick)*.05);}
    public float receipt(long stateTick) {
        ready();if(stateTick<0 || stateTick>newestTick)throw new IllegalArgumentException("Observer future confirmation");
        return (float)Math.max(0,tickTime(stateTick));
    }
    public float now(long nanos) {
        ready();long elapsed=nanos-baseNanos;if(elapsed<0)throw new IllegalArgumentException("Observer monotonic clock reversed");
        double time=Math.max(floor,MARGIN+initialLatency+elapsed*1e-9);
        if(time>MAX_TIME)throw new IllegalStateException("Observer clock precision window exhausted; renew namespace");
        last=Math.max(last,(float)time);return last;
    }
    public boolean initialized(){return initialized;}
    private void ready(){if(!initialized)throw new IllegalStateException("Observer clock has no server sample");}
}
