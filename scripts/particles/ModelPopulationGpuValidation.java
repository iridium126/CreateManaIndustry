import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.particles.engine.*;
import com.iridium126.createmanaindustry.client.packages.render.PackageDrawCulling;

/** Production keygen/dispatch/shadow/pick/damage/emit kernels, with no game-world mutation. */
public class ModelPopulationGpuValidation {
    static long checks;static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    static ByteBuffer bytes(int n){return BufferUtils.createByteBuffer(n);}
    static String source(String name){String s=ParticleShaderSource.loadParticle(name,p->{try(var in=ModelPopulationGpuValidation.class.getClassLoader().getResourceAsStream("assets/createmanaindustry/"+p)){return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}catch(Exception e){throw new IllegalStateException(p,e);}});
        for(String phase:List.of("spawn","update"))s=s.replace("#pragma cmi_types "+phase,com.iridium126.createmanaindustry.client.particles.emitter.ParticleTypes.shaderHooks(phase));return s;}
    static int compile(String name){long began=System.nanoTime();System.out.println("Compiling "+name+" at "+java.time.Instant.now());int s=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);GL20.glShaderSource(s,"#version 450 core\n"+ParticlePrograms.commonPrelude()+source(name));GL20.glCompileShader(s);check(GL20.glGetShaderi(s,GL20.GL_COMPILE_STATUS)!=0,name+": "+GL20.glGetShaderInfoLog(s));int p=GL20.glCreateProgram();GL20.glAttachShader(p,s);GL20.glLinkProgram(p);check(GL20.glGetProgrami(p,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(p));GL20.glDeleteShader(s);System.out.println("Compiled "+name+" in "+((System.nanoTime()-began)/1e9)+" s");return p;}
    static int buffer(ByteBuffer b){int id=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,b,GL15.GL_DYNAMIC_COPY);return id;}
    static void bind(int slot,int id){GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,slot,id);}
    static void put(int id,ByteBuffer b){GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,b);}
    static ByteBuffer read(int id,int n){GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);var b=bytes(n);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,b);return b;}
    static void ui(int p,String n,int v){GL41.glProgramUniform1ui(p,GL20.glGetUniformLocation(p,n),v);}
    static void bool(int p,String n,boolean v){GL41.glProgramUniform1i(p,GL20.glGetUniformLocation(p,n),v?1:0);}
    static void f(int p,String n,float v){GL41.glProgramUniform1f(p,GL20.glGetUniformLocation(p,n),v);}
    static void gen(int p,String n,int v){GL41.glProgramUniform2ui(p,GL20.glGetUniformLocation(p,n),v,0);}
    static void dispatch(int p,int groups){GL20.glUseProgram(p);GL43.glDispatchCompute(groups,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_COMMAND_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);check(GL11.glGetError()==0,"dispatch "+p);}
    static void particle(ByteBuffer b,int slot,int header,float x,float z,float hp){int o=slot*64;b.putFloat(o,x).putFloat(o+8,z).putFloat(o+12,1).putFloat(o+48,1).putFloat(o+52,hp).putInt(o+60,header);}
    static ByteBuffer headers(){var b=bytes(3*ParticleBuffers.VEC4_PER_EMITTER*16);for(int h=0;h<3;h++){int o=h*ParticleBuffers.VEC4_PER_EMITTER*16;b.putFloat(o+7*16,h==0?0:2).putFloat(o+5*16+8,.3f).putFloat(o+5*16+12,.3f).putFloat(o+6*16,1).putFloat(o+18*16,h==2?1:0);}return b;}
    static class Fixture implements AutoCloseable {
        final int cap,modelCap,stormCap,pool,emit,counter,previous,pop,commands,args,sorted,carriers,add,opaque,hit;
        final int reset=compile("reset.comp"),keygen=compile("keygen.comp"),prepare=compile("prepare_dispatch.comp");
        final List<Integer> resources=new ArrayList<>();int alloc(ByteBuffer b){int id=buffer(b);resources.add(id);return id;}
        Fixture(int n,int m,int s){cap=n;modelCap=m;stormCap=s;pool=alloc(bytes(Math.max(1,n)*64));emit=alloc(headers());counter=alloc(bytes(16));previous=alloc(bytes(16));pop=alloc(bytes(32+4*(m+s)+64));commands=alloc(bytes(ParticleBuffers.INDIRECT_UINTS*4));args=alloc(bytes(60));sorted=alloc(bytes((n+ParticleBuffers.CARRIER_CAP+1)*8));carriers=alloc(bytes((n+ParticleBuffers.CARRIER_CAP+1)*8));add=alloc(bytes(Math.max(1,n)*4));opaque=alloc(bytes(Math.max(1,n)*4));hit=alloc(bytes(16));
            for(int p:new int[]{reset,keygen,prepare}){ui(p,"uCapacity",n);bool(p,"uPopulationWriteEnabled",true);gen(p,"uPopulationWriteGeneration",7);ui(p,"uPopulationModelCapacity",m);ui(p,"uPopulationStormCapacity",s);}ui(keygen,"uUpper",n);ui(keygen,"uCarrierBase",n);f(keygen,"uMaxDepth",128);f(keygen,"uDepthLogRange",7);GL41.glProgramUniformMatrix4fv(keygen,GL20.glGetUniformLocation(keygen,"uView"),false,new org.joml.Matrix4f().get(new float[16]));}
        void bindings(){bind(0,pool);bind(1,pool);bind(2,commands);bind(3,counter);bind(5,emit);bind(7,sorted);bind(8,add);bind(15,opaque);bind(17,hit);bind(23,carriers);bind(24,pop);bind(25,pop);bind(29,args);bind(14,previous);}
        void build(ByteBuffer data,int live){put(pool,data);bindings();dispatch(reset,1);put(counter,bytes(16).putInt(0,live));dispatch(keygen,(live+63)/64);bool(prepare,"uPopulationReadEnabled",true);gen(prepare,"uPopulationGeneration",7);ui(prepare,"uPhase",2);dispatch(prepare,1);}
        public void close(){GL20.glUseProgram(0);for(int id:resources)GL15.glDeleteBuffers(id);for(int p:new int[]{reset,keygen,prepare})GL20.glDeleteProgram(p);}
    }
    static void lists(){for(int n:new int[]{0,1,63,64,65,4097})for(boolean overflow:new boolean[]{false,true})try(var q=new Fixture(Math.max(1,n),overflow?1:Math.max(1,n),overflow?1:Math.max(1,n))){
        var data=bytes(Math.max(1,n)*64);int models=0,storm=0;for(int i=0;i<n;i++){int h=i%3;particle(data,i,h,i%2==0?5:-5,2,h==1?-.5f:20);if(h!=0)models++;if(h==2)storm++;}
        // Main frustum rejects every MODEL; population/shadow must retain them.
        GL41.glProgramUniform4fv(q.keygen,GL20.glGetUniformLocation(q.keygen,"uFrustum"),new float[]{1,0,0,-100,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0});
        q.build(data,n);var meta=read(q.pop,32+4*(q.modelCap+q.stormCap)+64);check(meta.getInt(0)==models&&meta.getInt(4)==storm&&meta.getInt(8)==n&&meta.getInt(12)==1&&meta.getInt(16)==7,"unculled role metadata");
        for(int i=32+4*(q.modelCap+q.stormCap);i<meta.capacity();i++)check(meta.get(i)==0,"role buffer tail overwritten");
        var dispatches=read(q.args,60);int expected=models<=q.modelCap&&models<n?models:n;check(dispatches.getInt(36)==(expected+63)/64,"model indirect count");expected=storm<=q.stormCap&&storm<n?storm:n;check(dispatches.getInt(48)==(expected+63)/64,"storm indirect count");
        try(var shadow=new ModelShadowGpu(Math.max(1,n),ModelPopulationGpuValidation::source)){var c=new PackageDrawCulling();c.clear();shadow.prepare(q.pool,q.emit,q.counter,c,0,0,0,.35f,.6f,1,0,new float[16],new float[16],new float[4],q.pop,7);check(read(shadow.commands(),(ParticleBuffers.INDIRECT_UINTS+4)*4).getInt(ParticleBuffers.IDX_CNT_MODELOP*4)==models,"shadow role population/overflow fallback");shadow.prepare(q.pool,q.emit,q.counter,c,0,0,0,.35f,.6f,1,0,new float[16],new float[16],new float[4],q.pop,8);check(read(shadow.commands(),(ParticleBuffers.INDIRECT_UINTS+4)*4).getInt(ParticleBuffers.IDX_CNT_MODELOP*4)==models,"wrong generation did not fall back");}
    }}
    static void attack(){try(var q=new Fixture(65,65,65)){
        var data=bytes(65*64);particle(data,0,1,0,2.01f,20);particle(data,1,1,0,2,20);particle(data,2,2,8,3,20);q.build(data,3);
        int identity=buffer(bytes(4+(65*2+ParticleBuffers.DAMAGE_QUEUE_CAP*2)*4).putInt(4,202).putInt(8,101)),damage=buffer(bytes(16+ParticleBuffers.DAMAGE_ENTRY_BYTES)),output=buffer(bytes(65*64));
        int pick=compile("hit.comp"),capture=compile("capture.comp"),validate=compile("damage_prepare.comp"),update=compile("update.comp"),emit=compile("emit.comp");var scratch=new ArrayList<Integer>();
        try{q.bindings();bind(31,identity);bind(16,damage);put(q.previous,bytes(16).putInt(0,3));
            for(int p:new int[]{pick,validate}){bool(p,"uPopulationReadEnabled",true);gen(p,"uPopulationGeneration",7);}ui(validate,"uIdentityReadBase",0);ui(validate,"uIdentityCapacity",65);
            GL41.glProgramUniform3f(pick,GL20.glGetUniformLocation(pick,"uEye"),0,.2f,0);GL41.glProgramUniform3f(pick,GL20.glGetUniformLocation(pick,"uLook"),0,0,1);f(pick,"uReach",4);
            bool(pick,"uHitResolve",false);dispatch(pick,1);bool(pick,"uHitResolve",true);dispatch(pick,1);check(read(q.hit,16).getInt(8)==1,"exact nearest lost to lower pool slot in same 1/32 distance bucket");
            bool(capture,"uHitResolved",true);ui(capture,"uIdentityWriteBase",0);GL41.glProgramUniform1i(capture,GL20.glGetUniformLocation(capture,"uMetaSlot"),65+ParticleBuffers.CARRIER_CAP);dispatch(capture,1);check(read(q.hit,16).getInt(12)==101,"capture picked wrong stable token");
            for(int token:new int[]{202,101}){
                var entry=bytes(16+ParticleBuffers.DAMAGE_ENTRY_BYTES);entry.putInt(0,1).putFloat(20,5).putInt(40,token).putFloat(52,.2f).putFloat(60,4).putFloat(72,1).putInt(76,1);put(damage,entry);
                q.bindings();bind(31,identity);bind(16,damage);dispatch(validate,1);check(read(damage,entry.capacity()).getInt(44)==(token==101?1:2),"stale snapshot damaged other target");
                put(q.counter,bytes(16));bind(1,output);ui(update,"uCapacity",65);ui(update,"uKillEmit",-1);ui(update,"uIdentityReadBase",0);ui(update,"uIdentityWriteBase",65);f(update,"uDt",0);
                for(int bb:new int[]{11,13,18,19,21}){int id=buffer(bytes(65536));scratch.add(id);bind(bb,id);}dispatch(update,1);
                var result=read(output,65*64);var ids=read(identity,4+(65*2+ParticleBuffers.DAMAGE_QUEUE_CAP*2)*4);for(int i=0;i<3;i++){int id=ids.getInt(4+(65+i)*4);if(id==101)check(result.getFloat(i*64+52)==(token==101?15:20),"A health incorrect");if(id==202)check(result.getFloat(i*64+52)==20,"B received A's damage");}
            }
            // Recompaction swapped source slots: local combat effects must find token 101 at slot 1, never slot 0.
            particle(data,0,1,9,2.01f,20);particle(data,1,1,-4,2,20);put(q.pool,data);
            q.bindings();bind(31,identity);put(q.counter,bytes(16));var h=headers();
            h.putFloat(17*16+4,2);put(q.emit,h);var cmd=bytes(48).putFloat(12,1).putFloat(16,0).putFloat(20,.4f).putInt(28,101).putFloat(40,-1);int commands=buffer(cmd);scratch.add(commands);bind(4,commands);bind(1,output);
            ui(emit,"uCapacity",65);ui(emit,"uTotalSpawn",1);ui(emit,"uEmitCount",1);ui(emit,"uIdentityReadBase",0);ui(emit,"uIdentityCapacity",65);ui(emit,"uDamageApprovalCount",1);bool(emit,"uPopulationReadEnabled",true);gen(emit,"uPopulationGeneration",7);
            dispatch(emit,1);check(read(q.counter,16).getInt(0)==1,"accepted stable combat effect not emitted");var effect=read(output,64);check(Math.abs(effect.getFloat(0)+4)<.6,"combat effect resolved to wrong source");
            // Rejected click token must suppress cosmetic damage as well.
            put(q.counter,bytes(16));cmd.putInt(28,202);put(commands,cmd);put(identity,bytes(4+(65*2+ParticleBuffers.DAMAGE_QUEUE_CAP*2)*4).putInt(4,202).putInt(8,101).putInt(4+130*4,202));dispatch(emit,1);check(read(q.counter,16).getInt(0)==0,"rejected click produced wrong-target effects");
        }finally{GL20.glUseProgram(0);for(int id:scratch)GL15.glDeleteBuffers(id);for(int id:new int[]{identity,damage,output})GL15.glDeleteBuffers(id);for(int p:new int[]{pick,capture,validate,update,emit})GL20.glDeleteProgram(p);}
    }}
    static void stormQueries(){try(var q=new Fixture(65,65,65)){
        var data=bytes(65*64);int living=0,all=0;for(int i=0;i<65;i++){int h=i%3;float hp=h==2&&i%9==2?0:20;particle(data,i,h,h==2?0:5,h==2?0:2,hp);if(h==2){data.putFloat(i*64+12,i+1);all++;if(hp>0)living++;}}
        q.build(data,65);put(q.previous,bytes(16).putInt(0,65));int grid=buffer(bytes((ParticleBuffers.GRID_TABLE+65)*4)),positions=buffer(bytes(4+ParticleBuffers.STORMPOS_CAP*32)),players=buffer(bytes(ParticleBuffers.MAX_STORM_PLAYERS*16));
        int build=compile("gridbuild.comp"),query=compile("stormpos.comp");try{
            q.bindings();bind(13,grid);bool(build,"uPopulationReadEnabled",true);gen(build,"uPopulationGeneration",7);dispatch(build,1);
            var links=read(grid,(ParticleBuffers.GRID_TABLE+65)*4);int visited=0;var seen=new BitSet();for(int bucket=0;bucket<ParticleBuffers.GRID_TABLE;bucket++){int slot=links.getInt(bucket*4);while(slot!=0){int i=slot-1;check(i<65&&i%3==2&&!seen.get(i),"grid included non-Storm or duplicate slot");seen.set(i);visited++;slot=links.getInt((ParticleBuffers.GRID_TABLE+i)*4);}}check(visited==all,"grid dropped Storm corpse/member");
            q.bindings();bind(18,players);bind(20,positions);bool(query,"uPopulationReadEnabled",true);gen(query,"uPopulationGeneration",7);GL41.glProgramUniform1i(query,GL20.glGetUniformLocation(query,"uPlayerCount"),1);dispatch(query,1);check(read(positions,4).getInt(0)==living,"indexed Storm readback included foreign MODEL or corpse");
        }finally{GL20.glUseProgram(0);for(int id:new int[]{grid,positions,players})GL15.glDeleteBuffers(id);GL20.glDeleteProgram(build);GL20.glDeleteProgram(query);}
    }}
    static double p(double[] a,double quantile){var b=a.clone();Arrays.sort(b);return b[(int)Math.ceil(quantile*b.length)-1];}
    static void benchmark()throws Exception{
        var rows=new ArrayList<String>();var raw=new ArrayList<String>();rows.add("total_live,model_live,mode,run,gpu_p50_ms,gpu_p95_ms,cpu_p50_ms,cpu_p95_ms,index_bytes");raw.add("total_live,model_live,mode,run,sample,gpu_ms,cpu_ms");
        int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);GL20.glShaderSource(shader,"#version 450 core\nlayout(local_size_x=1)in;layout(std430,binding=3)buffer Count{uint live;};uniform uint uLive;void main(){live=uLive;}");GL20.glCompileShader(shader);int liveProgram=GL20.glCreateProgram();GL20.glAttachShader(liveProgram,shader);GL20.glLinkProgram(liveProgram);GL20.glDeleteShader(shader);
        try{for(int[] scenario:new int[][]{{100000,100},{2000000,100},{2000000,10000},{2000000,2000000}}){int n=scenario[0],m=scenario[1];try(var q=new Fixture(n,Math.min(n,ParticleBuffers.MODEL_INDEX_LIMIT),Math.min(n,ParticleBuffers.STORM_INDEX_LIMIT));var shadow=new ModelShadowGpu(n,ModelPopulationGpuValidation::source)){
            var data=bytes(n*64);for(int i=0;i<n;i++)particle(data,i,0,0,2,20);int stride=n/m;for(int i=0;i<m;i++)particle(data,i*stride,1,i%8,2,20);put(q.pool,data);ui(liveProgram,"uLive",n);var culling=new PackageDrawCulling();culling.clear();float[] waves=new float[16],targets=new float[16],tiers=new float[4];
            for(boolean indexed:new boolean[]{false,true}){for(int program:new int[]{q.reset,q.keygen,q.prepare})bool(program,"uPopulationWriteEnabled",indexed);bool(q.prepare,"uPopulationReadEnabled",indexed);gen(q.prepare,"uPopulationGeneration",7);ui(q.prepare,"uPhase",2);
                Runnable submit=()->{q.bindings();dispatch(q.reset,1);dispatch(liveProgram,1);dispatch(q.keygen,(n+63)/64);dispatch(q.prepare,1);shadow.prepare(q.pool,q.emit,q.counter,culling,0,0,0,.35f,.6f,1,0,waves,targets,tiers,indexed?q.pop:0,7);};
                for(int run=1;run<=3;run++){long deadline=System.nanoTime()+1_000_000_000L;int warm=0;while(warm++<30||System.nanoTime()<deadline){submit.run();GL11.glFinish();}
                    double[] gt=new double[120],ct=new double[120];int timer=GL15.glGenQueries();try{for(int i=0;i<120;i++){GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,timer);long start=System.nanoTime();submit.run();ct[i]=(System.nanoTime()-start)/1e6;GL15.glEndQuery(GL33.GL_TIME_ELAPSED);gt[i]=GL33.glGetQueryObjectui64(timer,GL15.GL_QUERY_RESULT)/1e6;raw.add(n+","+m+","+(indexed?"indexed":"full_scan")+","+run+","+i+","+gt[i]+","+ct[i]);}}finally{GL15.glDeleteQueries(timer);}
                    check(read(shadow.commands(),(ParticleBuffers.INDIRECT_UINTS+4)*4).getInt(ParticleBuffers.IDX_CNT_MODELOP*4)==m,"benchmark lost MODEL");String row=n+","+m+","+(indexed?"indexed":"full_scan")+","+run+","+p(gt,.5)+","+p(gt,.95)+","+p(ct,.5)+","+p(ct,.95)+","+(2L*(32+4L*(q.modelCap+q.stormCap)));rows.add(row);System.out.println(row);
                }
            }
        }}}finally{GL20.glUseProgram(0);GL20.glDeleteProgram(liveProgram);}
        Files.write(Path.of(System.getProperty("cmi.benchmark.output", "build/benchmarks/current")+"/model-population-benchmark.csv"),rows);Files.write(Path.of(System.getProperty("cmi.benchmark.output", "build/benchmarks/current")+"/model-population-benchmark-samples.csv"),raw);
    }
    public static void main(String[] args)throws Exception{GLFWErrorCallback.createPrint(System.err).set();check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(64,64,"MODEL population validation",0,0);try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();System.out.println(GL11.glGetString(GL11.GL_RENDERER));lists();attack();stormQueries();if(Arrays.asList(args).contains("--benchmark"))benchmark();check(GL11.glGetError()==0,"final GL error");System.out.println("MODEL population GPU: "+checks+" assertions passed");}finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}}
}
