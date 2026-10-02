package com.iridium126.createmanaindustry.client.particles.packages;

/** Transactional 20 Hz clock. Waiting for inputs never consumes simulation time. */
public final class PackageSimulationClock {
    public enum Advance { IDLE, STEP }
    public static final long STEP_NANOS=50_000_000L,MAX_BACKLOG_NANOS=1_000_000_000L;
    public static final int MAX_STEPS_PER_FRAME=4,HISTORY_TICKS=20;
    private long previous,accumulated,nextTick,step;
    private boolean initialized,gap;
    public void sample(long now,long tick,boolean paused) {
        if(!initialized || paused){rebase(now,tick);return;}
        long elapsed=now-previous;previous=now;
        if(elapsed<0 || elapsed>MAX_BACKLOG_NANOS || accumulated>MAX_BACKLOG_NANOS-elapsed){gap=true;return;}
        accumulated+=elapsed;
        if(tick-nextTick>=HISTORY_TICKS)gap=true;
    }
    public boolean due(long availableTick){return initialized&&!gap&&accumulated>=STEP_NANOS&&nextTick<=availableTick;}
    public long nextTick(){return nextTick;}
    public long step(){return step;}
    public boolean historyGap(){return gap;}
    public void commit(long tick){
        if(!due(tick)||tick!=nextTick)throw new IllegalStateException("Package step has no clock reservation");
        accumulated-=STEP_NANOS;nextTick++;step++;
    }
    /** Explicit rebaseline never claims that missing simulation steps completed. */
    public void rebase(long now,long tick){initialized=true;previous=now;accumulated=0;nextTick=tick+1;gap=false;}
    public Advance advance(long now,long tick,boolean paused){return advance(now,tick,paused,true);}
    public Advance advance(long now,long tick,boolean paused,boolean ready){
        sample(now,tick,paused);if(!paused&&ready&&due(tick)){commit(nextTick);return Advance.STEP;}return Advance.IDLE;
    }
    public float interpolation(){return Math.clamp((float)accumulated/STEP_NANOS,0,1);}
    public void reset(){initialized=false;gap=false;accumulated=0;step=0;}
}
