import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.particles.engine.*;
import com.iridium126.createmanaindustry.client.particles.allaystorm.*;
import com.iridium126.createmanaindustry.content.allaystorm.*;
import com.iridium126.createmanaindustry.content.allaystorm.network.*;

/** Production motion, carrier, state and contact shaders in a hidden real OpenGL context. */
public class DomeGpuValidation extends StarfallGpuValidation {
 static ClientboundStormWavePacket dome(int count,int id,int event,int phase){
  int[] members=new int[count];for(int i=0;i<count;i++)members[i]=i;
  float radius=AllayDome.radius(count);
  return new ClientboundStormWavePacket(id,37,event,1,1,5,7,4096,0,4,10,120,0,40,0,radius,members,
   new float[]{0,40,0,0,radius,0},new float[28],1,phase,AllayDome.groups(count),0,0,0,.17f,-1,10,0);
 }
 static int motionProgram(){String nav=source("chunks/storm_navigation.glsl");nav=nav.substring(nav.indexOf("vec3 cmiStarRoute"));
  return compileText("dome motion",source("chunks/allay_storm.glsl")+nav+"\nlayout(local_size_x=64)in;layout(std430,binding=10)buffer Output{vec4 rows[];} result;uniform uint uCount;void main(){uint i=gl_GlobalInvocationID.x;if(i>=uCount)return;vec3 target,velocity;uint phase=0u;float identity=float(i+1u);bool engaged=cmiStarSteering(identity,float(i)*.0001,vec3(0),vec3(99,40,0),6.,target,velocity,phase);result.rows[i*3u]=vec4(target,float(phase));result.rows[i*3u+1u]=vec4(velocity,engaged?1.:0.);result.rows[i*3u+2u]=vec4(cmiAttackCarry(identity,uTimeSec),float((cmiStarAssignment(identity)>>3u)&31u),0,0);}");
 }
 static void geometry()throws Exception {
  int p=motionProgram();try(var gpu=new StarfallGpuBuffers()){
   for(int n:new int[]{0,1,63,64,65,768,4096,8192,16384,24576}){
    var snapshot=dome(n,1,2,3);int out=buffer(bytes(Math.max(1,n)*48));bind(10,out);stage(gpu,snapshot,p);ui(p,"uCount",n);
    float r=snapshot.crownRadius();int[] ends=snapshot.groupEnds();
    for(int step=0;step<14;step++){
     float t=step==0?10:step==13?10+6*(1.5f+AllayDome.closeSeconds(r))+1:10+((step-1)/2)*(1.5f+AllayDome.closeSeconds(r))+(step%2==1?.6f:2);
     f(p,"uTimeSec",t);dispatch(p,Math.max(1,(n+63)/64));var rows=read(out,Math.max(1,n)*48);int harmful=0;
     for(int i=0;i<n;i++){
      int offset=i*48,group=AllayDome.group(ends,i);float phase=rows.getFloat(offset+12);
      assertFinite(rows,offset,8);check(phase==AllayDome.memberPhase(t,10,r,group),"Java/GPU dome phase mismatch n="+n+" member="+i+" group="+group+" t="+t+" got="+phase+" expected="+AllayDome.memberPhase(t,10,r,group));
      float vx=rows.getFloat(offset+16),vy=rows.getFloat(offset+20),vz=rows.getFloat(offset+24);check(vx*vx+vy*vy+vz*vz<=36.001,"dome speed exceeds cap");
      check(rows.getFloat(offset+36)==group,"group boundary identity wrong");
      if(phase==4){harmful++;check(group<6&&group==AllayDome.closingPetal(t,10,r),"two petals or crown harming");}
      if(group==6)check(rows.getFloat(offset+32)==0,"crown holds sword");
      if(step==0&&group<6){float x=rows.getFloat(offset),y=rows.getFloat(offset+4),z=rows.getFloat(offset+8);check(Math.abs(Math.sqrt(x*x+y*y+z*z)-r)<.1,"hemisphere is not hollow");double angle=Math.atan2(z,x)-(.17-group*Math.PI/3);angle=Math.atan2(Math.sin(angle),Math.cos(angle));check(Math.abs(angle)<Math.toRadians(9)+.04,"ribbon fills sky gap");}
     }
     int active=AllayDome.closingPetal(t,10,r);int expected=active<0?0:ends[active]-(active==0?0:ends[active-1]);check(harmful==expected,"wrong damaging group size");
     if(step==0&&n==4096){renderDome(rows,n);bind(10,out);}
    }
    // Boundary targets must not jump when circulation freezes or damage is removed.
    for(int g=0;g<6;g++)for(float boundary:new float[]{AllayDome.warningStart(10,r,g),AllayDome.warningStart(10,r,g)+1.5f,AllayDome.warningStart(10,r,g)+1.5f+AllayDome.closeSeconds(r)}){
     f(p,"uTimeSec",boundary-.001f);dispatch(p,Math.max(1,(n+63)/64));var before=read(out,Math.max(1,n)*48);
     f(p,"uTimeSec",boundary+.001f);dispatch(p,Math.max(1,(n+63)/64));var after=read(out,Math.max(1,n)*48);
     for(int i=g==0?0:ends[g-1];i<ends[g];i++){double d=0;for(int j=0;j<3;j++){double delta=before.getFloat(i*48+j*4)-after.getFloat(i*48+j*4);d+=delta*delta;}check(d<.005,"dome phase target discontinuity");}
    }
    gpu.commit();int committed=gpu.committedBuffer();gpu.rebuild(new ClientboundStormWavePacket[]{dome(n,2,2,3),null,null,null});gpu.prepare(2);gpu.abort();check(gpu.committedBuffer()==committed,"failed dome prepare published");
    GL15.glDeleteBuffers(out);
   }
  }finally{CMIParticleEngine.clearUniformProgram(p);GL20.glDeleteProgram(p);}
 }
 static void assertFinite(ByteBuffer b,int o,int words){for(int j=0;j<words;j++)check(Float.isFinite(b.getFloat(o+j*4)),"nonfinite dome geometry");}
 static void unlockedCarrier(){
  int p=motionProgram(),out=buffer(bytes(768*48));try(var gpu=new StarfallGpuBuffers()){
   stage(gpu,dome(768,1,0,2),p);bind(10,out);ui(p,"uCount",768);f(p,"uTimeSec",12);dispatch(p,12);var rows=read(out,768*48);
   for(int i=0;i<768;i++){check(rows.getFloat(i*48+12)==2,"unlocked dome entered warning phase");check(rows.getFloat(i*48+32)==0,"unlocked dome holds sword");}
  }finally{GL15.glDeleteBuffers(out);CMIParticleEngine.clearUniformProgram(p);GL20.glDeleteProgram(p);}
 }
 static void contactsWithoutRings(){
  int p=compile("wavecontact.comp");try(var gpu=new StarfallGpuBuffers()){
   for(int n:new int[]{1,63,64,65,768}){
    int total=Math.max(768,n);var snap=dome(total,17,2,3);int firstSize=snap.groupEnds()[0],hits=Math.min(n,firstSize);
    var particles=bytes((n+2)*64);for(int i=0;i<n+2;i++){particle(particles,i,2,0,0,20);particles.putFloat(i*64+4,.5f).putFloat(i*64+12,i<n?i+1:snap.groupEnds()[5]+1);}
    particles.putFloat((n+1)*64+52,0); // a corpse and a crown member must never report
    int pool=buffer(particles),counter=buffer(bytes(16).putInt(0,n+2)),header=buffer(headers()),out=buffer(bytes(4+16384*24)),stampsA=buffer(bytes(total*16)),stampsB=buffer(bytes(total*16));
    bind(1,pool);bind(3,counter);bind(5,header);bind(22,out);bind(30,stampsA);stage(gpu,snap,p);
    int stateBuffer=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28);var state=bytes(StarfallGpuBuffers.STATE_WORDS*4);
    for(int i=0;i<total;i++)state.putInt(i*8,17).putInt(i*8+4,4);put(stateBuffer,state); // marker census deliberately zero
    GL41.glProgramUniform4fv(p,GL20.glGetUniformLocation(p,"uWaveTarget"),new float[]{0,.5f,0,17,0,0,0,0,0,0,0,0,0,0,0,0});GL41.glProgramUniform3f(p,GL20.glGetUniformLocation(p,"uLocalPlayerPos"),0,.5f,0);ui(p,"uLocalWaveMask",1);
    f(p,"uTimeSec",10.8f);dispatch(p,(n+65)/64);check(read(out,4).getInt(0)==0,"dome warning damages");
    f(p,"uTimeSec",12);dispatch(p,(n+65)/64);check(read(out,4).getInt(0)==hits,"dome incorrectly requires ring census");
    put(out,bytes(4+16384*24));dispatch(p,(n+65)/64);check(read(out,4).getInt(0)==0,"victim A duplicate");
    bind(30,stampsB);dispatch(p,(n+65)/64);check(read(out,4).getInt(0)==hits,"victim B suppressed by A");
    put(out,bytes(4+16384*24));f(p,"uTimeSec",10+1.5f+AllayDome.closeSeconds(snap.crownRadius())+.01f);dispatch(p,(n+65)/64);check(read(out,4).getInt(0)==0,"completed petal damages");
    for(int b:new int[]{pool,counter,header,out,stampsA,stampsB})GL15.glDeleteBuffers(b);
   }
  }finally{CMIParticleEngine.clearUniformProgram(p);GL20.glDeleteProgram(p);}
 }
 static void carrierAndCompaction(){
  int n=24576;try(var q=new Fixture(n,n,n);var gpu=new StarfallGpuBuffers();var shadow=new ModelShadowGpu(n,ParticleBuffers.CARRIER_CAP+n,DomeGpuValidation::source)){
   var data=bytes(n*64);for(int i=0;i<n;i++){particle(data,i,2,0,0,20);data.putFloat(i*64+12,n-i);}
   q.build(data,n);var snap=dome(n,1,2,3);stage(gpu,snap,0);gpu.commit();shadow.starfall(gpu.committedBuffer());var c=new com.iridium126.createmanaindustry.client.particles.packages.PackageDrawCulling();c.distance=-1;c.safe=-1;
   for(int group=0;group<6;group++){
    float time=AllayDome.warningStart(10,20,group)+.5f;
    shadow.prepare(q.pool,q.emit,q.counter,c,0,0,0,.35f,.6f,1,time,new float[16],new float[16],new float[4],q.pop,7);
    int count=read(shadow.commands(),(ParticleBuffers.INDIRECT_UINTS+4)*4).getInt(ParticleBuffers.IDX_CNT_CARRIER*4);
    check(count==snap.groupEnds()[group]-(group==0?0:snap.groupEnds()[group-1]),"compacted identity carrier group incorrect");
   }
   shadow.prepare(q.pool,q.emit,q.counter,c,0,0,0,.35f,.6f,1,9,new float[16],new float[16],new float[4],q.pop,7);
   check(read(shadow.commands(),(ParticleBuffers.INDIRECT_UINTS+4)*4).getInt(ParticleBuffers.IDX_CNT_CARRIER*4)==0,"assembling dome holds swords");
  }
 }
 static void renderDome(ByteBuffer rows,int count)throws Exception{
  // Framebuffer geometry evidence, not a substitute for the user's in-game model/art acceptance.
  int vs=GL20.glCreateShader(GL20.GL_VERTEX_SHADER),fs=GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
  GL20.glShaderSource(vs,"#version 450 core\nlayout(std430,binding=10)readonly buffer R{vec4 p[];};uniform mat4 vp;out vec3 color;void main(){vec4 v=p[uint(gl_VertexID)*3u];gl_Position=vp*vec4(v.xyz,1);gl_PointSize=2.;color=vec3(.25,.7,1);}");
  GL20.glShaderSource(fs,"#version 450 core\nin vec3 color;out vec4 outColor;void main(){outColor=vec4(color,1);}");
  GL20.glCompileShader(vs);GL20.glCompileShader(fs);int program=GL20.glCreateProgram();GL20.glAttachShader(program,vs);GL20.glAttachShader(program,fs);GL20.glLinkProgram(program);check(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(program));
  int vao=GL30.glGenVertexArrays(),fb=GL30.glGenFramebuffers(),tex=GL11.glGenTextures(),input=buffer(rows);
  GL30.glBindVertexArray(vao);GL11.glBindTexture(GL11.GL_TEXTURE_2D,tex);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,512,384,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(ByteBuffer)null);
  GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fb);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,tex,0);bind(10,input);
  var vp=new org.joml.Matrix4f().setPerspective((float)Math.toRadians(48),512f/384,.1f,100).lookAt(23,14,27,0,5,0,0,1,0);
  GL20.glUseProgram(program);GL41.glProgramUniformMatrix4fv(program,GL20.glGetUniformLocation(program,"vp"),false,vp.get(new float[16]));GL11.glViewport(0,0,512,384);GL11.glClearColor(.025f,.035f,.07f,1);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);GL11.glEnable(GL32.GL_PROGRAM_POINT_SIZE);GL11.glDrawArrays(GL11.GL_POINTS,0,count);
  var pixels=bytes(512*384*4);GL11.glReadPixels(0,0,512,384,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);var image=new java.awt.image.BufferedImage(512,384,java.awt.image.BufferedImage.TYPE_INT_RGB);int lit=0;
  for(int y=0;y<384;y++)for(int x=0;x<512;x++){int o=(y*512+x)*4,r=pixels.get(o)&255,g=pixels.get(o+1)&255,b=pixels.get(o+2)&255;if(b>100)lit++;image.setRGB(x,383-y,(r<<16)|(g<<8)|b);}check(lit>100,"dome framebuffer missing");javax.imageio.ImageIO.write(image,"png",Path.of(System.getProperty("cmi.benchmark.output", "build/benchmarks/current")+"/dome-geometry-framebuffer.png").toFile());
  GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,0);GL11.glDisable(GL32.GL_PROGRAM_POINT_SIZE);GL15.glDeleteBuffers(input);GL11.glDeleteTextures(tex);GL30.glDeleteFramebuffers(fb);GL30.glDeleteVertexArrays(vao);GL20.glUseProgram(0);GL20.glDeleteProgram(program);GL20.glDeleteShader(vs);GL20.glDeleteShader(fs);
 }
 static void simulationBanks(){
  int n=65,update=compile("update.comp");try(var q=new Fixture(n,n,n);var gpu=new StarfallGpuBuffers()){
   var data=bytes(n*64);for(int i=0;i<n;i++){particle(data,i,i<63?2:1,0,0,i==63?10:i==64?-.3f:20);data.putFloat(i*64+4,4).putFloat(i*64+12,i<63?i+1:1).putFloat(i*64+48,9999).putFloat(i*64+56,AllayStormWaves.memberSeed(1,i));}
   put(q.pool,data);var h=headers();h.putFloat(2*320+18*16+12,6);put(q.emit,h);
   int output=buffer(bytes(n*64)),grid=buffer(bytes((ParticleBuffers.GRID_TABLE+n)*4)),damage=buffer(bytes(16+ParticleBuffers.DAMAGE_ENTRY_BYTES)),players=buffer(bytes(16)),correction=buffer(bytes(n*32)),map=buffer(bytes(n*4));
   int identity=buffer(bytes(4+(n*2+ParticleBuffers.DAMAGE_QUEUE_CAP*2)*4).putInt(4+63*4,101).putInt(4+64*4,202));
   try{
    // A deliberately large network correction used to bypass the servo velocity cap.
    var corrections=bytes(n*32).putFloat(0,100).putFloat(4,100).putFloat(8,100).putFloat(12,12).putFloat(16,60).putFloat(28,1);put(correction,corrections);
    ui(update,"uCapacity",n);ui(update,"uKillEmit",-1);ui(update,"uIdentityReadBase",0);ui(update,"uIdentityWriteBase",n);f(update,"uDt",.05f);
    gpu.rebuild(new ClientboundStormWavePacket[]{dome(768,1,2,3),null,null,null});int live=n;
    for(int frame=0;frame<35;frame++){
     put(q.previous,bytes(16).putInt(0,live));put(q.counter,bytes(16));
     var queue=bytes(16+ParticleBuffers.DAMAGE_ENTRY_BYTES);
     if(frame==0||frame==10){queue.putInt(0,1).putFloat(16,frame==0?2:1).putFloat(20,5).putFloat(36,frame==0?1:3);}
     put(damage,queue);bind(0,q.pool);bind(1,output);bind(3,q.counter);bind(5,q.emit);bind(13,grid);bind(14,q.previous);bind(16,damage);bind(18,players);bind(19,correction);bind(21,map);bind(31,identity);
     gpu.prepare(frame+1);gpu.bind(update,true);f(update,"uTimeSec",12+frame*.05f);dispatch(update,2);gpu.commit();
     live=read(q.counter,16).getInt(0);var result=read(output,n*64);var ids=read(identity,4+(n*2+ParticleBuffers.DAMAGE_QUEUE_CAP*2)*4);var nextIds=bytes(ids.capacity());
     boolean normal=false,damaged=false;
     for(int i=0;i<live;i++){
      int o=i*64;assertFinite(result,o,15);int header=result.getInt(o+60);
      if(header==2&&result.getFloat(o+52)>0){float vx=result.getFloat(o+16),vy=result.getFloat(o+20),vz=result.getFloat(o+24);check(vx*vx+vy*vy+vz*vz<=36.001,"actual network-corrected dome exceeds speed cap");}
      int token=ids.getInt(4+(n+i)*4);nextIds.putInt(4+i*4,token);
      if(header==1&&token==101){normal=true;check(Math.abs(result.getFloat(o+52)-(10+(frame+1)*.1f))<.001,"ordinary MODEL regen/identity changed by dome");check(result.getFloat(o+48)>9999,"ordinary MODEL lost permanent life");}
      if(header==2&&Math.round(result.getFloat(o+12))==3){damaged=true;check(Math.abs(result.getFloat(o+52)-Math.min(20,15+(frame+1)*.1f))<.001,"storm HP/regen not retained");}
     }
     check(normal&&damaged,"MODEL or storm identity disappeared");put(identity,nextIds);put(q.pool,result);
     if(frame==0){gpu.bind(0,false);int state=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28);check(read(state,24).getInt(20)==4,"phase did not travel with committed bank");}
    }
    check(live==63,"corpse expiration or permanent members incorrect");
   }finally{for(int b:new int[]{output,grid,damage,players,correction,map,identity})GL15.glDeleteBuffers(b);}
  }finally{CMIParticleEngine.clearUniformProgram(update);GL20.glDeleteProgram(update);}
 }
 public static void main(String[] args)throws Exception{
  GLFWErrorCallback.createPrint(System.err).set();check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(512,384,"Dome GPU validation",0,0);
  try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();System.out.println(GL11.glGetString(GL11.GL_RENDERER));geometry();unlockedCarrier();contactsWithoutRings();carrierAndCompaction();simulationBanks();if(Arrays.asList(args).contains("--benchmark"))benchmarkDome();check(GL11.glGetError()==0,"dome final GL state");System.out.println("Dome GPU: "+checks+" assertions passed");}finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
 }
 static ClientboundStormWavePacket starBenchmarkPacket(int count){
  int per=1024,cols=(count+per-1)/per;int[] roster=new int[count];for(int i=0;i<count;i++)roster[i]=i;
  float[] land=new float[cols*4];for(int i=0;i<cols;i++){land[i*4+1]=.5f;land[i*4+3]=3.5f;}
  return new ClientboundStormWavePacket(1,37,2,1,1,5,cols,per,0,4,10,120,0,40,0,8,roster,new float[]{0,40,0,0,10,0},land);
 }
 static void benchmarkDome()throws Exception{
  var rows=new ArrayList<String>();rows.add("pool_live,participants,mode,run,update_gpu_p50_ms,update_gpu_p95_ms,shadow_gpu_p50_ms,shadow_gpu_p95_ms,cull_gpu_p50_ms,cull_gpu_p95_ms,cpu_wall_p50_ms,cpu_wall_p95_ms,thread_cpu_p50_ms,thread_cpu_p95_ms,allocated_bytes_p50,roster_upload_bytes,state_domain_bytes");
  var raw=new ArrayList<String>();raw.add("pool_live,participants,mode,run,sample,update_gpu_ms,shadow_gpu_ms,cull_gpu_ms,cpu_wall_ms,thread_cpu_ms,allocated_bytes");
  var thread=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();if(thread.isThreadCpuTimeSupported())thread.setThreadCpuTimeEnabled(true);if(thread.isThreadAllocatedMemorySupported())thread.setThreadAllocatedMemoryEnabled(true);long tid=Thread.currentThread().threadId();
  for(int members:new int[]{768,4096,8192,16384,24576})for(int total:new int[]{members,2000000}){
   try(var q=new Fixture(total,members,members);var gpu=new StarfallGpuBuffers();var shadow=new ModelShadowGpu(total,ParticleBuffers.CARRIER_CAP+members,StarfallGpuValidation::source)){
    var data=bytes(total*64);for(int i=0;i<total;i++){particle(data,i,i<members?2:0,i<members?(i%17-8)*.18f:20,i<members?((i/17)%17-8)*.18f:20,i<members?20:100);if(i<members){data.putFloat(i*64+4,4).putFloat(i*64+12,i+1).putFloat(i*64+56,AllayStormWaves.memberSeed(1,i));}}
    q.build(data,total);put(q.previous,bytes(16).putInt(0,total));var h=headers();h.putFloat(18*16+2*320+12,6);put(q.emit,h);
    int output=buffer(bytes(total*64)),grid=buffer(bytes((ParticleBuffers.GRID_TABLE+total)*4)),damage=buffer(bytes(16)),players=buffer(bytes(16)),correction=buffer(bytes(total*32)),map=buffer(bytes(total*4));
    int build=compile("gridbuild.comp"),update=compile("update.comp");
    gpu.rebuild(new ClientboundStormWavePacket[]{dome(members,1,2,3),null,null,null});gpu.prepare(1);gpu.commit();gpu.prepare(2);
    int carrierCapacity=ParticleBuffers.CARRIER_CAP+members;for(int buffer:new int[]{q.sorted,q.carriers}){GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,(total+carrierCapacity+1)*8L,GL15.GL_DYNAMIC_COPY);}ui(q.keygen,"uCarrierLimit",carrierCapacity);f(q.keygen,"uTimeSec",12);
    ui(build,"uCapacity",total);bool(build,"uPopulationReadEnabled",true);gen(build,"uPopulationGeneration",7);
    ui(update,"uCapacity",total);ui(update,"uKillEmit",-1);f(update,"uDt",.05f);f(update,"uTimeSec",12);f(update,"uStormOmegaNow",.1f);
    GL41.glProgramUniform4fv(update,GL20.glGetUniformLocation(update,"uWave"),new float[]{1,1,0,60,0,0,0,0,0,0,0,0,0,0,0,0});GL41.glProgramUniform4fv(update,GL20.glGetUniformLocation(update,"uWaveTarget"),new float[]{0,.5f,0,1,0,0,0,0,0,0,0,0,0,0,0,0});
    GL41.glProgramUniform4fv(update,GL20.glGetUniformLocation(update,"uWavePath"),new float[]{0,12,0,1,0,10.5f,0,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0});
    var cull=new com.iridium126.createmanaindustry.client.particles.packages.PackageDrawCulling();cull.distance=-1;cull.safe=-1;
    var zero=bytes(16);var liveCounter=bytes(16).putInt(0,total);float[] emptyWaves=new float[]{1,1,0,60,0,0,0,0,0,0,0,0,0,0,0,0},emptyTargets=new float[]{0,.5f,0,1,0,0,0,0,0,0,0,0,0,0,0,0},emptyTiers=new float[]{5,0,0,0};int updateQuery=GL15.glGenQueries(),shadowQuery=GL15.glGenQueries(),cullQuery=GL15.glGenQueries();
    for(boolean domeMode:new boolean[]{false,true}){
     var packet=domeMode?dome(members,1,2,3):starBenchmarkPacket(members);
     gpu.rebuild(new ClientboundStormWavePacket[]{packet,null,null,null});gpu.prepare(1);gpu.commit();gpu.prepare(2);
     boolean star=true;
     for(int run=0;run<3;run++){
     double[] ug=new double[120],sg=new double[120],kg=new double[120],cw=new double[120],tc=new double[120],alloc=new double[120];
     long warmUntil=System.nanoTime()+1_000_000_000L;
     for(int sample=-40;sample<120;sample++){
      long allocBefore=thread.isThreadAllocatedMemorySupported()?thread.getThreadAllocatedBytes(tid):0,cpuBefore=thread.getCurrentThreadCpuTime(),begin=System.nanoTime();
      GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,updateQuery);bind(0,q.pool);bind(1,output);bind(3,q.counter);bind(5,q.emit);bind(13,grid);bind(14,q.previous);bind(16,damage);bind(18,players);bind(19,correction);bind(21,map);bind(24,q.pop);
      GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,grid);GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,0,ParticleBuffers.GRID_TABLE*4L,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(IntBuffer)null);put(q.counter,zero);
      GL20.glUseProgram(build);GL43.glDispatchCompute((members+63)/64,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
      gpu.bind(update,true);bool(update,"uStarfallEnabled",star);GL20.glUseProgram(update);GL43.glDispatchCompute((total+63)/64,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
      // Shadow preparation is a separate fixed-distribution query, not a full game frame.
      GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,cullQuery);q.bindings();gpu.bind(q.reset,true);GL20.glUseProgram(q.reset);GL43.glDispatchCompute(1,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);put(q.counter,liveCounter);gpu.bind(q.keygen,true);GL20.glUseProgram(q.keygen);GL43.glDispatchCompute((total+63)/64,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);GL20.glUseProgram(q.prepare);GL43.glDispatchCompute(1,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
      GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,shadowQuery);shadow.starfall(star?gpu.committedBuffer():0);shadow.prepare(q.pool,q.emit,q.previous,cull,0,0,0,.35f,.6f,1,12,emptyWaves,emptyTargets,emptyTiers,q.pop,7);GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
      long end=System.nanoTime(),cpuEnd=thread.getCurrentThreadCpuTime(),allocEnd=thread.isThreadAllocatedMemorySupported()?thread.getThreadAllocatedBytes(tid):0;
      double cullMs=GL33.glGetQueryObjectui64(cullQuery,GL15.GL_QUERY_RESULT)/1e6;double updateMs=GL33.glGetQueryObjectui64(updateQuery,GL15.GL_QUERY_RESULT)/1e6,shadowMs=GL33.glGetQueryObjectui64(shadowQuery,GL15.GL_QUERY_RESULT)/1e6;
      if(sample==-1&&System.nanoTime()<warmUntil)sample=-2;
      if(sample>=0){ug[sample]=updateMs;sg[sample]=shadowMs;kg[sample]=cullMs;cw[sample]=(end-begin)/1e6;tc[sample]=(cpuEnd-cpuBefore)/1e6;alloc[sample]=allocEnd-allocBefore;raw.add(total+","+members+","+(domeMode?"dome":"starfall")+","+run+","+sample+","+updateMs+","+shadowMs+","+cullMs+","+cw[sample]+","+tc[sample]+","+alloc[sample]);}
     }
     check(GL11.glGetError()==0,"benchmark GL error");String row=total+","+members+","+(domeMode?"dome":"starfall")+","+run+","+p(ug,.5)+","+p(ug,.95)+","+p(sg,.5)+","+p(sg,.95)+","+p(kg,.5)+","+p(kg,.95)+","+p(cw,.5)+","+p(cw,.95)+","+p(tc,.5)+","+p(tc,.95)+","+p(alloc,.5)+","+gpu.uploadedBytes()+","+gpu.bytes();rows.add(row);System.out.println(row);
    }
    }
    GL15.glDeleteQueries(cullQuery);GL15.glDeleteQueries(updateQuery);GL15.glDeleteQueries(shadowQuery);for(int id:new int[]{output,grid,damage,players,correction,map})GL15.glDeleteBuffers(id);for(int p:new int[]{build,update}){CMIParticleEngine.clearUniformProgram(p);GL20.glDeleteProgram(p);}for(int p:new int[]{q.reset,q.keygen,q.prepare})CMIParticleEngine.clearUniformProgram(p);
   }
  }
  Files.write(Path.of(System.getProperty("cmi.benchmark.output", "build/benchmarks/current")+"/dome-benchmark.csv"),rows);Files.write(Path.of(System.getProperty("cmi.benchmark.output", "build/benchmarks/current")+"/dome-benchmark-samples.csv"),raw);
 }

}
