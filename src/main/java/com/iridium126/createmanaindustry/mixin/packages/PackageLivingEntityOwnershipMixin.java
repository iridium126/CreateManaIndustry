package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.client.particles.packages.PackageRenderOwnership;
import com.simibubi.create.content.logistics.box.PackageEntity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Retained authority boxes have stale client positions; their GPU force pass owns contacts. */
@Mixin(LivingEntity.class)
public abstract class PackageLivingEntityOwnershipMixin {
    @Inject(method="pushEntities",at=@At("HEAD"),cancellable=true)
    private void cmi$retainedContacts(CallbackInfo ci) {
        if((Object)this instanceof PackageEntity box && box.level() instanceof ClientLevel
                && PackageRenderOwnership.authorityOwned(box))ci.cancel();
    }
}
