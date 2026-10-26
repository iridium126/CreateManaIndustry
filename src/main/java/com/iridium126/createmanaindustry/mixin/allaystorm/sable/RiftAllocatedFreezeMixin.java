package com.iridium126.createmanaindustry.mixin.allaystorm.sable;
import com.iridium126.createmanaindustry.content.allaystorm.rift.*;
import com.iridium126.createmanaindustry.content.allaystorm.rift.sable.RiftSableFreeze;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(SubLevelContainer.class)
public abstract class RiftAllocatedFreezeMixin {
    @Inject(method="allocateSubLevel",at=@At("RETURN"))
    private void cmi$recovered(CallbackInfoReturnable<SubLevel> ci){if(ci.getReturnValue() instanceof ServerSubLevel sub&&StormRiftTransfers.frozen(sub.getUniqueId()))RiftSableFreeze.lock(sub);}
}
