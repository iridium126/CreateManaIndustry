import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.opengl.*;
import org.lwjgl.glfw.*;
import com.iridium126.createmanaindustry.client.particles.engine.*;

/** Independent CPU-direct candidates. GPU-copy results from the previous probe remain untouched. */
public class UpdateInputsGpuValidation extends UpdateStorageGpuValidation {
    static final String[] VARIANTS={"INPUTS","MEMBERS","ALLOCATOR","FULL"};
    static final class Inputs implements AutoCloseable {
        final ByteBuffer bake=bytes(320),players=bytes(16),damage=bytes(16);
        final int bakeBuffer=buffer(bake),playerBuffer=buffer(players);
        final UpdateFrameInputs packed=new UpdateFrameInputs(UpdateFrameInputs.Layout.of(bake.remaining(),players.remaining(),damage.remaining(),GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT)));
        final int[][] bindings=new int[4][16];final int[] pass=new int[5];
        Inputs(DomainFixture f,int[] programs){
            for(int i=0;i<5;i++)pass[i]=GL20.glGetUniformLocation(programs[i],"uModelPass");
            original(f);int[] original=new int[32];for(int i=0;i<32;i++)original[i]=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,i);
            for(int variant=0;variant<4;variant++){
                var layout=new UpdateStorageLayout((variant&1)!=0,(variant&2)!=0);int[] old=layout.sourceBindings();
                for(int i=0;i<16;i++)bindings[variant][i]=old[i]<0?0:original[old[i]];
                bindings[variant][5]=packed.buffer();bindings[variant][14]=image(2);bindings[variant][15]=image(3);
                var l=packed.layout();GL41.glProgramUniform3ui(programs[variant+1],GL20.glGetUniformLocation(programs[variant+1],"uFrameOffsets"),0,l.playerOffset()/16,l.damageOffset()/16);
                GL41.glProgramUniform3ui(programs[variant+1],GL20.glGetUniformLocation(programs[variant+1],"uFrameCounts"),l.bakeBytes()/16,l.playerBytes()/16,(l.damageBytes()-16)/4);
            }
        }
        int image(int unit){int t=GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_NAME,unit);return t==0?0:GL45.glGetTextureLevelParameteri(t,0,GL31.GL_TEXTURE_BUFFER_DATA_STORE_BINDING);}
        void original(DomainFixture f){f.bindings();bind(11,bakeBuffer);bind(18,playerBuffer);bind(16,f.damage);}
        void upload(DomainFixture f,int index){
            if(index==0){original(f);put(bakeBuffer,bake);put(playerBuffer,players);put(f.damage,damage);}
            else{f.bindings();packed.stage(bake,players,damage);packed.upload();for(int i=0;i<16;i++)bind(i,bindings[index-1][i]);}
        }
        public void close(){packed.close();GL15.glDeleteBuffers(bakeBuffer);GL15.glDeleteBuffers(playerBuffer);}
    }
    record Sample(double gpu,double cpu){}
    static void allocatorContract(){
        String declarations="layout(std430,binding=BIND_BAKEMETA)readonly buffer B{vec4 m[];}bakeMeta;\nlayout(std430,binding=BIND_PLAYERS)readonly buffer P{vec4 pl[];}players;\nlayout(std430,binding=BIND_DAMAGE)readonly buffer D{uvec4 header;float entries[];}damageQ;\n";
        String body=declarations+source("chunks/model_members_write.glsl")+"\nlayout(local_size_x=64)in;layout(std430,binding=0)buffer Output{uint slots[];};uniform uint phase;void main(){uint i=gl_GlobalInvocationID.x;if(phase==0u){uint slot=cmiModelAllocate();slots[i]=slot;cmiModelRetire(slot);}else cmiModelRestore(slots[i]);}";
        int[] shaders={compileText("allocator image contract",body),compileText("allocator SSBO contract",new UpdateStorageLayout(false,true).source(body))};
        int count=4096,words=16922;var initial=bytes(words*4);for(int i=0;i<count/32;i++)initial.putInt((8+i)*4,-1);for(int i=0;i<count/1024;i++)initial.putInt((8200+i)*4,-1);initial.putInt(8456*4,15).putInt(8464*4,1);
        int allocator=buffer(initial),output=buffer(bytes(count*4)),view=GL11.glGenTextures();GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,view);GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,GL30.GL_R32UI,allocator);
        try{for(int variant=0;variant<2;variant++){
            put(allocator,initial);bind(0,output);bind(15,allocator);GL42.glBindImageTexture(3,view,0,false,0,GL15.GL_READ_WRITE,GL30.GL_R32UI);bool(shaders[variant],"uModelMembersEnabled",true);ui(shaders[variant],"phase",0);dispatch(shaders[variant],count/64);GL42.glMemoryBarrier(UpdateResourceInterface.CROSS_VIEW_BARRIER);
            var result=read(output,count*4);var seen=new BitSet();for(int i=0;i<count;i++){int slot=result.getInt(i*4);check(slot>=0&&slot<count&&!seen.get(slot),"allocator reservation identity/uniqueness "+variant);seen.set(slot);}
            var retired=read(allocator,words*4);for(int i=0;i<count/32;i++){check(retired.getInt((8+i)*4)==0,"allocated bit remained free");check(retired.getInt((8465+i)*4)==-1,"retirement bit lost");}
            ui(shaders[variant],"phase",1);dispatch(shaders[variant],count/64);GL42.glMemoryBarrier(UpdateResourceInterface.CROSS_VIEW_BARRIER);var restored=read(allocator,words*4);for(int i=0;i<count/32;i++)check(restored.getInt((8+i)*4)==-1,"failed reservation restoration lost a free bit");check((restored.getInt(8464*4)&1)==1,"restored root missing");
        }}finally{for(int p:shaders)deleteProgram(p);GL11.glDeleteTextures(view);GL15.glDeleteBuffers(allocator);GL15.glDeleteBuffers(output);}
    }
    static Sample execute(DomainFixture f,Inputs inputs,int[] programs,int index){
        put(f.q.counter,bytes(16));int query=GL15.glGenQueries();GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,query);long start=System.nanoTime();
        inputs.upload(f,index);int p=programs[index],loc=inputs.pass[index];
        GL41.glProgramUniform1ui(p,loc,1);dispatch(p,(f.n+63)/64);AllayAnimationGpu.barrier();GL41.glProgramUniform1ui(p,loc,2);dispatch(p,(f.models+63)/64);AllayAnimationGpu.barrier();
        double cpu=(System.nanoTime()-start)/1e6;GL15.glEndQuery(GL33.GL_TIME_ELAPSED);double gpu=GL33.glGetQueryObjectui64(query,GL15.GL_QUERY_RESULT)/1e6;GL15.glDeleteQueries(query);return new Sample(gpu,cpu);
    }
    static void memberContract(){
        String declarations="layout(std430,binding=BIND_BAKEMETA)readonly buffer B{vec4 m[];}bakeMeta;\nlayout(std430,binding=BIND_PLAYERS)readonly buffer P{vec4 pl[];}players;\nlayout(std430,binding=BIND_DAMAGE)readonly buffer D{uvec4 header;float entries[];}damageQ;\n";
        String source=declarations+"\n#define CMI_MODEL_APPEND\nlayout(std430,binding=BIND_COUNTER)buffer Counter{uint a;uint b;uint translucentCensus;uint sortItems;}counter;\n"+source("chunks/model_members_write.glsl")+"\nlayout(local_size_x=64)in;void main(){uint i=gl_GlobalInvocationID.x;cmiModelAppend(i,i+17u,(i&1u)==0u);}";
        int[] programs={compileText("members image contract",source),compileText("members SSBO contract",new UpdateStorageLayout(true,false).source(source))};
        int count=128,storm=32,bytes=32+count*8+storm*4;var initial=bytes(bytes+64);for(int i=bytes;i<initial.capacity();i++)initial.put(i,(byte)0x5a);
        int members=buffer(initial),counter=buffer(bytes(16)),view=GL11.glGenTextures();GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,view);GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,GL30.GL_R32UI,members);
        try{for(int variant=0;variant<2;variant++){
            put(members,initial);put(counter,bytes(16));bind(3,counter);bind(2,counter);bind(14,members);GL42.glBindImageTexture(2,view,0,false,0,GL15.GL_READ_WRITE,GL30.GL_R32UI);ui(programs[variant],"uPopulationModelCapacity",count);ui(programs[variant],"uPopulationStormCapacity",storm);dispatch(programs[variant],count/64);GL42.glMemoryBarrier(UpdateResourceInterface.CROSS_VIEW_BARRIER);
            var counters=read(counter,16);check(counters.getInt(8)==count&&counters.getInt(12)==count/2,"candidate member counts");var data=read(members,bytes+64);var seen=new BitSet();
            for(int i=0;i<count;i++){int slot=data.getInt(32+i*8);check(slot>=0&&slot<count&&!seen.get(slot),"candidate member uniqueness");check(data.getInt(36+i*8)==slot+17,"stable animation slot changed");seen.set(slot);}
            seen.clear();for(int i=0;i<storm;i++){int slot=data.getInt(32+count*8+i*4);check(slot>=0&&slot<count&&(slot&1)==0&&!seen.get(slot),"storm population overflow guard");seen.set(slot);}
            for(int i=bytes;i<data.capacity();i++)check(data.get(i)==(byte)0x5a,"candidate member tail overwritten");
        }}finally{for(int p:programs)deleteProgram(p);GL11.glDeleteTextures(view);GL15.glDeleteBuffers(members);GL15.glDeleteBuffers(counter);}
    }
    public static void main(String[] args)throws Exception{
        GLFWErrorCallback.createPrint(System.err).set();check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(64,64,"CPU-direct update inputs",0,0);
        int round=Integer.getInteger("cmi.shadow.round",0);Path out=Path.of("build/shadow-completion/inputs-"+round);Files.createDirectories(out);
        var rows=new ArrayList<String>(List.of("count,mode,variant,comparison,pair,a_gpu_ms,b_gpu_ms,a_cpu_ms,b_cpu_ms"));int[] programs=new int[5];
        try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();Files.writeString(out.resolve("device.txt"),GL11.glGetString(GL11.GL_RENDERER)+"\n"+GL11.glGetString(GL11.GL_VERSION)+"\n");
            String original=source("update.comp");programs[0]=compileText("original CPU upload",original);Files.writeString(out.resolve("original-interface.txt"),UpdateResourceInterface.verify(programs[0],16).toString());
            for(int v=0;v<4;v++){var layout=new UpdateStorageLayout((v&1)!=0,(v&2)!=0);String text=layout.source(original);Files.writeString(out.resolve(VARIANTS[v]+".comp"),text);programs[v+1]=compileText(VARIANTS[v],text);Files.writeString(out.resolve(VARIANTS[v]+"-interface.txt"),UpdateResourceInterface.verify(programs[v+1],layout.activeBlocks()).toString());}
            allocatorContract();
            memberContract();
            for(int count:new int[]{1000,65536,131072})for(int mode=0;mode<5;mode++)try(var f=new DomainFixture(count,mode,programs);var inputs=new Inputs(f,programs)){
                execute(f,inputs,programs,0);var reference=f.snapshot();for(int v=1;v<5;v++){execute(f,inputs,programs,v);equal(reference,f.snapshot());}
                for(int v=1;v<5;v++){
                    int baseline=v==1||v==4?0:1;
                    for(int warm=0;warm<12;warm++){execute(f,inputs,programs,baseline);execute(f,inputs,programs,v);}
                    for(String comparison:List.of("AA","AB"))for(int pair=0;pair<64;pair++){
                        int candidate=comparison.equals("AA")?baseline:v;Sample a,b;
                        if((pair&1)==0){a=execute(f,inputs,programs,baseline);b=execute(f,inputs,programs,candidate);}else{b=execute(f,inputs,programs,candidate);a=execute(f,inputs,programs,baseline);}
                        rows.add(count+","+mode+","+VARIANTS[v-1]+","+comparison+","+pair+","+a.gpu+","+b.gpu+","+a.cpu+","+b.cpu);
                    }
                }System.out.println("CPU-direct input candidates exact count="+count+" mode="+mode);
            }
            check(GL11.glGetError()==0,"final GL error");System.out.println("CPU-direct candidates: "+checks+" checks; update domains measured, production layout unchanged");
        }finally{Files.write(out.resolve("samples.csv"),rows);for(int p:programs)if(p!=0)deleteProgram(p);GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
