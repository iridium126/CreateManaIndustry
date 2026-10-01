package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageAuthorityManager;
import com.simibubi.create.content.logistics.funnel.FunnelBlockEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep the native extractor and its filter/cooldown logic; extend its overflow test. */
@Mixin(FunnelBlockEntity.class)
public abstract class PackageFunnelLightQueryMixin {
    @Shadow private AABB getEntityOverflowScanningArea(){throw new AssertionError();}
    @Inject(method="activateExtractor",at=@At("HEAD"),cancellable=true)
    private void cmi$lightOverflow(CallbackInfo ci){var self=(FunnelBlockEntity)(Object)this;if(self.getLevel() instanceof ServerLevel level&&!PackageAuthorityManager.queryLight(level,getEntityOverflowScanningArea()).isEmpty())ci.cancel();}
}
