package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorPackage;
import net.minecraft.core.BlockPos;

/** Typed invocation of native Create inventory/anticipation callbacks; never reflection. */
public interface PackageChainNativeAccess {
    boolean cmi$exportToPort(ChainConveyorPackage box,BlockPos offset);
    void cmi$anticipatePort(BlockPos offset);
}
