import com.iridium126.createmanaindustry.client.particles.engine.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
public class ModelNativeColorGpuValidation {
    static String source(boolean frozen){
        return ParticleShaderSource.loadParticle("model.vsh",path->{try{return Files.readString(frozen?
            Path.of(".local/particle-baselines/reference/model-draw-20261030/src/main/resources/assets/createmanaindustry",path+".txt"):
            Path.of("src/main/resources/assets/createmanaindustry",path));}catch(Exception e){throw new RuntimeException(e);}});
    }
    static int program(boolean frozen){
        int s=GL20.glCreateShader(GL20.GL_VERTEX_SHADER),p=GL20.glCreateProgram();
        try{
            GL20.glShaderSource(s,"#version 450 core\n"+ParticlePrograms.commonPrelude()+source(frozen));GL20.glCompileShader(s);
            if(GL20.glGetShaderi(s,GL20.GL_COMPILE_STATUS)==0)throw new AssertionError(GL20.glGetShaderInfoLog(s));
            GL20.glAttachShader(p,s);GL30.glTransformFeedbackVaryings(p,new String[]{"gl_Position","vColor","vNormalView","vOverlay"},GL30.GL_INTERLEAVED_ATTRIBS);GL20.glLinkProgram(p);
            if(GL20.glGetProgrami(p,GL20.GL_LINK_STATUS)==0)throw new AssertionError(GL20.glGetProgramInfoLog(p));return p;
        }finally{GL20.glDeleteShader(s);}
    }
    static void upload(int buffer,float[] data){GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,data,GL15.GL_DYNAMIC_DRAW);}
    static int[] capture(int p,int output,int vertices){
        GL20.glUseProgram(p);float[] identity={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
        for(String name:new String[]{"ModelViewMat","ProjMat"})GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(p,name),false,identity);
        GL20.glUniform1f(GL20.glGetUniformLocation(p,"uFadeDist"),4096);
        GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,0,output);
        GL30.glBeginTransformFeedback(GL11.GL_POINTS);GL11.glDrawArrays(GL11.GL_POINTS,0,vertices);GL30.glEndTransformFeedback();
        GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,output);int[] result=new int[vertices*11];GL15.glGetBufferSubData(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,0,result);return result;
    }
    public static void main(String[] args)throws Exception{
        if(!GLFW.glfwInit())throw new AssertionError("GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);
        long window=GLFW.glfwCreateWindow(64,64,"Native MODEL color",0,0);if(window==0)throw new AssertionError("GL context");
        try{
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
            var field=AllayModelGeometry.class.getDeclaredField("VERTICES");field.setAccessible(true);float[] mesh=(float[])field.get(null);int vertices=mesh.length/AllayModelGeometry.VERTEX_FLOATS;
            int vao=GL30.glGenVertexArrays();GL30.glBindVertexArray(vao);int[] buffers=new int[5];GL15.glGenBuffers(buffers);int a=program(true),b=program(false);long words=0;
            try{
                upload(buffers[0],new float[16]);upload(buffers[1],new float[80]);upload(buffers[2],new float[4]);upload(buffers[3],mesh);
                int[] bindings={ParticleBuffers.PARTICLE_BB_WRITE,ParticleBuffers.EMITTER_BB,ParticleBuffers.SORTWRITE_BINDING,ParticleBuffers.MODELGEO_BINDING};for(int i=0;i<4;i++)GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,bindings[i],buffers[i]);
                GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,buffers[4]);GL15.glBufferData(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,vertices*11L*4,GL15.GL_STREAM_READ);
                GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
                for(int count=0;count<=9;count++)for(float life:new float[]{0,.125f,.5f,.999999f,1})for(int pose=0;pose<3;pose++){
                    float[] header=new float[80];header[22]=header[23]=.07f;header[24]=1;header[26]=count;header[70]=3;
                    for(int i=0;i<8;i++){header[32+i*4]=.1f+i*.1f;header[33+i*4]=.8f-i*.08f;header[34+i*4]=i%2==0?.25f:.9f;header[35+i*4]=1;}
                    float[] particle={0,0,0,1,1.5f,0,-1,0,1,.8f,.9f,pose==1?.4f:0,60*life,pose==2?-.25f:60,.6f,0};
                    upload(buffers[0],particle);upload(buffers[1],header);
                    int[] original=capture(a,buffers[4],vertices),candidate=capture(b,buffers[4],vertices);
                    if(original[4]==0||original[3]!=Float.floatToRawIntBits(1))throw new AssertionError("Native oracle produced no valid vertex");
                    if(!Arrays.equals(original,candidate))throw new AssertionError("Native MODEL mismatch count="+count+" life="+life+" pose="+pose);
                    words+=original.length;
                }
                GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);if(GL11.glGetError()!=0)throw new AssertionError("Native GL state");
                System.out.println("Native MODEL keyframes: "+words+" exact transform-feedback words passed");
            }finally{GL20.glDeleteProgram(a);GL20.glDeleteProgram(b);GL15.glDeleteBuffers(buffers);GL30.glDeleteVertexArrays(vao);}
        }finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
