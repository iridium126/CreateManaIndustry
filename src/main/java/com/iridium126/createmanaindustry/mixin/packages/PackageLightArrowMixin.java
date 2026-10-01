package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageLightGameplay;
import net.minecraft.world.entity.projectile.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractArrow.class)
public abstract class PackageLightArrowMixin {
    @Inject(method="tick",at=@At("HEAD"),cancellable=true)
    private void cmi$lightHit(CallbackInfo ci){if(PackageLightGameplay.arrowHit((AbstractArrow)(Object)this))ci.cancel();}
}
