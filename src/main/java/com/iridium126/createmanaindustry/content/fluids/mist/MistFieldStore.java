package com.iridium126.createmanaindustry.content.fluids.mist;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.iridium126.createmanaindustry.config.Config;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Central data store for active Kinetic Atomizer mist fields. Follows the
 * {@code TemporaryStress} pattern: a static utility class backed by a
 * per-dimension {@link ConcurrentHashMap}.
 * <p>
 * Each active atomizer is stored with its position and field parameters.
 * Concentration is computed on the fly at query time using Manhattan distance
 * — no cached concentration grids are maintained, so block changes are
 * automatically reflected.
 */
public final class MistFieldStore {

    /** Per-dimension map of atomizer positions to their field parameters. */
    private static final Map<ResourceKey<Level>, Map<BlockPos, AtomizerField>> ACTIVE = new ConcurrentHashMap<>();

    /** Per-dimension map of timed mist entries (recipe byproducts) with expiry. */
    private static final Map<ResourceKey<Level>, Map<BlockPos, TimedMistEntry>> TIMED = new ConcurrentHashMap<>();

    private MistFieldStore() {}

    /**
     * Called by {@code KineticAtomizerBlockEntity} on state transitions (inactive
     * → active or active → inactive). Not called every tick.
     *
     * @param active {@code true} to register this atomizer; {@code false} to remove
     */
    public static void setActive(Level level, BlockPos pos, boolean active, int radius) {
        if (level == null || level.isClientSide)
            return;

        ResourceKey<Level> dim = level.dimension();
        if (active) {
            ACTIVE.computeIfAbsent(dim, k -> new ConcurrentHashMap<>())
                    .put(pos.immutable(), new AtomizerField(radius));
        } else {
            Map<BlockPos, AtomizerField> dimFields = ACTIVE.get(dim);
            if (dimFields != null) {
                dimFields.remove(pos);
                if (dimFields.isEmpty())
                    ACTIVE.remove(dim, dimFields);
            }
        }
    }

    /**
     * Queries the mist concentration at a given position.
     * <ul>
     * <li>Only air blocks can contain mist — checked at query time so block
     * changes are automatically reflected.</li>
     * <li>If multiple atomizer fields overlap, the <b>maximum</b> concentration
     * is returned.</li>
     * </ul>
     *
     * @return concentration in {@code [0, mistBaseConcentration]}, or {@code 0}
     *         if the position is not in any mist field
     */
    public static float getConcentration(Level level, BlockPos pos) {
        if (level == null || pos == null)
            return 0f;

        // Mist only exists in air blocks
        BlockState state = level.getBlockState(pos);
        if (!state.isAir())
            return 0f;

        ResourceKey<Level> dim = level.dimension();
        float maxConc = 0f;

        // Check persistent entries
        Map<BlockPos, AtomizerField> dimFields = ACTIVE.get(dim);
        if (dimFields != null && !dimFields.isEmpty()) {
            for (var entry : dimFields.entrySet()) {
                float conc = calcConcentration(pos, entry.getKey(), entry.getValue().radius());
                if (conc > maxConc)
                    maxConc = conc;
            }
        }

        // Check timed entries
        Map<BlockPos, TimedMistEntry> dimTimed = TIMED.get(dim);
        if (dimTimed != null && !dimTimed.isEmpty()) {
            for (var entry : dimTimed.entrySet()) {
                float conc = calcConcentration(pos, entry.getKey(), entry.getValue().radius());
                if (conc > maxConc)
                    maxConc = conc;
            }
        }

        return maxConc;
    }

    private static float calcConcentration(BlockPos queryPos, BlockPos sourcePos, int radius) {
        double dx = queryPos.getX() - sourcePos.getX();
        double dy = queryPos.getY() - sourcePos.getY();
        double dz = queryPos.getZ() - sourcePos.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (dist <= radius) {
            return (float) (Config.mistBaseConcentration * (1.0 - dist / radius));
        }
        return 0f;
    }

    // ---- timed mist entries (recipe byproducts) ------------------------------

    /**
     * Registers a timed mist emission at the given position. Used for one-shot
     * recipe byproducts that should persist for a fixed duration.
     *
     * @param fluid        the fluid whose color determines mist appearance
     * @param radius       field radius in blocks
     * @param expiryTick   absolute tick when the entry expires
     */
    public static void addTimed(Level level, BlockPos pos, FluidStack fluid, int radius, long expiryTick) {
        if (level == null || level.isClientSide || pos == null)
            return;

        ResourceKey<Level> dim = level.dimension();
        TIMED.computeIfAbsent(dim, k -> new ConcurrentHashMap<>())
                .put(pos.immutable(), new TimedMistEntry(fluid, radius, expiryTick));
    }

    /** Removes a timed mist entry early (e.g. when a recipe source is removed). */
    public static void removeTimed(Level level, BlockPos pos) {
        if (level == null || level.isClientSide)
            return;

        ResourceKey<Level> dim = level.dimension();
        Map<BlockPos, TimedMistEntry> dimTimed = TIMED.get(dim);
        if (dimTimed != null) {
            dimTimed.remove(pos);
            if (dimTimed.isEmpty())
                TIMED.remove(dim, dimTimed);
        }
    }

    /**
     * Convenience check for whether a position has any mist concentration.
     *
     * @return {@code true} if {@link #getConcentration(Level, BlockPos)} &gt; 0
     */
    public static boolean isInMist(Level level, BlockPos pos) {
        return getConcentration(level, pos) > 0f;
    }

    /**
     * Called every tick via {@link MistFieldTicker} to:
     * <ul>
     *   <li>Remove persistent atomizers whose chunks are no longer loaded.</li>
     *   <li>Expire timed entries whose expiry tick has passed.</li>
     * </ul>
     */
    public static void tick(ServerLevel level) {
        ResourceKey<Level> dim = level.dimension();

        // Clean up persistent entries for unloaded chunks
        Map<BlockPos, AtomizerField> dimFields = ACTIVE.get(dim);
        if (dimFields != null && !dimFields.isEmpty()) {
            dimFields.entrySet().removeIf(entry -> !level.isPositionEntityTicking(entry.getKey()));
            if (dimFields.isEmpty())
                ACTIVE.remove(dim, dimFields);
        }

        // Expire timed entries
        Map<BlockPos, TimedMistEntry> dimTimed = TIMED.get(dim);
        if (dimTimed != null && !dimTimed.isEmpty()) {
            long currentTick = level.getGameTime();
            dimTimed.entrySet().removeIf(entry -> entry.getValue().expiryTick() <= currentTick);
            if (dimTimed.isEmpty())
                TIMED.remove(dim, dimTimed);
        }
    }

    /**
     * Immutable record storing per-atomizer field parameters.
     */
    private record AtomizerField(int radius) {}

    /**
     * Immutable record for timed mist entries with expiry.
     */
    private record TimedMistEntry(FluidStack fluid, int radius, long expiryTick) {}
}
