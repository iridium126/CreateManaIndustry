package com.iridium126.createmanaindustry.client.dimension.lod.voxy;

import java.util.function.Consumer;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * Ownership ledger for every ALLVR-injected voxy section, keyed per level by
 * the VIRTUAL section key (voxy integration plan §7.4). The ledger holds one
 * {@code WorldSection} reference per owned node — that held reference keeps
 * the engine from recycling the section's data array while ALLVR still owns
 * the node (plan §7.3 step 2).
 * <p>
 * The cell long ALIASES across levels (see {@code AllvrLodPos}), so the level
 * is the map identity — never a combined key. Mutations happen only on the
 * render thread (via the single-writer drain), so plain maps are safe.
 */
public final class AllvrVoxyNodeRegistry {

    /** Neighbor-mask bit order — the same layout Voxy's updater publishes. */
    public static final int NEIGHBOR_NEG_Y = 1;
    public static final int NEIGHBOR_POS_Y = 2;
    public static final int NEIGHBOR_NEG_X = 4;
    public static final int NEIGHBOR_POS_X = 8;
    public static final int NEIGHBOR_NEG_Z = 16;
    public static final int NEIGHBOR_POS_Z = 32;

    /** One owned node: the held engine section plus its ownership role. */
    public static final class Entry {
        public final int level;
        public final long key;
        public final int cellX;
        public final int cellY;
        public final int cellZ;
        /** Held engine section — released on forget/detach (§7.3 step 2). */
        public final me.cortex.voxy.common.world.WorldSection section;
        /** True when ALLVR voxel data lives in this section (mutable role). */
        public boolean dataOwned;
        /** Newest generation injected into this node. */
        public long generation;

        Entry(int level, long key, int cellX, int cellY, int cellZ,
              me.cortex.voxy.common.world.WorldSection section, boolean dataOwned,
              long generation) {
            this.level = level;
            this.key = key;
            this.cellX = cellX;
            this.cellY = cellY;
            this.cellZ = cellZ;
            this.section = section;
            this.dataOwned = dataOwned;
            this.generation = generation;
        }
    }

    /** Per-level maps — level IS the identity (cell longs alias). */
    @SuppressWarnings("unchecked")
    private final Long2ObjectOpenHashMap<Entry>[] byLevel = new Long2ObjectOpenHashMap[VoxyApi_0215_1211.MAX_LOD_LAYER + 1];

    {
        for (int lvl = 0; lvl < this.byLevel.length; lvl++) {
            this.byLevel[lvl] = new Long2ObjectOpenHashMap<>();
        }
    }

    public int ownedCount() {
        int n = 0;
        for (var map : this.byLevel) {
            n += map.size();
        }
        return n;
    }

    public boolean isOwned(int level, long key) {
        return this.byLevel[level].containsKey(key);
    }

    public Entry entry(int level, long key) {
        return this.byLevel[level].get(key);
    }

    /** Removes and returns the ledger entry, or null when it was never ours. */
    public Entry takeEntry(int level, long key) {
        return this.byLevel[level].remove(key);
    }

    /** Removes the ledger entry and returns its held section, or null. */
    public me.cortex.voxy.common.world.WorldSection take(int level, long key) {
        Entry entry = this.byLevel[level].remove(key);
        return entry == null ? null : entry.section;
    }

    /**
     * Registers (or upgrades) one owned node. A topology-only ancestor that
     * later gains ALLVR data is upgraded in place — the held reference and
     * the ledger entry stay the same, only the role changes.
     */
    public boolean register(int level, long key, int cellX, int cellY, int cellZ,
                         me.cortex.voxy.common.world.WorldSection section,
                         boolean dataOwned, long generation) {
        Entry existing = this.byLevel[level].get(key);
        if (existing != null) {
            if (dataOwned && !existing.dataOwned) {
                existing.dataOwned = true;
            }
            if (generation > existing.generation) {
                existing.generation = generation;
            }
            return false;
        }
        this.byLevel[level].put(key,
            new Entry(level, key, cellX, cellY, cellZ, section, dataOwned, generation));
        return true;
    }

    /** Six-direction neighbor bits for nodes adjacent to (x, y, z) at level. */
    public int neighborMask(int level, int cellX, int cellY, int cellZ) {
        int mask = 0;
        if (this.isOwned(level, entryKey(level, cellX, cellY - 1, cellZ))) {
            mask |= NEIGHBOR_NEG_Y;
        }
        if (this.isOwned(level, entryKey(level, cellX, cellY + 1, cellZ))) {
            mask |= NEIGHBOR_POS_Y;
        }
        if (this.isOwned(level, entryKey(level, cellX - 1, cellY, cellZ))) {
            mask |= NEIGHBOR_NEG_X;
        }
        if (this.isOwned(level, entryKey(level, cellX + 1, cellY, cellZ))) {
            mask |= NEIGHBOR_POS_X;
        }
        if (this.isOwned(level, entryKey(level, cellX, cellY, cellZ - 1))) {
            mask |= NEIGHBOR_NEG_Z;
        }
        if (this.isOwned(level, entryKey(level, cellX, cellY, cellZ + 1))) {
            mask |= NEIGHBOR_POS_Z;
        }
        return mask;
    }

    /** Direct owned-child bits for a topology node at {@code level}. */
    public int ownedChildMask(int level, int cellX, int cellY, int cellZ) {
        if (level <= 0) {
            return 0;
        }
        int mask = 0;
        int childLevel = level - 1;
        for (int y = 0; y <= 1; y++) {
            for (int z = 0; z <= 1; z++) {
                for (int x = 0; x <= 1; x++) {
                    if (this.isOwned(childLevel, entryKey(childLevel,
                        (cellX << 1) + x, (cellY << 1) + y, (cellZ << 1) + z))) {
                        int index = (y << 2) | (z << 1) | x;
                        mask |= 1 << index;
                    }
                }
            }
        }
        return mask;
    }

    /** The Voxy section key for a virtual (level, x, y, z). */
    public static long entryKey(int level, int cellX, int cellY, int cellZ) {
        return me.cortex.voxy.common.world.WorldEngine.getWorldSectionId(level, cellX, cellY, cellZ);
    }

    public void forEachOwned(Consumer<Entry> visitor) {
        for (var map : this.byLevel) {
            for (var entry : map.values()) {
                visitor.accept(entry);
            }
        }
    }

    /** Removes all ledger entries after the caller has released their refs. */
    public void clear() {
        for (var map : this.byLevel) {
            map.clear();
        }
    }
}
