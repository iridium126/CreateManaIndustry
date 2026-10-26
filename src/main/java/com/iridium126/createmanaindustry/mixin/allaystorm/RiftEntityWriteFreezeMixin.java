package com.iridium126.createmanaindustry.mixin.allaystorm;
import com.iridium126.createmanaindustry.content.allaystorm.rift.*;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(Entity.class)
public abstract class RiftEntityWriteFreezeMixin {
    private boolean cmi$locked(){Entity self=(Entity)(Object)this;return !self.level().isClientSide&&!RiftWorldAccess.allowed()&&StormRiftTransfers.frozen(self.getUUID());}
    @Inject(method="setPos(DDD)V",at=@At("HEAD"),cancellable=true)
    private void cmi$position(double x,double y,double z,CallbackInfo ci){if(cmi$locked())ci.cancel();}
    @Inject(method="stopRiding",at=@At("HEAD"),cancellable=true)
    private void cmi$unmount(CallbackInfo ci){if(cmi$locked())ci.cancel();}
    @Inject(method="startRiding(Lnet/minecraft/world/entity/Entity;Z)Z",at=@At("HEAD"),cancellable=true)
    private void cmi$mount(Entity vehicle,boolean force,CallbackInfoReturnable<Boolean> ci){if(!((Entity)(Object)this).level().isClientSide&&(cmi$locked()||!RiftWorldAccess.allowed()&&StormRiftTransfers.frozen(vehicle.getUUID())))ci.setReturnValue(false);}
}
