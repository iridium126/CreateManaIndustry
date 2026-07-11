package com.iridium126.createmanaindustry.content.kinetics.kineticmanagenerator;

import java.util.function.Supplier;

import com.iridium126.createmanaindustry.CMIPartialModels;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;

import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import dev.engine_room.flywheel.lib.transform.TransformStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.level.block.state.BlockState;

public class KineticManaGeneratorRenderer extends KineticBlockEntityRenderer<KineticManaGeneratorBlockEntity> {

    public KineticManaGeneratorRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected void renderSafe(KineticManaGeneratorBlockEntity be, float partialTicks, PoseStack ms,
        MultiBufferSource buffer, int light, int overlay) {

        if (VisualizationManager.supportsVisualization(be.getLevel()))
            return;

        VertexConsumer vc = buffer.getBuffer(RenderType.solid());
        Direction facing = be.getBlockState().getValue(KineticManaGeneratorBlock.FACING);
        Axis axis = getRotationAxisOf(be);

        renderRotatingBuffer(be,
            getVerticalPartial(CMIPartialModels.KINETIC_MANA_GENERATOR_OUTER, be.getBlockState(), facing),
            ms, vc, light);

        float angle = getAngleForLargeCogShaft(be, axis);
        SuperByteBuffer shaft =
            getVerticalPartial(CMIPartialModels.KINETIC_MANA_GENERATOR_INNER, be.getBlockState(), facing);
        kineticRotationTransform(shaft, be, axis, angle, light);
        shaft.renderInto(ms, vc);
    }

    private static SuperByteBuffer getVerticalPartial(PartialModel partial, BlockState state, Direction facing) {
        return CachedBuffers.partialDirectional(partial, state, facing, rotateVerticalModelToFace(facing));
    }

    private static Supplier<PoseStack> rotateVerticalModelToFace(Direction facing) {
        return () -> {
            PoseStack stack = new PoseStack();
            TransformStack.of(stack)
                .center()
                .rotateYDegrees(horizontalYRot(facing))
                .rotateXDegrees(AngleHelper.verticalAngle(facing) + 90)
                .uncenter();
            return stack;
        };
    }

    private static float horizontalYRot(Direction facing) {
        return switch (facing) {
            case NORTH -> 180;
            case SOUTH -> 0;
            case EAST -> 90;
            case WEST -> 270;
            default -> 0;
        };
    }

    public static float getAngleForLargeCogShaft(KineticManaGeneratorBlockEntity be, Axis axis) {
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
