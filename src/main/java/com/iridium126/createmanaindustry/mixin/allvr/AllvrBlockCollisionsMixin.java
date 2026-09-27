package com.iridium126.createmanaindustry.mixin.allvr;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.iridium126.createmanaindustry.dimension.collision.BlockCollisionChunkCacheAccess;
import com.iridium126.createmanaindustry.dimension.collision.BlockCollisionRoutingScope;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * Routes Allay collision boxes that cross the vanilla Y band through Level's cube-aware lookup.
 * For large, ordinary server-level scans, it also memoizes stable chunk getters within this one
 * iterator. Block positions and collision shapes are still visited in vanilla order.
 */
@Mixin(BlockCollisions.class)
public abstract class AllvrBlockCollisionsMixin implements BlockCollisionChunkCacheAccess {

    @Unique private static final long cmi$MIN_SCAN_CELLS = 8_192L;
    @Unique private static final int cmi$MAX_CACHED_CHUNKS = 4_096;
    @Unique private static final int cmi$INITIAL_CACHE_CAPACITY = 128;

    @Shadow @Final private net.minecraft.world.phys.AABB box;

    @Shadow
    @Final
    private CollisionGetter collisionGetter;

    @Unique private boolean cmi$cacheDecisionMade;
    @Unique private boolean cmi$cacheChunkGetters;
    @Unique private boolean cmi$countOriginalGetterCalls;
    @Unique private long cmi$chunkGetterCacheHits;
    @Unique private long cmi$originalGetterCalls;
    @Unique private Long2ObjectOpenHashMap<BlockGetter> cmi$chunkGetterCache;

    /** A boundary-spanning iterator needs the Level's per-position routing for both stores. */
    @WrapOperation(method = "getChunk", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/CollisionGetter;getChunkForCollisions(II)Lnet/minecraft/world/level/BlockGetter;"))
    private BlockGetter allvr$useCubeBackedGetter(CollisionGetter getter, int chunkX, int chunkZ,
                                                   Operation<BlockGetter> original) {
        if (this.collisionGetter instanceof Level level
            && level.dimension() == AllvrDimensions.ALLAY_LEVEL
            && (BlockCollisionRoutingScope.isForcedFor(level)
                || BlockCollisionRoutingScope.requiresCubeBackedGetter(level, box))) {
            return level;
        }

        // On a large rectangular scan, Cursor3D revisits the same X/Z chunk for each block row
        // (and for each Y layer). Level#getChunkForCollisions is stable for a chunk during this
        // synchronous iterator, while BlockCollisions' built-in one-entry cache only covers
        // adjacent cells. Keep the cache bounded and avoid any extra state on ordinary queries.
        if (this.collisionGetter instanceof ServerLevel serverLevel
            && serverLevel.dimension() != AllvrDimensions.ALLAY_LEVEL) {
            if (!cmi$cacheDecisionMade) {
                cmi$cacheDecisionMade = true;
                cmi$cacheChunkGetters = cmi$shouldCacheChunkGetters(box);
                if (cmi$cacheChunkGetters) {
                    cmi$chunkGetterCache = new Long2ObjectOpenHashMap<>(cmi$INITIAL_CACHE_CAPACITY);
                }
            }
            if (cmi$cacheChunkGetters) {
                long key = ChunkPos.asLong(chunkX, chunkZ);
                BlockGetter cached = cmi$chunkGetterCache.get(key);
                if (cached != null) {
                    if (cmi$countOriginalGetterCalls) {
                        cmi$chunkGetterCacheHits++;
                    }
                    return cached;
                }
                BlockGetter resolved = cmi$callOriginal(getter, chunkX, chunkZ, original);
                // A null result is intentionally not cached: vanilla retries it on a later
                // miss, which can matter while a chunk is becoming available.
                if (resolved != null && cmi$chunkGetterCache.size() < cmi$MAX_CACHED_CHUNKS) {
                    cmi$chunkGetterCache.put(key, resolved);
                }
                return resolved;
            }
        }
        return cmi$callOriginal(getter, chunkX, chunkZ, original);
    }

    @Unique
    private BlockGetter cmi$callOriginal(CollisionGetter getter, int chunkX, int chunkZ,
                                          Operation<BlockGetter> original) {
        if (cmi$countOriginalGetterCalls) {
            cmi$originalGetterCalls++;
        }
        return original.call(getter, chunkX, chunkZ);
    }

    @Unique
    private static boolean cmi$shouldCacheChunkGetters(AABB query) {
        if (!Double.isFinite(query.minX) || !Double.isFinite(query.minY) || !Double.isFinite(query.minZ)
            || !Double.isFinite(query.maxX) || !Double.isFinite(query.maxY) || !Double.isFinite(query.maxZ)) {
            return false;
        }
        long xCells = cmi$cellCount(query.minX, query.maxX);
        long yCells = cmi$cellCount(query.minY, query.maxY);
        long zCells = cmi$cellCount(query.minZ, query.maxZ);
        if (xCells <= 0 || yCells <= 0 || zCells <= 0) {
            return false;
        }
        if (xCells * yCells * zCells < cmi$MIN_SCAN_CELLS) {
            return false;
        }
        // A multi-row horizontal scan is needed for a chunk to be revisited after the vanilla
        // one-entry cache has moved on. Thin vertical queries already retain one chunk naturally.
        return xCells > 16 && zCells > 1 || zCells > 16 && xCells > 1;
    }

    @Unique
    private static long cmi$cellCount(double min, double max) {
        long first = (long) Mth.floor(min - 1.0E-7) - 1L;
        long last = (long) Mth.floor(max + 1.0E-7) + 1L;
        return Math.min(last - first + 1L, cmi$MIN_SCAN_CELLS + 1L);
    }

    @Override
    public int cmi$getCachedChunkGetterCount() {
        return cmi$chunkGetterCache == null ? 0 : cmi$chunkGetterCache.size();
    }

    @Override
    public long cmi$getChunkGetterCacheHitCount() {
        return cmi$chunkGetterCacheHits;
    }

    @Override
    public long cmi$getOriginalChunkGetterCallCount() {
        return cmi$originalGetterCalls;
    }

    @Override
    public void cmi$configureChunkGetterCacheForTest(boolean enabled, boolean countOriginalCalls) {
        cmi$cacheDecisionMade = true;
        cmi$cacheChunkGetters = enabled;
        cmi$countOriginalGetterCalls = countOriginalCalls;
        cmi$chunkGetterCacheHits = 0L;
        cmi$originalGetterCalls = 0L;
        cmi$chunkGetterCache = enabled ? new Long2ObjectOpenHashMap<>(cmi$INITIAL_CACHE_CAPACITY) : null;
    }

    @Override
    public void cmi$countOriginalChunkGetterCallsForTest() {
        cmi$countOriginalGetterCalls = true;
    }
}
