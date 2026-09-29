package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageAuthorityManager;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorPackage;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChainConveyorBlockEntity.class)
public abstract class ChainIdentityLifecycleMixin {
    @Unique private boolean cmi$identitiesInitialized;
    @Inject(method="tick",at=@At("HEAD"))
    private void cmi$initializeIdentities(CallbackInfo ci) {
        if(cmi$identitiesInitialized)return;
        var self=(ChainConveyorBlockEntity)(Object)this;
        if(self.getLevel()==null)return;
        if(self.getLevel() instanceof ServerLevel)PackageAuthorityManager.identifyChain(self);
        cmi$identitiesInitialized=true;
    }
    @Inject(method="read",at=@At("RETURN"))
    private void cmi$loaded(CompoundTag tag,HolderLookup.Provider registries,boolean clientPacket,CallbackInfo ci){cmi$identitiesInitialized=false;}
    @Inject(method="write",at=@At("HEAD"))
    private void cmi$beforeSave(CompoundTag tag,HolderLookup.Provider registries,boolean clientPacket,CallbackInfo ci){cmi$initializeIdentities(ci);}
    @Inject(method="addLoopingPackage",at=@At("HEAD"))
    private void cmi$identifyLoop(ChainConveyorPackage box,CallbackInfoReturnable<Boolean> cir){cmi$identify(box);}
    @Inject(method="addTravellingPackage",at=@At("HEAD"))
    private void cmi$identifyTravel(ChainConveyorPackage box,BlockPos connection,CallbackInfoReturnable<Boolean> cir){cmi$identify(box);}
    @Unique private void cmi$identify(ChainConveyorPackage box) {
        if(((ChainConveyorBlockEntity)(Object)this).getLevel() instanceof ServerLevel level)PackageAuthorityManager.identify(box,level);
    }
}
