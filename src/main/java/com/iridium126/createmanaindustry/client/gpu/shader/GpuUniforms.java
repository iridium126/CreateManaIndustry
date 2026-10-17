package com.iridium126.createmanaindustry.client.gpu.shader;

import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.joml.Matrix4fc;
import java.nio.FloatBuffer;

/** Uniform operations for the current GL context. Program owners retire names before deletion. */
public final class GpuUniforms {
    private record Context(org.lwjgl.opengl.GLCapabilities gl,GpuUniformCache locations) {}
    private static final ThreadLocal<Context> CONTEXT=new ThreadLocal<>();
    private GpuUniforms() {}
    public static void clear() {var context=CONTEXT.get();if(context!=null)context.locations().clear();}


    public static void clearUniformProgram(int program){var context=CONTEXT.get();if(context!=null)context.locations().retire(program);}
    /** Owners may retain this context's cache for one binding scope; retirement clears it in place. */
    public static GpuUniformCache currentCache() {
        var gl=org.lwjgl.opengl.GL.getCapabilities();var context=CONTEXT.get();
        if(context==null||context.gl()!=gl){context=new Context(gl,new GpuUniformCache());CONTEXT.set(context);}
        return context.locations();
    }
    public static int loc(int prog, String name) { return currentCache().location(prog,name); }

    public static void setUIntUniform(int prog, String name, int value) {
        int l = loc(prog, name);
        if (l >= 0)
            GL30.glUniform1ui(l, value);
    }

    public static void setIntUniform(int prog, String name, int value) {
        int l = loc(prog, name);
        if (l >= 0)
            GL20.glUniform1i(l, value);
    }

    public static void setFloatUniform(int prog, String name, float value) {
        int l = loc(prog, name);
        if (l >= 0)
            GL20.glUniform1f(l, value);
    }

    public static void setFloatUniform(int prog, String name, float x, float y, float z) {
        int l = loc(prog, name);
        if (l >= 0)
            GL20.glUniform3f(l, x, y, z);
    }

    /** Uploads a vec4 uniform array ({@code values.length} must be a multiple of 4). */
    public static void setVec4ArrayUniform(int prog, String name, float[] values) {
        int l = loc(prog, name);
        if (l >= 0 && values.length >= 4)
            GL20.glUniform4fv(l, values);
    }

    /** Uploads a float uniform array ({@code uniform float name[N]}). */
    public static void setFloatArrayUniform(int prog, String name, float[] values) {
        int l = loc(prog, name);
        if (l >= 0 && values.length > 0)
            GL20.glUniform1fv(l, values);
    }

    public static void setMat4Uniform(int prog, String name, Matrix4fc matrix) {
        int l = loc(prog, name);
        if (l < 0)
            return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buf = stack.mallocFloat(16);
            matrix.get(buf);
            GL20.glUniformMatrix4fv(l, false, buf);
        }
    }
}
