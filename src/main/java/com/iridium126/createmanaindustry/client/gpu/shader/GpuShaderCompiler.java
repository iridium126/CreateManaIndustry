package com.iridium126.createmanaindustry.client.gpu.shader;

import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL43;

/** Compiles owned programs without depending on a particle engine or a business module. */
public final class GpuShaderCompiler {
    private GpuShaderCompiler() {}

    public static int compute(String label, String source) {
        int shader = stage(label, GL43.GL_COMPUTE_SHADER, source);
        try {
            return link(label, shader, 0);
        } finally {
            GL20.glDeleteShader(shader);
        }
    }

    public static int graphics(String label, String vertex, String fragment) {
        int vsh = 0, fsh = 0;
        try {
            vsh = stage(label + " vertex", GL20.GL_VERTEX_SHADER, vertex);
            fsh = stage(label + " fragment", GL20.GL_FRAGMENT_SHADER, fragment);
            return link(label, vsh, fsh);
        } finally {
            if (vsh != 0) GL20.glDeleteShader(vsh);
            if (fsh != 0) GL20.glDeleteShader(fsh);
        }
    }

    private static int stage(String label, int type, String source) {
        int shader = GL20.glCreateShader(type);
        if (shader == 0) throw new IllegalStateException(label + ": cannot allocate shader");
        try {
            GL20.glShaderSource(shader, source);
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0)
                throw new IllegalStateException(label + ": " + GL20.glGetShaderInfoLog(shader));
            return shader;
        } catch (RuntimeException | Error failure) {
            GL20.glDeleteShader(shader);
            throw failure;
        }
    }

    private static int link(String label, int first, int second) {
        int program = GL20.glCreateProgram();
        try {
            if (program == 0) throw new IllegalStateException(label + ": cannot allocate program");
            GL20.glAttachShader(program, first);
            if (second != 0) GL20.glAttachShader(program, second);
            GL20.glLinkProgram(program);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0)
                throw new IllegalStateException(label + ": " + GL20.glGetProgramInfoLog(program));
            GL20.glDetachShader(program, first);
            if (second != 0) GL20.glDetachShader(program, second);
            return program;
        } catch (RuntimeException | Error failure) {
            if (program != 0) com.iridium126.createmanaindustry.client.gpu.shader.GpuPrograms.delete(program);
            throw failure;
        }
    }
}
