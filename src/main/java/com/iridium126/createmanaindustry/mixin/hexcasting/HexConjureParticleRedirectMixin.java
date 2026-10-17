package com.iridium126.createmanaindustry.mixin.hexcasting;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createmanaindustry.client.particles.engine.CMIParticleEngine;
import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;

import at.petrak.hexcasting.common.particles.ConjureParticleOptions;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;

/**
 * Intercepts creation after LevelRenderer has applied distance/particle settings
 * and overrideLimiter. Rejecting GPU admission preserves the original provider.
 */
@Mixin(ParticleEngine.class)
public class HexConjureParticleRedirectMixin {
    @Inject(method="tick()V",at=@At("TAIL"))
    private void cmi$nativeParticleTick(CallbackInfo ignored){com.iridium126.createmanaindustry.client.particles.engine.ParticleRuntime.INSTANCE.nativeParticleTick();}

    @Inject(method = "createParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)Lnet/minecraft/client/particle/Particle;",
            at = @At("HEAD"), cancellable = true)
    private void cmi$redirectConjureParticle(ParticleOptions options, double x, double y, double z,
            double xd, double yd, double zd, CallbackInfoReturnable<Particle> ci) {
        if (redirect(options, x, y, z, xd, yd, zd))
            ci.setReturnValue(null);
    }

    private static boolean redirect(ParticleOptions options, double x, double y, double z,
            double xd, double yd, double zd) {
        if (!(options instanceof ConjureParticleOptions conjure) || !ClientConfig.hexParticleRedirect)
            return false;
        CMIParticleEngine engine = CMIParticleEngine.INSTANCE;
        if (!com.iridium126.createmanaindustry.client.particles.engine.ParticleRuntime.INSTANCE.available())
            return false; // retain the vanilla provider during engine startup/failure
        return engine.trySpawnHexParticle(x,y,z,xd,yd,zd,conjure.color());
    }
}
