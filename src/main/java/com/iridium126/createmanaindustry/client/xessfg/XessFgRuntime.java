package com.iridium126.createmanaindustry.client.xessfg;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.particles.engine.spatial.StormGpuDevices;
import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.system.Platform;
import static com.iridium126.createmanaindustry.client.xessfg.XessFgFrameStats.Stage.*;

/** Render-thread owner of the frame inputs and the window's active presenter. */
public final class XessFgRuntime {
    private static final XessFgRuntime INSTANCE = new XessFgRuntime();
    private static final int SIMULATION_START = 0, SIMULATION_END = 1, RENDER_START = 2;
    private static final float MAX_CONTIGUOUS_FRAME_MS = 120;
    private final XessFgTransport[] slots = new XessFgTransport[XessFgNative.SLOT_COUNT];
    private ByteBuffer constants;
    private long session, sessionId, window, sequence, frameStart, previousFrameStart;
    private int frame, width, height, slot = -1, cameraMode, previousCameraMode;
    private long irisGeneration, previousIrisGeneration;
    private boolean failed, eligible, depthReady, hudReady, reset, iris, interpolate;
    private boolean reportedSleepRejection;
    private XessFgCapture capture;
    private XessFgView current, previous;
    private ClientLevel level, previousLevel;
    private RenderTarget deferredBlit;
    private long renderLuid, generationLuid;

    private XessFgRuntime() {}

    public static boolean requested() {
        return ClientConfig.xessFg().enabled() && Platform.get() == Platform.WINDOWS && !INSTANCE.failed;
    }

    public static void begin(Minecraft minecraft, boolean render) {
        if (!ClientConfig.xessFg().enabled()) return;
        INSTANCE.beginFrame(minecraft, render);
    }

    public static void pollProfile(XessFgFrameStats profile, boolean finishing) {
        profile.poll(INSTANCE.session, INSTANCE.sessionId, finishing);
    }

    private void beginFrame(Minecraft minecraft, boolean render) {
        current = null;
        depthReady = hudReady = iris = false;
        deferredBlit = null;
        irisGeneration = 0;
        if (!requested()) return;
        var target = minecraft.getWindow();
        long handle = target.getWindow();
        int w = target.getWidth(), h = target.getHeight();
        if (w <= 0 || h <= 0 || GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_ICONIFIED) != GLFW.GLFW_FALSE
                || target.isFullscreen()) {
            close();
            return;
        }
        if (window != handle || width != w || height != h) close();
        level = minecraft.level;
        cameraMode = minecraft.options.getCameraType().ordinal();
        eligible = render && level != null && minecraft.screen == null && minecraft.getOverlay() == null
                && !minecraft.isPaused() && minecraft.isWindowActive();
        if (session == 0 && !eligible) return;
        var profile = XessFgFrameStats.current();
        try {
            if (session == 0) {
                long start = XessFgFrameStats.Frame.start(profile);
                open(minecraft, handle, w, h);
                if (profile != null) profile.complete(SESSION_OPEN, start);
            }
            if (slot >= 0) {
                XessFgNative.discard(session, slot, sequence);
                slot = -1;
                previous = null;
            }
            if (!acquireSlot(target, profile)) {
                close();
                return;
            }
            ++frame;
            long start = XessFgFrameStats.Frame.start(profile);
            int sleepStatus = XessFgNative.sleep(session, frame);
            if (profile != null) {
                profile.complete(XELL_SLEEP, start);
                profile.presentId = Integer.toUnsignedLong(frame);
                profile.sleepStatus = sleepStatus;
                profile.renderLuid = renderLuid;
                profile.generationLuid = generationLuid;
                if (!eligible) profile.disabledChecks |= 1;
            }
            if (sleepStatus < 0 && !reportedSleepRejection) {
                reportedSleepRejection = true;
                CreateManaIndustry.LOGGER.warn("[CMI/XeSS-FG] XeLL skipped pacing for frame {} (Intel SDK status {}). Continuing presentation.",
                        Integer.toUnsignedLong(frame), sleepStatus);
            }
            frameStart = System.nanoTime();
            XessFgNative.marker(session, frame, SIMULATION_START);
        } catch (IOException | RuntimeException | LinkageError failure) {
            fail(failure);
        }
    }

    private boolean acquireSlot(Window target, XessFgFrameStats.Frame profile) {
        long start = XessFgFrameStats.Frame.start(profile);
        long next = sequence + 1;
        long captureFrame = profile == null ? 0 : profile.id();
        if (profile != null) profile.slotsInUse = XessFgNative.slotsInUse(session);
        slot = XessFgNative.acquire(session, next, captureFrame);
        if (slot < 0) {
            long waitStart = XessFgFrameStats.Frame.start(profile);
            do {
                XessFgNative.waitForSlot(session);
                GLFW.glfwPollEvents();
                if (!surfaceMatches(target)) {
                    if (profile != null) profile.complete(SLOT_WAIT, waitStart);
                    return false;
                }
                slot = XessFgNative.acquire(session, next, captureFrame);
            } while (slot < 0);
            if (profile != null) profile.complete(SLOT_WAIT, waitStart);
        }
        sequence = next;
        slots[slot].color.acquire();
        if (profile != null) {
            profile.bindSession(session, sessionId);
            profile.sequence = sequence;
            profile.earlySlot = slot;
            profile.complete(EARLY_SLOT, start);
        }
        return true;
    }

    private boolean surfaceMatches(Window target) {
        return target.getWindow() == window && !target.isFullscreen()
                && target.getWidth() == width && target.getHeight() == height
                && !GLFW.glfwWindowShouldClose(window)
                && GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_FALSE;
    }

    private void open(Minecraft minecraft, long handle, int w, int h) throws IOException {
        if (!XessFgTransport.supported()) throw new IOException("Win32 OpenGL memory and semaphore sharing are unavailable");
        var renderAdapter = StormGpuDevices.renderAdapter();
        if (renderAdapter.luid() == 0 || renderAdapter.nodes() != 1) {
            throw new IOException("Minecraft's OpenGL hardware adapter could not be identified");
        }
        var adapter = XessFgAdapters.select(XessFgAdapters.discover(minecraft.gameDirectory.toPath()), ClientConfig.xessFg().gpu());
        renderLuid = renderAdapter.luid();
        generationLuid = adapter.luid();
        capture = new XessFgCapture(w, h);
        session = XessFgNative.create(GLFWNativeWin32.glfwGetWin32Window(handle), renderAdapter.luid(), adapter.luid(), w, h);
        ++sessionId;
        for (int i = 0; i < slots.length; i++) slots[i] = new XessFgTransport(session, i);
        window = handle;
        width = w;
        height = h;
        constants = ByteBuffer.allocateDirect(XessFgNative.CONSTANT_BYTES).order(ByteOrder.nativeOrder());
        reportedSleepRejection = false;
        CreateManaIndustry.LOGGER.info("[CMI/XeSS-FG] render={} (LUID {}), generation={} (PCI {}, LUID {}), transport={}, XMX-capable Arc={}",
                StormGpuDevices.renderer(), Long.toUnsignedString(renderAdapter.luid(), 16), adapter.selection().label(),
                String.format(Locale.ROOT, "%04x:%04x", adapter.vendor(), adapter.device()),
                Long.toUnsignedString(adapter.luid(), 16), renderAdapter.luid() == adapter.luid() ? "same-adapter" : "cross-adapter",
                adapter.xmxArc());
    }

    public static void renderStart() {
        var owner = INSTANCE;
        if (owner.session == 0) return;
        try {
            XessFgNative.marker(owner.session, owner.frame, SIMULATION_END);
            XessFgNative.marker(owner.session, owner.frame, RENDER_START);
        } catch (RuntimeException | LinkageError failure) {
            owner.fail(failure);
        }
    }

    public static void worldView(Camera camera, Matrix4fc view, Matrix4fc projection) {
        var owner = INSTANCE;
        if (owner.canCapture() && !owner.iris) {
            owner.current = new XessFgView(view, projection, camera.getPosition());
        }
    }

    public static void irisBegin(long generation) {
        var owner = INSTANCE;
        if (!owner.canCapture()) return;
        owner.iris = true;
        owner.irisGeneration = generation;
        owner.current = null;
        owner.depthReady = false;
    }

    public static void irisDepth(int texture, int width, int height, Camera camera, Matrix4fc view, Matrix4fc projection,
                                 XessFgCoordinates coordinates) {
        var owner = INSTANCE;
        if (!owner.canCapture() || !owner.iris) return;
        owner.current = new XessFgView(new Matrix4f(view), new Matrix4f(projection), camera.getPosition(), coordinates);
        owner.captureDepth(texture, width, height);
    }

    public static void vanillaDepth(RenderTarget target) {
        var owner = INSTANCE;
        if (owner.canCapture() && !owner.iris) {
            owner.captureDepth(target.getDepthTextureId(), target.width, target.height);
        }
    }

    private void captureDepth(int texture, int w, int h) {
        if (texture <= 0 || w <= 0 || h <= 0 || current == null || depthReady) return;
        var profile = XessFgFrameStats.current();
        long start = XessFgFrameStats.Frame.start(profile);
        boolean exported = false;
        boolean gpu = profile != null && profile.startGpu(DEPTH_MOTION);
        try {
            float elapsed = (frameStart - previousFrameStart) / 1_000_000f;
            reset = previous == null || previousLevel != level || previousCameraMode != cameraMode
                    || previousIrisGeneration != irisGeneration || elapsed > MAX_CONTIGUOUS_FRAME_MS
                    || current.coordinates().warp() != previous.coordinates().warp()
                    || current.camera().distanceToSqr(previous.camera()) > 8 * 8
                    || current.modelView().m02() * previous.modelView().m02()
                        + current.modelView().m12() * previous.modelView().m12()
                        + current.modelView().m22() * previous.modelView().m22() < 0.5f;
            slots[slot].geometry.acquire();
            capture.depthAndMotion(slots[slot], texture, w, h, current, reset ? current : previous);
            exported = true;
        } catch (RuntimeException | LinkageError failure) {
            fail(failure);
        } finally {
            if (profile != null) {
                profile.endGpu(gpu, exported);
                profile.complete(DEPTH_MOTION, start);
            }
        }
        if (!exported) return;
        start = XessFgFrameStats.Frame.start(profile);
        try {
            slots[slot].geometry.publish(sequence);
            XessFgNative.submitGeometry(session, slot, sequence);
            depthReady = true;
        } catch (RuntimeException | LinkageError failure) {
            fail(failure);
        } finally {
            if (profile != null) profile.complete(GEOMETRY_PUBLISH, start);
        }
    }

    private boolean canCapture() {
        return session != 0 && slot >= 0 && eligible;
    }

    public static void hudless(RenderTarget target) {
        var owner = INSTANCE;
        if (!owner.canCapture() || !owner.depthReady) return;
        try {
            owner.captureColor(target, XessFgCapture.HUDLESS, HUDLESS);
            owner.hudReady = true;
        } catch (RuntimeException | LinkageError failure) {
            owner.fail(failure);
        }
    }

    private void captureColor(RenderTarget target, int plane, XessFgFrameStats.Stage stage) {
        var profile = XessFgFrameStats.current();
        long start = XessFgFrameStats.Frame.start(profile);
        boolean gpu = profile != null && profile.startGpu(stage);
        boolean success = false;
        try {
            capture.color(slots[slot], target.getColorTextureId(), target.viewWidth, target.viewHeight, plane);
            success = true;
        } finally {
            if (profile != null) {
                profile.endGpu(gpu, success);
                profile.complete(stage, start);
            }
        }
    }

    public static boolean prepareDisplay(RenderTarget target, int width, int height) {
        return INSTANCE.exportFrame(target, width, height);
    }

    private boolean exportFrame(RenderTarget target, int w, int h) {
        if (session == 0) return false;
        if (width != w || height != h) {
            close();
            return false;
        }
        var client = Minecraft.getInstance();
        boolean gameplay = client.level == level && client.screen == null && client.getOverlay() == null && !client.isPaused();
        interpolate = canCapture() && depthReady && hudReady && gameplay;
        var profile = XessFgFrameStats.current();
        if (profile != null) {
            if (!depthReady) profile.disabledChecks |= 8;
            if (!hudReady) profile.disabledChecks |= 16;
            if (!gameplay) profile.disabledChecks |= 32;
        }
        try {
            captureColor(target, XessFgCapture.FINAL, FINAL_COLOR);
            if (interpolate) writeConstants();
            long start = XessFgFrameStats.Frame.start(profile);
            slots[slot].color.publish(sequence);
            if (profile != null) profile.complete(GL_PUBLISH, start);
            start = XessFgFrameStats.Frame.start(profile);
            XessFgNative.submitColor(session, slot, sequence, interpolate);
            if (profile != null) profile.complete(COLOR_SUBMIT, start);
            deferredBlit = target;
            return true;
        } catch (RuntimeException | LinkageError failure) {
            fail(failure);
            return false;
        }
    }

    public static boolean present(long handle) {
        var owner = INSTANCE;
        if (owner.session == 0 || owner.window != handle) return false;
        if (owner.deferredBlit == null) {
            owner.close();
            return false;
        }
        RenderTarget fallback = owner.deferredBlit;
        owner.deferredBlit = null;
        var minecraft = Minecraft.getInstance();
        if (owner.presentFrame(minecraft)) return true;
        var window = minecraft.getWindow();
        fallback.blitToScreen(window.getWidth(), window.getHeight());
        return false;
    }

    private boolean presentFrame(Minecraft minecraft) {
        var target = minecraft.getWindow();
        if (!surfaceMatches(target)) {
            close();
            return false;
        }
        try {
            var profile = XessFgFrameStats.current();
            boolean vsync = minecraft.options.enableVsync().get();
            int queued;
            if (profile == null) {
                queued = XessFgNative.present(session, slot, frame, sequence, interpolate, vsync, constants);
            } else {
                profile.resetHistory = interpolate && reset;
                queued = profile.present(session, slot, frame, sequence, interpolate, vsync, constants);
                profile.route = queued == 0 ? "dxgi_no_queued_frames" : "dxgi";
            }
            slot = -1;
            if (queued == 0) interpolate = false;
            previous = interpolate ? current : null;
            previousLevel = interpolate ? level : null;
            previousCameraMode = cameraMode;
            previousFrameStart = frameStart;
            previousIrisGeneration = irisGeneration;
            return true;
        } catch (RuntimeException | LinkageError failure) {
            fail(failure);
            return false;
        }
    }

    private void writeConstants() {
        XessFgCapture.matrix(constants, 0, current.sdkView());
        XessFgCapture.matrix(constants, 64, XessFgCapture.dxProjection(current.projection()));
        // The exported buffer has already been resampled into the unjittered output grid.
        constants.putFloat(128, 0);
        constants.putFloat(132, 0);
        constants.putFloat(136, 1);
        constants.putFloat(140, 1);
        constants.putInt(144, reset ? 1 : 0);
        constants.putFloat(148, reset ? 0 : (frameStart - previousFrameStart) / 1_000_000f);
    }

    private void fail(Throwable error) {
        var profile = XessFgFrameStats.current();
        if (profile != null) profile.route = "opengl_native_failure";
        failed = true;
        CreateManaIndustry.LOGGER.warn("[CMI/XeSS-FG] Returning to OpenGL presentation", error);
        close();
    }

    public static void shutdown() {
        INSTANCE.close();
        INSTANCE.level = null;
    }

    private void close() {
        if (session != 0) {
            XessFgNative.destroy(session);
            session = 0;
        }
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != null) slots[i].close();
            slots[i] = null;
        }
        if (capture != null) capture.close();
        capture = null;
        constants = null;
        previous = current = null;
        previousLevel = null;
        deferredBlit = null;
        window = sequence = 0;
        slot = -1;
    }
}
