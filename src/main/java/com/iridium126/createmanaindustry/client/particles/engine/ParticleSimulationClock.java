package com.iridium126.createmanaindustry.client.particles.engine;

/** World-tick clock. Rendering never changes the simulation step or discards backlog. */
public final class ParticleSimulationClock {
    public static final float STEP_SECONDS = .05f;
    public static final int MAX_STEPS_PER_FRAME = 2;
    private Object world;
    private long completed, target, epoch;
    private int remaining;
    private float alpha;
    private boolean initialized,paused;
    private float frozenAlpha;

    public void beginFrame(Object world, long tick, float partial, boolean paused) {
        if (world == null) { reset(); return; }
        boolean newTimeline=!initialized||this.world!=world||tick<completed;
        float previousAlpha=alpha();
        if (newTimeline) {
            this.world = world;
            completed = tick - 1;
            initialized = true;
            epoch++;
        }
        target = tick;
        remaining = paused ? 0 : MAX_STEPS_PER_FRAME;
        alpha = Float.isFinite(partial) ? Math.clamp(partial, 0, 1) : 0;
        if(paused&&(!this.paused||newTimeline))frozenAlpha=newTimeline?1:previousAlpha;
        this.paused=paused;
    }

    public boolean hasStep() { return initialized && remaining > 0 && completed < target; }
    public long nextTick() {
        if (!hasStep()) throw new IllegalStateException("No particle simulation tick due");
        return completed + 1;
    }
    public void commit(long tick) {
        if (!hasStep() || tick != completed + 1) throw new IllegalStateException("Out-of-order particle tick");
        completed = tick;
        remaining--;
    }
    public float alpha() { return paused?frozenAlpha:completed<target?1:alpha; }
    public long completedTick() { return completed; }
    public long targetTick() { return target; }
    public long epoch() { return epoch; }
    public long backlog() { return initialized ? Math.max(0, target - completed) : 0; }
    public void reset() { world = null; initialized = paused = false; remaining = 0; alpha = frozenAlpha = 0; epoch++; }
}
