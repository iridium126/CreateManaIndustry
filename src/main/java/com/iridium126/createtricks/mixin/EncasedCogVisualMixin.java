package com.iridium126.createtricks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createtricks.content.kinetics.TemporaryStressModel;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.encased.EncasedCogVisual;

import dev.engine_room.flywheel.api.visual.BlockEntityVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.model.Models;

@Mixin(value = EncasedCogVisual.class, remap = false)
public abstract class EncasedCogVisualMixin {
	@Inject(method = "small", at = @At("HEAD"), cancellable = true)
	private static void createtricks$useStressedSmall(VisualizationContext context, KineticBlockEntity be, float partialTick,
			CallbackInfoReturnable<BlockEntityVisual<KineticBlockEntity>> cir) {
		cir.setReturnValue(
				new EncasedCogVisual(context, be, false, partialTick, Models.partial(TemporaryStressModel.shaftlessCogwheel(be))));
	}

	@Inject(method = "large", at = @At("HEAD"), cancellable = true)
	private static void createtricks$useStressedLarge(VisualizationContext context, KineticBlockEntity be, float partialTick,
			CallbackInfoReturnable<BlockEntityVisual<KineticBlockEntity>> cir) {
		cir.setReturnValue(new EncasedCogVisual(context, be, true, partialTick,
				Models.partial(TemporaryStressModel.shaftlessLargeCogwheel(be))));
	}
}
