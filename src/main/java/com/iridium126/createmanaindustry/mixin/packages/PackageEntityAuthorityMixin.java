package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageInitialEntityAccess;
import com.simibubi.create.content.logistics.box.PackageEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.nbt.CompoundTag;
import java.util.UUID;

/** Initial item validation and lossless native state at the explicit server-disable boundary. */
@Mixin(PackageEntity.class)
public abstract class PackageEntityAuthorityMixin implements PackageInitialEntityAccess {
    @Shadow protected abstract void verifyInitialEntity();
    @Unique private UUID cmi$tossedById;
    @Override public boolean cmi$validInitialEntity(){verifyInitialEntity();return !((PackageEntity)(Object)this).isRemoved();}
    @Override public UUID cmi$tossedBy(){var player=((PackageEntity)(Object)this).tossedBy.get();return player==null?cmi$tossedById:player.getUUID();}
    @Override public void cmi$tossedBy(UUID uuid){cmi$tossedById=uuid;var self=(PackageEntity)(Object)this;self.tossedBy=new java.lang.ref.WeakReference<>(uuid==null?null:self.level().getPlayerByUUID(uuid));}
    @Inject(method="addAdditionalSaveData",at=@At("TAIL"))
    private void cmi$saveNativeState(CompoundTag tag,CallbackInfo ci){
        tag.putInt("CMIPackageInsertionDelay",((PackageEntity)(Object)this).insertionDelay);
        var uuid=cmi$tossedBy();if(uuid!=null)tag.putUUID("CMIPackageTossedBy",uuid);
    }
    @Inject(method="readAdditionalSaveData",at=@At("TAIL"))
    private void cmi$readNativeState(CompoundTag tag,CallbackInfo ci){
        if(tag.contains("CMIPackageInsertionDelay"))((PackageEntity)(Object)this).insertionDelay=Math.max(0,tag.getInt("CMIPackageInsertionDelay"));
        cmi$tossedBy(tag.hasUUID("CMIPackageTossedBy")?tag.getUUID("CMIPackageTossedBy"):null);
    }
    @Inject(method="tick",at=@At("HEAD"))
    private void cmi$resolveThrower(CallbackInfo ci){if(cmi$tossedById!=null&&((PackageEntity)(Object)this).tossedBy.get()==null)cmi$tossedBy(cmi$tossedById);}
}
