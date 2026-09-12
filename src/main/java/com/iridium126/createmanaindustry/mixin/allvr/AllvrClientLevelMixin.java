package com.iridium126.createmanaindustry.mixin.allvr;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.client.dimension.AllvrClientCubeCache;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/** Client cube routing outside the real chunk band; central reads/writes use ClientChunkCache. */
@Mixin(Level.class)
public abstract class AllvrClientLevelMixin {

    private static boolean allvr$isAllayClient(Level self) {
        return self.isClientSide && self.dimension() == AllvrDimensions.ALLAY_LEVEL;
    }

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void allvr$clientGetBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        Level self = (Level) (Object) this;
        if (!AllvrDimensionLimits.isVanillaY(pos.getY()) && allvr$isAllayClient(self)) {
            cir.setReturnValue(AllvrClientCubeCache.getBlockState(pos));
        }
    }

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void allvr$clientGetFluidState(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
        Level self = (Level) (Object) this;
        if (!AllvrDimensionLimits.isVanillaY(pos.getY()) && allvr$isAllayClient(self)) {
            cir.setReturnValue(AllvrClientCubeCache.getFluidState(pos));
        }
    }

    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
    private void allvr$clientGetBlockEntity(BlockPos pos, CallbackInfoReturnable<BlockEntity> cir) {
        Level self = (Level) (Object) this;
        if (!AllvrDimensionLimits.isVanillaY(pos.getY()) && allvr$isAllayClient(self)) {
            cir.setReturnValue(AllvrClientCubeCache.getBlockEntity(pos));
        }
    }

    /** Cube predictions bypass LevelChunk section indexing outside the formal height. */
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
        at = @At("HEAD"), cancellable = true)
    private void allvr$clientSetBlock(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                      CallbackInfoReturnable<Boolean> cir) {
        Level self = (Level) (Object) this;
        if (!AllvrDimensionLimits.isVanillaY(pos.getY()) && allvr$isAllayClient(self)) {
            cir.setReturnValue(AllvrClientCubeCache.setBlock(pos, state, flags, recursionLeft));
        }
    }
}
