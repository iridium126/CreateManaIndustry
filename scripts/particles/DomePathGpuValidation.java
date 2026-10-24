import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.particles.engine.*;
import com.iridium126.createmanaindustry.client.allaystorm.*;
import com.iridium126.createmanaindustry.content.allaystorm.*;
import com.iridium126.createmanaindustry.content.allaystorm.network.*;

public final class DomePathGpuValidation extends DomeGpuValidation {
 static ByteBuffer range(int buffer,int word,int length){GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);var result=bytes(length*4);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,word*4L,result);return result;}
 static void upload(int buffer,int word,ByteBuffer data){GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,word*4L,data);}
 static ClientboundStormWavePacket plan(int slot){
  var source=domeAt(80+slot,0,1,30);int[] roster=java.util.stream.IntStream.range(slot*12288,(slot+1)*12288).toArray();
  return new ClientboundStormWavePacket(source.waveId(),37,0,1,1,5,1,12288,0,30,30,90.25f,0,40,0,32,roster,source.path(),source.landings(),1,1,new int[]{12288},0,0,0,0,1,0,0);
 }
 static Map<StormCollisionPages.Section,StormCollisionPages.Page> pages(boolean obstacle){
  var result=new LinkedHashMap<StormCollisionPages.Section,StormCollisionPages.Page>();
  for(int sectionX=-1;sectionX<=1;sectionX++)for(int sectionY=-1;sectionY<=1;sectionY++)for(int sectionZ=-1;sectionZ<=1;sectionZ++){
   int[] cells=new int[128];if(obstacle&&sectionX==0&&sectionY==0&&sectionZ==0){int cell=3|(4<<8);cells[cell>>>5]|=1<<(cell&31);}
   result.put(new StormCollisionPages.Section(sectionX,sectionY,sectionZ),StormCollisionPages.page(1,cells));
  }return result;
 }
 static void publish(StormCollisionGpu collision,Map<StormCollisionPages.Section,StormCollisionPages.Page> pages,int program){
  collision.offer(pages);for(int attempt=0;attempt<1000&&!collision.settled();attempt++){collision.pump(131072,50_000_000);collision.bind(program);collision.submitted();GL11.glFlush();}check(collision.settled(),"Navigation collision pages did not publish");
 }
 static void seedPhases(int state,int perSlot){
  for(int slot=0;slot<4;slot++){var phases=bytes(perSlot*8);for(int rank=0;rank<perSlot;rank++)phases.putInt(rank*8,80+slot).putInt(rank*8+4,1);upload(state,slot*12288*2,phases);}
 }
 static void seedRequests(int state,int perSlot){
  var masks=bytes(1536*4);
  for(int slot=0;slot<4;slot++){
   var records=bytes(perSlot*48);
   for(int rank=0;rank<perSlot;rank++){
    int offset=rank*48,member=slot*12288+rank;records.putInt(offset,80+slot).putInt(offset+4,(member<<15)|1).putFloat(offset+8,0).putFloat(offset+12,Float.POSITIVE_INFINITY);
    records.putFloat(offset+16,3.5f).putFloat(offset+20,4).putFloat(offset+24,.5f).putFloat(offset+36,-3.5f).putFloat(offset+40,0).putFloat(offset+44,-3.5f);
    int word=slot*384+rank/32;masks.putInt(word*4,masks.getInt(word*4)|(1<<(rank&31)));
   }
   upload(state,StarfallGpuBuffers.DOME_PATH_BASE+slot*12288*12,records);
  }
  upload(state,StarfallGpuBuffers.DOME_PATH_REQUESTS,masks);
  upload(state,StarfallGpuBuffers.DOME_PATH_CONTROL+1,bytes(4).putInt(0,perSlot*4));
 }
 static int status(int state,int member){return range(state,StarfallGpuBuffers.DOME_PATH_BASE+member*12+1,1).getInt(0)&15;}
 static void search(int program,boolean obstacle,boolean unknown,int perSlot)throws Exception{
  try(var gpu=new StarfallGpuBuffers();var collision=new StormCollisionGpu()){
   gpu.rebuild(new ClientboundStormWavePacket[]{plan(0),plan(1),plan(2),plan(3)});warmDome(gpu,program);
   for(int bank=0;bank<2;bank++){gpu.prepare(bank+1);gpu.bind(program,true);seedPhases(GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28),perSlot);gpu.commit();}
   publish(collision,unknown?Map.of():pages(obstacle),program);
   int queuedFirst=-1;boolean finished=false;long uploadBaseline=gpu.uploadedBytes();
   for(int frame=0;frame<128;frame++){
    gpu.clock(frame*.05);gpu.prepare(100+frame);gpu.bind(program,true);int state=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28);
    if(frame==0)seedRequests(state,perSlot);collision.bind(program);DomeGatherNavigation.run(program,frame*.05f,gpu);
    var control=range(state,StarfallGpuBuffers.DOME_PATH_CONTROL,16);check(control.getInt(36)<=8192,"Global navigation expansion budget exceeded");check(control.getInt(32)<=128,"Too many in-flight navigation tasks");
    if(frame==0){queuedFirst=control.getInt(40);if(perSlot>32){check(queuedFirst==perSlot*4-128,"Queue saturation dropped requests queued="+queuedFirst+" active="+control.getInt(32)+" nodes="+control.getInt(36));for(int slot=0;slot<4;slot++){int searching=0;var records=range(state,StarfallGpuBuffers.DOME_PATH_BASE+slot*12288*12,perSlot*12);for(int rank=0;rank<perSlot;rank++)if((records.getInt(rank*48+4)&15)==2)searching++;check(searching==32,"Dome slot starved during round-robin scheduling="+searching);}check(control.getInt(36)>7000,"Saturated frame did not exercise the node budget");}}
    boolean all=true;for(int slot=0;slot<4;slot++)for(int rank=0;rank<perSlot;rank++){int value=status(state,slot*12288+rank);if(value==1||value==2)all=false;else check(value==(unknown?5:obstacle?4:3),"Wrong search result="+value+" frame="+frame+" unknown="+unknown+" obstacle="+obstacle);}
    gpu.commit();if(all){finished=true;break;}
   }
   check(finished,"Persistent frontiers or queue never drained");check(gpu.uploadedBytes()==uploadBaseline,"Navigation uploaded positions or paths");
   gpu.prepare(999);gpu.bind(program,true);int candidate=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28);var before=range(candidate,StarfallGpuBuffers.DOME_PATH_BASE,StarfallGpuBuffers.STATE_WORDS-StarfallGpuBuffers.DOME_PATH_BASE);
   seedRequests(candidate,1);collision.bind(program);DomeGatherNavigation.run(program,2,gpu);gpu.abort();gpu.prepare(1000);gpu.bind(program,true);var after=range(GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28),StarfallGpuBuffers.DOME_PATH_BASE,StarfallGpuBuffers.STATE_WORDS-StarfallGpuBuffers.DOME_PATH_BASE);check(before.equals(after),"Aborted frame leaked navigation state into the next candidate");
   System.out.println("Navigation obstacle="+obstacle+" unknown="+unknown+" requests="+(perSlot*4)+" firstQueued="+queuedFirst);
  }
 }
 static void detour(int program)throws Exception{
  try(var gpu=new StarfallGpuBuffers();var collision=new StormCollisionGpu()){
   gpu.rebuild(new ClientboundStormWavePacket[]{plan(0),plan(1),plan(2),plan(3)});warmDome(gpu,program);
   var geometry=pages(false);int[] cells=new int[128];int obstacle=1|(4<<8);cells[obstacle>>>5]|=1<<(obstacle&31);geometry.put(new StormCollisionPages.Section(0,0,0),StormCollisionPages.page(2,cells));publish(collision,geometry,program);
   boolean complete=false;
   for(int frame=0;frame<16;frame++){
    gpu.prepare(200+frame);gpu.bind(program,true);int state=gpu.candidateStateBuffer();seedPhases(state,1);if(frame==0)seedRequests(state,1);collision.bind(program);DomeGatherNavigation.run(program,frame*.05f,gpu,0);
    if(status(state,0)==3){
     var record=range(state,StarfallGpuBuffers.DOME_PATH_BASE,12);int count=(record.getInt(4)>>>4)&15;check(count>1&&count<=8,"Reachable obstacle did not produce a turning path");
     double[] previous={.5,4,.5};
     for(int point=0;point<count;point++){
      int cell=(record.getInt(16+point/2*4)>>>((point&1)*16))&511;double[] next={-3.5+(cell&7),(cell>>>3)&7,-3.5+((cell>>>6)&7)};
      double distance=0;for(int axis=0;axis<3;axis++)distance+=(next[axis]-previous[axis])*(next[axis]-previous[axis]);int steps=Math.max(1,(int)Math.ceil(Math.sqrt(distance)/.25));
      for(int step=1;step<=steps;step++){double fraction=(double)step/steps,positionX=previous[0]+(next[0]-previous[0])*fraction,positionY=previous[1]+(next[1]-previous[1])*fraction,positionZ=previous[2]+(next[2]-previous[2])*fraction;boolean overlaps=positionX+.175>=1&&positionX-.175<2&&positionY+.599>=4&&positionY+.001<5&&positionZ+.175>=0&&positionZ-.175<1;check(!overlaps,"Cached detour cut through the known body obstacle");}previous=next;
     }
     check(Math.sqrt((previous[0]-3.5)*(previous[0]-3.5)+(previous[1]-4)*(previous[1]-4)+(previous[2]-.5)*(previous[2]-.5))<=1.00001,"Detour did not reach the verified final target segment");complete=true;
    }
    gpu.commit();if(complete)break;
   }
   check(complete,"Reachable local detour failed to finish within the persistent search budget");
  }
 }
 static void completedOwnership(int program)throws Exception{
  try(var gpu=new StarfallGpuBuffers();var collision=new StormCollisionGpu()){
   var plans=new ClientboundStormWavePacket[]{plan(0),plan(1),plan(2),plan(3)};gpu.rebuild(plans);warmDome(gpu,program);publish(collision,pages(true),program);
   gpu.prepare(400);gpu.bind(program,true);seedPhases(gpu.candidateStateBuffer(),33);seedRequests(gpu.candidateStateBuffer(),33);collision.bind(program);DomeGatherNavigation.run(program,0,gpu,0);gpu.commit();
   for(int slot=0;slot<4;slot++)gpu.completed(80+slot,new long[]{(1L<<33)-1});gpu.rebuild(plans);gpu.prepare(401);gpu.bind(program,true);collision.bind(program);DomeGatherNavigation.run(program,.05f,gpu,0);
   var metrics=range(gpu.candidateStateBuffer(),StarfallGpuBuffers.DOME_PATH_CONTROL,16);check(metrics.getInt(32)==0&&metrics.getInt(40)==0,"Removed roster members retained in-flight or queued work while old GPU phases still gathered");
  }
 }
 static double percentile(List<Double> samples){var ordered=new ArrayList<>(samples);Collections.sort(ordered);return ordered.get((int)Math.ceil(ordered.size()*.95)-1);}
 static void invalidation(int program)throws Exception{
  try(var gpu=new StarfallGpuBuffers();var collision=new StormCollisionGpu()){
   gpu.rebuild(new ClientboundStormWavePacket[]{plan(0),plan(1),plan(2),plan(3)});warmDome(gpu,program);
   for(int bank=0;bank<2;bank++){gpu.prepare(bank+1);gpu.bind(program,true);seedPhases(gpu.candidateStateBuffer(),1);gpu.commit();}
   publish(collision,pages(true),program);gpu.prepare(10);gpu.bind(program,true);seedRequests(gpu.candidateStateBuffer(),1);collision.bind(program);DomeGatherNavigation.run(program,0,gpu,0);gpu.commit();
   var changed=new LinkedHashMap<StormCollisionPages.Section,StormCollisionPages.Page>();for(var section:pages(false).keySet())changed.put(section,new StormCollisionPages.Page(2,StormCollisionPages.AIR,null));publish(collision,changed,program);
   gpu.prepare(11);gpu.bind(program,true);collision.bind(program);DomeGatherNavigation.run(program,.05f,gpu,0);for(int slot=0;slot<4;slot++)check(status(gpu.candidateStateBuffer(),slot*12288)==5,"Changed terrain preserved an old frontier or manufactured no-route");gpu.commit();
   var replacement=new ClientboundStormWavePacket[4];for(int slot=0;slot<4;slot++){var source=plan(slot);replacement[slot]=new ClientboundStormWavePacket(180+slot,37,0,1,1,5,1,12288,0,30,30,90.25f,0,40,0,32,source.members(),source.path(),source.landings(),1,1,new int[]{12288},0,0,0,0,1,0,0);}
   gpu.rebuild(replacement);gpu.prepare(12);gpu.bind(program,true);int state=gpu.candidateStateBuffer();seedRequests(state,1);for(int slot=0;slot<4;slot++)upload(state,slot*12288*2,bytes(8).putInt(0,180+slot).putInt(4,1));collision.bind(program);DomeGatherNavigation.run(program,.1f,gpu,0);
   var metrics=range(state,StarfallGpuBuffers.DOME_PATH_CONTROL,16);check(metrics.getInt(32)==0&&metrics.getInt(40)==0,"Reused attack slot retained stale queued or in-flight work");check(range(state,StarfallGpuBuffers.COMPLETIONS,384).asIntBuffer().get(0)==0,"Old navigation state completed a new attack");
   int initialize=compileText("Dome path stable-rank identity","layout(std430,binding=27)readonly buffer OldState{uint words[];}starStateRead;layout(std430,binding=28)buffer State{uint words[];}starStateWrite;\n"+source("chunks/allay_storm.glsl")+source("chunks/dome_path_state.glsl")+source("chunks/dome_path_identity.glsl")+"layout(local_size_x=64)in;void main(){uint slot=gl_GlobalInvocationID.x;if(slot>=4u)return;uint member=slot*12288u;cmiDomePathIdentity(cmiStarAssignment(float(member+1u)),member);}");
   gpu.bind(initialize,true);dispatch(initialize,1);for(int slot=0;slot<4;slot++){var record=range(state,StarfallGpuBuffers.DOME_PATH_BASE+slot*12288*12,4);check(record.getInt(0)==180+slot&&(record.getInt(4)&15)==0&&record.getInt(4)>>>15==slot*12288,"Old path or cooldown survived new stable-member ownership");}
   com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clearUniformProgram(initialize);GL20.glUseProgram(0);deleteProgram(initialize);
  }
 }
 static void auditWindows()throws Exception{
  int program=compile("dome_contact_audit.comp");
  for(int scenario=0;scenario<8;scenario++)try(var fixture=new Fixture(1,1,1);var gpu=new StarfallGpuBuffers();var collision=new StormCollisionGpu()){
   var packet=plan(0);int member=scenario==7?31:0;if(scenario==7){int[] shuffled=packet.members().clone();shuffled[0]=31;shuffled[31]=0;packet=packet.withMembers(shuffled);}float[] frozen=new float[66];Arrays.fill(frozen,0);packet=packet.withGatherFlows(new DomeGatherState(frozen));gpu.rebuild(new ClientboundStormWavePacket[]{packet,null,null,null});warmDome(gpu,program);
   var goal=DomeTemplate.gatherPoint(0,0).add(0,-.3,0);var data=bytes(64);particle(data,0,2,(float)goal.x,(float)goal.z,20);data.putFloat(4,(float)goal.y+3).putFloat(12,member+1);fixture.build(data,1);put(fixture.previous,bytes(16).putInt(0,1));
   int grid=buffer(bytes((ParticleBuffers.GRID_TABLE+1)*4)),memberMap=buffer(bytes((member+1)*4).putInt(member*4,1));var geometry=new LinkedHashMap<StormCollisionPages.Section,StormCollisionPages.Page>();
   for(int sectionX=-3;sectionX<=3;sectionX++)for(int sectionY=-3;sectionY<=3;sectionY++)for(int sectionZ=-3;sectionZ<=3;sectionZ++){
    int[] cells=new int[128];if(scenario==0)for(int localY=0;localY<16;localY++)if(sectionY*16+localY<=Math.floor(goal.y))for(int local=0;local<256;local++){int cell=(localY<<8)|local;cells[cell>>>5]|=1<<(cell&31);}
    geometry.put(new StormCollisionPages.Section(sectionX,sectionY,sectionZ),StormCollisionPages.page(1,cells));
   }
   publish(collision,geometry,program);bool(program,"uStormCollisionEnabled",true);int flags=1;
   for(int frame=0;frame<10;frame++){
    float time=(frame+1)*.05f;gpu.clock(time);gpu.prepare(100+frame);gpu.bind(program,true);int state=gpu.candidateStateBuffer();upload(state,member*2,bytes(8).putInt(0,80).putInt(4,1));
    int frameFlags=scenario==4&&frame==5?2:scenario==5&&frame==5?4:1;upload(state,StarfallGpuBuffers.DOME_GATHER_WINDOW+7,bytes(4).putInt(0,frameFlags));
    int status=scenario==7?0:scenario==1?1:scenario==2?2:4;var record=bytes(48).putInt(0,80).putInt(4,(member<<15)|status).putFloat(8,scenario==7?-1000000:time).putFloat(12,0).putFloat(32,time).putFloat(36,(float)goal.x-4).putFloat(40,(float)goal.y-1).putFloat(44,(float)goal.z-4);collision.bind(program);record.putInt(16,GL20.glGetUniformi(program,GL20.glGetUniformLocation(program,"uStormCollisionRevision")));upload(state,StarfallGpuBuffers.DOME_PATH_BASE+member*12,record);
    if(scenario==6)data.putFloat(4,(float)goal.y+3-frame*.03f);put(fixture.pool,data);bind(0,fixture.pool);bind(1,fixture.pool);bind(3,fixture.counter);put(fixture.counter,bytes(16).putInt(0,1));bind(5,fixture.emit);bind(13,grid);bind(14,fixture.previous);bind(21,memberMap);ui(program,"uMode",5);f(program,"uDt",.05f);f(program,"uTimeSec",time);dispatch(program,1);
    flags=range(state,member*2,2).getInt(4);if(scenario==7&&frame>=1)check((range(state,StarfallGpuBuffers.DOME_PATH_REQUESTS,1).getInt(0)&(1<<31))!=0,"Shuffled Dome roster queued its rank instead of stable member");if(frame<9)check((flags&255)==1,"Dome retired before a complete half-second observation");gpu.commit();
   }
   boolean retired=scenario==0||scenario==3;check(((flags&255)==6)==retired,"Dome gather observation classification scenario="+scenario+" phase="+(flags&255));
   gpu.bind(program,false);int state=gpu.candidateStateBuffer();var complete=range(state,StarfallGpuBuffers.COMPLETIONS,1);check(((complete.getInt(0)&1)!=0)==retired,"Gather retirement did not publish exactly the terminal completion");
   if(retired){check(read(fixture.pool,64).getFloat(28)==0,"Gather retirement retained a sword");check((range(state,StarfallGpuBuffers.GATHER_ELIGIBLE,1).getInt(0)&1)==0,"Retired rank remained eligible");}
   GL15.glDeleteBuffers(grid);GL15.glDeleteBuffers(memberMap);
  }
  GL20.glUseProgram(0);com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clearUniformProgram(program);deleteProgram(program);
 }
 static void performance(int program)throws Exception{
  var rows=new ArrayList<String>();rows.add("round,kind,p95Milliseconds,maxNodes,stateBytes,uploadBytes");
  for(int round=0;round<3;round++)for(boolean busy:new boolean[]{false,true})try(var gpu=new StarfallGpuBuffers();var collision=new StormCollisionGpu()){
   gpu.rebuild(new ClientboundStormWavePacket[]{plan(0),plan(1),plan(2),plan(3)});warmDome(gpu,program);publish(collision,pages(true),program);
   int startQuery=GL15.glGenQueries(),endQuery=GL15.glGenQueries(),maximumNodes=0;var samples=new ArrayList<Double>();
   for(int frame=0;frame<160;frame++){
    gpu.prepare(frame+1);gpu.bind(program,true);int state=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28);seedPhases(state,32);
    if(busy&&frame%10==0)seedRequests(state,32);collision.bind(program);
    gpu.bind(program,true);gpu.bindPathDispatch();f(program,"uTimeSec",frame*.016f);GL20.glUseProgram(program);GL33.glQueryCounter(startQuery,GL33.GL_TIMESTAMP);DomeGatherNavigation.dispatch(program,gpu.domePathDispatchBuffer(),0);GL33.glQueryCounter(endQuery,GL33.GL_TIMESTAMP);
    long began=GL33.glGetQueryObjectui64(startQuery,GL15.GL_QUERY_RESULT),ended=GL33.glGetQueryObjectui64(endQuery,GL15.GL_QUERY_RESULT);if(frame>=40)samples.add((ended-began)/1e6);
    maximumNodes=Math.max(maximumNodes,range(state,StarfallGpuBuffers.DOME_PATH_CONTROL+9,1).getInt(0));gpu.commit();
   }
   double p95=percentile(samples);rows.add(round+","+(busy?"saturated":"empty")+","+p95+","+maximumNodes+","+StarfallGpuBuffers.DOME_PATH_BYTES+",0");System.out.println("Navigation round="+round+" busy="+busy+" p95Ms="+p95+" nodes="+maximumNodes);
   GL15.glDeleteQueries(startQuery);GL15.glDeleteQueries(endQuery);
  }
  Path directory=Path.of(System.getProperty("cmi.benchmark.output","build/benchmarks/current"));Files.createDirectories(directory);Files.write(directory.resolve("dome-path-performance.csv"),rows);
 }
 public static void main(String[] arguments)throws Exception{
  GLFWErrorCallback.createPrint(System.err).set();check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(64,64,"Dome navigation",0,0);
  try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();System.out.println(GL11.glGetString(GL11.GL_RENDERER));int program=compile("dome_path.comp");try{search(program,false,false,1);search(program,true,false,300);search(program,false,true,1);detour(program);completedOwnership(program);invalidation(program);auditWindows();performance(program);check(GL11.glGetError()==0,"Navigation GL error");System.out.println("Dome navigation GPU: "+checks+" assertions passed");}finally{GL20.glUseProgram(0);deleteProgram(program);}}
  finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
 }
}
