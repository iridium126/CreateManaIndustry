package com.iridium126.createmanaindustry.content.kinetics.condenser;

import java.util.List;

import com.iridium126.createmanaindustry.config.Config;
import com.iridium126.createmanaindustry.content.fluids.mist.MistFieldStore;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import com.simibubi.create.content.fluids.drain.ItemDrainBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

public class CondenserBlockEntity extends SmartBlockEntity {

    public CondenserBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        behaviours.add(new CondenserFluidTransportBehaviour(this));
        registerAwardables(behaviours, FluidPropagator.getSharedTriggers());
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide)
            return;

        // 1. Check for water flow through pipe connections and gather pressure
        FluidTransportBehaviour fluidBehaviour = getBehaviour(FluidTransportBehaviour.TYPE);
        if (fluidBehaviour == null)
            return;

        BlockState state = getBlockState();
        Direction facing = state.getValue(BlockStateProperties.FACING);
        float flowPressure = 0f;
        boolean waterFlowing = false;
        for (Direction side : new Direction[]{facing, facing.getOpposite()}) {
            PipeConnection.Flow flow = fluidBehaviour.getFlow(side);
            if (flow != null && flow.complete && flow.fluid.is(FluidTags.WATER)) {
                waterFlowing = true;
                PipeConnection conn = fluidBehaviour.getConnection(side);
                if (conn != null)
                    flowPressure = Math.max(flowPressure, conn.getPressure().get(flow.inbound));
            }
        }
        if (!waterFlowing)
            return;

        // 2. Check for mist at condenser position
        ResourceLocation mistFluidId = MistFieldStore.getFluidType(level, worldPosition);
        if (mistFluidId == null)
            return;
        float concentration = MistFieldStore.getConcentration(level, worldPosition);
        if (concentration <= 0)
            return;

        // 3. Check for Drain below
        BlockEntity below = level.getBlockEntity(worldPosition.below());
        if (!(below instanceof ItemDrainBlockEntity drain))
            return;

        // 4. Inject mist fluid into Drain
        SmartFluidTankBehaviour tank = drain.getBehaviour(SmartFluidTankBehaviour.TYPE);
        if (tank == null)
            return;

        Fluid fluid = BuiltInRegistries.FLUID.get(mistFluidId);
        if (fluid == null)
            return;

        int amount = Math.max(1, (int) (concentration * Config.condenseEfficiency * (1 + flowPressure / 64)));
        FluidStack stack = new FluidStack(fluid, amount);

        tank.allowInsertion();
        tank.getPrimaryHandler().fill(stack, IFluidHandler.FluidAction.EXECUTE);
        tank.forbidInsertion();
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
