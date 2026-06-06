package com.iridium126.createtricks.content.kinetics.cogwheel_chain;

import java.util.Collection;
import java.util.List;

import org.joml.Matrix4f;

import com.iridium126.createtricks.CreateTricks;
import com.iridium126.createtricks.content.kinetics.cogwheel_chain.render.ChainQuadBuilder;
import com.iridium126.createtricks.content.kinetics.cogwheel_chain.render.CogwheelChainRenderGeometryBuilder;
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
		Vec3 side = CogwheelChainNodes.getVisualAxis(level, cogwheel).cross(direction);
		if (side.lengthSqr() < 1e-6)
			side = CogwheelChainNodes.getVisualAxis(level, core).cross(direction);
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

		renderChainSegment(ms, buffer, camera, cogwheelLeft, coreLeft, light1, light2);
		renderChainSegment(ms, buffer, camera, cogwheelRight, coreRight, light1, light2);
	}

	private static void renderChainSegment(PoseStack ms, MultiBufferSource buffer, Vec3 camera,
			Vec3 from, Vec3 to, int lightAtSource, int lightAtDest) {
		Vec3 diff = to.subtract(from);
		if (diff.lengthSqr() < 1e-6)
			return;

		Vec3 direction = diff.normalize();
		Vec3 preFrom = from.subtract(direction.scale(0.5));
		Vec3 postTo = to.add(direction.scale(0.5));
		List<Vec3> sourcePoints =
			CogwheelChainRenderGeometryBuilder.getEndPointsForChainJoint(preFrom, from, to);
		List<Vec3> destinationPoints =
			CogwheelChainRenderGeometryBuilder.getEndPointsForChainJoint(from, to, postTo);
		destinationPoints = CogwheelChainRenderGeometryBuilder.getPointsInClosestOrder(destinationPoints, sourcePoints);

		ms.pushPose();
		ms.translate(-camera.x, -camera.y, -camera.z);

		VertexConsumer vc = buffer.getBuffer(RenderTypes.chain(CHAIN_TEXTURE));
		PoseStack.Pose pose = ms.last();
		Matrix4f matrix = pose.pose();
		Vec3 segDir = to.subtract(from);
		double segLenSq = segDir.lengthSqr();
		float maxV = (float) from.distanceTo(to);

		ChainQuadBuilder.VertexEmitter emitter = (x, y, z, u, v, nx, ny, nz) -> {
			float t = segLenSq > 1e-8
				? net.minecraft.util.Mth.clamp((float) (new Vec3(x, y, z).subtract(from).dot(segDir) / segLenSq), 0f, 1f)
				: 0f;
			int light = lerpPackedLight(lightAtSource, lightAtDest, t);
			vc.addVertex(matrix, x, y, z)
				.setColor(1.0f, 1.0f, 1.0f, 1.0f)
				.setUv(u, v)
				.setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(light)
				.setNormal(pose, nx, ny, nz);
		};

		ChainQuadBuilder.buildSegmentFaces(destinationPoints, sourcePoints, 0, maxV, emitter);

		ms.popPose();
	}

	private static int lerpPackedLight(int light1, int light2, float t) {
		int block = (int) net.minecraft.util.Mth.lerp(t, light1 & 0xFFFF, light2 & 0xFFFF);
		int sky = (int) net.minecraft.util.Mth.lerp(t, (light1 >> 16) & 0xFFFF, (light2 >> 16) & 0xFFFF);
		return block | (sky << 16);
	}
}
