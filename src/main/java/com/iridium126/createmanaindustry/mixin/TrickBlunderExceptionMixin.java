package com.iridium126.createmanaindustry.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.trickster.InvalidKineticTargetBlunder;

import dev.enjarai.trickster.spell.blunder.TrickBlunderException;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Injects custom blunder information into TrickBlunderException.createMessage().
 *
 * We target the parent class TrickBlunderException because createMessage() is
 * defined there. We then use an instanceof check to only apply changes to
 * InvalidKineticTargetBlunder instances.
 */
@Mixin(TrickBlunderException.class)
public abstract class TrickBlunderExceptionMixin {
	@Inject(method = "createMessage", at = @At("RETURN"), cancellable = true, remap = false)
	private void createtricks$overrideCreateMessage(CallbackInfoReturnable<MutableComponent> cir) {
		if ((Object) this instanceof InvalidKineticTargetBlunder self) {
			MutableComponent message = cir.getReturnValue();

			if (message == null) {
				message = Component.literal("");
			}

			cir.setReturnValue(message.append("Invalid kinetic target at ").append(self.getPos().toShortString()));
		}
	}
}
