package com.iridium126.createmanaindustry.client.particles.shaderpack;

import com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms;

import com.iridium126.createmanaindustry.client.gpu.state.GpuBindings;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuPrograms;

import com.iridium126.createmanaindustry.client.particles.engine.ShadowQualityFrame;

import com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackStatusReport;
import com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackStatusReport.State;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.particles.engine.ParticleBuffers;
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
import org.lwjgl.opengl.GL40;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.entity.EntityType;
import com.iridium126.createmanaindustry.client.particles.engine.ModelShadowGpu;
import com.iridium126.createmanaindustry.client.particles.engine.ParticlePrograms;
import com.iridium126.createmanaindustry.client.gpu.state.GpuComputeState;
import com.iridium126.createmanaindustry.client.packages.render.PackageDrawCulling;
import com.iridium126.createmanaindustry.client.gpu.profile.GpuPassTelemetry;
import com.iridium126.createmanaindustry.client.packages.render.PackageRenderState;


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
 * {@link ParticleRuntime#markHookModelDrawn()}, so the engine's AFTER_LEVEL
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

    private static final com.iridium126.createmanaindustry.client.gpu.profile.GpuTimestampRing MERGE_TIMER=
            new com.iridium126.createmanaindustry.client.gpu.profile.GpuTimestampRing(4,ms->com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.addExternalGpuMs(ms),(frame,generation,ms)->com.iridium126.createmanaindustry.client.gpu.profile.GpuTimingObserver.sample("draw.pack.merge",frame,generation,Double.NaN,ms));

    /** Uniform-location cache (program id -> name -> location). */
    private static boolean rebuildListenerRegistered = false;

    private static ModelShadowGpu MODEL_SHADOW;
    private static final PackageDrawCulling SHADOW_CULLING=new PackageDrawCulling();
    private static final Matrix4f shadowReceiverMatrix=new Matrix4f(),shadowInverseMatrix=new Matrix4f();
    private static final Matrix4f shadowProjectionUpload=new Matrix4f(),shadowViewUpload=new Matrix4f(),shadowViewInverseUpload=new Matrix4f();
    private static final org.joml.Vector4f shadowPlane=new org.joml.Vector4f();
    private static final org.joml.Vector3f shadowRay=new org.joml.Vector3f();
    private static final float[] shadowReceiverPlanes=new float[24];
    private static String geometricFallback="";
    private static final GpuPassTelemetry SHADOW_TIMING=new GpuPassTelemetry().named("draw.pack.model-shadow");
    private static final GpuPassTelemetry SHADOW_PREPARE=new GpuPassTelemetry().named("draw.pack.model-shadow.prepare");
    private static final GpuPassTelemetry SHADOW_DRAW=new GpuPassTelemetry().named("draw.pack.model-shadow.draw");
    private static final ModelMergeTelemetry DRAW_TIMING=new ModelMergeTelemetry();
    private static boolean timingEnabled,detailed;
    public static void measurementMode(boolean timing,boolean details) {
        if(timingEnabled!=timing||detailed!=details)MERGE_TIMER.close();
        timingEnabled=timing;detailed=details;SHADOW_TIMING.configure(timing,details);SHADOW_PREPARE.configure(timing,details);SHADOW_DRAW.configure(timing,details);DRAW_TIMING.configure(details);
    }
    /** A pack/options/viewport change starts a fresh, internally consistent profile window. */
    public static void restartMeasurementWindow(){
        MERGE_TIMER.close();SHADOW_TIMING.close();SHADOW_PREPARE.close();SHADOW_DRAW.close();DRAW_TIMING.close();
        SHADOW_TIMING.configure(timingEnabled,detailed);SHADOW_PREPARE.configure(timingEnabled,detailed);SHADOW_DRAW.configure(timingEnabled,detailed);DRAW_TIMING.configure(detailed);
    }
    public static void resetMeasurements(){MERGE_TIMER.close();SHADOW_TIMING.close();SHADOW_PREPARE.close();SHADOW_DRAW.close();DRAW_TIMING.close();timingEnabled=detailed=false;}
    public static void discardPendingMeasurements(){MERGE_TIMER.close();SHADOW_TIMING.discardPending();SHADOW_PREPARE.discardPending();SHADOW_DRAW.discardPending();DRAW_TIMING.discardPending();}
    public static String profilingReport(){return mergeReport(true);}
    public static String profilingSummary(){return mergeReport(false);}
    private static String mergeReport(boolean details){String draw=DRAW_TIMING.report(details);return draw+(SHADOW_TIMING.cpuSamples()==0?"":(draw.isEmpty()?"":"\n")+"MODEL shadow: "+(com.iridium126.createmanaindustry.client.particles.engine.ParticleDiagnostics.INSTANCE.timing(SHADOW_TIMING,details)));}
    private static GpuComputeState shadowComputeState;
    private static PackageRenderState shadowDrawState;
    private static final com.iridium126.createmanaindustry.client.gpu.state.GpuTextureBufferState mergedTboState=
            new com.iridium126.createmanaindustry.client.gpu.state.GpuTextureBufferState(ShaderPackProgramCompiler.MERGED_SAMPLER_UNIT_BASE,ShaderPackProgramCompiler.MERGED_SAMPLER_UNIT_COUNT);
    public static void invalidateShadow(){if(MODEL_SHADOW!=null)MODEL_SHADOW.close();MODEL_SHADOW=null;SHADOW_TIMING.close();SHADOW_PREPARE.close();SHADOW_DRAW.close();MERGE_TIMER.close();DRAW_TIMING.close();DRAW_TIMING.configure(detailed);}
    public static void invalidatePrograms(){
        invalidateShadow();COMPILER.reset();com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clear();
        var renderer=com.iridium126.createmanaindustry.client.render.scene.ParticleRenderer.INSTANCE;
        renderer.shaderPackPathStatus="self-drawn";renderer.shaderPackDepthStatus="pending";
        renderer.shaderPackShadowStatus="pending";renderer.shaderPackPermStatus="pending";renderer.shaderPackErrorStatus="";
    }
    private CMIPackEntityMergeHook() {}
    public static ShaderPackStatusReport.Section diagnostics(){
        var renderer=com.iridium126.createmanaindustry.client.render.scene.ParticleRenderer.INSTANCE;
        boolean current=COMPILER.currentSession();
        String error=current?(COMPILER.lastError().isEmpty()?renderer.shaderPackErrorStatus:COMPILER.lastError()):"";
        if(current&&!COMPILER.shadowLastError().isEmpty())error+=(error.isEmpty()?"":"; ")+"shadow: "+COMPILER.shadowLastError();
        else if(current&&renderer.shaderPackShadowStatus.startsWith("draw failed"))error+=(error.isEmpty()?"":"; ")+renderer.shaderPackShadowStatus;
        if(!ClientConfig.particleEnabled||!ClientConfig.shaderPackIntegration)error="";
        var state=!ClientConfig.particleEnabled||!ClientConfig.shaderPackIntegration?State.OFF:
                !current?State.PENDING:
                !COMPILER.isReady()||!error.isEmpty()?State.FALLBACK:
                State.ACTIVE;
        var atlas=com.iridium126.createmanaindustry.client.particles.engine.ParticleAtlas.ALLAY;
        return new ShaderPackStatusReport.Section("MODEL",state,
                !ClientConfig.particleEnabled?"disabled":ClientConfig.shaderPackIntegration&&current&&COMPILER.isReady()?"pack entity merge":"self-drawn",error,
                ShaderPackStatusReport.field("depth",current?renderer.shaderPackDepthStatus:"pending"),
                ShaderPackStatusReport.field("shadow",!ShadowQualityFrame.current().castsShadows()?"off (quality OFF)":current?renderer.shaderPackShadowStatus:"pending"),
                ShaderPackStatusReport.field("shadow quality",ShadowQualityFrame.current().label()),
                ShaderPackStatusReport.field("shadow groups storage",ParticleBuffers.INSTANCE.shadowGroups.bytes()+" B (allocated, not physical residency)"),
                ShaderPackStatusReport.field("receiving",COMPILER.receiverStatus()),
                ShaderPackStatusReport.field("identity",current?"allay="+COMPILER.entityContext().allayId()+"; carrier=0":"pending"),
                ShaderPackStatusReport.field("atlas",atlas.width()+"x"+atlas.height()),
                ShaderPackStatusReport.field("blend",current?COMPILER.blendPlan().report():"pending"),
                ShaderPackStatusReport.field("optimization",current?COMPILER.optimizationReport():"pending"),
                ShaderPackStatusReport.field("shadow optimization",current?COMPILER.shadowOptimization():"pending"),
                ShaderPackStatusReport.field("feather",current?COMPILER.featherOptimization():"pending"),
                ShaderPackStatusReport.field("source program",current?COMPILER.sourceProgram():"pending"),
                ShaderPackStatusReport.field("programs",current&&COMPILER.isReady()?"body="+COMPILER.entitiesShader().getId()+"; carrier="+COMPILER.carrierShader().getId()+"; ghost="+COMPILER.ghostShader().getId():"pending"),
                ShaderPackStatusReport.field("generation",current?renderer.shaderPackPermStatus:"pending"));
    }

    // ------------------------------------------------------------------
    // Entry point (mixin, render thread)
    // ------------------------------------------------------------------

    /** Called from {@code LevelRendererBlockEntitiesMixin} inside renderLevel. */
    public static void render() {
        try(var diagnostic=com.iridium126.createmanaindustry.client.particles.engine.ParticleFrameStages.begin("submission.pack.merge-whole")) {
        try(var accounting=com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.budgets.prepare()) {
            if (!ClientConfig.shaderPackIntegration
                    || !IrisVeilCompat.isShaderPackInUse()
                    || com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.publication()==null
                    || !com.iridium126.createmanaindustry.client.particles.engine.ParticleAtlas.ALLAY.ready())
                return;

            if (!ensureCompiledWithListener())
                return; // sticky failure recorded; the engine's plain AFTER_LEVEL path takes over

            boolean timed=beginTimer(),succeeded=false;
            try(var drawState=new ModelRenderState();
                var state=new com.iridium126.createmanaindustry.client.allaystorm.rift.SkyRenderState();
                var materialState=com.iridium126.createmanaindustry.client.gpu.state.GpuTexture2DState.withSamplerRange(net.irisshaders.iris.gl.IrisRenderSystem::bindSamplerToUnit,net.irisshaders.iris.gl.sampler.SamplerLimits.get().getMaxTextureUnits(),0,1,2,COMPILER.gtextureUnit(),COMPILER.ghostGtextureUnit(),COMPILER.carrierGtextureUnit())){
                mergedTboState.capture();
                try {drawEntities();succeeded=true;}
                finally {net.irisshaders.iris.gl.blending.BlendModeStorage.restoreBlend();mergedTboState.restore();if(timed)endTimer(succeeded);}
            }

            // Latch even when this frame's instance count is zero: skipping the
            // L0 drawModels then costs nothing (it would submit the same empty
            // commands), while a CPU-side per-type readback would be a stall.
            com.iridium126.createmanaindustry.client.render.scene.ParticleRenderer.INSTANCE.markHookModelDrawn();
            com.iridium126.createmanaindustry.client.render.scene.ParticleRenderer.INSTANCE.shaderPackPathStatus = "pack entity merge (allayId="+COMPILER.entityContext().allayId()+", "+COMPILER.blendPlan().report()+", atlas="+com.iridium126.createmanaindustry.client.particles.engine.ParticleAtlas.ALLAY.width()+"x"+com.iridium126.createmanaindustry.client.particles.engine.ParticleAtlas.ALLAY.height()+")";
            com.iridium126.createmanaindustry.client.render.scene.ParticleRenderer.INSTANCE.shaderPackDepthStatus = "hardware (gbuffer)";
            com.iridium126.createmanaindustry.client.render.scene.ParticleRenderer.INSTANCE.shaderPackErrorStatus = "";
            com.iridium126.createmanaindustry.client.render.scene.ParticleRenderer.INSTANCE.shaderPackPermStatus = "gbuffer permutation age " + com.iridium126.createmanaindustry.client.render.scene.ParticleRenderer.INSTANCE.lastFinalPermAgeFrames()
                    + "f (0 = this frame's cull)";
        } catch (RuntimeException | LinkageError e) {
            CreateManaIndustry.LOGGER.warn("[CMI particles] pack entity merge draw failed", e);
            com.iridium126.createmanaindustry.client.render.scene.ParticleRenderer.INSTANCE.shaderPackErrorStatus = String.valueOf(e);
        }
        }
    }

    private static boolean ensureCompiledWithListener() {
        if (!rebuildListenerRegistered) {
            rebuildListenerRegistered = true;
            COMPILER.setOnRebuild(com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms::clear);
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

        if(!ShadowQualityFrame.current().castsShadows()){
            com.iridium126.createmanaindustry.client.render.scene.ParticleRenderer.INSTANCE.shaderPackShadowStatus="off (quality OFF)";return;
        }
        if(frustum instanceof net.irisshaders.iris.shadows.frustum.CullEverythingFrustum){
            com.iridium126.createmanaindustry.client.render.scene.ParticleRenderer.INSTANCE.shaderPackShadowStatus="off (Iris rejected all entities)";return;
        }

        if(!com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.currentFrameReady()){com.iridium126.createmanaindustry.client.render.scene.ParticleRenderer.INSTANCE.shaderPackShadowStatus="current frame unavailable";return;}
        if(!ClientConfig.shaderPackIntegration||!IrisVeilCompat.isShaderPackInUse()||com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.publication()==null)return;
        if(!com.iridium126.createmanaindustry.client.particles.engine.ParticleAtlas.ALLAY.ready())return;
        try(var accounting=com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.budgets.prepare()){
            if(!PackageShadowPolicy.copy(frustum,SHADOW_CULLING)){com.iridium126.createmanaindustry.client.render.scene.ParticleRenderer.INSTANCE.shaderPackShadowStatus="unsupported shadow frustum: "+(frustum==null?"null":frustum.getClass().getName());return;}
            // The enclosing compute snapshot already restores the shadow pass resource bindings.
            if(shadowComputeState==null){shadowComputeState=new GpuComputeState();shadowDrawState=new PackageRenderState(false);}
            shadowComputeState.capture();
            try(var modelState=new ModelRenderState()) {
            shadowDrawState.capture();
            try{
                // First-use shader construction belongs inside the same boundary as drawing.
                if(!ensureCompiledWithListener()){com.iridium126.createmanaindustry.client.render.scene.ParticleRenderer.INSTANCE.shaderPackShadowStatus="merge failed";return;}
                if(!COMPILER.shadowReady()){com.iridium126.createmanaindustry.client.render.scene.ParticleRenderer.INSTANCE.shaderPackShadowStatus="no shadow track";return;}
                var gpu=ParticleBuffers.INSTANCE;
                if(MODEL_SHADOW==null||MODEL_SHADOW.capacity()!=gpu.capacity()||MODEL_SHADOW.carrierCapacity()!=gpu.carrierCapacity()){invalidateShadow();MODEL_SHADOW=new ModelShadowGpu(gpu.capacity(),gpu.carrierCapacity(),ParticlePrograms::loadParticlePlain);}
                int rtVisibility=configureShadowCulling(gpu,camera);
                MODEL_SHADOW.visibility(rtVisibility);
                com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.addExternalGpuMs(SHADOW_TIMING.poll());
                SHADOW_TIMING.begin(timingEnabled,detailed,com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.renderFrameToken(),com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.publication().generation());
                boolean shadowSucceeded=false;
                try{var scene=com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE;SHADOW_PREPARE.poll();SHADOW_DRAW.poll();SHADOW_PREPARE.begin(timingEnabled,detailed,scene.renderFrameToken(),scene.publication().generation());boolean prepared=false;
                    try{MODEL_SHADOW.starfall(com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.INSTANCE.committedBuffer(),com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.displayTimeSec()-com.iridium126.createmanaindustry.client.allaystorm.AllayStormRuntime.INSTANCE.timeSec());var p=camera.getPosition();MODEL_SHADOW.prepare(gpu.particleDrawBufferId(),gpu.particleDrawEmitterBufferId(),com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.publication().counter(),SHADOW_CULLING,
                        (float)p.x,(float)p.y,(float)p.z,EntityType.ALLAY.getWidth(),EntityType.ALLAY.getHeight(),(float)net.minecraft.world.entity.Entity.getViewScale(),com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.displayTimeSec(),null,null,null,gpu.populationBufferId(com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.publication().generation()),com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.publication().generation());
                    com.iridium126.createmanaindustry.client.particles.engine.ClientFrameCapture.INSTANCE.visibilitySubmitted(rtVisibility,true);
                    scene.prepareParticleShadowPoses(MODEL_SHADOW.commands(),MODEL_SHADOW.instances());
                    prepared=true;}finally{SHADOW_PREPARE.end(prepared);}
                SHADOW_DRAW.begin(timingEnabled,detailed,scene.renderFrameToken(),scene.publication().generation());boolean drawn=false;
                try{
                gpu.bindMergedTbos(ShaderPackProgramCompiler.MERGED_SAMPLER_UNIT_BASE,gpu.particleDrawBufferId(),MODEL_SHADOW.instances());gpu.bindVao();GpuBindings.bind(GL40.GL_DRAW_INDIRECT_BUFFER,MODEL_SHADOW.commands());
                ShaderInstance shader=COMPILER.shadowShader();boolean applied=false;
                try(var textures=com.iridium126.createmanaindustry.client.gpu.state.GpuTexture2DState.withSamplerRange(net.irisshaders.iris.gl.IrisRenderSystem::bindSamplerToUnit,net.irisshaders.iris.gl.sampler.SamplerLimits.get().getMaxTextureUnits(),0,1,2,COMPILER.shadowGtextureUnit())){
                    try{bindAtlas(COMPILER.shadowGtextureUnit());applied=true;GpuPrograms.applyExternal(shader);uploadShadowUniforms(shader.getId());setInt(shader.getId(),"cmi_ShadowPass",1);
                    RenderSystem.enableDepthTest();RenderSystem.disableBlend();RenderSystem.depthMask(true);RenderSystem.enableCull();bindAtlas(COMPILER.shadowGtextureUnit());
                    uploadSegmentMode(shader.getId(),0);gpu.drawModelCutout();uploadSegmentMode(shader.getId(),2);COMPILER.entityContext().upload(shader.getId(),true);gpu.drawModelCarrier();uploadSegmentMode(shader.getId(),0);COMPILER.entityContext().upload(shader.getId(),false);gpu.drawModelGhost();
                    }finally{if(applied)GpuPrograms.clearExternal(shader);}
                }
                drawn=true;}finally{SHADOW_DRAW.end(drawn);}
                shadowSucceeded=true;
                com.iridium126.createmanaindustry.client.particles.engine.ClientFrameCapture.INSTANCE.shadowSubmitted(scene.renderFrameToken(),scene.displayPublicationId(),scene.publication().generation(),MODEL_SHADOW.commands());
                }finally{SHADOW_TIMING.end(shadowSucceeded);}
                com.iridium126.createmanaindustry.client.render.scene.ParticleRenderer.INSTANCE.shaderPackShadowStatus="active (current frame, age=0); "+ShadowQualityFrame.current().label()+geometricFallback;
            }finally{
                shadowDrawState.restoreVanillaState();
                shadowDrawState.restore();
            }
            } finally {shadowComputeState.restore();}
        }catch(RuntimeException|LinkageError error){com.iridium126.createmanaindustry.client.render.scene.ParticleRenderer.INSTANCE.shaderPackShadowStatus="draw failed: "+error;CreateManaIndustry.LOGGER.warn("[CMI particles] current-frame model shadow failed",error);}
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
    private static void uploadShadowUniforms(int progId) {
        setInt(progId,"cmi_FeatherPass",0);setInt(progId,"cmi_LodPass",0);
        Matrix4f projection = shadowProjectionUpload.set(ShadowRenderer.PROJECTION);
        Matrix4f modelView = shadowViewUpload.set(ShadowRenderer.MODELVIEW);
        uploadIrisMatrices(progId, projection, modelView);
        uploadNeutralEntityUniforms(progId);
        var camPos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        setFloat3(progId, "cmi_CameraPos", (float) camPos.x, (float) camPos.y, (float) camPos.z);
        setFloat(progId, "cmi_FadeDist", (float) ClientConfig.particleFadeDistance);
        // the shared simulation clock — without it the wave-claim window test
        // (chunks/allay_storm.glsl) never fires and swords drop out of shadows
        setFloat(progId, "uTimeSec", com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.displayTimeSec());
        // Round-trip insurance for packs whose merged program is the plain
        // terrain-fallback shadow variant (no shadow_entities source): its
        // vertex stage routes positions through the NAMED matrices
        // shadowModelView/shadowModelViewInverse/cameraPosition. Push them to
        // agree with OUR uploads so the round-trip stays exact regardless of
        // how Iris's uniform updaters behave during an external apply(). All
        // three are unused by the entities variant -- locations read -1.
        setMat(progId, "shadowModelView", modelView);
        setMat(progId, "shadowModelViewInverse", shadowViewInverseUpload.set(modelView).invert());
        setFloat3(progId, "cameraPosition", (float) camPos.x, (float) camPos.y, (float) camPos.z);
        setSamplers(progId);
        com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.INSTANCE.bindTexture(ShaderPackProgramCompiler.MERGED_SAMPLER_UNIT_BASE+4);
        com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.INSTANCE.bindPose(progId);
        com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.INSTANCE.bindRenderClock(progId,com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.displayTimeSec()-com.iridium126.createmanaindustry.client.allaystorm.AllayStormRuntime.INSTANCE.timeSec());
        ParticleBuffers.INSTANCE.bindAllayAnimation(progId);
        setInt(progId,"uStarfallEnabled",com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.INSTANCE.committedBuffer()>0?1:0);setInt(progId,"cmi_Starfall",ShaderPackProgramCompiler.MERGED_SAMPLER_UNIT_BASE+4);
        uploadStormItemUniforms(progId);
    }

    /** Read-only capture metadata; does not compile programs or query GL state. */
    public static String performanceShadowPath(){
        return "casting="+com.iridium126.createmanaindustry.client.render.scene.ParticleRenderer.INSTANCE.shaderPackShadowStatus
                +"; receiving="+COMPILER.receiverStatus();
    }
    private static int configureShadowCulling(ParticleBuffers gpu,Camera camera){
        geometricFallback="";
        var quality=ShadowQualityFrame.current();
        boolean rt=quality==com.iridium126.createmanaindustry.infrastructure.config.ParticleShadowQuality.RT;
        if(rt&&!ParticleShadowQualification.current()){
            geometricFallback="; RT light=unqualified pack/options";
            MODEL_SHADOW.culling(null,0,0,0,false,0);
            return 0;
        }
        if(!rt&&!quality.groupsStorm()){MODEL_SHADOW.culling(null,0,0,0,false,0);return 0;}
        var captured=CapturedRenderingState.INSTANCE;
        if(rt)shadowReceiverMatrix.set(ShadowRenderer.PROJECTION).mul(ShadowRenderer.MODELVIEW);
        else shadowReceiverMatrix.set(captured.getGbufferProjection()).mul(captured.getGbufferModelView());
        for(int i=0;i<6;i++){shadowReceiverMatrix.frustumPlane(i,shadowPlane);int j=i*4;
            shadowReceiverPlanes[j]=shadowPlane.x;shadowReceiverPlanes[j+1]=shadowPlane.y;shadowReceiverPlanes[j+2]=shadowPlane.z;shadowReceiverPlanes[j+3]=shadowPlane.w;}
        shadowInverseMatrix.set(ShadowRenderer.MODELVIEW).invert().transformDirection(shadowRay.set(0,0,-1));shadowRay.normalize();
        boolean finite=Float.isFinite(shadowRay.x)&&Float.isFinite(shadowRay.y)&&Float.isFinite(shadowRay.z)&&shadowRay.lengthSquared()>.5f;
        for(float value:shadowReceiverPlanes)finite&=Float.isFinite(value);
        if(!finite){MODEL_SHADOW.culling(null,0,0,0,false,0);geometricFallback="; visibility=Iris fallback (invalid receiver/light matrices)";return 0;}
        long generation=com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.publication().generation();
        MODEL_SHADOW.culling(shadowReceiverPlanes,shadowRay.x,shadowRay.y,shadowRay.z,false,gpu.shadowGroupsBuffer(generation));
        return rt?com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE
                .prepareParticleLightVisibility(camera.getPosition(),shadowRay):0;
    }

    // ------------------------------------------------------------------
    // Draw
    // ------------------------------------------------------------------

    private static void drawEntities() {
        var gpu = ParticleBuffers.INSTANCE;

        bindSharedResources(gpu);
        gpu.bindDrawIndirect();

        // Cutout segment: native solid-entity treatment -- no blending, depth
        // writes everywhere (early-Z feeder for later passes). The program
        // inherits the pack's entity directives wholesale.
        ShaderInstance shader = COMPILER.entitiesShader();
        bindAtlas(COMPILER.gtextureUnit());
        try {GpuPrograms.applyExternal(shader);
        int progId = shader.getId();
        uploadSegmentUniforms(progId);
        // Nearest-first cutout: early-Z rejects occluded fragments before the
        // pack's fragment shader runs -- the dense-swarm fragment-pressure fix.
        uploadSegmentMode(progId, 1);
        RenderSystem.enableDepthTest();
        GL11.glEnable(GL11.GL_CULL_FACE);
        if(!net.irisshaders.iris.gl.blending.BlendModeStorage.isBlendLocked())RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        bindAtlas(COMPILER.gtextureUnit());
        DRAW_TIMING.begin(ModelMergeTelemetry.Segment.BODY);boolean bodyDrawn=false;
        try{gpu.drawModelCutout();bodyDrawn=true;}finally{DRAW_TIMING.end(ModelMergeTelemetry.Segment.BODY,bodyDrawn);}
        var feather=com.iridium126.createmanaindustry.client.particles.engine.AllayFeatherGpu.INSTANCE;
        if(feather.generation()==com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.publication().generation()){
            DRAW_TIMING.begin(ModelMergeTelemetry.Segment.FEATHER);boolean featherDrawn=false;
            try{feather.drawMerged(progId);featherDrawn=true;}finally{DRAW_TIMING.end(ModelMergeTelemetry.Segment.FEATHER,featherDrawn);}
        }
        bindSharedResources(gpu);gpu.bindDrawIndirect();
        var lod=com.iridium126.createmanaindustry.client.particles.engine.StormLodGpu.INSTANCE;
        var publication=com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.publication();
        var captured=CapturedRenderingState.INSTANCE;
        if(com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.currentFrameReady())lod.drawNative(publication.generation(),captured.getGbufferModelView(),captured.getGbufferProjection(),projection->{
            uploadIrisMatrices(progId,projection,new Matrix4f(captured.getGbufferModelView()));
            lod.bindNative();
            setMat(progId,"cmi_LodInverseView",new Matrix4f(captured.getGbufferModelView()).invert());
            RenderSystem.enableDepthTest();RenderSystem.depthMask(true);RenderSystem.disableCull();
            setInt(progId,"cmi_LodPass",1);DRAW_TIMING.begin(ModelMergeTelemetry.Segment.LOD1);boolean lod1Drawn=false;
            try{lod.drawBody();lod1Drawn=true;}finally{DRAW_TIMING.end(ModelMergeTelemetry.Segment.LOD1,lod1Drawn);}
            setInt(progId,"cmi_LodPass",2);DRAW_TIMING.begin(ModelMergeTelemetry.Segment.LOD2);boolean lod2Drawn=false;
            try{lod.drawPixel();lod2Drawn=true;}finally{DRAW_TIMING.end(ModelMergeTelemetry.Segment.LOD2,lod2Drawn);}
        });
        if(COMPILER.carrierShader()==shader){
            gpu.beginBindings();bindSharedResources(gpu);gpu.bindDrawIndirect();uploadSegmentUniforms(progId);
            drawCarrier(gpu,progId,COMPILER.gtextureUnit());
        }
        }finally{GpuPrograms.clearExternal(shader);}
        // A separate apply is necessary only for a distinct neutral specialization.
        ShaderInstance carrier=COMPILER.carrierShader();
        if(carrier!=shader){
            gpu.beginBindings();bindSharedResources(gpu);gpu.bindDrawIndirect();bindAtlas(COMPILER.carrierGtextureUnit());
            try{GpuPrograms.applyExternal(carrier);int progId=carrier.getId();uploadSegmentUniforms(progId);drawCarrier(gpu,progId,COMPILER.carrierGtextureUnit());}
            finally{GpuPrograms.clearExternal(carrier);}
        }

        // Native MRT blend directives keep encoded metadata as overwrite outputs.
        // Packs without an attachment contract retain the legacy alpha fallback.
        ShaderInstance ghost = COMPILER.ghostShader();
        bindAtlas(COMPILER.ghostGtextureUnit());
        try {GpuPrograms.applyExternal(ghost);
        int progId = ghost.getId();
        uploadSegmentUniforms(progId);
        // Ghost keeps forward reads: back-to-front order is load-bearing for
        // correct translucent compositing of overlapping shells.
        uploadSegmentMode(progId, 0);
        RenderSystem.enableDepthTest();
        GL11.glEnable(GL11.GL_CULL_FACE);
        if(!COMPILER.blendPlan().nativeAttachments()&&!net.irisshaders.iris.gl.blending.BlendModeStorage.isBlendLocked()){
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        }
        RenderSystem.depthMask(true);
        bindAtlas(COMPILER.ghostGtextureUnit());
        DRAW_TIMING.begin(ModelMergeTelemetry.Segment.GHOST);boolean ghostDrawn=false;
        try{gpu.drawModelGhost();ghostDrawn=true;}finally{DRAW_TIMING.end(ModelMergeTelemetry.Segment.GHOST,ghostDrawn);}
        }finally{GpuPrograms.clearExternal(ghost);}

        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        GL30.glBindVertexArray(0);
        com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(0);

        gpu.unbindMergedTbos(ShaderPackProgramCompiler.MERGED_SAMPLER_UNIT_BASE);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
    }

    private static void drawCarrier(ParticleBuffers gpu,int program,int textureUnit){
        uploadSegmentMode(program,2);COMPILER.entityContext().upload(program,true);bindAtlas(textureUnit);
        RenderSystem.enableDepthTest();RenderSystem.depthMask(true);
        if(!net.irisshaders.iris.gl.blending.BlendModeStorage.isBlendLocked())RenderSystem.disableBlend();
        DRAW_TIMING.begin(ModelMergeTelemetry.Segment.CARRIER);boolean success=false;
        try{gpu.drawModelCarrier();success=true;}finally{DRAW_TIMING.end(ModelMergeTelemetry.Segment.CARRIER,success);}
    }

    /**
     * Per-program uniform refresh for one merged segment: the iris_* matrices
     * are NOT refreshed outside Iris's own draw stream, so supply the current
     * gbuffer matrices ourselves, then publish this segment's entity context.
     */
    private static void uploadSegmentUniforms(int progId) {
        Matrix4f projection = new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferProjection());
        Matrix4f modelView = new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView());
        uploadIrisMatrices(progId, projection, modelView);
        uploadNeutralEntityUniforms(progId);
        uploadCmiUniforms(progId);
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

    private static void bindSharedResources( ParticleBuffers gpu) {
        // Merged programs read particle data through TBO views pinned to fixed
        // texture units; plain sampler declarations survive every in-game pass
        // where interface blocks demonstrably do not.
        int permId = com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.publication().permutation();
        gpu.bindMergedTbos(ShaderPackProgramCompiler.MERGED_SAMPLER_UNIT_BASE,
                gpu.particleDrawBufferId(),
                permId >= 0 ? permId : gpu.sortBuffer(0));
        gpu.bindVao();
    }

    /**
     * Iris supplies {@code entityId}/{@code entityColor} only during its own
     * entity stream; outside it they hold stale values from the last rendered
     * entity. Replace them with the effective allay identity and actual atlas
     * dimensions after apply; held items switch to a separate neutral identity.
     */
    private static void uploadNeutralEntityUniforms(int progId) {
        COMPILER.entityContext().upload(progId,false);
        var atlas=com.iridium126.createmanaindustry.client.particles.engine.ParticleAtlas.ALLAY;
        int size=loc(progId,"atlasSize");if(size>=0)com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current2i(size,atlas.width(),atlas.height());
        size=loc(progId,"gtextureSize");if(size>=0)com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current2i(size,atlas.width(),atlas.height());
    }

    private static void uploadIrisMatrices(int progId, Matrix4f projection, Matrix4f modelView) {
        int projLoc = GpuUniforms.loc(progId, "iris_ProjMat");
        if (projLoc >= 0)
            GL20.glUniformMatrix4fv(projLoc, false, projection.get(new float[16]));
        int mvLoc = GpuUniforms.loc(progId, "iris_ModelViewMat");
        if (mvLoc >= 0)
            GL20.glUniformMatrix4fv(mvLoc, false, modelView.get(new float[16]));
        int normalLoc = GpuUniforms.loc(progId, "iris_NormalMat");
        if (normalLoc >= 0) {
            Matrix4f inv = new Matrix4f(modelView).invert();
            Matrix3f normalMat = new Matrix3f(inv.transpose());
            GL20.glUniformMatrix3fv(normalLoc, false, normalMat.get(new float[9]));
        }
    }

    private static void uploadCmiUniforms(int progId) {
        setInt(progId,"cmi_FeatherPass",0);
        setInt(progId,"cmi_LodPass",0);
        setInt(progId,"cmi_ShadowPass",0);
        var camPos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        setFloat3(progId, "cmi_CameraPos", (float) camPos.x, (float) camPos.y, (float) camPos.z);
        setFloat(progId, "cmi_FadeDist", (float) ClientConfig.particleFadeDistance);
        setFloat(progId, "uTimeSec", com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.displayTimeSec());
        com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.INSTANCE.bindTexture(ShaderPackProgramCompiler.MERGED_SAMPLER_UNIT_BASE+4);
        com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.INSTANCE.bindPose(progId);
        com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.INSTANCE.bindRenderClock(progId,com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.displayTimeSec()-com.iridium126.createmanaindustry.client.allaystorm.AllayStormRuntime.INSTANCE.timeSec());
        ParticleBuffers.INSTANCE.bindAllayAnimation(progId);
        setInt(progId,"uStarfallEnabled",com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.INSTANCE.committedBuffer()>0?1:0);setInt(progId,"cmi_Starfall",ShaderPackProgramCompiler.MERGED_SAMPLER_UNIT_BASE+4);
        uploadStormItemUniforms(progId);
    }

    /**
     * Held-item uniforms (mirror of the engine's uploadStormItemUniforms): the
     * dive-wave staging feeding the carrier predicate and sword tier, plus the
     * per-tier atlas UV rects. Same names and staging the self-drawn model.vsh
     * consumes; cheap on stormless frames.
     */
    private static void uploadStormItemUniforms(int progId) {
        com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.INSTANCE.uploadUniforms(progId);
        setVec4Array(progId, "uHeldItemUV",
                com.iridium126.createmanaindustry.client.particles.engine.HeldItemGeometry.uvTable());
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
    private static void uploadSegmentMode(int progId,  int segmentMode) {
        setInt(progId, "cmi_MetaSlot", ParticleBuffers.INSTANCE.metaSlotIndex());
        setInt(progId, "cmi_CarrierBase", ParticleBuffers.INSTANCE.carrierBase());
        setInt(progId, "cmi_SegmentMode", segmentMode);
    }

    /** The pack fragment samples the entity texture through its gtexture sampler. */
    private static void bindAtlas( int unit) {
        if (unit < 0)
            return;
        int texture=com.iridium126.createmanaindustry.client.particles.engine.ParticleAtlas.ALLAY.textureId();
        if(texture<=0)return;
        RenderSystem.setShaderTexture(0,texture);
        com.iridium126.createmanaindustry.client.gpu.state.GpuTexture2DState.bind(unit,texture);
    }

    // ------------------------------------------------------------------
    // Timer ring (never blocks)
    // ------------------------------------------------------------------

    private static boolean beginTimer() {
        var scene=com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE;
        return timingEnabled&&MERGE_TIMER.begin(true,scene.renderFrameToken(),scene.publication()==null?-1:scene.publication().generation());
    }

    private static void endTimer(boolean publish) {MERGE_TIMER.end(publish);}

    // ------------------------------------------------------------------
    // Uniform helpers
    // ------------------------------------------------------------------

    private static int loc(int prog, String name) {
        return com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(prog,name);
    }

    private static void setInt(int prog, String name, int v) {
        int l = loc(prog, name);
        if (l >= 0) com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current1i(l, v);
    }

    private static void setFloat(int prog, String name, float v) {
        int l = loc(prog, name);
        if (l >= 0) com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current1f(l, v);
    }

    private static void setFloat3(int prog, String name, float x, float y, float z) {
        int l = loc(prog, name);
        if (l >= 0) com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current3f(l, x, y, z);
    }

    private static void setFloat4(int prog, String name, float x, float y, float z, float w) {
        int l = loc(prog, name);
        if (l >= 0) com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current4f(l, x, y, z, w);
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
