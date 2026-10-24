package com.iridium126.createmanaindustry.mixin.irisveil;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.render.shaderpack.IrisWorldEffects;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Retains the late world window previously supplied by Iris Veil's removed hook registry. */
@Mixin(LevelRenderer.class)
abstract class LevelRendererWorldEffectsMixin {
    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderWorldBorder(Lnet/minecraft/client/Camera;)V",
            shift = At.Shift.AFTER))
    private void cmi$renderWorldEffects(CallbackInfo ci) {
        if (CreateManaIndustry.IRISVEIL_ACTIVE) IrisWorldEffects.render();
    }
}
