import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.IntStream;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.allaystorm.*;
import com.iridium126.createmanaindustry.client.particles.engine.*;
import com.iridium126.createmanaindustry.content.allaystorm.*;
import com.iridium126.createmanaindustry.content.allaystorm.network.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Independent columns through real production update/contact/carrier/ring kernels. No game client. */
public class StarfallColumnGpuValidation extends StarfallGpuValidation {
 static ClientboundStormColumnPacket column(int index){
  float x=1500+(index%8)*8-28,z=-2300+(index/8)*8-28;
  return new ClientboundStormColumnPacket(Level.OVERWORLD.location(),37,100+index,false,123,0,4,24,31.28f,70,x,64.02f,z,120,1500,-2300,64,IntStream.range(index*64,index*64+64).toArray());
 }
 static ClientboundStormWavePacket[] columns(){var result=new ClientboundStormWavePacket[68];for(int i=0;i<64;i++)result[4+i]=column(i).snapshot();return result;}
 static void retire(int... programs){GL20.glUseProgram(0);for(int p:programs){com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clearUniformProgram(p);GL20.glDeleteProgram(p);}}
 static void reference(){
  int p=compileText("column-reference",source("chunks/allay_storm.glsl")+"""
   layout(local_size_x=64) in;layout(std430,binding=10) buffer Output{vec4 rows[];} result;
   uniform float uAge;uniform uint uMode;void main(){uint member=gl_GlobalInvocationID.x;if(member>=4096u)return;
    uint a=cmiStarAssignment(float(member+1u)),rank=cmiStarRank(a),b=cmiStarBase(cmiStarSlot(a));
    vec4 t=cmiStarVec(b+4u);float duration=t.z+cmiColumnDelay(rank)-t.y;
    vec3 point=uMode==0u?cmiColumnBloom(b,rank,uAge):cmiColumnDescent(b,rank,t.y+duration*uAge);
    vec3 next=uMode==0u?cmiColumnBloom(b,rank,uAge+.01):cmiColumnDescent(b,rank,t.y+duration*uAge+.01);
    result.rows[member*2u]=vec4(point,float(cmiStarSlot(a)));result.rows[member*2u+1u]=vec4((next-point)/.01,float(rank));}
   """);
  try(var gpu=new StarfallGpuBuffers()){
   int out=buffer(bytes(4096*32));bind(10,out);gpu.rebuild(columns());gpu.prepare(1);gpu.bind(p,true);
   for(int mode=0;mode<2;mode++)for(float age:mode==0?new float[]{0,.1f,.4f,.75f,1.2f,1.499f,1.5f}:new float[]{0,.001f,.1f,.5f,.9f,.999f,1}){
    ui(p,"uMode",mode);
    f(p,"uAge",age);dispatch(p,64);var result=read(out,4096*32);
    for(int member=0;member<4096;member++){
     int slot=member/64,rank=member%64,o=member*32;var c=column(slot);
     Vec3 landing=new Vec3(c.x(),c.y(),c.z());
     var expected=mode==0?StarfallColumnRules.bloom(landing,rank,c.seed(),age):StarfallColumnRules.descent(new Vec3(c.x(),c.gatherY(),c.z()),landing,rank,c.seed(),age,c.assemble()-c.start(),c.touch()+StarfallColumnRules.delay(rank)-c.assemble());
     check(Math.abs(result.getFloat(o)-expected.x)<.001&&Math.abs(result.getFloat(o+4)-expected.y)<.001&&Math.abs(result.getFloat(o+8)-expected.z)<.001,"CPU/GPU column orbit/descent/parabola mode="+mode+" rank="+rank);
     check(result.getFloat(o+12)==slot+4&&result.getFloat(o+28)==rank,"slot >= 32 or rank aliased");
     float speed=0;for(int j=4;j<7;j++){float v=result.getFloat(o+j*4);check(Float.isFinite(v),"column tangent nonfinite");speed+=v*v;}check(speed<37,"column bloom exceeds six b/s");
    }
   }
   GL15.glDeleteBuffers(out);
  }finally{retire(p);}
 }
 static void production()throws Exception{
  int n=4096,update=compile("update.comp"),contact=compile("wavecontact.comp");
  try(var q=new Fixture(n,n,n);var gpu=new StarfallGpuBuffers();var collision=new StormCollisionGpu()){
   int output=buffer(bytes(n*64)),grid=buffer(bytes((ParticleBuffers.GRID_TABLE+n)*4)),damage=buffer(bytes(16)),players=buffer(bytes(16)),correction=buffer(bytes(n*32)),map=buffer(bytes(n*4));
   int meta=buffer(bytes(8*16)),hits=buffer(bytes(4+16384*24)),stamps=buffer(bytes(n*16));
   int texture=GL11.glGenTextures();GL13.glActiveTexture(GL13.GL_TEXTURE0);GL12.glBindTexture(GL12.GL_TEXTURE_3D,texture);
   GL11.glTexParameteri(GL12.GL_TEXTURE_3D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL12.GL_TEXTURE_3D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
   ByteBuffer voxels=bytes(48*32*48*8),metadata=bytes(8*16);
   for(int slice=0;slice<4;slice++){
    metadata.putFloat(slice*16,1460+(slice%2)*40).putFloat(slice*16+4,60).putFloat(slice*16+8,-2340+(slice/2)*40).putFloat(slice*16+12,1);
    for(int z=0;z<48;z++)for(int y=0;y<4;y++)for(int x=0;x<48;x++)voxels.put(((slice*48+z)*32+y)*48+x,(byte)255);
   }
   GL12.glTexImage3D(GL12.GL_TEXTURE_3D,0,GL30.GL_R8,48,32,48*8,0,GL11.GL_RED,GL11.GL_UNSIGNED_BYTE,voxels);put(meta,metadata);
   try{
    var data=bytes(n*64);for(int i=0;i<n;i++){
     var c=column(i/64);var foot=StarfallColumnRules.bloom(new Vec3(c.x(),c.y(),c.z()),i%64,c.seed(),0);
     particle(data,i,2,(float)foot.x,(float)foot.z,20);data.putFloat(i*64+4,(float)foot.y).putFloat(i*64+12,i+1).putFloat(i*64+56,AllayStormWaves.memberSeed(1,i));
    }
    q.build(data,n);var h=headers();int hb=2*ParticleBuffers.VEC4_PER_EMITTER*16;
    h.putFloat(hb+18*16+4,64).putFloat(hb+18*16+12,6).putFloat(hb+19*16,1500).putFloat(hb+19*16+4,128).putFloat(hb+19*16+8,-2300).putFloat(hb+7*16+4,1);put(q.emit,h);put(q.previous,bytes(16).putInt(0,n));
    gpu.rebuild(columns());for(int s=4;s<68;s++)gpu.columnReady(s,true);gpu.prepare(1);gpu.bind(update,true);
    ui(update,"uCapacity",n);ui(update,"uKillEmit",-1);f(update,"uDt",.05f);f(update,"uTimeSec",25);GL41.glProgramUniform1i(update,GL20.glGetUniformLocation(update,"uCollisionOn"),1);
    Runnable bindings=()->{bind(0,q.pool);bind(1,output);bind(3,q.counter);bind(5,q.emit);bind(11,meta);bind(13,grid);bind(14,q.previous);bind(16,damage);bind(18,players);bind(19,correction);bind(21,map);};
    bindings.run();put(q.counter,bytes(16));dispatch(update,64);
    var cold=read(output,n*64);for(int i=0;i<n;i++)check(Float.isFinite(cold.getFloat(i*64)),"column spawn nonfinite");
    var gathered=bytes(StarfallGpuBuffers.STATE_WORDS*4);
    for(int member=0;member<n;member++){int r=(StarfallGpuBuffers.COLUMN_STATE+member*16)*4;gathered.putInt(r,100+member/64).putFloat(r+4,25).putFloat(r+8,4).putFloat(r+12,-1);int arc=(StarfallGpuBuffers.COLUMN_ARC+member*StarfallGpuBuffers.COLUMN_ARC_WORDS)*4;gathered.putInt(arc+43*4,4).putFloat(arc+45*4,(float)StarfallColumnRules.root(member%64)).putFloat(arc+46*4,(float)(((member%64*27)&63)*Math.PI/32+123*.0001f));}
    put(GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,27),gathered);put(q.counter,bytes(16));dispatch(update,64);
    // Touchdown is latched immediately, but the exact transition frame has no elapsed bloom motion.
    var touchFrame=read(output,n*64);for(int i=0;i<n;i++)check(touchFrame.getFloat(i*64+28)<-1,"Touchdown inserted a stationary frame");
    gpu.commit();put(q.pool,touchFrame);gpu.prepare(2);gpu.bind(update,true);bindings.run();put(q.counter,bytes(16));f(update,"uTimeSec",25.05f);dispatch(update,64);
    var result=read(output,n*64);check(read(q.counter,16).getInt(0)==n,"column update lost members");
    for(int i=0;i<n;i++){int o=i*64;check(result.getFloat(o+28)<=-1&&result.getFloat(o+28)>-2.5,"actual floor did not enable bloom");for(int j=0;j<7;j++)check(Float.isFinite(result.getFloat(o+j*4)),"column update nonfinite");}
    // Report against a different local player near a column in the upper half of the slot domain.
    gpu.bind(contact,true);bind(1,output);bind(3,q.counter);bind(5,q.emit);bind(22,hits);bind(30,stamps);ui(contact,"uLocalWaveMask",16);f(contact,"uTimeSec",25.05f);f(contact,"uDt",.05f);
    var c=column(63);GL41.glProgramUniform3f(contact,GL20.glGetUniformLocation(contact,"uLocalPlayerPos"),c.x(),c.y()+.5f,c.z());
    int stateBuffer=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28);var state=read(stateBuffer,StarfallGpuBuffers.STATE_WORDS*4);
    state.putInt((262528+63)*4,127);put(stateBuffer,state);
    state.putInt((262528+63)*4,128);put(stateBuffer,state);dispatch(contact,64);int count=read(hits,4).getInt(0),contactCount=0,sounds=0;var events=read(hits,4+count*24);
    for(int event=0;event<count;event++){if(events.getFloat(4+event*24)<0)sounds++;else contactCount++;}
    check(contactCount>0&&contactCount<=64&&sounds>0&&sounds<=64,"regional bloom lost high-slot contacts or sound merge");
    put(hits,bytes(4+16384*24));dispatch(contact,64);check(read(hits,4).getInt(0)==0,"duplicate column hit");
    // Same roster, new attack ID: stale phase/touch state must never authorize it.
    var replacement=columns();var old=column(63);replacement[67]=new ClientboundStormColumnPacket(old.dimension(),37,999,false,old.seed(),0,4,24,31.28f,70,old.x(),old.y(),old.z(),120,1500,-2300,64,old.members()).snapshot();
    gpu.rebuild(replacement);gpu.prepare(2);gpu.bind(contact,true);put(stamps,bytes(n*16));dispatch(contact,64);check(read(hits,4).getInt(0)==0,"old touchdown authorized new column");
    // No collision data: nominal touchdown alone never starts a bloom or equips a sword.
    gpu.bind(update,true);bindings.run();put(q.counter,bytes(16));GL41.glProgramUniform1i(update,GL20.glGetUniformLocation(update,"uCollisionOn"),0);dispatch(update,64);
    var blocked=read(output,n*64);for(int i=0;i<n;i++){if(Math.round(blocked.getFloat(i*64+12))>4032)check(blocked.getFloat(i*64+28)==0,"unconfirmed touchdown equipped sword");}
    // The carrier list consumes the same per-particle marker as native and shadow rendering.
    gpu.commit();gpu.bind(q.keygen,false);q.build(result,n);check(read(q.commands,ParticleBuffers.INDIRECT_UINTS*4).getInt(ParticleBuffers.IDX_CNT_CARRIER*4)==n,"bloom carrier partition mismatch");
    try(var shadow=new ModelShadowGpu(n,ParticleBuffers.CARRIER_CAP+n,StarfallColumnGpuValidation::source)){
     shadow.starfall(gpu.committedBuffer());var culling=new com.iridium126.createmanaindustry.client.packages.render.PackageDrawCulling();culling.distance=-1;culling.safe=-1;
     shadow.prepare(q.pool,q.emit,q.counter,culling,1500,64,-2300,.35f,.6f,10,25,new float[16],new float[16],new float[4],q.pop,7);
     check(read(shadow.commands(),(ParticleBuffers.INDIRECT_UINTS+4)*4).getInt(ParticleBuffers.IDX_CNT_CARRIER*4)==n,"high column slots lost shadow swords");
    }
    for(int i=0;i<n;i++)result.putFloat(i*64+28,0);q.build(result,n);check(read(q.commands,ParticleBuffers.INDIRECT_UINTS*4).getInt(ParticleBuffers.IDX_CNT_CARRIER*4)==0,"descent retained swords");
    // Real production-kernel microbenchmark at 64 columns / 4096 members. Repeated fixed input, not a full game frame.
    gpu.rebuild(columns());gpu.prepare(3);gpu.bind(update,true);put(GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,27),gathered);put(q.pool,data);bindings.run();GL41.glProgramUniform1i(update,GL20.glGetUniformLocation(update,"uCollisionOn"),1);
    int query=GL15.glGenQueries();List<String> rows=new ArrayList<>();rows.add("columns,members,run,update_gpu_p50_ms,update_gpu_p95_ms,cpu_submit_p50_ms,cpu_submit_p95_ms,state_domain_bytes");
    for(int run=0;run<3;run++){double[] times=new double[120],cpu=new double[120];for(int sample=-40;sample<120;sample++){
      put(q.counter,bytes(16));long begin=System.nanoTime();GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,query);GL20.glUseProgram(update);GL43.glDispatchCompute(64,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);GL15.glEndQuery(GL33.GL_TIME_ELAPSED);long end=System.nanoTime();
      double elapsed=GL33.glGetQueryObjectui64(query,GL15.GL_QUERY_RESULT)/1e6;if(sample>=0){times[sample]=elapsed;cpu[sample]=(end-begin)/1e6;}
     }rows.add("64,4096,"+run+","+p(times,.5)+","+p(times,.95)+","+p(cpu,.5)+","+p(cpu,.95)+","+gpu.bytes());}
    check(read(q.counter,16).getInt(0)==n,"benchmark did not process all 4096 members");
    // Isolate one-time arc construction from the steady bloom kernel above.
    var initial=bytes(n*64);for(int i=0;i<n;i++){var coldColumn=column(i/64);var top=StarfallColumnRules.gather(new Vec3(coldColumn.x(),coldColumn.gatherY(),coldColumn.z()),i%64,coldColumn.seed());particle(initial,i,2,(float)top.x,(float)top.z,20);initial.putFloat(i*64+4,(float)top.y).putFloat(i*64+12,i+1).putFloat(i*64+16,6).putFloat(i*64+56,AllayStormWaves.memberSeed(1,i));}
    put(q.pool,initial);put(GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,27),bytes(StarfallGpuBuffers.STATE_WORDS*4));double[] coldTimes=new double[120];
    for(int sample=-40;sample<120;sample++){put(q.counter,bytes(16));GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,query);dispatch(update,64);GL15.glEndQuery(GL33.GL_TIME_ELAPSED);double elapsed=GL33.glGetQueryObjectui64(query,GL15.GL_QUERY_RESULT)/1e6;if(sample>=0)coldTimes[sample]=elapsed;}
    var initialization=List.of("columns,members,arc_init_gpu_p50_ms,arc_init_gpu_p95_ms","64,4096,"+p(coldTimes,.5)+","+p(coldTimes,.95));
    Files.write(Path.of(System.getProperty("cmi.benchmark.output","build/benchmarks/current"),"starfall-arc-initialization.csv"),initialization);initialization.forEach(System.out::println);
    // Follow a complete column through actual gathering, landing, bloom and return using both state banks.
    Map<StormCollisionPages.Section,StormCollisionPages.Page> terrain=new LinkedHashMap<>();
    for(int sy=3;sy<=9;sy++)for(int sz=-147;sz<=-141;sz++)for(int sx=90;sx<=97;sx++){
     int[] bits=new int[128];for(int cell=0;cell<4096;cell++){int wx=sx*16+(cell&15),wy=sy*16+(cell>>>8);int floor=wx>1500?65:63;if(wy<=floor||(wx==1503&&wy<72))bits[cell>>>5]|=1<<(cell&31);}
     terrain.put(new StormCollisionPages.Section(sx,sy,sz),StormCollisionPages.page(1,bits));
    }
    collision.offer(terrain);for(int i=0;i<1000&&!collision.settled();i++){collision.pump(131072,50_000_000);GL11.glFlush();}
    for(int frameMode=0;frameMode<6;frameMode++){
    bool(update,"uStormCollisionEnabled",frameMode==5);
    var life=new ClientboundStormColumnPacket(Level.OVERWORLD.location(),37,444+frameMode,false,1,0,8,18,25.28f,40,1500,64.02f,-2300,80,1500,-2300,64,IntStream.range(0,64).toArray());
    var lifePackets=new ClientboundStormWavePacket[68];lifePackets[4]=life.snapshot();gpu.rebuild(lifePackets);gpu.columnReady(4,true);
    var moving=bytes(64*64);for(int member=0;member<64;member++){
        var point=StarfallColumnRules.gather(new Vec3(1500,80,-2300),member,1).add(5,3,-2);
        particle(moving,member,2,(float)point.x,(float)point.z,20);moving.putFloat(member*64+4,(float)point.y).putFloat(member*64+12,member+1).putFloat(member*64+16,.5f).putFloat(member*64+24,.5f).putFloat(member*64+56,AllayStormWaves.memberSeed(1,member));
    }
    var bloomed=new BitSet();var completed=new BitSet();double[] firstTouch=new double[64];Arrays.fill(firstTouch,-1);put(q.previous,bytes(16).putInt(0,64));
    double descentMin=Double.POSITIVE_INFINITY,descentMax=0;
    double renderTime=0;float[] frameSteps=frameMode>=4?new float[]{1/144f,1/60f,.012f,.028f}:new float[]{1f/new int[]{30,60,144,240}[frameMode]};
    for(int frame=0;renderTime<=41;frame++){
        float frameDt=frameSteps[frame%frameSteps.length];renderTime+=frameDt;f(update,"uDt",frameDt);
        put(q.pool,moving);put(q.counter,bytes(16));gpu.prepare(10+frameMode*20000+frame);gpu.bind(update,true);bindings.run();if(frameMode==5)collision.bind(update);f(update,"uTimeSec",(float)renderTime);dispatch(update,1);
        check(read(q.counter,16).getInt(0)==64,"lifecycle lost a member");moving=read(output,64*64);
        for(int row=0;row<64;row++){
            int o=row*64,member=Math.round(moving.getFloat(o+12))-1;float speed=0;
            for(int j=4;j<7;j++){float v=moving.getFloat(o+j*4);speed+=v*v;}check(speed<=36.01,"lifecycle exceeded six b/s");
            if(frameMode<5&&renderTime>9&&renderTime<10){double speedValue=Math.sqrt(speed);descentMin=Math.min(descentMin,speedValue);descentMax=Math.max(descentMax,speedValue);check(speedValue>5.99,"arc speed drifted at frame mode="+frameMode+" speed="+speedValue);}
            check(moving.getFloat(o)>1400&&moving.getFloat(o+8)<-2200,"column travelled toward origin");
            float marker=moving.getFloat(o+28);
            if(marker<=-1&&marker>-2.5){check(!completed.get(member),"Bloom phase flickered back on");bloomed.set(member);if(firstTouch[member]<0)firstTouch[member]=renderTime;double r=Math.hypot(moving.getFloat(o)-1500,moving.getFloat(o+8)+2300);
                check(r<=4.001&&(frameMode==5||Math.abs(moving.getFloat(o+4)-64.02-StarfallColumnRules.height(r))<.3),"actual bloom left parabola");}
            else if(bloomed.get(member))completed.set(member);
        }
        gpu.commit();if(frameMode==5)collision.submitted();
    }
    check(frameMode==5?bloomed.cardinality()>0:bloomed.cardinality()==64,"complete lifecycle did not land all members: "+bloomed.cardinality());
    check(completed.cardinality()==bloomed.cardinality(),"members failed to leave bloom at frame mode "+frameMode);
    System.out.println("Column full lifecycle frame mode="+frameMode+" landed="+bloomed.cardinality()+" completed="+completed.cardinality()+" free_descent_speed="+descentMin+".."+descentMax);
    }
    GL15.glDeleteQueries(query);Files.write(Path.of(System.getProperty("cmi.benchmark.output","build/benchmarks/current"),"starfall-64-columns.csv"),rows);rows.forEach(System.out::println);
   }finally{for(int b:new int[]{output,grid,damage,players,correction,map,meta,hits,stamps})GL15.glDeleteBuffers(b);com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames.deleteTexture(texture);}
  }finally{retire(update,contact);}
 }
 static void columnRings(){
  int reserve=compile("starfall_ring_reserve.comp"),emit=compile("emit.comp"),n=64*128;
  int counter=buffer(bytes(16)),pool=buffer(bytes(n*64)),cmd=buffer(bytes(64*48));
  var header=bytes(320);header.putFloat(17*16+4,7).putFloat(5*16+8,.135f).putFloat(5*16+12,.135f).putFloat(6*16,1);int h=buffer(header);
  try(var gpu=new StarfallGpuBuffers()){
   gpu.rebuild(columns());gpu.prepare(1);gpu.bind(reserve,true);var commands=bytes(64*48);
   for(int i=0;i<64;i++)commands.putFloat(i*48+12,128).putFloat(i*48+24,i*128).putFloat(i*48+32,100+i).putFloat(i*48+36,4+i);
   put(cmd,commands);bind(3,counter);bind(4,cmd);bind(5,h);ui(reserve,"uEmitCount",64);ui(reserve,"uCapacity",n);f(reserve,"uTimeSec",1);dispatch(reserve,1);check(read(counter,16).getInt(0)==n,"64 circles failed whole reservation");
   gpu.bind(emit,true);bind(1,pool);ui(emit,"uCapacity",n);ui(emit,"uTotalSpawn",n);ui(emit,"uEmitCount",64);f(emit,"uTimeSec",1);dispatch(emit,n/64);
   var particles=read(pool,n*64);for(int i=0;i<n;i++){
    int o=i*64;uintTag(particles.getInt(o+56),i/128);
    var c=column(i/128);check(Math.abs(Math.hypot(particles.getFloat(o)-c.x(),particles.getFloat(o+8)-c.z())-4)<.001,"column circle radius");
    check(Math.abs(particles.getFloat(o+4)-c.y()-.18)<.001,"column circle height");float size=particles.getFloat(o+12)*.135f;check(size>=.09&&size<.18,"column marker not half size");
   }
   try(var census=new Fixture(n,0,0)){
    put(census.emit,header);gpu.bind(census.reset,true);gpu.bind(census.keygen,true);census.build(particles,n);
    int state=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28);var counts=read(state,StarfallGpuBuffers.STATE_WORDS*4);
    for(int slot=0;slot<64;slot++)check(counts.getInt((262528+slot)*4)==128,"high-slot circle census");
    census.build(particles,n-1);check(read(state,StarfallGpuBuffers.STATE_WORDS*4).getInt((262528+63)*4)==127,"partial last-column census");
    com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clearUniformProgram(census.reset);com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clearUniformProgram(census.keygen);com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clearUniformProgram(census.prepare);
   }
  }finally{for(int id:new int[]{counter,pool,cmd,h})GL15.glDeleteBuffers(id);retire(reserve,emit);}
 }
 static void uintTag(int tag,int slot){check((tag&0x80000000)!=0&&(tag&63)==slot&&((tag&0x3fffffff)>>>6)==100+slot,"column circle feedback identity alias");}
 public static void main(String[] args)throws Exception{
  GLFWErrorCallback.createPrint(System.err).set();check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(128,128,"Column GPU validation",0,0);
  try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();System.out.println(GL11.glGetString(GL11.GL_RENDERER));reference();columnRings();production();check(GL11.glGetError()==0,"final column GL state");System.out.println("Starfall column GPU: "+checks+" assertions passed");}
  finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
 }
}
