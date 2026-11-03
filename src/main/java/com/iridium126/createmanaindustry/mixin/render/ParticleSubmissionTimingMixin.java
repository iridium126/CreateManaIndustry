package com.iridium126.createmanaindustry.mixin.render;

import com.iridium126.createmanaindustry.client.particles.engine.ParticleFrameStages;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.*;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.culling.Frustum;
import java.util.function.Predicate;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ParticleEngine.class)
public abstract class ParticleSubmissionTimingMixin {
    @WrapMethod(method="render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V")
    private void cmi$particles(LightTexture light,Camera camera,float partial,Frustum frustum,Predicate<ParticleRenderType> filter,Operation<Void> original){
        var scope=ParticleFrameStages.solidSubmission("solid_particles.native.particles.render");boolean success=false;
        try{original.call(light,camera,partial,frustum,filter);success=true;}finally{if(scope!=null)scope.end(success);}
    }
}
