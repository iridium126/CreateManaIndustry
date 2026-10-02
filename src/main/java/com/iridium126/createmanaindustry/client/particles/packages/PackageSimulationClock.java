package com.iridium126.createmanaindustry.client.particles.packages;

/** Wall-clock 20 Hz accumulator. A moving pose pair may be consumed only once per client tick.
 * Long render stalls rebase the clock and skip stale steps without changing package authority. */
public final class PackageSimulationClock {
    public enum Advance { IDLE, STEP }
    public static final long STEP_NANOS=50_000_000L,MAX_BACKLOG_NANOS=100_000_000L;
    private long previous,lastStepTick=Long.MIN_VALUE,accumulated;
    private boolean initialized;

    public Advance advance(long now,long poseTick,boolean paused) {
        return advance(now,poseTick,paused,true);
    }
    /** An unfinished force capture must not consume either time or the current pose pair. */
    public Advance advance(long now,long poseTick,boolean paused,boolean inputsReady) {
        if(!initialized || paused){initialized=true;previous=now;accumulated=0;lastStepTick=poseTick;return Advance.IDLE;}
        long elapsed=now-previous;previous=now;
        if(elapsed<0 || elapsed>MAX_BACKLOG_NANOS || accumulated>MAX_BACKLOG_NANOS-elapsed) {
            accumulated=0;lastStepTick=poseTick;return Advance.IDLE;
        }
        if(!inputsReady) {
            accumulated=0;lastStepTick=poseTick;return Advance.IDLE;
        }
        accumulated+=elapsed;
        if(accumulated>=STEP_NANOS && poseTick!=lastStepTick) {
            accumulated-=STEP_NANOS;lastStepTick=poseTick;return Advance.STEP;
        }
        return Advance.IDLE;
    }
    public float interpolation(){return Math.clamp((float)accumulated/STEP_NANOS,0,1);}
    public void reset(){initialized=false;lastStepTick=Long.MIN_VALUE;accumulated=0;}
}
