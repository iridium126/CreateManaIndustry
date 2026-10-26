package com.iridium126.createmanaindustry.mixin.allaystorm;
import com.iridium126.createmanaindustry.content.allaystorm.rift.RiftTransferContext;
import net.minecraft.world.entity.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(LivingEntity.class)
public abstract class RiftDismountMixin {
    @Inject(method="dismountVehicle",at=@At("HEAD"),cancellable=true)
    private void cmi$keepFrozenPose(Entity vehicle,CallbackInfo ci){if(RiftTransferContext.contains(((Entity)(Object)this).getUUID()))ci.cancel();}
}
