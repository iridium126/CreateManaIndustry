import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.particles.engine.ParticlePrograms;
import com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers;

/** First nonempty invocation of the exact cold source variants, in a fresh context.
 * Relinking is reported separately: the cold compiler test previously submitted zero groups.
 * Compute uses an empty live population but executes one real workgroup; graphics draws one instance.
 */
public class ShaderFirstExecutionValidation extends StarfallGpuValidation {
 static void integer(int p,String name,int value){GL41.glProgramUniform1i(p,GL20.glGetUniformLocation(p,name),value);}
 static void scalar(int p,String name,float value){GL41.glProgramUniform1f(p,GL20.glGetUniformLocation(p,name),value);}
 static Map<String,String> specifications()throws Exception{
  var field=ParticlePrograms.class.getDeclaredField("SPECIFICATIONS");field.setAccessible(true);var result=new LinkedHashMap<String,String>();
  for(Object spec:(List<?>)field.get(null)){var v=spec.getClass().getDeclaredMethod("vertex");var f=spec.getClass().getDeclaredMethod("fragment");v.setAccessible(true);f.setAccessible(true);result.put((String)v.invoke(spec),(String)f.invoke(spec));}return result;
 }
 static int program(String vertex,String fragment,String key)throws Exception{
  int program=GL20.glCreateProgram();var shaders=new ArrayList<Integer>();
  for(String name:fragment==null?new String[]{vertex}:new String[]{vertex,fragment}){
   int shader=GL20.glCreateShader(fragment==null?GL43.GL_COMPUTE_SHADER:name.equals(vertex)?GL20.GL_VERTEX_SHADER:GL20.GL_FRAGMENT_SHADER);shaders.add(shader);
   GL20.glShaderSource(shader,"#version 450 core\n#define CMI_COLD_"+key+"\n"+ParticlePrograms.commonPrelude()+source(name));GL20.glCompileShader(shader);ShaderColdCompileValidation.waitFor(shader,false);check(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)!=0,name+GL20.glGetShaderInfoLog(shader));GL20.glAttachShader(program,shader);
  }
  GL20.glLinkProgram(program);ShaderColdCompileValidation.waitFor(program,true);check(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(program));for(int shader:shaders)GL20.glDeleteShader(shader);return program;
 }
 static class Invocation implements AutoCloseable{
  final int zero=GL15.glGenBuffers(),pool=buffer(bytes(64).putFloat(12,1).putFloat(32,1).putFloat(36,1).putFloat(40,1).putFloat(48,.2f).putFloat(52,20)),header=buffer(headers()),geometry,vao=GL30.glGenVertexArrays();
  final List<Integer> textures=new ArrayList<>();
  Invocation(){
   GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,zero);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,Math.max(64L*1024*1024,StarfallGpuBuffers.STATE_WORDS*4L),GL15.GL_DYNAMIC_COPY);clear();
   var vertices=bytes(3*7*4);float[][] points={{-8,16,0},{8,16,0},{0,0,0}};for(int i=0;i<3;i++)for(int axis=0;axis<3;axis++)vertices.putFloat((i*7+axis)*4,points[i][axis]);geometry=buffer(vertices);
   GL30.glBindVertexArray(vao);
  }
  void clear(){GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,zero);GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(IntBuffer)null);GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT);}
  int texture(int target){int t=GL11.glGenTextures();textures.add(t);GL11.glBindTexture(target,t);if(target!=GL31.GL_TEXTURE_BUFFER){GL11.glTexParameteri(target,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(target,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);}return t;}
  void samplers(int p){
   int count=GL20.glGetProgrami(p,GL20.GL_ACTIVE_UNIFORMS);var size=org.lwjgl.BufferUtils.createIntBuffer(1);var type=org.lwjgl.BufferUtils.createIntBuffer(1);var units=new HashMap<Integer,Integer>();
   for(int i=0;i<count;i++){
    String name=GL20.glGetActiveUniform(p,i,size,type);int kind=type.get(0),target=switch(kind){case GL20.GL_SAMPLER_2D->GL11.GL_TEXTURE_2D;case GL20.GL_SAMPLER_3D,GL30.GL_UNSIGNED_INT_SAMPLER_3D->GL12.GL_TEXTURE_3D;case GL31.GL_SAMPLER_BUFFER,GL31.GL_INT_SAMPLER_BUFFER,GL31.GL_UNSIGNED_INT_SAMPLER_BUFFER->GL31.GL_TEXTURE_BUFFER;default->0;};if(target==0)continue;
    Integer unit=units.get(kind);if(unit==null){unit=units.size();units.put(kind,unit);GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);int t=texture(target);
     if(target==GL31.GL_TEXTURE_BUFFER)GL31.glTexBuffer(target,kind==GL31.GL_INT_SAMPLER_BUFFER?GL30.GL_RGBA32I:kind==GL31.GL_UNSIGNED_INT_SAMPLER_BUFFER?GL30.GL_R32UI:GL30.GL_R32F,zero);
     else if(target==GL12.GL_TEXTURE_3D)GL12.glTexImage3D(target,0,kind==GL30.GL_UNSIGNED_INT_SAMPLER_3D?GL30.GL_R32UI:GL30.GL_R32F,1,1,1,0,kind==GL30.GL_UNSIGNED_INT_SAMPLER_3D?GL30.GL_RED_INTEGER:GL11.GL_RED,kind==GL30.GL_UNSIGNED_INT_SAMPLER_3D?GL11.GL_UNSIGNED_INT:GL11.GL_FLOAT,bytes(4));
     else GL11.glTexImage2D(target,0,GL11.GL_RGBA8,1,1,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,bytes(4).putInt(0,-1));
    }integer(p,name,unit);
   }GL13.glActiveTexture(GL13.GL_TEXTURE0);
  }
  void bind(int p,boolean graphics){
   clear();for(int slot=0;slot<32;slot++)ModelPopulationGpuValidation.bind(slot,zero);samplers(p);
   if(graphics){ModelPopulationGpuValidation.bind(1,pool);ModelPopulationGpuValidation.bind(5,header);ModelPopulationGpuValidation.bind(12,geometry);GL30.glBindVertexArray(vao);float[] identity=new org.joml.Matrix4f().get(new float[16]);for(String name:new String[]{"ModelViewMat","ProjMat"})GL41.glProgramUniformMatrix4fv(p,GL20.glGetUniformLocation(p,name),false,identity);GL41.glProgramUniform3f(p,GL20.glGetUniformLocation(p,"uCamRight"),1,0,0);GL41.glProgramUniform3f(p,GL20.glGetUniformLocation(p,"uCamUp"),0,1,0);scalar(p,"uFadeDist",100);scalar(p,"uAtlasCols",1);scalar(p,"uAtlasRows",1);}
   GL11.glViewport(0,0,64,64);GL11.glFinish();check(GL11.glGetError()==0,"First invocation setup");
  }
  public void close(){for(int t:textures)GL11.glDeleteTextures(t);for(int b:new int[]{zero,pool,header,geometry})GL15.glDeleteBuffers(b);GL30.glDeleteVertexArrays(vao);}
 }
 public static void main(String[] args)throws Exception{
  GLFWErrorCallback.createPrint(System.err).set();check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(64,64,"First shader execution",0,0);
  try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();ARBParallelShaderCompile.glMaxShaderCompilerThreadsARB(2);var specs=specifications();var rows=new ArrayList<String>();rows.add("round,program,source_key,relink_wall_ms,first_workgroup_or_draw_wall_ms");Path input=Path.of(System.getProperty("cmi.compiler.csv"));Path output=Path.of(System.getProperty("cmi.benchmark.output"));Files.createDirectories(output);
   try(var invocation=new Invocation()){
    for(String row:Files.readAllLines(input).subList(1,Files.readAllLines(input).size())){String[] columns=row.split(",",-1);if(!columns[1].equals("true"))continue;String name=columns[2],fragment=specs.get(name),key=columns[7];long begin=System.nanoTime();int program=program(name,fragment,key);double relink=(System.nanoTime()-begin)/1e6;
     try{invocation.bind(program,fragment!=null);begin=System.nanoTime();GL20.glUseProgram(program);if(fragment==null)GL43.glDispatchCompute(1,1,1);else GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES,0,name.equals("model.vsh")?3:6,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);GL11.glFinish();double execute=(System.nanoTime()-begin)/1e6;check(GL11.glGetError()==0,"First invocation "+name);rows.add(columns[0]+","+name+","+key+","+relink+","+execute);Files.write(output.resolve("shader-first-execution.csv"),rows);System.out.println("FIRST "+name+" relinkMs="+relink+" executeMs="+execute);}finally{GL20.glUseProgram(0);GL20.glDeleteProgram(program);}
    }
   }
  }finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
 }
}
