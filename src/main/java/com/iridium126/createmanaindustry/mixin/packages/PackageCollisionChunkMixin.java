package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.client.particles.packages.PackageCollisionRuntime;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Catches both Level.setBlock and mod-local direct LevelChunk writes on the client thread. */
@Mixin(LevelChunk.class)
public abstract class PackageCollisionChunkMixin {
    @Inject(method="setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",at=@At("RETURN"))
    private void cmi$packageCollisionBlockChanged(BlockPos position, BlockState state, boolean moving,
                                                  CallbackInfoReturnable<BlockState> result) {
        if(result.getReturnValue()!=null&&((LevelChunk)(Object)this).getLevel() instanceof ClientLevel level)
            PackageCollisionRuntime.blockChanged(level,position);
    }
}
