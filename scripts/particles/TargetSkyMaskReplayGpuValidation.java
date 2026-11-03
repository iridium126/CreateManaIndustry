import com.iridium126.createmanaindustry.client.render.shaderpack.*;
import com.google.common.collect.ImmutableList;
import net.irisshaders.iris.shaderpack.include.*;
import net.irisshaders.iris.shaderpack.option.ShaderPackOptions;
import net.irisshaders.iris.shaderpack.parsing.*;
import net.irisshaders.iris.shaderpack.properties.*;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;

/** Actual captured options + an executable history counterexample. Passing means rejection is correct. */
public final class TargetSkyMaskReplayGpuValidation {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static final String VERTEX="#version 330 compatibility\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2-1,0,1);}";
    // Quad closure of the one-texel filter halo preserves derivatives in retained quads.
    static final String MASK="""
        bool retained(ivec2 pixel,ivec2 size){
            ivec2 q=(pixel/2)*2;
            for(int y=-1;y<=2;y++)for(int x=-1;x<=2;x++){
                ivec2 p=clamp(q+ivec2(x,y),ivec2(0),size-1);
                if(p==size/2)return true;
            }
            return false;
        }
        """.replace("if(p==size/2)","if(all(equal(p,size/2)))");
    static float[] render(int program,int width,int height,boolean mask,int attachment){
        GL20.glUseProgram(program);GL20.glUniform1i(GL20.glGetUniformLocation(program,"masked"),mask?1:0);
        GL20.glUniform2i(GL20.glGetUniformLocation(program,"size"),width,height);
        for(int i=0;i<2;i++)GL30.glClearBufferfv(GL11.GL_COLOR,i,new float[]{0,0,0,0});
        GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0+attachment);
        float[] data=new float[width*height*4];GL11.glReadPixels(0,0,width,height,GL11.GL_RGBA,GL11.GL_FLOAT,data);return data;
    }
    static void historyCounterexample(Path out)throws Exception{
        String fragment="#version 330 compatibility\nuniform bool masked;uniform ivec2 size;\n"+MASK+"""
            void main(){ivec2 p=ivec2(gl_FragCoord.xy);if(masked&&!retained(p,size))discard;
                gl_FragData[0]=vec4(gl_FragCoord.xy/vec2(size),.5,1);
                gl_FragData[1]=vec4(.2,.3,.4,.75);}
            """;
        int program=ModelMaterialGpuValidation.link(Map.of(PatchShaderType.VERTEX,VERTEX,PatchShaderType.FRAGMENT,fragment),"history-prototype",out);
        int vao=GL30.glGenVertexArrays(),fbo=GL30.glGenFramebuffers();GL30.glBindVertexArray(vao);GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);
        var records=new ArrayList<String>();records.add("width,height,current_filter_footprint_equal,history_equal,next_history_sample_equal");
        try{
            for(int size:new int[]{31,32,63}){
                int[] textures=new int[]{GL11.glGenTextures(),GL11.glGenTextures()};
                try{
                    for(int i=0;i<2;i++){GL11.glBindTexture(GL11.GL_TEXTURE_2D,textures[i]);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL30.GL_RGBA32F,size,size,0,GL11.GL_RGBA,GL11.GL_FLOAT,(java.nio.ByteBuffer)null);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0+i,GL11.GL_TEXTURE_2D,textures[i],0);}
                    GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0,GL30.GL_COLOR_ATTACHMENT1});GL11.glViewport(0,0,size,size);
                    GL11.glDisable(GL11.GL_BLEND);GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDisable(GL11.GL_CULL_FACE);
                    float[] a=render(program,size,size,false,0),aa=render(program,size,size,false,0),b=render(program,size,size,true,0);
                    check(Arrays.equals(a,aa),"prototype baseline A/A");
                    for(int y=size/2-1;y<=size/2+1;y++)for(int x=size/2-1;x<=size/2+1;x++)for(int k=0;k<4;k++)check(Float.floatToRawIntBits(a[(y*size+x)*4+k])==Float.floatToRawIntBits(b[(y*size+x)*4+k]),"all potentially filtered current pixels match");
                    float[] old=render(program,size,size,false,1),candidate=render(program,size,size,true,1);
                    check(!Arrays.equals(old,candidate),"retained attachment must expose unsafe skipped writes");
                    int last=((size-1)*size+size-1)*4+3;
                    check(old[last]!=candidate[last],"next-frame last-pixel history consumer changes");
                    records.add(size+","+size+",true,false,false");
                }finally{for(int texture:textures)GL11.glDeleteTextures(texture);}
            }
            Files.write(out.resolve("history-counterexample.csv"),records);
        }finally{GL30.glBindVertexArray(0);GL30.glDeleteVertexArrays(vao);GL30.glDeleteFramebuffers(fbo);GL20.glDeleteProgram(program);}
    }
    public static void main(String[] args)throws Exception{
        if(!GLFW.glfwInit())throw new AssertionError("GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE,GLFW.GLFW_OPENGL_COMPAT_PROFILE);
        long window=GLFW.glfwCreateWindow(64,64,"Target mask qualification",0,0);check(window!=0,"GL context");
        try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();com.mojang.blaze3d.systems.RenderSystem.initRenderThread();qualify();}
        finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
    static void qualify()throws Exception{
        Path out=Path.of(System.getProperty("cmi.benchmark.output"));Files.createDirectories(out);
        if(Files.exists(out.resolve("qualification.json")))throw new IllegalStateException("Existing evidence");
        Path archive=Path.of("run/shaderpacks/ComplementaryUnbound_r5.9.3.zip");var inventory=ShaderPackInventory.readPack(archive);
        ModelMergeGpuValidation.benchmarkEnvironment(archive.getFileName().toString(),inventory.digest());
        check("2f845d3b8761".equals(ModelMergeGpuValidation.replayOptionHash),"exact R5 options");
        var plan=NativeRiftPatchPlan.prepare(inventory);check(plan.enabled()&&plan.family().equals("complementary"),"native contract");
        Path root=out.resolve("captured-pack");var sources=new TreeMap<>(inventory.sources());sources.putAll(plan.edits());
        var cloud=StormCloudDensityPatchPlan.prepare(ShaderPackInventory.of(sources),plan.family());check(cloud.enabled(),cloud.reason());sources.putAll(cloud.edits());
        for(var entry:sources.entrySet()){var path=root.resolve(entry.getKey()).normalize();check(path.startsWith(root.normalize()),"safe source path");Files.createDirectories(path.getParent());Files.writeString(path,entry.getValue());}
        String f=ModelMaterialGpuValidation.preprocess(root,"world0/deferred1.fsh"),v=ModelMaterialGpuValidation.preprocess(root,"world0/deferred1.vsh");
        Files.writeString(out.resolve("active-terminal-fragment.glsl"),f);Files.writeString(out.resolve("active-terminal-vertex.glsl"),v);
        if(Boolean.getBoolean("cmi.rift.r6")){
            String clipped=NativeSkySources.isolate(f,"complementary");
            check(clipped.contains("cmiTargetUnclipped"),"production terminal wrapper present");
            int program=ModelMaterialGpuValidation.link(Map.of(PatchShaderType.VERTEX,NativeSkySources.readOnly(v),PatchShaderType.FRAGMENT,clipped),"r6-actual-terminal",out);
            GL20.glDeleteProgram(program);check(GL11.glGetError()==0,"actual clipped shader GL state");
            Files.writeString(out.resolve("r6-actual-compile.txt"),"actual Complementary options="+ModelMergeGpuValidation.replayOptionHash+"\nproduction isolated/clipped terminal compiled and linked\nNo full target-chain performance claim: upstream textures and histories were not replayed.\n");
            System.out.println("R6 actual Complementary terminal compiled with captured options");return;
        }
        var graph=new IncludeGraph(root,ImmutableList.of(AbsolutePackPath.fromAbsolutePath("/world0/deferred1.fsh")),false);
        var options=new ShaderPackOptions(graph,ModelMaterialGpuValidation.OPTION_OVERRIDES);
        var properties=new ShaderProperties(inventory.source("shaders.properties"),options,ModelMaterialGpuValidation.DEFINES);
        var buffers=new TreeSet<Integer>();for(int i=0;i<32;i++)buffers.add(i);
        var directives=new PackDirectives(buffers,properties);var holder=new DispatchingDirectiveHolder();directives.acceptDirectivesFrom(holder);
        // Iris gathers global target directives from other valid programs too.
        // deferred1 does not include pipelineSettings; the actual final program does.
        String global=ModelMaterialGpuValidation.preprocess(root,"world0/final.fsh");
        Files.writeString(out.resolve("active-final-fragment.glsl"),global);
        for(String source:List.of(global,f))for(var directive:ConstDirectiveParser.findDirectives(source))holder.processDirective(directive);
        var matcher=Pattern.compile("/\\*\\s*(DRAWBUFFERS|RENDERTARGETS)\\s*:\\s*([0-9, ]+)\\*/").matcher(f);
        List<Integer> writes=List.of();while(matcher.find())writes=matcher.group(1).equals("DRAWBUFFERS")?matcher.group(2).chars().filter(Character::isDigit).map(c->c-'0').boxed().toList():Arrays.stream(matcher.group(2).split(",")).map(String::trim).map(Integer::parseInt).toList();
        check(writes.contains(5),"actual active terminal writes colortex5");
        var retained=writes.stream().filter(i->!directives.getRenderTargetDirectives().getRenderTargetSettings().get(i).shouldClear()).toList();
        var decision=TargetSkyMaskPreflight.inspect(plan.family(),retained,false);check(!decision.eligible()&&retained.contains(5),"history preflight must reject the requested optimization");
        var report=new TreeMap<String,Object>();report.put("kind","qualification-rejection-not-performance");report.put("source_sha256",inventory.digest());report.put("options_hash",ModelMergeGpuValidation.replayOptionHash);report.put("draw_buffers",writes);report.put("retained_outputs",retained);report.put("decision",TargetSkyMaskPreflight.metadata(decision));report.put("vertex_reads_colortex5",v.contains("texelFetch(colortex5"));
        historyCounterexample(out);check(GL11.glGetError()==0,"GL state");report.put("checks",checks);Files.writeString(out.resolve("qualification.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));System.out.println("Target sky mask rejected: "+decision.reason()+"; "+checks+" checks passed");
    }
}
