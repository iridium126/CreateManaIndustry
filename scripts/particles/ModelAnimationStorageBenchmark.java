import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.opengl.*;
import org.lwjgl.glfw.*;
import com.iridium126.createmanaindustry.client.particles.engine.*;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms;

/** Frozen old sources and current production pipeline; no per-frame readback inside submit timing. */
public class ModelAnimationStorageBenchmark extends ModelAnimationStorageGpuValidation {
 static int oldUpdate,oldReset,oldPrepare,oldKeys,oldGrid;
 static final class OldAnimation implements AutoCloseable{
  final int[] buffers=new int[2],textures=new int[2];final int capacity;
  OldAnimation(int capacity){this.capacity=capacity;if(capacity==0)return;
   for(int i=0;i<2;i++){buffers[i]=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffers[i]);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,capacity*64L,GL15.GL_DYNAMIC_COPY);GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(IntBuffer)null);textures[i]=GL11.glGenTextures();GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,textures[i]);GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,GL30.GL_RGBA32UI,buffers[i]);}
  }
  void bind(int p,int bank){bool(p,"uAllayAnimationWriteEnabled",capacity>0);if(capacity==0)return;
   AllayAnimationGpu.barrier();GL13.glActiveTexture(GL13.GL_TEXTURE3);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,textures[bank]);GL13.glActiveTexture(GL13.GL_TEXTURE0);GL41.glProgramUniform1i(p,GpuUniforms.loc(p,"cmi_AllayAnimationRead"),3);GL42.glBindImageTexture(0,textures[1-bank],0,false,0,GL15.GL_READ_WRITE,GL30.GL_RGBA32UI);
  }
  public void close(){for(int i=0;i<2;i++){if(textures[i]!=0)GL11.glDeleteTextures(textures[i]);if(buffers[i]!=0)GL15.glDeleteBuffers(buffers[i]);}}
 }
 static int frozen(String file)throws Exception{
  Path root=Path.of(System.getProperty("cmi.modelAnimation.baseline","build/model-animation-before/particles"));
  if(!Files.isRegularFile(root.resolve(file)))throw new IllegalStateException("Frozen pre-change shader directory required: -PmodelAnimationBaseline=<particles directory>");
  String text=ParticleShaderSource.loadParticle(file,path->{try{return Files.readString(root.resolve(path.substring("shaders/particles/".length())));}catch(Exception e){throw new IllegalStateException(e);}});
  for(String phase:List.of("spawn","update"))text=text.replace("#pragma cmi_types "+phase,com.iridium126.createmanaindustry.client.particles.emitter.ParticleTypes.shaderHooks(phase));
  return compileText("frozen "+file,text);
 }
 static void prepareScene(Rig r,int models,int ordinary,boolean storm){
  var h=headers();h.putFloat(5*16,1e9f).putFloat(5*16+4,1e9f);
  if(storm){int b=2*320;h.putFloat(b+17*16+4,3).putFloat(b+18*16,2).putFloat(b+18*16+4,16).putFloat(b+18*16+12,6);}
  put(r.emit,h);r.countWrites=false;r.begin(0);if(models>0)r.spawn(models,storm?2:1);if(ordinary>0)r.spawn(ordinary,0);r.finish(true,false);
  check(r.models==models&&r.live==models+ordinary,"Benchmark setup lost particles");r.measure=true;r.stormGrid=storm;
 }
 static void legacyFrame(Rig r,OldAnimation animation,float dt){
  r.mark(0);r.mark(1);r.bindingsFor(oldPrepare,false);put(r.previous,bytes(16).putInt(0,r.live));ui(oldPrepare,"uPhase",0);dispatch(oldPrepare,1);
  r.bindingsFor(oldReset,false);dispatch(oldReset,1);r.mark(2);
  if(r.stormGrid)r.buildGrid(oldGrid);r.mark(3);
  r.bindingsFor(oldUpdate,false);animation.bind(oldUpdate,r.bank);f(oldUpdate,"uDt",dt);r.indirect(oldUpdate,0);r.mark(4);r.mark(5);r.mark(6);
  r.bindingsFor(oldPrepare,true);ui(oldPrepare,"uPhase",1);dispatch(oldPrepare,1);r.mark(7);r.mark(8);r.mark(9);
  r.bindingsFor(oldKeys,true);r.indirect(oldKeys,1);r.mark(10);r.mark(11);
  r.bindingsFor(oldPrepare,true);ui(oldPrepare,"uPhase",2);dispatch(oldPrepare,1);r.mark(12);r.bank=1-r.bank;
 }
 static double percentile(double[] a,double p){double[] b=a.clone();Arrays.sort(b);return b[Math.min(b.length-1,(int)Math.ceil(p*b.length)-1)];}
 static void run()throws Exception{
  Path output=Path.of(System.getProperty("cmi.benchmark.output","build/benchmarks/current"));Files.createDirectories(output);
  Path baseline=Path.of(System.getProperty("cmi.modelAnimation.baseline","build/model-animation-before/particles"));
  var hashes=new ArrayList<String>();try(var sources=Files.walk(baseline)){
   for(Path source:sources.filter(Files::isRegularFile).sorted().toList()){
    byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source));
    hashes.add(HexFormat.of().formatHex(hash)+"  "+baseline.relativize(source).toString().replace('\\','/'));
   }
  }
  Files.write(output.resolve("model-animation-storage-baseline.sha256"),hashes);
  oldUpdate=frozen("update.comp");oldReset=frozen("reset.comp");oldPrepare=frozen("prepare_dispatch.comp");oldKeys=frozen("keygen.comp");oldGrid=frozen("gridbuild.comp");
  var rows=new ArrayList<String>();rows.add("profile,variant,run,main_capacity,model_count,ordinary_count,gpu_p95_ms,gpu_p99_ms,cpu_p95_ms,cpu_p99_ms,animation_bytes,allocator_bytes,model_list_bytes,animation_written_bytes");
  var raw=new ArrayList<String>();raw.add("profile,variant,run,sample,gpu_ms,cpu_ms");
  int cap=2_000_000;float dt=1f/120;
  for(String profile:new String[]{"no-model","storm-65536","model-262144","mixed"}){
   int models=profile.equals("no-model")?0:profile.equals("model-262144")?262144:65536;
   int ordinary=profile.equals("no-model")?65536:profile.equals("mixed")?262144:0;boolean storm=profile.startsWith("storm");
   try(var modern=new Rig(cap,models>0);var legacy=new Rig(cap,models>0);var oldAnimation=new OldAnimation(models>0?cap:0)){
    prepareScene(modern,models,ordinary,storm);prepareScene(legacy,models,ordinary,storm);
    ui(oldKeys,"uUpper",cap);ui(oldKeys,"uCarrierBase",cap);ui(oldKeys,"uCarrierLimit",ParticleBuffers.CARRIER_CAP);
    f(oldKeys,"uMaxDepth",128);f(oldKeys,"uDepthLogRange",7);
    GL41.glProgramUniformMatrix4fv(oldKeys,GpuUniforms.loc(oldKeys,"uView"),false,new org.joml.Matrix4f().get(new float[16]));
    // Install the frozen 4-byte MODEL roster format once; subsequent old keygen maintains it.
    var list=legacy.roster();var oldList=bytes(32+4*(legacy.modelCap+legacy.stormCap));
    for(int i=0;i<8;i++)oldList.putInt(i*4,list.getInt(i*4));
    for(int i=0;i<models;i++)oldList.putInt(32+i*4,list.getInt(32+i*8));
    for(int i=0;i<(storm?models:0);i++)oldList.putInt(32+(legacy.modelCap+i)*4,list.getInt(32+i*8));
    put(legacy.lists[legacy.bank],oldList);legacy.animation.close();
    for(int run=1;run<=3;run++)for(boolean current:new boolean[]{false,true}){
     Rig r=current?modern:legacy;Runnable submit=current?()->{r.begin(dt);r.finish(true,true);}:()->legacyFrame(r,oldAnimation,dt);
     for(int warm=0;warm<120;warm++){submit.run();GL11.glFinish();}
     int n=180,query=GL15.glGenQueries();double[] gt=new double[n],ct=new double[n];
     long animationTicks=r.animation.tick();
     try{for(int i=0;i<n;i++){GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,query);long started=System.nanoTime();submit.run();ct[i]=(System.nanoTime()-started)/1e6;GL15.glEndQuery(GL33.GL_TIME_ELAPSED);gt[i]=GL33.glGetQueryObjectui64(query,GL15.GL_QUERY_RESULT)/1e6;raw.add(profile+","+(current?"current":"before")+","+run+","+i+","+gt[i]+","+ct[i]);}}
     finally{GL15.glDeleteQueries(query);}
     check(read(r.counter,16).getInt(0)==models+ordinary,"Benchmark lost particles profile="+profile+" current="+current+" actual="+read(r.counter,16).getInt(0));
     check(read(r.commands,ParticleBuffers.INDIRECT_UINTS*4).getInt(ParticleBuffers.IDX_CNT_MODELOP*4)==models,"Benchmark culling/partition lost MODEL instances");
     long written=current?(r.animation.tick()-animationTicks)*models*64L:n*models*64L;
     String row=profile+","+(current?"current":"before")+","+run+","+cap+","+models+","+ordinary+","+percentile(gt,.95)+","+percentile(gt,.99)+","+percentile(ct,.95)+","+percentile(ct,.99)+","+(current?r.animation.bytes():oldAnimation.capacity*128L)+","+(current?r.animation.allocatorBytes():0)+","+(current?legacy.modelCap*16L:legacy.modelCap*8L)+","+written;
     rows.add(row);System.out.println(row);
     if(run==1){r.stageQueries=new int[13];for(int i=0;i<13;i++)r.stageQueries[i]=GL15.glGenQueries();double[] stages=new double[12];
      try{for(int f=0;f<12;f++){submit.run();GL11.glFinish();long before=GL33.glGetQueryObjectui64(r.stageQueries[0],GL15.GL_QUERY_RESULT);for(int stage=1;stage<13;stage++){long after=GL33.glGetQueryObjectui64(r.stageQueries[stage],GL15.GL_QUERY_RESULT);stages[stage-1]+=(after-before)/1e6/12;before=after;}}}finally{for(int id:r.stageQueries)GL15.glDeleteQueries(id);r.stageQueries=null;}
      System.out.println("STAGES "+profile+" "+current+" "+Arrays.toString(stages));
     }
    }
   }
  }
  Path out=Path.of(System.getProperty("cmi.benchmark.output","build/benchmarks/current"));Files.createDirectories(out);Files.write(out.resolve("model-animation-storage.csv"),rows);Files.write(out.resolve("model-animation-storage-samples.csv"),raw);
 }
 static void memoryInventory()throws Exception{
  var gpu=new ParticleBuffers();try{
   check(gpu.init(2_000_000,128),"Memory inventory owner init");
   var ids=new HashSet<Integer>();
   for(String name:List.of("identitySSBO","dispatchSSBO","particleSSBOs","populationSSBOs","emitSSBOs","emitterSSBO","indirectFrames","additiveFrames","opaqueFrames","counterSSBOs","sortSSBOs","histSSBO","offsetSSBO","bakeMetaSSBO","gridSSBO","damageSSBO","hitSSBO","playersSSBO","stormPosSSBO","waveContactSSBO")){
    var field=ParticleBuffers.class.getDeclaredField(name);field.setAccessible(true);Object value=field.get(gpu);if(value instanceof int[] array){for(int id:array)ids.add(id);}else ids.add((Integer)value);
   }
   var field=ParticleBuffers.class.getDeclaredField("stormIdentities");field.setAccessible(true);var storm=(com.iridium126.createmanaindustry.client.allaystorm.StormIdentityBuffers)field.get(gpu);ids.add(storm.corrections());ids.add(storm.members());
   long total=0;for(int id:ids){GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);total+=GL32.glGetBufferParameteri64(GL43.GL_SHADER_STORAGE_BUFFER,GL15.GL_BUFFER_SIZE);}
   long listDelta=2L*262144*4;
   var rows=new ArrayList<String>();rows.add("state,before_core_buffer_bytes,current_core_buffer_bytes,animation_bytes,allocator_bytes,model_list_delta_bytes");
   rows.add("no-model,"+(total-listDelta-24)+","+total+",0,0,"+listDelta);
   float[] header=new float[80];header[28]=2;gpu.setEmitterHeader(1,header);gpu.beginComputeFrame(0);gpu.allayAnimation().prepareAllocator(modelPrepare);
   long animation=gpu.allayAnimation().bytes(),allocator=gpu.allayAnimation().allocatorBytes();
   rows.add("model,"+(total-listDelta-24+2L*2_000_000*64)+","+(total+animation+allocator)+","+animation+","+allocator+","+listDelta);
   Path out=Path.of(System.getProperty("cmi.benchmark.output","build/benchmarks/current"));Files.write(out.resolve("model-animation-storage-memory.csv"),rows);rows.forEach(System.out::println);
  }finally{gpu.free();}
 }
 public static void main(String[] args)throws Exception{
  GLFWErrorCallback.createPrint(System.err).set();check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long w=GLFW.glfwCreateWindow(64,64,"MODEL animation benchmark",0,0);
  try{GLFW.glfwMakeContextCurrent(w);GL.createCapabilities();System.out.println(GL11.glGetString(GL11.GL_RENDERER)+" / "+GL11.glGetString(GL11.GL_VERSION));update=compile("update.comp");emit=compile("emit.comp");animate=compile("allay_animate.comp");grid=compile("gridbuild.comp");modelPrepare=compile("model_prepare.comp");run();memoryInventory();}
  finally{for(int p:new int[]{update,emit,animate,grid,modelPrepare,oldUpdate,oldReset,oldPrepare,oldKeys,oldGrid})if(p!=0)deleteProgram(p);GLFW.glfwDestroyWindow(w);GLFW.glfwTerminate();}
 }
}
