import com.iridium126.createmanaindustry.client.gpu.profile.GpuTimestampRing;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import java.util.*;
public class ModelDrawTelemetryGpuValidation {
    public static void main(String[] args){
        if(!GLFW.glfwInit())throw new AssertionError("GLFW");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);
        long window=GLFW.glfwCreateWindow(64,64,"Tagged GPU queries",0,0);
        if(window==0)throw new AssertionError("GL context");
        try{
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();var seen=new ArrayList<String>();
            try(var ring=new GpuTimestampRing(2,null,(frame,generation,ms)->{if(ms<0)throw new AssertionError("negative time");seen.add(frame+":"+generation);})){
                for(long frame=10;frame<12;frame++){if(!ring.begin(false,frame,frame+100))throw new AssertionError("missing slot");ring.end(true,false);}
                if(ring.begin(false,99,99))throw new AssertionError("Overwrote pending query");
                GL11.glFinish();ring.poll();
                if(!seen.equals(List.of("10:110","11:111")))throw new AssertionError("Submission tags lost: "+seen);
                ring.begin(false,12,112);ring.end(false,false);GL11.glFinish();ring.poll();
                if(seen.size()!=2)throw new AssertionError("Aborted sample published");
                ring.begin(false,13,113);ring.end(true,false);GL11.glFinish();ring.poll();
                if(!seen.get(2).equals("13:113"))throw new AssertionError("Reused slot has stale tag");
            }
            try(var pass=new com.iridium126.createmanaindustry.client.gpu.profile.GpuPassTelemetry()){
                pass.begin(true,true,10,110);pass.end();pass.begin(true,true,11,111);pass.end();GL11.glFinish();pass.poll();
                if(!pass.report(10,11,true).contains("samples CPU/GPU=2/2"))throw new AssertionError("Current window samples lost");
                if(!pass.report(11,11,true).contains("samples CPU/GPU=1/1"))throw new AssertionError("Frame window boundary");
                String inactive=pass.report(100,339,true);
                if(!inactive.contains("inactive; current window unmeasured; last GPU frame=11")||inactive.contains("p50/p95"))throw new AssertionError("Stale percentiles: "+inactive);
                pass.configure(false,false);if(!pass.report(10,11,true).contains("samples CPU/GPU=2/2"))throw new AssertionError("Stopped capture changed");
                pass.configure(true,true);if(!pass.report(100,339,true).startsWith("unmeasured"))throw new AssertionError("Reload retained old samples");
            }
            if(GL11.glGetError()!=0)throw new AssertionError("GL error");
            System.out.println("MODEL draw telemetry GPU: tags, saturation, abort and slot reuse passed");
        }finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
