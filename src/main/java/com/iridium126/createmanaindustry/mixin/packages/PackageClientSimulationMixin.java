package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.client.particles.packages.PackageRenderOwnership;
import com.simibubi.create.content.logistics.box.PackageEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Preserve the retained entity's interaction/replication lifecycle while its box is GPU owned.
 * This mixin lives only in the client list; dedicated servers never resolve client classes. */
@Mixin(PackageEntity.class)
public abstract class PackageClientSimulationMixin {
    @Inject(method="travel",at=@At("HEAD"),cancellable=true)
    private void cmi$gpuTravel(Vec3 movement,CallbackInfo ci) {
        var self=(PackageEntity)(Object)this;
        if(self.level() instanceof ClientLevel && PackageRenderOwnership.renderedByGpu(self))ci.cancel();
    }
    @Inject(method="canCollideWith",at=@At("HEAD"),cancellable=true)
    private void cmi$retainedCollision(Entity other,CallbackInfoReturnable<Boolean> cir) {
        var self=(PackageEntity)(Object)this;
        if(self.level() instanceof ClientLevel && (PackageRenderOwnership.authorityOwned(self)
                || other instanceof PackageEntity box && PackageRenderOwnership.authorityOwned(box)))cir.setReturnValue(false);
    }
    @Inject(method="isPushable",at=@At("HEAD"),cancellable=true)
    private void cmi$retainedPushable(CallbackInfoReturnable<Boolean> cir) {
        var self=(PackageEntity)(Object)this;
        if(self.level() instanceof ClientLevel && PackageRenderOwnership.authorityOwned(self))cir.setReturnValue(false);
    }
    @Inject(method="push(Lnet/minecraft/world/entity/Entity;)V",at=@At("HEAD"),cancellable=true)
    private void cmi$retainedPush(Entity other,CallbackInfo ci) {
        var self=(PackageEntity)(Object)this;
        if(self.level() instanceof ClientLevel && (PackageRenderOwnership.authorityOwned(self)
                || other instanceof PackageEntity box && PackageRenderOwnership.authorityOwned(box)))ci.cancel();
    }
}
