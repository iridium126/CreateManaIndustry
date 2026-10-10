package com.iridium126.createmanaindustry.mixin.hexcasting;

import com.iridium126.createmanaindustry.client.particles.engine.CMIParticleEngine;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Only the matching world/shadow/preview pass may replace a native casting-stack call. */
@Mixin(targets = "at.petrak.hexcasting.api.client.ClientRenderHelper", remap = false)
public abstract class HexPatternRedirectMixin {
    @Inject(method = "renderCastingStack", at = @At("HEAD"), cancellable = true)
    private static void cmi$gpuPatterns(PoseStack pose, Player player, float partialTick, CallbackInfo ci) {
        if (com.iridium126.createmanaindustry.client.particles.hex.HexPatternService.castingStack(pose,player,partialTick)) ci.cancel();
    }
}
