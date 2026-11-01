import java.nio.file.*;
import org.lwjgl.opengl.*;
final class R6IndexOrder implements AutoCloseable {
    final int program,buffer,capacity,mode;
    R6IndexOrder(int capacity,int mode)throws Exception{this.capacity=capacity;this.mode=mode;int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);GL20.glShaderSource(shader,Files.readString(Path.of("scripts/particles/r6_order.comp")));GL20.glCompileShader(shader);if(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==0)throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));program=GL20.glCreateProgram();GL20.glAttachShader(program,shader);GL20.glLinkProgram(program);GL20.glDeleteShader(shader);if(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(program));buffer=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,(12352L+capacity*2L)*4,GL15.GL_DYNAMIC_COPY);}
    void prepare(int models,boolean attack){
        if(mode==0)return;int previous=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,15);long offset=GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START,15),length=GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE,15);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,15,buffer);GL20.glUseProgram(program);R6Grid.ui(program,"uMode",mode);R6Grid.ui(program,"uCapacity",capacity);GL41.glProgramUniform1i(program,GL20.glGetUniformLocation(program,"uHasAttack"),attack?1:0);
        for(int phase=0;phase<7;phase++){R6Grid.ui(program,"uPhase",phase);int n=phase==0||phase==2||phase==4?4096:phase==3?1:models;GL43.glDispatchCompute(Math.max(1,(n+255)/256),1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);}
        if(length==0)GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,15,previous);else GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,15,previous,offset,length);
    }
    void verify(int population,int models,boolean attack){
        if(mode==0)return;
        int[] before=new int[8+models*2],after=new int[before.length];GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,population);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,before);prepare(models,attack);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,population);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,after);
        for(int i=0;i<8;i++)if(before[i]!=after[i])throw new AssertionError("order changed population header");
        long[] a=new long[models],b=new long[models];for(int i=0;i<models;i++){a[i]=Integer.toUnsignedLong(before[8+i*2])<<32|Integer.toUnsignedLong(before[9+i*2]);b[i]=Integer.toUnsignedLong(after[8+i*2])<<32|Integer.toUnsignedLong(after[9+i*2]);}java.util.Arrays.sort(a);java.util.Arrays.sort(b);if(!java.util.Arrays.equals(a,b))throw new AssertionError("order lost/duplicated pool-index and animation-slot pairing");
    }
    public void close(){GL20.glDeleteProgram(program);GL15.glDeleteBuffers(buffer);}
}
