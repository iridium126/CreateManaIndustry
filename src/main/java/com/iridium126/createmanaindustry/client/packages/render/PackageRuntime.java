package com.iridium126.createmanaindustry.client.packages.render;
import com.iridium126.createmanaindustry.client.packages.authority.PackageAuthorityClient;

import com.iridium126.createmanaindustry.client.packages.world.PackageCollisionRuntime;
import com.iridium126.createmanaindustry.client.packages.world.PackageWorldRuntime;


import com.iridium126.createmanaindustry.client.particles.engine.*;

import java.util.concurrent.ConcurrentLinkedQueue;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.particles.emitter.EmitterSpec;
import com.iridium126.createmanaindustry.client.particles.emitter.ParticleTypes;
import com.iridium126.createmanaindustry.client.packages.render.PackagePoolGpu;
import com.iridium126.createmanaindustry.client.packages.render.PackagePreviewRuntime;
import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.phys.Vec3;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms;
import static com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.*;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

/** Owns package preparation, preview, pool integration and render state. */
public final class PackageRuntime {

    PackagePoolGpu packageParticles;
    private final PackagePreviewRuntime packagePreview=new PackagePreviewRuntime();
    private final java.util.function.Supplier<PackagePoolGpu> packagePoolFactory=this::packageParticles;
    private final com.iridium126.createmanaindustry.client.packages.world.PackageWorldRuntime packageWorld=
            new com.iridium126.createmanaindustry.client.packages.world.PackageWorldRuntime();
    private record PackagePreviewRequest(int count,Vec3 origin,Vec3 forward) {}
    private volatile String packagePreviewStatus="off";
    private volatile boolean packageShadersDirty;
    private int packageEmitter=-1;
    float packagePartialTick=1;
    private final EmitterSpec packageSpec=EmitterSpec.builder()
            .type(ParticleTypes.standard(ParticleTypes.Material.PACKAGE)).life(1,1).build();

    /** Render-thread internal integration seam. The Create adapter must gate ownership on GPU admission. */
    private PackagePoolGpu packageParticles() {
        RenderSystem.assertOnRenderThread();
        if(!particles.available())return null;
        if(packageParticles==null)
            packageParticles=new PackagePoolGpu(particles.capacity(),4096,
                    ParticlePrograms::loadParticlePlain);
        return packageParticles;
    }
    private boolean hookPackagesDrawn;
    public void markHookPackageDrawn(){hookPackagesDrawn=true;}
    public void previewPackages(int count,Vec3 origin,Vec3 forward) {
        if(count<0 || count>Math.min(131072,particles.capacity()))throw new IllegalArgumentException("Package preview exceeds pool capacity");
        packagePreviewStatus=count==0?"stopping":"queued "+count;
        requests.add(new PackagePreviewRequest(count,origin,forward));
    }
    public String packagePreviewStatus() { return packagePreviewStatus; }
    public boolean prepare(boolean doClear) {
        boolean changed=false;
        PackagePreviewRequest previewRequest=null,next;
        while((next=requests.poll())!=null)previewRequest=next;
        if(packageShadersDirty) {
            packageShadersDirty=false;
            if(packageParticles!=null)try { packageParticles.rebuild(ParticlePrograms::loadParticlePlain); }
            catch(RuntimeException failure) { CreateManaIndustry.LOGGER.warn("[CMI packages] shader reload failed; retaining previous programs",failure); }
        }
        if(previewRequest!=null && !doClear) {
            com.iridium126.createmanaindustry.client.packages.authority.PackageAuthorityClient.closeAll("Package preview owns the pool",true);
            if(previewRequest.count()==0) {
                this.packagePreview.close();this.packagePreviewStatus="off";
            } else try {
                this.packagePreview.start(packageParticles(),previewRequest.count(),previewRequest.origin(),previewRequest.forward(),
                        ParticlePrograms::loadParticlePlain);
                this.packagePreviewStatus="active; requested="+previewRequest.count();
            } catch(RuntimeException | LinkageError failure) {
                this.packagePreviewStatus="initialization failed: "+failure.getClass().getSimpleName();
                CreateManaIndustry.LOGGER.error("[CMI packages] preview initialization failed",failure);
                Minecraft.getInstance().gui.getChat().addMessage(net.minecraft.network.chat.Component.literal(
                        "[CMI packages] Package preview initialization failed; see latest.log and /cmi particle stats."));
            }
        }
        if(this.packagePreview.active()) {
            ParticleDiagnostics.INSTANCE.mark("package_physics");
            if(dev.engine_room.flywheel.lib.util.ShadersModHelper.isShaderPackInUse()
                    && (!CreateManaIndustry.IRIS_ACTIVE || !com.iridium126.createmanaindustry.client.packages.render.PackageShaderHook.prepare())) {
                this.packagePreview.close();this.packagePreviewStatus="stopped: package shaderpack program unavailable";
            }
            else this.packagePartialTick=this.packagePreview.prepare(Minecraft.getInstance().isPaused());
            ParticleDiagnostics.INSTANCE.mark("upload_after_packages");
        }
        if(com.iridium126.createmanaindustry.client.packages.world.PackageCollisionRuntime.pumpGpu())changed=true;
        boolean profilePackageWorld=ClientConfig.packageGpuAuthority && !this.packagePreview.active();
        if(profilePackageWorld)ParticleDiagnostics.INSTANCE.mark("package_physics");
        if(this.packageWorld.prepare(this.packagePoolFactory,ParticlePrograms::loadParticlePlain,this.packagePreview.active())) {
            if(!this.packagePreview.active())this.packagePartialTick=this.packageWorld.interpolation();
            changed=true;
        }
        if(profilePackageWorld)ParticleDiagnostics.INSTANCE.mark("upload_after_packages");

        if(packageParticles!=null&&packageParticles.hasInput())packageEmitter=particles.ensureEmitter(packageSpec);
        hookPackagesDrawn=false;
        return changed;
    }
        public void drawPackages(Matrix4fc view,Matrix4fc projection,Camera camera,com.iridium126.createmanaindustry.client.gpu.frame.GpuFrameCoordinator.Publication frame) {
            if(packageParticles==null || packageParticles.admissionCount()==0)return;
            if(frame==null||frame.packageGeneration()<0||frame.pool()!=packageParticles.committedPoolBuffer())return;
            if(hookPackagesDrawn || dev.engine_room.flywheel.lib.util.ShadersModHelper.isShaderPackInUse())return;
            Vec3 position=camera.getPosition();
            RenderSystem.enableDepthTest();RenderSystem.depthMask(true);RenderSystem.disableBlend();
            RenderSystem.enableCull();
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,Minecraft.getInstance().getTextureManager()
                    .getTexture(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS).getId());
            LightTexture light=Minecraft.getInstance().gameRenderer.lightTexture();
            GL13.glActiveTexture(GL13.GL_TEXTURE2);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,Minecraft.getInstance().getTextureManager()
                    .getTexture(light.lightTextureLocation).getId());
            var level=Minecraft.getInstance().level;
            var directions=net.createmod.ponder.mixin.client.accessor.RenderSystemAccessor.catnip$getShaderLightDirections();
            packageParticles.lighting(dev.engine_room.flywheel.api.visualization.VisualizationManager.supportsVisualization(level),
                    level.effects().constantAmbientLight(),directions[0],directions[1]);
            packageParticles.draw(frame.pool(),view,projection,(float)position.x,(float)position.y,
                    (float)position.z,packagePartialTick);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);

        }

    public static final PackageRuntime INSTANCE=new PackageRuntime(ParticleRuntime.INSTANCE);
    private final PackageParticlePort particles;
    private final ConcurrentLinkedQueue<PackagePreviewRequest> requests=new ConcurrentLinkedQueue<>();
    public PackageRuntime(PackageParticlePort particles) { this.particles=java.util.Objects.requireNonNull(particles); }
    public boolean hasInput() { return packageParticles!=null&&packageParticles.hasInput()&&packageEmitter>=0; }
    public boolean populated() { return packageParticles!=null&&packageParticles.admissionCount()>0; }
    public long stage(float[] frustum,Camera camera,float distance) {
        if(packageParticles==null||!packageParticles.needsStage())return -1;
        ParticleDiagnostics.INSTANCE.mark("package_import_group");
        packageParticles.mainRenderDistance(distance);
        return particles.importPackages(packageParticles,packageEmitter,frustum,camera);
    }
    public void commit() {
        packageParticles.commit();
    }
    public void abort() { if(packageParticles!=null)packageParticles.abort(); }
    public void reset() {
        PackageAuthorityClient.closeAll("Particle identities reset",true);packagePreview.close();packagePreviewStatus="off";
        if(packageParticles!=null)packageParticles.reset();requests.clear();
    }
    public void reload() {
        PackageAuthorityClient.closeAll("Package resources reloaded",true);packageShadersDirty=true;
        requests.add(new PackagePreviewRequest(0,Vec3.ZERO,new Vec3(0,0,1)));
    }
    public void close() {
        PackageAuthorityClient.closeAll("Particle scene closed",false);packagePreview.close();
        if(packageParticles!=null)packageParticles.close();packageParticles=null;requests.clear();
    }
}
