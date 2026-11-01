import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import com.google.gson.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;

/** Isolated actual read-pool geometry. No production continuation or FPS claim. */
public final class R6NativeReplayGpu {
    static double[] run(NeighborQueryGpuValidation.Fixture fixture,int backend){return fixture.run(fixture.nativeHandle==0?0:backend);}
    static void verify(NeighborQueryGpuValidation.Fixture fixture,int backend){fixture.verify(fixture.nativeHandle==0?0:backend);}
    static float[] points(Path root,JsonObject frame)throws Exception{
        byte[] bytes=Files.readAllBytes(root.resolve(frame.get("file").getAsString()));NeighborQueryGpuValidation.check(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(frame.get("sha256").getAsString()),"input hash");int n=frame.get("points").getAsInt();NeighborQueryGpuValidation.check(bytes.length==n*16,"point layout");float[] values=new float[n*4];ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(values);return values;
    }
    static void load(NeighborQueryGpuValidation.Fixture fixture,float[] values){System.arraycopy(values,0,fixture.points,0,values.length);fixture.expected=NeighborQueryGpuValidation.reference(values,fixture.q,1);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,fixture.input);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,values);GL11.glFinish();}
    public static void main(String[] args)throws Exception{
        Path inputs=Path.of(System.getProperty("cmi.r6.nativeInputs")),out=Path.of(System.getProperty("cmi.benchmark.output"));Files.createDirectories(out);NeighborQueryGpuValidation.check(!Files.exists(out.resolve("samples.csv")),"fresh output");JsonObject description=JsonParser.parseString(Files.readString(inputs.resolve("inputs.json"))).getAsJsonObject();
        boolean sequence=description.get("contiguous").getAsBoolean();if(sequence)NeighborQueryGpuValidation.check(description.get("periodic_rebuild_eligible").getAsBoolean(),"period experiment needs verified contiguous frames and fixed stable membership");
        NeighborQueryGpuValidation.check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,6);long window=GLFW.glfwCreateWindow(32,32,"R6 native actual geometry",0,0);NeighborQueryGpuValidation.check(window!=0,"context");GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
        Path nativeRoot=Path.of(".gradle/neighbor-rt/build").toAbsolutePath();for(String dll:List.of("nvrtc-builtins64_128.dll","nvrtc64_120_0.dll","CmiNeighborExperiment.dll"))System.load(nativeRoot.resolve(dll).toString());JsonObject deps=JsonParser.parseString(Files.readString(Path.of(".gradle/neighbor-rt/dependencies.json"))).getAsJsonObject();int p=NeighborQueryGpuValidation.compile();int samples=Integer.getInteger("cmi.r6.samples",64);
        var info=new LinkedHashMap<String,Object>();info.put("scope","read-pool geometric queries, index/BVH maintenance and native ownership transfer only; no actual local query origins, physical rules, full chain, shaderpack competition or FPS qualification");info.put("input_upload","fixture operation excluded equally; completed before query timers");info.put("wall_ms","completed host wall time includes GL/CUDA ownership transfer and synchronization; GPU event time alone does not");info.put("periodic_rebuild",sequence);info.put("sdk",deps);info.put("gpu",GL11.glGetString(GL11.GL_RENDERER));info.put("driver",GL11.glGetString(GL11.GL_VERSION));
        info.put("synthetic_input",description.has("synthetic_input")&&description.get("synthetic_input").getAsBoolean());info.put("empty_input_policy","empty native queries use the GL no-op path; never dereference a null native handle");
        try(var raw=Files.newBufferedWriter(out.resolve("samples.csv"))){
            raw.write("input,scope,path,pair,side,gpu_ms,completed_wall_ms\n");
            if(!sequence){
                for(var element:description.getAsJsonArray("frames")){var frame=element.getAsJsonObject();float[] values=points(inputs,frame);int n=values.length/4,q=Math.min(n,1024);int[][] expected=NeighborQueryGpuValidation.reference(values,q,1);int total=0;for(int[] row:expected)total=Math.addExact(total,row.length);
                    try(var fixture=new NeighborQueryGpuValidation.Fixture(n,q,frame.get("name").getAsString(),Math.max(1,total),p,deps)){load(fixture,values);for(int backend=0;backend<4;backend++)verify(fixture,backend);for(int backend=0;backend<4;backend++){
                        for(int warm=0;warm<12;warm++)run(fixture,(warm&1)==0?0:backend);
                        for(int pair=0;pair<samples;pair++)for(int turn=0;turn<2;turn++){int side=(pair+turn)&1;double[] timing=run(fixture,side==0?0:backend);raw.write(frame.get("name").getAsString()+",static-snapshot-geometric-query,"+backend+","+pair+","+side+","+timing[0]+","+timing[1]+"\n");}raw.flush();
                    }}
                }
            }else{
                var frames=description.getAsJsonArray("frames");float[][] values=new float[frames.size()][];int n=0,q=0,capacity=1;
                for(int i=0;i<frames.size();i++){
                    JsonObject frame=frames.get(i).getAsJsonObject();values[i]=points(inputs,frame);if(i==0){n=values[i].length/4;q=Math.min(n,1024);}NeighborQueryGpuValidation.check(values[i].length==n*4,"fixed member count");
                    if(i>0){JsonObject previous=frames.get(i-1).getAsJsonObject();NeighborQueryGpuValidation.check(frame.get("scene_frame").getAsLong()==previous.get("scene_frame").getAsLong()+1&&frame.get("input_generation").getAsLong()==previous.get("input_generation").getAsLong()+1,"contiguous frame and generation");for(int j=3;j<values[i].length;j+=4)NeighborQueryGpuValidation.check(Float.floatToRawIntBits(values[i][j])==Float.floatToRawIntBits(values[0][j]),"fixed stable identity ordering");}
                    int total=0;for(int[] row:NeighborQueryGpuValidation.reference(values[i],q,1))total=Math.addExact(total,row.length);capacity=Math.max(capacity,total);
                }
                try(var fixture=new NeighborQueryGpuValidation.Fixture(n,q,"contiguous-sequence",capacity,p,deps)){
                    for(int path:new int[]{0,1,2,4,8}){
                        // Correctness/readback is a separate pass, never a timing sample.
                        for(int i=0;i<values.length;i++){load(fixture,values[i]);verify(fixture,path==0?0:path==1?1:path==2||i%path==0?2:3);}
                        for(int pair=-4;pair<samples;pair++)for(int turn=0;turn<2;turn++){int side=(pair+turn)&1;double gpu=0,wall=0;
                            for(int i=0;i<values.length;i++){load(fixture,values[i]);int backend=side==0||path==0?0:path==1?1:path==2||i%path==0?2:3;double[] timing=run(fixture,backend);gpu+=timing[0];wall+=timing[1];}
                            if(pair>=0)raw.write("contiguous-sequence,sequence-total-geometric-query,"+path+","+pair+","+side+","+gpu+","+wall+"\n");
                        }raw.flush();
                    }
                }
            }
            info.put("passed_query_checks",NeighborQueryGpuValidation.checks);info.put("initialization",NeighborQueryGpuValidation.initialization);info.put("first_execution",NeighborQueryGpuValidation.firstExecutions);Files.writeString(out.resolve("metadata.json"),new GsonBuilder().setPrettyPrinting().create().toJson(info)+"\n");
        }finally{GL20.glDeleteProgram(p);GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
