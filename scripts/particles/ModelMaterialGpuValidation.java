import com.iridium126.createmanaindustry.client.particles.shaderpack.*;
import com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackInventory;
import com.google.common.collect.ImmutableList;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.include.*;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import net.irisshaders.iris.pipeline.transform.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.lwjgl.opengl.*;
import org.lwjgl.glfw.GLFW;

/** Actual eight-pack entity fragments and CMI/Iris vertex merge; hidden driver context only. */
public final class ModelMaterialGpuValidation {
    static int checks;
    static int layers=1;
    static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    static final List<StringPair> DEFINES=new ArrayList<>(List.of(new StringPair("MC_VERSION","12101"),new StringPair("IS_IRIS","1"),new StringPair("MC_GL_VERSION","450"),new StringPair("MC_GLSL_VERSION","450"),new StringPair("MC_RENDER_QUALITY","1.0"),new StringPair("MC_SHADOW_QUALITY","1.0"),new StringPair("MC_HAND_DEPTH","0.125")));
    static String preprocess(Path root,String file){
        var path=AbsolutePackPath.fromAbsolutePath("/"+file);var graph=new IncludeGraph(root,ImmutableList.of(path),false);
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
    static void run()throws Exception{
        com.mojang.blaze3d.systems.RenderSystem.initRenderThread();DEFINES.add(new StringPair("MC_NORMAL_MAP","1"));DEFINES.add(new StringPair("MC_SPECULAR_MAP","1"));for(var phase:net.irisshaders.iris.pipeline.WorldRenderingPhase.values())DEFINES.add(new StringPair("MC_RENDER_STAGE_"+phase.name(),Integer.toString(phase.ordinal())));
        var compiler=new ShaderPackProgramCompiler(name->com.iridium126.createmanaindustry.client.particles.engine.ParticleShaderSource.loadParticle(name,path->{try{return Files.readString(Path.of("src/main/resources/assets/createmanaindustry",path));}catch(Exception e){throw new RuntimeException(e);}}));
        var method=ShaderPackProgramCompiler.class.getDeclaredMethod("buildMergedSource",boolean.class);method.setAccessible(true);var uniform=Pattern.compile("\\buniform\\s+vec4\\s+entityColor\\s*;");int packs=0;
        try(var samples=Files.list(Path.of("run/shaderpacks"))){for(Path sample:samples.filter(p->p.toString().endsWith(".zip")).sorted().toList()){
            var inventory=ShaderPackInventory.readPack(sample);Path root=Path.of("build/model-material-validation",inventory.digest().substring(0,12));Files.createDirectories(root);for(var e:inventory.sources().entrySet()){Path f=root.resolve(e.getKey());Files.createDirectories(f.getParent());Files.writeString(f,e.getValue());}
            String base=null;for(String candidate:List.of("gbuffers_entities","gbuffers_textured_lit","gbuffers_textured","gbuffers_basic")){String path=Files.exists(root.resolve("world0/"+candidate+".vsh"))?"world0/"+candidate:candidate;if(Files.exists(root.resolve(path+".vsh"))&&Files.exists(root.resolve(path+".fsh"))){base=path;break;}}check(base!=null,"entity fallback "+sample);
            String v=preprocess(root,base+".vsh"),f=preprocess(root,base+".fsh"),g=Files.exists(root.resolve(base+".gsh"))?preprocess(root,base+".gsh"):null;
            int nativeProgram=compile(v,g,f,"native",root);GL20.glDeleteProgram(nativeProgram);
            boolean exact=g==null&&!uniform.matcher(v).replaceAll("").contains("entityColor");String own=(String)method.invoke(compiler,exact);String merged=new ParticleVertexInjector().patch(exact?uniform.matcher(v).replaceAll(""):v,own,"model-material");check(merged.contains("cmi_ShadowPass"),"CMI injection succeeded "+sample);merged=JcppProcessor.glslPreprocessSource(merged,DEFINES);
            int model=compile(merged,g,exact?uniform.matcher(f).replaceAll("in vec4 entityColor;"):f,"model",root);GL20.glDeleteProgram(model);
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

