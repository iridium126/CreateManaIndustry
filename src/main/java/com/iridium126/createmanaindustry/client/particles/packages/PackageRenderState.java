package com.iridium126.createmanaindustry.client.particles.packages;

import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/** Reused external-pass boundary. Saves binding ranges, including nonzero offsets. Never waits. */
public final class PackageRenderState {
    private final int[] ssbo=new int[12],tbo=new int[3],textures=new int[3];
    private final long[] offsets=new long[12],sizes=new long[12];
    private final int[][] blend;
    private final boolean[] blendEnabled;
    private int program,vao,array,indirect,storage,copyRead,copyWrite,active,drawFb,readFb,patchVertices;
    private boolean cull,depth,depthMask,open;
    public PackageRenderState() {
        int n=GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS);blend=new int[n][6];blendEnabled=new boolean[n];
    }
    public void capture() {
        if(open)throw new IllegalStateException("Nested package render boundary");
        program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);vao=GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        array=GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);indirect=GL11.glGetInteger(GL40.GL_DRAW_INDIRECT_BUFFER_BINDING);
        storage=GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
        copyRead=GL11.glGetInteger(GL31.GL_COPY_READ_BUFFER);copyWrite=GL11.glGetInteger(GL31.GL_COPY_WRITE_BUFFER);
        active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);drawFb=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);readFb=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        patchVertices=GL11.glGetInteger(GL40.GL_PATCH_VERTICES);
        cull=GL11.glIsEnabled(GL11.GL_CULL_FACE);depth=GL11.glIsEnabled(GL11.GL_DEPTH_TEST);depthMask=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        for(int i=0;i<ssbo.length;i++) {
            ssbo[i]=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,i);
            offsets[i]=ssbo[i]==0?0:GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START,i);
            sizes[i]=ssbo[i]==0?0:GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE,i);
        }
        for(int i=0;i<3;i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0+i);textures[i]=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL13.glActiveTexture(GL13.GL_TEXTURE10+i);tbo[i]=GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER);
        }
        GL13.glActiveTexture(active);
        for(int i=0;i<blend.length;i++) {
            blendEnabled[i]=GL30.glIsEnabledi(GL11.GL_BLEND,i);
            blend[i][0]=GL30.glGetIntegeri(GL14.GL_BLEND_SRC_RGB,i);blend[i][1]=GL30.glGetIntegeri(GL14.GL_BLEND_DST_RGB,i);
            blend[i][2]=GL30.glGetIntegeri(GL14.GL_BLEND_SRC_ALPHA,i);blend[i][3]=GL30.glGetIntegeri(GL14.GL_BLEND_DST_ALPHA,i);
            blend[i][4]=GL30.glGetIntegeri(GL20.GL_BLEND_EQUATION_RGB,i);blend[i][5]=GL30.glGetIntegeri(GL20.GL_BLEND_EQUATION_ALPHA,i);
        }
        open=true;
    }
    public void restore() {
        if(!open)return;open=false;
        try(var stack=MemoryStack.stackPush()) {
            // Base bindings report SIZE=0: they follow future buffer resizes.
            // Passing these to BindBuffersRange is invalid and would lose that
            // behavior even if replaced with today's allocation size.
            GL44.glBindBuffersBase(GL43.GL_SHADER_STORAGE_BUFFER,0,stack.ints(ssbo));
            for(int i=0;i<ssbo.length;i++)if(ssbo[i]!=0 && sizes[i]>0)
                GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,i,ssbo[i],offsets[i],sizes[i]);
        }
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,storage);
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,copyRead);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,copyWrite);
        for(int i=0;i<3;i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0+i);GL11.glBindTexture(GL11.GL_TEXTURE_2D,textures[i]);
            // Zero glBindTextures would unbind every foreign target on these units.
            GL13.glActiveTexture(GL13.GL_TEXTURE10+i);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,tbo[i]);
        }
        GL13.glActiveTexture(active);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,drawFb);GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,readFb);
        GL30.glBindVertexArray(vao);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,array);GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER,indirect);
        GL40.glPatchParameteri(GL40.GL_PATCH_VERTICES,patchVertices);
        if(cull)GL11.glEnable(GL11.GL_CULL_FACE);else GL11.glDisable(GL11.GL_CULL_FACE);
        if(depth)GL11.glEnable(GL11.GL_DEPTH_TEST);else GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(depthMask);
        for(int i=0;i<blend.length;i++) {
            if(blendEnabled[i])GL30.glEnablei(GL11.GL_BLEND,i);else GL30.glDisablei(GL11.GL_BLEND,i);
            GL40.glBlendFuncSeparatei(i,blend[i][0],blend[i][1],blend[i][2],blend[i][3]);
            GL40.glBlendEquationSeparatei(i,blend[i][4],blend[i][5]);
        }
        GL20.glUseProgram(program);
    }
}
