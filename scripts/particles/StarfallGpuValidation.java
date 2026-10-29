import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.joml.Matrix4f;
import com.iridium126.createmanaindustry.client.particles.engine.*;
import com.iridium126.createmanaindustry.client.allaystorm.*;
import com.iridium126.createmanaindustry.content.allaystorm.*;
import com.iridium126.createmanaindustry.content.allaystorm.network.*;
/** Actual production kernels, bank lifecycle and framebuffer checks, without a running game. */
public class StarfallGpuValidation extends ModelPopulationGpuValidation {
 static ClientboundStormWavePacket packet(int count,int id,int event,float abort){int per=512,columns=Math.max(1,(count+per-1)/per);int[] members=new int[count];for(int i=0;i<count;i++)members[i]=i;
  float[] land=new float[columns*4];for(int i=0;i<columns;i++){land[i*4+1]=.5f;land[i*4+3]=3.5f;}
  return new ClientboundStormWavePacket(id,37,event,1,1,5,columns,per,0,4,event==3?abort:10,60,0,12,0,4,members,new float[]{0,12,0,0,10.5f,0},land);
 }
 static void stage(StarfallGpuBuffers gpu,ClientboundStormWavePacket p,int program){gpu.rebuild(new ClientboundStormWavePacket[]{p,null,null,null});gpu.prepare(1);gpu.bind(program,true);}



 static void contacts(){int p=compile("wavecontact.comp");try(var gpu=new StarfallGpuBuffers()){
  for(int n:new int[]{0,1,63,64,65}){int capacity=Math.max(1,n);var particles=bytes(capacity*64);for(int i=0;i<n;i++){particle(particles,i,2,0,0,20);particles.putFloat(i*64+4,.5f).putFloat(i*64+12,i+1);}
   int pool=buffer(particles),counter=buffer(bytes(16).putInt(0,n)),header=buffer(headers()),out=buffer(bytes(4+16384*24)),stamps=buffer(bytes(capacity*16));bind(1,pool);bind(3,counter);bind(5,header);bind(22,out);bind(30,stamps);
   stage(gpu,packet(n,17,2,0),p);int stateBuffer=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28);var phaseState=bytes(StarfallGpuBuffers.DOME_JOIN*4);for(int i=0;i<n;i++)phaseState.putInt(i*8,17).putInt(i*8+4,4);put(stateBuffer,phaseState);GL41.glProgramUniform4fv(p,GL20.glGetUniformLocation(p,"uWave"),new float[]{1,1,0,60,0,0,0,0,0,0,0,0,0,0,0,0});GL41.glProgramUniform4fv(p,GL20.glGetUniformLocation(p,"uWaveTarget"),new float[]{0,.5f,0,17,0,0,0,0,0,0,0,0,0,0,0,0});GL41.glProgramUniform3f(p,GL20.glGetUniformLocation(p,"uLocalPlayerPos"),0,.5f,0);
   check(GL20.glGetUniformi(p,GL20.glGetUniformLocation(p,"uStarfallEnabled"))==1,"starfall enable uniform not set");f(p,"uTimeSec",10.5f);dispatch(p,Math.max(1,(n+63)/64));check(read(out,4).getInt(0)==0,"warning damages player");f(p,"uTimeSec",12);phaseState.putInt(262400*4,0);put(stateBuffer,phaseState);dispatch(p,Math.max(1,(n+63)/64));check(read(out,4).getInt(0)==n,"descent lost contacts");if(n>0)check(read(out,28).getFloat(24)==12,"missing sample time");
   put(out,bytes(4+16384*24));dispatch(p,Math.max(1,(n+63)/64));check(read(out,4).getInt(0)==0,"duplicate contact");
   // Same identities can attack in the next wave, without a permanent completion bit.
   stage(gpu,packet(n,18,2,0),p);GL41.glProgramUniform4fv(p,GL20.glGetUniformLocation(p,"uWaveTarget"),new float[]{0,.5f,0,18,0,0,0,0,0,0,0,0,0,0,0,0});dispatch(p,Math.max(1,(n+63)/64));check(read(out,4).getInt(0)==0,"old phase accepted by new wave");for(int i=0;i<n;i++)phaseState.putInt(i*8,18);put(stateBuffer,phaseState);dispatch(p,Math.max(1,(n+63)/64));check(read(out,4).getInt(0)==n,"next wave suppressed");
   for(int id:new int[]{pool,counter,header,out,stamps})GL15.glDeleteBuffers(id);
  }
 }finally{{com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clearUniformProgram(p);deleteProgram(p);}}}
 static int compileText(String name,String text){int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);GL20.glShaderSource(shader,"#version 450 core\n"+ParticlePrograms.commonPrelude()+text);GL20.glCompileShader(shader);check(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)!=0,name+GL20.glGetShaderInfoLog(shader));int p=GL20.glCreateProgram();GL20.glAttachShader(p,shader);GL20.glLinkProgram(p);check(GL20.glGetProgrami(p,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(p));GL20.glDeleteShader(shader);return p;}
 static int compileDomeVariant(String label,java.util.function.UnaryOperator<String> transform){
  int control=compileText(label+" control",transform.apply(source("dome_contact_control.comp"))),solve=compileText(label+" solve",transform.apply(source("dome_contacts.comp"))),apply=compileText(label+" apply",transform.apply(source("dome_contact_apply.comp"))),audit=compileText(label+" audit",transform.apply(source("dome_contact_audit.comp")));
  DomeBodyContacts.register(new DomeBodyContacts.Programs(control,solve,apply,audit));return solve;
 }
 static void formations(){String nav=source("chunks/storm_navigation.glsl");nav=nav.substring(nav.indexOf("vec3 cmiStarRoute"));int p=compileText("formation",source("chunks/allay_storm.glsl")+nav+"\nlayout(local_size_x=64) in;layout(std430,binding=10) buffer Output{vec4 rows[];} result;uniform uint uCount;void main(){uint i=gl_GlobalInvocationID.x;if(i>=uCount)return;vec3 target,velocity;uint phase=0u;bool engaged=cmiStarSteering(float(i+1u),float(i)*.0001,vec3(0),vec3(99,0,0),6.,target,velocity,phase);result.rows[i*2u]=vec4(target,float(phase));result.rows[i*2u+1u]=vec4(velocity,engaged?1.:0.);}");
  try(var gpu=new StarfallGpuBuffers()){for(int n:new int[]{1,63,64,65,8192,16384}){int out=buffer(bytes((n+1)*32));bind(10,out);stage(gpu,packet(n,1,2,0),p);ui(p,"uCount",n+1);
   for(float time:new float[]{0,5,10.5f,12,15,17}){f(p,"uTimeSec",time);dispatch(p,(n+64)/64);var result=read(out,(n+1)*32);for(int i=0;i<n;i++){for(int j=0;j<7;j++)check(Float.isFinite(result.getFloat(i*32+j*4)),"nonfinite formation");float vx=result.getFloat(i*32+16),vy=result.getFloat(i*32+20),vz=result.getFloat(i*32+24);check(vx*vx+vy*vy+vz*vz<=36.001,"formation velocity cap");if(i==0){int expected=time==0?1:time==5?2:time==10.5?3:time==12?4:time==15?5:6;check(result.getFloat(12)==expected,"formation phase");}}
    // Direct-call contract: rejected roster claims always define all out values.
    int fallback=n*32;check(result.getFloat(fallback)==99&&result.getFloat(fallback+4)==0&&result.getFloat(fallback+8)==0,"unassigned target fallback");
    for(int word=3;word<8;word++)check(result.getFloat(fallback+word*4)==0,"unassigned velocity/phase/engagement fallback");
   }
   GL15.glDeleteBuffers(out);
  }}finally{com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clearUniformProgram(p);deleteProgram(p);}
  try(var q=new Fixture(20000,20000,20000);var gpu=new StarfallGpuBuffers();var shadow=new ModelShadowGpu(20000,36384,StarfallGpuValidation::source)){
   var data=bytes(20000*64);for(int i=0;i<20000;i++){particle(data,i,2,0,0,20);data.putFloat(i*64+12,i+1);}q.build(data,20000);
   // An upper-size fixture uses five 4096-member columns, preserving the same exact roster format.
   var original=packet(20000,1,2,0);var snap=new ClientboundStormWavePacket(1,37,2,1,1,5,5,4096,0,4,10,60,0,12,0,4,original.members(),original.path(),new float[]{0,.5f,0,3.5f,0,.5f,0,3.5f,0,.5f,0,3.5f,0,.5f,0,3.5f,0,.5f,0,3.5f});
   gpu.rebuild(new ClientboundStormWavePacket[]{snap,null,null,null});gpu.prepare(1);gpu.commit();shadow.starfall(gpu.committedBuffer());var allCull=new com.iridium126.createmanaindustry.client.packages.render.PackageDrawCulling();allCull.distance=-1;allCull.safe=-1;shadow.prepare(q.pool,q.emit,q.counter,allCull,0,0,0,.35f,.6f,1,12,new float[16],new float[16],new float[4],q.pop,7);
   check(read(shadow.commands(),(ParticleBuffers.INDIRECT_UINTS+4)*4).getInt(ParticleBuffers.IDX_CNT_CARRIER*4)==20000,"expanded shadow carrier lost swords");
  }
 }
 /** Compare the production update kernel with/without an attack far from the origin. */
 static void outerFlight(){
  int update=compile("update.comp"),feedback=compile("wavecontact.comp");
  try(var q=new Fixture(1,1,1);var gpu=new StarfallGpuBuffers();var terrain=new StormCollisionGpu()){
   int output=buffer(bytes(64)),grid=buffer(bytes((ParticleBuffers.GRID_TABLE+1)*4)),damage=buffer(bytes(16)),players=buffer(bytes(16)),correction=buffer(bytes(32)),map=buffer(bytes(4)),out=buffer(bytes(4+16384*24)),stamps=buffer(bytes(16));
   try{
    terrain.offer(Map.of(new StormCollisionPages.Section(0,5,0),new StormCollisionPages.Page(1,StormCollisionPages.AIR,null)));for(int i=0;i<16&&!terrain.settled();i++)terrain.pump(1048576,1000000000);check(terrain.settled(),"outer collision fixture");
    var h=headers();int hb=2*ParticleBuffers.VEC4_PER_EMITTER*16;h.putFloat(hb+7*16+4,1).putFloat(hb+18*16+4,32).putFloat(hb+18*16+12,6).putFloat(hb+19*16+4,80);put(q.emit,h);
    var input=bytes(64);particle(input,0,2,50,0,20);input.putFloat(4,80).putFloat(12,1).putFloat(56,AllayStormWaves.memberSeed(1,0));q.build(input,1);put(q.emit,h);put(q.previous,bytes(16).putInt(0,1));
    gpu.outer(StormOuterPacket.batch(net.minecraft.world.level.Level.OVERWORLD.location(),1,0,List.of(new StormOuterLoop.Flight(0,123456789,152,80,0))));gpu.prepare(1);
    ui(update,"uCapacity",1);ui(update,"uKillEmit",-1);f(update,"uDt",.05f);bool(update,"uStormCollisionEnabled",true);
    for(int step=0;step<3;step++){
     if(step==1){int[] wall=new int[128];wall[0]=2;terrain.offer(Map.of(new StormCollisionPages.Section(0,5,0),StormCollisionPages.page(2,wall)));for(int i=0;i<16&&!terrain.settled();i++)terrain.pump(1048576,1000000000);input.putFloat(0,.7f).putFloat(8,.5f).putFloat(16,6);put(q.pool,input);}
     if(step==2){input.putFloat(0,151.9f);put(q.pool,input);}
     bind(0,q.pool);bind(1,output);bind(3,q.counter);bind(5,q.emit);bind(13,grid);bind(14,q.previous);bind(16,damage);bind(18,players);bind(19,correction);bind(21,map);put(q.counter,bytes(16));gpu.bind(update,true);check(GL20.glGetUniformi(update,GL20.glGetUniformLocation(update,"uStarfallEnabled"))==0,"outer-only enabled attack bookkeeping");terrain.bind(update);dispatch(update,1);
     var result=read(output,64);check(read(q.counter,16).getInt(0)==1,"outer member lost");if(step==1)check(result.getFloat(0)<=.826,"outer loop ignored known wall");else check(result.getFloat(0)>input.getFloat(0),"outer member did not move outward through unknown coverage");check(result.getFloat(0)-input.getFloat(0)<=.3001,"outer speed cap");check(result.getFloat(52)==20&&result.getFloat(12)==1,"outer HP/identity changed");
    }
    int state=gpu.candidateStateBuffer();check(read(state,(StarfallGpuBuffers.OUTER_ARRIVALS+1)*4).getInt(StarfallGpuBuffers.OUTER_ARRIVALS*4)==123456789,"ring arrival not recorded");
    bind(1,output);bind(3,q.counter);bind(5,q.emit);bind(22,out);bind(30,stamps);gpu.bind(feedback,true);ui(feedback,"uLocalWaveMask",31);dispatch(feedback,1);
    var reports=read(out,4+16384*24);boolean arrived=false;for(int i=0;i<reports.getInt(0);i++){int at=4+i*24;if(reports.getFloat(at)==-15){int cycle=Math.round(reports.getFloat(at+8))|(Math.round(reports.getFloat(at+12))<<16);check(cycle==123456789,"full integer cycle feedback");int domain=Math.round(reports.getFloat(at+16))|(Math.round(reports.getFloat(at+20))<<16);check(gpu.outerFeedback(0,cycle,domain),"matching outer feedback domain");check(!gpu.outerFeedback(0,cycle,domain+1),"stale world feedback accepted");arrived=true;}}check(arrived,"outer arrival missing from existing feedback");
    gpu.abort();gpu.prepare(1);check(read(gpu.candidateStateBuffer(),(StarfallGpuBuffers.OUTER_ARRIVALS+1)*4).getInt(StarfallGpuBuffers.OUTER_ARRIVALS*4)==0,"failed frame consumed arrival");
    gpu.outer(StormOuterPacket.batch(net.minecraft.world.level.Level.OVERWORLD.location(),1,1,List.of(new StormOuterLoop.Flight(0,123456789,0,0,0))));check(!gpu.hasOuter(),"release did not close outer loop");
   }finally{for(int id:new int[]{output,grid,damage,players,correction,map,out,stamps})GL15.glDeleteBuffers(id);}
  }finally{deleteProgram(update);deleteProgram(feedback);}
 }
 static void bodyHomeDuringWave(){
  int update=compile("update.comp"),n=65;
  try(var q=new Fixture(n,n,n);var gpu=new StarfallGpuBuffers()){
   int output=buffer(bytes(n*64)),grid=buffer(bytes((ParticleBuffers.GRID_TABLE+n)*4)),damage=buffer(bytes(16)),players=buffer(bytes(16)),correction=buffer(bytes(n*32)),map=buffer(bytes(n*4));
   try{
    ui(update,"uCapacity",n);ui(update,"uKillEmit",-1);f(update,"uDt",.05f);
    f(update,"uStormOmegaNow",.2f);f(update,"uStormRotPhase",.7f);f(update,"uStormConvPhase",.3f);f(update,"uStormConvRate",.04f);
    for(float[] center:new float[][]{{1500,128,-2300},{-1700,192,2100}}){
     ByteBuffer data=bytes(n*64),h=headers();int hb=2*ParticleBuffers.VEC4_PER_EMITTER*16;
     h.putFloat(hb+18*16+4,16).putFloat(hb+18*16+12,6);
     for(int axis=0;axis<3;axis++)h.putFloat(hb+19*16+axis*4,center[axis]);
     for(int i=0;i<n;i++){particle(data,i,2,center[0]+i*4,center[2],20);data.putFloat(i*64+4,center[1]+3).putFloat(i*64+12,i+1).putFloat(i*64+56,AllayStormWaves.memberSeed(1,i));}
     q.build(data,n);put(q.emit,h);put(q.previous,bytes(16).putInt(0,n));
     for(int members:new int[]{1,32,64})for(int event:new int[]{2,3}){
      var p=packet(members,17,event,12);float[] route=p.path().clone(),land=p.landings().clone();
      for(int i=0;i<route.length;i++)route[i]+=center[i%3];
      for(int c=0;c<p.columns();c++)for(int axis=0;axis<3;axis++)land[c*4+axis]+=center[axis];
      var snap=new ClientboundStormWavePacket(p.waveId(),p.epoch(),p.event(),p.waveSeed(),p.targetEntityId(),p.swordTier(),p.columns(),p.perColumn(),p.startSec(),p.assembleSec(),p.lockSec(),p.diveUntilSec(),center[0],center[1]+12,center[2],p.crownRadius(),p.members(),route,land);
      stage(gpu,snap,update);
      for(float time:new float[]{12,-1,0,5,10.5f,15,17,61}){
       f(update,"uTimeSec",time);ByteBuffer[] result=new ByteBuffer[2];
       for(int enabled=0;enabled<2;enabled++){
        bind(0,q.pool);bind(1,output);bind(3,q.counter);bind(5,q.emit);bind(13,grid);bind(14,q.previous);bind(16,damage);bind(18,players);bind(19,correction);bind(21,map);
        put(q.counter,bytes(16));gpu.bind(update,true);bool(update,"uStarfallEnabled",enabled!=0);dispatch(update,2);
        check(read(q.counter,16).getInt(0)==n,"body member disappeared");
        var compacted=read(output,n*64);result[enabled]=bytes(n*64);
        for(int slot=0;slot<n;slot++){int id=Math.round(compacted.getFloat(slot*64+12))-1;check(id>=0&&id<n,"body identity changed");for(int word=0;word<16;word++)result[enabled].putInt(id*64+word*4,compacted.getInt(slot*64+word*4));}
       }
       boolean attackersMoved=false;
       for(int member=0;member<n;member++){
        boolean home=member>=members||event==3||time<0||time>60;
        for(int word:new int[]{0,1,2,4,5,6}){
         float expected=result[0].getFloat(member*64+word*4),actual=result[1].getFloat(member*64+word*4);
         check(Float.isFinite(actual),"nonfinite body steering");
         if(home)check(Math.abs(expected-actual)<.0001,"wave overwrote home steering: member="+member+", event="+event+", time="+time+", word="+word+", expected="+expected+", actual="+actual);
         else if(Math.abs(expected-actual)>.001)attackersMoved=true;
        }
       }
       if(event==2&&time>=0&&time<=60)check(attackersMoved,"attack roster stopped steering");
      }
     }
    }
   }finally{for(int id:new int[]{output,grid,damage,players,correction,map})GL15.glDeleteBuffers(id);}
  }finally{com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clearUniformProgram(update);deleteProgram(update);}
 }
 static void benchmarkOuter()throws Exception{
  int n=65536,update=compile("update.comp");var rows=new ArrayList<String>();rows.add("storm_members,outer_members,prepare_gpu_ms,update_gpu_ms,feedback_gpu_ms,cpu_submit_ms,steady_upload_bytes");
  try(var q=new Fixture(n,n,n);var gpu=new StarfallGpuBuffers()){
   int output=buffer(bytes(n*64)),grid=buffer(bytes((ParticleBuffers.GRID_TABLE+n)*4)),damage=buffer(bytes(16)),players=buffer(bytes(16)),correction=buffer(bytes(n*32)),map=buffer(bytes(n*4)),out=buffer(bytes(4+16384*24)),stamps=buffer(bytes(n*16)),feedback=compile("wavecontact.comp");
   int[] queries={GL15.glGenQueries(),GL15.glGenQueries(),GL15.glGenQueries()};
   try{
    var input=bytes(n*64);for(int i=0;i<n;i++){particle(input,i,2,(i%256-128)*.2f,(i/256-128)*.2f,20);input.putFloat(i*64+4,80).putFloat(i*64+12,i+1).putFloat(i*64+56,AllayStormWaves.memberSeed(1,i));}q.build(input,n);put(q.previous,bytes(16).putInt(0,n));
    var h=headers();int hb=2*320;h.putFloat(hb+18*16+4,32).putFloat(hb+18*16+12,6).putFloat(hb+19*16+4,80);put(q.emit,h);ui(update,"uCapacity",n);ui(update,"uKillEmit",-1);f(update,"uDt",.05f);ui(feedback,"uLocalWaveMask",31);
    var first=List.of(new StormOuterLoop.Flight(0,1,152,80,0));gpu.outer(StormOuterPacket.batch(net.minecraft.world.level.Level.OVERWORLD.location(),1,0,first));gpu.prepare(1);gpu.commit();gpu.outer(StormOuterPacket.batch(net.minecraft.world.level.Level.OVERWORLD.location(),1,1,first));
    int generation=2;
    for(int count:new int[]{0,1200}){
     var flights=new ArrayList<StormOuterLoop.Flight>();for(int i=0;i<count;i++)flights.add(new StormOuterLoop.Flight(i,i+2,152,80,0));for(int off=0;off<count;off+=1024)gpu.outer(StormOuterPacket.batch(net.minecraft.world.level.Level.OVERWORLD.location(),1,0,flights.subList(off,Math.min(off+1024,count))));
     double[] sums=new double[4];long uploaded=0;
     for(int frame=0;frame<40;frame++){
      long begin=System.nanoTime();GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,queries[0]);gpu.prepare(generation++);GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
      GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,queries[1]);bind(0,q.pool);bind(1,output);bind(3,q.counter);bind(5,q.emit);bind(13,grid);bind(14,q.previous);bind(16,damage);bind(18,players);bind(19,correction);bind(21,map);put(q.counter,bytes(16));gpu.bind(update,true);dispatch(update,(n+63)/64);GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
      GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,queries[2]);bind(1,output);bind(3,q.counter);bind(5,q.emit);bind(22,out);bind(30,stamps);put(out,bytes(4));gpu.bind(feedback,true);dispatch(feedback,(n+63)/64);GL15.glEndQuery(GL33.GL_TIME_ELAPSED);gpu.commit();long end=System.nanoTime();
      for(int i=0;i<3;i++){double ms=GL33.glGetQueryObjectui64(queries[i],GL15.GL_QUERY_RESULT)/1e6;if(frame>=10)sums[i]+=ms;}
      if(frame==9)uploaded=gpu.uploadedBytes();if(frame>=10)sums[3]+=(end-begin)/1e6;
     }
     String row=n+","+count+","+sums[0]/30+","+sums[1]/30+","+sums[2]/30+","+sums[3]/30+","+(gpu.uploadedBytes()-uploaded);rows.add(row);System.out.println(row);
    }
   }finally{deleteProgram(feedback);for(int query:queries)GL15.glDeleteQueries(query);for(int buffer:new int[]{output,grid,damage,players,correction,map,out,stamps})GL15.glDeleteBuffers(buffer);}
  }finally{deleteProgram(update);}
  var output=Path.of("build/benchmarks/allay-storm/outer-loop.csv");Files.createDirectories(output.getParent());Files.write(output,rows);
 }
 static void benchmarkStarfall()throws Exception{
  var rows=new ArrayList<String>();rows.add("pool_live,participants,mode,run,update_gpu_p50_ms,update_gpu_p95_ms,shadow_gpu_p50_ms,shadow_gpu_p95_ms,cpu_wall_p50_ms,cpu_wall_p95_ms,thread_cpu_p50_ms,thread_cpu_p95_ms,allocated_bytes_p50,roster_upload_bytes,state_domain_bytes");
  var raw=new ArrayList<String>();raw.add("pool_live,participants,mode,run,sample,update_gpu_ms,shadow_gpu_ms,cpu_wall_ms,thread_cpu_ms,allocated_bytes");
  var thread=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();if(thread.isThreadCpuTimeSupported())thread.setThreadCpuTimeEnabled(true);if(thread.isThreadAllocatedMemorySupported())thread.setThreadAllocatedMemoryEnabled(true);long tid=Thread.currentThread().threadId();
  for(int total:new int[]{100000,2000000})for(int members:new int[]{256,1024,8192,16384}){
   try(var q=new Fixture(total,members,members);var gpu=new StarfallGpuBuffers();var shadow=new ModelShadowGpu(total,ParticleBuffers.CARRIER_CAP+members,StarfallGpuValidation::source)){
    var data=bytes(total*64);for(int i=0;i<total;i++){particle(data,i,i<members?2:0,i<members?(i%17-8)*.18f:20,i<members?((i/17)%17-8)*.18f:20,i<members?20:100);if(i<members){data.putFloat(i*64+4,4).putFloat(i*64+12,i+1).putFloat(i*64+56,AllayStormWaves.memberSeed(1,i));}}
    q.build(data,total);put(q.previous,bytes(16).putInt(0,total));var h=headers();h.putFloat(18*16+2*320+12,6);put(q.emit,h);
    int output=buffer(bytes(total*64)),grid=buffer(bytes((ParticleBuffers.GRID_TABLE+total)*4)),damage=buffer(bytes(16)),players=buffer(bytes(16)),correction=buffer(bytes(total*32)),map=buffer(bytes(total*4));
    int build=compile("gridbuild.comp"),update=compile("update.comp");
    gpu.rebuild(new ClientboundStormWavePacket[]{packet(members,1,2,0),null,null,null});gpu.prepare(1);gpu.commit();gpu.prepare(2);
    ui(build,"uCapacity",total);bool(build,"uPopulationReadEnabled",true);gen(build,"uPopulationGeneration",7);
    ui(update,"uCapacity",total);ui(update,"uKillEmit",-1);f(update,"uDt",.05f);f(update,"uTimeSec",12);f(update,"uStormOmegaNow",.1f);
    GL41.glProgramUniform4fv(update,GL20.glGetUniformLocation(update,"uWave"),new float[]{1,1,0,60,0,0,0,0,0,0,0,0,0,0,0,0});GL41.glProgramUniform4fv(update,GL20.glGetUniformLocation(update,"uWaveTarget"),new float[]{0,.5f,0,1,0,0,0,0,0,0,0,0,0,0,0,0});
    GL41.glProgramUniform4fv(update,GL20.glGetUniformLocation(update,"uWavePath"),new float[]{0,12,0,1,0,10.5f,0,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0});
    var cull=new com.iridium126.createmanaindustry.client.packages.render.PackageDrawCulling();cull.distance=-1;cull.safe=-1;
    var zero=bytes(16);float[] emptyWaves=new float[]{1,1,0,60,0,0,0,0,0,0,0,0,0,0,0,0},emptyTargets=new float[]{0,.5f,0,1,0,0,0,0,0,0,0,0,0,0,0,0},emptyTiers=new float[]{5,0,0,0};int updateQuery=GL15.glGenQueries(),shadowQuery=GL15.glGenQueries();
    for(boolean star:new boolean[]{false,true})for(int run=0;run<3;run++){
     double[] ug=new double[120],sg=new double[120],cw=new double[120],tc=new double[120],alloc=new double[120];
     long warmUntil=System.nanoTime()+1_000_000_000L;
     for(int sample=-40;sample<120;sample++){
      long allocBefore=thread.isThreadAllocatedMemorySupported()?thread.getThreadAllocatedBytes(tid):0,cpuBefore=thread.getCurrentThreadCpuTime(),begin=System.nanoTime();
      GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,updateQuery);bind(0,q.pool);bind(1,output);bind(3,q.counter);bind(5,q.emit);bind(13,grid);bind(14,q.previous);bind(16,damage);bind(18,players);bind(19,correction);bind(21,map);bind(24,q.pop);
      GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,grid);GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,0,ParticleBuffers.GRID_TABLE*4L,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(IntBuffer)null);put(q.counter,zero);
      GL20.glUseProgram(build);GL43.glDispatchCompute((members+63)/64,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
      gpu.bind(update,true);bool(update,"uStarfallEnabled",star);GL20.glUseProgram(update);GL43.glDispatchCompute((total+63)/64,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
      // Shadow preparation is a separate fixed-distribution query, not a full game frame.
      GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,shadowQuery);shadow.starfall(star?gpu.committedBuffer():0);shadow.prepare(q.pool,q.emit,q.previous,cull,0,0,0,.35f,.6f,1,12,emptyWaves,emptyTargets,emptyTiers,q.pop,7);GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
      long end=System.nanoTime(),cpuEnd=thread.getCurrentThreadCpuTime(),allocEnd=thread.isThreadAllocatedMemorySupported()?thread.getThreadAllocatedBytes(tid):0;
      double updateMs=GL33.glGetQueryObjectui64(updateQuery,GL15.GL_QUERY_RESULT)/1e6,shadowMs=GL33.glGetQueryObjectui64(shadowQuery,GL15.GL_QUERY_RESULT)/1e6;
      if(sample==-1&&System.nanoTime()<warmUntil)sample=-2;
      if(sample>=0){ug[sample]=updateMs;sg[sample]=shadowMs;cw[sample]=(end-begin)/1e6;tc[sample]=(cpuEnd-cpuBefore)/1e6;alloc[sample]=allocEnd-allocBefore;raw.add(total+","+members+","+(star?"starfall":"legacy_dive")+","+run+","+sample+","+updateMs+","+shadowMs+","+cw[sample]+","+tc[sample]+","+alloc[sample]);}
     }
     check(GL11.glGetError()==0,"benchmark GL error");String row=total+","+members+","+(star?"starfall":"legacy_dive")+","+run+","+p(ug,.5)+","+p(ug,.95)+","+p(sg,.5)+","+p(sg,.95)+","+p(cw,.5)+","+p(cw,.95)+","+p(tc,.5)+","+p(tc,.95)+","+p(alloc,.5)+","+gpu.uploadedBytes()+","+gpu.bytes();rows.add(row);System.out.println(row);
    }
    GL15.glDeleteQueries(updateQuery);GL15.glDeleteQueries(shadowQuery);for(int id:new int[]{output,grid,damage,players,correction,map})GL15.glDeleteBuffers(id);for(int p:new int[]{build,update}){com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clearUniformProgram(p);deleteProgram(p);}
   }
  }
  Files.write(Path.of(System.getProperty("cmi.benchmark.output", "build/benchmarks/current")+"/starfall-benchmark.csv"),rows);Files.write(Path.of(System.getProperty("cmi.benchmark.output", "build/benchmarks/current")+"/starfall-benchmark-samples.csv"),raw);
 }

 public static void main(String[] args)throws Exception{GLFWErrorCallback.createPrint(System.err).set();check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(128,128,"Starfall GPU validation",0,0);
  try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();System.out.println(GL11.glGetString(GL11.GL_RENDERER));outerFlight();bodyHomeDuringWave();contacts();formations();if(Arrays.asList(args).contains("--benchmark"))benchmarkStarfall();if(Arrays.asList(args).contains("--outer-benchmark"))benchmarkOuter();check(GL11.glGetError()==0,"final GL state");System.out.println("Starfall GPU: "+checks+" assertions passed");}finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
 }
}
