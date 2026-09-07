package com.iridium126.createmanaindustry.client.dimension.lod;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

import com.iridium126.createmanaindustry.dimension.lod.AllvrLodSectionData;
import com.iridium126.createmanaindustry.client.dimension.render.AllvrRenderer;

/**
 * Legacy backend (voxy integration plan §7.1): the pre-existing ALLVR LOD
 * draw path. Kept as the AUTO fallback for missing/incompatible Voxy builds
 * and as an A/B baseline — with this backend the client also requests the
 * legacy wire format (server-meshed quads), so nothing else changes.
 */
final class LegacyAllvrLodBackend implements AllvrLodBackend {

    @Override
    public Availability probe() {
        return Availability.ok(); // always present — it is CMI's own renderer
    }

    @Override
    public void enter(ClientLevel level) {}

    @Override
    public boolean apply(AllvrLodSectionData data, Holder<Biome> biome) {
        // legacy consumes the quad wire path, not sections — a section
        // payload arriving here means the wire/backend pair raced; the
        // pending entry is consumed and the walk re-requests with the
        // legacy capability (AllvrLodClientState handles that)
        return false;
    }

    @Override
    public void forget(int level, long cellLong) {
        AllvrRenderer.INSTANCE.forgetLod(level, cellLong);
    }

    @Override
    public void tick(double cameraX, double cameraY, double cameraZ) {}

    @Override
    public void leave() {}

    @Override
    public String debugState() {
        return "legacy";
    }
}
