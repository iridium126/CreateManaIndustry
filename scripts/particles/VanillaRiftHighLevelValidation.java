import com.iridium126.createmanaindustry.client.allaystorm.rift.RiftVanillaComposite;
import com.iridium126.createmanaindustry.client.gpu.state.GpuTexture2DState;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.*;
import org.joml.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import java.nio.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;

/** Real ShaderInstance/Uniform upload and BufferUploader/VertexBuffer; no Minecraft client. */
public final class VanillaRiftHighLevelValidation {
    static int checks;
    static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    static int texture(int format,int size,int components,float value){
        int t=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,t);float[] data=new float[size*size*components];Arrays.fill(data,value);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,format,size,size,0,components==1?GL11.GL_RED:GL11.GL_RGBA,GL11.GL_FLOAT,data);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);return t;
    }
    static ResourceProvider resources(){return id->{
        String path="assets/"+id.getNamespace()+"/"+id.getPath();byte[] bytes;
        try(var in=VanillaRiftHighLevelValidation.class.getClassLoader().getResourceAsStream(path)){
            if(in==null){var file=Path.of(".refs/neoforge-21.1.227",path);if(!Files.exists(file))return Optional.empty();bytes=Files.readAllBytes(file);}else bytes=in.readAllBytes();
        }catch(IOException error){throw new java.io.UncheckedIOException(error);}
        var pack=(net.minecraft.server.packs.PackResources)java.lang.reflect.Proxy.newProxyInstance(VanillaRiftHighLevelValidation.class.getClassLoader(),new Class<?>[]{net.minecraft.server.packs.PackResources.class},(proxy,method,args)->method.getName().equals("packId")?"validation":null);
        return Optional.of(new Resource(pack,()->new ByteArrayInputStream(bytes)));
    };}
    static void run()throws Exception{
        int fbo=GL30.glGenFramebuffers(),color=texture(GL30.GL_RGBA32F,64,4,0),hit=texture(GL30.GL_R32F,64,1,10),target=texture(GL30.GL_RGBA32F,1,4,.75f);
        int depth=GL30.glGenRenderbuffers();GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER,depth);GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER,GL30.GL_DEPTH24_STENCIL8,64,64);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,color,0);GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER,GL30.GL_DEPTH_STENCIL_ATTACHMENT,GL30.GL_RENDERBUFFER,depth);
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);GL11.glViewport(0,0,64,64);check(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)==GL30.GL_FRAMEBUFFER_COMPLETE,"framebuffer");
        int hostile=GL33.glGenSamplers();GL33.glSamplerParameteri(hostile,GL14.GL_TEXTURE_COMPARE_MODE,GL30.GL_COMPARE_REF_TO_TEXTURE);
        // Reproduce an actual/cache mismatch, including a cached bind-to-zero.
        GpuTexture2DState.bind(0,0);GL11.glBindTexture(GL11.GL_TEXTURE_2D,target);GpuTexture2DState.bind(0,0);
        check(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)==0,"zero binding repairs stale cache");
        GpuTexture2DState.activate(0);GL13.glActiveTexture(GL13.GL_TEXTURE3);
        try(var saved=new GpuTexture2DState(0,1)){GpuTexture2DState.bind(0,hit);RenderSystem.setShaderTexture(0,target);}
        check(GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)==GL13.GL_TEXTURE3&&GlStateManager._getActiveTexture()==GL13.GL_TEXTURE3,"repair mismatched active texture cache");
        GL33.glBindSampler(7,hostile);RenderSystem.setShaderTexture(0,hit);
        try(var saved=GpuTexture2DState.withSamplerRange(null,8,0,1,2)){
            for(int unit=0;unit<8;unit++)GL33.glBindSampler(unit,0);RenderSystem.setShaderTexture(0,target);
        }
        check(GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING,7)==hostile,"unrelated sampler survives native clear");
        check(RenderSystem.getShaderTexture(0)==hit,"external engine texture table restored");GL33.glBindSampler(7,0);
        try(var shader=new ShaderInstance(resources(),ResourceLocation.fromNamespaceAndPath("createmanaindustry","allay_sky"),DefaultVertexFormat.POSITION)){
            var projection=new Matrix4f().perspective((float)java.lang.Math.PI/2,1,.1f,1000);
            var view=new Matrix4f().lookAt(0,0,0,0,1,0,0,0,-1);
            shader.safeGetUniform("CmiView").set(view);shader.safeGetUniform("CmiProjection").set(projection);shader.safeGetUniform("CmiInverseView").set(new Matrix4f(view).invert());shader.safeGetUniform("CmiInverseProjection").set(new Matrix4f(projection).invert());shader.safeGetUniform("CmiSkyViewProjection").set(new Matrix4f(projection).mul(view));shader.safeGetUniform("CmiRift").set(new Vector4f(0,10,0,2));shader.safeGetUniform("CmiSkyDimension").set(0);
            shader.setSampler("CmiJuliaHit",hit);shader.setSampler("CmiTargetSky",target);
            for(int frame=0;frame<4;frame++){
                boolean dirty=frame%2!=0;int width=frame==2?96:64,height=frame==2?48:64;
                GpuTexture2DState.bind(0,color);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL30.GL_RGBA32F,width,height,0,GL11.GL_RGBA,GL11.GL_FLOAT,(java.nio.ByteBuffer)null);
                GpuTexture2DState.bind(0,hit);float[] hits=new float[width*height];Arrays.fill(hits,10);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL30.GL_R32F,width,height,0,GL11.GL_RED,GL11.GL_FLOAT,hits);
                GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER,depth);GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER,GL30.GL_DEPTH24_STENCIL8,width,height);GL11.glViewport(0,0,width,height);
                GL33.glBindSampler(0,0);GL33.glBindSampler(1,0);
                GL11.glDisable(GL11.GL_SCISSOR_TEST);GL11.glDisable(GL11.GL_STENCIL_TEST);GL11.glColorMask(true,true,true,true);GL11.glDepthMask(true);GL11.glClearColor(.1f,.2f,.3f,1);GL11.glClearDepth(1);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT|GL11.GL_STENCIL_BUFFER_BIT);
                if(dirty){GL11.glEnable(GL11.GL_SCISSOR_TEST);GL11.glScissor(0,0,1,1);GL11.glEnable(GL11.GL_STENCIL_TEST);GL11.glStencilFunc(GL11.GL_NEVER,1,255);GL11.glColorMask(false,true,false,false);GL33.glBindSampler(0,hostile);GL33.glBindSampler(1,hostile);}
                GpuTexture2DState.activate(3);GL11.glBindTexture(GL11.GL_TEXTURE_2D,target);
                RiftVanillaComposite.draw(shader);
                float[] pixels=new float[width*height*4];GL11.glReadPixels(0,0,width,height,GL11.GL_RGBA,GL11.GL_FLOAT,pixels);int visible=0;for(int i=0;i<width*height;i++)if(java.lang.Math.abs(pixels[i*4]-.75f)<.001)visible++;
                check(visible>width*height*.75,"real ShaderInstance sky visible, frame="+frame+" hostile state="+dirty+" visible="+visible);
                check(GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)==dirty&&GL11.glIsEnabled(GL11.GL_STENCIL_TEST)==dirty,"clip state restored");
                check(GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)==GlStateManager._getActiveTexture(),"texture cache agrees with GL");
                check(GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)==GL13.GL_TEXTURE3,"activity restored");
                check(GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING,0)==(dirty?hostile:0),"sampler restored");
                RiftVanillaComposite.close(); // resource reload / recreation on the next frame
            }
        }finally{RiftVanillaComposite.close();GL33.glBindSampler(0,0);GL33.glBindSampler(1,0);GL33.glDeleteSamplers(hostile);for(int t:new int[]{color,hit,target})GL11.glDeleteTextures(t);GL30.glDeleteRenderbuffers(depth);GL30.glDeleteFramebuffers(fbo);}
        check(GL11.glGetError()==GL11.GL_NO_ERROR,"no GL errors");
    }
    public static void main(String[] args)throws Exception{
        check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);
        long window=GLFW.glfwCreateWindow(64,64,"Vanilla Rift high-level validation",0,0);check(window!=0,"context");
        try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();RenderSystem.initRenderThread();Tesselator.init();run();System.out.println("Vanilla rift high-level GPU: "+checks+" checks passed");}finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
