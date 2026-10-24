import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.particles.engine.ParticlePrograms;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler;
import java.util.*;
import java.util.function.Function;
import java.nio.file.*;
import java.lang.reflect.*;

public final class ParticleShaderStartupGpuValidation extends StarfallGpuValidation {
 static final String COLD_VARIANT=Long.toUnsignedString(System.nanoTime());
 static Set<Integer> programs(ParticlePrograms owner)throws Exception{
  var result=new HashSet<Integer>();for(var field:ParticlePrograms.class.getDeclaredFields())if(field.getType()==int.class&&!Modifier.isStatic(field.getModifiers())){field.setAccessible(true);int value=field.getInt(owner);check(value!=0&&GL20.glIsProgram(value),"Missing active program "+field.getName());result.add(value);}return result;
 }
 static boolean advance(ParticlePrograms owner,Function<String,String> loader)throws Exception{
  var method=ParticlePrograms.class.getDeclaredMethod("rebuildWithSources",Function.class);method.setAccessible(true);return (boolean)method.invoke(owner,loader);
 }
 static Function<String,String> loader(String mode,boolean cold){
  return path->{String name=path.substring("shaders/particles/".length()),body=source(name);if(cold&&name.equals("update.comp"))body="#define CMI_STARTUP_ASYNC_VALIDATION_"+COLD_VARIANT+"\n"+body;if(name.equals("reset.comp")){if(mode.equals("invalid"))body+="\ninvalid shader syntax\n";else body+="\n#define CMI_STARTUP_REVISION_"+mode+"\n";}return body;};
 }
 static int finish(ParticlePrograms owner,Function<String,String> loader,Set<Integer> active,String label,List<String> rows)throws Exception{
  long begin=System.nanoTime(),logged=begin;double maximum=0;int frames=0,swaps=0;
  while(owner.needsRebuild()){
   long tick=System.nanoTime();boolean changed=advance(owner,loader);double elapsed=(System.nanoTime()-tick)/1e6;maximum=Math.max(maximum,elapsed);rows.add(label+","+frames+","+elapsed);frames++;if(changed)swaps++;else if(!active.isEmpty())check(owner.ready()&&programs(owner).equals(active),"Candidate changed the active programs before its atomic publication");
   GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);GLFW.glfwPollEvents();check(GL11.glGetError()==0,"Compilation damaged current GL state");
   if(System.nanoTime()-logged>5_000_000_000L){System.out.println(label+" responsive frames="+frames+" maxAdvanceMs="+maximum);logged=System.nanoTime();}
   check(System.nanoTime()-begin<900_000_000_000L,"Compilation did not complete within fifteen minutes");Thread.sleep(2);
  }
  System.out.println(label+" finished frames="+frames+" swaps="+swaps+" wallMs="+(System.nanoTime()-begin)/1e6+" maxAdvanceMs="+maximum);check(maximum<500,"A shader readiness poll blocked the render thread for half a second");
  return swaps;
 }
 static Object field(Object owner,String name)throws Exception{var field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
 static void cancelLinked(ParticlePrograms owner,Function<String,String> original,boolean cold,Set<Integer> active)throws Exception{
  owner.requestRebuild();Object pending,current=null;long begin=System.nanoTime();
  while(true){check(!advance(owner,loader("cancelLinked",cold)),"Cancelled candidate was published");pending=field(owner,"pendingReload");current=field(pending,"current");if(current!=null&&(boolean)field(current,"linked"))break;check(System.nanoTime()-begin<30_000_000_000L,"Candidate did not reach link submission");GLFW.glfwPollEvents();Thread.sleep(2);}
  int program=(int)field(current,"program");int[] shaders=((int[])field(current,"shaders")).clone();
  long tick=System.nanoTime();owner.requestRebuild();check(!advance(owner,original),"Reversion published a candidate");check((System.nanoTime()-tick)/1e6<500,"Cancelling an in-flight link blocked the render thread");
  check(field(owner,"pendingReload")==null&&programs(owner).equals(active),"Link cancellation changed the active programs");check(!GL20.glIsProgram(program),"Cancelled link leaked its program");for(int shader:shaders)check(!GL20.glIsShader(shader),"Cancelled link leaked its shader");
 }
 static void rejectedCompilation(GpuShaderCompiler.Compilation compilation)throws Exception{
  int program=0;int[] shaders=null;boolean rejected=false;long begin=System.nanoTime();
  try(compilation){try{while(!compilation.poll()){check(System.nanoTime()-begin<30_000_000_000L,"Invalid shader did not fail");Thread.sleep(2);}}catch(IllegalStateException expected){rejected=true;}program=(int)field(compilation,"program");shaders=((int[])field(compilation,"shaders")).clone();}
  check(rejected,"Invalid shader was accepted");if(program!=0)check(!GL20.glIsProgram(program),"Failed compilation leaked its program");for(int shader:shaders)if(shader!=0)check(!GL20.glIsShader(shader),"Failed compilation leaked its shader");
 }
 static void lifecycle(boolean cold)throws Exception{
  if(cold)System.out.println("Cold update source variant="+COLD_VARIANT);
  var rows=new ArrayList<String>();rows.add("case,frame,advanceMilliseconds");int previous=GL11.glGetInteger(ARBParallelShaderCompile.GL_MAX_SHADER_COMPILER_THREADS_ARB);
  var owner=new ParticlePrograms();try{
   check(finish(owner,loader("invalid",cold),Set.of(),"failedInitial",rows)==0&&!owner.ready(),"Failed initial compilation was published");owner.requestRebuild();
   var original=loader("base",cold);check(finish(owner,original,Set.of(),"initial",rows)==1,"Initial compilation was not published exactly once");check(owner.ready(),"Background initial compilation did not publish a complete set");var first=programs(owner);
   owner.requestRebuild();check(!advance(owner,original)&&!owner.needsRebuild()&&programs(owner).equals(first),"Identical sources recompiled or invalidated active programs");
   owner.requestRebuild();advance(owner,loader("superseded",cold));var pending=ParticlePrograms.class.getDeclaredField("pendingReload");pending.setAccessible(true);Object before=pending.get(owner);owner.requestRebuild();advance(owner,loader("superseded",cold));check(pending.get(owner)==before,"Identical pending reload restarted compilation");
   owner.requestRebuild();check(!advance(owner,original)&&pending.get(owner)==null&&programs(owner).equals(first),"Resource reversion did not cancel its stale candidate");
   cancelLinked(owner,original,cold,first);
   owner.requestRebuild();check(!advance(owner,path->null)&&!owner.needsRebuild()&&programs(owner).equals(first),"Missing resource did not preserve the active set");
   owner.requestRebuild();check(finish(owner,loader("invalid",cold),first,"failure",rows)==0,"Failed candidate was published");check(owner.ready()&&programs(owner).equals(first),"Failed candidate destroyed active programs");
   owner.requestRebuild();check(finish(owner,loader("replacement",cold),first,"replacement",rows)==1,"Replacement was not published exactly once");var replacement=programs(owner);check(!replacement.equals(first),"Changed sources did not publish new programs");for(int id:first)check(!GL20.glIsProgram(id),"Replaced program leaked");
   owner.requestRebuild();advance(owner,loader("closing",cold));owner.delete();check(!owner.ready()&&pending.get(owner)==null,"Close retained pending GL programs");for(int id:replacement)check(!GL20.glIsProgram(id),"Shutdown leaked an active program");
   check(GL11.glGetInteger(ARBParallelShaderCompile.GL_MAX_SHADER_COMPILER_THREADS_ARB)==previous,"Compiler thread hint was not restored");
  }finally{owner.delete();}
  try(var compilation=GpuShaderCompiler.beginCompute("cancelled probe","#version 450 core\nlayout(local_size_x=1)in;void main(){}")){compilation.close();boolean rejected=false;try{compilation.poll();}catch(IllegalStateException expected){rejected=true;}check(rejected,"Closed compilation accessed deleted GL names");}
  rejectedCompilation(GpuShaderCompiler.beginCompute("compile failure","#version 450 core\ninvalid shader syntax"));
  rejectedCompilation(GpuShaderCompiler.beginGraphics("link failure","#version 450 core\nout vec3 mismatch;void main(){mismatch=vec3(1);gl_Position=vec4(0,0,0,1);}","#version 450 core\nin vec4 mismatch;out vec4 color;void main(){color=mismatch;}"));
  Path directory=Path.of(System.getProperty("cmi.benchmark.output","build/benchmarks/current"));Files.createDirectories(directory);Files.write(directory.resolve("particle-shader-startup.csv"),rows);
 }
 static void probe(String name)throws Exception{
  String text="#version 450 core\n#define CMI_STARTUP_PROBE_"+COLD_VARIANT+"\n"+ParticlePrograms.commonPrelude()+source(name);
  int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);GL20.glShaderSource(shader,text);long begin=System.nanoTime();System.out.println("SUBMIT compile "+name);System.out.flush();GL20.glCompileShader(shader);System.out.println("compile submit ms="+(System.nanoTime()-begin)/1e6);waitShader(shader,false);check(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)!=0,GL20.glGetShaderInfoLog(shader));
  int program=GL20.glCreateProgram();GL20.glAttachShader(program,shader);begin=System.nanoTime();System.out.println("SUBMIT link "+name);System.out.flush();GL20.glLinkProgram(program);System.out.println("link submit ms="+(System.nanoTime()-begin)/1e6);waitShader(program,true);check(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(program));GL20.glDeleteShader(shader);deleteProgram(program);
 }
 static void waitShader(int object,boolean program)throws Exception{
  long begin=System.nanoTime(),logged=begin;double maximum=0;int frames=0;
  while(true){long tick=System.nanoTime();boolean complete=program?GL20.glGetProgrami(object,ARBParallelShaderCompile.GL_COMPLETION_STATUS_ARB)!=0:GL20.glGetShaderi(object,ARBParallelShaderCompile.GL_COMPLETION_STATUS_ARB)!=0;GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);GLFW.glfwPollEvents();maximum=Math.max(maximum,(System.nanoTime()-tick)/1e6);frames++;if(complete)break;if(System.nanoTime()-logged>5_000_000_000L){System.out.println("still compiling; responsive frames="+frames+" maxPollMs="+maximum);logged=System.nanoTime();}Thread.sleep(2);}
  System.out.println("COMPLETE waitMs="+(System.nanoTime()-begin)/1e6+" frames="+frames+" maxPollMs="+maximum);
 }
 public static void main(String[] args)throws Exception{
  GLFWErrorCallback.createPrint(System.err).set();check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(64,64,"Shader startup probe",0,0);try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();System.out.println(GL11.glGetString(GL11.GL_RENDERER));System.out.println("parallel="+GL.getCapabilities().GL_ARB_parallel_shader_compile);check(GL.getCapabilities().GL_ARB_parallel_shader_compile,"Parallel shader compile unavailable");if(Arrays.asList(args).contains("--probe")){ARBParallelShaderCompile.glMaxShaderCompilerThreadsARB(2);for(String name:new String[]{"update.comp","dome_contacts.comp","dome_path.comp"})probe(name);}else lifecycle(Arrays.asList(args).contains("--cold"));System.out.println("Particle startup GPU: "+checks+" assertions passed");}finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
 }
}
