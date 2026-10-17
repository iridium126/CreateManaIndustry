package com.iridium126.createmanaindustry.client.particles.shaderpack;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.accessor.*;
import com.iridium126.createmanaindustry.client.particles.engine.ParticlePrograms;
import com.iridium126.createmanaindustry.client.hex.pattern.*;
import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;
import com.mojang.blaze3d.shaders.Uniform;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.*;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.vertices.ImmediateState;
import net.minecraft.client.*;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.entity.player.Player;
import org.joml.*;
import org.lwjgl.opengl.*;
import java.util.*;

/** Iris-only bridge; Hex shadows never require iris-veil or the MODEL shader. */
public final class HexIrisHook {
    private static final com.iridium126.createmanaindustry.client.gpu.shader.GpuUniformCache uniforms=new com.iridium126.createmanaindustry.client.gpu.shader.GpuUniformCache();
    private static CMIPackageIrisPipeline owner;private static ShaderInstance shader;private static Uniform placeholder;
    private static Object attempted;private static boolean tessellation;private static final Deque<Set<Player>> accepted=new ArrayDeque<>();
    private static final HexPassState state=new HexPassState();private static String status="off";
    private HexIrisHook(){}
    public static boolean inShadow(){return ShadowRenderer.ACTIVE;}
    public static boolean accepted(Player player){return ShadowRenderer.ACTIVE&&!accepted.isEmpty()&&accepted.peek().contains(player);}
    public static String status(){return status;}
    public static void begin(){accepted.push(new HashSet<>());}
    public static void end(){if(accepted.isEmpty())throw new IllegalStateException("Hex shadow scope");accepted.pop();}
    public static void invalidate(){uniforms.clear();if(shader!=null)HexPatternService.retireProgram(shader.getId());if(owner!=null&&shader!=null)owner.cmi$discardPackageShader(shader);if(placeholder!=null)placeholder.close();owner=null;shader=null;placeholder=null;attempted=null;}
    private static boolean ensure() {
        Object pipeline=Iris.getPipelineManager().getPipelineNullable();if(attempted==pipeline)return shader!=null&&owner==pipeline;
        invalidate();attempted=pipeline;if(!(pipeline instanceof CMIPackageIrisPipeline bridge)||!bridge.cmi$packageHasShadows())return false;
        try{
            var set=bridge.cmi$packageProgramSet();var ref=new ProgramFallbackResolver(set).resolve(ProgramId.Shadow).orElseThrow();
            String vertex=ref.getVertexSource().orElseThrow();String block="#version 400 compatibility\n"+ParticlePrograms.loadParticlePlain("chunks/hex_shared_vertex.glsl")+"\nvoid main(){cmiHexVertex();}";
            String patched=new ParticleVertexInjector().patchStrict(vertex,block,"cmi_hex_shadow",Map.of());
            patched=JcppProcessor.glslPreprocessSource(patched,StandardMacros.createStandardEnvironmentDefines());
            var properties=((CMIPackageProgramSet)set).cmi$packageProperties();
            var source=new ProgramSource("cmi_hex_shadow",patched,ref.getGeometrySource().orElse(null),ref.getTessControlSource().orElse(null),ref.getTessEvalSource().orElse(null),ref.getFragmentSource().orElseThrow(),set,properties,null).withDirectiveOverride(ref.getDirectives());
            try(var reservation=PackageSamplerReservation.open(2)){shader=bridge.cmi$createPackageShadowShader("cmi_hex_shadow",source,ProgramId.Shadow,ShaderKey.SHADOW_TEX_COLOR.getAlphaTest(),ShaderKey.SHADOW_TEX_COLOR.getVertexFormat(),false,false,false,false);}
            if(shader==null)throw new IllegalStateException("Missing Hex shadow shader");owner=bridge;
            if(shader.MODEL_VIEW_MATRIX==null)shader.MODEL_VIEW_MATRIX=placeholder=new Uniform("ModelViewMat",10,16,shader);
            tessellation=ref.getTessControlSource().isPresent();return true;
        }catch(Exception|LinkageError error){status="native fallback: "+error;CreateManaIndustry.LOGGER.warn("[CMI Hex] Native Iris shadow merge unavailable",error);if(shader!=null){HexPatternService.retireProgram(shader.getId());uniforms.retire(shader.getId());bridge.cmi$discardPackageShader(shader);shader=null;}return false;}
    }
    private static void matrix(int id,String name,Matrix4f value){int l=uniforms.location(id,name);if(l>=0)uniforms.matrix(id,name,value);}
    public static void draw(Camera camera,Frustum frustum,boolean entities,boolean playerOnly){
        if(!GL.getCapabilities().OpenGL45||!CreateManaIndustry.HEX_ACTIVE||!HexPatternService.enabled()||!ClientConfig.shaderPackIntegration||(!entities&&!playerOnly))return;
        var mc=Minecraft.getInstance();if(mc.level==null)return;
        float alpha=CapturedRenderingState.INSTANCE.getCurrentAlphaTest();boolean oldTessellation=ImmediateState.usingTessellation;
        try(var measurement=HexMetrics.phase(HexPass.IRIS_SHADOW,"prepare+draw");var ignored=state.capture()){
            if(!ensure()){status="native fallback: shader unavailable";return;}
            var draws=new ArrayList<HexGpuBackend.Draw>();var owners=new HashSet<Player>();var position=camera.getPosition();float partial=CapturedRenderingState.INSTANCE.getRealTickDelta();
            Iterable<? extends net.minecraft.world.entity.Entity> candidates=mc.level.entitiesForRendering();
            if(!entities){
                if(mc.player==null||mc.player.isSpectator()||!mc.getEntityRenderDispatcher().shouldRender(mc.player,frustum,position.x,position.y,position.z))return;
                var group=new ArrayList<net.minecraft.world.entity.Entity>(mc.player.getPassengers());if(mc.player.getVehicle()!=null)group.add(mc.player.getVehicle());group.add(mc.player);candidates=group;
            }
            for(var candidate:candidates){
                if(!(candidate instanceof Player player)||!((Object)mc.getEntityRenderDispatcher().getRenderer(player) instanceof net.minecraft.client.renderer.entity.player.PlayerRenderer))continue;
                if(entities&&(player.isSpectator()||!mc.getEntityRenderDispatcher().shouldRender(player,frustum,position.x,position.y,position.z)))continue;
                var list=HexPatternService.shadowDraws(player,mc.level.tickRateManager().isEntityFrozen(player)?CapturedRenderingState.INSTANCE.getTickDelta():partial,camera);
                if(list==null||draws.size()+list.size()>HexGpuBackend.MAX_INSTANCES)continue;
                if(!list.isEmpty()){draws.addAll(list);owners.add(player);}
            }
            if(draws.isEmpty())return;
            try(var batch=HexPatternService.prepareBatch(draws)){
                if(batch==null){status="native fallback: budget/bank";return;}
                boolean applied=false;try{
                    com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(0,at.petrak.hexcasting.client.render.VCDrawHelper.getWHITE());
                    applied=true;shader.apply();int id=shader.getId();
                    int white=mc.getTextureManager().getTexture(at.petrak.hexcasting.client.render.VCDrawHelper.getWHITE()).getId();
                    for(String name:new String[]{"gtexture","tex","texture0"}){int location=uniforms.location(id,name);if(location>=0){int unit=GL20.glGetUniformi(id,location);GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);GL11.glBindTexture(GL11.GL_TEXTURE_2D,white);}}HexPatternService.bindBatch(batch,id);
                    var view=new Matrix4f(ShadowRenderer.MODELVIEW);var projection=new Matrix4f(ShadowRenderer.PROJECTION);
                    matrix(id,"iris_ModelViewMat",view);matrix(id,"iris_ProjMat",projection);matrix(id,"iris_ModelViewMatInverse",new Matrix4f(view).invert());matrix(id,"iris_ProjMatInverse",new Matrix4f(projection).invert());
                    matrix(id,"shadowModelView",view);matrix(id,"shadowModelViewInverse",new Matrix4f(view).invert());
                    int cp=uniforms.location(id,"cameraPosition");if(cp>=0)GL20.glUniform3f(cp,(float)position.x,(float)position.y,(float)position.z);
                    HexPassState.nativeState();if(tessellation)GL40.glPatchParameteri(GL40.GL_PATCH_VERTICES,3);batch.draw(tessellation);
                    if(!accepted.isEmpty())accepted.peek().addAll(owners);status="active: current-frame, "+batch.count()+" patterns";
                }finally{if(applied)shader.clear();}
            }
        }catch(RuntimeException|LinkageError error){if(!accepted.isEmpty())accepted.peek().clear();status="native fallback: "+error;CreateManaIndustry.LOGGER.warn("[CMI Hex] Shadow batch failed",error);}
        finally{CapturedRenderingState.INSTANCE.setCurrentAlphaTest(alpha);ImmediateState.usingTessellation=oldTessellation;}
    }
}
