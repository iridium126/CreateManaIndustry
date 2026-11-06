import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import com.google.gson.GsonBuilder;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler;
import com.iridium126.createmanaindustry.client.particles.engine.spatial.SpatialNative;

/** Fixed-input query control. This is not a Minecraft motion/render replay. */
public final class RtTopologyReplayGpu {
    private static final String PACKER="""
        #version 450 core
        layout(local_size_x=256)in;
        struct Row{vec4 position,velocity;uvec4 identity;vec4 preferred;};
        layout(std430,binding=0)buffer Packet{uint header[32];Row rows[];}p;
        uniform uint capacity,terrain,terrainCount,step,scenario,faultPeriod;
        void main(){
         uint i=gl_GlobalInvocationID.x;
         if(i==0u){for(uint k=0u;k<32u;k++)p.header[k]=0u;
          p.header[0]=0x41313149u;p.header[1]=12u;p.header[2]=step+1u;p.header[4]=91u;p.header[6]=step;p.header[8]=step+1u;
          p.header[10]=capacity;p.header[12]=floatBitsToUint(float(step)*.05);p.header[13]=floatBitsToUint(.05);p.header[14]=floatBitsToUint(.1);
          p.header[16]=91u+scenario;p.header[18]=1u;p.header[19]=2u;p.header[28]=terrainCount;p.header[29]=32u+capacity*16u;
         }
         if(i>=capacity+terrain)return;
         if(i>=capacity){uint j=i-capacity;
          if(j>=terrainCount){p.rows[i]=Row(vec4(0),vec4(0),uvec4(0),vec4(0));return;}
          p.rows[i]=Row(vec4(4.+float(j%64u)*.8,float(j/1024u)*3.,float((j/64u)%16u)*3.,0),vec4(.15,1.,1.,0),uvec4(0,0,0,3),vec4(0));return;
         }
         float t=float(step)*.05,angle=scenario==2u?t*.3:0.,scale=scenario==1u?.4:1.;
         vec3 point=vec3(float(i%64u),float(i/4096u),float((i/64u)%64u))*.8;
         point.xz=mat2(cos(angle),sin(angle),-sin(angle),cos(angle))*point.xz*scale;
         point+=vec3(sin(t*.7+float(i)*.01)*.4,0,cos(t*.3+float(i)*.02)*.4);
         uint flags=(scenario==4u&&i%97u==0u?0u:1u)|((scenario==1u||scenario==2u)&&i<12288u?0x80000000u:0u);
         if(step%faultPeriod==13u&&i==0u){point.x=uintBitsToFloat(0x7fc00000u);flags=1u;}
         p.rows[i]=Row(vec4(point,.34),vec4(8,0,0,16),uvec4(i,100u+i+scenario*capacity,9,flags),vec4(8,0,0,0));
        }
        """;
    private static void ui(int p,String name,int value){GL41.glProgramUniform1ui(p,GL20.glGetUniformLocation(p,name),value);}
    public static void main(String[] args)throws Exception{
        var options=ParticleFixtureOptions.parse(args);
        int seconds=Integer.parseInt(options.getOrDefault("seconds","900"));
        int members=Integer.parseInt(options.getOrDefault("members","65536")),terrain=4096;
        int faultPeriod=Integer.parseInt(options.getOrDefault("fault-period","3000"));
        if(seconds<1||seconds>3600||members<1||members>131072||faultPeriod<14)throw new IllegalArgumentException("Bounded replay dimensions required");
        Path out=Path.of(options.getOrDefault("output","build/particle-engine/topology"));Files.createDirectories(out);
        if(!GLFW.glfwInit())throw new IllegalStateException("GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,6);
        long window=GLFW.glfwCreateWindow(32,32,"RT fixed input topology control",0,0);GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
        long[] bridges=new long[2],workers=new long[2];long submitted=0,consumed=0,retired=0;int program=0,steps=0;
        var report=new LinkedHashMap<String,Object>();report.put("passed",false);long start=System.nanoTime();
        report.put("scope","fixed-input spatial queries only; no game simulation, shading, pose caching or full-frame qualification");
        report.put("complete_plan_replay",false);report.put("execution","serial AB/BA; no overlapping strategy queries");report.put("gpu",GL11.glGetString(GL11.GL_RENDERER));report.put("driver",GL11.glGetString(GL11.GL_VERSION));
        try(var csv=Files.newBufferedWriter(out.resolve("topology.csv"))){
            Path modules=Path.of(".gradle/particle-rt/build");SpatialNative.load(modules);program=GpuShaderCompiler.compute("fixed topology workload",PACKER);
            int[][][] buffers=new int[2][3][];
            for(int b=0;b<2;b++){
                bridges[b]=SpatialNative.createBuffers(128L+(members+terrain)*64L,3);
                workers[b]=b==0?SpatialNative.create(bridges[b],members,terrain,2,91,modules.toAbsolutePath().toString()):SpatialNative.createReference(bridges[b],members,terrain,2,91,modules.toAbsolutePath().toString());
                for(int slot=0;slot<3;slot++)buffers[b][slot]=SpatialNative.buffers(bridges[b],slot);
            }
            csv.write("step,strategy,scenario,members,terrain,delay_ms,receipt,input_wait_ms,prepare_ms,ias_ms,probe_ms,seal_ms,owned_bytes\n");
            int perScenario=Math.max(1,seconds*20/6);
            while(System.nanoTime()-start<seconds*1_000_000_000L){
                int clockStep=(int)((System.nanoTime()-start)/50_000_000L),step=steps++,scenario=Math.min(5,clockStep/perScenario),slot=step%3;
                int geometry=scenario<3?0:scenario==3?Math.min(terrain,(clockStep%perScenario)*terrain/perScenario):scenario==4?terrain:Math.max(0,terrain-(clockStep%perScenario)*terrain/perScenario);
                int delay=step==100?10:step==200?50:step==300?500:0;
                int first=step&1;
                for(int order=0;order<2;order++){
                    int b=(first+order)&1;GL20.glUseProgram(program);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,buffers[b][slot][0]);
                    ui(program,"capacity",members);ui(program,"terrain",terrain);ui(program,"terrainCount",geometry);ui(program,"step",step);ui(program,"scenario",scenario);ui(program,"faultPeriod",faultPeriod);
                    GL43.glDispatchCompute((members+terrain+255)/256,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
                    if(delay!=0)SpatialNative.delay(workers[b],delay);
                    if(!SpatialNative.submitRange(workers[b],slot,step+1,step,91+scenario,0,members,geometry))throw new AssertionError("submission refused");submitted++;
                    long until=System.nanoTime()+30_000_000_000L;
                    while(SpatialNative.poll(workers[b],slot,step+1)==1){if(System.nanoTime()>until)throw new AssertionError("completion timeout");Thread.sleep(1);}
                    int receipt=SpatialNative.consume(workers[b],slot,step+1);consumed++;
                    try{
                    if((receipt!=0)!=(step%faultPeriod==13))throw new AssertionError("unexpected validation receipt "+receipt);
                    double[] times=SpatialNative.timing(workers[b],slot,step+1);
                    csv.write(step+","+(b==0?"rebuild":"reference_refit")+","+scenario+","+members+","+geometry+","+delay+","+receipt);
                    for(double ms:times)csv.write(","+ms);csv.write(","+SpatialNative.ownedBytes()+"\n");
                    }finally{
                    long fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);
                    while(!SpatialNative.recycle(workers[b],slot,step+1,fence)){if(System.nanoTime()>until)throw new AssertionError("consumer retirement timeout");Thread.sleep(1);}
                    GL32.glDeleteSync(fence);retired++;
                    }
                }
                if(step%600==0){csv.flush();System.out.println("topology step="+step+" scenario="+scenario+" submitted="+submitted+" consumed="+consumed+" retired="+retired);}
                long wait=start+steps*50_000_000L-System.nanoTime();if(wait>0)Thread.sleep(wait/1_000_000L,(int)(wait%1_000_000L));
            }
            if(submitted!=consumed||consumed!=retired)throw new AssertionError("lifecycle conservation");report.put("passed",true);
        }catch(Throwable failure){report.put("failure",failure.toString());throw failure;}finally{
            boolean cleanupFailed=false;
            for(int b=0;b<2;b++)if(workers[b]!=0)try{
                long until=System.nanoTime()+30_000_000_000L;
                while(!SpatialNative.close(workers[b])){if(System.nanoTime()>until)throw new IllegalStateException("unretired replay worker; bridge retained");Thread.sleep(1);}
                SpatialNative.closeBuffers(bridges[b]);
            }catch(Throwable failure){report.put("passed",false);report.put("cleanup_error_"+b,failure.toString());cleanupFailed=true;}
            report.put("elapsed_seconds",(System.nanoTime()-start)/1e9);report.put("steps",steps);report.put("submitted",submitted);report.put("consumed",consumed);report.put("retired",retired);report.put("owned_bytes_after_close",SpatialNative.ownedBytes());
            Files.writeString(out.resolve("topology.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
            if(program!=0)GL20.glDeleteProgram(program);GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();
            if(cleanupFailed&&!report.containsKey("failure"))throw new IllegalStateException("replay cleanup failed; see topology.json");
        }
    }
}
