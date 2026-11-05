package com.iridium126.createmanaindustry.mixin.allaystorm;

import com.iridium126.createmanaindustry.client.particles.engine.ParticleRuntime;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class StormContactTeleportMixin {
    // RETURN is reached only after the main-thread packet guard.
    @Inject(method="handleMovePlayer",at=@At("RETURN"))
    private void cmi$invalidateStormTargetEdge(CallbackInfo ci){
        ParticleRuntime.INSTANCE.invalidateStormTargetEdge();
    }
}
