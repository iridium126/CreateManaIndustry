package com.iridium126.createmanaindustry.content.fluids.mist;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.iridium126.createmanaindustry.config.Config;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

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

        Map<BlockPos, AtomizerField> dimFields = ACTIVE.get(level.dimension());
        if (dimFields == null || dimFields.isEmpty())
            return 0f;

        float maxConc = 0f;
        for (var entry : dimFields.entrySet()) {
            BlockPos ap = entry.getKey();
            int radius = entry.getValue().radius();

            double dx = pos.getX() - ap.getX();
                double dy = pos.getY() - ap.getY();
                double dz = pos.getZ() - ap.getZ();
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

            if (dist <= radius) {
                float conc = (float) (Config.mistBaseConcentration * (1.0 - dist / radius));
                if (conc > maxConc)
                    maxConc = conc;
            }
        }
        return maxConc;
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
     * Called every tick via {@link MistFieldTicker} to remove atomizers whose
     * chunks are no longer loaded — a safety net for chunk unloads that miss
     * the {@code invalidate()} callback.
     */
    public static void tick(ServerLevel level) {
        Map<BlockPos, AtomizerField> dimFields = ACTIVE.get(level.dimension());
        if (dimFields == null || dimFields.isEmpty())
            return;

        dimFields.entrySet().removeIf(entry -> !level.isPositionEntityTicking(entry.getKey()));
        if (dimFields.isEmpty())
            ACTIVE.remove(level.dimension(), dimFields);
    }

    /**
     * Immutable record storing per-atomizer field parameters. Extendable with
     * additional parameters (custom concentration, etc.) in the future.
     */
    private record AtomizerField(int radius) {}
}
