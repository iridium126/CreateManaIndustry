import java.nio.*;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.particles.engine.hardware.*;

/** Real ABI 10 triangle probes and asynchronous ownership fault tests. */
public final class AvoidanceGpu extends ModelPopulationGpuValidation {
 static ByteBuffer packet(int count,long nonce,long epoch,long generation){
  var b=bytes(128+count*64);b.putInt(0,0x41313049).putInt(4,10).putLong(8,nonce).putLong(16,epoch).putLong(24,generation).putLong(32,generation+1).putInt(40,count).putFloat(48,1).putFloat(52,1f/60).putFloat(56,.1f).putLong(64,epoch).putInt(72,1);
  for(int i=0;i<count;i++){int at=128+i*64;b.putFloat(at,i*.8f).putFloat(at+12,.34f).putFloat(at+16,8).putFloat(at+28,16).putInt(at+32,i).putInt(at+36,1234+i).putInt(at+40,9).putInt(at+44,1).putFloat(at+48,8);}
  return b;
 }
 static int waitReady(long worker,int slot,long nonce)throws Exception{long end=System.nanoTime()+15_000_000_000L;int s;while((s=HardwareNative.pollAvoidance(worker,slot,nonce))==1){check(System.nanoTime()<end,"probe timeout");Thread.sleep(1);}return s;}
 public static void main(String[] args)throws Exception{
  Path out=Path.of(System.getProperty("cmi.benchmark.output"));Files.createDirectories(out);Path src=out.resolve("native");Files.createDirectories(src);for(String n:List.of("Avoidance.h","avoidance-grid.cu","avoidance-rt.cu"))Files.copy(Path.of("native/offload",n),src.resolve(n),StandardCopyOption.REPLACE_EXISTING);
  var deps=JsonParser.parseString(Files.readString(Path.of(".gradle/neighbor-rt/dependencies.json"))).getAsJsonObject();
  check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,6);long window=GLFW.glfwCreateWindow(32,32,"async avoidance",0,0);GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();HardwareNative.load(Path.of(".gradle/hardware-offload/build"));check(HardwareNative.nativeAbiVersion()==10,"ABI");
  var result=new LinkedHashMap<String,Object>();result.put("passed",false);long bridge=0,worker=0;try{
   int count=65536;bridge=HardwareNative.createCopy(128+count*64,3);
   try{HardwareNative.createAvoidance(bridge,count+1,91,deps.getAsJsonObject("cuda_cudart").get("root").getAsString(),deps.getAsJsonObject("optix").get("root").getAsString(),src.toAbsolutePath().toString(),deps.getAsJsonObject("cuda_nvcc").get("root").getAsString());throw new AssertionError("undersized bridge accepted");}catch(IllegalStateException expected){}
   worker=HardwareNative.createAvoidance(bridge,count,91,deps.getAsJsonObject("cuda_cudart").get("root").getAsString(),deps.getAsJsonObject("optix").get("root").getAsString(),src.toAbsolutePath().toString(),deps.getAsJsonObject("cuda_nvcc").get("root").getAsString());
   var samples=new ArrayList<Object>();
   for(int frame=0;frame<16;frame++){
    int slot=frame%3;long nonce=frame+1;var p=packet(count,nonce,91,frame);if(frame==1)p.putLong(24,999);if(frame==2)p.putInt(128+32,999);if(frame==3)p.putFloat(128,Float.NaN);if(frame==4)p.putLong(32,0);if(frame==5)p.putInt(72,0);if(frame==9)p.putLong(64,92);if(frame==10)p.putLong(16,92);if(frame==11)p.putLong(8,999);if(frame==12)for(int i=0;i<count;i++)p.putFloat(128+i*64+48,0);
    int[] buffers=HardwareNative.copyBuffers(bridge,slot);put(buffers[0],p);if(frame>=6&&frame<=8)HardwareNative.delayAvoidance(worker,new int[]{10,50,500}[frame-6]);
    long start=System.nanoTime();check(HardwareNative.submitAvoidance(worker,slot,nonce,frame,91),"submit");double submitMs=(System.nanoTime()-start)/1e6;check(!HardwareNative.submitAvoidance(worker,slot,nonce+1000,frame,91),"live slot protected");check(waitReady(worker,slot,nonce)==2,"completed probe");HardwareNative.consumeAvoidance(worker,slot,nonce);
    var output=R7HardwareGpu.readShared(buffers[1],128+count*64);check(output.getInt(0)==0x4131304f&&output.getInt(72)==1,"complete header");check((output.getInt(44)!=0)==(frame>=1&&frame<=5||frame>=9&&frame<=11),"all frame rejection");
    if(frame==12)check(output.getFloat(128)==0&&output.getFloat(132)==0&&output.getFloat(136)==0,"stationary skips probes");
    if(frame==0){check(output.getFloat(128+12)<1.14f,"nearest external opaque triangle");check(output.getFloat(128)<0,"outward avoidance");check(output.getInt(128+28)==1,"identity");}
    var timing=HardwareNative.avoidanceTiming(worker,slot,nonce);long fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);long end=System.nanoTime()+10_000_000_000L;while(!HardwareNative.recycleAvoidance(worker,slot,nonce,fence)){check(System.nanoTime()<end,"consumer retirement");Thread.sleep(1);}GL32.glDeleteSync(fence);samples.add(Map.of("frame",frame,"submit_cpu_ms",submitMs,"stages_ms",timing));
   }
   HardwareNative.delayAvoidance(worker,500);for(int i=0;i<3;i++){put(HardwareNative.copyBuffers(bridge,i)[0],packet(count,17+i,91,17+i));check(HardwareNative.submitAvoidance(worker,i,17+i,17+i,91),"three slot submit");}check(!HardwareNative.closeAvoidance(worker),"pending close is nonblocking");for(int i=0;i<3;i++){check(waitReady(worker,i,17+i)==2,"pending close completion");check(HardwareNative.recycleAvoidance(worker,i,17+i,0),"unread ready output retired");}
   result.put("diagnostics",HardwareNative.avoidanceDiagnostics(worker));result.put("samples",samples);result.put("capabilities",JsonParser.parseString(HardwareNative.avoidanceCapabilities()));result.put("passed",true);
  }catch(Throwable failure){failure.printStackTrace();throw failure;}finally{if(worker!=0){GL11.glFinish();for(int i=0;i<3;i++)for(int n=i+1;n<=19;n+=3)try{int state=HardwareNative.pollAvoidance(worker,i,n);if(state==5){long f=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);while(!HardwareNative.recycleAvoidance(worker,i,n,f))Thread.sleep(1);GL32.glDeleteSync(f);}else if(state==2)HardwareNative.recycleAvoidance(worker,i,n,0);}catch(IllegalStateException unused){}long end=System.nanoTime()+15_000_000_000L;while(!HardwareNative.closeAvoidance(worker)){check(System.nanoTime()<end,"retire worker");Thread.sleep(1);}}if(bridge!=0)HardwareNative.closeCopy(bridge);result.put("checks",checks);Files.writeString(out.resolve("avoidance.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
 }
}
