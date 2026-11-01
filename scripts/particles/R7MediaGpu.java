import java.nio.file.*;
import com.google.gson.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import com.iridium126.createmanaindustry.client.particles.engine.hardware.HardwareNative;
public final class R7MediaGpu extends ModelPopulationGpuValidation {
    public static void main(String[] args)throws Exception{
        Path out=Path.of(System.getProperty("cmi.benchmark.output"));Files.createDirectories(out);check(!Files.exists(out.resolve("media.json")),"fresh evidence");check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(32,32,"R7 media capabilities",0,0);check(window!=0,"GL context");GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
        try{HardwareNative.load(Path.of(".gradle/hardware-offload/build"));var result=new JsonObject();result.add("hardware",JsonParser.parseString(HardwareNative.probe()));result.add("media",JsonParser.parseString(HardwareNative.mediaProbe()));result.addProperty("scope","actual same-GPU capability queries; no optical flow/codec particle workload or FPS claim");Files.writeString(out.resolve("media.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result)+"\n");}
        finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
