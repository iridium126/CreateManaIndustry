package com.iridium126.createmanaindustry.client.dimension.lod;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

import com.iridium126.createmanaindustry.dimension.lod.AllvrLodSectionData;

/**
 * Client LOD rendering backend SPI (voxy integration plan §7.1) — the seam
 * between the request/bookkeeping half ({@code AllvrLodClientState}) and
 * whatever renders the far-terrain sections. No Voxy types here: the voxy
 * adapter lives behind this interface in {@code compat}-style classes that
 * only load when the probed Voxy build is present.
 * <p>
 * Lifecycle: {@link #enter} on client level join, one {@link #tick} per
 * client tick, {@link #leave} on unload/switch/backend switch. All calls
 * happen on the main thread, which is also Voxy's render thread.
 */
public interface AllvrLodBackend {

    /** Runtime capability verdict — probed once at backend selection. */
    Availability probe();

    /** Binds the backend to the client level (dimension entered / changed). */
    void enter(ClientLevel level);

    /**
     * Publishes one absolute-coordinate voxel section. Returns false when
     * the backend cannot take it right now (no engine, queue full, rebase in
     * flight) — the caller keeps the node un-meshed and the request walk
     * re-issues it later.
     */
    boolean apply(AllvrLodSectionData data, Holder<Biome> biome);

    /** Drops one node (server forget, band eviction, invalidation). */
    void forget(int level, long cellLong);

    /** Per-tick driver: engine binding, budgeted injection drain, rebase. */
    void tick(double cameraX, double cameraY, double cameraZ);

    /** Releases everything (owned refs, queues, engine binding). */
    void leave();

    /** True only while the live backend can accept a new section ticket. */
    default boolean requestsOpen() {
        return true;
    }

    /** Terminal writer failures that must return their nodes to the request walk. */
    default java.util.List<Failure> drainFailures() {
        return java.util.List.of();
    }

    /** One-line debug state for logs/overlays. */
    String debugState();

    /** Backend availability probe result. */
    record Availability(boolean available, String reason) {
        public static Availability ok() {
            return new Availability(true, "");
        }

        public static Availability fail(String reason) {
            return new Availability(false, reason);
        }
    }

    record Failure(int level, long cellLong) {}
}
