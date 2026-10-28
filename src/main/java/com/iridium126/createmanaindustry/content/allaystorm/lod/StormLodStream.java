package com.iridium126.createmanaindustry.content.allaystorm.lod;

/** Connection-scoped subscription gate, retained across world changes and local disable. */
public final class StormLodStream {
    private long highest, active;

    public boolean initialize(long subscription) {
        if (subscription <= highest) return false;
        highest = active = subscription;
        return true;
    }

    public boolean accepts(long subscription) { return active != 0 && subscription == active; }
    public void clear() { active = 0; }
    public void disconnect() { highest = active = 0; }
}
