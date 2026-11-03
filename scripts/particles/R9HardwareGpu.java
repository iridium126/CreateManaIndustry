import java.nio.*;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.particles.engine.hardware.*;

/** ABI 9 force/envelope/retirement regressions, using real OptiX and GL ownership. */
public final class R9HardwareGpu extends ModelPopulationGpuValidation {
 static Path out,source;static JsonObject deps;static final List<Object> cases=new ArrayList<>();
 static String dep(String key){return deps.getAsJsonObject(key).get("root").getAsString();}
 static void force(ByteBuffer result,float[] points,int q){
  check(result.getInt(48)==0x46395231&&result.getInt(52)==4,"force result format");int at=result.getInt(24)*4,n=points.length/4;
  for(int i=0;i<q;i++){
   double[] sum=new double[3],abs=new double[3];int terms=0;
   for(int j=0;j<n;j++){if(i==j)continue;float x=points[i*4]-points[j*4],y=points[i*4+1]-points[j*4+1],z=points[i*4+2]-points[j*4+2],d2=(x*x+y*y)+z*z;if(d2>=1)continue;
    if(d2<=1e-8f){int mine=Float.floatToRawIntBits(points[i*4+3]),other=Float.floatToRawIntBits(points[j*4+3]),m=Math.min(mine,other),sign=mine<other?1:-1;x=R8ForceOracle.deltas.getFloat(m*16)*sign;y=R8ForceOracle.deltas.getFloat(m*16+4)*sign;z=R8ForceOracle.deltas.getFloat(m*16+8)*sign;d2=(x*x+y*y)+z*z;}
    if(d2<1&&d2>1e-8f){float len=(float)Math.sqrt(d2),w=(1-len)/len;float[] values={x*w,y*w,z*w};for(int k=0;k<3;k++){sum[k]+=values[k];abs[k]+=Math.abs(values[k]);}terms++;}}
   double u=Math.scalb(1.,-24),gamma=Math.max(0,terms-1)*u/(1-Math.max(0,terms-1)*u);
   for(int k=0;k<3;k++){float value=result.getFloat(at+i*16+k*4);check(Float.isFinite(value)&&Math.abs(value-sum[k])<=(gamma+32*u)*abs[k]+2e-5,"independent FP64 fused force i="+i+" k="+k+" actual="+value+" expected="+sum[k]);}
  }
 }
 static void run(int policy)throws Exception{
  long bridge=HardwareNative.createCopy(2<<20,3),worker=0;try{
   worker=HardwareNative.createAsyncQueryForBackend(bridge,257,257,400000,91,1,dep("cuda_cudart"),dep("optix"),source.toAbsolutePath().toString(),dep("cuda_nvcc"));HardwareNative.configureAsyncQuery(worker,policy,64<<20);
   float[] base=NeighborQueryGpuValidation.positions(257,"crowded");for(int i=0;i<257;i++)base[i*4+3]=Float.intBitsToFloat(130000+i);
   for(int frame=0;frame<18;frame++){
    int slot=frame%3,n=frame==12?65:257,q=frame==13?0:Math.min(n,128);float[] p=Arrays.copyOf(base,n*4);
    if(frame==2||frame==3){p[4]=p[0];p[5]=p[1];p[6]=p[2];}
    if(frame>=5)for(int i=0;i<n;i++)p[i*4]+=(frame==8?20:.05f);
    if(frame==9)for(int i=0;i<n;i++)p[i*4+3]=Float.intBitsToFloat(i);
    if(frame==10){p[0]=0;p[1]=0;p[2]=0;p[4]=1;p[5]=0;p[6]=0;}
    long nonce=100+frame;int[] buffers=HardwareNative.copyBuffers(bridge,slot);var basic=R7AsyncGpu.packet(n,q,nonce,91,p);int deltaOffset=R7AsyncGpu.align(basic.getInt(44)+257*4)+131072*4;var packet=bytes(deltaOffset+n*16);packet.put(basic).putInt(24,4);for(int i=0;i<n;i++){int member=Float.floatToRawIntBits(p[i*4+3]);for(int k=0;k<3;k++)packet.putFloat(deltaOffset+i*16+k*4,R8ForceOracle.deltas.getFloat(member*16+k*4));}packet.clear();put(buffers[0],packet);
    check(HardwareNative.submitAsyncQuery(worker,slot,nonce,1,false),"force submit");check(R7AsyncGpu.waitResult(worker,slot,nonce)==2,"force complete: "+HardwareNative.asyncQueryDiagnostics(worker));HardwareNative.consumeAsyncQuery(worker,slot,nonce);
    var result=R7HardwareGpu.readShared(buffers[1],2<<20);check(result.getInt(32)==0&&result.getInt(36)==1,"complete envelope");
    // No manual glFlush: recycle must flush the consumer fence itself.
    double[] timing=HardwareNative.asyncQueryTiming(worker,slot,nonce);
    long fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0),end=System.nanoTime()+5_000_000_000L;
    while(!HardwareNative.recycleAsyncQuery(worker,slot,nonce,fence)){check(System.nanoTime()<end,"no-flush consumer retirement");Thread.onSpinWait();}GL32.glDeleteSync(fence);
    force(result,p,q);
    cases.add(Map.of("policy",policy,"frame",frame,"queries",q,"native_ms",timing,"lifetime_trace",HardwareNative.asyncQueryTrace(worker,slot,nonce)));
   }
  }finally{if(worker!=0){long end=System.nanoTime()+5_000_000_000L;while(!HardwareNative.closeAsyncQuery(worker)){check(System.nanoTime()<end,"force close");Thread.sleep(1);}}HardwareNative.closeCopy(bridge);}
 }
 public static void main(String[] args)throws Exception{
  out=Path.of(System.getProperty("cmi.benchmark.output"));Files.createDirectories(out);source=out.resolve("native");Files.createDirectory(source);for(String name:List.of("Query.h","grid.cu","rt.cu","rt-triangle.cu","tensor.cu"))Files.copy(Path.of("native/offload",name),source.resolve(name));deps=JsonParser.parseString(Files.readString(Path.of(".gradle/neighbor-rt/dependencies.json"))).getAsJsonObject();
  check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,6);long window=GLFW.glfwCreateWindow(32,32,"R9 force regression",0,0);GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();HardwareNative.load(Path.of(".gradle/hardware-offload/build"));check(HardwareNative.nativeAbiVersion()==10,"ABI 10");
  var metadata=new LinkedHashMap<String,Object>();metadata.put("passed",false);try{R8ForceOracle.ensure();int policy=Integer.getInteger("cmi.r8.policy",0);if(policy==0){run(83);run(211);run(339);}else run(policy);check(GL11.glGetError()==0,"GL state");metadata.put("passed",true);}finally{R8ForceOracle.close();metadata.put("checks",checks);metadata.put("cases",cases);Files.writeString(out.resolve("hardware.json"),new GsonBuilder().setPrettyPrinting().create().toJson(metadata));GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
 }
}
