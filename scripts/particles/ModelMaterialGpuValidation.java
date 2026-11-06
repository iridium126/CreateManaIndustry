import com.iridium126.createmanaindustry.client.particles.shaderpack.*;
import com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackInventory;
import com.iridium126.createmanaindustry.client.particles.emitter.AllayFeatherLayout;
import com.iridium126.createmanaindustry.client.particles.engine.FeatherLeafDisplayGpu;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuPrograms;
import com.google.common.collect.ImmutableList;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.include.*;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import net.irisshaders.iris.shaderpack.option.ShaderPackOptions;
import net.irisshaders.iris.pipeline.transform.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.lwjgl.opengl.*;
import org.lwjgl.glfw.GLFW;

/** Actual eight-pack entity fragments and CMI/Iris vertex merge; hidden driver context only. */
// OffscreenIrisPlatform supplies the standalone loader services via gpu.init.gradle.
public final class ModelMaterialGpuValidation {
    static int checks;
    static int layers=1;
    static int receiverShadow=-1;
    static int receiverNoise=-1;
    static float receiverNormalY=1;
    static final List<String> receiverSamples=new ArrayList<>(List.of("options,stage,attachment,component,native_a,native_b,cmi_a,cmi_b"));
    static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    static final List<StringPair> DEFINES=new ArrayList<>(List.of(new StringPair("MC_VERSION","12101"),new StringPair("IS_IRIS","1"),new StringPair("MC_GL_VERSION","450"),new StringPair("MC_GLSL_VERSION","450"),new StringPair("MC_RENDER_QUALITY","1.0"),new StringPair("MC_SHADOW_QUALITY","1.0"),new StringPair("MC_HAND_DEPTH","0.125")));
    static Map<String,String> OPTION_OVERRIDES=Map.of();
    static boolean STRICT_CAPTURE_OPTIONS;
    static String preprocess(Path root,String file){
        var path=AbsolutePackPath.fromAbsolutePath("/"+file);var graph=new IncludeGraph(root,ImmutableList.of(path),false);
        if(!OPTION_OVERRIDES.isEmpty()){var options=new ShaderPackOptions(graph,OPTION_OVERRIDES);if(STRICT_CAPTURE_OPTIONS){var effective=new TreeMap<String,String>();options.getOptionSet().getBooleanOptions().keySet().forEach(k->effective.put(k,Boolean.toString(options.getOptionValues().getBooleanValueOrDefault(k))));options.getOptionSet().getStringOptions().keySet().forEach(k->effective.put(k,options.getOptionValues().getStringValueOrDefault(k)));for(var entry:effective.entrySet())if(!entry.getValue().equals(OPTION_OVERRIDES.get(entry.getKey())))throw new IllegalStateException("Compiled root option is absent or differs from capture: "+file+" / "+entry);}graph=options.getIncludes();}
        check(graph.getFailures().isEmpty(),file+": "+graph.getFailures());return JcppProcessor.glslPreprocessSource(String.join("\n",new IncludeProcessor(graph).getIncludedFile(path)),DEFINES);
    }
    static int compile(String vertex,String geometry,String fragment,String label,Path root)throws Exception{
        var patched=TransformPatcher.patchVanilla(label,vertex,geometry,null,null,fragment,net.irisshaders.iris.gl.blending.AlphaTests.ONE_TENTH_ALPHA,false,true,new net.irisshaders.iris.gl.state.ShaderAttributeInputs(true,true,false,true,true),new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<>());
        return link(patched,label,root);
    }
    static int link(Map<PatchShaderType,String> sources,String label,Path root)throws Exception{
        int p=GL20.glCreateProgram();var shaders=new ArrayList<Integer>();
        try{for(var entry:sources.entrySet())if(entry.getValue()!=null){
            int type=switch(entry.getKey()){case VERTEX->GL20.GL_VERTEX_SHADER;case FRAGMENT->GL20.GL_FRAGMENT_SHADER;case GEOMETRY->GL32.GL_GEOMETRY_SHADER;default->throw new AssertionError(entry.getKey());};
            Files.writeString(root.resolve(label+"-"+entry.getKey()+".glsl"),entry.getValue());int s=GL20.glCreateShader(type);shaders.add(s);GL20.glShaderSource(s,entry.getValue());GL20.glCompileShader(s);
            check(GL20.glGetShaderi(s,GL20.GL_COMPILE_STATUS)!=0,label+" "+entry.getKey()+": "+GL20.glGetShaderInfoLog(s));GL20.glAttachShader(p,s);
        }GL20.glLinkProgram(p);check(GL20.glGetProgrami(p,GL20.GL_LINK_STATUS)!=0,label+": "+GL20.glGetProgramInfoLog(p));return p;
        }catch(Throwable failure){GL20.glDeleteProgram(p);throw failure;}finally{shaders.forEach(GL20::glDeleteShader);}
    }
    // Only the raster inputs are synthetic; the native entity fragment and its
    // material encoders remain unmodified. This isolates material from animation.
    static String samplingVertex(String fragment){
        var out=new StringBuilder("#version 330 compatibility\n");var assignments=new StringBuilder();
        var inputs=Pattern.compile("(?m)^\\s*(flat\\s+)?in\\s+(\\w+)\\s+([^;]+);").matcher(fragment);
        while(inputs.find()){
            String type=inputs.group(2);out.append(inputs.group(1)==null?"":inputs.group(1)).append("out ").append(type).append(' ').append(inputs.group(3)).append(";\n");
            for(String name:inputs.group(3).split(",")){
                name=name.trim();String value=type+"(0)";
                if(name.equals("texCoord"))value="vec2(.5)";
                if(name.equals("color")||name.equals("glColor"))value="vec4(1)";
                if(name.equals("lmCoord")||name.equals("blockLight"))value="vec2(1)";
                if(type.equals("mat3"))value="mat3(1)";
                if(name.equals("normal")||name.equals("northVec"))value="vec3(0,0,1)";
                if(name.equals("upVec")||name.equals("sunVec"))value="vec3(0,1,0)";
                if(name.equals("eastVec")||name.equals("tangent"))value="vec3(1,0,0)";
                if(name.equals("binormal"))value="vec3(0,1,0)";
                if(name.equals("viewPos"))value="vec3(0,0,-10)";
                if(name.equals("materialIDs"))value="128.0";
                assignments.append(name).append('=').append(value).append(';');
            }
        }
        return out+"void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2-1,0,1);"+assignments+"}";
    }
    static int texture(float r,float g,float b,float a){int t=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,t);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL30.GL_RGBA32F,1,1,0,GL11.GL_RGBA,GL11.GL_FLOAT,new float[]{r,g,b,a});GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);return t;}
    static void integer(int p,String name,int value){GL20.glUniform1i(GL20.glGetUniformLocation(p,name),value);}
    static void matrices(int p){for(String name:List.of("gbufferModelView","gbufferModelViewInverse","gbufferProjectionInverse","shadowModelViewInverse"))GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(p,name),false,new org.joml.Matrix4f().get(new float[16]));}
    static float[][] draw(int p,int[] textures,float background,boolean broken,boolean model,int entityId){
        GL20.glUseProgram(p);matrices(p);integer(p,"entityId",entityId);if(model)new ModelEntityContext(entityId).upload(p,false);
        for(String name:List.of("tex","gtexture","iris_Sampler0","noisetex","shadowtex0","shadowtex1","shadowcolor0","shadowcolor1"))integer(p,name,0);
        integer(p,"specular",1);integer(p,"normals",2);GL20.glUniform2i(GL20.glGetUniformLocation(p,"atlasSize"),128,64);GL20.glUniform1f(GL20.glGetUniformLocation(p,"viewWidth"),4);GL20.glUniform1f(GL20.glGetUniformLocation(p,"viewHeight"),4);GL20.glUniform1f(GL20.glGetUniformLocation(p,"far"),256);GL20.glUniform1f(GL20.glGetUniformLocation(p,"near"),.1f);GL20.glUniform1f(GL20.glGetUniformLocation(p,"sunAngle"),.25f);GL20.glUniform1f(GL20.glGetUniformLocation(p,"timeAngle"),.25f);
        GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDisable(GL11.GL_CULL_FACE);
        for(int i=0;i<textures.length;i++){GL13.glActiveTexture(GL13.GL_TEXTURE0+i);GL11.glBindTexture(GL11.GL_TEXTURE_2D,textures[i]);}
        if(receiverShadow>=0){for(String name:List.of("shadowtex0","shadowtex1"))integer(p,name,4);GL20.glUniform1f(GL20.glGetUniformLocation(p,"shadowFade"),1);GL20.glUniform1f(GL20.glGetUniformLocation(p,"timeBrightness"),1);GL13.glActiveTexture(GL13.GL_TEXTURE4);GL11.glBindTexture(GL11.GL_TEXTURE_2D,receiverShadow);}
        if(receiverNoise>=0){integer(p,"noisetex",5);GL13.glActiveTexture(GL13.GL_TEXTURE5);GL11.glBindTexture(GL11.GL_TEXTURE_2D,receiverNoise);GL20.glUniform1f(GL20.glGetUniformLocation(p,"cmiProbeNormalY"),receiverNormalY);}
        for(int i=0;i<3;i++)GL30.glClearBufferfv(GL11.GL_COLOR,i,new float[]{background,background,background,background});
        GL11.glDisable(GL11.GL_BLEND);if(broken){GL11.glEnable(GL11.GL_BLEND);GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_ONE,GL11.GL_ONE_MINUS_SRC_ALPHA);}
        GL30.glEnablei(GL11.GL_BLEND,0);GL40.glBlendFuncSeparatei(0,GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_ZERO,GL11.GL_ONE);
        for(int layer=0;layer<layers;layer++)GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);float[][] result=new float[3][4];for(int i=0;i<3;i++){GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0+i);GL11.glReadPixels(2,2,1,1,GL11.GL_RGBA,GL11.GL_FLOAT,result[i]);}return result;
    }
    static void materials(String fragment,boolean iteration,int allayId,Path root,boolean nativeEmission)throws Exception{
        var transformed=TransformPatcher.patchVanilla("native-fragment",samplingVertex(fragment.replaceAll("\\bvarying\\b","in")),null,null,null,fragment,net.irisshaders.iris.gl.blending.AlphaTests.ONE_TENTH_ALPHA,false,true,new net.irisshaders.iris.gl.state.ShaderAttributeInputs(true,true,false,true,true),new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<>());
        String f=transformed.get(PatchShaderType.FRAGMENT);int p=link(Map.of(PatchShaderType.VERTEX,samplingVertex(f),PatchShaderType.FRAGMENT,f),"material",root);
        int fbo=GL30.glGenFramebuffers(),vao=GL30.glGenVertexArrays();GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);GL30.glBindVertexArray(vao);int[] targets=new int[3];
        for(int i=0;i<3;i++){targets[i]=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,targets[i]);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL30.GL_RGBA32F,4,4,0,GL11.GL_RGBA,GL11.GL_FLOAT,(java.nio.ByteBuffer)null);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0+i,GL11.GL_TEXTURE_2D,targets[i],0);}GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0,GL30.GL_COLOR_ATTACHMENT1,GL30.GL_COLOR_ATTACHMENT2});GL11.glViewport(0,0,4,4);
        int albedo=texture(.96f,.97f,1,.35f),specular=texture(.3f,.6f,.2f,.4f),normal=texture(.5f,.5f,1,1);int[] textures={albedo,specular,normal};
        try{
            if(iteration){
                float[][] sky=draw(p,textures,0,false,true,allayId),terrain=draw(p,textures,.8f,false,true,allayId),broken=draw(p,textures,0,true,true,allayId),brokenTerrain=draw(p,textures,.8f,true,true,allayId);
                boolean reproduced=false;for(int attachment:new int[]{1,2})for(int i=0;i<4;i++){check(Float.isFinite(sky[attachment][i]),"finite material output");check(Math.abs(sky[attachment][i]-terrain[attachment][i])<.00001,"native metadata independent of sky/terrain");reproduced|=Math.abs(broken[attachment][i]-brokenTerrain[attachment][i])>.01;}
                check(reproduced,"old global alpha blend must reproduce background contamination");
                layers=2;float[][] overlapping=draw(p,textures,.8f,false,true,allayId);layers=1;
                for(int attachment:new int[]{1,2})for(int i=0;i<4;i++)check(Math.abs(overlapping[attachment][i]-sky[attachment][i])<.00001,"overlapping wings preserve encoded outputs");
                // The native two-byte packed channel decodes identically for both backgrounds.
                String utilities=preprocess(root,"Lib/Utilities.glsl");int decoder=link(Map.of(PatchShaderType.VERTEX,"#version 330 compatibility\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2-1,0,1);}",PatchShaderType.FRAGMENT,"#version 330 compatibility\n"+utilities+"\nuniform vec4 cmiPacked;layout(location=0)out vec4 decoded;void main(){decoded=vec4(Unpack2x8(cmiPacked.x),Unpack2x8(cmiPacked.y));}"),"native-decode",root);
                try{GL11.glDisable(GL11.GL_BLEND);float[] previous=null;for(float[][] value:List.of(sky,terrain)){GL20.glUseProgram(decoder);GL20.glUniform4fv(GL20.glGetUniformLocation(decoder,"cmiPacked"),value[2]);GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);float[] decoded=new float[4];GL11.glReadPixels(2,2,1,1,GL11.GL_RGBA,GL11.GL_FLOAT,decoded);for(int i=0;i<4;i++)check(Float.isFinite(decoded[i])&&(previous==null||Math.abs(decoded[i]-previous[i])<.00001),"native decoder metadata equality");previous=decoded;}}finally{GL20.glDeleteProgram(decoder);}
            }else{
                for(float[] color:new float[][]{{.98f,.98f,1,1},{.2f,.65f,.8f,1},{.2f,.65f,.8f,.3f}}){GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glBindTexture(GL11.GL_TEXTURE_2D,albedo);GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,0,1,1,GL11.GL_RGBA,GL11.GL_FLOAT,color);
                    float[][] nativeOutput=draw(p,textures,0,false,false,allayId),model=draw(p,textures,0,false,true,allayId);for(int a=0;a<3;a++)for(int i=0;i<4;i++)check(Float.isFinite(model[a][i])&&Math.abs(nativeOutput[a][i]-model[a][i])<.0001,"native allay vs model material context");
                }
                float[][] neutral=draw(p,textures,0,false,true,0),allay=draw(p,textures,0,false,true,allayId);boolean differs=false;for(int a=0;a<3;a++)for(int i=0;i<3;i++)differs|=Math.abs(neutral[a][i]-allay[a][i])>.001;if(nativeEmission)check(differs,"native allay branch must affect output");
            }
        }finally{for(int t:targets)GL11.glDeleteTextures(t);for(int t:textures)GL11.glDeleteTextures(t);GL30.glDeleteFramebuffers(fbo);GL30.glDeleteVertexArrays(vao);GL20.glDeleteProgram(p);com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clear();}
    }
    static void receiverOutputs(String fragment,ShadowReceiverContract contract,Path root)throws Exception{
        String low=contract.adapt(new ModelShaderOptimization.Stages("",null,null,null,fragment)).fragment();
        int[] programs=new int[2];int fbo=GL30.glGenFramebuffers(),vao=GL30.glGenVertexArrays();GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);GL30.glBindVertexArray(vao);
        int[] targets=new int[3];for(int i=0;i<3;i++){targets[i]=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,targets[i]);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL30.GL_RGBA32F,4,4,0,GL11.GL_RGBA,GL11.GL_FLOAT,(java.nio.ByteBuffer)null);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0+i,GL11.GL_TEXTURE_2D,targets[i],0);}
        GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0,GL30.GL_COLOR_ATTACHMENT1,GL30.GL_COLOR_ATTACHMENT2});GL11.glViewport(0,0,4,4);
        int[] textures={texture(.8f,.7f,.6f,.35f),texture(.3f,.6f,.2f,.4f),texture(.5f,.5f,1,1)};
        int shadow=GL11.glGenTextures();receiverShadow=shadow;
        GL13.glActiveTexture(GL13.GL_TEXTURE5);int noise=texture(0,0,0,1);receiverNoise=noise;
        try{
            for(int i=0;i<2;i++){
                String frag=i==0?fragment:low;var stages=TransformPatcher.patchVanilla("receiver-output",samplingVertex(frag.replaceAll("\\bvarying\\b","in")),null,null,null,frag,net.irisshaders.iris.gl.blending.AlphaTests.ONE_TENTH_ALPHA,false,true,new net.irisshaders.iris.gl.state.ShaderAttributeInputs(true,true,false,true,true),new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<>());
                String f=stages.get(PatchShaderType.FRAGMENT),v=samplingVertex(f);
                v=v.replace("void main(){","uniform float cmiProbeNormalY;\nvoid main(){").replace("lightVec=vec3(0);","lightVec=vec3(0,1,0);").replace("normal=vec3(0,0,1);","normal=vec3(0,cmiProbeNormalY,sqrt(max(1.-cmiProbeNormalY*cmiProbeNormalY,0.)));");
                programs[i]=link(Map.of(PatchShaderType.VERTEX,v,PatchShaderType.FRAGMENT,f),"receiver-output-"+i,root);
            }
            float[][][] nativeValues=new float[2][][],lowValues=new float[2][][];
            for(int d=0;d<2;d++){
                GL13.glActiveTexture(GL13.GL_TEXTURE4);GL11.glBindTexture(GL11.GL_TEXTURE_2D,shadow);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL30.GL_DEPTH_COMPONENT32F,1,1,0,GL11.GL_DEPTH_COMPONENT,GL11.GL_FLOAT,new float[]{d});
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL14.GL_TEXTURE_COMPARE_MODE,GL30.GL_COMPARE_REF_TO_TEXTURE);
                nativeValues[d]=draw(programs[0],textures,.2f,false,false,0);lowValues[d]=draw(programs[1],textures,.2f,false,false,0);
            }
            boolean witness=false;for(int a=0;a<3;a++)for(int k=0;k<4;k++){
                check(Float.isFinite(lowValues[0][a][k]),"finite unshadowed native material");
                check(Math.abs(lowValues[0][a][k]-lowValues[1][a][k])<1e-5,"unshadowed output depends on scene shadow depth");
                check(Math.abs(lowValues[1][a][k]-nativeValues[1][a][k])<1e-5,"unshadowed material differs from unoccluded native lighting");
                witness|=Math.abs(nativeValues[0][a][k]-nativeValues[1][a][k])>1e-5;
                receiverSample("scene",a,k,nativeValues[0][a][k],nativeValues[1][a][k],lowValues[0][a][k],lowValues[1][a][k]);
            }
            check(witness==!"false".equals(OPTION_OVERRIDES.get("SHADOW")),"native lighting probe scene-shadow witness disagrees with effective options");
            if("true".equals(OPTION_OVERRIDES.get("SHADOW_CLOUD"))){
                float[][] cloudNative=draw(programs[0],textures,.2f,false,false,0),cloudLow=draw(programs[1],textures,.2f,false,false,0);
                // Opposite constant noise endpoints alter only cloud density; albedo and light stay fixed.
                GL13.glActiveTexture(GL13.GL_TEXTURE5);GL11.glBindTexture(GL11.GL_TEXTURE_2D,noise);GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,0,1,1,GL11.GL_RGBA,GL11.GL_FLOAT,new float[]{1,1,1,0});
                float[][] denseNative=draw(programs[0],textures,.2f,false,false,0),denseLow=draw(programs[1],textures,.2f,false,false,0);boolean cloudWitness=false;
                for(int a=0;a<3;a++)for(int k=0;k<4;k++){check(Math.abs(cloudLow[a][k]-denseLow[a][k])<1e-5,"cloud shadow still attenuates CMI");cloudWitness|=Math.abs(cloudNative[a][k]-denseNative[a][k])>1e-5;receiverSample("cloud",a,k,cloudNative[a][k],denseNative[a][k],cloudLow[a][k],denseLow[a][k]);}
                check(cloudWitness,"native cloud branch must visibly respond to noise perturbation");
            }
            if(!"false".equals(OPTION_OVERRIDES.get("SHADOW"))){
                receiverNormalY=.25f;float[][] angled=draw(programs[1],textures,.2f,false,false,0);boolean directional=false;
                for(int a=0;a<3;a++)for(int k=0;k<4;k++){check(Float.isFinite(angled[a][k]),"finite angled native light");directional|=Math.abs(angled[a][k]-lowValues[1][a][k])>1e-5;}
                check(directional,"unshadowed material must retain directional response");
            }
            Files.write(root.resolve("receiver-samples.csv"),receiverSamples);
        }finally{receiverShadow=receiverNoise=-1;receiverNormalY=1;for(int p:programs)if(p!=0)GL20.glDeleteProgram(p);for(int t:textures)GL11.glDeleteTextures(t);for(int t:targets)GL11.glDeleteTextures(t);GL11.glDeleteTextures(shadow);GL11.glDeleteTextures(noise);GL30.glDeleteFramebuffers(fbo);GL30.glDeleteVertexArrays(vao);}
    }
    static void receiverSample(String stage,int attachment,int component,float nativeA,float nativeB,float lowA,float lowB){
        String options=OPTION_OVERRIDES.isEmpty()?"default":new TreeMap<>(OPTION_OVERRIDES).entrySet().stream().map(e->e.getKey()+"="+e.getValue()).collect(java.util.stream.Collectors.joining(";"));
        receiverSamples.add(options+","+stage+","+attachment+","+component+","+nativeA+","+nativeB+","+lowA+","+lowB);
    }
    static void atlasBindings()throws Exception{
        var atlas=com.iridium126.createmanaindustry.client.particles.engine.ParticleAtlas.ALLAY;
        var field=atlas.getClass().getDeclaredField("textureId");field.setAccessible(true);int original=field.getInt(atlas);
        var bind=CMIPackEntityMergeHook.class.getDeclaredMethod("bindAtlas",int.class);bind.setAccessible(true);
        int sentinel=texture(1,1,1,1),loaded=texture(.2f,.65f,.8f,1);
        try{
            for(int missing:new int[]{-1,0}){
                field.setInt(atlas,missing);
                com.iridium126.createmanaindustry.client.gpu.state.GpuTexture2DState.bind(0,sentinel);
                com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(0,sentinel);
                bind.invoke(null,0);
                check(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)==sentinel,"unallocated atlas must not bind a texture name");
                check(com.mojang.blaze3d.systems.RenderSystem.getShaderTexture(0)==sentinel,"unallocated atlas must not poison Iris's engine texture table");
                check(GL11.glGetError()==0,"unallocated atlas GL error");
            }
            field.setInt(atlas,loaded);bind.invoke(null,0);
            check(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)==loaded,"loaded atlas GL binding");
            check(com.mojang.blaze3d.systems.RenderSystem.getShaderTexture(0)==loaded,"loaded atlas engine binding");
            // Iris ExtendedShader.setupTextures uses these DSA operations on the engine table.
            int current=com.mojang.blaze3d.systems.RenderSystem.getShaderTexture(0);
            GL45.glBindTextureUnit(0,current);GL45.glTextureParameteriv(current,GL33.GL_TEXTURE_SWIZZLE_RGBA,new int[]{GL11.GL_RED,GL11.GL_GREEN,GL11.GL_BLUE,GL11.GL_ALPHA});
            check(GL11.glGetError()==0,"Iris DSA atlas operations");
        }finally{field.setInt(atlas,original);com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(0,0);com.iridium126.createmanaindustry.client.gpu.state.GpuTexture2DState.bind(0,0);GL11.glDeleteTextures(sentinel);GL11.glDeleteTextures(loaded);}
    }
    static void mergedFeatherVertices(String vertex,String fragment,String label)throws Exception{
        var patched=TransformPatcher.patchVanilla(label,vertex,null,null,null,fragment,net.irisshaders.iris.gl.blending.AlphaTests.ONE_TENTH_ALPHA,false,true,new net.irisshaders.iris.gl.state.ShaderAttributeInputs(true,true,false,true,true),new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<>());
        // Capture CMI's actual output before pack projection/temporal uniforms, which require a live world.
        String input=patched.get(PatchShaderType.VERTEX);check(input.contains("vec4 cmi_VertexLevel;"),"CMI feedback anchor");input=input.replace("vec4 cmi_VertexLevel;","out vec4 cmi_VertexLevel;");
        int shader=GL20.glCreateShader(GL20.GL_VERTEX_SHADER);GL20.glShaderSource(shader,input);GL20.glCompileShader(shader);check(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)!=0,label+GL20.glGetShaderInfoLog(shader));
        int program=GL20.glCreateProgram();GL20.glAttachShader(program,shader);GL30.glTransformFeedbackVaryings(program,new String[]{"cmi_VertexLevel"},GL30.GL_INTERLEAVED_ATTRIBS);GL20.glLinkProgram(program);GL20.glDeleteShader(shader);check(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)!=0,label+GL20.glGetProgramInfoLog(program));
        int prefix=16384+4096,stride=com.iridium126.createmanaindustry.client.particles.emitter.AllayFeatherLayout.WORDS,visible=com.iridium126.createmanaindustry.client.particles.emitter.AllayFeatherLayout.VISIBLE;
        var particles=org.lwjgl.BufferUtils.createByteBuffer(3*64);
        for(int i=0;i<3;i++){int at=i*64;particles.putFloat(at,i==0?0:(i==1?.25f:-.25f)).putFloat(at+12,i==0?100000:1).putFloat(at+52,60);}
        boolean sway=GL20.glGetUniformLocation(program,"cmi_FeatherSwayEnabled")>=0;
        if(sway){
            particles.putFloat(64+4,1.004f).putFloat(64+12,-1).putInt(64+56,(1<<18)|(15<<19));
            particles.putInt(128+56,(1<<18)|(15<<19));
        }
        var metadata=org.lwjgl.BufferUtils.createByteBuffer((prefix+stride*2)*4);
        for(int bank=0;bank<2;bank++){int base=prefix+bank*stride;metadata.putInt((base+11)*4,6).putInt((base+12)*4,2);metadata.putInt((base+visible)*4,bank==0?1:2).putInt((base+visible+1)*4,bank==0?2:1);}
        int pool=GL15.glGenBuffers(),state=GL15.glGenBuffers(),feedback=GL15.glGenBuffers(),vao=GL30.glGenVertexArrays(),poolTex=GL11.glGenTextures(),stateTex=GL11.glGenTextures(),noise=GL11.glGenTextures();
        try{
            GL30.glBindVertexArray(vao);GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER,pool);GL15.glBufferData(GL31.GL_TEXTURE_BUFFER,particles,GL15.GL_STATIC_DRAW);GL13.glActiveTexture(GL13.GL_TEXTURE11);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,poolTex);GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,GL30.GL_RGBA32F,pool);
            GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER,state);GL15.glBufferData(GL31.GL_TEXTURE_BUFFER,metadata,GL15.GL_STATIC_DRAW);GL13.glActiveTexture(GL13.GL_TEXTURE13);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,stateTex);GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,GL32.GL_RG32UI,state);
            GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,feedback);GL15.glBufferData(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,192,GL15.GL_DYNAMIC_READ);GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,0,feedback);GL20.glUseProgram(program);
            int active=GL20.glGetProgrami(program,GL20.GL_ACTIVE_UNIFORMS);
            for(int i=0;i<active;i++)try(var stack=org.lwjgl.system.MemoryStack.stackPush()){
                var size=stack.mallocInt(1);var type=stack.mallocInt(1);String name=GL20.glGetActiveUniform(program,i,size,type);int location=GL20.glGetUniformLocation(program,name),kind=type.get(0);
                if(kind==GL20.GL_FLOAT_MAT4)GL20.glUniformMatrix4fv(location,false,new org.joml.Matrix4f().get(new float[16]));
                if(kind==GL20.GL_FLOAT_MAT3)GL20.glUniformMatrix3fv(location,false,new org.joml.Matrix3f().get(new float[9]));
                if(kind==GL31.GL_SAMPLER_BUFFER)GL20.glUniform1i(location,11);
                if(kind==GL31.GL_UNSIGNED_INT_SAMPLER_BUFFER)GL20.glUniform1i(location,13);
            }
            integer(program,"cmi_FeatherPass",1);integer(program,"cmi_LodPass",0);GL20.glUniform3f(GL20.glGetUniformLocation(program,"cmi_FeatherRight"),1,0,0);GL20.glUniform3f(GL20.glGetUniformLocation(program,"cmi_FeatherUp"),0,1,0);
            GL20.glUniform1f(GL20.glGetUniformLocation(program,"frameTimeCounter"),19.25f);
            GL20.glUniform1f(GL20.glGetUniformLocation(program,"wetness"),.45f);
            GL20.glUniform1f(GL20.glGetUniformLocation(program,"rainStrength"),.65f);
            GL20.glUniform1f(GL20.glGetUniformLocation(program,"rainFactor"),.65f);
            GL20.glUniform1f(GL20.glGetUniformLocation(program,"weatherStrength"),.45f);
            if(GL20.glGetUniformLocation(program,"noisetex")>=0){
                var pattern=org.lwjgl.BufferUtils.createByteBuffer(16);
                pattern.putInt(0,0xf0508090).putInt(4,0xd0406020).putInt(8,0xb0304050).putInt(12,0xf060a0e0);
                GL13.glActiveTexture(GL13.GL_TEXTURE7);GL11.glBindTexture(GL11.GL_TEXTURE_2D,noise);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,2,2,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pattern);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_LINEAR);
                integer(program,"noisetex",7);
            }
            integer(program,"worldTime",1300);
            GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER,state);
            for(int bank=0;bank<2;bank++){
                int base=prefix+bank*stride;integer(program,"cmi_FeatherWordBase",base);
                var result=featherVertices(program,base,feedback,false);
                for(int i=0;i<48;i++){float value=result.getFloat(i*4);check(Float.isFinite(value)&&Math.abs(value)<2,label+" read a MODEL sentinel through the feather index: "+value);}
                check(GL11.glGetError()==0,label+" indirect/TBO draw GL error");
            }
        }finally{GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);GL20.glUseProgram(0);GL20.glDeleteProgram(program);GL30.glDeleteVertexArrays(vao);for(int t:new int[]{poolTex,stateTex,noise})GL11.glDeleteTextures(t);for(int b:new int[]{pool,state,feedback})GL15.glDeleteBuffers(b);}
    }
    private static java.nio.ByteBuffer featherVertices(int program,int base,int feedback,boolean sway){
        integer(program,"cmi_FeatherSwayEnabled",sway?1:0);
        GL30.glBeginTransformFeedback(GL11.GL_TRIANGLES);
        GL40.glDrawArraysIndirect(GL11.GL_TRIANGLES,(base+11)*4L);
        GL30.glEndTransformFeedback();
        GL42.glMemoryBarrier(GL42.GL_TRANSFORM_FEEDBACK_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        var result=org.lwjgl.BufferUtils.createByteBuffer(192);
        GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,feedback);
        GL15.glGetBufferSubData(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,0,result);
        return result;
    }

    private static final class NativeLeafReference implements AutoCloseable {
        final int program, vao, feedback;
        NativeLeafReference(FeatherLeafSway.Plan leaf, String source) {
            // Observe the native terrain result just after its own animation,
            // before projection and pack-specific jitter.
            String[] probe=switch(leaf.pack()){
                case "Complementary"->new String[]{"(DoWave\\(position\\.xyz,\\s*mat\\);)","$1 cmiNativeLeaf=position.xyz;"};
                case "Bliss"->new String[]{"(position\\s*=\\s*mat3\\(gbufferModelView\\)\\s*\\*\\s*worldpos)","cmiNativeLeaf=worldpos; $1"};
                case "BSL"->new String[]{"(position\\.xyz\\s*=\\s*WavingBlocks\\([^;]+;)","$1 cmiNativeLeaf=position.xyz;"};
                case "iterationT"->new String[]{"(worldPos\\.xyz\\s*-=\\s*cameraPosition\\.xyz;)","$1 cmiNativeLeaf=worldPos.xyz;"};
                case "Photon"->new String[]{"(pos\\s*=\\s*pos\\s*-\\s*cameraPosition;)","$1 cmiNativeLeaf=pos;"};
                case "Sildur’s"->new String[]{"(gl_Position\\s*=\\s*gl_ProjectionMatrix)","cmiNativeLeaf=position.xyz; $1"};
                case "Solas"->new String[]{"(position\\.xyz\\s*=\\s*getWavingBlocks\\([^;]+;)","$1 cmiNativeLeaf=position.xyz;"};
                case "Sundial Lite"->new String[]{"(worldPos\\s*-=\\s*waveOffset;)","$1 cmiNativeLeaf=worldPos;"};
                default->throw new AssertionError(leaf.pack());
            };
            int main=source.indexOf("void main");
            check(main>=0,leaf.pack()+" native terrain main");
            String patchedMain=source.substring(main).replaceFirst(probe[0],probe[1]);
            check(!patchedMain.equals(source.substring(main)),leaf.pack()+" native leaf instrumentation");
            var version=Pattern.compile("(?m)^\\s*#version\\s+(\\d+)[^\\r\\n]*").matcher(source);
            check(version.find(),leaf.pack()+" native terrain GLSL version");
            String declaration=Integer.parseInt(version.group(1))<130?"varying vec3":"out vec3";
            String instrumented=source.substring(0,version.end())+"\n"+declaration+" cmiNativeLeaf;\n"
                    +source.substring(version.end(),main)+patchedMain;
            var patched=TransformPatcher.patchVanilla("native-leaf",instrumented,null,null,null,null,
                    net.irisshaders.iris.gl.blending.AlphaTests.ONE_TENTH_ALPHA,false,true,
                    new net.irisshaders.iris.gl.state.ShaderAttributeInputs(true,true,false,true,true),
                    new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<>());
            int shader=GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
            GL20.glShaderSource(shader,patched.get(PatchShaderType.VERTEX));GL20.glCompileShader(shader);
            check(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)!=0,leaf.pack()+" native terrain: "+GL20.glGetShaderInfoLog(shader));
            program=GL20.glCreateProgram();GL20.glAttachShader(program,shader);
            GL30.glTransformFeedbackVaryings(program,new String[]{"cmiNativeLeaf","gl_Position"},GL30.GL_INTERLEAVED_ATTRIBS);
            GL20.glLinkProgram(program);GL20.glDeleteShader(shader);
            check(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)!=0,leaf.pack()+" native terrain: "+GL20.glGetProgramInfoLog(program));
            vao=GL30.glGenVertexArrays();feedback=GL45.glCreateBuffers();
            GL45.glNamedBufferData(feedback,112,GL15.GL_DYNAMIC_READ);
        }
        float[] sample(float[][] corners,int material,float time,float rawCamera,float irisCamera) {
            GL20.glUseProgram(program);GL30.glBindVertexArray(vao);matrices(program);
            for(String name:List.of("iris_ModelViewMat","iris_ModelViewMatInverse","iris_ProjMat","gbufferProjection","gbufferModelViewInverse")){
                GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program,name),false,new org.joml.Matrix4f().get(new float[16]));
            }
            GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program,"iris_LightmapTextureMatrix"),false,
                    new org.joml.Matrix4f().translation(.03125f,.03125f,.03125f).scale(1f/256).get(new float[16]));
            leafUniforms(program,time,irisCamera);
            int position=GL20.glGetAttribLocation(program,"iris_Position"), light=GL20.glGetAttribLocation(program,"iris_UV2");
            int entity=GL20.glGetAttribLocation(program,"mc_Entity"), normal=GL20.glGetAttribLocation(program,"iris_Normal");
            check(position>=0&&entity>=0,"native terrain attributes");
            GL20.glVertexAttrib4f(entity,material,0,0,1);
            if(normal>=0)GL20.glVertexAttrib3f(normal,0,1,0);
            GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,0,feedback);
            GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
            GL30.glBeginTransformFeedback(GL11.GL_POINTS);
            for(float[] corner:corners){
                GL20.glVertexAttrib3f(position,corner[0]-rawCamera,corner[1],corner[2]);
                if(light>=0)GL30.glVertexAttribI2i(light,0,(int)corner[3]);
                GL11.glDrawArrays(GL11.GL_POINTS,0,1);
            }
            GL30.glEndTransformFeedback();GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
            var read=org.lwjgl.BufferUtils.createFloatBuffer(28);
            GL45.glGetNamedBufferSubData(feedback,0,read);
            float[] result=new float[28];read.get(result);
            for(int k=0;k<28;k+=7)result[k]+=rawCamera;
            return result;
        }
        @Override public void close(){
            GL20.glUseProgram(0);GL30.glDeleteVertexArrays(vao);GL15.glDeleteBuffers(feedback);GL20.glDeleteProgram(program);
        }
    }

    private static void leafUniforms(int program,float time,float camera) {
        integer(program,"worldTime",1300);
        integer(program,"noisetex",7);
        for(String name:List.of("rainStrength","rainFactor","weatherStrength","wetness")){
            GL20.glUniform1f(GL20.glGetUniformLocation(program,name),.45f);
        }
        GL20.glUniform1f(GL20.glGetUniformLocation(program,"frameTimeCounter"),time);
        GL20.glUniform3f(GL20.glGetUniformLocation(program,"cameraPosition"),camera,0,0);
        GL20.glUniform1f(GL20.glGetUniformLocation(program,"viewWidth"),2560);
        GL20.glUniform1f(GL20.glGetUniformLocation(program,"viewHeight"),1506);
    }

    static float leafDisplay(FeatherLeafSway.Plan leaf, String terrain, String label) {
        int program=GpuShaderCompiler.compute(label,FeatherLeafDisplayGpu.source(FeatherLeafSway.displaySource(leaf,leaf),
                ModelMaterialGpuValidation::particleSource));
        int[] buffers=new int[4];
        GL45.glCreateBuffers(buffers);
        int noise=0;
        try (var nativeLeaf=new NativeLeafReference(leaf,terrain)) {
            var pool=org.lwjgl.BufferUtils.createByteBuffer(4*64);
            for(int i=0;i<4;i++){
                int base=i*64;
                float x=i==0?.5f:.25f;
                float z=i==0?.5f:.75f;
                pool.putFloat(base,x).putFloat(base+4,1.004f).putFloat(base+8,z)
                        .putFloat(base+12,i==2?1:-1).putFloat(base+28,i==0?.23f:0).putInt(base+56,1<<18);
            }
            var visible=org.lwjgl.BufferUtils.createByteBuffer((AllayFeatherLayout.VISIBLE+4)*4);
            visible.putInt(8,3).putInt(48,4);
            int dispatch=AllayFeatherLayout.LEAF_DISPATCH*4;
            visible.putInt(dispatch,1).putInt(dispatch+4,1).putInt(dispatch+8,1);
            for(int i=0;i<4;i++)visible.putInt((AllayFeatherLayout.VISIBLE+i)*4,i);
            visible.putInt(AllayFeatherLayout.GROUND*4,0).putInt((AllayFeatherLayout.GROUND+1)*4,1)
                    .putInt((AllayFeatherLayout.GROUND+2)*4,3);
            var support=org.lwjgl.BufferUtils.createByteBuffer((8192+4)*16);
            support.putInt(12,10);
            float[][] corners={{0,1,0,240},{1,1,0,225},{1,1,1,208},{0,1,1,176}};
            for(int j=0;j<4;j++)for(int k=0;k<4;k++)support.putFloat((8192+j)*16+k*4,corners[j][k]);
            GL45.glNamedBufferData(buffers[0],pool,GL15.GL_STATIC_DRAW);
            GL45.glNamedBufferData(buffers[1],visible,GL15.GL_STATIC_DRAW);
            GL45.glNamedBufferData(buffers[2],support,GL15.GL_STATIC_DRAW);
            GL45.glNamedBufferData(buffers[3],(FeatherLeafDisplayGpu.SPLIT_BASE+FeatherLeafDisplayGpu.SPLIT_STRIDE)*16L,GL15.GL_DYNAMIC_READ);
            GL45.glClearNamedBufferData(buffers[3],GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(java.nio.ByteBuffer)null);
            GL45.glNamedBufferSubData(buffers[3],0,new int[]{18,0,0,0});
            for(int i=0;i<4;i++)GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,i,buffers[i]);
            GL20.glUseProgram(program);
            matrices(program);
            GL13.glActiveTexture(GL13.GL_TEXTURE7);
            noise=texture(.37f,.61f,.48f,1);
            GL15.glBindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER,buffers[1]);
            var results=org.lwjgl.BufferUtils.createFloatBuffer(36);
            var header=org.lwjgl.BufferUtils.createIntBuffer(4);
            var ranks=org.lwjgl.BufferUtils.createFloatBuffer(16);
            var split=org.lwjgl.BufferUtils.createFloatBuffer(FeatherLeafDisplayGpu.SPLIT_STRIDE*4);
            var jitter=org.lwjgl.BufferUtils.createFloatBuffer(4);
            float displacement=0;
            float[] previous=null;
            float[] times={19.25f,19.25f+1f/30,19.25f+1f/60,19.25f+1f/144,19.25f+1f/240,19.375f,19.375f};
            for(int frame=1;frame<=times.length;frame++){
                float time=times[frame-1], rawCamera=frame==1?0:.125f;
                float irisCamera=frame==1?0:31.125f;
                float[] reference=nativeLeaf.sample(corners,leaf.material(),time,rawCamera,irisCamera);
                GL20.glUseProgram(program);
                leafUniforms(program,time,irisCamera);
                GL20.glUniform3f(GL20.glGetUniformLocation(program,"cmi_LeafCameraFraction"),rawCamera,0,0);
                GL30.glUniform1ui(GL20.glGetUniformLocation(program,"cmi_LeafStamp"),frame);
                GL45.glClearNamedBufferSubData(buffers[3],GL30.GL_R32UI,4,4,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(java.nio.ByteBuffer)null);
                GL43.glDispatchComputeIndirect(dispatch);
                GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT);
                GL45.glGetNamedBufferSubData(buffers[3],0,header);
                GL45.glGetNamedBufferSubData(buffers[3],16,jitter);
                GL45.glGetNamedBufferSubData(buffers[3],32,ranks);
                GL45.glGetNamedBufferSubData(buffers[3],FeatherLeafDisplayGpu.FACE_BASE*16L,results);
                GL45.glGetNamedBufferSubData(buffers[3],FeatherLeafDisplayGpu.SPLIT_BASE*16L,split);
                check(header.get(0)==18&&header.get(1)==1,label+" one diagonal split");
                check(ranks.get(0)==FeatherLeafDisplayGpu.FACE_BASE&&ranks.get(1)==1&&ranks.get(2)==frame,
                        label+" crossing instance references the face");
                check(ranks.get(4)==ranks.get(12)&&ranks.get(4)==ranks.get(0)&&ranks.get(8)==0,
                        label+" shared face and unaffected flight");
                check(ranks.get(5)==0&&ranks.get(13)==0,label+" no expansion inside a native triangle");
                check(split.get(0)==0,label+" split rank");
                for(int triangle=0;triangle<2;triangle++){
                    int points=(int)split.get(1+triangle);
                    check(points>=3&&points<=5,label+" clipped polygon vertex count");
                    for(int k=0;k<points*2;k++){
                        float coordinate=split.get(4+triangle*12+k);
                        check(coordinate>=0&&coordinate<=1,label+" split UV stays in the sprite");
                    }
                }
                float[] current=new float[12];
                for(int j=0;j<4;j++)for(int k=0;k<3;k++){
                    float value=results.get(20+j*4+k);
                    check(Float.isFinite(value),label+" finite terrain corner");
                    check(Math.abs(value-reference[j*7+k])<.0003f,
                            label+" native terrain corner "+j+"/"+k+": "+value+" vs "+reference[j*7+k]);
                    if(leaf.terrainJitter()&&k<2){
                        float clip=value-(k==0?rawCamera:0)+jitter.get(2+k)*reference[j*7+6];
                        check(Math.abs(clip-reference[j*7+3+k])<.00005f,label+" native terrain clip-space jitter");
                    }
                    current[j*3+k]=value;
                    if(frame==1)displacement+=Math.abs(value-corners[j][k]);
                }
                if(frame==times.length)check(Arrays.equals(previous,current),label+" identical terrain clock reproduces the surface");
                previous=current;
            }
            check(displacement>1e-5f,label+" native leaf branch moves the supporting face");
            check(GL11.glGetError()==0,label+" leaf display GL error");
            return displacement;
        } finally {
            GL20.glUseProgram(0);
            if(noise!=0)GL11.glDeleteTextures(noise);
            GL15.glDeleteBuffers(buffers);
            GpuPrograms.delete(program);
        }
    }
    private static String particleSource(String name) {
        return com.iridium126.createmanaindustry.client.particles.engine.ParticleShaderSource.loadParticle(name,path->{
            try { return Files.readString(Path.of("src/main/resources/assets/createmanaindustry",path)); }
            catch(java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
        });
    }

    static void run()throws Exception{
        OffscreenIrisPlatform.initialize();
        com.mojang.blaze3d.systems.RenderSystem.initRenderThread();DEFINES.add(new StringPair("MC_NORMAL_MAP","1"));DEFINES.add(new StringPair("MC_SPECULAR_MAP","1"));for(var phase:net.irisshaders.iris.pipeline.WorldRenderingPhase.values())DEFINES.add(new StringPair("MC_RENDER_STAGE_"+phase.name(),Integer.toString(phase.ordinal())));
        atlasBindings();
        var compiler=new ShaderPackProgramCompiler(ModelMaterialGpuValidation::particleSource);
        var method=ShaderPackProgramCompiler.class.getDeclaredMethod("buildMergedSource",boolean.class);method.setAccessible(true);var uniform=Pattern.compile("\\buniform\\s+vec4\\s+entityColor\\s*;");int packs=0;
        try(var samples=Files.list(Path.of("run/shaderpacks"))){for(Path sample:samples.filter(p->p.toString().endsWith(".zip")).sorted().toList()){
            var inventory=ShaderPackInventory.readPack(sample);Path root=Path.of("build/model-material-validation",inventory.digest().substring(0,12));Files.createDirectories(root);for(var e:inventory.sources().entrySet()){Path f=root.resolve(e.getKey());Files.createDirectories(f.getParent());Files.writeString(f,e.getValue());}
            String base=null;for(String candidate:List.of("gbuffers_entities","gbuffers_textured_lit","gbuffers_textured","gbuffers_basic")){String path=Files.exists(root.resolve("world0/"+candidate+".vsh"))?"world0/"+candidate:candidate;if(Files.exists(root.resolve(path+".vsh"))&&Files.exists(root.resolve(path+".fsh"))){base=path;break;}}check(base!=null,"entity fallback "+sample);
            String v=preprocess(root,base+".vsh"),f=preprocess(root,base+".fsh"),g=Files.exists(root.resolve(base+".gsh"))?preprocess(root,base+".gsh"):null;
            var receiver=ShadowReceiverContract.resolve(inventory);
            check(!receiver.pack().equals("unknown/modified"),"fixed receiver inventory "+sample);
            if(receiver.forward()){
                var adapted=receiver.adapt(new ModelShaderOptimization.Stages(v,g,null,null,f));
                int low=compile(adapted.vertex(),adapted.geometry(),adapted.fragment(),"unshadowed-native",root);GL20.glDeleteProgram(low);
                receiverOutputs(f,receiver,root);
                int optionCase=0;
                try{for(var options:List.of(Map.of("SHADOW","false"),Map.of("SHADOW_FILTER","false"),Map.of("SHADOW_COLOR","false"),Map.of("SHADOW_CLOUD","true"),Map.of("ADVANCED_MATERIALS","true"),Map.of("ADVANCED_MATERIALS","true","SHADOW_CLOUD","true"))){
                    OPTION_OVERRIDES=options;String ov=preprocess(root,base+".vsh"),of=preprocess(root,base+".fsh");var variant=receiver.adapt(new ModelShaderOptimization.Stages(ov,null,null,null,of));
                    int optionProgram=compile(variant.vertex(),null,variant.fragment(),"unshadowed-options-"+(optionCase++),root);GL20.glDeleteProgram(optionProgram);
                    receiverOutputs(of,receiver,root);
                }}finally{OPTION_OVERRIDES=Map.of();}
                Files.writeString(root.resolve("receiver-status.txt"),receiver.pack()+": native fragment scene/cloud perturbation and directional response passed for default + six option cases; production segment/deferred/postprocessing/cost matrix still required\n");
            }else Files.writeString(root.resolve("receiver-status.txt"),receiver.pack()+": INCOMPLETE: "+receiver.reason()+"\n");
            int nativeProgram=compile(v,g,f,"native",root);GL20.glDeleteProgram(nativeProgram);
            String terrainPath=java.util.stream.Stream.of("gbuffers_terrain_cutout","gbuffers_terrain","gbuffers_textured_lit","gbuffers_textured")
                    .flatMap(name->java.util.stream.Stream.of("world0/"+name+".vsh",name+".vsh"))
                    .filter(path->Files.exists(root.resolve(path))).findFirst().orElseThrow();
            String terrain=preprocess(root,terrainPath);
            var leaf=FeatherLeafSway.resolve(inventory,terrain);
            check(leaf!=null,"effective leaf deformation "+sample);
            String leafSwitch=switch(leaf.pack()){
                case "Bliss"->"WAVY_PLANTS";
                case "BSL"->"WAVING_LEAF";
                case "iterationT"->"WAVING_PLANTS";
                case "Sildur’s"->"Waving_Leaves";
                case "Sundial Lite"->"PLANT_WAVE";
                default->"WAVING_LEAVES";
            };
            try{
                OPTION_OVERRIDES=Map.of(leafSwitch,"false");
                check(FeatherLeafSway.resolve(inventory,preprocess(root,terrainPath))==null,"disabled leaf option "+sample);
            }finally{OPTION_OVERRIDES=Map.of();}
            compiler.reset();
            var leafField=ShaderPackProgramCompiler.class.getDeclaredField("leafSway");leafField.setAccessible(true);leafField.set(compiler,leaf);
            boolean exact=g==null&&!uniform.matcher(v).replaceAll("").contains("entityColor");String own=(String)method.invoke(compiler,exact);String merged=new ParticleVertexInjector().patch(exact?uniform.matcher(v).replaceAll(""):v,own,"model-material");check(merged.contains("cmi_ShadowPass"),"CMI injection succeeded "+sample);merged=JcppProcessor.glslPreprocessSource(merged,DEFINES);
            check(merged.contains("cmiFeatherAttach"),"leaf attachment injected "+sample);
            int model=compile(merged,g,exact?uniform.matcher(f).replaceAll("in vec4 entityColor;"):f,"model",root);GL20.glDeleteProgram(model);
            if(g==null){
                String featherFragment=exact?uniform.matcher(f).replaceAll("in vec4 entityColor;"):f;
                mergedFeatherVertices(merged,featherFragment,sample.getFileName().toString());
            }
            float displacement=leafDisplay(leaf,terrain,sample.getFileName().toString());
                var stronger=switch(leaf.pack()){
                    case "Bliss"->Map.of("WAVY_STRENGTH","2.0");
                    case "Complementary"->Map.of("WAVING_I","2.00");
                    case "Sundial Lite"->Map.of("PLANT_WAVE_STRENGTH","2.0");
                    default->Map.<String,String>of();
                };
                if(!stronger.isEmpty())try{
                    OPTION_OVERRIDES=stronger;
                    String variantTerrain=preprocess(root,terrainPath);
                    var variant=FeatherLeafSway.resolve(inventory,variantTerrain);
                    check(variant!=null&&!variant.source().equals(leaf.source()),"active leaf strength option "+sample);
                    float strongerDisplacement=leafDisplay(variant,variantTerrain,sample+" stronger");
                    check(strongerDisplacement>displacement+1e-5f,"leaf strength changes grounded vertex positions "+sample);
                }finally{OPTION_OVERRIDES=Map.of();}
            if(sample.getFileName().toString().startsWith("iterationT"))materials(f,true,0,root,false);
            if(sample.getFileName().toString().startsWith("Complementary")){
                var graph=new IncludeGraph(root,ImmutableList.of(AbsolutePackPath.fromAbsolutePath("/"+base+".vsh"),AbsolutePackPath.fromAbsolutePath("/"+base+".fsh")),false);
                var options=new net.irisshaders.iris.shaderpack.option.ShaderPackOptions(graph,Map.of());
                var constructor=net.irisshaders.iris.shaderpack.IdMap.class.getDeclaredConstructor(Path.class,net.irisshaders.iris.shaderpack.option.ShaderPackOptions.class,Iterable.class);constructor.setAccessible(true);
                var ids=(net.irisshaders.iris.shaderpack.IdMap)constructor.newInstance(root,options,DEFINES);int allay=ModelEntityContext.resolve(ids).allayId();check(allay!=0,"effective parsed native allay ID");materials(f,false,allay,root,true);
                Path common=root.resolve("lib/common.glsl");String original=Files.readString(common);check(original.contains("#define RP_MODE 1"),"RP option anchor");Files.writeString(common,original.replace("#define RP_MODE 1","#define RP_MODE 2"));
                try{materials(preprocess(root,base+".fsh"),false,allay,root,false);}finally{Files.writeString(common,original);}
            }
            packs++;System.out.println(sample.getFileName()+": native and MODEL entity stages linked");
        }}check(packs>=8,"all eight samples");System.out.println("MODEL material GPU: "+checks+" checks passed");
    }
    public static void main(String[] args)throws Exception{check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(32,32,"MODEL material validation",0,0);check(window!=0,"context");try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();run();}finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}}
}

