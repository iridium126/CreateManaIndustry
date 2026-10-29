import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.joml.Matrix4f;
import com.iridium126.createmanaindustry.client.particles.engine.*;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler;

/** Production analytic kernels and extended-depth MRT, executed on the real driver. */
public class StormLodGpuValidation extends ModelPopulationGpuValidation {
 static final int N=131072;
 static int graphics(String vertex,String fragment){return GpuShaderCompiler.graphics(vertex,"#version 450 core\n"+ParticlePrograms.commonPrelude()+source(vertex),"#version 450 core\n"+ParticlePrograms.commonPrelude()+source(fragment));}
 static class Lod implements AutoCloseable {
  final int program;
  Lod(){this(compile("storm_lod.comp"));}
  Lod(int program){this.program=program;}
  int records=buffer(bytes(N*32)),order=buffer(bytes(N*16)),commands=buffer(bytes(40)),dead=buffer(bytes(N/8)),near=buffer(bytes(N/8));
  void run(int count,float z){
   bind(0,records);bind(1,order);bind(2,commands);bind(3,dead);bind(4,near);
   put(commands,bytes(40).putInt(0,144).putInt(20,6));
   ui(program,"uCount",count);f(program,"uSeed",16777215);f(program,"uRadius",32);f(program,"uOmega",.12f);f(program,"uRotation",.7f);f(program,"uCirculation",.3f);f(program,"uCirculationRate",.015f);
   f(program,"uNear",120);f(program,"uMiddle",1024);f(program,"uFar",8192);
   GL41.glProgramUniform3f(program,GL20.glGetUniformLocation(program,"uCenter"),0,150,z);
   dispatch(program,Math.max(1,(count+63)/64));
  }
  int verify(int expected,int grade){
   var count=read(commands,40);int body=count.getInt(4),pixel=count.getInt(24);check(body+pixel==expected,"LOD count "+body+"/"+pixel+" expected "+expected);
   if(grade==1)check(pixel==0,"Wrong pixel grade");if(grade==2)check(body==0,"Wrong body grade");
   var list=read(order,N*16);BitSet ids=new BitSet();
   for(int g=0;g<2;g++)for(int rank=0;rank<(g==0?body:pixel);rank++){
    int id=list.getInt((g*N+rank)*8);check(id>=0&&id<N&&!ids.get(id),"Duplicate/out-of-range member");ids.set(id);
   }
   return body+pixel;
  }
  public void close(){GL20.glUseProgram(0);for(int b:new int[]{records,order,commands,dead,near})GL15.glDeleteBuffers(b);deleteProgram(program);}
 }
 static void members()throws Exception {
  try(var lod=new Lod()){
   for(int n:new int[]{0,1,63,64,65,65536,N}){lod.run(n,-500);lod.verify(n,1);lod.run(n,-2000);lod.verify(n,2);}
   var deaths=bytes(N/8);deaths.putInt(0,3);deaths.putInt((N/32-1)*4,0x80000000);put(lod.dead,deaths);
   put(lod.near,bytes(N/8).putInt(4,1));lod.run(N,-2000);lod.verify(N-4,2);
   put(lod.dead,bytes(N/8));put(lod.near,bytes(N/8));
   lod.run(N,-9000);lod.verify(0,0);
   lod.run(N,0); // y=150 leaves the complete vortex outside D0, despite x/z around zero.
   lod.verify(N,1);
   GL41.glProgramUniform4fv(lod.program,GL20.glGetUniformLocation(lod.program,"uFrustum"),new float[]{1,0,0,-10000,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0});lod.run(N,-2000);lod.verify(0,0);
   GL41.glProgramUniform4fv(lod.program,GL20.glGetUniformLocation(lod.program,"uFrustum"),new float[24]);
   lod.run(N,-2000);
   // Retain the original pre-extraction precision chain as an independent reference.
   String reference="""
    layout(local_size_x=64)in;
    layout(std430,binding=0)buffer Reference {vec4 value[];};
    void main(){uint memberIdx=gl_GlobalInvocationID.x;
      precise float s0=cmiHash1(16777215.0+31.7);precise float shift=s0*97.0;
      precise float t=float(memberIdx)*.6123;precise float p=t+.7;precise float q=p+shift;
      precise float hA=cmiHash1(q);precise float q2=q*7.31;precise float hB=cmiHash1(q2);
      precise float mA=hA*.618;precise float mB=hB*.382;precise float seed=mA+mB;
      vec3 pos,vel;cmiTyphoonHome(seed,32.,.12,.7,.3,.015,6.,vec3(0,150,-2000),pos,vel);
      value[memberIdx*2u]=vec4(pos,.3);value[memberIdx*2u+1u]=vec4(vel,seed);
    }
    """;
   int ref=GpuShaderCompiler.compute("original seed/home reference","#version 450 core\n"+ParticlePrograms.commonPrelude()+source("chunks/allay_storm.glsl")+reference);
   int result=buffer(bytes(N*32));bind(0,result);dispatch(ref,N/64);
   var actual=read(lod.records,N*32);var expected=read(result,N*32);
   for(int i=0;i<N*8;i++)check(Math.abs(actual.getFloat(i*4)-expected.getFloat(i*4))<.0003,"Seed/home mismatch at word "+i);
   // Exercise additional seeds, radii and frozen-law growth phases without
   // allowing the extracted seed helper to serve as its own reference.
   for(int seed:new int[]{0,1,12345,16777215})for(float radius:new float[]{.5f,8,32,45.254833f}){
    var phase=com.iridium126.createmanaindustry.content.allaystorm.AllayStormPhases.evaluate(.5f,45.254833f,20,0,(long)(radius*radius*64),0f,128.*radius*radius,seed);
    String variant=reference.replace("16777215.0",Float.toString((float)seed)).replace("seed,32.,.12,.7,.3,.015,6.","seed,"+radius+","+phase.omega()+","+phase.rotation()+","+phase.conveyor()+","+phase.conveyorRate()+",6.");
    int referenceProgram=GpuShaderCompiler.compute("seed/growth reference","#version 450 core\n"+ParticlePrograms.commonPrelude()+source("chunks/allay_storm.glsl")+variant);
    lod.run(4096,-2000);put(lod.commands,bytes(40).putInt(0,144).putInt(20,6));f(lod.program,"uSeed",seed);f(lod.program,"uRadius",radius);f(lod.program,"uOmega",phase.omega());f(lod.program,"uRotation",phase.rotation());f(lod.program,"uCirculation",phase.conveyor());f(lod.program,"uCirculationRate",phase.conveyorRate());dispatch(lod.program,4096/64);
    bind(0,result);dispatch(referenceProgram,4096/64);actual=read(lod.records,4096*32);expected=read(result,4096*32);for(int i=0;i<4096*8;i++)check(Math.abs(actual.getFloat(i*4)-expected.getFloat(i*4))<.0003,"Seed/growth mismatch");deleteProgram(referenceProgram);
   }
   GL15.glDeleteBuffers(result);deleteProgram(ref);
  }
 }
 static void computeBenchmark()throws Exception{
  try(var lod=new Lod()){
   var rows=new ArrayList<String>();rows.add("members,lod,run,gpu_ms");
   for(int n:new int[]{65536,N})for(int grade:new int[]{1,2}){
    for(int i=0;i<30;i++)lod.run(n,grade==1?-500:-2000);
    int query=GL15.glGenQueries();
    for(int i=0;i<30;i++){GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,query);lod.run(n,grade==1?-500:-2000);GL15.glEndQuery(GL33.GL_TIME_ELAPSED);rows.add(n+","+grade+","+i+","+GL33.glGetQueryObjectui64(query,GL15.GL_QUERY_RESULT)/1e6);}
    GL15.glDeleteQueries(query);lod.verify(n,grade);
   }
   Path directory=Path.of(System.getProperty("cmi.benchmark.output","build/benchmarks/current"));Files.createDirectories(directory);Files.write(directory.resolve("storm-lod-compute.csv"),rows);
  }
 }
 static void nearCoverage(){
  try(var q=new Fixture(4,4,4)){
   int covered=buffer(bytes(N/8));bind(21,covered);bool(q.keygen,"uLodCapture",true);f(q.keygen,"uOpaqueCutoff",120);
   GL41.glProgramUniform4fv(q.keygen,GL20.glGetUniformLocation(q.keygen,"uFrustum"),new float[]{1,0,0,-10000,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0});
   var pool=bytes(4*64);for(int id=0;id<4;id++){particle(pool,id,2,id==2?120:10,0,id==1?0:20);pool.putFloat(id*64+12,id==3?131072:id+7);}
   q.build(pool,4);var mask=read(covered,N/8);check(mask.getInt(0)==(1<<6),"Coverage includes a corpse or distance-boundary member");check(mask.getInt(N/8-4)==0x80000000,"Coverage lost last member or used main frustum");
   put(covered,bytes(N/8));pool.putFloat(12,32);q.build(pool,1);mask=read(covered,N/8);check(mask.getInt(0)==0x80000000,"Coverage used an old input-pool member mapping");check(mask.getInt(N/8-4)==0,"Coverage retained a previous generation");GL15.glDeleteBuffers(covered);
  }
 }
 static void residentBenchmark()throws Exception{
  int n=50000;
  try(var q=new Fixture(n,1,1);var lod=new Lod()){
   var ordinary=bytes(n*64);for(int i=0;i<n;i++)particle(ordinary,i,0,(i%100)*.05f,2,20);
   var header=headers();header.putFloat(7*16,3);put(q.emit,header);put(q.pool,ordinary);
   Runnable resident=()->{q.bindings();dispatch(q.reset,1);put(q.counter,bytes(16).putInt(0,n));dispatch(q.keygen,(n+63)/64);};
   var rows=new ArrayList<String>();rows.add("resident_opaque,lod_members,lod,run,gpu_ms");int query=GL15.glGenQueries();
   for(int count:new int[]{0,65536,N})for(int grade:new int[]{1,2}){
    for(int i=0;i<20;i++){resident.run();if(count>0)lod.run(count,grade==1?-500:-2000);}
    for(int i=0;i<30;i++){GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,query);resident.run();if(count>0)lod.run(count,grade==1?-500:-2000);GL15.glEndQuery(GL33.GL_TIME_ELAPSED);rows.add(n+","+count+","+grade+","+i+","+GL33.glGetQueryObjectui64(query,GL15.GL_QUERY_RESULT)/1e6);}
    if(count>0)lod.verify(count,grade);
   }
   GL15.glDeleteQueries(query);Path output=Path.of(System.getProperty("cmi.benchmark.output","build/benchmarks/current"));Files.createDirectories(output);Files.write(output.resolve("storm-lod-resident-keygen.csv"),rows);
  }
 }
 static int assetTexture()throws Exception{
  var image=javax.imageio.ImageIO.read(StormLodGpuValidation.class.getResourceAsStream("/assets/createmanaindustry/textures/particle/allay_0.png"));
  int width=ParticleAtlasLayout.ALLAY_WIDTH,height=ParticleAtlasLayout.ALLAY_HEIGHT;
  var pixels=bytes(width*height*4);for(int y=0;y<32;y++)for(int x=0;x<32;x++){int c=image.getRGB(x,y),offset=(y*width+x)*4;pixels.put(offset,(byte)(c>>16)).put(offset+1,(byte)(c>>8)).put(offset+2,(byte)c).put(offset+3,(byte)(c>>>24));}
  int texture=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,width,height,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);return texture;
 }
 static void hardCutoff()throws Exception{
  int fbo=GL30.glGenFramebuffers(),color=texture(GL30.GL_RGBA32F),depth=texture(GL30.GL_DEPTH_COMPONENT32F),vao=GL30.glGenVertexArrays();GL30.glBindVertexArray(vao);GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,color,0);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_DEPTH_ATTACHMENT,GL11.GL_TEXTURE_2D,depth,0);GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0});GL11.glViewport(0,0,16,16);
  var field=AllayModelGeometry.class.getDeclaredField("VERTICES");field.setAccessible(true);float[] vertices=(float[])field.get(null);var geo=bytes(vertices.length*4);geo.asFloatBuffer().put(vertices);int geometry=buffer(geo);
  field=AllayModelGeometry.class.getDeclaredField("INDICES");field.setAccessible(true);int[] indices=(int[])field.get(null);int ebo=GL15.glGenBuffers();GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,ebo);GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER,indices,GL15.GL_STATIC_DRAW);
  int pool=buffer(bytes(64)),emit=buffer(bytes(320)),sorted=buffer(bytes(8)),order=buffer(bytes(4)),command=buffer(bytes(120)),atlas=assetTexture();
  // Opaque billboard coverage is independent of atlas art; use an opaque atlas-sized input.
  int sprite=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,sprite);byte[] white=new byte[ParticleAtlasLayout.SPRITE_WIDTH*ParticleAtlasLayout.SPRITE_HEIGHT*4];java.util.Arrays.fill(white,(byte)255);
  GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,ParticleAtlasLayout.SPRITE_WIDTH,ParticleAtlasLayout.SPRITE_HEIGHT,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,bytes(white.length).put(white).flip());GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
  bind(ParticleBuffers.MODELGEO_BINDING,geometry);bind(ParticleBuffers.PARTICLE_BB_WRITE,pool);bind(ParticleBuffers.EMITTER_BB,emit);bind(ParticleBuffers.SORTWRITE_BINDING,sorted);bind(ParticleBuffers.ORDEROPAQUE_BINDING,order);bind(ParticleBuffers.INDIRECT_BB,command);
  float[] distances={96,108,116,119.9f,120};
  for(boolean model:new boolean[]{false,true}){
   int current=graphics(model?"model.vsh":"textured.vsh",model?"model.fsh":"textured.fsh");
   var header=bytes(320);header.putFloat(5*16+8,.3f).putFloat(5*16+12,.3f).putFloat(6*16,1).putFloat(6*16+8,1).putFloat(6*16+12,1).putFloat(7*16,model?2:3).putFloat(8*16,1).putFloat(8*16+4,1).putFloat(8*16+8,1).putFloat(8*16+12,1).putFloat(16*16+4,1);put(emit,header);
   int previousPixels=-1;
   for(int column=0;column<distances.length;column++){
    int p=current;GL20.glUseProgram(p);GL20.glUniform1i(GL20.glGetUniformLocation(p,"uMode"),1);GL20.glUniform1i(GL20.glGetUniformLocation(p,"uSprite"),1);GL20.glUniform1i(GL20.glGetUniformLocation(p,"cmi_AllayAnimation"),3);GL20.glUniform1i(GL20.glGetUniformLocation(p,"uLightmap"),2);
    f(p,"uFadeDist",96);GL41.glProgramUniform3f(p,GL20.glGetUniformLocation(p,"uCamRight"),1,0,0);GL41.glProgramUniform3f(p,GL20.glGetUniformLocation(p,"uCamUp"),0,1,0);
    GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(p,"ModelViewMat"),false,new Matrix4f().get(new float[16]));GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(p,"ProjMat"),false,new Matrix4f().ortho(-.4f,.4f,-.1f,.7f,.01f,200).get(new float[16]));
    var particle=bytes(64);particle.putFloat(8,-distances[column]).putFloat(12,1).putFloat(32,1).putFloat(36,1).putFloat(40,1).putFloat(44,model?0:1).putFloat(48,1).putFloat(52,20);put(pool,particle);
    GL13.glActiveTexture(GL13.GL_TEXTURE1);GL11.glBindTexture(GL11.GL_TEXTURE_2D,model?atlas:sprite);GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthMask(true);GL11.glDisable(GL11.GL_CULL_FACE);GL11.glEnable(GL11.GL_BLEND);GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_ONE,GL11.GL_ZERO);GL30.glClearBufferfv(GL11.GL_COLOR,0,new float[]{0,0,0,0});GL30.glClearBufferfv(GL11.GL_DEPTH,0,new float[]{1});
    if(model)GL31.glDrawElementsInstanced(GL11.GL_TRIANGLES,indices.length,GL11.GL_UNSIGNED_INT,0L,1);else GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES,0,6,1);
    var pixels=bytes(16*16*4);GL11.glReadPixels(0,0,16,16,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);int lit=0;
    for(int y=0;y<16;y++)for(int x=0;x<16;x++){int o=(y*16+x)*4,r=pixels.get(o)&255,g=pixels.get(o+1)&255,b=pixels.get(o+2)&255;if(r+g+b>0)lit++;}
    {check(distances[column]<120?lit>0:lit==0,"Whole-instance distance cutoff");if(distances[column]<120){if(previousPixels>=0)check(lit==previousPixels,"Distance eroded opaque silhouette");previousPixels=lit;}}
   }
   GL20.glUseProgram(0);deleteProgram(current);
  }
  for(int b:new int[]{geometry,ebo,pool,emit,sorted,order,command})GL15.glDeleteBuffers(b);for(int t:new int[]{color,depth,atlas,sprite})GL11.glDeleteTextures(t);GL30.glDeleteFramebuffers(fbo);GL30.glDeleteVertexArrays(vao);
 }
 static void renderBenchmark()throws Exception{
  int width=1280,height=720;
  int fbo=GL30.glGenFramebuffers(),color=GL11.glGenTextures(),depth=GL11.glGenTextures(),vao=GL30.glGenVertexArrays();
  GL30.glBindVertexArray(vao);GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);
  for(int t:new int[]{color,depth}){GL11.glBindTexture(GL11.GL_TEXTURE_2D,t);boolean d=t==depth;GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,d?GL30.GL_DEPTH_COMPONENT32F:GL11.GL_RGBA8,width,height,0,d?GL11.GL_DEPTH_COMPONENT:GL11.GL_RGBA,GL11.GL_FLOAT,0L);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,d?GL30.GL_DEPTH_ATTACHMENT:GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,t,0);}
  GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0});GL11.glViewport(0,0,width,height);
  var field=AllayModelGeometry.class.getDeclaredField("VERTICES");field.setAccessible(true);float[] vertices=(float[])field.get(null);var data=bytes(vertices.length*4);data.asFloatBuffer().put(vertices);int geometry=buffer(data);
  field=AllayModelGeometry.class.getDeclaredField("INDICES");field.setAccessible(true);int ebo=GL15.glGenBuffers();GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,ebo);GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER,(int[])field.get(null),GL15.GL_STATIC_DRAW);
  int atlas=assetTexture(),body=graphics("storm_lod.vsh","model.fsh"),pixel=graphics("storm_lod.vsh","textured.fsh"),composite=graphics("storm_lod_composite.vsh","storm_lod_composite.fsh");
  field=ParticlePrograms.class.getDeclaredField("lodComposite");field.setAccessible(true);field.setInt(ParticlePrograms.INSTANCE,composite);
  var type=Class.forName("com.iridium126.createmanaindustry.client.particles.engine.StormLodTarget");var constructor=type.getDeclaredConstructor();constructor.setAccessible(true);Object target=constructor.newInstance();
  var draw=type.getDeclaredMethod("draw",org.joml.Matrix4fc.class,org.joml.Matrix4fc.class,Runnable.class);draw.setAccessible(true);
  var profiling=type.getDeclaredMethod("profiling",boolean.class);profiling.setAccessible(true);profiling.invoke(target,true);
  var report=type.getDeclaredMethod("report");report.setAccessible(true);
  Matrix4f projection=new Matrix4f().perspective(1f,(float)width/height,.05f,128),extended=StormLodGpu.extended(projection,8192);
  var rows=new ArrayList<String>();rows.add("members,lod,stage,run,gpu_ms");var summaries=new ArrayList<String>();summaries.add("1280x720 standalone production shaders; fixed R=32, center Y=150, Z=-500/-2000; no real pack/world.");
  try(var lod=new Lod()){
   bind(ParticleBuffers.MODELGEO_BINDING,geometry);int query=GL15.glGenQueries();
   for(int count:new int[]{65536,N})for(int grade:new int[]{1,2}){
    lod.run(count,grade==1?-500:-2000);final int program=grade==1?body:pixel;
    Runnable geometryDraw=()->{
     GL20.glUseProgram(program);GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program,"ModelViewMat"),false,new Matrix4f().get(new float[16]));GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program,"ProjMat"),false,extended.get(new float[16]));GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program,"cmi_LodInverseView"),false,new Matrix4f().get(new float[16]));
     GL20.glUniform1i(GL20.glGetUniformLocation(program,"cmi_LodPass"),grade);GL20.glUniform1i(GL20.glGetUniformLocation(program,"uSprite"),1);GL20.glUniform1i(GL20.glGetUniformLocation(program,"uMode"),1);
     bind(0,lod.records);bind(1,lod.order);bind(ParticleBuffers.MODELGEO_BINDING,geometry);GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER,lod.commands);GL13.glActiveTexture(GL13.GL_TEXTURE1);GL11.glBindTexture(GL11.GL_TEXTURE_2D,atlas);GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthFunc(GL11.GL_LESS);GL11.glDepthMask(true);GL11.glDisable(GL11.GL_CULL_FACE);GL11.glDisable(GL11.GL_BLEND);
     if(grade==1)GL40.glDrawElementsIndirect(GL11.GL_TRIANGLES,GL11.GL_UNSIGNED_INT,0L);else GL40.glDrawArraysIndirect(GL11.GL_TRIANGLES,20L);
    };
    for(int i=0;i<20;i++){GL30.glClearBufferfv(GL11.GL_DEPTH,0,new float[]{1});geometryDraw.run();}
    for(int i=0;i<30;i++){GL30.glClearBufferfv(GL11.GL_DEPTH,0,new float[]{1});GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,query);geometryDraw.run();GL15.glEndQuery(GL33.GL_TIME_ELAPSED);rows.add(count+","+grade+",draw,"+i+","+GL33.glGetQueryObjectui64(query,GL15.GL_QUERY_RESULT)/1e6);}
    for(int i=0;i<20;i++){profiling.invoke(target,true);GL30.glClearBufferfv(GL11.GL_DEPTH,0,new float[]{1});draw.invoke(target,projection,extended,geometryDraw);}
    var timingField=type.getDeclaredField("compositeTiming");timingField.setAccessible(true);((AutoCloseable)timingField.get(target)).close();profiling.invoke(target,true);
    for(int i=0;i<30;i++){profiling.invoke(target,true);GL30.glClearBufferfv(GL11.GL_DEPTH,0,new float[]{1});GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,query);draw.invoke(target,projection,extended,geometryDraw);GL15.glEndQuery(GL33.GL_TIME_ELAPSED);rows.add(count+","+grade+",target_total,"+i+","+GL33.glGetQueryObjectui64(query,GL15.GL_QUERY_RESULT)/1e6);}
    profiling.invoke(target,true);summaries.add(count+" LOD"+grade+" "+report.invoke(target));
   }
   GL15.glDeleteQueries(query);
  }
  ((AutoCloseable)target).close();field.setInt(ParticlePrograms.INSTANCE,0);GL20.glUseProgram(0);
  for(int b:new int[]{geometry,ebo})GL15.glDeleteBuffers(b);for(int t:new int[]{color,depth,atlas})GL11.glDeleteTextures(t);GL30.glDeleteFramebuffers(fbo);GL30.glDeleteVertexArrays(vao);for(int program:new int[]{body,pixel,composite})deleteProgram(program);
  Path output=Path.of(System.getProperty("cmi.benchmark.output","build/benchmarks/current"));Files.createDirectories(output);Files.write(output.resolve("storm-lod-render.csv"),rows);Files.write(output.resolve("storm-lod-composite.txt"),summaries);
 }
 static void projection()throws Exception {
  // Capture the production vertex stage's clip positions, not a CPU imitation
  // or rasterized pixel count: a subpixel square is allowed to shade no pixels.
  int shader=GL20.glCreateShader(GL20.GL_VERTEX_SHADER),program=GL20.glCreateProgram();
  GL20.glShaderSource(shader,"#version 450 core\n"+ParticlePrograms.commonPrelude()+source("storm_lod.vsh"));GL20.glCompileShader(shader);
  check(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)!=0,"Projection vertex compile: "+GL20.glGetShaderInfoLog(shader));
  GL20.glAttachShader(program,shader);
  GL30.glTransformFeedbackVaryings(program,new String[]{"gl_Position"},GL30.GL_INTERLEAVED_ATTRIBS);
  GL20.glLinkProgram(program);
  check(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)!=0,"Projection transform feedback link: "+GL20.glGetProgramInfoLog(program));
  GL20.glDetachShader(program,shader);GL20.glDeleteShader(shader);
  check(GL20.glGetUniformLocation(program,"cmi_LodPixelScale")==-1,"Screen-size uniform survived");
  int records=buffer(bytes(32)),order=buffer(bytes((N+1)*8)),feedback=buffer(bytes(6*16)),vao=GL30.glGenVertexArrays();
  GL30.glBindVertexArray(vao);bind(0,records);bind(1,order);
  GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,0,feedback);
  GL20.glUseProgram(program);GL20.glUniform1i(GL20.glGetUniformLocation(program,"cmi_LodPass"),2);
  var rows=new ArrayList<String>();rows.add("width,height,vertical_fov,rotated,view_depth,projected_width_px,projected_height_px,world_width,world_height");
  for(int[] viewport:new int[][]{{1280,720},{1920,1080},{3840,2160}})for(int fov:new int[]{40,70,100})for(boolean rotated:new boolean[]{false,true}){
   Matrix4f view=new Matrix4f();if(rotated)view.rotateY(.7f).rotateX(-.4f).rotateZ(.5f);
   Matrix4f inverse=new Matrix4f(view).invert(),projection=new Matrix4f().perspective((float)Math.toRadians(fov),(float)viewport[0]/viewport[1],.05f,8192);
   GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program,"ModelViewMat"),false,view.get(new float[16]));
   GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program,"ProjMat"),false,projection.get(new float[16]));
   GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program,"cmi_LodInverseView"),false,inverse.get(new float[16]));
   double previousWidth=0;float previousDistance=0;
   for(float distance:new float[]{512,1024,2048,8191}){
    var centre=new org.joml.Vector3f(distance*.1f,-distance*.05f,-distance).mulPosition(inverse);
    put(records,bytes(32).putFloat(0,centre.x).putFloat(4,centre.y-.3f).putFloat(8,centre.z).putFloat(12,.3f));
    GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);GL30.glBeginTransformFeedback(GL11.GL_TRIANGLES);
    GL11.glDrawArrays(GL11.GL_TRIANGLES,0,6);GL30.glEndTransformFeedback();GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
    GL42.glMemoryBarrier(GL42.GL_TRANSFORM_FEEDBACK_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
    var captured=read(feedback,6*16);double minX=Double.POSITIVE_INFINITY,minY=minX,maxX=-minX,maxY=-minX;
    org.joml.Vector3f[] vertices=new org.joml.Vector3f[6];var mean=new org.joml.Vector3f();
    for(int vertex=0;vertex<6;vertex++){
     int offset=vertex*16;float x=captured.getFloat(offset),y=captured.getFloat(offset+4),w=captured.getFloat(offset+12);
     check(Math.abs(w-distance)<.004,"Billboard not camera facing");
     double sx=(double)x/w,sy=(double)y/w;minX=Math.min(minX,sx);maxX=Math.max(maxX,sx);minY=Math.min(minY,sy);maxY=Math.max(maxY,sy);
     // Standard perspective has clip.w=-view.z; reconstruct without losing
     // far-depth precision through a z/w depth-buffer round trip.
     vertices[vertex]=new org.joml.Vector3f(x/projection.m00(),y/projection.m11(),-w).mulPosition(inverse);mean.add(vertices[vertex]);
    }
    mean.div(6);double worldWidth=vertices[0].distance(vertices[1]),worldHeight=vertices[0].distance(vertices[5]);
    check(Math.abs(worldWidth-.6)<.003&&Math.abs(worldHeight-.6)<.003,"LOD2 world dimensions changed");
    check(mean.distance(centre)<.004,"LOD2 centre offset changed");
    double pixelWidth=(maxX-minX)*viewport[0]/2,pixelHeight=(maxY-minY)*viewport[1]/2;
    double expectedWidth=.6*projection.m00()*viewport[0]/(2*distance),expectedHeight=.6*projection.m11()*viewport[1]/(2*distance);
    check(Math.abs(pixelWidth-expectedWidth)<.002&&Math.abs(pixelHeight-expectedHeight)<.002,"Perspective/FOV/resolution mismatch");
    if(previousDistance*2==distance)check(Math.abs(pixelWidth/previousWidth-.5)<.004,"Distance doubling did not halve screen size");
    rows.add(viewport[0]+","+viewport[1]+","+fov+","+rotated+","+distance+","+pixelWidth+","+pixelHeight+","+worldWidth+","+worldHeight);
    previousWidth=pixelWidth;previousDistance=distance;
   }
  }
  GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,0,0);GL20.glUseProgram(0);
  for(int id:new int[]{records,order,feedback})GL15.glDeleteBuffers(id);GL30.glDeleteVertexArrays(vao);deleteProgram(program);
  Path output=Path.of(System.getProperty("cmi.benchmark.output","build/benchmarks/current"));Files.createDirectories(output);Files.write(output.resolve("storm-lod-projection.csv"),rows);
  subpixelAndEdges();
 }
 static void subpixelAndEdges()throws Exception {
  try(var lod=new Lod()){
   lod.run(1,-2000);var position=read(lod.records,32);
   for(float middle:new float[]{1024,8192})for(float separation:new float[]{.64f,.70f,.76f}){
    lod.run(1,-2000);f(lod.program,"uMiddle",middle);
    GL41.glProgramUniform4fv(lod.program,GL20.glGetUniformLocation(lod.program,"uFrustum"),new float[]{1,0,0,-position.getFloat(0)-separation,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0});
    put(lod.commands,bytes(40).putInt(0,144).putInt(20,6));dispatch(lod.program,1);
    lod.verify(separation<(middle==1024?.75f:.65f)?1:0,middle==1024?2:1);
   }
   GL41.glProgramUniform4fv(lod.program,GL20.glGetUniformLocation(lod.program,"uFrustum"),new float[24]);
   lod.run(1,-2000);lod.verify(1,2);
   float distance=(float)Math.sqrt(position.getFloat(0)*position.getFloat(0)+position.getFloat(4)*position.getFloat(4)+position.getFloat(8)*position.getFloat(8));
   for(float middle:new float[]{distance-.02f,distance+.02f}){
    lod.run(1,-2000);f(lod.program,"uMiddle",middle);put(lod.commands,bytes(40).putInt(0,144).putInt(20,6));dispatch(lod.program,1);lod.verify(1,middle<distance?2:1);
   }
   lod.run(1,-2000);
   // Even-sized viewport: an on-axis tiny square falls between sample centres.
   put(lod.records,bytes(32).putFloat(4,-.3f).putFloat(8,-2000).putFloat(12,.3f));
   int fbo=GL30.glGenFramebuffers(),color=texture(GL30.GL_RGBA32F),depth=texture(GL30.GL_DEPTH_COMPONENT32F),vao=GL30.glGenVertexArrays(),atlas=assetTexture(),program=graphics("storm_lod.vsh","textured.fsh");
   GL30.glBindVertexArray(vao);GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,color,0);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_DEPTH_ATTACHMENT,GL11.GL_TEXTURE_2D,depth,0);GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0});GL11.glViewport(0,0,16,16);
   GL20.glUseProgram(program);GL20.glUniform1i(GL20.glGetUniformLocation(program,"cmi_LodPass"),2);GL20.glUniform1i(GL20.glGetUniformLocation(program,"uMode"),1);GL20.glUniform1i(GL20.glGetUniformLocation(program,"uSprite"),1);
   for(String name:new String[]{"ModelViewMat","cmi_LodInverseView"})GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program,name),false,new Matrix4f().get(new float[16]));
   GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program,"ProjMat"),false,new Matrix4f().perspective(1f,1f,.05f,8192).get(new float[16]));
   GL13.glActiveTexture(GL13.GL_TEXTURE1);GL11.glBindTexture(GL11.GL_TEXTURE_2D,atlas);bind(0,lod.records);bind(1,lod.order);GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER,lod.commands);
   GL11.glDisable(GL11.GL_BLEND);GL11.glDisable(GL11.GL_CULL_FACE);GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthMask(true);GL30.glClearBufferfv(GL11.GL_DEPTH,0,new float[]{1});GL30.glClearBufferfv(GL11.GL_COLOR,0,new float[]{0,0,0,0});
   GL40.glDrawArraysIndirect(GL11.GL_TRIANGLES,20L);var pixels=bytes(16*16*4);GL11.glReadPixels(0,0,16,16,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
   for(int offset=0;offset<pixels.capacity();offset++)check(pixels.get(offset)==0,"Subpixel square was enlarged to a sample");
   check(read(lod.commands,40).getInt(24)==1,"Subpixel member instance was dropped");
   GL20.glUseProgram(0);for(int id:new int[]{color,depth,atlas})GL11.glDeleteTextures(id);GL30.glDeleteFramebuffers(fbo);GL30.glDeleteVertexArrays(vao);deleteProgram(program);
  }
 }
 static int texture(int format){int t=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,t);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,format,16,16,0,format==GL30.GL_DEPTH_COMPONENT32F?GL11.GL_DEPTH_COMPONENT:GL11.GL_RGBA,GL11.GL_FLOAT,0L);return t;}
 static void target()throws Exception{
  com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
  int render=graphics("storm_lod.vsh","model.fsh"),composite=graphics("storm_lod_composite.vsh","storm_lod_composite.fsh");
  var field=ParticlePrograms.class.getDeclaredField("lodComposite");field.setAccessible(true);field.setInt(ParticlePrograms.INSTANCE,composite);
  var type=Class.forName("com.iridium126.createmanaindustry.client.particles.engine.StormLodTarget");var constructor=type.getDeclaredConstructor();constructor.setAccessible(true);Object target=constructor.newInstance();
  var draw=type.getDeclaredMethod("draw",org.joml.Matrix4fc.class,org.joml.Matrix4fc.class,Runnable.class);draw.setAccessible(true);
  int fbo=GL30.glGenFramebuffers(),color=texture(GL30.GL_RGBA16F),metadata=texture(GL30.GL_RGBA32F),depth=texture(GL30.GL_DEPTH_COMPONENT32F),vao=GL30.glGenVertexArrays();
  GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,color,0);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT1,GL11.GL_TEXTURE_2D,metadata,0);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_DEPTH_ATTACHMENT,GL11.GL_TEXTURE_2D,depth,0);GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0,GL30.GL_COLOR_ATTACHMENT1});check(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)==GL30.GL_FRAMEBUFFER_COMPLETE,"Source framebuffer");
  GL30.glBindVertexArray(vao);GL11.glViewport(0,0,16,16);
  int geometry=GpuShaderCompiler.graphics("MRT probe","#version 450 core\nuniform float z;uniform mat4 projection;void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2)*2.-1.;vec4 hit=projection*vec4(0,0,z,1);gl_Position=vec4(p*hit.w,hit.z,hit.w);}","#version 450 core\nlayout(location=0)out vec4 color;layout(location=1)out vec4 metadata;void main(){color=vec4(.2,.7,.9,1);metadata=vec4(.125,.25,.5,.75);}");
  Matrix4f scene=new Matrix4f().perspective(1f,1f,.05f,128),extended=StormLodGpu.extended(scene,8192);
  for(float distance:new float[]{40,3000}){
  Runnable probe=()->{GL20.glUseProgram(geometry);GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(geometry,"projection"),false,extended.get(new float[16]));GL20.glUniform1f(GL20.glGetUniformLocation(geometry,"z"),-distance);GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthFunc(GL11.GL_LESS);GL11.glDepthMask(true);GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);};
  for(boolean obstructed:new boolean[]{false,true}){
   GL30.glClearBufferfv(GL11.GL_DEPTH,0,new float[]{obstructed?.5f:1});GL30.glClearBufferfv(GL11.GL_COLOR,0,new float[]{0,0,0,1});GL30.glClearBufferfv(GL11.GL_COLOR,1,new float[]{0,0,0,0});
   draw.invoke(target,scene,extended,probe);check(GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)==fbo,"Target did not restore framebuffer");
   FloatBuffer pixel=org.lwjgl.BufferUtils.createFloatBuffer(4);GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);GL11.glReadPixels(8,8,1,1,GL11.GL_RGBA,GL11.GL_FLOAT,pixel);
   check(Math.abs(pixel.get(0)-(obstructed?0:.2))<.001,"Far visibility or foreground obstruction");
   GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT1);GL11.glReadPixels(8,8,1,1,GL11.GL_RGBA,GL11.GL_FLOAT,pixel);check(Math.abs(pixel.get(3)-(obstructed?0:.75))<.0001,"Native MRT metadata was changed");
   GL11.glReadPixels(8,8,1,1,GL11.GL_DEPTH_COMPONENT,GL11.GL_FLOAT,pixel);check(obstructed?pixel.get(0)==.5f:distance>128?pixel.get(0)==1f:pixel.get(0)>0&&pixel.get(0)<1,"Mapped or unrepresentable main depth is wrong");
  }
  }
  ((AutoCloseable)target).close();field.setInt(ParticlePrograms.INSTANCE,0);for(int t:new int[]{color,metadata,depth})GL11.glDeleteTextures(t);GL30.glDeleteFramebuffers(fbo);GL30.glDeleteVertexArrays(vao);for(int p:new int[]{render,composite,geometry})deleteProgram(p);
 }
 public static void main(String[] args)throws Exception{
  GLFWErrorCallback.createPrint(System.err).set();check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(64,64,"Storm LOD validation",0,0);
  try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();System.out.println(GL11.glGetString(GL11.GL_RENDERER));members();nearCoverage();check(GL11.glGetError()==0,"Member GL error");target();hardCutoff();projection();if(Arrays.asList(args).contains("--benchmark")){computeBenchmark();residentBenchmark();renderBenchmark();}check(GL11.glGetError()==0,"Final GL error");System.out.println("Storm LOD GPU: "+checks+" assertions passed");}finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
 }
}
