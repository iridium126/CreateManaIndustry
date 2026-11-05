import com.iridium126.createmanaindustry.client.gpu.state.GpuBindings;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuProgramContract;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuPrograms;
import com.iridium126.createmanaindustry.client.particles.engine.*;
import org.lwjgl.opengl.*;
import org.lwjgl.glfw.GLFW;
import java.nio.file.*;

/** Real-driver resource semantics, not a mock of the binding cache. */
public final class BindingResourceGpuValidation {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static void foreignProgramUniforms(){
        int compute=GpuShaderCompiler.compute("engine program before Iris apply","""
            #version 450 core
            layout(local_size_x=1) in;
            layout(location=0) uniform float stepSize;
            layout(std430,binding=0) buffer Result { float value; };
            void main(){value=stepSize;}
            """);
        int pack=GpuShaderCompiler.graphics("external Iris program","""
            #version 450 core
            void main(){gl_Position=vec4(0,0,0,1);}
            ""","""
            #version 450 core
            layout(location=0) uniform vec4 entityColor;
            layout(location=5) uniform int segment;
            uniform sampler2D gtexture;
            out vec4 color;
            void main(){color=entityColor+float(segment)+texelFetch(gtexture,ivec2(0),0);}
            """);
        GpuBindings.enter();
        try{
            GpuBindings.program(compute);
            GL20.glUseProgram(pack); // Iris binds directly, outside GpuBindings.
            GpuBindings.externalProgramApplied(pack);
            GpuUniforms.current4f(GpuUniforms.loc(pack,"entityColor"),1,0,0,1);
            int colorError=GL11.glGetError();
            GpuUniforms.current1i(GpuUniforms.loc(pack,"segment"),2);
            int segmentError=GL11.glGetError();
            check(colorError==0&&segmentError==0,"pack uniform errors after external bind: "+colorError+"/"+segmentError);
            float[] color=new float[4];GL20.glGetUniformfv(pack,0,color);
            check(color[0]==1&&color[3]==1,"color reached the pack program");
            check(GL20.glGetUniformi(pack,5)==2,"segment reached the pack program");
            int sampler=GpuUniforms.loc(pack,"gtexture");
            GpuUniforms.sampler(pack,"gtexture",3);
            GpuBindings.program(compute);
            check(GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)==compute,"returning to the compute program binds it");
            GL20.glUseProgram(pack);
            GL20.glUniform1i(5,7);
            GL20.glUniform1i(sampler,1);
            GpuBindings.externalProgramApplied(pack);
            GpuUniforms.current1i(5,2);
            GpuUniforms.sampler(pack,"gtexture",3);
            check(GL20.glGetUniformi(pack,5)==2&&GL20.glGetUniformi(pack,sampler)==3,
                "Iris refresh invalidates cached values and sampler assignments");
            GpuBindings.invalidateProgram();
            GL20.glUseProgram(0);
            GpuBindings.program(pack);
            check(GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)==pack,"binding after external clear is submitted");
        }finally{
            GpuBindings.program(0);GpuBindings.exit();
            GpuPrograms.delete(compute);GpuPrograms.delete(pack);
        }
    }
    static void run(){
        int a=ParticleEngineGpuValidation.buffer(ParticleEngineGpuValidation.bytes(128));
        int b=ParticleEngineGpuValidation.buffer(ParticleEngineGpuValidation.bytes(128));
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,3,a);
        long baseSize=GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE,3);
        GpuBindings.enter();
        try{
            GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,3,a);long before=GpuBindings.submitted();
            GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,3,a);
            check(before==GpuBindings.submitted(),"identical indexed+generic binding is cached");
            GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,b);GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,3,a);
            check(GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING)==a,"cached indexed binding preserves generic side effect");
            GpuBindings.bindRange(GL43.GL_SHADER_STORAGE_BUFFER,3,a,0,16);
            check(GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE,3)==16,"range size installed");
            GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,3,a);
            check(GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE,3)==baseSize,"range/base ownership distinguished");
            GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,b);
            GpuBindings.bindBases(GL43.GL_SHADER_STORAGE_BUFFER,3,org.lwjgl.BufferUtils.createIntBuffer(2).put(new int[]{a,b}).flip());
            check(GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,4)==b,"multi-bind indexed state");
            check(GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING)==b,"multi-bind preserves generic binding");
            GpuBindings.forget(a);before=GpuBindings.submitted();GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,3,a);
            check(before<GpuBindings.submitted(),"retired names invalidate binding cache");
        }finally{GpuBindings.exit();GL15.glDeleteBuffers(new int[]{a,b});}
        String displaySource="#version 450 core\n"+DisplayParameters.prelude()+ParticlePrograms.commonPrelude()+ParticleEngineGpuValidation.source("display.comp");
        int uniform=GpuShaderCompiler.compute("display std140 contract",displaySource);
        try{
            DisplayParameters.verify(uniform);
            GpuProgramContract.installSamplers(uniform);
            GpuProgramContract.verify(uniform,"display.comp");
            int sampler=GL20.glGetUniformLocation(uniform,"cmi_Starfall");
            check(sampler>=0&&GL20.glGetUniformi(uniform,sampler)==com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.SAMPLER_MERGED_STARFALL,
                "display uses the stable Starfall texture unit");
        }finally{GL20.glDeleteProgram(uniform);}
        int census=ParticleEngineGpuValidation.program("census_ordinary.comp");
        try{
            check(GL20.glGetUniformLocation(census,"uFrustum")<0,"simulation census has no camera frustum");
            check(GL20.glGetUniformLocation(census,"uView")<0,"simulation census has no sorting matrix");
            check(GL43.glGetProgramResourceIndex(census,GL43.GL_SHADER_STORAGE_BLOCK,"SortWrite")==GL43.GL_INVALID_INDEX,"simulation census has no sort writes");
        }finally{GL20.glDeleteProgram(census);}
        check(GL11.glGetError()==GL11.GL_NO_ERROR,"GL state");
    }
    public static void main(String[] args)throws Exception{
        if(!GLFW.glfwInit())throw new IllegalStateException("GLFW");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,6);
        long window=GLFW.glfwCreateWindow(32,32,"particle resource contract",0,0);
        if(window==0)throw new IllegalStateException("GL context");
        try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();foreignProgramUniforms();run();
            Path out=Path.of(System.getProperty("cmi.particle.output","build/particle-engine/resources"));Files.createDirectories(out);
            Files.writeString(out.resolve("resources.json"),"{\"passed\":true,\"checks\":"+checks+"}");
            System.out.println("Resource binding checks passed: "+checks);
        }finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
