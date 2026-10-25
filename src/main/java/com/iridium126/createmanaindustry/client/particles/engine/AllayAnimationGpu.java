package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames;
import com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms;
import com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames;
import org.lwjgl.opengl.*;
import org.lwjgl.BufferUtils;
import java.nio.IntBuffer;

/** Stable MODEL slots. Animation publication is independent of main-pool compaction. */
public final class AllayAnimationGpu implements AutoCloseable {
    public static final int CAPACITY=262144, BYTES=64;
    public static final int FREE=8, SUMMARY=8200, TOP=8456, ROOT=8464, RETIRED=8465, ALLOCATOR_WORDS=RETIRED+8457;
    private final int[] buffers=new int[2],textures=new int[2],allocators=new int[2],allocatorTextures=new int[2];
    private boolean allocated,pending,tickPending;
    private int read,allocatorRead;
    private double time,candidateTime;
    private long tick,candidateTick,version;
    public boolean allocated(){return allocated;}
    public long bytes(){return allocated?2L*CAPACITY*BYTES:0;}
    public long allocatorBytes(){return allocated?2L*ALLOCATOR_WORDS*4:0;}
    public int buffer(int bank){return buffers[bank];}
    public int allocatorBuffer(int bank){return allocators[bank];}
    public int readBank(){return read;}
    public int allocatorReadBank(){return allocatorRead;}
    public long tick(){return tick;}
    public long version(){return version;}
    public boolean tickPending(){return allocated&&pending&&tickPending;}
    public float alpha(){return (float)Math.clamp(time*20-tick,0,1);}
    public double time(){return time;}
    public void ensure(int ignored){
        if(allocated)return;
        if(4L*CAPACITY>GL11.glGetInteger(GL31.GL_MAX_TEXTURE_BUFFER_SIZE))throw new IllegalStateException("Allay animation texture capacity");
        int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE),old=GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER),storage=GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
        try{for(int i=0;i<2;i++){
            buffers[i]=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffers[i]);
            GpuTransfers.allocate(GL43.GL_SHADER_STORAGE_BUFFER,(long)CAPACITY*BYTES,GL15.GL_DYNAMIC_DRAW);
            GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(IntBuffer)null);
            textures[i]=texture(buffers[i],GL30.GL_RGBA32UI);
            allocators[i]=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,allocators[i]);
            GpuTransfers.allocate(GL43.GL_SHADER_STORAGE_BUFFER,ALLOCATOR_WORDS*4L,GL15.GL_DYNAMIC_DRAW);
            allocatorTextures[i]=texture(allocators[i],GL30.GL_R32UI);
        }allocated=true;resetAllocators();}catch(RuntimeException|Error failure){close();throw failure;}
        finally{GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,old);GL13.glActiveTexture(active);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,storage);}
    }
    private static int texture(int buffer,int format){int texture=GL11.glGenTextures();GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,texture);GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,format,buffer);return texture;}
    private void resetAllocators(){
        IntBuffer initial=BufferUtils.createIntBuffer(ALLOCATOR_WORDS);
        for(int i=FREE;i<ROOT;i++)initial.put(i,-1);initial.put(ROOT,255);
        for(int buffer:allocators){GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,0,initial);}
    }
    public void clear(){
        if(allocated){barrier();for(int buffer:buffers){GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(IntBuffer)null);}resetAllocators();}
        read=allocatorRead=0;time=candidateTime=0;tick=candidateTick=version=0;pending=tickPending=false;
    }
    public void beginFrame(float dt){
        pending=true;candidateTime=time+Math.max(0,dt);candidateTick=(long)Math.floor(candidateTime*20+1e-7);tickPending=candidateTick>tick;
    }
    public void prepareAllocator(int program){
        if(!allocated)return;barrier();GL20.glUseProgram(program);
        GL42.glBindImageTexture(1,allocatorTextures[allocatorRead],0,false,0,GL15.GL_READ_ONLY,GL30.GL_R32UI);
        GL42.glBindImageTexture(3,allocatorTextures[1-allocatorRead],0,false,0,GL15.GL_WRITE_ONLY,GL30.GL_R32UI);
        GL43.glDispatchCompute((ALLOCATOR_WORDS+63)/64,1,1);barrier();
    }
    public void commit(){if(!pending)return;if(allocated){allocatorRead=1-allocatorRead;if(tickPending){read=1-read;version++;}}time=candidateTime;if(tickPending)tick=candidateTick;pending=false;}
    public void abort(){pending=false;tickPending=false;}
    public static void barrier(){GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT|GL42.GL_TEXTURE_FETCH_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT);}
    /** Production binding, using the animation clock and allocator's own generation. */
    public void bindCompute(int program){
        bindCompute(program,read);
        GL41.glProgramUniform1i(program,GpuUniforms.loc(program,"uModelAllocatorEnabled"),allocated?1:0);
        GL41.glProgramUniform1i(program,GpuUniforms.loc(program,"uAllayAnimationClockEnabled"),1);
        GL41.glProgramUniform1ui(program,GpuUniforms.loc(program,"uAllayAnimationTick"),(int)candidateTick);
        GL41.glProgramUniform1f(program,GpuUniforms.loc(program,"uAllayAnimationFraction"),(float)Math.clamp(candidateTime*20-candidateTick,0,1));
        GL41.glProgramUniform1f(program,GpuUniforms.loc(program,"uAllayAnimationAlpha"),alpha());
        if(allocated&&GpuUniforms.loc(program,"cmi_ModelAllocator")>=0)GL42.glBindImageTexture(3,allocatorTextures[1-allocatorRead],0,false,0,GL15.GL_READ_WRITE,GL30.GL_R32UI);
    }
    /** Explicit-bank binding for standalone numerical oracles. */
    public void bindCompute(int program,int bank){
        GL41.glProgramUniform1i(program,GpuUniforms.loc(program,"uAllayAnimationWriteEnabled"),allocated?1:0);
        GL41.glProgramUniform1i(program,GpuUniforms.loc(program,"cmi_AllayAnimationRead"),3);
        if(!allocated)return;
        boolean sample=GpuUniforms.loc(program,"cmi_AllayAnimationRead")>=0,write=GpuUniforms.loc(program,"cmi_AllayAnimationWrite")>=0,initialize=GpuUniforms.loc(program,"cmi_AllayAnimationInitial")>=0;
        if(sample||write||initialize)barrier();
        if(sample){int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);GL13.glActiveTexture(GL13.GL_TEXTURE3);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,textures[bank]);GL13.glActiveTexture(active);}
        if(write)GL42.glBindImageTexture(0,textures[1-bank],0,false,0,GL15.GL_READ_WRITE,GL30.GL_RGBA32UI);
        if(initialize)GL42.glBindImageTexture(1,textures[bank],0,false,0,GL15.GL_READ_WRITE,GL30.GL_RGBA32UI);
    }
    public void bindDraw(int program){bindDraw(program,read);GL41.glProgramUniform1f(program,GpuUniforms.loc(program,"uAllayAnimationAlpha"),alpha());}
    public void bindDraw(int program,int bank){
        GL41.glProgramUniform1i(program,GpuUniforms.loc(program,"uAllayAnimationEnabled"),allocated?1:0);
        GL41.glProgramUniform1i(program,GpuUniforms.loc(program,"cmi_AllayAnimation"),15);
        if(!allocated)return;barrier();int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE15);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,textures[bank]);GL13.glActiveTexture(active);
    }
    @Override public void close(){for(int i=0;i<2;i++){
        if(textures[i]!=0)GpuObjectNames.deleteTexture(textures[i]);if(allocatorTextures[i]!=0)GpuObjectNames.deleteTexture(allocatorTextures[i]);
        if(buffers[i]!=0)GpuBufferNames.delete(buffers[i]);if(allocators[i]!=0)GpuBufferNames.delete(allocators[i]);
        textures[i]=buffers[i]=allocators[i]=allocatorTextures[i]=0;
    }allocated=pending=tickPending=false;read=allocatorRead=0;time=candidateTime=0;tick=candidateTick=version=0;}
}
