package com.iridium126.createtricks.mixin.bnb;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createtricks.content.kinetics.bnb.BnBChainRenderContext;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.MultiBufferSource;
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
		try {
			if (!(blockEntity instanceof BlockEntity be))
				return;
			Class<?> chainBEClass = Class.forName(
					"com.kipti.bnb.content.cogwheel_chain.block.CogwheelChainBlockEntity");
			if (!chainBEClass.isInstance(be))
				return;

			boolean isController = (Boolean) chainBEClass.getMethod("isController").invoke(be);
			if (!isController)
				return;

			Object chain = chainBEClass.getMethod("getChain").invoke(be);
			if (chain == null)
				return;

			float speed = (Float) chainBEClass.getMethod("getSpeed").invoke(be);

			@SuppressWarnings("unchecked")
			List<Object> nodes = (List<Object>) chain.getClass()
					.getMethod("getChainPathCogwheelNodes").invoke(chain);
			BlockPos controllerPos = be.getBlockPos();

			for (Object node : nodes) {
				// Use each node's own sideFactor — not the controller's
				// chainRotationFactor — so adjacent gears in the chain rotate in
				// opposite directions where appropriate.
				float sideFactor = (Float) node.getClass().getMethod("sideFactor").invoke(node);
				float angularVelocity = (float) (Math.PI * sideFactor * speed / -300.0f);

				BlockPos localPos = (BlockPos) node.getClass().getMethod("localPos").invoke(node);
				BlockPos nodeWorldPos = controllerPos.offset(localPos);
				BnBChainRenderContext.putChainAngularVelocity(nodeWorldPos, angularVelocity);
			}
		} catch (ReflectiveOperationException ignored) {
		}
	}
}
