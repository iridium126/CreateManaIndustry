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
/** Fixed-input GPU workload. Includes grid + update, not a game frame or world capture. */
public class StormV10Benchmark extends HelixGpuValidation {
 static boolean domeMode;
 static double percentile(double[] values,double q){var copy=values.clone();Arrays.sort(copy);return copy[(int)((copy.length-1)*q)];}
 static void workload()throws Exception{
  int n=131072;long coldBegin=System.nanoTime();int update=compile("update.comp"),gridProgram=compile("gridbuild.comp");
  long templateBegin=System.nanoTime();check(HelixTemplate.available(),"template");double templateMs=(System.nanoTime()-templateBegin)/1e6;
  try(var q=new Fixture(n,n,n);var gpu=new StarfallGpuBuffers()){
   var packets=new ClientboundStormWavePacket[68];var data=bytes(n*64);
   for(int i=0;i<n;i++){particle(data,i,2,1500+(i%128-64),-2300+((i/128)%128-64),20);data.putFloat(i*64+4,128+(i/16384-4)*2.5f).putFloat(i*64+12,i+1).putFloat(i*64+56,AllayStormWaves.memberSeed(1,i));}
   for(int slot=0;slot<4;slot++){
    int[] members=java.util.stream.IntStream.range(slot*4096,(slot+1)*4096).toArray();var center=new Vec3(1500+(slot%2==0?-120:120),128,-2300+(slot<2?-120:120));var axis=new Vec3(1500,64,-2300).subtract(center).normalize();float launch=slot<2?20:30;
    var h=AllayHelix.linearFlight(new AllayHelix.Selection(0,3,members,new int[]{2048,4096}),center,axis,80,12,launch);
    packets[slot]=new ClientboundStormWavePacket(1+slot,37,slot<2?2:0,1,1,5,2,2048,0,launch,launch,h.finish()+.25f,1500,128,-2300,32,members,new float[]{1500,128,-2300,(float)center.x,128,(float)center.z},new float[8],2,slot<2?4:1,new int[]{2048,4096},(float)center.x,128,(float)center.z,0,1,20,0,h);
    var side=new Vec3(0,1,0).cross(axis).normalize();var up=axis.cross(side);double travel=slot<2?12*(5-.075):0;
    for(int rank=0;rank<4096;rank++){int i=members[rank],o=i*64;var pos=center.add(axis.scale(HelixTemplate.coordinate(rank,0)+travel)).add(side.scale(HelixTemplate.coordinate(rank,1))).add(up.scale(HelixTemplate.coordinate(rank,2)));data.putFloat(o,(float)pos.x).putFloat(o+4,(float)pos.y).putFloat(o+8,(float)pos.z);}
   }
   int columnBase=domeMode?32768:16384;
   if(domeMode){check(DomeTemplate.available(),"Dome template");for(int slot=0;slot<4;slot++){int[] members=java.util.stream.IntStream.range(slot*8192,(slot+1)*8192).toArray();float cx=1500+(slot%2==0?-32:32),cy=64,cz=-2300+(slot<2?-32:32),launch=slot<2?20:30;
     packets[slot]=new ClientboundStormWavePacket(1+slot,37,slot<2?2:0,1,1,5,1,8192,0,launch,launch,launch+60.25f,1500,128,-2300,32,members,new float[]{1500,128,-2300,cx,cy,cz},new float[4],1,slot<2?4:1,new int[]{8192},cx,cy,cz,0,1,20,0);
     for(int rank=0;rank<8192;rank++){var pos=DomeTemplate.point(rank,slot<2?30:0);int o=members[rank]*64;data.putFloat(o,(float)pos.x+cx).putFloat(o+4,(float)pos.y+cy-.3f).putFloat(o+8,(float)pos.z+cz);}
   }}
   var state=bytes(StarfallGpuBuffers.STATE_WORDS*4);
   for(int c=0;c<64;c++){var old=StarfallColumnGpuValidation.column(c);int[] members=java.util.stream.IntStream.range(columnBase+c*64,columnBase+(c+1)*64).toArray();var col=new ClientboundStormColumnPacket(old.dimension(),37,100+c,false,123,0,4,24,31.28f,32.03f,old.x(),old.y(),old.z(),120,1500,-2300,64,members);packets[c+4]=col.snapshot();
    for(int rank=0;rank<64;rank++){int member=members[rank],o=member*64;var pos=StarfallColumnRules.bloom(new Vec3(col.x(),col.y(),col.z()),rank,123,0);data.putFloat(o,(float)pos.x).putFloat(o+4,(float)pos.y).putFloat(o+8,(float)pos.z);
     int s=(StarfallGpuBuffers.COLUMN_STATE+(c*64+rank)*16)*4,a=(StarfallGpuBuffers.COLUMN_ARC+(c*64+rank)*96)*4;state.putInt(s,col.id()).putFloat(s+4,25).putFloat(s+12,-1);state.putInt(a+43*4,4).putFloat(a+45*4,(float)StarfallColumnRules.root(rank)).putFloat(a+46*4,(float)(((rank*27)&63)*Math.PI/32+123*.0001f));}
   }
   q.build(data,n);var headers=headers();int hb=2*320;headers.putFloat(hb+18*16+4,64).putFloat(hb+18*16+12,6).putFloat(hb+19*16,1500).putFloat(hb+19*16+4,128).putFloat(hb+19*16+8,-2300);put(q.emit,headers);put(q.previous,bytes(16).putInt(0,n));
   int output=buffer(bytes(n*64)),grid=buffer(bytes((ParticleBuffers.GRID_TABLE+n)*4)),damage=buffer(bytes(16)),corrections=buffer(bytes(n*32)),map=buffer(bytes(n*4));
   float[] xyz=new float[300];for(int i=0;i<100;i++){xyz[i*3]=1440+i%10*12;xyz[i*3+1]=64;xyz[i*3+2]=-2360+i/10*12;}var playerGrid=new StormPlayerGrid();playerGrid.build(xyz,100);var packed=bytes(playerGrid.words()*4);for(int i=0;i<playerGrid.words();i++)packed.putFloat(i*4,playerGrid.data()[i]);int players=buffer(packed);
   long publishBegin=System.nanoTime();gpu.rebuild(packets);gpu.prepare(1);gpu.commit();gpu.prepare(2);gpu.bind(update,true);int readState=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,27);put(readState,state);GL11.glFinish();double publishMs=(System.nanoTime()-publishBegin)/1e6,setupMs=(System.nanoTime()-coldBegin)/1e6;
   ui(update,"uCapacity",n);ui(update,"uKillEmit",-1);GL41.glProgramUniform1i(update,GL20.glGetUniformLocation(update,"uPlayerCount"),100);f(update,"uDt",1/60f);f(update,"uTimeSec",25);ui(gridProgram,"uCapacity",n);
   double[] times=new double[120],cpu=new double[120],playerCpu=new double[120];var rows=new ArrayList<String>();rows.add("sample,grid_update_gpu_ms,cpu_submit_ms,player_grid_cpu_ms");int query=GL15.glGenQueries();var zero=bytes(16);long bytesBefore=gpu.uploadedBytes();double coldGpu=0,coldCpu=0;
   for(int sample=-41;sample<120;sample++){
    long begin=System.nanoTime();playerGrid.build(xyz,100);double playerMs=(System.nanoTime()-begin)/1e6;GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,query);gpu.prepare(100+sample+41);gpu.bind(update,true);
    bind(0,q.pool);bind(1,output);bind(3,q.counter);bind(5,q.emit);bind(13,grid);bind(14,q.previous);bind(16,damage);bind(18,players);bind(19,corrections);bind(21,map);put(q.counter,zero);
    GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,grid);GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,0,ParticleBuffers.GRID_TABLE*4L,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(IntBuffer)null);
    dispatch(gridProgram,(n+63)/64);dispatch(update,(n+63)/64);GL15.glEndQuery(GL33.GL_TIME_ELAPSED);double submit=(System.nanoTime()-begin)/1e6,gpuMs=GL33.glGetQueryObjectui64(query,GL15.GL_QUERY_RESULT)/1e6;
    if(sample==-41){coldGpu=gpuMs;coldCpu=submit;}if(sample>=0){times[sample]=gpuMs;cpu[sample]=submit;playerCpu[sample]=playerMs;rows.add(sample+","+gpuMs+","+submit+","+playerMs);}
   }
   check(read(q.counter,16).getInt(0)==n,"combined workload lost members");check(GL11.glGetError()==0,"combined GL error");
   var summary=List.of("members,"+(domeMode?"dome_slots":"helix_slots")+",column_slots,players,helix_template_load_cpu_ms,setup_cpu_ms,initial_publish_cpu_ms,cold_grid_update_gpu_ms,cold_submit_ms,steady_grid_update_gpu_p50_ms,steady_grid_update_gpu_p95_ms,steady_submit_p95_ms,player_grid_cpu_p95_ms,storm_domain_bytes,total_uploaded_bytes,steady_storm_upload_bytes",n+",4,64,100,"+templateMs+","+setupMs+","+publishMs+","+coldGpu+","+coldCpu+","+percentile(times,.5)+","+percentile(times,.95)+","+percentile(cpu,.95)+","+percentile(playerCpu,.95)+","+gpu.bytes()+","+gpu.uploadedBytes()+","+(gpu.uploadedBytes()-bytesBefore));
   Path directory=Path.of(System.getProperty("cmi.benchmark.output","build/benchmarks/current"));Files.createDirectories(directory);Files.write(directory.resolve(domeMode?"storm-v11-dome-combined.csv":"storm-v11-helix-combined.csv"),summary);Files.write(directory.resolve(domeMode?"storm-v11-dome-combined-samples.csv":"storm-v11-helix-combined-samples.csv"),rows);summary.forEach(System.out::println);
   GL15.glDeleteQueries(query);for(int b:new int[]{output,grid,damage,corrections,map,players})GL15.glDeleteBuffers(b);
  }finally{GL20.glDeleteProgram(update);GL20.glDeleteProgram(gridProgram);}
 }
 public static void main(String[] args)throws Exception{domeMode=Arrays.asList(args).contains("--dome");GLFWErrorCallback.createPrint(System.err).set();check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(128,128,"Storm fixed workload",0,0);try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();System.out.println(GL11.glGetString(GL11.GL_RENDERER));workload();}finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}}
}
