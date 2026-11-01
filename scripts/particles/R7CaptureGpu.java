import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.particles.engine.*;

/** Checks the complete unified capture variant, not a replacement physics backend. */
public final class R7CaptureGpu extends ModelPopulationGpuValidation {
    public static void main(String[] args)throws Exception{
        check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(32,32,"R7 actual query boundary",0,0);check(window!=0,"GL context");GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
        int baseline=compile("update.comp"),traced=0,buffer=0,texture=0;var differences=new java.util.ArrayList<String>();
        try{
            traced=com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler.compute("R7 query boundary capture","#version 450 core\n"+ParticlePrograms.commonPrelude()+ParticleQuerySequence.traceSource(source("update.comp")));
            int offset=256+131072*32,domeHeader=offset+131072*32,domeOffset=domeHeader+64;buffer=buffer(bytes(domeOffset+131072*80));texture=GL11.glGenTextures();GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,texture);GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,GL30.GL_RGBA32UI,buffer);GL42.glBindImageTexture(7,texture,0,false,0,GL15.GL_WRITE_ONLY,GL30.GL_RGBA32UI);
            for(int n:new int[]{0,1,63,64,65,1000})for(int mode=0;mode<3;mode++)try(var fixture=new UpdateDomainGpuValidation.DomainFixture(n,mode,new int[]{baseline,traced,traced})){
                GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(IntBuffer)null);
                fixture.frame(false);var expected=fixture.snapshot();bool(traced,"uR7CaptureEnabled",true);fixture.frame(true);var actual=fixture.snapshot();check(actual.keySet().equals(expected.keySet()),"stable identities");for(int key:actual.keySet())check(Arrays.equals(actual.get(key),expected.get(key)),"capture must preserve effective state");
                ByteBuffer trace=read(buffer,offset+Math.max(n,1)*32),input=read(fixture.q.pool,Math.max(n,1)*64);
                for(int i=0;i<n;i++){int at=offset+i*32;boolean model=mode==1||mode==2&&i%2==0;check(trace.getInt(at+24)==(model?1:0),"query eligibility");if(model){check(trace.getInt(at+16)==i&&trace.getInt(at+20)==i&&trace.getInt(at+28)==1,"query identity/pool/stage");for(int axis=0;axis<3;axis++)check(trace.getInt(at+axis*4)==input.getInt(i*64+axis*4),"actual local origin");check(trace.getFloat(at+12)==1,"ordinary radius");}}
                check(GL11.glGetError()==0,"GL state");
            }
            try(var original=ShaderEfficiencyUpdateGpuValidation.Programs.load(Path.of("src/main/resources").toAbsolutePath().toString())){
                int contact=R7DomeGpu.contacts(ParticleQuerySequence.traceDomeSource(source("dome_contacts.comp")));
                try{var capture=R7DomeGpu.replace(original,traced,contact);for(int n:new int[]{65,1000})for(int mode:new int[]{1,2})for(String scene:new String[]{"dome_gather","dome_attack"})try(var frame=new ShaderEfficiencyUpdateGpuValidation.Frame(n,mode,scene,original,capture)){
                    GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(IntBuffer)null);
                    bool(traced,"uR7CaptureEnabled",true);for(int p:DomeBodyContacts.related(contact)){bool(p,"uR7DomeCapture",true);gen(p,"uR7DomeGeneration",8);}
                    ByteBuffer[] candidate={null};frame.beforeContacts=p->{if(p==capture)candidate[0]=read(frame.d.output,Math.max(1,n)*64);};
                    frame.run(original,true);var expected=R7DomeGpu.semantic(frame.snapshot());frame.run(original,true);try{ShaderEfficiencyUpdateGpuValidation.equal(expected,R7DomeGpu.semantic(frame.snapshot()),"production A/A "+n+","+mode+","+scene);}catch(AssertionError difference){differences.add(difference.toString());}frame.run(capture,true);var actual=R7DomeGpu.semantic(frame.snapshot());try{ShaderEfficiencyUpdateGpuValidation.equal(expected,actual,"instrumented trace "+n+","+mode+","+scene);}catch(AssertionError difference){differences.add(difference.toString());}NeighborCompleteUpdateGpuValidation.invariants(expected,actual,scene);
                    var data=read(buffer,domeOffset+n*80);check(data.getInt(domeHeader)==n&&data.getInt(domeHeader+4)==8&&data.getInt(domeHeader+12)==1,"correct candidate phase/generation header");
                    int points=0,queries=0;for(int i=0;i<n;i++){int at=domeOffset+i*80;if(data.getInt(at+40)==0)continue;points++;int slot=data.getInt(at+28);check(slot>=0&&slot<n&&data.getInt(at+12)==i&&data.getInt(at+44)==2,"immutable Dome point identity/pool/stage");for(int k=0;k<3;k++)check(data.getInt(at+k*4)==candidate[0].getInt(slot*64+k*4),"actual post-MODEL candidate position");if(data.getFloat(at+60)>0||data.getFloat(at+76)>0)queries++;}check(points==frame.d.models&&queries>0,"nonempty actual Dome query capture");check(GL11.glGetError()==0,"Dome trace GL state");
                }}finally{deleteProgram(contact);}
            }
            Path out=Path.of(System.getProperty("cmi.benchmark.output"));Files.createDirectories(out);Files.write(out.resolve("production-and-instrumented-order-differences.txt"),differences,StandardOpenOption.CREATE_NEW);Files.writeString(out.resolve("capture-correctness.txt"),"Passed "+checks+" checks. Unified GENERIC then MODEL preserves tested update state. Post-MODEL Dome solve/apply capture validates actual sampled input and physical invariants; original A/A and instrumentation ordering differences are retained separately and are not equivalence passes. Candidate coordinates, pool identity, stage and generation checked on GPU. Not FPS or client visual validation.\n",StandardOpenOption.CREATE_NEW);
        }finally{if(texture!=0)GL11.glDeleteTextures(texture);if(buffer!=0)GL15.glDeleteBuffers(buffer);if(traced!=0)deleteProgram(traced);deleteProgram(baseline);GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
