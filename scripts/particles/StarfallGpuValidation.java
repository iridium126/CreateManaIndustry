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
 static void rings()throws Exception{
  int reserve=compile("starfall_ring_reserve.comp"),emit=compile("emit.comp"),update=compile("update.comp");
  int cap=128;var header=bytes(320);header.putFloat(17*16+4,7).putFloat(5*16+8,.27f).putFloat(5*16+12,.27f).putFloat(6*16,1).putFloat(6*16+8,1).putFloat(6*16+12,1);header.putFloat(8*16,1).putFloat(8*16+4,1).putFloat(8*16+8,1).putFloat(8*16+12,1);
  int h=buffer(header),counter=buffer(bytes(16)),pool=buffer(bytes(cap*64)),output=buffer(bytes(cap*64)),previous=buffer(bytes(16).putInt(0,cap));
  var command=bytes(48).putFloat(12,128).putFloat(20,.42f).putFloat(32,1);int cmd=buffer(command);
  try(var gpu=new StarfallGpuBuffers()){
   stage(gpu,packet(65,1,2,0),reserve);bind(3,counter);bind(4,cmd);bind(5,h);ui(reserve,"uEmitCount",1);f(reserve,"uTimeSec",10);ui(reserve,"uCapacity",127);dispatch(reserve,1);check(read(counter,16).getInt(0)==0,"partial ring reserved");
   ui(reserve,"uCapacity",cap);dispatch(reserve,1);check(read(counter,16).getInt(0)==128,"whole ring reservation");
   gpu.bind(emit,true);bind(1,pool);f(emit,"uTimeSec",10);ui(emit,"uCapacity",cap);ui(emit,"uTotalSpawn",128);ui(emit,"uEmitCount",1);dispatch(emit,2);
   var particles=read(pool,cap*64);for(int i=0;i<128;i++){int o=i*64;double angle=i*Math.PI*2/128;check(Math.abs(particles.getFloat(o)-2.8*Math.cos(angle))<.0001,"ring X");check(Math.abs(particles.getFloat(o+8)-2.8*Math.sin(angle))<.0001,"ring Z");check(Math.abs(particles.getFloat(o+4)-.2)<.0001,"ground height");float side=particles.getFloat(o+12)*.27f;check(side>=.18&&side<.36,"native full quad size");}
   try(var censusFixture=new Fixture(128,0,0)){
    put(censusFixture.emit,header);gpu.bind(censusFixture.reset,true);gpu.bind(censusFixture.keygen,true);censusFixture.build(particles,128);
    int status=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28);check(read(status,StarfallGpuBuffers.DOME_JOIN*4).getInt(262400*4)==128,"keygen full-circle census");
    censusFixture.build(particles,127);check(read(status,StarfallGpuBuffers.DOME_JOIN*4).getInt(262400*4)==127,"keygen incomplete-circle census");com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clearUniformProgram(censusFixture.reset);com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clearUniformProgram(censusFixture.keygen);com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clearUniformProgram(censusFixture.prepare);
   }
   bind(3,counter);bind(5,h);bind(0,pool);bind(1,output);bind(14,previous);put(counter,bytes(16));gpu.bind(update,true);ui(update,"uCapacity",cap);ui(update,"uKillEmit",-1);f(update,"uDt",.25f);f(update,"uTimeSec",12);dispatch(update,2);
   check(read(counter,16).getInt(0)==128,"ring died early");var next=read(output,cap*64);for(int i=0;i<128;i++)for(int j=0;j<8;j++){int original=Math.round(next.getFloat(i*64+24)*128/(float)(2*Math.PI))&127;check(next.getInt(i*64+j*4)==particles.getInt(original*64+j*4),"ring position/size/roll moved");}
   gpu.commit();int committed=gpu.committedBuffer();gpu.rebuild(new ClientboundStormWavePacket[]{packet(65,2,2,0),null,null,null});gpu.prepare(2);gpu.abort();check(gpu.committedBuffer()==committed,"aborted snapshot replaced current generation");
   stage(gpu,packet(65,1,3,12),update);put(counter,bytes(16));f(update,"uTimeSec",12.15f);dispatch(update,2);check(read(counter,16).getInt(0)==128,"abort lacks fade");check(Math.abs(read(output,cap*64).getFloat(44)-.4)<.001,"abort fade wrong");
   put(counter,bytes(16));f(update,"uTimeSec",12.3f);dispatch(update,2);check(read(counter,16).getInt(0)==0,"aborted ring survived");
   // Render the real additive shaders with conjure-sized camera-facing particles.
   stage(gpu,packet(65,1,2,0),emit);gpu.commit();gpu.bind(0,false);var census=bytes(StarfallGpuBuffers.DOME_JOIN*4).putInt(262400*4,128);put(GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28),census);renderRing(gpu,pool,h);gpu.invalidate();stage(gpu,packet(65,1,2,0),update);bind(0,pool);bind(1,output);bind(3,counter);bind(14,previous);put(counter,bytes(16));f(update,"uTimeSec",12);f(update,"uDt",.1f);dispatch(update,2);check(read(counter,16).getInt(0)==128,"cross-generation rings lack fade");check(Math.abs(read(output,8192).getFloat(44)-.6)<.001,"old ring followed restarted wave");
  }finally{for(int p:new int[]{reserve,emit,update}){com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clearUniformProgram(p);deleteProgram(p);}for(int b:new int[]{h,counter,pool,output,previous,cmd})GL15.glDeleteBuffers(b);}
 }
 static int graphicsStage(String name,int type){int s=GL20.glCreateShader(type);GL20.glShaderSource(s,"#version 450 core\n"+ParticlePrograms.commonPrelude()+source(name));GL20.glCompileShader(s);check(GL20.glGetShaderi(s,GL20.GL_COMPILE_STATUS)!=0,GL20.glGetShaderInfoLog(s));return s;}
 static void renderRing(StarfallGpuBuffers gpu,int pool,int header)throws Exception{
  int vs=graphicsStage("additive.vsh",GL20.GL_VERTEX_SHADER),fs=graphicsStage("additive.fsh",GL20.GL_FRAGMENT_SHADER),p=GL20.glCreateProgram();GL20.glAttachShader(p,vs);GL20.glAttachShader(p,fs);GL20.glLinkProgram(p);check(GL20.glGetProgrami(p,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(p));
  int vao=GL30.glGenVertexArrays();GL30.glBindVertexArray(vao);int fb=GL30.glGenFramebuffers(),tex=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,tex);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,128,128,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(ByteBuffer)null);GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fb);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,tex,0);
  int order=buffer(bytes(128*4));var index=bytes(128*4);for(int i=0;i<128;i++)index.putInt(i*4,i);put(order,index);bind(8,order);bind(1,pool);bind(5,header);gpu.bind(p,false);GL20.glUseProgram(p);
  GL41.glProgramUniformMatrix4fv(p,GL20.glGetUniformLocation(p,"ProjMat"),false,new Matrix4f().setOrtho(-4,4,-4,4,.1f,20).get(new float[16]));GL41.glProgramUniformMatrix4fv(p,GL20.glGetUniformLocation(p,"ModelViewMat"),false,new Matrix4f().lookAt(0,0,0,0,-1,0,0,0,-1).get(new float[16]));
  GL41.glProgramUniform3f(p,GL20.glGetUniformLocation(p,"uCamPos"),0,8,0);GL41.glProgramUniform3f(p,GL20.glGetUniformLocation(p,"uCamRight"),1,0,0);GL41.glProgramUniform3f(p,GL20.glGetUniformLocation(p,"uCamUp"),0,0,-1);f(p,"uTimeSec",12);f(p,"uFadeDist",128);f(p,"uGlow",1);
  GL11.glViewport(0,0,128,128);GL11.glClearColor(0,0,0,0);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);GL11.glEnable(GL11.GL_BLEND);GL11.glBlendFunc(GL11.GL_ONE,GL11.GL_ONE);GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES,0,6,128);
  var pixels=bytes(128*128*4);GL11.glReadPixels(0,0,128,128,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);int lit=0;var image=new java.awt.image.BufferedImage(128,128,java.awt.image.BufferedImage.TYPE_INT_RGB);
  for(int y=0;y<128;y++)for(int x=0;x<128;x++){int o=(y*128+x)*4,r=pixels.get(o)&255,g=pixels.get(o+1)&255,b=pixels.get(o+2)&255;if(b>8)lit++;image.setRGB(x,127-y,(r<<16)|(g<<8)|b);}
  check(lit>100,"ring missing from framebuffer");check((pixels.get((64*128+64)*4+2)&255)==0,"ring filled center");javax.imageio.ImageIO.write(image,"png",Path.of(System.getProperty("cmi.benchmark.output", "build/benchmarks/current")+"/starfall-ring-framebuffer.png").toFile());int currentState=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28);var census=bytes(StarfallGpuBuffers.DOME_JOIN*4).putInt(262400*4,127);put(currentState,census);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES,0,6,128);GL11.glReadPixels(0,0,128,128,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);for(int i=2;i<pixels.capacity();i+=4)check(pixels.get(i)==0,"partial circle rendered");census.putInt(262400*4,128);put(currentState,census);
  GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,0);GL11.glDisable(GL11.GL_BLEND);com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames.deleteTexture(tex);GL30.glDeleteFramebuffers(fb);GL15.glDeleteBuffers(order);com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames.deleteVertexArray(vao);{com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clearUniformProgram(p);deleteProgram(p);}GL20.glDeleteShader(vs);GL20.glDeleteShader(fs);GL20.glUseProgram(0);
 }
 static void contacts(){int p=compile("wavecontact.comp");try(var gpu=new StarfallGpuBuffers()){
  for(int n:new int[]{0,1,63,64,65}){int capacity=Math.max(1,n);var particles=bytes(capacity*64);for(int i=0;i<n;i++){particle(particles,i,2,0,0,20);particles.putFloat(i*64+4,.5f).putFloat(i*64+12,i+1);}
   int pool=buffer(particles),counter=buffer(bytes(16).putInt(0,n)),header=buffer(headers()),out=buffer(bytes(4+16384*24)),stamps=buffer(bytes(capacity*16));bind(1,pool);bind(3,counter);bind(5,header);bind(22,out);bind(30,stamps);
   stage(gpu,packet(n,17,2,0),p);int stateBuffer=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28);var phaseState=bytes(StarfallGpuBuffers.DOME_JOIN*4);for(int i=0;i<n;i++)phaseState.putInt(i*8,17).putInt(i*8+4,4);phaseState.putInt(262400*4,128);put(stateBuffer,phaseState);GL41.glProgramUniform4fv(p,GL20.glGetUniformLocation(p,"uWave"),new float[]{1,1,0,60,0,0,0,0,0,0,0,0,0,0,0,0});GL41.glProgramUniform4fv(p,GL20.glGetUniformLocation(p,"uWaveTarget"),new float[]{0,.5f,0,17,0,0,0,0,0,0,0,0,0,0,0,0});GL41.glProgramUniform3f(p,GL20.glGetUniformLocation(p,"uLocalPlayerPos"),0,.5f,0);
   check(GL20.glGetUniformi(p,GL20.glGetUniformLocation(p,"uStarfallEnabled"))==1,"starfall enable uniform not set");f(p,"uTimeSec",10.5f);dispatch(p,Math.max(1,(n+63)/64));check(read(out,4).getInt(0)==0,"warning damages player");f(p,"uTimeSec",12);phaseState.putInt(262400*4,127);put(stateBuffer,phaseState);dispatch(p,Math.max(1,(n+63)/64));check(read(out,4).getInt(0)==0,"incomplete warning caused damage");phaseState.putInt(262400*4,128);put(stateBuffer,phaseState);dispatch(p,Math.max(1,(n+63)/64));check(read(out,4).getInt(0)==n,"descent lost contacts");if(n>0)check(read(out,28).getFloat(24)==12,"missing sample time");
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
 static void benchmarkRings()throws Exception{
  var rows=new ArrayList<String>();rows.add("columns,particles,run,birth_gpu_p50_ms,birth_gpu_p95_ms,draw_gpu_p50_ms,draw_gpu_p95_ms,cpu_wall_p50_ms,cpu_wall_p95_ms,allocated_bytes_p50,command_bytes");
  int reserve=compile("starfall_ring_reserve.comp"),emit=compile("emit.comp"),vs=graphicsStage("additive.vsh",GL20.GL_VERTEX_SHADER),fs=graphicsStage("additive.fsh",GL20.GL_FRAGMENT_SHADER),render=GL20.glCreateProgram();GL20.glAttachShader(render,vs);GL20.glAttachShader(render,fs);GL20.glLinkProgram(render);check(GL20.glGetProgrami(render,GL20.GL_LINK_STATUS)!=0,"ring render link");
  int vao=GL30.glGenVertexArrays();GL30.glBindVertexArray(vao);int fb=GL30.glGenFramebuffers(),tex=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,tex);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,256,256,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(ByteBuffer)null);GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fb);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,tex,0);
  var h=bytes(320).putFloat(17*16+4,7).putFloat(5*16+8,.27f).putFloat(5*16+12,.27f).putFloat(6*16,1).putFloat(6*16+8,1).putFloat(6*16+12,1);int header=buffer(h);var zero=bytes(16);
  var thread=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();if(thread.isThreadAllocatedMemorySupported())thread.setThreadAllocatedMemoryEnabled(true);long tid=Thread.currentThread().threadId();
  try{for(int columns:new int[]{1,2,16,32}){
   int n=columns*128,pool=buffer(bytes(n*64)),counter=buffer(bytes(16));ByteBuffer cmd=bytes(columns*48),order=bytes(n*4);for(int c=0;c<columns;c++)cmd.putFloat(c*48+12,128).putFloat(c*48+20,.41f).putFloat(c*48+24,c*128).putFloat(c*48+32,1).putFloat(c*48+40,c);for(int i=0;i<n;i++)order.putInt(i*4,i);int commands=buffer(cmd),indices=buffer(order);
   try(var gpu=new StarfallGpuBuffers()){
    var original=packet(columns*512,1,2,0);float[] land=original.landings().clone();for(int c=0;c<columns;c++){land[c*4]=AllayStarfall.offsetX(c,1);land[c*4+2]=AllayStarfall.offsetZ(c,1);}var event=new ClientboundStormWavePacket(1,37,2,1,1,5,columns,512,0,4,10,60,0,12,0,4,original.members(),original.path(),land);
    stage(gpu,event,reserve);gpu.commit();gpu.prepare(2);gpu.bind(0,false);var census=bytes(StarfallGpuBuffers.DOME_JOIN*4);for(int c=0;c<columns;c++)census.putInt((262400+c)*4,128);put(GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28),census);ui(reserve,"uEmitCount",columns);ui(reserve,"uCapacity",n);f(reserve,"uTimeSec",12);ui(emit,"uCapacity",n);ui(emit,"uTotalSpawn",n);ui(emit,"uEmitCount",columns);f(emit,"uTimeSec",12);
    GL41.glProgramUniformMatrix4fv(render,GL20.glGetUniformLocation(render,"ProjMat"),false,new Matrix4f().setOrtho(-14,14,-14,14,.1f,40).get(new float[16]));GL41.glProgramUniformMatrix4fv(render,GL20.glGetUniformLocation(render,"ModelViewMat"),false,new Matrix4f().lookAt(0,0,0,0,-1,0,0,0,-1).get(new float[16]));GL41.glProgramUniform3f(render,GL20.glGetUniformLocation(render,"uCamPos"),0,20,0);GL41.glProgramUniform3f(render,GL20.glGetUniformLocation(render,"uCamRight"),1,0,0);GL41.glProgramUniform3f(render,GL20.glGetUniformLocation(render,"uCamUp"),0,0,-1);f(render,"uTimeSec",12);f(render,"uFadeDist",128);f(render,"uGlow",1);
    GL11.glViewport(0,0,256,256);GL11.glEnable(GL11.GL_BLEND);GL11.glBlendFunc(GL11.GL_ONE,GL11.GL_ONE);int birth=GL15.glGenQueries(),draw=GL15.glGenQueries();
    for(int run=0;run<3;run++){double[] bt=new double[120],dt=new double[120],ct=new double[120],at=new double[120];long warmUntil=System.nanoTime()+1_000_000_000L;
     for(int sample=-40;sample<120;sample++){long allocated=thread.isThreadAllocatedMemorySupported()?thread.getThreadAllocatedBytes(tid):0,start=System.nanoTime();
      GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,birth);put(counter,zero);bind(1,pool);bind(3,counter);bind(4,commands);bind(5,header);gpu.bind(reserve,true);GL20.glUseProgram(reserve);GL43.glDispatchCompute((columns+63)/64,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);gpu.bind(emit,true);GL20.glUseProgram(emit);GL43.glDispatchCompute((n+63)/64,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
      GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,draw);bind(8,indices);gpu.bind(render,false);GL20.glUseProgram(render);GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES,0,6,n);GL15.glEndQuery(GL33.GL_TIME_ELAPSED);long end=System.nanoTime(),allocatedEnd=thread.isThreadAllocatedMemorySupported()?thread.getThreadAllocatedBytes(tid):0;
      double b=GL33.glGetQueryObjectui64(birth,GL15.GL_QUERY_RESULT)/1e6,d=GL33.glGetQueryObjectui64(draw,GL15.GL_QUERY_RESULT)/1e6;if(sample==-1&&System.nanoTime()<warmUntil)sample=-2;if(sample>=0){bt[sample]=b;dt[sample]=d;ct[sample]=(end-start)/1e6;at[sample]=allocatedEnd-allocated;}
     }
     check(read(counter,16).getInt(0)==n,"ring benchmark incomplete reservation");check(GL11.glGetError()==0,"ring benchmark GL error");String row=columns+","+n+","+run+","+p(bt,.5)+","+p(bt,.95)+","+p(dt,.5)+","+p(dt,.95)+","+p(ct,.5)+","+p(ct,.95)+","+p(at,.5)+","+(columns*48);rows.add(row);System.out.println(row);
    }GL15.glDeleteQueries(birth);GL15.glDeleteQueries(draw);
   }finally{for(int id:new int[]{pool,counter,commands,indices})GL15.glDeleteBuffers(id);}
  }}finally{GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,0);GL11.glDisable(GL11.GL_BLEND);com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames.deleteTexture(tex);GL30.glDeleteFramebuffers(fb);com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames.deleteVertexArray(vao);GL15.glDeleteBuffers(header);for(int p:new int[]{reserve,emit,render}){com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clearUniformProgram(p);deleteProgram(p);}GL20.glDeleteShader(vs);GL20.glDeleteShader(fs);}
  Files.write(Path.of(System.getProperty("cmi.benchmark.output", "build/benchmarks/current")+"/starfall-ring-benchmark.csv"),rows);
 }
 public static void main(String[] args)throws Exception{GLFWErrorCallback.createPrint(System.err).set();check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(128,128,"Starfall GPU validation",0,0);
  try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();System.out.println(GL11.glGetString(GL11.GL_RENDERER));bodyHomeDuringWave();rings();contacts();formations();if(Arrays.asList(args).contains("--benchmark"))benchmarkStarfall();if(Arrays.asList(args).contains("--ring-benchmark"))benchmarkRings();check(GL11.glGetError()==0,"final GL state");System.out.println("Starfall GPU: "+checks+" assertions passed");}finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
 }
}
