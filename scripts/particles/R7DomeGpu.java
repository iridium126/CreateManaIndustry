import java.nio.*;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.particles.engine.*;
import com.iridium126.createmanaindustry.client.particles.engine.hardware.*;
import com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers;

/** Device-resident Dome broad phase versus independent complete enumeration. */
public final class R7DomeGpu extends ShaderEfficiencyUpdateGpuValidation {
    static int program(String text){return com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler.compute("R7 Dome query boundary","#version 450 core\n"+ParticlePrograms.commonPrelude()+text);}
    static int contacts(String source){int[] ids=new int[4];int[] passes={4,0,3,5};for(int i=0;i<4;i++)ids[i]=program(source.replace("#define CMI_DOME_PASS 0","#define CMI_DOME_PASS "+passes[i]));DomeBodyContacts.register(new DomeBodyContacts.Programs(ids[0],ids[1],ids[2],ids[3]));return ids[1];}
    static Programs replace(Programs p,int update,int contact){return new Programs(update,p.recovery(),contact,p.prepare(),p.schedule(),p.depen(),p.path(),p.feedback());}
    /** Existing solve visit diagnostics prove the consumer used CSR lengths,
     * rather than a successful worker followed by unnoticed full enumeration. */
    static int verifyConsumer(Frame frame,HardwareQueryStage stage,HardwareQueryStage.Ticket ticket){
        int buffer=stage.resultBufferForValidation(ticket);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);int size=GL15.glGetBufferParameteri(GL43.GL_SHADER_STORAGE_BUFFER,GL15.GL_BUFFER_SIZE);var data=R7HardwareGpu.readShared(buffer,size);int count=data.getInt(16),lookup=data.getInt(48);if(count==0)return 0;var state=read(frame.stateBuffer,frame.stateSeed.capacity());int checked=0;
        for(int member=0;member<131072;member++){int row=data.getInt((lookup+member)*4);if(row<0||row>=count)continue;int visits=state.getInt((StarfallGpuBuffers.DOME_CONTACT_DELTA+member*4+3)*4)&65535;if(visits==0)continue;long from=data.getLong(80+row*8),to=data.getLong(88+row*8);check(visits==Math.min(65535,to-from),"Dome solve actually consumes native CSR, not silent full reference");checked++;}return checked;
    }
    static Snapshot semantic(Snapshot s){int[] state=s.state().clone();for(int m=0;m<131072;m++){int at=StarfallGpuBuffers.DOME_CONTACT_DELTA+m*4+3;if(at<state.length)state[at]&=0xffff0000;}if(state.length>StarfallGpuBuffers.DOME_CONTACT_ARGS+7){int count=state[StarfallGpuBuffers.DOME_CONTACT_ARGS+7];check(count>=0&&count<=131072,"bounded stopped queue");Arrays.sort(state,StarfallGpuBuffers.DOME_CONTACT_QUEUE,StarfallGpuBuffers.DOME_CONTACT_QUEUE+count);}return new Snapshot(s.particles(),state,s.events());}
    public static void main(String[] args)throws Exception{
        Path out=Path.of(System.getProperty("cmi.benchmark.output"));Files.createDirectories(out);check(!Files.exists(out.resolve("dome.json")),"fresh output");Path source=out.resolve("native-source");Files.createDirectory(source);for(String name:List.of("Query.h","grid.cu","rt.cu","rt-triangle.cu","tensor.cu"))Files.copy(Path.of("native/offload",name),source.resolve(name));var deps=JsonParser.parseString(Files.readString(Path.of(".gradle/neighbor-rt/dependencies.json"))).getAsJsonObject();
        check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(32,32,"R7 swept Dome queries",0,0);check(window!=0,"GL context");GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();HardwareNative.load(Path.of(".gradle/hardware-offload/build"));
        var metadata=new LinkedHashMap<String,Object>();metadata.put("passed",false);metadata.put("scope","complete synthetic update/contact/depenetration/navigation/feedback chain; GPU-resident Dome query; debug sorted IDs; not client FPS");var failures=new ArrayList<String>();var aa=new ArrayList<String>();var cases=new ArrayList<Object>();int update=0,refContact=0,candidateContact=0;
        try(var base=Programs.load(Path.of("src/main/resources").toAbsolutePath().toString())){
            var motion=HardwareUpdateSource.build(ModelPopulationGpuValidation.source("update.comp"));update=program(motion.source());var dome=HardwareDomeSource.build(ModelPopulationGpuValidation.source("dome_contacts.comp"));Files.writeString(out.resolve("dome-consumer.glsl"),dome.consumer());Files.writeString(out.resolve("dome-prepare.glsl"),dome.prepare());refContact=contacts(dome.consumer());candidateContact=contacts(dome.consumer());var before=replace(base,update,refContact);var after=replace(base,update,candidateContact);int backend=Integer.getInteger("cmi.r7.backendIndex",1);
            for(String count:System.getProperty("cmi.r7.counts","0,1,63,64,65,1000").split(","))for(String scene:System.getProperty("cmi.r7.scenes","dome_gather,dome_attack").split(","))for(int mode:new int[]{1,2}){
                int n=Integer.parseInt(count);String cell=n+","+mode+","+scene;HardwareQueryStage.pollRetirement();
                try(var frame=new Frame(n,mode,scene,before,after);var stage=new HardwareQueryStage(Math.max(1,n),Math.max(1,n),8*1024*1024,"#version 450 core\n"+ParticlePrograms.commonPrelude()+dome.prepare(),Path.of(deps.getAsJsonObject("cuda_cudart").get("root").getAsString()),Path.of(deps.getAsJsonObject("optix").get("root").getAsString()),source,Path.of(deps.getAsJsonObject("cuda_nvcc").get("root").getAsString()),true,backend)){
                    int scratch=buffer(bytes(16+Math.max(1,n)*motion.strideWords()*4)),scratchTexture=GL11.glGenTextures();GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,scratchTexture);GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,GL30.GL_R32UI,scratch);HardwareQueryStage.Ticket[] ticket={null};
                    try{
                        frame.updateDispatch=p->{GL42.glBindImageTexture(7,scratchTexture,0,false,0,GL15.GL_READ_WRITE,GL30.GL_R32UI);ui(p.update(),"uR7Stage",0);ui(p.update(),"uModelPass",1);dispatch(p.update(),Math.max(1,(n+63)/64));ui(p.update(),"uModelPass",2);dispatch(p.update(),(frame.d.models+63)/64);};
                        frame.beforeContacts=p->{for(int part:DomeBodyContacts.related(p.contacts()))bool(part,"uR7ResultsEnabled",false);if(p!=after)return;int prepare=stage.prepareProgram();frame.d.bindings();frame.stars.bind(prepare,true);f(prepare,"uTimeSec",frame.time);f(prepare,"uDt",frame.dt);ticket[0]=stage.beginBound(0,0,7,7,backend,Math.max(1,n),Math.max(1,n));check(stage.consume(ticket[0],10_000_000_000L),"Dome shared query: "+stage.lastReason());for(int part:DomeBodyContacts.related(p.contacts())){bool(part,"uR7ResultsEnabled",true);gen(part,"uR7Generation",7);gen(part,"uR7ResourceEpoch",(int)stage.resourceEpoch());}};
                        frame.run(before,true);var expected=semantic(frame.snapshot());frame.run(before,true);try{equal(expected,semantic(frame.snapshot()),cell+" reference AA");}catch(AssertionError e){aa.add(e.getMessage());}
                        frame.run(after,true);stage.afterResume(ticket[0]);var actual=semantic(frame.snapshot());equal(expected,actual,cell+" native Dome");NeighborCompleteUpdateGpuValidation.invariants(expected,actual,scene);cases.add(Map.of("case",cell,"passed",true));System.out.println("R7 Dome exact "+cell);
                    }finally{GL11.glDeleteTextures(scratchTexture);GL15.glDeleteBuffers(scratch);}
                }catch(AssertionError|RuntimeException e){failures.add(cell+": "+e);System.out.println("R7 Dome FAILED "+cell+": "+e);}
                long deadline=System.nanoTime()+5_000_000_000L;while(HardwareQueryStage.retainedStages()>0){HardwareQueryStage.pollRetirement();check(System.nanoTime()<deadline,"Dome resource retirement");java.util.concurrent.locks.LockSupport.parkNanos(100_000);}
            }metadata.put("passed",failures.isEmpty());
        }finally{metadata.put("checks",checks);metadata.put("cases",cases);metadata.put("failures",failures);metadata.put("reference_aa_differences",aa);Files.writeString(out.resolve("dome.json"),new GsonBuilder().setPrettyPrinting().create().toJson(metadata)+"\n");if(update!=0)deleteProgram(update);if(refContact!=0)deleteProgram(refContact);if(candidateContact!=0)deleteProgram(candidateContact);GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
        check(failures.isEmpty(),"Dome query qualification failed; raw evidence retained");
    }
}
