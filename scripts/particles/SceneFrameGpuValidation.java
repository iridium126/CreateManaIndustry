import java.nio.*;
import java.nio.charset.StandardCharsets;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.gpu.frame.*;
import com.iridium126.createmanaindustry.client.gpu.buffer.*;
import com.iridium126.createmanaindustry.client.gpu.shader.*;

/** Real-driver reservation and failed-publication checks. No Minecraft client or world is started. */
public final class SceneFrameGpuValidation {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static ByteBuffer bytes(int size){return BufferUtils.createByteBuffer(size);}
    private static int buffer(ByteBuffer data){int id=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,data,GL15.GL_DYNAMIC_COPY);return id;}
    private static ByteBuffer read(int id,int size){GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);var data=bytes(size);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,data);return data;}
    private static void put(int id,ByteBuffer data){GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,data);}
    private static int program()throws Exception {
        String helper;
        try(var stream=SceneFrameGpuValidation.class.getClassLoader().getResourceAsStream("assets/createmanaindustry/shaders/particles/chunks/pool_reservation.glsl")){
            helper=new String(java.util.Objects.requireNonNull(stream).readAllBytes(),StandardCharsets.UTF_8);
        }
        return GpuShaderCompiler.compute("scene reservation fault fixture","#version 450 core\n"
                +"layout(local_size_x=64) in;layout(std430,binding=0) buffer Counter{uint writeSlot;uint spare;uint census;uint sorted;}counter;"
                +"layout(std430,binding=1) buffer Output{uvec4 particles[];};uniform uint uCapacity,uRequested,uInvocations;uniform bool uWhole;\n"
                +helper+"\nvoid main(){if(gl_GlobalInvocationID.x>=uInvocations)return;uint count;uint first=cmiReservePool(uRequested,uWhole,count);"
                +"for(uint i=0u;i<count;i++){uint p=(first+i+1u)*4u;for(uint word=0u;word<4u;word++)particles[p+word]=uvec4(0x1234u);}}\n");
    }
    private static void dispatch(int program,int capacity,int requested,boolean whole,int invocations,int counter,int output){
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,counter);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,1,output);
        GL20.glUseProgram(program);GL30.glUniform1ui(GpuUniforms.loc(program,"uCapacity"),capacity);GL30.glUniform1ui(GpuUniforms.loc(program,"uRequested"),requested);
        GL30.glUniform1ui(GpuUniforms.loc(program,"uInvocations"),invocations);GL20.glUniform1i(GpuUniforms.loc(program,"uWhole"),whole?1:0);
        GL43.glDispatchCompute((invocations+63)/64,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
    }
    private static void boundaries(int program){
        for(int capacity:new int[]{0,1,255,256,257})for(int requested:new int[]{1,3,128})for(boolean whole:new boolean[]{false,true}){
            int counter=buffer(bytes(16)),output=buffer(bytes((capacity+2)*64));
            try {
                dispatch(program,capacity,requested,whole,257,counter,output);
                int count=read(counter,16).getInt(0);check(count>=0&&count<=capacity,"Visible count exceeded capacity");
                check(!whole||count%requested==0,"Partial atomic output");
                var result=read(output,(capacity+2)*64);
                for(int offset=0;offset<64;offset+=4){check(result.getInt(offset)==0,"Leading guard overwritten");check(result.getInt((capacity+1)*64+offset)==0,"Trailing guard overwritten");}
                for(int slot=0;slot<count;slot++)for(int word=0;word<16;word++)check(result.getInt((slot+1)*64+word*4)==0x1234,"Reserved output missing");
                int previous=count;dispatch(program,capacity,capacity+1,true,1,counter,output);check(read(counter,16).getInt(0)==previous,"Rejected reservation changed count");
            } finally {GpuBufferNames.delete(counter);GpuBufferNames.delete(output);}
        }
    }
    private static void failedCandidate(int program){
        int counter=buffer(bytes(16)),output=buffer(bytes(6*64));var coordinator=new GpuFrameCoordinator();var writes=new GpuWriteReservations();int consumed=0,drawn=0;
        int render=GpuShaderCompiler.graphics("failed frame draw fixture","#version 450 core\nvoid main(){gl_Position=vec4(0,0,0,1);gl_PointSize=1;}",
                "#version 450 core\nout vec4 color;void main(){color=vec4(1);}");
        int vao=GL30.glGenVertexArrays(),query=GL15.glGenQueries();
        try {
            for(int attempt=1;attempt<=2;attempt++) {
                put(counter,bytes(16).putInt(4,1));put(output,bytes(6*64));coordinator.abort();coordinator.begin(attempt);writes.begin(attempt,GpuBufferNames.epoch(),4);
                GL15.glBeginQuery(GL30.GL_PRIMITIVES_GENERATED,query);
                try {
                    coordinator.simulate();coordinator.generate();var range=writes.appendDomain();writes.validate(range,GpuBufferNames.epoch());
                    dispatch(program,4,1,true,4,counter,output);
                    if(attempt==1)throw new IllegalStateException("Injected after actual GLSL output write");
                    coordinator.cullSort();writes.requireCommittable();var candidate=coordinator.candidate(attempt,output,0,1,counter,1,attempt,-1,-1);
                    coordinator.publish(candidate);consumed++;coordinator.draw();
                    GL42.glMemoryBarrier(GL42.GL_COMMAND_BARRIER_BIT);
                    GL20.glUseProgram(render);GL30.glBindVertexArray(vao);GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER,counter);
                    GL40.glDrawArraysIndirect(GL11.GL_POINTS,0L);drawn++;coordinator.finishDraw();
                } catch(IllegalStateException injected) {writes.fail();coordinator.abort();}
                finally {writes.retire();GL15.glEndQuery(GL30.GL_PRIMITIVES_GENERATED);}
                check(GL15.glGetQueryObjecti(query,GL15.GL_QUERY_RESULT)==(attempt==1?0:4),"Failed candidate reached an actual GPU draw");
                check(consumed==attempt-1&&drawn==attempt-1,"Failed candidate consumed input or drew");
                check(coordinator.publication()==null,"Aborted or completed publication remained visible");
                check(read(counter,16).getInt(0)==4,"Next candidate retained partial counters");
            }
        }finally {GL15.glDeleteQueries(query);GL30.glBindVertexArray(0);com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames.deleteVertexArray(vao);GL20.glUseProgram(0);GpuPrograms.delete(render);GpuBufferNames.delete(counter);GpuBufferNames.delete(output);}
    }
    private static void reloadFailure(int previous){
        int before=GpuUniforms.loc(previous,"uCapacity");boolean failed=false;
        try {GpuShaderCompiler.compute("deliberate invalid reload","#version 450 core\nthis is deliberately invalid GLSL");}
        catch(IllegalStateException expected){failed=true;}
        check(failed&&GL20.glIsProgram(previous),"Failed reload destroyed previous program");
        check(GpuUniforms.loc(previous,"uCapacity")==before,"Failed reload invalidated live program state");
        var writes=new GpuWriteReservations();int id=buffer(bytes(64));writes.begin(1,GpuBufferNames.epoch(),1);var old=writes.appendDomain();GpuBufferNames.delete(id);
        boolean refused=false;try {writes.validate(old,GpuBufferNames.epoch());}catch(IllegalStateException expected){refused=true;}
        check(refused,"Retired storage revived an old reservation");
    }
    private static void activeOwnerRetirement()throws Exception {
        var owner=new com.iridium126.createmanaindustry.client.particles.engine.ParticleBuffers();
        var cacheField=owner.getClass().getDeclaredField("bound");cacheField.setAccessible(true);var cached=(int[])cacheField.get(owner);
        int id=buffer(bytes(64));owner.beginBindings();
        try {
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,id);cached[0]=id;
            GpuBufferNames.delete(id);check(cached[0]==-1,"Active owner retained a retired binding name");
            check(GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,0)==0,"Retired GL name remained bound");
            int replacement=buffer(bytes(64));
            try {cached[0]=replacement;GpuBufferNames.delete(replacement);check(cached[0]==-1,"Replacement name escaped retirement notification");}
            finally {if(GL15.glIsBuffer(replacement))GpuBufferNames.delete(replacement);}
        }finally {owner.unbindShaders();if(GL15.glIsBuffer(id))GpuBufferNames.delete(id);}
        // Closing the scope unregisters the cache, including on exceptional exits.
        cached[0]=117;GpuBufferRetirement.retiring(117);check(cached[0]==117,"Closed owner remained registered");
    }
    public static void main(String[] args)throws Exception {
        check(GLFW.glfwInit(),"GLFW initialization");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);
        long window=GLFW.glfwCreateWindow(32,32,"CMI scene validation",0,0);check(window!=0,"OpenGL context");
        try {GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();System.out.println(GL11.glGetString(GL11.GL_RENDERER));int program=program();
            try {boundaries(program);failedCandidate(program);reloadFailure(program);activeOwnerRetirement();GL20.glUseProgram(0);check(GL11.glGetError()==0,"GL error");}
            finally {GpuPrograms.delete(program);}
            System.out.println("Scene frame GPU: "+checks+" assertions passed");
        }finally {GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
