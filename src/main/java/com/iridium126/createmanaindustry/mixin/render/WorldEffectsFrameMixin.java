package com.iridium126.createmanaindustry.mixin.render;

import net.minecraft.client.renderer.GameRenderer;
import com.iridium126.createmanaindustry.client.render.shaderpack.IrisWorldEffects;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class WorldEffectsFrameMixin {
    /** Executes even when hand rendering is disabled; AFTER_LEVEL is already dispatched here. */
    @Inject(method="renderLevel",at=@At(value="FIELD",target="Lnet/minecraft/client/renderer/GameRenderer;renderHand:Z",opcode=Opcodes.GETFIELD))
    private void cmi$worldEffects(CallbackInfo ci){if(com.iridium126.createmanaindustry.CreateManaIndustry.VEIL_ACTIVE)IrisWorldEffects.renderBaseline();}
}
