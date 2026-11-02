import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.opengl.*;
import org.lwjgl.glfw.*;
import com.iridium126.createmanaindustry.client.particles.engine.*;
import com.iridium126.createmanaindustry.client.packages.render.PackageDrawCulling;

/** Experimental workgroup reservation. Never loaded by the client. */
public class ShadowCollectGpuValidation extends ShadowQualityGpuValidation {
    static String batched(String name){
        String s=source(name);if(!name.equals("model_shadow_collect.comp"))return s;
        String original=s;
        s=s.replace("void main(){", "shared uint localBody,localCarrier,bodyBase,carrierBase;\nshared uvec2 bodyRows[64],carrierRows[64];\nvoid collectLane(){");
        s=s.replace("atomicAdd(cmd[IDX_CNT_MODELOP],1u)","atomicAdd(localBody,1u)").replace("items[row]=", "bodyRows[row]=");
        s=s.replace("atomicAdd(cmd[IDX_CNT_CARRIER],1u)","atomicAdd(localCarrier,1u)").replace("if(carrier<(uCarrierLimit==0u?uint(CARRIER_CAP):uCarrierLimit))items[uCarrierBase+carrier]=", "carrierRows[carrier]=");
        check(!s.equals(original)&&!s.contains("items[row]=")&&!s.contains("items[uCarrierBase+carrier]="),"collector rewrite drift");
        return s+"\nvoid main(){uint lane=gl_LocalInvocationID.x;if(lane==0u){localBody=0u;localCarrier=0u;}barrier();collectLane();barrier();if(lane==0u){bodyBase=atomicAdd(cmd[IDX_CNT_MODELOP],localBody);carrierBase=atomicAdd(cmd[IDX_CNT_CARRIER],localCarrier);}barrier();if(lane<localBody)items[bodyBase+lane]=bodyRows[lane];if(lane<localCarrier&&carrierBase+lane<(uCarrierLimit==0u?uint(CARRIER_CAP):uCarrierLimit))items[uCarrierBase+carrierBase+lane]=carrierRows[lane];}\n";
    }
    static BitSet slots(ModelShadowGpu g,int base,int n){var bytes=read(g.instances(),(base+n)*8);var seen=new BitSet();for(int i=0;i<n;i++){int slot=bytes.getInt((base+i)*8+4)>>>2;check(!seen.get(slot),"duplicate collection slot");seen.set(slot);}return seen;}
    static void run(){
        for(int n:new int[]{65536,131072})for(String scene:new String[]{"body","carrier","half"}){
            var data=bytes(n*64);for(int i=0;i<n;i++)particle(data,i,i%8-4,0,i/8%8-4,1,1);
            int pool=buffer(data),emit=buffer(headers(2,.3f,scene.equals("carrier"),false)),count=buffer(bytes(16).putInt(0,n));
            try(var a=new ModelShadowGpu(n,ShadowCollectGpuValidation::source);var b=new ModelShadowGpu(n,ShadowCollectGpuValidation::batched)){
                var c=new PackageDrawCulling();c.clear();if(scene.equals("half"))c.set(new float[][]{{1,0,0,0}},1);
                Runnable baseline=()->prepare(a,pool,emit,count,c),candidate=()->prepare(b,pool,emit,count,c);
                baseline.run();candidate.run();int expected=models(a);check(models(b)==expected,"batch count differs");check(slots(a,0,expected).equals(slots(b,0,expected)),"batch identity set differs");
                int carriers=read(a.commands(),commandBytes()).getInt(ParticleBuffers.IDX_CNT_CARRIER*4);check(read(b.commands(),commandBytes()).getInt(ParticleBuffers.IDX_CNT_CARRIER*4)==carriers,"batch carrier count differs");
                if(carriers>0){check(slots(a,n,carriers).cardinality()==carriers&&slots(b,n,carriers).cardinality()==carriers,"carrier truncation invalid");}
                int round=Integer.getInteger("cmi.shadow.round",0);pair("BATCH_AA",n,scene,round,baseline,baseline);pair("BATCH_AB",n,scene,round,baseline,candidate);
            }finally{for(int id:new int[]{pool,emit,count})GL15.glDeleteBuffers(id);}
        }
    }
    public static void main(String[] args)throws Exception{
        GLFWErrorCallback.createPrint(System.err).set();check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(64,64,"Shadow reservation probe",0,0);Files.createDirectories(OUT);
        try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();System.out.println(GL11.glGetString(GL11.GL_RENDERER));run();check(GL11.glGetError()==0,"final GL error");System.out.println("Shadow reservation: "+checks+" assertions passed");}
        finally{Files.write(OUT.resolve("samples.csv"),samples);GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
