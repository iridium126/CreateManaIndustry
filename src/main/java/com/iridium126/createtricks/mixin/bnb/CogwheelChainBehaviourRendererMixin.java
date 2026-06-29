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
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;

/**
 * Injects into the updated BnB {@code CogwheelChainBehaviourRenderer} to populate
 * angular velocity data for kinetics spell cores so they rotate in sync with the
 * cogwheel chain.
 */
@Mixin(targets = "com.kipti.bnb.content.kinetics.cogwheel_chain.behaviour.CogwheelChainBehaviourRenderer", remap = false)
public class CogwheelChainBehaviourRendererMixin {

	@Inject(
		method = "renderSafe(Lcom/cake/azimuth/behaviour/SuperBlockEntityBehaviour;Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
		at = @At("HEAD"),
		remap = false)
	private void createtricks$beginKineticsCoreRender(@Coerce Object behaviour, KineticBlockEntity be,
			float partialTicks, PoseStack ms, MultiBufferSource buffer, int light, int overlay,
			CallbackInfo ci) {
		BnBChainRenderContext.begin(be);
		createtricks$populateChainAngularVelocities(behaviour, be);
	}

	@Inject(
		method = "renderSafe(Lcom/cake/azimuth/behaviour/SuperBlockEntityBehaviour;Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
		at = @At("RETURN"),
		remap = false)
	private void createtricks$endKineticsCoreRender(@Coerce Object behaviour, KineticBlockEntity be,
			float partialTicks, PoseStack ms, MultiBufferSource buffer, int light, int overlay,
			CallbackInfo ci) {
		BnBChainRenderContext.end();
	}

	/**
	 * Iterates over the chain's cogwheel nodes and stores each node's world
	 * position &rarr; angular velocity mapping in {@link BnBChainRenderContext}, so
	 * the modular spell construct renderer can rotate kinetics cores at the same
	 * speed and direction as the chain.
	 */
	private static void createtricks$populateChainAngularVelocities(Object behaviour, KineticBlockEntity be) {
		if (!BnBReflection.isController(be))
			return;

		Object chain = BnBReflection.getChain(be);
		if (chain == null)
			return;

		float speed = BnBReflection.getSpeed(be);
		List<Object> nodes = BnBReflection.getChainPathCogwheelNodes(chain);
		BlockPos controllerPos = be.getBlockPos();

		for (Object node : nodes) {
			float sideFactor = BnBReflection.sideFactor(node);
			float angularVelocity = (float) (Math.PI * sideFactor * speed / -300.0f);

			BlockPos nodeWorldPos = controllerPos.offset(BnBReflection.localPos(node));
			BnBChainRenderContext.putChainAngularVelocity(nodeWorldPos, angularVelocity);
		}
	}
}
