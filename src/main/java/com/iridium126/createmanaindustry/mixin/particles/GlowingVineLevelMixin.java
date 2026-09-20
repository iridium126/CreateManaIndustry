package com.iridium126.createmanaindustry.mixin.particles;

import com.iridium126.createmanaindustry.client.particles.BlockParticleEmitterClient;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps the block emitter index current without polling the whole world. */
@Mixin(Level.class)
public abstract class GlowingVineLevelMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("TAIL"))
    private void cmi$trackGlowingVine(BlockPos pos, BlockState state, int flags, int recursionLeft,
            CallbackInfoReturnable<Boolean> cir) {
        Level level = (Level) (Object) this;
        if (cir.getReturnValueZ() && level instanceof ClientLevel clientLevel)
            BlockParticleEmitterClient.onBlockChanged(clientLevel, pos);
    }
}
