package com.iridium126.createmanaindustry.mixin.render;

import com.iridium126.createmanaindustry.client.particles.engine.ParticleFrameStages;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.*;
import org.spongepowered.asm.mixin.Mixin;

/** Only the solid_particles sequential section; all contains type when it flushes types. */
@Mixin(MultiBufferSource.BufferSource.class)
public abstract class BatchSubmissionTimingMixin {
    @WrapMethod(method="endBatch()V")
    private void cmi$all(Operation<Void> original){
        var scope=ParticleFrameStages.solidSubmission("solid_particles.native.batch.all");boolean success=false;
        try{original.call();success=true;}finally{if(scope!=null)scope.end(success);}
    }
    @WrapMethod(method="endBatch(Lnet/minecraft/client/renderer/RenderType;)V")
    private void cmi$type(RenderType type,Operation<Void> original){
        var scope=ParticleFrameStages.solidSubmission("solid_particles.native.batch.type");boolean success=false;
        try{original.call(type);success=true;}finally{if(scope!=null)scope.end(success);}
    }
}
