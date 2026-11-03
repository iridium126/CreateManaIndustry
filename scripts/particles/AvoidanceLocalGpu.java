import java.nio.*;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.particles.engine.*;
import com.iridium126.createmanaindustry.client.particles.engine.hardware.*;
import com.iridium126.createmanaindustry.client.allaystorm.*;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler;

/** Exact current-state and bounded-work checks; these do not certify visual quality. */
public final class AvoidanceLocalGpu extends ModelPopulationGpuValidation {
 static void mode(int p,int phase,int n){ui(p,"phase",phase);dispatch(p,Math.max(1,(n+255)/256));}
 static void run(int n,String scene,List<Object> cases)throws Exception{
  int p=GpuShaderCompiler.compute("local avoidance fixture","#version 450 core\n"+ParticlePrograms.commonPrelude()+AvoidanceSource.resource("diagnostics/avoidance.comp"));
  int snap=buffer(bytes(128+n*64)),alt=buffer(bytes(128+n*64)),meta=buffer(bytes(n*16)),grid=buffer(bytes((262144+n)*4)),state=buffer(bytes(n*8));
  int total=n==65536?146000:n;var input=bytes(total*64);for(int i=0;i<total;i++){
   boolean model=i<n;int at=i*64;float x=scene.equals("dense")?1:(i/2%256)*1.1f+((i&1)==0?0:.323f),y=scene.equals("dense")?1:1+(i/512)*1.1f,z=1;
   particle(input,i,model?2:0,x,z,20);input.putFloat(at+4,y).putFloat(at+12,i+1).putFloat(at+16,1).putFloat(at+56,Float.intBitsToFloat(i+100));
  }
  var capsData=bytes(n*32);boolean capped=scene.equals("capped");for(int i=0;i<n;i++){int at=i*32;capsData.putFloat(at,input.getFloat(i*64)).putFloat(at+4,input.getFloat(i*64+4)).putFloat(at+8,input.getFloat(i*64+8)).putFloat(at+12,capped?.02f:128).putInt(at+16,input.getInt(i*64+56)).putInt(at+28,1);}
  int caps=buffer(capsData),capsTexture=GL45.glCreateTextures(GL31.GL_TEXTURE_BUFFER);GL45.glTextureBuffer(capsTexture,GL30.GL_R32UI,caps);GL42.glBindImageTexture(4,capsTexture,0,false,0,GL15.GL_READ_ONLY,GL30.GL_R32UI);
  int pool=buffer(input),counter=buffer(bytes(16).putInt(0,total)),emit=buffer(headers());
  var resources=List.of(pool,counter,emit,snap,alt,meta,grid,state,caps);var before=read(pool,total*64);
  try(var terrain=new StormCollisionGpu()){
   var pages=new HashMap<StormCollisionPages.Section,StormCollisionPages.Page>();for(int x=-1;x<20;x++)for(int y=-1;y<12;y++)pages.put(new StormCollisionPages.Section(x,y,0),new StormCollisionPages.Page(1,StormCollisionPages.AIR,null));
   terrain.offer(pages);for(int i=0;i<64&&!terrain.settled();i++)terrain.pump(1<<24,1_000_000_000);check(terrain.settled(),"terrain publication");terrain.bind(p);bool(p,"uStormCollisionEnabled",!scene.equals("unknown"));bool(p,"motionCapsEnabled",true);
   bind(0,pool);bind(3,counter);bind(5,emit);bind(6,snap);bind(7,alt);bind(8,meta);bind(9,grid);bind(10,snap);bind(28,state);
   ui(p,"capacity",n);ui(p,"poolCapacity",total);gen(p,"generation",9);gen(p,"epoch",1);gen(p,"nonce",1);f(p,"timeSec",1);f(p,"dt",1f/60);bool(p,"stateEnabled",false);
   int timer=GL15.glGenQueries();GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,timer);
   mode(p,0,n);mode(p,1,total);mode(p,2,1);
   for(int step=0;step<2;step++){bind(6,snap);bind(7,alt);mode(p,3,262144);mode(p,4,n);mode(p,5,n);int old=snap;snap=alt;alt=old;}
   bind(6,snap);mode(p,3,262144);mode(p,4,n);mode(p,7,n);mode(p,8,n);mode(p,6,n);GL15.glEndQuery(GL33.GL_TIME_ELAPSED);double ms=GL33.glGetQueryObjectui64(timer,GL15.GL_QUERY_RESULT)/1e6;GL15.glDeleteQueries(timer);
   var after=read(pool,total*64);var status=read(meta,n*16);int overflow=0,unresolved=0;double worst=0;
   for(int i=0;i<total;i++){int at=i*64;double d2=0;for(int k=0;k<3;k++){double d=after.getFloat(at+k*4)-before.getFloat(at+k*4);check(Double.isFinite(d),"finite motion");d2+=d*d;}worst=Math.max(worst,Math.sqrt(d2));check(d2<=.0301*.0301,"two-step displacement bound");for(int k=12;k<64;k+=4)check(after.getInt(at+k)==before.getInt(at+k),"exact identity HP lifecycle velocity");
    if(i<n){if((status.getInt(i*16+12)&2)!=0)overflow++;if((status.getInt(i*16+12)&4)!=0)unresolved++;}else for(int k=0;k<64;k++)check(before.get(at+k)==after.get(at+k),"non-model untouched");}
   if(scene.equals("dense"))check(overflow>0,"dense overflow explicit");else if(capped){check(worst<=.02/60+.00002,"total frame speed cap");check(unresolved>0,"capped unresolved explicit");}else if(scene.equals("unknown")){check(worst==0,"unknown terrain does not move members");check(unresolved>0,"unknown terrain unresolved explicit");}else for(int i=0;i<n;i+=2){int a=i*64,b=(i+1)*64;double d=after.getFloat(b)-after.getFloat(a);check(d>=.34-.0001,"mild pair resolved within two steps");}
   cases.add(Map.of("members",n,"total",total,"scene",scene,"gpu_ms",ms,"overflow",overflow,"unresolved_after_final_audit",unresolved,"max_displacement",worst));
  }finally{GL11.glDeleteTextures(capsTexture);for(int r:resources)GL15.glDeleteBuffers(r);deleteProgram(p);}
 }
 public static void main(String[] args)throws Exception{
  Path out=Path.of(System.getProperty("cmi.benchmark.output"));Files.createDirectories(out);check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,6);long w=GLFW.glfwCreateWindow(32,32,"bounded local avoidance",0,0);GLFW.glfwMakeContextCurrent(w);GL.createCapabilities();var report=new LinkedHashMap<String,Object>();var cases=new ArrayList<Object>();report.put("passed",false);
  try{String production=source("update.comp");int updated=GpuShaderCompiler.compute("hint consumer","#version 450 core\n"+ParticlePrograms.commonPrelude()+AvoidanceSource.update(production));deleteProgram(updated);for(boolean fused:new boolean[]{false,true}){int p=GpuShaderCompiler.compute("production fallback "+fused,"#version 450 core\n"+ParticlePrograms.commonPrelude()+HardwareUpdateSource.build(production,fused).source());deleteProgram(p);}int audit=GpuShaderCompiler.compute("coverage Dome audit","#version 450 core\n"+ParticlePrograms.commonPrelude()+AvoidanceSource.domeAudit(source("dome_contacts.comp")));deleteProgram(audit);for(int n:new int[]{128,65536})for(String scene:List.of("mild","dense","capped","unknown"))run(n,scene,cases);report.put("passed",true);}finally{report.put("checks",checks);report.put("cases",cases);Files.writeString(out.resolve("local.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));GLFW.glfwDestroyWindow(w);GLFW.glfwTerminate();}
 }
}
