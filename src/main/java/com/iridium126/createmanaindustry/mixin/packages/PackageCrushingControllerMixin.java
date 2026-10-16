package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.content.logistics.gpupackage.*;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(CrushingWheelControllerBlockEntity.class)
public abstract class PackageCrushingControllerMixin extends SmartBlockEntity implements PackageCrushingAccess {
    @Unique private PackageLease.Identity cmi$package;
    protected PackageCrushingControllerMixin(BlockEntityType<?> type,BlockPos pos,BlockState state){super(type,pos,state);}
    @Override public boolean cmi$hasPackage(){return cmi$package!=null;}
    @Override public boolean cmi$ownsPackage(PackageLease.Identity identity){return identity.equals(cmi$package);}
    @Override public boolean cmi$startPackage(PackageLease.Identity identity){
        var self=(CrushingWheelControllerBlockEntity)(Object)this;
        if(cmi$package!=null)return cmi$package.equals(identity);
        if(self.isOccupied())return false;cmi$package=identity;setChanged();return true;
    }
    @Inject(method="isOccupied",at=@At("HEAD"),cancellable=true)
    private void cmi$occupied(CallbackInfoReturnable<Boolean> cir){if(cmi$package!=null)cir.setReturnValue(true);}
    @Inject(method="tick",at=@At("HEAD"),cancellable=true)
    private void cmi$recordTick(CallbackInfo ci){
        if(cmi$package==null)return;super.tick();
        if(level instanceof ServerLevel server&&!PackageLightCrushing.tick(server,(CrushingWheelControllerBlockEntity)(Object)this,cmi$package)){cmi$package=null;setChanged();}
        ci.cancel();
    }
    @Inject(method="clear",at=@At("TAIL")) private void cmi$clear(CallbackInfo ci){cmi$package=null;}
    @Inject(method="write",at=@At("TAIL"))
    private void cmi$write(CompoundTag tag,HolderLookup.Provider registries,boolean clientPacket,CallbackInfo ci){
        if(cmi$package!=null&&!clientPacket){tag.putLong("CMIPackageId",cmi$package.id());tag.putLong("CMIPackageGeneration",cmi$package.generation());}
    }
    @Inject(method="read",at=@At("TAIL"))
    private void cmi$read(CompoundTag tag,HolderLookup.Provider registries,boolean clientPacket,CallbackInfo ci){
        long id=tag.getLong("CMIPackageId"),generation=tag.getLong("CMIPackageGeneration");
        if(!clientPacket)cmi$package=id>0&&generation>0?new PackageLease.Identity(id,generation):null;
    }
}
