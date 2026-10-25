import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.particles.engine.*;
import com.iridium126.createmanaindustry.client.allaystorm.*;
import com.iridium126.createmanaindustry.content.allaystorm.*;
import com.iridium126.createmanaindustry.content.allaystorm.network.*;
/** Protocol 15 thick-shell Dome, through actual production shaders. */
public class DomeGpuValidation extends StarfallGpuValidation {
 static ClientboundStormWavePacket dome(int count,int id,int event,int phase){
  return domeAt(id,event,phase,10);
 }
 static ClientboundStormWavePacket domeAt(int id,int event,int phase,float at){
  int[] members=java.util.stream.IntStream.range(0,12288).toArray();return new ClientboundStormWavePacket(id,37,event,1,1,5,1,12288,0,at,at,at+60.25f,0,40,0,32,members,new float[]{0,40,0,0,0,0},new float[4],1,phase,new int[]{12288},0,0,0,0,1,0,0);
 }
 static ClientboundStormWavePacket starBenchmarkPacket(int count){int per=1024,cols=(count+per-1)/per;int[] roster=java.util.stream.IntStream.range(0,count).toArray();float[] land=new float[cols*4];for(int i=0;i<cols;i++){land[i*4+1]=.5f;land[i*4+3]=3.5f;}return new ClientboundStormWavePacket(1,37,2,1,1,5,cols,per,0,4,10,120,0,40,0,8,roster,new float[]{0,40,0,0,10,0},land);}
 static void assertFinite(ByteBuffer b,int o,int words){for(int j=0;j<words;j++)check(Float.isFinite(b.getFloat(o+j*4)),"nonfinite Dome output");}
 static int motionProgram(){String nav=source("chunks/storm_navigation.glsl");nav=nav.substring(nav.indexOf("vec3 cmiStarRoute"));return compileText("Dome shared lanes",source("chunks/allay_storm.glsl")+nav+"layout(local_size_x=64)in;layout(std430,binding=10)buffer Output{vec4 rows[];} result;void main(){uint i=gl_GlobalInvocationID.x;if(i>=12288u)return;uint a=cmiStarAssignment(float(i+1u));float phase=cmiStarPhase(a,uTimeSec);vec3 point=cmiDomePoint(a,0.,uTimeSec,vec3(0)),velocity=cmiDomeVelocity(a,uTimeSec);result.rows[i*3u]=vec4(point,phase);result.rows[i*3u+1u]=vec4(velocity,cmiAttackCarry(float(i+1u),uTimeSec,-4.));result.rows[i*3u+2u]=vec4(cmiAttackSpeed(float(i+1u),uTimeSec,6.),0,0,0);}");}
 static void warmDome(StarfallGpuBuffers gpu,int program){DomeTemplate.keys();for(int frame=0;!gpu.domeReady()&&frame<128;frame++){gpu.prepare(90000+frame);gpu.bind(program,true);gpu.commit();}check(gpu.domeReady(),"Dome key upload never finished");gpu.prepare(90200);gpu.bind(program,true);}
 static void geometry()throws Exception{
  check(DomeTemplate.available(),"Dome template missing");int p=motionProgram(),out=buffer(bytes(12288*48));try(var gpu=new StarfallGpuBuffers()){
   bind(10,out);for(boolean locked:new boolean[]{false,true}){
    stage(gpu,dome(12288,7,locked?2:0,locked?4:1),p);warmDome(gpu,p);
    for(float t:locked?new float[]{10,10.1f,15,25,40,55,69.99f}:new float[]{0,1,9,15}){
     f(p,"uTimeSec",t);dispatch(p,192);var rows=read(out,12288*48);int done=0;
     for(int i=0;i<12288;i++){int o=i*48;assertFinite(rows,o,12);var expected=locked?DomeTemplate.lockedPoint(i,Math.max(0,t-10),10):DomeTemplate.gatherPoint(i,t);
      check(Math.abs(rows.getFloat(o)-expected.x)<.004&&Math.abs(rows.getFloat(o+4)-(expected.y-.3))<.004&&Math.abs(rows.getFloat(o+8)-expected.z)<.004,"CPU/GPU lane mismatch rank="+i+" time="+t+" locked="+locked+" expected="+expected+" actual="+rows.getFloat(o)+","+rows.getFloat(o+4)+","+rows.getFloat(o+8));
      int phase=locked?4:1;check(rows.getFloat(o+12)==phase,"Dome per-member phase");if(phase==6)done++;
      check(rows.getFloat(o+32)==(phase==1?8:6),"Dome gather/attack speed cap");check(rows.getFloat(o+28)==(phase==4?1:0),"Dome carrier escaped flight phase");
      if(!locked){double radius=expected.length();check(radius>=16&&radius<=32&&expected.y>=0,"not an upper thick shell");}
      if(phase==4&&t>=12.&&t-10+.01<DomeTemplate.duration(DomeTemplate.mappedRank(i,10))){double vx=rows.getFloat(o+16),vy=rows.getFloat(o+20),vz=rows.getFloat(o+24),speed=Math.sqrt(vx*vx+vy*vy+vz*vz);check(Math.abs(speed-6)<.025,"Dome reference arc speed "+speed);}
     }
     if(!locked&&t==1){renderDome(rows,12288);bind(10,out);}
    }
   }
   var wrapped=new ClientboundStormWavePacket(9,37,2,1,1,5,1,12288,104857.5f,104857.625f,104857.625f,104918,0,40,0,32,java.util.stream.IntStream.range(0,12288).toArray(),new float[]{0,40,0,0,0,0},new float[4],1,4,new int[]{12288},0,0,0,0,1,0,0);
   stage(gpu,wrapped,p);warmDome(gpu,p);bind(10,out);f(p,"uTimeSec",104857.75f);dispatch(p,192);var unwrapped=read(out,12288*48);f(p,"uTimeSec",104857.75f-104857.6f);dispatch(p,192);var afterWrap=read(out,12288*48);
   for(int rank=0;rank<12288;rank++)for(int word=0;word<12;word++)check(Math.abs(unwrapped.getFloat(rank*48+word*4)-afterWrap.getFloat(rank*48+word*4))<.001,"Dome clock wrap changed position, speed or phase");
   var distant=new ClientboundStormWavePacket(10,37,2,1,1,5,1,12288,0,10,10,70.25f,1000000,104, -1000000,32,java.util.stream.IntStream.range(0,12288).toArray(),new float[]{1000000,104,-1000000,1000000,64,-1000000},new float[4],1,4,new int[]{12288},1000000,64,-1000000,0,1,0,0);
   stage(gpu,distant,p);warmDome(gpu,p);bind(10,out);f(p,"uTimeSec",15);dispatch(p,192);var translated=read(out,12288*48);
   for(int rank=0;rank<12288;rank++){var expected=DomeTemplate.lockedPoint(rank,5,10).add(1000000,63.7,-1000000);int o=rank*48;check(Math.abs(translated.getFloat(o)-expected.x)<.125&&Math.abs(translated.getFloat(o+4)-expected.y)<.004&&Math.abs(translated.getFloat(o+8)-expected.z)<.125,"Dome distant coordinates changed its local curtain");}
  }finally{GL15.glDeleteBuffers(out);com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clearUniformProgram(p);deleteProgram(p);}
 }
 static void cyclicMapping()throws Exception{
  int program=compileText("Dome exact cyclic mapping",source("chunks/starfall.glsl")+"layout(local_size_x=64)in;layout(std430,binding=10)buffer Output{uint ranks[];}result;void main(){uint i=gl_GlobalInvocationID.x;if(i<12288u)result.ranks[i]=cmiDomeMappedRank(cmiStarAssignment(float(i+1u)));}");
  int out=buffer(bytes(12288*4));try(var gpu=new StarfallGpuBuffers()){
   for(float lock:new float[]{0,1,10,13.7f,17.35f,60.2f,600}){stage(gpu,domeAt(7,2,4,lock),program);warmDome(gpu,program);bind(10,out);dispatch(program,192);var ranks=read(out,12288*4);BitSet used=new BitSet();for(int i=0;i<12288;i++){int rank=ranks.getInt(i*4);check(rank==DomeTemplate.mappedRank(i,lock),"Dome cyclic mapping mismatch member="+i+" lock="+lock+" GPU="+rank);check(!used.get(rank),"Dome cyclic mapping duplicated a channel");used.set(rank);}}
  }finally{GL15.glDeleteBuffers(out);com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clearUniformProgram(program);deleteProgram(program);}
 }
 static void publishedClockWins()throws Exception{
  int p=compileText("authority clock supersedes local speculation","layout(std430,binding=27)readonly buffer S{uint words[];}s;\n#define CMI_DOME_RUNTIME_FLOW\nuint cmiDomeFlowWord(uint a){return s.words[a];}\n"+source("chunks/allay_storm.glsl")+"layout(local_size_x=64)in;layout(std430,binding=10)buffer R{float values[];}r;void main(){uint i=gl_GlobalInvocationID.x;if(i>=12288u)return;r.values[i]=cmiDomePause(cmiStarAssignment(float(i+1u)));}");
  int out=buffer(bytes(12288*4));try(var gpu=new StarfallGpuBuffers()){
   stage(gpu,domeAt(91,0,1,90),p);warmDome(gpu,p);gpu.commit();gpu.bind(p,false);int state=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,state);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,StarfallGpuBuffers.DOME_FLOW_STATE*4L,bytes(8).putInt(0,91).putFloat(4,.5f));
   float[] times=new float[66];Arrays.fill(times,-1);times[0]=2.125f;gpu.rebuild(new ClientboundStormWavePacket[]{domeAt(91,0,1,90).withGatherFlows(new DomeGatherState(times)),null,null,null});gpu.prepare(999);gpu.bind(p,true);bind(10,out);dispatch(p,192);check(read(out,12288*4).getFloat(0)==2.125f,"Old local pause overrode published authority clock");
   gpu.rebuild(new ClientboundStormWavePacket[]{domeAt(92,0,1,90),null,null,null});gpu.prepare(1000);gpu.bind(p,true);bind(10,out);dispatch(p,192);check(Float.isInfinite(read(out,12288*4).getFloat(0)),"Old attack clock leaked into reused slot");
  }finally{GL15.glDeleteBuffers(out);com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clearUniformProgram(p);deleteProgram(p);}
 }
 static void pausedGeometry()throws Exception{
  int p=motionProgram(),out=buffer(bytes(12288*48));try(var gpu=new StarfallGpuBuffers()){
   float[] times=new float[66];Arrays.fill(times,-1);for(int f=0;f<66;f+=3)times[f]=2.125f;var clocks=new DomeGatherState(times);
   for(boolean locked:new boolean[]{false,true}){
    stage(gpu,domeAt(75,locked?2:0,locked?4:1,10).withGatherFlows(clocks),p);warmDome(gpu,p);bind(10,out);
    for(float t:locked?new float[]{10,10.001f,10.1f,10.5f,11.25f}:new float[]{5,6,9}){
     f(p,"uTimeSec",t);dispatch(p,192);var rows=read(out,12288*48);
     for(int rank=0;rank<12288;rank++){int o=rank*48;var expected=locked?DomeTemplate.lockedPoint(rank,t-10,10,clocks):DomeTemplate.gatherPoint(rank,t,clocks);var velocity=locked?DomeTemplate.lockedVelocity(rank,t-10,10,clocks):DomeTemplate.gatherVelocity(rank,t,clocks);
      check(Math.abs(rows.getFloat(o)-expected.x)<.004&&Math.abs(rows.getFloat(o+4)-(expected.y-.3))<.004&&Math.abs(rows.getFloat(o+8)-expected.z)<.004,"Paused CPU/GPU point mismatch");for(int k=0;k<3;k++)check(Math.abs(rows.getFloat(o+16+k*4)-(k==0?velocity.x:k==1?velocity.y:velocity.z))<.006,"Paused CPU/GPU velocity mismatch");
     }
    }
   }
   stage(gpu,domeAt(76,0,1,90),p);warmDome(gpu,p);bind(10,out);f(p,"uTimeSec",6);dispatch(p,192);var rows=read(out,12288*48);check(Math.abs(rows.getFloat(16))+Math.abs(rows.getFloat(20))+Math.abs(rows.getFloat(24))>1,"Reused slot inherited paused velocity");
  }finally{GL15.glDeleteBuffers(out);com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clearUniformProgram(p);deleteProgram(p);}
 }
 static void gatherGates()throws Exception{
  com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clear();
  int n=12288,program=compile("update.comp"),gridProgram=compile("gridbuild.comp"),bodies=compile("dome_contacts.comp");try{for(int scenario=0;scenario<6;scenario++){
   int live=scenario==0?128:n,far=scenario==1?738:scenario==2?492:0;
   try(var q=new Fixture(n,n,n);var gpu=new StarfallGpuBuffers();var collision=new StormCollisionGpu()){
    var data=bytes(n*64);for(int i=0;i<live;i++){var p=DomeTemplate.gatherPoint(i,.95);particle(data,i,2,(float)p.x+(i<far?100:0),(float)p.z,20);data.putFloat(i*64+4,(float)p.y-.3f).putFloat(i*64+12,i+1).putFloat(i*64+56,AllayStormWaves.memberSeed(1,i));var v=DomeTemplate.gatherVelocity(i,.95);data.putFloat(i*64+16,(float)v.x).putFloat(i*64+20,(float)v.y).putFloat(i*64+24,(float)v.z);}q.build(data,live);var h=headers();h.putFloat(2*320+18*16+12,6);if(scenario==3||scenario==4)h.putFloat(2*320+7*16+4,1);put(q.emit,h);put(q.previous,bytes(16).putInt(0,live));
    int out=buffer(bytes(n*64)),grid=buffer(bytes((ParticleBuffers.GRID_TABLE+n)*4)),damage=buffer(bytes(16)),players=buffer(bytes(16)),corrections=buffer(bytes(n*32)),map=buffer(bytes(n*4));
    if(scenario==3||scenario==4){var pages=new LinkedHashMap<StormCollisionPages.Section,StormCollisionPages.Page>();if(scenario==4)pages.put(new StormCollisionPages.Section(1000,1000,1000),new StormCollisionPages.Page(1,StormCollisionPages.AIR,null));else for(int x=-3;x<=3;x++)for(int y=-3;y<=3;y++)for(int z=-3;z<=3;z++)pages.put(new StormCollisionPages.Section(x,y,z),new StormCollisionPages.Page(1,StormCollisionPages.SOLID,null));collision.offer(pages);for(int k=0;k<1000&&!collision.settled();k++){collision.pump(131072,50_000_000);collision.bind(program);collision.submitted();GL11.glFlush();}}
    gpu.rebuild(new ClientboundStormWavePacket[]{domeAt(71,0,1,2),null,null,null});if(scenario!=5)warmDome(gpu,program);ui(program,"uCapacity",n);ui(program,"uKillEmit",-1);f(program,"uDt",.05f);bool(program,"uStormCollisionEnabled",scenario==3||scenario==4);bool(bodies,"uStormCollisionEnabled",scenario==3||scenario==4);
    ByteBuffer counts=null;
    for(int frame=0;frame<3;frame++){put(q.pool,data);put(q.counter,bytes(16));bind(0,q.pool);bind(1,out);bind(3,q.counter);bind(5,q.emit);bind(13,grid);bind(14,q.previous);bind(16,damage);bind(18,players);bind(19,corrections);bind(21,map);gpu.prepare(frame+1);gpu.bind(program,true);collision.bind(program);f(program,"uTimeSec",1+frame*.05f);dispatch(program,192);if(scenario!=5){collision.bind(bodies);DomeBodyContacts.run(gridProgram,bodies,q.pool,q.previous,out,q.counter,grid,1+frame*.05f,.05f,0,gpu);}int state=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28);counts=bytes(16);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,state);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,StarfallGpuBuffers.GATHER_COUNTS*4L,counts);data=read(out,n*64);gpu.commit();}
    int ready=counts.getInt(0),eligible=counts.getInt(4),blocked=counts.getInt(8),unknown=counts.getInt(12);
    if(scenario==0)check(eligible==n&&ready<=live&&!StormGatherRules.ready(ready,eligible,1,2),"Unspawned members vanished from gather denominator");
    if(scenario==1)check(!DomeGatherRules.ready(ready,eligible,22,40),"94 percent fired before its threshold");
    if(scenario==2)check(ready<n&&DomeGatherRules.ready(ready,eligible,22,40),"96 percent waited for all members at midpoint");
    if(scenario==3)check(eligible==n&&blocked==n&&ready==0,"Solid terrain retired ranks before the observation window");
    if(scenario==5)check(eligible==n&&unknown==n&&ready==0,"Partial trajectory upload became attack-ready");
    if(scenario==4)check(eligible==n&&unknown==n&&ready==0,"Unknown terrain counted as covered air");
    for(int b:new int[]{out,grid,damage,players,corrections,map})GL15.glDeleteBuffers(b);
   }
  }}finally{for(int shader:new int[]{program,gridProgram,bodies}){com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clearUniformProgram(shader);deleteProgram(shader);}}
 }
 static Map<StormCollisionPages.Section,StormCollisionPages.Page> slopePages(){
  var pages=new LinkedHashMap<StormCollisionPages.Section,StormCollisionPages.Page>();
  for(int sx=-4;sx<=4;sx++)for(int sy=-4;sy<=7;sy++)for(int sz=-4;sz<=4;sz++){
   int[] bits=new int[128];for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){
    int wx=sx*16+x,wz=sz*16+z,wy=sy*16+y;int top=(int)Math.floor(wx*.25)+2+(wz>8&&wz<14?2:0);
    if(wy<top){int cell=x|(z<<4)|(y<<8);bits[cell>>>5]|=1<<(cell&31);}
   }pages.put(new StormCollisionPages.Section(sx,sy,sz),StormCollisionPages.page(1,bits));
  }return pages;
 }
 static void slopeGather()throws Exception{
  int n=12288,update=compile("update.comp"),gridProgram=compile("gridbuild.comp"),bodies=compile("dome_contacts.comp"),contact=compile("wavecontact.comp"),navigation=compile("dome_path.comp");
  float deadline=AllayDome.gatherSeconds(40,16);
  int initialize=compileText("slope production homes",source("chunks/allay_storm.glsl")+"layout(local_size_x=64)in;layout(std430,binding=10)buffer P{vec4 rows[];}p;void main(){uint i=gl_GlobalInvocationID.x;if(i>=12288u)return;vec3 position,velocity;cmiTyphoonHome(p.rows[i*4u+3u].z,16.,.2,.7,.3,.04,6.,vec3(0,40,0),position,velocity);p.rows[i*4u].xyz=position;p.rows[i*4u+1u].xyz=velocity;}");
  try(var q=new Fixture(n,n,n);var gpu=new StarfallGpuBuffers();var collision=new StormCollisionGpu()){
   var data=bytes(n*64);for(int i=0;i<n;i++){particle(data,i,2,0,0,20);data.putFloat(i*64+12,i+1).putFloat(i*64+56,AllayStormWaves.memberSeed(1,i));}q.build(data,n);bind(10,q.pool);dispatch(initialize,192);data=read(q.pool,n*64);
   var h=headers();h.putFloat(2*320+18*16+4,16).putFloat(2*320+18*16+12,6).putFloat(2*320+19*16+4,40).putFloat(2*320+7*16+4,1);put(q.emit,h);put(q.previous,bytes(16).putInt(0,n));
   int output=buffer(bytes(n*64)),grid=buffer(bytes((ParticleBuffers.GRID_TABLE+n)*4)),damage=buffer(bytes(16)),players=buffer(bytes(16)),correction=buffer(bytes(n*32)),map=buffer(bytes(n*4)),reports=buffer(bytes(4+16384*24));
   collision.offer(slopePages());for(int k=0;k<1000&&!collision.settled();k++){collision.pump(131072,50_000_000);collision.bind(update);collision.submitted();GL11.glFlush();}check(collision.settled(),"slope pages did not publish");
   gpu.rebuild(new ClientboundStormWavePacket[]{domeAt(27,0,1,deadline),null,null,null});warmDome(gpu,update);ui(update,"uCapacity",n);ui(update,"uKillEmit",-1);bool(update,"uStormCollisionEnabled",true);bool(bodies,"uStormCollisionEnabled",true);collision.bind(bodies);
   float[] latched=new float[66];Arrays.fill(latched,-1);int ready=0,eligible=0,blocked=0,unknown=0,paused=0;float lock=-1;double maximumTravel=0;
   int knownPauses=0;var retired=new BitSet();
   for(int frame=0;frame*.05f<deadline;frame++){
    float time=frame*.05f;gpu.clock(time);f(update,"uStormOmegaNow",.2f);f(update,"uStormRotPhase",.7f+.2f*time);f(update,"uStormConvPhase",.3f+.04f*time);f(update,"uStormConvRate",.04f);put(q.pool,data);put(q.counter,bytes(16));bind(0,q.pool);bind(1,output);bind(3,q.counter);bind(5,q.emit);bind(13,grid);bind(14,q.previous);bind(16,damage);bind(18,players);bind(19,correction);bind(21,map);
    GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,grid);GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,0,ParticleBuffers.GRID_TABLE*4L,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(IntBuffer)null);dispatch(gridProgram,192);
    gpu.prepare(frame+1);gpu.bind(update,true);collision.bind(update);f(update,"uTimeSec",time);f(update,"uDt",.05f);dispatch(update,192);collision.bind(bodies);DomeBodyContacts.run(gridProgram,bodies,q.pool,q.previous,output,q.counter,grid,time,.05f,0,gpu);
    collision.bind(navigation);DomeGatherNavigation.run(navigation,time,gpu);
    int state=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28);var counts=bytes(16);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,state);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,StarfallGpuBuffers.GATHER_COUNTS*4L,counts);ready=counts.getInt(0);eligible=counts.getInt(4);blocked=counts.getInt(8);unknown=counts.getInt(12);
    var clocks=bytes(66*8);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,StarfallGpuBuffers.DOME_FLOW_STATE*4L,clocks);paused=0;for(int f=0;f<66;f++){float value=clocks.getFloat(f*8+4);if(Float.isFinite(value)){paused++;if(latched[f]>=0)check(value==latched[f],"Paused flow resumed/changed phase");latched[f]=value;}}
    var finished=bytes(384*4);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,StarfallGpuBuffers.COMPLETIONS*4L,finished);var completed=new long[192];for(int word=0;word<192;word++)completed[word]=finished.getLong(word*8);var newRetired=BitSet.valueOf(completed);newRetired.andNot(retired);boolean rebuild=paused>knownPauses||!newRetired.isEmpty();retired.or(newRetired);
    if(!newRetired.isEmpty()){gpu.trackReturn(domeAt(27,0,1,deadline),newRetired.toLongArray());gpu.completed(27,newRetired.toLongArray());}
    if(rebuild){knownPauses=paused;gpu.rebuild(new ClientboundStormWavePacket[]{domeAt(27,0,1,deadline).withGatherFlows(new DomeGatherState(latched)),null,null,null});}
    var next=read(output,n*64);int[] oldIndex=new int[n];for(int i=0;i<n;i++)oldIndex[(Math.round(data.getFloat(i*64+12))&262143)-1]=i;for(int i=0;i<n;i++){assertFinite(next,i*64,3);int old=oldIndex[(Math.round(next.getFloat(i*64+12))&262143)-1];double d=0;for(int k=0;k<3;k++){double v=next.getFloat(i*64+k*4)-data.getFloat(old*64+k*4);d+=v*v;}maximumTravel=Math.max(maximumTravel,Math.sqrt(d));}data=next;gpu.commit();
    if(frame%100==0){var metrics=DomePathGpuValidation.range(state,StarfallGpuBuffers.DOME_PATH_CONTROL,16);System.out.println("slope "+time+" ready="+ready+" eligible="+eligible+" blocked="+blocked+" unknown="+unknown+" paused="+paused+" searches="+metrics.getInt(32)+" nodes="+metrics.getInt(36)+" queue="+metrics.getInt(40));}
    if(DomeGatherRules.ready(ready,eligible,DomeGatherRules.elapsedTicks(time,0),DomeGatherRules.durationTicks(0,deadline))){
     bind(22,reports);put(reports,bytes(4+16384*24));gpu.bind(contact,false);bind(28,state);f(contact,"uTimeSec",time);ui(contact,"uLocalWaveMask",1);dispatch(contact,192);var snapshot=read(reports,4+16384*24);int reported=0,revision=-1;var readyMask=new BitSet();var eligibleMask=new BitSet();for(int k=0;k<snapshot.getInt(0);k++){int at=4+k*24,kind=Math.round(snapshot.getFloat(at));if(kind== -8){revision=Math.round(snapshot.getFloat(at+8));continue;}if(kind== -7){reported++;continue;}if(kind!= -4&&kind!= -5)continue;int word=Math.round(snapshot.getFloat(at+8)),bits=Math.round(snapshot.getFloat(at+12))|(Math.round(snapshot.getFloat(at+16))<<16);for(int bit=0;bit<32;bit++)if((bits&(1<<bit))!=0)(kind== -4?readyMask:eligibleMask).set(word*32+bit);}check(reported==paused&&revision==paused,"Pause/readiness snapshot clocks missing");check(readyMask.cardinality()==ready&&eligibleMask.cardinality()==eligible&&DomeGatherRules.valid(readyMask,eligibleMask,n,time,0,deadline),"Slope masks and aggregate diverged");boolean partial=false;for(int rank=0;rank<n;rank++)if(DomeTemplate.ringBase(rank)==rank&&latched[DomeTemplate.flowIndex(rank)]>=0){int end=rank+DomeTemplate.ringCount(rank),r=readyMask.nextSetBit(rank),e=eligibleMask.nextClearBit(rank);if(r>=rank&&r<end&&e<end)partial=true;}check(partial,"Paused flow mates were excluded together");lock=time;break;
    }
   }
   if(lock<0){gpu.bind(0,false);int state=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28);var states=read(state,StarfallGpuBuffers.STATE_WORDS*4);var pending=new ArrayList<String>();pending.add("member,flow,error,speedError,streak,status,elapsed,initialError,blocked,occupied,evidence");var clocks=new DomeGatherState(latched);
    for(int row=0;row<n;row++){int offset=row*64,member=(Math.round(data.getFloat(offset+12))&262143)-1,bit=1<<(member&31);if((states.getInt((StarfallGpuBuffers.GATHER_ELIGIBLE+member/32)*4)&bit)==0||(states.getInt((StarfallGpuBuffers.GATHER_READY+member/32)*4)&bit)!=0)continue;var point=DomeTemplate.gatherPoint(member,(float)Math.floor(deadline/.05f)*.05f,clocks).add(0,-.3,0);var velocity=DomeTemplate.gatherVelocity(member,(float)Math.floor(deadline/.05f)*.05f,clocks);var actual=new net.minecraft.world.phys.Vec3(data.getFloat(offset),data.getFloat(offset+4),data.getFloat(offset+8));var actualVelocity=new net.minecraft.world.phys.Vec3(data.getFloat(offset+16),data.getFloat(offset+20),data.getFloat(offset+24));int window=(StarfallGpuBuffers.DOME_GATHER_WINDOW+member*8)*4;pending.add(member+","+DomeTemplate.flowIndex(member)+","+actual.distanceTo(point)+","+actualVelocity.distanceTo(velocity)+","+states.getInt((StarfallGpuBuffers.GATHER_STREAK+member)*4)+","+(states.getInt((StarfallGpuBuffers.DOME_PATH_BASE+member*12+1)*4)&15)+","+states.getFloat(window+4)+","+states.getFloat(window+8)+","+states.getFloat(window+12)+","+states.getFloat(window+16)+","+states.getFloat(window+24));}
    Files.createDirectories(Path.of("build/dome-navigation"));Files.write(Path.of("build/dome-navigation/slope-pending.csv"),pending);
   }
   check(lock>=0&&lock<deadline,"Slope gather missed production deadline="+deadline+" ready="+ready+" eligible="+eligible);check(paused>0&&paused<66,"Terrain did not pause only affected flows");check(eligible>0&&eligible<n&&ready>n/2,"Slope removed whole roster instead of blocked slots");check(unknown==0,"Loaded slope became unknown");check(maximumTravel<=.4001,"Slope gather teleported or exceeded eight metres per second");
   Files.createDirectories(Path.of("build/dome-v15"));Files.writeString(Path.of("build/dome-v15/slope-gather.json"),"{\"seconds\":"+lock+",\"ready\":"+ready+",\"eligible\":"+eligible+",\"blocked\":"+blocked+",\"pausedFlows\":"+paused+",\"maximumTravel\":"+maximumTravel+"}");
   System.out.println("Slope ready="+ready+"/"+eligible+" blocked="+blocked+" paused="+paused+" seconds="+lock+" maxTravel="+maximumTravel);
   for(int b:new int[]{output,grid,damage,players,correction,map,reports})GL15.glDeleteBuffers(b);
  }finally{for(int p:new int[]{update,gridProgram,bodies,contact,initialize,navigation}){com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clearUniformProgram(p);deleteProgram(p);}}
 }
 static void coincidentSeparation()throws Exception{
  int program=compileText("shared coincident-body separation",source("chunks/storm_separation.glsl")+"layout(local_size_x=64)in;layout(std430,binding=10)buffer R{vec4 values[];}r;void main(){uint i=gl_GlobalInvocationID.x;if(i>=64u)return;vec3 a=cmiStormSeparationDelta(vec3(0),i,i+64u),b=cmiStormSeparationDelta(vec3(0),i+64u,i);r.values[i*2u]=vec4(a,0);r.values[i*2u+1u]=vec4(b,0);}");
  int output=buffer(bytes(64*32));try{bind(10,output);dispatch(program,1);var data=read(output,64*32);for(int i=0;i<64;i++){double length=0,sum=0;for(int k=0;k<3;k++){double a=data.getFloat(i*32+k*4),b=data.getFloat(i*32+16+k*4);length+=a*a;sum+=(a+b)*(a+b);}check(length>.00009&&length<.00011&&sum<1e-12,"Coincident separation did not produce opposite stable directions");}}finally{GL15.glDeleteBuffers(output);deleteProgram(program);}
 }
 static void lazyStateGrowth()throws Exception{
  int program=motionProgram();try(var gpu=new StarfallGpuBuffers()){
   gpu.rebuild(new ClientboundStormWavePacket[]{starBenchmarkPacket(64),null,null,null});gpu.prepare(1);gpu.bind(program,true);
   int original=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,original);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,bytes(4).putInt(0,0x12345678));gpu.commit();
   gpu.rebuild(new ClientboundStormWavePacket[]{dome(12288,71,0,1),null,null,null});gpu.prepare(2);gpu.bind(program,true);
   int preserved=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,27);check(read(preserved,4).getInt(0)==0x12345678,"Dome growth lost committed Starfall state");GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,preserved);check(GL15.glGetBufferParameteri(GL43.GL_SHADER_STORAGE_BUFFER,GL15.GL_BUFFER_SIZE)==StarfallGpuBuffers.STATE_WORDS*4,"Dome grew to an unexpected state size");
  }finally{com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clearUniformProgram(program);deleteProgram(program);}
 }
 static void exceptionalBodyDomain()throws Exception{
  int gridProgram=compile("gridbuild.comp"),program=compile("dome_contacts.comp");
  try(var q=new Fixture(3,3,3);var gpu=new StarfallGpuBuffers()){
   var original=bytes(192);particle(original,0,2,0,0,20);particle(original,1,2,0,0,20);original.putFloat(0,-.25f).putFloat(64,.25f).putFloat(64+12,2);particle(original,2,1,.01f,0,20);q.build(original,3);put(q.counter,bytes(16).putInt(0,3));put(q.previous,bytes(16).putInt(0,3));
   var candidates=bytes(192);candidates.put(original.duplicate());candidates.clear();candidates.putFloat(0,.10f).putFloat(64,-.10f);int output=buffer(candidates),grid=buffer(bytes((ParticleBuffers.GRID_TABLE+3)*4)),map=buffer(bytes(12).putInt(0,1).putInt(4,2));
   gpu.rebuild(new ClientboundStormWavePacket[]{domeAt(71,0,1,90),null,null,null});warmDome(gpu,program);int state=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,state);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,bytes(16).putInt(0,71).putInt(4,1).putInt(8,71).putInt(12,1));bind(5,q.emit);bind(21,map);
   int indirect=GL11.glGetInteger(GL43.GL_DISPATCH_INDIRECT_BUFFER_BINDING);DomeBodyContacts.run(gridProgram,program,q.pool,q.previous,output,q.counter,grid,1,.05f,0,gpu);var result=read(output,192);check(Math.abs(result.getFloat(128)-.01f)<.000001,"Dome contact solver moved an ordinary MODEL");check(Math.abs(result.getFloat(0)-result.getFloat(64))>=.33,"Predictive body sweep allowed crossing");
   for(int i=0;i<2;i++){double travel=0;for(int k=0;k<3;k++){double d=result.getFloat(i*64+k*4)-original.getFloat(i*64+k*4);travel+=d*d;}check(travel<=.16001,"Gather body depenetration exceeded eight metres per second");}
   check(GL11.glGetInteger(GL43.GL_DISPATCH_INDIRECT_BUFFER_BINDING)==indirect,"Contacts lost engine indirect binding");put(q.counter,bytes(16));DomeBodyContacts.run(gridProgram,program,q.pool,q.previous,output,q.counter,grid,1,.05f,0,gpu);check(GL11.glGetError()==0,"Empty contact queue caused GL errors");
   put(output,candidates);put(q.counter,bytes(16).putInt(0,3));bool(program,"uStormCollisionEnabled",true);bool(program,"uStormCollisionOn",false);DomeBodyContacts.run(gridProgram,program,q.pool,q.previous,output,q.counter,grid,1,.05f,0,gpu);var unknown=read(output,192);
   for(int i=0;i<3;i++)for(int k=0;k<3;k++)check(unknown.getFloat(i*64+k*4)==candidates.getFloat(i*64+k*4),"Body correction entered unknown collision coverage");
   for(int buffer:new int[]{output,grid,map})GL15.glDeleteBuffers(buffer);
  }finally{for(int p:new int[]{gridProgram,program}){com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clearUniformProgram(p);deleteProgram(p);}}
 }
 static double wrapped(double seconds){double period=104857.6;return seconds-Math.floor(seconds/period)*period;}
 static ClientboundStormWavePacket clockedDome(double origin,int event,int phase,float at){var p=domeAt(27,event,phase,at);return new ClientboundStormWavePacket(p.waveId(),p.epoch(),p.event(),p.waveSeed(),p.targetEntityId(),p.swordTier(),p.columns(),p.perColumn(),(float)origin,(float)(origin+p.assembleSec()),(float)(origin+p.lockSec()),(float)(origin+p.diveUntilSec()),p.crownX(),p.crownY(),p.crownZ(),p.crownRadius(),p.members(),p.path(),p.landings(),p.attackType(),p.phase(),p.groupEnds(),p.centerX(),p.centerY(),p.centerZ(),p.orientation(),p.direction(),(float)origin,0);}
 static byte[] toArray(ByteBuffer source){var a=new byte[source.capacity()];source.duplicate().clear().get(a);return a;}
 static void actualLifecycle()throws Exception{
  com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clear();
  float baseStep=Float.parseFloat(System.getProperty("cmi.dome.step",".05")),step=baseStep;boolean jitter=Boolean.getBoolean("cmi.dome.jitter");double origin=Double.parseDouble(System.getProperty("cmi.dome.clockOrigin","0")),gatherClock=0,attackClock=0;float feedbackDelay=Float.parseFloat(System.getProperty("cmi.dome.feedbackDelay","0")),deliveryDelay=Float.parseFloat(System.getProperty("cmi.dome.deliveryDelay","0")),submittedAt=-1,deliveredAt=0;float[] cadence={.6f,1.5f,.9f,1.2f,.8f};
  int n=12288,update=compile("update.comp"),gridProgram=compile("gridbuild.comp"),contact=compile("wavecontact.comp"),bodies=Boolean.getBoolean("cmi.dome.noCorrections")?compileDomeVariant("Dome diagnostic without runtime contacts",text->text.replace("vec3 preferred=(p.xyz-old)","if(uMode==0u)return;vec3 preferred=(p.xyz-old)")):compile("dome_contacts.comp");
  int initialize=compileText("actual Dome storm homes",source("chunks/allay_storm.glsl")+"layout(local_size_x=64)in;layout(std430,binding=10)buffer P{vec4 rows[];}p;void main(){uint i=gl_GlobalInvocationID.x;if(i>=12288u)return;vec3 position,velocity;cmiTyphoonHome(p.rows[i*4u+3u].z,16.,.2,.7,.3,.04,6.,vec3(0,40,0),position,velocity);p.rows[i*4u].xyz=position;p.rows[i*4u+1u].xyz=velocity;}");
  try(var q=new Fixture(n,n,n);var gpu=new StarfallGpuBuffers()){
   var data=bytes(n*64);for(int i=0;i<n;i++){particle(data,i,2,0,0,20);data.putFloat(i*64+12,i+1).putFloat(i*64+56,AllayStormWaves.memberSeed(1,i));}q.build(data,n);bind(10,q.pool);dispatch(initialize,192);data=read(q.pool,n*64);
   var h=headers();int hb=2*320;h.putFloat(hb+18*16+4,16).putFloat(hb+18*16+12,6).putFloat(hb+19*16+4,40);put(q.emit,h);put(q.previous,bytes(16).putInt(0,n));
   int output=buffer(bytes(n*64)),grid=buffer(bytes((ParticleBuffers.GRID_TABLE+n)*4)),damage=buffer(bytes(16)),players=buffer(bytes(16)),correction=buffer(bytes(n*32)),map=buffer(bytes(n*4)),reports=buffer(bytes(4+16384*24));
   float deadline=AllayDome.gatherSeconds(40,16);ui(update,"uCapacity",n);ui(update,"uKillEmit",-1);f(update,"uDt",step);gpu.clock(wrapped(origin));gpu.rebuild(new ClientboundStormWavePacket[]{clockedDome(origin,0,1,deadline),null,null,null});warmDome(gpu,update);
   Runnable bindings=()->{bind(0,q.pool);bind(1,output);bind(3,q.counter);bind(5,q.emit);bind(13,grid);bind(14,q.previous);bind(16,damage);bind(18,players);bind(19,correction);bind(21,map);};
   BitSet selected=new BitSet(),gatheredEligible=new BitSet();float lock=-1;double minimum=100,minimumAt=0;int overlaps=0;
   for(int frame=0;frame<=(int)Math.ceil(deadline/baseStep);frame++){
    step=baseStep*(jitter?cadence[frame%cadence.length]:1);double preciseTime=jitter?gatherClock:frame*(double)baseStep;float time=(float)preciseTime;gatherClock+=step;gpu.clock(wrapped(origin+preciseTime));f(update,"uDt",step);put(q.pool,data);put(q.counter,bytes(16));bindings.run();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,grid);GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,0,ParticleBuffers.GRID_TABLE*4L,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(IntBuffer)null);dispatch(gridProgram,192);
    gpu.prepare(frame+1);gpu.bind(update,true);f(update,"uTimeSec",(float)wrapped(origin+preciseTime));dispatch(update,192);DomeBodyContacts.run(gridProgram,bodies,q.pool,q.previous,output,q.counter,grid,(float)wrapped(origin+preciseTime),step,0,gpu);
    int state=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28);var ready=bytes(16);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,state);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,StarfallGpuBuffers.GATHER_COUNTS*4L,ready);
    if(frame%300==0)System.out.println("gather "+time+" ready="+ready.getInt(0)+" eligible="+ready.getInt(4));var completed=bytes(384*4);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,state);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,StarfallGpuBuffers.COMPLETIONS*4L,completed);for(int k=0;k<384;k++)check(completed.getInt(k*4)==0,"Dome completed before LOCK");
    data=read(output,n*64);
    {BitSet identities=new BitSet();for(int k=0;k<n;k++){int id=Math.round(data.getFloat(k*64+12))-1;check(id>=0&&id<n&&!identities.get(id),"Duplicate/invalid Dome stable ID at frame="+frame+" slot="+k+" id="+id);identities.set(id);}}
    if(submittedAt>=0){if(time>=submittedAt+feedbackDelay+deliveryDelay){lock=submittedAt+feedbackDelay;deliveredAt=time;gpu.commit();break;}gpu.commit();continue;}
    float now=(float)wrapped(origin+preciseTime),start=(float)origin,end=(float)(origin+deadline);
    if(DomeGatherRules.withinDeadline(now,start,end)&&DomeGatherRules.ready(ready.getInt(0),ready.getInt(4),DomeGatherRules.elapsedTicks(now,start),DomeGatherRules.durationTicks(start,end))){
     // Consume the production contact readback, not an injected timer or gathered flag.
     bind(22,reports);put(reports,bytes(4+16384*24));gpu.bind(contact,true);ui(contact,"uLocalWaveMask",1);f(contact,"uTimeSec",(float)wrapped(origin+preciseTime));dispatch(contact,192);var result=read(reports,4+16384*24);int count=result.getInt(0);BitSet eligible=new BitSet();
     for(int k=0;k<count;k++){int o=4+k*24,kind=Math.round(result.getFloat(o));if(kind!= -4&&kind!= -5)continue;int word=Math.round(result.getFloat(o+8)),bits=Math.round(result.getFloat(o+12))|(Math.round(result.getFloat(o+16))<<16);var mask=kind== -4?selected:eligible;for(int bit=0;bit<32;bit++)if((bits&(1<<bit))!=0)mask.set(word*32+bit);}
     check(DomeGatherRules.valid(selected,eligible,n,now,start,end),"actual GPU gathered packet failed server rule");gatheredEligible.or(eligible);if(feedbackDelay+deliveryDelay>0){submittedAt=time;gpu.commit();continue;}lock=time;deliveredAt=time;gpu.commit();break;
    }gpu.commit();
   }
   check(lock>=0,"Dome timed out instead of reporting gather readiness");Files.write(Path.of("build/dome-v14/lock-positions.bin"),toArray(data));Files.write(Path.of("build/dome-v14/lock-selected.bin"),selected.toByteArray());System.out.println("LOCK time="+lock+" selected="+selected.cardinality());
   var pg=new StormPlayerGrid();pg.build(new float[]{0,0,0},1);var pp=bytes(pg.words()*4);for(int k=0;k<pg.words();k++)pp.putFloat(k*4,pg.data()[k]);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,players);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,pp,GL15.GL_DYNAMIC_COPY);GL41.glProgramUniform1i(update,GL20.glGetUniformLocation(update,"uPlayerCount"),1);
   int stamps=buffer(bytes(n*16));int contacts=0;float[] firstContact=null;
   long motionSamples=0,turnsOver45=0;double maximumNormalAcceleration=0,maximumNormalTurnRate=0,maximumPoseTurnRate=0;int normalCompleted=0,earlyCompleted=0;BitSet accounted=new BitSet();BitSet done=new BitSet();done.set(0,n);done.andNot(selected);gpu.completed(27,done.toLongArray());gpu.rebuild(new ClientboundStormWavePacket[]{clockedDome(origin,2,4,lock),null,null,null});float localLock=gpu.domeLockSec(0)-(float)origin;check(Math.abs(localLock-deliveredAt)<(origin==0?.00001:.005),"Late LOCK did not capture the actual moving gather phase");
   int reportedDone=done.cardinality(),boundaryMember=-1;boolean injectBoundary=Boolean.getBoolean("cmi.dome.exitFault"),boundaryInjected=false,boundaryReleased=false;
   var launchState=bytes(n*32);float[] elapsedValues=new float[4];
   for(int frame=0;frame<(int)Math.ceil(120/baseStep)+1;frame++){
    step=baseStep*(jitter?cadence[frame%cadence.length]:1);double preciseTime=(double)deliveredAt+(jitter?attackClock+step:(frame+1)*(double)baseStep);float t=(float)preciseTime;attackClock+=step;
    if(injectBoundary&&!boundaryInjected&&t-localLock>=2){
     // Fault injection AFTER real gather/feedback/LOCK: displace one active
     // member, retaining its sphere history but losing the narrower centre latch.
     gpu.bind(0,false);int committed=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28);var previous=read(committed,n*8);
     for(int i=0;i<n;i++){int o=i*64,member=Math.round(data.getFloat(o+12))-1;if(!selected.get(member)||(previous.getInt(member*8+4)&255)!=4)continue;
      int mapped=DomeTemplate.mappedRank(member,localLock);var tangent=DomeTemplate.sampleArc(mapped,DomeTemplate.length(mapped),1).normalize();var displaced=DomeTemplate.point(mapped,DomeTemplate.length(mapped)).add(tangent.scale(40)).add(0,-.3,0);
      for(int k=0;k<3;k++){data.putFloat(o+k*4,(float)(k==0?displaced.x:k==1?displaced.y:displaced.z));data.putFloat(o+16+k*4,(float)(k==0?tangent.x:k==1?tangent.y:tangent.z)*6);}
      GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,committed);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,member*8L+4,bytes(4).putInt(0,(previous.getInt(member*8+4)&~0x20000000)|DomeExitGpuValidation.INSIDE));boundaryMember=member;boundaryInjected=true;System.out.println("Boundary fault member="+member+" attack age="+(t-localLock));break;
     }
    }
    gpu.clock(wrapped(origin+preciseTime));f(update,"uDt",step);put(q.pool,data);put(q.counter,bytes(16));bindings.run();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,grid);GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,0,ParticleBuffers.GRID_TABLE*4L,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(IntBuffer)null);dispatch(gridProgram,192);gpu.prepare(frame+10000);gpu.bind(update,true);f(update,"uTimeSec",(float)wrapped(origin+preciseTime));dispatch(update,192);DomeBodyContacts.run(gridProgram,bodies,q.pool,q.previous,output,q.counter,grid,(float)wrapped(origin+preciseTime),step,1,gpu);data=read(output,n*64);
    int state=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28);var phases=read(state,n*8);var previousPhases=read(GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,27),n*8);var before=read(q.pool,n*64);var inputSlots=read(map,n*4);
    if(frame==0){GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,state);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,StarfallGpuBuffers.DOME_JOIN*4L,launchState);}GL20.glGetUniformfv(update,GL20.glGetUniformLocation(update,"uDomeElapsed"),elapsedValues);float elapsedClock=elapsedValues[0];
    var poses=bytes(n*8);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,state);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,StarfallGpuBuffers.DOME_POSE*4L,poses);var oldPoses=bytes(n*8);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,27));GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,StarfallGpuBuffers.DOME_POSE*4L,oldPoses);
    for(int i=0;i<n;i++){int o=i*64;assertFinite(data,o,12);int member=Math.round(data.getFloat(o+12))-1;int phase=phases.getInt(member*8+4)&255;
    if(member==boundaryMember){check(phase>=6&&data.getFloat(o+28)==0,"Outside Dome member retained attack/sword after audit");boundaryReleased=true;}
    int input=inputSlots.getInt(member*4)-1;check(input>=0&&input<n&&Math.round(before.getFloat(input*64+12))-1==member,"Production stable member map addressed the wrong compacted slot");
    if(phase==4&&(previousPhases.getInt(member*8+4)&255)==4&&((phases.getInt(member*8+4)|previousPhases.getInt(member*8+4))&0x80000000)==0){double vv=0,ww=0,dot=0,delta=0;for(int j=0;j<3;j++){double v=data.getFloat(o+16+j*4),w=before.getFloat(input*64+16+j*4);vv+=v*v;ww+=w*w;dot+=v*w;delta+=(v-w)*(v-w);}if(vv>.25&&ww>.25){double angle=Math.acos(Math.clamp(dot/Math.sqrt(vv*ww),-1,1));maximumNormalTurnRate=Math.max(maximumNormalTurnRate,angle/step);maximumNormalAcceleration=Math.max(maximumNormalAcceleration,Math.sqrt(delta)/step);motionSamples++;if(angle>Math.PI/4)turnsOver45++;}}
    if(phase==4&&(poses.getInt(member*8+4)&oldPoses.getInt(member*8+4)&0x80000000)!=0){int a=poses.getInt(member*8),b=oldPoses.getInt(member*8);double yaw=((a&65535)-(b&65535))*2*Math.PI/65535,pitch=((a>>>16)-(b>>>16))*Math.PI/65535;yaw=Math.atan2(Math.sin(yaw),Math.cos(yaw));maximumPoseTurnRate=Math.max(maximumPoseTurnRate,Math.hypot(yaw,pitch)/step);}
    if(phase==6){done.set(member);if(selected.get(member)&&!accounted.get(member)){accounted.set(member);if((phases.getInt(member*8+4)&0x20000000)!=0&&DomeTemplate.passedExitPlane(DomeTemplate.mappedRank(member,localLock),new net.minecraft.world.phys.Vec3(data.getFloat(o),data.getFloat(o+4)+.3f,data.getFloat(o+8))))normalCompleted++;else earlyCompleted++;}}else if(!done.get(member)&&t>lock+.1){double speed=0;for(int j=4;j<7;j++){double v=data.getFloat(o+j*4);speed+=v*v;}double blend=Math.clamp((elapsedClock-launchState.getFloat(member*32+28))/.5,0,1);blend=blend*blend*blend*(10-15*blend+6*blend*blend);double budget=Math.max(6,Math.min(8,launchState.getFloat(member*32+24))*(1-blend)+6*blend);boolean motionValid=speed<=budget*budget+.002&&(((phases.getInt(member*8+4)|previousPhases.getInt(member*8+4))&0x80000000)!=0||speed>=.2499);check(motionValid,motionValid?"":"Dome motion invalid speed="+Math.sqrt(speed)+" member="+member+" age="+(t-lock)+" budget="+budget);}float dx=data.getFloat(o),dy=data.getFloat(o+4),dz=data.getFloat(o+8);boolean near=dx*dx+dy*dy+dz*dz<=36;// GPU dot may fuse multiply-adds at the exact six-metre boundary.
    boolean boundary=Math.abs(dx*dx+dy*dy+dz*dz-36)<.0001f;boolean carryCorrect=((phases.getInt(member*8+4)&0x80000000)!=0&&data.getFloat(o+28)==0)||data.getFloat(o+28)==(phase==4&&near?-4:0)||(phase==4&&boundary&&(data.getFloat(o+28)==0||data.getFloat(o+28)==-4));if(!carryCorrect){System.out.println("update players="+GL20.glGetUniformi(update,GL20.glGetUniformLocation(update,"uPlayerCount"))+" player="+read(players,16).getFloat(0)+","+read(players,16).getFloat(4)+","+read(players,16).getFloat(8)+" body players="+GL20.glGetUniformi(bodies,GL20.glGetUniformLocation(bodies,"uPlayerCount")));}check(carryCorrect,carryCorrect?"":"Dome sword/nearby-player phase mismatch flags="+Integer.toHexString(phases.getInt(member*8+4))+" member="+member+" phase="+phase+" d2="+(dx*dx+dy*dy+dz*dz)+" marker="+data.getFloat(o+28)+" time="+t);}
    // Contact readback uses the production map, verified above; never replace it with a CPU map.
    bind(0,q.pool);bind(1,output);bind(14,q.previous);bind(22,reports);bind(30,stamps);put(reports,bytes(4+16384*24));gpu.bind(contact,true);ui(contact,"uLocalWaveMask",1);f(contact,"uTimeSec",(float)wrapped(origin+preciseTime));f(contact,"uDt",step);GL41.glProgramUniform3f(contact,GL20.glGetUniformLocation(contact,"uLocalPlayerPos"),0,-.4f,0);GL41.glProgramUniform4fv(contact,GL20.glGetUniformLocation(contact,"uWaveTarget"),new float[]{0,-.4f,0,27,0,0,0,0,0,0,0,0,0,0,0,0});dispatch(contact,192);var candidates=read(reports,4+16384*24);for(int k=0;k<Math.min(16384,candidates.getInt(0));k++)if(candidates.getFloat(4+k*24)>=0){check(Math.round(candidates.getFloat(4+k*24))!=boundaryMember,"Boundary-retired member reported contact");contacts++;if(firstContact==null){int c=4+k*24;firstContact=new float[]{candidates.getFloat(c),candidates.getFloat(c+8),candidates.getFloat(c+12),candidates.getFloat(c+16),AllayStarfall.elapsed(candidates.getFloat(c+20),(float)wrapped(origin+lock))};}}
    {double gap=bodyGap(data,phases,n);if(gap<minimum){minimum=gap;minimumAt=t-lock;if(gap<.30){for(int worst:new int[]{worstBodyA,worstBodyB}){int o=worst*64,member=Math.round(data.getFloat(o+12))-1;var expected=DomeTemplate.lockedPoint(member,t-localLock,localLock).add(0,-.3,0);var actual=new net.minecraft.world.phys.Vec3(data.getFloat(o),data.getFloat(o+4),data.getFloat(o+8));var scratch=bytes(16);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,state);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,(StarfallGpuBuffers.DOME_CONTACT_DELTA+member*4L)*4,scratch);System.out.println("contact="+Integer.toHexString(scratch.getInt(12))+" proposedMotion="+scratch.getFloat(0)+","+scratch.getFloat(4)+","+scratch.getFloat(8));System.out.println("body gap="+gap+" age="+(t-lock)+" member="+member+" actual="+actual+" expected="+expected+" error="+actual.distanceTo(expected)+" flags="+Integer.toHexString(phases.getInt(member*8+4)));}}}if(gap<.4)overlaps++;}
    gpu.commit();if(done.cardinality()!=reportedDone){gpu.completed(27,done.toLongArray());gpu.rebuild(new ClientboundStormWavePacket[]{clockedDome(origin,2,4,lock),null,null,null});reportedDone=done.cardinality();}if(done.cardinality()==n)break;
   }
   if(injectBoundary)check(boundaryInjected&&boundaryReleased,"Boundary lifecycle fault was not exercised");
   // Vanilla torso is 3x4x2 pixels (about .189x.252x.126m at this model scale).
   // A .3m centre margin permits small head/wing edges but rejects deep body overlap.
   System.out.println("Dome completion diagnostic normal="+normalCompleted+" early="+earlyCompleted+" selected="+selected.cardinality()+" minBody="+minimum);check(minimum>=.3,"Dome bodies overlap too deeply: "+minimum+" at "+minimumAt);check(contacts>0,"Stationary center player never received actual swept contact");check(done.cardinality()==n,"Dome did not release all 12288 through production kernel");System.out.println("Dome completion normal="+normalCompleted+" early="+earlyCompleted+" selected="+selected.cardinality());check(normalCompleted>=Math.ceil(selected.cardinality()*.99),"Dome normal completion below 99 percent: "+normalCompleted+"/"+selected.cardinality()+" early="+earlyCompleted);var evidence=new com.google.gson.JsonObject();evidence.add("ready",new com.google.gson.Gson().toJsonTree(selected.toLongArray()));evidence.add("eligible",new com.google.gson.Gson().toJsonTree(gatheredEligible.toLongArray()));evidence.add("contact",new com.google.gson.Gson().toJsonTree(firstContact));evidence.addProperty("clockOrigin",origin);evidence.addProperty("production",!Boolean.getBoolean("cmi.dome.noCorrections"));evidence.addProperty("cadenceSeconds",baseStep);evidence.addProperty("jitter",jitter);evidence.addProperty("feedbackDelaySeconds",feedbackDelay);evidence.addProperty("lockDeliveryDelaySeconds",deliveryDelay);evidence.addProperty("clientLockSeconds",localLock);evidence.addProperty("gatherSeconds",lock);check(turnsOver45==0&&maximumNormalTurnRate<=Math.PI+.025,"Normal Dome navigation exceeded the turn bound");check(maximumNormalAcceleration<=18.08,"Normal Dome navigation exceeded acceleration bound: "+maximumNormalAcceleration);check(maximumPoseTurnRate<=Math.PI+.025,"Committed Dome pose exceeded slew bound: "+maximumPoseTurnRate);evidence.addProperty("motionSamples",motionSamples);evidence.addProperty("turnsOver45Degrees",turnsOver45);evidence.addProperty("maximumNormalTurnRadiansPerSecond",maximumNormalTurnRate);evidence.addProperty("maximumNormalAcceleration",maximumNormalAcceleration);evidence.addProperty("maximumPoseTurnRadiansPerSecond",maximumPoseTurnRate);evidence.addProperty("injectedBoundaryMember",boundaryMember);evidence.addProperty("normalCompleted",normalCompleted);evidence.addProperty("earlyCompleted",earlyCompleted);evidence.addProperty("minimumBodyGap",minimum);try(var in=DomeTemplate.class.getResourceAsStream("/data/createmanaindustry/dome-template-v4.bin")){evidence.addProperty("sha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(in.readAllBytes())));}Files.createDirectories(Path.of("build/dome-v14"));if(!injectBoundary&&!Boolean.getBoolean("cmi.dome.noCorrections"))Files.writeString(Path.of("build/dome-v14/gpu-evidence.json"),evidence.toString());Files.writeString(Path.of("build/dome-v14/gpu-evidence-"+Math.round(1/baseStep)+"hz"+(jitter?"-jitter":"")+(feedbackDelay+deliveryDelay>0?"-delayed":"")+(Boolean.getBoolean("cmi.dome.noCorrections")?"-diagnostic":"")+(origin!=0?"-clock-wrap":"")+(injectBoundary?"-boundary":"")+".json"),evidence.toString());
   // Release through the same incremental CPU ownership updates as production.
   gpu.completed(27,done.toLongArray());gpu.rebuild(new ClientboundStormWavePacket[4]);
   verifyReturn(q,gpu,data,update,gridProgram,bodies,output,grid,bindings,baseStep);
   GL15.glDeleteBuffers(stamps);System.out.println("Dome dt="+baseStep+" jitter="+jitter+" actual gather seconds="+lock+" participants="+selected.cardinality()+" completed="+done.cardinality()+" minimum="+minimum+" minimumAt="+minimumAt+" overlapFrames="+overlaps+" centerContacts="+contacts);
   for(int b:new int[]{output,grid,damage,players,correction,map,reports})GL15.glDeleteBuffers(b);
  }finally{for(int p:new int[]{update,gridProgram,contact,initialize,bodies}){com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clearUniformProgram(p);deleteProgram(p);}}
 }
 // Runs after actual START/gather/readback/LOCK/contact/completion, never an injected LOCK.
 static void verifyReturn(Fixture q,StarfallGpuBuffers gpu,ByteBuffer data,int update,int gridProgram,int bodies,int output,int grid,Runnable bindings,float dt)throws Exception{
  int n=data.capacity()/64;
  int probe=compileText("ordinary circulating return targets",source("chunks/allay_storm.glsl")+"layout(local_size_x=64)in;layout(std430,binding=10)buffer P{vec4 rows[];}p;uniform float age;uniform vec3 anchor;uniform float radius;void main(){uint i=gl_GlobalInvocationID.x;if(i>=12288u)return;vec3 position,velocity;cmiTyphoonHome(p.rows[i*4u+3u].z,radius,.2,.2*age,.04*age,.04,6.,anchor,position,velocity);p.rows[i*4u].xyz=position;p.rows[i*4u+1u].xyz=velocity;}");
  int[] homeSlots=new int[n];for(int i=0;i<n;i++)homeSlots[Math.round(data.getFloat(i*64+12))-1]=i;
  int emptyDomain=buffer(bytes(StarfallGpuBuffers.DATA_WORDS*4));
  int homes=buffer(data);var reached=new BitSet(n);float settled=-1,observed=0;double maximum=0;int hb=2*320;long steadyUploads=-1;
  var samples=new ArrayList<String>();samples.add("seconds,member,x,y,z,vx,vy,vz,homeX,homeY,homeZ,attackId,phase,poseValid");
  try{
   for(int frame=0;frame<Math.ceil(110/dt);frame++){
    float age=(frame+1)*dt;boolean moving=Boolean.parseBoolean(System.getProperty("cmi.dome.returnMotion","true"));float radius=16+(moving?age*.01f:0);
    float offset=Float.parseFloat(System.getProperty("cmi.dome.returnOffset","0"));
    float x=offset+(moving?age*.04f:0),y=40,z=-offset;
    // Far-coordinate variant translates the actual released state once, preserving velocity.
    if(frame==0&&offset!=0){for(int i=0;i<n;i++){int o=i*64;data.putFloat(o,data.getFloat(o)+offset).putFloat(o+8,data.getFloat(o+8)-offset);}}
    var h=headers();h.putFloat(hb+18*16+4,radius).putFloat(hb+18*16+12,6).putFloat(hb+19*16,x).putFloat(hb+19*16+4,y).putFloat(hb+19*16+8,z);put(q.emit,h);
    f(update,"uStormOmegaNow",.2f);f(update,"uStormRotPhase",age*.2f);f(update,"uStormConvPhase",age*.04f);f(update,"uStormConvRate",.04f);
    gpu.clock(age+200);f(update,"uDt",dt);put(q.pool,data);put(q.counter,bytes(16));bindings.run();
    GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,grid);GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,0,ParticleBuffers.GRID_TABLE*4L,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(IntBuffer)null);dispatch(gridProgram,(n+63)/64);
    gpu.prepare(200000+frame);gpu.bind(update,true);bind(26,emptyDomain);bool(update,"uStarfallEnabled",true);f(update,"uTimeSec",age+200);dispatch(update,(n+63)/64);
    DomeBodyContacts.run(gridProgram,bodies,q.pool,q.previous,output,q.counter,grid,age+200,dt,1,gpu);data=read(output,n*64);
    bind(10,homes);f(probe,"age",age);f(probe,"radius",radius);GL41.glProgramUniform3f(probe,GL20.glGetUniformLocation(probe,"anchor"),x,y,z);dispatch(probe,(n+63)/64);var target=read(homes,n*64);
    var phases=read(GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28),n*8);int outside=0;maximum=0;
    for(int i=0;i<n;i++){int o=i*64,member=Math.round(data.getFloat(o+12))-1;double error=0;assertFinite(data,o,12);for(int k=0;k<3;k++){double d=data.getFloat(o+k*4)-target.getFloat(homeSlots[member]*64+k*4);error+=d*d;}maximum=Math.max(maximum,Math.sqrt(error));if(error<4)reached.set(member);else outside++;check(data.getFloat(o+28)==0,"Released Dome retained attack marker");check((phases.getInt(member*8+4)&255)>=6,"Released Dome re-entered active stage");}
    if(frame%Math.max(1,Math.round(1/dt))==0){var pose=bytes(12*8);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28));GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,StarfallGpuBuffers.DOME_POSE*4L,pose);for(int i=0;i<n;i++){int o=i*64,member=Math.round(data.getFloat(o+12))-1;if(member>=12)continue;String row=age+","+member;for(int k=0;k<3;k++)row+=","+data.getFloat(o+k*4);for(int k=0;k<3;k++)row+=","+data.getFloat(o+16+k*4);for(int k=0;k<3;k++)row+=","+target.getFloat(homeSlots[member]*64+k*4);samples.add(row+","+phases.getInt(member*8)+","+(phases.getInt(member*8+4)&255)+","+((pose.getInt(member*8+4)&0x80000000)!=0));}}
    gpu.commit();observed=age;if(frame==2)steadyUploads=gpu.uploadedBytes();if(frame>2)check(steadyUploads==gpu.uploadedBytes(),"Steady return uploaded member/trajectory data");if(settled<0&&reached.cardinality()==n)settled=age;
    if(frame%Math.max(1,Math.round(5/dt))==0)System.out.println("RETURN age="+age+" outside="+outside+" maximum="+maximum+" reached="+reached.cardinality());
    if(settled>=0&&age>=settled+60){check(maximum<2,"Ordinary return developed growing deviation: "+maximum);break;}
   }
   check(settled>=0,"Released members failed to reach their ordinary circulating target: "+reached.cardinality()+"/"+n+" error="+maximum);
   check(observed>=settled+60,"Return observation ended before sixty settled seconds");
   String profile="boundary"+System.getProperty("cmi.dome.exitFault","false")+"-"+System.getProperty("cmi.dome.returnOffset","0")+"-"+System.getProperty("cmi.dome.returnMotion","true")+"-jitter"+System.getProperty("cmi.dome.jitter","false")+"-clock"+System.getProperty("cmi.dome.clockOrigin","0")+"-delay"+System.getProperty("cmi.dome.feedbackDelay","0");
   Path evidence=Path.of("build/dome-v15/return-"+Math.round(1/dt)+"hz-"+profile);Files.createDirectories(evidence.getParent());Files.write(Path.of(evidence+".csv"),samples);
   var result=new com.google.gson.JsonObject();result.addProperty("members",n);result.addProperty("allReachedSeconds",settled);result.addProperty("observedSeconds",observed);result.addProperty("finalMaximumError",maximum);result.addProperty("steadyUploadBytes",0);Files.writeString(Path.of(evidence+".json"),result.toString());
   System.out.println("Ordinary return all reached by "+settled+"s; observed another 60s; maximum error="+maximum);
  }finally{GL15.glDeleteBuffers(emptyDomain);GL15.glDeleteBuffers(homes);deleteProgram(probe);}
 }
 static final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap bodyCells=new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
 static int[] bodyNext=new int[12288];static int worstBodyA,worstBodyB;
 static double bodyGap(ByteBuffer data,ByteBuffer phases,int n){
  bodyCells.clear();double result=.8,cell=.6;
  for(int i=0;i<n;i++){
   int o=i*64,member=Math.round(data.getFloat(o+12))-1;if((phases.getInt(member*8+4)&255)!=4)continue;
   double x=data.getFloat(o),y=data.getFloat(o+4),z=data.getFloat(o+8);int cx=(int)Math.floor(x/cell),cy=(int)Math.floor(y/cell),cz=(int)Math.floor(z/cell);
   int ax=(int)Math.floor((x-result)/cell),bx=(int)Math.floor((x+result)/cell),ay=(int)Math.floor((y-result)/cell),by=(int)Math.floor((y+result)/cell),az=(int)Math.floor((z-result)/cell),bz=(int)Math.floor((z+result)/cell);
   for(int a=ax;a<=bx;a++)for(int b=ay;b<=by;b++)for(int c=az;c<=bz;c++){
    double dx=Math.max(a*cell-x,Math.max(0,x-(a+1)*cell)),dy=Math.max(b*cell-y,Math.max(0,y-(b+1)*cell)),dz=Math.max(c*cell-z,Math.max(0,z-(c+1)*cell));if(dx*dx+dy*dy+dz*dz>=result*result)continue;
    for(int node=bodyCells.get(key(a,b,c));node!=0;node=bodyNext[node-1]){int q=(node-1)*64;dx=x-data.getFloat(q);dy=y-data.getFloat(q+4);dz=z-data.getFloat(q+8);double distance=Math.sqrt(dx*dx+dy*dy+dz*dz);if(distance<result){result=distance;worstBodyA=i;worstBodyB=node-1;}}
   }
   bodyNext[i]=bodyCells.put(key(cx,cy,cz),i+1);
  }return result;
 }
 static long key(int x,int y,int z){return ((long)(x&0x1fffff)<<42)|((long)(y&0x1fffff)<<21)|(z&0x1fffff);}
 static void renderDome(ByteBuffer rows,int count)throws Exception{
  // Framebuffer geometry evidence, not a substitute for the user's in-game model/art acceptance.
  int vs=GL20.glCreateShader(GL20.GL_VERTEX_SHADER),fs=GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
  GL20.glShaderSource(vs,"#version 450 core\nlayout(std430,binding=10)readonly buffer R{vec4 p[];};uniform mat4 vp;out vec3 color;void main(){vec4 v=p[uint(gl_VertexID)*3u];gl_Position=vp*vec4(v.xyz,1);gl_PointSize=2.;color=vec3(.25,.7,1);}");
  GL20.glShaderSource(fs,"#version 450 core\nin vec3 color;out vec4 outColor;void main(){outColor=vec4(color,1);}");
  GL20.glCompileShader(vs);GL20.glCompileShader(fs);int program=GL20.glCreateProgram();GL20.glAttachShader(program,vs);GL20.glAttachShader(program,fs);GL20.glLinkProgram(program);check(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(program));
  int vao=GL30.glGenVertexArrays(),fb=GL30.glGenFramebuffers(),tex=GL11.glGenTextures(),input=buffer(rows);
  GL30.glBindVertexArray(vao);GL11.glBindTexture(GL11.GL_TEXTURE_2D,tex);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,512,384,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(ByteBuffer)null);
  GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fb);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,tex,0);bind(10,input);
  var vp=new org.joml.Matrix4f().setPerspective((float)Math.toRadians(48),512f/384,.1f,160).lookAt(60,38,75,0,12,0,0,1,0);
  GL20.glUseProgram(program);GL41.glProgramUniformMatrix4fv(program,GL20.glGetUniformLocation(program,"vp"),false,vp.get(new float[16]));GL11.glViewport(0,0,512,384);GL11.glClearColor(.025f,.035f,.07f,1);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);GL11.glEnable(GL32.GL_PROGRAM_POINT_SIZE);GL11.glDrawArrays(GL11.GL_POINTS,0,count);
  var pixels=bytes(512*384*4);GL11.glReadPixels(0,0,512,384,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);var image=new java.awt.image.BufferedImage(512,384,java.awt.image.BufferedImage.TYPE_INT_RGB);int lit=0;
  for(int y=0;y<384;y++)for(int x=0;x<512;x++){int o=(y*512+x)*4,r=pixels.get(o)&255,g=pixels.get(o+1)&255,b=pixels.get(o+2)&255;if(b>100)lit++;image.setRGB(x,383-y,(r<<16)|(g<<8)|b);}check(lit>100,"dome framebuffer missing");javax.imageio.ImageIO.write(image,"png",Path.of(System.getProperty("cmi.benchmark.output", "build/benchmarks/current")+"/dome-geometry-framebuffer.png").toFile());
  GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,0);GL11.glDisable(GL32.GL_PROGRAM_POINT_SIZE);GL15.glDeleteBuffers(input);com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames.deleteTexture(tex);GL30.glDeleteFramebuffers(fb);com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames.deleteVertexArray(vao);GL20.glUseProgram(0);deleteProgram(program);GL20.glDeleteShader(vs);GL20.glDeleteShader(fs);
 }

 public static void main(String[] args)throws Exception{GLFWErrorCallback.createPrint(System.err).set();check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(512,384,"Dome v11 GPU",0,0);try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();System.out.println(GL11.glGetString(GL11.GL_RENDERER));if(Boolean.getBoolean("cmi.dome.slopeOnly")){slopeGather();return;}if(!Boolean.getBoolean("cmi.dome.lifecycleOnly")){slopeGather();publishedClockWins();pausedGeometry();cyclicMapping();geometry();gatherGates();coincidentSeparation();lazyStateGrowth();exceptionalBodyDomain();}if(!Boolean.getBoolean("cmi.dome.geometryOnly"))actualLifecycle();check(GL11.glGetError()==0,"Dome GL error");System.out.println("Dome GPU: "+checks+" assertions passed");}finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}}
}
