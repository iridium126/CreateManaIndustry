import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.particles.emitter.*;
import com.iridium126.createmanaindustry.client.particles.engine.*;
import com.iridium126.createmanaindustry.client.allaystorm.*;
import static com.iridium126.createmanaindustry.client.particles.emitter.AllayFeatherLayout.*;

/** Production update/keygen/ground/emit, real collision publications and vertex readback. */
public class AllayFeatherGpuValidation extends ModelPopulationGpuValidation {
    static boolean runBenchmark;
    static ByteBuffer floats(float[] f){var b=bytes(f.length*4);b.asFloatBuffer().put(f);return b;}
    static void vec(int p,String n,float x,float y,float z){GL41.glProgramUniform3f(p,GL20.glGetUniformLocation(p,n),x,y,z);}
    static void intUniform(int p,String n,int v){GL41.glProgramUniform1i(p,GL20.glGetUniformLocation(p,n),v);}
    static void near(double actual,double expected,double epsilon,String label){check(Math.abs(actual-expected)<=epsilon,label+": "+actual+" expected "+expected);}
    static int graphics(String vertex){
        int s=GL20.glCreateShader(GL20.GL_VERTEX_SHADER);GL20.glShaderSource(s,"#version 450 core\n"+ParticlePrograms.commonPrelude()+source(vertex));GL20.glCompileShader(s);
        check(GL20.glGetShaderi(s,GL20.GL_COMPILE_STATUS)!=0,GL20.glGetShaderInfoLog(s));int p=GL20.glCreateProgram();GL20.glAttachShader(p,s);
        GL30.glTransformFeedbackVaryings(p,new CharSequence[]{"gl_Position","vUv"},GL30.GL_INTERLEAVED_ATTRIBS);GL20.glLinkProgram(p);
        check(GL20.glGetProgrami(p,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(p));GL20.glDeleteShader(s);return p;
    }
    static class FeatherFixture implements AutoCloseable {
        final int cap,prefix,grid,input,output,header,counter,previous,commands,sort,opaque,update,key,tools,emit;
        final List<Integer> buffers=new ArrayList<>();
        final StormCollisionGpu terrain=new StormCollisionGpu();final Map<StormCollisionPages.Section,StormCollisionPages.Page> pages=new HashMap<>();
        int alloc(int n){int id=buffer(bytes(n));buffers.add(id);return id;}
        FeatherFixture(int capacity){
            cap=capacity;prefix=ParticleBuffers.GRID_TABLE+cap;
            input=alloc(cap*64);output=alloc(cap*64);grid=alloc(prefix*4+BYTES);header=alloc(960);counter=alloc(16);previous=alloc(16);
            commands=alloc(ParticleBuffers.INDIRECT_UINTS*4);sort=alloc(Math.max(64,cap*8));opaque=alloc(Math.max(4,cap*4));
            var h=bytes(960);float[] f=AllayFeatherSpecs.FEATHER.packed();h.asFloatBuffer().put(f).put(AllayGoldSpecs.GOLD.packed()).put(EmitterPresets.CHERRY_LEAVES.packed());put(header,h);
            update=compile("update.comp");key=compile("keygen.comp");tools=compile("feather_tools.comp");emit=compile("feather_emit.comp");
            for(int p:new int[]{update,key,tools,emit}){bool(p,"uFeatherEnabled",true);bool(p,"uStormCollisionEnabled",true);ui(p,"uCapacity",cap);}
            ui(update,"uKillEmit",-1);ui(update,"uFeatherGridBase",cap);ui(key,"uUpper",cap);ui(emit,"uEmitter",0);
            GL41.glProgramUniformMatrix4fv(key,GL20.glGetUniformLocation(key,"uView"),false,new org.joml.Matrix4f().get(new float[16]));f(key,"uMaxDepth",1024);f(key,"uDepthLogRange",10);
            for(int x=-2;x<=2;x++)for(int y=-2;y<=2;y++)for(int z=-2;z<=2;z++)pages.put(new StormCollisionPages.Section(x,y,z),new StormCollisionPages.Page(1,StormCollisionPages.AIR,null));
            floor(true);clear();
        }
        void floor(boolean solid){
            for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++){
                int[] bits=new int[128];if(solid)for(int a=0;a<256;a++)bits[a>>>5]|=1<<(a&31);
                pages.put(new StormCollisionPages.Section(x,0,z),StormCollisionPages.page(solid?2:3,bits));
            }
            terrain.offer(pages);for(int i=0;!terrain.settled()&&i<32;i++)terrain.pump(1<<20,1_000_000_000);check(terrain.settled(),"terrain publication completed");
        }
        void range(){GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,20,grid,prefix*4L,BYTES);}
        void clear(){
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,grid);GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,prefix*4L,BYTES,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,new int[]{0});
            var head=bytes(128).putInt(36,1).putInt(40,1).putInt(44,6);for(int i=0;i<3;i++)head.putInt((16+i)*4,Integer.MAX_VALUE).putInt((19+i)*4,Integer.MIN_VALUE);
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,prefix*4L,head);put(counter,bytes(16));put(commands,bytes(ParticleBuffers.INDIRECT_UINTS*4));range();
        }
        ByteBuffer metadata(){var b=bytes(128);GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,grid);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,prefix*4L,b);return b;}
        void word(int index,int value){GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,grid);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,(prefix+index)*4L,new int[]{value});}
        void simulate(ByteBuffer particles,int n,float dt){put(input,particles);put(previous,bytes(16).putInt(0,n));runUpdate(dt,n);}
        void runUpdate(float dt,int n){
            bind(0,input);bind(1,output);bind(3,counter);bind(5,header);bind(13,grid);bind(14,previous);range();terrain.bind(update);
            f(update,"uDt",dt);dispatch(update,(n+63)/64);
        }
        void lists(int n){
            bind(0,output);bind(2,commands);bind(3,counter);bind(5,header);bind(7,sort);bind(8,opaque);bind(15,opaque);bind(23,sort);range();
            GL41.glProgramUniform4fv(key,GL20.glGetUniformLocation(key,"uFrustum"),new float[24]);dispatch(key,(n+63)/64);
        }
        void ground(){bind(1,output);range();terrain.bind(tools);ui(tools,"uPhase",0);dispatch(tools,1);ui(tools,"uPhase",2);GL20.glUseProgram(tools);GL15.glBindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER,grid);GL43.glDispatchComputeIndirect((prefix+8)*4L);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);}
        void disturb(float x,float y,float z,float strength){
            int p=compileText("feather impulse",source("chunks/feather_state.glsl")+"\nlayout(local_size_x=1) in;uniform vec4 impact;void main(){cmiFeatherDisturb(impact.xyz,impact.w);}");
            bool(p,"uFeatherEnabled",true);range();GL41.glProgramUniform4f(p,GL20.glGetUniformLocation(p,"impact"),x,y,z,strength);dispatch(p,1);deleteProgram(p);
        }
        void player(float x,float y,float z,float strength){
            int movement=alloc(16);put(movement,floats(new float[]{x,y,z,strength}));bind(18,movement);range();
            ui(tools,"uPhase",1);ui(tools,"uPlayerCount",1);ui(tools,"uMovementBase",0);dispatch(tools,1);
        }
        void requestBatch(int count,boolean repeated){
            int p=compileText("feather requests",source("chunks/feather_state.glsl")+"\nlayout(local_size_x=64) in;uniform uint count;uniform bool repeated;void main(){uint i=gl_GlobalInvocationID.x;if(i>=count)return;vec3 p=repeated?vec3(0):vec3(float(i&127u),float(i>>7u),0)*16.;cmiFeatherRequest(p);}");
            range();bool(p,"uFeatherEnabled",true);bool(p,"uFeatherRequestFrame",true);bool(p,"repeated",repeated);ui(p,"count",count);dispatch(p,(count+63)/64);deleteProgram(p);
        }
        void modelImpact(){
            var spec=EmitterSpec.builder().type(ParticleTypes.standard(ParticleTypes.Material.MODEL)).collide(EmitterSpec.CollideMode.REST).build().packed();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,header);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,640,floats(spec));
            int damage=alloc(64),identities=alloc(cap*8+64),meta=alloc(CollisionBake.MAX_SLICES*16);bind(16,damage);bind(31,identities);bind(11,meta);
            put(meta,bytes(CollisionBake.MAX_SLICES*16).putFloat(0,-24).putFloat(4,-16).putFloat(8,-24).putFloat(12,1));
            var voxels=bytes(48*32*48);for(int z=0;z<48;z++)for(int x=0;x<48;x++)voxels.put((z*32+16)*48+x,(byte)255);
            int texture=GL11.glGenTextures();GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glBindTexture(GL12.GL_TEXTURE_3D,texture);GL12.glTexImage3D(GL12.GL_TEXTURE_3D,0,GL30.GL_R8,48,32,48,0,GL11.GL_RED,GL11.GL_UNSIGNED_BYTE,voxels);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL12.GL_TEXTURE_3D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);intUniform(update,"uCollisionOn",1);
            var model=one(2,1.001f,2,1,false).putFloat(20,-3).putFloat(32,1).putFloat(36,1).putFloat(40,1).putFloat(44,0).putFloat(52,20).putInt(60,2);
            simulate(model,1,.05f);check(metadata().getInt(60)>0,"actual MODEL landing deposits disturbance");
            put(counter,bytes(16));simulate(one(2,1.003f,2,1,true),1,.05f);lists(1);ground();check(read(output,64).getFloat(12)>0,"MODEL contact lifts nearby feather");
            if(cap>=60000){
                var crowded=bytes(60000*64);for(int i=0;i<60000;i++)for(int j=0;j<64;j++)crowded.put(i*64+j,model.get(j));
                int timer=GL15.glGenQueries();System.out.println("60000 simultaneous MODEL impacts begin");
                for(int frame=0;frame<4;frame++){clear();GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,timer);simulate(crowded,60000,.05f);GL15.glEndQuery(GL33.GL_TIME_ELAPSED);System.out.println("MODEL impact stress ms="+GL33.glGetQueryObjectui64(timer,GL15.GL_QUERY_RESULT)/1e6);check(metadata().getInt(60)<=8,"bounded colliding impulse field");}
                GL15.glDeleteQueries(timer);
            }
            intUniform(update,"uCollisionOn",0);GL11.glDeleteTextures(texture);
        }
        void emit(int n){
            propose(n,0,8,new net.minecraft.world.phys.Vec3(0,10,0));
        }
        void propose(int leaves,int gold,float radius,net.minecraft.world.phys.Vec3 center){
            bind(1,output);bind(3,counter);range();terrain.bind(emit);ui(emit,"uCount",leaves);ui(emit,"uGoldCount",gold);
            ui(emit,"uEmitter",0);ui(emit,"uGoldEmitter",1);ui(emit,"uSeed",1729);f(emit,"uRadius",radius);
            vec(emit,"uCenter",(float)center.x,(float)center.y,(float)center.z);dispatch(emit,(leaves+gold+63)/64);
        }
        @Override public void close(){GL20.glUseProgram(0);for(int id:buffers)GL15.glDeleteBuffers(id);for(int p:new int[]{update,key,tools,emit})deleteProgram(p);terrain.close();}
    }
    static int compileText(String name,String body){int s=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);String text="#version 450 core\n"+ParticlePrograms.commonPrelude()+body;try{Files.writeString(Path.of("build/feather-probe.comp"),text);}catch(java.io.IOException ex){throw new RuntimeException(ex);}GL20.glShaderSource(s,text);GL20.glCompileShader(s);check(GL20.glGetShaderi(s,GL20.GL_COMPILE_STATUS)!=0,name+GL20.glGetShaderInfoLog(s));int p=GL20.glCreateProgram();GL20.glAttachShader(p,s);GL20.glLinkProgram(p);check(GL20.glGetProgrami(p,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(p));GL20.glDeleteShader(s);return p;}
    static ByteBuffer one(float x,float y,float z,float age,boolean rest){
        return bytes(64).putFloat(0,x).putFloat(4,y).putFloat(8,z).putFloat(12,rest?-1:1)
                .putFloat(32,2).putFloat(36,0).putFloat(40,(float)Math.toRadians(30)).putFloat(44,(float)Math.toRadians(2.5))
                .putFloat(48,age).putFloat(52,60).putInt(56,3).putInt(60,0);
    }
    static void manualLifecycle(){try(var q=new FeatherFixture(4096)){
        int program=compile("emit.comp");
        try{
            var commands=new EmitCommandWriter();commands.basic(0,300,new net.minecraft.world.phys.Vec3(0,5,0),0);
            var encoded=commands.encode(123);float[] values=new float[encoded.remaining()];encoded.get(values);int input=q.alloc(values.length*4);put(input,floats(values));
            bind(0,q.input);bind(1,q.output);bind(3,q.counter);bind(4,input);bind(5,q.header);bind(14,q.previous);q.range();
            bool(program,"uFeatherEnabled",true);ui(program,"uTotalSpawn",300);ui(program,"uEmitCount",1);ui(program,"uCapacity",q.cap);
            System.out.println("Manual feather production emit begins");dispatch(program,5);
            int count=read(q.counter,16).getInt(0);check(count==300,"manual command count");q.lists(count);q.ground();
            var meta=q.metadata();check(meta.getInt(0)==300&&meta.getInt(8)==0,"manual airborne census");
            int initial=count;
            for(int frame=0;frame<1240;frame++){
                GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,q.output);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,q.input);GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,0,0,count*64L);
                put(q.previous,bytes(16).putInt(0,count));q.clear();q.runUpdate(.05f,count);
                count=read(q.counter,16).getInt(0);check(count>=0&&count<=q.cap,"manual lifecycle bounded pool");q.lists(count);
                meta=q.metadata();check(meta.getInt(8)>=0&&meta.getInt(8)<=initial,"manual lifecycle bounded ground index");q.ground();
                if(frame%200==0)System.out.println("Manual feather frame "+frame+" live="+count+" ground="+meta.getInt(8));
            }
            check(count==0,"manual feather expires without children");
        }finally{deleteProgram(program);}
    }}
    static void field(Object owner,String name,Object value)throws Exception{var f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);}
    static Object field(Object owner,String name)throws Exception{var f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    static void productionOwnership()throws Exception{
        var gpu=ParticleBuffers.INSTANCE;var owner=AllayFeatherGpu.INSTANCE;var programs=ParticlePrograms.INSTANCE;
        check(gpu.init(4096,32),"production buffers initialized");int key=compile("keygen.comp"),tools=compile("feather_tools.comp");
        int render=com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler.graphics("owned-feather","#version 450 core\n"+ParticlePrograms.commonPrelude()+source("feather.vsh"),"#version 450 core\n"+ParticlePrograms.commonPrelude()+source("textured.fsh"));
        int previousTools=(int)field(programs,"featherTools"),previousRender=(int)field(programs,"featherRender");
        int texture=GL11.glGenTextures();var tex=ParticleAtlas.class.getDeclaredField("textureId");tex.setAccessible(true);int previousTexture=tex.getInt(ParticleAtlas.ALLAY);
        try{
            field(programs,"featherTools",tools);field(programs,"featherRender",render);gpu.setEmitterHeader(0,AllayFeatherSpecs.FEATHER.packed());gpu.uploadDirtyEmitters();
            var allocate=owner.getClass().getDeclaredMethod("allocate");allocate.setAccessible(true);allocate.invoke(owner);field(owner,"enabled",true);field(owner,"mayFeathers",true);field(owner,"candidateBirth",true);field(owner,"candidate",1);
            owner.begin();int[] banks=(int[])field(owner,"state");long base=banks[1]*4L;
            var particles=bytes(300*64);for(int i=0;i<300;i++){var record=one(0,0,0,0,false);for(int j=0;j<64;j++)particles.put(i*64+j,record.get(j));}put(((int[])field(gpu,"particleSSBOs"))[1-(int)field(gpu,"readIndex")],particles);put(((int[])field(gpu,"counterSSBOs"))[0],bytes(16).putInt(0,300));
            gpu.beginBindings();GL20.glUseProgram(key);gpu.bindParticleWrite(0);gpu.bindCounter(3,0);gpu.bindEmitters(5);gpu.bindIndirect(2);gpu.bindOrderAdd();gpu.bindOrderOpaque();gpu.bindSort(7,gpu.sortBuffer(0));gpu.bindCarrierSink();owner.bind(key);ui(key,"uCapacity",4096);ui(key,"uUpper",4096);f(key,"uMaxDepth",128);f(key,"uDepthLogRange",7);GL41.glProgramUniformMatrix4fv(key,GL20.glGetUniformLocation(key,"uView"),false,new org.joml.Matrix4f().get(new float[16]));dispatch(key,5);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,gpu.dispatchBufferId());GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,ParticleBuffers.STORM_DISPATCH*12L,new int[]{1,1,1});
            // Wrong ambient binding would submit 24 groups, kept safe in this probe.
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,gpu.gridBufferId());GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,ParticleBuffers.STORM_DISPATCH*12L,new int[]{2,3,4});
            gpu.bindDispatch();System.out.println("Production feather ground command begins");owner.ground();
            check(GL11.glGetInteger(GL43.GL_DISPATCH_INDIRECT_BUFFER_BINDING)==gpu.dispatchBufferId(),"feather ground pass leaked its grid buffer into the following Storm wave-contact dispatch");
            int probe=compileText("feather following dispatch","layout(local_size_x=64)in;layout(std430,binding=0)buffer Result{uint groups;};void main(){if(gl_LocalInvocationIndex==0u)atomicAdd(groups,1u);}"),result=buffer(bytes(4));
            try{bind(0,result);GL20.glUseProgram(probe);gpu.dispatch(ParticleBuffers.STORM_DISPATCH);check(read(result,4).getInt(0)==1,"following Storm-style dispatch must consume its own 1x1x1 command");}finally{deleteProgram(probe);GL15.glDeleteBuffers(result);}
            var meta=bytes(128);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,gpu.gridBufferId());GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,base,meta);
            check(meta.getInt(0)==300&&meta.getInt(8)==0&&meta.getInt(44)==6&&meta.getInt(48)==300,"production candidate command and counters");
            gpu.swap();owner.commit(1);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,1,1,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,bytes(4).putInt(0,-1));GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);tex.setInt(ParticleAtlas.ALLAY,texture);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,0);GL11.glViewport(0,0,64,64);GL11.glClearColor(0,0,0,0);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT);
            System.out.println("Production feather fallback draw begins");owner.draw(1,new org.joml.Matrix4f(),new org.joml.Matrix4f(),new net.minecraft.client.Camera());GL11.glFinish();check(GL11.glGetError()==0,"production feather fallback draw GL error");
        }finally{tex.setInt(ParticleAtlas.ALLAY,previousTexture);GL11.glDeleteTextures(texture);owner.close();field(programs,"featherTools",previousTools);field(programs,"featherRender",previousRender);gpu.free();for(int p:new int[]{key,tools,render})deleteProgram(p);GL30.glBindVertexArray(GL30.glGenVertexArrays());}
    }
    static void behavior(){try(var q=new FeatherFixture(1024)){
        q.emit(256);int bornCount=read(q.counter,16).getInt(0);check(bornCount>100&&bornCount<200,"radially weighted rain generation");var born=read(q.output,bornCount*64);boolean small=false,large=false;
        for(int i=0;i<bornCount;i++){int b=i*64;double r=Math.hypot(born.getFloat(b),born.getFloat(b+8));check(r>=0&&r<16.001,"spawn ring");check(born.getFloat(b+4)>=2&&born.getFloat(b+4)<=10,"spawn height");check(born.getFloat(b+52)==60,"birth life");small|=born.getFloat(b+12)<.9;large|=born.getFloat(b+12)>.9;check((born.getInt(b+56)&15)<12,"sprite selection");}
        check(small&&large,"both cherry size classes");
        q.clear();q.word(4,MAX_FEATHERS);q.emit(16);check(read(q.counter,16).getInt(0)==0,"feather cap");
        q.clear();q.simulate(one(2,6,2,0,false),1,.05f);var air=read(q.output,64);near(air.getFloat(4),6-.00075,1e-6,"air gravity");check(air.getFloat(12)>0,"airborne state");
        q.clear();q.simulate(one(2,1.001f,2,1,false).putFloat(20,-1),1,.05f);var landed=read(q.output,64);check(landed.getFloat(12)<0,"landed survives");near(landed.getFloat(20),0,0,"landing stops velocity");
        q.lists(1);check(q.metadata().getInt(0)==1&&q.metadata().getInt(8)==1&&q.metadata().getInt(12)==1,"unculled ground and visible lists");check(read(q.commands,ParticleBuffers.INDIRECT_UINTS*4).getInt(ParticleBuffers.IDX_CNT_SPRITE*4)==0,"excluded from generic sprite draw");q.ground();
        q.clear();q.simulate(one(2,1.003f,2,1,true),1,.05f);var resting=read(q.output,64);near(resting.getFloat(4),1.003,1e-6,"rest has no gravity");near(resting.getFloat(48),1.05,1e-6,"rest ages once");
        q.lists(1);q.disturb(2,1.003f,2,2);q.ground();var lifted=read(q.output,64);check(lifted.getFloat(12)>0&&lifted.getFloat(20)>1,"ground disturbance lifts");near(lifted.getFloat(48),1.05,1e-6,"ground pass does not age twice");near(lifted.getFloat(4),1.003,1e-6,"lift starts moving next frame");
        for(float distance:new float[]{1.9f,2f,2.1f}){q.clear();q.simulate(one(2,1.003f,2,1,true),1,.05f);q.lists(1);q.player(2+distance,1.003f,2,2);q.ground();check((read(q.output,64).getFloat(12)>0)==(distance<2),"two block player disturbance boundary "+distance);}
        q.clear();q.player(2,1,2,0);check(q.metadata().getInt(60)==0,"stationary/airborne player produces no disturbance");
        q.clear();q.modelImpact();
        q.clear();var cooling=one(2,1.003f,2,1,true).putInt(56,3|(30<<4));q.simulate(cooling,1,.05f);q.lists(1);q.disturb(2,1.003f,2,2);q.ground();check(read(q.output,64).getFloat(12)<0,"lift cooldown");
        q.floor(false);q.clear();q.simulate(one(2,1.003f,2,1,true),1,.05f);q.lists(1);q.ground();check(read(q.output,64).getFloat(12)>0,"removed support resumes falling");q.floor(true);
        q.clear();q.simulate(one(2,80,2,1,false).putFloat(20,-4),1,.05f);var unknown=read(q.output,64);near(unknown.getFloat(4),80,0,"unknown coverage holds position");near(unknown.getFloat(48),1.05,1e-6,"unknown coverage still ages");
        q.clear();q.simulate(one(2,.5f,2,60,true),1,.05f);check(read(q.counter,16).getInt(0)==0,"expiration removes feather without children");
        q.clear();q.ground();check(q.metadata().getInt(32)==0,"zero ground yields zero workgroups");
        q.clear();q.requestBatch(4096,true);check(q.metadata().getInt(88)==1,"GPU section requests deduplicate concurrent identical keys");
        q.clear();q.word(REQUESTS+MAX_REQUESTS*4,0x12345678);q.requestBatch(8192,false);
        check(q.metadata().getInt(88)>=4096,"bounded request overflow exercised");var requested=bytes(MAX_REQUESTS*16+4);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,q.grid);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,(q.prefix+REQUESTS)*4L,requested);
        var unique=new HashSet<String>();for(int i=0;i<MAX_REQUESTS;i++){int b=i*16;check(requested.getInt(b+12)==1,"retained request initialized");check(unique.add(requested.getInt(b)+":"+requested.getInt(b+4)+":"+requested.getInt(b+8)),"retained requests unique");}
        check(requested.getInt(MAX_REQUESTS*16)==0x12345678,"request overflow does not write beyond retained capacity");
    }}
    static void paletteDistribution(){try(var q=new FeatherFixture(65536)){
        q.emit(60000);int count=read(q.counter,16).getInt(0);check(count>34000&&count<36000,"palette population");var data=read(q.output,count*64);int[] colors=new int[3];
        for(int i=0;i<count;i++){int palette=(data.getInt(i*64+56)>>>16)&3;check(palette<3,"valid stable palette");colors[palette]++;}
        for(int palette=0;palette<3;palette++)near(colors[palette]/(double)count,new double[]{.60,.32,.08}[palette],.012,"birth palette proportion");
    }}
    static void publish(FeatherFixture q){q.terrain.offer(q.pages);for(int i=0;!q.terrain.settled()&&i<64;i++)q.terrain.pump(1048576,1000000000);check(q.terrain.settled(),"surface fixture publication");}
    static int height(StormSurfaceGpu surface,net.minecraft.world.phys.Vec3 center,int x,int z){
        int ox=((int)Math.floor(center.x)>>4)*16-128,oz=((int)Math.floor(center.z)>>4)*16-128;
        return StormSurfaceCache.height(read(surface.buffer(),StormSurfaceGpu.BYTES).getInt(StormSurfaceCache.cell(x,z)*4));
    }
    static void surfaceAndGold()throws Exception{try(var q=new FeatherFixture(16384);var surface=new StormSurfaceGpu()){
        int kernel=compile("gold_surface.comp");try{
            var center=new net.minecraft.world.phys.Vec3(0,10,0);long bytes=q.terrain.uploaded();
            check(surface.prepare(kernel,q.terrain,center),"surface build");check(q.terrain.uploaded()==bytes,"surface build uploaded terrain");
            check(height(surface,center,2,2)==1,"flat top");check(height(surface,center,100,100)==Integer.MIN_VALUE,"unknown top");
            long builds=surface.rebuilds();surface.prepare(kernel,q.terrain,new net.minecraft.world.phys.Vec3(1,10,1));check(surface.rebuilds()==builds,"stable aligned surface cache rebuilt");
            int[] roof=new int[128];for(int cell=0;cell<256;cell++)roof[cell>>>5]|=1<<(cell&31);int cell=2+2*16+5*256;roof[cell>>>5]|=1<<(cell&31);
            q.pages.put(new StormCollisionPages.Section(0,0,0),StormCollisionPages.page(100,roof));publish(q);surface.prepare(kernel,q.terrain,center);check(height(surface,center,2,2)==6,"highest roof top");
            var lower=new net.minecraft.world.phys.Vec3(0,5,0);surface.prepare(kernel,q.terrain,lower);check(height(surface,lower,2,2)==1,"under roof top does not require sky");
            cell=2+2*16+15*256;roof=roof.clone();roof[cell>>>5]|=1<<(cell&31);q.pages.put(new StormCollisionPages.Section(0,0,0),StormCollisionPages.page(101,roof));q.pages.remove(new StormCollisionPages.Section(0,1,0));publish(q);
            var higher=new net.minecraft.world.phys.Vec3(0,20,0);surface.prepare(kernel,q.terrain,higher);check(height(surface,higher,2,2)==6,"unknown above page cannot expose top");
            q.pages.put(new StormCollisionPages.Section(0,1,0),new StormCollisionPages.Page(102,StormCollisionPages.AIR,null));publish(q);surface.prepare(kernel,q.terrain,higher);check(height(surface,higher,2,2)==16,"section boundary top");
            int[] negative=new int[128];cell=15+15*16+14*256;negative[cell>>>5]|=1<<(cell&31);q.pages.put(new StormCollisionPages.Section(-1,-1,-1),StormCollisionPages.page(103,negative));publish(q);
            var below=new net.minecraft.world.phys.Vec3(-1,0,-1);surface.prepare(kernel,q.terrain,below);check(height(surface,below,-1,-1)==-1,"negative coordinate top");
            surface.prepare(kernel,q.terrain,center);q.clear();GL20.glUseProgram(q.emit);surface.bindEmitter(q.emit);bool(q.emit,"uSurfaceReady",true);q.propose(0,10000,8,center);
            int count=read(q.counter,16).getInt(0);check(count>5500&&count<6100,"gold radial rate");var particles=read(q.output,count*64);int stars=0;
            for(int i=0;i<count;i++){int b=i*64;float size=particles.getFloat(b+12);check(particles.getInt(b+60)==1,"gold emitter");check(particles.getFloat(b+52)>=8&&particles.getFloat(b+52)<=12,"gold lifetime");check(particles.getFloat(b+20)>=.5&&particles.getFloat(b+20)<=1.1,"gold upward speed");check((size>=.025&&size<=.06)||(size>=.08&&size<=.16),"gold size classes");if(size>.07)stars++;}
            near(stars/(double)count,.2,.02,"gold star ratio");q.lists(count);var metadata=q.metadata();check(metadata.getInt(0)==0&&metadata.getInt(4)==count&&metadata.getInt(8)==0,"gold never enters feather ground list");
            var one=bytes(64);for(int w=0;w<16;w++)one.putInt(w*4,particles.getInt(w*4));one.putFloat(4,.5f);q.clear();bool(q.update,"uFeatherRequestFrame",true);q.simulate(one,1,.05f);var moved=read(q.output,64);check(moved.getFloat(4)>.5,"gold has no collision inside solid");check(q.metadata().getInt(88)==0,"gold motion requested terrain");
            // Pure cached helix must retain radius through the longest production step.
            double radius=Math.hypot(one.getFloat(32),one.getFloat(36));for(int frame=0;frame<47;frame++){q.clear();q.simulate(moved,1,.25f);if(read(q.counter,16).getInt(0)==0)break;moved=read(q.output,64);near(Math.hypot(moved.getFloat(32),moved.getFloat(36)),radius,.00005,"gold orbit stability");}
            q.clear();one.putFloat(48,12);q.simulate(one,1,.05f);check(read(q.counter,16).getInt(0)==0,"gold expiration has no children");
            q.clear();q.word(5,AllayGoldSpecs.MAX_COUNT);GL20.glUseProgram(q.emit);surface.bindEmitter(q.emit);bool(q.emit,"uSurfaceReady",true);q.propose(0,100,8,center);check(read(q.counter,16).getInt(0)==0,"gold cap");
            q.pages.put(new StormCollisionPages.Section(2,0,2),new StormCollisionPages.Page(104,StormCollisionPages.SOLID,null));
            q.pages.put(new StormCollisionPages.Section(2,1,2),new StormCollisionPages.Page(105,StormCollisionPages.AIR,null));publish(q);surface.prepare(kernel,q.terrain,higher);
            check(height(surface,higher,33,33)==16,"compressed solid page exposes its top beneath known air");
            int[] above=new int[128];above[0]=1;q.pages.put(new StormCollisionPages.Section(2,1,2),StormCollisionPages.page(106,above));publish(q);surface.prepare(kernel,q.terrain,higher);
            check(height(surface,higher,32,32)==17&&height(surface,higher,33,33)==16,"compressed solid page with mixed page above");
            q.pages.remove(new StormCollisionPages.Section(2,1,2));publish(q);surface.prepare(kernel,q.terrain,higher);
            check(height(surface,higher,33,33)==Integer.MIN_VALUE,"compressed solid page cannot expose unknown upper boundary");
            surface.reset();builds=surface.rebuilds();surface.prepare(kernel,q.terrain,center);check(surface.rebuilds()==builds+1,"clear invalidates derived surface");
        }finally{deleteProgram(kernel);}
    }}
    static ByteBuffer surfaceFeedback(StormSurfaceGpu surface,net.minecraft.world.phys.Vec3 center,double radius){
        surface.feedback(center,radius,true);var all=read(surface.buffer(),StormSurfaceCache.TOTAL_BYTES);all.position(StormSurfaceGpu.BYTES).limit(StormSurfaceGpu.BYTES+StormSurfaceCache.FEEDBACK_BYTES);return all.slice().order(ByteOrder.nativeOrder());
    }
    static void hybridSurface()throws Exception{try(var q=new FeatherFixture(8192);var surface=new StormSurfaceGpu()){
        int kernel=compile("gold_surface.comp");try{
            var center=new net.minecraft.world.phys.Vec3(0,128,0);surface.prepare(kernel,null,center,32,16384);
            var requests=surfaceFeedback(surface,center,32);var cache=surface.cache();cache.feedback(cache.epoch,requests);check(cache.queued()>10000,"gold-only missing columns queued without voxel residency");
            var plane=new StormSurfaceCache.Source(){public int minY(){return -64;}public int startY(int x,int z,int top){return Math.min(top,64);}public int occupied(int x,int y,int z){return y<64?1:0;}};
            int frames=0;while((cache.pending()||cache.hasPatches())&&frames++<128){cache.capture(plane,Long.MAX_VALUE);surface.prepare(kernel,null,center,32,16384);}
            check(!cache.pending()&&!cache.hasPatches(),"bounded fallback prewarm completes");check(height(surface,center,0,0)==64,"gold-only fallback reaches ordinary ground under Y128");
            long builds=surface.rebuilds(),upload=surface.uploaded(),queries=cache.queries;surface.prepare(kernel,null,center,32,16384);cache.capture(plane,Long.MAX_VALUE);
            check(surface.rebuilds()==builds&&surface.uploaded()==upload&&cache.queries==queries,"stable fallback performs no query upload or rebuild");
            q.clear();GL20.glUseProgram(q.emit);surface.bindEmitter(q.emit);bool(q.emit,"uSurfaceReady",true);q.propose(0,2048,32,center);check(read(q.counter,16).getInt(0)>1000,"fallback heights feed production gold spawn");
            surface.prepare(kernel,q.terrain,center,32,16384);check(height(surface,center,0,0)==1,"voxel surface takes priority over higher fallback");
            q.pages.put(new StormCollisionPages.Section(1000,0,1000),new StormCollisionPages.Page(900,StormCollisionPages.AIR,null));publish(q);builds=surface.rebuilds();surface.prepare(kernel,q.terrain,center,32,16384);check(surface.rebuilds()==builds,"unrelated voxel publication leaves surface intact");
            // Invalidate the CPU data and remove local voxel coverage: old heights must not survive.
            cache.changed(0,0,true);q.pages.remove(new StormCollisionPages.Section(0,0,0));publish(q);surface.prepare(kernel,q.terrain,center,32,16384);check(height(surface,center,0,0)==Integer.MIN_VALUE,"terrain invalidation clears stale fallback and voxel heights");
            var moved=new net.minecraft.world.phys.Vec3(16,128,0);int version=cache.versions[StormSurfaceCache.tile(-1,0)];surface.prepare(kernel,q.terrain,moved,32,16384);check(cache.versions[StormSurfaceCache.tile(-1,0)]==version,"ring window preserves overlapping tiles");
            var alias=new net.minecraft.world.phys.Vec3(256,128,0);surface.prepare(kernel,null,alias,32,16384);check(height(surface,alias,256,0)==Integer.MIN_VALUE,"ring window clears reused storage");
            check(q.terrain.uploaded()>0&&surface.bytes()==StormSurfaceCache.TOTAL_BYTES,"one packed height map and bounded staging only");
            var delayed=surfaceFeedback(surface,alias,32);long oldEpoch=cache.epoch;surface.reset();surface.prepare(kernel,null,alias,32,16384);cache.feedback(oldEpoch,delayed);check(!cache.pending(),"world reset drops delayed surface feedback");
        }finally{deleteProgram(kernel);}
    }}
    static void surfaceSnapshots()throws Exception{try(var surface=new StormSurfaceGpu()){
        int kernel=compile("gold_surface.comp");var type=Class.forName("com.iridium126.createmanaindustry.client.particles.engine.ParticleReadbacks");
        var ctor=type.getDeclaredConstructor();ctor.setAccessible(true);Object snapshots=ctor.newInstance();
        java.lang.reflect.Method free=type.getDeclaredMethod("freeSlot"),completed=type.getDeclaredMethod("completed"),close=type.getDeclaredMethod("close");for(var method:new java.lang.reflect.Method[]{free,completed,close})method.setAccessible(true);
        try{
            var center=new net.minecraft.world.phys.Vec3(0,128,0);surface.prepare(kernel,null,center,32,16384);
            Object slot=free.invoke(snapshots);Class<?> slotType=slot.getClass();
            java.lang.reflect.Method copy=StormSurfaceGpu.class.getDeclaredMethod("copySnapshot",slotType),observe=StormSurfaceGpu.class.getDeclaredMethod("observe",slotType),commit=StormSurfaceGpu.class.getDeclaredMethod("commit"),release=type.getDeclaredMethod("release",slotType);
            for(var method:new java.lang.reflect.Method[]{copy,observe,commit,release})method.setAccessible(true);
            surface.feedback(center,32,false);check(!(boolean)field(surface,"feedbackGenerated"),"surface feedback only on eligible successful-frame cadence");
            surface.feedback(center,32,true);copy.invoke(surface,slot);field(slot,"generation",1L);field(slot,"fence",GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0));GL11.glFinish();
            check(completed.invoke(snapshots)==null,"failed unpublished frame cannot deliver terrain feedback");release.invoke(snapshots,slot);
            // Begin another candidate without commit: the missing feedback remains due.
            surface.frame(null,null,0,false);surface.feedback(center,32,true);check((boolean)field(surface,"feedbackGenerated"),"aborted snapshot does not consume feedback request");
            slot=free.invoke(snapshots);copy.invoke(surface,slot);field(slot,"published",true);field(slot,"fence",GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0));GL11.glFinish();
            check(completed.invoke(snapshots)==slot,"surface bitmap shares existing completed fence");observe.invoke(surface,slot);check(surface.cache().queued()>10000,"persistent snapshot mapping delivers missing columns");
            commit.invoke(surface);surface.feedback(center,32,true);check(!(boolean)field(surface,"feedbackGenerated"),"stable committed snapshot suppresses further feedback");
            surface.reset();observe.invoke(surface,slot);check(!surface.cache().pending(),"reset rejects completed snapshot from previous world epoch");release.invoke(snapshots,slot);
        }finally{close.invoke(snapshots);deleteProgram(kernel);}
    }}
    static void manualGold(){try(var q=new FeatherFixture(4096)){
        int emit=compile("emit.comp");try{
            var writer=new EmitCommandWriter();writer.basic(1,300,new net.minecraft.world.phys.Vec3(0,5,0),0);var encoded=writer.encode(123);float[] values=new float[encoded.remaining()];encoded.get(values);int commands=q.alloc(values.length*4);put(commands,floats(values));
            bind(0,q.input);bind(1,q.output);bind(3,q.counter);bind(4,commands);bind(5,q.header);bind(14,q.previous);q.range();bool(emit,"uFeatherEnabled",true);ui(emit,"uCapacity",q.cap);ui(emit,"uTotalSpawn",300);ui(emit,"uEmitCount",1);dispatch(emit,5);
            int count=read(q.counter,16).getInt(0);check(count==300,"manual allay_gold command");q.lists(count);check(q.metadata().getInt(4)==300&&q.metadata().getInt(8)==0,"manual gold census");
            for(int frame=0;frame<260;frame++){var input=read(q.output,Math.max(1,count)*64);q.clear();q.simulate(input,count,.05f);count=read(q.counter,16).getInt(0);check(count<=300,"gold lifecycle grew children");}
            check(count==0,"manual gold expiration");
        }finally{deleteProgram(emit);}
    }}
    static int stage(String name,int type){int id=GL20.glCreateShader(type);GL20.glShaderSource(id,"#version 450 core\n"+ParticlePrograms.commonPrelude()+source(name));GL20.glCompileShader(id);check(GL20.glGetShaderi(id,GL20.GL_COMPILE_STATUS)!=0,GL20.glGetShaderInfoLog(id));return id;}
    static void goldDrawing()throws Exception{
        int vs=stage("additive.vsh",GL20.GL_VERTEX_SHADER),fs=stage("additive.fsh",GL20.GL_FRAGMENT_SHADER),program=GL20.glCreateProgram();GL20.glAttachShader(program,vs);GL20.glAttachShader(program,fs);GL20.glLinkProgram(program);check(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(program));
        int pool=buffer(bytes((runBenchmark?65536:1)*64)),headers=buffer(floats(AllayGoldSpecs.GOLD.packed())),order=buffer(bytes((runBenchmark?65536:1)*4)),framebuffer=GL30.glGenFramebuffers(),texture=GL11.glGenTextures(),depth=GL30.glGenRenderbuffers();
        try{
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,framebuffer);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL30.GL_RGBA32F,256,256,0,GL11.GL_RGBA,GL11.GL_FLOAT,(ByteBuffer)null);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,texture,0);GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER,depth);GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER,GL30.GL_DEPTH_COMPONENT24,256,256);GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER,GL30.GL_DEPTH_ATTACHMENT,GL30.GL_RENDERBUFFER,depth);check(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)==GL30.GL_FRAMEBUFFER_COMPLETE,"gold framebuffer");
            bind(1,pool);bind(5,headers);bind(8,order);GL20.glUseProgram(program);GL41.glProgramUniformMatrix4fv(program,GL20.glGetUniformLocation(program,"ModelViewMat"),false,new org.joml.Matrix4f().get(new float[16]));GL41.glProgramUniformMatrix4fv(program,GL20.glGetUniformLocation(program,"ProjMat"),false,new org.joml.Matrix4f().ortho(-.12f,.12f,-.12f,.12f,.1f,10).get(new float[16]));vec(program,"uCamPos",0,0,2);vec(program,"uCamRight",1,0,0);vec(program,"uCamUp",0,1,0);f(program,"uFadeDist",96);f(program,"uGlow",1);
            GL11.glViewport(0,0,256,256);GL11.glDisable(GL11.GL_CULL_FACE);GL11.glEnable(GL11.GL_BLEND);GL11.glBlendFunc(GL11.GL_ONE,GL11.GL_ONE);GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthMask(true);GL11.glClearDepth(1);GL11.glClearColor(0,0,0,.37f);
            for(boolean star:new boolean[]{false,true}){
                var particle=bytes(64).putFloat(12,star?.16f:.06f).putFloat(48,1).putFloat(52,10).putFloat(56,.9f);put(pool,particle);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT);GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES,0,6,1);
                var pixels=bytes(256*256*16);GL11.glReadPixels(0,0,256,256,GL11.GL_RGBA,GL11.GL_FLOAT,pixels);int center=(128*256+128)*16;check(pixels.getFloat(center)>pixels.getFloat(center+4)&&pixels.getFloat(center+4)>pixels.getFloat(center+8),"gold palette in production draw");near(pixels.getFloat(center+12),.37,.00001,"additive preserves destination alpha");
                if(star)check(pixels.getFloat((128*256+178)*16)>pixels.getFloat((178*256+178)*16)*3,"four point star rays");
                var image=new java.awt.image.BufferedImage(256,256,java.awt.image.BufferedImage.TYPE_INT_RGB);for(int y=0;y<256;y++)for(int x=0;x<256;x++){int b=(y*256+x)*16,r=(int)(Math.clamp(pixels.getFloat(b),0,1)*255),g=(int)(Math.clamp(pixels.getFloat(b+4),0,1)*255),blue=(int)(Math.clamp(pixels.getFloat(b+8),0,1)*255);image.setRGB(x,255-y,(r<<16)|(g<<8)|blue);}var path=Path.of("build/validation/allay-gold-"+(star?"star":"dust")+".png");Files.createDirectories(path.getParent());javax.imageio.ImageIO.write(image,"png",path.toFile());
            }
            GL11.glDepthMask(true);GL11.glClearDepth(0);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT);GL11.glDepthMask(false);GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES,0,6,1);var hidden=bytes(16);GL11.glReadPixels(128,128,1,1,GL11.GL_RGBA,GL11.GL_FLOAT,hidden);near(hidden.getFloat(0),0,0,"gold respects scene depth");GL11.glClearDepth(1);if(runBenchmark)benchmarkGoldDraw(program,pool,order);check(GL11.glGetError()==0,"gold draw GL state");
        }finally{GL11.glClearDepth(1);GL11.glDisable(GL11.GL_BLEND);GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDepthMask(true);GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,0);GL11.glViewport(0,0,64,64);GL30.glDeleteFramebuffers(framebuffer);GL30.glDeleteRenderbuffers(depth);GL11.glDeleteTextures(texture);for(int id:new int[]{pool,headers,order})GL15.glDeleteBuffers(id);deleteProgram(program);GL20.glDeleteShader(vs);GL20.glDeleteShader(fs);}
    }
    static void benchmarkGoldDraw(int program,int pool,int order)throws Exception{
        var rows=new ArrayList<String>();rows.add("gold_count,draw_gpu_ms,cpu_draw_submit_ms");int query=GL15.glGenQueries();
        try{
            GL41.glProgramUniformMatrix4fv(program,GL20.glGetUniformLocation(program,"ProjMat"),false,new org.joml.Matrix4f().ortho(-8,8,-8,8,.1f,10).get(new float[16]));
            for(int count:new int[]{0,10000,65536}){
                var records=bytes(Math.max(1,count)*64);var indices=bytes(Math.max(1,count)*4);for(int i=0;i<count;i++){int b=i*64;records.putFloat(b,(i%256-128)/16f).putFloat(b+4,(i/256-128)/16f).putFloat(b+12,i%5==0?.12f:.04f).putFloat(b+48,1).putFloat(b+52,10).putFloat(b+56,.9f);indices.putInt(i*4,i);}
                put(pool,records);put(order,indices);double gpu=0,cpu=0;
                for(int sample=0;sample<40;sample++){GL11.glDepthMask(true);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT);GL11.glDepthMask(false);long begin=System.nanoTime();GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,query);GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES,0,6,count);GL15.glEndQuery(GL33.GL_TIME_ELAPSED);long end=System.nanoTime();double elapsed=GL33.glGetQueryObjectui64(query,GL15.GL_QUERY_RESULT)/1e6;if(sample>=10){gpu+=elapsed;cpu+=(end-begin)/1e6;}}
                String row=count+","+gpu/30+","+cpu/30;rows.add(row);System.out.println("Gold draw "+row);
            }
        }finally{GL15.glDeleteQueries(query);}
        var output=Path.of("build/benchmarks/allay-gold/draw.csv");Files.createDirectories(output.getParent());Files.write(output,rows);
    }
    static void benchmarkGoldEmission()throws Exception{try(var q=new FeatherFixture(65536);var surface=new StormSurfaceGpu()){
        int program=compile("gold_surface.comp");int[] queries={GL15.glGenQueries(),GL15.glGenQueries(),GL15.glGenQueries()};var rows=new ArrayList<String>();rows.add("rebuild_surface,gold_count,surface_gpu_ms,emit_gpu_ms,update_gpu_ms,cpu_submit_ms,terrain_upload_delta");
        try{
            var center=new net.minecraft.world.phys.Vec3(0,10,0);surface.prepare(program,q.terrain,center);long uploaded=q.terrain.uploaded();
            for(boolean rebuild:new boolean[]{false,true}){double[] sums=new double[4];int count=0;
                for(int sample=0;sample<40;sample++){
                    q.clear();long begin=System.nanoTime();GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,queries[0]);if(rebuild)surface.reset();surface.prepare(program,q.terrain,center);GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                    GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,queries[1]);GL20.glUseProgram(q.emit);surface.bindEmitter(q.emit);bool(q.emit,"uSurfaceReady",true);q.propose(0,17143,8,center);GL15.glEndQuery(GL33.GL_TIME_ELAPSED);long end=System.nanoTime();
                    count=read(q.counter,16).getInt(0);var spawned=read(q.output,count*64);put(q.input,spawned);put(q.previous,bytes(16).putInt(0,count));put(q.counter,bytes(16));
                    long updateBegin=System.nanoTime();GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,queries[2]);q.runUpdate(.05f,count);GL15.glEndQuery(GL33.GL_TIME_ELAPSED);long updateEnd=System.nanoTime();
                    for(int i=0;i<3;i++){double ms=GL33.glGetQueryObjectui64(queries[i],GL15.GL_QUERY_RESULT)/1e6;if(sample>=10)sums[i]+=ms;}if(sample>=10)sums[3]+=(end-begin+updateEnd-updateBegin)/1e6;
                }
                check(q.terrain.uploaded()==uploaded,"gold benchmark uploaded collision data");String row=rebuild+","+count+","+sums[0]/30+","+sums[1]/30+","+sums[2]/30+","+sums[3]/30+","+(q.terrain.uploaded()-uploaded);rows.add(row);System.out.println("Gold emission "+row);
            }
        }finally{for(int query:queries)GL15.glDeleteQueries(query);deleteProgram(program);}
        var output=Path.of("build/benchmarks/allay-gold/emission.csv");Files.createDirectories(output.getParent());Files.write(output,rows);
    }}
    static void benchmarkHybridSurface()throws Exception{try(var q=new FeatherFixture(4096)){
        int kernel=compile("gold_surface.comp");int[] timers={GL15.glGenQueries(),GL15.glGenQueries()};var rows=new ArrayList<String>();
        rows.add("radius,scenario,frames,surface_bytes,upload_bytes,block_probes,cpu_capture_ms,cpu_submit_ms,surface_gpu_ms,emit_gpu_ms");
        var plane=new StormSurfaceCache.Source(){public int minY(){return -64;}public int startY(int x,int z,int top){return Math.min(top,64);}public int occupied(int x,int y,int z){return y<64?1:0;}};
        try{for(double radius:new double[]{16,32,Math.sqrt(2048)})try(var surface=new StormSurfaceGpu()){
            var center=new net.minecraft.world.phys.Vec3(0,128,0);surface.prepare(kernel,null,center,radius,16384);surface.cache().feedback(surface.cache().epoch,surfaceFeedback(surface,center,radius));
            for(String scenario:new String[]{"prewarm","stable","moving"}){
                long bytes=surface.uploaded(),probes=surface.cache().queries;double[] sums=new double[4];int frames=0;
                do{
                    if(scenario.equals("moving"))center=center.add(1,0,0);
                    long capture=System.nanoTime();surface.cache().capture(plane,200000);sums[0]+=(System.nanoTime()-capture)/1e6;
                    q.clear();long cpu=System.nanoTime();GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,timers[0]);surface.prepare(kernel,null,center,radius,16384);
                    boolean feedback=scenario.equals("moving")&&(frames&7)==0;if(feedback)surface.feedback(center,radius,true);GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                    GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,timers[1]);GL20.glUseProgram(q.emit);surface.bindEmitter(q.emit);bool(q.emit,"uSurfaceReady",true);q.propose(0,(int)Math.ceil(AllayFeatherLayout.proposals(4000,radius)*.05),(float)radius,center);GL15.glEndQuery(GL33.GL_TIME_ELAPSED);sums[1]+=(System.nanoTime()-cpu)/1e6;
                    for(int i=0;i<2;i++)sums[2+i]+=GL33.glGetQueryObjectui64(timers[i],GL15.GL_QUERY_RESULT)/1e6;
                    if(feedback){var all=read(surface.buffer(),StormSurfaceCache.TOTAL_BYTES);all.position(StormSurfaceGpu.BYTES).limit(StormSurfaceGpu.BYTES+StormSurfaceCache.FEEDBACK_BYTES);surface.cache().feedback(surface.cache().epoch,all.slice().order(ByteOrder.nativeOrder()));}
                    frames++;
                }while(scenario.equals("prewarm")?(surface.cache().pending()||surface.cache().hasPatches())&&frames<512:frames<64);
                if(scenario.equals("stable")){check(surface.uploaded()==bytes,"stable hybrid benchmark uploads");check(surface.cache().queries==probes,"stable hybrid benchmark queries");}
                String row=radius+","+scenario+","+frames+","+surface.bytes()+","+(surface.uploaded()-bytes)+","+(surface.cache().queries-probes)+","+sums[0]/frames+","+sums[1]/frames+","+sums[2]/frames+","+sums[3]/frames;rows.add(row);System.out.println("Hybrid surface "+row);
            }
        }}finally{for(int timer:timers)GL15.glDeleteQueries(timer);deleteProgram(kernel);}
        var output=Path.of("build/benchmarks/allay-gold/hybrid.csv");Files.createDirectories(output.getParent());Files.write(output,rows);
    }}
    static double radialCdf(double u){return u<=1?3*u*u/7:(6*u*u-2*u*u*u-1)/7;}
    static void radialDensity(){try(var q=new FeatherFixture(131072)){
        for(int x=-6;x<=6;x++)for(int z=-6;z<=6;z++){int[] bits=new int[128];for(int cell=0;cell<256;cell++)bits[cell>>>5]|=1<<(cell&31);q.pages.put(new StormCollisionPages.Section(x,0,z),StormCollisionPages.page(1000,bits));}publish(q);
        int positive=0;var center=new net.minecraft.world.phys.Vec3(0,10,0);
        for(float radius:new float[]{16,32,(float)Math.sqrt(2048)}){
            q.clear();int proposals=(int)Math.round(AllayFeatherLayout.proposals(1000,radius)*60);q.propose(proposals,0,radius,center);int count=read(q.counter,16).getInt(0);near(count/60.,AllayFeatherLayout.rate(1000,radius),AllayFeatherLayout.rate(1000,radius)*.018,"R squared total emission");
            var points=read(q.output,count*64);int[] bins=new int[8],ys=new int[8];int right=0;
            for(int i=0;i<count;i++){int b=i*64;double r=Math.hypot(points.getFloat(b),points.getFloat(b+8));check(r<2*radius,"storm birth horizontal bound");float y=points.getFloat(b+4);check(y>=2&&y<=10,"storm birth vertical bound");bins[Math.min(7,(int)(r/radius*4))]++;ys[Math.min(7,(int)(y-2))]++;if(points.getFloat(b)>=0)right++;}
            for(int bin=0;bin<8;bin++){near(bins[bin]/(double)count,radialCdf((bin+1)*.25)-radialCdf(bin*.25),.013,"radial density profile");near(ys[bin]/(double)count,.125,.013,"uniform eight block height");}
            if(radius==32)positive=right;
        }
        q.pages.keySet().removeIf(k->k.x()<0);publish(q);q.clear();q.propose((int)Math.round(AllayFeatherLayout.proposals(1000,32)*60),0,32,center);check(read(q.counter,16).getInt(0)==positive,"missing coverage redistributed quota into valid half");
    }}
    static void appearance()throws Exception{
        nativeAtlasPixels(ParticleAtlas.SPRITE,false);nativeAtlasPixels(ParticleAtlas.ALLAY,true);
        try(var atlas=new com.mojang.blaze3d.platform.NativeImage(ParticleAtlasLayout.ALLAY_WIDTH,ParticleAtlasLayout.ALLAY_HEIGHT,true)){
            int sentinel=0xff123456;atlas.fillRect(0,0,atlas.getWidth(),atlas.getHeight(),sentinel);
            for(int frame=0;frame<12;frame++)try(var stream=Files.newInputStream(Path.of("src/main/resources/assets/createmanaindustry/textures/particle/cherry_"+frame+".png"));var image=com.mojang.blaze3d.platform.NativeImage.read(stream)){
                AllayFeatherAppearance.bakeFrame(atlas,image,frame);
                for(int palette=0;palette<3;palette++){var rect=ParticleAtlasLayout.feather(frame+palette*12);
                for(int y=-1;y<=3;y++)for(int x=-1;x<=3;x++){
                    int original=image.getPixelRGBA(Math.clamp(x,0,2),Math.clamp(y,0,2));
                    int expected=AllayFeatherAppearance.recolor(original,palette),actual=atlas.getPixelRGBA(rect.x()+x,rect.y()+y);
                    check(actual==expected,"native palette pixels and edge gutter");check((actual>>>24)==(original>>>24),"original alpha footprint");
                }}
            }
            for(int y=0;y<atlas.getHeight();y++)for(int x=0;x<atlas.getWidth();x++)if(x<32||y<32)check(atlas.getPixelRGBA(x,y)==sentinel,"model/sword atlas region untouched");
        }
    }
    static void nativeAtlasPixels(ParticleAtlas owner,boolean model)throws Exception {
        int width=model?ParticleAtlasLayout.ALLAY_WIDTH:ParticleAtlasLayout.SPRITE_WIDTH,height=model?ParticleAtlasLayout.ALLAY_HEIGHT:ParticleAtlasLayout.SPRITE_HEIGHT;
        var names=ParticleAtlas.class.getDeclaredField("files");names.setAccessible(true);String[] files=(String[])names.get(owner);
        try(var canvas=new com.mojang.blaze3d.platform.NativeImage(width,height,true)){
            canvas.fillRect(0,0,width,height,0);
            for(int frame=0;frame<files.length;frame++)try(var stream=AllayFeatherGpuValidation.class.getResourceAsStream("/assets/createmanaindustry/textures/particle/"+files[frame]+".png");var image=com.mojang.blaze3d.platform.NativeImage.read(stream)){
                var rect=model?ParticleAtlasLayout.model(frame):ParticleAtlasLayout.sprite(frame);ParticleAtlasLayout.copy(canvas,image,rect);
                check(rect.width()==image.getWidth()&&rect.height()==image.getHeight(),"source resolution preserved");
                for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)check(canvas.getPixelRGBA(rect.x()+x,rect.y()+y)==image.getPixelRGBA(x,y),"native source RGB and alpha copied 1:1");
            }
            var pixels=bytes(width*height*4);for(int rgba:canvas.getPixelsRGBA())pixels.putInt(rgba);pixels.flip();
            int texture=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
            try{
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,width,height,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
                var actual=bytes(width*height*4);GL11.glGetTexImage(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,actual);
                for(int i=0;i<pixels.capacity();i++)check(actual.get(i)==pixels.get(i),"native atlas GPU pixel readback");
                if(model){
                    // Color each aligned material region to detect cross-material mip bleeding.
                    for(int frame=0;frame<7;frame++){var r=ParticleAtlasLayout.model(frame);canvas.fillRect(r.x(),r.y(),r.width(),r.height(),0xff000000|(frame+1)*24);}
                    canvas.fillRect(32,32,32,16,0xff00ff00);pixels.clear();for(int rgba:canvas.getPixelsRGBA())pixels.putInt(rgba);pixels.flip();
                    GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,0,width,height,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL12.GL_TEXTURE_MAX_LEVEL,ParticleAtlasLayout.ALLAY_MAX_MIP);GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
                    check(GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,4,GL11.GL_TEXTURE_WIDTH)==4&&GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,4,GL11.GL_TEXTURE_HEIGHT)==4,"compact mip dimensions");
                    var mip=bytes(4*4*4);GL11.glGetTexImage(GL11.GL_TEXTURE_2D,4,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,mip);
                    for(int frame=0;frame<7;frame++){var r=ParticleAtlasLayout.model(frame);check(mip.getInt(((r.y()/16)*4+r.x()/16)*4)==(0xff000000|(frame+1)*24),"allay/sword material isolation through mip 4");}
                    check(mip.getInt((2*4+2)*4)==0xff00ff00,"feather mip region does not sample swords");
                }
            }finally{GL11.glDeleteTextures(texture);}
        }
    }
    static void vertex(){
        int p=graphics("feather.vsh"),pool=buffer(bytes(64)),state=buffer(bytes(BYTES)),feedback=GL15.glGenBuffers();
        GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,feedback);GL15.glBufferData(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,6*24,GL15.GL_DYNAMIC_READ);GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,0,feedback);
        bind(1,pool);bind(20,state);GL20.glUseProgram(p);float[] matrix=new org.joml.Matrix4f().get(new float[16]);for(String n:new String[]{"ModelViewMat","ProjMat"})GL41.glProgramUniformMatrix4fv(p,GL20.glGetUniformLocation(p,n),false,matrix);
        vec(p,"uCamRight",1,0,0);vec(p,"uCamUp",0,1,0);GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
        for(int frame=0;frame<36;frame++)for(float scale:new float[]{1,2f/3})for(int ticks:new int[]{0,1,2,20,300,1199})for(int signs=0;signs<4;signs++){
            float v=(float)Math.toRadians((signs&1)==0?30:-30),a=(float)Math.toRadians((signs&2)==0?5:-5),t=ticks/20f;
            var b=one(0,0,0,t,false).putFloat(12,scale).putFloat(40,v).putFloat(44,a/2).putInt(56,(frame%12)|((frame/12)<<16));put(pool,b);
            GL30.glBeginTransformFeedback(GL11.GL_TRIANGLES);GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES,0,6,1);GL30.glEndTransformFeedback();GL42.glMemoryBarrier(GL42.GL_TRANSFORM_FEEDBACK_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
            var result=bytes(144);GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,feedback);GL15.glGetBufferSubData(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,0,result);
            double angle=0;for(int n=1;n<=ticks;n++)angle+=v/20.+a*n/400.;double half=scale==1?.075:.05;
            near(result.getFloat(0),half*(-Math.cos(angle)+Math.sin(angle)),.000008,"vertex size/rotation X");near(result.getFloat(4),half*(-Math.sin(angle)-Math.cos(angle)),.000008,"vertex size/rotation Y");
            var rect=ParticleAtlasLayout.feather(frame);
            near(result.getFloat(16),rect.x()/(double)ParticleAtlasLayout.ALLAY_WIDTH,1e-6,"native atlas frame U");near(result.getFloat(20),(rect.y()+3)/(double)ParticleAtlasLayout.ALLAY_HEIGHT,1e-6,"native atlas frame V");
        }
        GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);deleteProgram(p);for(int id:new int[]{pool,state,feedback})GL15.glDeleteBuffers(id);
    }
    static void spriteVertices(){
        int p=graphics("textured.vsh"),pool=buffer(bytes(64)),header=buffer(bytes(320)),order=buffer(bytes(4)),feedback=GL15.glGenBuffers();
        GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,feedback);GL15.glBufferData(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,144,GL15.GL_DYNAMIC_READ);GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,0,feedback);
        bind(1,pool);bind(5,header);bind(15,order);GL20.glUseProgram(p);intUniform(p,"uMode",1);f(p,"uFadeDist",128);
        float[] matrix=new org.joml.Matrix4f().get(new float[16]);for(String n:new String[]{"ModelViewMat","ProjMat"})GL41.glProgramUniformMatrix4fv(p,GL20.glGetUniformLocation(p,n),false,matrix);
        vec(p,"uCamRight",1,0,0);vec(p,"uCamUp",0,1,0);GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
        try{
            int[] qx={0,1,0,0,1,1},qy={0,0,1,1,0,1};
            for(int frame=0;frame<23;frame++){
                float[] emitter=EmitterPresets.CHERRY_LEAVES.packed();emitter[16*4+1]=1;emitter[17*4+3]=frame;put(header,floats(emitter));
                var particle=one(0,0,0,0,false).putFloat(12,1).putFloat(32,1).putFloat(36,1).putFloat(40,1).putFloat(44,1);put(pool,particle);
                GL30.glBeginTransformFeedback(GL11.GL_TRIANGLES);GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES,0,6,1);GL30.glEndTransformFeedback();GL42.glMemoryBarrier(GL42.GL_TRANSFORM_FEEDBACK_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
                var result=bytes(144);GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,feedback);GL15.glGetBufferSubData(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,0,result);var rect=ParticleAtlasLayout.sprite(frame);
                for(int vertex=0;vertex<6;vertex++){
                    near(result.getFloat(vertex*24), (qx[vertex]-.5)*.15,1e-6,"native sprite world width");near(result.getFloat(vertex*24+4),(qy[vertex]-.5)*.15,1e-6,"native sprite world height");
                    near(result.getFloat(vertex*24+16),(rect.x()+qx[vertex]*rect.width())/(double)ParticleAtlasLayout.SPRITE_WIDTH,1e-6,"native sprite U");
                    near(result.getFloat(vertex*24+20),(rect.y()+(1-qy[vertex])*rect.height())/(double)ParticleAtlasLayout.SPRITE_HEIGHT,1e-6,"native sprite V");
                }
            }
        }finally{GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);deleteProgram(p);for(int id:new int[]{pool,header,order,feedback})GL15.glDeleteBuffers(id);}
    }
    static void benchmark()throws Exception{
        var rows=new StringBuilder("count,groundFraction,updateMs,keygenMs,groundMs,cpuSubmitMs\n");
        for(int count:new int[]{0,60000,120000})for(float fraction:count==0?new float[]{0}:new float[]{0,.5f,1})try(var q=new FeatherFixture(131072)){
            var data=bytes(Math.max(1,count)*64);for(int i=0;i<count;i++){var particle=one((i%32)*.5f, i<count*fraction?1.003f:6, (i/32%32)*.5f,30,i<count*fraction);for(int j=0;j<64;j++)data.put(i*64+j,particle.get(j));}
            put(q.input,data);put(q.previous,bytes(16).putInt(0,count));double[] sums=new double[4];int[] queries={GL15.glGenQueries(),GL15.glGenQueries(),GL15.glGenQueries()};
            for(int frame=0;frame<30;frame++){
                long begin=System.nanoTime();q.clear();
                GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,queries[0]);q.runUpdate(.016666667f,count);GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,queries[1]);q.lists(count);GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,queries[2]);q.ground();GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                double cpu=(System.nanoTime()-begin)/1e6;
                for(int i=0;i<3;i++){double ms=GL33.glGetQueryObjectui64(queries[i],GL15.GL_QUERY_RESULT)/1e6;if(frame>=10)sums[i]+=ms;}
                if(frame>=10)sums[3]+=cpu;
            }
            for(int query:queries)GL15.glDeleteQueries(query);
            String row=count+","+fraction+","+sums[0]/20+","+sums[1]/20+","+sums[2]/20+","+sums[3]/20;rows.append(row).append('\n');System.out.println(row);
        }
        var path=Path.of("build/benchmarks/allay-feather/kernels.csv");Files.createDirectories(path.getParent());Files.writeString(path,rows.toString());
    }
    public static void main(String[] args)throws Exception{
        try(var errors=GLFWErrorCallback.createPrint(System.err)){
            runBenchmark=Arrays.asList(args).contains("--benchmark");errors.set();check(GLFW.glfwInit(),"GLFW init");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE,GLFW.GLFW_OPENGL_CORE_PROFILE);
            long window=GLFW.glfwCreateWindow(64,64,"Allay feather validation",0,0);check(window!=0,"GL context");
            try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();GL30.glBindVertexArray(GL30.glGenVertexArrays());System.out.println(GL11.glGetString(GL11.GL_RENDERER));
                com.mojang.blaze3d.systems.RenderSystem.initRenderThread();productionOwnership();try(var stress=new FeatherFixture(65536)){stress.modelImpact();}manualLifecycle();if(!Arrays.asList(args).contains("--manual-only")){appearance();behavior();paletteDistribution();surfaceAndGold();hybridSurface();surfaceSnapshots();manualGold();radialDensity();goldDrawing();vertex();spriteVertices();}if(runBenchmark){benchmark();benchmarkGoldEmission();benchmarkHybridSurface();}check(GL11.glGetError()==0,"GL errors");System.out.println("Allay feather GPU: "+checks+" assertions passed");
            }finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
        }
    }
}
