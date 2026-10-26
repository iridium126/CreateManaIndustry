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
        try{
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,color,0);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_DEPTH_ATTACHMENT,GL11.GL_TEXTURE_2D,depth,0);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);check(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)==GL30.GL_FRAMEBUFFER_COMPLETE,"sky framebuffer");
            GL30.glBindVertexArray(vao);GL11.glViewport(0,0,SIZE,SIZE);GL11.glDisable(GL11.GL_CULL_FACE);GL11.glDisable(GL11.GL_BLEND);GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthFunc(GL11.GL_LEQUAL);
            Matrix4f projection=new Matrix4f().perspective((float)Math.PI/2,1,.1f,256),up=new Matrix4f().lookAt(0,0,0,0,1,0,0,0,-1);
            GL20.glUseProgram(sky);matrix(sky,"CmiView",up);matrix(sky,"CmiProjection",projection);matrix(sky,"CmiInverseView",new Matrix4f(up).invert());matrix(sky,"CmiInverseProjection",new Matrix4f(projection).invert());
            vector(sky,"CmiSkyContext",.25f,0,0,123);vector(sky,"CmiRift",0,100,0,0);GL20.glUniform1i(GL20.glGetUniformLocation(sky,"CmiSkyDimension"),0);
            geometry(vbo,new float[]{-1,-1,0,1,-1,0,-1,1,0,1,1,0});
            clear();GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP,0,4);int initial=covered(pixels());check(initial>0&&initial<SIZE*SIZE,"immediate center, bounded sky window");
            vector(sky,"CmiRift",0,100,0,2);clear();GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP,0,4);int expanded=covered(pixels());check(expanded>initial,"two-second outer expansion");
            var depthPixels=BufferUtils.createFloatBuffer(SIZE*SIZE);GL11.glReadPixels(0,0,SIZE,SIZE,GL11.GL_DEPTH_COMPONENT,GL11.GL_FLOAT,depthPixels);
            for(int i=0;i<depthPixels.limit();i++)check(depthPixels.get(i)==1,"sky must not hide source terrain behind the plane");
            Matrix4f horizontal=new Matrix4f().lookAt(0,0,0,0,0,-1,0,1,0);matrix(sky,"CmiView",horizontal);matrix(sky,"CmiInverseView",new Matrix4f(horizontal).invert());
            vector(sky,"CmiRift",0,10,0,2);clear();GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP,0,4);var horizon=pixels();
            for(int x=0;x<SIZE;x++)check(horizon.get(((SIZE/2-1)*SIZE+x)*4)>.8,"horizontal look has no horizon stripe");
            Matrix4f down=new Matrix4f().lookAt(0,0,0,0,-1,0,0,0,1);matrix(sky,"CmiView",down);matrix(sky,"CmiInverseView",new Matrix4f(down).invert());vector(sky,"CmiRift",0,-10,0,2);
            clear();GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP,0,4);check(covered(pixels())>SIZE*SIZE/2,"rift visible from above");
            matrix(sky,"CmiView",up);matrix(sky,"CmiInverseView",new Matrix4f(up).invert());vector(sky,"CmiRift",0,10,0,-1);clear();GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP,0,4);check(covered(pixels())==0,"STOP removes sky window");
            GL20.glUniform1i(GL20.glGetUniformLocation(sky,"CmiSkyDimension"),1);clear();GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP,0,4);check(covered(pixels())==SIZE*SIZE,"dimension evaluates shared sky without a window");
            GL20.glUseProgram(clouds);matrix(clouds,"ModelViewMat",up);matrix(clouds,"ProjMat",projection);matrix(clouds,"CmiInverseCameraView",new Matrix4f(up).invert());vector(clouds,"CmiRift",0,10,0,2);vector(clouds,"ColorModulator",1,1,1,1);GL20.glUniform1f(GL20.glGetUniformLocation(clouds,"FogStart"),10000);GL20.glUniform1f(GL20.glGetUniformLocation(clouds,"FogEnd"),20000);GL20.glUniform1i(GL20.glGetUniformLocation(clouds,"FogShape"),0);GL20.glUniform1i(GL20.glGetUniformLocation(clouds,"Sampler0"),0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glBindTexture(GL11.GL_TEXTURE_2D,white);GL20.glDisableVertexAttribArray(1);GL20.glVertexAttrib2f(1,.5f,.5f);GL20.glDisableVertexAttribArray(2);GL20.glVertexAttrib4f(2,1,1,1,1);
            for(float height:new float[]{5,20}){
                geometry(vbo,new float[]{-30,height,-30,30,height,-30,-30,height,30,30,height,30});clear();GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP,0,4);var pixels=pixels();int middle=(SIZE/2*SIZE+SIZE/2)*4;
                check(height==5?pixels.get(middle+1)>.9:pixels.get(middle+1)<.1,height==5?"foreground cloud retained":"source cloud behind rift excluded");
            }
            check(GL11.glGetError()==GL11.GL_NO_ERROR,"no GL errors");
        }finally{GL20.glDeleteProgram(sky);GL20.glDeleteProgram(clouds);GL15.glDeleteBuffers(vbo);GL30.glDeleteVertexArrays(vao);GL30.glDeleteFramebuffers(fbo);GL11.glDeleteTextures(color);GL11.glDeleteTextures(depth);GL11.glDeleteTextures(white);}
    }
    public static void main(String[] args)throws Exception{
        check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,3);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,2);
        long window=GLFW.glfwCreateWindow(SIZE,SIZE,"CMI rift sky validation",0,0);check(window!=0,"OpenGL context");
        try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();System.out.println(GL11.glGetString(GL11.GL_RENDERER));run();System.out.println("Rift sky GPU: "+checks+" assertions passed");}
        finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
