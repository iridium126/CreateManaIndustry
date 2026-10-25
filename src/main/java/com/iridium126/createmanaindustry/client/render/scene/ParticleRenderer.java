package com.iridium126.createmanaindustry.client.render.scene;
import com.iridium126.createmanaindustry.client.particles.engine.*;

import java.util.Set;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.greattrick.sharedfate.SharedFateGpu;
import com.iridium126.createmanaindustry.client.allaystorm.AllayStormRuntime;
import com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers;
import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.world.phys.Vec3;
import com.iridium126.createmanaindustry.client.packages.render.PackageRuntime;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms;
import static com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.*;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** Actual generic particle GPU draw passes and Iris arbitration. */
public final class ParticleRenderer {
        private final com.iridium126.createmanaindustry.client.gpu.state.GpuTextureBufferState poseTboState=
                new com.iridium126.createmanaindustry.client.gpu.state.GpuTextureBufferState(15,1);
        public void draw(ParticleRenderFrame frame,com.iridium126.createmanaindustry.client.gpu.frame.GpuFrameCoordinator.Publication publication,Camera camera, Matrix4fc view, Matrix4fc projectionMatrix,com.iridium126.createmanaindustry.client.gpu.profile.GpuTimestampRing drawTimer) {
            if(frame.generation()!=publication.generation())throw new IllegalStateException("Stale particle draw input");
            this.frame=frame;
            gpu.beginBindings();
            poseTboState.capture();
            boolean queryActive = false;
            try {
                if (drawTimer!=null&&drawTimer.begin())
                    queryActive = true;

                // Repeat of the compute section's empty-guard from the handoff
                // values: nothing alive and nothing spawned skips vertex work while
                // keeping bindings correct for a valid buffer anyway.
                if (frame.aliveEstimate() > 0 || frame.entryCount() > 0
                        || PackageRuntime.INSTANCE.populated()) {
                    ParticleDiagnostics.INSTANCE.mark("draw_opaque");
                    drawPass(1, view, projectionMatrix, camera);
                    ParticleDiagnostics.INSTANCE.mark("draw_packages");
                    PackageRuntime.INSTANCE.drawPackages(view,projectionMatrix,camera,publication);gpu.beginBindings();
                    ParticleDiagnostics.INSTANCE.mark("draw_models");
                    if (hookModelsDrawn) {
                        // The pack entity merge hook drew the MODEL segments earlier
                        // this frame against the freshly promoted permutation;
                        // skipping ours prevents a double-draw (render arbitration).
                    } else {
                        drawModels(view, projectionMatrix, camera);
                    }
                    if (frame.sorted()) {
                        ParticleDiagnostics.INSTANCE.mark("draw_alpha");
                        drawPass(2, view, projectionMatrix, camera);
                    }
                    ParticleDiagnostics.INSTANCE.mark("draw_hex");
                    if(CreateManaIndustry.HEX_ACTIVE)com.iridium126.createmanaindustry.client.hex.pattern.HexPatternService.drawWorld();
                    ParticleDiagnostics.INSTANCE.mark("draw_additive");
                    drawPass(0, view, projectionMatrix, camera);
                    ParticleDiagnostics.INSTANCE.mark("draw_cleanup");
                }

                // Clear the merge-hook arbitration latch at the DRAW TAIL: the hook
                // fires mid-renderLevel — between our two phases within one frame —
                // and will set it again before endFrame's skip-check below runs.
                hookModelsDrawn = false;


                if (queryActive) {
                    drawTimer.end(true);
                    queryActive = false;
                }
            } finally {
                if (queryActive) {
                    // Same partial-bracket discipline as the compute phase.
                    try {
                        drawTimer.end(false);
                    } catch (RuntimeException | LinkageError ignoredCleanup) {
                        // context is going away; nothing further to do
                    }
                }
                try {
                    GL20.glUseProgram(0);
                    GL30.glBindVertexArray(0);
                } catch (RuntimeException | LinkageError ignoredCleanup) {
                    // see above
                }
                try {
                    // SSBO bases 0-15 (permutations, sort data, counters, model geo…)
                    gpu.unbindShaders();
                } catch (RuntimeException | LinkageError ignoredCleanup) {
                    // see above
                }
                try {
                    GL13.glActiveTexture(GL13.GL_TEXTURE0);
                    GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
                    GL13.glActiveTexture(GL13.GL_TEXTURE1);
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
                    GL13.glActiveTexture(GL13.GL_TEXTURE2);
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
                    GL13.glActiveTexture(GL13.GL_TEXTURE0);
                } catch (RuntimeException | LinkageError ignoredCleanup) {
                    // see above
                }
                try {
                    RenderSystem.depthMask(true);
                    RenderSystem.defaultBlendFunc();
                    RenderSystem.disableBlend();
                } catch (RuntimeException | LinkageError ignoredCleanup) {
                    // see above
                }
                poseTboState.restore();
            }
        }

        /** All package box/rig mesh groups share one indirect multi-draw submission. */

        /**
         * Body, held-item carriers and translucent cloak/wings share one element multi-draw.
         * Disjoint index ranges select vertex partId; each command reads its own permutation
         * partition. All segments blend and write depth, preserving the existing ghost
         * occlusion semantics. Indexed MODEL geometry and package vertex attributes currently
         * use different programs/VAOs, so they require separate submissions.
         */
        private void drawModels(Matrix4fc view, Matrix4fc projectionMatrix, Camera camera) {
            int prog = programs.modelRender();
            if (prog == 0)
                return;
            GL20.glUseProgram(prog);
            com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.INSTANCE.bind(prog,false);
            setFloatUniform(prog,"uTimeSec",AllayStormRuntime.INSTANCE.timeSec());
            gpu.bindModelDrawInputs(mainPermutation());
            gpu.bindAllayAnimation(prog);

            setMat4Uniform(prog, "ModelViewMat", view);
            setMat4Uniform(prog, "ProjMat", projectionMatrix);
            Vec3 pos = camera.getPosition();
            setFloatUniform(prog, "uCamPos", (float) pos.x, (float) pos.y, (float) pos.z);
            setFloatUniform(prog, "uFadeDist", (float) ClientConfig.particleFadeDistance);
            setFloatUniform(prog, "uTimeSec", AllayStormRuntime.INSTANCE.timeSec());
            // fixed base of the held-item carrier region inside the sort buffer
            setUIntUniform(prog, "uCarrierBase", gpu.carrierBase());
            uploadStormItemUniforms(prog);
            allayAtlas.bind(1);
            setIntUniform(prog, "uSprite", 1);

            RenderSystem.enableDepthTest();
            GL11.glEnable(GL11.GL_CULL_FACE); // double-wound faces stay visible from both sides
            // blend enabled for both segments (the opaque fsh outputs alpha 1.0,
            // so blending is a no-op there); BOTH write depth — opaque by
            // definition, translucent so ghosts occlude later translucent draws
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            RenderSystem.depthMask(true);

            gpu.bindDrawIndirect();
            gpu.drawModelSegments();

            RenderSystem.depthMask(true);
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
            GL20.glUseProgram(0);
            GL30.glBindVertexArray(0);
        }

        /**
         * Held-item uniforms for a MODEL vertex program (self-drawn or merged):
         * the wave staging (carrier predicate + tier — the same arrays update.comp
         * steers with) and the per-tier atlas UV rects. Cheap on stormless frames
         * (zeroed staging); the UV table is a constant of the atlas layout.
         */
        private void uploadStormItemUniforms(int prog) {
            com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.INSTANCE.bind(prog,false);
            com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.INSTANCE.uploadUniforms(prog);
            setVec4ArrayUniform(prog, "uHeldItemUV", com.iridium126.createmanaindustry.client.particles.engine.HeldItemGeometry.uvTable());
        }

        /**
         * Draws one billboard bucket: 0 = additive (soft circle, unsorted, drawn
         * LAST), 1 = OPAQUE cutout sprite (depth write, unsorted), 2 = ALPHA
         * blended sprite (walks the sprite partition of the type-partitioned
         * sort array, offset by the exact model count from cmd[IDX_CNT_MODELOP];
         * no depth write). Bucket 1 runs before every blended pass so its depth
         * writes feed early-Z; buckets 0/2 never write depth.
         */
        private void drawPass(int mode,
                Matrix4fc view, Matrix4fc projectionMatrix, Camera camera) {
            boolean textured = mode != 0;
            int prog = textured ? programs.texturedRender() : programs.render();
            if (prog == 0)
                return;
            GL20.glUseProgram(prog);
            com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.INSTANCE.bind(prog,false);
            setFloatUniform(prog,"uTimeSec",AllayStormRuntime.INSTANCE.timeSec());
            gpu.bindBillboardDrawInputs(mode, mainPermutation());
            sharedFate.bindDrawInputs();

            setMat4Uniform(prog, "ModelViewMat", view);
            setMat4Uniform(prog, "ProjMat", projectionMatrix);

            Vec3 pos = camera.getPosition();
            Vector3f up = camera.getUpVector();
            Vector3f left = camera.getLeftVector();
            setFloatUniform(prog, "uCamPos", (float) pos.x, (float) pos.y, (float) pos.z);
            setFloatUniform(prog, "uCamRight", -left.x, -left.y, -left.z);
            setFloatUniform(prog, "uCamUp", up.x, up.y, up.z);

            if (textured) {
                spriteAtlas.bind(1);
                setIntUniform(prog, "uSprite", 1);
                setFloatUniform(prog, "uAtlasCols", spriteAtlas.cols());
                setFloatUniform(prog, "uAtlasRows", spriteAtlas.rows());
                setIntUniform(prog, "uMode", mode == 1 ? 1 : 0);
                // unit 2: the real vanilla lightmap — combat OPAQUE particles turn
                // their spawn-time packed light into a lightmap sample (header
                // lightMode); LightTexture keeps its registered path private, hence
                // the accesstransformer entry
                LightTexture light = Minecraft.getInstance().gameRenderer.lightTexture();
                if (light != null) {
                    AbstractTexture lightTex = Minecraft.getInstance().getTextureManager()
                            .getTexture(light.lightTextureLocation);
                    GL13.glActiveTexture(GL13.GL_TEXTURE2);
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, lightTex.getId());
                    GL13.glActiveTexture(GL13.GL_TEXTURE0);
                }
                setIntUniform(prog, "uLightmap", 2);
            } else {
                setFloatUniform(prog,"uHexPartialTick",Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false));
                setIntUniform(prog,"uHexTickReady",1);
                setFloatUniform(prog, "uGlow", 1.0f);
            }
            setFloatUniform(prog, "uFadeDist", (float) ClientConfig.particleFadeDistance);

            RenderSystem.enableDepthTest();
            if (mode == 1) {
                // OPAQUE cutout: no blending, depth writes — order-independent
                RenderSystem.disableBlend();
                RenderSystem.depthMask(true);
            } else {
                RenderSystem.enableBlend();
                if (mode == 2)
                    RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                            GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
                else
                    RenderSystem.blendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE);
                RenderSystem.depthMask(false);
            }

            if (mode == 0)
                gpu.drawIndirect(0);
            else if (mode == 1)
                gpu.drawIndirect(1);
            else
                gpu.drawIndirect(5);

            RenderSystem.depthMask(true);
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
            GL20.glUseProgram(0);
            GL30.glBindVertexArray(0);
        }

        private int mainPermutation() {
            return frame.sorted() ? frame.permutation() : gpu.sortBuffer(0);
        }

        // ------------------------------------------------------------------
        // Private helpers
        // ------------------------------------------------------------------

    
    /**
     * Set by the pack entity merge hook when it drew the MODEL segments this
     * frame (it fires mid-renderLevel, between the AFTER_SKY compute phase and
     * the AFTER_LEVEL draw phase); {@code endFrame} skips its own drawModels
     * accordingly (render arbitration) and clears the latch afterwards.
     * Written only from the render thread.
     */
    private boolean hookModelsDrawn = false;
    // Observable status for /cmi particle shaderpack status -- written by the merge
    // hook, read by the command, both on the render thread.
    public volatile String shaderPackPathStatus = "self-drawn";
    public volatile String shaderPackDepthStatus = "n/a";
    /** S-track state for /cmi particle shaderpack status: n/a / active / no shadow track / ... */
    public volatile String shaderPackShadowStatus = "n/a";
    /**
     * Diagnostics for /cmi particle shaderpack status: how many frames old the sort
     * permutation a pack hook last consumed was. 0 = the merged programs drew
     * THIS frame's cull/sort result; >= 1 = stale generation. MODEL shadows
     * use an independent current-generation list, reported separately.
     */
    public volatile String shaderPackPermStatus = "n/a";
    public volatile String shaderPackErrorStatus = "";

    /** Called by the pack entity merge hook after it drew the MODEL segments itself. */
    public void markHookModelDrawn() {
        this.hookModelsDrawn = true;
    }
    public static final ParticleRenderer INSTANCE=new ParticleRenderer();
    private final ParticleBuffers gpu=ParticleBuffers.INSTANCE;
    private final ParticlePrograms programs=ParticlePrograms.INSTANCE;
    private final ParticleAtlas allayAtlas=ParticleAtlas.ALLAY,spriteAtlas=ParticleAtlas.SPRITE;
    private final SharedFateGpu sharedFate=SharedFateGpu.INSTANCE;
    private ParticleRenderFrame frame;
    private ParticleRenderer() {}
    public void beginFrame() { hookModelsDrawn=false; }
    public int lastFinalPermAgeFrames() {
        return com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.publication()==null?-1:0;
    }

}
