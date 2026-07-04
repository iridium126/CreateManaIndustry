package com.iridium126.createmanaindustry.content.kinetics.stressmanaconverter;

import com.iridium126.createmanaindustry.CMIPartialModels;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;

import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;

public class StressManaConverterRenderer extends KineticBlockEntityRenderer<StressManaConverterBlockEntity> {

	public StressManaConverterRenderer(BlockEntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	protected void renderSafe(StressManaConverterBlockEntity be, float partialTicks, PoseStack ms,
		MultiBufferSource buffer, int light, int overlay) {

		if (VisualizationManager.supportsVisualization(be.getLevel()))
			return;

		// Large cogs sometimes have to offset their teeth by 11.25 degrees in order to
		// mesh properly

		VertexConsumer vc = buffer.getBuffer(RenderType.solid());
		Direction facing = be.getBlockState().getValue(StressManaConverterBlock.FACING);
		Axis axis = getRotationAxisOf(be);
		renderRotatingBuffer(be,
			CachedBuffers.partialFacing(CMIPartialModels.STRESS_MANA_CONVERTER_OUTER, be.getBlockState(), facing),
			ms, vc, light);

		float angle = getAngleForLargeCogShaft(be, axis);
		SuperByteBuffer shaft =
			CachedBuffers.partialFacing(CMIPartialModels.STRESS_MANA_CONVERTER_INNER, be.getBlockState(), facing);
		kineticRotationTransform(shaft, be, axis, angle, light);
		shaft.renderInto(ms, vc);

	}

	public static float getAngleForLargeCogShaft(StressManaConverterBlockEntity be, Axis axis) {
		BlockPos pos = be.getBlockPos();
		float offset = getShaftAngleOffset(axis, pos);
		float time = AnimationTickHolder.getRenderTime(be.getLevel());
		float angle = ((time * be.getSpeed() * 3f / 10 + offset) % 360) / 180 * (float) Math.PI;
		return angle;
	}

	public static float getShaftAngleOffset(Axis axis, BlockPos pos) {
		if (KineticBlockEntityVisual.shouldOffset(axis, pos)) {
			return 22.5f;
		} else {
			return 0;
		}
	}
}
