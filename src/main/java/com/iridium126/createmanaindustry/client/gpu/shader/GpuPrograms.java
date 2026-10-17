package com.iridium126.createmanaindustry.client.gpu.shader;

import org.lwjgl.opengl.GL20;

/** Program retirement is one resource operation, including cached locations. */
public final class GpuPrograms {
    private GpuPrograms() {}
    public static void delete(int program) {
        if(program==0)return;
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferRetirement.retiringProgram(program);
        GpuUniforms.clearUniformProgram(program);
        GL20.glDeleteProgram(program);
    }
}
