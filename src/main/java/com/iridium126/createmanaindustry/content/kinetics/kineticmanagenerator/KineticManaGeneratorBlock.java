package com.iridium126.createmanaindustry.content.kinetics.kineticmanagenerator;

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

public class KineticManaGeneratorBlock extends DirectionalKineticBlock implements IBE<KineticManaGeneratorBlockEntity>, ICogWheel {

	private static final VoxelShaper SHAPE = VoxelShaper.forDirectional(
			Shapes.or(
					Block.box(0, 0, 0, 16, 14, 16),
					Block.box(1, 14, 1, 15, 16, 15)),
			Direction.UP);

	public KineticManaGeneratorBlock(Properties properties) {
		super(properties);
	}

	@Override
	public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE.get(state.getValue(FACING));
	}

	@Override
	public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, Direction face) {
		return face == state.getValue(FACING).getOpposite();
	}

	@Override
	public Axis getRotationAxis(BlockState state) {
		return state.getValue(FACING).getAxis();
	}

	public static BlockPos getManaOutputPos(BlockState state, BlockPos pos) {
		return pos.relative(state.getValue(FACING));
	}

	@Override
	public Class<KineticManaGeneratorBlockEntity> getBlockEntityClass() {
		return KineticManaGeneratorBlockEntity.class;
	}

	@Override
	public BlockEntityType<? extends KineticManaGeneratorBlockEntity> getBlockEntityType() {
		return CMIBlockEntityTypes.KINETIC_MANA_GENERATOR.get();
	}

	@Override
	protected boolean isPathfindable(BlockState state, PathComputationType pathComputationType) {
		return false;
	}

	@Override
	public boolean isLargeCog() {
		return true;
	}
}
