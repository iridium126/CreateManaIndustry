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
            var h=bytes(960);float[] f=AllayFeatherSpecs.FEATHER.packed(),s=AllayFeatherSpecs.SPARK.packed();h.asFloatBuffer().put(f).put(s).put(EmitterPresets.CHERRY_LEAVES.packed());put(header,h);
            update=compile("update.comp");key=compile("keygen.comp");tools=compile("feather_tools.comp");emit=compile("feather_emit.comp");
            for(int p:new int[]{update,key,tools,emit}){bool(p,"uFeatherEnabled",true);bool(p,"uStormCollisionEnabled",true);ui(p,"uCapacity",cap);ui(p,"uFeatherSparkEmitter",1);}
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
        void emit(int n,boolean inside){
            bind(1,output);bind(3,counter);range();terrain.bind(emit);ui(emit,"uCount",n);vec(emit,"uPlayer",0,2,0);vec(emit,"uCamPos",0,2,0);f(emit,"uTop",30);f(emit,"uSeed",1729);
            float[] planes={1,0,0,1,-1,0,0,1,0,1,0,1,0,-1,0,1,0,0,1,1,0,0,-1,1};if(inside)for(int i=3;i<24;i+=4)planes[i]=1000;
            GL41.glProgramUniform4fv(emit,GL20.glGetUniformLocation(emit,"uFrustum"),planes);dispatch(emit,(n+63)/64);
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
            check(count==0,"manual feather and dissolution expire");
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
        q.emit(256,false);check(read(q.counter,16).getInt(0)==256,"offscreen rain generation");var born=read(q.output,256*64);boolean small=false,large=false;
        for(int i=0;i<256;i++){int b=i*64;double r=Math.hypot(born.getFloat(b),born.getFloat(b+8));check(r>=5.999&&r<=24.001,"spawn ring");check(born.getFloat(b+4)>=6&&born.getFloat(b+4)<=18,"spawn height");check(born.getFloat(b+52)==60,"birth life");small|=born.getFloat(b+12)<.9;large|=born.getFloat(b+12)>.9;check((born.getInt(b+56)&15)<12,"sprite selection");}
        check(small&&large,"both cherry size classes");q.clear();q.emit(64,true);check(read(q.counter,16).getInt(0)==0,"inside-frustum births rejected");
        q.clear();q.word(4,MAX_FEATHERS);q.emit(16,false);check(read(q.counter,16).getInt(0)==0,"feather cap");
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
        q.clear();q.simulate(one(2,.5f,2,60,true),1,.05f);check(read(q.counter,16).getInt(0)==12,"single dissolution burst");var spark=read(q.output,12*64);
        for(int i=0;i<12;i++){int b=i*64;check(spark.getInt(b+60)==1,"spark emitter");check(spark.getFloat(b+20)>0,"upward spark");for(int j=32;j<=44;j+=4)near(spark.getFloat(b+j),1,0,"white spark");check(spark.getFloat(b+52)>=.8&&spark.getFloat(b+52)<=1.6,"spark lifetime");}
        q.clear();q.simulate(spark,12,.05f);check(read(q.counter,16).getInt(0)==12,"sparks do not chain or collide inside floor");check(read(q.output,64).getFloat(4)>.5,"sparks pass upward through floor");
        q.clear();q.word(5,MAX_SPARKS);q.simulate(one(2,1,2,60,true),1,.05f);check(read(q.counter,16).getInt(0)==0,"spark capacity saturation");
        q.clear();q.ground();check(q.metadata().getInt(32)==0,"zero ground yields zero workgroups");
        q.clear();q.requestBatch(4096,true);check(q.metadata().getInt(88)==1,"GPU section requests deduplicate concurrent identical keys");
        q.clear();q.word(REQUESTS+MAX_REQUESTS*4,0x12345678);q.requestBatch(8192,false);
        check(q.metadata().getInt(88)>=4096,"bounded request overflow exercised");var requested=bytes(MAX_REQUESTS*16+4);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,q.grid);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,(q.prefix+REQUESTS)*4L,requested);
        var unique=new HashSet<String>();for(int i=0;i<MAX_REQUESTS;i++){int b=i*16;check(requested.getInt(b+12)==1,"retained request initialized");check(unique.add(requested.getInt(b)+":"+requested.getInt(b+4)+":"+requested.getInt(b+8)),"retained requests unique");}
        check(requested.getInt(MAX_REQUESTS*16)==0x12345678,"request overflow does not write beyond retained capacity");
    }}
    static void appearance()throws Exception{
        nativeAtlasPixels(ParticleAtlas.SPRITE,false);nativeAtlasPixels(ParticleAtlas.ALLAY,true);
        try(var atlas=new com.mojang.blaze3d.platform.NativeImage(ParticleAtlasLayout.ALLAY_WIDTH,ParticleAtlasLayout.ALLAY_HEIGHT,true)){
            int sentinel=0xff123456;atlas.fillRect(0,0,atlas.getWidth(),atlas.getHeight(),sentinel);
            for(int frame=0;frame<12;frame++)try(var stream=Files.newInputStream(Path.of("src/main/resources/assets/createmanaindustry/textures/particle/cherry_"+frame+".png"));var image=com.mojang.blaze3d.platform.NativeImage.read(stream)){
                AllayFeatherAppearance.bakeFrame(atlas,image,frame);
                var rect=ParticleAtlasLayout.feather(frame);
                for(int y=-1;y<=3;y++)for(int x=-1;x<=3;x++){
                    int original=image.getPixelRGBA(Math.clamp(x,0,2),Math.clamp(y,0,2));
                    int expected=AllayFeatherAppearance.recolor(original),actual=atlas.getPixelRGBA(rect.x()+x,rect.y()+y);
                    check(actual==expected,"native palette pixels and edge gutter");check((actual>>>24)==(original>>>24),"original alpha footprint");
                }
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
                    check(GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,4,GL11.GL_TEXTURE_WIDTH)==4&&GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,4,GL11.GL_TEXTURE_HEIGHT)==3,"compact mip dimensions");
                    var mip=bytes(4*3*4);GL11.glGetTexImage(GL11.GL_TEXTURE_2D,4,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,mip);
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
        for(int frame=0;frame<12;frame++)for(float scale:new float[]{1,2f/3})for(int ticks:new int[]{0,1,2,20,300,1199})for(int signs=0;signs<4;signs++){
            float v=(float)Math.toRadians((signs&1)==0?30:-30),a=(float)Math.toRadians((signs&2)==0?5:-5),t=ticks/20f;
            var b=one(0,0,0,t,false).putFloat(12,scale).putFloat(40,v).putFloat(44,a/2).putInt(56,frame);put(pool,b);
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
            errors.set();check(GLFW.glfwInit(),"GLFW init");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE,GLFW.GLFW_OPENGL_CORE_PROFILE);
            long window=GLFW.glfwCreateWindow(64,64,"Allay feather validation",0,0);check(window!=0,"GL context");
            try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();GL30.glBindVertexArray(GL30.glGenVertexArrays());System.out.println(GL11.glGetString(GL11.GL_RENDERER));
                com.mojang.blaze3d.systems.RenderSystem.initRenderThread();productionOwnership();try(var stress=new FeatherFixture(65536)){stress.modelImpact();}manualLifecycle();if(!Arrays.asList(args).contains("--manual-only")){appearance();behavior();vertex();spriteVertices();}if(Arrays.asList(args).contains("--benchmark"))benchmark();check(GL11.glGetError()==0,"GL errors");System.out.println("Allay feather GPU: "+checks+" assertions passed");
            }finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
        }
    }
}
