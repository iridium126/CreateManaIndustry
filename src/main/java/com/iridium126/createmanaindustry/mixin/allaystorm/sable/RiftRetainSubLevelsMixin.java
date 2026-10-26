package com.iridium126.createmanaindustry.mixin.allaystorm.sable;
import com.iridium126.createmanaindustry.content.allaystorm.rift.StormRiftTransfers;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.*;
@Mixin(ServerSubLevelContainer.class)
public abstract class RiftRetainSubLevelsMixin {
    @Inject(method="collectForceLoadedSubLevels",at=@At("RETURN"),cancellable=true)
    private void cmi$temporary(CallbackInfoReturnable<Collection<ServerSubLevel>> ci){var all=new LinkedHashSet<>(ci.getReturnValue());for(var sub:((ServerSubLevelContainer)(Object)this).getAllSubLevels())if(StormRiftTransfers.retained(sub.getUniqueId()))all.add(sub);ci.setReturnValue(all);}
}
