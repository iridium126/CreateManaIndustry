package com.iridium126.createmanaindustry.content.allaystorm;

import net.minecraft.resources.ResourceLocation;

/** A storm generation belongs to exactly one dimension. */
public record StormIdentity(ResourceLocation dimension, long epoch) {
    public static StormIdentity of(net.minecraft.world.level.Level level, AllayStormData data) {
        return new StormIdentity(level.dimension().location(), AllayStarfall.epoch(data.createdAtGameTime, data.stormSeed));
    }

    public boolean acceptsLifecycle(int action, ResourceLocation currentDimension, StormIdentity active) {
        return dimension.equals(currentDimension) && (action == 0 || equals(active));
    }
}
