package com.iridium126.createmanaindustry.client.particles.shaderpack;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.accessor.CMIPackageIrisPipeline;
import com.iridium126.createmanaindustry.accessor.CMIPackageProgramSet;
import com.iridium126.createmanaindustry.client.particles.engine.ParticlePrograms;
import com.mojang.blaze3d.shaders.Uniform;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import net.irisshaders.iris.shaderpack.programs.*;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL41;

/** Native SBB material tracks. A failed reload cannot publish half a program bundle. */
public final class PackageShaderCompiler implements AutoCloseable {
    public static final int SAMPLER_BASE=10;
    public record Program(ShaderInstance shader,int atlasUnit,boolean tessellation,java.util.Map<String,Integer> uniforms,Uniform matrixPlaceholder) {
        public int location(String name){return uniforms.getOrDefault(name,-1);}
    }
    private static final String[] DRAW_UNIFORMS={"iris_ProjMat","iris_ProjMatInverse","iris_ModelViewMat","iris_ModelViewMatInverse",
            "iris_NormalMat","iris_TextureMat","cmi_PartialTick","cmi_CameraPos","cmi_SampledLighting",
            "cmi_BlockId","entityId","blockEntityId",
            "currentRenderedItemId","entityColor","iris_ColorModulator","iris_ChunkOffset"};
    private record Bundle(CMIPackageIrisPipeline owner,long revision,Program main,Program shadow) {}
    private Bundle live;
    private Object attemptedPipeline;
    private boolean attemptedShadows;
    private long revision,attemptedRevision=-1;
    private String error="";
    /** Resource-manager reload, even when Iris retains the same pipeline instance. */
    public void invalidate(){revision=Math.incrementExact(revision);}
    public String error(){return error;}
    public Program main(){return live==null?null:live.main;}
    public Program shadow(){return live==null?null:live.shadow;}
    public boolean usable() {
        var pipeline=Iris.getPipelineManager().getPipelineNullable();
        return live!=null && live.owner==pipeline
                && (!live.owner.cmi$packageHasShadows() || live.shadow!=null);
    }
    public boolean needsCompile() {
        var pipeline=Iris.getPipelineManager().getPipelineNullable();
        return pipeline instanceof CMIPackageIrisPipeline bridge
                && (attemptedPipeline!=pipeline || attemptedRevision!=revision || attemptedShadows!=bridge.cmi$packageHasShadows());
    }

    public boolean ensureCompiled() {
        var pipeline=Iris.getPipelineManager().getPipelineNullable();
        if(!(pipeline instanceof IrisRenderingPipeline))return false;
        CMIPackageIrisPipeline bridge=(CMIPackageIrisPipeline)pipeline;
        boolean hasShadows=bridge.cmi$packageHasShadows();
        if(attemptedPipeline==pipeline && attemptedRevision==revision && attemptedShadows==hasShadows)
            return live!=null && live.owner==pipeline && (!hasShadows || live.shadow!=null);
        attemptedPipeline=pipeline;attemptedRevision=revision;attemptedShadows=hasShadows;
        Program main=null,shadow=null;
        try {
            var set=bridge.cmi$packageProgramSet();var resolver=new ProgramFallbackResolver(set);
            var injector=new PackageVertexInjector();
            // Iris routes native solid/cutoutMipped SBB inside ENTITIES/BLOCK_ENTITIES
            // through MOVING_BLOCK. Shadow solid/cutout both use SHADOW_TERRAIN_CUTOUT.
            main=compile(bridge,set,resolver,injector,ShaderKey.MOVING_BLOCK,false);
            if(hasShadows)shadow=compile(bridge,set,resolver,injector,ShaderKey.SHADOW_TERRAIN_CUTOUT,true);
            Bundle next=new Bundle(bridge,revision,main,shadow),old=live;
            live=next;error="";
            // Cleanup errors cannot revoke a completely compiled new bundle.
            try{dispose(old);}catch(RuntimeException|LinkageError cleanup){CreateManaIndustry.LOGGER.warn("[CMI packages] Retired Iris bundle cleanup failed",cleanup);}
            return true;
        }catch(Exception|LinkageError failure) {
            discard(bridge,main);discard(bridge,shadow);
            error=failure.toString();CreateManaIndustry.LOGGER.warn("[CMI packages] Iris program bundle failed",failure);
            // Same-pipeline old resources remain usable. Never use shaders referring
            // to framebuffers and uniforms from a previous Iris pipeline.
            if(live!=null && live.owner!=bridge){dispose(live);live=null;}
            return live!=null && live.owner==bridge && (!hasShadows || live.shadow!=null);
        }
    }
    private static Program compile(CMIPackageIrisPipeline bridge,ProgramSet set,ProgramFallbackResolver resolver,
            PackageVertexInjector injector,ShaderKey key,boolean shadow) throws Exception {
        ProgramId id=key.getProgram();var ref=resolver.resolve(id).orElseThrow(()->new IllegalStateException("Missing package track "+id));
        String vertex=ref.getVertexSource().orElseThrow(),fragment=ref.getFragmentSource().orElseThrow();
        String name=shadow?"cmi_package_shadow":"cmi_package_block";
        String patched=injector.patch(vertex,ParticlePrograms::loadParticlePlain,name);
        patched=JcppProcessor.glslPreprocessSource(patched,StandardMacros.createStandardEnvironmentDefines());
        // Sources have already been include-resolved/preprocessed by Iris. Fragment,
        // geometry, tessellation and resolved directives are inherited verbatim.
        var properties=((CMIPackageProgramSet)set).cmi$packageProperties();
        var source=new ProgramSource(name,patched,ref.getGeometrySource().orElse(null),ref.getTessControlSource().orElse(null),
                ref.getTessEvalSource().orElse(null),fragment,set,properties,null).withDirectiveOverride(ref.getDirectives());
        ShaderInstance shader;
        try(var reservation=PackageSamplerReservation.open()) {
            shader=shadow
                ?bridge.cmi$createPackageShadowShader(name,source,id,key.getAlphaTest(),key.getVertexFormat(),false,false,false,false)
                :bridge.cmi$createPackageShader(name,source,id,key.getAlphaTest(),key.getVertexFormat(),key.getFogMode(),false,false,false,false,false);
        }
        if(shader==null)throw new IllegalStateException("Iris returned no package program");
        Uniform placeholder=null;
        try {
            // The placeholder is not in ShaderInstance.uniforms, so Iris cannot
            // release its native buffer. Keep ownership in the program bundle.
            if(shader.MODEL_VIEW_MATRIX==null)shader.MODEL_VIEW_MATRIX=placeholder=new Uniform("ModelViewMat",10,16,shader);
            String[] names={"cmi_PackagePool","cmi_PackageAttachment","cmi_PackageLight"};
            for(int i=0;i<names.length;i++)GL41.glProgramUniform1i(shader.getId(),GL20.glGetUniformLocation(shader.getId(),names[i]),SAMPLER_BASE+i);
            int atlas=-1;
            for(String uniform:new String[]{"gtexture","tex","texture0"}) {
                int location=GL20.glGetUniformLocation(shader.getId(),uniform);
                if(location>=0){atlas=GL20.glGetUniformi(shader.getId(),location);break;}
            }
            var locations=new java.util.HashMap<String,Integer>();
            for(String uniform:DRAW_UNIFORMS)locations.put(uniform,GL20.glGetUniformLocation(shader.getId(),uniform));
            return new Program(shader,atlas,ref.getTessControlSource().isPresent() || ref.getTessEvalSource().isPresent(),java.util.Map.copyOf(locations),placeholder);
        }catch(RuntimeException|LinkageError failure){try{bridge.cmi$discardPackageShader(shader);}finally{if(placeholder!=null)placeholder.close();}throw failure;}
    }
    private static void discard(CMIPackageIrisPipeline owner,Program program){if(program!=null)try{owner.cmi$discardPackageShader(program.shader);}finally{if(program.matrixPlaceholder!=null)program.matrixPlaceholder.close();}}
    private static void dispose(Bundle bundle){if(bundle!=null)try{discard(bundle.owner,bundle.main);}finally{discard(bundle.owner,bundle.shadow);}}
    @Override public void close(){var old=live;live=null;attemptedPipeline=null;attemptedRevision=-1;error="";dispose(old);}
}
