package com.iridium126.createmanaindustry.client.dimension.render;

import java.util.Iterator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL46;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.dimension.AllvrClientCubeCache;
import com.iridium126.createmanaindustry.client.dimension.AllvrClientRenderDistance;
import com.iridium126.createmanaindustry.client.dimension.iris.AllvrIrisDataHolder;
import com.iridium126.createmanaindustry.client.dimension.iris.AllvrIrisFrameTarget;
import com.iridium126.createmanaindustry.client.dimension.iris.AllvrIrisPipelineData;
import com.iridium126.createmanaindustry.client.dimension.iris.AllvrVoxyUniforms;
import com.iridium126.createmanaindustry.client.dimension.render.backend.AllvrCompatBackend;
import com.iridium126.createmanaindustry.client.render.shaderpack.ShadowDistortionRegistry;
import com.iridium126.createmanaindustry.config.ClientConfig;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;
import com.iridium126.createmanaindustry.dimension.mesh.AllvrMesher;
import com.mojang.blaze3d.systems.RenderSystem;

import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * ALLVR terrain renderer (doc §13 phase 4): Tier B MDI forward pass, fully
 * GPU-driven since 4c-2 — node traversal → cmdgen → one
 * glMultiDrawElementsIndirectCount per frame. The V0 CPU command source and
 * its {@code allvrGpuPipeline} switch were deleted at 4c-2 (grilling option
 * A): below the merged capability gate (GL 4.6, or 4.5 +
 * ARB_shader_draw_parameters + ARB_indirect_parameters) the terrain is
 * Tier C-inactive. The shadow pass keeps its CPU command source by design —
 * it culled against the light's ortho box, which the player-view-driven GPU
 * path cannot serve.
 * <p>
 * Frame slot by mode (grilling decision ⑧ fallback chain): no pack →
 * AFTER_SKY (the vanilla-terrain window this pass replaces); pack in use
 * without a resolvable voxy patch (or with the patched program unavailable) →
 * AFTER_LEVEL, self-lit after the composite blit (documented V0 coexistence
 * trade-off); pack-lit via the voxy patch (iris integration G2) →
 * AFTER_BLOCK_ENTITIES — inside the gbuffer phase, so the draw lands in the
 * pack's colortex targets before its deferred passes sample them
 * ({@link AllvrIrisFrameTarget} carries the pack-lit framebuffer).
 * <p>
 * Lifecycle: cube apply/forget/setBlock arrive from {@link AllvrClientCubeCache}
 * on the main thread; mesh jobs run on the mesher worker; results are drained
 * and uploaded on the render thread inside the stage handler.
 */
public final class AllvrRenderer {

    public static final AllvrRenderer INSTANCE = new AllvrRenderer();

    private final AllvrBuffers buffers = new AllvrBuffers();
    private final AllvrShaderCache shaders = new AllvrShaderCache();
    private final AllvrNodeStore nodes = new AllvrNodeStore();
    private final AllvrRenderWorld renderWorld = new AllvrRenderWorld();
    private final AllvrCompatBackend compat = new AllvrCompatBackend();
    private final Long2ObjectOpenHashMap<Cube> renderCubes = new Long2ObjectOpenHashMap<>();
    /** Dedupe set for submitted mesh jobs (main thread only). */
    private final LongOpenHashSet pending = new LongOpenHashSet();
    /** Results pulled from the worker but deferred by this frame's upload budget. */
    private final Deque<AllvrMesherWorker.MeshResult> deferredResults = new ArrayDeque<>();
    private final int[] commands = new int[AllvrBuffers.COMMAND_STRIDE * AllvrBuffers.MAX_COMMANDS];

    /** Terrain backend tier (sodium-parity plan §7.7): B = default GPU-driven
     *  MDI path, C = CPU render list + plain indexed batch drawing. Tier C
     *  never disables the near terrain — capability shortfalls and Tier B
     *  session failures latch into it. */
    private enum Tier { B, C }
    private Tier tier = Tier.B;

    // GPU-cull path state (4a). Nodes are maintained regardless of the active
    // path (setMesh/freeNode mirror assignQuads/forget), so the config switch
    // is seamless without a resync pass.
    private final float[] frustumPlanes = new float[24];
    private final Matrix4f projViewScratch = new Matrix4f();
    private final Vector4f planeScratch = new Vector4f();
    /** Merged terrain gate (4c-2): GL 4.6 core, or GL 4.5 with BOTH
     *  ARB_shader_draw_parameters (gl_BaseInstance vertex path) and
     *  ARB_indirect_parameters (MDIC count). Anything below is Tier C — no
     *  terrain, one-time hint; the V0 CPU path that served the no-MDIC
     *  window was deleted. */
    private boolean capsOk;
    /** True when the context is GL 4.6 core (MDIC entry point choice). */
    private boolean coreGl46;
    private boolean warnedComputeFail;
    // starts at 2 so cleared stamps (0) and pre-run stamps never alias a
    // live lastFrameId (>= 2) in the two-phase membership test
    private int frameId = 2;
    /** 1.5·near·far/(far−near): view-space → depth-space bias for HiZ tests. */
    private float depthBiasScale = -1f;
    private long lastGpuDebugMillis;
    /** HiZ re-allocation throttle state (failure retry backoff). */
    private long lastHizAllocMillis;
    private boolean lastHizAllocFailed;
    /** View state that produced the currently stored HiZ pyramid. */
    private final Matrix4f hizHistoryModelView = new Matrix4f();
    private final Matrix4f hizHistoryProjection = new Matrix4f();
    private double hizHistoryCamX;
    private double hizHistoryCamY;
    private double hizHistoryCamZ;
    private boolean hizHistoryValid;
    /** 30 fps floor: a frame-time EMA above this decays the request inflow. */
    private static final double FRAME_BUDGET_MS = 33.0;
    private static final float REQUEST_COOL_DOWN = 0.85f;
    private static final float REQUEST_RAMP_UP = 1.05f;
    private static final float MIN_REQUEST_SCALE = 0.05f;
    /** Frame-period EMA + hysteretic LOD request throttle (4c-2 帧时 EMA 自适应,
     *  ParticleFrameProfiler pattern): the request scale decays while the EMA
     *  sits above the 30 fps floor and ramps back when frames are healthy.
     *  It paces NEW requests only — the per-level in-flight cap is unchanged. */
    private double frameEmaMs;
    private boolean frameEmaInit;
    private float lodRequestScale = 1.0f;
    /** Render-thread terrain-slice EMA (pumpResults + gpuDraw) in ms — the
     *  4c-2 CPU<1ms acceptance instrument, printed by logGpuStats. */
    private double sliceEmaMs;
    private long lastStageNanos;

    private boolean initialized;
    private boolean warnedTier;
    private boolean warnedPack;
    private boolean warnedPatchFallback;
    private boolean warnedShadowEmpty;
    private boolean loggedShadowStart;
    /** Shader rebuild throttle (5 s) so a failing compile can't spin per frame. */
    private long lastShaderRebuildMillis;
    // iris integration (G2 draw mounting): the allay pipeline's patch data +
    // the pack-lit draw targets it resolves to
    private AllvrIrisPipelineData irisData;
    private AllvrIrisFrameTarget frameTarget;
    private int appliedCustomIdRevision = -1;
    private int loggedMeshResults;
    private long lastStatsLogMillis;
    /** Throttle for FAILED_* result logging (5 s, like the starve warn). */
    private long lastWorkerFailWarnMillis;
    /** Cubes currently holding a deferred (arena-starved) mesh stream. */
    private int deferredCount;
    private long lastStarveWarnMillis;
    /** Session epoch (plan §7.1): bumped on every level drop; mesh results
     *  stamped with an older epoch are discarded instead of resurrecting the
     *  previous session's geometry through the freshly cleared buffers. */
    private long epoch;

    /** Texture unit for the vanilla lightmap in the level-2 albedo pass
     *  (outside the HiZ/MC-depth compute units and the patch's sampler range). */
    private static final int LIGHTMAP_UNIT = 5;

    private static final class Cube {
        int slot = -1;
        int quadStart = -1;
        int quadCount = 0;
        long contentRevision;
        long scheduledRevision;
        long publishedRevision;
        int connectivityMask;
        /** Mesh result held when the quad arena couldn't fit it — retried by
         *  {@link #retryDeferred} once {@code AllvrBuffers#canFit} passes, so
         *  an exhausted arena defers instead of dropping the cube forever. */
        long[] deferredQuads;
        AllvrFallbackBlock[] fallbackBlocks = new AllvrFallbackBlock[0];
        AllvrFallbackBlock[] deferredFallbackBlocks;
        long deferredRevision;
    }

    // ------------------------------------------------------------------
    // frame
    // ------------------------------------------------------------------

    public void onRenderStage(RenderLevelStageEvent event) {
        // Sodium is the sole production near-terrain owner.  The legacy
        // renderer remains source-compatible only for a developer rollback
        // build and must never allocate, build, or draw in the normal path.
        if (com.iridium126.createmanaindustry.client.dimension.render.sodium.AllvrSodiumBridge.active()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || level.dimension() != AllvrDimensions.ALLAY_LEVEL) {
            return;
        }
        boolean packInUse = irisPackInUse();
        // iris integration context — resolved BEFORE the stage gate so the vx*
        // uniform suppliers stay current even on frames the terrain draw skips
        AllvrIrisPipelineData data = null;
        if (packInUse && ClientConfig.allvrIrisIntegration) {
            data = AllvrIrisDataHolder.current();
            if (data != null) {
                this.syncIrisData(data);
            }
        }
        if (data == null) {
            this.dropIrisState();
        }
        RenderLevelStageEvent.Stage stage = event.getStage();
        // mode decision (grilling decision ⑧ fallback chain):
        //  1. pack-lit (patched): voxy.json patch function resolved + compiled →
        //     AFTER_BLOCK_ENTITIES, inside the gbuffer phase before the pack's
        //     deferred passes sample the patch-written buffers (Photon).
        //     NOT AFTER_SOLID_BLOCKS — that stage dispatches from the tail of
        //     renderSectionLayer, which this mod HEAD-cancels in the allay
        //     dimension (vanilla terrain is ALLVR's job), so the event never
        //     fires there (G2 smoke ⑤)
        //  2. albedo pass: voxy.json resolves but ships NO patch function
        //     (Complementary) — vanilla-gbuffer-mimicking albedo into the pack's
        //     declared colortex, same stage; the pack's own deferred lighting
        //     shades our pixels like vanilla terrain
        //  3. unpatched coexistence (no voxy.json / compile failure / switch
        //     off): AFTER_LEVEL, self-lit after the composite blit (V0 behavior)
        //  4. no pack: AFTER_SKY (vanilla window, byte-identical V0)
        boolean patched = data != null && this.shaders.patchedTerrain() != 0;
        boolean albedo = !patched && data != null && this.shaders.albedoTerrain() != 0;
        RenderLevelStageEvent.Stage chosen = patched || albedo
            ? RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES
            : packInUse ? RenderLevelStageEvent.Stage.AFTER_LEVEL
            : RenderLevelStageEvent.Stage.AFTER_SKY;
        if (stage != chosen) {
            return;
        }
        if (data != null) {
            // Exactly once per rendered frame. Updating at every NeoForge
            // stage advanced PREVIOUS_* several times inside the same frame,
            // collapsing motion vectors to zero and leaving TAA history
            // smeared across the sky while the camera turned.
            AllvrVoxyUniforms.update(event.getModelViewMatrix(), event.getProjectionMatrix(),
                AllvrClientRenderDistance.blocks());
        }
        if (packInUse && !this.warnedPack) {
            this.warnedPack = true;
            chat(mc, data != null
                ? "[Allvr] shader pack active — allay terrain drawn into the pack's gbuffer (pack-lit)"
                : "[Allvr] shader pack active — drawing allay terrain post-composite "
                    + "(no pack lighting; V0 coexistence)");
        }
        if (!this.initialized) {
            this.initialize(mc);
        }
        this.recordFrameTime();
        boolean compat = this.tier == Tier.C;
        boolean programsReady = compat ? this.shaders.compatReady() : this.shaders.ready();
        // Tier C deliberately does not allocate the Tier B VAO/SSBO set.  Its
        // own VAO is the backend readiness gate; the shared material TBO is
        // created lazily by drawTerrain below.
        boolean backendReady = compat ? this.compat.ready() : this.buffers.ready();
        if (!programsReady || !backendReady) {
            // rebuild throttle: a failed compile sets needsRebuild again — retry
            // at most once per 5 s instead of compiling every frame
            if (this.shaders.needsRebuild() && backendReady
                && System.currentTimeMillis() - this.lastShaderRebuildMillis > 5000) {
                this.lastShaderRebuildMillis = System.currentTimeMillis();
                this.shaders.rebuild();
            }
            return;
        }

        if (compat) {
            // compat floor: CPU frustum cull + plain indexed batch drawing; no
            // compute, no HiZ, no shadow pass (plan §7.7 — Tier C guarantees
            // a visible, correct near terrain)
            long t0 = System.nanoTime();
            this.pumpResults();
            this.compatDraw(event, level, data);
            double ms = (System.nanoTime() - t0) / 1.0e6;
            this.sliceEmaMs = this.sliceEmaMs * 0.9 + ms * 0.1;
            return;
        }

        // Tier B main pass — GPU-driven since 4c-2. A failed compute compile
        // latches the session into Tier C instead of leaving terrain invisible
        // (F3+T rebuilds still re-try Tier B on the next session).
        if (this.shaders.gpuReady()) {
            long t0 = System.nanoTime();
            this.pumpResults();
            this.gpuDraw(event, level, data);
            // EMA over the whole terrain slice — mesh uploads legitimately
            // spike it during a fill; the <1 ms acceptance number is steady state
            double ms = (System.nanoTime() - t0) / 1.0e6;
            this.sliceEmaMs = this.sliceEmaMs * 0.9 + ms * 0.1;
        } else {
            this.latchTierC("GPU cull pipeline unavailable (compile failure?)");
        }
    }

    /**
     * Session failure latch (sodium-parity plan §7.7): a Tier B failure
     * switches the renderer to the compat backend for the rest of the
     * session. Meshes uploaded to the Tier B arena are stranded there, so the
     * compat store is rebuilt from the cube cache's key set — snapshots are
     * cheap and the remesh is bounded by the streamed radius.
     */
    private void latchTierC(String reason) {
        this.tier = Tier.C;
        this.renderCubes.clear();
        this.pending.clear();
        this.deferredResults.clear();
        this.deferredCount = 0;
        this.nodes.clear();
        this.buffers.invalidateNodeUpload();
        this.sawFirstMesh.clear();
        this.compat.reset();
        if (!this.warnedComputeFail) {
            this.warnedComputeFail = true;
            CreateManaIndustry.LOGGER.error(
                "[Allvr] {} — session latched to the Tier C compat backend", reason);
            chat(Minecraft.getInstance(), "[Allvr] allay terrain fell back to the compat "
                + "renderer for this session (" + reason + ")");
        }
        for (long key : AllvrClientCubeCache.cubeKeys()) {
            this.onCubeApplied(key);
        }
    }

    /** Visible compat draw entries built for the current frame (0 = skip). */
    private int compatEntries;

    /**
     * Tier C draw: CPU frustum cull over the cube set (the exact planes the
     * Tier B traversal consumes) → plain glMultiDrawElements batch via the
     * compat backend's expanded-vertex VAO. No HiZ, no shadow pass, no MDIC —
     * the compat tier's contract is a correct, visible near terrain.
     */
    private void compatDraw(RenderLevelStageEvent event, ClientLevel level, AllvrIrisPipelineData data) {
        Minecraft mc = Minecraft.getInstance();
        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();

        this.extractFrustum(event.getProjectionMatrix(), event.getModelViewMatrix(), camPos);
        this.compatEntries = this.buildCompatCommands(camPos);
        try {
            this.drawTerrain(event, level, camPos, data);
        } finally {
            GL20.glUseProgram(0);
            this.compat.unbind();
            this.buffers.unbindStateTable();
            RenderSystem.depthMask(true);
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
        }
        this.logGpuStats(this.renderCubes.size(), this.renderCubes.size(), -1, -1,
            this.compatEntries, " | compat (Tier C)");
    }

    /**
     * CPU frustum pass for the compat backend: the same Gribb–Hartmann plane
     * test the GPU traversal runs, per cell AABB (16³ local extent), appended
     * into the compat draw list.
     */
    private int buildCompatCommands(Vec3 camPos) {
        int camX = net.minecraft.util.Mth.floor(camPos.x);
        int camY = net.minecraft.util.Mth.floor(camPos.y);
        int camZ = net.minecraft.util.Mth.floor(camPos.z);
        int n = 0;
        for (var e : this.renderCubes.long2ObjectEntrySet()) {
            Cube rc = e.getValue();
            if (rc.quadCount <= 0 || rc.quadStart < 0) {
                continue;
            }
            float ox = AllvrRenderCellKey.minBlockX(e.getLongKey()) - camX;
            float oy = AllvrRenderCellKey.minBlockY(e.getLongKey()) - camY;
            float oz = AllvrRenderCellKey.minBlockZ(e.getLongKey()) - camZ;
            boolean ok = true;
            for (int i = 0; i < 6 && ok; i++) {
                float nx = this.frustumPlanes[i * 4];
                float ny = this.frustumPlanes[i * 4 + 1];
                float nz = this.frustumPlanes[i * 4 + 2];
                float d = this.frustumPlanes[i * 4 + 3];
                float px = nx > 0f ? ox + 16f : ox;
                float py = ny > 0f ? oy + 16f : oy;
                float pz = nz > 0f ? oz + 16f : oz;
                if (nx * px + ny * py + nz * pz + d < 0f) {
                    ok = false;
                }
            }
            if (ok) {
                n = this.compat.appendDrawEntry(rc.quadStart, rc.quadCount, n);
            }
        }
        return n;
    }

    /**
     * Frame-period EMA + the LOD request throttle (4c-2). Measured between
     * consecutive chosen-stage events — a full frame period while the level
     * renders; outliers (menus, stalls >250 ms) are dropped. The hysteretic
     * controller mirrors {@code ParticleFrameProfiler}: above the 30 fps
     * floor the LOD request scale decays, well under it the inflow ramps back.
     */
    private void recordFrameTime() {
        long now = System.nanoTime();
        if (this.lastStageNanos != 0) {
            double ms = (now - this.lastStageNanos) / 1.0e6;
            if (ms >= 1.0 && ms <= 250.0) {
                this.frameEmaMs = this.frameEmaInit ? this.frameEmaMs * 0.9 + ms * 0.1 : ms;
                this.frameEmaInit = true;
                if (this.frameEmaMs > FRAME_BUDGET_MS) {
                    this.lodRequestScale *= REQUEST_COOL_DOWN;
                } else if (this.frameEmaMs < FRAME_BUDGET_MS * 0.5) {
                    this.lodRequestScale = Math.min(1.0f, this.lodRequestScale * REQUEST_RAMP_UP);
                }
                this.lodRequestScale = Math.max(MIN_REQUEST_SCALE, Math.min(1.0f, this.lodRequestScale));
            }
        }
        this.lastStageNanos = now;
    }

    /** Current LOD request scale from the frame-time throttle (0.05..1). */
    public float lodRequestScale() {
        return this.lodRequestScale;
    }

    /**
     * Keeps the iris-integration runtime state in step with the pipeline data:
     * the patched program (identity-keyed, rebuilt after an F3+T), the pack-lit
     * frame target (one per data — the old one's GL objects are released) and
     * the customId column of the state table (full re-resolve on pack change,
     * revision-driven re-upload for late-registered states).
     */
    private void syncIrisData(AllvrIrisPipelineData data) {
        this.shaders.syncPatchedTerrain(data);
        if (this.shaders.patchedTerrain() == 0) {
            // no patch function in the pack's voxy.json (or it failed to
            // compile) — the level-2 albedo pass takes over
            this.shaders.syncAlbedoTerrain(data);
        }
        if (data == this.irisData) {
            if (AllvrRenderStateMap.customIdRevision() != this.appliedCustomIdRevision) {
                this.appliedCustomIdRevision = AllvrRenderStateMap.setCustomIds(data.getCustomIds());
                this.buffers.invalidateStateTable();
            }
            return;
        }
        if (this.frameTarget != null) {
            this.frameTarget.destroy();
            this.frameTarget = null;
        }
        this.irisData = data;
        this.frameTarget = new AllvrIrisFrameTarget(data, AllvrIrisDataHolder.depthSupplier());
        data.setDepthTextures(this.frameTarget::depthTexture, this.frameTarget::depthTexture);
        this.appliedCustomIdRevision = AllvrRenderStateMap.setCustomIds(data.getCustomIds());
        this.buffers.invalidateStateTable();
    }

    /**
     * Tears the iris-integration runtime state down when no pipeline data is
     * current. Three trigger paths reach it through the {@code data == null}
     * branch in {@link #onRenderStage}: a pack reload to a voxy-less pack
     * (the holder publishes null), a disabled shader pack, and the
     * integration switch turned off mid-session. Without this, the previously
     * built {@link AllvrIrisFrameTarget} (two GL FBOs + the uniform UBO + a
     * strong reference to the dead pipeline object) and the identity-keyed
     * patched/albedo programs linger until some later patch-bearing pack
     * happens to replace them. Everything rebuilds from scratch on the next
     * non-null data (a returning pack rebuilds its pipeline → new identity →
     * full setup in {@link #syncIrisData}), so the early tear-down costs
     * nothing. No-op when nothing is held (the common case — every stage
     * event runs this check).
     */
    private void dropIrisState() {
        if (this.irisData == null) {
            return;
        }
        if (this.frameTarget != null) {
            this.frameTarget.destroy();
            this.frameTarget = null;
        }
        this.irisData = null;
        this.shaders.syncPatchedTerrain(null);
        this.shaders.syncAlbedoTerrain(null);
        this.appliedCustomIdRevision = -1;
    }

    private void initialize(Minecraft mc) {
        this.renderWorld.bind(mc.level);
        var caps = org.lwjgl.opengl.GL.getCapabilities();
        // 4c-2 merged gate: per-command draw parameters (gl_BaseInstance in the
        // vertex path) AND the MDIC draw count must both be present — GL 4.6
        // core carries both; on 4.5 they arrive as two ARB extensions. Below
        // the merged gate the compat Tier C floor takes over (plan §7.7):
        // near terrain stays visible through the CPU-drawn compat backend.
        this.capsOk = caps.OpenGL46
            || (caps.GL_ARB_shader_draw_parameters && caps.GL_ARB_indirect_parameters);
        AllvrShaderCache.setUseExtensionFallback(!caps.OpenGL46 && caps.GL_ARB_shader_draw_parameters);
        this.coreGl46 = caps.OpenGL46;
        this.tier = this.capsOk ? Tier.B : Tier.C;
        CreateManaIndustry.LOGGER.info(
            "[Allvr] caps probe: OpenGL46={} ARB_shader_draw_parameters={} ARB_indirect_parameters={} → {}",
            caps.OpenGL46, caps.GL_ARB_shader_draw_parameters, caps.GL_ARB_indirect_parameters,
            this.capsOk ? "terrain ok (Tier B)" : "Tier C compat backend (near terrain stays visible)");
        // Tier C owns only its compact VAO/VBO. Tier B resources are optional
        // and are created transactionally; a shader/SSBO allocation failure
        // must not strand near rendering behind a half-initialized B backend.
        if (this.tier == Tier.B) {
            try {
                this.buffers.ensure();
            } catch (Throwable failure) {
                CreateManaIndustry.LOGGER.error("[Allvr] Tier B resource initialization failed; using Tier C",
                    failure);
                this.tier = Tier.C;
                this.capsOk = false;
                this.buffers.destroy();
            }
        }
        this.compat.ensure();
        AllvrMesherWorker.start();
        this.initialized = true;
        if (!this.capsOk && !this.warnedTier) {
            this.warnedTier = true;
            chat(mc, "[Allvr] GL 4.6 / ARB draw-parameters+indirect-parameters unavailable — "
                + "allay terrain drawn through the Tier C compat backend");
        }
    }

    // ------------------------------------------------------------------
    // cube lifecycle (main thread)
    // ------------------------------------------------------------------

    public void onCubeApplied(long key) {
        if (com.iridium126.createmanaindustry.client.dimension.render.sodium.AllvrSodiumBridge.active()) {
            return;
        }
        if (!this.initialized) {
            // main thread === render thread: GL context is live, so the lazy
            // init is safe here too (cube packets can arrive before the first
            // rendered frame)
            this.initialize(Minecraft.getInstance());
        }
        this.renderWorld.bind(Minecraft.getInstance().level);
        AllvrCubePos cube = AllvrCubePos.fromLong(key);
        for (int ly = 0; ly < 2; ly++) {
            for (int lz = 0; lz < 2; lz++) {
                for (int lx = 0; lx < 2; lx++) {
                    this.applyCell(AllvrRenderCellKey.ofCell((cube.getX() << 1) + lx,
                        (cube.getY() << 1) + ly, (cube.getZ() << 1) + lz));
                }
            }
        }
    }

    private void applyCell(long key) {
        Cube rc = this.renderCubes.get(key);
        if (rc == null) {
            rc = new Cube();
            if (this.tier == Tier.B) {
                rc.slot = this.buffers.allocSlot(AllvrRenderCellKey.minBlockX(key),
                    AllvrRenderCellKey.minBlockY(key), AllvrRenderCellKey.minBlockZ(key));
            }
            this.renderCubes.put(key, rc);
        }
        rc.contentRevision = this.renderWorld.markCellDirty(key);
        this.submit(key, AllvrBuildScheduler.Priority.NEAR_CAMERA);
    }

    /** Drops all eight cell meshes owned by one streamed 32³ cube. */
    public void onCubeForgotten(long key) {
        if (com.iridium126.createmanaindustry.client.dimension.render.sodium.AllvrSodiumBridge.active()) {
            return;
        }
        AllvrCubePos cube = AllvrCubePos.fromLong(key);
        for (int ly = 0; ly < 2; ly++) {
            for (int lz = 0; lz < 2; lz++) {
                for (int lx = 0; lx < 2; lx++) {
                    this.forgetCell(AllvrRenderCellKey.ofCell((cube.getX() << 1) + lx,
                        (cube.getY() << 1) + ly, (cube.getZ() << 1) + lz));
                }
            }
        }
    }

    private void forgetCell(long key) {
        Cube rc = this.renderCubes.remove(key);
        this.renderWorld.forgetCell(key);
        if (rc == null) {
            return;
        }
        AllvrMesherWorker.cancel(key); // queued jobs for a dead cube settle as CANCELLED
        if (rc.deferredQuads != null) {
            this.deferredCount--;
        }
        if (rc.quadStart >= 0) {
            if (this.tier == Tier.C) {
                this.compat.freeRange(rc.quadStart, rc.quadCount);
            } else {
                this.buffers.freeRange(rc.quadStart, rc.quadCount);
            }
        }
        this.buffers.freeSlot(rc.slot);
        this.nodes.freeNode(key);
        this.pending.remove(key);
        this.sawFirstMesh.remove(key);
        AllvrRenderCellKey.release(key);
    }

    /**
     * Client-side block change: remesh one 16³ cell and only the border
     * neighbours whose face visibility changed.  The 32³ cube remains the
     * network/cache unit, but a normal edit now rebuilds one cell instead of
     * all eight cells.
     */
    public void onBlockChanged(net.minecraft.core.BlockPos pos, BlockState oldState, BlockState newState) {
        if (com.iridium126.createmanaindustry.client.dimension.render.sodium.AllvrSodiumBridge.active()) {
            return;
        }
        int cellX = pos.getX() >> 4;
        int cellY = pos.getY() >> 4;
        int cellZ = pos.getZ() >> 4;
        long key = AllvrRenderCellKey.ofCell(cellX, cellY, cellZ);
        Cube rc = this.renderCubes.get(key);
        if (rc == null) {
            return;
        }
        rc.contentRevision = this.renderWorld.markCellDirty(key);
        this.submit(key, AllvrBuildScheduler.Priority.VISIBLE_UPDATE);
        // A change at a cell border can invalidate the neighbour's culled
        // faces across the seam — remesh only the directly touched neighbours.
        int lx = pos.getX() & 15;
        int ly = pos.getY() & 15;
        int lz = pos.getZ() & 15;
        if (lx == 0) {
            this.dirtyCell(cellX - 1, cellY, cellZ, AllvrBuildScheduler.Priority.SEAM_HANDOFF);
        } else if (lx == 15) {
            this.dirtyCell(cellX + 1, cellY, cellZ, AllvrBuildScheduler.Priority.SEAM_HANDOFF);
        }
        if (ly == 0) {
            this.dirtyCell(cellX, cellY - 1, cellZ, AllvrBuildScheduler.Priority.SEAM_HANDOFF);
        } else if (ly == 15) {
            this.dirtyCell(cellX, cellY + 1, cellZ, AllvrBuildScheduler.Priority.SEAM_HANDOFF);
        }
        if (lz == 0) {
            this.dirtyCell(cellX, cellY, cellZ - 1, AllvrBuildScheduler.Priority.SEAM_HANDOFF);
        } else if (lz == 15) {
            this.dirtyCell(cellX, cellY, cellZ + 1, AllvrBuildScheduler.Priority.SEAM_HANDOFF);
        }
        // Light dirt is cell-granular.  A 128-block sky window spans eight
        // cells; block emitters can reach the adjacent cell ring.
        if (oldState != null && newState != null) {
            if (AllvrMesher.occludesAt(oldState) != AllvrMesher.occludesAt(newState)) {
                for (int k = 1; k <= AllvrLightBaker.SKY_WINDOW_BLOCKS >> 4; k++) {
                    this.dirtyCell(cellX, cellY - k, cellZ, AllvrBuildScheduler.Priority.VISIBLE_UPDATE);
                }
            }
            if (oldState.getLightEmission() != newState.getLightEmission()) {
                int minX = lx <= 14 ? -1 : 0;
                int maxX = lx >= 1 ? 1 : 0;
                int minY = ly <= 14 ? -1 : 0;
                int maxY = ly >= 1 ? 1 : 0;
                int minZ = lz <= 14 ? -1 : 0;
                int maxZ = lz >= 1 ? 1 : 0;
                for (int dx = minX; dx <= maxX; dx++) {
                    for (int dy = minY; dy <= maxY; dy++) {
                        for (int dz = minZ; dz <= maxZ; dz++) {
                            if ((dx | dy | dz) != 0) {
                                this.dirtyCell(cellX + dx, cellY + dy, cellZ + dz,
                                    AllvrBuildScheduler.Priority.VISIBLE_UPDATE);
                            }
                        }
                    }
                }
            }
        }
    }

    public void dropLevel() {
        this.epoch++; // in-flight results from the old session are discarded
        this.renderWorld.beginSession();
        this.renderCubes.clear();
        this.pending.clear();
        this.deferredResults.clear();
        this.deferredCount = 0;
        this.buffers.reset();
        this.nodes.clear();
        this.compat.reset();
        // zero the GPU node buffer on the next sync — nodes.clear() alone
        // leaves the old session's nodes alive in it (capacity unchanged →
        // the dirty-set upload is a no-op) and the traversal's over-dispatch
        // tail draws them as ghost cubes with stale arena offsets
        this.buffers.invalidateNodeUpload();
        this.hizHistoryValid = false;
        // frame-time throttle restarts cleanly per session
        this.lastStageNanos = 0;
        this.frameEmaMs = 0;
        this.frameEmaInit = false;
        this.lodRequestScale = 1.0f;
        this.sliceEmaMs = 0;
        AllvrMesherWorker.clearQueues();
    }

    public void requestShaderRebuild() {
        this.shaders.requestRebuild();
    }

    /**
     * Invalidates the immutable render-resource snapshot on F3+T.  Existing
     * GPU meshes remain drawable until their replacement is uploaded, while
     * every resident cell is scheduled against the new resource revision.
     */
    public void onResourceReload() {
        if (com.iridium126.createmanaindustry.client.dimension.render.sodium.AllvrSodiumBridge.active()) {
            return;
        }
        this.renderWorld.bumpResourceRevision();
        AllvrRenderStateMap.invalidateResources();
        AllvrClientCubeCache.prepareRenderResources();
        this.buffers.invalidateStateTable();
        for (var entry : this.renderCubes.long2ObjectEntrySet()) {
            Cube cell = entry.getValue();
            cell.contentRevision = this.renderWorld.markCellDirty(entry.getLongKey());
            this.submit(entry.getLongKey(), AllvrBuildScheduler.Priority.VISIBLE_UPDATE);
        }
        this.requestShaderRebuild();
    }

    /**
     * Full GL close (sodium-parity plan §7.1: GL objects are closed explicitly
     * by the session's single owner). Called from the game-shutdown event —
     * the renderer singleton holds no GL object across a context change, and
     * every owned object here is deleted exactly once.
     */
    public void close() {
        this.buffers.destroy();
        this.compat.destroy();
        this.shaders.destroy();
        this.renderWorld.close();
        this.renderCubes.clear();
        this.pending.clear();
        this.deferredResults.clear();
        this.nodes.clear();
        this.initialized = false;
        AllvrMesherWorker.stop();
    }

    private void submit(long key) {
        this.submit(key, AllvrBuildScheduler.Priority.BACKGROUND);
    }

    private void submit(long key, AllvrBuildScheduler.Priority priority) {
        if (!this.pending.add(key)) {
            return;
        }
        if (AllvrMesherWorker.threadOrNull() == null) {
            AllvrMesherWorker.start();
        }
        Cube rc = this.renderCubes.get(key);
        if (rc == null) {
            this.pending.remove(key);
            return;
        }
        rc.scheduledRevision = rc.contentRevision;
        AllvrRenderCell cell = this.renderWorld.cell(key);
        if (cell != null) {
            cell.schedule(rc.scheduledRevision);
            cell.begin(rc.scheduledRevision);
        }
        AllvrRenderCell ticketCell = this.renderWorld.cell(key);
        AllvrMesherWorker.submit(key, this.epoch,
            ticketCell == null ? 0L : ticketCell.incarnation(),
            this.renderWorld.resourceRevision(), rc.scheduledRevision, priority);
    }

    /** Marks a cell for remesh when it already has visible geometry. */
    private void dirtyCell(int cx, int cy, int cz, AllvrBuildScheduler.Priority priority) {
        long nkey = AllvrRenderCellKey.ofCell(cx, cy, cz);
        Cube rc = this.renderCubes.get(nkey);
        if (rc != null && hasGeometry(rc)) {
            rc.contentRevision = this.renderWorld.markCellDirty(nkey);
            this.submit(nkey, priority);
        }
    }

    private static boolean hasGeometry(Cube rc) {
        return rc.quadCount > 0 || rc.fallbackBlocks.length > 0 || rc.deferredFallbackBlocks != null;
    }

    // ------------------------------------------------------------------
    // results + draw (render thread)
    // ------------------------------------------------------------------

    private void pumpResults() {
        long deadline = System.nanoTime() + 2_000_000L;
        long uploadedBytes = 0L;
        final long uploadBudgetBytes = 4L * 1024L * 1024L;
        AllvrMesherWorker.MeshResult result;
        while (System.nanoTime() < deadline && uploadedBytes < uploadBudgetBytes
            && (result = this.deferredResults.isEmpty() ? AllvrMesherWorker.poll() : this.deferredResults.pollFirst()) != null) {
            long resultBytes = result.quads() == null ? 0L : (long) result.quads().length * Long.BYTES;
            if (uploadedBytes > 0 && uploadedBytes + resultBytes > uploadBudgetBytes) {
                this.deferredResults.addFirst(result);
                break;
            }
            uploadedBytes += resultBytes;
            long key = result.key();
            if (result.epoch() != this.epoch) {
                continue; // stale session (level switched) — never republish
            }
            Cube rc = this.renderCubes.get(key);
            AllvrRenderCell cell = this.renderWorld.cell(key);
            if (rc == null || cell == null) {
                continue;
            }
            if (result.incarnation() != cell.incarnation()) {
                continue; // old coordinate incarnation or material snapshot
            }
            if (result.resourceRevision() != 0
                && result.resourceRevision() != this.renderWorld.resourceRevision()) {
                this.pending.remove(key);
                this.submit(key, AllvrBuildScheduler.Priority.VISIBLE_UPDATE);
                continue;
            }
            if (result.revision() != 0 && result.revision() != cell.contentRevision()) {
                // The result is valid work, but no longer current.  Settle it
                // and immediately schedule the newest revision; never publish
                // stale geometry just because it completed first.
                this.pending.remove(key);
                this.submit(key, AllvrBuildScheduler.Priority.VISIBLE_UPDATE);
                continue;
            }
            this.pending.remove(key); // settle only the matching current ticket
            switch (result.status()) {
                case SUCCESS -> {
                    rc.connectivityMask = result.connectivityMask();
                    this.applySuccess(key, rc, result.quads(), result.fallbackBlocks(), result.revision());
                }
                case CANCELLED, FAILED_RETRYABLE, FAILED_FATAL -> this.applyFailure(key, rc, result);
            }
        }
        this.retryDeferred();
    }

    /**
     * Publishes one successful mesh result with the plan §7.2 atomic-swap
     * discipline: the new stream is allocated FIRST and published, and only
     * then is the old arena range freed — the old mesh keeps drawing until
     * the new one takes over, so a failed allocation can never leave an
     * update hole (the old "free first, then try to fit" order did).
     */
    private void applySuccess(long key, Cube rc, long[] quads, AllvrFallbackBlock[] fallbackBlocks,
                              long revision) {
        if (fallbackBlocks == null) {
            fallbackBlocks = new AllvrFallbackBlock[0];
        }
        if (quads.length > 0) {
            if (this.loggedMeshResults < 3) {
                this.loggedMeshResults++;
                CreateManaIndustry.LOGGER.info("[Allvr] mesh result #{}: cell {} → {} quads",
                    this.loggedMeshResults, AllvrRenderCellKey.describe(key), quads.length);
            }
            int start = this.tier == Tier.C
                ? this.compat.allocRange(quads.length)
                : this.buffers.allocRange(quads.length);
            if (start < 0) {
                // arena full: the OLD mesh stays visible (never unpublished) and
                // the new stream defers to retryDeferred; a newer stream arriving
                // while deferred simply replaces this one (latest wins)
                if (rc.deferredQuads == null) {
                    this.deferredCount++;
                }
                rc.deferredQuads = quads;
                rc.deferredFallbackBlocks = fallbackBlocks;
                rc.deferredRevision = revision;
                long now = System.currentTimeMillis();
                if (now - this.lastStarveWarnMillis > 5000) {
                    this.lastStarveWarnMillis = now;
                    CreateManaIndustry.LOGGER.warn("[Allvr] quad arena full — {} cube(s) deferred",
                        this.deferredCount);
                }
                return;
            }
            int oldStart = rc.quadStart;
            int oldCount = rc.quadCount;
            rc.quadStart = -1;
            rc.quadCount = 0;
            if (rc.deferredQuads != null) {
                // a fresher stream just landed — the stale one is superseded
                rc.deferredQuads = null;
                rc.deferredFallbackBlocks = null;
                rc.deferredRevision = 0L;
                this.deferredCount--;
            }
            this.assignQuads(key, rc, start, quads);
            rc.fallbackBlocks = fallbackBlocks;
            if (oldStart >= 0) {
                // freed AFTER the new publication — the swap is atomic per frame
                if (this.tier == Tier.C) {
                    this.compat.freeRange(oldStart, oldCount);
                } else {
                    this.buffers.freeRange(oldStart, oldCount);
                }
            }
        } else if (fallbackBlocks.length == 0) {
            // empty mesh: publish nothing and drop the old publication —
            // the range freed here is never re-referenced (see unpublishNodeMesh)
            int oldStart = rc.quadStart;
            int oldCount = rc.quadCount;
            rc.quadStart = -1;
            rc.quadCount = 0;
            if (rc.deferredQuads != null) {
                rc.deferredQuads = null;
                rc.deferredFallbackBlocks = null;
                rc.deferredRevision = 0L;
                this.deferredCount--;
            }
            rc.fallbackBlocks = new AllvrFallbackBlock[0];
            this.unpublishNodeMesh(key);
            AllvrRenderCell cell = this.renderWorld.cell(key);
            if (cell != null) {
                cell.clearAllocation();
            }
            if (oldStart >= 0) {
                if (this.tier == Tier.C) {
                    this.compat.freeRange(oldStart, oldCount);
                } else {
                    this.buffers.freeRange(oldStart, oldCount);
                }
            }
        } else {
            // A cell containing only generic geometry has no descriptor range,
            // but it is still a successful publication and must replace any
            // previous descriptor mesh atomically.
            int oldStart = rc.quadStart;
            int oldCount = rc.quadCount;
            if (rc.deferredQuads != null) {
                rc.deferredQuads = null;
                rc.deferredFallbackBlocks = null;
                rc.deferredRevision = 0L;
                this.deferredCount--;
            }
            rc.quadStart = -1;
            rc.quadCount = 0;
            this.unpublishNodeMesh(key);
            rc.fallbackBlocks = fallbackBlocks;
            rc.publishedRevision = rc.contentRevision;
            AllvrRenderCell cell = this.renderWorld.cell(key);
            if (cell != null) {
                cell.clearAllocation();
                cell.publish(rc.contentRevision, -1, 0);
            }
            this.markFirstMeshAndNeighbors(key, rc);
            if (oldStart >= 0) {
                if (this.tier == Tier.C) {
                    this.compat.freeRange(oldStart, oldCount);
                } else {
                    this.buffers.freeRange(oldStart, oldCount);
                }
            }
        }
    }

    /**
     * A CANCELLED or FAILED_* result keeps the cube's previous mesh published —
     * the plan §7.2 atomic-swap rule ("keep the old mesh until the new upload
     * succeeds") applies to failures too: a stale-but-valid mesh beats an
     * update hole, and the next edit to the cube re-triggers the remesh. Only
     * the pure-empty SUCCESS result drops a publication (the new truth is
     * "no geometry"). Logs throttled; no auto-retry — a deterministic failure
     * would loop forever (plan §7.1).
     */
    private void applyFailure(long key, Cube rc, AllvrMesherWorker.MeshResult result) {
        AllvrRenderCell cell = this.renderWorld.cell(key);
        if (cell != null) {
            cell.fail(result.revision());
        }
        long now = System.currentTimeMillis();
        if (now - this.lastWorkerFailWarnMillis > 5000) {
            this.lastWorkerFailWarnMillis = now;
            CreateManaIndustry.LOGGER.error("[Allvr] mesher job for cell {} settled as {} — previous mesh kept, "
                + "retried on the cell's next edit", AllvrRenderCellKey.describe(key), result.status(), result.failure());
        }
    }

    private void assignQuads(long key, Cube rc, int start, long[] quads) {
        if (this.tier == Tier.C) {
            // compat: vertices expand into the compat VBO (origin included);
            // no GPU arena upload, no node publication
            this.compat.publish(key, start, quads);
        } else {
            this.buffers.uploadQuads(start, quads);
            // GPU-cull path: publish the mesh into the node SSBO. Slotless cubes
            // (cubeInfo table full) stay nodeless — the draw skips them too.
            if (rc.slot >= 0) {
                int cx = AllvrRenderCellKey.minBlockX(key);
                int cy = AllvrRenderCellKey.minBlockY(key);
                int cz = AllvrRenderCellKey.minBlockZ(key);
                this.nodes.setMesh(key,
                    new net.minecraft.core.BlockPos(cx, cy, cz),
                    start, quads.length, rc.slot);
            }
        }
        rc.quadStart = start;
        rc.quadCount = quads.length;
        rc.publishedRevision = rc.contentRevision;
        AllvrRenderCell cell = this.renderWorld.cell(key);
        if (cell != null) {
            cell.publish(rc.contentRevision, start, quads.length);
        }
        // first mesh: neighbors meshed earlier may have culled faces
        // against this cube while it was still void air
        if (hasGeometry(rc) && !this.sawFirstMesh.contains(key)) {
            this.markFirstMeshAndNeighbors(key, rc);
        }
    }

    private void markFirstMeshAndNeighbors(long key, Cube rc) {
        if (!hasGeometry(rc) || this.sawFirstMesh.contains(key)) {
            return;
        }
        this.sawFirstMesh.add(key);
        this.dirtyAllNeighbors(key);
    }

    /**
     * Drops the cube's node publication (GPU-cull path) after its arena range
     * was freed. Without this, the node keeps HAS_MESH + stale quadStart/
     * quadCount pointing at freed — and possibly already-reused — quad memory,
     * which the traversal then draws as ghost geometry at this cube's origin:
     * a GPU-path-only artifact (the V0 draw skips the cube via quadCount == 0),
     * same class as the 4b dropLevel ghost fix. Reached by two pumpResults
     * branches: an empty mesh result, and an arena-starved (deferred) remesh.
     * {@code freeNode} is a no-op for cubes that never published a node (first
     * mesh already empty, or the cubeInfo table was full); a later
     * {@link #assignQuads} re-allocates the node via {@code setMesh}.
     */
    private void unpublishNodeMesh(long key) {
        if (this.tier == Tier.B) {
            this.nodes.freeNode(key);
        } // Tier C: nothing is published — the freed VBO range is the whole state
    }

    /** Uploads deferred mesh results once the arena can actually fit them,
     *  keeping the §7.2 atomic-swap order: publish the new stream first, free
     *  the old range after — the cube never draws through freed memory and
     *  never shows an update hole. {@code canFit} mirrors {@code allocRange}'s
     *  success test, so a fragmented arena waits for real contiguous space
     *  instead of spin-remeshing the same cube every frame. */
    private void retryDeferred() {
        if (this.deferredCount == 0) {
            return;
        }
        Iterator<Long2ObjectOpenHashMap.Entry<Cube>> it = this.renderCubes.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            Long2ObjectOpenHashMap.Entry<Cube> e = it.next();
            Cube rc = e.getValue();
            if (rc.deferredQuads == null) {
                continue;
            }
            if (rc.deferredRevision != 0L && rc.deferredRevision != rc.contentRevision) {
                rc.deferredQuads = null;
                rc.deferredFallbackBlocks = null;
                rc.deferredRevision = 0L;
                this.deferredCount--;
                this.submit(e.getLongKey(), AllvrBuildScheduler.Priority.VISIBLE_UPDATE);
                continue;
            }
            boolean fits = this.tier == Tier.C
                ? this.compat.canFit(rc.deferredQuads.length)
                : this.buffers.canFit(rc.deferredQuads.length);
            if (!fits) {
                continue;
            }
            int start = this.tier == Tier.C
                ? this.compat.allocRange(rc.deferredQuads.length)
                : this.buffers.allocRange(rc.deferredQuads.length);
            if (start < 0) {
                continue; // arena changed between canFit and alloc — retry next frame
            }
            long[] quads = rc.deferredQuads;
            rc.deferredQuads = null;
            AllvrFallbackBlock[] fallbackBlocks = rc.deferredFallbackBlocks;
            rc.deferredFallbackBlocks = null;
            rc.deferredRevision = 0L;
            this.deferredCount--;
            int oldStart = rc.quadStart;
            int oldCount = rc.quadCount;
            rc.quadStart = -1;
            rc.quadCount = 0;
            this.assignQuads(e.getLongKey(), rc, start, quads);
            rc.fallbackBlocks = fallbackBlocks == null ? new AllvrFallbackBlock[0] : fallbackBlocks;
            if (oldStart >= 0) {
                if (this.tier == Tier.C) {
                    this.compat.freeRange(oldStart, oldCount);
                } else {
                    this.buffers.freeRange(oldStart, oldCount);
                }
            }
        }
    }

    private final LongOpenHashSet sawFirstMesh = new LongOpenHashSet();

    private void dirtyAllNeighbors(long key) {
        int cellX = AllvrRenderCellKey.cellX(key);
        int cellY = AllvrRenderCellKey.cellY(key);
        int cellZ = AllvrRenderCellKey.cellZ(key);
        for (int axis = 0; axis < 3; axis++) {
            for (int dir = -1; dir <= 1; dir += 2) {
                int x = cellX + (axis == 0 ? dir : 0);
                int y = cellY + (axis == 1 ? dir : 0);
                int z = cellZ + (axis == 2 ? dir : 0);
                long nkey = AllvrRenderCellKey.ofCell(x, y, z);
                Cube rc = this.renderCubes.get(nkey);
                if (rc != null && hasGeometry(rc)) {
                    rc.contentRevision = this.renderWorld.markCellDirty(nkey);
                    this.submit(nkey, AllvrBuildScheduler.Priority.SEAM_HANDOFF);
                }
            }
        }
    }

    /**
     * Appends the MDI commands for one cube into {@link #commands} (respecting
     * the shared-index {@code MAX_QUADS_PER_COMMAND} split — craftable cubes
     * beyond it, e.g. checkerboard ~5×10⁴ quads, continue in consecutive
     * commands); returns the new command count, capped at {@code MAX_COMMANDS}.
     */
    private int appendCommands(Cube rc, int n) {
        int remaining = rc.quadCount;
        int baseQuad = rc.quadStart;
        while (remaining > 0 && n < AllvrBuffers.MAX_COMMANDS) {
            int take = Math.min(remaining, AllvrBuffers.MAX_QUADS_PER_COMMAND);
            int o = n * AllvrBuffers.COMMAND_STRIDE;
            this.commands[o] = take * 6;
            this.commands[o + 1] = 1;
            this.commands[o + 2] = 0;
            this.commands[o + 3] = baseQuad * 4;
            this.commands[o + 4] = rc.slot;
            baseQuad += take;
            remaining -= take;
            n++;
        }
        return n;
    }

    /**
     * Shadow-pass command source (doc §13 4i 排查⑦): the main pass's commands
     * are culled by the PLAYER's view frustum, but the shadow map must contain
     * every cube inside the light's ortho box regardless of where the player
     * looks — reusing the view-culled commands made terrain shadows appear,
     * disappear and shift with view rotation. Culls against the shadow box
     * instead: cube bounding spheres (r = 16√3 + margin) versus |x|,|y| ≤
     * halfPlaneLength and the z window, evaluated in double precision. The
     * centers go through the shadow modelview as PLAYER-space points (world −
     * camera, the same input space the shadow program's vertices use) —
     * feeding absolute centers (排查⑨) put |Y| ≈ 9.7k through a rotation into
     * a ±shadowDistance box and rejected everything. Non-ortho (legacy
     * perspective shadow) projections skip culling.
     */
    private int buildShadowCommands(Matrix4f shadowModelView, Matrix4f shadowProjection, Vec3 camPos) {
        boolean ortho = shadowProjection.m33() == 1.0f;
        Matrix4d view = new Matrix4d(shadowModelView);
        double limXY = 0;
        double zMin = 0;
        double zMax = 0;
        if (ortho) {
            double radius = 8.0 * Math.sqrt(3.0) + 4.0;
            limXY = 1.0 / Math.abs(shadowProjection.m00()) + radius;
            float m22 = shadowProjection.m22();
            float m32 = shadowProjection.m32();
            double zA = (-1.0 - m32) / m22;
            double zB = (1.0 - m32) / m22;
            zMin = Math.min(zA, zB) - radius;
            zMax = Math.max(zA, zB) + radius;
        }
        Vector3d center = new Vector3d();
        int n = 0;
        Iterator<Long2ObjectOpenHashMap.Entry<Cube>> it = this.renderCubes.long2ObjectEntrySet().fastIterator();
        while (it.hasNext() && n < AllvrBuffers.MAX_COMMANDS) {
            Long2ObjectOpenHashMap.Entry<Cube> e = it.next();
            Cube rc = e.getValue();
            if (rc.quadCount <= 0 || rc.slot < 0) {
                continue;
            }
            if (ortho) {
                view.transformPosition(center.set(
                    AllvrRenderCellKey.minBlockX(e.getLongKey()) + 8.0 - camPos.x,
                    AllvrRenderCellKey.minBlockY(e.getLongKey()) + 8.0 - camPos.y,
                    AllvrRenderCellKey.minBlockZ(e.getLongKey()) + 8.0 - camPos.z), center);
                if (Math.abs(center.x) > limXY || Math.abs(center.y) > limXY
                    || center.z < zMin || center.z > zMax) {
                    continue;
                }
            }
            n = this.appendCommands(rc, n);
        }
        return n;
    }

    /** Camera-relative + fog + day uniforms for the GPU-driven main pass
     *  (the shadow pass sets its own). */
    private void terrainUniforms(int prog, RenderLevelStageEvent event, ClientLevel level, Vec3 camPos) {
        AllvrShaderCache.uniformMat4(prog, "ModelViewMat", event.getModelViewMatrix());
        AllvrShaderCache.uniformMat4(prog, "ProjMat", event.getProjectionMatrix());
        int camX = net.minecraft.util.Mth.floor(camPos.x);
        int camY = net.minecraft.util.Mth.floor(camPos.y);
        int camZ = net.minecraft.util.Mth.floor(camPos.z);
        AllvrShaderCache.uniformIVec3(prog, "uCamInt", camX, camY, camZ);
        AllvrShaderCache.uniformVec3(prog, "uCamFrac",
            (float) (camPos.x - camX), (float) (camPos.y - camY), (float) (camPos.z - camZ));
        AllvrShaderCache.uniformFloat(prog, "uLight", dayFactor(level));

        // fog: vanilla 1.21.1 exposes the current level fog via RenderSystem
        // scalars; unset defaults (0..1) mean "no fog set" — treat as none
        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = RenderSystem.getShaderFogEnd();
        if (fogEnd > fogStart) {
            float[] fogColor = RenderSystem.getShaderFogColor();
            AllvrShaderCache.uniformFloat(prog, "uFogStart", fogStart);
            AllvrShaderCache.uniformFloat(prog, "uFogEnd", fogEnd);
            AllvrShaderCache.uniformVec3(prog, "uFogColor", fogColor[0], fogColor[1], fogColor[2]);
        } else {
            AllvrShaderCache.uniformFloat(prog, "uFogStart", 1.0e6f);
            AllvrShaderCache.uniformFloat(prog, "uFogEnd", 2.0e6f);
            AllvrShaderCache.uniformVec3(prog, "uFogColor", 1, 1, 1);
        }
        AllvrShaderCache.uniformInt(prog, "uAtlas", 0);
        AllvrShaderCache.uniformInt(prog, "uStateTable", AllvrBuffers.STATE_TBO_UNIT);
    }

    /**
     * Draw tail of the GPU-driven main pass (doc §13 iris slice G2):
     * patched-program selection (pack-lit vs unpatched fallback), the pack-lit
     * frame target bind/unbind with FBO+viewport save/restore (the vanilla
     * translucents and iris's own passes continue in the same frame), the
     * common terrain program state and the MDIC submission with the
     * GPU-owned command count. The shadow pass (below) submits separately
     * with an explicit count — its command source is CPU-built per light box.
     */
    private void drawTerrain(RenderLevelStageEvent event, ClientLevel level, Vec3 camPos,
                             AllvrIrisPipelineData data) {
        Minecraft mc = Minecraft.getInstance();
        // 0 = unpatched main-target draw, 1 = patched (pack-lit), 2 = albedo pass
        int mode = 0;
        int prog = this.tier == Tier.C ? this.shaders.compatTerrain() : this.shaders.terrain();
        if (data != null) {
            if (this.tier == Tier.C) {
                prog = this.shaders.compatPatchedTerrain();
                if (prog != 0) {
                    mode = 1;
                } else if (this.shaders.compatAlbedoTerrain() != 0) {
                    mode = 2;
                    prog = this.shaders.compatAlbedoTerrain();
                } else {
                    // compat draw without any pack-aware program — unpatched
                    prog = this.shaders.compatTerrain();
                }
            } else {
                prog = this.shaders.patchedTerrain();
                if (prog != 0) {
                    mode = 1;
                } else if (this.shaders.albedoTerrain() != 0) {
                    mode = 2;
                    prog = this.shaders.albedoTerrain();
                } else if (!this.warnedPatchFallback) {
                    this.warnedPatchFallback = true;
                    CreateManaIndustry.LOGGER.warn("[Allvr] patched terrain programs unavailable — falling back "
                        + "to the unpatched draw (fallback chain, grilling decision ⑧)");
                }
                if (mode == 0) {
                    prog = this.shaders.terrain();
                }
            }
        }
        if (prog == 0) {
            return;
        }

        int savedFbo = 0;
        int[] savedViewport = null;
        boolean targetBound = false;
        if (mode > 0) {
            savedFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            savedViewport = new int[4];
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, savedViewport);
            var main = mc.getMainRenderTarget();
            if (this.frameTarget.beginFrame(main.width, main.height)) {
                this.frameTarget.bind();
                targetBound = true;
            } else {
                // incomplete target (dead pack textures) → albedo fallback: the
                // unpatched program draws into the currently bound gbuffer FBO
                mode = 0;
                prog = this.tier == Tier.C ? this.shaders.compatTerrain() : this.shaders.terrain();
            }
        }

        try {
        // TBO freshness parity: the state table must cover every id the mesher
        // registers (invalidateStateTable forces the re-upload after a
        // customId re-resolve — grilling decision ⑦)
        this.buffers.ensureStateTable(AllvrRenderStateMap.entryCount());
        GL20.glUseProgram(prog);
        if (this.tier == Tier.C) {
            this.buffers.bindStateTable();
            this.compat.bindForDraw();
        } else {
            this.buffers.bindForDraw();
        }
        this.terrainUniforms(prog, event, level, camPos);
        if (mode == 2) {
            // albedo pass samples the vanilla lightmap with the baked nibbles
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + LIGHTMAP_UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, AllvrIrisPipelineData.getLightmapTextureId());
            GL20.glUniform1i(GL20.glGetUniformLocation(prog, "uLightmapTex"), LIGHTMAP_UNIT);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D,
            mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getId());

        // state: opaque pass (borrowed depth — the pack's deferred shading and
        // the vanilla translucents depth-test against it correctly)
        RenderSystem.enableDepthTest();
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glCullFace(GL11.GL_BACK);
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();

        if (this.tier == Tier.C) {
            this.compat.draw(this.compatEntries);
            this.compat.unbind();
        } else {
            this.buffers.drawIndirectCount(this.coreGl46);
        }

        // The compact descriptor stream deliberately contains only proven
        // full-cube SOLID states.  Everything else is submitted through the
        // vanilla/NeoForge dispatcher while the same camera-relative pose and
        // target are active, so partial models and fluids cannot disappear.
        this.drawFallbackBlocks(event, level, camPos);
        this.drawBlockEntities(event, camPos);

        if (mode > 0) {
            if (mode == 2) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + LIGHTMAP_UNIT);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
            }
            this.frameTarget.unbind();
            targetBound = false;
            // shadow pass is a Tier B feature (GPU command source + pack FBO
            // plumbing); the compat tier stays main-pass-only
            if (this.tier == Tier.B && ClientConfig.allvrIrisShadowPass) {
                this.drawShadowPass(camPos);
            }
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, savedFbo);
            GL11.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);
        }
        } finally {
            if (targetBound) {
                try {
                    this.frameTarget.unbind();
                } finally {
                    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, savedFbo);
                    GL11.glViewport(savedViewport[0], savedViewport[1],
                        savedViewport[2], savedViewport[3]);
                }
            }
        }
    }

    /**
     * Generic M4 stream for partial models, cutout/translucent materials and
     * fluids.  The worker supplies immutable states and light values; only
     * dispatcher/model access occurs here on the render thread.  Translucent
     * entries retain the last valid list and are sorted back-to-front before
     * each submission, so a budgeted future sort can safely reuse this list.
     */
    private void drawFallbackBlocks(RenderLevelStageEvent event, ClientLevel level, Vec3 camPos) {
        List<AllvrFallbackBlock> blocks = new ArrayList<>();
        for (var entry : this.renderCubes.long2ObjectEntrySet()) {
            Cube rc = entry.getValue();
            if (rc.fallbackBlocks.length == 0 || !this.cellInFrustum(entry.getLongKey(), camPos)) {
                continue;
            }
            java.util.Collections.addAll(blocks, rc.fallbackBlocks);
        }
        if (blocks.isEmpty()) {
            return;
        }
        blocks.sort((a, b) -> {
            if (a.translucent() != b.translucent()) {
                return a.translucent() ? 1 : -1;
            }
            if (!a.translucent()) {
                return 0;
            }
            double ad = distanceSqr(a, camPos);
            double bd = distanceSqr(b, camPos);
            return Double.compare(bd, ad);
        });

        Minecraft mc = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        PoseStack pose = new PoseStack();
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.set(event.getModelViewMatrix());
        RenderSystem.applyModelViewMatrix();
        AllvrBlockAccess access = new AllvrBlockAccess(level);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        try {
            for (AllvrFallbackBlock block : blocks) {
                pos.set(block.x(), block.y(), block.z());
                pose.pushPose();
                try {
                    pose.translate((float) (block.x() - camPos.x),
                        (float) (block.y() - camPos.y), (float) (block.z() - camPos.z));
                    if (block.fluid()) {
                        RenderType type = ItemBlockRenderTypes.getRenderLayer(block.state().getFluidState());
                        VertexConsumer consumer = buffers.getBuffer(type);
                        mc.getBlockRenderer().renderLiquid(pos, access,
                            new AllvrLiquidVertexConsumer(consumer, pose, pos),
                            block.state(), block.state().getFluidState());
                    }
                    if (block.model()) {
                        var model = mc.getBlockRenderer().getBlockModel(block.state());
                        var modelData = model.getModelData(access, pos, block.state(), access.getModelData(pos));
                        mc.getBlockRenderer().renderSingleBlock(block.state(), pose, buffers,
                            block.packedLight(), OverlayTexture.NO_OVERLAY,
                            modelData, null);
                    }
                } finally {
                    pose.popPose();
                }
            }
        } finally {
            try {
                buffers.endBatch();
            } finally {
                modelView.popMatrix();
                RenderSystem.applyModelViewMatrix();
            }
        }
    }

    private boolean cellInFrustum(long key, Vec3 camPos) {
        int camX = net.minecraft.util.Mth.floor(camPos.x);
        int camY = net.minecraft.util.Mth.floor(camPos.y);
        int camZ = net.minecraft.util.Mth.floor(camPos.z);
        float ox = AllvrRenderCellKey.minBlockX(key) - camX;
        float oy = AllvrRenderCellKey.minBlockY(key) - camY;
        float oz = AllvrRenderCellKey.minBlockZ(key) - camZ;
        for (int i = 0; i < 6; i++) {
            float nx = this.frustumPlanes[i * 4];
            float ny = this.frustumPlanes[i * 4 + 1];
            float nz = this.frustumPlanes[i * 4 + 2];
            float d = this.frustumPlanes[i * 4 + 3];
            float px = nx > 0f ? ox + 16f : ox;
            float py = ny > 0f ? oy + 16f : oy;
            float pz = nz > 0f ? oz + 16f : oz;
            if (nx * px + ny * py + nz * pz + d < 0f) {
                return false;
            }
        }
        return true;
    }

    private static double distanceSqr(AllvrFallbackBlock block, Vec3 camPos) {
        double dx = block.x() + 0.5 - camPos.x;
        double dy = block.y() + 0.5 - camPos.y;
        double dz = block.z() + 0.5 - camPos.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Draws ALLVR-owned block entities exactly once; empty shell sections do
     * not participate in vanilla's block-entity list. */
    private void drawBlockEntities(RenderLevelStageEvent event, Vec3 camPos) {
        List<BlockEntity> entities = AllvrClientCubeCache.blockEntities();
        if (entities.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        PoseStack pose = new PoseStack();
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.set(event.getModelViewMatrix());
        RenderSystem.applyModelViewMatrix();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        try {
            for (BlockEntity entity : entities) {
                BlockPos pos = entity.getBlockPos();
                long cellKey = AllvrRenderCellKey.ofBlock(pos.getX(), pos.getY(), pos.getZ());
                if (!this.renderCubes.containsKey(cellKey) || !this.cellInFrustum(cellKey, camPos)) {
                    continue;
                }
                pose.pushPose();
                try {
                    pose.translate((float) (pos.getX() - camPos.x),
                        (float) (pos.getY() - camPos.y), (float) (pos.getZ() - camPos.z));
                    mc.getBlockEntityRenderDispatcher().render(entity, partialTick, pose, buffers);
                } finally {
                    pose.popPose();
                }
            }
        } finally {
            try {
                buffers.endBatch();
            } finally {
                modelView.popMatrix();
                RenderSystem.applyModelViewMatrix();
            }
        }
    }

    /**
     * G2 shadow-pass participation (grilling decision ⑥, sub-switch
     * {@code allvrIrisShadowPass}): depth-only MDI into the pack's shadow map.
     * iris rendered its own shadow content earlier this frame — ours adds on
     * top via depth LEQUAL, so the pack's deferred shadow sampling sees allay
     * terrain shadows. Runs inside the patched draw's stage slot (still before
     * every composite that samples shadowtex).
     * <p>
     * Submission is <b>player-space</b> (camera-relative): the shadow modelview
     * is rotation-only plus a grid-snap translate
     * ({@code ShadowMatrices.createModelViewMatrix}), i.e. it consumes
     * {@code worldPos - cameraPos} — exactly the shader's {@code relPos} when
     * uCamInt/uCamFrac carry the camera. An earlier world-space submission
     * (uCamInt = 0) pushed |Y| ≈ 30M through a ±shadowDistance ortho (NDC in
     * the hundred-thousands), so every vertex left clip space and nothing
     * reached the map — the ±30M R20 envelope was never accepted here, it is
     * avoided outright.
     * <p>
     * Both depth textures are written: shadowtex0 (everything) already holds
     * iris's entity + translucent depth, while shadowtex1 (opaque-only) is a
     * blit taken inside the shadow pass — Photon samples shadowtex1 for opaque
     * shadows, so a shadowtex0-only write was invisible.
     * <p>
     * The command source is rebuilt for the shadow box via
     * {@link #buildShadowCommands} — the main pass's view-frustum-culled
     * commands would make the shadows view-dependent — and submitted with an
     * explicit count ({@code draw(n)}), keeping the GPU path's
     * GL_PARAMETER_BUFFER semantics untouched.
     * <p>
     * The program is the depth-only shadow variant ({@code ALLVR_SHADOW_PASS}): it
     * applies the pack's shadow-map distortion (mode/bias/depthScale from the
     * shared {@link com.iridium126.createmanaindustry.client.render.shaderpack.ShadowDistortionRegistry}
     * — the same conventions the mist Tyndall sampling uses) so our depth
     * lands on the texels the pack's deferred stages actually sample. The
     * descriptor mesher bounds faces to four blocks per axis, keeping the
     * nonlinear chord error small while retaining hardware early-Z. The old
     * fragment-exact solve wrote gl_FragDepth and forced every overlapping
     * island layer to shade every covered texel in both shadow maps.
     */
    private void drawShadowPass(Vec3 camPos) {
        Matrix4f shadowModelView = AllvrIrisDataHolder.shadowModelView();
        Matrix4f shadowProjection = AllvrIrisDataHolder.shadowProjection();
        if (shadowModelView == null || shadowProjection == null) {
            return; // no shadow pass ran this frame (shadows off / night config)
        }
        // Four-block mesh patches keep vertex-projected radial distortion
        // stable and let the fixed-function depth path reject hidden layers.
        int prog = this.shaders.shadowTerrainSimple();
        if (prog == 0) {
            return;
        }
        int resolution = AllvrIrisDataHolder.shadowResolution();
        int commandCount = this.buildShadowCommands(shadowModelView, shadowProjection, camPos);
        if (commandCount == 0) {
            if (!this.warnedShadowEmpty && !this.renderCubes.isEmpty()) {
                this.warnedShadowEmpty = true;
                CreateManaIndustry.LOGGER.warn("[Allvr] shadow pass culled to 0 commands ({} cubes with geometry) "
                    + "— shadow-box cull mismatch, terrain will not cast shadows", this.renderCubes.size());
            }
            return;
        }
        if (!this.loggedShadowStart) {
            this.loggedShadowStart = true;
            CreateManaIndustry.LOGGER.info("[Allvr] shadow pass drawing: {} commands, program=depth-only distortion mode={} "
                + "bias={} depthScale={}", commandCount,
                ShadowDistortionRegistry.resolveForCurrentPack().glslMode(),
                ShadowDistortionRegistry.resolveForCurrentPack().bias(),
                ShadowDistortionRegistry.resolveForCurrentPack().depthScale());
        }

        int savedFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int[] savedViewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, savedViewport);

        GL20.glUseProgram(prog);
        AllvrShaderCache.uniformMat4(prog, "ModelViewMat", shadowModelView);
        AllvrShaderCache.uniformMat4(prog, "ProjMat", shadowProjection);
        int camX = net.minecraft.util.Mth.floor(camPos.x);
        int camY = net.minecraft.util.Mth.floor(camPos.y);
        int camZ = net.minecraft.util.Mth.floor(camPos.z);
        AllvrShaderCache.uniformIVec3(prog, "uCamInt", camX, camY, camZ);
        AllvrShaderCache.uniformVec3(prog, "uCamFrac",
            (float) (camPos.x - camX), (float) (camPos.y - camY), (float) (camPos.z - camZ));
        AllvrShaderCache.uniformInt(prog, "uAtlas", 0);
        AllvrShaderCache.uniformInt(prog, "uStateTable", AllvrBuffers.STATE_TBO_UNIT);
        var distortion = ShadowDistortionRegistry.resolveForCurrentPack();
        AllvrShaderCache.uniformInt(prog, "uShadowDistortionMode", distortion.glslMode());
        AllvrShaderCache.uniformFloat(prog, "uShadowDistortion", distortion.bias());
        AllvrShaderCache.uniformFloat(prog, "uShadowDepthScale", distortion.depthScale());
        AllvrShaderCache.uniformVec4(prog, "uShadowLogParams",
            distortion.logK(), distortion.logA(), distortion.logB(), distortion.depthScale());
        // fragment-exact solve inputs (absent locations no-op on the legacy program)
        GL20.glUniform1f(GL20.glGetUniformLocation(prog, "uShadowHalfPlane"),
            (float) (1.0 / Math.max(Math.abs(shadowProjection.m00()), 1e-6)));
        GL20.glUniform2f(GL20.glGetUniformLocation(prog, "uShadowMapSize"), resolution, resolution);

        GL11.glViewport(0, 0, resolution, resolution);
        // depth-only: the exact fsh computes gl_FragDepth per fragment and the
        // legacy fsh interpolates it — neither writes color
        GL11.glColorMask(false, false, false, false);
        RenderSystem.enableDepthTest();

        this.buffers.uploadCommands(this.commands, commandCount);
        // The fallback block/entity batches above bind their own VAOs and
        // endBatch leaves ours inactive.  Both shadow targets issue an indexed
        // draw, so restore the ALLVR VAO/IBO and descriptor bindings first.
        // Without this each target generated GL_INVALID_OPERATION once per
        // frame and no terrain depth reached either shadow map.
        this.buffers.bindForDraw();
        int[] shadowTex = {AllvrIrisDataHolder.shadowDepthTexture(),
            AllvrIrisDataHolder.shadowDepthTextureNoTranslucents()};
        for (int tex : shadowTex) {
            if (tex <= 0 || !this.frameTarget.bindShadow(tex, resolution)) {
                continue;
            }
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.frameTarget.shadowFbo());
            this.buffers.draw(commandCount);
        }

        GL11.glColorMask(true, true, true, true);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, savedFbo);
        GL11.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);
    }

    /**
     * 4b GPU-driven draw (doc §9.1/§9.2/§9.4): flush dirty nodes → reset →
     * traversal (frustum + HiZ vs last frame's pyramid + two-phase split) →
     * finalize (both indirect dispatch sizes) → cmdgen → clamp → one
     * glMultiDrawElementsIndirectCount whose command count is only ever read by
     * the GPU (GL_PARAMETER_BUFFER) → build the HiZ pyramid from the borrowed
     * MC main depth → revalidate phase-1 stamps against the fresh pyramid
     * (dispatch also GPU-sized). Kernel sequence is serialized by SSBO/image
     * barriers. HiZ degrades to frustum-only (4a) under an active iris pack
     * or when the pyramid can't be built (Q7 safe degradation).
     */
    private void gpuDraw(RenderLevelStageEvent event, ClientLevel level, AllvrIrisPipelineData data) {
        Minecraft mc = Minecraft.getInstance();
        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();

        this.buffers.ensureGpuCull();
        this.buffers.syncNodes(this.nodes);
        int highWater = this.nodes.highWater();
        int nodeCount = this.nodes.nodeCount();
        if (highWater == 0) {
            this.hizHistoryValid = false;
            this.logGpuStats(nodeCount, 0, -1, -1, -1, "");
            return;
        }
        // One matrix is shared by compute visibility and terrain.vsh.  The
        // traversal additionally reads cubeInfo, so its tested AABB is exactly
        // the AABB the emitted command will rasterize.
        this.extractFrustum(event.getProjectionMatrix(), event.getModelViewMatrix(), camPos);
        int curFrame = ++this.frameId;
        int lastFrame = curFrame - 1;
        boolean hizAvailable = !irisPackInUse() && this.shaders.hizReady()
            && this.extractDepthBias(event.getProjectionMatrix())
            && this.ensureHiz(mc);
        // The pyramid is screen-space history. It is valid for traversal only
        // when the camera and both transforms exactly match the frame that
        // built it; otherwise current screen coordinates address unrelated
        // previous-frame depths. We still rebuild below so stationary frames
        // resume occlusion on the next draw.
        boolean hizCull = hizAvailable && this.hizHistoryMatches(event, camPos);
        if (!hizAvailable) {
            this.hizHistoryValid = false;
        }

        this.buffers.bindGpuCull();
        if (hizAvailable) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + AllvrBuffers.HIZ_UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.buffers.hizTexture());
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
        }

        // 1. reset queue + command counters
        GL20.glUseProgram(this.shaders.cullReset());
        GL43.glDispatchCompute(1, 1, 1);
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);

        // 2. traversal: frustum + HiZ cull every live node → two-phase queue + stamp
        int travProg = this.shaders.traversal();
        GL20.glUseProgram(travProg);
        AllvrShaderCache.uniformMat4(travProg, "uViewProjection", this.projViewScratch);
        AllvrShaderCache.uniformIVec3(travProg, "uCamInt",
            net.minecraft.util.Mth.floor(camPos.x),
            net.minecraft.util.Mth.floor(camPos.y),
            net.minecraft.util.Mth.floor(camPos.z));
        AllvrShaderCache.uniformVec3(travProg, "uCamFrac",
            (float) (camPos.x - net.minecraft.util.Mth.floor(camPos.x)),
            (float) (camPos.y - net.minecraft.util.Mth.floor(camPos.y)),
            (float) (camPos.z - net.minecraft.util.Mth.floor(camPos.z)));
        GL30.glUniform1ui(GL30.glGetUniformLocation(travProg, "uFrameId"), curFrame);
        GL30.glUniform1ui(GL30.glGetUniformLocation(travProg, "uLastFrameId"), lastFrame);
        GL30.glUniform1ui(GL30.glGetUniformLocation(travProg, "uHizEnabled"), hizCull ? 1 : 0);
        if (hizCull) {
            this.uploadHizUniforms(travProg, event, mc);
        }
        GL43.glDispatchCompute((highWater + 63) / 64, 1, 1);
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);

        // 3. finalize: two-phase counts → cmdgen dispatch size
        GL20.glUseProgram(this.shaders.cullFinalize());
        GL43.glDispatchCompute(1, 1, 1);
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);

        // 4. cmdgen: visible nodes (phase 1 first) → MDI commands + atomic count
        GL20.glUseProgram(this.shaders.cmdgen());
        this.buffers.dispatchCmdgenIndirect();
        GL42.glMemoryBarrier(GL43.GL_COMMAND_BARRIER_BIT | GL43.GL_SHADER_STORAGE_BARRIER_BIT);

        // 4b. clamp the MDI command count to MAX_COMMANDS: cmdgen drops commands
        // past the cap but keeps its counter running, and a parameter-buffer
        // count above maxcount makes the MDIC draw error out entirely (nothing
        // draws that frame) — converge in place before the draw consumes it
        GL20.glUseProgram(this.shaders.cullClamp());
        GL43.glDispatchCompute(1, 1, 1);
        GL42.glMemoryBarrier(GL43.GL_COMMAND_BARRIER_BIT | GL43.GL_SHADER_STORAGE_BARRIER_BIT);

        // triage readback (Q9): 5 s-throttled, debug-log gated — the real
        // per-frame path stays zero-readback. Also dumps node[0]'s raw uints
        // (GPU mirror truth) and a CPU re-run of the plane test (frustum
        // sanity reference for the GPU traversal count).
        int phase1 = -1;
        int phase2 = -1;
        int cmdCount = -1;
        String triage = "";
        if (CreateManaIndustry.LOGGER.isDebugEnabled()
                && System.currentTimeMillis() - this.lastGpuDebugMillis > 5000) {
            this.lastGpuDebugMillis = System.currentTimeMillis();
            int[] q = new int[3];
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.buffers.queueBuffer());
            GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, q);
            phase1 = q[0];
            phase2 = q[1];
            int[] c = new int[1];
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.buffers.commandCountBuffer());
            GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, c);
            cmdCount = c[0];
            // command-stream validity triage: garbage commands (stale/ghost
            // node data, misaligned arena offsets) draw misaligned quads and
            // surface as stretched dark-line artifacts — the first commands
            // must reference an aligned, in-bounds arena range and a live slot
            String cmdTriage = "";
            if (cmdCount > 0) {
                int[] cmds = new int[20];
                GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.buffers.commandBuffer());
                GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, cmds);
                StringBuilder sb = new StringBuilder(" | cmds");
                for (int i = 0; i < 4; i++) {
                    int count = cmds[i * 5];
                    int baseV = cmds[i * 5 + 3];
                    int inst = cmds[i * 5 + 4];
                    boolean ok = (baseV & 3) == 0 && cmds[i * 5 + 1] == 1 && cmds[i * 5 + 2] == 0
                        && inst > 0 && inst < AllvrBuffers.MAX_SLOTS
                        && ((long) (baseV >> 2)) + (count / 6) <= this.buffers.arenaUsedQuads();
                    sb.append(String.format(" [%d,%d,%d,%d,%d%s]", count, cmds[i * 5 + 1],
                        cmds[i * 5 + 2], baseV, inst, ok ? "" : " BAD"));
                }
                cmdTriage = sb.toString();
            }
            int[] n0 = new int[8];
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.buffers.nodeBuffer());
            GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, n0);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
            int camX = net.minecraft.util.Mth.floor(camPos.x);
            int camY = net.minecraft.util.Mth.floor(camPos.y);
            int camZ = net.minecraft.util.Mth.floor(camPos.z);
            triage = String.format(
                " | planes0=(%.3f,%.3f,%.3f,%.3f) planes3=(%.3f,%.3f,%.3f,%.3f) cam=(%d,%d,%d)"
                    + " | node0=[%08x %08x %08x %08x %08x %08x %08x %08x]"
                    + " | node0mirror=[%08x %08x %08x %08x %08x %08x %08x %08x]"
                    + " | cpuFrustum %d/%d",
                this.frustumPlanes[0], this.frustumPlanes[1], this.frustumPlanes[2], this.frustumPlanes[3],
                this.frustumPlanes[12], this.frustumPlanes[13], this.frustumPlanes[14], this.frustumPlanes[15],
                camX, camY, camZ,
                n0[0], n0[1], n0[2], n0[3], n0[4], n0[5], n0[6], n0[7],
                this.nodes.mirror()[0], this.nodes.mirror()[1],
                this.nodes.mirror()[2], this.nodes.mirror()[3],
                this.nodes.mirror()[4], this.nodes.mirror()[5],
                this.nodes.mirror()[6], this.nodes.mirror()[7],
                this.debugCpuFrustumPass(camX, camY, camZ), this.nodes.nodeCount()) + cmdTriage
                + (cmdCount >= AllvrBuffers.MAX_COMMANDS ? " CLAMPED" : "");
        }

        // 5. terrain draw — shared tail (pack-lit FBO under a resolved patch),
        // MDIC submission with the GPU count
        this.drawTerrain(event, level, camPos, data);
        this.logGpuStats(nodeCount, highWater, phase1, phase2, cmdCount, triage);

        // 6. build the fresh HiZ pyramid from this frame's depth, then
        // re-validate phase-1 stamps against it (their depth is on screen)
        if (hizAvailable) {
            GL42.glMemoryBarrier(GL43.GL_FRAMEBUFFER_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
            this.buildHiz(mc);
            // pyramid levels were written via imageStore — make them visible to
            // the revalidate kernel's sampler reads before its dispatch
            GL42.glMemoryBarrier(GL43.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
            int revProg = this.shaders.revalidate();
            GL20.glUseProgram(revProg);
            AllvrShaderCache.uniformIVec3(revProg, "uCamInt",
                net.minecraft.util.Mth.floor(camPos.x),
                net.minecraft.util.Mth.floor(camPos.y),
                net.minecraft.util.Mth.floor(camPos.z));
            this.uploadHizUniforms(revProg, event, mc);
            // indirect over-dispatch: finalize sized this dispatch from the
            // GPU-side p1 count (dispatch[1] = ceil(p1/64)) — over-dispatch is
            // ≤63 idle invocations instead of a constant QUEUE_CAPACITY/64
            // groups every frame; the kernel's queue-bounds guard still covers
            // the tail (4c-2 indirect dispatch)
            this.buffers.dispatchRevalidateIndirect();
            GL42.glMemoryBarrier(GL43.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
            this.rememberHizView(event, camPos);
        }

        // off-departure hygiene (particle-engine discipline)
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + AllvrBuffers.HIZ_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + AllvrBuffers.MC_DEPTH_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        GL20.glUseProgram(0);
        this.buffers.unbind();
        this.buffers.unbindGpuCull();
    }

    /** Binds the pyramid sampler + projection uniforms the HiZ chunk consumes. */
    private void uploadHizUniforms(int prog, RenderLevelStageEvent event, Minecraft mc) {
        GL20.glUniform1i(GL20.glGetUniformLocation(prog, "uHiz"), AllvrBuffers.HIZ_UNIT);
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(prog, "uModelView"), false,
            event.getModelViewMatrix().get(new float[16]));
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(prog, "uProj"), false,
            event.getProjectionMatrix().get(new float[16]));
        Vec3 camPos = event.getCamera().getPosition();
        AllvrShaderCache.uniformVec3(prog, "uCamFrac",
            (float) (camPos.x - net.minecraft.util.Mth.floor(camPos.x)),
            (float) (camPos.y - net.minecraft.util.Mth.floor(camPos.y)),
            (float) (camPos.z - net.minecraft.util.Mth.floor(camPos.z)));
        var main = mc.getMainRenderTarget();
        GL20.glUniform2f(GL20.glGetUniformLocation(prog, "uViewport"), main.width, main.height);
        GL20.glUniform1i(GL20.glGetUniformLocation(prog, "uHizTopLevel"), this.buffers.hizLevels() - 1);
        GL20.glUniform1f(GL20.glGetUniformLocation(prog, "uDepthBiasScale"), this.depthBiasScale);
    }

    /**
     * Depth-comparison bias scale: dz/du of the perspective depth for a
     * 1.5-block view-space margin (doc §9.2 conservative test), extracted from
     * the projection each frame (MC's far plane tracks fog distance). Returns
     * false when the matrix yields no sane perspective (HiZ disabled this frame).
     */
    private boolean extractDepthBias(Matrix4fc projectionMatrix) {
        try {
            float near = projectionMatrix.perspectiveNear();
            float far = projectionMatrix.perspectiveFar();
            if (near <= 0f || far <= near) {
                this.depthBiasScale = -1f;
                return false;
            }
            this.depthBiasScale = 1.5f * near * far / (far - near);
            return true;
        } catch (UnsupportedOperationException e) {
            this.depthBiasScale = -1f;
            return false;
        }
    }

    /** Allocates / resizes the HiZ pyramid to the current main target size. */
    private boolean ensureHiz(Minecraft mc) {
        var main = mc.getMainRenderTarget();
        int w = main.width;
        int h = main.height;
        if (w <= 0 || h <= 0) {
            return false;
        }
        if (this.buffers.hizReady() && this.buffers.hizWidth() == w && this.buffers.hizHeight() == h) {
            return true;
        }
        // re-allocation throttle: a failed attempt left hizReady() false, so
        // without this the alloc (and its GL error) would retry every frame
        long now = System.currentTimeMillis();
        if (this.lastHizAllocFailed && now - this.lastHizAllocMillis < 1000) {
            return false;
        }
        this.lastHizAllocMillis = now;
        this.hizHistoryValid = false;
        this.buffers.allocHiz(w, h);
        this.lastHizAllocFailed = !this.buffers.hizReady();
        // the caller's hiz predicate must see the allocation outcome: returning
        // true unconditionally here left an incomplete texture bound (samples
        // 0.0 = near plane) and culled the entire world instead of degrading
        // to frustum-only
        return this.buffers.hizReady();
    }

    /** True only when the HiZ texture was built from this exact screen-space view. */
    private boolean hizHistoryMatches(RenderLevelStageEvent event, Vec3 camPos) {
        if (!this.hizHistoryValid) {
            return false;
        }
        double dx = camPos.x - this.hizHistoryCamX;
        double dy = camPos.y - this.hizHistoryCamY;
        double dz = camPos.z - this.hizHistoryCamZ;
        return dx * dx + dy * dy + dz * dz <= 1e-8
            && matrixNear(this.hizHistoryModelView, event.getModelViewMatrix())
            && matrixNear(this.hizHistoryProjection, event.getProjectionMatrix());
    }

    private void rememberHizView(RenderLevelStageEvent event, Vec3 camPos) {
        this.hizHistoryModelView.set(event.getModelViewMatrix());
        this.hizHistoryProjection.set(event.getProjectionMatrix());
        this.hizHistoryCamX = camPos.x;
        this.hizHistoryCamY = camPos.y;
        this.hizHistoryCamZ = camPos.z;
        this.hizHistoryValid = true;
    }

    private static boolean matrixNear(Matrix4fc a, Matrix4fc b) {
        final float e = 1e-6f;
        return Math.abs(a.m00() - b.m00()) <= e && Math.abs(a.m01() - b.m01()) <= e
            && Math.abs(a.m02() - b.m02()) <= e && Math.abs(a.m03() - b.m03()) <= e
            && Math.abs(a.m10() - b.m10()) <= e && Math.abs(a.m11() - b.m11()) <= e
            && Math.abs(a.m12() - b.m12()) <= e && Math.abs(a.m13() - b.m13()) <= e
            && Math.abs(a.m20() - b.m20()) <= e && Math.abs(a.m21() - b.m21()) <= e
            && Math.abs(a.m22() - b.m22()) <= e && Math.abs(a.m23() - b.m23()) <= e
            && Math.abs(a.m30() - b.m30()) <= e && Math.abs(a.m31() - b.m31()) <= e
            && Math.abs(a.m32() - b.m32()) <= e && Math.abs(a.m33() - b.m33()) <= e;
    }

    /**
     * Builds the MAX-depth pyramid from the main target's depth (verified
     * plain GL_DEPTH_COMPONENT/float in 1.21.1 MainTarget — directly
     * sampleable): level 0 = 2×2 depth quads, then a CPU-looped chain step
     * per mip with image-unit rebinding. An unbound/garbage depth source
     * collapses to "occludes nothing" — the safe-degradation contract.
     */
    private void buildHiz(Minecraft mc) {
        var main = mc.getMainRenderTarget();
        int hw = Math.max(1, (this.buffers.hizWidth() + 1) / 2);
        int hh = Math.max(1, (this.buffers.hizHeight() + 1) / 2);

        GL13.glActiveTexture(GL13.GL_TEXTURE0 + AllvrBuffers.MC_DEPTH_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, main.getDepthTextureId());
        GL13.glActiveTexture(GL13.GL_TEXTURE0);

        int first = this.shaders.hizFirst();
        GL20.glUseProgram(first);
        GL20.glUniform1i(GL20.glGetUniformLocation(first, "uDepth"), AllvrBuffers.MC_DEPTH_UNIT);
        GL20.glUniform2f(GL20.glGetUniformLocation(first, "uDepthSize"), main.width, main.height);
        GL20.glUniform2f(GL20.glGetUniformLocation(first, "uHizSize"), hw, hh);
        GL43.glBindImageTexture(0, this.buffers.hizTexture(), 0, false, 0, GL43.GL_WRITE_ONLY, GL30.GL_R32F);
        GL43.glDispatchCompute(Math.max(1, (hw + 7) / 8), Math.max(1, (hh + 7) / 8), 1);
        GL42.glMemoryBarrier(GL43.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

        int down = this.shaders.hizDownsample();
        GL20.glUseProgram(down);
        for (int lvl = 1; lvl < this.buffers.hizLevels(); lvl++) {
            int pw = AllvrBuffers.hizLevelDim(hw, lvl - 1);
            int ph = AllvrBuffers.hizLevelDim(hh, lvl - 1);
            int dw = AllvrBuffers.hizLevelDim(hw, lvl);
            int dh = AllvrBuffers.hizLevelDim(hh, lvl);
            GL43.glBindImageTexture(0, this.buffers.hizTexture(), lvl - 1, false, 0,
                GL43.GL_READ_ONLY, GL30.GL_R32F);
            GL43.glBindImageTexture(1, this.buffers.hizTexture(), lvl, false, 0,
                GL43.GL_WRITE_ONLY, GL30.GL_R32F);
            GL20.glUniform2f(GL20.glGetUniformLocation(down, "uSrcDims"), pw, ph);
            GL20.glUniform2f(GL20.glGetUniformLocation(down, "uDestSize"), dw, dh);
            GL43.glDispatchCompute(Math.max(1, (dw + 7) / 8), Math.max(1, (dh + 7) / 8), 1);
            GL42.glMemoryBarrier(GL43.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        }
        GL43.glBindImageTexture(0, 0, 0, false, 0, GL43.GL_READ_ONLY, GL30.GL_R32F);
        GL43.glBindImageTexture(1, 0, 0, false, 0, GL43.GL_WRITE_ONLY, GL30.GL_R32F);
    }

    /**
     * Gribb–Hartmann planes of Proj*View (particle-engine extraction, §6.5),
     * normalized; the camera's sub-block fraction is folded into each plane's
     * distance so the shader can test integer camera-relative AABBs directly.
     */
    private void extractFrustum(Matrix4fc projectionMatrix, Matrix4fc modelViewMatrix, Vec3 camPos) {
        Matrix4f m = this.projViewScratch.set(projectionMatrix).mul(modelViewMatrix);
        int camX = net.minecraft.util.Mth.floor(camPos.x);
        int camY = net.minecraft.util.Mth.floor(camPos.y);
        int camZ = net.minecraft.util.Mth.floor(camPos.z);
        float fx = (float) (camPos.x - camX);
        float fy = (float) (camPos.y - camY);
        float fz = (float) (camPos.z - camZ);
        for (int i = 0; i < 6; i++) {
            m.frustumPlane(i, this.planeScratch);
            float a = this.planeScratch.x;
            float b = this.planeScratch.y;
            float c = this.planeScratch.z;
            float d = this.planeScratch.w;
            float inv = 1f / (float) Math.sqrt(a * a + b * b + c * c);
            int o = i * 4;
            this.frustumPlanes[o] = a * inv;
            this.frustumPlanes[o + 1] = b * inv;
            this.frustumPlanes[o + 2] = c * inv;
            this.frustumPlanes[o + 3] = (d - (a * fx + b * fy + c * fz)) * inv;
        }
    }

    /** 5 s-throttled GPU-path stats (CPU-known counters + optional readback).
     *  The cpu figure is the render-thread terrain-slice EMA (pumpResults +
     *  gpuDraw, ms) — the 4c-2 CPU<1 ms acceptance instrument; it legitimately
     *  spikes during a fill, judge it in steady state. */
    private void logGpuStats(int nodeCount, int highWater, int phase1, int phase2, int cmdCount, String triage) {
        long now = System.currentTimeMillis();
        if (now - this.lastStatsLogMillis < 5000) {
            return;
        }
        this.lastStatsLogMillis = now;
        long quads = 0L;
        long fallback = 0L;
        for (Cube cube : this.renderCubes.values()) {
            quads += Math.max(0, cube.quadCount);
            fallback += cube.fallbackBlocks.length;
        }
        String cpu = String.format(", %d quads, %d fallback blocks, cpu %.2fms",
            quads, fallback, this.sliceEmaMs);
        if (phase1 >= 0) {
            CreateManaIndustry.LOGGER.info(
                "[Allvr] gpu frame: {} nodes ({} high water) → p1 {} + p2 {} = {} visible → {} commands{}{}",
                nodeCount, highWater, phase1, phase2, phase1 + phase2, cmdCount, cpu, triage);
        } else {
            CreateManaIndustry.LOGGER.info("[Allvr] gpu frame: {} nodes ({} high water){}{}",
                nodeCount, highWater, cpu, triage);
        }
    }

    /**
     * Triage: re-runs the traversal's exact plane test on the CPU for every
     * live node, using the same frustumPlanes array uploaded to the shader.
     * A nonzero count here with a GPU-side 0 pins the failure to the node
     * upload / kernel; a zero count here pins it to the plane extraction.
     */
    private int debugCpuFrustumPass(int camX, int camY, int camZ) {
        int pass = 0;
        for (var e : this.renderCubes.long2ObjectEntrySet()) {
            Cube rc = e.getValue();
            if (rc.quadCount <= 0 || rc.slot < 0) {
                continue;
            }
            float ox = AllvrRenderCellKey.minBlockX(e.getLongKey()) - camX;
            float oy = AllvrRenderCellKey.minBlockY(e.getLongKey()) - camY;
            float oz = AllvrRenderCellKey.minBlockZ(e.getLongKey()) - camZ;
            boolean ok = true;
            for (int i = 0; i < 6 && ok; i++) {
                float nx = this.frustumPlanes[i * 4];
                float ny = this.frustumPlanes[i * 4 + 1];
                float nz = this.frustumPlanes[i * 4 + 2];
                float d = this.frustumPlanes[i * 4 + 3];
                float px = nx > 0f ? ox + 16f : ox;
                float py = ny > 0f ? oy + 16f : oy;
                float pz = nz > 0f ? oz + 16f : oz;
                if (nx * px + ny * py + nz * pz + d < 0f) {
                    ok = false;
                }
            }
            if (ok) {
                pass++;
            }
        }
        return pass;
    }

    /** 0.25 at full darkness → 1.0 at clear day (V0 stand-in for phase-5 light). */
    private static float dayFactor(ClientLevel level) {
        return 0.25f + 0.75f * (15 - level.getSkyDarken()) / 15.0f;
    }

    private static void chat(Minecraft mc, String text) {
        try {
            mc.gui.getChat().addMessage(net.minecraft.network.chat.Component.literal(text));
        } catch (RuntimeException ignored) {
            // gui not ready — the log line below still records it
        }
        CreateManaIndustry.LOGGER.info(text);
    }

    /**
     * Pack detection for the stage switch. iris is a compileOnly dep guarded by
     * {@code IRIS_ACTIVE}; {@code isShaderPackInUse} is the reporting API (doc
     * §6.4) — we deliberately do NOT gate iris's own pipeline with it (see the
     * analysis in the phase-3 doc section).
     */
    private static boolean irisPackInUse() {
        if (!CreateManaIndustry.IRIS_ACTIVE) {
            return false;
        }
        try {
            return net.irisshaders.iris.api.v0.IrisApi.getInstance().isShaderPackInUse();
        } catch (Throwable t) {
            return false;
        }
    }

    private AllvrRenderer() {
    }
}
