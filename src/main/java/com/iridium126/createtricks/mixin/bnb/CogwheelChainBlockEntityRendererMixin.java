package com.iridium126.createtricks.mixin.bnb;

import com.iridium126.createtricks.content.kinetics.bnb.BnBChainRenderContext;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.kipti.bnb.content.cogwheel_chain.block.CogwheelChainBlockEntityRenderer", remap = false)
public class CogwheelChainBlockEntityRendererMixin {
	@Inject(
		method = "renderSafe(Lcom/kipti/bnb/content/cogwheel_chain/block/CogwheelChainBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
		at = @At("HEAD"),
		remap = false)
	private void createtricks$beginKineticsCoreRender(@Coerce Object blockEntity, float partialTicks, PoseStack ms,
		MultiBufferSource buffer, int light, int overlay, CallbackInfo ci) {
		BnBChainRenderContext.begin(blockEntity);
	}

	@Inject(
		method = "renderSafe(Lcom/kipti/bnb/content/cogwheel_chain/block/CogwheelChainBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
		at = @At("RETURN"),
		remap = false)
	private void createtricks$endKineticsCoreRender(@Coerce Object blockEntity, float partialTicks, PoseStack ms,
		MultiBufferSource buffer, int light, int overlay, CallbackInfo ci) {
		BnBChainRenderContext.end();
	}
}
