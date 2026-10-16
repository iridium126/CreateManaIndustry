package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public interface PackagePressurePlateAccess {
    int cmi$signal(BlockState state);
    void cmi$checkPressed(Entity source,Level level,BlockPos pos,BlockState state,int signal);
}
