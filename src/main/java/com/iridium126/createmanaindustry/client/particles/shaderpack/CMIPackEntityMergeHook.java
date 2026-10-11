package com.iridium126.createmanaindustry.client.particles.shaderpack;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.particles.engine.CMIParticleEngine;
import com.iridium126.createmanaindustry.client.particles.engine.ParticleBuffers;
import com.iridium126.createmanaindustry.client.particles.engine.ParticleGLUtil;
import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;

import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import top.leonx.irisveil.IrisVeilCompat;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL40;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.entity.EntityType;
import com.iridium126.createmanaindustry.client.particles.engine.ModelShadowGpu;
import com.iridium126.createmanaindustry.client.particles.engine.ParticlePrograms;
import com.iridium126.createmanaindustry.client.particles.engine.ParticleComputeState;
import com.iridium126.createmanaindustry.client.particles.packages.PackageDrawCulling;
import com.iridium126.createmanaindustry.client.particles.packages.PackageDrawTelemetry;
import com.iridium126.createmanaindustry.client.particles.packages.PackageRenderState;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws the MODEL (allay) particle segments INSIDE the gbuffer phase -- at the
 * exact point Flywheel draws its entity-stage instanced content: injected by
 * {@code LevelRendererBlockEntitiesMixin} just before the profiler section
 * switches to {@code blockentities}, i.e. after regular entities and before
 * block entities, translucent terrain and any deferred composite.
 *
 * <p>The MODEL segments are drawn through a program MERGED into the active
 * shader pack's {@code gbuffers_entities} pipeline (see
 * {@link ShaderPackProgramCompiler}), so the pack consumes the merged output
 * exactly like a native entity's: Photon-family packs light the particles
 * through their deferred pass -- including the {@code material_mask = 40}
 * emission their entity vertex shaders apply at full block light, which our
 * injected full-bright lightmap deliberately triggers (that IS the vanilla
 * allay's "glowing eyes" treatment) -- while forward-lit packs shade us
 * in-fragment as usual.
 *
 * <p>Arbitration: a successful draw here latches
 * {@link CMIParticleEngine#markHookModelDrawn()}, so the engine's AFTER_LEVEL
 * frame skips its own drawModels. When compilation fails the latch stays clear
 * and runFrame draws the MODEL segments itself on that plain path --
 * byte-for-byte the treatment every other particle type gets (sprites are
 * permanently on it per design freeze Q9).
 *
 * <p>All iris-veil-compat references require the mod to be loaded -- render()
 * is only reachable under {@link CreateManaIndustry#IRISVEIL_ACTIVE} (checked
 * in the mixin before this class is first touched).
 */
public final class CMIPackEntityMergeHook {

    private static final ShaderPackProgramCompiler COMPILER = new ShaderPackProgramCompiler();

    /** GL_TIME_ELAPSED ring for this window's GPU cost (never blocks). */
    private static final int[] TIMER_QUERIES = new int[4];
    private static int timerSlot = 0;
    private static int timerIssued = 0;
    private static boolean timerReady = false;

    /** Uniform-location cache (program id -> name -> location). */
    private static final Map<Integer, Map<String, Integer>> UNIFORMS = new HashMap<>();
    private static boolean rebuildListenerRegistered = false;

    private static ModelShadowGpu MODEL_SHADOW;
    private static final PackageDrawCulling SHADOW_CULLING=new PackageDrawCulling();
    private static final PackageDrawTelemetry SHADOW_PREP_TIMING=new PackageDrawTelemetry();
    private static ParticleComputeState shadowComputeState;
    private static PackageRenderState shadowDrawState;
    public static void invalidateShadow(){if(MODEL_SHADOW!=null)MODEL_SHADOW.close();MODEL_SHADOW=null;SHADOW_PREP_TIMING.close();}
    private CMIPackEntityMergeHook() {}

    // ------------------------------------------------------------------
    // Entry point (mixin, render thread)
    // ------------------------------------------------------------------

    /** Called from {@code LevelRendererBlockEntitiesMixin} inside renderLevel. */
    public static void render() {
        CMIParticleEngine engine = CMIParticleEngine.INSTANCE;
        try {
            if (!ClientConfig.shaderPackIntegration
                    || !IrisVeilCompat.isShaderPackInUse()
                    || !engine.available()
                    || !engine.packageMainFrameReady())
                return;

            if (!ensureCompiledWithListener())
                return; // sticky failure recorded; the engine's plain AFTER_LEVEL path takes over

            // The finally is load-bearing: a drawEntities throw with the
            // bracket open would leave this query ACTIVE forever, failing
            // every later glBeginQuery on the target with GL_INVALID_OPERATION
            // ("Cannot begin query on an active query object").
            boolean timed = beginTimer(engine);
            try {
                drawEntities(engine);
            } finally {
                if (timed)
                    endTimer(engine);
            }

            // Latch even when this frame's instance count is zero: skipping the
            // L0 drawModels then costs nothing (it would submit the same empty
            // commands), while a CPU-side per-type readback would be a stall.
            engine.markHookModelDrawn();
            engine.shaderPackPathStatus = "pack entity merge (cutout+ghost dual, model near-first, current-frame perm)";
            engine.shaderPackDepthStatus = "hardware (gbuffer)";
            engine.shaderPackErrorStatus = "";
            engine.shaderPackPermStatus = "gbuffer permutation age " + engine.lastFinalPermAgeFrames()
                    + "f (0 = this frame's cull)";
        } catch (RuntimeException | LinkageError e) {
            CreateManaIndustry.LOGGER.warn("[CMI particles] pack entity merge draw failed", e);
            engine.shaderPackErrorStatus = String.valueOf(e);
        }
    }

    private static boolean ensureCompiledWithListener() {
        if (!rebuildListenerRegistered) {
            rebuildListenerRegistered = true;
            COMPILER.setOnRebuild(UNIFORMS::clear);
        }
        return COMPILER.ensureCompiled();
    }

    // ------------------------------------------------------------------
    // Shadow track (S-track)
    // ------------------------------------------------------------------

    /**
     * Draws the MODEL segments into the active shadow map -- called from
     * {@code MixinIrisShadowRenderer} inside {@code renderShadows} at the same
     * spot iris-flw-compat uses for Flywheel content, right before Iris batches
     * its own buffered entity geometry ("draw entities"). Only the MODEL type
     * casts shadows; sprites stay shadow-free exactly like vanilla particles.
     *
     * <p>Fully contained: any failure here only means no particle shadows this
     * frame -- the gbuffer path and the plain fallback are untouched.
     */
    public static void renderShadow(Camera camera,Frustum frustum) {
        CMIParticleEngine engine=CMIParticleEngine.INSTANCE;
        if(!engine.currentFrameReady()){engine.shaderPackShadowStatus="current frame unavailable";return;}
        if(!ClientConfig.shaderPackIntegration||!IrisVeilCompat.isShaderPackInUse()||!engine.available())return;
        try{
            if(!PackageShadowPolicy.copy(frustum,SHADOW_CULLING)){engine.shaderPackShadowStatus="unsupported shadow frustum: "+(frustum==null?"null":frustum.getClass().getName());return;}
            if(shadowComputeState==null){shadowComputeState=new ParticleComputeState();shadowDrawState=new PackageRenderState();}
            shadowComputeState.capture();
            try {
            shadowDrawState.capture();
            var captured=CapturedRenderingState.INSTANCE;
            float alpha=captured.getCurrentAlphaTest();
            boolean tessellation=net.irisshaders.iris.vertices.ImmediateState.usingTessellation;
            int srcRgb=GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_SRC_RGB),dstRgb=GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_DST_RGB);
            int srcAlpha=GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_SRC_ALPHA),dstAlpha=GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_DST_ALPHA);
            boolean depth=GL11.glIsEnabled(GL11.GL_DEPTH_TEST),blend=GL11.glIsEnabled(GL11.GL_BLEND),cull=GL11.glIsEnabled(GL11.GL_CULL_FACE),depthMask=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            try{
                // First-use shader construction belongs inside the same boundary as drawing.
                if(!ensureCompiledWithListener()){engine.shaderPackShadowStatus="merge failed";return;}
                if(!COMPILER.shadowReady()){engine.shaderPackShadowStatus="no shadow track";return;}
                var gpu=engine.gpuBuffers();
                if(MODEL_SHADOW==null||MODEL_SHADOW.capacity()!=gpu.capacity()||MODEL_SHADOW.carrierCapacity()!=gpu.carrierCapacity()){invalidateShadow();MODEL_SHADOW=new ModelShadowGpu(gpu.capacity(),gpu.carrierCapacity(),ParticlePrograms::loadParticlePlain);}
                engine.addExternalGpuMs(SHADOW_PREP_TIMING.poll());
                SHADOW_PREP_TIMING.begin(ClientConfig.particleAutoThrottle||com.iridium126.createmanaindustry.client.particles.engine.ParticleDiagnostics.INSTANCE.enabled());
                try{MODEL_SHADOW.starfall(engine.starfallGpu().committedBuffer());var p=camera.getPosition();MODEL_SHADOW.prepare(gpu.particleReadBufferId(),gpu.emitterBufferId(),engine.committedCounterBuffer(),SHADOW_CULLING,
                        (float)p.x,(float)p.y,(float)p.z,EntityType.ALLAY.getWidth(),EntityType.ALLAY.getHeight(),(float)net.minecraft.world.entity.Entity.getViewScale(),engine.timeSec(),engine.stormWaveUniform(),engine.stormWaveTargetUniform(),engine.stormWaveTierUniform(),gpu.populationBufferId(engine.currentGeneration()),engine.currentGeneration());}
                finally{SHADOW_PREP_TIMING.end();}
                gpu.bindMergedTbos(ShaderPackProgramCompiler.MERGED_SAMPLER_UNIT_BASE,gpu.particleReadBufferId(),MODEL_SHADOW.instances());gpu.bindVao();GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER,MODEL_SHADOW.commands());
                ShaderInstance shader=COMPILER.shadowShader();boolean applied=false;
                try{
                    applied=true;shader.apply();uploadShadowUniforms(shader.getId(),engine);setInt(shader.getId(),"cmi_ShadowPass",1);
                    RenderSystem.enableDepthTest();RenderSystem.disableBlend();RenderSystem.depthMask(true);RenderSystem.enableCull();bindAtlas(engine,COMPILER.shadowGtextureUnit());
                    uploadSegmentMode(shader.getId(),engine,0);gpu.drawModelCutout();uploadSegmentMode(shader.getId(),engine,2);gpu.drawModelCarrier();uploadSegmentMode(shader.getId(),engine,0);gpu.drawModelGhost();
                }finally{if(applied)shader.clear();}
                engine.shaderPackShadowStatus="active (frame="+engine.renderFrameToken()+", generation="+engine.currentGeneration()+", age=0, storage="+MODEL_SHADOW.bytes()+"B; "+SHADOW_PREP_TIMING.report()+")";
            }finally{
                if(depth)RenderSystem.enableDepthTest();else RenderSystem.disableDepthTest();if(blend)RenderSystem.enableBlend();else RenderSystem.disableBlend();if(cull)RenderSystem.enableCull();else RenderSystem.disableCull();RenderSystem.depthMask(depthMask);
                captured.setCurrentAlphaTest(alpha);
                net.irisshaders.iris.vertices.ImmediateState.usingTessellation=tessellation;
                RenderSystem.blendFuncSeparate(srcRgb,dstRgb,srcAlpha,dstAlpha);
                shadowDrawState.restore();
            }
            } finally {shadowComputeState.restore();}
        }catch(RuntimeException|LinkageError error){engine.shaderPackShadowStatus="draw failed";CreateManaIndustry.LOGGER.warn("[CMI particles] current-frame model shadow failed",error);}
    }

    /**
     * Shadow-pass uniform refresh: same protocol as the gbuffer segments except
     * the matrices come from Iris's shadow camera. The vertex INPUT SPACE is the
     * same camera-relative level space as the gbuffer pass: ShadowMatrices.
     * createModelViewMatrix bakes NO camera translation into MODELVIEW -- only
     * the sub-grid snap remainder -- while native geometry arrives relative to
     * the UNSHIFTED camera position (entities via pose translation, terrain via
     * per-section offsets). So cmi_CameraPos here feeds exactly what the gbuffer
     * path feeds, and our vertex main needs no shadow variant at all.
     */
    private static void uploadShadowUniforms(int progId, CMIParticleEngine engine) {
        Matrix4f projection = new Matrix4f(ShadowRenderer.PROJECTION);
        Matrix4f modelView = new Matrix4f(ShadowRenderer.MODELVIEW);
        uploadIrisMatrices(progId, projection, modelView);
        uploadNeutralEntityUniforms(progId);
        var camPos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        setFloat3(progId, "cmi_CameraPos", (float) camPos.x, (float) camPos.y, (float) camPos.z);
        setFloat(progId, "cmi_FadeDist", (float) ClientConfig.particleFadeDistance);
        // the shared simulation clock — without it the wave-claim window test
        // (chunks/allay_storm.glsl) never fires and swords drop out of shadows
        setFloat(progId, "uTimeSec", engine.timeSec());
        // Round-trip insurance for packs whose merged program is the plain
        // terrain-fallback shadow variant (no shadow_entities source): its
        // vertex stage routes positions through the NAMED matrices
        // shadowModelView/shadowModelViewInverse/cameraPosition. Push them to
        // agree with OUR uploads so the round-trip stays exact regardless of
        // how Iris's uniform updaters behave during an external apply(). All
        // three are unused by the entities variant -- locations read -1.
        setMat(progId, "shadowModelView", modelView);
        setMat(progId, "shadowModelViewInverse", new Matrix4f(modelView).invert());
        setFloat3(progId, "cameraPosition", (float) camPos.x, (float) camPos.y, (float) camPos.z);
        setSamplers(progId);
        engine.starfallGpu().bindTexture(ShaderPackProgramCompiler.MERGED_SAMPLER_UNIT_BASE+4);
        setInt(progId,"uStarfallEnabled",engine.starfallGpu().committedBuffer()>0?1:0);setInt(progId,"cmi_Starfall",ShaderPackProgramCompiler.MERGED_SAMPLER_UNIT_BASE+4);
        uploadStormItemUniforms(progId, engine);
    }

    // ------------------------------------------------------------------
    // Draw
    // ------------------------------------------------------------------

    private static void drawEntities(CMIParticleEngine engine) {
        var gpu = engine.gpuBuffers();

        bindSharedResources(engine, gpu);
        gpu.bindDrawIndirect();

        // Cutout segment: native solid-entity treatment -- no blending, depth
        // writes everywhere (early-Z feeder for later passes). The program
        // inherits the pack's entity directives wholesale.
        ShaderInstance shader = COMPILER.entitiesShader();
        shader.apply();
        int progId = shader.getId();
        uploadSegmentUniforms(progId, engine);
        // Nearest-first cutout: early-Z rejects occluded fragments before the
        // pack's fragment shader runs -- the dense-swarm fragment-pressure fix.
        uploadSegmentMode(progId, engine, 1);
        RenderSystem.enableDepthTest();
        GL11.glEnable(GL11.GL_CULL_FACE);
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        bindAtlas(engine, COMPILER.gtextureUnit());
        gpu.drawModelCutout();
        // Held-item carrier segment: same cutout class as the body (depth
        // writes, no blending), so it rides the body's shader state — drawn
        // after the body, before the blended ghost, mirroring the L0 order.
        uploadSegmentMode(progId, engine, 2);
        gpu.drawModelCarrier();
        shader.clear();

        // Ghost segment: same geometry stream and inherited alpha test, but the
        // pinned translucent blend applies (ExtendedShader.apply() overrides GL
        // blend state itself; we mirror it through RenderSystem so the state
        // tracker stays consistent). Depth writes stay ON -- the documented L0
        // tradeoff lets ghost surfaces occlude later translucent passes.
        ShaderInstance ghost = COMPILER.ghostShader();
        ghost.apply();
        progId = ghost.getId();
        uploadSegmentUniforms(progId, engine);
        // Ghost keeps forward reads: back-to-front order is load-bearing for
        // correct translucent compositing of overlapping shells.
        uploadSegmentMode(progId, engine, 0);
        RenderSystem.enableDepthTest();
        GL11.glEnable(GL11.GL_CULL_FACE);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        RenderSystem.depthMask(true);
        bindAtlas(engine, COMPILER.ghostGtextureUnit());
        gpu.drawModelGhost();
        ghost.clear();

        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        GL30.glBindVertexArray(0);
        GL20.glUseProgram(0);

        gpu.unbindMergedTbos(ShaderPackProgramCompiler.MERGED_SAMPLER_UNIT_BASE);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
    }

    /**
     * Per-program uniform refresh for one merged segment: the iris_* matrices
     * are NOT refreshed outside Iris's own draw stream, so supply the current
     * gbuffer matrices ourselves (same protocol as flw-compat), then neutralise
     * the stale entity uniforms and push our data uniforms.
     */
    private static void uploadSegmentUniforms(int progId, CMIParticleEngine engine) {
        Matrix4f projection = new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferProjection());
        Matrix4f modelView = new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView());
        uploadIrisMatrices(progId, projection, modelView);
        uploadNeutralEntityUniforms(progId);
        uploadCmiUniforms(progId, engine);
        setSamplers(progId);
    }

    /** Points the merged program's samplerBuffer uniforms at our TBO units. */
    private static void setSamplers(int progId) {
        int base = ShaderPackProgramCompiler.MERGED_SAMPLER_UNIT_BASE;
        setInt(progId, "cmi_Geo", base);
        setInt(progId, "cmi_Pool", base + 1);
        setInt(progId, "cmi_Emitters", base + 2);
        setInt(progId, "cmi_Sorted", base + 3);
    }

    private static void setMat(int progId, String name, Matrix4f m) {
        int l = loc(progId, name);
        if (l >= 0)
            GL20.glUniformMatrix4fv(l, false, m.get(new float[16]));
    }

    private static void bindSharedResources(CMIParticleEngine engine, ParticleBuffers gpu) {
        // Merged programs read particle data through TBO views pinned to fixed
        // texture units; plain sampler declarations survive every in-game pass
        // where interface blocks demonstrably do not.
        int permId = engine.lastFinalPermBufferId();
        gpu.bindMergedTbos(ShaderPackProgramCompiler.MERGED_SAMPLER_UNIT_BASE,
                gpu.particleReadBufferId(),
                permId >= 0 ? permId : gpu.sortBuffer(0));
        gpu.bindVao();
    }

    /**
     * Iris supplies {@code entityId}/{@code entityColor} only during its own
     * entity stream; outside it they hold stale values from the last rendered
     * entity. Zero them so pack-side id-keyed branches (sign text, end portal,
     * hurt flash...) cannot misfire on our instances. The mask-40 glow we WANT
     * comes from the full-bright lightmap rule, not from these.
     */
    private static void uploadNeutralEntityUniforms(int progId) {
        setInt(progId, "entityId", 0);
        setInt(progId, "blockEntityId", 0);
        setInt(progId, "currentRenderedItemId", 0);
        setFloat4(progId, "entityColor", 0f, 0f, 0f, 0f);
    }

    private static void uploadIrisMatrices(int progId, Matrix4f projection, Matrix4f modelView) {
        int projLoc = GL20.glGetUniformLocation(progId, "iris_ProjMat");
        if (projLoc >= 0)
            GL20.glUniformMatrix4fv(projLoc, false, projection.get(new float[16]));
        int mvLoc = GL20.glGetUniformLocation(progId, "iris_ModelViewMat");
        if (mvLoc >= 0)
            GL20.glUniformMatrix4fv(mvLoc, false, modelView.get(new float[16]));
        int normalLoc = GL20.glGetUniformLocation(progId, "iris_NormalMat");
        if (normalLoc >= 0) {
            Matrix4f inv = new Matrix4f(modelView).invert();
            Matrix3f normalMat = new Matrix3f(inv.transpose());
            GL20.glUniformMatrix3fv(normalLoc, false, normalMat.get(new float[9]));
        }
    }

    private static void uploadCmiUniforms(int progId, CMIParticleEngine engine) {
        setInt(progId,"cmi_ShadowPass",0);
        var camPos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        setFloat3(progId, "cmi_CameraPos", (float) camPos.x, (float) camPos.y, (float) camPos.z);
        setFloat(progId, "cmi_FadeDist", (float) ClientConfig.particleFadeDistance);
        setFloat(progId, "uTimeSec", engine.timeSec());
        engine.starfallGpu().bindTexture(ShaderPackProgramCompiler.MERGED_SAMPLER_UNIT_BASE+4);
        setInt(progId,"uStarfallEnabled",engine.starfallGpu().committedBuffer()>0?1:0);setInt(progId,"cmi_Starfall",ShaderPackProgramCompiler.MERGED_SAMPLER_UNIT_BASE+4);
        uploadStormItemUniforms(progId, engine);
    }

    /**
     * Held-item uniforms (mirror of the engine's uploadStormItemUniforms): the
     * dive-wave staging feeding the carrier predicate and sword tier, plus the
     * per-tier atlas UV rects. Same names and staging the self-drawn model.vsh
     * consumes; cheap on stormless frames.
     */
    private static void uploadStormItemUniforms(int progId, CMIParticleEngine engine) {
        setVec4Array(progId, "uWave", engine.stormWaveUniform());
        setVec4Array(progId, "uWaveTarget", engine.stormWaveTargetUniform());
        setFloatArray(progId, "uWaveTier", engine.stormWaveTierUniform());
        setVec4Array(progId, "uHeldItemUV",
                com.iridium126.createmanaindustry.client.particles.engine.ParticlePrograms.heldItemUVTable());
    }

    /**
     * Segment-selection uniforms shared by every merged program variant. The
     * metadata slot index sits BEHIND the held-item carrier region (index ==
     * capacity + CARRIER_CAP, read from the live buffer sizing rather than
     * config so the two can never drift), and {@code cmi_CarrierBase} is the
     * carrier region's fixed base (== capacity); {@code segmentMode} selects
     * the vertex-stage instance resolution: 0 = ghost (forward), 1 = cutout
     * (nearest-first reversed read, see the reversed-read comment in the
     * merged vertex source), 2 = held-item carrier region (forward, exact
     * count).
     */
    private static void uploadSegmentMode(int progId, CMIParticleEngine engine, int segmentMode) {
        setInt(progId, "cmi_MetaSlot", engine.gpuBuffers().metaSlotIndex());
        setInt(progId, "cmi_CarrierBase", engine.gpuBuffers().carrierBase());
        setInt(progId, "cmi_SegmentMode", segmentMode);
    }

    /** The pack fragment samples the entity texture through its gtexture sampler. */
    private static void bindAtlas(CMIParticleEngine engine, int unit) {
        if (unit < 0)
            return;
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, engine.modelAtlasTextureId());
    }

    // ------------------------------------------------------------------
    // Timer ring (never blocks)
    // ------------------------------------------------------------------

    /**
     * Begins a timer bracket ONLY when the GL_TIME_ELAPSED target is free
     * (vanilla's Alt+F3 profiler and other timer users can own it); returns
     * false when the bracket was skipped — the caller must then not run
     * {@link #endTimer}, which would end the foreign query or error.
     */
    private static boolean beginTimer(CMIParticleEngine engine) {
        if (!timerReady) {
            GL30.glGenQueries(TIMER_QUERIES);
            timerReady = true;
        }
        if (ParticleGLUtil.activeTimeElapsedQuery() != 0)
            return false;
        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, TIMER_QUERIES[timerSlot]);
        return true;
    }

    private static void endTimer(CMIParticleEngine engine) {
        GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
        timerSlot = (timerSlot + 1) % TIMER_QUERIES.length;
        if (++timerIssued >= TIMER_QUERIES.length) {
            int oldest = TIMER_QUERIES[timerSlot];
            if (GL15.glGetQueryObjecti(oldest, GL15.GL_QUERY_RESULT_AVAILABLE) == GL11.GL_TRUE)
                engine.addExternalGpuMs(GL15.glGetQueryObjectui(oldest, GL15.GL_QUERY_RESULT) / 1_000_000.0);
        }
    }

    // ------------------------------------------------------------------
    // Uniform helpers
    // ------------------------------------------------------------------

    private static int loc(int prog, String name) {
        return UNIFORMS.computeIfAbsent(prog, k -> new HashMap<>())
                .computeIfAbsent(name, n -> GL20.glGetUniformLocation(prog, n));
    }

    private static void setInt(int prog, String name, int v) {
        int l = loc(prog, name);
        if (l >= 0) GL20.glUniform1i(l, v);
    }

    private static void setFloat(int prog, String name, float v) {
        int l = loc(prog, name);
        if (l >= 0) GL20.glUniform1f(l, v);
    }

    private static void setFloat3(int prog, String name, float x, float y, float z) {
        int l = loc(prog, name);
        if (l >= 0) GL20.glUniform3f(l, x, y, z);
    }

    private static void setFloat4(int prog, String name, float x, float y, float z, float w) {
        int l = loc(prog, name);
        if (l >= 0) GL20.glUniform4f(l, x, y, z, w);
    }

    private static void setVec4Array(int prog, String name, float[] values) {
        int l = loc(prog, name);
        if (l >= 0 && values != null && values.length >= 4)
            GL20.glUniform4fv(l, values);
    }

    private static void setFloatArray(int prog, String name, float[] values) {
        int l = loc(prog, name);
        if (l >= 0 && values != null && values.length > 0)
            GL20.glUniform1fv(l, values);
    }
}
