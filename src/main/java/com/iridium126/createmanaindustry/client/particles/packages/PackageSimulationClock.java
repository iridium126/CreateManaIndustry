package com.iridium126.createmanaindustry.client.particles.packages;

import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageTickTiming;

/** Transactional world-tick clock. Waiting for inputs never consumes simulation time. */
public final class PackageSimulationClock {
    public enum Advance { IDLE, STEP }
    public static final long STEP_NANOS=50_000_000L;
    public static final int MAX_STEPS_PER_FRAME=4,HISTORY_TICKS=20;
    private long previous,accumulated,nextTick,step;
    private long period=STEP_NANOS;
    private int historyTicks=HISTORY_TICKS,stepsPerFrame=MAX_STEPS_PER_FRAME;
    private boolean initialized,gap,cadenceReady;
    public void sample(long now,long tick,boolean paused) {
        sample(now,tick,paused,20);
    }
    public void sample(long now,long tick,boolean paused,double tickRate) {
        sample(now,tick,paused,tickRate,1);
    }
    /** A client capture supplies a bounded batch at high rates. Reserve at most one such
     * future batch; waiting between its captures must not throw away real elapsed time. */
    public void sample(long now,long tick,boolean paused,double tickRate,int captureSteps) {
        if(captureSteps<1||captureSteps>Math.ceil(Math.max(1,PackageTickTiming.rate(tickRate)/20)))throw new IllegalArgumentException("Package input cadence");
        long nextPeriod=PackageTickTiming.periodNanos(tickRate);
        if(period!=nextPeriod){accumulated=Math.round((double)accumulated/period*nextPeriod);period=nextPeriod;}
        // Keep already retained inputs across a later rate decrease; never discard a backlog
        // merely because a command changed the size of the current one-second window.
        historyTicks=Math.max(historyTicks,PackageTickTiming.historyTicks(tickRate));
        stepsPerFrame=Math.max(stepsPerFrame,PackageTickTiming.stepsPerFrame(tickRate));
        if(!initialized || paused){rebase(now,tick);return;}
        long elapsed=now-previous;previous=now;
        if(elapsed<0 || tick-nextTick>=historyTicks){gap=true;return;}
        // Simulation inputs advance with server ticks. A slow/stalled server cannot supply
        // twenty new poses per wall-clock second. Keep the real tick backlog and at most
        // one future step; otherwise healthy history eventually triggers a global revoke.
        long available=Math.max(0,tick-nextTick+1);
        long limit=(available+captureSteps)*period;
        accumulated=Math.min(limit,accumulated+Math.min(elapsed,limit));
        // Start one captured interval behind the observation stream. Charging all startup
        // waiting time would empty each ten-step capture immediately and visibly freeze
        // until the next 20 Hz capture, despite a 200 Hz physical scheduler.
        if(!cadenceReady&&available>0){cadenceReady=true;if(captureSteps>1)accumulated=Math.min(accumulated,period);}
    }
    public boolean due(long availableTick){return initialized&&!gap&&accumulated>=period&&nextTick<=availableTick;}
    public int stepsPerFrame(){return stepsPerFrame;}
    public long nextTick(){return nextTick;}
    public long step(){return step;}
    public boolean historyGap(){return gap;}
    public void commit(long tick){
        if(!due(tick)||tick!=nextTick)throw new IllegalStateException("Package step has no clock reservation");
        accumulated-=period;nextTick++;step++;
    }
    /** Explicit rebaseline never claims that missing simulation steps completed. */
    public void rebase(long now,long tick){initialized=true;previous=now;accumulated=0;nextTick=tick+1;gap=false;cadenceReady=false;}
    public Advance advance(long now,long tick,boolean paused){return advance(now,tick,paused,true);}
    public Advance advance(long now,long tick,boolean paused,boolean ready){
        sample(now,tick,paused);if(!paused&&ready&&due(tick)){commit(nextTick);return Advance.STEP;}return Advance.IDLE;
    }
    public float interpolation(){return Math.clamp((float)accumulated/period,0,1);}
    public void reset(){initialized=false;gap=false;accumulated=0;step=0;}
}
