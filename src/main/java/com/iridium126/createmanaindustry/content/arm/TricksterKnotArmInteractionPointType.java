package com.iridium126.createmanaindustry.content.arm;

import com.iridium126.createmanaindustry.trickster.TricksterKnotUtils;
import com.iridium126.createmanaindustry.trickster.TricksterReflection;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPointType;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class TricksterKnotArmInteractionPointType extends ArmInteractionPointType {
    @Override
    public boolean canCreatePoint(Level level, BlockPos pos, BlockState state) {
        if (!TricksterReflection.isAvailable())
            return false;
        BlockEntity be = level.getBlockEntity(pos);
        return be != null && TricksterKnotUtils.isTricksterKnotBlockEntity(be);
    }

    @Override
    public ArmInteractionPoint createPoint(Level level, BlockPos pos, BlockState state) {
        return new TricksterKnotArmInteractionPoint(this, level, pos, state);
    }
}
