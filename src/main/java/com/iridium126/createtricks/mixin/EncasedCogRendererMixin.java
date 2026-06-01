package com.iridium126.createtricks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import com.iridium126.createtricks.content.kinetics.TemporaryStress;
import com.iridium126.createtricks.content.kinetics.TemporaryStressModel;
import com.simibubi.create.content.kinetics.simpleRelays.SimpleKineticBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.encased.EncasedCogRenderer;
import com.simibubi.create.content.kinetics.simpleRelays.encased.EncasedCogwheelBlock;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.world.level.block.state.BlockState;

@Mixin(value = EncasedCogRenderer.class, remap = false)
public class EncasedCogRendererMixin {

	@Shadow
	private boolean large;

	@Unique
	private SimpleKineticBlockEntity createtricks$renderedBlockEntity;

	@Inject(method = "getRotatedModel", at = @At("HEAD"), cancellable = true)
	private void createtricks$useStressedCogModel(SimpleKineticBlockEntity be, BlockState state,
			CallbackInfoReturnable<SuperByteBuffer> cir) {
		if (!TemporaryStress.isActive(be))
			return;
		PartialModel model = large ? TemporaryStressModel.shaftlessLargeCogwheel(be)
				: TemporaryStressModel.shaftlessCogwheel(be);
		Direction facing = Direction.fromAxisAndDirection(state.getValue(EncasedCogwheelBlock.AXIS), AxisDirection.POSITIVE);
		cir.setReturnValue(CachedBuffers.partialFacingVertical(model, state, facing));
	}

	@Inject(method = "renderSafe(Lcom/simibubi/create/content/kinetics/simpleRelays/SimpleKineticBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V", at = @At("HEAD"))
	private void createtricks$captureBlockEntity(SimpleKineticBlockEntity be, float partialTicks, PoseStack ms,
			MultiBufferSource buffer, int light, int overlay, CallbackInfo ci) {
		createtricks$renderedBlockEntity = be;
	}

	@Inject(method = "renderSafe(Lcom/simibubi/create/content/kinetics/simpleRelays/SimpleKineticBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V", at = @At("RETURN"))
	private void createtricks$clearBlockEntity(SimpleKineticBlockEntity be, float partialTicks, PoseStack ms,
			MultiBufferSource buffer, int light, int overlay, CallbackInfo ci) {
		createtricks$renderedBlockEntity = null;
	}

	@ModifyArgs(method = "renderSafe(Lcom/simibubi/create/content/kinetics/simpleRelays/SimpleKineticBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V", at = @At(value = "INVOKE", target = "Lnet/createmod/catnip/render/CachedBuffers;partialFacing(Ldev/engine_room/flywheel/lib/model/baked/PartialModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;)Lnet/createmod/catnip/render/SuperByteBuffer;"))
	private void createtricks$useStressedShaft(Args args) {
		SimpleKineticBlockEntity be = createtricks$renderedBlockEntity;
		if (be == null || !TemporaryStress.isActive(be))
			return;
		args.set(0, TemporaryStressModel.shaftHalf(be));
	}
}