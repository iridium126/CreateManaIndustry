package com.iridium126.createmanaindustry.mixin.render;

import com.iridium126.createmanaindustry.client.xessfg.XessFgRuntime;
import dev.engine_room.flywheel.api.backend.Engine;
import dev.engine_room.flywheel.api.backend.RenderContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.engine_room.flywheel.backend.engine.EngineImpl", remap = false)
abstract class XessFgFlywheelMotionMixin {
    @Inject(method = "render(Ldev/engine_room/flywheel/api/backend/RenderContext;)V", at = @At("RETURN"))
    private void cmi$motion(RenderContext context, CallbackInfo ci) {
        XessFgRuntime.flywheelMotion((Engine) this, context);
    }
}
