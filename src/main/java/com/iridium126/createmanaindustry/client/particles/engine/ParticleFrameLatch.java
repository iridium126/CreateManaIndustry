package com.iridium126.createmanaindustry.client.particles.engine;

/** Render-call identity, independent of tick count and reused DeltaTracker instances. */
public final class ParticleFrameLatch {
    private long token;
    private int depth;
    private boolean primary, attempted, ready;

    public boolean enter(boolean main) {
        if (depth++ != 0) return false;
        if (main) token++;
        primary = main;
        attempted = ready = false;
        return main;
    }

    public void leave() {
        if (depth <= 0) throw new IllegalStateException("Unbalanced particle render scope");
        if (--depth == 0) primary = attempted = ready = false;
    }

    public boolean active() { return depth > 0; }
    public boolean primary() { return depth == 1 && primary; }

    public boolean claim() {
        if (!primary() || attempted) return false;
        attempted = true;
        return true;
    }

    public void complete(boolean success) {
        if (!primary() || !attempted) throw new IllegalStateException("No particle frame preparation");
        ready = success;
    }

    public boolean ready() { return primary() && ready; }
    public long token() { return token; }
}
