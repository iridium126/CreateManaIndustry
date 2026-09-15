package com.iridium126.createmanaindustry.mixin.hexcasting;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createmanaindustry.client.particles.engine.CMIParticleEngine;
import com.iridium126.createmanaindustry.config.ClientConfig;

import at.petrak.hexcasting.common.particles.ConjureParticleOptions;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.phys.Vec3;

/**
 * Captures every client-side ingress for Hexcasting's custom
 * {@code hexcasting:conjure_particle}. Hex uses the six-argument overload for
 * local/client effects and the limiter-aware seven-argument overload for
 * server particle packets, so both must be covered to make the redirection
 * complete without changing any other particle type.
 */
@Mixin(ClientLevel.class)
public class HexConjureParticleRedirectMixin {

    @Inject(method = "addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V",
            at = @At("HEAD"), cancellable = true)
    private void cmi$redirectConjureParticle(ParticleOptions options, double x, double y, double z,
            double xd, double yd, double zd, CallbackInfo ci) {
        if (redirect(options, x, y, z, xd, yd, zd))
            ci.cancel();
    }

    @Inject(method = "addParticle(Lnet/minecraft/core/particles/ParticleOptions;ZDDDDDD)V",
            at = @At("HEAD"), cancellable = true)
    private void cmi$redirectConjureParticleWithLimiter(ParticleOptions options, boolean overrideLimiter,
            double x, double y, double z, double xd, double yd, double zd, CallbackInfo ci) {
        if (redirect(options, x, y, z, xd, yd, zd))
            ci.cancel();
    }

    private static boolean redirect(ParticleOptions options, double x, double y, double z,
            double xd, double yd, double zd) {
        if (!(options instanceof ConjureParticleOptions conjure) || !ClientConfig.hexParticleRedirect)
            return false;
        CMIParticleEngine engine = CMIParticleEngine.INSTANCE;
        if (!engine.available())
            return false; // retain the vanilla provider during engine startup/failure
        engine.spawnHexParticle(new Vec3(x, y, z), new Vec3(xd, yd, zd), conjure.color());
        return true;
    }
}
