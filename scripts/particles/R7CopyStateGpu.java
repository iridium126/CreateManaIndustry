import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers;
import com.iridium126.createmanaindustry.client.particles.engine.hardware.*;

/** Exact state-bank ranges, including untouched gaps and whole-copy fallback. */
public final class R7CopyStateGpu extends ModelPopulationGpuValidation {
    public static void main(String[] args)throws Exception{
        Path out=Path.of(System.getProperty("cmi.benchmark.output"));Files.createDirectories(out);check(!Files.exists(out.resolve("copy-state.json")),"fresh evidence");
        check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(32,32,"R7 state COPY",0,0);check(window!=0,"GL context");GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();HardwareNative.load(Path.of(".gradle/hardware-offload/build"));
        boolean passed=false;int size=StarfallGpuBuffers.STATE_WORDS*4;ByteBuffer seed=bytes(size),initial=bytes(size);Random random=new Random(7101);for(int i=0;i<size;i+=4){seed.putInt(i,random.nextInt());initial.putInt(i,random.nextInt());}int source=buffer(seed),reference=buffer(initial),candidate=buffer(initial);var results=new ArrayList<Object>();
        try(var stage=new HardwareCopyStage(32<<20)){
            long[][] ranges={{StarfallGpuBuffers.DOME_FLOW_STATE*4L,StarfallGpuBuffers.DOME_FLOW_WORDS*4L},{StarfallGpuBuffers.DOME_PATH_BASE*4L,(StarfallGpuBuffers.OUTER_ARRIVALS-StarfallGpuBuffers.DOME_PATH_BASE)*4L},{StarfallGpuBuffers.OUTER_ARRIVALS*4L,131072*4L}};
            for(int round=0;round<4;round++){
                put(reference,initial);put(candidate,initial);
                for(var range:ranges){GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,source);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,reference);GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,range[0],range[0],range[1]);boolean used=stage.copy(source,candidate,range[0],range[0],range[1],round==3?0:1_000_000_000L);if(!used){GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,source);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,candidate);GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,range[0],range[0],range[1]);}results.add(Map.of("round",round,"bytes",range[1],"used_copy",used,"reason",stage.lastReason(),"queue_ms",Double.isFinite(stage.millis())?stage.millis():"unavailable"));}
                check(Arrays.equals(R6CaptureReplayGpu.Capture.array(read(reference,size)),R6CaptureReplayGpu.Capture.array(read(candidate,size))),"whole state bank and untouched gaps exactly match");
            }
            check(!stage.copy(source,candidate,0,0,(32L<<20)+4,0),"capacity selects whole reference");GL11.glFinish();passed=true;
        }finally{GL15.glDeleteBuffers(source);GL15.glDeleteBuffers(reference);GL15.glDeleteBuffers(candidate);long deadline=System.nanoTime()+5_000_000_000L;while(HardwareCopyStage.retainedStages()>0){HardwareCopyStage.pollRetirement();check(System.nanoTime()<deadline,"bounded COPY retirement");java.util.concurrent.locks.LockSupport.parkNanos(100_000);}Files.writeString(out.resolve("copy-state.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("passed",passed,"checks",checks,"state_bytes",size,"scope","actual state-copy ranges and fallback; not client FPS","cases",results))+"\n");GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
