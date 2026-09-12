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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
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
