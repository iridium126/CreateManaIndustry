package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageInitialEntityAccess;
import com.simibubi.create.content.logistics.box.PackageEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Free package creation is intercepted before travel; native entity motion has no GPU handback. */
@Mixin(PackageEntity.class)
public abstract class PackageEntityAuthorityMixin implements PackageInitialEntityAccess {
    @Shadow protected abstract void verifyInitialEntity();
    @Override public boolean cmi$validInitialEntity(){verifyInitialEntity();return !((PackageEntity)(Object)this).isRemoved();}
}
