package com.iridium126.createmanaindustry.mixin.iris;

import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import net.minecraft.client.Minecraft;
import net.irisshaders.iris.uniforms.WorldTimeUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=WorldTimeUniforms.class,remap=false)
public abstract class AllayWorldTimeMixin {
    @Inject(method="getWorldDayTime",at=@At("HEAD"),cancellable=true)
    private static void cmi$phase(CallbackInfoReturnable<Integer> ci) {
        var level=Minecraft.getInstance().level;
        if(com.iridium126.createmanaindustry.client.allaystorm.rift.SkyRenderScope.active()||AllvrDimensions.isAllay(level))ci.setReturnValue((int)Math.floorMod(level.getDayTime(),24000L));
    }
    @Inject(method="getWorldDay",at=@At("HEAD"),cancellable=true)
    private static void cmi$day(CallbackInfoReturnable<Integer> ci) {
        var level=Minecraft.getInstance().level;
        if(com.iridium126.createmanaindustry.client.allaystorm.rift.SkyRenderScope.active()||AllvrDimensions.isAllay(level))ci.setReturnValue((int)Math.floorDiv(level.getDayTime(),24000L));
    }
}
