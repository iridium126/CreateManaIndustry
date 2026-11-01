import java.nio.*;
import java.nio.file.*;
import org.lwjgl.opengl.*;

/** Owned packed-query sidecar appended after the original grid/feather storage. Offline only. */
final class R6Grid implements AutoCloseable {
    final int program,capacity,buckets,base,bytes;final float cell;final boolean packed;
    R6Grid(int originalBytes,int capacity,int buckets,float cell,boolean packed)throws Exception{
        this.capacity=capacity;this.buckets=buckets;this.cell=cell;this.packed=packed;base=(originalBytes/4+63)&~63;
        int entries=(17+3*buckets+(buckets+255)/256+63)&~63;bytes=Math.multiplyExact(base+entries+capacity*8,4);
        int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);GL20.glShaderSource(shader,Files.readString(Path.of("scripts/particles/r6_grid.comp")));GL20.glCompileShader(shader);
        if(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==0)throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
        program=GL20.glCreateProgram();GL20.glAttachShader(program,shader);GL20.glLinkProgram(program);GL20.glDeleteShader(shader);if(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(program));
    }
    static void ui(int p,String name,int value){GL41.glProgramUniform1ui(p,GL20.glGetUniformLocation(p,name),value);}
    void configure(int p,long generation,int stage){
        ui(p,"uR6Base",base);ui(p,"uR6Capacity",capacity);ui(p,"uR6Buckets",buckets);ui(p,"uR6Stage",stage);
        GL41.glProgramUniform2ui(p,GL20.glGetUniformLocation(p,"uR6Generation"),(int)generation,(int)(generation>>>32));GL41.glProgramUniform1i(p,GL20.glGetUniformLocation(p,"uR6Packed"),packed?1:0);
    }
    void prepare(int input,int emitters,int grid,int live,long generation,int stage){
        if(!packed)return;
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,input);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,5,emitters);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,13,grid);
        ui(program,"uBase",base);ui(program,"uLive",live);ui(program,"uCapacity",capacity);ui(program,"uBuckets",buckets);ui(program,"uStage",stage);GL41.glProgramUniform2ui(program,GL20.glGetUniformLocation(program,"uGeneration"),(int)generation,(int)(generation>>>32));GL41.glProgramUniform1f(program,GL20.glGetUniformLocation(program,"uCell"),cell);
        GL20.glUseProgram(program);
        for(int phase=0;phase<6;phase++){ui(program,"uPhase",phase);int work=phase==1||phase==5?live:phase==3?1:buckets;GL43.glDispatchCompute(Math.max(1,(work+255)/256),1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);}
    }
    public void close(){GL20.glDeleteProgram(program);}
}
