package com.iridium126.createtricks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createtricks.content.kinetics.TemporaryStressModel;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.SingleAxisRotatingVisual;

import dev.engine_room.flywheel.api.visual.BlockEntityVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer;
import net.minecraft.core.Direction;

@Mixin(value = SingleAxisRotatingVisual.class, remap = false)
public abstract class SingleAxisRotatingVisualMixin {
	@Inject(method = "of", at = @At("HEAD"), cancellable = true)
	private static <T extends KineticBlockEntity> void createtricks$useStressedModel(PartialModel partial,
			CallbackInfoReturnable<SimpleBlockEntityVisualizer.Factory<T>> cir) {
		if (TemporaryStressModel.hasReplacement(partial))
			cir.setReturnValue((context, be, partialTick) -> createtricks$create(context, be, partialTick, partial));
	}

	@Inject(method = "ofZ", at = @At("HEAD"), cancellable = true)
	private static <T extends KineticBlockEntity> void createtricks$useStressedZModel(PartialModel partial,
			CallbackInfoReturnable<SimpleBlockEntityVisualizer.Factory<T>> cir) {
		if (TemporaryStressModel.hasReplacement(partial))
			cir.setReturnValue((context, be, partialTick) -> createtricks$createZ(context, be, partialTick, partial));
	}

	@Inject(method = "shaft", at = @At("HEAD"), cancellable = true)
	private static <T extends KineticBlockEntity> void createtricks$useStressedShaft(VisualizationContext context, T be,
			float partialTick, CallbackInfoReturnable<SingleAxisRotatingVisual<T>> cir) {
		cir.setReturnValue(new SingleAxisRotatingVisual<>(context, be, partialTick,
				Models.partial(TemporaryStressModel.shaft(be))));
	}

	@Unique
	private static <T extends KineticBlockEntity> BlockEntityVisual<? super T> createtricks$create(VisualizationContext context,
			T be, float partialTick, PartialModel partial) {
		return new SingleAxisRotatingVisual<>(context, be, partialTick,
				Models.partial(TemporaryStressModel.replacementOrSelf(be, partial)));
	}

	@Unique
	private static <T extends KineticBlockEntity> BlockEntityVisual<? super T> createtricks$createZ(VisualizationContext context,
			T be, float partialTick, PartialModel partial) {
		return new SingleAxisRotatingVisual<>(context, be, partialTick, Direction.SOUTH,
				Models.partial(TemporaryStressModel.replacementOrSelf(be, partial)));
	}
}
