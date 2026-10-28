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
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

/** Driver evidence after effective options and dead-code elimination, collected only during linking. */
@Mixin(value=ProgramBuilder.class,remap=false)
public abstract class StormCloudProgramMixin {
    @WrapMethod(method="buildShader")
    private static net.irisshaders.iris.gl.shader.GlShader cmi$compile(net.irisshaders.iris.gl.shader.ShaderType type,String name,String source,Operation<net.irisshaders.iris.gl.shader.GlShader> original){
        try{return original.call(type,name,source);}catch(RuntimeException failure){
            if(!NativeSkyBuildScope.active())ShaderPackSession.current().compilationFailed(name,failure);
            throw failure;
        }
    }
    @Inject(method="build",at=@At("RETURN"))
    private void cmi$cloudProgram(CallbackInfoReturnable<Program> ci) {
        if(NativeSkyBuildScope.active())return;
        int program=ci.getReturnValue().getProgramId();
        if(GL20.glGetUniformLocation(program,"CmiStormCloud")>=0&&GL20.glGetUniformLocation(program,"CmiStormCenter")>=0)
            ShaderPackSession.current().linkedCloudProgram();
    }
}
