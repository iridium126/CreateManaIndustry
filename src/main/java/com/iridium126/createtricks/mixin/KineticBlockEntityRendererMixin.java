package com.iridium126.createtricks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createtricks.content.kinetics.TemporaryStress;
import com.iridium126.createtricks.content.kinetics.TemporaryStressModel;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.world.level.block.state.BlockState;

@Mixin(value = KineticBlockEntityRenderer.class, remap = false)
public class KineticBlockEntityRendererMixin<T extends KineticBlockEntity> {

	@Inject(method = "getRotatedModel", at = @At("HEAD"), cancellable = true)
	private void createtricks$useStressedModel(T be, BlockState state, CallbackInfoReturnable<SuperByteBuffer> cir) {
		if (!TemporaryStress.isActive(be))
			return;
		PartialModel model = TemporaryStressModel.rotatingBlockModel(be);
		if (model == null)
			return;
		Axis axis = ((IRotate) be.getBlockState().getBlock()).getRotationAxis(be.getBlockState());
		Direction facing = Direction.fromAxisAndDirection(axis, AxisDirection.POSITIVE);
		cir.setReturnValue(CachedBuffers.partialFacingVertical(model, state, facing));
	}
}