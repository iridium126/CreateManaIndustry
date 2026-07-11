package com.iridium126.createmanaindustry.content.kinetics.kineticatomizer;

import com.iridium126.createmanaindustry.CMIBlockEntityTypes;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.kinetics.simpleRelays.ICogWheel;
import com.simibubi.create.foundation.block.IBE;

import net.createmod.catnip.math.VoxelShaper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class KineticAtomizerBlock extends DirectionalKineticBlock implements IBE<KineticAtomizerBlockEntity>, ICogWheel {

    private static final VoxelShaper SHAPE = VoxelShaper.forDirectional(
            Shapes.or(
                    Block.box(0, 0, 0, 16, 12, 16),
                    Block.box(3, 12, 3, 13, 16, 13)),
            Direction.UP);

    public KineticAtomizerBlock(Properties properties) {
        super(properties);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE.get(state.getValue(FACING));
    }

    @Override
    public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, Direction face) {
        return false;
    }

    @Override
    public Axis getRotationAxis(BlockState state) {
        return state.getValue(FACING).getAxis();
    }

    @Override
    public Class<KineticAtomizerBlockEntity> getBlockEntityClass() {
        return KineticAtomizerBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends KineticAtomizerBlockEntity> getBlockEntityType() {
        return CMIBlockEntityTypes.KINETIC_ATOMIZER.get();
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType pathComputationType) {
        return false;
    }
}
