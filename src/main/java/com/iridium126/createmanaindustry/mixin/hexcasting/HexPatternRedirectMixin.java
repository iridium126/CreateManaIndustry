package com.iridium126.createmanaindustry.mixin.hexcasting;

import com.iridium126.createmanaindustry.client.particles.engine.CMIParticleEngine;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cancel only when this world's current compute generation accepted the whole stack. */
@Mixin(targets = "at.petrak.hexcasting.api.client.ClientRenderHelper", remap = false)
public abstract class HexPatternRedirectMixin {
    @Inject(method = "renderCastingStack", at = @At("HEAD"), cancellable = true)
    private static void cmi$gpuPatterns(PoseStack pose, Player player, float partialTick, CallbackInfo ci) {
        if (CMIParticleEngine.INSTANCE.redirectsHexPatterns(player)) ci.cancel();
    }
}
