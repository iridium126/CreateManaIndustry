package com.iridium126.createmanaindustry.content.kinetics.kineticatomizer;

import static net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING;

import com.iridium126.createmanaindustry.CMIPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;

import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

public class KineticAtomizerRenderer extends KineticBlockEntityRenderer<KineticAtomizerBlockEntity> {

    public KineticAtomizerRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected SuperByteBuffer getRotatedModel(KineticAtomizerBlockEntity be, BlockState state) {
        Direction facing = state.getValue(FACING);
        return CachedBuffers.partialFacingVertical(CMIPartialModels.KINETIC_ATOMIZER_COG, state, facing);
    }
}