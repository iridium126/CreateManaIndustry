package com.iridium126.createmanaindustry.content.allaystorm;

import net.minecraft.resources.ResourceLocation;

public final class StormStreamClock {
    private StormIdentity identity;
    private long lastTime = Long.MIN_VALUE;

    public boolean accept(StormIdentity incoming, StormIdentity active, ResourceLocation dimension, long time) {
        if (incoming == null || !incoming.equals(active) || !incoming.dimension().equals(dimension)) return false;
        if (!incoming.equals(identity)) {
            identity = incoming;
            lastTime = Long.MIN_VALUE;
        }
        if (time < lastTime) return false;
        lastTime = time;
        return true;
    }

    public void reset() {
        identity = null;
        lastTime = Long.MIN_VALUE;
    }
}
