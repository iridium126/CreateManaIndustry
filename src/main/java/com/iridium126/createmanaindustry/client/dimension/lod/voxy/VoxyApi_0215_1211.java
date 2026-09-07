package com.iridium126.createmanaindustry.client.dimension.lod.voxy;

import com.iridium126.createmanaindustry.CreateManaIndustry;

/**
 * The one class that touches Voxy internals (voxy integration plan §8.1): a
 * thin adapter pinned to the verified NeoForge artifact
 * {@code voxy-0.2.15-beta+1.21.1-neoforge.jar} (build aab0ab95). This file
 * carries the version-matrix constants and the ABI probe; engine writes live
 * in {@link AllvrVoxyEngineOps}, the ownership ledger in
 * {@link AllvrVoxyNodeRegistry}.
 * <p>
 * Referenced Voxy surface (pinned via javap): {@code VoxyCommon.getInstance()}
 * → the {@code VoxyClientInstance} cast (WorldEngine table owner, §7.2);
 * {@code VoxyInstance#getNullable(WorldIdentifier)} (engine fetch, no create);
 * {@code IGetVoxyRenderSystem#getNullable()} on LevelRenderer +
 * {@code VoxyRenderSystem#getEngine()} (render-system ↔ engine identity gate);
 * {@code WorldEngine#acquire(int×4)/markDirty/getMapper/isLive/pprintPos} and
 * its BLOCK/CHILD_EXISTENCE/DONT_SAVE update-type constants;
 * {@code WorldSection} raw-data/counter/child-mask accessors,
 * {@code release(int)}/{@code assertNotFree()}, static
 * {@code RELEASE_HINT_POSSIBLE_REUSE} and {@code getChildIndex(int,int,int)};
 * {@code Mapper#getIdForBlockState/getIdForBiome/composeMappingId} and the
 * packed-id layout (light 8b | biome 9b | block 20b, air = block bits 0).
 */
public final class VoxyApi_0215_1211 {

    /** Voxy's own mod id, as registered in its neoforge.mods.toml. */
    public static final String VOXY_MOD_ID = "voxy";

    /** Voxy {@code WorldEngine.MAX_LOD_LAYER} (verified value 4). */
    public static final int MAX_LOD_LAYER = 4;

    /** Voxy L0 hard section-key Y range in blocks: {@code [-4096, 4095]}. */
    public static final int VOXY_MIN_BLOCK_Y = -128 << 5;
    public static final int VOXY_MAX_BLOCK_Y = ((128 - 1) << 5) + 31;

    private VoxyApi_0215_1211() {}

    public static boolean modInstalled() {
        return net.neoforged.fml.loading.FMLLoader.getLoadingModList().getModFileById(VOXY_MOD_ID) != null;
    }

    /** {@code VoxyCommon.MOD_VERSION}, or null when unreadable. */
    public static String modVersion() {
        try {
            return me.cortex.voxy.commonImpl.VoxyCommon.MOD_VERSION;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The Voxy section key for a virtual (level, x, y, z). */
    public static long sectionKey(int level, int cellX, int cellY, int cellZ) {
        return me.cortex.voxy.common.world.WorldEngine.getWorldSectionId(level, cellX, cellY, cellZ);
    }

    /** Key decoders used by the registry and the backend. */
    public static int keyLevel(long key) {
        return me.cortex.voxy.common.world.WorldEngine.getLevel(key);
    }

    /** X/Y/Z decoders for a Voxy section key. */
    public static int keyX(long key) {
        return me.cortex.voxy.common.world.WorldEngine.getX(key);
    }

    public static int keyY(long key) {
        return me.cortex.voxy.common.world.WorldEngine.getY(key);
    }

    public static int keyZ(long key) {
        return me.cortex.voxy.common.world.WorldEngine.getZ(key);
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

    /** Maps the packet palette to voxy block ids (one mapper call per state). */
    public static int[] mappedBlockIds(me.cortex.voxy.common.world.other.Mapper mapper,
                                       net.minecraft.world.level.block.state.BlockState[] palette) {
        int[] ids = new int[palette.length];
        for (int i = 1; i < palette.length; i++) {
            ids[i] = mapper.getIdForBlockState(palette[i]);
        }
        return ids;
    }

    /** Returns the first missing Voxy symbol, or null when the ABI matches. */
    public static String probeDescriptors() {
        try {
            checkMethod(me.cortex.voxy.common.world.WorldEngine.class, "getWorldSectionId",
                int.class, int.class, int.class, int.class);
            checkMethod(me.cortex.voxy.common.world.WorldEngine.class, "getLevel", long.class);
            checkMethod(me.cortex.voxy.common.world.WorldEngine.class, "acquire",
                int.class, int.class, int.class, int.class);
            checkMethod(me.cortex.voxy.common.world.WorldEngine.class, "markDirty",
                me.cortex.voxy.common.world.WorldSection.class, int.class, int.class);
            checkMethod(me.cortex.voxy.common.world.WorldEngine.class, "getMapper");
            checkMethod(me.cortex.voxy.common.world.WorldEngine.class, "isLive");
            checkMethod(me.cortex.voxy.common.world.WorldEngine.class, "pprintPos", long.class);
            checkField(me.cortex.voxy.common.world.WorldEngine.class, "UPDATE_TYPE_BLOCK_BIT");
            checkField(me.cortex.voxy.common.world.WorldEngine.class, "UPDATE_TYPE_CHILD_EXISTENCE_BIT");
            checkField(me.cortex.voxy.common.world.WorldEngine.class, "UPDATE_TYPE_DONT_SAVE");
            checkMethod(me.cortex.voxy.common.world.WorldSection.class, "_unsafeGetRawDataArray");
            checkMethod(me.cortex.voxy.common.world.WorldSection.class, "addNonEmptyBlockCount", int.class);
            checkMethod(me.cortex.voxy.common.world.WorldSection.class, "updateLvl0State");
            checkMethod(me.cortex.voxy.common.world.WorldSection.class, "updateEmptyChildState",
                me.cortex.voxy.common.world.WorldSection.class);
            checkMethod(me.cortex.voxy.common.world.WorldSection.class, "_unsafeSetNonEmptyChildren", byte.class);
            checkMethod(me.cortex.voxy.common.world.WorldSection.class, "release", int.class);
            checkMethod(me.cortex.voxy.common.world.WorldSection.class, "assertNotFree");
            checkMethod(me.cortex.voxy.common.world.WorldSection.class, "getNonEmptyChildren");
            checkMethod(me.cortex.voxy.common.world.WorldSection.class, "getChildIndex",
                int.class, int.class, int.class);
            checkField(me.cortex.voxy.common.world.WorldSection.class, "RELEASE_HINT_POSSIBLE_REUSE");
            checkMethod(me.cortex.voxy.common.world.other.Mapper.class, "getIdForBlockState",
                net.minecraft.world.level.block.state.BlockState.class);
            checkMethod(me.cortex.voxy.common.world.other.Mapper.class, "getIdForBiome",
                net.minecraft.core.Holder.class);
            checkMethod(me.cortex.voxy.common.world.other.Mapper.class, "composeMappingId",
                byte.class, int.class, int.class);
            checkField(me.cortex.voxy.common.world.other.Mapper.class, "AIR");
            checkMethod(me.cortex.voxy.commonImpl.VoxyCommon.class, "getInstance");
            checkMethod(me.cortex.voxy.commonImpl.VoxyInstance.class, "getNullable",
                me.cortex.voxy.commonImpl.WorldIdentifier.class);
            checkMethod(me.cortex.voxy.commonImpl.WorldIdentifier.class, "of",
                net.minecraft.world.level.Level.class);
            checkMethod(me.cortex.voxy.client.core.IGetVoxyRenderSystem.class, "getNullable");
            checkMethod(me.cortex.voxy.client.core.VoxyRenderSystem.class, "getEngine");
            return null;
        } catch (Throwable t) {
            return String.valueOf(t.getMessage());
        }
    }

    private static void checkMethod(Class<?> owner, String name, Class<?>... params)
        throws NoSuchMethodException {
        owner.getMethod(name, params);
    }

    private static void checkField(Class<?> owner, String name) throws NoSuchFieldException {
        owner.getField(name);
    }
}
