package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageIdentified;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorPackage;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChainConveyorPackage.class)
public abstract class ChainPackageIdentityMixin implements PackageIdentified {
    @Unique private long cmi$identity,cmi$generation;
    @Override public long cmi$packageId(){return cmi$identity;}
    @Override public long cmi$packageGeneration(){return cmi$generation;}
    @Override public void cmi$packageIdentity(long id,long generation){cmi$identity=id;cmi$generation=generation;}
    @Inject(method="write",at=@At("RETURN"))
    private void cmi$saveIdentity(HolderLookup.Provider registries,CallbackInfoReturnable<CompoundTag> cir) {
        if(cmi$identity>0 && cmi$generation>0) {
            cir.getReturnValue().putLong("CMIGpuPackageId",cmi$identity);
            cir.getReturnValue().putLong("CMIGpuPackageGeneration",cmi$generation);
        }
    }
    @Inject(method="read",at=@At("RETURN"))
    private static void cmi$readIdentity(CompoundTag tag,HolderLookup.Provider registries,CallbackInfoReturnable<ChainConveyorPackage> cir) {
        long id=tag.getLong("CMIGpuPackageId"),generation=tag.getLong("CMIGpuPackageGeneration");
        if(id>0 && generation>0)((PackageIdentified)cir.getReturnValue()).cmi$packageIdentity(id,generation);
    }
}
