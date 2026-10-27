import java.nio.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import org.joml.Matrix4f;

/** Actual shipped vanilla shaders on a real driver. It does not certify shader-pack or client integration. */
public final class RiftSkyGpuValidation {
    static int checks;
    static final int SIZE=64;
    static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    static String resource(String path)throws Exception{
        try(var in=RiftSkyGpuValidation.class.getClassLoader().getResourceAsStream("assets/createmanaindustry/shaders/"+path)){
            if(in==null)throw new IllegalStateException(path);return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    static String source(String name)throws Exception{
        return resource("core/"+name).replace("#moj_import <createmanaindustry:allay_sky.glsl>",resource("include/allay_sky.glsl"))
                .replace("#moj_import <fog.glsl>",Files.readString(Path.of(".refs/neoforge-21.1.227/assets/minecraft/shaders/include/fog.glsl")).replaceAll("(?m)^#version[^\\r\\n]*", ""));
    }
    static int compile(int type,String source){
        int shader=GL20.glCreateShader(type);GL20.glShaderSource(shader,source);GL20.glCompileShader(shader);
        check(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==GL11.GL_TRUE,GL20.glGetShaderInfoLog(shader));return shader;
    }
    static int program(String name)throws Exception{
        int vertex=compile(GL20.GL_VERTEX_SHADER,source(name+".vsh")),fragment=compile(GL20.GL_FRAGMENT_SHADER,source(name+".fsh"));
        int program=GL20.glCreateProgram();GL20.glAttachShader(program,vertex);GL20.glAttachShader(program,fragment);
        for(int i=0;i<4;i++)GL20.glBindAttribLocation(program,i,new String[]{"Position","UV0","Color","Normal"}[i]);
        GL20.glLinkProgram(program);check(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)==GL11.GL_TRUE,GL20.glGetProgramInfoLog(program));
        GL20.glDeleteShader(vertex);GL20.glDeleteShader(fragment);return program;
    }
    static void matrix(int program,String name,Matrix4f value){var data=BufferUtils.createFloatBuffer(16);value.get(data);GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program,name),false,data);}
    static void vector(int program,String name,float x,float y,float z,float w){GL20.glUniform4f(GL20.glGetUniformLocation(program,name),x,y,z,w);}
    static int texture(int format,int width,int height,int dataFormat){
        int texture=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,format,width,height,0,dataFormat,GL11.GL_FLOAT,(ByteBuffer)null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);return texture;
    }
    static void geometry(int vbo,float[] positions){
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vbo);GL15.glBufferData(GL15.GL_ARRAY_BUFFER,positions,GL15.GL_STREAM_DRAW);
        GL20.glEnableVertexAttribArray(0);GL20.glVertexAttribPointer(0,3,GL11.GL_FLOAT,false,0,0L);
    }
    static FloatBuffer pixels(){var data=BufferUtils.createFloatBuffer(SIZE*SIZE*4);GL11.glReadPixels(0,0,SIZE,SIZE,GL11.GL_RGBA,GL11.GL_FLOAT,data);return data;}
    static int covered(FloatBuffer data){int covered=0;for(int i=0;i<SIZE*SIZE;i++){float r=data.get(i*4),g=data.get(i*4+1),b=data.get(i*4+2);check(Float.isFinite(r)&&Float.isFinite(g)&&Float.isFinite(b),"finite sky radiance");if(b>r&&g>r)covered++;}return covered;}
    static void clear(){GL11.glDepthMask(true);GL11.glClearDepth(1);GL11.glClearColor(.9f,.05f,.05f,1);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT);GL11.glDepthMask(false);}
    static void run()throws Exception{
        int sky=program("allay_sky"),clouds=program("allay_clouds");
        int fbo=GL30.glGenFramebuffers(),vao=GL30.glGenVertexArrays(),vbo=GL15.glGenBuffers();
        int color=texture(GL30.GL_RGBA32F,SIZE,SIZE,GL11.GL_RGBA),depth=texture(GL30.GL_DEPTH_COMPONENT32F,SIZE,SIZE,GL11.GL_DEPTH_COMPONENT);
        int white=texture(GL30.GL_RGBA32F,1,1,GL11.GL_RGBA);GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,0,1,1,GL11.GL_RGBA,GL11.GL_FLOAT,new float[]{1,1,1,1});
        int target=texture(GL30.GL_RGBA32F,1,1,GL11.GL_RGBA);GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,0,1,1,GL11.GL_RGBA,GL11.GL_FLOAT,new float[]{.1f,.6f,.8f,1});
        int hit=texture(GL30.GL_R32F,SIZE,SIZE,GL11.GL_RED);
        var distances=BufferUtils.createFloatBuffer(SIZE*SIZE);
        for(int y=0;y<SIZE;y++)for(int x=0;x<SIZE;x++)distances.put(Math.hypot(x+.5-SIZE/2,y+.5-SIZE/2)<SIZE/3?10:-1);
        distances.flip();GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,0,SIZE,SIZE,GL11.GL_RED,GL11.GL_FLOAT,distances);
        try{
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,color,0);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_DEPTH_ATTACHMENT,GL11.GL_TEXTURE_2D,depth,0);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);check(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)==GL30.GL_FRAMEBUFFER_COMPLETE,"sky framebuffer");
            GL30.glBindVertexArray(vao);GL11.glViewport(0,0,SIZE,SIZE);GL11.glDisable(GL11.GL_CULL_FACE);GL11.glDisable(GL11.GL_BLEND);GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthFunc(GL11.GL_LEQUAL);
            Matrix4f projection=new Matrix4f().perspective((float)Math.PI/2,1,.1f,256),up=new Matrix4f().lookAt(0,0,0,0,1,0,0,0,-1);
            GL20.glUseProgram(sky);matrix(sky,"CmiView",up);matrix(sky,"CmiProjection",projection);matrix(sky,"CmiInverseView",new Matrix4f(up).invert());matrix(sky,"CmiInverseProjection",new Matrix4f(projection).invert());matrix(sky,"CmiSkyViewProjection",new Matrix4f(projection).mul(up));
            vector(sky,"CmiRift",0,100,0,2);GL20.glUniform1i(GL20.glGetUniformLocation(sky,"CmiSkyDimension"),0);
            GL20.glUniform1i(GL20.glGetUniformLocation(sky,"CmiJuliaHit"),1);GL13.glActiveTexture(GL13.GL_TEXTURE1);GL11.glBindTexture(GL11.GL_TEXTURE_2D,hit);
            GL20.glUniform1i(GL20.glGetUniformLocation(sky,"CmiTargetSky"),2);GL13.glActiveTexture(GL13.GL_TEXTURE2);GL11.glBindTexture(GL11.GL_TEXTURE_2D,target);
            geometry(vbo,new float[]{-1,-1,0,1,-1,0,-1,1,0,1,1,0});
            clear();GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP,0,4);var scene=pixels();int n=covered(scene);check(n>0&&n<SIZE*SIZE,"bounded shared mask");
            int middle=(SIZE/2*SIZE+SIZE/2)*4;check(Math.abs(scene.get(middle+1)-.6)<.001&&Math.abs(scene.get(middle+2)-.8)<.001,"native target color passes unchanged");
            var depthPixels=BufferUtils.createFloatBuffer(SIZE*SIZE);GL11.glReadPixels(0,0,SIZE,SIZE,GL11.GL_DEPTH_COMPONENT,GL11.GL_FLOAT,depthPixels);
            for(int i=0;i<depthPixels.limit();i++)check(depthPixels.get(i)==1,"sky does not write scene depth");
            clear();GL11.glEnable(GL11.GL_SCISSOR_TEST);GL11.glScissor(0,0,SIZE/2,SIZE);GL11.glDepthMask(true);GL11.glClearDepth(.01);GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);GL11.glDepthMask(false);GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP,0,4);scene=pixels();check(scene.get((SIZE/2*SIZE+SIZE/2-2)*4)>.8,"foreground terrain retained");check(scene.get(middle+1)>.5,"unoccluded target remains visible");
            vector(sky,"CmiRift",0,10,0,-1);clear();GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP,0,4);check(covered(pixels())==0,"STOP removes sky window");
            GL20.glUseProgram(clouds);matrix(clouds,"ModelViewMat",up);matrix(clouds,"ProjMat",projection);matrix(clouds,"CmiInverseCameraView",new Matrix4f(up).invert());matrix(clouds,"CmiSkyViewProjection",new Matrix4f(projection).mul(up));vector(clouds,"CmiRift",0,10,0,2);vector(clouds,"ColorModulator",1,1,1,1);GL20.glUniform1f(GL20.glGetUniformLocation(clouds,"FogStart"),10000);GL20.glUniform1f(GL20.glGetUniformLocation(clouds,"FogEnd"),20000);GL20.glUniform1i(GL20.glGetUniformLocation(clouds,"FogShape"),0);GL20.glUniform1i(GL20.glGetUniformLocation(clouds,"Sampler0"),0);GL20.glUniform1i(GL20.glGetUniformLocation(clouds,"CmiJuliaHit"),1);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glBindTexture(GL11.GL_TEXTURE_2D,white);GL20.glDisableVertexAttribArray(1);GL20.glVertexAttrib2f(1,.5f,.5f);GL20.glDisableVertexAttribArray(2);GL20.glVertexAttrib4f(2,1,1,1,1);
            for(float height:new float[]{5,20}){
                geometry(vbo,new float[]{-30,height,-30,30,height,-30,-30,height,30,30,height,30});clear();GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP,0,4);scene=pixels();
                check(height==5?scene.get(middle+1)>.9:scene.get(middle+1)<.1,height==5?"foreground vanilla cloud retained":"source cloud behind Julia excluded");
            }
            check(GL11.glGetError()==GL11.GL_NO_ERROR,"no GL errors");
        }finally{GL20.glDeleteProgram(sky);GL20.glDeleteProgram(clouds);GL15.glDeleteBuffers(vbo);GL30.glDeleteVertexArrays(vao);GL30.glDeleteFramebuffers(fbo);for(int t:new int[]{color,depth,white,target,hit})GL11.glDeleteTextures(t);}
    }
    static void stateAndCloudField()throws Exception {
        var stateMask=BufferUtils.createByteBuffer(4);
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();GL30.glDisablei(GL11.GL_BLEND,1);
        GL30.glColorMaski(1,false,true,false,true);
        GL40.glBlendFuncSeparatei(1,GL11.GL_ONE,GL11.GL_ZERO,GL11.GL_ZERO,GL11.GL_ONE);
        com.mojang.blaze3d.systems.RenderSystem.enableScissor(1,2,3,4);
        try(var state=new com.iridium126.createmanaindustry.client.allaystorm.rift.SkyRenderState()) {
            state.prepare();check(!GL11.glIsEnabled(GL11.GL_SCISSOR_TEST),"scissor disabled for full view");
            com.mojang.blaze3d.systems.RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_ONE,GL11.GL_ZERO);
            com.mojang.blaze3d.systems.RenderSystem.disableBlend();
        }
        check(GL11.glIsEnabled(GL11.GL_SCISSOR_TEST),"scissor restored");check(!GL30.glIsEnabledi(GL11.GL_BLEND,1),"per-attachment blend enable restored");
        GL30.glGetBooleani_v(GL11.GL_COLOR_WRITEMASK,1,stateMask);check(stateMask.get(0)==0&&stateMask.get(1)!=0&&stateMask.get(2)==0,"per-attachment color mask restored");
        check(GL30.glGetIntegeri(GL14.GL_BLEND_SRC_RGB,1)==GL11.GL_ONE&&GL30.glGetIntegeri(GL14.GL_BLEND_DST_ALPHA,1)==GL11.GL_ONE,"per-attachment blend function restored");
        com.mojang.blaze3d.systems.RenderSystem.disableScissor();com.mojang.blaze3d.systems.RenderSystem.disableBlend();GL11.glColorMask(true,true,true,true);
        String fragment="#version 330 core\nuniform vec3 Point;uniform bool MainView;out vec4 Color;\n"+resource("include/allay_sky.glsl")+resource("include/native_rift.glsl")+"\nvoid main(){cmiMainView=MainView;vec3 point=cmiStormCloudPosition(Point);Color=vec4(point.x,point.y,point.z,cmiStormCloudDensity(Point.xz));}";
        int v=compile(GL20.GL_VERTEX_SHADER,"#version 330 core\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.-1.,0.,1.);}"),f=compile(GL20.GL_FRAGMENT_SHADER,fragment),p=GL20.glCreateProgram();
        GL20.glAttachShader(p,v);GL20.glAttachShader(p,f);GL20.glLinkProgram(p);check(GL20.glGetProgrami(p,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(p));GL20.glDeleteShader(v);GL20.glDeleteShader(f);
        int fb=GL30.glGenFramebuffers(),tex=texture(GL30.GL_RGBA32F,1,1,GL11.GL_RGBA),vao=GL30.glGenVertexArrays();
        try {
            GL30.glBindVertexArray(vao);GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fb);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,tex,0);GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL11.glViewport(0,0,1,1);GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDisable(GL11.GL_BLEND);GL20.glUseProgram(p);
            vector(p,"CmiStormCenter",0,128,0,16);GL20.glUniform2f(GL20.glGetUniformLocation(p,"CmiStormCloud"),2,1);GL20.glUniform1i(GL20.glGetUniformLocation(p,"CmiSkyDimension"),0);GL20.glUniform1i(GL20.glGetUniformLocation(p,"MainView"),1);
            for(float[] sample:new float[][]{{0,0,.3f},{64,0,1.6f},{200,0,1f}}){
                GL20.glUniform3f(GL20.glGetUniformLocation(p,"Point"),sample[0],499,sample[1]);GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);var value=BufferUtils.createFloatBuffer(4);GL11.glReadPixels(0,0,1,1,GL11.GL_RGBA,GL11.GL_FLOAT,value);
                check(Math.abs(value.get(3)-sample[2])<.0001,"native cloud density field");check(value.get(1)==499,"native cloud height unchanged");
                if(sample[0]==200)check(value.get(0)==200&&value.get(2)==0,"outside disk remains native");
            }
            for(int target:new int[]{0,1}){
                GL20.glUniform1i(GL20.glGetUniformLocation(p,"CmiSkyDimension"),target);GL20.glUniform1i(GL20.glGetUniformLocation(p,"MainView"),target);GL20.glUniform3f(GL20.glGetUniformLocation(p,"Point"),64,499,0);GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);
                var value=BufferUtils.createFloatBuffer(4);GL11.glReadPixels(0,0,1,1,GL11.GL_RGBA,GL11.GL_FLOAT,value);check(value.get(0)==64&&value.get(2)==0&&value.get(3)==1,"target/cloud caches do not acquire storm warp");
            }
            check(GL11.glGetError()==GL11.GL_NO_ERROR,"state/cloud field GL errors");
        }finally{GL20.glDeleteProgram(p);GL30.glDeleteFramebuffers(fb);GL30.glDeleteVertexArrays(vao);GL11.glDeleteTextures(tex);}
    }
    public static void main(String[] args)throws Exception{
        check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);
        long window=GLFW.glfwCreateWindow(SIZE,SIZE,"CMI rift sky validation",0,0);check(window!=0,"OpenGL context");
        try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();com.mojang.blaze3d.systems.RenderSystem.initRenderThread();System.out.println(GL11.glGetString(GL11.GL_RENDERER));run();stateAndCloudField();System.out.println("Rift sky GPU: "+checks+" assertions passed");}
        finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
