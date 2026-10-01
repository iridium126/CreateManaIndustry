package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageChainAccess;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;
import com.simibubi.create.content.kinetics.chainConveyor.ChainPackageInteractionPacket;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Create selects by progress before removing/copying the actual item. Refresh server-owned
 * checkpoints for that explicit interaction; normal ticks never scan the GPU population. */
@Mixin(ChainPackageInteractionPacket.class)
public abstract class ChainPackageInteractionOwnershipMixin {
    @Shadow private boolean removingPackage;
    @Inject(method="applySettings(Lnet/minecraft/server/level/ServerPlayer;Lcom/simibubi/create/content/kinetics/chainConveyor/ChainConveyorBlockEntity;)V",at=@At("HEAD"))
    private void cmi$interactionCheckpoint(ServerPlayer player,ChainConveyorBlockEntity conveyor,CallbackInfo ci){if(removingPackage)((PackageChainAccess)conveyor).cmi$materializePackages();}
}
