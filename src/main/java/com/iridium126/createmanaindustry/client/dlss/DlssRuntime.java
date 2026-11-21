package com.iridium126.createmanaindustry.client.dlss;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.allaystorm.rift.RiftRenderFrame;
import com.iridium126.createmanaindustry.client.gpu.profile.GpuTimingObserver;
import com.iridium126.createmanaindustry.client.particles.engine.ParticleFrameStages;
import com.iridium126.createmanaindustry.client.particles.engine.ParticlePrograms;
import com.iridium126.createmanaindustry.client.particles.engine.spatial.StormGpuDevices;
import com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene;
import com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackSession;
import com.iridium126.createmanaindustry.client.xessfg.XessFgCoordinates;
import com.iridium126.createmanaindustry.client.xessfg.XessFgIrisOptions;
import com.iridium126.createmanaindustry.client.xessfg.XessFgPack;
import com.iridium126.createmanaindustry.client.xessfg.XessFgRuntime;
import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;
import com.iridium126.createmanaindustry.mixin.iris.DlssIrisAccess;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.util.Set;
import net.irisshaders.iris.targets.RenderTargets;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2i;
import org.lwjgl.system.Platform;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import static org.lwjgl.opengl.GL45.*;

/** Owns the primary scene target and the NGX history independently of presentation. */
public final class DlssRuntime {
    private static final DlssRuntime INSTANCE = new DlssRuntime();
    private static final int COLOR = 0, DEPTH = 1, OBJECT_MOTION = 2, GPU_MOTION = 3, HAND_DEPTH = 4;
    private final DlssInterop[] slots = new DlssInterop[DlssNative.SLOTS];
    private final int[] outputFramebuffers = new int[DlssNative.SLOTS];
    private final long[] profileFrames = new long[DlssNative.SLOTS];
    private long session, token, previousTime;
    private RenderTarget scene, override, output;
    private DlssPass pass;
    private DlssIrisTargets irisTargets;
    private IrisCut irisCut;
    private ParticleFrameStages.Scope irisWorldTiming, irisPostTiming;
    private int irisDepth, irisHand, irisResolveProgram;
    private XessFgCoordinates irisCoordinates = XessFgCoordinates.VANILLA, previousIrisCoordinates = XessFgCoordinates.VANILLA;
    private boolean irisPost, irisTransitioned, irisReconstructed;
    private Request configured, rejected;
    private ClientLevel level, previousLevel;
    private View current, previous;
    private int cameraMode, previousCameraMode, slot = -1, jitterIndex;
    private long now;
    private GLCapabilities glContext;
    private StormGpuDevices.RenderAdapter adapter;
    private float jitterX, jitterY;
    private boolean reset, depthPrepared, disableNext, rendersHand, reportedIris, reportedInterop;

    private record Request(int width, int height, int outputWidth, int outputHeight, long luid, long packGeneration,
                           float rasterScale, DlssPack.Encoding encoding, boolean stencil) {}
    private record IrisCut(DlssPack pack, long generation, DlssIrisAccess.Pass pass, int program, int uniform,
                           boolean stockJitter, float rasterScale) {}
    private record View(Matrix4fc modelView, Matrix4fc projection, Vec3 position) {
        Matrix4f toPrevious(View prior, boolean translate) {
            Matrix4f transform = new Matrix4f(prior.projection).mul(prior.modelView);
            if (translate) {
                Vec3 delta = position.subtract(prior.position);
                transform.translate((float) delta.x, (float) delta.y, (float) delta.z);
            }
            return transform.mul(new Matrix4f(projection).mul(modelView).invert());
        }
    }

    private DlssRuntime() {}

    public static RenderTarget worldTarget() {
        return INSTANCE.override;
    }

    public static RenderTarget irisSceneTarget() {
        return irisActive() ? INSTANCE.scene : null;
    }

    public static boolean requested() {
        return INSTANCE.session != 0 || ClientConfig.dlss().enabled();
    }

    public static boolean active() {
        return INSTANCE.output != null;
    }

    public static boolean irisActive() {
        return active() && INSTANCE.irisCut != null;
    }

    public static boolean externalJitter() {
        return active() && (INSTANCE.irisCut == null || !INSTANCE.irisCut.stockJitter);
    }

    public static String measurementContext() {
        if (!ClientConfig.dlss().enabled()) return "off";
        Request request = INSTANCE.configured;
        if (INSTANCE.reportedIris) return "original shaderpack reconstruction";
        if (request == null || INSTANCE.disableNext) return "original renderer";
        return (INSTANCE.irisCut == null ? "" : "Iris " + INSTANCE.irisCut.pack.pass() + " ")
                + request.width + "x" + request.height + " -> " + request.outputWidth + "x" + request.outputHeight;
    }

    public static XessFgCoordinates coordinates() {
        var owner = INSTANCE;
        return active() ? new XessFgCoordinates(1, 1, owner.jitterX, owner.jitterY, XessFgCoordinates.Warp.NONE, 0, 0)
                : XessFgCoordinates.VANILLA;
    }

    public static Matrix4f jitterProjection(Matrix4f input) {
        if (!externalJitter() || INSTANCE.irisTransitioned) return input;
        var owner = INSTANCE;
        return new Matrix4f().translation(2 * owner.jitterX / owner.scene.width,
                2 * owner.jitterY / owner.scene.height, 0).mul(input);
    }

    public static Matrix4f stableProjection(Matrix4fc input) {
        if (externalJitter()) {
            var owner = INSTANCE;
            return new Matrix4f().translation(-2 * owner.jitterX / owner.scene.width,
                    -2 * owner.jitterY / owner.scene.height, 0).mul(input);
        }
        return new Matrix4f(input);
    }

    public static void sceneView(Camera camera, Matrix4fc view, Matrix4fc projection) {
        if (!active() || irisActive()) return;
        INSTANCE.updateView(camera, view, projection);
    }

    private void updateView(Camera camera, Matrix4fc view, Matrix4fc projection) {
        current = new View(new Matrix4f(view), new Matrix4f(projection), camera.getPosition());
        reset |= previous == null || current.position.distanceToSqr(previous.position) > 64
                || current.modelView.m02() * previous.modelView.m02() + current.modelView.m12() * previous.modelView.m12()
                + current.modelView.m22() * previous.modelView.m22() < 0.5f;
    }

    public static void worldDepth(RenderTarget target, boolean rendersHand) {
        if (!active() || irisActive()) return;
        var owner = INSTANCE;
        owner.rendersHand = rendersHand;
        try {
            owner.prepareDepth(target.getDepthTextureId());
        } catch (RuntimeException | LinkageError failure) {
            owner.fail(failure);
        }
    }

    public static XessFgCoordinates irisCoordinates(XessFgCoordinates coordinates) {
        if (!irisActive() || coordinates == null || INSTANCE.irisCut.stockJitter) return coordinates;
        return new XessFgCoordinates(coordinates.scaleX(), coordinates.scaleY(), INSTANCE.jitterX, INSTANCE.jitterY,
                coordinates.warp(), coordinates.time(), coordinates.strength());
    }

    public static void irisDepth(int depth, int hand, Camera camera, Matrix4fc view, Matrix4fc projection,
                                 XessFgCoordinates coordinates) {
        if (!irisActive()) return;
        var owner = INSTANCE;
        owner.irisDepth = depth;
        owner.irisHand = hand;
        if (owner.irisCut.stockJitter) {
            owner.jitterX = coordinates.jitterX();
            owner.jitterY = coordinates.jitterY();
        }
        owner.irisCoordinates = coordinates;
        owner.updateView(camera, view, stableProjection(projection));
    }

    public static boolean irisPass(Object pass, RenderTargets targets) {
        var owner = INSTANCE;
        if (!irisActive() || owner.irisTransitioned || pass != owner.irisCut.pass || RiftRenderFrame.auxiliary()) return false;
        try {
            owner.resolveIris(targets, owner.irisCut.pass.cmi$flipped());
        } catch (RuntimeException | LinkageError failure) {
            owner.fail(failure);
            owner.endIrisTargets();
        }
        return owner.irisTransitioned;
    }

    public static boolean irisFinal(RenderTargets targets, Set<Integer> flipped) {
        var owner = INSTANCE;
        if (!irisActive() || owner.irisTransitioned || owner.irisCut.pass != null || RiftRenderFrame.auxiliary()) return false;
        try {
            owner.resolveIris(targets, flipped);
            if (owner.irisTransitioned) owner.output.bindWrite(true);
        } catch (RuntimeException | LinkageError failure) {
            owner.fail(failure);
            owner.endIrisTargets();
        }
        return owner.irisTransitioned;
    }

    private void resolveIris(RenderTargets targets, Set<Integer> flipped) {
        stopWorldTiming();
        var pack = irisCut.pack;
        var input = targets.get(pack.color());
        if (input == null || input.getWidth() != scene.width || input.getHeight() != scene.height || irisDepth == 0)
            throw new IllegalStateException("Iris scene color and depth must share the DLSS input grid");
        int color = flipped.contains(pack.color()) ? input.getAltTexture() : input.getMainTexture();
        glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT | GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        int rebuilt = 0;
        try {
            prepareDepth(irisDepth);
            if (depthPrepared) {
                if (irisHand != 0) {
                    dispatch(irisHand, HAND_DEPTH, 0);
                    XessFgRuntime.dlssHandMotion(reset, irisHand, configured.width, configured.height, irisCoordinates,
                            motion -> dispatch(motion, OBJECT_MOTION, 0));
                }
                try (var timing = ParticleFrameStages.begin("dlss.inputs.iris.color")) {
                    pass.dispatch(slots[slot], configured.width, configured.height, color, COLOR, 0, jitterX, jitterY,
                            null, null, pack.encoding());
                }
                evaluate();
                rebuilt = slots[slot].textures[DlssInterop.OUTPUT];
            }
        } catch (RuntimeException | LinkageError failure) {
            fail(failure);
        }
        try (var timing = ParticleFrameStages.begin("dlss.iris.promote")) {
            if (irisTargets == null) irisTargets = new DlssIrisTargets();
            irisTargets.begin(targets, output.width, output.height, ShaderPackSession.current().xessFgPack());
            irisTargets.resolve(color, rebuilt, configured.width, configured.height, pack.encoding());
            copyDepth(output, configured.width, configured.height);
        }
        if (irisCut.uniform >= 0) {
            irisResolveProgram = irisCut.program;
            glProgramUniform1i(irisResolveProgram, irisCut.uniform, 1);
        }
        irisPost = irisTransitioned = true;
        irisReconstructed = rebuilt != 0;
        override = null;
        irisPostTiming = ParticleFrameStages.begin("dlss.iris.post");
    }

    public static boolean irisPost() {
        return INSTANCE.irisPost && !RiftRenderFrame.auxiliary();
    }

    public static int irisTexture(int texture) {
        return irisPost() ? INSTANCE.irisTargets.texture(texture) : texture;
    }

    public static void irisImage(int texture, int access) {
        if (irisPost() && access != GL_READ_ONLY) INSTANCE.irisTargets.markImageWrite(texture);
    }

    public static void irisFramebuffer(net.irisshaders.iris.gl.framebuffer.GlFramebuffer framebuffer,
                                       it.unimi.dsi.fastutil.ints.Int2IntMap attachments, boolean write) {
        if (irisPost()) INSTANCE.irisTargets.framebuffer(framebuffer, attachments, write);
    }

    public static int irisPostWidth(int size) {
        var owner = INSTANCE;
        return Math.round(size * (owner.output.width / (float) owner.scene.width));
    }

    public static int irisPostHeight(int size) {
        var owner = INSTANCE;
        return Math.round(size * (owner.output.height / (float) owner.scene.height));
    }

    public static Vector2i irisPostSize(int attachment) {
        return INSTANCE.irisTargets.outputSize(attachment);
    }

    public static void finishIrisPost() {
        if (irisPost()) INSTANCE.endIrisTargets();
    }

    private void endIrisTargets() {
        if (irisPostTiming != null) {
            irisPostTiming.close();
            irisPostTiming = null;
        }
        if (irisResolveProgram != 0) {
            glProgramUniform1i(irisResolveProgram, irisCut.uniform, 0);
            irisResolveProgram = 0;
        }
        if (irisPost) {
            irisPost = false;
            try (var timing = ParticleFrameStages.begin("dlss.iris.history")) {
                irisTargets.finish();
            }
        }
    }

    private void stopWorldTiming() {
        if (irisWorldTiming != null) {
            irisWorldTiming.close();
            irisWorldTiming = null;
        }
    }

    public static void renderWorld(Minecraft client, Runnable render) {
        INSTANCE.render(client, render);
    }

    private void render(Minecraft client, Runnable render) {
        RenderTarget output = client.getMainRenderTarget();
        if (!begin(client, output)) {
            render.run();
            return;
        }
        boolean rendered = false;
        boolean completed = false;
        try {
            XessFgRuntime.beginDlssMotion(level, configured.width, configured.height);
            this.output = output;
            irisTransitioned = irisReconstructed = irisPost = false;
            irisCoordinates = XessFgCoordinates.VANILLA;
            irisDepth = irisHand = irisResolveProgram = 0;
            override = scene;
            scene.bindWrite(true);
            if (irisCut != null) {
                irisWorldTiming = ParticleFrameStages.begin("dlss.iris.world");
                render.run();
            } else {
                try (var timing = ParticleFrameStages.begin("dlss.world")) {
                    render.run();
                }
            }
            rendered = true;
            try {
                if (irisCut != null) completed = irisReconstructed;
                else {
                    XessFgRuntime.finishDlssHand(scene);
                    completed = reconstruct(output);
                }
            } catch (RuntimeException | LinkageError failure) {
                fail(failure);
            }
        } finally {
            stopWorldTiming();
            override = null;
            this.output = null;
            endIrisTargets();
            if (rendered && !completed) {
                if (!irisTransitioned) blitScene(output, -1);
                if (irisCut != null && !irisTransitioned) fail(new IllegalStateException("Iris did not reach the reconstruction pass"));
                XessFgRuntime.resetDlssFrameGeneration();
            }
            XessFgRuntime.finishDlssMotion(completed);
            output.bindWrite(true);
            previous = completed ? current : null;
            previousIrisCoordinates = completed ? irisCoordinates : XessFgCoordinates.VANILLA;
            previousLevel = completed ? level : null;
            previousCameraMode = cameraMode;
            previousTime = now;
        }
    }

    private boolean begin(Minecraft client, RenderTarget output) {
        var settings = ClientConfig.dlss();
        if (!settings.enabled() || Platform.get() != Platform.WINDOWS) {
            close();
            rejected = null;
            reportedIris = reportedInterop = false;
            return false;
        }
        if (disableNext) close();
        if (client.level == null
                || client.screen != null || client.getOverlay() != null || client.isPaused()
                || output.width < 1 || output.height < 1) {
            previous = null;
            return false;
        }
        boolean packActive = CreateManaIndustry.IRIS_ACTIVE && IrisRoute.active();
        irisCut = packActive ? IrisRoute.cut() : null;
        if (packActive && irisCut == null) {
            if (!reportedIris) CreateManaIndustry.LOGGER.info("[CMI/DLSS] Using the shader pack's original reconstruction");
            reportedIris = true;
            if (configured != null) {
                close();
                XessFgRuntime.resetDlssFrameGeneration();
            }
            previous = null;
            return false;
        }
        reportedIris = false;
        if (!DlssInterop.supported() || !com.iridium126.createmanaindustry.client.xessfg.XessFgGpuMotion.shaderSupport()) {
            if (!reportedInterop) CreateManaIndustry.LOGGER.warn("[CMI/DLSS] Shared GL textures, fences or GPU motion are unavailable");
            reportedInterop = true;
            return false;
        }
        if (ClientConfig.particleEnabled && !ParticlePrograms.INSTANCE.motionCaptureReady()) return false;
        if (glContext != GL.getCapabilities()) {
            glContext = GL.getCapabilities();
            adapter = StormGpuDevices.renderAdapter();
        }
        if (adapter.luid() == 0 || adapter.nodes() != 1) return false;
        Request request = new Request(renderExtent(output.width, settings.renderScale()),
                renderExtent(output.height, settings.renderScale()), output.width, output.height, adapter.luid(),
                irisCut == null ? 0 : irisCut.generation, irisCut == null ? 1 : irisCut.rasterScale,
                irisCut == null ? DlssPack.Encoding.SDR : irisCut.pack.encoding(), output.isStencilEnabled());
        if (request.equals(rejected)) return false;
        try {
            if (!request.equals(configured)) {
                close();
                configured = request;
                open(client, request);
                XessFgRuntime.resetDlssFrameGeneration();
            }
            slot = -1;
            for (int i = 0; i < slots.length; ++i) {
                int candidate = (int) ((token + i) % slots.length);
                if (DlssNative.available(session, candidate)) {
                    if (slot < 0) slot = candidate;
                    if (profileFrames[candidate] != 0) {
                        double elapsed = DlssNative.pollGpuTime(session, candidate);
                        if (elapsed >= 0) GpuTimingObserver.sample("dlss.ngx", profileFrames[candidate], -1, Double.NaN, elapsed);
                        profileFrames[candidate] = 0;
                    }
                }
            }
        } catch (IOException | RuntimeException | LinkageError failure) {
            fail(failure);
            close();
            return false;
        }
        now = System.nanoTime();
        level = client.level;
        cameraMode = client.options.getCameraType().ordinal();
        current = null;
        depthPrepared = rendersHand = false;
        reset = previous == null || previousLevel != level || previousCameraMode != cameraMode
                || now - previousTime > 150_000_000L;
        int phases = (int) Math.ceil(8.0 * output.width * (double) output.height / (request.width * (double) request.height));
        if (reset) jitterIndex = 0;
        int sample = jitterIndex++ % phases + 1;
        jitterX = halton(sample, 2) - 0.5f;
        jitterY = halton(sample, 3) - 0.5f;
        if (irisCut != null && irisCut.stockJitter) jitterX = jitterY = 0;
        return true;
    }

    static int renderExtent(int output, double ratio) {
        return Math.max(1, Math.min(output, (int) Math.round(output * ratio)));
    }

    private static float halton(int index, int base) {
        float fraction = 1, result = 0;
        for (; index > 0; index /= base) {
            fraction /= base;
            result += fraction * (index % base);
        }
        return result;
    }

    private void open(Minecraft client, Request request) throws IOException {
        var directory = DlssNative.load(client.gameDirectory.toPath());
        session = DlssNative.create(request.luid, request.width, request.height, request.outputWidth, request.outputHeight,
                request.encoding.hdr, directory.toString(), Files.createDirectories(directory.resolve("ngx")).toString());
        scene = new TextureTarget(rasterContainer(request.width, request.rasterScale),
                rasterContainer(request.height, request.rasterScale), true, Minecraft.ON_OSX);
        if (request.stencil) scene.enableStencil();
        pass = new DlssPass();
        for (int i = 0; i < slots.length; i++) {
            slots[i] = new DlssInterop(session, i, request.width, request.height, request.outputWidth, request.outputHeight);
            int framebuffer = glCreateFramebuffers();
            outputFramebuffers[i] = framebuffer;
            glNamedFramebufferTexture(framebuffer, GL_COLOR_ATTACHMENT0, slots[i].textures[DlssInterop.OUTPUT], 0);
            if (glCheckNamedFramebufferStatus(framebuffer, GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("The shared DLSS output framebuffer is incomplete");
        }
        token = 2;
        CreateManaIndustry.LOGGER.info("[CMI/DLSS] NGX {}x{} → {}x{} on OpenGL adapter {}",
                request.width, request.height, request.outputWidth, request.outputHeight, Long.toUnsignedString(request.luid, 16));
        if (irisCut != null) {
            CreateManaIndustry.LOGGER.info("[CMI/DLSS] Iris {} at {}, colortex{}, {} encoding, {}x{} world target",
                    ShaderPackSession.current().xessFgPack(), irisCut.pass == null ? "final" : irisCut.pack.pass(),
                    irisCut.pack.color(), irisCut.pack.encoding(), scene.width, scene.height);
        }
    }

    static int rasterContainer(int extent, float scale) {
        return (int) Math.ceil(extent / (double) scale);
    }

    private void prepareDepth(int depth) {
        if (slot < 0 || current == null || depthPrepared || disableNext) return;
        try (var timing = ParticleFrameStages.begin("dlss.inputs.geometry")) {
            View prior = reset ? current : previous;
            pass.dispatch(slots[slot], configured.width, configured.height, depth, DEPTH, 0, jitterX, jitterY,
                    current.toPrevious(prior, true), current.toPrevious(prior, false),
                    DlssPack.Encoding.SDR, irisCoordinates, historyCoordinates());
            XessFgRuntime.dlssWorldMotion(reset, depth, configured.width, configured.height, irisActive() ? irisCoordinates : coordinates(),
                    motion -> dispatch(motion, OBJECT_MOTION, 0), winners -> dispatch(depth, GPU_MOTION, winners));
            depthPrepared = true;
        }
    }

    private boolean reconstruct(RenderTarget output) {
        if (slot < 0 || disableNext) return false;
        prepareDepth(scene.getDepthTextureId());
        if (!depthPrepared) return false;
        try (var timing = ParticleFrameStages.begin("dlss.inputs.color")) {
            if (rendersHand) {
                dispatch(scene.getDepthTextureId(), HAND_DEPTH, 0);
                XessFgRuntime.dlssHandMotion(reset, scene.getDepthTextureId(), scene.width, scene.height, coordinates(),
                        motion -> dispatch(motion, OBJECT_MOTION, 0));
            }
            dispatch(scene.getColorTextureId(), COLOR, 0);
        }
        evaluate();
        try (var timing = ParticleFrameStages.begin("dlss.scene.output")) {
            blitScene(output, outputFramebuffers[slot]);
        }
        return true;
    }

    private void evaluate() {
        try (var timing = ParticleFrameStages.begin("dlss.submit")) {
            var input = slots[slot];
            long stamp = ++token;
            boolean profile = ParticleFrameStages.active();
            input.publish(stamp);
            DlssNative.evaluate(session, slot, stamp, jitterX, -jitterY,
                    reset ? 0 : (now - previousTime) * 1e-6f, reset, profile);
            profileFrames[slot] = profile ? ClientGpuScene.INSTANCE.renderFrameToken() : 0;
            input.acquire(stamp);
        }
    }

    private void blitScene(RenderTarget output, int reconstructed) {
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
        if (scissor) glDisable(GL_SCISSOR_TEST);
        try {
            if (reconstructed >= 0) {
                glBlitNamedFramebuffer(reconstructed, output.frameBufferId,
                        0, output.height, output.width, 0, 0, 0, output.width, output.height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
            } else {
                glBlitNamedFramebuffer(scene.frameBufferId, output.frameBufferId, 0, 0, scene.width, scene.height,
                        0, 0, output.width, output.height, GL_COLOR_BUFFER_BIT, GL_LINEAR);
            }
            copyDepth(output, scene.width, scene.height);
        } finally {
            if (scissor) glEnable(GL_SCISSOR_TEST);
        }
    }

    private void copyDepth(RenderTarget output, int width, int height) {
        if (output.getDepthTextureId() == -1) return;
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
        if (scissor) glDisable(GL_SCISSOR_TEST);
        try {
            glBlitNamedFramebuffer(scene.frameBufferId, output.frameBufferId, 0, 0, width, height,
                    0, 0, output.width, output.height, GL_DEPTH_BUFFER_BIT, GL_NEAREST);
        } finally {
            if (scissor) glEnable(GL_SCISSOR_TEST);
        }
    }

    private void dispatch(int tex, int operation, int winners) {
        pass.dispatch(slots[slot], configured.width, configured.height, tex, operation, winners, jitterX, jitterY,
                null, null, DlssPack.Encoding.SDR, irisCoordinates, historyCoordinates());
    }

    private XessFgCoordinates historyCoordinates() {
        return reset ? irisCoordinates : previousIrisCoordinates;
    }

    private void fail(Throwable failure) {
        if (!disableNext) CreateManaIndustry.LOGGER.warn("[CMI/DLSS] Using the original scene renderer", failure);
        rejected = configured;
        disableNext = true;
        previous = null;
    }

    private void close() {
        override = null;
        if (session != 0) DlssNative.destroy(session);
        session = 0;
        for (int i = 0; i < slots.length; ++i) {
            if (outputFramebuffers[i] != 0) glDeleteFramebuffers(outputFramebuffers[i]);
            if (slots[i] != null) slots[i].close();
            outputFramebuffers[i] = 0;
            profileFrames[i] = 0;
            slots[i] = null;
        }
        if (scene != null) scene.destroyBuffers();
        if (pass != null) pass.close();
        if (irisTargets != null) irisTargets.close();
        irisTargets = null;
        scene = null;
        pass = null;
        configured = null;
        previous = null;
        previousLevel = null;
        disableNext = false;
    }

    public static void shutdown() {
        INSTANCE.close();
    }

    private static final class IrisRoute {
        private static WeakReference<Object> lastPipeline = new WeakReference<>(null);
        private static long lastPack;
        private static IrisCut lastCut;

        static boolean active() {
            return net.irisshaders.iris.Iris.isPackInUseQuick();
        }

        static IrisCut cut() {
            var p = net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable();
            if (!(p instanceof net.irisshaders.iris.pipeline.IrisRenderingPipeline)
                    || !(p instanceof DlssIrisAccess.Pipeline access)) return null;
            var session = ShaderPackSession.current();
            var pack = session.dlssPack();
            if (pack == null || session.options() == null || !session.ownsPipeline(p)
                    || net.irisshaders.iris.gui.option.IrisVideoSettings.colorSpace
                    != net.irisshaders.iris.pathways.colorspace.ColorSpace.SRGB) return null;
            if (lastPipeline.get() == p && lastPack == session.generation()) return lastCut;
            lastPipeline = new WeakReference<>(p);
            lastPack = session.generation();
            lastCut = findCut(access, session, pack);
            return lastCut;
        }

        static IrisCut findCut(DlssIrisAccess.Pipeline access, ShaderPackSession session, DlssPack pack) {
            var options = new XessFgIrisOptions(session.options());
            if (options.flag("SCREENSHOT_MODE", false) || options.flag("SR_ENABLE", false)) return null;
            var key = session.xessFgPack();
            boolean taa = options.flag("TAA", true);
            float rasterScale = switch (key) {
                case BLISS -> options.flag("TAA_UPSCALING", false) && taa ? options.number("SCALE_FACTOR", .75f) : 1;
                case PHOTON -> options.flag("TAAU", false) ? options.number("TAAU_RENDER_SCALE", .75f) : 1;
                default -> 1;
            };
            if (!Float.isFinite(rasterScale) || rasterScale < .1f || rasterScale > 1) return null;
            for (Object pass : ((DlssIrisAccess.Composites) access.cmi$composites()).cmi$passes()) {
                var candidate = (DlssIrisAccess.Pass) pass;
                if (!pack.pass().equals(candidate.cmi$name()) || candidate.cmi$program() == null) continue;
                int program = candidate.cmi$program().getProgramId();
                int uniform = glGetUniformLocation(program, DlssPack.RESOLVE);
                if (uniform < 0 && taa) return null;
                return new IrisCut(pack, session.generation(), candidate, program, uniform, taa || rasterScale < 1, rasterScale);
            }
            return key == XessFgPack.BSL && !taa
                    && !options.flag("RETRO_FILTER", false)
                    ? new IrisCut(pack, session.generation(), null, 0, -1, false, 1) : null;
        }
    }
}
