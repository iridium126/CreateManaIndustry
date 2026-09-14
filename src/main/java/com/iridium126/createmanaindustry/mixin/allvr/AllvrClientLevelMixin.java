package com.iridium126.createmanaindustry.mixin.allvr;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createmanaindustry.client.dimension.AllvrClientCubeCache;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
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

    @Inject(method = "isLoaded", at = @At("HEAD"), cancellable = true)
    private void allvr$clientIsLoaded(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        Level self = (Level) (Object) this;
        if (!AllvrDimensionLimits.isVanillaY(pos.getY()) && allvr$isAllayClient(self)) {
            cir.setReturnValue(AllvrClientCubeCache.isLoaded(pos));
        }
    }

    @Inject(method = "loadedAndEntityCanStandOnFace", at = @At("HEAD"), cancellable = true)
    private void allvr$clientLoadedAndEntityCanStandOnFace(BlockPos pos, Entity entity,
                                                            Direction direction,
                                                            CallbackInfoReturnable<Boolean> cir) {
        Level self = (Level) (Object) this;
        if (!AllvrDimensionLimits.isVanillaY(pos.getY()) && allvr$isAllayClient(self)) {
            cir.setReturnValue(AllvrClientCubeCache.loadedAndEntityCanStandOnFace(pos, entity, direction));
        }
    }

    @Inject(method = "setBlockEntity", at = @At("HEAD"), cancellable = true)
    private void allvr$clientSetBlockEntity(BlockEntity blockEntity, CallbackInfo ci) {
        Level self = (Level) (Object) this;
        if (!AllvrDimensionLimits.isVanillaY(blockEntity.getBlockPos().getY()) && allvr$isAllayClient(self)) {
            AllvrClientCubeCache.setBlockEntity(blockEntity);
            ci.cancel();
        }
    }

    @Inject(method = "removeBlockEntity", at = @At("HEAD"), cancellable = true)
    private void allvr$clientRemoveBlockEntity(BlockPos pos, CallbackInfo ci) {
        Level self = (Level) (Object) this;
        if (!AllvrDimensionLimits.isVanillaY(pos.getY()) && allvr$isAllayClient(self)) {
            AllvrClientCubeCache.removeBlockEntity(pos);
            self.updateNeighbourForOutputSignal(pos, self.getBlockState(pos).getBlock());
            ci.cancel();
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
