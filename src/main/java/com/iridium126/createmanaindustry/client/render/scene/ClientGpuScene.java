package com.iridium126.createmanaindustry.client.render.scene;
import com.iridium126.createmanaindustry.client.gpu.state.GpuComputeState;
import com.iridium126.createmanaindustry.client.particles.engine.*;

import java.util.Set;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.greattrick.sharedfate.SharedFateGpu;
import com.iridium126.createmanaindustry.client.greattrick.partition.PartitionGpu;
import com.iridium126.createmanaindustry.client.allaystorm.AllayStormRuntime;
import com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers;
import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Matrix4f;
import com.iridium126.createmanaindustry.client.particles.interaction.ModelInteractionRuntime;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms;
import static com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.*;
import org.joml.Matrix4fc;

/** Fixed business composition and render-scope lifecycle. No plugin registration. */
public final class ClientGpuScene {

    public final com.iridium126.createmanaindustry.client.gpu.profile.GpuBudgetLedger budgets=new com.iridium126.createmanaindustry.client.gpu.profile.GpuBudgetLedger();
    public final ParticleFrameProfiler profiler = new ParticleFrameProfiler();
    /** Accumulated GPU ms measured inside the shader-pack hook (timer ring). */
    private double externalHookGpuMs = 0;

    // ---- Split-frame handoff (AFTER_SKY compute -> AFTER_LEVEL draws) ------
    /** Set by beginFrame when its guards passed; drives endFrame accounting. */
    private boolean frameAttempted = false;
    /** Set only when the compute phase committed fully; gates ALL draw submission. */
    private boolean frameArmed = false;
    /** beginFrame submission clock; endFrame falls back to it before timer samples flow. */
    private long frameStartNanos = 0;
    private long computeSubmitNanos;

    // Fixed owners keep independent clocks; this root sequences their render transaction.
    private long lastErrorTime = 0;
    private final com.iridium126.createmanaindustry.client.gpu.profile.GpuTimestampRing computeTimer = new com.iridium126.createmanaindustry.client.gpu.profile.GpuTimestampRing(4,budgets::gpu);
    private final com.iridium126.createmanaindustry.client.gpu.profile.GpuTimestampRing drawTimer = new com.iridium126.createmanaindustry.client.gpu.profile.GpuTimestampRing(4,budgets::gpu);

    /** Accumulates one completed hook-side timer query (GPU ms, lagged). */
    public synchronized void addExternalGpuMs(double ms) {
        this.externalHookGpuMs += Math.max(0.0, ms);
        budgets.gpu(Math.max(0,ms));
    }

    // ------------------------------------------------------------------
    // Frame hooks: optional pre-shadow preparation, AFTER_SKY reuse/fallback,
    // AFTER_LEVEL drawing and timing. The main camera scope supplies immutable
    // matrices and one frame token; nested/auxiliary calls cannot advance it.
    // ------------------------------------------------------------------

    private final ParticleFrameLatch levelFrame = new ParticleFrameLatch();
    private final com.iridium126.createmanaindustry.client.gpu.frame.GpuFrameCoordinator frameCoordinator =
            new com.iridium126.createmanaindustry.client.gpu.frame.GpuFrameCoordinator();
    private final GpuComputeState earlyComputeState = new GpuComputeState();
    private final org.joml.Matrix4f levelView = new org.joml.Matrix4f();
    private final org.joml.Matrix4f levelProjection = new org.joml.Matrix4f();
    private Camera levelCamera;
    private DeltaTracker levelDelta;
    private net.minecraft.client.multiplayer.ClientLevel frameLevel;

    public void enterLevelFrame(boolean mainRenderer) {
        var mc = Minecraft.getInstance();
        if (levelFrame.enter(mainRenderer && mc.level != null)) {
            levelCamera = null;
            levelDelta = null;
            frameLevel = mc.level;
            frameAttempted = frameArmed = false;
            frameCoordinator.abort();
        }
    }

    public void captureLevelFrame(Camera camera, Matrix4fc view, Matrix4fc projection, DeltaTracker delta) {
        if (!levelFrame.primary() || camera != Minecraft.getInstance().gameRenderer.getMainCamera()) return;
        levelCamera = camera;
        levelDelta = delta;
        levelView.set(view);
        levelProjection.set(projection);
    }

    public void leaveLevelFrame() {
        boolean primary = levelFrame.primary();
        try {
            if (primary && frameAttempted) {
                ParticleDiagnostics.INSTANCE.resumeCpu();
                ParticleDiagnostics.INSTANCE.end();
            }
        } finally {
            if (primary) {
                frameAttempted = frameArmed = false;
                frameCoordinator.abort();
                levelCamera = null;
                levelDelta = null;
                frameLevel = null;
            }
            levelFrame.leave();
        }
    }

    public long renderFrameToken(){return levelFrame.token();}
    public boolean currentFrameReady(){return levelFrame.ready()&&frameCoordinator.publication()!=null&&frameLevel==Minecraft.getInstance().level;}
    /** Called after Iris pipeline setup but before any shadow camera/state is installed. */
    public boolean prepareCurrentFrame(){
        if(!levelFrame.primary()||levelCamera==null||frameLevel!=Minecraft.getInstance().level)return false;
        if(!ClientConfig.shaderPackIntegration||!dev.engine_room.flywheel.lib.util.ShadersModHelper.isShaderPackInUse())return false;
        if(!levelFrame.claim())return currentFrameReady();
        try {
            earlyComputeState.capture();
            try {
                computeFrame(levelCamera, levelView, levelProjection, levelDelta);
            } finally {
                earlyComputeState.restore();
            }
        } catch (RuntimeException | LinkageError failure) {
            frameArmed = false;
            frameCoordinator.abort();
            CreateManaIndustry.LOGGER.warn("[CMI particles] pre-shadow preparation failed", failure);
        }
        levelFrame.complete(frameArmed);
        return currentFrameReady();
    }
    public void beginFrame(Camera camera, Matrix4fc view, Matrix4fc projectionMatrix, DeltaTracker deltaTracker) {
        if(camera!=Minecraft.getInstance().gameRenderer.getMainCamera())return;
        if(levelFrame.active()){
            if(!levelFrame.claim())return;
            computeFrame(levelCamera,levelView,levelProjection,levelDelta);levelFrame.complete(frameArmed);return;
        }
        computeFrame(camera,view,projectionMatrix,deltaTracker);
    }
    private void computeFrame(Camera camera, Matrix4fc view, Matrix4fc projectionMatrix, DeltaTracker deltaTracker) {
        this.frameAttempted = false;
        this.frameArmed = false;
        this.frameCoordinator.abort();
        budgets.begin(renderFrameToken());
        if(!ClientConfig.particleEnabled) { particles.clearParticles();return; }
        if(Minecraft.getInstance().level==null)return;
        try(var accounting=budgets.prepare()){if(!particles.prepareResources())return;}
        profiler.setBudget((float)ClientConfig.particleBudgetMs);
        this.frameAttempted = true;
        this.frameStartNanos = System.nanoTime();
        ParticleDiagnostics.INSTANCE.begin();
        boolean timedCompute=this.computeTimer.begin(), computeSucceeded=false;
        try(var accounting=budgets.prepare()) {
            frameCoordinator.begin(renderFrameToken());
            try(var work=particles.prepareInput(camera,view,projectionMatrix,deltaTracker,frameCoordinator,profiler.emissionScale())) {
                if(work.hasWork()) {
                    frameCoordinator.simulate();work.simulate();
                    frameCoordinator.generate();work.generate();
                    frameCoordinator.cullSort();work.cullSort();
                    var publication=work.preparePublication();
                    ScenePublicationPolicy.validate(publication,publication.generation(),
                            com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.INSTANCE.candidateGeneration(),work.packageVersion());
                    renderInput=work.commit();frameCoordinator.publish(publication);work.consumeInputs();
                    frameArmed=true;
                } else frameCoordinator.abort();
            }
            computeSucceeded=true;
        } catch (RuntimeException | LinkageError e) {
            this.frameArmed = false;
            this.frameCoordinator.abort();
            particles.recoverFailedInputs();
            com.iridium126.createmanaindustry.client.packages.authority.PackageAuthorityClient.closeAll("Particle frame failed",true);
            long now = System.currentTimeMillis();
            if (now - this.lastErrorTime > 5000) {
                this.lastErrorTime = now;
                CreateManaIndustry.LOGGER.error("[CMI particles] compute phase failed", e);
            }
        } finally {
            if(timedCompute)this.computeTimer.end(computeSucceeded);
            this.computeSubmitNanos = System.nanoTime() - this.frameStartNanos;
            ParticleDiagnostics.INSTANCE.mark("world");
            ParticleDiagnostics.INSTANCE.pauseCpu();
        }
    }

    /**
     * Phase D of the split frame: submits every draw pass (honouring the
     * merge-hook arbitration latch) and folds the lagged timer samples of BOTH
     * phases into the throttle. Always records costing whenever the compute
     * phase attempted, so throttle continuity survives aborted frames.
     */
    public void endFrame(Camera camera, Matrix4fc view, Matrix4fc projectionMatrix) {
        if(camera!=Minecraft.getInstance().gameRenderer.getMainCamera()||levelFrame.active()&&!levelFrame.primary())return;
        if (!particles.available() || !ClientConfig.particleEnabled || !this.frameAttempted)
            return;
        long drawStartNanos = System.nanoTime();
        ParticleDiagnostics.INSTANCE.resumeCpu();
        ParticleDiagnostics.INSTANCE.mark("draw");
        try {
            // An aborted compute phase leaves frameArmed clear; the last fully
            // committed generation simply persists one more frame undrawn.
            if (this.frameArmed) {
                this.frameCoordinator.draw();
                try(var accounting=budgets.prepare()) { ParticleRenderer.INSTANCE.draw(renderInput,frameCoordinator.publication(),camera,view,projectionMatrix,drawTimer); }
                finally { this.frameCoordinator.finishDraw(); }
            }
        } finally {
            // Prefer the lagged GPU-side cost of BOTH phases (each ring reads a
            // 3-frame-old completed sample, summed); fall back to CPU submit
            // time across beginFrame..endFrame until samples flow. Hook-side
            // GPU work (shader-pack MODEL draw) is measured by its own timer
            // ring and folded in so the throttle sees the full per-frame cost.
            double baseMs;
            if (this.computeTimer.sampled())
                baseMs = this.computeTimer.lastMs() + (particles.idle()?0:this.drawTimer.lastMs());
            else
                baseMs = (this.computeSubmitNanos + System.nanoTime() - drawStartNanos) / 1_000_000.0;
            synchronized (this) {
                baseMs += this.externalHookGpuMs;
                this.externalHookGpuMs = 0;
            }
            this.profiler.record(baseMs, ClientConfig.particleAutoThrottle);

            ParticleDiagnostics.INSTANCE.end();
            this.frameAttempted = false;
            this.frameArmed = false;
        }
    }
    public static final ClientGpuScene INSTANCE=new ClientGpuScene();
    private ParticleRenderFrame renderInput;
    private final ParticleRuntime particles=ParticleRuntime.INSTANCE;
    private ClientGpuScene() {}
    /** Immutable publication, valid only for this render scope. */
    public com.iridium126.createmanaindustry.client.gpu.frame.GpuFrameCoordinator.Publication publication() {
        return currentFrameReady()?frameCoordinator.publication():null;
    }
    public void requestProgramRebuild() {
        frameCoordinator.abort();frameArmed=false;
        com.iridium126.createmanaindustry.client.greattrick.sharedfate.SharedFateGpu.INSTANCE.close();
        com.iridium126.createmanaindustry.client.greattrick.partition.PartitionGpu.INSTANCE.close();
        if(CreateManaIndustry.HEX_ACTIVE)com.iridium126.createmanaindustry.client.hex.pattern.HexPatternService.requestReload();
        if(CreateManaIndustry.IRISVEIL_ACTIVE)com.iridium126.createmanaindustry.client.particles.shaderpack.CMIPackEntityMergeHook.invalidateShadow();
        if(CreateManaIndustry.IRIS_ACTIVE)com.iridium126.createmanaindustry.client.packages.render.PackageShaderHook.reload();
        com.iridium126.createmanaindustry.client.packages.render.PackageRuntime.INSTANCE.reload();
        particles.reloadPrograms();
    }
    public void onLevelChanged() {
        frameCoordinator.abort();frameArmed=frameAttempted=false;
        if(CreateManaIndustry.HEX_ACTIVE)com.iridium126.createmanaindustry.client.hex.pattern.HexPatternService.requestReload();
        com.iridium126.createmanaindustry.client.allaystorm.AllayStormRuntime.INSTANCE.resetStormState();
        com.iridium126.createmanaindustry.client.allaystorm.StormFeedback.INSTANCE.reset();
        com.iridium126.createmanaindustry.client.particles.interaction.ModelInteractionRuntime.INSTANCE.reset();
        com.iridium126.createmanaindustry.client.packages.render.PackageRuntime.INSTANCE.reset();
        profiler.reset();particles.resetWorld();
    }
    public void close() {
        frameCoordinator.abort();frameArmed=frameAttempted=false;
        com.iridium126.createmanaindustry.client.greattrick.sharedfate.SharedFateGpu.INSTANCE.close();
        com.iridium126.createmanaindustry.client.greattrick.partition.PartitionGpu.INSTANCE.close();
        com.iridium126.createmanaindustry.client.greattrick.SharedFateClient.clear();
        com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.INSTANCE.close();
        if(CreateManaIndustry.HEX_ACTIVE)com.iridium126.createmanaindustry.client.hex.pattern.HexPatternService.close();
        com.iridium126.createmanaindustry.client.packages.authority.PackageAuthorityClient.closeAll("Particle engine closed",false);
            if(CreateManaIndustry.IRIS_ACTIVE)com.iridium126.createmanaindustry.client.packages.render.PackageShaderHook.close();
            if(CreateManaIndustry.IRISVEIL_ACTIVE)com.iridium126.createmanaindustry.client.particles.shaderpack.CMIPackEntityMergeHook.invalidateShadow();
            com.iridium126.createmanaindustry.client.packages.render.PackageRuntime.INSTANCE.close();
        try { particles.closeResources(); } finally { computeTimer.close();drawTimer.close(); }

    }

}
