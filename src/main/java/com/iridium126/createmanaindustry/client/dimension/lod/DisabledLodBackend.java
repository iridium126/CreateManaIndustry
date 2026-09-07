package com.iridium126.createmanaindustry.client.dimension.lod;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

import com.iridium126.createmanaindustry.dimension.lod.AllvrLodSectionData;

/**
 * Backend for an explicitly disabled LOD (voxy integration plan §7.1) —
 * accepts nothing, forgets into nothing.
 */
final class DisabledLodBackend implements AllvrLodBackend {

    @Override
    public Availability probe() {
        return Availability.ok();
    }

    @Override
    public void enter(ClientLevel level) {}

    @Override
    public boolean apply(AllvrLodSectionData data, Holder<Biome> biome) {
        return false;
    }

    @Override
    public void forget(int level, long cellLong) {}

    @Override
    public void tick(double cameraX, double cameraY, double cameraZ) {}

    @Override
    public void leave() {}

    @Override
    public String debugState() {
        return "off";
    }
}
