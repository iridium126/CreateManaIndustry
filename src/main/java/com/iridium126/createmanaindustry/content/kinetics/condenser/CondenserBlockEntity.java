package com.iridium126.createmanaindustry.content.kinetics.condenser;

import java.util.List;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

public class CondenserBlockEntity extends SmartBlockEntity {

    public CondenserBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        behaviours.add(new CondenserFluidTransportBehaviour(this));
        registerAwardables(behaviours, FluidPropagator.getSharedTriggers());
    }

    class CondenserFluidTransportBehaviour extends FluidTransportBehaviour {

        public CondenserFluidTransportBehaviour(SmartBlockEntity be) {
            super(be);
        }

        @Override
        public boolean canHaveFlowToward(BlockState state, Direction direction) {
            Direction facing = state.getValue(BlockStateProperties.FACING);
            return direction == facing || direction == facing.getOpposite();
        }

        @Override
        public AttachmentTypes getRenderedRimAttachment(BlockAndTintGetter world, BlockPos pos,
                BlockState state, Direction direction) {
            if (!canHaveFlowToward(state, direction))
                return AttachmentTypes.NONE;
            AttachmentTypes attachment = super.getRenderedRimAttachment(world, pos, state, direction);
            if (attachment == AttachmentTypes.RIM)
                return AttachmentTypes.DETAILED_CONNECTION;
            return attachment;
        }
    }
}
