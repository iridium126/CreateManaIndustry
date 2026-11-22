package com.iridium126.createmanaindustry.client.xessfg;

import com.iridium126.createmanaindustry.client.dlss.DlssRuntime;
import java.util.function.IntConsumer;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.allaystorm.rift.RiftRenderFrame;
import com.iridium126.createmanaindustry.client.particles.engine.spatial.StormGpuDevices;
import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;
import com.iridium126.createmanaindustry.infrastructure.config.XessFgSurfaceMode;
import com.iridium126.createmanaindustry.infrastructure.config.XessFgTransportMode;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.system.Platform;
import static com.iridium126.createmanaindustry.client.xessfg.XessFgFrameStats.Stage.*;
import static org.lwjgl.opengl.GL11.glFlush;

/** Render-thread owner of the frame inputs and the window's active presenter. */
public final class XessFgRuntime {
    private static final XessFgRuntime INSTANCE = new XessFgRuntime();
    private static final int SIMULATION_START = 0, SIMULATION_END = 1, RENDER_START = 2;
    private static final float MAX_CONTIGUOUS_FRAME_MS = 120;
    private enum WaitFor { TRANSFER_SLOT, OPENGL_PRODUCER, PRESENT_QUEUE }
    private final XessFgTransport[] slots = new XessFgTransport[XessFgNative.SLOT_COUNT];
    private final XessFgAdmission admission = new XessFgAdmission();
    private ByteBuffer constants, reprojectionConstants;
    private long session, sessionId, window, sequence, frameStart, previousFrameStart;
    private int skipReason;
    private int frame, width, height, slot = -1, cameraMode, previousCameraMode;
    private long irisGeneration, previousIrisGeneration;
    private boolean failed, eligible, slotDeferred, depthReady, hudReady, reset, iris, fullInputs;
    private boolean reportedSleepRejection;
    private boolean servicingWindow, windowInterrupted;
    private boolean topLevel;
    private XessFgCapture capture;
    private XessFgMotion motion;
    private XessFgGpuMotion gpuMotion;
    private XessFgFlywheelMotion flywheelMotion;
    private boolean worldScope, geometryStaged, motionOverrides, dlssMotion, dlssMotionFinished;
    private ByteBuffer stagedReprojection;
    private record Hand(InteractionHand hand, Item item) {}
    public interface MotionScope extends AutoCloseable {
        MotionScope INACTIVE = () -> {};
        @Override void close();
    }
    private XessFgView current, previous;
    private ClientLevel level, previousLevel;
    private RenderTarget deferredBlit;
    private long renderLuid, generationLuid;
    private long geometrySize;

    private XessFgRuntime() {}

    public static boolean requested() {
        return ClientConfig.xessFg().enabled() && Platform.get() == Platform.WINDOWS
                && !INSTANCE.failed && !INSTANCE.servicingWindow;
    }

    public static void begin(Minecraft minecraft, boolean render) {
        if (INSTANCE.dlssMotionFinished) INSTANCE.dlssMotionFinished = false;
        if (!ClientConfig.dlss().enabled() && INSTANCE.session == 0 && INSTANCE.motion != null)
            INSTANCE.close();
        if (!ClientConfig.xessFg().enabled()) return;
        if (INSTANCE.servicingWindow) {
            INSTANCE.windowInterrupted = true;
            return;
        }
        INSTANCE.beginFrame(minecraft, render);
    }

    public static void pollProfile(XessFgFrameStats profile, boolean finishing) {
        if (INSTANCE.servicingWindow) return;
        profile.poll(INSTANCE.session, INSTANCE.sessionId, finishing);
    }

    private void beginFrame(Minecraft minecraft, boolean render) {
        if (gpuMotion != null) gpuMotion.discardUnfinishedFrame();
        current = null;
        worldScope = geometryStaged = motionOverrides = false;
        stagedReprojection = null;
        slotDeferred = depthReady = hudReady = iris = fullInputs = false;
        deferredBlit = null;
        irisGeneration = 0;
        if (!requested()) return;
        var target = minecraft.getWindow();
        long handle = target.getWindow();
        int w = target.getWidth(), h = target.getHeight();
        if (minecraft.level == null || w <= 0 || h <= 0
                || GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_ICONIFIED) != GLFW.GLFW_FALSE
                || exclusiveFullscreen(target)) {
            close();
            level = null;
            return;
        }
        long geometry = geometrySize(w, h);
        if (window != handle || width != w || height != h || topLevel != usePopup(target) || geometrySize != geometry) close();
        level = minecraft.level;
        cameraMode = minecraft.options.getCameraType().ordinal();
        eligible = render && gameplayActive(minecraft);
        if (session == 0 && !eligible) return;
        var profile = XessFgFrameStats.current();
        try {
            if (session == 0) {
                long start = XessFgFrameStats.Frame.start(profile);
                if (!open(minecraft, handle, w, h, usePopup(target), geometry)) return;
                if (profile != null) profile.complete(SESSION_OPEN, start);
            }
            long state = XessFgNative.admissionState(session, eligible, admission.pending());
            skipReason = eligible ? admission.update(state) : admission.reset();
            if (profile != null) profile.skipReason = skipReason;
            if (slot >= 0) {
                while (!XessFgNative.discard(session, slot, sequence)) {
                    if (!waitForWindow(minecraft, WaitFor.PRESENT_QUEUE)) {
                        close();
                        return;
                    }
                }
                slot = -1;
                previous = null;
            }
            motion.begin();
            slotDeferred = eligible && slots[0].geometry.sharedInputs;
            if (!slotDeferred && !acquireSlot(minecraft, profile)) {
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
                profile.gameplayEligible = eligible;
                profile.geometryWidth = (int) (geometrySize >>> 32);
                profile.geometryHeight = (int) geometrySize;
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

    private boolean acquireSlot(Minecraft minecraft, XessFgFrameStats.Frame profile) {
        long start = XessFgFrameStats.Frame.start(profile);
        long next = sequence + 1;
        long captureFrame = profile == null ? 0 : profile.id();
        if (profile != null) profile.slotsInUse = XessFgNative.slotsInUse(session);
        boolean colorOnly = !eligible || skipReason != 0;
        slot = XessFgNative.acquire(session, next, captureFrame, colorOnly);
        if (slot < 0 && slotDeferred) glFlush();
        while (slot < 0) {
            boolean producer = slot == XessFgNative.PRODUCER_BACKPRESSURE;
            long waitStart = XessFgFrameStats.Frame.start(profile);
            boolean active = waitForWindow(minecraft, producer ? WaitFor.OPENGL_PRODUCER : WaitFor.TRANSFER_SLOT);
            if (profile != null) profile.accumulate(producer ? PRODUCER_WAIT : SLOT_WAIT, waitStart);
            if (!active) return false;
            slot = XessFgNative.acquire(session, next, captureFrame, colorOnly);
        }
        sequence = next;
        slots[slot].color.acquire();
        slotDeferred = false;
        if (profile != null) {
            profile.bindSession(session, sessionId);
            profile.sequence = sequence;
            profile.earlySlot = slot;
            profile.complete(EARLY_SLOT, start);
        }
        return true;
    }

    private boolean ensureSlot(Minecraft minecraft, XessFgFrameStats.Frame profile) {
        if (slot >= 0) return true;
        if (slotDeferred && acquireSlot(minecraft, profile)) return true;
        close();
        return false;
    }

    private boolean surfaceMatches(Window target) {
        return target.getWindow() == window && !exclusiveFullscreen(target) && topLevel == usePopup(target)
                && target.getWidth() == width && target.getHeight() == height
                && !GLFW.glfwWindowShouldClose(window)
                && GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_FALSE;
    }

    private static boolean gameplayActive(Minecraft minecraft) {
        return minecraft.isWindowActive() && minecraft.screen == null && minecraft.getOverlay() == null && !minecraft.isPaused();
    }

    private static boolean exclusiveFullscreen(Window target) {
        return target.isFullscreen() && GLFW.glfwGetWindowMonitor(target.getWindow()) != 0;
    }

    private static boolean usePopup(Window target) {
        return target.isFullscreen() && ClientConfig.xessFg().surface() == XessFgSurfaceMode.BORDERLESS_POPUP;
    }

    private static long geometrySize(int width, int height) {
        var coords = CreateManaIndustry.IRIS_ACTIVE ? IrisShadows.unwarpedCoordinates(width, height) : null;
        int w = (width + 1) / 2, h = (height + 1) / 2;
        if (coords != null && coords.warp() == XessFgCoordinates.Warp.NONE && coords.scaleX() < 1 && coords.scaleY() < 1) {
            w = Math.max(1, (int) (width * coords.scaleX()));
            h = Math.max(1, (int) (height * coords.scaleY()));
        }
        return (long) w << 32 | Integer.toUnsignedLong(h);
    }

    private boolean open(Minecraft minecraft, long handle, int w, int h, boolean popup, long geometry) throws IOException {
        if (!XessFgTransport.supported()) throw new IOException("Win32 OpenGL memory and semaphore sharing are unavailable");
        var renderAdapter = StormGpuDevices.renderAdapter();
        if (renderAdapter.luid() == 0 || renderAdapter.nodes() != 1) {
            throw new IOException("Minecraft's OpenGL hardware adapter could not be identified");
        }
        var adapter = XessFgAdapters.select(XessFgAdapters.discover(minecraft.gameDirectory.toPath()), ClientConfig.xessFg().gpu());
        renderLuid = renderAdapter.luid();
        generationLuid = adapter.luid();
        boolean crossAdapter = renderLuid != generationLuid;
        var transport = crossAdapter ? ClientConfig.xessFg().transport() : XessFgTransportMode.RGBA8;
        int multiplier = ClientConfig.xessFg().multiplier();
        capture = new XessFgCapture();
        windowInterrupted = false;
        servicingWindow = true;
        try {
            session = XessFgNative.create(GLFWNativeWin32.glfwGetWin32Window(handle), renderAdapter.luid(), adapter.luid(), w, h,
                    transport.nativeId(), popup, (int) (geometry >>> 32), (int) geometry, multiplier);
        } finally {
            servicingWindow = false;
        }
        window = handle;
        width = w;
        height = h;
        geometrySize = geometry;
        topLevel = popup;
        if (windowInterrupted || minecraft.level != level || !surfaceMatches(minecraft.getWindow())) {
            close();
            return false;
        }
        ++sessionId;
        for (int i = 0; i < slots.length; i++) slots[i] = new XessFgTransport(session, i);
        motion = new XessFgMotion();
        if (XessFgGpuMotion.shaderSupport()) gpuMotion = new XessFgGpuMotion((int) (geometry >>> 32), (int) geometry);
        flywheelMotion = new XessFgFlywheelMotion();
        constants = ByteBuffer.allocateDirect(XessFgNative.CONSTANT_BYTES).order(ByteOrder.nativeOrder());
        if (slots[0].geometry.sharedInputs) {
            reprojectionConstants = ByteBuffer.allocateDirect(XessFgNative.REPROJECTION_BYTES).order(ByteOrder.nativeOrder());
        }
        reportedSleepRejection = false;
        CreateManaIndustry.LOGGER.info("[CMI/XeSS-FG] render={} (LUID {}), generation={} (PCI {}, LUID {}), transport={} ({}), surface={}, Arc shared inputs={}, XMX-capable Arc={}, requested multiplier={}x, interpolated frames={}",
                StormGpuDevices.renderer(), Long.toUnsignedString(renderAdapter.luid(), 16), adapter.selection().label(),
                String.format(Locale.ROOT, "%04x:%04x", adapter.vendor(), adapter.device()),
                Long.toUnsignedString(adapter.luid(), 16), transport.value(), crossAdapter ? "cross-adapter" : "same-adapter",
                popup ? "borderless_popup" : "child", slots[0].geometry.sharedInputs, adapter.xmxArc(),
                multiplier, XessFgNative.interpolatedFrames(session));
        return true;
    }

    public static void renderStart() {
        var owner = INSTANCE;
        if (owner.servicingWindow || owner.session == 0 || (owner.slot < 0 && !owner.slotDeferred)) return;
        try {
            XessFgNative.marker(owner.session, owner.frame, SIMULATION_END);
            XessFgNative.marker(owner.session, owner.frame, RENDER_START);
        } catch (RuntimeException | LinkageError failure) {
            owner.fail(failure);
        }
    }

    public static void worldView(Camera camera, Matrix4fc view, Matrix4fc projection) {
        var owner = INSTANCE;
        if ((owner.canCapture() || owner.dlssMotion) && !RiftRenderFrame.auxiliary()) {
            owner.worldScope = true;
            var coordinates = DlssRuntime.coordinates();
            if (!owner.iris) owner.current = new XessFgView(new Matrix4f(view), new Matrix4f(projection),
                    camera.getPosition(), coordinates);
            if (!owner.dlssMotion && owner.gpuMotion != null) owner.restoreFgMotionGrid();
            if (owner.gpuMotion != null && (!CreateManaIndustry.IRIS_ACTIVE || !IrisShadows.packActive())) {
                var target = Minecraft.getInstance().getMainRenderTarget();
                owner.gpuMotion.begin(camera.getPosition(), target.width, target.height, coordinates);
            }
        }
    }

    public static void beginDlssMotion(ClientLevel level, int width, int height) {
        var owner = INSTANCE;
        owner.dlssMotion = true;
        owner.level = level;
        if (owner.motion == null) owner.motion = new XessFgMotion();
        if (owner.flywheelMotion == null) owner.flywheelMotion = new XessFgFlywheelMotion();
        owner.motion.begin();
        owner.motionGrid(width, height);
    }

    private void restoreFgMotionGrid() {
        motionGrid((int) (geometrySize >>> 32), (int) geometrySize);
    }

    private void motionGrid(int width, int height) {
        if (width <= 0 || height <= 0) return;
        if (gpuMotion != null && (gpuMotion.width() != width || gpuMotion.height() != height)) {
            gpuMotion.close();
            gpuMotion = null;
        }
        if (gpuMotion == null && XessFgGpuMotion.shaderSupport()) gpuMotion = new XessFgGpuMotion(width, height);
    }

    public static void beginSharedMotionDepth() {
        if (INSTANCE.motion != null) INSTANCE.motion.beginSharedDepth();
    }

    public static void endSharedMotionDepth() {
        if (INSTANCE.motion != null) INSTANCE.motion.endSharedDepth();
    }

    @FunctionalInterface
    public interface WorldMotionLayers {
        void apply(int opaque, int gpuWinners, int translucent);
    }

    public static void dlssWorldMotion(boolean reset, int depth, int width, int height, XessFgCoordinates coords,
                                       WorldMotionLayers layers) {
        var owner = INSTANCE;
        var motion = owner.motion;
        var gpu = owner.gpuMotion;
        int opaque = motion == null ? 0 : motion.draw(XessFgMotion.Layer.WORLD, reset, depth, width, height, coords);
        int winners = gpu != null && gpu.width() == width && gpu.height() == height && gpu.hasWinners(reset) ? gpu.winners() : 0;
        int translucent = motion == null ? 0 : motion.draw(XessFgMotion.Layer.TRANSLUCENT, reset, depth, width, height, coords);
        layers.apply(opaque, winners, translucent);
    }

    public static void dlssHandMotion(boolean reset, int depth, int width, int height, XessFgCoordinates coords,
                                      IntConsumer object) {
        var owner = INSTANCE;
        owner.emitDlssMotion(XessFgMotion.Layer.HAND, reset, depth, width, height,
                owner.iris && !DlssRuntime.externalJitter() ? IrisShadows.handCoordinates(coords) : coords, object);
    }

    private void emitDlssMotion(XessFgMotion.Layer layer, boolean reset, int depth, int width, int height,
                                XessFgCoordinates coords, IntConsumer object) {
        if (motion == null) return;
        int vectors = motion.draw(layer, reset, depth, width, height, coords);
        if (vectors != 0) object.accept(vectors);
    }

    public static void finishDlssHand(RenderTarget scene) {
        var owner = INSTANCE;
        if (!owner.iris && owner.canCapture()) owner.finishGeometry(scene.getDepthTextureId());
    }

    public static void resetDlssFrameGeneration() {
        var owner = INSTANCE;
        owner.depthReady = false;
        owner.previous = null;
    }

    public static void finishDlssMotion(boolean keep) {
        var owner = INSTANCE;
        owner.dlssMotion = owner.worldScope = false;
        owner.dlssMotionFinished = true;
        if (owner.motion != null) owner.motion.finish(keep);
        if (owner.gpuMotion != null) owner.gpuMotion.finish(keep);
    }

    public static MotionScope entityMotion(Object entity) {
        var owner = INSTANCE;
        return owner.captureObjects() ? owner.motion.track(entity, false) : MotionScope.INACTIVE;
    }

    public static MotionScope handMotion(InteractionHand hand, Item item) {
        var owner = INSTANCE;
        if (!owner.captureObjects()) return MotionScope.INACTIVE;
        return owner.motion.track(new Hand(hand, item), true, owner.iris ? IrisShadows.handDeformation() : null);
    }

    public static VertexConsumer motionBuffer(VertexConsumer source, RenderType type) {
        var motion = INSTANCE.motion;
        return motion != null ? motion.buffers(source, type) : source;
    }

    public static VertexConsumer particleMotion(VertexConsumer source, Particle particle) {
        var owner = INSTANCE;
        if (!owner.captureObjects()) return source;
        var type = particle.getRenderType();
        boolean transparent = type == ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
        return transparent || type == ParticleRenderType.TERRAIN_SHEET || type == ParticleRenderType.PARTICLE_SHEET_OPAQUE
                ? owner.motion.particle(source, particle, type, transparent) : source;
    }

    public static void flywheelMotion(dev.engine_room.flywheel.api.backend.Engine engine,
                                      dev.engine_room.flywheel.api.backend.RenderContext context) {
        var owner = INSTANCE;
        if (owner.captureObjects() && owner.level == context.level()) owner.flywheelMotion.capture(owner.motion, engine, context);
    }

    public static void gpuParticleMotion(int program,
                                         com.iridium126.createmanaindustry.client.particles.engine.ParticleBuffers buffers) {
        var owner = INSTANCE;
        if (owner.gpuMotion != null && owner.captureObjects())
            owner.gpuMotion.particle(program, buffers.historyBufferId(), buffers.drawIdentityWordBase());
    }

    public static void gpuLodMotion(int program, Matrix4fc sceneProjection, Matrix4fc lodProjection) {
        var owner = INSTANCE;
        if (owner.gpuMotion != null && owner.captureObjects())
            owner.gpuMotion.lod(program, sceneProjection, lodProjection);
    }

    public static MotionScope auxiliaryView() {
        var gpuMotion = INSTANCE.gpuMotion;
        return gpuMotion != null && RiftRenderFrame.auxiliary() ? gpuMotion.suspend() : MotionScope.INACTIVE;
    }

    private boolean captureObjects() {
        return worldScope && (canCapture() || dlssMotion) && !RiftRenderFrame.auxiliary()
                && (!CreateManaIndustry.IRIS_ACTIVE || !IrisShadows.active());
    }

    private static final class IrisShadows {
        static boolean packActive() {
            return net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable()
                    instanceof net.irisshaders.iris.pipeline.IrisRenderingPipeline;
        }

        static boolean active() {
            return net.irisshaders.iris.shadows.ShadowRenderingState.areShadowsCurrentlyBeingRendered();
        }

        static java.util.function.Consumer<org.joml.Vector4f> handDeformation() {
            var pack = com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackSession.current();
            if (!pack.xessFgHandDeformation() || pack.options() == null) return null;
            var captured = net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE;
            return pack.xessFgPack().handDeformation(new XessFgIrisOptions(pack.options()),
                    com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix(),
                    com.mojang.blaze3d.systems.RenderSystem.getModelViewMatrix(), captured.getGbufferModelView(),
                    net.irisshaders.iris.uniforms.SystemTimeUniforms.TIMER.getFrameTimeCounter());
        }

        static XessFgCoordinates handCoordinates(XessFgCoordinates coordinates) {
            var pack = com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackSession.current();
            if (pack.xessFgPack() == XessFgPack.COMPLEMENTARY || (pack.xessFgPack() == XessFgPack.BSL
                    && pack.options() != null && new XessFgIrisOptions(pack.options()).flag("TAA_SELECTIVE", false)))
                return new XessFgCoordinates(coordinates.scaleX(), coordinates.scaleY(), 0, 0,
                        coordinates.warp(), coordinates.time(), coordinates.strength());
            return coordinates;
        }

        static XessFgCoordinates unwarpedCoordinates(int width, int height) {
            var pack = com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackSession.current();
            if (pack.xessFgPack() == null || pack.options() == null
                    || !(net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable()
                    instanceof net.irisshaders.iris.pipeline.IrisRenderingPipeline)) return null;
            return pack.xessFgPack().coordinates(new XessFgIrisOptions(pack.options()), 0, width, height, 0, false, 0);
        }
    }

    public static void irisBegin(long generation, XessFgCoordinates coordinates, Camera camera, int width, int height,
                                 boolean terrainHistory) {
        var owner = INSTANCE;
        if (!owner.canCapture() && !owner.dlssMotion) return;
        owner.iris = true;
        owner.irisGeneration = generation;
        owner.current = null;
        owner.depthReady = false;
        if (owner.gpuMotion != null && coordinates != null) {
            owner.gpuMotion.begin(camera.getPosition(), width, height, coordinates);
            if (terrainHistory) owner.gpuMotion.terrain();
        }
    }

    public static void irisDepth(int texture, int handDepth, int width, int height, Camera camera, Matrix4fc view,
                                 Matrix4fc projection, XessFgCoordinates coordinates) {
        var owner = INSTANCE;
        if (!owner.canCapture() || !owner.iris) return;
        owner.current = new XessFgView(new Matrix4f(view), new Matrix4f(projection), camera.getPosition(), coordinates);
        owner.captureDepth(texture, width, height);
        owner.finishGeometry(handDepth);
    }

    public static void vanillaDepth(RenderTarget target, boolean rendersHand) {
        var owner = INSTANCE;
        if (owner.canCapture() && !owner.iris) {
            owner.captureDepth(target.getDepthTextureId(), target.width, target.height);
            if (!rendersHand) owner.finishGeometry(0);
        }
    }

    private void captureDepth(int texture, int w, int h) {
        if (texture <= 0 || w <= 0 || h <= 0 || current == null || geometryStaged) return;
        var profile = XessFgFrameStats.current();
        try {
            if (!ensureSlot(Minecraft.getInstance(), profile)) return;
        } catch (RuntimeException | LinkageError failure) {
            fail(failure);
            return;
        }
        long start = XessFgFrameStats.Frame.start(profile);
        boolean exported = false;
        boolean gpu = profile != null && profile.startGpu(DEPTH_MOTION);
        ByteBuffer reprojection = null;
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
            var priorView = reset ? current : previous;
            if (capture.canReproject(slots[slot], current, priorView)) reprojection = reprojectionConstants;
            capture.depthAndMotion(slots[slot], texture, w, h, current, priorView, reprojection);
            stagedReprojection = reprojection;
            applyObjectMotion(XessFgMotion.Layer.WORLD, texture);
            if (gpuMotion != null && gpuMotion.hasWinners(reset)) {
                capture.applyGpuMotion(slots[slot], texture, w, h, gpuMotion,
                        stagedReprojection != null && !motionOverrides);
                motionOverrides = true;
            }
            applyObjectMotion(XessFgMotion.Layer.TRANSLUCENT, texture);
            geometryStaged = true;
            exported = true;
        } catch (RuntimeException | LinkageError failure) {
            fail(failure);
        } finally {
            if (profile != null) {
                profile.endGpu(gpu, exported);
                profile.complete(DEPTH_MOTION, start);
            }
        }
    }

    private void applyObjectMotion(XessFgMotion.Layer layer, int texture) {
        var coords = layer == XessFgMotion.Layer.HAND && iris ? IrisShadows.handCoordinates(current.coordinates()) : current.coordinates();
        var geometry = slots[slot].geometry;
        int vectors = motion.draw(layer, reset, texture, geometry.width, geometry.height, coords);
        if (vectors != 0) mergeMotion(vectors);
    }

    private void mergeMotion(int vectors) {
        capture.applyMotion(slots[slot], vectors, stagedReprojection != null && !motionOverrides);
        motionOverrides = true;
    }

    private void finishGeometry(int handDepth) {
        if (!geometryStaged || depthReady || slot < 0) return;
        var profile = XessFgFrameStats.current();
        long start = XessFgFrameStats.Frame.start(profile);
        try {
            if (handDepth != 0) applyObjectMotion(XessFgMotion.Layer.HAND, handDepth);
            slots[slot].geometry.publish(sequence);
            XessFgNative.submitGeometry(session, slot, sequence, stagedReprojection, motionOverrides);
            depthReady = true;
        } catch (RuntimeException | LinkageError failure) {
            fail(failure);
        } finally {
            if (profile != null) profile.complete(GEOMETRY_PUBLISH, start);
        }
    }

    private boolean canCapture() {
        return !servicingWindow && session != 0 && (slot >= 0 || slotDeferred) && eligible && skipReason == 0;
    }

    public static void hudless(RenderTarget target) {
        var owner = INSTANCE;
        if (!owner.canCapture()) return;
        owner.worldScope = false;
        if (!owner.iris) owner.finishGeometry(target.getDepthTextureId());
        if (!owner.depthReady) return;
        try {
            owner.captureColor(target, XessFgCapture.HUDLESS, HUDLESS);
            var base = owner.slots[owner.slot].hudBase;
            if (base != null) {
                base.publish(owner.sequence);
                XessFgNative.submitHudless(owner.session, owner.slot, owner.sequence);
            }
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
            capture.color(slots[slot], target.getColorTextureId(), target.viewWidth, target.viewHeight, plane, fullInputs);
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
        if (servicingWindow || session == 0) return false;
        var client = Minecraft.getInstance();
        if (client.level == null || width != w || height != h) {
            close();
            return false;
        }
        boolean gameplay = client.level == level && gameplayActive(client);
        fullInputs = canCapture() && depthReady && hudReady && gameplay;
        var profile = XessFgFrameStats.current();
        if (profile != null) {
            if (!depthReady) profile.disabledChecks |= 8;
            if (!hudReady) profile.disabledChecks |= 16;
            if (!gameplay) profile.disabledChecks |= 32;
        }
        try {
            if (!ensureSlot(client, profile)) return false;
            captureColor(target, XessFgCapture.FINAL, FINAL_COLOR);
            if (fullInputs) writeConstants();
            long start = XessFgFrameStats.Frame.start(profile);
            slots[slot].color.publish(sequence);
            if (profile != null) profile.complete(GL_PUBLISH, start);
            start = XessFgFrameStats.Frame.start(profile);
            XessFgNative.submitColor(session, slot, sequence, fullInputs);
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
        if (owner.servicingWindow) {
            owner.windowInterrupted = true;
            return false;
        }
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
        if (fallback == minecraft.getMainRenderTarget()) fallback.blitToScreen(window.getWidth(), window.getHeight());
        return false;
    }

    private boolean presentFrame(Minecraft minecraft) {
        var target = minecraft.getWindow();
        if (minecraft.level != level || !surfaceMatches(target)) {
            close();
            return false;
        }
        try {
            var profile = XessFgFrameStats.current();
            boolean interpolate = fullInputs;
            long captureFrame = profile == null ? 0 : profile.id();
            long start = XessFgFrameStats.Frame.start(profile);
            long wait = 0;
            for (;;) {
                // GLFW polls events before the swap hook and again while waiting for queue space.
                interpolate = interpolate && gameplayActive(minecraft);
                if (XessFgNative.enqueuePresent(session, slot, frame, sequence, interpolate,
                        minecraft.options.enableVsync().get(), constants, captureFrame)) {
                    break;
                }
                if (profile != null && wait == 0) wait = System.nanoTime();
                if (!waitForWindow(minecraft, WaitFor.PRESENT_QUEUE)) {
                    close();
                    return false;
                }
            }
            if (profile != null) {
                if (fullInputs && !interpolate) profile.disabledChecks |= 32;
                profile.complete(PRESENT_ENQUEUE, start);
                if (wait != 0) profile.complete(PRESENT_BACKPRESSURE, wait);
                profile.enqueued(sessionId, interpolate, slots[slot].color.transportMode, wait != 0);
            }
            slot = -1;
            if (!dlssMotionFinished) {
                motion.finish(interpolate);
                if (gpuMotion != null) gpuMotion.finish(interpolate);
            }
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

    private boolean waitForWindow(Minecraft minecraft, WaitFor wait) {
        // GLFW can run a nested display tick while handling a world or window transition.
        windowInterrupted = false;
        servicingWindow = true;
        try {
            switch (wait) {
                case TRANSFER_SLOT -> XessFgNative.waitForSlot(session, !eligible || skipReason != 0);
                case OPENGL_PRODUCER -> XessFgNative.waitForProducer(session, !eligible || skipReason != 0);
                case PRESENT_QUEUE -> XessFgNative.waitForPresentSpace(session);
            }
            GLFW.glfwPollEvents();
        } finally {
            servicingWindow = false;
        }
        return !windowInterrupted && minecraft.level == level && surfaceMatches(minecraft.getWindow());
    }

    private void writeConstants() {
        XessFgCapture.matrix(constants, 0, current.sdkView());
        XessFgCapture.matrix(constants, 64, XessFgCapture.dxProjection(current.projection()));
        var coords = current.coordinates();
        boolean nativeGrid = coords.warp() == XessFgCoordinates.Warp.NONE && coords.scaleX() < 1 && coords.scaleY() < 1;
        constants.putFloat(128, nativeGrid ? coords.jitterX() : 0);
        constants.putFloat(132, nativeGrid ? -coords.jitterY() : 0);
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
        if (!INSTANCE.servicingWindow) INSTANCE.level = null;
    }

    private void close() {
        if (servicingWindow) {
            windowInterrupted = true;
            return;
        }
        if (session != 0) {
            long closing = session;
            session = 0;
            servicingWindow = true;
            try {
                XessFgNative.destroy(closing);
            } finally {
                servicingWindow = false;
            }
        }
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != null) slots[i].close();
            slots[i] = null;
        }
        if (capture != null) capture.close();
        if (motion != null) motion.close();
        if (gpuMotion != null) gpuMotion.close();
        gpuMotion = null;
        motion = null;
        flywheelMotion = null;
        capture = null;
        constants = null;
        reprojectionConstants = null;
        previous = current = null;
        previousLevel = null;
        deferredBlit = null;
        window = sequence = 0;
        topLevel = slotDeferred = false;
        slot = -1;
        skipReason = admission.reset();
        frameStart = previousFrameStart = 0;
    }
}
