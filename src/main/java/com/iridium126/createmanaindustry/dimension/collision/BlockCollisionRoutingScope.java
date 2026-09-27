package com.iridium126.createmanaindustry.dimension.collision;

import org.jetbrains.annotations.Nullable;

import com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;

import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/** Carries the original broad-sweep Allay routing decision into axis sub-queries. */
public final class BlockCollisionRoutingScope implements AutoCloseable {
    private static final ThreadLocal<Level> FORCED_CUBE_BACKED_LEVEL = new ThreadLocal<>();

    @Nullable
    private final Level previousLevel;
    private final boolean changed;
    private boolean closed;

    private BlockCollisionRoutingScope(@Nullable Level previousLevel, boolean changed) {
        this.previousLevel = previousLevel;
        this.changed = changed;
    }

    public static BlockCollisionRoutingScope preserveOriginalRoute(Level level, AABB originalSweep) {
        if (!requiresCubeBackedGetter(level, originalSweep)) {
            return new BlockCollisionRoutingScope(null, false);
        }
        Level previous = FORCED_CUBE_BACKED_LEVEL.get();
        FORCED_CUBE_BACKED_LEVEL.set(level);
        return new BlockCollisionRoutingScope(previous, true);
    }

    public static boolean isForcedFor(Level level) {
        return FORCED_CUBE_BACKED_LEVEL.get() == level;
    }

    public static boolean requiresCubeBackedGetter(Level level, AABB box) {
        return level.dimension() == AllvrDimensions.ALLAY_LEVEL
            && (Mth.floor(box.minY - 1.0E-7) - 1 < AllvrDimensionLimits.VANILLA_MIN_Y
                || Mth.floor(box.maxY + 1.0E-7) + 1 >= AllvrDimensionLimits.VANILLA_MAX_Y);
    }

    @Override
    public void close() {
        if (this.closed) return;
        this.closed = true;
        if (this.changed) {
            if (this.previousLevel == null) {
                FORCED_CUBE_BACKED_LEVEL.remove();
            } else {
                FORCED_CUBE_BACKED_LEVEL.set(this.previousLevel);
            }
        }
    }
}
