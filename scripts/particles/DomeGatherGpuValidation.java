import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.allaystorm.*;
import com.iridium126.createmanaindustry.client.particles.engine.*;
import com.iridium126.createmanaindustry.content.allaystorm.*;
import com.iridium126.createmanaindustry.content.allaystorm.network.*;

public final class DomeGatherGpuValidation extends DomeGpuValidation {
 static void stormEnvelope(){
  int program=compileText("Dome preflight covers the production storm body",source("chunks/allay_storm.glsl")+"uniform float uRadius,uPhase;layout(local_size_x=64)in;layout(std430,binding=10)buffer R{vec4 rows[];}r;void main(){uint member=gl_GlobalInvocationID.x;vec3 point,velocity;cmiTyphoonHome(float(member)/32768.,uRadius,.2,uPhase,uPhase*.731,.04,6.,vec3(0),point,velocity);r.rows[member]=vec4(point,1.);}");int output=buffer(bytes(32768*16));
  for(float radius:new float[]{2,16,32,64})for(float phase:new float[]{0,.7f,3.14f,12.3f}){bind(10,output);f(program,"uRadius",radius);f(program,"uPhase",phase);dispatch(program,512);var result=read(output,32768*16);for(int member=0;member<32768;member++){double horizontal=Math.hypot(result.getFloat(member*16),result.getFloat(member*16+8)),vertical=result.getFloat(member*16+4),band=Math.clamp(.4*radius,3,8);check(horizontal<=1.15*radius+.4001&&vertical>=-1.2-band*.5-.4001&&vertical<=Math.clamp(.2*radius,2,6)+band*.5+.4001,"Production storm home escaped its geometric bounds");check(AllayDome.overlapsStormBody(horizontal,vertical,radius),"Preflight failed to cover a production storm home");}}
  GL15.glDeleteBuffers(output);GL20.glDeleteProgram(program);
 }
 static void thresholdAgreement(){
  var inputs=new ArrayList<float[]>();var expected=new ArrayList<Boolean>();
  for(float start:new float[]{0,.025f,5000,104850,104857.375f})for(int duration:new int[]{160,627,12000})for(int progress:new int[]{-1,0,1,duration/2,duration-1,duration,duration+1})for(int eligible:new int[]{0,1,21,12288}){
   float deadline=start+duration/20f,now=(float)wrapped(start+progress/20.);int required=DomeGatherRules.required(eligible,progress,duration);
   for(int ready:new int[]{Math.max(0,required-1),required,eligible+1}){inputs.add(new float[]{ready,eligible,now,start,deadline});expected.add(DomeGatherRules.withinDeadline(now,start,deadline)&&DomeGatherRules.ready(ready,eligible,DomeGatherRules.elapsedTicks(now,start),DomeGatherRules.durationTicks(start,deadline)));}
  }
  int program=compileText("Dome integer threshold parity",source("chunks/dome_gather_rules.glsl")+"layout(local_size_x=64)in;layout(std430,binding=10)buffer R{vec4 rows[];}r;uniform uint uCount;void main(){uint index=gl_GlobalInvocationID.x;if(index>=uCount)return;vec4 a=r.rows[index*2u],b=r.rows[index*2u+1u];r.rows[index*2u+1u].y=cmiDomeGatherReady(uint(a.x),uint(a.y),a.z,a.w,b.x)?1.:0.;}");
  var data=bytes(inputs.size()*32);for(int index=0;index<inputs.size();index++)for(int word=0;word<5;word++)data.putFloat(index*32+word*4,inputs.get(index)[word]);int output=buffer(data);bind(10,output);ui(program,"uCount",inputs.size());dispatch(program,(inputs.size()+63)/64);var result=read(output,inputs.size()*32);for(int index=0;index<inputs.size();index++)check((result.getFloat(index*32+20)==1)==expected.get(index),"CPU/GPU dynamic threshold mismatch at "+Arrays.toString(inputs.get(index)));GL15.glDeleteBuffers(output);GL20.glDeleteProgram(program);
 }
 static void returningTraffic()throws Exception{
  boolean baseline=Boolean.getBoolean("cmi.dome.gatherBaseline");int gridProgram=compile("gridbuild.comp"),body=compile("dome_contacts.comp");var rows=new ArrayList<String>();rows.add("scenario,frame,streak,ready,positionError,velocityError");
  for(int scenario=0;scenario<(baseline?4:8);scenario++)try(var fixture=new Fixture(1025,1025,1025);var gpu=new StarfallGpuBuffers()){
   gpu.rebuild(new ClientboundStormWavePacket[]{domeAt(71,0,1,40),null,null,null});warmDome(gpu,body);int grid=buffer(bytes((ParticleBuffers.GRID_TABLE+1025)*4)),memberMap=buffer(bytes(131072*4));
   int finalReady=0;DomeGatherState flows=DomeGatherState.EMPTY;
   for(int frame=0;frame<8;frame++){
    float time=2+frame*.05f;int owner=scenario==7&&frame>=3?72:71;if(frame==3&&(scenario==6||scenario==7)){if(scenario==6){float[] times=new float[66];Arrays.fill(times,-1);times[0]=time;flows=new DomeGatherState(times);}gpu.rebuild(new ClientboundStormWavePacket[]{domeAt(owner,0,1,40).withGatherFlows(flows),null,null,null});}var target=DomeTemplate.gatherPoint(0,time,flows).add(0,-.3,0);var motion=DomeTemplate.gatherVelocity(0,time,flows);ByteBuffer before=bytes(1025*64),candidate=bytes(1025*64),map=bytes(131072*4);
    for(int index=0;index<1025;index++){
     int member=index==0?0:12288+index,offset=index*64;particle(candidate,index,2,(float)target.x,(float)target.z,20);candidate.putFloat(offset+4,(float)target.y).putFloat(offset+12,member+1).putFloat(offset+16,(float)motion.x).putFloat(offset+20,(float)motion.y).putFloat(offset+24,(float)motion.z);
     if(index>0){float separation=scenario==1?.45f:scenario==2?.2f:1.2f;candidate.putFloat(offset,(float)target.x+separation);}
     for(int component=0;component<16;component++)before.putInt(offset+component*4,candidate.getInt(offset+component*4));for(int axis=0;axis<3;axis++)before.putFloat(offset+axis*4,candidate.getFloat(offset+axis*4)-candidate.getFloat(offset+16+axis*4)*.05f);map.putInt(member*4,index+1);
    }
    if(scenario==3&&frame>=3&&frame<=4)candidate.putFloat(16,(float)motion.x+1.2f);
    put(fixture.pool,before);put(fixture.counter,bytes(16).putInt(0,1025));put(fixture.previous,bytes(16).putInt(0,1025));put(memberMap,map);gpu.clock(time);gpu.prepare(100+frame);gpu.bind(body,true);int state=gpu.candidateStateBuffer(),old=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,27);int previousStreak=DomePathGpuValidation.range(old,StarfallGpuBuffers.GATHER_STREAK,1).getInt(0);DomePathGpuValidation.upload(state,0,bytes(8).putInt(0,owner).putInt(4,1));if(frame==0)DomePathGpuValidation.upload(old,0,bytes(8).putInt(0,71).putInt(4,1));DomePathGpuValidation.upload(state,StarfallGpuBuffers.DOME_GATHER_WINDOW+7,bytes(4).putInt(0,(scenario==4||scenario==5)&&frame>=3&&frame<=4?(scenario==4?2:4):0));
    int proposed=Math.min(3,previousStreak+1);DomePathGpuValidation.upload(state,StarfallGpuBuffers.GATHER_STREAK,bytes(4).putInt(0,proposed));if(proposed==3)DomePathGpuValidation.upload(state,StarfallGpuBuffers.GATHER_READY,bytes(4).putInt(0,1));
    int output=buffer(candidate);bind(5,fixture.emit);bind(21,memberMap);DomeBodyContacts.run(gridProgram,body,fixture.pool,fixture.previous,output,fixture.counter,grid,time,.05f,0,gpu);var result=read(output,64);var actual=new net.minecraft.world.phys.Vec3(result.getFloat(0),result.getFloat(4),result.getFloat(8));var velocity=new net.minecraft.world.phys.Vec3(result.getFloat(16),result.getFloat(20),result.getFloat(24));int streak=DomePathGpuValidation.range(state,StarfallGpuBuffers.GATHER_STREAK,1).getInt(0);finalReady=DomePathGpuValidation.range(state,StarfallGpuBuffers.GATHER_READY,1).getInt(0)&1;rows.add(scenario+","+frame+","+streak+","+finalReady+","+actual.distanceTo(target)+","+velocity.distanceTo(motion));
    if(!baseline){if(scenario<3&&frame>=2)check(finalReady==1,"Kinematically valid member rejected by returning traffic scenario="+scenario+" streak="+streak);if(scenario==3&&frame>=3&&frame<=4)check(finalReady==0&&streak==0,"Final velocity mismatch did not reset readiness");check(actual.distanceTo(target)<.401,"Physical body correction exceeded the positional gate");if(scenario>=4&&frame==3)check(finalReady==0&&streak<=1,"Unknown, depenetration, freeze, or slot reuse inherited readiness");if(frame==7)check(finalReady==1,"Readiness did not restart after invalid motion or identity");check(DomePathGpuValidation.range(state,StarfallGpuBuffers.GATHER_COUNTS,1).getInt(0)==finalReady,"Final readiness count differs from its bitmap");check(velocity.length()<=8.001,"Body solver exceeded the gather budget");}
    if(!baseline&&scenario==0&&frame==3){gpu.abort();check(DomePathGpuValidation.range(old,StarfallGpuBuffers.GATHER_STREAK,1).getInt(0)==previousStreak,"Discarded candidate changed the committed streak");}else gpu.commit();GL15.glDeleteBuffers(output);
   }
   System.out.println("Returning traffic scenario="+scenario+" ready="+finalReady+" baseline="+baseline);GL15.glDeleteBuffers(grid);GL15.glDeleteBuffers(memberMap);
  }
  Path directory=Path.of(System.getProperty("cmi.benchmark.output","build/benchmarks/current"));Files.createDirectories(directory);Files.write(directory.resolve("dome-gather-returning-"+(baseline?"before":"after")+".csv"),rows);for(int program:new int[]{gridProgram,body}){com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clearUniformProgram(program);GL20.glDeleteProgram(program);}
 }
 public static void main(String[] arguments)throws Exception{
  GLFWErrorCallback.createPrint(System.err).set();check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(64,64,"Dome gather readiness",0,0);try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();if(!Boolean.getBoolean("cmi.dome.gatherBaseline")){thresholdAgreement();stormEnvelope();}returningTraffic();check(GL11.glGetError()==0,"Dome gather GL error");System.out.println("Dome gather GPU: "+checks+" assertions passed");}finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
 }
}
