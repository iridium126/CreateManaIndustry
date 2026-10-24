import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.allaystorm.*;
import com.iridium126.createmanaindustry.client.particles.engine.*;
import static com.iridium126.createmanaindustry.client.allaystorm.StormCollisionPages.*;

/** Production page uploader / hash lookup and growth; no game client. */
public class StormCollisionGpuValidation extends StarfallGpuValidation {
 static void atlas()throws Exception{
  int p=compileText("storm bit atlas",source("chunks/storm_collision.glsl")+"""
   layout(local_size_x=64) in;layout(std430,binding=10) buffer Output{int values[];} result;
   layout(std430,binding=11) readonly buffer Points{vec4 values[];} points;
   uniform uint uCount;void main(){uint i=gl_GlobalInvocationID.x;if(i>=uCount)return;
    vec3 point=points.values[i].xyz;bool known=cmiStormKnown(point),solid=cmiStormSolid(point);
    result.values[i]=known?(solid?1:0):-1;}
   """);
  int n=20000+4096+1,output=buffer(bytes(n*4)),input=buffer(bytes(n*16));
  try(var gpu=new StormCollisionGpu()){
   Map<Section,Page> pages=new LinkedHashMap<>();List<Section> keys=new ArrayList<>();int[] bits=new int[128];
   for(int cell=0;cell<4096;cell++)if(cell%7==0)bits[cell>>>5]|=1<<(cell&31);
   for(int i=0;i<20000;i++){
    var key=new Section(i%37-18,i/1369+5000,i/37%37-18);keys.add(key);
    pages.put(key,i<9000?page(1,bits):new Page(1,i%2==0?AIR:SOLID,null));
   }
   gpu.offer(pages);int passes=0;long begin=System.nanoTime();
   for(;passes<1000;passes++){long before=gpu.uploaded();gpu.pump(131072,50_000_000);check(gpu.uploaded()-before<=131072,"atlas exceeded byte budget");gpu.bind(p);gpu.submitted();GL11.glFlush();if(gpu.ready()==20000&&gpu.settled())break;}
   check(gpu.ready()==20000,"active coverage was evicted");
   // Flush the final table snapshot after the last page upload, without waiting on a fence.
   for(int i=0;i<1000&&!gpu.settled();i++){gpu.pump(131072,50_000_000);GL11.glFlush();}
   double coldWallMs=(System.nanoTime()-begin)/1e6;
   gpu.bind(p);var points=bytes(n*16);int[] expected=new int[n];
   for(int i=0;i<20000;i++){var k=keys.get(i);points.putFloat(i*16,k.x()*16+.01f).putFloat(i*16+4,k.y()*16+.01f).putFloat(i*16+8,k.z()*16+.01f);expected[i]=i<9000||i%2!=0?1:0;}
   var k=keys.getFirst();for(int cell=0;cell<4096;cell++){int i=20000+cell;points.putFloat(i*16,k.x()*16+(cell&15)+.01f).putFloat(i*16+4,k.y()*16+(cell>>>8)+.01f).putFloat(i*16+8,k.z()*16+((cell>>>4)&15)+.01f);expected[i]=cell%7==0?1:0;}
   points.putFloat((n-1)*16,1_000_000).putFloat((n-1)*16+4,-1_000_000).putFloat((n-1)*16+8,1_000_000);expected[n-1]=-1;
   put(input,points);bind(10,output);bind(11,input);ui(p,"uCount",n);dispatch(p,(n+63)/64);var result=read(output,n*4);
   for(int i=0;i<n;i++)check(result.getInt(i*4)==expected[i],"page lookup/bit mismatch at "+i);
   gpu.submitted();
   double[] lookupTimes=new double[120],pumpTimes=new double[120];int timer=GL15.glGenQueries();long steadyBytes=gpu.uploaded();
   try{for(int i=-40;i<120;i++){
    long cpu=System.nanoTime();gpu.pump(131072,500_000);double wall=(System.nanoTime()-cpu)/1e6;
    GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,timer);dispatch(p,(n+63)/64);GL15.glEndQuery(GL33.GL_TIME_ELAPSED);double elapsed=GL33.glGetQueryObjectui64(timer,GL15.GL_QUERY_RESULT)/1e6;
    if(i>=0){pumpTimes[i]=wall;lookupTimes[i]=elapsed;}
   }}finally{GL15.glDeleteQueries(timer);}
   check(gpu.uploaded()==steadyBytes,"stable collision reuploaded data");
   List<String> steady=List.of("queries,lookup_gpu_p95_ms,steady_pump_cpu_p95_ms,steady_upload_bytes",n+","+p(lookupTimes,.95)+","+p(pumpTimes,.95)+",0");
   Files.write(Path.of(System.getProperty("cmi.benchmark.output","build/benchmarks/current"),"storm-collision-steady.csv"),steady);steady.forEach(System.out::println);
   // Replacement and removal publish atomically; an unknown section never borrows the old occupant.
   pages.put(keys.getFirst(),new Page(2,AIR,null));pages.remove(keys.get(1));gpu.offer(pages);for(int i=0;i<1000&&!gpu.settled();i++){gpu.pump(131072,50_000_000);GL11.glFlush();}
   gpu.bind(p);dispatch(p,(n+63)/64);result=read(output,n*4);check(result.getInt(0)==0,"replacement did not publish");check(result.getInt(4)==-1,"unloaded section kept stale data");gpu.submitted();
   List<String> rows=List.of("sections,mixed_pages,atlas_and_tables_bytes,host_upload_bytes,pump_passes,cold_wall_ms", "20000,9000,"+gpu.bytes()+","+gpu.uploaded()+","+passes+","+coldWallMs);
   Files.write(Path.of(System.getProperty("cmi.benchmark.output","build/benchmarks/current"),"storm-collision-atlas.csv"),rows);rows.forEach(System.out::println);
  }finally{GL15.glDeleteBuffers(output);GL15.glDeleteBuffers(input);deleteProgram(p);}
 }

 static void production()throws Exception{
  int update=compile("update.comp"),n=65;
  try(var q=new Fixture(n,n,n);var gpu=new StormCollisionGpu()){
   Map<Section,Page> pages=new LinkedHashMap<>();
   for(int y=3;y<=9;y++)for(int z=-146;z<=-143;z++)for(int x=92;x<=95;x++){
    int[] bits=new int[128];for(int cell=0;cell<4096;cell++){
     int wx=x*16+(cell&15),wy=y*16+(cell>>>8);
     if(wy<=63||(wx==1502&&wy<76))bits[cell>>>5]|=1<<(cell&31);
    }pages.put(new Section(x,y,z),page(1,bits));
   }
   gpu.offer(pages);for(int i=0;i<1000&&!gpu.settled();i++){gpu.pump(131072,50_000_000);GL11.glFlush();}
   int output=buffer(bytes(n*64)),grid=buffer(bytes((ParticleBuffers.GRID_TABLE+n)*4)),damage=buffer(bytes(16)),players=buffer(bytes(16)),correction=buffer(bytes(n*32)),map=buffer(bytes(n*4));
   try{
    var data=bytes(n*64);for(int i=0;i<n;i++){particle(data,i,2,i==64?5000:1501.6f,-2300, i==0?-.1f:20);data.putFloat(i*64+4,64.02f).putFloat(i*64+12,i+1).putFloat(i*64+16,6);}
    q.build(data,n);var h=headers();int hb=2*ParticleBuffers.VEC4_PER_EMITTER*16;
    h.putFloat(hb+18*16+4,2).putFloat(hb+18*16+12,6).putFloat(hb+19*16,1504).putFloat(hb+19*16+4,65).putFloat(hb+19*16+8,-2300).putFloat(hb+7*16+4,1);put(q.emit,h);put(q.previous,bytes(16).putInt(0,n));
    ui(update,"uCapacity",n);ui(update,"uKillEmit",-1);f(update,"uDt",.05f);bool(update,"uCollisionOn",false);bool(update,"uStormCollisionEnabled",true);
    boolean unknownMoved=false;
    for(int frame=0;frame<12;frame++){
     put(q.pool,data);put(q.counter,bytes(16));bind(0,q.pool);bind(1,output);bind(3,q.counter);bind(5,q.emit);bind(13,grid);bind(14,q.previous);bind(16,damage);bind(18,players);bind(19,correction);bind(21,map);
     gpu.bind(update);f(update,"uTimeSec",frame*.05f);dispatch(update,2);data=read(output,n*64);check(read(q.counter,16).getInt(0)==n,"dedicated collision lost member/corpse");
     for(int row=0;row<n;row++){int o=row*64,id=Math.round(data.getFloat(o+12))-1;float x=data.getFloat(o),y=data.getFloat(o+4);
      check(Float.isFinite(x)&&Float.isFinite(y),"dedicated collision nonfinite");
      if(id==64){unknownMoved|=Math.abs(x-5000)>.1;continue;}
      check(y>=63.99,"storm/corpse fell through dedicated floor");check(x<1502||x>=1503||y>=76,"storm member entered dedicated wall");
     }gpu.submitted();
    }
    check(unknownMoved,"unknown coverage froze visual motion");
   }finally{for(int b:new int[]{output,grid,damage,players,correction,map})GL15.glDeleteBuffers(b);}
  }finally{GL20.glUseProgram(0);deleteProgram(update);}
 }
 public static void main(String[] args)throws Exception{
  GLFWErrorCallback.createPrint(System.err).set();check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(128,128,"Storm collision validation",0,0);
  try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();System.out.println(GL11.glGetString(GL11.GL_RENDERER));atlas();production();check(GL11.glGetError()==0,"storm collision GL state");System.out.println("Storm collision GPU: "+checks+" assertions passed");}
  finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
 }
}
