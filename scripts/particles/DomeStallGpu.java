import java.nio.*;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.particles.engine.*;
import com.iridium126.createmanaindustry.client.particles.engine.hardware.*;

/** Full production Dome + forced ordinary spatial fallback, including recovery. */
public final class DomeStallGpu extends ShaderEfficiencyUpdateGpuValidation {
 public static void main(String[] args)throws Exception{
  Path out=Path.of(System.getProperty("cmi.benchmark.output"));Files.createDirectories(out);check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,6);long window=GLFW.glfwCreateWindow(32,32,"Dome fallback recovery",0,0);GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();var report=new LinkedHashMap<String,Object>();var cases=new ArrayList<Object>();report.put("passed",false);
  int update=0,scratch=0,texture=0;
  try(var production=Programs.load(Path.of("src/main/resources").toAbsolutePath().toString())){
   var variant=HardwareUpdateSource.build(ModelPopulationGpuValidation.source("update.comp"),true);check(!variant.source().contains("for(uint j=0u;j<min(prevCounter.live,uCapacity);j++)"),"no exhaustive runtime fallback");update=R7ContinuationGpu.compileVariant(variant.source());var fallback=R7ContinuationGpu.replace(production,update);
   int total=146000;scratch=buffer(bytes(16+total*variant.strideWords()*4));texture=GL11.glGenTextures();GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,texture);GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,GL30.GL_R32UI,scratch);final int image=texture,storage=scratch;
   for(String scene:List.of("dome_gather","ordinary"))try(var frame=new Frame(total,2,scene,production,fallback)){
    var input=read(frame.d.q.pool,total*64);for(int i=0;i<total;i++){boolean member=i<131072&&(i&1)==0;input.putInt(i*64+60,member?2:0).putFloat(i*64+12,member?i/2+1:1).putFloat(i*64+52,member?20:100);}
    frame.d.q.build(input,total);put(frame.d.q.previous,bytes(16).putInt(0,total));frame.d.bindings();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,frame.d.grid);GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,0,ParticleBuffers.GRID_TABLE*4L,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(IntBuffer)null);dispatch(frame.d.gridProgram,(total+63)/64);frame.gridSeed.clear();frame.gridSeed.put(read(frame.d.grid,frame.gridSeed.capacity())).clear();
    frame.stateful=true;frame.measureGridPreparation=false;
    frame.updateDispatch=p->{if(p==production){ui(p.update(),"uModelPass",1);dispatch(p.update(),(total+63)/64);ui(p.update(),"uModelPass",2);dispatch(p.update(),(frame.d.models+63)/64);return;}
     put(storage,bytes(16));GL42.glBindImageTexture(7,image,0,false,0,GL15.GL_READ_WRITE,GL30.GL_R32UI);gen(p.update(),"uR7Generation",frame.inputGeneration);ui(p.update(),"uR7Stage",1);bool(p.update(),"uR7ResultsEnabled",false);ui(p.update(),"uModelPass",1);dispatch(p.update(),(total+63)/64);ui(p.update(),"uModelPass",2);dispatch(p.update(),(frame.d.models+63)/64);ui(p.update(),"uR7Stage",2);dispatch(p.update(),(frame.d.models+63)/64);
    };
    for(int step=0;step<4;step++){var baseline=frame.run(production,true);var expected=R7DomeGpu.semantic(frame.snapshot());var candidate=frame.run(fallback,true);var actual=frame.snapshot();equal(expected,R7DomeGpu.semantic(actual),"production spatial fallback "+scene+" step="+step);NeighborCompleteUpdateGpuValidation.invariants(expected,R7DomeGpu.semantic(actual),scene);cases.add(Map.of("scene",scene,"step",step,"total",total,"members",65536,"dome_members",scene.startsWith("dome")?12288:0,"production_gpu_ms",baseline.gpu(),"fallback_gpu_ms",candidate.gpu()));if(step<3)frame.advance(actual);}
   }
   report.put("passed",true);report.put("scope","synthetic full current GL chain; forced spatial fallback, no native submission or delay in this fixture; native delay/ownership tested separately");
  }finally{if(texture!=0)GL11.glDeleteTextures(texture);if(scratch!=0)GL15.glDeleteBuffers(scratch);if(update!=0)deleteProgram(update);report.put("checks",checks);report.put("cases",cases);Files.writeString(out.resolve("dome-stall.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
 }
}
