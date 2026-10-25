import java.nio.*;
import java.nio.charset.StandardCharsets;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import org.joml.Matrix4f;
import com.mojang.blaze3d.systems.RenderSystem;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler;
import com.iridium126.createmanaindustry.client.render.shaderpack.*;

/** Production fragments and scene-copy math on the real driver; does not claim to apply Iris mixins. */
public final class WorldEffectsGpuValidation {
    static int checks;
    static void check(boolean b,String reason){checks++;if(!b)throw new AssertionError(reason);}
    static String resource(String name)throws Exception{try(var stream=WorldEffectsGpuValidation.class.getClassLoader().getResourceAsStream(name)){if(stream==null)throw new IllegalStateException(name);return new String(stream.readAllBytes(),StandardCharsets.UTF_8);}}
    static String fragment(String name)throws Exception{
        String helper=resource("assets/createmanaindustry/pinwheel/shaders/include/world_effect_space.glsl");
        return "#version 450 core\n"+resource("assets/createmanaindustry/pinwheel/shaders/program/"+name+".fsh").replace("#include createmanaindustry:world_effect_space",helper);
    }
    static int program(String name)throws Exception{
        String vertex="#version 450 core\nout vec2 texCoord;void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);texCoord=p;gl_Position=vec4(p*2.0-1.0,0,1);}";
        return GpuShaderCompiler.graphics(name,vertex,fragment(name));
    }
    static int texture(int size,int format,float value){
        int id=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,id);var pixels=BufferUtils.createFloatBuffer(size*size*(format==GL30.GL_R32F?1:4));
        while(pixels.hasRemaining())pixels.put(value);pixels.flip();GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,format,size,size,0,format==GL30.GL_R32F?GL11.GL_RED:GL11.GL_RGBA,GL11.GL_FLOAT,pixels);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);return id;
    }
    static void matrix(int program,String name,Matrix4f matrix){var b=BufferUtils.createFloatBuffer(16);matrix.get(b);GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program,name),false,b);}
    static void bind(int program,String name,int unit,int id){GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);GL11.glBindTexture(GL11.GL_TEXTURE_2D,id);GL20.glUniform1i(GL20.glGetUniformLocation(program,name),unit);}
    static void probe(int program,int size,SceneColorCopy copy){
        int color=texture(size,GL30.GL_RGBA16F,.125f),depth=texture(size,GL30.GL_R32F,1),blocked=texture(size,GL30.GL_R32F,.001f),fbo=GL30.glGenFramebuffers(),vao=GL30.glGenVertexArrays();
        try{
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,color,0);
            check(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)==GL30.GL_FRAMEBUFFER_COMPLETE,"fixture framebuffer");GL11.glViewport(0,0,size,size);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);int snapshot=copy.capture(size,size);check(snapshot!=color,"read/write isolation");
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,snapshot);var captured=BufferUtils.createFloatBuffer(size*size*4);GL11.glGetTexImage(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA,GL11.GL_FLOAT,captured);
            for(int i=0;i<captured.limit();i++)check(Math.abs(captured.get(i)-.125)<.001,"scene copy value");
            GL20.glUseProgram(program);GL30.glBindVertexArray(vao);GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDisable(GL11.GL_BLEND);
            var projection=new Matrix4f().perspective((float)Math.PI/2,1,.1f,100);var view=new Matrix4f().lookAt(0,0,0,0,1,0,0,0,-1);
            matrix(program,"CmiWorldCamera.IProjMat",new Matrix4f(projection).invert());matrix(program,"CmiWorldCamera.IViewMat",new Matrix4f(view).invert());
            GL20.glUniform3f(GL20.glGetUniformLocation(program,"CmiWorldCamera.CameraPosition"),0,0,0);GL20.glUniform4f(GL20.glGetUniformLocation(program,"ProbeAnchor"),0,10,0,4);GL20.glUniform1f(GL20.glGetUniformLocation(program,"ProbeTime"),1);
            bind(program,"DiffuseSampler0",0,snapshot);
            for(int mode:new int[]{0,1,2}){
                GL20.glUniform1i(GL20.glGetUniformLocation(program,"OutputMode"),mode);
                for(boolean occluded:new boolean[]{false,true}){
                    bind(program,"DiffuseDepthSampler",1,occluded?blocked:depth);GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);
                    var pixels=BufferUtils.createFloatBuffer(size*size*4);GL11.glReadPixels(0,0,size,size,GL11.GL_RGBA,GL11.GL_FLOAT,pixels);int changed=0;
                    for(int i=0;i<pixels.limit();i+=4){check(Float.isFinite(pixels.get(i)),"finite output");if(Math.abs(pixels.get(i)-.125)>.001)changed++;}
                    check(occluded?changed==0:changed>0,"probe depth/mode "+mode+" occluded="+occluded);
                }
            }
            // The anchor is directly above the camera. Its finite disk is entirely
            // outside this horizontal view; even horizon-crossing quads stay unchanged.
            bind(program,"DiffuseDepthSampler",1,depth);
            for(float tilt:new float[]{-.03f,0,.03f}){
                var horizontal=new Matrix4f().lookAt(0,0,0,0,tilt,1,0,1,0);
                matrix(program,"CmiWorldCamera.IViewMat",new Matrix4f(horizontal).invert());
                for(int mode:new int[]{0,1,2}){
                    GL20.glUniform1i(GL20.glGetUniformLocation(program,"OutputMode"),mode);GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);
                    var pixels=BufferUtils.createFloatBuffer(size*size*4);GL11.glReadPixels(0,0,size,size,GL11.GL_RGBA,GL11.GL_FLOAT,pixels);
                    for(int i=0;i<pixels.limit();i++)check(Math.abs(pixels.get(i)-.125)<.001,"no horizon tint: overhead anchor tilt="+tilt+" mode="+mode);
                }
            }
            RenderSystem.colorMask(false,true,false,true);
            GL30.glEnablei(GL11.GL_BLEND,1);
            GL40.glBlendFuncSeparatei(1,GL11.GL_DST_COLOR,GL11.GL_ONE_MINUS_DST_COLOR,GL11.GL_ONE,GL11.GL_ZERO);
            GL40.glBlendEquationSeparatei(1,GL14.GL_FUNC_REVERSE_SUBTRACT,GL14.GL_FUNC_SUBTRACT);
            GL30.glColorMaski(1,true,false,false,false);
            try(var state=new WorldEffectState()){
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,0);GL11.glViewport(3,4,5,6);GL20.glUseProgram(0);GL30.glBindVertexArray(0);
                RenderSystem.colorMask(true,true,true,true);
                GL11.glDisable(GL11.GL_BLEND);RenderSystem.blendFuncSeparate(GL11.GL_ONE,GL11.GL_ZERO,GL11.GL_ONE,GL11.GL_ZERO);GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD,GL14.GL_FUNC_ADD);
            }
            check(GL30.glIsEnabledi(GL11.GL_BLEND,1),"indexed blend enable restoration");
            check(GL30.glGetIntegeri(GL14.GL_BLEND_SRC_RGB,1)==GL11.GL_DST_COLOR&&GL30.glGetIntegeri(GL14.GL_BLEND_DST_RGB,1)==GL11.GL_ONE_MINUS_DST_COLOR,"indexed RGB blend restoration");
            check(GL30.glGetIntegeri(GL14.GL_BLEND_SRC_ALPHA,1)==GL11.GL_ONE&&GL30.glGetIntegeri(GL14.GL_BLEND_DST_ALPHA,1)==GL11.GL_ZERO,"indexed alpha blend restoration");
            check(GL30.glGetIntegeri(GL20.GL_BLEND_EQUATION_RGB,1)==GL14.GL_FUNC_REVERSE_SUBTRACT&&GL30.glGetIntegeri(GL20.GL_BLEND_EQUATION_ALPHA,1)==GL14.GL_FUNC_SUBTRACT,"indexed blend equation restoration");
            var indexedMask=new int[4];GL30.glGetIntegeri_v(GL11.GL_COLOR_WRITEMASK,1,indexedMask);check(indexedMask[0]==1&&indexedMask[1]==0&&indexedMask[2]==0&&indexedMask[3]==0,"indexed color mask restoration");GL30.glDisablei(GL11.GL_BLEND,1);
            var mask=new int[4];GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK,mask);check(mask[0]==0&&mask[1]==1&&mask[2]==0&&mask[3]==1,"color mask restoration");RenderSystem.colorMask(true,true,true,true);
            check(GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)==fbo,"framebuffer restoration");check(GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)==program,"program restoration");check(GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING)==vao,"VAO restoration");
        }finally{GL20.glUseProgram(0);GL30.glBindVertexArray(0);GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,0);GL30.glDeleteFramebuffers(fbo);GL30.glDeleteVertexArrays(vao);for(int id:new int[]{color,depth,blocked})GL11.glDeleteTextures(id);}
    }
    static void mistLighting(int program,SceneColorCopy copy){
        int size=32,color=texture(size,GL30.GL_RGBA16F,.125f),depth=texture(size,GL30.GL_R32F,1),fbo=GL30.glGenFramebuffers(),vao=GL30.glGenVertexArrays();
        try{
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,color,0);GL11.glViewport(0,0,size,size);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);int snapshot=copy.capture(size,size);
            GL20.glUseProgram(program);GL30.glBindVertexArray(vao);GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDisable(GL11.GL_BLEND);
            matrix(program,"CmiWorldCamera.IProjMat",new Matrix4f().perspective((float)Math.PI/2,1,.1f,100).invert());
            matrix(program,"CmiWorldCamera.IViewMat",new Matrix4f().lookAt(0,0,0,0,1,0,0,0,-1).invert());
            GL20.glUniform3f(GL20.glGetUniformLocation(program,"CmiWorldCamera.CameraPosition"),0,0,0);
            bind(program,"DiffuseSampler0",0,snapshot);bind(program,"DiffuseDepthSampler",1,depth);
            GL20.glUniform1i(GL20.glGetUniformLocation(program,"AtomizerCount"),1);GL20.glUniform1i(GL20.glGetUniformLocation(program,"PaletteCount"),1);
            GL20.glUniform1fv(GL20.glGetUniformLocation(program,"AtomizerData"),new float[]{0,5,0,1f/49,0,1});
            GL20.glUniform4fv(GL20.glGetUniformLocation(program,"MistPalette"),new float[]{1,1,1,0});
            GL20.glUniform1f(GL20.glGetUniformLocation(program,"MistOpacity"),.4f);GL20.glUniform1f(GL20.glGetUniformLocation(program,"MistDensity"),.25f);GL20.glUniform1f(GL20.glGetUniformLocation(program,"MistStepScale"),.8f);
            double[] energy=new double[2];int sample=0;
            for(float sunY:new float[]{-1,1}){
                GL20.glUniform3f(GL20.glGetUniformLocation(program,"SunDirection"),0,sunY,0);GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);
                var pixels=BufferUtils.createFloatBuffer(size*size*4);GL11.glReadPixels(0,0,size,size,GL11.GL_RGBA,GL11.GL_FLOAT,pixels);
                for(int i=0;i<pixels.limit();i+=4){check(Float.isFinite(pixels.get(i)),"finite mist lighting");energy[sample]+=pixels.get(i);}
                sample++;
            }
            check(energy[1]>energy[0]*1.05,"daytime scattering must respond to the uploaded sun direction");
            GL20.glUniform3f(GL20.glGetUniformLocation(program,"SunDirection"),0,1,0);
            float[] radiance=mistImage(program,size,copy,0,1),white=mistImage(program,size,copy,1,1);
            for(float gamma:new float[]{1,2,2.2f})for(float background:new float[]{0,.02f,.18f,.5f,.9f}){
                var pixels=mistImage(program,size,copy,background,gamma);
                double stored=Float.float16ToFloat(Float.floatToFloat16(background));
                for(int i=0;i<pixels.length;i+=4)for(int channel=0;channel<3;channel++){
                    double fog=radiance[i+channel],transmittance=Math.max(0,Math.min(1,white[i+channel]-fog));
                    double expected=Math.pow(Math.pow(stored,gamma)*transmittance+fog,1.0/gamma);
                    check(Math.abs(pixels[i+channel]-expected)<.004,"native scene transfer gamma="+gamma+" background="+background);
                }
            }
            GL20.glUniform1i(GL20.glGetUniformLocation(program,"AtomizerCount"),0);
            var empty=mistImage(program,size,copy,.18f,2.2f);
            for(float value:empty)check(Math.abs(value-.18)<.001,"empty encoded native scene stays untouched");
        }finally{GL20.glUseProgram(0);GL30.glBindVertexArray(0);GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,0);GL30.glDeleteFramebuffers(fbo);GL30.glDeleteVertexArrays(vao);GL11.glDeleteTextures(color);GL11.glDeleteTextures(depth);}
    }
    static float[] mistImage(int program,int size,SceneColorCopy copy,float background,float gamma){
        GL30.glClearBufferfv(GL11.GL_COLOR,0,new float[]{background,background,background,background});
        GL13.glActiveTexture(GL13.GL_TEXTURE0);int scene=copy.capture(size,size);bind(program,"DiffuseSampler0",0,scene);
        GL20.glUniform1f(GL20.glGetUniformLocation(program,"CmiSceneGamma"),gamma);GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);
        var pixels=BufferUtils.createFloatBuffer(size*size*4);GL11.glReadPixels(0,0,size,size,GL11.GL_RGBA,GL11.GL_FLOAT,pixels);float[] result=new float[pixels.limit()];pixels.get(result);return result;
    }
    public static void main(String[] args)throws Exception{
        check(GLFW.glfwInit(),"GLFW initialization");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);
        long window=GLFW.glfwCreateWindow(64,64,"CMI world effects validation",0,0);check(window!=0,"OpenGL context");
        try{
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();RenderSystem.initRenderThread();System.out.println(GL11.glGetString(GL11.GL_RENDERER));
            for(String name:new String[]{"mist_volumetric_iris","fuel_rod_glow_iris","storm_visual_probe"}){int p=program(name);try{check(p!=0,"production fragment "+name);try(var copy=new SceneColorCopy()){if(name.equals("storm_visual_probe")){probe(p,32,copy);probe(p,64,copy);}if(name.equals("mist_volumetric_iris"))mistLighting(p,copy);}}finally{GL20.glDeleteProgram(p);}}
            check(GL11.glGetError()==GL11.GL_NO_ERROR,"GL errors");System.out.println("World effects GPU: "+checks+" assertions passed");
        }finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
