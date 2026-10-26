package com.iridium126.createmanaindustry.mixin.allaystorm;

import com.iridium126.createmanaindustry.content.allaystorm.rift.StormRiftTransfers;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class RiftGroupFreezeMixin {
    @Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
    private void cmi$freezeRoot(Entity entity, CallbackInfo ci) { if (StormRiftTransfers.frozen(entity.getUUID())) ci.cancel(); }
    @Inject(method = "tickPassenger", at = @At("HEAD"), cancellable = true)
    private void cmi$freezePassenger(Entity vehicle, Entity passenger, CallbackInfo ci) { if (StormRiftTransfers.frozen(passenger.getUUID())) ci.cancel(); }
}
