import java.nio.file.*;
import java.util.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;

/** Full production chain screen for the untruncated streaming reference, not an equivalence claim. */
public final class NeighborCompleteUpdateGpuValidation extends ShaderEfficiencyUpdateGpuValidation {
    static void invariants(Snapshot baseline,Snapshot candidate,String scene){
        check(baseline.particles().keySet().equals(candidate.particles().keySet()),"stable population identities");
        for(int id:candidate.particles().keySet()){
            int[] a=baseline.particles().get(id),b=candidate.particles().get(id);
            for(int k:new int[]{0,1,2,4,5,6,7,8,9,10,11,12,13})check(Float.isFinite(Float.intBitsToFloat(b[k])),"finite state");
            // These one-frame fixtures have identical input HP/damage; legal trajectory differences cannot change that.
            check(a[13]==b[13],"one-frame HP/death rule");check(a[15]==b[15],"emitter/material ownership");
            if(scene.equals("ordinary")&&Float.intBitsToFloat(b[13])>0){
                double speed=0;for(int k=4;k<=6;k++){double v=Float.intBitsToFloat(b[k]);speed+=v*v;}
                check(speed<=6.0001*6.0001,"ordinary speed budget");
            }
        }
    }
    public static void main(String[] args)throws Exception{
        Path out=Path.of(System.getProperty("cmi.benchmark.output"));Files.createDirectories(out);check(!Files.exists(out.resolve("samples.csv")),"Never overwrite earlier samples");
        check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);long window=GLFW.glfwCreateWindow(32,32,"complete-query full-chain screen",0,0);check(window!=0,"context");GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
        var oldAa=new ArrayList<String>();
        try(var production=Programs.load(System.getProperty("cmi.neighbor.baseline"));var reference=Programs.load(System.getProperty("cmi.neighbor.reference"));var raw=Files.newBufferedWriter(out.resolve("samples.csv"))){
            raw.write("count,mode,scene,chain,sample,baseline_ms,candidate_ms,baseline_cpu_ms,candidate_cpu_ms\n");
            for(String scene:System.getProperty("cmi.neighbor.scenes","ordinary,starfall,helix,dome_gather,dome_attack,corpse,recovery").split(","))for(String size:System.getProperty("cmi.neighbor.counts","1000,10000,65536,131072").split(",")){
                int n=Integer.parseInt(size);try(var frame=new Frame(n,1,scene,production,reference)){
                    frame.measureGridPreparation=true;
                    frame.run(production,true);Snapshot before=frame.snapshot();frame.run(production,true);
                    try{equal(before,frame.snapshot(),n+","+scene+" production A/A");}catch(AssertionError failure){oldAa.add(failure.getMessage());}
                    boolean aa=Boolean.getBoolean("cmi.neighbor.aa");Programs candidate=aa?production:reference;
                    frame.run(candidate,true);invariants(before,frame.snapshot(),scene);
                    for(int warm=0;warm<16;warm++)frame.run(warm%2==0?production:candidate,true);
                    for(int pair=0;pair<64;pair++){
                        Result a,b;if(pair%2==0){a=frame.run(production,true);b=frame.run(candidate,true);}else{b=frame.run(candidate,true);a=frame.run(production,true);}
                        raw.write(n+",1,"+scene+",full,"+pair+","+a.gpu()+","+b.gpu()+","+a.cpu()+","+b.cpu()+"\n");
                    }raw.flush();System.out.println("Full-chain preliminary screen n="+n+" "+scene);
                }
            }
            Files.writeString(out.resolve("preliminary-only.txt"),"No multi-frame event/contact/visual qualification. Do not replace production based on this screen. checks="+checks+"\n");
        }finally{Files.write(out.resolve("production-aa-differences.txt"),oldAa);GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
