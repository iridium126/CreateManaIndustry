package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.content.logistics.gpupackage.*;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorPackage;
import com.simibubi.create.content.contraptions.StructureTransform;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Never cancels the conveyor tick: shafts, routing advertisements and non-owned items remain Create's. */
@Mixin(ChainConveyorBlockEntity.class)
public abstract class ChainOwnershipContainersMixin implements PackageChainAccess,PackageChainRenderAccess {
    @Shadow List<ChainConveyorPackage> loopingPackages;
    @Shadow Map<BlockPos,List<ChainConveyorPackage>> travellingPackages;
    @Unique private PackageChainContainers cmi$containers;
    @Unique private volatile PackageChainContainers.RenderSnapshot cmi$renderSnapshot;
    @Override public PackageChainContainers.RenderSnapshot cmi$renderPackages(){return cmi$renderSnapshot;}
    @Override public void cmi$publishRenderPackages(){
        var self=(ChainConveyorBlockEntity)(Object)this;
        if(self.getLevel()!=null && self.getLevel().isClientSide)cmi$renderSnapshot=cmi$containers==null?null:cmi$containers.renderSnapshot();
    }
    @Override public boolean cmi$acquirePackage(ChainConveyorPackage box,BlockPos connection,PackageOwnershipList.Owner<ChainConveyorPackage> owner) {
        if(cmi$containers==null){cmi$containers=new PackageChainContainers(loopingPackages,travellingPackages);cmi$publishRenderPackages();loopingPackages=cmi$containers.allLoop();}
        if(connection!=null)cmi$containers.syncTravel(connection);
        boolean acquired=cmi$containers.acquire(box,connection,owner);cmi$unwrapIfIdle();return acquired;
    }
    @Unique private void cmi$unwrapIfIdle() {
        if(cmi$containers!=null && !cmi$containers.hasOwned()){loopingPackages=cmi$containers.unwrap();cmi$containers=null;cmi$renderSnapshot=null;}
    }
    @Override public boolean cmi$restorePackage(ChainConveyorPackage box){boolean restored=cmi$containers!=null && cmi$containers.restore(box);cmi$unwrapIfIdle();return restored;}
    @Override public boolean cmi$ownsPackage(ChainConveyorPackage box){return cmi$containers!=null && cmi$containers.owns(box);}
    @Override public void cmi$materializePackage(ChainConveyorPackage box){if(cmi$containers!=null)cmi$containers.materialize(box);}
    @Override public void cmi$materializePackages(){if(cmi$containers!=null)cmi$containers.materializeAll();}
    @Override public void cmi$restorePackages(){if(cmi$containers!=null)try{cmi$containers.restoreAll();}finally{cmi$unwrapIfIdle();}}
    @Unique private void cmi$checkpointAndRestorePackages(){try{cmi$materializePackages();}finally{cmi$restorePackages();}}

    @Inject(method="tick",at=@At("HEAD"))
    private void cmi$restoreNativeListsAfterTerminalRemoval(CallbackInfo ci){cmi$unwrapIfIdle();}
    // Server routing needs progress only. Resolve a logical position at transaction boundaries.
    @Redirect(method="tick",at=@At(value="INVOKE",target="Lcom/simibubi/create/content/kinetics/chainConveyor/ChainConveyorBlockEntity;updateBoxWorldPositions()V"))
    private void cmi$skipServerVisualPositions(ChainConveyorBlockEntity self){
        if(self.getLevel()==null||self.getLevel().isClientSide)self.updateBoxWorldPositions();
    }
    @Unique private void cmi$logicalPosition(ChainConveyorPackage box){
        var self=(ChainConveyorBlockEntity)(Object)this;
        if(self.getLevel()==null||self.getLevel().isClientSide)return;
        BlockPos connection=null;
        for(var entry:travellingPackages.entrySet())if(entry.getValue().contains(box)){connection=entry.getKey();break;}
        box.worldPosition=self.getPackagePosition(box.chainPosition,connection);
    }
    @Inject(method="exportToPort",at=@At("HEAD"))
    private void cmi$positionForPort(ChainConveyorPackage box,BlockPos port,CallbackInfoReturnable<Boolean> cir){cmi$logicalPosition(box);}
    @Inject(method="tick",at=@At("RETURN"))
    private void cmi$publishNativeMembership(CallbackInfo ci){if(cmi$containers!=null)cmi$publishRenderPackages();}

    @Redirect(method={"tick","updateBoxWorldPositions"},at=@At(value="FIELD",opcode=Opcodes.GETFIELD,
            target="Lcom/simibubi/create/content/kinetics/chainConveyor/ChainConveyorBlockEntity;loopingPackages:Ljava/util/List;"))
    private List<ChainConveyorPackage> cmi$createLoop(ChainConveyorBlockEntity self){return cmi$containers==null?loopingPackages:cmi$containers.createLoop();}
    @Redirect(method={"tick","updateBoxWorldPositions"},at=@At(value="FIELD",opcode=Opcodes.GETFIELD,
            target="Lcom/simibubi/create/content/kinetics/chainConveyor/ChainConveyorBlockEntity;travellingPackages:Ljava/util/Map;"))
    private Map<BlockPos,List<ChainConveyorPackage>> cmi$createTravel(ChainConveyorBlockEntity self){return cmi$containers==null?travellingPackages:cmi$containers.createTravel();}
    @Redirect(method="tickBoxVisuals()V",at=@At(value="FIELD",opcode=Opcodes.GETFIELD,
            target="Lcom/simibubi/create/content/kinetics/chainConveyor/ChainConveyorBlockEntity;loopingPackages:Ljava/util/List;"))
    private List<ChainConveyorPackage> cmi$visualLoop(ChainConveyorBlockEntity self){var snapshot=cmi$renderSnapshot;return snapshot==null?loopingPackages:snapshot.loop();}
    @Redirect(method="tickBoxVisuals()V",at=@At(value="FIELD",opcode=Opcodes.GETFIELD,
            target="Lcom/simibubi/create/content/kinetics/chainConveyor/ChainConveyorBlockEntity;travellingPackages:Ljava/util/Map;"))
    private Map<BlockPos,List<ChainConveyorPackage>> cmi$visualTravel(ChainConveyorBlockEntity self){var snapshot=cmi$renderSnapshot;return snapshot==null?travellingPackages:snapshot.travel();}
    @Inject(method="addTravellingPackage",at=@At("RETURN"))
    private void cmi$travelAdded(ChainConveyorPackage box,BlockPos connection,CallbackInfoReturnable<Boolean> cir){if(cmi$containers!=null)cmi$containers.syncTravel(connection);}
    @Inject(method="removeConnectionTo",at=@At("HEAD"))
    private void cmi$beforeDisconnect(BlockPos target,CallbackInfoReturnable<Boolean> cir) {
        if(cmi$containers!=null)cmi$containers.beforeTravelRemoval(target.subtract(((ChainConveyorBlockEntity)(Object)this).getBlockPos()));
    }
    @Inject(method="removeConnectionTo",at=@At("RETURN"))
    private void cmi$afterDisconnect(BlockPos target,CallbackInfoReturnable<Boolean> cir) {
        if(cmi$containers!=null)cmi$containers.syncTravel(target.subtract(((ChainConveyorBlockEntity)(Object)this).getBlockPos()));
    }
    @Inject(method="write",at=@At("HEAD"))
    private void cmi$checkpointForSave(CompoundTag tag,HolderLookup.Provider registries,boolean clientPacket,CallbackInfo ci){cmi$materializePackages();}
    @Inject(method="drop",at=@At("HEAD"))
    private void cmi$checkpointForDrop(ChainConveyorPackage box,CallbackInfo ci){try{cmi$materializePackage(box);}finally{cmi$restorePackage(box);}cmi$logicalPosition(box);}
    @Inject(method="read",at=@At("HEAD"))
    private void cmi$beforeRead(CompoundTag tag,HolderLookup.Provider registries,boolean clientPacket,CallbackInfo ci) {
        PackageChainClientHooks.beforeRead((ChainConveyorBlockEntity)(Object)this);
        cmi$checkpointAndRestorePackages();cmi$containers=null;
    }
    @Inject(method="read",at=@At("RETURN"))
    private void cmi$afterClientRead(CompoundTag tag,HolderLookup.Provider registries,boolean clientPacket,CallbackInfo ci) {
        PackageChainClientHooks.afterRead((ChainConveyorBlockEntity)(Object)this);
    }
    @Inject(method={"destroy","clearContent","remove"},at=@At("HEAD"))
    private void cmi$beforeClear(CallbackInfo ci){PackageChainClientHooks.removed((ChainConveyorBlockEntity)(Object)this);cmi$checkpointAndRestorePackages();cmi$containers=null;}
    @Inject(method="transform",at=@At("HEAD"))
    private void cmi$beforeTransform(BlockEntity be,StructureTransform transform,CallbackInfo ci){PackageChainClientHooks.removed((ChainConveyorBlockEntity)(Object)this);cmi$checkpointAndRestorePackages();cmi$containers=null;}
}
