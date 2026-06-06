package com.iridium126.createtricks.content.kinetics.cogwheel_chain;

import java.util.Collection;
import java.util.List;

import org.joml.Matrix4f;

import com.iridium126.createtricks.CreateTricks;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.foundation.render.RenderTypes;

import dev.engine_room.flywheel.lib.transform.TransformStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
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
		if (nodes.size() < 2)
			return;

		for (int i = 0; i < nodes.size(); i++) {
			int next = (i + 1) % nodes.size();
			CogwheelChainNode from = nodes.get(i);
			CogwheelChainNode to = nodes.get(next);

			Vec3 start = Vec3.atCenterOf(from.pos());
			Vec3 end = Vec3.atCenterOf(to.pos());

			renderChainSegment(ms, buffer, level, camera, start, end, from.pos(), to.pos());
		}
	}

	private static void renderChainSegment(PoseStack ms, MultiBufferSource buffer, Level level,
			Vec3 camera, Vec3 start, Vec3 end, BlockPos startPos, BlockPos endPos) {
		Vec3 diff = end.subtract(start);
		float yaw = (float) (Mth.RAD_TO_DEG * Mth.atan2(diff.x, diff.z));
		float pitch = (float) (Mth.RAD_TO_DEG * Mth.atan2(diff.y,
			diff.multiply(1, 0, 1).length()));
		float length = (float) start.distanceTo(end);

		int light1 = LightTexture.pack(
			level.getBrightness(LightLayer.BLOCK, startPos),
			level.getBrightness(LightLayer.SKY, startPos));
		int light2 = LightTexture.pack(
			level.getBrightness(LightLayer.BLOCK, endPos),
			level.getBrightness(LightLayer.SKY, endPos));

		Vec3 startOffset = start.subtract(camera);

		ms.pushPose();
		var chain = TransformStack.of(ms);
		chain.translate(startOffset.x, startOffset.y, startOffset.z);
		chain.rotateYDegrees(yaw);
		chain.rotateXDegrees(90 - pitch);
		chain.rotateYDegrees(45);
		chain.translate(0, 8f / 16f, 0);

		renderCrossChain(ms, buffer, length, light1, light2);

		ms.popPose();
	}

	private static void renderCrossChain(PoseStack ms, MultiBufferSource buffer,
			float length, int light1, int light2) {
		float radius = 1.5f / 16f;
		float minV = 0;
		float maxV = length;
		float minU = 0;
		float maxU = 3f / 16f;
		float uOffset = 3f / 16f;

		ms.pushPose();
		ms.translate(0.5D, 0.0D, 0.5D);

		VertexConsumer vc = buffer.getBuffer(RenderTypes.chain(CHAIN_TEXTURE));
		PoseStack.Pose pose = ms.last();
		Matrix4f matrix = pose.pose();

		// Plane 1 along Z-axis + back face
		renderQuad(matrix, pose, vc, 0, length,
			0, radius, 0, -radius,
			minU, maxU, minV, maxV, light1, light2);
		renderQuad(matrix, pose, vc, 0, length,
			0, -radius, 0, radius,
			minU, maxU, minV, maxV, light1, light2);

		// Plane 2 along X-axis + back face
		renderQuad(matrix, pose, vc, 0, length,
			radius, 0, -radius, 0,
			minU + uOffset, maxU + uOffset, minV, maxV, light1, light2);
		renderQuad(matrix, pose, vc, 0, length,
			-radius, 0, radius, 0,
			minU + uOffset, maxU + uOffset, minV, maxV, light1, light2);

		ms.popPose();
	}

	private static void renderQuad(Matrix4f matrix, PoseStack.Pose pose, VertexConsumer vc,
			float minY, float maxY, float minX, float minZ, float maxX, float maxZ,
			float minU, float maxU, float minV, float maxV, int light1, int light2) {
		addVertex(matrix, pose, vc, maxY, minX, minZ, maxU, minV, light2);
		addVertex(matrix, pose, vc, minY, minX, minZ, maxU, maxV, light1);
		addVertex(matrix, pose, vc, minY, maxX, maxZ, minU, maxV, light1);
		addVertex(matrix, pose, vc, maxY, maxX, maxZ, minU, minV, light2);
	}

	private static void addVertex(Matrix4f matrix, PoseStack.Pose pose, VertexConsumer vc,
			float y, float x, float z, float u, float v, int light) {
		vc.addVertex(matrix, x, y, z)
			.setColor(1.0f, 1.0f, 1.0f, 1.0f)
			.setUv(u, v)
			.setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(light)
			.setNormal(pose, 0.0F, 1.0F, 0.0F);
	}
}
