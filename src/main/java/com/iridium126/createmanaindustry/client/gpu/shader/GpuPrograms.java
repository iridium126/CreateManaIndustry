package com.iridium126.createmanaindustry.client.gpu.shader;

import com.iridium126.createmanaindustry.client.gpu.state.GpuBindings;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.GL20;

/** Program ownership and transitions to externally applied render shaders. */
public final class GpuPrograms {
    private GpuPrograms() {}
    public static void applyExternal(ShaderInstance shader) {
        GpuBindings.invalidateProgram();
        shader.apply();
        GpuBindings.externalProgramApplied(shader.getId());
    }
    public static void clearExternal(ShaderInstance shader) {
        try {
            shader.clear();
        } finally {
            GpuBindings.invalidateProgram();
        }
    }
    public static void delete(int program) {
        if(program==0)return;
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferRetirement.retiringProgram(program);
        GpuUniforms.clearUniformProgram(program);
        GpuBindings.forgetProgram(program);
        GpuShaderCompiler.forget(program);GL20.glDeleteProgram(program);
    }
}
