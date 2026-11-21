package com.iridium126.createmanaindustry.client.xessfg;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFWNativeWin32;
import net.minecraft.world.phys.Vec3;
import static org.lwjgl.glfw.GLFW.glfwPollEvents;
import static org.lwjgl.opengl.GL45.*;

/** Presents NGX output through the production RGB24 HUD transport and Arc proxy. */
public final class XessFgDlssValidation implements AutoCloseable {
    private final XessFgTransport[] slots = new XessFgTransport[XessFgNative.SLOT_COUNT];
    private final ByteBuffer constants = direct(XessFgNative.CONSTANT_BYTES);
    private final ByteBuffer reprojection = direct(XessFgNative.REPROJECTION_BYTES);
    private final ByteBuffer presents = direct(XessFgNative.PRESENT_RESULT_CAPACITY * (3 + XessFgFrameStats.NATIVE_FIELDS) * Long.BYTES);
    private final ByteBuffer gpu = direct(XessFgNative.SLOT_COUNT * XessFgNative.GPU_PROFILE_FIELDS * Long.BYTES);
    private final XessFgView view = new XessFgView(new Matrix4f(), new Matrix4f(), Vec3.ZERO);
    private final int width, height, src = glCreateFramebuffers(), dst = glCreateFramebuffers();
    private final int output = glCreateTextures(GL_TEXTURE_2D), depth = glCreateTextures(GL_TEXTURE_2D);
    private long session;
    private XessFgCapture capture;
    private int submitted, retired, generated, sparse;

    public XessFgDlssValidation(long window, long render, int width, int height) throws Exception {
        this.width = width;
        this.height = height;
        try {
            Path root = Path.of("build/dlss-hardware-probe");
            long arc = XessFgAdapters.discover(root).stream().filter(XessFgAdapters.Adapter::arcA750)
                    .findFirst().orElseThrow(() -> new IllegalStateException("Arc A750 is unavailable")).luid();
            session = XessFgNative.create(GLFWNativeWin32.glfwGetWin32Window(window), render, arc, width, height, 1,
                    true, (width + 1) / 2, (height + 1) / 2);
            for (int i = 0; i < slots.length; i++) slots[i] = new XessFgTransport(session, i);
            require(slots[0].color.sharedInputs, "Arc shared RGB24 transport is unavailable");
            capture = new XessFgCapture(Files.readString(Path.of(
                    "src/main/resources/assets/createmanaindustry/shaders/xessfg/export.comp")));
            glTextureStorage2D(output, 1, GL_RGBA8, width, height);
            glTextureStorage2D(depth, 1, GL_R32F, width, height);
            glClearTexImage(depth, 0, GL_RED, GL_FLOAT, new float[]{0.5f});
            glNamedFramebufferTexture(dst, GL_COLOR_ATTACHMENT0, output, 0);
            XessFgCapture.matrix(constants, 0, new Matrix4f());
            XessFgCapture.matrix(constants, 64, new Matrix4f());
            constants.putFloat(136, 1).putFloat(140, 1).putFloat(148, 16.67f);
        } catch (Exception | LinkageError failure) {
            close();
            throw failure;
        }
    }

    public void present(int dlssColor, boolean reset) throws InterruptedException {
        int frame = ++submitted;
        long deadline = System.nanoTime() + 5_000_000_000L;
        int index;
        while ((index = XessFgNative.acquire(session, frame, frame, false)) < 0) {
            require(System.nanoTime() < deadline, "Waiting for an Arc transfer slot");
            glfwPollEvents();
            poll();
            Thread.sleep(1);
        }
        XessFgNative.sleep(session, frame);
        for (int marker = 0; marker < 3; marker++) XessFgNative.marker(session, frame, marker);
        var slot = slots[index];
        slot.geometry.acquire();
        capture.depthAndMotion(slot, depth, width, height, view, view, reprojection);
        slot.geometry.publish(frame);
        XessFgNative.submitGeometry(session, index, frame, reprojection, false);

        glNamedFramebufferTexture(src, GL_COLOR_ATTACHMENT0, dlssColor, 0);
        glBlitNamedFramebuffer(src, dst, 0, height, width, 0, 0, 0, width, height,
                GL_COLOR_BUFFER_BIT, GL_NEAREST);
        capture.color(slot, output, width, height, XessFgCapture.HUDLESS, true);
        slot.hudBase.publish(frame);
        XessFgNative.submitHudless(session, index, frame);
        glClearTexSubImage(output, 0, 0, 0, 0, 32, 8, 1, GL_RGBA, GL_FLOAT, new float[]{1, 1, 1, 1});
        slot.color.acquire();
        capture.color(slot, output, width, height, XessFgCapture.FINAL, true);
        if (frame == 4) glEnable(0);
        slot.color.publish(frame);
        XessFgNative.submitColor(session, index, frame, true);
        constants.putInt(144, reset ? 1 : 0);
        while (!XessFgNative.enqueuePresent(session, index, frame, frame, true, true, constants, frame)) {
            require(System.nanoTime() < deadline, "Waiting for Arc proxy presentation");
            glfwPollEvents();
            poll();
            Thread.sleep(1);
        }
        poll();
    }

    public void verify() throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (retired < submitted && System.nanoTime() < deadline) {
            glfwPollEvents();
            poll();
            Thread.sleep(1);
        }
        poll();
        require(retired == submitted && generated > 0 && sparse > 0, "Incomplete Arc proxy or sparse HUD coverage");
        System.out.printf("PASS RTX DLSS → Arc XeSS-FG, %d real submissions in order, %d with 2 presents, %d sparse HUD transfers%n",
                retired, generated, sparse);
    }

    private void poll() {
        int count = XessFgNative.pollPresents(session, presents);
        for (int i = 0; i < count; i++) {
            int offset = i * (3 + XessFgFrameStats.NATIVE_FIELDS) * Long.BYTES;
            require(presents.getLong(offset) == ++retired, "Unexpected Arc presentation order");
            if (presents.getLong(offset + Long.BYTES) == 2) ++generated;
        }
        int measured = XessFgNative.pollGpuProfile(session, gpu);
        for (int i = 0; i < measured; ++i) {
            long route = gpu.getLong(((i + 1) * XessFgNative.GPU_PROFILE_FIELDS - 1) * Long.BYTES);
            if (route == 0) ++sparse;
        }
    }

    private static ByteBuffer direct(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new IllegalStateException(description);
    }

    @Override
    public void close() {
        if (session != 0) XessFgNative.destroy(session);
        for (var slot : slots) if (slot != null) slot.close();
        if (capture != null) capture.close();
        glDeleteFramebuffers(new int[]{src, dst});
        glDeleteTextures(new int[]{output, depth});
    }
}
