import java.nio.*;
import java.nio.file.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;

/** Real-driver extraction checks; no world and no performance samples. */
public final class R6QuerySequenceGpu extends ModelPopulationGpuValidation {
    public static void main(String[] args)throws Exception{
        check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(32,32,"R6 bounded sequence kernel",0,0);check(window!=0,"context");GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
        String source=Files.readString(Path.of("src/main/resources/assets/createmanaindustry/shaders/particles/diagnostics/query_sequence.comp"));int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);GL20.glShaderSource(shader,source);GL20.glCompileShader(shader);check(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)!=0,GL20.glGetShaderInfoLog(shader));int p=GL20.glCreateProgram();GL20.glAttachShader(p,shader);GL20.glLinkProgram(p);GL20.glDeleteShader(shader);check(GL20.glGetProgrami(p,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(p));
        try{
            for(int n:new int[]{0,1,63,64,65,255,256,257,1000,10000,50000,65535,65536,131072})for(int failure=0;failure<3;failure++){
                int cap=Math.max(1,n);ByteBuffer particles=bytes(cap*64),population=bytes(32+cap*12);population.putInt(0,n).putInt(4,n).putInt(8,n).putInt(12,1).putInt(16,failure==1?16:17).putInt(24,cap).putInt(28,cap);
                for(int i=0;i<n;i++){particles.putFloat(i*64,i*.01f).putFloat(i*64+4,-i*.02f).putFloat(i*64+8,1000).putFloat(i*64+12,i+1).putFloat(i*64+16,1);population.putInt(32+cap*8+i*4,failure==2&&i==n-1?n:i);}
                int input=buffer(particles),pop=buffer(population),previous=buffer(bytes(16).putInt(0,n)),output=buffer(bytes(64+cap*32));
                try{bind(0,input);bind(14,previous);bind(24,pop);bind(15,output);ui(p,"uRecordCapacity",131072);GL41.glProgramUniform2ui(p,GL20.glGetUniformLocation(p,"uInputGeneration"),17,0);ui(p,"uPhase",0);dispatch(p,1);ui(p,"uPhase",1);dispatch(p,512);
                    ByteBuffer result=read(output,64+cap*32);boolean valid=failure==0||failure==2&&n==0;check(result.getInt(4)==(valid?1:0),"valid header n="+n+" failure="+failure);check(result.getInt(0)==n,"count");
                    if(valid)for(int i=0;i<n;i++){check(result.getInt(64+i*32+12)==i,"stable identity");check(result.getInt(64+i*32+28)==i,"pool slot");for(int k=0;k<3;k++)check(result.getInt(64+i*32+k*4)==particles.getInt(i*64+k*4),"exact read-pool position");}
                    check(read(input,particles.capacity()).equals(particles),"read-only input");check(GL11.glGetError()==0,"GL state");
                }finally{for(int b:new int[]{input,pop,previous,output})GL15.glDeleteBuffers(b);}
            }
            Path out=Path.of(System.getProperty("cmi.benchmark.output"));Files.createDirectories(out);Files.writeString(out.resolve("sequence-kernel.txt"),"Passed "+checks+" checks. Extractor kernel only; asynchronous queue/lifecycle/client tests remain separate.\n",StandardOpenOption.CREATE_NEW);
        }finally{deleteProgram(p);GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
