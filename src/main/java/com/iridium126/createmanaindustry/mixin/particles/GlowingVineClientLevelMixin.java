package com.iridium126.createmanaindustry.mixin.particles;

import com.iridium126.createmanaindustry.client.particles.GlowingVineParticleClient;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Drops the local vine index when the client unloads a chunk. */
@Mixin(ClientLevel.class)
public abstract class GlowingVineClientLevelMixin {
    @Inject(method = "unload", at = @At("TAIL"))
    private void cmi$forgetGlowingVines(LevelChunk chunk, CallbackInfo ci) {
        GlowingVineParticleClient.onChunkUnloaded((ClientLevel) (Object) this, chunk.getPos());
    }
}
