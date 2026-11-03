package com.iridium126.createmanaindustry.client.allaystorm.rift;
import org.lwjgl.opengl.*;

/** Target-only transfers: blits participate in the same conditional render as draws. */
public final class RiftTargetTransfer {
    private static int read,draw;
    private RiftTargetTransfer(){}
    private static void ensure(){if(read==0){read=GL30.glGenFramebuffers();draw=GL30.glGenFramebuffers();}}
    public static void copy(int source,int destination,int width,int height,boolean depth){
        ensure();int oldRead=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),oldDraw=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int attachment=depth?GL30.GL_DEPTH_ATTACHMENT:GL30.GL_COLOR_ATTACHMENT0;
        try{
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,read);GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER,attachment,GL11.GL_TEXTURE_2D,source,0);GL11.glReadBuffer(depth?GL11.GL_NONE:GL30.GL_COLOR_ATTACHMENT0);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,draw);GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER,attachment,GL11.GL_TEXTURE_2D,destination,0);GL11.glDrawBuffer(depth?GL11.GL_NONE:GL30.GL_COLOR_ATTACHMENT0);
            GL30.glBlitFramebuffer(0,0,width,height,0,0,width,height,depth?GL11.GL_DEPTH_BUFFER_BIT:GL11.GL_COLOR_BUFFER_BIT,GL11.GL_NEAREST);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER,attachment,GL11.GL_TEXTURE_2D,0,0);GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER,attachment,GL11.GL_TEXTURE_2D,0,0);
        }finally{GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,oldRead);GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,oldDraw);}
    }
    public static void close(){if(read!=0)GL30.glDeleteFramebuffers(read);if(draw!=0)GL30.glDeleteFramebuffers(draw);read=draw=0;}
    public static void mipmaps(int texture){
        ensure();int oldRead=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),oldDraw=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        try(var textures=new com.iridium126.createmanaindustry.client.gpu.state.GpuTexture2DState(0)){
            com.iridium126.createmanaindustry.client.gpu.state.GpuTexture2DState.activate(0);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
            int w=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_WIDTH),h=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_HEIGHT),format=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_INTERNAL_FORMAT);
            // Allocate the complete chain before attaching non-base levels.
            int levels=32-Integer.numberOfLeadingZeros(Math.max(w,h));
            for(int level=1;level<levels;level++){
                int nw=Math.max(1,w>>level),nh=Math.max(1,h>>level);
                if(GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,level,GL11.GL_TEXTURE_WIDTH)!=nw||GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,level,GL11.GL_TEXTURE_HEIGHT)!=nh)GL11.glTexImage2D(GL11.GL_TEXTURE_2D,level,format,nw,nh,0,GL11.GL_RGBA,GL11.GL_FLOAT,(java.nio.ByteBuffer)null);
            }
            for(int level=1;w>1||h>1;level++){
                int nw=Math.max(1,w/2),nh=Math.max(1,h/2);
                if(GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,level,GL11.GL_TEXTURE_WIDTH)!=nw||GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,level,GL11.GL_TEXTURE_HEIGHT)!=nh)GL11.glTexImage2D(GL11.GL_TEXTURE_2D,level,format,nw,nh,0,GL11.GL_RGBA,GL11.GL_FLOAT,(java.nio.ByteBuffer)null);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,read);GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,texture,level-1);GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,draw);GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,texture,level);GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
                GL30.glBlitFramebuffer(0,0,w,h,0,0,nw,nh,GL11.GL_COLOR_BUFFER_BIT,GL11.GL_LINEAR);w=nw;h=nh;
            }
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,0,0);GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,0,0);
        }finally{GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,oldRead);GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,oldDraw);}
    }
}
