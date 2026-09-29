package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.client.particles.packages.PackageCollisionRuntime;
import com.simibubi.create.content.contraptions.Contraption;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Client-only, exact Create ABI gated by CMIMixinPlugin. */
@Mixin(value=Contraption.class,remap=false)
public abstract class ContraptionCollisionMixin {
    @Inject(method="invalidateColliders",at=@At("HEAD"))
    private void cmi$invalidateMovingGeometry(CallbackInfo ci) {
        if(Minecraft.getInstance().isSameThread())PackageCollisionRuntime.contraptionChanged((Contraption)(Object)this);
    }
}
