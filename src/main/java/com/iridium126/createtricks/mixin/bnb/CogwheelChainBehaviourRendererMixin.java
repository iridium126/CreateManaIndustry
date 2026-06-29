package com.iridium126.createtricks.mixin.bnb;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createtricks.content.kinetics.bnb.BnBChainRenderContext;
import com.kipti.bnb.content.kinetics.cogwheel_chain.behaviour.CogwheelChainBehaviour;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.CogwheelChain;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.PathedCogwheelNode;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

@Mixin(targets = "com.kipti.bnb.content.kinetics.cogwheel_chain.behaviour.CogwheelChainBehaviourRenderer", remap = false)
public class CogwheelChainBehaviourRendererMixin {

	private static final ThreadLocal<KineticBlockEntity> CAPTURED_BE = new ThreadLocal<>();

	@Inject(
		method = "renderSafe(Lcom/cake/azimuth/behaviour/SuperBlockEntityBehaviour;Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
		at = @At("HEAD"),
		remap = false)
	private void createtricks$beginKineticsCoreRender(@Coerce Object behaviour, KineticBlockEntity be,
			float partialTicks, PoseStack ms, MultiBufferSource buffer, int light, int overlay,
			CallbackInfo ci) {
		CAPTURED_BE.set(be);
		if (behaviour instanceof CogwheelChainBehaviour chainBehaviour) {
			createtricks$populateChainAngularVelocities(chainBehaviour, be);
		}
	}

	/**
	 * Intercept the {@code buildSegments} call within {@code renderSafe} and
	 * set the render context immediately before the chain nodes are queried.
	 * This guarantees {@code BnBChainRenderContext.CURRENT} is set when
	 * {@code getChainPathNodes()} fires its expansion mixin.
	 */
	@SuppressWarnings("rawtypes")
	@Redirect(method = "renderSafe(Lcom/cake/azimuth/behaviour/SuperBlockEntityBehaviour;Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
		at = @At(value = "INVOKE",
			target = "Lcom/kipti/bnb/content/kinetics/cogwheel_chain/render/CogwheelChainRenderGeometryBuilder;buildSegments(Lcom/kipti/bnb/content/kinetics/cogwheel_chain/graph/CogwheelChain;Lnet/minecraft/world/phys/Vec3;)Ljava/util/List;"),
		remap = false)
	private List createtricks$expandBeforeBuildSegments(CogwheelChain chain, Vec3 origin) {
		KineticBlockEntity be = CAPTURED_BE.get();
		if (be != null) {
			BnBChainRenderContext.begin(be);
		}
		try {
			return com.kipti.bnb.content.kinetics.cogwheel_chain.render.CogwheelChainRenderGeometryBuilder
					.buildSegments(chain, origin);
		} finally {
			// Don't remove context — it's needed by getChainAngularVelocity
			// and will be cleared by the RETURN inject.
		}
	}

	@Inject(
		method = "renderSafe(Lcom/cake/azimuth/behaviour/SuperBlockEntityBehaviour;Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
		at = @At("RETURN"),
		remap = false)
	private void createtricks$endKineticsCoreRender(@Coerce Object behaviour, KineticBlockEntity be,
			float partialTicks, PoseStack ms, MultiBufferSource buffer, int light, int overlay,
			CallbackInfo ci) {
		CAPTURED_BE.remove();
		BnBChainRenderContext.end();
	}

	private static void createtricks$populateChainAngularVelocities(CogwheelChainBehaviour chainBehaviour,
			KineticBlockEntity be) {
		if (!chainBehaviour.isController())
			return;

		CogwheelChain chain = chainBehaviour.getControlledChain();
		if (chain == null)
			return;

		float speed = be.getSpeed();
		List<PathedCogwheelNode> nodes = chain.getChainPathCogwheelNodes();
		BlockPos controllerPos = be.getBlockPos();

		for (PathedCogwheelNode node : nodes) {
			float sideFactor = node.sideFactor();
			float angularVelocity = (float) (Math.PI * sideFactor * speed / -300.0f);

			BlockPos nodeWorldPos = controllerPos.offset(node.localPos());
			BnBChainRenderContext.putChainAngularVelocity(nodeWorldPos, angularVelocity);
		}
	}
}
