import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.opengl.*;
import org.lwjgl.glfw.*;
import com.iridium126.createmanaindustry.client.particles.engine.*;
import com.iridium126.createmanaindustry.client.packages.render.PackageDrawCulling;

/** Isolated kernels. Group measurements include a separate writer (upper bound on piggyback cost).
 * These measurements do not qualify a native shader-pack depth convention or a full draw. */
public class ShadowQualityGpuValidation extends ModelShadowGpuValidation {
    static final Path OUT=Path.of(System.getProperty("cmi.benchmark.output","build/shadow-quality/results"));
    static final List<String> samples=new ArrayList<>(List.of("kind,count,scenario,round,pair,a_ms,b_ms"));
    static final List<String> compilation=new ArrayList<>(List.of("name,milliseconds,active_ssbo_blocks"));
    static int compute(String name,String source){long start=System.nanoTime();int p=com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler.compute(name,source);compilation.add(name+","+(System.nanoTime()-start)/1e6+","+GL43.glGetProgramInterfacei(p,GL43.GL_SHADER_STORAGE_BLOCK,GL43.GL_ACTIVE_RESOURCES));return p;}
    static double timed(Runnable work){int q=GL15.glGenQueries();try{GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,q);work.run();GL15.glEndQuery(GL33.GL_TIME_ELAPSED);return GL33.glGetQueryObjectui64(q,GL15.GL_QUERY_RESULT)/1e6;}finally{GL15.glDeleteQueries(q);}}
    static String reference(String name){return ParticleShaderSource.loadParticle(name,p->{try{return Files.readString(Path.of(".local/particle-baselines/reference/shadow-quality-20261102/src/main/resources/assets/createmanaindustry",p));}catch(Exception e){throw new IllegalStateException(e);}});}
    static void pair(String kind,int n,String scene,int round,Runnable a,Runnable b){
        long deadline=System.nanoTime()+1_000_000_000L;int warm=0;
        while(warm++<64||System.nanoTime()<deadline){a.run();b.run();if((warm&15)==0)GL11.glFinish();}GL11.glFinish();
        for(int i=0;i<64;i++){double av,bv;if((i&1)==0){av=timed(a);bv=timed(b);}else{bv=timed(b);av=timed(a);}samples.add(kind+","+n+","+scene+","+round+","+i+","+av+","+bv);}
    }
    static void grouping()throws Exception{
        int writer=compute("group writer","#version 450 core\nlayout(local_size_x=64)in;layout(std430,binding=0)readonly buffer P{vec4 p[];};uniform uint count;\n"+source("chunks/model_shadow_groups_write.glsl")+"\nvoid main(){uint i=gl_GlobalInvocationID.x;if(i<count)cmiShadowGroupRecord(i,i+100u,p[i*4u].xyz,3.4,true);}");
        try{for(int n:new int[]{65536,131072})for(String scene:new String[]{"visible","hidden","mixed"}){
            var particles=bytes(n*64);int outside=scene.equals("mixed")?n/10:0;
            for(int i=0;i<n;i++)particle(particles,i,i<outside?25:i%8-4,0,i/8%8-4,i+1,1);
            int pool=buffer(particles),emit=buffer(headers(2,.3f,false,true)),count=buffer(bytes(16).putInt(0,n));
            var metadata=bytes(64+n*8);metadata.putInt(8,n).putInt(12,1).putLong(16,7).putFloat(44,12).putFloat(48,-20).putFloat(52,20);int groups=buffer(metadata);
            try(var old=new ModelShadowGpu(n,ShadowQualityGpuValidation::reference);var current=new ModelShadowGpu(n,ShadowQualityGpuValidation::source)){
                var cull=new PackageDrawCulling();cull.clear();float[] waves=new float[16],targets=new float[16],tiers=new float[4];
                Runnable baseline=()->old.prepare(pool,emit,count,cull,0,0,0,.35f,.6f,1,0,waves,targets,tiers,0,7);
                Runnable unchanged=()->{current.culling(null,0,0,0,false,0);current.prepare(pool,emit,count,cull,0,0,0,.35f,.6f,1,0,waves,targets,tiers,0,7);};
                Runnable candidate=()->{boolean group=!scene.equals("visible");
                    if(group){metadata.position(0).limit(64);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,groups);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,metadata);metadata.clear();GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,30,groups);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,pool);GL20.glUseProgram(writer);GL41.glProgramUniform1i(writer,GL20.glGetUniformLocation(writer,"uShadowGroupsWrite"),1);GL41.glProgramUniform1ui(writer,GL20.glGetUniformLocation(writer,"count"),n);GL43.glDispatchCompute((n+63)/64,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);}
                    current.culling(receiverBox(scene.equals("visible")?-20:20,30),0,0,1,false,group?groups:0);current.prepare(pool,emit,count,cull,0,0,0,.35f,.6f,1,0,waves,targets,tiers,0,7);
                };
                baseline.run();check(models(old)==n,"baseline work incomplete");unchanged.run();check(models(current)==n,"FULL changed work");candidate.run();check(models(current)==(scene.equals("visible")?n:outside),"coarse list differs");
                if(outside>0){var result=read(current.instances(),outside*8);var seen=new BitSet();for(int i=0;i<outside;i++){int slot=result.getInt(i*8+4)>>>2;check(slot<outside&&!seen.get(slot),"outside duplicate");check(result.getInt(i*8)==((slot+100)<<9),"animation token changed");seen.set(slot);}}
                for(int round=0;round<3;round++){if(Integer.getInteger("cmi.shadow.round",round)!=round)continue;pair("AA",n,scene,round,baseline,baseline);pair("FULL_AB",n,scene,round,baseline,unchanged);pair("GROUP_AB",n,scene,round,baseline,candidate);}
            }finally{for(int id:new int[]{pool,emit,count,groups})GL15.glDeleteBuffers(id);}
        }}finally{GL20.glDeleteProgram(writer);}
    }
    static final class Pyramid implements AutoCloseable{
        final int texture=GL11.glGenTextures(),sampler=GL33.glGenSamplers(),program,width,height,levels;
        Pyramid(int w,int h,int p){width=w;height=h;program=p;levels=32-Integer.numberOfLeadingZeros(Math.max(w,h));GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);GL42.glTexStorage2D(GL11.GL_TEXTURE_2D,levels,GL30.GL_R32F,w,h);GL33.glSamplerParameteri(sampler,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL33.glSamplerParameteri(sampler,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);GL33.glSamplerParameteri(sampler,GL14.GL_TEXTURE_COMPARE_MODE,GL11.GL_NONE);}
        void build(int source){GL20.glUseProgram(program);GL13.glActiveTexture(GL13.GL_TEXTURE4);GL33.glBindSampler(4,sampler);for(int level=0;level<levels;level++){GL11.glBindTexture(GL11.GL_TEXTURE_2D,level==0?source:texture);GL41.glProgramUniform1i(program,GL20.glGetUniformLocation(program,"sourceLevel"),Math.max(0,level-1));GL42.glBindImageTexture(0,texture,level,false,0,GL15.GL_WRITE_ONLY,GL30.GL_R32F);GL43.glDispatchCompute((Math.max(1,width>>level)+7)/8,(Math.max(1,height>>level)+7)/8,1);GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT|GL42.GL_TEXTURE_FETCH_BARRIER_BIT);}}
        float top(){float[] v=new float[1];GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);GL11.glGetTexImage(GL11.GL_TEXTURE_2D,levels-1,GL11.GL_RED,GL11.GL_FLOAT,v);return v[0];}
        public void close(){GL11.glDeleteTextures(texture);GL33.glDeleteSamplers(sampler);}
    }
    static void hierarchy()throws Exception{
        int reduction=compute("Hi-Z reduction",Files.readString(Path.of("scripts/particles/experiments/shadow-quality/hiz.comp"))),cull=compute("Hi-Z bounds",Files.readString(Path.of("scripts/particles/experiments/shadow-quality/hiz_cull.comp")));
        try{for(int[] size:new int[][]{{63,65},{1024,1024},{2048,2048}}){int w=size[0],h=size[1],depth=GL11.glGenTextures();GL13.glActiveTexture(GL13.GL_TEXTURE4);GL11.glBindTexture(GL11.GL_TEXTURE_2D,depth);float[] data=new float[w*h];Arrays.fill(data,.2f);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL30.GL_R32F,w,h,0,GL11.GL_RED,GL11.GL_FLOAT,data);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
            try(var p=new Pyramid(w,h,reduction)){
                p.build(depth);float top=p.top();check(Math.abs(top-.2f)<1e-6,"max reduction "+w+"x"+h+" top="+top+" GL="+GL11.glGetError());data[data.length-1]=1;GL11.glBindTexture(GL11.GL_TEXTURE_2D,depth);GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,0,w,h,GL11.GL_RED,GL11.GL_FLOAT,data);p.build(depth);check(p.top()==1,"NPOT sky edge disappeared");Arrays.fill(data,.2f);GL11.glBindTexture(GL11.GL_TEXTURE_2D,depth);GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,0,w,h,GL11.GL_RED,GL11.GL_FLOAT,data);p.build(depth);
                for(int n:new int[]{65536,131072}){var boxes=bytes(n*32);for(int i=0;i<n;i++){int o=i*32;boxes.putFloat(o,-.01f).putFloat(o+4,-.01f).putFloat(o+8,.2f).putFloat(o+16,.01f).putFloat(o+20,.01f).putFloat(o+24,.3f);}int bounds=buffer(boxes),counter=buffer(bytes(4));float[] matrix=new org.joml.Matrix4f().get(new float[16]);
                    Runnable query=()->{put(counter,bytes(4));GL20.glUseProgram(cull);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,bounds);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,1,counter);GL13.glActiveTexture(GL13.GL_TEXTURE4);GL11.glBindTexture(GL11.GL_TEXTURE_2D,p.texture);GL33.glBindSampler(4,p.sampler);GL41.glProgramUniformMatrix4fv(cull,GL20.glGetUniformLocation(cull,"projection"),false,matrix);GL41.glProgramUniform1ui(cull,GL20.glGetUniformLocation(cull,"count"),n);GL41.glProgramUniform1f(cull,GL20.glGetUniformLocation(cull,"margin"),1e-5f);GL43.glDispatchCompute((n+63)/64,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);};
                    try{query.run();check(read(counter,4).getInt(0)==0,"hidden boxes survived");for(int round=0;round<3;round++){if(Integer.getInteger("cmi.shadow.round",round)!=round)continue;pair("HIZ_COST",n,w+"x"+h,round,query,()->{p.build(depth);query.run();});}}finally{GL15.glDeleteBuffers(bounds);GL15.glDeleteBuffers(counter);}
                }
            }finally{GL11.glDeleteTextures(depth);}
        }}finally{GL20.glDeleteProgram(reduction);GL20.glDeleteProgram(cull);}
    }
    static void stateBoundaries(){
        GL20.glUseProgram(0);com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
        int alignment=GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT),foreign=buffer(bytes(alignment+64));
        var outer=new com.iridium126.createmanaindustry.client.gpu.state.GpuComputeState();
        var old=new com.iridium126.createmanaindustry.client.packages.render.PackageRenderState();
        var narrow=new com.iridium126.createmanaindustry.client.packages.render.PackageRenderState(false);
        try{for(var draw:new com.iridium126.createmanaindustry.client.packages.render.PackageRenderState[]{old,narrow}){
            GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,0,foreign,alignment,64);GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,30,foreign,alignment,64);
            com.mojang.blaze3d.systems.RenderSystem.depthMask(true);com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
            GL30.glEnablei(GL11.GL_BLEND,1);GL40.glPatchParameteri(GL40.GL_PATCH_VERTICES,3);
            outer.capture();draw.capture();GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,0);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,30,0);
            com.mojang.blaze3d.systems.RenderSystem.depthMask(false);com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();GL30.glDisablei(GL11.GL_BLEND,1);GL40.glPatchParameteri(GL40.GL_PATCH_VERTICES,4);
            draw.restoreVanillaState();draw.restore();outer.restore();
            for(int binding:new int[]{0,30})check(GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,binding)==foreign&&GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START,binding)==alignment,"state range lost");
            check(GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)&&GL11.glIsEnabled(GL11.GL_DEPTH_TEST),"depth state lost");check(GL30.glIsEnabledi(GL11.GL_BLEND,1),"indexed blend lost");check(GL11.glGetInteger(GL40.GL_PATCH_VERTICES)==3,"patch state lost");
        }
        // Frozen hook also queried these states after PackageRenderState had captured them.
        Runnable a=()->{outer.capture();old.capture();
            GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
            GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
            GL11.glIsEnabled(GL11.GL_DEPTH_TEST);GL11.glIsEnabled(GL11.GL_BLEND);GL11.glIsEnabled(GL11.GL_CULL_FACE);GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            old.restoreVanillaState();old.restore();outer.restore();};
        Runnable b=()->{outer.capture();narrow.capture();narrow.restoreVanillaState();narrow.restore();outer.restore();};
        for(int i=0;i<64;i++){a.run();b.run();}
        for(int round=0;round<3;round++)for(int sample=0;sample<64;sample++){
            double av,bv;if((sample&1)==0){long start=System.nanoTime();for(int i=0;i<16;i++)a.run();av=(System.nanoTime()-start)/16e6;start=System.nanoTime();for(int i=0;i<16;i++)b.run();bv=(System.nanoTime()-start)/16e6;}
            else{long start=System.nanoTime();for(int i=0;i<16;i++)b.run();bv=(System.nanoTime()-start)/16e6;start=System.nanoTime();for(int i=0;i<16;i++)a.run();av=(System.nanoTime()-start)/16e6;}
            samples.add("STATE_CPU,0,boundary,"+round+","+sample+","+av+","+bv);
        }
        }finally{GL15.glDeleteBuffers(foreign);}
    }
    public static void main(String[] args)throws Exception{
        GLFWErrorCallback.createPrint(System.err).set();check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(64,64,"Shadow quality probe",0,0);Files.createDirectories(OUT);
        try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();System.out.println(GL11.glGetString(GL11.GL_RENDERER));qualityGroups();grouping();hierarchy();stateBoundaries();check(GL11.glGetError()==0,"final GL error");System.out.println("Shadow quality: "+checks+" assertions passed; native/full-draw depth qualification not claimed");}
        finally{Files.write(OUT.resolve("samples.csv"),samples);Files.write(OUT.resolve("compile.csv"),compilation);GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
