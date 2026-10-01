package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.client.particles.packages.PackageRenderOwnership;
import com.simibubi.create.content.logistics.box.PackageEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Preserve the retained entity's interaction/replication lifecycle while its box is GPU owned.
 * This mixin lives only in the client list; dedicated servers never resolve client classes. */
@Mixin(PackageEntity.class)
public abstract class PackageClientSimulationMixin {
    @Inject(method="travel",at=@At("HEAD"),cancellable=true)
    private void cmi$gpuTravel(Vec3 movement,CallbackInfo ci) {
        if(PackageRenderOwnership.renderedByGpu((PackageEntity)(Object)this))ci.cancel();
    }
}
