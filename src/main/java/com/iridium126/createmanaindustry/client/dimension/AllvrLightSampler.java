package com.iridium126.createmanaindustry.client.dimension;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;

/**
 * Entity/block-entity light lookup for Allay. The value comes from the same
 * sparse light engine that feeds Sodium and Voxy, so entities no longer use a
 * separate ray scan or Manhattan-distance approximation.
 */
public final class AllvrLightSampler {

    public static int sample(ClientLevel level, BlockPos pos) {
        if (!AllvrClientCubeCache.isAllay(level)) {
            return LightTexture.pack(0, 0);
        }
        return LightTexture.pack(AllvrClientCubeCache.sampleBlockLight(pos),
            AllvrClientCubeCache.sampleSkyLight(pos));
    }

    private AllvrLightSampler() {}
}
