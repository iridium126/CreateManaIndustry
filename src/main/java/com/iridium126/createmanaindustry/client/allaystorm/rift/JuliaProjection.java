package com.iridium126.createmanaindustry.client.allaystorm.rift;

import com.iridium126.createmanaindustry.client.allaystorm.visual.StormCenterView;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;
import java.io.IOException;
import java.nio.FloatBuffer;

/** One instanced rasterization of the original numerical boundary; sky/cloud programs sample its distance. */
public final class JuliaProjection {
    private static JuliaShape shape;
    private static int program,segments,segmentTexture,vao,fbo,color,depth,width,height;
    private static final Matrix4f VIEW_PROJECTION=new Matrix4f();
    private static RiftRenderFrame published;
    public static boolean ready(RiftRenderFrame frame){return frame!=null&&published==frame&&color!=0&&frame.matches(frame.generation(),width,height);}
    private JuliaProjection() {}
    public static Matrix4f viewProjection() { return VIEW_PROJECTION; }
    public static JuliaShape shape() { return shape; }
    public static int texture() { return color; }
    public static void reload(net.minecraft.server.packs.resources.ResourceProvider resources) throws IOException {
        JuliaShape candidate=JuliaShape.load();
        String vertex,fragment;
        try(var s=resources.getResourceOrThrow(com.iridium126.createmanaindustry.CreateManaIndustry.modLoc("shaders/core/julia_projection.vsh")).open()){vertex=new String(s.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
        try(var s=resources.getResourceOrThrow(com.iridium126.createmanaindustry.CreateManaIndustry.modLoc("shaders/core/julia_projection.fsh")).open()){fragment=new String(s.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
        int next=GL20.glCreateProgram(),vs=0,fs=0;
        try {
            vs=compile(GL20.GL_VERTEX_SHADER,vertex);fs=compile(GL20.GL_FRAGMENT_SHADER,fragment);
            GL20.glAttachShader(next,vs);GL20.glAttachShader(next,fs);GL20.glLinkProgram(next);
            if(GL20.glGetProgrami(next,GL20.GL_LINK_STATUS)==0)throw new IOException(GL20.glGetProgramInfoLog(next));
        } catch(IOException error) {GL20.glDeleteProgram(next);throw error;}
        finally {if(vs!=0)GL20.glDeleteShader(vs);if(fs!=0)GL20.glDeleteShader(fs);}
        destroy();program=next;shape=candidate;
    }
    private static int compile(int type,String source) throws IOException {
        int shader=GL20.glCreateShader(type);GL20.glShaderSource(shader,source);GL20.glCompileShader(shader);
        if(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==0) {String log=GL20.glGetShaderInfoLog(shader);GL20.glDeleteShader(shader);throw new IOException(log);}return shader;
    }
    public static void draw(org.joml.Matrix4fc view,org.joml.Matrix4fc projection,float partial) {
        published=null;
        if(shape==null||program==0)return;
        var mc=Minecraft.getInstance();var state=AllaySkyProvider.rift();
        if(mc.level==null||state==null||state.state()!=com.iridium126.createmanaindustry.content.allaystorm.rift.StormRiftSnapshot.OPEN||AllaySkyProvider.dimension())return;
        VIEW_PROJECTION.set(projection).mul(view);
        int oldProgram=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),oldVao=GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),oldFbo=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),oldRead=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE),oldBuffer=GL11.glGetInteger(GL31.GL_TEXTURE_BUFFER),oldFunction=GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        boolean blend=GL11.glIsEnabled(GL11.GL_BLEND),cull=GL11.glIsEnabled(GL11.GL_CULL_FACE),test=GL11.glIsEnabled(GL11.GL_DEPTH_TEST),mask=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),scissor=GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int[] viewport=new int[4];GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);
        com.iridium126.createmanaindustry.client.gpu.state.GpuTexture2DState.activate(0);int oldTexture=GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER),old2d=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D),oldRbo=GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
        try(var stateGuard=new SkyRenderState()) {
            stateGuard.prepare();
            prepare(mc.getMainRenderTarget().width,mc.getMainRenderTarget().height);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);GL11.glViewport(0,0,width,height);
            GL11.glDisable(GL11.GL_BLEND);GL11.glDisable(GL11.GL_CULL_FACE);GL11.glDisable(GL11.GL_SCISSOR_TEST);GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthMask(true);GL11.glDepthFunc(GL11.GL_LESS);
            GL30.glClearBufferfv(GL11.GL_COLOR,0,new float[]{-1,0,0,0});GL30.glClearBufferfv(GL11.GL_DEPTH,0,new float[]{1});
            var rift=AllaySkyProvider.rift(mc.gameRenderer.getMainCamera().getPosition(),partial);
            var snapshot=AllaySkyProvider.rift();var frame=StormCenterView.timeline().sample(mc.level.getGameTime(),partial);
            if(rift.w<0||snapshot==null||frame==null)return;
            GL20.glUseProgram(program);GL30.glBindVertexArray(vao);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,segmentTexture);
            GL20.glUniform1i(GL20.glGetUniformLocation(program,"Segments"),0);
            try(var stack=MemoryStack.stackPush()) {
                FloatBuffer values=stack.mallocFloat(16);
                GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program,"View"),false,view.get(values));
                GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program,"Projection"),false,projection.get(values));
            }
            GL20.glUniform4f(GL20.glGetUniformLocation(program,"Shape"),rift.x,rift.y,rift.z,(float)shape.scale(frame.radius(),frame.y(),snapshot.planeY(),rift.w));
            var style=JuliaShape.projection(Math.sqrt((double)rift.x*rift.x+(double)rift.y*rift.y+(double)rift.z*rift.z));
            GL20.glUniform2f(GL20.glGetUniformLocation(program,"ProjectionStyle"),style.weak(),style.gain());
            GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_STRIP,0,258,shape.segments.length/4);
            published=RiftRenderFrame.current();
        } finally {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,oldFbo);GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,oldRead);GL11.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
            GL20.glUseProgram(oldProgram);GL30.glBindVertexArray(oldVao);GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER,oldBuffer);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,oldTexture);com.iridium126.createmanaindustry.client.gpu.state.GpuTexture2DState.bind(0,old2d);GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER,oldRbo);com.mojang.blaze3d.systems.RenderSystem.activeTexture(active);GL13.glActiveTexture(active);
            enabled(GL11.GL_CULL_FACE,cull);enabled(GL11.GL_DEPTH_TEST,test);enabled(GL11.GL_SCISSOR_TEST,scissor);GL11.glDepthMask(mask);GL11.glDepthFunc(oldFunction);
        }
    }
    private static void enabled(int flag,boolean value){if(value)GL11.glEnable(flag);else GL11.glDisable(flag);}
    private static void prepare(int w,int h) {
        if(segments==0) {
            segments=GL15.glGenBuffers();GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER,segments);GL15.glBufferData(GL31.GL_TEXTURE_BUFFER,shape.segments,GL15.GL_STATIC_DRAW);
            segmentTexture=GL11.glGenTextures();GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,segmentTexture);GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,GL30.GL_RGBA32F,segments);
            vao=GL30.glGenVertexArrays();fbo=GL30.glGenFramebuffers();color=GL11.glGenTextures();depth=GL30.glGenRenderbuffers();
        }
        if(width==w&&height==h)return;width=w;height=h;
        GL11.glBindTexture(GL11.GL_TEXTURE_2D,color);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL30.GL_R32F,w,h,0,GL11.GL_RED,GL11.GL_FLOAT,(java.nio.ByteBuffer)null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER,depth);GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER,GL14.GL_DEPTH_COMPONENT24,w,h);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,color,0);GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER,GL30.GL_DEPTH_ATTACHMENT,GL30.GL_RENDERBUFFER,depth);
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        if(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Julia projection framebuffer");
    }
    public static void destroy() {
        if(program!=0)GL20.glDeleteProgram(program);if(segments!=0)GL15.glDeleteBuffers(segments);if(segmentTexture!=0)GL11.glDeleteTextures(segmentTexture);
        if(vao!=0)GL30.glDeleteVertexArrays(vao);if(fbo!=0)GL30.glDeleteFramebuffers(fbo);if(color!=0)GL11.glDeleteTextures(color);if(depth!=0)GL30.glDeleteRenderbuffers(depth);
        program=segments=segmentTexture=vao=fbo=color=depth=width=height=0;shape=null;
        published=null;RiftRenderFrame.clear();
    }
}
