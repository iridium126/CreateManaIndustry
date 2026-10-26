package com.iridium126.createmanaindustry.mixin.allaystorm.sable;
import com.iridium126.createmanaindustry.content.allaystorm.rift.sable.RiftSableFreeze;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ServerSubLevel.class)
public abstract class RiftSubLevelFreezeMixin {
    @Inject(method={"prePhysicsTick","applyQueuedForces","tick","onPlotBoundsChanged"},at=@At("HEAD"),cancellable=true)
    private void cmi$forces(CallbackInfo ci){if(RiftSableFreeze.frozen((ServerSubLevel)(Object)this))ci.cancel();}
}
