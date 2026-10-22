import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.allaystorm.*;
import com.iridium126.createmanaindustry.client.particles.engine.*;
import com.iridium126.createmanaindustry.content.allaystorm.*;
import com.iridium126.createmanaindustry.content.allaystorm.network.*;
import net.minecraft.world.phys.Vec3;

public class StormMotionGpuValidation extends StarfallColumnGpuValidation {
 static class ColumnProbe implements AutoCloseable {
  final int update;
  final int count;
  final Fixture fixture;
  final StarfallGpuBuffers gpu=new StarfallGpuBuffers();
  final int output,grid,damage,players,corrections,map;
  ByteBuffer data;
  long generation;
  ColumnProbe(int members,int program){
   count=members;update=program;fixture=new Fixture(count,count,count);data=bytes(count*64);
   output=fixture.alloc(bytes(count*64));grid=fixture.alloc(bytes((ParticleBuffers.GRID_TABLE+count)*4));damage=fixture.alloc(bytes(16));players=fixture.alloc(bytes(16));corrections=fixture.alloc(bytes(count*32));map=fixture.alloc(bytes(count*4));
   put(fixture.emit,headers());put(fixture.previous,bytes(16).putInt(0,count));
   ui(update,"uCapacity",count);ui(update,"uKillEmit",-1);
   gpu.rebuild(columns());
  }
  ByteBuffer step(float time,float dt,boolean commit){
   put(fixture.pool,data);put(fixture.counter,bytes(16));
   bind(0,fixture.pool);bind(1,output);bind(3,fixture.counter);bind(5,fixture.emit);bind(13,grid);bind(14,fixture.previous);bind(16,damage);bind(18,players);bind(19,corrections);bind(21,map);
   gpu.prepare(++generation);gpu.bind(update,true);f(update,"uTimeSec",time);f(update,"uDt",dt);dispatch(update,(count+63)/64);
   check(read(fixture.counter,16).getInt(0)==count,"Motion probe lost members");
   data=read(output,count*64);int state=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28);var result=read(state,StarfallGpuBuffers.DOME_JOIN*4);if(commit)gpu.commit();return result;
  }
  public void close(){gpu.close();fixture.close();retire(update);}
 }
 static void starfallEntry()throws Exception {
  var rows=new ArrayList<String>();rows.add("time,rank,phase,tangential_m_s,vertical_m_s,total_m_s");
  try(var probe=new ColumnProbe(64,compile("update.comp"))){
   for(int rank=0;rank<64;rank++){var packet=column(0);var point=StarfallColumnRules.gather(new Vec3(packet.x(),packet.gatherY(),packet.z()),rank,packet.seed(),2);particle(probe.data,rank,2,(float)point.x+100,(float)point.z,20);probe.data.putFloat(rank*64+4,(float)point.y).putFloat(rank*64+12,rank+1);}
   probe.step(2,.05f,true);double maximum=0;for(int rank=0;rank<64;rank++){double speed=Math.sqrt(Math.pow(probe.data.getFloat(rank*64+16),2)+Math.pow(probe.data.getFloat(rank*64+20),2)+Math.pow(probe.data.getFloat(rank*64+24),2));maximum=Math.max(maximum,speed);check(speed<=8.001,"Starfall interception exceeded eight");}check(maximum>7.9,"Starfall interception was clipped back to six");
   probe.gpu.invalidate();probe.gpu.rebuild(columns());float dt=1/60f,time=4-3*dt;
   for(int rank=0;rank<64;rank++){var packet=column(0);var point=StarfallColumnRules.gather(new Vec3(packet.x(),packet.gatherY(),packet.z()),rank,packet.seed(),time-dt);double angle=StarfallColumnRules.orbitAngle(rank,packet.seed(),time-dt);particle(probe.data,rank,2,(float)point.x,(float)point.z,20);probe.data.putFloat(rank*64+4,(float)point.y).putFloat(rank*64+12,rank+1).putFloat(rank*64+16,(float)(-Math.sin(angle)*3)).putFloat(rank*64+20,0).putFloat(rank*64+24,(float)(Math.cos(angle)*3));}
   boolean entered=false;
   for(int frame=0;frame<12;frame++,time+=dt){
    var state=probe.step(time,dt,true);for(int row=0;row<64;row++){
     int offset=row*64,rank=Math.round(probe.data.getFloat(offset+12))-1;check(rank>=0&&rank<64,"Starfall entry lost stable identity");var packet=column(0);double radialX=probe.data.getFloat(offset)-packet.x(),radialZ=probe.data.getFloat(offset+8)-packet.z(),radius=Math.hypot(radialX,radialZ);
     double tangential=(-radialZ*probe.data.getFloat(offset+16)+radialX*probe.data.getFloat(offset+24))/radius,vertical=probe.data.getFloat(offset+20),speed=Math.sqrt(Math.pow(probe.data.getFloat(offset+16),2)+vertical*vertical+Math.pow(probe.data.getFloat(offset+24),2));
     int arc=StarfallGpuBuffers.COLUMN_ARC+rank*96,phase=state.getInt((arc+43)*4);rows.add(time+","+rank+","+phase+","+tangential+","+vertical+","+speed);
     check(speed<=(time<4?8.001:6.001),"Starfall phase speed budget violated");
     if(time<4)check(Math.abs(tangential-3)<.02,"Settled Starfall orbit is not three m/s");
     if(phase==2){double travel=state.getFloat((StarfallGpuBuffers.COLUMN_STATE+rank*16+15)*4);var expected=entryCurve(state,arc,packet,rank,entryParameter(state,arc,travel)).subtract(entryCurve(state,arc,packet,rank,entryParameter(state,arc,Math.max(0,travel-6*dt)))).normalize().scale(6);var actual=new Vec3(probe.data.getFloat(offset+16),vertical,probe.data.getFloat(offset+24));check(expected.distanceTo(actual)<.02,"Discrete Starfall velocity departed from its actual initialized curve: "+expected+" / "+actual);}
     if(phase==2&&!entered){double startX=state.getFloat((arc+33)*4)-packet.x(),startZ=state.getFloat((arc+35)*4)-packet.z(),startRadius=Math.hypot(startX,startZ);double tangent=(-startZ*state.getFloat((arc+36)*4)+startX*state.getFloat((arc+38)*4))/startRadius;check(Math.abs(tangent*6-3)<.005,"Descent entry tangent does not match gathering");check(Math.abs(state.getFloat((arc+37)*4)*6+Math.sqrt(27))<.005,"Descent entry vertical component wrong");}
    }
    if(state.getInt((StarfallGpuBuffers.COLUMN_ARC+43)*4)==2)entered=true;
   }
   check(entered,"Column added a new delay before descent");
  }
  Files.write(Path.of(System.getProperty("cmi.benchmark.output","build/benchmarks/current"),"starfall-entry-velocity.csv"),rows);
 }
 static double entryParameter(ByteBuffer state,int arc,double distance){
  if(distance<=0)return 0;if(distance>=state.getFloat((arc+32)*4))return 1;int low=0,high=32;
  for(int iteration=0;iteration<5;iteration++){int middle=(low+high)/2;if(state.getFloat((arc+middle)*4)<distance)low=middle;else high=middle;}
  double start=state.getFloat((arc+low)*4),delta=Math.max(state.getFloat((arc+high)*4)-start,.000001),goal=distance-start,progress=Math.clamp(goal/delta,0,1);
  double first=Math.clamp(state.getFloat((arc+48+low)*4)/32.,.000001,3*delta),last=Math.clamp(state.getFloat((arc+48+high)*4)/32.,.000001,3*delta),cubic=first+last-2*delta,quadratic=3*delta-2*first-last;
  for(int iteration=0;iteration<3;iteration++){double value=((cubic*progress+quadratic)*progress+first)*progress,derivative=(3*cubic*progress+2*quadratic)*progress+first;progress=Math.clamp(progress-(value-goal)/Math.max(derivative,.000001),0,1);}
  return (low+progress)/32.;
 }
 static Vec3 entryCurve(ByteBuffer state,int arc,ClientboundStormColumnPacket packet,int rank,double progress){
  double startX=state.getFloat((arc+33)*4)-packet.x(),startY=state.getFloat((arc+34)*4)-packet.y(),startZ=state.getFloat((arc+35)*4)-packet.z(),radius=Math.max(Math.hypot(startX,startZ),.2),root=StarfallColumnRules.root(rank),angle=Math.atan2(startZ,startX);
  double terminal=angle+.7*(packet.touch()+StarfallColumnRules.delay(rank)-packet.assemble()),goal=((rank*27)&63)*Math.PI/32+packet.seed()*.0001f;terminal+=Math.atan2(Math.sin(goal-terminal),Math.cos(goal-terminal));
  double radialX=Math.cos(angle),radialZ=Math.sin(angle),directionX=state.getFloat((arc+36)*4),directionY=state.getFloat((arc+37)*4),directionZ=state.getFloat((arc+38)*4),blend=progress*progress*progress*(10-15*progress+6*progress*progress),first=progress*(1-progress)*(1-progress),last=progress*progress*(progress-1);
  double radialDerivative=Math.clamp((directionX*radialX+directionZ*radialZ)*4,-.25*radius,.25*radius),angularDerivative=(-directionX*radialZ+directionZ*radialX)*4/radius;
  radius=Math.max(.2,radius*(1-blend)+root*blend+radialDerivative*first+.35*last);angle=angle*(1-blend)+terminal*blend+angularDerivative*first;
  return new Vec3(Math.cos(angle)*radius,startY*(1-blend)+StarfallColumnRules.height(root)*blend+directionY*4*first+.35*root*.125*last,Math.sin(angle)*radius);
 }
 static void domeInterception(){
  try(var probe=new DomeExitGpuValidation.Probe()){
   probe.wave=DomeGpuValidation.domeAt(probe.id,0,1,90);probe.gpu.rebuild(new ClientboundStormWavePacket[]{probe.wave,null,null,null});probe.seed(new Vec3(100,40,0),1,1);
   double maximum=0;for(int frame=0;frame<20;frame++){check((probe.step(1+frame*.05f,.05f)&255)==1,"Dome interception changed phase");double speed=Math.sqrt(Math.pow(probe.data.getFloat(16),2)+Math.pow(probe.data.getFloat(20),2)+Math.pow(probe.data.getFloat(24),2));maximum=Math.max(maximum,speed);check(speed<=8.001,"Dome interception exceeded eight");}check(maximum>7.9,"Dome final contact pass clipped interception to six");
   probe.wave=DomeGpuValidation.domeAt(probe.id,2,4,10);probe.gpu.rebuild(new ClientboundStormWavePacket[]{probe.wave,null,null,null});
   probe.seed(new Vec3(24,12,0),1,10);probe.data.putFloat(16,-8);probe.gpu.bind(0,false);DomeExitGpuValidation.writeRange(GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28),StarfallGpuBuffers.DOME_JOIN*4L,bytes(32));
   double first=0;for(int frame=0;frame<40;frame++){probe.step(10+frame/60f,1/60f);double speed=Math.sqrt(Math.pow(probe.data.getFloat(16),2)+Math.pow(probe.data.getFloat(20),2)+Math.pow(probe.data.getFloat(24),2));if(frame==0)first=speed;check(speed<=(frame>=30?6.001:8.001),"Dome LOCK violated its transient or stable budget");}check(first>7.9,"Dome LOCK hard-clipped eight to six");
  }
 }
 static void unsettledEntry(){
  try(var probe=new ColumnProbe(1,compile("update.comp"))){
   var packet=column(0);var point=StarfallColumnRules.gather(new Vec3(packet.x(),packet.gatherY(),packet.z()),0,packet.seed(),4-1/60f);
   particle(probe.data,0,2,(float)point.x+2,(float)point.z,20);probe.data.putFloat(4,(float)point.y).putFloat(12,1).putFloat(16,1);
   var state=probe.step(4,1/60f,true);int arc=StarfallGpuBuffers.COLUMN_ARC;
   check(state.getInt((arc+43)*4)==2,"Unsettled member added an entry stage");
   check(state.getFloat((arc+36)*4)==1&&state.getFloat((arc+37)*4)==0&&state.getFloat((arc+38)*4)==0,"Unsettled member lost its actual incoming direction");
  }
 }
 static void domeFastNeighbor(){
  int gridProgram=compile("gridbuild.comp"),body=compile("dome_contacts.comp");
  try(var fixture=new Fixture(2,2,2);var gpu=new StarfallGpuBuffers()){
   var original=bytes(128);particle(original,0,2,0,0,20);particle(original,1,2,-6,0,20);original.putFloat(12,1).putFloat(64+12,12289).putFloat(64+16,32);
   var candidate=bytes(128);candidate.put(original.duplicate()).clear();candidate.putFloat(64,2);
   put(fixture.pool,original);put(fixture.emit,headers());put(fixture.previous,bytes(16).putInt(0,2));put(fixture.counter,bytes(16).putInt(0,2));
   int output=fixture.alloc(candidate),grid=fixture.alloc(bytes((ParticleBuffers.GRID_TABLE+2)*4)),map=fixture.alloc(bytes(16384*4).putInt(0,1).putInt(12288*4,2));
   var dome=DomeGpuValidation.domeAt(71,0,1,90);var helix=HelixGpuValidation.helix(4096,72,2,4,false).withMembers(java.util.stream.IntStream.range(12288,16384).toArray());
   gpu.rebuild(new ClientboundStormWavePacket[]{dome,helix,null,null});DomeGpuValidation.warmDome(gpu,body);
   for(int pass=0;pass<2;pass++){
    if(pass==1){gpu.commit();gpu.rebuild(new ClientboundStormWavePacket[]{dome,null,null,null});gpu.prepare(100000);gpu.bind(body,true);}
    put(output,candidate);int state=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28);DomeExitGpuValidation.writeRange(state,0,bytes(8).putInt(0,71).putInt(4,1));bind(5,fixture.emit);bind(21,map);
    DomeBodyContacts.run(gridProgram,body,fixture.pool,fixture.previous,output,fixture.counter,grid,12,.25f,0,gpu);
    var result=read(output,128);var diagnostic=bytes(4);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,state);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,StarfallGpuBuffers.DOME_CONTACT_ARGS*4L,diagnostic);check(diagnostic.getInt(0)>0,"Dome missed a 32m/s cross-attack or released neighbor");
    check(result.getFloat(64)==2&&result.getFloat(64+16)==32,"Dome solver changed another attack's member");check(Math.sqrt(Math.pow(result.getFloat(0),2)+Math.pow(result.getFloat(4),2)+Math.pow(result.getFloat(8),2))<=2.001,"High-speed neighbor forced Dome past its own eight m/s budget");
   }
  }finally{retire(gridProgram,body);}
 }
 static void danceBoundary(){
  int program=compileText("Dance boundary",source("chunks/allay_pose.glsl")+"layout(local_size_x=64)in;layout(std430,binding=10)buffer Result{uint value[];}result;void main(){uint row=gl_GlobalInvocationID.x;if(row>=144u)return;float distances[4]=float[4](0.,19.999,20.,20.001);result.value[row]=uint(cmiStormAnimOverride(distances[row%4u],100.,.123,float(row/4u)));}");
  int output=buffer(bytes(144*4));try{bind(10,output);dispatch(program,3);var result=read(output,144*4);int cycles=0;for(int frame=0;frame<36;frame++){check(result.getInt(frame*16)==result.getInt(frame*16+4),"Inner dance rhythm changed");cycles+=result.getInt(frame*16);check(result.getInt(frame*16+8)==0&&result.getInt(frame*16+12)==0,"Dance radius includes the 0.2R boundary");}check(cycles>=10&&cycles<=11,"Dance cadence changed");}finally{GL15.glDeleteBuffers(output);retire(program);}
 }
 static void helixRotation(){
  for(float dt:new float[]{1/60f,.05f,.25f})try(var probe=new ColumnProbe(1,compile("update.comp"))){
   var packet=HelixGpuValidation.helix(4096,51,2,4,false);probe.gpu.rebuild(new ClientboundStormWavePacket[]{packet,null,null,null});
   int rank=0;double radius=0;for(int candidate=0;candidate<4096;candidate++){double radial=Math.hypot(HelixTemplate.coordinate(candidate,1),HelixTemplate.coordinate(candidate,2));if(radial>radius){radius=radial;rank=candidate;}}
   var axis=AllayHelix.axis(packet.helix());var side=new Vec3(0,1,0).cross(axis).normalize();var up=axis.cross(side);var center=new Vec3(packet.centerX(),packet.centerY(),packet.centerZ());
   var point=center.add(axis.scale(HelixTemplate.coordinate(rank,0))).add(side.scale(HelixTemplate.coordinate(rank,1))).add(up.scale(HelixTemplate.coordinate(rank,2)));
   particle(probe.data,0,2,(float)point.x,(float)point.z,20);probe.data.putFloat(4,(float)point.y).putFloat(12,rank+1);
   double startAngle=Math.atan2(HelixTemplate.coordinate(rank,2),HelixTemplate.coordinate(rank,1)),angle=startAngle,rotation=0;
   for(int frame=1;frame<=Math.round(2/dt);frame++){
    probe.step(10+frame*dt,dt,true);var actual=new Vec3(probe.data.getFloat(0),probe.data.getFloat(4),probe.data.getFloat(8)).subtract(center);double next=Math.atan2(actual.dot(up),actual.dot(side));rotation+=Math.atan2(Math.sin(next-angle),Math.cos(next-angle));angle=next;
    var velocity=new Vec3(probe.data.getFloat(16),probe.data.getFloat(20),probe.data.getFloat(24));check(velocity.length()<=32.001,"Actual rotating Helix exceeded 32m/s");
   }
   check(rotation< -1.1&&rotation> -2.2,"Actual Helix failed to rotate with its template at step "+dt+": "+rotation);
  }
  try(var probe=new HelixGpuValidation.BlockingProbe(new Vec3(1,0,0))){
   probe.terrain(1);probe.seed(new Vec3(-4,10,0),4);probe.data.putFloat(16,32);var before=new Vec3(-4,10,0);probe.step(12,.25f,true);
   var after=new Vec3(probe.data.getFloat(0),probe.data.getFloat(4),probe.data.getFloat(8));check(after.x>0&&after.x<1.001,"High-speed Helix stopped early or tunneled through a wall: "+after);check(after.distanceTo(before)<=8.001,"Low-FPS Helix exceeded its sweep budget");
  }
 }
 static void profile()throws Exception {
  var summary=new ArrayList<String>();summary.add("variant,run,update_gpu_p50_ms,update_gpu_p95_ms");
  String original=source("update.comp");
  for(String variant:new String[]{"production","finite-helix","old-entry","old-dome"}){
   String text=original;
   if(variant.equals("finite-helix"))text=text.replace("if(cmiHelix(cmiStarBase(cmiStarSlot(assignment)))&&phase==4u){velocity=cmiHelixVelocity(assignment,cmiStarTime(cmiStarBase(cmiStarSlot(assignment)),uTimeSec));return true;}","");
   if(variant.equals("old-entry")){int start=text.indexOf("vec3 direction=vel;float begin=time-step;"),end=text.indexOf("cmiColumnCurveInit(s,arc,b,rank,pos,direction,begin);state=2u;",start);text=text.substring(0,start)+"cmiColumnCurveInit(s,arc,b,rank,pos,vel,time-step);state=2u;"+text.substring(end+"cmiColumnCurveInit(s,arc,b,rank,pos,direction,begin);state=2u;".length());}
   if(variant.equals("old-dome"))text=text.replace("spMaxV=cmiDomeMotionLimit(4u,age,initialSpeed);","");
   try(var probe=new ColumnProbe(4096,compileText(variant,text))){
    for(int member=0;member<4096;member++){var packet=column(member/64);var point=StarfallColumnRules.bloom(new Vec3(packet.x(),packet.y(),packet.z()),member%64,packet.seed(),0);particle(probe.data,member,2,(float)point.x,(float)point.z,20);probe.data.putFloat(member*64+4,(float)point.y).putFloat(member*64+12,member+1);}
    probe.gpu.prepare(1);probe.gpu.bind(probe.update,true);int state=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,27);var phases=bytes(StarfallGpuBuffers.DOME_JOIN*4);for(int member=0;member<4096;member++){int at=(StarfallGpuBuffers.COLUMN_STATE+member*16)*4,arc=(StarfallGpuBuffers.COLUMN_ARC+member*96)*4;phases.putInt(at,100+member/64).putFloat(at+4,25);phases.putInt(arc+43*4,4).putFloat(arc+45*4,(float)StarfallColumnRules.root(member%64));}put(state,phases);
    put(probe.fixture.pool,probe.data);bind(0,probe.fixture.pool);bind(1,probe.output);bind(3,probe.fixture.counter);bind(5,probe.fixture.emit);bind(13,probe.grid);bind(14,probe.fixture.previous);bind(16,probe.damage);bind(18,probe.players);bind(19,probe.corrections);bind(21,probe.map);f(probe.update,"uTimeSec",25.1f);f(probe.update,"uDt",.05f);
    int query=GL15.glGenQueries();try{for(int run=0;run<3;run++){double[] times=new double[120];for(int sample=-40;sample<120;sample++){put(probe.fixture.counter,bytes(16));GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,query);dispatch(probe.update,64);GL15.glEndQuery(GL33.GL_TIME_ELAPSED);double elapsed=GL33.glGetQueryObjectui64(query,GL15.GL_QUERY_RESULT)/1e6;if(sample>=0)times[sample]=elapsed;}summary.add(variant+","+run+","+p(times,.5)+","+p(times,.95));}}finally{GL15.glDeleteQueries(query);}
   }
  }
  Files.write(Path.of(System.getProperty("cmi.benchmark.output","build/benchmarks/current"),"motion-kernel-profile.csv"),summary);summary.forEach(System.out::println);
 }
 public static void main(String[] args)throws Exception {
  GLFWErrorCallback.createPrint(System.err).set();check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(64,64,"Storm motion validation",0,0);
  try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();if(Arrays.asList(args).contains("--profile"))profile();else{danceBoundary();starfallEntry();unsettledEntry();domeInterception();domeFastNeighbor();helixRotation();}check(GL11.glGetError()==0,"Motion GL state");System.out.println("Storm motion GPU: "+checks+" assertions passed");}finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
 }
}
