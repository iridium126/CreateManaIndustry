import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.particles.engine.ParticlePrograms;

/** Serial, fresh source-key compilation. Never clears the user's driver cache. */
public final class ShaderColdCompileValidation extends StarfallGpuValidation {
 static boolean shadowsOnly;
 record SourceSpec(String vertex,String fragment){}
 static double ms(long start){return (System.nanoTime()-start)/1e6;}
 static void waitFor(int object,boolean program)throws Exception{
  long start=System.nanoTime(),logged=start;
  while((program?GL20.glGetProgrami(object,ARBParallelShaderCompile.GL_COMPLETION_STATUS_ARB):GL20.glGetShaderi(object,ARBParallelShaderCompile.GL_COMPLETION_STATUS_ARB))==0){
   GLFW.glfwPollEvents();if(System.nanoTime()-logged>5_000_000_000L){System.out.println("compiler pending "+Math.round(ms(start)/1000)+"s");logged=System.nanoTime();}
   check(ms(start)<1800000,"Single program compilation exceeded thirty minutes");Thread.sleep(2);
  }
 }
 static void round(int round,boolean cold,List<String> rows)throws Exception{
  var field=ParticlePrograms.class.getDeclaredField("SPECIFICATIONS");field.setAccessible(true);var production=(List<?>)field.get(null);var specs=new ArrayList<Object>();if(!shadowsOnly)specs.addAll(production);for(String name:List.of("model_shadow_prepare.comp","model_shadow_collect.comp","model_shadow_finish.comp"))specs.add(new SourceSpec(name,null));String key=cold?Long.toUnsignedString(System.nanoTime()):"native";long total=System.nanoTime();
  for(Object spec:specs){var v=spec.getClass().getDeclaredMethod("vertex");v.setAccessible(true);var f=spec.getClass().getDeclaredMethod("fragment");f.setAccessible(true);String vertex=(String)v.invoke(spec),fragment=(String)f.invoke(spec);long began=System.nanoTime();String[] names=fragment==null?new String[]{vertex}:new String[]{vertex,fragment};String[] sources=new String[names.length];
   for(int i=0;i<names.length;i++)sources[i]="#version 450 core\n"+(cold?"#define CMI_COLD_"+key+"\n":"")+ParticlePrograms.commonPrelude()+source(names[i]);double expand=ms(began),compile=0;
   int[] shaders=new int[names.length];int program=GL20.glCreateProgram();try{
    for(int i=0;i<names.length;i++){shaders[i]=GL20.glCreateShader(fragment==null?GL43.GL_COMPUTE_SHADER:i==0?GL20.GL_VERTEX_SHADER:GL20.GL_FRAGMENT_SHADER);GL20.glShaderSource(shaders[i],sources[i]);began=System.nanoTime();GL20.glCompileShader(shaders[i]);waitFor(shaders[i],false);check(GL20.glGetShaderi(shaders[i],GL20.GL_COMPILE_STATUS)!=0,names[i]+GL20.glGetShaderInfoLog(shaders[i]));compile+=ms(began);GL20.glAttachShader(program,shaders[i]);}
    began=System.nanoTime();GL20.glLinkProgram(program);waitFor(program,true);check(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)!=0,vertex+GL20.glGetProgramInfoLog(program));double link=ms(began);
    // Empty submission isolates first-use driver setup; real workload time is measured separately.
    began=System.nanoTime();GL20.glUseProgram(program);if(fragment==null)GL43.glDispatchCompute(0,1,1);GL11.glFinish();double first=ms(began);
    rows.add(round+","+cold+","+vertex+","+expand+","+compile+","+link+","+(fragment==null?first:"")+","+key);System.out.println("COLD "+round+" "+vertex+" compileMs="+compile+" linkMs="+link);
   }finally{GL20.glUseProgram(0);GL20.glDeleteProgram(program);for(int s:shaders)if(s!=0)GL20.glDeleteShader(s);}
   Files.write(Path.of(System.getProperty("cmi.benchmark.output","build/benchmarks/current"),"shader-cold.csv"),rows);
  }
  System.out.println("COMPILER ROUND "+round+" cold="+cold+" totalMs="+ms(total));
 }
 public static void main(String[] args)throws Exception{
  shadowsOnly=Arrays.asList(args).contains("--shadow-only");GLFWErrorCallback.createPrint(System.err).set();check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long w=GLFW.glfwCreateWindow(64,64,"Cold compiler measurement",0,0);
  try{GLFW.glfwMakeContextCurrent(w);GL.createCapabilities();check(GL.getCapabilities().GL_ARB_parallel_shader_compile,"Parallel compilation extension required for measurement");System.out.println(GL11.glGetString(GL11.GL_RENDERER)+" / "+GL11.glGetString(GL11.GL_VERSION));ARBParallelShaderCompile.glMaxShaderCompilerThreadsARB(2);Path out=Path.of(System.getProperty("cmi.benchmark.output","build/benchmarks/current"));Files.createDirectories(out);var rows=new ArrayList<String>();rows.add("round,cold,program,expand_ms,compile_ms,link_ms,first_empty_submit_ms,source_key");if(!Arrays.asList(args).contains("--warm-only"))for(int i=0;i<3;i++)round(i,true,rows);round(3,false,rows);round(4,false,rows);System.out.println("Cold compiler: "+checks+" assertions passed");}finally{GLFW.glfwDestroyWindow(w);GLFW.glfwTerminate();}
 }
}
