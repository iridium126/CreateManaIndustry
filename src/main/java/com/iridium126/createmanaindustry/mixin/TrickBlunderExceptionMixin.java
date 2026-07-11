package com.iridium126.createmanaindustry.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.trickster.InvalidKineticTargetBlunder;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Injects custom blunder information into TrickBlunderException.createMessage().
 * <p>
 * Uses a string target rather than {@code TrickBlunderException.class} so the
 * mixin class itself can be loaded when Trickster is absent — the mixin plugin
 * disables it by name in that case.
 */
@Mixin(targets = "dev.enjarai.trickster.spell.blunder.TrickBlunderException", remap = false)
public abstract class TrickBlunderExceptionMixin {
    @Inject(method = "createMessage", at = @At("RETURN"), cancellable = true, remap = false)
    private void createmanaindustry$overrideCreateMessage(CallbackInfoReturnable<MutableComponent> cir) {
        if ((Object) this instanceof InvalidKineticTargetBlunder self) {
            MutableComponent message = cir.getReturnValue();

            if (message == null) {
                message = Component.literal("");
            }

            cir.setReturnValue(message.append("Invalid kinetic target at ").append(self.getPos().toShortString()));
        }
    }
}
