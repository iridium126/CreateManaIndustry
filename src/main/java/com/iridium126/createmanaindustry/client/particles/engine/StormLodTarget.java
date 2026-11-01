package com.iridium126.createmanaindustry.client.particles.engine;

import java.util.Arrays;
import com.iridium126.createmanaindustry.client.allaystorm.rift.SkyRenderState;
import com.iridium126.createmanaindustry.client.gpu.state.GpuTexture2DState;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.*;
import static com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.*;

/** Private extended-depth MRT. Native pack outputs are copied as encoded attachment values, never alpha-blended. */
final class StormLodTarget implements AutoCloseable {
    private int fbo,depth,sceneDepth,width,height;
    private final com.iridium126.createmanaindustry.client.gpu.profile.GpuPassTelemetry compositeTiming=new com.iridium126.createmanaindustry.client.gpu.profile.GpuPassTelemetry();
    private boolean profiling;
    private long profileFrame=-1,profileGeneration=-1;
    void frameContext(long frame,long generation){profileFrame=frame;profileGeneration=generation;}
    void profiling(boolean enabled){profiling=enabled;compositeTiming.configure(enabled,enabled);compositeTiming.poll();}
    private final int[] colors=new int[8],formats=new int[8];
    private final float[] bounds={0,0,1,1};
    void bounds(float[] value){System.arraycopy(value,0,bounds,0,4);}
    private static int texture(int format,int width,int height,boolean depth){
        int id=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,id);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,format,width,height,0,depth?GL11.GL_DEPTH_COMPONENT:GL11.GL_RGBA,GL11.GL_FLOAT,0L);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
        return id;
    }
    private static int format(int attachment){
        int type=GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER,attachment,GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        int name=GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER,attachment,GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        if(type==GL11.GL_TEXTURE){
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,name);
            return GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_INTERNAL_FORMAT);
        }
        if(type==GL30.GL_RENDERBUFFER){GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER,name);return GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER,GL30.GL_RENDERBUFFER_INTERNAL_FORMAT);}
        throw new IllegalStateException("LOD requires a texture/renderbuffer framebuffer attachment");
    }
    void draw(Matrix4fc sceneProjection,Matrix4fc lodProjection,Runnable geometry){
        int source=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int indirect=GL11.glGetInteger(GL40.GL_DRAW_INDIRECT_BUFFER_BINDING),depthFunction=GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        boolean depthEnabled=GL11.glIsEnabled(GL11.GL_DEPTH_TEST),depthMask=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),cull=GL11.glIsEnabled(GL11.GL_CULL_FACE);
        int[] viewport=new int[4],scissorBox=new int[4];GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX,scissorBox);
        try(var state=new SkyRenderState();var textures=new GpuTexture2DState(0,1,2,3,4,5,6,7,8,9)){
            state.prepare();
            GpuTexture2DState.activate(0);int original0=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GpuTexture2DState.activate(9);int original9=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);GpuTexture2DState.activate(0);
            int[] next=new int[8];
            for(int slot=0;slot<8;slot++){
                int attachment=GL11.glGetInteger(GL20.GL_DRAW_BUFFER0+slot);
                if(attachment!=GL11.GL_NONE)next[slot]=format(attachment);
            }
            if(source==0)throw new IllegalStateException("LOD requires an offscreen main framebuffer");
            if(fbo==0||width!=viewport[2]||height!=viewport[3]||!Arrays.equals(next,formats)){
                close();width=viewport[2];height=viewport[3];System.arraycopy(next,0,formats,0,8);
                fbo=GL30.glGenFramebuffers();depth=texture(GL30.GL_DEPTH_COMPONENT32F,width,height,true);sceneDepth=texture(GL30.GL_DEPTH_COMPONENT32F,width,height,true);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,fbo);
                GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER,GL30.GL_DEPTH_ATTACHMENT,GL11.GL_TEXTURE_2D,depth,0);
                int[] drawBuffers=new int[8];
                for(int slot=0;slot<8;slot++)if(formats[slot]!=0){
                    colors[slot]=texture(formats[slot],width,height,false);drawBuffers[slot]=GL30.GL_COLOR_ATTACHMENT0+slot;
                    GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER,drawBuffers[slot],GL11.GL_TEXTURE_2D,colors[slot],0);
                }
                GL20.glDrawBuffers(drawBuffers);
                if(GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Incomplete LOD MRT");
            }
            int x=Math.max(0,(int)Math.floor(bounds[0]*width)-2),y=Math.max(0,(int)Math.floor(bounds[1]*height)-2);
            int w=Math.min(width,(int)Math.ceil(bounds[2]*width)+2)-x,h=Math.min(height,(int)Math.ceil(bounds[3]*height)+2)-y;
            if(w<=0||h<=0)return;
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,source);
            GpuTexture2DState.bind(9,sceneDepth);GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D,0,x,y,viewport[0]+x,viewport[1]+y,w,h);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,fbo);RenderSystem.viewport(0,0,width,height);RenderSystem.enableScissor(x,y,w,h);
            RenderSystem.depthMask(true);GL30.glClearBufferfv(GL11.GL_DEPTH,0,new float[]{1});
            for(int slot=0;slot<8;slot++)if(colors[slot]!=0)GL30.glClearBufferfv(GL11.GL_COLOR,slot,new float[]{0,0,0,0});
            GpuTexture2DState.bind(0,original0);GpuTexture2DState.bind(9,original9);
            geometry.run();
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT|GL42.GL_FRAMEBUFFER_BARRIER_BIT);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,source);RenderSystem.viewport(viewport[0],viewport[1],width,height);RenderSystem.enableScissor(viewport[0]+x,viewport[1]+y,w,h);
            int composite=ParticlePrograms.INSTANCE.lodComposite();GL20.glUseProgram(composite);
            int fallback=Arrays.stream(colors).filter(id->id!=0).findFirst().orElseThrow();
            for(int slot=0;slot<8;slot++){
                GpuTexture2DState.bind(slot,colors[slot]!=0?colors[slot]:fallback);GL33.glBindSampler(slot,0);
                setIntUniform(composite,"uColors["+slot+"]",slot);
                GL30.glDisablei(GL11.GL_BLEND,slot);GL30.glColorMaski(slot,true,true,true,true);
            }
            GpuTexture2DState.bind(8,depth);GpuTexture2DState.bind(9,sceneDepth);GL33.glBindSampler(8,0);GL33.glBindSampler(9,0);
            setIntUniform(composite,"uLodDepth",8);setIntUniform(composite,"uSceneDepth",9);
            setMat4Uniform(composite,"uInverseLodProjection",new Matrix4f(lodProjection).invert());setMat4Uniform(composite,"uInverseSceneProjection",new Matrix4f(sceneProjection).invert());setMat4Uniform(composite,"uSceneProjection",sceneProjection);
            RenderSystem.enableDepthTest();RenderSystem.depthFunc(GL11.GL_ALWAYS);RenderSystem.disableCull();
            compositeTiming.begin(profiling,profiling,profileFrame,profileGeneration);
            try{
                // Copy encoded native outputs without fabricating main-scene depth.
                setIntUniform(composite,"uDepthOnly",0);RenderSystem.depthMask(false);
                GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);
                for(int slot=0;slot<8;slot++)GL30.glColorMaski(slot,false,false,false,false);
                setIntUniform(composite,"uDepthOnly",1);RenderSystem.depthMask(true);
                GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);
            }finally{compositeTiming.end();}
        }catch(RuntimeException|LinkageError failure){close();throw failure;}finally{
            GL11.glScissor(scissorBox[0],scissorBox[1],scissorBox[2],scissorBox[3]);
            RenderSystem.depthFunc(depthFunction);RenderSystem.depthMask(depthMask);if(depthEnabled)RenderSystem.enableDepthTest();else RenderSystem.disableDepthTest();if(cull)RenderSystem.enableCull();else RenderSystem.disableCull();
            GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER,indirect);GL20.glUseProgram(program);
        }
    }
    String report(){return "target="+width+"x"+height+", color attachments="+Arrays.stream(colors).filter(id->id!=0).count()+", composite: "+ParticleDiagnostics.INSTANCE.timing(compositeTiming,true);}
    @Override public void close(){
        if(fbo!=0)GL30.glDeleteFramebuffers(fbo);if(depth!=0)GL11.glDeleteTextures(depth);if(sceneDepth!=0)GL11.glDeleteTextures(sceneDepth);
        for(int i=0;i<8;i++){if(colors[i]!=0)GL11.glDeleteTextures(colors[i]);colors[i]=0;formats[i]=0;}
        fbo=depth=sceneDepth=0;width=height=0;compositeTiming.discardPending();
    }
}
