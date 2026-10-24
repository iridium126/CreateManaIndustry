import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.allaystorm.*;
import com.iridium126.createmanaindustry.client.particles.engine.*;
import com.iridium126.createmanaindustry.content.allaystorm.network.*;

/** Real production kernels; the old roof deadlock is an expected failing control. */
public class StormDepenetrationGpuValidation extends StarfallGpuValidation {
 static final int MEMBER=64;static int prepare;
 static Map<StormCollisionPages.Section,StormCollisionPages.Page> terrain(Predicate<int[]> solid,boolean unknown){
  var result=new LinkedHashMap<StormCollisionPages.Section,StormCollisionPages.Page>();
  for(int x=-2;x<=2;x++)for(int y=-2;y<=2;y++)for(int z=-2;z<=2;z++){
   if(unknown&&x<0)continue;int[] bits=new int[128];
   for(int cell=0;cell<4096;cell++)if(solid.test(new int[]{x*16+(cell&15),y*16+(cell>>>8),z*16+((cell>>>4)&15)}))bits[cell>>>5]|=1<<(cell&31);
   result.put(new StormCollisionPages.Section(x,y,z),StormCollisionPages.page(1,bits));
  }return result;
 }
 static String frozen(String name){
  String text=ParticleShaderSource.loadParticle(name,path->{try{return Files.readString(Path.of("build/cold-escape/baseline/resources/assets/createmanaindustry",path));}catch(Exception failure){throw new IllegalStateException(failure);}});
  for(String phase:List.of("spawn","update"))text=text.replace("#pragma cmi_types "+phase,com.iridium126.createmanaindustry.client.particles.emitter.ParticleTypes.shaderHooks(phase));
  return text;
 }
 static int frozenProgram(String name)throws Exception{
  String type=ParticlePrograms.class.getName(),prelude;
  try(var loader=new java.net.URLClassLoader(new java.net.URL[]{Path.of("build/cold-escape/baseline/classes").toUri().toURL()},ParticlePrograms.class.getClassLoader()){
   @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
    if(name.equals(type)||name.startsWith(type+"$")){synchronized(getClassLoadingLock(name)){Class<?> result=findLoadedClass(name);if(result==null)result=findClass(name);if(resolve)resolveClass(result);return result;}}
    return super.loadClass(name,resolve);
   }
  }){prelude=(String)Class.forName(type,true,loader).getMethod("commonPrelude").invoke(null);}
  int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);GL20.glShaderSource(shader,"#version 450 core\n"+prelude+frozen(name));GL20.glCompileShader(shader);check(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)!=0,GL20.glGetShaderInfoLog(shader));int program=GL20.glCreateProgram();GL20.glAttachShader(program,shader);GL20.glLinkProgram(program);check(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(program));GL20.glDeleteShader(shader);return program;
 }
 static class Rig implements AutoCloseable{
  final Fixture fixture=new Fixture(32,32,32);final StarfallGpuBuffers stars=new StarfallGpuBuffers();final StormCollisionGpu collision=new StormCollisionGpu();
  final int output=fixture.alloc(bytes(32*64)),grid=fixture.alloc(bytes((ParticleBuffers.GRID_TABLE+32)*4)),damage=fixture.alloc(bytes(16)),players=fixture.alloc(bytes(16)),correction=fixture.alloc(bytes(128*32)),map=fixture.alloc(bytes(128*4));
  Rig(){var packets=new ClientboundStormWavePacket[68];packets[4]=StarfallColumnGpuValidation.column(0).snapshot();stars.rebuild(packets);var header=headers();header.putFloat(2*320+7*16+4,1).putFloat(2*320+18*16+4,2).putFloat(2*320+18*16+12,6).putFloat(2*320+19*16+4,4);put(fixture.emit,header);}
  void bindings(int count){bind(0,fixture.pool);bind(1,output);bind(3,fixture.counter);bind(5,fixture.emit);bind(13,grid);bind(14,fixture.previous);bind(16,damage);bind(18,players);bind(19,correction);bind(21,map);put(fixture.previous,bytes(16).putInt(0,count));}
  void publish(Map<StormCollisionPages.Section,StormCollisionPages.Page> geometry,int program){DomePathGpuValidation.publish(collision,geometry,program);}
  void depenetrate(int schedule,int search,int frame,float clock){
   for(int p:new int[]{prepare,schedule,search}){stars.bind(p,true);f(p,"uTimeSec",clock);}bool(search,"uStormCollisionEnabled",true);collision.bind(search);StormDepenetration.dispatch(prepare,schedule,search,stars);
  }
  public void close(){collision.close();stars.close();fixture.close();}
 }
 static void roof(int update,int schedule,int search,int probe,boolean old)throws Exception{
  try(var r=new Rig()){
   r.publish(terrain(p->p[1]==1,false),update);bool(update,"uStormCollisionEnabled",true);ui(update,"uCapacity",32);ui(update,"uKillEmit",-1);f(update,"uDt",.05f);
   var data=bytes(32*64);particle(data,0,2,.5f,.5f,20);data.putFloat(4,.65f).putFloat(12,MEMBER+1);
   for(int frame=0;frame<(old?20:610);frame++){
    float clock=frame*.05f;r.stars.prepare(frame+1);r.stars.bind(update,true);r.bindings(1);put(r.fixture.pool,data);put(r.fixture.counter,bytes(16));
    if(frame==0)DomePathGpuValidation.upload(GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,27),MEMBER*2,bytes(8).putInt(0,777).putInt(4,6));
    r.collision.bind(update);f(update,"uTimeSec",clock);dispatch(update,1);
    if(!old)r.depenetrate(schedule,search,frame,clock);
    data=read(r.output,32*64);check(read(r.fixture.counter,16).getInt(0)==1,"Roof case lost a member");
    bool(probe,"uStormCollisionEnabled",true);r.collision.bind(probe);bind(1,r.output);dispatch(probe,1);var body=read(r.damage,16);
    if(old)check(body.getInt(0)==1,"Old kernel no longer reproduces the stationary roof embed");
    else check(body.getInt(0)==0,"Body remained/re-entered roof at frame "+frame);
    r.stars.commit();
   }
   System.out.println(old?"Expected baseline failure reproduced: feet in air, head embedded for 1s":"Roof embed corrected in first frame; no re-embedding during 30s");
  }
 }
 static void exits(int schedule,int search,int probe)throws Exception{
  for(int scenario=0;scenario<11;scenario++)try(var r=new Rig()){
   final int mode=scenario;
   Predicate<int[]> shape=switch(mode){
    case 0,1,2,3->p->p[1]==1;
    case 4->p->p[0]==0;
    case 5->p->p[0]==0||p[2]==0;
    case 6->p->true;
    case 7->p->Math.abs(p[0])<10&&Math.abs(p[1])<10&&Math.abs(p[2])<10;
    case 8->p->!(p[0]>=1&&p[1]>=1&&p[2]>=1);
    case 9->p->p[1]==1;
    default->p->p[0]==1;
   };
   r.publish(terrain(shape,scenario==9),search);
   float x=scenario==1?-.01f:scenario==2?15.99f:.5f,y=.65f,z=scenario==3?-.01f:.5f;
   if(scenario==9)x=0;if(scenario==10)x=.5f;
   int n=scenario==0?32:1;var data=bytes(32*64);
   for(int member=0;member<n;member++){particle(data,member,2,x,z,20);data.putFloat(member*64+4,y).putFloat(member*64+12,MEMBER+member+1).putFloat(member*64+16,2).putFloat(member*64+20,3).putFloat(member*64+24,4);}
   put(r.output,data);r.bindings(n);put(r.fixture.counter,bytes(16).putInt(0,n));
   var seen=new BitSet();
   for(int frame=0;frame<10;frame++){
    r.stars.prepare(frame+1);r.stars.bind(search,true);int state=r.stars.candidateStateBuffer();
    DomePathGpuValidation.upload(state,StarfallGpuBuffers.DEPEN_CONTROL+9,bytes(4).putInt(0,n-seen.cardinality()));
    int pendingMask=n==32?-1:(1<<n)-1;for(int member=seen.nextSetBit(0);member>=0;member=seen.nextSetBit(member+1))pendingMask&=~(1<<member);
    DomePathGpuValidation.upload(state,StarfallGpuBuffers.DEPEN_MASK+MEMBER/32,bytes(4).putInt(0,pendingMask));
    for(int member=0;member<n;member++){
     int id=MEMBER+member;var extra=bytes(64).putInt(4,member+1).putFloat(44,0).putInt(60,frame+2);
     // Preserve failed-search cooldowns across committed banks.
     if(frame>0)extra.putFloat(12,DomePathGpuValidation.range(state,StarfallGpuBuffers.NAV_EXTRA+id*16+3,1).getFloat(0));
     DomePathGpuValidation.upload(state,StarfallGpuBuffers.NAV_EXTRA+id*16,extra);
    }
    r.depenetrate(schedule,search,frame,frame*.05f);
    var result=read(r.output,n*64);
    for(int member=0;member<n;member++){
     double distance=Math.sqrt(Math.pow(result.getFloat(member*64)-x,2)+Math.pow(result.getFloat(member*64+4)-y,2)+Math.pow(result.getFloat(member*64+8)-z,2));
     if(distance>.00001){check(distance<=8.0001,"Correction exceeded 8m");seen.set(member);}
    }
    r.stars.commit();
   }
   boolean expected=scenario!=6&&scenario!=7&&scenario!=9&&scenario!=10;
   check(expected?seen.cardinality()==n:seen.isEmpty(),"Exit/unknown/normal blocker outcome scenario="+scenario+" corrected="+seen.cardinality());
   if(scenario==0){var result=read(r.output,n*64);for(int member=0;member<n;member++)check(result.getFloat(member*64+16)==2&&result.getFloat(member*64+20)==0&&result.getFloat(member*64+24)==4,"Correction discarded tangential velocity");}
  }
 }
 static void lifecycle(int schedule,int search)throws Exception{
  for(int mode=0;mode<5;mode++)try(var r=new Rig()){
   r.publish(terrain(p->p[1]==1,false),search);var data=bytes(32*64);particle(data,0,2,.5f,.5f,mode==3?0:20);data.putFloat(4,.65f).putFloat(12,mode==4?MEMBER+2:MEMBER+1).putFloat(16,2).putFloat(20,3).putFloat(24,4).putFloat(28,-4);
   put(r.fixture.pool,data);put(r.output,data);r.bindings(1);put(r.fixture.counter,bytes(16).putInt(0,1));r.stars.prepare(1);r.stars.bind(search,true);int state=r.stars.candidateStateBuffer(),record=StarfallGpuBuffers.DOME_PATH_BASE+MEMBER*12,extra=StarfallGpuBuffers.NAV_EXTRA+MEMBER*16,status=Math.min(mode+1,3);
   DomePathGpuValidation.upload(state,StarfallGpuBuffers.DEPEN_MASK+MEMBER/32,bytes(4).putInt(0,1));DomePathGpuValidation.upload(state,StarfallGpuBuffers.DEPEN_CONTROL+9,bytes(4).putInt(0,1));
   DomePathGpuValidation.upload(state,extra,bytes(64).putInt(0,2).putInt(4,1).putFloat(16,1).putFloat(28,5).putInt(60,2));DomePathGpuValidation.upload(state,record,bytes(48).putInt(0,777).putInt(4,(MEMBER<<15)|status).putFloat(8,.25f).putFloat(36,-3.5f).putFloat(40,-3.35f).putFloat(44,-3.5f));
   DomePathGpuValidation.upload(state,StarfallGpuBuffers.GATHER_STREAK+MEMBER,bytes(4).putInt(0,3));
   if(status==1){DomePathGpuValidation.upload(state,StarfallGpuBuffers.DOME_PATH_REQUESTS+MEMBER/32,bytes(4).putInt(0,1));DomePathGpuValidation.upload(state,StarfallGpuBuffers.DOME_PATH_CONTROL+1,bytes(4).putInt(0,1));}
   r.depenetrate(schedule,search,0,1);ByteBuffer result=read(r.output,64),path=DomePathGpuValidation.range(state,record,12),motion=DomePathGpuValidation.range(state,extra,16);
   if(mode<3){
    check(result.getFloat(4)<.5&&result.getFloat(28)==0,"Correction retained contact marker or failed");check((path.getInt(4)&15)==(status==3?0:status),"Correction cancelled queued/in-flight search or retained stale path");check(path.getFloat(8)==.25f,"Correction reordered the queued request");
    check(DomePathGpuValidation.range(state,StarfallGpuBuffers.GATHER_STREAK+MEMBER,1).getInt(0)==0,"Correction retained readiness history");check(motion.getInt(60)==0x80000002,"Correction did not stamp the output generation");
    for(int k=4;k<=7;k++)check(motion.getInt(k*4)==0,"Correction retained obsolete avoidance direction");
    if(status==1){check(Math.abs(path.getFloat(40)-(result.getFloat(4)-4))<.00001,"Queued search retained pre-correction origin");check(DomePathGpuValidation.range(state,StarfallGpuBuffers.DOME_PATH_REQUESTS+MEMBER/32,1).getInt(0)==1,"Correction cancelled queued search bit");}
    if(status==2)check(Math.abs(path.getFloat(40)+3.35)<.00001,"Correction moved active search window");
   }else check(result.getFloat(4)==.65f,"Dead or mismatched member consumed a correction");
   var untouched=read(r.fixture.pool,data.capacity());for(int i=0;i<data.capacity();i++)check(untouched.get(i)==data.get(i),"Correction mutated committed input particles");
   r.stars.abort();r.stars.prepare(1);check(DomePathGpuValidation.range(r.stars.candidateStateBuffer(),StarfallGpuBuffers.DEPEN_MASK+MEMBER/32,1).getInt(0)==0,"Rollback leaked pending correction mask");check(DomePathGpuValidation.range(r.stars.candidateStateBuffer(),extra+15,1).getInt(0)==0,"Rollback leaked corrected generation");
  }
 }
 static void performance(int schedule,int search)throws Exception{
  var rows=new ArrayList<String>();rows.add("tasks,terrain,gpu_p50_ms,gpu_p95_ms,cpu_p95_ms");
  for(int n:new int[]{0,1,16})for(boolean sealed:new boolean[]{false,true})try(var r=new Rig()){
   // Mixed pages force real voxel reads throughout the full eight-metre search.
   r.publish(terrain(p->sealed?Math.abs(p[0])<10&&Math.abs(p[1])<10&&Math.abs(p[2])<10:p[1]==1,false),search);r.bindings(n);put(r.fixture.counter,bytes(16).putInt(0,n));
   var data=bytes(32*64);for(int i=0;i<n;i++){particle(data,i,2,.5f,.5f,20);data.putFloat(i*64+4,.65f).putFloat(i*64+12,MEMBER+i+1);}
   double[] gpu=new double[120],cpu=new double[120];int query=GL15.glGenQueries();
   try{for(int sample=-30;sample<120;sample++){
    r.stars.prepare(1);r.stars.bind(search,true);int state=r.stars.candidateStateBuffer();put(r.output,data);
    DomePathGpuValidation.upload(state,StarfallGpuBuffers.DEPEN_CONTROL,bytes(64).putInt(36,n));DomePathGpuValidation.upload(state,StarfallGpuBuffers.DEPEN_MASK+MEMBER/32,bytes(4).putInt(0,(1<<n)-1));
    for(int i=0;i<n;i++)DomePathGpuValidation.upload(state,StarfallGpuBuffers.NAV_EXTRA+(MEMBER+i)*16,bytes(64).putInt(4,i+1).putInt(60,2));
    GL11.glFinish();GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,query);long start=System.nanoTime();r.depenetrate(schedule,search,0,1);double submitted=(System.nanoTime()-start)/1e6;GL15.glEndQuery(GL33.GL_TIME_ELAPSED);double elapsed=GL33.glGetQueryObjectui64(query,GL15.GL_QUERY_RESULT)/1e6;
    if(sample>=0){gpu[sample]=elapsed;cpu[sample]=submitted;}r.stars.abort();
   }}finally{GL15.glDeleteQueries(query);}
   String row=n+","+(sealed?"sealed-mixed":"roof")+","+StormBenchmark.percentile(gpu,.5)+","+StormBenchmark.percentile(gpu,.95)+","+StormBenchmark.percentile(cpu,.95);rows.add(row);System.out.println("DEPEN COST "+row);
  }
  Files.write(Path.of(System.getProperty("cmi.benchmark.output"),"depenetration-cost.csv"),rows);
 }
 static void rejectedBeforePublication(int schedule,int search)throws Exception{
  try(var r=new Rig()){
   r.publish(terrain(p->p[1]==1,false),search);r.bindings(1);
   var data=bytes(32*64);particle(data,0,2,.5f,.5f,20);data.putFloat(4,.65f).putFloat(12,MEMBER+1);put(r.fixture.pool,data);put(r.output,data);put(r.fixture.counter,bytes(16).putInt(0,1));
   r.stars.prepare(1);r.stars.bind(search,true);int state=r.stars.candidateStateBuffer(),extra=StarfallGpuBuffers.NAV_EXTRA+MEMBER*16;
   DomePathGpuValidation.upload(state,StarfallGpuBuffers.DEPEN_MASK+MEMBER/32,bytes(4).putInt(0,1));DomePathGpuValidation.upload(state,StarfallGpuBuffers.DEPEN_CONTROL+9,bytes(4).putInt(0,1));DomePathGpuValidation.upload(state,extra,bytes(64).putInt(4,1).putInt(60,2));
   r.depenetrate(schedule,search,0,1); // No CPU readback or buffer-publication barrier.
   r.stars.abort();r.stars.prepare(1);state=r.stars.candidateStateBuffer();
   check(DomePathGpuValidation.range(state,extra+15,1).getInt(0)==0,"Rejected correction published its generation");
   check(DomePathGpuValidation.range(state,extra+1,1).getInt(0)==0,"Rejected correction published its output row");
   check(DomePathGpuValidation.range(state,StarfallGpuBuffers.DEPEN_MASK+MEMBER/32,1).getInt(0)==0,"Rejected correction retained its mask");
   check(read(r.fixture.pool,64).getFloat(4)==.65f,"Rejected correction changed committed particles");
  }
 }
 static void clockAndCruise(int update,int schedule,int search)throws Exception{
  for(float clock:new float[]{60000f,104857.55f,.05f})try(var r=new Rig()){
   r.publish(terrain(p->p[1]==1,false),update);r.stars.prepare(1);r.stars.bind(update,true);r.bindings(1);
   var data=bytes(32*64);particle(data,0,2,.5f,.5f,20);data.putFloat(4,.65f).putFloat(12,MEMBER+1);put(r.fixture.pool,data);put(r.fixture.counter,bytes(16));
   bool(update,"uStormCollisionEnabled",true);ui(update,"uCapacity",32);ui(update,"uKillEmit",-1);f(update,"uDt",.05f);f(update,"uTimeSec",clock);r.collision.bind(update);dispatch(update,1);r.depenetrate(schedule,search,0,clock);
   check(read(r.output,64).getFloat(4)<.5f,"Fresh correction delayed by an old zero cooldown at clock="+clock);
  }
  int contact=compile("wavecontact.comp");try(var r=new Rig()){
   int guard=r.fixture.alloc(bytes(4096));for(int binding=0;binding<32;binding++)bind(binding,guard);
   r.stars.prepare(1);r.stars.commit();r.stars.rebuild(new ClientboundStormWavePacket[68]);r.stars.prepare(2);r.stars.bind(contact,true);
   check(GL11.glGetError()==0,"Cruise state initialization");
   var values=bytes(32).putInt(8,3).putInt(12,2).putInt(16,1).putFloat(24,.1f).putFloat(28,1.25f);DomePathGpuValidation.upload(r.stars.candidateStateBuffer(),StarfallGpuBuffers.DEPEN_CONTROL,values);
   int reports=r.fixture.alloc(bytes(256));bind(22,reports);bool(contact,"uStormNavigationDiagnostics",true);check(GL11.glGetError()==0,"Cruise diagnostic upload");
   GL20.glValidateProgram(contact);check(GL20.glGetProgrami(contact,GL20.GL_VALIDATE_STATUS)!=0,"Cruise program validation: "+GL20.glGetProgramInfoLog(contact));dispatch(contact,1);var result=read(reports,256);
   check(result.getInt(0)==2&&result.getFloat(4)==-13&&result.getFloat(28)==-14,"Cruise without attacks lost aggregate correction diagnostics");
   check(result.getFloat(12)==3&&result.getFloat(16)==2&&result.getFloat(36)==.1f&&result.getFloat(40)==1.25f,"Cruise correction diagnostics layout mismatch");
  }finally{deleteProgram(contact);}
 }
 public static void main(String[] args)throws Exception{
  GLFWErrorCallback.createPrint(System.err).set();check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(64,64,"Sparse depenetration validation",0,0);
  try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();ARBParallelShaderCompile.glMaxShaderCompilerThreadsARB(2);
   prepare=compile("storm_depenetration_prepare.comp");int schedule=compile("storm_depenetration_schedule.comp"),search=compile("storm_depenetration.comp"),update=compile("update.comp");
   int probe=compileText("body query",source("chunks/storm_collision.glsl")+"layout(local_size_x=1)in;layout(std430,binding=1)readonly buffer P{vec4 p[];};layout(std430,binding=16)buffer R{ivec4 result;};void main(){CmiStormBody b=cmiStormBody(p[0].xyz);result=ivec4(b.occupied?1:0,b.complete?1:0,0,0);}");
   try{if(Files.exists(Path.of("build/cold-escape/baseline/commit.txt"))){int old=frozenProgram("update.comp");try{roof(old,schedule,search,probe,true);}finally{deleteProgram(old);}}else System.out.println("Frozen before-build unavailable; old-kernel control not run");roof(update,schedule,search,probe,false);exits(schedule,search,probe);lifecycle(schedule,search);rejectedBeforePublication(schedule,search);clockAndCruise(update,schedule,search);performance(schedule,search);check(GL11.glGetError()==0,"Depenetration GL state");System.out.println("Storm depenetration GPU: "+checks+" assertions passed");}finally{for(int p:new int[]{prepare,schedule,search,update,probe})deleteProgram(p);}
  }finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
 }
}
