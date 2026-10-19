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
/** Fixed initial GPU storm homes, static aim, no world obstacles. Measures readiness, not FPS. */
public class StormV11GatherValidation extends HelixGpuValidation {
 static double measure(boolean old)throws Exception{
  int n=4096,update=compile("update.comp"),initialize=compileText("gather homes",source("chunks/allay_storm.glsl")+"layout(local_size_x=64)in;layout(std430,binding=10)buffer P{vec4 rows[];}p;void main(){uint i=gl_GlobalInvocationID.x;if(i>=4096u)return;vec3 position,velocity;cmiTyphoonHome(p.rows[i*4u+3u].z,16.,.2,.7,.3,.04,6.,vec3(0,128,0),position,velocity);p.rows[i*4u].xyz=position;p.rows[i*4u+1u].xyz=velocity;}");
  double result=-1;try(var q=new Fixture(n,n,n);var gpu=new StarfallGpuBuffers()){
   var selection=AllayHelix.select(16384,new BitSet(),new BitSet(),4096,123,0);var data=bytes(n*64);for(int i=0;i<n;i++){int member=selection.members()[i];particle(data,i,2,0,0,20);data.putFloat(i*64+12,member+1).putFloat(i*64+56,AllayStormWaves.memberSeed(123,member));}q.build(data,n);bind(10,q.pool);dispatch(initialize,64);
   var center=new Vec3(old?66:16,old?128:120,0);var axis=new Vec3(0,64.9,0).subtract(center).normalize();var flight=AllayHelix.linearFlight(selection,center,axis,100,12,120);var snapshot=new ClientboundStormWavePacket(37,37,0,1,1,5,2,2048,0,120,120,160,0,128,0,32,selection.members(),new float[]{0,128,0,(float)center.x,(float)center.y,0},new float[8],2,1,selection.ends(),(float)center.x,(float)center.y,0,0,1,0,0,flight);gpu.rebuild(new ClientboundStormWavePacket[]{snapshot,null,null,null});
   var headers=headers();headers.putFloat(2*320+18*16+4,16).putFloat(2*320+18*16+12,6).putFloat(2*320+19*16+4,128);put(q.emit,headers);put(q.previous,bytes(16).putInt(0,n));
   int out=buffer(bytes(n*64)),grid=buffer(bytes((ParticleBuffers.GRID_TABLE+n)*4)),damage=buffer(bytes(16)),players=buffer(bytes(16)),correction=buffer(bytes(16384*32)),map=buffer(bytes(16384*4));ui(update,"uCapacity",n);ui(update,"uKillEmit",-1);f(update,"uDt",1/60f);
   for(int frame=0;frame<3600;frame++){
    put(q.counter,bytes(16));bind(0,q.pool);bind(1,out);bind(3,q.counter);bind(5,q.emit);bind(13,grid);bind(14,q.previous);bind(16,damage);bind(18,players);bind(19,correction);bind(21,map);gpu.prepare(frame+1);gpu.bind(update,true);f(update,"uTimeSec",1+frame/60f);dispatch(update,64);
    int state=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28);var ready=bytes(16);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,state);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,StarfallGpuBuffers.GATHER_COUNTS*4L,ready);gpu.commit();if(ready.getInt(0)==n){result=(frame+1)/60.;break;}
    GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,out);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,q.pool);GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,0,0,n*64L);
   }
   for(int id:new int[]{out,grid,damage,players,correction,map})GL15.glDeleteBuffers(id);
  }finally{GL20.glDeleteProgram(update);GL20.glDeleteProgram(initialize);}check(result>0,"static gather never became ready: old="+old);return result;
 }
 public static void main(String[] args)throws Exception{GLFWErrorCallback.createPrint(System.err).set();check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long w=GLFW.glfwCreateWindow(128,128,"Gather readiness",0,0);try{GLFW.glfwMakeContextCurrent(w);GL.createCapabilities();double before=measure(true),after=measure(false);check(after<before,"new anchor did not shorten actual static gather");var rows=List.of("scenario,old_offset_radius_plus_50_s,new_edge_below_8_s","static_storm_homes_4096_no_obstacles,"+before+","+after);Files.write(Path.of(System.getProperty("cmi.benchmark.output","build/benchmarks/current"),"helix-gather-comparison.csv"),rows);rows.forEach(System.out::println);}finally{GLFW.glfwDestroyWindow(w);GLFW.glfwTerminate();}}
}
