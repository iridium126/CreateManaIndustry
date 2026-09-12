package com.iridium126.createmanaindustry.client.dimension.lod.voxy;

import com.iridium126.createmanaindustry.CreateManaIndustry;

/**
 * The one class that touches Voxy internals (voxy integration plan §8.1): a
 * thin adapter pinned to the verified NeoForge artifact
 * {@code voxy-0.2.15-beta+1.21.1-neoforge.jar} (build aab0ab95). This file
 * carries the small ABI adapter used by the client ingest bridge.
 * <p>
 * Referenced Voxy surface (pinned via javap): {@code VoxyCommon.getInstance()}
 * → the {@code VoxyClientInstance} cast (WorldEngine table owner, §7.2);
 * {@code VoxyInstance#getNullable(WorldIdentifier)} (engine fetch, no create);
 * {@code IGetVoxyRenderSystem#getNullable()} on LevelRenderer +
 * {@code VoxyRenderSystem#getEngine()} (render-system ↔ engine identity gate);
 * {@code VoxelIngestService.rawIngest} and the normal client
 * renderer/storage lifecycle. Allay never calls Voxy's internal node writer
 * directly; cube sections enter through the same public ingest path used by
 * ordinary chunks.
 */
public final class VoxyApi_0215_1211 {

    /** Voxy's own mod id, as registered in its neoforge.mods.toml. */
    public static final String VOXY_MOD_ID = "voxy";

    private VoxyApi_0215_1211() {}

    public static boolean modInstalled() {
        return net.neoforged.fml.loading.FMLLoader.getLoadingModList().getModFileById(VOXY_MOD_ID) != null;
    }

    /**
     * Finds the live {@code WorldEngine} for {@code level} — getNullable only
     * (engine creation stays inside Voxy's own renderer setup, so a null here
     * just means "not ready yet"). Returns null when the voxy instance is
     * absent, disabled, or the engine is dead/unbound, and when the render
     * system is bound to a different engine (identity gate, plan §7.2).
     */
    public static me.cortex.voxy.common.world.WorldEngine findEngine(net.minecraft.world.level.Level level) {
        try {
            var instance = me.cortex.voxy.commonImpl.VoxyCommon.getInstance();
            if (!(instance instanceof me.cortex.voxy.client.VoxyClientInstance client)) {
                return null;
            }
            var engine = client.getNullable(me.cortex.voxy.commonImpl.WorldIdentifier.of(level));
            if (engine == null || !engine.isLive()) {
                return null;
            }
            var renderer = me.cortex.voxy.client.core.IGetVoxyRenderSystem.getNullable();
            if (renderer != null && renderer.getEngine() != engine) {
                return null; // render system bound elsewhere — refuse to write
            }
            return engine;
        } catch (Throwable t) {
            CreateManaIndustry.LOGGER.debug("[Allvr] voxy engine probe failed", t);
            return null;
        }
    }

}
