package com.iridium126.createmanaindustry.mixin.allaystorm;

import com.iridium126.createmanaindustry.client.allaystorm.rift.SkyRenderScope;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class AllayScopedSkyTimeMixin implements LevelAccessor {
    /** Vanilla sun/sky/cloud color and moon queries use this interface clock,
     * which otherwise reads LevelData directly and bypasses getDayTime(). */
    @Override public long dayTime() {
        if(SkyRenderScope.active()&&((Level)(Object)this).isClientSide)return SkyRenderScope.dayTime();
        return LevelAccessor.super.dayTime();
    }
    @Inject(method="getDayTime",at=@At("HEAD"),cancellable=true)
    private void cmi$skyTime(CallbackInfoReturnable<Long> ci){if(((Level)(Object)this).isClientSide&&SkyRenderScope.active())ci.setReturnValue(SkyRenderScope.dayTime());}
    @Inject(method="getRainLevel",at=@At("HEAD"),cancellable=true)
    private void cmi$skyRain(float partial,CallbackInfoReturnable<Float> ci){if(((Level)(Object)this).isClientSide&&SkyRenderScope.active())ci.setReturnValue(SkyRenderScope.rain());}
    @Inject(method="getThunderLevel",at=@At("HEAD"),cancellable=true)
    private void cmi$skyThunder(float partial,CallbackInfoReturnable<Float> ci){if(((Level)(Object)this).isClientSide&&SkyRenderScope.active())ci.setReturnValue(SkyRenderScope.thunder());}
}
