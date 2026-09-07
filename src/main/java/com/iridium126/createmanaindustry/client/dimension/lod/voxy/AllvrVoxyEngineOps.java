package com.iridium126.createmanaindustry.client.dimension.lod.voxy;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.dimension.lod.AllvrLodPos;
import com.iridium126.createmanaindustry.dimension.lod.AllvrLodSectionData;

/**
 * The exact voxy-engine write protocol (voxy integration plan §7.3), run on
 * the render thread through the single-writer drain. Injection fills the
 * acquired section's 32³ raw array in one shot and only then publishes the
 * dirty event; forget air-fills first, then tears the node down through the
 * parent's child bit and cascades upward while each ancestor stays childless
 * and topology-only.
 * <p>
 * Every write uses {@code UPDATE_TYPE_DONT_SAVE} — virtual data can never
 * reach the voxy database even if the storage interceptor ever misses.
 */
final class AllvrVoxyEngineOps {

    private AllvrVoxyEngineOps() {}

    /**
     * Injects one absolute section payload into the engine and chains the
     * ancestor bits upward. Returns false on a refused write (round-trip
     * failure or outside the virtual window) so the caller drops the node
     * and the request walk re-issues it.
     */
    static boolean writeSection(me.cortex.voxy.common.world.WorldEngine engine,
                                AllvrVoxyYWindow window, AllvrVoxyNodeRegistry registry,
                                AllvrLodSectionData data, Holder<Biome> biome) {
        int lvl = data.level();
        AllvrLodPos pos = AllvrLodPos.fromCellLong(lvl, data.cellLong());
        int vx = pos.cellX();
        int vy = window.virtualCellY(lvl, pos.cellY());
        int vz = pos.cellZ();
        if (vy < AllvrVoxyYWindow.VOXY_MIN_CELL_Y || vy > AllvrVoxyYWindow.VOXY_MAX_CELL_Y) {
            return false; // outside the virtual window — never silently alias
        }
        long key = VoxyApi_0215_1211.sectionKey(lvl, vx, vy, vz);
        me.cortex.voxy.common.world.WorldSection section = engine.acquire(lvl, vx, vy, vz);
        try {
            long[] raw = section._unsafeGetRawDataArray();
            int[] blockIds = VoxyApi_0215_1211.mappedBlockIds(engine.getMapper(), data.palette());
            int biomeId = engine.getMapper().getIdForBiome(biome);
            int[] indices = data.indices();
            byte[] light = data.light();
            for (int i = 0; i < AllvrLodSectionData.CELLS; i++) {
                raw[i] = me.cortex.voxy.common.world.other.Mapper.composeMappingId(
                    light[i], blockIds[indices[i]], biomeId);
            }
            if (lvl == 0) {
                int delta = nonAirCount(data) - section.getNonEmptyBlockCount();
                if (delta != 0) {
                    section.addNonEmptyBlockCount(delta);
                }
                section.updateLvl0State();
            }
            engine.markDirty(section,
                me.cortex.voxy.common.world.WorldEngine.UPDATE_TYPE_BLOCK_BIT
                    | me.cortex.voxy.common.world.WorldEngine.UPDATE_TYPE_CHILD_EXISTENCE_BIT
                    | me.cortex.voxy.common.world.WorldEngine.UPDATE_TYPE_DONT_SAVE,
                registry.neighborMask(lvl, vx, vy, vz));
            registry.register(lvl, key, vx, vy, vz, section, true, data.generation());
            chainAncestors(engine, registry, section, lvl, vx, vy, vz);
            return true;
        } catch (Throwable t) {
            try {
                section.release(me.cortex.voxy.common.world.WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
            } catch (Throwable ignored) {
            }
            throw t instanceof RuntimeException rt ? rt : new IllegalStateException(t);
        }
    }

    /**
     * Drops one owned node: air-fill first (a stale re-read can never
     * resurrect it), then the parent's child-bit revoke tears the node and
     * its subtree down (in-flight-safe inside Voxy's NodeManager), then the
     * six-neighbor remesh, then the upward cascade while each ancestor stays
     * childless and topology-only. Idempotent when the node was never ours.
     */
    static void forgetNode(me.cortex.voxy.common.world.WorldEngine engine,
                           AllvrVoxyYWindow window, AllvrVoxyNodeRegistry registry,
                           int lvl, long virtualKey) {
        int vx = VoxyApi_0215_1211.keyX(virtualKey);
        int vy = VoxyApi_0215_1211.keyY(virtualKey);
        int vz = VoxyApi_0215_1211.keyZ(virtualKey);
        me.cortex.voxy.common.world.WorldSection section = registry.take(lvl, virtualKey);
        if (section == null) {
            return; // never owned here — idempotent
        }
        try {
            clearSectionData(section);
        } catch (Throwable t) {
            CreateManaIndustry.LOGGER.error("[Allvr] voxy forget air-fill failed at {}",
                me.cortex.voxy.common.world.WorldEngine.pprintPos(virtualKey), t);
        }
        detachFromParent(engine, registry, section, lvl, vx, vy, vz);
        engine.markDirty(section,
            me.cortex.voxy.common.world.WorldEngine.UPDATE_TYPE_BLOCK_BIT
                | me.cortex.voxy.common.world.WorldEngine.UPDATE_TYPE_DONT_SAVE,
            registry.neighborMask(lvl, vx, vy, vz));
        section.release(me.cortex.voxy.common.world.WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
    }

    /** Counts non-air cells (palette index != 0) for the L0 block counter. */
    static int nonAirCount(AllvrLodSectionData data) {
        int n = 0;
        for (int idx : data.indices()) {
            if (idx != 0) {
                n++;
            }
        }
        return n;
    }

    /**
     * Chains the ancestor bits L+1..L4 so the traversal can find the node
     * from the top (sparse topology, plan §7.4): every ancestor gains (or
     * keeps) the child bit, and a changed ancestor publishes the
     * CHILD_EXISTENCE dirty event that makes its node adopt the child.
     * Level 4 belongs to Voxy's own tracker, so the walk releases there.
     */
    static void chainAncestors(me.cortex.voxy.common.world.WorldEngine engine,
                               AllvrVoxyNodeRegistry registry,
                               me.cortex.voxy.common.world.WorldSection child,
                               int lvl, int vx, int vy, int vz) {
        int ancLvl = lvl + 1;
        int ancX = vx;
        int ancY = vy;
        int ancZ = vz;
        me.cortex.voxy.common.world.WorldSection childSection = child;
        while (ancLvl <= me.cortex.voxy.common.world.WorldEngine.MAX_LOD_LAYER) {
            ancX >>= 1;
            ancY >>= 1;
            ancZ >>= 1;
            me.cortex.voxy.common.world.WorldSection ancestor =
                engine.acquire(ancLvl, ancX, ancY, ancZ);
            long ancKey = VoxyApi_0215_1211.sectionKey(ancLvl, ancX, ancY, ancZ);
            int changed = ancestor.updateEmptyChildState(childSection);
            if (changed != 0) {
                engine.markDirty(ancestor,
                    me.cortex.voxy.common.world.WorldEngine.UPDATE_TYPE_CHILD_EXISTENCE_BIT
                        | me.cortex.voxy.common.world.WorldEngine.UPDATE_TYPE_DONT_SAVE,
                    0);
                if (ancLvl < me.cortex.voxy.common.world.WorldEngine.MAX_LOD_LAYER) {
                    registry.register(ancLvl, ancKey, ancX, ancY, ancZ, ancestor, false, 0);
                } else {
                    ancestor.release(me.cortex.voxy.common.world.WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
                }
            } else {
                ancestor.release(me.cortex.voxy.common.world.WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
            }
            childSection = ancestor;
            ancLvl++;
        }
    }

    /** Air-fills one section (silent — no dirty events) so a stale read of
     *  the data array after the forget can never resurrect old geometry. */
    private static void clearSectionData(me.cortex.voxy.common.world.WorldSection section) {
        long[] raw = section._unsafeGetRawDataArray();
        java.util.Arrays.fill(raw, me.cortex.voxy.common.world.other.Mapper.airWithLight(15));
        if (section.lvl == 0) {
            int delta = -section.getNonEmptyBlockCount();
            if (delta != 0) {
                section.addNonEmptyBlockCount(delta);
            }
            section.updateLvl0State();
        }
        section._unsafeSetNonEmptyChildren((byte) 0);
    }

    /**
     * Revokes the child bit of {@code childSection} in each ancestor level,
     * walking up while each ancestor stays childless and topology-only (plan
     * §7.4). Level 4 belongs to Voxy's own tracker, so the cascade stops there.
     */
    static void detachFromParent(me.cortex.voxy.common.world.WorldEngine engine,
                                 AllvrVoxyNodeRegistry registry,
                                 me.cortex.voxy.common.world.WorldSection childSection,
                                 int lvl, int vx, int vy, int vz) {
        int ancLvl = lvl + 1;
        int ancX = vx;
        int ancY = vy;
        int ancZ = vz;
        while (ancLvl <= me.cortex.voxy.common.world.WorldEngine.MAX_LOD_LAYER) {
            ancX >>= 1;
            ancY >>= 1;
            ancZ >>= 1;
            me.cortex.voxy.common.world.WorldSection ancestor =
                engine.acquire(ancLvl, ancX, ancY, ancZ);
            long ancKey = VoxyApi_0215_1211.sectionKey(ancLvl, ancX, ancY, ancZ);
            int childIdx = me.cortex.voxy.common.world.WorldSection.getChildIndex(
                childSection.x, childSection.y, childSection.z);
            byte next = (byte) (ancestor.getNonEmptyChildren() & ~(1 << childIdx));
            ancestor._unsafeSetNonEmptyChildren(next);
            engine.markDirty(ancestor,
                me.cortex.voxy.common.world.WorldEngine.UPDATE_TYPE_CHILD_EXISTENCE_BIT
                    | me.cortex.voxy.common.world.WorldEngine.UPDATE_TYPE_DONT_SAVE,
                0);
            AllvrVoxyNodeRegistry.Entry owned = registry.takeEntry(ancLvl, ancKey);
            boolean keep = next != 0 || (owned != null && owned.dataOwned);
            if (owned != null) {
                if (keep) {
                    registry.register(ancLvl, ancKey, ancX, ancY, ancZ,
                        owned.section, owned.dataOwned, owned.generation);
                } else {
                    owned.section.release(me.cortex.voxy.common.world.WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
                }
            }
            if (keep) {
                ancestor.release(me.cortex.voxy.common.world.WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
                return;
            }
            childSection = ancestor;
            ancLvl++;
        }
    }
}
