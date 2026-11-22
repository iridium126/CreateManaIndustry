package com.iridium126.createmanaindustry.client.dlss;

import com.iridium126.createmanaindustry.client.gpu.state.GpuGlErrors;
import com.iridium126.createmanaindustry.client.particles.engine.spatial.OptixQueryWorker;
import com.iridium126.createmanaindustry.client.particles.engine.spatial.ParticleNativeModules;
import com.iridium126.createmanaindustry.client.particles.engine.spatial.StormGpuDevices;
import com.iridium126.createmanaindustry.client.xessfg.XessFgCoordinates;
import com.iridium126.createmanaindustry.client.xessfg.XessFgDlssValidation;
import java.nio.file.Files;
import java.nio.file.Path;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45.*;

/** Exercises the production NGX evaluator with GL textures on the actual render adapter. */
public final class DlssGpuValidation {
    private DlssGpuValidation() {}

    public static void main(String[] args) throws Exception {
        require(glfwInit(), "GLFW failed to initialize");
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 5);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        var flags = java.util.Arrays.asList(args);
        boolean arc = flags.contains("--arc");
        glfwWindowHint(GLFW_FOCUS_ON_SHOW, GLFW_FALSE);
        long window = glfwCreateWindow(arc ? 960 : 32, arc ? 540 : 32, "CMI DLSS validation", 0, 0);
        try {
            require(window != 0, "OpenGL 4.5 unavailable");
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            System.out.println(glGetString(GL_RENDERER));
            XessFgDlssValidation.verifyMotionTargets();
            require(DlssInterop.supported(), "GL shared textures or fences unavailable");
            var adapter = StormGpuDevices.renderAdapter();
            require(adapter.luid() != 0 && adapter.nodes() == 1, "Invalid OpenGL adapter LUID");
            Path directory = DlssNative.load(Path.of("build/dlss-hardware-probe"));
            Path cache = Files.createDirectories(directory.resolve("ngx"));
            verify(adapter.luid(), directory, cache, 640, 360, 960, 540, null);
            verify(adapter.luid(), directory, cache, 642, 362, 963, 543, null);
            verify(adapter.luid(), directory, cache, 640, 360, 640, 360, null);
            verify(adapter.luid(), directory, cache, 1707, 1067, 2560, 1600, null);
            verify(adapter.luid(), directory, cache, 640, 360, 960, 540, null, DlssPack.Encoding.SILDUR);
            verifyIrisTargets();
            verifyIrisHistoryOverwrite();
            verifyIrisMutableDepth(GL_DEPTH_COMPONENT, GL_UNSIGNED_SHORT, GL_DEPTH_ATTACHMENT);
            verifyIrisMutableDepth(GL_DEPTH_STENCIL, GL_UNSIGNED_INT_24_8, GL_DEPTH_STENCIL_ATTACHMENT);
            if (flags.contains("--optix")) verifyOptixInterop();
            if (arc) {
                glfwShowWindow(window);
                glfwPollEvents();
                try (var fg = new XessFgDlssValidation(window, adapter.luid(), 960, 540)) {
                    verify(adapter.luid(), directory, cache, 640, 360, 960, 540, fg);
                    fg.verify();
                }
            }
        } finally {
            if (window != 0) glfwDestroyWindow(window);
            glfwTerminate();
        }
    }

    private static void verify(long luid, Path directory, Path cache, int width, int height,
                               int outputWidth, int outputHeight, XessFgDlssValidation fg) throws Exception {
        verify(luid, directory, cache, width, height, outputWidth, outputHeight, fg, DlssPack.Encoding.SDR);
    }

    private static void verify(long luid, Path directory, Path cache, int width, int height,
                               int outputWidth, int outputHeight, XessFgDlssValidation fg,
                               DlssPack.Encoding encoding) throws Exception {
        long session = DlssNative.create(luid, width, height, outputWidth, outputHeight, encoding.hdr,
                directory.toString(), cache.toString());
        int color = glCreateTextures(GL_TEXTURE_2D);
        int depth = glCreateTextures(GL_TEXTURE_2D);
        int handDepth = glCreateTextures(GL_TEXTURE_2D);
        var imports = new DlssInterop[DlssNative.SLOTS];
        try (var pass = new DlssPass(Files.readString(Path.of(
                "src/main/resources/assets/createmanaindustry/shaders/dlss/inputs.comp")))) {
            glTextureStorage2D(color, 1, GL_RGBA16F, width, height);
            glTextureStorage2D(depth, 1, GL_R32F, width, height);
            glTextureStorage2D(handDepth, 1, GL_R32F, width, height);
            glClearTexImage(depth, 0, GL_RED, GL_FLOAT, new float[]{0.5f});
            glClearTexImage(handDepth, 0, GL_RED, GL_FLOAT, new float[]{0.5f});
            glClearTexSubImage(handDepth, 0, width / 4, height / 2, 0, 1, 1, 1, GL_RED, GL_FLOAT, new float[]{0.2f});
            glClearTexSubImage(color, 0, 0, 0, 0, width, height / 2, 1, GL_RGBA, GL_FLOAT, new float[]{0.8f, 0.1f, 0.1f, 1f});
            glClearTexSubImage(color, 0, 0, height / 2, 0, width, height - height / 2, 1,
                    GL_RGBA, GL_FLOAT, new float[]{0.1f, 0.1f, 0.8f, 1f});
            for (int i = 0; i < imports.length; ++i)
                imports[i] = new DlssInterop(session, i, width, height, outputWidth, outputHeight);
            verifyWarp(pass, imports[0], width, height, depth);
            if (width == 642) verifyFusedInputs(pass, imports[0], width, height, depth);
            var shifted = new Matrix4f().translation(4f / width, 2f / height, 0);
            var stationary = new Matrix4f();
            double totalGpuMs = 0;
            for (int frame = 0; frame < 8; ++frame) {
                int index = frame % imports.length;
                var entry = imports[index];
                long token = frame + 2;
                boolean reset = frame == 0 || frame == 4;
                require(DlssNative.available(session, index), "GPU slot did not retire after readback");
                if (frame == 4) {
                    boolean rejected = false;
                    try {
                        DlssNative.evaluate(session, index, token - 1, 0, 0, 16.67f, true, false);
                    } catch (IllegalStateException expected) {
                        rejected = expected.getMessage().contains("out of order");
                    }
                    require(rejected, "An invalid submission must fail before advancing DLSS history");
                }
                pass.dispatch(entry, width, height, color, 0, 0, 0, 0, null, null, encoding);
                if (encoding.hdr) {
                    float[] decoded = read(entry.textures[DlssInterop.COLOR], width / 2, height / 4, GL_RGBA, 4);
                    require(Math.abs(decoded[2] - Math.pow(0.8, 1 / encoding.gamma)) < 0.002f,
                            "Iris HDR color was not decoded before inference");
                }
                pass.dispatch(entry, width, height, depth, 1, 0, 0, 0, reset ? stationary : shifted, stationary);
                pass.dispatch(entry, width, height, handDepth, 4, 0, 0, 0, null, null);
                var motion = read(entry.textures[DlssInterop.MOTION], width / 2, height / 2, GL_RG, 2);
                require(Math.abs(motion[0] - (reset ? 0 : 2)) <= 0.5 && Math.abs(motion[1] - (reset ? 0 : -1)) <= 0.5,
                        "Hand depth overwrote the background DLSS motion");
                int handY = height - 1 - height / 2;
                require(Math.abs(read(entry.textures[DlssInterop.DEPTH], width / 4, handY, GL_RED, 1)[0] - 0.2f) < 0.0001,
                        "Visible hand did not update the DLSS depth");
                if (frame == 2) {
                    DlssNative.evaluate(session, index, token, 0, 0, 16.67f, reset, true);
                    Thread.sleep(10);
                    require(!DlssNative.available(session, index), "DLSS evaluation retired before input publication");
                    entry.publish(token);
                } else {
                    if (frame == 3) glEnable(0);
                    entry.publish(token);
                    DlssNative.evaluate(session, index, token, 0, 0, 16.67f, reset, true);
                }
                if (frame == 5) glEnable(0);
                entry.acquire(token);
                long complete = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
                try {
                    int wait = glClientWaitSync(complete, GL_SYNC_FLUSH_COMMANDS_BIT, 5_000_000_000L);
                    require(wait == GL_ALREADY_SIGNALED || wait == GL_CONDITION_SATISFIED,
                            "GL timed out or failed acquiring the DLSS output");
                } finally {
                    glDeleteSync(complete);
                }
                var upper = read(entry.textures[DlssInterop.OUTPUT], outputWidth / 2, outputHeight / 4, GL_RGBA, 4);
                var lower = read(entry.textures[DlssInterop.OUTPUT], outputWidth / 2, outputHeight * 3 / 4, GL_RGBA, 4);
                require(upper[2] > 0.4 && upper[0] < 0.3 && lower[0] > 0.4 && lower[2] < 0.3,
                        "DLSS output is missing or reversed");
                double ms = DlssNative.pollGpuTime(session, index);
                require(ms >= 0, "Missing DLSS GPU timestamp");
                totalGpuMs += ms;
                if (fg != null) fg.present(entry.textures[DlssInterop.OUTPUT], reset);
            }
            require(glGetError() == GL_NO_ERROR, "Unexpected GL error");
            System.out.printf("PASS DLSS %dx%d -> %dx%d (%s), 8 frames across 3 slots, NGX GPU average %.3f ms%n",
                    width, height, outputWidth, outputHeight, encoding, totalGpuMs / 8);
        } finally {
            DlssNative.destroy(session);
            for (var entry : imports) if (entry != null) entry.close();
            glDeleteTextures(color);
            glDeleteTextures(depth);
            glDeleteTextures(handDepth);
        }
    }

    private static void verifyFusedInputs(DlssPass pass, DlssInterop slot, int width, int height, int depth) {
        int opaque = glCreateTextures(GL_TEXTURE_2D);
        int translucent = glCreateTextures(GL_TEXTURE_2D);
        int winners = glCreateBuffers();
        try {
            for (int texture : new int[]{opaque, translucent}) {
                glTextureStorage2D(texture, 1, GL_RGBA32F, width, height);
                glClearTexImage(texture, 0, GL_RGBA, GL_FLOAT, new float[4]);
            }
            glClearTexSubImage(opaque, 0, 0, 0, 0, width * 2 / 3, height, 1, GL_RGBA, GL_FLOAT,
                    new float[]{1.25f, -0.5f, 0.4f, 1});
            glClearTexSubImage(translucent, 0, 0, 0, 0, width / 4, height / 2, 1, GL_RGBA, GL_FLOAT,
                    new float[]{-0.25f, 1.75f, 0.3f, 0.75f});
            int[] marks = new int[width * height * 4];
            int rankedDepth = ((0xffffff - Math.round(0xffffff * 0.5f)) << 8) | 1;
            int motion = Short.toUnsignedInt(Float.floatToFloat16(-2f))
                    | Short.toUnsignedInt(Float.floatToFloat16(4f)) << 16;
            for (int y = 0; y < height; y++) {
                for (int x = width / 5; x < width / 2; x++) {
                    int index = 2 * (y * width + x);
                    marks[index] = motion;
                    marks[index + 1] = rankedDepth;
                }
                for (int x = width / 6; x < width / 3; x++) {
                    int index = 2 * ((height + y) * width + x);
                    marks[index] = motion >>> 16 | motion << 16;
                    marks[index + 1] = rankedDepth;
                }
            }
            glNamedBufferData(winners, marks, GL_STATIC_DRAW);
            var shift = new Matrix4f().translation(4f / width, 2f / height, 0);
            var now = new XessFgCoordinates(1, 1, .31f, -.2f, XessFgCoordinates.Warp.BSL_WATER, 3, .0005f);
            var prior = new XessFgCoordinates(1, 1, -.15f, .12f, XessFgCoordinates.Warp.BSL_WATER, 4, .0005f);
            int[] inputs = {depth, opaque, depth, translucent};
            int[] modes = {1, 2, 3, 2};
            for (int i = 0; i < inputs.length; i++) {
                pass.dispatch(slot, width, height, inputs[i], modes[i], i == 2 ? winners : 0,
                        now.jitterX(), now.jitterY(), i == 0 ? shift : null, i == 0 ? shift : null,
                        DlssPack.Encoding.SDR, now, prior);
            }
            float[] expectedDepth = new float[width * height];
            float[] expectedMotion = new float[2 * width * height];
            glGetTextureImage(slot.textures[DlssInterop.DEPTH], 0, GL_RED, GL_FLOAT, expectedDepth);
            glGetTextureImage(slot.textures[DlssInterop.MOTION], 0, GL_RG, GL_FLOAT, expectedMotion);
            pass.world(slot, width, height, depth, now.jitterX(), now.jitterY(), shift, shift, now, prior,
                    opaque, winners, translucent);
            float[] actualDepth = new float[expectedDepth.length];
            float[] actualMotion = new float[expectedMotion.length];
            glGetTextureImage(slot.textures[DlssInterop.DEPTH], 0, GL_RED, GL_FLOAT, actualDepth);
            glGetTextureImage(slot.textures[DlssInterop.MOTION], 0, GL_RG, GL_FLOAT, actualMotion);
            require(java.util.Arrays.equals(expectedDepth, actualDepth), "Fused DLSS inputs changed depth");
            require(java.util.Arrays.equals(expectedMotion, actualMotion), "Fused DLSS inputs changed motion or layer order");
            System.out.println("PASS fused DLSS depth and motion match separate passes");
        } finally {
            glDeleteBuffers(winners);
            glDeleteTextures(opaque);
            glDeleteTextures(translucent);
        }
    }

    private static void verifyWarp(DlssPass pass, DlssInterop slot, int width, int height, int depth) {
        var now = new XessFgCoordinates(1, 1, .31f, -.2f, XessFgCoordinates.Warp.BSL_WATER, 3, .0005f);
        var prior = new XessFgCoordinates(1, 1, -.15f, .12f, XessFgCoordinates.Warp.BSL_WATER, 4, .0005f);
        var shift = new Matrix4f().translation(4f / width, 2f / height, 0);
        pass.dispatch(slot, width, height, depth, 1, 0, now.jitterX(), now.jitterY(), shift, shift,
                DlssPack.Encoding.SDR, now, prior);
        for (int y : new int[]{height / 4, height / 2, height * 3 / 4}) {
            for (int x : new int[]{width / 4, width / 2, width * 3 / 4}) {
                double u = (x + 0.5) / width;
                double v = (height - y - 0.5) / height;
                double previousU = u + .0005 * Math.cos(v * 32 + 9) + (2 - now.jitterX()) / width;
                double previousV = v + .0005 * Math.sin(u * 32 + 5.1) + (1 - now.jitterY()) / height;
                // A first-order inverse is sufficient for this small distortion.
                double expectedX = (previousU - .0005 * Math.cos(previousV * 32 + 12) - u) * width + now.jitterX();
                double expectedY = (v - previousV + .0005 * Math.sin(previousU * 32 + 6.8)) * height - now.jitterY();
                float[] actual = read(slot.textures[DlssInterop.MOTION], x, y, GL_RG, 2);
                require(Math.abs(actual[0] - expectedX) < .05 && Math.abs(actual[1] - expectedY) < .05,
                        "BSL water reprojection differs by more than 0.05 input pixels");
            }
        }
    }

    private static void verifyIrisTargets() {
        int color = glCreateTextures(GL_TEXTURE_2D);
        int model = glCreateTextures(GL_TEXTURE_2D);
        int depth = glCreateTextures(GL_TEXTURE_2D);
        int framebuffer = glCreateFramebuffers();
        try (var targets = new DlssIrisTargets()) {
            int width = 13, height = 9, outputWidth = 17, outputHeight = 11;
            glTextureStorage2D(color, 1, GL_RGBA16F, width, height);
            glTextureStorage2D(model, 1, GL_RGBA16F, outputWidth, outputHeight);
            glTextureStorage2D(depth, 1, GL_DEPTH24_STENCIL8, width, height);
            glClearTexImage(color, 0, GL_RGBA, GL_FLOAT, new float[]{0.25f, 0.5f, 0.75f, 0.75f});
            glClearTexSubImage(color, 0, 11, 0, 0, 2, height, 1, GL_RGBA, GL_FLOAT, new float[]{1, 0, 0, 0});
            glClearTexImage(model, 0, GL_RGBA, GL_FLOAT, new float[]{0.1f, 0.2f, 0.3f, 1});
            glClearTexSubImage(model, 0, 0, 0, 0, outputWidth, 4, 1, GL_RGBA, GL_FLOAT, new float[]{0.6f, 0.2f, 0.3f, 1});
            glClearTexImage(depth, 0, GL_DEPTH_STENCIL, GL_UNSIGNED_INT_24_8, new int[]{0x80000007});
            targets.nextFrame();
            targets.register(color, outputWidth, outputHeight, false, true, new int[]{0, 0});
            targets.register(depth, outputWidth, outputHeight, true, false);
            targets.resolve(color, model, 11, height, DlssPack.Encoding.SUNDIAL);
            int expanded = targets.texture(color);
            float[] upper = read(expanded, outputWidth - 1, outputHeight - 1, GL_RGBA, 4);
            float[] lower = read(expanded, outputWidth - 1, 0, GL_RGBA, 4);
            require(Math.abs(upper[0] - 6) < 0.02f && Math.abs(lower[0] - 1) < 0.02f
                            && upper[3] == 0.75f && lower[3] == 0.75f,
                    "Iris did not preserve crop, orientation, alpha and Sundial encoding");
            int expandedDepth = targets.texture(depth);
            require(Math.abs(read(expandedDepth, 7, 5, GL_DEPTH_COMPONENT, 1)[0] - 0.5f) < 0.0001f,
                    "Iris depth promotion changed depth");
            glNamedFramebufferTexture(framebuffer, GL_COLOR_ATTACHMENT0, color, 0);
            glNamedFramebufferTexture(framebuffer, GL_DEPTH_STENCIL_ATTACHMENT, depth, 0);
            targets.framebuffer(framebuffer, true, it.unimi.dsi.fastutil.ints.Int2IntMaps.singleton(0, color), true);
            require(glGetNamedFramebufferAttachmentParameteri(framebuffer, GL_COLOR_ATTACHMENT0, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME)
                            == expanded && glGetNamedFramebufferAttachmentParameteri(framebuffer, GL_DEPTH_ATTACHMENT,
                            GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME) == expandedDepth,
                    "The post pass must bind a matched color and depth grid");
            glClearTexSubImage(expanded, 0, 0, 0, 0, 1, 1, 1, GL_RGBA, GL_FLOAT, new float[]{2, 0, 0, 0.375f});
            targets.finish();
            require(glGetNamedFramebufferAttachmentParameteri(framebuffer, GL_COLOR_ATTACHMENT0, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME)
                            == color && glGetNamedFramebufferAttachmentParameteri(framebuffer, GL_DEPTH_ATTACHMENT,
                            GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME) == depth,
                    "The frame boundary must restore Iris's original color and depth attachments");
            require(Math.abs(read(color, 3, 0, GL_RGBA, 4)[0] - 1) < 0.02f,
                    "Iris did not copy a persistent attachment to its low-resolution history");
            require(read(color, 0, 0, GL_RGBA, 4)[3] == 0.375f,
                    "Iris did not preserve temporal metadata associated with a fixed texel");
            targets.nextFrame();
            targets.resolve(color, model, 11, height, DlssPack.Encoding.SILDUR);
            require(Math.abs(read(targets.texture(color), 7, 0, GL_RGBA, 4)[0] - Math.pow(0.1, 0.454)) < 0.002,
                    "Iris did not restore Sildur's gamma encoding");
            targets.finish();
            targets.nextFrame();
            targets.resolve(color, 0, 11, height, DlssPack.Encoding.SUNDIAL);
            require(read(targets.texture(color), outputWidth - 1, height / 2, GL_RGBA, 4)[3] > 0.5f,
                    "A failed evaluation must spatially enlarge the cropped Iris scene");
            targets.finish();
            require(glGetError() == GL_NO_ERROR, "Unexpected GL error in Iris promotion");
            System.out.println("PASS Iris crop, output orientation, Sundial/Sildur encoding, depth, framebuffer restore and history");
        } finally {
            glDeleteTextures(color);
            glDeleteTextures(model);
            glDeleteTextures(depth);
            glDeleteFramebuffers(framebuffer);
        }
    }

    private static float[] read(int texture, int x, int y, int format, int channels) {
        float[] result = new float[channels];
        glGetTextureSubImage(texture, 0, x, y, 0, 1, 1, 1, format, GL_FLOAT, result);
        return result;
    }

    private static void verifyIrisHistoryOverwrite() {
        int width = 1707, height = 1067, outputWidth = 2560, outputHeight = 1600;
        int main = glCreateTextures(GL_TEXTURE_2D), alt = glCreateTextures(GL_TEXTURE_2D);
        int query = glGenQueries();
        var overwritten = java.util.Set.of(main);
        double[][] samples = new double[2][40];
        try (var targets = new DlssIrisTargets()) {
            glTextureStorage2D(main, 1, GL_RGBA16F, width, height);
            glTextureStorage2D(alt, 1, GL_RGBA16F, width, height);
            targets.register(main, outputWidth, outputHeight, false, true);
            targets.register(alt, outputWidth, outputHeight, false, true);
            for (int frame = -10; frame < samples[0].length; frame++) {
                for (int route = 0; route < samples.length; route++) {
                    boolean skipMain = route == ((frame & 1) == 0 ? 1 : 0);
                    targets.nextFrame();
                    int expandedMain = targets.texture(main), expandedAlt = targets.texture(alt);
                    glClearTexImage(expandedMain, 0, GL_RGBA, GL_FLOAT, new float[]{1, 0, 0, 1});
                    glClearTexImage(expandedAlt, 0, GL_RGBA, GL_FLOAT, new float[]{0, 1, 0, 1});
                    targets.markImageWrite(expandedMain);
                    targets.markImageWrite(expandedAlt);
                    glBeginQuery(GL_TIME_ELAPSED, query);
                    if (skipMain) targets.finish(overwritten);
                    else targets.finish();
                    glEndQuery(GL_TIME_ELAPSED);
                    if (frame >= 0) samples[skipMain ? 1 : 0][frame] = glGetQueryObjectui64(query, GL_QUERY_RESULT) / 1e6;
                    else glGetQueryObjectui64(query, GL_QUERY_RESULT);
                    require(read(main, width / 2, height / 2, GL_RGBA, 4)[0] == (skipMain ? 0 : 1),
                            "History writeback did not respect the pending Iris swap");
                    glCopyImageSubData(alt, GL_TEXTURE_2D, 0, 0, 0, 0,
                            main, GL_TEXTURE_2D, 0, 0, 0, 0, width, height, 1);
                    require(read(main, width / 2, height / 2, GL_RGBA, 4)[1] == 1,
                            "The final copy must see the restored alt history on both paths");
                }
            }
            require(glGetError() == GL_NO_ERROR, "Unexpected GL error in Iris history overwrite");
            for (var durations : samples) java.util.Arrays.sort(durations);
            System.out.printf("PASS Iris history before stock alt-to-main swap. GPU P50 %.3f -> %.3f ms, P95 %.3f -> %.3f ms%n",
                    samples[0][19], samples[1][19], samples[0][37], samples[1][37]);
        } finally {
            glDeleteQueries(query);
            glDeleteTextures(main);
            glDeleteTextures(alt);
        }
    }

    private static void verifyIrisMutableDepth(int format, int type, int attachment) {
        int depth = glCreateTextures(GL_TEXTURE_2D);
        int color = glCreateTextures(GL_TEXTURE_2D);
        int framebuffer = glCreateFramebuffers();
        try (var targets = new DlssIrisTargets()) {
            glBindTexture(GL_TEXTURE_2D, depth);
            glTexImage2D(GL_TEXTURE_2D, 0, format, 13, 9, 0, format, type, (java.nio.ByteBuffer) null);
            glBindTexture(GL_TEXTURE_2D, 0);
            glBindTexture(GL_TEXTURE_2D, color);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 13, 9, 0, GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
            glBindTexture(GL_TEXTURE_2D, 0);
            glNamedFramebufferTexture(framebuffer, GL_COLOR_ATTACHMENT0, color, 0);
            glNamedFramebufferTexture(framebuffer, attachment, depth, 0);
            require(glCheckNamedFramebufferStatus(framebuffer, GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE,
                    "Original Iris framebuffer is incomplete");
            glClearNamedFramebufferfv(framebuffer, GL_DEPTH, 0, new float[]{0.25f});
            int reportedFormat = glGetTextureLevelParameteri(depth, 0, GL_TEXTURE_INTERNAL_FORMAT);
            require(glGetError() == GL_NO_ERROR, "Unexpected GL error before promotion");
            targets.nextFrame();
            targets.register(color, 17, 11, false, false);
            targets.register(depth, 17, 11, true, false);
            int expanded = targets.texture(depth);
            int error = glGetError();
            require(error == GL_NO_ERROR, "Iris mutable depth 0x" + Integer.toHexString(reportedFormat)
                    + " failed promotion (GL 0x" + Integer.toHexString(error) + ")");
            targets.framebuffer(framebuffer, true, it.unimi.dsi.fastutil.ints.Int2IntMaps.singleton(0, color), true);
            require(glCheckNamedFramebufferStatus(framebuffer, GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE,
                    "Iris framebuffer is incomplete after promoting mutable depth");
            require(Math.abs(read(expanded, 8, 5, GL_DEPTH_COMPONENT, 1)[0] - 0.25f) < 0.0001f,
                    "Mutable depth promotion changed the stored value");
            targets.finish();
            require(glGetError() == GL_NO_ERROR, "Unexpected GL error restoring mutable Iris depth");
            System.out.println("PASS Iris mutable depth 0x" + Integer.toHexString(reportedFormat));
        } finally {
            glDeleteFramebuffers(framebuffer);
            glDeleteTextures(color);
            glDeleteTextures(depth);
        }
    }

    private static void verifyOptixInterop() throws Exception {
        var modules = ParticleNativeModules.prepareOptix();
        glEnable(0);
        try (var worker = new OptixQueryWorker(64, 16, 2, 1, modules, "GL error isolation probe")) {
            require(!worker.failed(), "OptiX initialization failed on a pre-existing GL error");
        } finally {
            OptixQueryWorker.retire();
        }
        require(glGetError() == GL_NO_ERROR, "Unconsumed error after OptiX setup");
        GpuGlErrors.begin("Intentional probe error");
        glEnable(0);
        boolean detected = false;
        try {
            GpuGlErrors.check("Intentional probe error");
        } catch (IllegalStateException expected) {
            detected = expected.getMessage().contains("0x500");
        }
        require(detected, "A new OpenGL failure must still be detected");
        System.out.println("PASS OptiX initialization with a pre-existing OpenGL error and detection of a fresh error");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
