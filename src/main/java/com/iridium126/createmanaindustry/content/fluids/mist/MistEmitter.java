package com.iridium126.createmanaindustry.content.fluids.mist;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.iridium126.createmanaindustry.config.Config;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
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

    /**
     * Sync payload sent to registered client callbacks whenever mist state changes.
     * An empty fluid signals deactivation; radius is 0 when deactivated.
     */
    public record MistSyncData(BlockPos pos, FluidStack fluid, int radius) {}

    /** Shared client-sync callbacks. Thread-safe for concurrent registration. */
    private static final List<Consumer<MistSyncData>> syncCallbacks = new CopyOnWriteArrayList<>();

    private MistEmitter() {}

    // ---- persistent mist (atomizers, continuous emitters) --------------------

    /** Activate persistent mist at the given position. */
    public static void activate(Level level, BlockPos pos, int radius) {
        MistFieldStore.setActive(level, pos, true, radius);
        notifyCallbacks(pos, FluidStack.EMPTY, radius);
    }

    /** Activate persistent mist at the given position with a specific fluid. */
    public static void activate(Level level, BlockPos pos, FluidStack fluid, int radius) {
        MistFieldStore.setActive(level, pos, true, radius, fluid);
        notifyCallbacks(pos, fluid, radius);
    }

    /** Deactivate persistent mist at the given position. */
    public static void deactivate(Level level, BlockPos pos) {
        MistFieldStore.setActive(level, pos, false, 0);
        notifyCallbacks(pos, FluidStack.EMPTY, 0);
    }

    // ---- timed mist (recipe byproducts) -------------------------------------

    /**
     * Emit a timed mist puff at the given position. The mist persists for
     * {@code duration} ticks and then automatically expires.
     */
    public static void emitTimed(Level level, BlockPos pos, FluidStack fluid, int radius, int duration) {
        long expiryTick = level.getGameTime() + duration;
        MistFieldStore.addTimed(level, pos, fluid, radius, expiryTick);
        notifyCallbacks(pos, fluid, radius);
    }

    /**
     * Create or refresh a timed mist entry. If an entry already exists at
     * {@code pos}, its expiry is reset to {@code now + duration} and
     * {@code capacityAmount} is added to its fluid capacity. Otherwise a new
     * entry is created.
     */
    public static void emitOrExtendTimed(Level level, BlockPos pos, FluidStack fluid,
            int radius, int duration, long capacityAmount) {
        long expiry = level.getGameTime() + duration;
        MistFieldStore.emitOrExtendTimed(level, pos, fluid, radius, expiry, capacityAmount);
        notifyCallbacks(pos, fluid, radius);
    }

    /** Remove a timed mist entry before its natural expiry. */
    public static void removeTimed(Level level, BlockPos pos) {
        MistFieldStore.removeTimed(level, pos);
        notifyCallbacks(pos, FluidStack.EMPTY, 0);
    }

    // ---- query --------------------------------------------------------------

    public static boolean isActive(Level level, BlockPos pos) {
        return MistFieldStore.isInMist(level, pos);
    }

    public static float getConcentration(Level level, BlockPos pos) {
        return MistFieldStore.getConcentration(level, pos);
    }

    // ---- capacity management ------------------------------------------------

    /** Adds drained fluid to the persistent atomizer source at {@code pos}. */
    public static void addCapacity(Level level, BlockPos pos, long amount) {
        MistFieldStore.addCapacity(level, pos, amount);
    }

    /** Updates the radius of a persistent atomizer source in-place. */
    public static void updateRadius(Level level, BlockPos pos, int radius) {
        MistFieldStore.updateRadius(level, pos, radius);
    }

    /**
     * Consumes up to {@code desired} mB from mist sources matching
     * {@code fluidId}. Drains strongest-concentration sources first.
     *
     * @return total mB actually consumed
     */
    public static long consumeCapacity(Level level, BlockPos pos,
            ResourceLocation fluidId, long desired) {
        return MistFieldStore.consumeCapacity(level, pos, fluidId, desired);
    }

    // ---- tick ---------------------------------------------------------------

    /** Per-tick cleanup — expires timed entries and notifies the client. */
    public static void tick(ServerLevel level) {
        MistFieldStore.tick(level,
                expiredPos -> notifyCallbacks(expiredPos, FluidStack.EMPTY, 0));
    }

    // ---- client sync --------------------------------------------------------

    /**
     * Register a callback that receives position + fluid + radius updates
     * whenever mist state changes. Called by the client-side render handler
     * during initialization.
     */
    public static void registerSyncCallback(Consumer<MistSyncData> callback) {
        if (callback != null && !syncCallbacks.contains(callback))
            syncCallbacks.add(callback);
    }

    /**
     * Direct notification for client-side sync (e.g. from block entity
     * {@code read(clientPacket=true)} handlers).
     */
    public static void notifyClientSync(BlockPos pos, FluidStack fluid, int radius) {
        notifyCallbacks(pos, fluid, radius);
    }

    /** Backward-compatible overload — defaults radius to {@code Config.mistMaxRadius}. */
    public static void notifyClientSync(BlockPos pos, FluidStack fluid) {
        notifyCallbacks(pos, fluid, Config.mistMaxRadius);
    }

    private static void notifyCallbacks(BlockPos pos, FluidStack fluid, int radius) {
        if (syncCallbacks.isEmpty())
            return;
        MistSyncData data = new MistSyncData(pos, fluid, radius);
        for (Consumer<MistSyncData> cb : syncCallbacks)
            cb.accept(data);
    }
}
