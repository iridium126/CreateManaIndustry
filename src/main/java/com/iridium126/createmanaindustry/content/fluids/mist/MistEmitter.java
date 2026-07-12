package com.iridium126.createmanaindustry.content.fluids.mist;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Central API for activating, deactivating, and querying mist fields.
 * <p>
 * Wraps {@link MistFieldStore} and provides a unified entry point for both
 * persistent mist emitters (e.g. {@code KineticAtomizerBlockEntity}) and
 * timed one-shot emissions (e.g. recipe byproducts via
 * {@link #emitTimed}).
 * <p>
 * Client sync is handled through a shared list of callbacks. Any system
 * that needs to update the client-side mist state (rendered by
 * {@code ClientMistHandler}) can register here.
 */
public final class MistEmitter {

    /** Shared client-sync callbacks. Thread-safe for concurrent registration. */
    private static final List<BiConsumer<BlockPos, FluidStack>> syncCallbacks = new CopyOnWriteArrayList<>();

    private MistEmitter() {}

    // ---- persistent mist (atomizers, continuous emitters) --------------------

    /** Activate persistent mist at the given position. */
    public static void activate(Level level, BlockPos pos, int radius) {
        MistFieldStore.setActive(level, pos, true, radius);
        notifyCallbacks(pos, FluidStack.EMPTY); // non-empty means active (atomizer fluid is known by client)
    }

    /** Activate persistent mist at the given position with a specific fluid. */
    public static void activate(Level level, BlockPos pos, FluidStack fluid, int radius) {
        MistFieldStore.setActive(level, pos, true, radius, fluid);
        notifyCallbacks(pos, fluid);
    }

    /** Deactivate persistent mist at the given position. */
    public static void deactivate(Level level, BlockPos pos) {
        MistFieldStore.setActive(level, pos, false, 0);
        notifyCallbacks(pos, FluidStack.EMPTY);
    }

    // ---- timed mist (recipe byproducts) -------------------------------------

    /**
     * Emit a timed mist puff at the given position. The mist persists for
     * {@code durationTicks} ticks and then automatically expires.
     *
     * @param level         the level (server-side only)
     * @param pos           source position
     * @param fluid         the fluid whose color tints the mist
     * @param radius        field radius in blocks
     * @param durationTicks duration in ticks before expiry
     */
    public static void emitTimed(Level level, BlockPos pos, FluidStack fluid, int radius, int durationTicks) {
        long expiryTick = level.getGameTime() + durationTicks;
        MistFieldStore.addTimed(level, pos, fluid, radius, expiryTick);
        notifyCallbacks(pos, fluid);
    }

    /** Remove a timed mist entry before its natural expiry. */
    public static void removeTimed(Level level, BlockPos pos) {
        MistFieldStore.removeTimed(level, pos);
        notifyCallbacks(pos, FluidStack.EMPTY);
    }

    // ---- query --------------------------------------------------------------

    public static boolean isActive(Level level, BlockPos pos) {
        return MistFieldStore.isInMist(level, pos);
    }

    public static float getConcentration(Level level, BlockPos pos) {
        return MistFieldStore.getConcentration(level, pos);
    }

    // ---- client sync --------------------------------------------------------

    /**
     * Register a callback that receives position + fluid updates whenever mist
     * state changes. Called by the client-side render handler during
     * initialization, and by block entity sync handlers on the client.
     * <p>
     * A {@link FluidStack#EMPTY} fluid signals that the mist at this position
     * is now inactive.
     */
    public static void registerSyncCallback(BiConsumer<BlockPos, FluidStack> callback) {
        if (callback != null && !syncCallbacks.contains(callback))
            syncCallbacks.add(callback);
    }

    /**
     * Direct notification for client-side sync (e.g. from block entity
     * {@code read(clientPacket=true)} handlers). Unlike
     * {@link #activate}/{@link #deactivate} which run server-side,
     * this can be called on the client to push updates from synced BE data.
     */
    public static void notifyClientSync(BlockPos pos, FluidStack fluid) {
        notifyCallbacks(pos, fluid);
    }

    private static void notifyCallbacks(BlockPos pos, FluidStack fluid) {
        if (syncCallbacks.isEmpty())
            return;
        for (BiConsumer<BlockPos, FluidStack> cb : syncCallbacks)
            cb.accept(pos, fluid);
    }
}
