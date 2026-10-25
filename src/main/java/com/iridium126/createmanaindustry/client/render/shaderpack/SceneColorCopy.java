package com.iridium126.createmanaindustry.client.render.shaderpack;

import org.lwjgl.opengl.*;

/** Owned color snapshot. Raw GL only, so the actual copy can be exercised without a Minecraft client. */
public final class SceneColorCopy implements AutoCloseable {
    private int texture,width,height;
    public int capture(int w,int h){
        if(w<=0||h<=0)throw new IllegalArgumentException("Empty scene target");
        int draw=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),read=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),previousTexture=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        if(draw==0)throw new IllegalStateException("Scene target must be a texture framebuffer");
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,draw);int readBuffer=GL11.glGetInteger(GL11.GL_READ_BUFFER);
        try {
            if(texture==0)texture=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
            if(width!=w||height!=h){
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL30.GL_RGBA16F,w,h,0,GL11.GL_RGBA,GL11.GL_FLOAT,(java.nio.ByteBuffer)null);width=w;height=h;
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_LINEAR);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
            }
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,0,0,0,w,h);return texture;
        }finally{GL11.glReadBuffer(readBuffer);GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,read);GL11.glBindTexture(GL11.GL_TEXTURE_2D,previousTexture);}
    }
    public void close(){if(texture!=0)GL11.glDeleteTextures(texture);texture=width=height=0;}
}
