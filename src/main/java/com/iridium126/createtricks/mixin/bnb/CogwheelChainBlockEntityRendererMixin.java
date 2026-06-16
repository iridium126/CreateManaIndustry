package com.iridium126.createtricks.mixin.bnb;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createtricks.content.kinetics.bnb.BnBChainRenderContext;
import com.iridium126.createtricks.content.kinetics.bnb.BnBReflection;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

@Mixin(targets = "com.kipti.bnb.content.cogwheel_chain.block.CogwheelChainBlockEntityRenderer", remap = false)
public class CogwheelChainBlockEntityRendererMixin {
	@Inject(
		method = "renderSafe(Lcom/kipti/bnb/content/cogwheel_chain/block/CogwheelChainBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
		at = @At("HEAD"),
		remap = false)
	private void createtricks$beginKineticsCoreRender(@Coerce Object blockEntity, float partialTicks, PoseStack ms,
			MultiBufferSource buffer, int light, int overlay, CallbackInfo ci) {
		BnBChainRenderContext.begin(blockEntity);
		createtricks$populateChainAngularVelocities(blockEntity);
	}

	@Inject(
		method = "renderSafe(Lcom/kipti/bnb/content/cogwheel_chain/block/CogwheelChainBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
		at = @At("RETURN"),
		remap = false)
	private void createtricks$endKineticsCoreRender(@Coerce Object blockEntity, float partialTicks, PoseStack ms,
			MultiBufferSource buffer, int light, int overlay, CallbackInfo ci) {
		BnBChainRenderContext.end();
	}

	/**
	 * Iterates over the chain's cogwheel nodes and stores each node's world
	 * position → angular velocity mapping in {@link BnBChainRenderContext}, so that
	 * the modular spell construct renderer can rotate kinetics cores at the same
	 * speed and direction as the chain.
	 */
	private static void createtricks$populateChainAngularVelocities(Object blockEntity) {
		if (!(blockEntity instanceof BlockEntity be))
			return;
		if (!BnBReflection.isChainBE(be) || !BnBReflection.isController(be))
			return;

		Object chain = BnBReflection.getChain(be);
		if (chain == null)
			return;

		float speed = BnBReflection.getSpeed(be);
		List<Object> nodes = BnBReflection.getChainPathCogwheelNodes(chain);
		BlockPos controllerPos = be.getBlockPos();

		for (Object node : nodes) {
			// Use each node's own sideFactor — not the controller's
			// chainRotationFactor — so adjacent gears in the chain rotate in
			// opposite directions where appropriate.
			float sideFactor = BnBReflection.sideFactor(node);
			float angularVelocity = (float) (Math.PI * sideFactor * speed / -300.0f);

			BlockPos nodeWorldPos = controllerPos.offset(BnBReflection.localPos(node));
			BnBChainRenderContext.putChainAngularVelocity(nodeWorldPos, angularVelocity);
		}
	}
}
