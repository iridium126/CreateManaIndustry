import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.particles.engine.hardware.*;

/** Measures all state staging/COPY/ownership/waits/consumption in the complete
 * synthetic simulation chain. No claim of actual terrain overlap or FPS. */
public final class R7CopyChainGpu extends ShaderEfficiencyUpdateGpuValidation {
    public static void main(String[] args)throws Exception{
        Path out=Path.of(System.getProperty("cmi.benchmark.output"));Files.createDirectories(out);check(!Files.exists(out.resolve("samples.csv")),"fresh evidence");check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(32,32,"R7 state COPY full chain",0,0);check(window!=0,"context");GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();HardwareNative.load(Path.of(".gradle/hardware-offload/build"));var field=HardwareBackendRuntime.class.getDeclaredField("copyStage");field.setAccessible(true);var runtime=HardwareBackendRuntime.INSTANCE;var differences=new ArrayList<String>();boolean passed=false;
        try(var programs=Programs.load(Path.of("src/main/resources").toAbsolutePath().toString());var stage=new HardwareCopyStage(32<<20);var raw=Files.newBufferedWriter(out.resolve("samples.csv"))){
            raw.write("scene,comparison,pair,side,path,gpu_ms,cpu_submit_ms\n");
            for(String scene:new String[]{"starfall","dome_attack"})try(var frame=new Frame(1000,1,scene,programs,programs)){
                frame.measureGridPreparation=true;field.set(runtime,null);frame.run(programs,true);var expected=R7DomeGpu.semantic(frame.snapshot());frame.run(programs,true);try{equal(expected,R7DomeGpu.semantic(frame.snapshot()),scene+" production AA");}catch(AssertionError e){differences.add(e.toString());}field.set(runtime,stage);frame.run(programs,true);var actual=R7DomeGpu.semantic(frame.snapshot());try{equal(expected,actual,scene+" copied state");}catch(AssertionError e){differences.add(e.toString());}NeighborCompleteUpdateGpuValidation.invariants(expected,actual,scene);
                for(int comparison=0;comparison<3;comparison++)for(int pair=-8;pair<64;pair++)for(int turn=0;turn<2;turn++){
                    int side=(pair+turn)&1;boolean use=comparison==1||comparison==2&&side==1;field.set(runtime,use?stage:null);Result timing=frame.run(programs,true);if(pair>=0)raw.write(scene+","+(comparison==0?"AA_production":comparison==1?"AA_copy":"AB_production_copy")+","+pair+","+side+","+(use?"copy":"production")+","+timing.gpu()+","+timing.cpu()+"\n");
                }
            }field.set(runtime,null);check(GL11.glGetError()==0,"COPY complete chain GL state");passed=true;
        }finally{field.set(runtime,null);long deadline=System.nanoTime()+10_000_000_000L;while(HardwareCopyStage.retainedStages()>0){HardwareCopyStage.pollRetirement();check(System.nanoTime()<deadline,"COPY chain retirement");java.util.concurrent.locks.LockSupport.parkNanos(100_000);}Files.writeString(out.resolve("metadata.json"),new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("passed",passed,"checks",checks,"process_id",ProcessHandle.current().pid(),"samples",64,"scope","complete synthetic simulation and all COPY staging/wait/consume costs; no real-client terrain concurrency or FPS","production_atomic_order_differences",differences))+"\n");GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
