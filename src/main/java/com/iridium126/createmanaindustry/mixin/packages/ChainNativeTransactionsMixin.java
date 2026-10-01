package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageChainNativeAccess;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorPackage;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChainConveyorBlockEntity.class)
public interface ChainNativeTransactionsMixin extends PackageChainNativeAccess {
    @Override @Invoker("exportToPort") boolean cmi$exportToPort(ChainConveyorPackage box,BlockPos offset);
    @Override @Invoker("notifyPortToAnticipate") void cmi$anticipatePort(BlockPos offset);
}
