package com.iridium126.createmanaindustry.client.particles.engine;

import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;
import com.mojang.blaze3d.systems.RenderSystem;

/** Compute/initialization boundary. No framebuffer, viewport or blend/depth writes. */
public final class ParticleComputeState {
    private static final int UNITS=16;
    private final int[] ssbo=new int[16],renderTextures=new int[UNITS];
    private final long[] offsets=new long[16],sizes=new long[16];
    private final int[][] textures=new int[UNITS][3];
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
        for(int i=0;i<16;i++){ssbo[i]=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,i);offsets[i]=ssbo[i]==0?0:GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START,i);sizes[i]=ssbo[i]==0?0:GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE,i);}
        for(int i=0;i<UNITS;i++){renderTextures[i]=RenderSystem.getShaderTexture(i);GL13.glActiveTexture(GL13.GL_TEXTURE0+i);
            textures[i][0]=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);textures[i][1]=GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);textures[i][2]=GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER);}
        // Lazy atlas/TBO initialization binds on the active unit. Confine it to
        // our saved unit 0 even when Iris entered on a higher pack sampler unit.
        open=true;GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }
    public void restore(){
        if(!open)return;open=false;
        try(var stack=MemoryStack.stackPush()){GL44.glBindBuffersBase(GL43.GL_SHADER_STORAGE_BUFFER,0,stack.ints(ssbo));}
        for(int i=0;i<16;i++)if(ssbo[i]!=0&&sizes[i]>0)GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,i,ssbo[i],offsets[i],sizes[i]);
        GL30.glBindVertexArray(vao);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,array);if(vao!=0)GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,element);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,storage);GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER,draw);GL15.glBindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER,dispatch);
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,read);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,write);GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER,textureBuffer);
        for(int i=0;i<UNITS;i++){RenderSystem.setShaderTexture(i,renderTextures[i]);GL13.glActiveTexture(GL13.GL_TEXTURE0+i);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,textures[i][0]);GL11.glBindTexture(GL12.GL_TEXTURE_3D,textures[i][1]);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,textures[i][2]);}
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,unpackBuffer);for(int i=0;i<UNPACK.length;i++)GL11.glPixelStorei(UNPACK[i],unpack[i]);
        RenderSystem.activeTexture(active);GL13.glActiveTexture(active);GL20.glUseProgram(program);
    }
}
