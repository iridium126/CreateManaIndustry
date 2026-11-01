import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.particles.engine.DomeBodyContacts;

/** Synthetic full chain. Real schema-1 dispatch replays remain a separate scope. */
public final class R6FullChainGpu extends ShaderEfficiencyUpdateGpuValidation {
    public static void main(String[] args)throws Exception{
        Path out=Path.of(System.getProperty("cmi.benchmark.output"));Files.createDirectories(out);check(!Files.exists(out.resolve("samples.csv")),"fresh output");
        check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(32,32,"R6 full-chain fixture",0,0);check(window!=0,"context");GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
        String variant=System.getProperty("cmi.r6.variant","grid100"),root=System.getProperty("cmi.r6.candidates");boolean aa=Boolean.getBoolean("cmi.r6.aa");int samples=Integer.getInteger("cmi.r6.samples",64);var differences=new ArrayList<String>();var failures=new ArrayList<String>();
        var meta=com.google.gson.JsonParser.parseString(Files.readString(Path.of(root,variant,"variant.json"))).getAsJsonObject();
        try(var production=Programs.load(Path.of(root,"production").toString());var candidate=Programs.load(Path.of(root,aa?"production":variant).toString());var raw=Files.newBufferedWriter(out.resolve("samples.csv"))){
            raw.write("scope,count,mode,scene,pair,baseline_ms,candidate_ms,baseline_cpu_ms,candidate_cpu_ms\n");
            for(String scene:System.getProperty("cmi.r6.scenes","ordinary,dome_gather,dome_attack").split(","))for(String size:System.getProperty("cmi.r6.counts","1000,65536").split(","))for(String modeText:System.getProperty("cmi.r6.modes","1").split(",")){
                int n=Integer.parseInt(size),mode=Integer.parseInt(modeText);
                try(var frame=new Frame(n,mode,scene,production,candidate);var grid=new R6Grid(frame.gridSeed.capacity(),Math.max(1,n),meta.get("buckets").getAsInt(),meta.get("cell").getAsFloat(),!aa&&meta.get("packed").getAsBoolean());var order=new R6IndexOrder(Math.max(1,n),aa?0:variant.contains("space")?1:variant.contains("phase")?2:0)){
                    GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,frame.d.grid);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,grid.bytes,GL15.GL_DYNAMIC_COPY);
                    ByteBuffer population=read(frame.d.q.pop,Frame.size(frame.d.q.pop));frame.resetExtra=()->put(frame.d.q.pop,population);frame.measureGridPreparation=true;
                    frame.d.bindings();frame.stars.bind(candidate.update(),true);order.verify(frame.d.q.pop,frame.d.models,!scene.equals("ordinary"));
                    frame.beforeUpdate=p->{if(p==candidate&&!aa){order.prepare(frame.d.models,!scene.equals("ordinary"));grid.prepare(frame.d.q.pool,frame.d.q.emit,frame.d.grid,n,7,1);grid.configure(p.update(),7,1);}};
                    frame.beforeContacts=p->{if(p==candidate&&!aa&&frame.stars.hasDome()&&frame.stars.domeReady()){grid.prepare(frame.d.output,frame.d.q.emit,frame.d.grid,n,7,2);for(int part:DomeBodyContacts.related(p.contacts()))grid.configure(part,7,2);}};
                    frame.run(production,true);var first=frame.snapshot();frame.run(production,true);try{equal(first,frame.snapshot(),n+","+mode+","+scene+" production AA");}catch(AssertionError failure){differences.add(failure.getMessage());}
                    frame.run(candidate,true);NeighborCompleteUpdateGpuValidation.invariants(first,frame.snapshot(),scene);check(GL11.glGetError()==0,"GL state "+scene);
                    for(int warm=0;warm<(samples==0?0:16);warm++)frame.run((warm&1)==0?production:candidate,true);
                    for(int pair=0;pair<samples;pair++){Result a,b;if((pair&1)==0){a=frame.run(production,true);b=frame.run(candidate,true);}else{b=frame.run(candidate,true);a=frame.run(production,true);}raw.write("synthetic-full-chain,"+n+","+mode+","+scene+","+pair+","+a.gpu()+","+b.gpu()+","+a.cpu()+","+b.cpu()+"\n");}raw.flush();System.out.println("R6 full chain "+variant+" "+n+" mode="+mode+" "+scene);
                }catch(AssertionError|RuntimeException failure){failures.add(n+","+mode+","+scene+": "+failure);}
            }
        }finally{Files.write(out.resolve("production-aa-differences.txt"),differences);Files.write(out.resolve("failures.txt"),failures);Files.writeString(out.resolve("scope.txt"),"Synthetic complete preparation/recovery/GENERIC/MODEL/contact/depenetration/navigation/feedback/commit; NOT a real captured full chain, multi-frame physics or visual qualification. checks="+checks+"\n");GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
        check(failures.isEmpty(),"Full-chain fixture failures: "+failures);
    }
}
