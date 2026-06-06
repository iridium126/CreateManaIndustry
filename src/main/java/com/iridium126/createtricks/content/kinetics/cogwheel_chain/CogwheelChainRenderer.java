package com.iridium126.createtricks.content.kinetics.cogwheel_chain;

import java.util.Collection;
import java.util.List;

import org.joml.Matrix4f;

import com.iridium126.createtricks.CreateTricks;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.foundation.render.RenderTypes;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

@EventBusSubscriber(modid = CreateTricks.MODID, value = Dist.CLIENT)
public final class CogwheelChainRenderer {

	private static final ResourceLocation CHAIN_TEXTURE =
		ResourceLocation.withDefaultNamespace("textures/block/chain.png");

	private CogwheelChainRenderer() {}

	@SubscribeEvent
	public static void onRenderLevel(RenderLevelStageEvent event) {
		if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES)
			return;

		Collection<CogwheelChainData> chains = CogwheelChainClientData.getAllChains();
		if (chains.isEmpty() && !CogwheelChainHandler.isBuilding())
			return;

		PoseStack ms = event.getPoseStack();
		Vec3 camera = event.getCamera().getPosition();
		Level level = Minecraft.getInstance().level;
		if (level == null)
			return;

		RenderBuffers renderBuffers = Minecraft.getInstance().renderBuffers();
		MultiBufferSource.BufferSource buffer = renderBuffers.bufferSource();

		for (CogwheelChainData chain : chains) {
			renderChainLoop(ms, buffer, level, camera, chain.getNodes());
		}

		if (CogwheelChainHandler.isBuilding()) {
			List<CogwheelChainNode> nodes = CogwheelChainHandler.getBuildingNodes();
			if (nodes.size() >= 2) {
				renderChainLoop(ms, buffer, level, camera, nodes);
			}
		}

		buffer.endLastBatch();
	}

	private static void renderChainLoop(PoseStack ms, MultiBufferSource buffer, Level level,
			Vec3 camera, List<CogwheelChainNode> nodes) {
		if (nodes.size() != 2)
			return;

		CogwheelChainNode first = nodes.get(0);
		CogwheelChainNode second = nodes.get(1);
		if (!first.canLinkTo(second))
			return;

		CogwheelChainNode cogwheel = first.isCogwheel() ? first : second;
		CogwheelChainNode core = first.isKineticsCore() ? first : second;
		Vec3 cogwheelCenter = CogwheelChainNodes.getRenderPosition(level, cogwheel);
		Vec3 coreCenter = CogwheelChainNodes.getRenderPosition(level, core);

		renderTrapezoidChain(ms, buffer, level, camera, cogwheelCenter, coreCenter, cogwheel, core);
	}

	private static void renderTrapezoidChain(PoseStack ms, MultiBufferSource buffer, Level level,
			Vec3 camera, Vec3 cogwheelCenter, Vec3 coreCenter,
			CogwheelChainNode cogwheel, CogwheelChainNode core) {
		Vec3 centerDiff = coreCenter.subtract(cogwheelCenter);
		if (centerDiff.lengthSqr() < 1e-6)
			return;

		Vec3 direction = centerDiff.normalize();
		Vec3 side = direction.cross(new Vec3(0, 1, 0));
		if (side.lengthSqr() < 1e-6)
			side = new Vec3(1, 0, 0);
		else
			side = side.normalize();

		Vec3 cogwheelEdge = cogwheelCenter.add(direction.scale(CogwheelChainNodes.getRadius(cogwheel)));
		Vec3 coreEdge = coreCenter.subtract(direction.scale(CogwheelChainNodes.getRadius(core)));
		double cogwheelHalfWidth = CogwheelChainNodes.getChainWidth(cogwheel) / 2;
		double coreHalfWidth = CogwheelChainNodes.getChainWidth(core) / 2;

		int light1 = LightTexture.pack(
			level.getBrightness(LightLayer.BLOCK, cogwheel.pos()),
			level.getBrightness(LightLayer.SKY, cogwheel.pos()));
		int light2 = LightTexture.pack(
			level.getBrightness(LightLayer.BLOCK, core.pos()),
			level.getBrightness(LightLayer.SKY, core.pos()));

		Vec3 cogwheelLeft = cogwheelEdge.add(side.scale(cogwheelHalfWidth));
		Vec3 cogwheelRight = cogwheelEdge.subtract(side.scale(cogwheelHalfWidth));
		Vec3 coreLeft = coreEdge.add(side.scale(coreHalfWidth));
		Vec3 coreRight = coreEdge.subtract(side.scale(coreHalfWidth));
		renderTaperedQuad(ms, buffer, camera, cogwheelLeft, cogwheelRight, coreRight, coreLeft, light1, light2);
	}

	private static void renderTaperedQuad(PoseStack ms, MultiBufferSource buffer, Vec3 camera,
			Vec3 cogwheelLeft, Vec3 cogwheelRight, Vec3 coreRight, Vec3 coreLeft,
			int cogwheelLight, int coreLight) {
		ms.pushPose();
		ms.translate(-camera.x, -camera.y, -camera.z);

		VertexConsumer vc = buffer.getBuffer(RenderTypes.chain(CHAIN_TEXTURE));
		PoseStack.Pose pose = ms.last();
		Matrix4f matrix = pose.pose();
		float length = (float) cogwheelLeft.distanceTo(coreLeft);

		addVertex(matrix, pose, vc, cogwheelLeft, 0, 0, cogwheelLight);
		addVertex(matrix, pose, vc, cogwheelRight, 1, 0, cogwheelLight);
		addVertex(matrix, pose, vc, coreRight, 1, length, coreLight);
		addVertex(matrix, pose, vc, coreLeft, 0, length, coreLight);

		addVertex(matrix, pose, vc, coreLeft, 0, length, coreLight);
		addVertex(matrix, pose, vc, coreRight, 1, length, coreLight);
		addVertex(matrix, pose, vc, cogwheelRight, 1, 0, cogwheelLight);
		addVertex(matrix, pose, vc, cogwheelLeft, 0, 0, cogwheelLight);

		ms.popPose();
	}

	private static void addVertex(Matrix4f matrix, PoseStack.Pose pose, VertexConsumer vc, Vec3 position,
			float u, float v, int light) {
		vc.addVertex(matrix, (float) position.x, (float) position.y, (float) position.z)
			.setColor(1.0f, 1.0f, 1.0f, 1.0f)
			.setUv(u, v)
			.setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(light)
			.setNormal(pose, 0.0F, 1.0F, 0.0F);
	}
}
