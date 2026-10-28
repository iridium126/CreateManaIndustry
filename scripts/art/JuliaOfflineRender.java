import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import org.lwjgl.BufferUtils;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.*;
import java.nio.file.*;

/** Unwarped quaternion polynomial Julia slices. Only the camera changes across views. */
public final class JuliaOfflineRender {
    record Candidate(String id,int power,float a,float b,float c,float d,float w,float roll){}
    static int program,vao,framebuffer,texture;
    static long window;
    static void uniform(String key,int value){GL20.glUniform1i(GL20.glGetUniformLocation(program,key),value);}
    static void uniform(String key,float value){GL20.glUniform1f(GL20.glGetUniformLocation(program,key),value);}
    static void vec3(String key,float a,float b,float c){GL20.glUniform3f(GL20.glGetUniformLocation(program,key),a,b,c);}
    static int shader(int type,String source){int s=GL20.glCreateShader(type);GL20.glShaderSource(s,source);GL20.glCompileShader(s);if(GL20.glGetShaderi(s,GL20.GL_COMPILE_STATUS)==0)throw new IllegalStateException(GL20.glGetShaderInfoLog(s));return s;}
    static void initialize()throws Exception{
        if(!GLFW.glfwInit())throw new IllegalStateException("GLFW initialization failed");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,3);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,3);
        window=GLFW.glfwCreateWindow(16,16,"Offline Julia rendering",0,0);if(window==0)throw new IllegalStateException("Hidden GL context failed");GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
        int vs=shader(GL20.GL_VERTEX_SHADER,"#version 330 core\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.-1.,0.,1.);}");
        int fs=shader(GL20.GL_FRAGMENT_SHADER,Files.readString(Path.of("scripts/art/julia-offline.frag")));
        program=GL20.glCreateProgram();GL20.glAttachShader(program,vs);GL20.glAttachShader(program,fs);GL20.glLinkProgram(program);if(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(program));GL20.glDeleteShader(vs);GL20.glDeleteShader(fs);GL20.glUseProgram(program);
        vao=GL30.glGenVertexArrays();GL30.glBindVertexArray(vao);framebuffer=GL30.glGenFramebuffers();texture=GL11.glGenTextures();
        System.out.println("Renderer: "+GL11.glGetString(GL11.GL_RENDERER));
    }
    static void draw(Candidate item,int size,int iter,int step,float eps,float scale,float ex,float ey,float ez,int mask,Path path)throws Exception {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,framebuffer);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,size,size,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(ByteBuffer)null);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,texture,0);
        if(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Incomplete offline framebuffer");GL11.glViewport(0,0,size,size);
        GL20.glUniform2f(GL20.glGetUniformLocation(program,"resolution"),size,size);GL20.glUniform4f(GL20.glGetUniformLocation(program,"juliaC"),item.a,item.b,item.c,item.d);uniform("degree",item.power);uniform("iterations",iter);uniform("steps",step);uniform("epsilon",eps);uniform("viewScale",scale);uniform("sliceW",item.w);uniform("roll",item.roll);uniform("silhouette",mask);vec3("eye",ex,ey,ez);vec3("upHint",0,1,0);
        GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);ByteBuffer rgba=BufferUtils.createByteBuffer(size*size*4);GL11.glReadPixels(0,0,size,size,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,rgba);
        BufferedImage image=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<size;y++)for(int x=0;x<size;x++){int offset=((size-1-y)*size+x)*4;int r=Byte.toUnsignedInt(rgba.get(offset)),g=Byte.toUnsignedInt(rgba.get(offset+1)),b=Byte.toUnsignedInt(rgba.get(offset+2)),a=Byte.toUnsignedInt(rgba.get(offset+3));image.setRGB(x,y,a<<24|r<<16|g<<8|b);}
        Files.createDirectories(path.getParent());ImageIO.write(image,"png",path.toFile());System.out.println(path);
    }
    public static void main(String[] args)throws Exception {
        var c=new Candidate("051",2,-.5f,0,0,0,0,0);Path output=Path.of("build/julia-offline");Files.createDirectories(output);
        Files.writeString(output.resolve("parameters.json"),"{\"id\":51,\"power\":2,\"c_real\":-0.5,\"slice_w\":0}\n");
        initialize();try{
            for(int view=0;view<3;view++){
                float[] camera=switch(view){case 0->new float[]{0,0,4};case 1->new float[]{2.2f,1.3f,3.1f};default->new float[]{3.6f,.8f,.8f};};
                draw(c,900,192,1200,.00035f,1.85f,camera[0],camera[1],camera[2],1,output.resolve("render/"+c.id+"-silhouette"+view+".png"));
            }
            draw(c,900,192,1200,.00035f,1.85f,0,0,4,1,output.resolve("render/"+c.id+"-mask192.png"));
        }finally{GL20.glDeleteProgram(program);GL30.glDeleteVertexArrays(vao);GL30.glDeleteFramebuffers(framebuffer);GL11.glDeleteTextures(texture);GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
