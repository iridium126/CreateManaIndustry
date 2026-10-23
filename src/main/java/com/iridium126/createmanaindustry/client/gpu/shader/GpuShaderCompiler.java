package com.iridium126.createmanaindustry.client.gpu.shader;

import org.lwjgl.opengl.ARBParallelShaderCompile;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL43;

/** Compiles owned programs without depending on a particle engine or a business module. */
public final class GpuShaderCompiler {
    private GpuShaderCompiler() {}

    public static final class Compilation implements AutoCloseable {
        private final String label;
        private final String[] sources;
        private final int[] types, shaders;
        private final boolean parallel;
        private int index, program;
        private boolean submitted, linked, complete, closed;

        private Compilation(String label, String[] sources, int[] types) {
            this.label = label;
            this.sources = sources;
            this.types = types;
            this.shaders = new int[sources.length];
            parallel = GL.getCapabilities().GL_ARB_parallel_shader_compile;
        }
        public boolean poll() {
            if (closed) throw new IllegalStateException("Shader compilation is closed: " + label);
            if (complete) return true;
            if (index < shaders.length) {
                if (!submitted) {
                    int shader = GL20.glCreateShader(types[index]);
                    if (shader == 0) throw new IllegalStateException(label + ": cannot allocate shader");
                    shaders[index] = shader;
                    GL20.glShaderSource(shader, sources[index]);
                    GL20.glCompileShader(shader);
                    submitted = true;
                    return false;
                }
                if (parallel && GL20.glGetShaderi(shaders[index], ARBParallelShaderCompile.GL_COMPLETION_STATUS_ARB) == 0) return false;
                if (GL20.glGetShaderi(shaders[index], GL20.GL_COMPILE_STATUS) == 0)
                    throw new IllegalStateException(label + ": " + GL20.glGetShaderInfoLog(shaders[index]));
                index++;
                submitted = false;
                return false;
            }
            if (!linked) {
                program = GL20.glCreateProgram();
                if (program == 0) throw new IllegalStateException(label + ": cannot allocate program");
                for (int shader : shaders) GL20.glAttachShader(program, shader);
                GL20.glLinkProgram(program);
                linked = true;
                return false;
            }
            if (parallel && GL20.glGetProgrami(program, ARBParallelShaderCompile.GL_COMPLETION_STATUS_ARB) == 0) return false;
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0)
                throw new IllegalStateException(label + ": " + GL20.glGetProgramInfoLog(program));
            for (int shader : shaders) GL20.glDetachShader(program, shader);
            complete = true;
            return true;
        }
        public int take() {
            if (closed || !complete || program == 0) throw new IllegalStateException("Shader compilation is incomplete: " + label);
            int result = program;
            program = 0;
            return result;
        }
        @Override public void close() {
            closed = true;
            if (program != 0) {
                GL20.glDeleteProgram(program);
                program = 0;
            }
            for (int stage = 0; stage < shaders.length; stage++) {
                if (shaders[stage] != 0) {
                    GL20.glDeleteShader(shaders[stage]);
                    shaders[stage] = 0;
                }
            }
        }
    }
    public static Compilation beginCompute(String label, String source) {
        return new Compilation(label, new String[] {source}, new int[] {GL43.GL_COMPUTE_SHADER});
    }
    public static Compilation beginGraphics(String label, String vertex, String fragment) {
        return new Compilation(label, new String[] {vertex, fragment}, new int[] {GL20.GL_VERTEX_SHADER, GL20.GL_FRAGMENT_SHADER});
    }

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
