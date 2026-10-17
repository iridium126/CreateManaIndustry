package com.iridium126.createmanaindustry.client.particles.engine;

import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;
import com.mojang.blaze3d.systems.RenderSystem;
import com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferRetirement;

/** Compute/initialization boundary. No framebuffer, viewport or blend/depth writes. */
public final class ParticleComputeState implements GpuBufferRetirement.Snapshot {
    // Render-thread stack: resource retirement is rare, so validate saved names
    // at that boundary rather than issuing glIs* queries on every frame restore.
    private static ParticleComputeState activeBoundary;
    private ParticleComputeState parent;
    private static final int UNITS=16, BINDINGS=ParticleBuffers.IDENTITY_BB+1;
    private final int units;
    private final int[] targets,bindings;
    private final int[] ssbo=new int[BINDINGS],renderTextures=new int[UNITS];
    private final long[] offsets=new long[BINDINGS],sizes=new long[BINDINGS];
    private final int[][] textures;
    public ParticleComputeState(){this(false);}
    public ParticleComputeState(boolean shaderSamplers){
        units=shaderSamplers?Math.max(UNITS,GL11.glGetInteger(GL20.GL_MAX_TEXTURE_IMAGE_UNITS)):UNITS;
        targets=shaderSamplers?new int[]{GL11.GL_TEXTURE_2D,GL12.GL_TEXTURE_3D,GL31.GL_TEXTURE_BUFFER,GL11.GL_TEXTURE_1D,GL13.GL_TEXTURE_CUBE_MAP,GL30.GL_TEXTURE_2D_ARRAY,GL31.GL_TEXTURE_RECTANGLE}:new int[]{GL11.GL_TEXTURE_2D,GL12.GL_TEXTURE_3D,GL31.GL_TEXTURE_BUFFER};
        bindings=shaderSamplers?new int[]{GL11.GL_TEXTURE_BINDING_2D,GL12.GL_TEXTURE_BINDING_3D,GL31.GL_TEXTURE_BINDING_BUFFER,GL11.GL_TEXTURE_BINDING_1D,GL13.GL_TEXTURE_BINDING_CUBE_MAP,GL30.GL_TEXTURE_BINDING_2D_ARRAY,GL31.GL_TEXTURE_BINDING_RECTANGLE}:new int[]{GL11.GL_TEXTURE_BINDING_2D,GL12.GL_TEXTURE_BINDING_3D,GL31.GL_TEXTURE_BINDING_BUFFER};
        textures=new int[units][targets.length];
    }
    private int program,vao,array,element,storage,draw,dispatch,read,write,active,textureBuffer;
    private static final int[] UNPACK={GL11.GL_UNPACK_ALIGNMENT,GL11.GL_UNPACK_ROW_LENGTH,GL11.GL_UNPACK_SKIP_ROWS,GL11.GL_UNPACK_SKIP_PIXELS,GL12.GL_UNPACK_IMAGE_HEIGHT,GL12.GL_UNPACK_SKIP_IMAGES};
    private final int[] unpack=new int[UNPACK.length];private int unpackBuffer;
    private boolean open;
    public void capture(){
        if(open)throw new IllegalStateException("Nested compute state boundary");
        program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);vao=GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        array=GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);element=GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        storage=GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);draw=GL11.glGetInteger(GL40.GL_DRAW_INDIRECT_BUFFER_BINDING);
        dispatch=GL11.glGetInteger(GL43.GL_DISPATCH_INDIRECT_BUFFER_BINDING);read=GL11.glGetInteger(GL31.GL_COPY_READ_BUFFER);write=GL11.glGetInteger(GL31.GL_COPY_WRITE_BUFFER);
        active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);textureBuffer=GL11.glGetInteger(GL31.GL_TEXTURE_BUFFER);
        unpackBuffer=GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);for(int i=0;i<UNPACK.length;i++)unpack[i]=GL11.glGetInteger(UNPACK[i]);
        for(int i=0;i<BINDINGS;i++){ssbo[i]=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,i);offsets[i]=ssbo[i]==0?0:GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START,i);sizes[i]=ssbo[i]==0?0:GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE,i);}
        for(int i=0;i<units;i++){if(i<UNITS)renderTextures[i]=RenderSystem.getShaderTexture(i);GL13.glActiveTexture(GL13.GL_TEXTURE0+i);for(int j=0;j<bindings.length;j++)textures[i][j]=GL11.glGetInteger(bindings[j]);}
        // Lazy atlas/TBO initialization binds on the active unit. Confine it to
        // our saved unit 0 even when Iris entered on a higher pack sampler unit.
        GpuBufferRetirement.capture(this);open=true;parent=activeBoundary;activeBoundary=this;GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }
    /** Call after deleting resources, before their GL names can be reused. */
    public static void resourcesRetired(){
        for(var state=activeBoundary;state!=null;state=state.parent)state.forgetDeletedResources();
    }
    private static int liveBuffer(int id){return id==0||GL15.glIsBuffer(id)?id:0;}
    private static int liveTexture(int id){return id==0||GL11.glIsTexture(id)?id:0;}
    @Override public void forgetBuffer(int id){
        for(int i=0;i<BINDINGS;i++)if(ssbo[i]==id){ssbo[i]=0;offsets[i]=sizes[i]=0;}
        if(array==id)array=0;if(element==id)element=0;if(storage==id)storage=0;if(draw==id)draw=0;
        if(dispatch==id)dispatch=0;if(read==id)read=0;if(write==id)write=0;if(textureBuffer==id)textureBuffer=0;if(unpackBuffer==id)unpackBuffer=0;
    }
    private void forgetDeletedResources(){
        for(int i=0;i<BINDINGS;i++)ssbo[i]=liveBuffer(ssbo[i]);
        array=liveBuffer(array);element=liveBuffer(element);storage=liveBuffer(storage);draw=liveBuffer(draw);
        dispatch=liveBuffer(dispatch);read=liveBuffer(read);write=liveBuffer(write);textureBuffer=liveBuffer(textureBuffer);unpackBuffer=liveBuffer(unpackBuffer);
        if(vao!=0&&!GL30.glIsVertexArray(vao))vao=0;
        if(program!=0&&(!GL20.glIsProgram(program)||GL20.glGetProgrami(program,GL20.GL_DELETE_STATUS)!=0))program=0;
        for(int i=0;i<units;i++)for(int j=0;j<targets.length;j++)textures[i][j]=liveTexture(textures[i][j]);
        for(int i=0;i<UNITS;i++)renderTextures[i]=liveTexture(renderTextures[i]);
    }
    public void restore(){
        if(!open)return;open=false;
        GpuBufferRetirement.restore(this);
        if(activeBoundary==this)activeBoundary=parent;
        else for(var state=activeBoundary;state!=null;state=state.parent)if(state.parent==this){state.parent=parent;break;}
        parent=null;
        try(var stack=MemoryStack.stackPush()){GL44.glBindBuffersBase(GL43.GL_SHADER_STORAGE_BUFFER,0,stack.ints(ssbo));}
        for(int i=0;i<BINDINGS;i++)if(ssbo[i]!=0&&sizes[i]>0)GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,i,ssbo[i],offsets[i],sizes[i]);
        GL30.glBindVertexArray(vao);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,array);if(vao!=0)GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,element);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,storage);GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER,draw);GL15.glBindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER,dispatch);
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,read);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,write);GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER,textureBuffer);
        for(int i=0;i<units;i++){if(i<UNITS)RenderSystem.setShaderTexture(i,renderTextures[i]);GL13.glActiveTexture(GL13.GL_TEXTURE0+i);for(int j=0;j<targets.length;j++)GL11.glBindTexture(targets[j],textures[i][j]);}
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,unpackBuffer);for(int i=0;i<UNPACK.length;i++)GL11.glPixelStorei(UNPACK[i],unpack[i]);
        RenderSystem.activeTexture(active);GL13.glActiveTexture(active);GL20.glUseProgram(program);
    }
}
