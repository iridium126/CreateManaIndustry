import java.nio.*;
import org.lwjgl.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.joml.Matrix4f;
import com.iridium126.createmanaindustry.client.particles.engine.*;

/** Real production compute kernels in a hidden GL context; never starts Minecraft. */
public class SharedFateGpuValidation {
    static int checks;
    static int preparation,reservation;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static ByteBuffer bytes(int size){return BufferUtils.createByteBuffer(size);}
    static String source(String name){return ParticleShaderSource.loadParticle(name,path->{
        try(var in=SharedFateGpuValidation.class.getClassLoader().getResourceAsStream("assets/createmanaindustry/"+path)){
            return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }catch(Exception e){throw new IllegalStateException(path,e);}
    });}
    static int program(String name,int type,String prefix){
        int shader=GL20.glCreateShader(type);GL20.glShaderSource(shader,"#version 450 core\n"+prefix+source(name));
        GL20.glCompileShader(shader);check(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)!=0,name+": "+GL20.glGetShaderInfoLog(shader));
        int program=GL20.glCreateProgram();GL20.glAttachShader(program,shader);GL20.glLinkProgram(program);
        check(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(program));
        GL20.glDeleteShader(shader);return program;
    }
    static int buffer(int size){int id=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,bytes(size),GL15.GL_DYNAMIC_COPY);return id;}
    static void bind(int slot,int id){GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,slot,id);}
    static void put(int id,ByteBuffer value){GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,value);}
    static ByteBuffer read(int id,int size){GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);var result=bytes(size);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,result);return result;}
    static void ui(int p,String key,int value){GL41.glProgramUniform1ui(p,GL20.glGetUniformLocation(p,key),value);if(p==preparation)GL41.glProgramUniform1ui(reservation,GL20.glGetUniformLocation(reservation,key),value);}
    static void f(int p,String key,float value){GL41.glProgramUniform1f(p,GL20.glGetUniformLocation(p,key),value);}
    static void matrix(int p,String key,Matrix4f value){GL41.glProgramUniformMatrix4fv(p,GL20.glGetUniformLocation(p,key),false,value.get(new float[16]));}
    static void dispatch(int p,int n){GL20.glUseProgram(p);GL43.glDispatchCompute(n,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);if(p==preparation){GL20.glUseProgram(reservation);GL43.glDispatchCompute(1,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);}}
    static ByteBuffer input(float x,float y,float z,float time,float stop,boolean animate,int slot){
        return bytes(80).putFloat(0,0).putFloat(4,0).putFloat(8,-10).putFloat(12,2)
            .putFloat(16,x).putFloat(20,y).putFloat(24,z).putFloat(28,2)
            .putFloat(32,time).putFloat(36,stop).putFloat(40,stop<0?0:1).putFloat(44,37)
            .putInt(48,slot).putInt(52,1).putInt(56,animate?1:0);
    }
    static void kernels(){
        int prepare=program("shared_fate_prepare.comp",GL43.GL_COMPUTE_SHADER,"");
        int emit=program("shared_fate_emit.comp",GL43.GL_COMPUTE_SHADER,"");
        int reserve=program("shared_fate_reserve.comp",GL43.GL_COMPUTE_SHADER,"");preparation=prepare;reservation=reserve;
        int input=buffer(160),jobs=buffer(160),previous=buffer(160),next=buffer(160),counter=buffer(16),pool=buffer((8192+1)*64);
        try{
            bind(0,input);bind(1,jobs);bind(2,previous);bind(3,next);bind(4,counter);bind(5,pool);
            ui(prepare,"uCount",1);ui(prepare,"uCapacity",8192);ui(prepare,"uBudget",8192);
            f(prepare,"uFadeDistance",256);f(prepare,"uViewportHeight",1080);
            GL41.glProgramUniform4fv(prepare,GL20.glGetUniformLocation(prepare,"uFrustum"),new float[24]);
            for(int p:new int[]{prepare,emit}){
                matrix(p,"uView",new Matrix4f());matrix(p,"uProjection",new Matrix4f().perspective((float)Math.toRadians(70),1.777f,.05f,20000));
                f(p,"uViewportHeight",1080);f(p,"uTime",.1f);
            }
            ui(emit,"uEmitter",7);
            for(float[] endpoint:new float[][]{{4,0,-10},{0,8,-10},{0,0,-10},{.001f,100,-10},{1000,300,-10}}){
                put(input,input(endpoint[0],endpoint[1],endpoint[2],0,-1,true,0));put(counter,bytes(16));put(previous,bytes(160));
                dispatch(prepare,1);dispatch(emit,1);
                var j=read(jobs,64);int count=j.getInt(28);check(count>=16&&count<=8192,"sample count bounded");
                check(j.getFloat(4)==1.1f&&j.getFloat(20)==endpoint[1]+1.1f,"endpoint heights consistent");
                var particles=read(pool,(8192+1)*64);
                for(int sample=0;sample<count;sample++)for(int k=0;k<15;k++)check(Float.isFinite(particles.getFloat(sample*64+k*4)),"finite degenerate particle");
                check(particles.getFloat(44)>0&&particles.getFloat(44)<.45,"condensation starts below sustained glow");
                // Sentinels after the destination allocation are untouched.
                check(particles.getFloat(8192*64)==0,"emit overran capacity");
            }
            put(input,input(4,0,-10,0,-1,true,0));put(counter,bytes(16));put(previous,bytes(160));
            f(prepare,"uTime",.1f);dispatch(prepare,1);dispatch(emit,1);
            var frozen=read(next,160);float initialGlow=frozen.getFloat(12);
            put(previous,frozen);
            var stopped=input(100,50,-10,0,.1f,true,0).putFloat(64,-3);
            put(input,stopped);put(counter,bytes(16));dispatch(prepare,1);dispatch(emit,1);
            var job=read(jobs,64);
            check(job.getFloat(0)==-3&&job.getFloat(16)==1,"dissipation freezes GPU pose with camera shift");
            check(Math.abs(job.getFloat(48)-initialGlow)<1e-5,"interrupted condensation has no glow jump");
            var state=read(next,160);put(previous,state);put(input,stopped.putFloat(64,0));
            put(counter,bytes(16));f(prepare,"uTime",.5f);dispatch(prepare,1);dispatch(emit,1);
            job=read(jobs,64);check(job.getFloat(0)==-3&&job.getFloat(16)==1,"stopped wire never follows live endpoints");
            check(job.getFloat(48)<initialGlow,"dissipation fades");
            put(counter,bytes(16));f(prepare,"uTime",.91f);dispatch(prepare,1);check(read(counter,16).getInt(0)==0,"dissipation finishes");
            // Two links, total budget and native pool capacity share the same allocation boundary.
            var pair=bytes(160);pair.put(input(4,0,-10,0,-1,false,0)).put(input(4,1,-10,0,-1,false,1)).flip();put(input,pair);
            ui(prepare,"uCount",2);ui(prepare,"uBudget",32);put(counter,bytes(16));dispatch(prepare,1);dispatch(emit,2);
            check(read(counter,16).getInt(0)==32,"shared budget exceeded");
            put(counter,bytes(16).putInt(0,8190));dispatch(prepare,1);dispatch(emit,2);
            check(read(counter,16).getInt(0)==8192,"native pool boundary exceeded");
            check(read(pool,(8192+1)*64).getInt(8192*64)==0,"boundary sentinel modified");
        }finally{preparation=reservation=0;for(int p:new int[]{prepare,reserve,emit})GL20.glDeleteProgram(p);for(int b:new int[]{input,jobs,previous,next,counter,pool})GL15.glDeleteBuffers(b);}
    }
    static void batches(){
        int prepare=program("shared_fate_prepare.comp",GL43.GL_COMPUTE_SHADER,"");
        int reserve=program("shared_fate_reserve.comp",GL43.GL_COMPUTE_SHADER,"");
        int emit=program("shared_fate_emit.comp",GL43.GL_COMPUTE_SHADER,"");preparation=prepare;reservation=reserve;
        int capacity=32768,budget=16384;
        try{for(int count:new int[]{1,63,64,65,8192}){
            int input=buffer(count*80),jobs=buffer(count*80),previous=buffer(count*80),next=buffer(count*80),counter=buffer(16),pool=buffer((capacity+1)*64);
            try{
                var entries=bytes(count*80);
                for(int i=0;i<count;i++)entries.put(input(4,i*.001f,-10,0,-1,false,i));
                entries.flip();put(input,entries);put(pool,bytes((capacity+1)*64).putInt(capacity*64,0x12345678));
                bind(0,input);bind(1,jobs);bind(2,previous);bind(3,next);bind(4,counter);bind(5,pool);
                ui(prepare,"uCount",count);ui(prepare,"uCapacity",capacity);ui(prepare,"uBudget",budget);
                f(prepare,"uFadeDistance",256);GL41.glProgramUniform4fv(prepare,GL20.glGetUniformLocation(prepare,"uFrustum"),new float[24]);
                for(int p:new int[]{prepare,emit}){
                    matrix(p,"uView",new Matrix4f());matrix(p,"uProjection",new Matrix4f().perspective((float)Math.toRadians(70),1.777f,.05f,20000));
                    f(p,"uViewportHeight",1080);f(p,"uTime",1);
                }
                ui(emit,"uEmitter",7);dispatch(prepare,(count+63)/64);dispatch(emit,count);
                int live=read(counter,16).getInt(0);check(live>0&&live<=budget,"parallel batch shares global budget");
                var job=read(jobs,count*80);var state=read(next,count*80);int offset=0;
                for(int i=0;i<count;i++){
                    int assigned=job.getInt(i*80+28);
                    check(job.getInt(i*80+12)==offset,"batch reservation overlaps or has a gap");
                    check(assigned>=0&&assigned<=8192,"per-line budget");
                    check(state.getFloat(i*80+40)==assigned,"freeze saves actual generated density");
                    offset+=assigned;
                }
                check(offset==live,"allocation census agrees");check(read(pool,(capacity+1)*64).getInt(capacity*64)==0x12345678,"parallel emission buffer sentinel");
            }finally{for(int b:new int[]{input,jobs,previous,next,counter,pool})GL15.glDeleteBuffers(b);}
        }}finally{preparation=reservation=0;for(int p:new int[]{prepare,reserve,emit})GL20.glDeleteProgram(p);}
    }
    static void math(){
        // Exercise production catenary endpoints directly, without jitter or rendering.
        String text="#version 450 core\n"+source("chunks/shared_fate.glsl")+"\nlayout(local_size_x=1)in;layout(std430,binding=0)buffer Result{vec4 points[];};uniform vec3 uA,uB;void main(){vec3 c=cmiFateCoefficients(uA,uB);for(int i=0;i<=100;i++)points[i]=vec4(cmiFateCurve(uA,uB,c,float(i)/100.),1);}";
        int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);GL20.glShaderSource(shader,text);GL20.glCompileShader(shader);check(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)!=0,GL20.glGetShaderInfoLog(shader));
        int p=GL20.glCreateProgram();GL20.glAttachShader(p,shader);GL20.glLinkProgram(p);check(GL20.glGetProgrami(p,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(p));GL20.glDeleteShader(shader);
        int out=buffer(101*16);bind(0,out);
        try{for(float d:new float[]{0,.0001f,.001f,.1f,1,4,50,1000})for(float dy:new float[]{0,10,300,-300}){
            GL41.glProgramUniform3f(p,GL20.glGetUniformLocation(p,"uA"),0,0,0);GL41.glProgramUniform3f(p,GL20.glGetUniformLocation(p,"uB"),d,dy,0);dispatch(p,1);
            var points=read(out,101*16);check(points.getFloat(0)==0&&points.getFloat(100*16)==d&&points.getFloat(100*16+4)==dy,"exact catenary endpoints");
            for(int i=0;i<=100;i++){float y=points.getFloat(i*16+4);check(Float.isFinite(y),"finite catenary");check(y<=dy*i/100f+.001f,"catenary never rises over its chord");}
        }}finally{GL15.glDeleteBuffers(out);GL20.glDeleteProgram(p);}
    }
    public static void main(String[] args){
        GLFWErrorCallback.createPrint(System.err).set();check(GLFW.glfwInit(),"GLFW initialization");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);
        long window=GLFW.glfwCreateWindow(64,64,"Shared Fate shader validation",0,0);
        try{check(window!=0,"hidden GPU context");GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
            System.out.println(GL11.glGetString(GL11.GL_RENDERER));math();kernels();batches();check(GL11.glGetError()==0,"no GL error");
            System.out.println("Shared Fate GPU: "+checks+" assertions passed");
        }finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}

