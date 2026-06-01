package com.iridium126.createtricks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import com.iridium126.createtricks.content.kinetics.TemporaryStress;
import com.iridium126.createtricks.content.kinetics.TemporaryStressModel;
import com.simibubi.create.content.kinetics.transmission.SplitShaftBlockEntity;
import com.simibubi.create.content.kinetics.transmission.SplitShaftRenderer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;

@Mixin(value = SplitShaftRenderer.class, remap = false)
public class SplitShaftRendererMixin {

	@Unique
	private SplitShaftBlockEntity createtricks$renderedBlockEntity;

	@Inject(method = "renderSafe(Lcom/simibubi/create/content/kinetics/transmission/SplitShaftBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V", at = @At("HEAD"))
	private void createtricks$captureBlockEntity(SplitShaftBlockEntity be, float partialTicks, PoseStack ms,
			MultiBufferSource buffer, int light, int overlay, CallbackInfo ci) {
		createtricks$renderedBlockEntity = be;
	}

	@Inject(method = "renderSafe(Lcom/simibubi/create/content/kinetics/transmission/SplitShaftBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V", at = @At("RETURN"))
	private void createtricks$clearBlockEntity(SplitShaftBlockEntity be, float partialTicks, PoseStack ms,
			MultiBufferSource buffer, int light, int overlay, CallbackInfo ci) {
		createtricks$renderedBlockEntity = null;
	}

	@ModifyArgs(method = "renderSafe(Lcom/simibubi/create/content/kinetics/transmission/SplitShaftBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V", at = @At(value = "INVOKE", target = "Lnet/createmod/catnip/render/CachedBuffers;partialFacing(Ldev/engine_room/flywheel/lib/model/baked/PartialModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;)Lnet/createmod/catnip/render/SuperByteBuffer;"))
	private void createtricks$useStressedShaft(Args args) {
		SplitShaftBlockEntity be = createtricks$renderedBlockEntity;
		if (be == null || !TemporaryStress.isActive(be))
			return;
		args.set(0, TemporaryStressModel.shaftHalf(be));
	}
}