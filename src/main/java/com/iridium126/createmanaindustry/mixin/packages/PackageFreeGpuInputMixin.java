package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.client.particles.packages.PackageWorldRuntime;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Defer native input until one nonblocking GPU ray query resolves the actual visual package. */
@Mixin(Minecraft.class)
public abstract class PackageFreeGpuInputMixin {
    @Shadow private int missTime;
    @Inject(method="startUseItem",at=@At("HEAD"),cancellable=true)
    private void cmi$freeUse(CallbackInfo ci){if(PackageWorldRuntime.freeUse())ci.cancel();}
    @Inject(method="startAttack",at=@At("HEAD"),cancellable=true)
    private void cmi$freeAttack(CallbackInfoReturnable<Boolean> cir){if(missTime<=0&&PackageWorldRuntime.freeAttack())cir.setReturnValue(false);}
    @Inject(method="continueAttack",at=@At("HEAD"),cancellable=true)
    private void cmi$waitForFreePick(boolean leftClick,CallbackInfo ci){
        if(PackageWorldRuntime.freeInputPending()){var mc=(Minecraft)(Object)this;if(mc.gameMode!=null)mc.gameMode.stopDestroyBlock();ci.cancel();}
    }
}
