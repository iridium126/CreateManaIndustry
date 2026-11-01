import java.nio.*;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.particles.engine.*;

/** Full physics journal -> independent resource reconstruction -> reference and
 * hardware continuation. Historical update-only snapshots cannot run this test. */
public final class R7FullChainJournalGpu extends ShaderEfficiencyUpdateGpuValidation {
    static Snapshot snapshot(R7ChainReplayGpu.Replay r,Map<String,String> names){
        int count=read(r.buffer(names.get("counter")),16).getInt(0);var pool=read(r.buffer(names.get("output")),Math.max(1,count)*64);var particles=new TreeMap<Integer,int[]>();for(int i=0;i<count;i++){int[] row=new int[16];for(int k=0;k<16;k++)row[k]=pool.getInt(i*64+k*4);check(particles.put(row[14],row)==null,"stable full replay identity");}
        int state=r.buffer(names.get("state"));GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,state);int[] words=words(read(state,GL15.glGetBufferParameteri(GL43.GL_SHADER_STORAGE_BUFFER,GL15.GL_BUFFER_SIZE)));var event=read(r.buffer(names.get("feedback")),4+16384*24);int events=event.getInt(0);check(events>=0&&events<=16384,"full replay feedback bounds");var rows=new ArrayList<String>();for(int i=0;i<events;i++){int[] values=new int[6];for(int k=0;k<6;k++)values[k]=event.getInt(4+(i*6+k)*4);rows.add(Arrays.toString(values));}Collections.sort(rows);return R7DomeGpu.semantic(new Snapshot(particles,words,rows));
    }
    public static void main(String[] args)throws Exception{
        Path out=Path.of(System.getProperty("cmi.benchmark.output"));Files.createDirectories(out);check(!Files.exists(out.resolve("full-chain-journal.json")),"fresh evidence");check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(32,32,"R7 full physics journal",0,0);check(window!=0,"context");GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();var cases=new ArrayList<Object>();boolean passed=false;
        try(var production=Programs.load(Path.of("src/main/resources").toAbsolutePath().toString())){
            for(String scene:new String[]{"starfall","recovery","dome_gather","dome_attack"}){
                Path capture=out.resolve(scene);Map<String,String> names;
                try(var frame=new Frame(65,2,scene,production,production)){
                    var recorder=ParticleChainCapture.INSTANCE;recorder.startAt(capture,17,7,Map.of("synthetic_fixture",true,"time",frame.time,"dt",frame.dt,"scene",scene));
                    recorder.sourceForValidation(production.update(),"update.comp",source("update.comp"));int[] contacts=DomeBodyContacts.related(production.contacts());String[] sources={"dome_contact_control.comp","dome_contacts.comp","dome_contact_apply.comp","dome_contact_audit.comp"};for(int i=0;i<contacts.length;i++)recorder.sourceForValidation(contacts[i],sources[i],source(sources[i]));
                    frame.measureGridPreparation=true;frame.run(production,true);var aliases=recorder.bufferNamesForValidation();names=Map.of("output",aliases.get(frame.d.output),"counter",aliases.get(frame.d.q.counter),"state",aliases.get(frame.stateBuffer),"feedback",aliases.get(frame.feedback));recorder.finish(true);GL11.glFlush();long deadline=System.nanoTime()+10_000_000_000L;while(recorder.busy()){recorder.poll();check(System.nanoTime()<deadline,"journal export bounded");java.util.concurrent.locks.LockSupport.parkNanos(100_000);}
                }
                Snapshot expected;try(var reference=new R7ChainReplayGpu.Replay(capture,"complete-reference")){reference.run();expected=snapshot(reference,names);reference.run();equal(expected,snapshot(reference,names),scene+" full command reference AA");}
                for(String backend:R7HardwareGpu.BACKENDS)try(var candidate=new R7ChainReplayGpu.Replay(capture,backend)){candidate.run();var actual=snapshot(candidate,names);equal(expected,actual,scene+" complete journal "+backend);NeighborCompleteUpdateGpuValidation.invariants(expected,actual,scene);check(candidate.hardware.ordinaryUsed>0||scene.startsWith("dome")&&candidate.hardware.domeUsed>0,"actual native result consumed");cases.add(Map.of("scene",scene,"backend",backend,"ordinary_native",candidate.hardware.ordinaryUsed,"dome_native",candidate.hardware.domeUsed,"fallbacks",candidate.hardware.fallbacks));}
            }check(GL11.glGetError()==0,"full journal final GL state");passed=true;
        }finally{Files.writeString(out.resolve("full-chain-journal.json"),new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("passed",passed,"checks",checks,"scope","full synthetic physics command capture and reconstructed reference/native continuation; not historical complete world replay or FPS","cases",cases))+"\n");GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
