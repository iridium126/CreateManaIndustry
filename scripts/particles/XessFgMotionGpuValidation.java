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
        int shader = glCreateShader(type);
        glShaderSource(shader, Files.readString(SHADERS.resolve(name)));
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
