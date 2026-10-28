package com.iridium126.createmanaindustry.client.render.shaderpack;

import com.google.common.collect.*;
import com.iridium126.createmanaindustry.client.render.shaderpack.*;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.shaderpack.include.*;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import net.irisshaders.iris.helpers.StringPair;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;

/** Actual Sundial composition plus source/target attachment and external sampler ownership. */
public final class RiftReplayGpuValidation {
    static int checks;
    static final int SIZE=32;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static int shader(int type,String text){int s=GL20.glCreateShader(type);GL20.glShaderSource(s,text);GL20.glCompileShader(s);check(GL20.glGetShaderi(s,GL20.GL_COMPILE_STATUS)!=0,GL20.glGetShaderInfoLog(s));return s;}
    static int link(String vertex,String fragment){int p=GL20.glCreateProgram(),v=shader(GL20.GL_VERTEX_SHADER,vertex),f=shader(GL20.GL_FRAGMENT_SHADER,fragment);GL20.glAttachShader(p,v);GL20.glAttachShader(p,f);GL20.glLinkProgram(p);check(GL20.glGetProgrami(p,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(p));GL20.glDeleteShader(v);GL20.glDeleteShader(f);return p;}
    static int texture(int w,int h,int format,float[] values){int t=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,t);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,format,w,h,0,format==GL30.GL_R32F?GL11.GL_RED:GL11.GL_RGBA,GL11.GL_FLOAT,values);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);return t;}
    static String preprocess(Path root,String file){var path=AbsolutePackPath.fromAbsolutePath("/"+file);var g=new IncludeGraph(root,ImmutableList.of(path),false);check(g.getFailures().isEmpty(),g.getFailures().toString());return JcppProcessor.glslPreprocessSource(String.join("\n",new IncludeProcessor(g).getIncludedFile(path)),List.of(new StringPair("MC_VERSION","12101"),new StringPair("IS_IRIS","1")));}
    static void write(Path root,Map<String,String> files)throws Exception{for(var e:files.entrySet()){var p=root.resolve(e.getKey());Files.createDirectories(p.getParent());Files.writeString(p,e.getValue());}}
    static void matrix(int p,String name,Matrix4f m){var b=BufferUtils.createFloatBuffer(16);m.get(b);GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(p,name),false,b);}
    static float[] read(){float[] values=new float[SIZE*SIZE*4];GL11.glReadPixels(0,0,SIZE,SIZE,GL11.GL_RGBA,GL11.GL_FLOAT,values);return values;}
    static void bind(int p,String name,int unit,int texture){GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);GL20.glUniform1i(GL20.glGetUniformLocation(p,name),unit);}
    static void sundial()throws Exception{
        ShaderPackInventory inventory=null;
        try(var packs=Files.list(Path.of("run/shaderpacks"))){for(var path:packs.filter(p->p.toString().endsWith(".zip")).toList()){var candidate=ShaderPackInventory.readPack(path);if(candidate.sources().containsKey("programs/composite/Composite4.frag")){inventory=candidate;break;}}}
        check(inventory!=null,"actual Sundial sample");
        var stock=inventory.sources();var sky=NativeRiftPatchPlan.prepare(inventory);check(sky.enabled(),sky.reason());
        var patched=new TreeMap<>(stock);patched.putAll(sky.edits());var density=StormCloudDensityPatchPlan.prepare(ShaderPackInventory.of(patched),sky.family());check(density.enabled(),density.reason());patched.putAll(density.edits());
        Path root=Path.of("build/rift-replay-validation");write(root.resolve("stock"),stock);write(root.resolve("patched"),patched);
        String vertex="#version 330 compatibility\nout vec2 texcoord;out vec3 skyColorUp;void main(){vec2 q=vec2((gl_VertexID<<1)&2,gl_VertexID&2);texcoord=q;skyColorUp=vec3(.2,.4,.6);gl_Position=vec4(q*2.-1.,0.,1.);}";
        int[] programs={link(vertex,preprocess(root.resolve("stock"),"composite4.fsh")),link(vertex,preprocess(root.resolve("patched"),"composite4.fsh"))};
        int vao=GL30.glGenVertexArrays(),fbo=GL30.glGenFramebuffers();GL30.glBindVertexArray(vao);GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);
        int output=texture(SIZE,SIZE,GL30.GL_RGBA32F,null);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,output,0);GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        float[] input=new float[SIZE*SIZE*4],depths=new float[SIZE*SIZE],hits=new float[SIZE*SIZE];
        Arrays.fill(depths,.990099f);Arrays.fill(hits,320);
        for(int i=0;i<SIZE*SIZE;i++){input[i*4]=.3f+i%SIZE*.01f;input[i*4+1]=.5f;input[i*4+2]=.7f;input[i*4+3]=1;}
        int color=texture(SIZE,SIZE,GL30.GL_RGBA32F,input),depth=texture(SIZE,SIZE,GL30.GL_R32F,depths),hit=texture(SIZE,SIZE,GL30.GL_R32F,hits),target=texture(1,1,GL30.GL_RGBA32F,new float[]{10,20,30,1}),noise=texture(64,64,GL30.GL_RGBA32F,new float[64*64*4]);
        GL11.glViewport(0,0,SIZE,SIZE);GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDisable(GL11.GL_BLEND);
        float[] reference=null;
        for(int p:programs){
            GL20.glUseProgram(p);matrix(p,"gbufferProjectionInverse",new Matrix4f().perspective((float)Math.PI/2,1,.1f,1000).invert());matrix(p,"gbufferModelViewInverse",new Matrix4f());
            matrix(p,"CmiSkyViewProjection",new Matrix4f().perspective((float)Math.PI/2,1,.1f,1000));
            GL20.glUniform3f(GL20.glGetUniformLocation(p,"cameraPosition"),0,64,0);
            for(String name:List.of("sunDirection","shadowDirection","sunColor"))GL20.glUniform3f(GL20.glGetUniformLocation(p,name),0,1,0);
            bind(p,"colortex3",0,color);bind(p,"depthtex0",1,depth);bind(p,"depthtex1",2,depth);bind(p,"CmiJuliaHit",3,hit);bind(p,"CmiTargetSky",4,target);bind(p,"noisetex",5,noise);
            GL20.glUniform4f(GL20.glGetUniformLocation(p,"CmiRift"),0,320,0,2);
            for(int mode:new int[]{0,1,2}){
                Arrays.fill(hits,mode==2?-1:320);bind(p,"CmiJuliaHit",3,hit);GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,0,SIZE,SIZE,GL11.GL_RED,GL11.GL_FLOAT,hits);
                GL20.glUniform1f(GL20.glGetUniformLocation(p,"CmiRiftEnabled"),mode==0?0:1);GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);var actual=read();
                if(reference==null)reference=actual;
                for(int i=0;i<actual.length;i++){check(Float.isFinite(actual[i]),"Sundial finite geometry");check(Math.abs(actual[i]-reference[i])<.00001,"actual Sundial preserves upper/lower foreground with rift mode="+mode);}
            }
        }
        check(reference[0]>.1&&reference[(SIZE*SIZE/2)*4]>.1,"fixture has visible geometry above and below eye");
        GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glBindTexture(GL11.GL_TEXTURE_2D,color);var unchanged=BufferUtils.createFloatBuffer(input.length);GL11.glGetTexImage(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA,GL11.GL_FLOAT,unchanged);for(int i=0;i<input.length;i++)check(input[i]==unchanged.get(i),"source scene texture remains unchanged");
        for(int p:programs)GL20.glDeleteProgram(p);for(int t:new int[]{output,color,depth,hit,target,noise})GL11.glDeleteTextures(t);GL30.glDeleteFramebuffers(fbo);GL30.glDeleteVertexArrays(vao);
    }
    static SkyTextureState savedTextures()throws Exception{
        var constructor=SkyTextureState.class.getDeclaredConstructor(int.class);constructor.setAccessible(true);return constructor.newInstance(12);
    }
    static void textures()throws Exception{
        int t=texture(1,1,GL30.GL_RGBA32F,new float[]{.3f,.5f,.7f,1}),sampler=GL33.glGenSamplers(),lut=GL11.glGenTextures();
        com.mojang.blaze3d.systems.RenderSystem.activeTexture(GL13.GL_TEXTURE2);com.mojang.blaze3d.systems.RenderSystem.bindTexture(t);
        com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(2,t);IrisRenderSystem.bindSamplerToUnit(2,sampler);
        GL13.glActiveTexture(GL13.GL_TEXTURE5);GL11.glBindTexture(GL12.GL_TEXTURE_3D,lut);
        try(var state=savedTextures()){
            IrisRenderSystem.unbindAllSamplers();
            for(int unit=0;unit<12;unit++){GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);GL11.glBindTexture(GL11.GL_TEXTURE_2D,0);GL11.glBindTexture(GL12.GL_TEXTURE_3D,0);}
            com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(2,0);
        }
        check(GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)==GL13.GL_TEXTURE5,"replay active texture restored");check(GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D)==lut,"3D LUT binding restored");
        GL13.glActiveTexture(GL13.GL_TEXTURE2);check(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)==t,"external lightmap restored");check(GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING,2)==sampler,"external sampler restored");check(com.mojang.blaze3d.systems.RenderSystem.getShaderTexture(2)==t,"engine texture table restored");
        IrisRenderSystem.unbindAllSamplers();GL33.glDeleteSamplers(sampler);GL11.glDeleteTextures(t);GL11.glDeleteTextures(lut);
        long start=System.nanoTime();for(int i=0;i<120;i++)try(var state=savedTextures()){}System.out.println("Native replay texture guard CPU mean="+((System.nanoTime()-start)/120_000.0)+" us / 12 units (idle hidden GL context; not client frame timing)");
    }
    static void frames()throws Exception{
        var inputs=new net.irisshaders.iris.uniforms.custom.CustomUniformFixedInputUniformsHolder.Builder();RiftIrisUniforms.bind(inputs);
        check(!inputs.build().containsKey("CmiSkyViewProjection"),"custom expression graph cannot overwrite CMI matrices");
        int program=link("#version 330 core\nvoid main(){gl_Position=vec4(0);}","#version 330 core\nuniform mat4 CmiSkyViewProjection;out vec4 Color;void main(){Color=CmiSkyViewProjection[0];}");
        var builder=net.irisshaders.iris.gl.program.ProgramUniforms.builder("rift-frame-test",program);RiftIrisUniforms.bind(builder);var uniforms=builder.buildUniforms();
        var dynamic=uniforms.getClass().getDeclaredField("dynamic");dynamic.setAccessible(true);
        @SuppressWarnings("unchecked") var updates=(java.util.List<net.irisshaders.iris.gl.uniform.Uniform>)dynamic.get(uniforms);
        check(updates.size()==1,"matrix uses actual program dynamic channel");
        GL20.glUseProgram(program);var matrix=com.iridium126.createmanaindustry.client.allaystorm.rift.JuliaProjection.viewProjection();
        var original=new Matrix4f(matrix);
        try{
            for(int frame=0;frame<120;frame++){
                matrix.identity().rotateY(frame*.01f);for(var update:updates)update.update();
                var uploaded=BufferUtils.createFloatBuffer(16);GL20.glGetUniformfv(program,GL20.glGetUniformLocation(program,"CmiSkyViewProjection"),uploaded);
                check(Math.abs(uploaded.get(0)-matrix.m00())<.000001,"matrix updated every render use without advancing tick or Iris frame cache");
            }
        }finally{matrix.set(original);GL20.glDeleteProgram(program);}
    }
    static void attachments(){
        net.irisshaders.iris.gl.GLDebug.reloadDebugState();
        com.mojang.blaze3d.systems.RenderSystem.activeTexture(GL13.GL_TEXTURE0);GL13.glActiveTexture(GL13.GL_TEXTURE0);
        var directives=new net.irisshaders.iris.shaderpack.properties.PackDirectives(java.util.Set.of(0,1,2,3,4,5,6,7),net.irisshaders.iris.shaderpack.properties.ShaderProperties.empty());
        var depth=new net.irisshaders.iris.targets.DepthTexture("replay_source_depth",SIZE,SIZE,net.irisshaders.iris.gl.texture.DepthBufferFormat.DEPTH24);
        var targetDepth=new net.irisshaders.iris.targets.DepthTexture("replay_target_depth",SIZE,SIZE,net.irisshaders.iris.gl.texture.DepthBufferFormat.DEPTH24);
        var source=new net.irisshaders.iris.targets.RenderTargets(SIZE,SIZE,depth.getTextureId(),0,net.irisshaders.iris.gl.texture.DepthBufferFormat.DEPTH24,directives.getRenderTargetDirectives().getRenderTargetSettings(),directives);
        var sourceFramebuffer=source.createFramebufferWritingToMain(new int[]{0});sourceFramebuffer.bind();GL30.glClearBufferfv(GL11.GL_COLOR,0,new float[]{.25f,.5f,.75f,1});float[] before=read();
        var target=new NativeSkyTargets(SIZE,SIZE,targetDepth.getTextureId(),directives,source,ImmutableSet.of(),java.util.Set.of(3));
        try{
            check(target.getOrCreate(0).getMainTexture()==source.getOrCreate(0).getMainTexture(),"readonly input retains source texture");
            check(target.getOrCreate(3).getMainTexture()!=source.getOrCreate(3).getMainTexture(),"target sky output owns a distinct texture");
            boolean refused=false;try{target.createFramebufferWritingToMain(new int[]{0});}catch(IllegalStateException expected){refused=true;}check(refused,"source alias cannot become a target write attachment");
            var own=target.createFramebufferWritingToMain(new int[]{3});own.bind();GL30.glClearBufferfv(GL11.GL_COLOR,0,new float[]{0,0,0,0});
            sourceFramebuffer.bind();check(Arrays.equals(before,read()),"source scene attachment is bitwise unchanged after target clear");
        }finally{target.destroy();source.destroy();depth.destroy();targetDepth.destroy();}
    }
    public static void main(String[] args)throws Exception{
        check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE,GLFW.GLFW_OPENGL_COMPAT_PROFILE);
        long w=GLFW.glfwCreateWindow(SIZE,SIZE,"Rift replay GPU validation",0,0);check(w!=0,"GL context");
        try{GLFW.glfwMakeContextCurrent(w);GL.createCapabilities();com.mojang.blaze3d.systems.RenderSystem.initRenderThread();IrisRenderSystem.initRenderer();sundial();textures();frames();attachments();check(GL11.glGetError()==0,"no GL errors");System.out.println("Rift replay GPU: "+checks+" assertions passed");}finally{GLFW.glfwDestroyWindow(w);GLFW.glfwTerminate();}
    }
}
