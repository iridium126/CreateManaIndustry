package com.iridium126.createmanaindustry.mixin.iris;

import com.iridium126.createmanaindustry.client.render.shaderpack.NativeSkyBuildScope;
import com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackSession;
import net.irisshaders.iris.gl.program.Program;
import net.irisshaders.iris.gl.program.ProgramBuilder;
import org.lwjgl.opengl.GL20;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Driver evidence after effective options and dead-code elimination, collected only during linking. */
@Mixin(value=ProgramBuilder.class,remap=false)
public abstract class StormCloudProgramMixin {
    @Inject(method="build",at=@At("RETURN"))
    private void cmi$cloudProgram(CallbackInfoReturnable<Program> ci) {
        if(NativeSkyBuildScope.active())return;
        int program=ci.getReturnValue().getProgramId();
        if(GL20.glGetUniformLocation(program,"CmiStormCloud")>=0&&GL20.glGetUniformLocation(program,"CmiStormCenter")>=0)
            ShaderPackSession.current().linkedCloudProgram();
    }
}
