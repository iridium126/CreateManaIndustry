import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.particles.engine.*;
import com.iridium126.createmanaindustry.client.allaystorm.*;
import com.iridium126.createmanaindustry.content.allaystorm.*;
import com.iridium126.createmanaindustry.content.allaystorm.network.*;

/** Real production kernels; no Minecraft window or mutable world. */
public class HelixGpuValidation extends DomeGpuValidation {
 static AllayHelix.Flight flight(int n,boolean curved){var ids=new int[4096];for(int i=0;i<4096;i++)ids[i]=i;return AllayHelix.linearFlight(new AllayHelix.Selection(0,3,ids,new int[]{2048,4096}),new net.minecraft.world.phys.Vec3(-24,28,0),new net.minecraft.world.phys.Vec3(1,-1,0).normalize(),80,12,10);}
 static ClientboundStormWavePacket helix(int n,int id,int event,int phase,boolean curved){var h=flight(n,curved);int[] ids=new int[4096];for(int i=0;i<4096;i++)ids[i]=i;
  return new ClientboundStormWavePacket(id,37,event,1,1,5,2,2048,0,10,10,40,0,40,0,32,ids,new float[]{0,40,0,-24,28,0},new float[8],2,phase,new int[]{2048,4096},-24,28,0,0,1,10,0,h);
 }
 static int helixMotion(){String nav=source("chunks/storm_navigation.glsl");nav=nav.substring(nav.indexOf("vec3 cmiStarRoute"));return compileText("helix motion",source("chunks/allay_storm.glsl")+nav+
  "\nlayout(local_size_x=64)in;layout(std430,binding=10)buffer Output{vec4 rows[];} result;uniform uint uCount;void main(){uint i=gl_GlobalInvocationID.x;if(i>=uCount)return;float identity=float(i+1u),seed=float(i)*.0001;vec3 target,velocity;uint phase=0u;float cap=cmiAttackSpeed(identity,uTimeSec,6.);bool engaged=cmiStarSteering(identity,seed,vec3(0),vec3(99,40,0),cap,target,velocity,phase);result.rows[i*3u]=vec4(target,float(phase));result.rows[i*3u+1u]=vec4(velocity,engaged?1.:0.);result.rows[i*3u+2u]=vec4(cmiAttackCarry(identity,uTimeSec),float((cmiStarAssignment(identity)>>3u)&31u),cap,cmiStarMarkerFade(1u<<7u,uTimeSec));}");}
 static void helixFormations()throws Exception{
  int p=helixMotion(),n=4096;try(var gpu=new StarfallGpuBuffers()){
   int output=buffer(bytes(n*48));bind(10,output);var snap=helix(n,1,2,4,false);stage(gpu,snap,p);ui(p,"uCount",n);var axis=AllayHelix.axis(snap.helix());var side=new net.minecraft.world.phys.Vec3(0,1,0).cross(axis).normalize();var up=axis.cross(side);
   for(float time:new float[]{0,5,9.999f,10,10.001f,12,15,19.5f,20}){
    f(p,"uTimeSec",time);dispatch(p,64);var rows=read(output,n*48);float elapsed=Math.max(0,time-10),progress=12*(elapsed-.075f*(1-(float)Math.pow(1-Math.clamp(elapsed/.15f,0,1),2)));
    int done=0;for(int i=0;i<n;i++){int o=i*48;assertFinite(rows,o,11);int phase=time<10?1:4;if(phase==6)done++;
     check(rows.getFloat(o+12)==phase,"per-member helix completion");check(rows.getFloat(o+28)==(phase==6?0:1),"completed member still steered");
     if(phase!=6){var expected=new net.minecraft.world.phys.Vec3(-24,28,0).add(axis.scale(HelixTemplate.coordinate(i,0)+(time<10?0:progress))).add(side.scale(HelixTemplate.coordinate(i,1))).add(up.scale(HelixTemplate.coordinate(i,2)));
      check(Math.abs(rows.getFloat(o)-expected.x)<.002&&Math.abs(rows.getFloat(o+4)-expected.y)<.002&&Math.abs(rows.getFloat(o+8)-expected.z)<.002,"cached logarithmic template differs on GPU");}
     float cap=phase==1||phase==4?12:6;check(rows.getFloat(o+40)==cap,"helix phase cap");
    }check(done==0,"reference progress completed an actual member");
   }
   gpu.commit();int old=gpu.committedBuffer();stage(gpu,helix(n,2,2,4,false),p);gpu.abort();check(gpu.committedBuffer()==old,"failed submission published");GL15.glDeleteBuffers(output);
  }finally{com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clearUniformProgram(p);GL20.glDeleteProgram(p);}
 }
 static void meshClearance()throws Exception{
  var field=AllayModelGeometry.class.getDeclaredField("VERTICES");field.setAccessible(true);float[] vertices=(float[])field.get(null);int count=vertices.length/7;
  int p=compileText("posed mesh clearance",source("chunks/allay_pose.glsl")+"layout(local_size_x=64)in;layout(std430,binding=12)readonly buffer Geo{float v[];}geo;layout(std430,binding=10)buffer Out{float radius[];}result;uniform uint uVertices;void main(){uint i=gl_GlobalInvocationID.x;if(i>=1024u)return;float age=float(i)*.017,seed=float(i)*.00097;float speeds[6]=float[6](0.,.05,.5,1.5,6.,12.);vec3 velocity=normalize(vec3(1,float(i%3u)-1.,0))*speeds[i%6u];float maximum=0.;for(uint j=0u;j<uVertices;j++){uint b=j*7u;int part=int(geo.v[b+5u]);if(part==7)continue;float yaw;mat4 pose=cmiAllayPartTransform(age,seed,velocity,0,part,yaw,float(i%2u));vec3 q=(pose*vec4(geo.v[b],geo.v[b+1u],geo.v[b+2u],1)).xyz/16.;q=vec3(-q.x,1.501-q.y,q.z)*(0.6/"+AllayModelGeometry.MODEL_ABOVE_FEET+");maximum=max(maximum,length(q-vec3(0,.3,0)));}result.radius[i]=maximum;}");
  var packed=bytes(vertices.length*4);for(float v:vertices)packed.putFloat(v);packed.flip();int geo=buffer(packed),out=buffer(bytes(1024*4));bind(12,geo);bind(10,out);ui(p,"uVertices",count);bool(p,"uStarfallEnabled",true);dispatch(p,16);var rows=read(out,1024*4);float maximum=0;for(int j=0;j<1024;j++)maximum=Math.max(maximum,rows.getFloat(j*4));System.out.println("Helix posed body+wing bounding radius="+maximum);check(maximum*2<HelixTemplate.SPACING,"posed mesh clearance exceeds template safety bound");GL15.glDeleteBuffers(geo);GL15.glDeleteBuffers(out);GL20.glDeleteProgram(p);
 }
 static void playerGrid(){
  int p=compileText("player grid", "layout(std430,binding=18)readonly buffer Players{vec4 pl[];}players;uniform int uPlayerCount;"+source("chunks/storm_players.glsl")+"layout(local_size_x=64)in;layout(std430,binding=10)buffer Output{uint hits[];}result;void main(){uint i=gl_GlobalInvocationID.x;if(i<8192u){float x=float(i)*.01-40.96;result.hits[i]=cmiNearPlayer(vec3(x,4,0),36.)?1u:0u;}}");
  int out=buffer(bytes(8192*4));bind(10,out);float[] xyz=new float[300];for(int i=0;i<99;i++){xyz[i*3]=1000+i*7;xyz[i*3+1]=4;}xyz[298]=4;
  var grid=new StormPlayerGrid();grid.build(xyz,100);var packed=bytes(grid.words()*4);for(int j=0;j<grid.words();j++)packed.putFloat(j*4,grid.data()[j]);int players=buffer(packed);bind(18,players);GL41.glProgramUniform1i(p,GL20.glGetUniformLocation(p,"uPlayerCount"),100);dispatch(p,128);var result=read(out,8192*4);
  for(int j=0;j<8192;j++){float x=j*.01f-40.96f;check(result.getInt(j*4)==(Math.abs(x)<6?1:0),"player #100 omitted or 6m boundary wrong");}GL15.glDeleteBuffers(out);GL15.glDeleteBuffers(players);GL20.glDeleteProgram(p);
 }
 static void completionReadback(){
  int p=compile("wavecontact.comp");try(var gpu=new StarfallGpuBuffers()){
   gpu.rebuild(StarfallColumnGpuValidation.columns());gpu.prepare(1);gpu.bind(p,true);
   int stateBuffer=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28);var state=bytes(StarfallGpuBuffers.DOME_JOIN*4);state.putInt((StarfallGpuBuffers.COMPLETIONS+StarfallGpuBuffers.completionOffset(67)+1)*4,-1);put(stateBuffer,state);
   int pool=buffer(bytes(64)),counter=buffer(bytes(16).putInt(0,1)),out=buffer(bytes(4+16384*24));bind(1,pool);bind(3,counter);bind(22,out);ui(p,"uLocalWaveMask",31);f(p,"uTimeSec",25);dispatch(p,1);var rows=read(out,28);
   check(rows.getInt(0)==1&&rows.getFloat(4)==-2&&rows.getFloat(8)==163,"high column completion identity");check(rows.getFloat(12)==1&&rows.getFloat(16)==65535&&rows.getFloat(20)==65535,"32-bit completion bitmap truncated");
   gpu.prepare(2);gpu.bind(p,true);put(out,bytes(4+16384*24));dispatch(p,1);check(read(out,4).getInt(0)==0,"completion accumulator survived a new frame");
   for(int b:new int[]{pool,counter,out})GL15.glDeleteBuffers(b);
  }finally{GL20.glDeleteProgram(p);}
 }
 static void armIdentity(){
  String text=source("chunks/allay_storm.glsl")+"\nlayout(local_size_x=64)in;layout(std430,binding=10)buffer R{uint arm[];} result;uniform float uStormSeed;void main(){uint i=gl_GlobalInvocationID.x;if(i>=131072u)return;precise float s0=cmiHash1(uStormSeed+31.7);precise float shift=s0*97.;precise float t=float(i)*.6123;precise float p=t+.7;precise float q=p+shift;precise float a=cmiHash1(q);precise float q2=q*7.31;precise float b=cmiHash1(q2);precise float ma=a*.618;precise float mb=b*.382;precise float seed=ma+mb;result.arm[i]=uint(floor(cmiHash1(seed*5.7)*6.));}";
  int p=compileText("actual arm",text),out=buffer(bytes(131072*4));try{bind(10,out);for(int seed:new int[]{0,1,16777215}){f(p,"uStormSeed",seed);dispatch(p,2048);var rows=read(out,131072*4);for(int i=0;i<131072;i++)check(rows.getInt(i*4)==AllayHelix.arm(seed,i),"CPU/GPU arm mismatch");}}finally{GL15.glDeleteBuffers(out);GL20.glDeleteProgram(p);}
 }
 static void sweep(){
  int p=compile("wavecontact.comp");try(var gpu=new StarfallGpuBuffers()){
   for(int n:new int[]{1,63,64,65}){
    int cap=n+2;ByteBuffer current=bytes(cap*64),old=bytes(cap*64);for(int i=0;i<cap;i++){particle(current,i,2,1.5f,0,20);particle(old,i,2,-1.5f,0,20);current.putFloat(i*64+28,-4).putFloat(i*64+4,.5f).putFloat(i*64+8,1.4f).putFloat(i*64+12,i+1);old.putFloat(i*64+4,.5f).putFloat(i*64+8,1.4f).putFloat(i*64+12,i+1);}
    current.putFloat(n*64+52,0);old.putFloat((n+1)*64+12,9999); // corpse and stale map
    int pool=buffer(current),previous=buffer(old),count=buffer(bytes(16).putInt(0,cap)),prevCount=buffer(bytes(16).putInt(0,cap)),header=buffer(headers()),out=buffer(bytes(4+16384*24)),map=buffer(bytes(cap*4)),stampsA=buffer(bytes(cap*16)),stampsB=buffer(bytes(cap*16));
    var mapping=bytes(cap*4);for(int i=0;i<cap;i++)mapping.putInt(i*4,i+1);put(map,mapping);bind(0,previous);bind(1,pool);bind(3,count);bind(5,header);bind(14,prevCount);bind(21,map);bind(22,out);bind(30,stampsA);
    stage(gpu,helix(512,17,2,3,false),p);gpu.helixReady(0,true);gpu.prepare(1);gpu.bind(p,true);int stateBuffer=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28);var state=bytes(StarfallGpuBuffers.DOME_JOIN*4);for(int i=0;i<cap;i++)state.putInt(i*8,17).putInt(i*8+4,4);put(stateBuffer,state);
    GL41.glProgramUniform4fv(p,GL20.glGetUniformLocation(p,"uWaveTarget"),new float[]{0,.5f,0,17,0,0,0,0,0,0,0,0,0,0,0,0});GL41.glProgramUniform3f(p,GL20.glGetUniformLocation(p,"uLocalPlayerPos"),0,.5f,0);ui(p,"uLocalWaveMask",1);f(p,"uDt",.25f);f(p,"uTimeSec",12.5f);
    dispatch(p,(cap+63)/64);check(read(out,4).getInt(0)==n,"low-FPS grazing sweep lost contact or required rings");var report=read(out,28);check(Math.abs(report.getFloat(12))<.0001,"sweep intersection wrong");check(Math.abs(report.getFloat(24)-12.375f)<.001,"sweep sample time wrong");
    put(out,bytes(4+16384*24));dispatch(p,(cap+63)/64);check(read(out,4).getInt(0)==0,"duplicate helix contact");bind(30,stampsB);dispatch(p,(cap+63)/64);check(read(out,4).getInt(0)==n,"victim B suppressed");
    put(out,bytes(4+16384*24));put(stampsB,bytes(cap*16));for(int i=0;i<cap;i++)state.putInt(i*8+4,4|AllayHelix.SWEEP_INVALID);put(stateBuffer,state);dispatch(p,(cap+63)/64);check(read(out,4).getInt(0)==0,"escape warp hurt player");
    for(int i=0;i<cap;i++)state.putInt(i*8+4,4);put(stateBuffer,state);gpu.helixReady(0,false);gpu.prepare(2);gpu.bind(p,true);dispatch(p,(cap+63)/64);check(read(out,4).getInt(0)==n,"global window gate rejected locally valid contacts");
    for(int b:new int[]{pool,previous,count,prevCount,header,out,map,stampsA,stampsB})GL15.glDeleteBuffers(b);
   }
  }finally{com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clearUniformProgram(p);GL20.glDeleteProgram(p);}
 }
 static void renderHelix(ByteBuffer rows,int count)throws Exception{
  // Use the existing framebuffer probe with a side view tailored to the long drill.
  var centered=bytes(rows.capacity());float min=Float.POSITIVE_INFINITY,max=Float.NEGATIVE_INFINITY,minY=Float.POSITIVE_INFINITY,maxY=Float.NEGATIVE_INFINITY;
  for(int i=0;i<count;i++){min=Math.min(min,rows.getFloat(i*48));max=Math.max(max,rows.getFloat(i*48));minY=Math.min(minY,rows.getFloat(i*48+4));maxY=Math.max(maxY,rows.getFloat(i*48+4));}
  for(int i=0;i<rows.capacity()/4;i++)centered.putInt(i*4,rows.getInt(i*4));
  for(int i=0;i<count;i++)centered.putFloat(i*48,(rows.getFloat(i*48)-(min+max)*.5f)/3).putFloat(i*48+4,(rows.getFloat(i*48+4)-(minY+maxY)*.5f)/3+5).putFloat(i*48+8,rows.getFloat(i*48+8)/3);
  renderDome(centered,count);Files.move(Path.of(System.getProperty("cmi.benchmark.output", "build/benchmarks/current")+"/dome-geometry-framebuffer.png"),Path.of(System.getProperty("cmi.benchmark.output", "build/benchmarks/current")+"/helix-geometry-framebuffer.png"),StandardCopyOption.REPLACE_EXISTING);
 }
 public static void main(String[] args)throws Exception{
  GLFWErrorCallback.createPrint(System.err).set();check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(512,384,"Helix GPU validation",0,0);
  try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();System.out.println(GL11.glGetString(GL11.GL_RENDERER));armIdentity();actualHelixExit();meshClearance();playerGrid();completionReadback();helixFormations();sweep();helixSimulationBanks();helixCarriers();if(Arrays.asList(args).contains("--benchmark"))benchmarkHelix();check(GL11.glGetError()==0,"helix final GL state");System.out.println("Helix GPU: "+checks+" assertions passed");}finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
 }
 static void actualHelixExit(){
  int update=compile("update.comp"),n=2;
  try(var fixture=new Fixture(n,n,n);var gpu=new StarfallGpuBuffers()){
   var packet=helix(n,1,2,4,false);var axis=AllayHelix.axis(packet.helix());var data=bytes(n*64);
   for(int member=0;member<n;member++){particle(data,member,2,0,0,20);var point=new net.minecraft.world.phys.Vec3(packet.centerX(),packet.centerY(),packet.centerZ()).add(axis.scale(member==0?-100:81));data.putFloat(member*64,(float)point.x).putFloat(member*64+4,(float)point.y).putFloat(member*64+8,(float)point.z).putFloat(member*64+12,member+1).putFloat(member*64+48,9999).putFloat(member*64+56,AllayStormWaves.memberSeed(1,member));}
   put(fixture.pool,data);put(fixture.previous,bytes(16).putInt(0,n));put(fixture.emit,headers());
   int output=buffer(bytes(n*64)),grid=buffer(bytes((ParticleBuffers.GRID_TABLE+n)*4)),damage=buffer(bytes(16)),players=buffer(bytes(16)),correction=buffer(bytes(n*32)),map=buffer(bytes(n*4)),identity=buffer(bytes(4+(n*2+ParticleBuffers.DAMAGE_QUEUE_CAP*2)*4));
   try{
    bind(0,fixture.pool);bind(1,output);bind(3,fixture.counter);bind(5,fixture.emit);bind(13,grid);bind(14,fixture.previous);bind(16,damage);bind(18,players);bind(19,correction);bind(21,map);bind(31,identity);
    gpu.rebuild(new ClientboundStormWavePacket[]{packet,null,null,null});gpu.prepare(1);gpu.bind(update,true);ui(update,"uCapacity",n);ui(update,"uKillEmit",-1);ui(update,"uIdentityReadBase",0);ui(update,"uIdentityWriteBase",n);f(update,"uDt",.05f);f(update,"uTimeSec",30);dispatch(update,1);
    int state=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28);var words=read(state,(StarfallGpuBuffers.COMPLETIONS+1)*4);check((words.getInt(4)&255)==4,"member before actual plane completed by reference time");check((words.getInt(12)&255)==6,"actual plane crossing did not complete");check(words.getInt(StarfallGpuBuffers.COMPLETIONS*4)==2,"actual completion ranks wrong");
    var result=read(output,n*64);for(int index=0;index<n;index++)if(Math.round(result.getFloat(index*64+12))==2)check(result.getFloat(index*64+28)==0,"completed helix retained sword marker");
   }finally{for(int buffer:new int[]{output,grid,damage,players,correction,map,identity})GL15.glDeleteBuffers(buffer);}
  }finally{com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clearUniformProgram(update);GL20.glDeleteProgram(update);}
 }
 static void helixSimulationBanks(){
  int n=65,update=compile("update.comp");try(var q=new Fixture(n,n,n);var gpu=new StarfallGpuBuffers()){
   var data=bytes(n*64);for(int i=0;i<n;i++){particle(data,i,i<63?2:1,0,0,i==63?10:i==64?-.3f:20);data.putFloat(i*64+4,4).putFloat(i*64+12,i<63?i+1:1).putFloat(i*64+48,9999).putFloat(i*64+56,AllayStormWaves.memberSeed(1,i));}
   put(q.pool,data);var h=headers();h.putFloat(2*320+18*16+12,6);put(q.emit,h);
   int output=buffer(bytes(n*64)),grid=buffer(bytes((ParticleBuffers.GRID_TABLE+n)*4)),damage=buffer(bytes(16+ParticleBuffers.DAMAGE_ENTRY_BYTES)),players=buffer(bytes(16)),correction=buffer(bytes(n*32)),map=buffer(bytes(n*4));
   int identity=buffer(bytes(4+(n*2+ParticleBuffers.DAMAGE_QUEUE_CAP*2)*4).putInt(4+63*4,101).putInt(4+64*4,202));
   try{
    // A deliberately large network correction used to bypass the servo velocity cap.
    var corrections=bytes(n*32).putFloat(0,100).putFloat(4,100).putFloat(8,100).putFloat(12,12).putFloat(16,60).putFloat(28,1);put(correction,corrections);
    ui(update,"uCapacity",n);ui(update,"uKillEmit",-1);ui(update,"uIdentityReadBase",0);ui(update,"uIdentityWriteBase",n);f(update,"uDt",.05f);
    gpu.rebuild(new ClientboundStormWavePacket[]{helix(768,1,2,3,false),null,null,null});int live=n;
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
      if(header==2&&result.getFloat(o+52)>0){float vx=result.getFloat(o+16),vy=result.getFloat(o+20),vz=result.getFloat(o+24);check(vx*vx+vy*vy+vz*vz<=144.001,"actual network-corrected helix exceeds speed cap");}
      int token=ids.getInt(4+(n+i)*4);nextIds.putInt(4+i*4,token);
      if(header==1&&token==101){normal=true;check(Math.abs(result.getFloat(o+52)-(10+(frame+1)*.1f))<.001,"ordinary MODEL regen/identity changed by helix");check(result.getFloat(o+48)>9999,"ordinary MODEL lost permanent life");}
      if(header==2&&Math.round(result.getFloat(o+12))==3){damaged=true;check(Math.abs(result.getFloat(o+52)-Math.min(20,15+(frame+1)*.1f))<.001,"storm HP/regen not retained");}
     }
     check(normal&&damaged,"MODEL or storm identity disappeared");put(identity,nextIds);put(q.pool,result);
     if(frame==0){gpu.bind(0,false);int state=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28);check(read(state,24).getInt(20)==4,"phase did not travel with committed bank");}
    }
    check(live==63,"corpse expiration or permanent members incorrect");
   }finally{for(int b:new int[]{output,grid,damage,players,correction,map,identity})GL15.glDeleteBuffers(b);}
  }finally{com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clearUniformProgram(update);GL20.glDeleteProgram(update);}
 }


 static void helixCarriers(){
  int n=4096;try(var q=new Fixture(n,n,n);var gpu=new StarfallGpuBuffers();var shadow=new ModelShadowGpu(n,ParticleBuffers.CARRIER_CAP+n,HelixGpuValidation::source)){
   var particles=bytes(n*64);for(int i=0;i<n;i++){particle(particles,i,2,0,0,20);particles.putFloat(i*64+12,n-i).putFloat(i*64+28,i%2==0?-4:0);}
   q.build(particles,n);gpu.rebuild(new ClientboundStormWavePacket[]{helix(n,1,2,3,true),null,null,null});gpu.prepare(1);gpu.commit();shadow.starfall(gpu.committedBuffer());
   var c=new com.iridium126.createmanaindustry.client.packages.render.PackageDrawCulling();c.distance=-1;c.safe=-1;
   byte[] main=new byte[(n+ParticleBuffers.CARRIER_CAP+1)*8];read(q.sorted,main.length).get(main);
   for(float time:new float[]{7,8.5f,12,50}){
    shadow.prepare(q.pool,q.emit,q.counter,c,0,0,0,.35f,.6f,1,time,new float[16],new float[16],new float[4],q.pop,7);
    var commands=read(shadow.commands(),(ParticleBuffers.INDIRECT_UINTS+4)*4);check(commands.getInt(ParticleBuffers.IDX_CNT_MODELOP*4)==n,"helix shadow body lost");check(commands.getInt(ParticleBuffers.IDX_CNT_CARRIER*4)==(time==12?n/2:0),"helix carrier phase wrong");
    byte[] after=new byte[main.length];read(q.sorted,after.length).get(after);check(Arrays.equals(main,after),"helix shadow overwrote main sort");
   }
   particles.putFloat(52,-.5f);put(q.pool,particles);shadow.prepare(q.pool,q.emit,q.counter,c,0,0,0,.35f,.6f,1,12,new float[16],new float[16],new float[4],q.pop,7);
   check(read(shadow.commands(),(ParticleBuffers.INDIRECT_UINTS+4)*4).getInt(ParticleBuffers.IDX_CNT_CARRIER*4)==n/2-1,"helix corpse retains sword");
  }
 }

 static void benchmarkHelix()throws Exception{
  var rows=new ArrayList<String>();rows.add("pool_live,participants,mode,run,update_gpu_p50_ms,update_gpu_p95_ms,shadow_gpu_p50_ms,shadow_gpu_p95_ms,cull_gpu_p50_ms,cull_gpu_p95_ms,contact_gpu_p50_ms,contact_gpu_p95_ms,cpu_wall_p50_ms,cpu_wall_p95_ms,thread_cpu_p50_ms,thread_cpu_p95_ms,allocated_bytes_p50,roster_upload_bytes,state_domain_bytes");
  var raw=new ArrayList<String>();raw.add("pool_live,participants,mode,run,sample,update_gpu_ms,shadow_gpu_ms,cull_gpu_ms,contact_gpu_ms,cpu_wall_ms,thread_cpu_ms,allocated_bytes");
  var thread=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();if(thread.isThreadCpuTimeSupported())thread.setThreadCpuTimeEnabled(true);if(thread.isThreadAllocatedMemorySupported())thread.setThreadAllocatedMemoryEnabled(true);long tid=Thread.currentThread().threadId();
  for(int members:new int[]{4096})for(int total:new int[]{members,2000000}){
   try(var q=new Fixture(total,members,members);var gpu=new StarfallGpuBuffers();var shadow=new ModelShadowGpu(total,ParticleBuffers.CARRIER_CAP+members,StarfallGpuValidation::source)){
    var data=bytes(total*64);for(int i=0;i<total;i++){particle(data,i,i<members?2:0,i<members?(i%17-8)*.18f:20,i<members?((i/17)%17-8)*.18f:20,i<members?20:100);if(i<members){data.putFloat(i*64+4,4).putFloat(i*64+12,i+1).putFloat(i*64+56,AllayStormWaves.memberSeed(1,i));}}
    q.build(data,total);put(q.previous,bytes(16).putInt(0,total));var h=headers();h.putFloat(18*16+2*320+12,6);put(q.emit,h);
    int output=buffer(bytes(total*64)),grid=buffer(bytes((ParticleBuffers.GRID_TABLE+total)*4)),damage=buffer(bytes(16)),players=buffer(bytes(16)),correction=buffer(bytes(total*32)),map=buffer(bytes(total*4));
    int build=compile("gridbuild.comp"),update=compile("update.comp"),contact=compile("wavecontact.comp");int contactsOut=buffer(bytes(4+16384*24)),stamps=buffer(bytes(members*16));
    gpu.rebuild(new ClientboundStormWavePacket[]{helix(members,1,2,3,true),null,null,null});gpu.helixReady(0,true);gpu.prepare(1);gpu.commit();gpu.prepare(2);
    int carrierCapacity=ParticleBuffers.CARRIER_CAP+members;for(int buffer:new int[]{q.sorted,q.carriers}){GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,(total+carrierCapacity+1)*8L,GL15.GL_DYNAMIC_COPY);}ui(q.keygen,"uCarrierLimit",carrierCapacity);f(q.keygen,"uTimeSec",12);
    ui(build,"uCapacity",total);bool(build,"uPopulationReadEnabled",true);gen(build,"uPopulationGeneration",7);
    ui(update,"uCapacity",total);ui(update,"uKillEmit",-1);f(update,"uDt",.05f);f(update,"uTimeSec",12);f(update,"uStormOmegaNow",.1f);
    GL41.glProgramUniform4fv(update,GL20.glGetUniformLocation(update,"uWave"),new float[]{1,1,0,60,0,0,0,0,0,0,0,0,0,0,0,0});GL41.glProgramUniform4fv(update,GL20.glGetUniformLocation(update,"uWaveTarget"),new float[]{0,.5f,0,1,0,0,0,0,0,0,0,0,0,0,0,0});
    GL41.glProgramUniform4fv(update,GL20.glGetUniformLocation(update,"uWavePath"),new float[]{0,12,0,1,0,10.5f,0,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0});
    var cull=new com.iridium126.createmanaindustry.client.packages.render.PackageDrawCulling();cull.distance=-1;cull.safe=-1;
    var zero=bytes(16);var liveCounter=bytes(16).putInt(0,total);float[] emptyWaves=new float[]{1,1,0,60,0,0,0,0,0,0,0,0,0,0,0,0},emptyTargets=new float[]{0,.5f,0,1,0,0,0,0,0,0,0,0,0,0,0,0},emptyTiers=new float[]{5,0,0,0};int updateQuery=GL15.glGenQueries(),shadowQuery=GL15.glGenQueries(),cullQuery=GL15.glGenQueries(),contactQuery=GL15.glGenQueries();
    bool(contact,"uPopulationReadEnabled",true);gen(contact,"uPopulationGeneration",7);ui(contact,"uLocalWaveMask",1);f(contact,"uTimeSec",12);f(contact,"uDt",.05f);GL41.glProgramUniform3f(contact,GL20.glGetUniformLocation(contact,"uLocalPlayerPos"),-1000,0,0);GL41.glProgramUniform4fv(contact,GL20.glGetUniformLocation(contact,"uWaveTarget"),emptyTargets);
    for(boolean helixMode:new boolean[]{false,true}){
     var packet=helixMode?helix(members,1,2,3,true):starBenchmarkPacket(members);
     gpu.rebuild(new ClientboundStormWavePacket[]{packet,null,null,null});gpu.helixReady(0,true);gpu.prepare(1);gpu.commit();gpu.prepare(2);
     boolean star=true;
     for(int run=0;run<3;run++){
     double[] ug=new double[120],sg=new double[120],kg=new double[120],cg=new double[120],cw=new double[120],tc=new double[120],alloc=new double[120];
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
      GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,contactQuery);bind(0,q.pool);bind(1,q.pool);bind(3,q.counter);bind(5,q.emit);bind(14,q.previous);bind(21,map);bind(22,contactsOut);bind(30,stamps);bind(24,q.pop);gpu.bind(contact,true);GL20.glUseProgram(contact);GL43.glDispatchCompute((members+63)/64,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
      long end=System.nanoTime(),cpuEnd=thread.getCurrentThreadCpuTime(),allocEnd=thread.isThreadAllocatedMemorySupported()?thread.getThreadAllocatedBytes(tid):0;
      double contactMs=GL33.glGetQueryObjectui64(contactQuery,GL15.GL_QUERY_RESULT)/1e6;double cullMs=GL33.glGetQueryObjectui64(cullQuery,GL15.GL_QUERY_RESULT)/1e6;double updateMs=GL33.glGetQueryObjectui64(updateQuery,GL15.GL_QUERY_RESULT)/1e6,shadowMs=GL33.glGetQueryObjectui64(shadowQuery,GL15.GL_QUERY_RESULT)/1e6;
      if(sample==-1&&System.nanoTime()<warmUntil)sample=-2;
      if(sample>=0){ug[sample]=updateMs;sg[sample]=shadowMs;kg[sample]=cullMs;cg[sample]=contactMs;cw[sample]=(end-begin)/1e6;tc[sample]=(cpuEnd-cpuBefore)/1e6;alloc[sample]=allocEnd-allocBefore;raw.add(total+","+members+","+(helixMode?"helix":"starfall")+","+run+","+sample+","+updateMs+","+shadowMs+","+cullMs+","+contactMs+","+cw[sample]+","+tc[sample]+","+alloc[sample]);}
     }
     check(GL11.glGetError()==0,"benchmark GL error");String row=total+","+members+","+(helixMode?"helix":"starfall")+","+run+","+p(ug,.5)+","+p(ug,.95)+","+p(sg,.5)+","+p(sg,.95)+","+p(kg,.5)+","+p(kg,.95)+","+p(cg,.5)+","+p(cg,.95)+","+p(cw,.5)+","+p(cw,.95)+","+p(tc,.5)+","+p(tc,.95)+","+p(alloc,.5)+","+gpu.uploadedBytes()+","+gpu.bytes();rows.add(row);System.out.println(row);
    }
    }
    GL15.glDeleteQueries(contactQuery);GL15.glDeleteBuffers(contactsOut);GL15.glDeleteBuffers(stamps);GL15.glDeleteQueries(cullQuery);GL15.glDeleteQueries(updateQuery);GL15.glDeleteQueries(shadowQuery);for(int id:new int[]{output,grid,damage,players,correction,map})GL15.glDeleteBuffers(id);for(int p:new int[]{build,update,contact}){com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clearUniformProgram(p);GL20.glDeleteProgram(p);}for(int p:new int[]{q.reset,q.keygen,q.prepare})com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clearUniformProgram(p);
   }
  }
  Files.write(Path.of(System.getProperty("cmi.benchmark.output", "build/benchmarks/current")+"/helix-benchmark.csv"),rows);Files.write(Path.of(System.getProperty("cmi.benchmark.output", "build/benchmarks/current")+"/helix-benchmark-samples.csv"),raw);
 }

}
