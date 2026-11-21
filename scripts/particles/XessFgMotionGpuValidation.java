import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.lwjgl.opengl.GL;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45.*;

/** OpenGL velocity and transport probe. It runs in a hidden window without Minecraft or Arc. */
class XessFgMotionGpuValidation {
    private static final Path SHADERS = Path.of("src/main/resources/assets/createmanaindustry/shaders/xessfg");

    public static void main(String[] args) throws Exception {
        require(glfwInit(), "GLFW unavailable");
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 5);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(32, 32, "CMI XeSS-FG motion probe", 0, 0);
        try {
            require(window != 0, "OpenGL 4.5 unavailable");
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            System.out.println(glGetString(GL_RENDERER));
            verifyMotion();
            verifyGpuHistory("additive.vsh");
            verifyGpuHistory("textured.vsh");
            verifyGpuHistory("terrain");
            int exporter = program(stage("export.comp", GL_COMPUTE_SHADER));
            verifyMotionMerge(exporter);
            verifyDepthExport(exporter);
            glDeleteProgram(exporter);
            require(glGetError() == GL_NO_ERROR, "OpenGL error during motion probe");
        } finally {
            if (window != 0) glfwDestroyWindow(window);
            glfwTerminate();
        }
    }

    private static void verifyGpuHistory(String kind) throws Exception {
        require(GL.getCapabilities().GL_NV_shader_atomic_int64 && glGetInteger(GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS) > 35,
                "GPU history bindings or atomics unavailable");
        boolean terrain = kind.equals("terrain");
        String vertex = """
                #version 450 core
                #define VEC4_PER_EMITTER 19u
                layout(std430, binding = 2) readonly buffer ParticleRead { vec4 data[]; } particles;
                layout(std430, binding = 3) readonly buffer Emitters { vec4 u[]; } emitters;
                uniform float offsetX;
                uniform float jitterX;
                uniform mat4 iris_ProjectionMatrix, iris_ModelViewMatrix;
                vec4 getVertexPosition() {
                    vec2 corner = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                    return vec4(corner * 2.0 - 1.0, 0.0, 1.0);
                }
                void main() {
                    uint inst = 0u;
                    uint base = inst * 4u;
                    vec4 p0 = particles.data[base];
                    vec4 p3 = particles.data[base + 3u];
                    gl_Position = getVertexPosition() + vec4(offsetX + jitterX, 0.0, 0.0, 0.0);
                }
                """;
        String fragment = """
                #version 450 core
                uniform int uMode;
                uniform float coverage;
                out vec4 fragColor;
                void main() { fragColor = vec4(coverage, coverage * 0.5, 0.0, coverage); }
                """;
        var shader = terrain
                ? com.iridium126.createmanaindustry.client.xessfg.XessFgGpuShaderSources.terrain(
                        com.iridium126.createmanaindustry.client.xessfg.XessFgPack.BSL, vertex, fragment, false, false)
                : com.iridium126.createmanaindustry.client.xessfg.XessFgGpuShaderSources.particle(kind, vertex, fragment);
        require(shader != null, kind + " program was not decorated");
        int program = program(stageText(shader.vertex(), GL_VERTEX_SHADER), stageText(shader.fragment(), GL_FRAGMENT_SHADER));
        int merge = program(stage("export.comp", GL_COMPUTE_SHADER));
        int width = 16, height = 8;
        int[] buffers = new int[6];
        long[] bytes = {64, 19 * 16, 8, 4096 * 32, 4096 * 32, width * height * 16L};
        for (int i = 0; i < buffers.length; i++) {
            buffers[i] = glCreateBuffers();
            glNamedBufferStorage(buffers[i], bytes[i], GL_DYNAMIC_STORAGE_BIT);
            glClearNamedBufferData(buffers[i], GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, new int[]{0});
        }
        int grid = glCreateBuffers(), vao = glCreateVertexArrays();
        glNamedBufferStorage(grid, 64, GL_DYNAMIC_STORAGE_BIT);
        glBindBufferBase(GL_UNIFORM_BUFFER, 31, grid);
        glNamedBufferSubData(buffers[2], 0, new int[]{0, 19});
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, buffers[0]);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, buffers[1]);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 34, buffers[5]);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 35, buffers[2]);
        glBindVertexArray(vao);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        int sourceWidth = terrain ? width : width * 2, sourceHeight = terrain ? height : height * 2;
        glViewport(0, 0, sourceWidth, sourceHeight);
        int sceneDepth = texture(GL_R32F, sourceWidth, sourceHeight, GL_RED, new float[]{terrain ? .5f : .6f});
        int output = glCreateBuffers();
        int planeWords = width * height;
        glNamedBufferStorage(output, (long) planeWords * 8, GL_DYNAMIC_STORAGE_BIT);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, output);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, buffers[5]);
        int zero = Float.floatToIntBits(0.0f);
        for (int frame = 1; frame <= 6; frame++) {
            int previous = frame == 3 ? 0 : frame - 1;
            float jitter = terrain && frame % 2 == 0 ? .5f : 0;
            glNamedBufferSubData(grid, 0, new int[]{frame, previous, width, height,
                    Float.floatToIntBits(sourceWidth), Float.floatToIntBits(sourceHeight), Float.floatToIntBits(jitter), zero,
                    0, 0, 0, 0, 0, 0, 0, 0});
            glClearNamedBufferData(buffers[5], GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, new int[]{0});
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 32, buffers[3 + frame % 2]);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 33, buffers[3 + (frame + 1) % 2]);
            glUseProgram(program);
            glUniform1f(glGetUniformLocation(program, "offsetX"), .0625f + (frame % 2 == 0 ? .25f : 0));
            glUniform1f(glGetUniformLocation(program, "jitterX"), jitter * 2 / sourceWidth);
            glUniform1f(glGetUniformLocation(program, "coverage"), frame == 5 ? .04f : .7f);
            glUniform1i(glGetUniformLocation(program, "uMode"), 0);
            glUniform1ui(glGetUniformLocation(program, "cmiFgIdentityBase"), 1);
            float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
            glUniformMatrix4fv(glGetUniformLocation(program, "iris_ProjectionMatrix"), false, identity);
            glUniformMatrix4fv(glGetUniformLocation(program, "iris_ModelViewMatrix"), false, identity);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            glUseProgram(merge);
            glBindTextureUnit(0, sceneDepth);
            glUniform2i(glGetUniformLocation(merge, "extent"), width, height);
            glUniform2i(glGetUniformLocation(merge, "sourceSize"), sourceWidth, sourceHeight);
            glUniform2f(glGetUniformLocation(merge, "sourceScale"), 1, 1);
            glUniform2f(glGetUniformLocation(merge, "currentJitter"), jitter, 0);
            glUniform1i(glGetUniformLocation(merge, "rowWords"), width);
            glUniform4i(glGetUniformLocation(merge, "offsets"), 0, 0, 0, planeWords);
            glUniform1i(glGetUniformLocation(merge, "mode"), 9);
            if (frame == 6) glClearTexImage(sceneDepth, 0, GL_RED, GL_FLOAT, new float[]{.3f});
            glDispatchCompute(1, 1, 1);
            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
            long address = ((height - 1 - 4L) * width + 8) * 4;
            int[] depth = new int[1], velocity = new int[1];
            glGetNamedBufferSubData(output, address, depth);
            glGetNamedBufferSubData(output, planeWords * 4L + address, velocity);
            if (frame == 2 || frame == 4 || (terrain && frame == 5)) {
                float expected = frame == 5 ? 2 : -2;
                float dx = Float.float16ToFloat((short) velocity[0]);
                require(Math.abs(dx - expected) < .01f
                                && Math.abs(Float.intBitsToFloat(depth[0]) - (terrain ? .5f : .6f)) < .001,
                        kind + " velocity or scene depth mismatched: " + dx + ", " + Float.intBitsToFloat(depth[0]));
            } else require(velocity[0] == 0x7e007e00, kind + " published hidden, weak, or reset geometry at frame " + frame);
        }
        glDeleteTextures(sceneDepth);
        glDeleteBuffers(output);
        glDeleteVertexArrays(vao);
        glDeleteProgram(program);
        glDeleteProgram(merge);
        glDeleteBuffers(buffers);
        glDeleteBuffers(grid);
        System.out.println("PASS: " + kind + " history, scale, visibility and history reset");
    }

    private static void verifyMotionMerge(int program) {
        int width = 16, height = 8;
        int planeWords = width * height;
        int vectors = texture(GL_RGBA32F, width, height, GL_RGBA, new float[]{0, 0, 0, 0});
        glTextureSubImage2D(vectors, 0, 0, height - 1, 1, 1, GL_RGBA, GL_FLOAT, new float[]{-4, -2, .375f, 1});
        int buffer = glCreateBuffers();
        glNamedBufferStorage(buffer, (long) planeWords * 8, 0);
        glClearNamedBufferData(buffer, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, new int[]{Float.floatToIntBits(.25f)});
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, buffer);
        glBindTextureUnit(0, vectors);
        glUseProgram(program);
        glUniform2i(glGetUniformLocation(program, "extent"), width, height);
        glUniform1i(glGetUniformLocation(program, "rowWords"), width);
        glUniform4i(glGetUniformLocation(program, "offsets"), 0, 0, 0, planeWords);
        glUniform1i(glGetUniformLocation(program, "mode"), 7);
        glDispatchCompute(1, 1, 1);
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
        int[] depths = new int[2], motion = new int[2];
        glGetNamedBufferSubData(buffer, 0, depths);
        glGetNamedBufferSubData(buffer, (long) planeWords * Integer.BYTES, motion);
        require(depths[0] == Float.floatToIntBits(.375f) && depths[1] == Float.floatToIntBits(.25f),
                "Incorrect motion depth coverage");
        require(motion[0] == 0xc000c400 && motion[1] == 0x7e007e00, "Incorrect RG16F motion overlay or sentinel");
        glDeleteBuffers(buffer);
        glDeleteTextures(vectors);
        System.out.println("PASS: motion depth replacement and RG16F validity");
    }

    private static void verifyMotion() throws Exception {
        int w = 64, h = 32;
        int raster = program(stage("motion.vert", GL_VERTEX_SHADER), stage("motion.frag", GL_FRAGMENT_SHADER));
        int depth = texture(GL_R32F, w, h, GL_RED, new float[]{.5f});
        int target = texture(GL_RGBA32F, w, h, GL_RGBA, new float[]{0, 0, 0, 0});
        int framebuffer = glCreateFramebuffers(), vao = glCreateVertexArrays(), vbo = glCreateBuffers();
        glNamedFramebufferTexture(framebuffer, GL_COLOR_ATTACHMENT0, target, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glBindVertexArray(vao);
        glVertexArrayVertexBuffer(vao, 0, vbo, 0, 32);
        for (int i = 0; i < 2; i++) {
            glEnableVertexArrayAttrib(vao, i);
            glVertexArrayAttribBinding(vao, i, 0);
            glVertexArrayAttribFormat(vao, i, 4, GL_FLOAT, false, i * 16);
        }
        glUseProgram(raster);
        glBindTextureUnit(0, depth);
        glUniform2f(glGetUniformLocation(raster, "extent"), w, h);
        glUniform2f(glGetUniformLocation(raster, "sourceScale"), 1, 1);
        glViewport(0, 0, w, h);
        // A triangle moves four pixels right and two pixels down between samples.
        glNamedBufferData(vbo, new float[]{
                -1, -1, 0, 1, -1.125f, -.875f, 0, 1,
                3, -1, 0, 1, 2.875f, -.875f, 0, 1,
                -1, 3, 0, 1, -1.125f, 3.125f, 0, 1
        }, GL_STATIC_DRAW);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        float[] value = new float[4];
        glReadPixels(w / 2, h / 2, 1, 1, GL_RGBA, GL_FLOAT, value);
        require(Math.abs(value[0] + 4) < .01f && Math.abs(value[1] + 2) < .01f && value[2] == .5f && value[3] == 1,
                "Wrong current-to-previous motion " + Arrays.toString(value));
        glClearTexImage(depth, 0, GL_RED, GL_FLOAT, new float[]{.3f});
        glClearBufferfv(GL_COLOR, 0, new float[]{0, 0, 0, 0});
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glReadPixels(w / 2, h / 2, 1, 1, GL_RGBA, GL_FLOAT, value);
        require(value[3] == 0, "Occluded geometry contributed velocity");
        glDeleteProgram(raster);
        glDeleteFramebuffers(framebuffer);
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        glDeleteTextures(new int[]{depth, target});
        System.out.println("PASS: direction, pixel magnitude, and depth rejection");
    }

    private static void verifyDepthExport(int program) {
        int w = 2560, h = 1600;
        int depth = texture(GL_R32F, w, h, GL_RED, new float[]{.25f});
        int buffer = glCreateBuffers(), query = glGenQueries();
        glNamedBufferStorage(buffer, (long) w * h * 4, 0);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, buffer);
        glBindTextureUnit(0, depth);
        glUseProgram(program);
        glUniform2i(glGetUniformLocation(program, "sourceSize"), w, h);
        glUniform2f(glGetUniformLocation(program, "sourceScale"), 1, 1);
        glUniform1i(glGetUniformLocation(program, "mode"), 4);
        for (int scale : new int[]{1, 2}) {
            int width = w / scale, height = h / scale;
            glUniform2i(glGetUniformLocation(program, "extent"), width, height);
            glUniform1i(glGetUniformLocation(program, "rowWords"), width);
            long[] ticks = new long[51];
            for (int i = -8; i < ticks.length; i++) {
                glBeginQuery(GL_TIME_ELAPSED, query);
                glDispatchCompute((width + 15) / 16, (height + 7) / 8, 1);
                glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT);
                glEndQuery(GL_TIME_ELAPSED);
                long elapsed = glGetQueryObjectui64(query, GL_QUERY_RESULT);
                if (i >= 0) ticks[i] = elapsed;
            }
            int[] value = new int[1];
            glGetNamedBufferSubData(buffer, ((long) width * height - 1) * 4, value);
            require(value[0] == Float.floatToIntBits(.25f), "Depth export differed");
            Arrays.sort(ticks);
            System.out.printf("%dx%d: %,d bytes, depth export P50/P95/P99 %.3f/%.3f/%.3f ms%n",
                    width, height, (long) width * height * 4, ticks[25] / 1e6, ticks[48] / 1e6, ticks[50] / 1e6);
        }
        glDeleteQueries(query);
        glDeleteBuffers(buffer);
        glDeleteTextures(depth);
    }

    private static int texture(int format, int w, int h, int components, float[] clear) {
        int texture = glCreateTextures(GL_TEXTURE_2D);
        glTextureStorage2D(texture, 1, format, w, h);
        glTextureParameteri(texture, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTextureParameteri(texture, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glClearTexImage(texture, 0, components, GL_FLOAT, clear);
        return texture;
    }

    private static int stage(String name, int type) throws Exception {
        return stageText(Files.readString(SHADERS.resolve(name)), type);
    }

    private static int stageText(String code, int type) {
        int shader = glCreateShader(type);
        glShaderSource(shader, code);
        glCompileShader(shader);
        require(glGetShaderi(shader, GL_COMPILE_STATUS) != 0, glGetShaderInfoLog(shader));
        return shader;
    }

    private static int program(int... shaders) {
        int program = glCreateProgram();
        for (int shader : shaders) glAttachShader(program, shader);
        glLinkProgram(program);
        require(glGetProgrami(program, GL_LINK_STATUS) != 0, glGetProgramInfoLog(program));
        for (int shader : shaders) glDeleteShader(shader);
        return program;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
