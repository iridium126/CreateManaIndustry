import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;

/** Phase readbacks are diagnostic only, never benchmark samples. No production ordering changes. */
public final class ShaderEfficiencyTraceGpuValidation extends ShaderEfficiencyUpdateGpuValidation {
    record Phase(Snapshot snapshot,Map<String,int[]> buffers) {}
    static void rewindAttackContext(Frame frame)throws Exception{
        var field=frame.stars.getClass().getDeclaredField("data");field.setAccessible(true);
        int[] buffers=((int[])field.get(frame.stars)).clone();
        var seeds=new ByteBuffer[buffers.length];
        for(int i=0;i<buffers.length;i++)if(buffers[i]!=0)seeds[i]=read(buffers[i],Frame.size(buffers[i]));
        // Diagnostic fixture only: pin allocation bytes, including inactive outer
        // records never uploaded by this no-outer scene. Do not initialize production data.
        frame.resetExtra=()->{for(int i=0;i<buffers.length;i++)if(seeds[i]!=null)put(buffers[i],seeds[i]);};
    }
    static Phase capture(Frame frame,String phase){
        var buffers=new LinkedHashMap<String,int[]>();
        buffers.put("input_pool",words(read(frame.d.q.pool,Frame.size(frame.d.q.pool))));
        buffers.put("input_count",words(read(frame.d.q.previous,16)));
        if(!phase.equals("prepared")){
            var counter=read(frame.d.q.counter,16);int live=counter.getInt(0);
            check(live>=0&&live<=frame.d.n,"trace output count");
            buffers.put("output_count",words(counter));
            buffers.put("output_pool_physical_order",live==0?new int[0]:words(read(frame.d.output,live*64)));
        }
        buffers.put("grid",words(read(frame.d.grid,Frame.size(frame.d.grid))));
        buffers.put("correction",words(read(frame.d.correction,Frame.size(frame.d.correction))));
        buffers.put("population",words(read(frame.d.q.pop,Frame.size(frame.d.q.pop))));
        int state=frame.stars.candidateStateBuffer();
        buffers.put("persistent_state",state==0?new int[0]:words(read(state,Frame.size(state))));
        try {
            var field=frame.stars.getClass().getDeclaredField("data");field.setAccessible(true);
            int[] data=(int[])field.get(frame.stars);
            // prepare binds the pending write bank; commit publishes this same bank.
            int bank=phase.equals("published")?READ.getInt(frame.stars):1-READ.getInt(frame.stars);
            buffers.put("attack_context",data[bank]==0?new int[0]:words(read(data[bank],Frame.size(data[bank]))));
        } catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
        return new Phase(phase.equals("prepared")?null:frame.snapshot(),buffers);
    }
    static String diff(Phase a,Phase b){
        var result=new ArrayList<String>();
        for(var key:a.buffers.keySet()){
            int word=Arrays.mismatch(a.buffers.get(key),b.buffers.get(key));
            if(word>=0)result.add(key+" word="+word);
        }
        if(a.snapshot!=null)try{equal(a.snapshot,b.snapshot,"canonical");}catch(AssertionError e){result.add(e.getMessage());}
        return result.isEmpty()?"equal":String.join("; ",result);
    }
    public static void main(String[] args)throws Exception{
        var out=Path.of(System.getProperty("cmi.benchmark.output"));Files.createDirectories(out);
        var report=out.resolve("first-divergence.txt");if(Files.exists(report))throw new IllegalStateException("Existing evidence");
        if(!GLFW.glfwInit())throw new AssertionError("GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);
        long window=GLFW.glfwCreateWindow(32,32,"Phase trace",0,0);if(window==0)throw new AssertionError("GL context");
        var lines=new ArrayList<String>();lines.add("Trace-only blocking phase readbacks; no performance claim.");
        try{
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
            try(var p=Programs.load(System.getProperty("cmi.efficiency.baseline"))){
                for(int count:new int[]{1000,65536})for(int mode:new int[]{1,2})try(var frame=new Frame(count,mode,"dome_gather",p,p)){
                    var reference=new LinkedHashMap<String,Phase>();
                    rewindAttackContext(frame);
                    frame.checkpoint=phase->reference.put(phase,capture(frame,phase));frame.run(p,true);
                    for(int trial=0;trial<3;trial++){
                        int round=trial;
                        frame.checkpoint=phase->lines.add("count="+count+" mode="+mode+" trial="+round+" phase="+phase+": "+diff(reference.get(phase),capture(frame,phase)));
                        frame.run(p,true);
                    }
                }
            }
            if(GL11.glGetError()!=0)throw new AssertionError("GL error");
        }finally{Files.write(report,lines);GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
        lines.forEach(System.out::println);
    }
}
