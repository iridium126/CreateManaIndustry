package com.iridium126.createmanaindustry.client.gpu.state;

import com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferRetirement;
import org.lwjgl.opengl.*;

/** Narrow TBO boundary for Iris draws. Does not disturb the pack's other texture targets. */
public final class GpuTextureBufferState implements GpuBufferRetirement.Snapshot {
    private final int first;
    private final int[] textures;
    private int active;
    private boolean open;
    public GpuTextureBufferState(int first,int count){this.first=first;textures=new int[count];}
    public void capture(){
        if(open)throw new IllegalStateException("Nested texture buffer boundary");
        active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        for(int i=0;i<textures.length;i++){GL13.glActiveTexture(GL13.GL_TEXTURE0+first+i);textures[i]=GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER);}
        GL13.glActiveTexture(active);GpuBufferRetirement.capture(this);open=true;
    }
    @Override public void forgetBuffer(int id){}
    @Override public void forgetTexture(int id){for(int i=0;i<textures.length;i++)if(textures[i]==id)textures[i]=0;}
    public void restore(){
        if(!open)return;GpuBufferRetirement.restore(this);open=false;
        for(int i=0;i<textures.length;i++){GL13.glActiveTexture(GL13.GL_TEXTURE0+first+i);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,textures[i]);}
        GL13.glActiveTexture(active);
    }
}
