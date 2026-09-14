package com.iridium126.createmanaindustry.mixin.allvr;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubeMap;
import com.iridium126.createmanaindustry.dimension.cube.AllvrServerLevelDuck;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import net.minecraft.world.level.LightLayer;

/**
 * Routes only positions outside [-128, 384) to cubes. Central block access
 * falls through to Level/LevelChunk, including NeoForge hooks and mod mixins.
 */
@Mixin(Level.class)
public abstract class AllvrLevelMixin {

    /** Routes vanilla gameplay light queries to the cube engine. */
    public int getBrightness(LightLayer type, BlockPos pos) {
        Level self = (Level) (Object) this;
        AllvrCubeMap map = AllvrDimensionLimits.isVanillaY(pos.getY()) ? null : allvr$map();
        if (map != null) {
            return map.light(type, pos);
        }
        if (self.isClientSide && self.dimension() == AllvrDimensions.ALLAY_LEVEL
            && !AllvrDimensionLimits.isVanillaY(pos.getY())) {
            Integer light = com.iridium126.createmanaindustry.dimension.AllvrClientBlockHook.light(type, pos);
            if (light != null) {
                return light;
            }
        }
        return self.getLightEngine().getLayerListener(type).getLightValue(pos);
    }

    /** Keeps LevelReader#getRawBrightness on the same source as getBrightness. */
    public int getRawBrightness(BlockPos pos, int amount) {
        Level self = (Level) (Object) this;
        AllvrCubeMap map = AllvrDimensionLimits.isVanillaY(pos.getY()) ? null : allvr$map();
        if (map != null) {
            return map.rawLight(pos, amount);
        }
        if (self.isClientSide && self.dimension() == AllvrDimensions.ALLAY_LEVEL
            && !AllvrDimensionLimits.isVanillaY(pos.getY())) {
            Integer light = com.iridium126.createmanaindustry.dimension.AllvrClientBlockHook.rawLight(pos, amount);
            if (light != null) {
                return light;
            }
        }
        return self.getLightEngine().getRawBrightness(pos, amount);
    }

    /** Use native chunk biome palettes in the central band and cube palettes outside it. */
    public net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> getNoiseBiome(int x, int y, int z) {
        Level self = (Level) (Object) this;
        if (self.dimension() == AllvrDimensions.ALLAY_LEVEL && !AllvrDimensionLimits.isVanillaY(y * 4)) {
            if (self.isClientSide) {
                var biome = com.iridium126.createmanaindustry.dimension.AllvrClientBlockHook.biome(x, y, z);
                if (biome != null) return biome;
                // A missing cube is void, not a reason to synchronously ask
                // ClientChunkCache for a vanilla column.  Keep the lookup
                // side-effect free until the cube stream reaches this area.
                return self.getUncachedNoiseBiome(x, y, z);
            } else {
                AllvrCubeMap map = allvr$map();
                if (map != null) {
                    var cube = map.peek(new BlockPos(x * 4, y * 4, z * 4));
                    return cube == null ? map.generator().biome(x, y, z) : cube.getNoiseBiome(x, y, z);
                }
            }
        }
        var chunk = self.getChunk(x >> 2, z >> 2, net.minecraft.world.level.chunk.status.ChunkStatus.BIOMES, false);
        return chunk == null ? self.getUncachedNoiseBiome(x, y, z) : chunk.getNoiseBiome(x, y, z);
    }

    @Shadow
    public abstract void updateNeighbourForOutputSignal(BlockPos pos, Block block);

    @Unique
    private AllvrCubeMap allvr$map() {
        Level self = (Level) (Object) this;
        if (self.isClientSide || self.dimension() != AllvrDimensions.ALLAY_LEVEL) {
            return null;
        }
        return self instanceof AllvrServerLevelDuck duck ? duck.allvr$getCubeMap() : null;
    }

    /** Returns null when this is not a cube-backed Allay position. */
    @Unique
    private Boolean allvr$isCubeLoaded(BlockPos pos) {
        Level self = (Level) (Object) this;
        if (self.dimension() != AllvrDimensions.ALLAY_LEVEL
            || AllvrDimensionLimits.isVanillaY(pos.getY())) {
            return null;
        }
        if (self.isClientSide) {
            return com.iridium126.createmanaindustry.dimension.AllvrClientBlockHook.isLoaded(pos);
        }
        AllvrCubeMap map = allvr$map();
        return map == null ? Boolean.FALSE : map.isLoaded(pos);
    }

    /** Preserve LevelReader's native X/Z-only hasChunk query. */
    @Unique
    private boolean allvr$nativeHasChunk(int chunkX, int chunkZ) {
        Level self = (Level) (Object) this;
        return self.getChunkSource().hasChunk(chunkX, chunkZ);
    }

    /** Preserve LevelReader's native X/Z rectangle query. */
    @Unique
    private boolean allvr$nativeHasChunksAt(int fromX, int fromZ, int toX, int toZ) {
        int minChunkX = SectionPos.blockToSectionCoord(Math.min(fromX, toX));
        int maxChunkX = SectionPos.blockToSectionCoord(Math.max(fromX, toX));
        int minChunkZ = SectionPos.blockToSectionCoord(Math.min(fromZ, toZ));
        int maxChunkZ = SectionPos.blockToSectionCoord(Math.max(fromZ, toZ));
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                if (!allvr$nativeHasChunk(chunkX, chunkZ)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Checks every loaded cube intersecting an X/Y/Z box. The central band is
     * checked through the native column cache; cube sections are checked by
     * their resident 32-block cube key without generating anything.
     */
    @Unique
    private boolean allvr$hasChunksAt(int fromX, int fromY, int fromZ,
                                      int toX, int toY, int toZ) {
        Level self = (Level) (Object) this;
        int minX = Math.min(fromX, toX), maxX = Math.max(fromX, toX);
        int minY = Math.min(fromY, toY), maxY = Math.max(fromY, toY);
        int minZ = Math.min(fromZ, toZ), maxZ = Math.max(fromZ, toZ);
        if (self.dimension() != AllvrDimensions.ALLAY_LEVEL) {
            if (maxY < self.getMinBuildHeight() || minY >= self.getMaxBuildHeight()) {
                return false;
            }
            return allvr$nativeHasChunksAt(minX, minZ, maxX, maxZ);
        }
        if (maxY < -AllvrDimensionLimits.Y_BOUND || minY > AllvrDimensionLimits.Y_BOUND) {
            return false;
        }

        if (minY < AllvrDimensionLimits.VANILLA_MAX_Y
            && maxY >= AllvrDimensionLimits.VANILLA_MIN_Y
            && !allvr$nativeHasChunksAt(minX, minZ, maxX, maxZ)) {
            return false;
        }

        int minCubeX = Math.floorDiv(minX, 32);
        int maxCubeX = Math.floorDiv(maxX, 32);
        int minCubeZ = Math.floorDiv(minZ, 32);
        int maxCubeZ = Math.floorDiv(maxZ, 32);
        int lowerMaxY = Math.min(maxY, AllvrDimensionLimits.VANILLA_MIN_Y - 1);
        if (minY <= lowerMaxY
            && !allvr$hasCubeRange(minCubeX, Math.floorDiv(minY, 32), minCubeZ,
                maxCubeX, Math.floorDiv(lowerMaxY, 32), maxCubeZ)) {
            return false;
        }
        int upperMinY = Math.max(minY, AllvrDimensionLimits.VANILLA_MAX_Y);
        if (upperMinY <= maxY
            && !allvr$hasCubeRange(minCubeX, Math.floorDiv(upperMinY, 32), minCubeZ,
                maxCubeX, Math.floorDiv(maxY, 32), maxCubeZ)) {
            return false;
        }
        return true;
    }

    @Unique
    private boolean allvr$hasCubeRange(int minCubeX, int minCubeY, int minCubeZ,
                                       int maxCubeX, int maxCubeY, int maxCubeZ) {
        for (int cubeY = minCubeY; cubeY <= maxCubeY; cubeY++) {
            for (int cubeZ = minCubeZ; cubeZ <= maxCubeZ; cubeZ++) {
                for (int cubeX = minCubeX; cubeX <= maxCubeX; cubeX++) {
                    BlockPos sample = new BlockPos(cubeX << 5, cubeY << 5, cubeZ << 5);
                    Boolean loaded = allvr$isCubeLoaded(sample);
                    if (!Boolean.TRUE.equals(loaded)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** X/Z-only LevelReader overload. */
    public boolean hasChunkAt(int x, int z) {
        Level self = (Level) (Object) this;
        if (self.isClientSide && self.dimension() == AllvrDimensions.ALLAY_LEVEL) {
            Boolean cubeLoaded = com.iridium126.createmanaindustry.dimension.AllvrClientBlockHook
                .isChunkLoaded(x, z);
            if (cubeLoaded != null) {
                return cubeLoaded;
            }
        }
        return allvr$nativeHasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
    }

    /** Y-aware LevelReader overload used by neighbor and comparator updates. */
    public boolean hasChunkAt(BlockPos pos) {
        Boolean loaded = allvr$isCubeLoaded(pos);
        return loaded != null ? loaded : allvr$nativeHasChunk(
            SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
    }

    public boolean hasChunksAt(BlockPos from, BlockPos to) {
        return allvr$hasChunksAt(from.getX(), from.getY(), from.getZ(),
            to.getX(), to.getY(), to.getZ());
    }

    public boolean hasChunksAt(int fromX, int fromY, int fromZ,
                               int toX, int toY, int toZ) {
        return allvr$hasChunksAt(fromX, fromY, fromZ, toX, toY, toZ);
    }

    public boolean hasChunksAt(int fromX, int fromZ, int toX, int toZ) {
        return allvr$nativeHasChunksAt(fromX, fromZ, toX, toZ);
    }

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void allvr$getBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        AllvrCubeMap map = AllvrDimensionLimits.isVanillaY(pos.getY()) ? null : allvr$map();
        if (map != null) {
            cir.setReturnValue(map.getBlockState(pos));
        }
    }

    /**
     * Targets the 4-arg real implementation — both
     * {@code setBlock(pos, state, flags)} and all recursive neighbour-update
     * paths funnel through it.
     */
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
        at = @At("HEAD"), cancellable = true)
    private void allvr$setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                CallbackInfoReturnable<Boolean> cir) {
        AllvrCubeMap map = AllvrDimensionLimits.isVanillaY(pos.getY()) ? null : allvr$map();
        if (map != null) {
            cir.setReturnValue(map.setBlock(pos, state, flags, recursionLeft));
        }
    }

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void allvr$getFluidState(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
        AllvrCubeMap map = AllvrDimensionLimits.isVanillaY(pos.getY()) ? null : allvr$map();
        if (map != null) {
            cir.setReturnValue(map.getBlockState(pos).getFluidState());
        }
    }

    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
    private void allvr$getBlockEntity(BlockPos pos, CallbackInfoReturnable<BlockEntity> cir) {
        AllvrCubeMap map = AllvrDimensionLimits.isVanillaY(pos.getY()) ? null : allvr$map();
        if (map != null) {
            cir.setReturnValue(map.getBlockEntity(pos));
        }
    }

    /**
     * LevelAccessor exposes scheduleTick as interface defaults. A class
     * method on Level wins that dispatch and sends cube positions to the
     * independent queue while central positions retain native LevelTicks.
     */
    public void scheduleTick(BlockPos pos, Fluid fluid, int delay, TickPriority priority) {
        AllvrCubeMap map = AllvrDimensionLimits.isVanillaY(pos.getY()) ? null : allvr$map();
        if (map != null) {
            map.scheduleFluidTick(pos, fluid, delay, priority);
            return;
        }
        Level self = (Level) (Object) this;
        self.getFluidTicks().schedule(new ScheduledTick<>(fluid, pos,
            self.getGameTime() + Math.max(0, delay), priority, self.nextSubTickCount()));
    }

    public void scheduleTick(BlockPos pos, Fluid fluid, int delay) {
        this.scheduleTick(pos, fluid, delay, TickPriority.NORMAL);
    }

    @Inject(method = "setBlockEntity", at = @At("HEAD"), cancellable = true)
    private void allvr$setBlockEntity(BlockEntity blockEntity, CallbackInfo ci) {
        BlockPos pos = blockEntity.getBlockPos();
        AllvrCubeMap map = AllvrDimensionLimits.isVanillaY(pos.getY()) ? null : allvr$map();
        if (map != null) {
            map.setBlockEntity(blockEntity);
            ci.cancel();
        }
    }

    @Inject(method = "removeBlockEntity", at = @At("HEAD"), cancellable = true)
    private void allvr$removeBlockEntity(BlockPos pos, CallbackInfo ci) {
        AllvrCubeMap map = AllvrDimensionLimits.isVanillaY(pos.getY()) ? null : allvr$map();
        if (map != null) {
            map.removeBlockEntity(pos);
            // Level#removeBlockEntity always emits this comparator/neighbor
            // notification, including when the target BE was already absent.
            this.updateNeighbourForOutputSignal(pos, map.getBlockState(pos).getBlock());
            ci.cancel();
        }
    }

    @Inject(method = "isLoaded", at = @At("HEAD"), cancellable = true)
    private void allvr$isLoaded(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (!AllvrDimensionLimits.isVanillaY(pos.getY())) {
            Boolean loaded = allvr$isCubeLoaded(pos);
            if (loaded != null) {
                cir.setReturnValue(loaded);
            }
        }
    }

    @Inject(method = "loadedAndEntityCanStandOnFace", at = @At("HEAD"), cancellable = true)
    private void allvr$loadedAndEntityCanStandOnFace(BlockPos pos, Entity entity,
                                                      Direction direction,
                                                      CallbackInfoReturnable<Boolean> cir) {
        if (!AllvrDimensionLimits.isVanillaY(pos.getY())) {
            AllvrCubeMap map = allvr$map();
            if (map != null) {
                cir.setReturnValue(map.loadedAndEntityCanStandOnFace(pos, entity, direction));
            }
        }
    }

    /**
     * Persistence hard requirement (plan §7.4): {@code BlockEntity#setChanged()}
     * lands here and must mark the ALLVR cube, not the empty vanilla column
     * chunk. Routing cancels the vanilla branch; the comparator-output half of
     * the vanilla body is re-applied by hand so BE-driven comparator signals
     * still update.
     */
    @Inject(method = "blockEntityChanged", at = @At("HEAD"), cancellable = true)
    private void allvr$blockEntityChanged(BlockPos pos, CallbackInfo ci) {
        AllvrCubeMap map = AllvrDimensionLimits.isVanillaY(pos.getY()) ? null : allvr$map();
        if (map != null) {
            map.markBlockEntityDirty(pos);
            BlockState state = map.getBlockState(pos);
            if (!state.isAir()) {
                this.updateNeighbourForOutputSignal(pos, state.getBlock());
            }
            ci.cancel();
        }
    }
}
