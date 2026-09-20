package com.iridium126.createmanaindustry.mixin.particles;

import com.iridium126.createmanaindustry.client.particles.BlockParticleEmitterClient;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Maintains registered block emitter indices across full chunk packets and unloads. */
@Mixin(ClientLevel.class)
public abstract class GlowingVineClientLevelMixin {
    @Inject(method = "onChunkLoaded", at = @At("TAIL"))
    private void cmi$discoverBlockEmitters(net.minecraft.world.level.ChunkPos pos, CallbackInfo ci) {
        BlockParticleEmitterClient.onChunkLoaded((ClientLevel) (Object) this, pos);
    }

    @Inject(method = "unload", at = @At("TAIL"))
    private void cmi$forgetGlowingVines(LevelChunk chunk, CallbackInfo ci) {
        BlockParticleEmitterClient.onChunkUnloaded((ClientLevel) (Object) this, chunk.getPos());
    }
}
