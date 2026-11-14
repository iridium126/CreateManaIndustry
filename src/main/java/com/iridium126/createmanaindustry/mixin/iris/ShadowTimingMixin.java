package com.iridium126.createmanaindustry.mixin.iris;

import com.iridium126.createmanaindustry.client.particles.engine.ParticleFrameStages;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = ShadowRenderer.class, remap = false)
abstract class ShadowTimingMixin {
    @WrapMethod(method = "renderShadows")
    private void cmi$wholeShadow(LevelRendererAccessor renderer, Camera camera, Operation<Void> original) {
        var timing = ParticleFrameStages.begin("iris.shadow.whole");
        boolean success = false;
        try {
            original.call(renderer, camera);
            success = true;
        } finally {
            if (timing != null) timing.end(success);
        }
    }
}
