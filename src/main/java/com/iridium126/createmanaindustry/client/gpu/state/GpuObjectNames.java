package com.iridium126.createmanaindustry.client.gpu.state;

import com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferRetirement;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Delete resource names only after every captured state has forgotten them. */
public final class GpuObjectNames {
    private GpuObjectNames() {}
    public static void deleteTexture(int texture) {
        if(texture==0)return;
        GpuBufferRetirement.retiringTexture(texture);GL11.glDeleteTextures(texture);
    }
    public static void deleteTexture(int[] textures) {for(int texture:textures)deleteTexture(texture);}
    public static void deleteTexture(java.nio.IntBuffer textures) {for(int i=textures.position();i<textures.limit();i++)deleteTexture(textures.get(i));}
    public static void deleteVertexArray(int vao) {
        if(vao==0)return;
        GpuBufferRetirement.retiringVertexArray(vao);GL30.glDeleteVertexArrays(vao);
    }
}
