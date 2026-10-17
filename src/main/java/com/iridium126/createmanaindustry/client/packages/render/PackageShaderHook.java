package com.iridium126.createmanaindustry.client.packages.render;
import com.iridium126.createmanaindustry.client.gpu.profile.GpuPassTelemetry;
import com.iridium126.createmanaindustry.client.particles.shaderpack.*;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.packages.authority.PackageAuthorityClient;
import com.iridium126.createmanaindustry.client.packages.authority.PackageChainClientOwnership;


import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;
import com.iridium126.createmanaindustry.mixin.render.PackageCameraFrustumAccessor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.materialmap.*;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.vertices.ImmediateState;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.joml.*;
import org.lwjgl.opengl.*;
import java.util.*;

/** Package-owned Iris draws; main visibility checks only owning conveyors. */
public final class PackageShaderHook {
    private static final PackageShaderCompiler COMPILER=new PackageShaderCompiler();
    private static final PackageDrawCulling CULLING=new PackageDrawCulling();
    private static final Matrix4f VIEW=new Matrix4f(),PROJECTION=new Matrix4f(),INVERSE_VIEW=new Matrix4f(),INVERSE_PROJ=new Matrix4f(),CLIP=new Matrix4f();
    private static final Matrix3f NORMAL=new Matrix3f();
    private static final Vector4f PLANE=new Vector4f();
    private static final float[] MATRIX=new float[16],NORMAL_MATRIX=new float[9];
    private static int[] CHAIN_VISIBILITY_MASK=new int[0];
    private static final Set<ChainConveyorBlockEntity> VISIBLE_CONVEYORS=Collections.newSetFromMap(new IdentityHashMap<>());
    private static final NamespacedId PACKAGE_ID=new NamespacedId("create","package");
    private static final GpuPassTelemetry MAIN_TIMING=new GpuPassTelemetry(),SHADOW_TIMING=new GpuPassTelemetry();
    private static PackageRenderState state;
    private static Object failedPipeline;
    private static volatile boolean reloadRequested;
    private static String mainStatus="off",shadowStatus="off";
    private PackageShaderHook() {}
    public static String status(){return mainStatus+"; shadow="+shadowStatus+(COMPILER.error().isEmpty()?"":"; "+COMPILER.error())
            +"; main draw "+MAIN_TIMING.report()+"; shadow draw "+SHADOW_TIMING.report();}
    public static void reload(){reloadRequested=true;}
    /** Identity-only world boundary check; never compiles or mutates GL state. */
    public static Object pipelineBoundary(){return Iris.getPipelineManager().getPipelineNullable();}
    public static void close(){try{COMPILER.close();}finally{try{MAIN_TIMING.close();}finally{SHADOW_TIMING.close();state=null;failedPipeline=null;reloadRequested=false;mainStatus=shadowStatus="off";}}}
    public static boolean usable(){return !reloadRequested && ClientConfig.shaderPackIntegration && failedPipeline!=Iris.getPipelineManager().getPipelineNullable() && COMPILER.usable();}
    /** Distinguish a completed compile failure from the short pipeline-swap window. */
    public static boolean terminalFailure() {
        Object pipeline=Iris.getPipelineManager().getPipelineNullable();
        if(reloadRequested || !(pipeline instanceof IrisRenderingPipeline))return false;
        return failedPipeline==pipeline || !COMPILER.needsCompile()&&!COMPILER.error().isEmpty()&&!COMPILER.usable();
    }
    /** Render-thread preparation. Tick/network paths only call usable(), never compile. */
    public static boolean prepare() {
        if(reloadRequested){reloadRequested=false;COMPILER.invalidate();failedPipeline=null;mainStatus=shadowStatus="reload pending";}
        if(!ClientConfig.shaderPackIntegration || failedPipeline==Iris.getPipelineManager().getPipelineNullable())return false;
        if(!COMPILER.needsCompile())return COMPILER.usable();
        MAIN_TIMING.close();SHADOW_TIMING.close();
        if(state==null)state=new PackageRenderState();
        state.capture();try{return COMPILER.ensureCompiled();}finally{state.restore();}
    }
    public static boolean wantsMainDraw() {
        var pool=com.iridium126.createmanaindustry.client.packages.render.PackageRuntime.INSTANCE.packageParticles;
        return pool!=null && pool.admissionCount()!=0 && com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.publication()!=null && ClientConfig.shaderPackIntegration
                && !reloadRequested && dev.engine_room.flywheel.lib.util.ShadersModHelper.isShaderPackInUse();
    }
    public static void render(Frustum frustum) {
        var pool=com.iridium126.createmanaindustry.client.packages.render.PackageRuntime.INSTANCE.packageParticles;
        if(pool==null || pool.admissionCount()==0 || com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.publication()==null || !ClientConfig.shaderPackIntegration
                || reloadRequested || !dev.engine_room.flywheel.lib.util.ShadersModHelper.isShaderPackInUse())return;
        try{
            if(!prepare()){mainStatus="program unavailable";return;}
            VIEW.set(CapturedRenderingState.INSTANCE.getGbufferModelView());PROJECTION.set(CapturedRenderingState.INSTANCE.getGbufferProjection());
            CULLING.clear();CULLING.count=6;CLIP.set(((PackageCameraFrustumAccessor)frustum).createmanaindustry$getMatrix());
            for(int i=0;i<6;i++){CLIP.frustumPlane(i,PLANE);int p=i*4;CULLING.planes[p]=PLANE.x;CULLING.planes[p+1]=PLANE.y;CULLING.planes[p+2]=PLANE.z;CULLING.planes[p+3]=PLANE.w;}
            int words=(pool.packageCapacity()+31)>>>5;
            if(CHAIN_VISIBILITY_MASK.length!=words)CHAIN_VISIBILITY_MASK=new int[words];
            PackageChainClientOwnership.INSTANCE.mainVisibilityMask(CHAIN_VISIBILITY_MASK,frustum);
            timedDraw(MAIN_TIMING,pool,PackagePoolGpu.DrawPass.GBUFFER,COMPILER.main(),Minecraft.getInstance().gameRenderer.getMainCamera(),true,true,CHAIN_VISIBILITY_MASK);com.iridium126.createmanaindustry.client.packages.render.PackageRuntime.INSTANCE.markHookPackageDrawn();mainStatus="active";
        }
        catch(RuntimeException|LinkageError failure){fail(failure);}
    }
    public static void renderShadow(Camera camera,Frustum frustum,boolean entities,boolean blockEntities,
            List<BlockEntity> visibleBlockEntities,boolean hasEntityFrustum,double blockEntityDistance) {
        var pool=com.iridium126.createmanaindustry.client.packages.render.PackageRuntime.INSTANCE.packageParticles;
        if(!com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.currentFrameReady()){shadowStatus="current frame unavailable";return;}
        if(pool==null || pool.admissionCount()==0 || (!entities && !blockEntities) || !ClientConfig.shaderPackIntegration || reloadRequested)return;
        try {
            if(!prepare() || COMPILER.shadow()==null){shadowStatus="program unavailable";return;}
            if(!PackageShadowPolicy.copy(frustum,CULLING)){shadowStatus="unsupported shadow frustum: "+(frustum==null?"null":frustum.getClass().getName());return;}
            int words=(pool.packageCapacity()+31)>>>5;
            if(CHAIN_VISIBILITY_MASK.length!=words)CHAIN_VISIBILITY_MASK=new int[words];
            VISIBLE_CONVEYORS.clear();
            try {
                if(blockEntities && visibleBlockEntities!=null)for(BlockEntity entity:visibleBlockEntities)
                    if(entity instanceof ChainConveyorBlockEntity conveyor)VISIBLE_CONVEYORS.add(conveyor);
                var cameraPosition=camera.getPosition();
                PackageChainClientOwnership.INSTANCE.shadowVisibilityMask(CHAIN_VISIBILITY_MASK,VISIBLE_CONVEYORS,
                        cameraPosition.x,cameraPosition.y,cameraPosition.z,hasEntityFrustum,blockEntityDistance);
            }finally{VISIBLE_CONVEYORS.clear();}
            VIEW.set(ShadowRenderer.MODELVIEW);PROJECTION.set(ShadowRenderer.PROJECTION);
            timedDraw(SHADOW_TIMING,pool,PackagePoolGpu.DrawPass.SHADOW,COMPILER.shadow(),camera,entities,blockEntities,CHAIN_VISIBILITY_MASK);
            shadowStatus="active (current frame="+com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.renderFrameToken()+", generation="+com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.publication().generation()+", age=0)";
        }catch(RuntimeException|LinkageError failure){fail(failure);}
    }
    private static void fail(Throwable failure) {
        failedPipeline=Iris.getPipelineManager().getPipelineNullable();mainStatus=shadowStatus="draw failed; Create fallback";
        CreateManaIndustry.LOGGER.warn("[CMI packages] Iris draw failed",failure);
        PackageAuthorityClient.closeAll("Iris package draw failed",true);
    }
    private static void timedDraw(GpuPassTelemetry timing,PackagePoolGpu pool,PackagePoolGpu.DrawPass pass,
            PackageShaderCompiler.Program program,Camera camera,boolean ground,boolean chain,int[] chainVisibility) {
        com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.addExternalGpuMs(timing.poll());
        try(var accounting=com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.budgets.prepare()) {
            timing.begin(ClientConfig.particleAutoThrottle || com.iridium126.createmanaindustry.client.particles.engine.ParticleDiagnostics.INSTANCE.enabled());
            draw(pool,pass,program,camera,ground,chain,chainVisibility);
        }finally{timing.end();}
    }
    private static void draw(PackagePoolGpu pool,PackagePoolGpu.DrawPass pass,
            PackageShaderCompiler.Program program,Camera camera,boolean ground,boolean chain,int[] chainVisibility) {
        var mc=Minecraft.getInstance();var captured=CapturedRenderingState.INSTANCE;var position=camera.getPosition();
        int entity=captured.getCurrentRenderedEntity(),blockEntity=captured.getCurrentRenderedBlockEntity(),item=captured.getCurrentRenderedItem();
        float alpha=captured.getCurrentAlphaTest();boolean tessellation=ImmediateState.usingTessellation;
        int atlas=RenderSystem.getShaderTexture(0),light=RenderSystem.getShaderTexture(2);
        state.capture();boolean applied=false;
        try {
            pool.viewScale((float)net.minecraft.world.entity.Entity.getViewScale());
            pool.mainRenderDistance((float)(ClientConfig.particleFadeDistance+24.0));
            if(!pool.preparePass(pass,pool.committedPoolBuffer(),CULLING.planes,CULLING.count,CULLING.distance,CULLING.safe,
                    (float)position.x,(float)position.y,(float)position.z,chainVisibility))return;
            boolean sampled=pool.bindPassTbos(pass,PackageShaderCompiler.SAMPLER_BASE,com.iridium126.createmanaindustry.client.packages.render.PackageRuntime.INSTANCE.packagePartialTick);
            RenderSystem.setShaderTexture(0,InventoryMenu.BLOCK_ATLAS);
            RenderSystem.setShaderTexture(2,mc.getTextureManager().getTexture(mc.gameRenderer.lightTexture().lightTextureLocation).getId());
            var shader=program.shader();shader.MODEL_VIEW_MATRIX.set(VIEW);if(shader.PROJECTION_MATRIX!=null)shader.PROJECTION_MATRIX.set(PROJECTION);
            int packageId=WorldRenderingSettings.INSTANCE.getEntityIds()==null?0:WorldRenderingSettings.INSTANCE.getEntityIds().getInt(PACKAGE_ID);
            var blockIds=WorldRenderingSettings.INSTANCE.getBlockStateIds();int chainId=blockIds==null?0:blockIds.getOrDefault(AllBlocks.CHAIN_CONVEYOR.getDefaultState(),-1);
            GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthMask(true);GL11.glEnable(GL11.GL_CULL_FACE);GL11.glDisable(GL11.GL_BLEND);
            if(program.tessellation())GL40.glPatchParameteri(GL40.GL_PATCH_VERTICES,3);
            // Two constant-cost draws preserve Iris's per-entity custom uniform evaluation,
            // even when entityId itself was optimised out of the linked program.
            for(int layer=0;layer<2;layer++) {
                if(layer==0?!ground:!chain)continue;
                captured.setCurrentEntity(layer==0?packageId:0);captured.setCurrentBlockEntity(layer==1?chainId:0);captured.setCurrentRenderedItem(0);
                applied=true;shader.apply();upload(program,sampled,(float)position.x,(float)position.y,(float)position.z,layer==1?pool.chainInterpolationOr(com.iridium126.createmanaindustry.client.packages.render.PackageRuntime.INSTANCE.packagePartialTick):com.iridium126.createmanaindustry.client.packages.render.PackageRuntime.INSTANCE.packagePartialTick);
                integer(program,"entityId",layer==0?packageId:0);integer(program,"blockEntityId",layer==1?chainId:0);
                pool.drawPrepared(pass,layer,1,program.tessellation());shader.clear();applied=false;
            }
        }finally {
            try{if(applied)program.shader().clear();}
            finally {
                captured.setCurrentEntity(entity);captured.setCurrentBlockEntity(blockEntity);captured.setCurrentRenderedItem(item);captured.setCurrentAlphaTest(alpha);
                ImmediateState.usingTessellation=tessellation;
                try{RenderSystem.setShaderTexture(0,atlas);RenderSystem.setShaderTexture(2,light);}finally{state.restore();}
            }
        }
    }
    private static void upload(PackageShaderCompiler.Program p,boolean sampled,float x,float y,float z,float partialTick) {
        INVERSE_VIEW.set(VIEW).invert();INVERSE_PROJ.set(PROJECTION).invert();NORMAL.set(VIEW).invert().transpose();
        matrix(p,"iris_ModelViewMat",VIEW);matrix(p,"iris_ModelViewMatInverse",INVERSE_VIEW);matrix(p,"iris_ProjMat",PROJECTION);matrix(p,"iris_ProjMatInverse",INVERSE_PROJ);
        matrix(p,"iris_TextureMat",RenderSystem.getTextureMatrix());
        int normal=p.location("iris_NormalMat");if(normal>=0)GL20.glUniformMatrix3fv(normal,false,NORMAL.get(NORMAL_MATRIX));
        vector(p,"cmi_CameraPos",x,y,z);scalar(p,"cmi_PartialTick",partialTick);integer(p,"cmi_SampledLighting",sampled?1:0);
        integer(p,"cmi_BlockId",-1);integer(p,"currentRenderedItemId",0);
        int color=p.location("entityColor");if(color>=0)GL20.glUniform4f(color,0,0,0,0);
        color=p.location("iris_ColorModulator");if(color>=0)GL20.glUniform4f(color,1,1,1,1);vector(p,"iris_ChunkOffset",0,0,0);
        // Named pack uniforms (including shifted cameraPosition and shadow matrices)
        // belong to Iris's native providers. Only iris_* per-draw matrices need repair.
    }
    private static void matrix(PackageShaderCompiler.Program p,String name,Matrix4f value){int l=p.location(name);if(l>=0)GL20.glUniformMatrix4fv(l,false,value.get(MATRIX));}
    private static void scalar(PackageShaderCompiler.Program p,String name,float value){int l=p.location(name);if(l>=0)GL20.glUniform1f(l,value);}
    private static void integer(PackageShaderCompiler.Program p,String name,int value){int l=p.location(name);if(l>=0)GL20.glUniform1i(l,value);}
    private static void vector(PackageShaderCompiler.Program p,String name,float x,float y,float z){int l=p.location(name);if(l>=0)GL20.glUniform3f(l,x,y,z);}
}
