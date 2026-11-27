package com.iridium126.createmanaindustry.mixin.particles;

import com.iridium126.createmanaindustry.content.allaystorm.StormBlast;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
abstract class StormBlastChunkMixin {
    @Inject(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("RETURN"))
    private void createmanaindustry$captureStormBlast(BlockPos position, BlockState state, boolean moving,
                                                     CallbackInfoReturnable<BlockState> result) {
        var previous = result.getReturnValue();
        if (previous != null) StormBlast.blockChanged(((LevelChunk) (Object) this).getLevel(), position, previous);
    }
}
