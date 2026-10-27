import java.nio.*;
import java.nio.file.*;
import java.io.*;
import org.joml.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;

/** Compares the shipped runtime contour with the independent original quadratic DE renderer. */
public final class JuliaProjectionGpuValidation {
    static final int SIZE=640;
    static int checks;
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static int shader(int type,String text){int id=GL20.glCreateShader(type);GL20.glShaderSource(id,text);GL20.glCompileShader(id);check(GL20.glGetShaderi(id,GL20.GL_COMPILE_STATUS)!=0,GL20.glGetShaderInfoLog(id));return id;}
    static int program(String vertex,String fragment){int p=GL20.glCreateProgram(),v=shader(GL20.GL_VERTEX_SHADER,vertex),f=shader(GL20.GL_FRAGMENT_SHADER,fragment);GL20.glAttachShader(p,v);GL20.glAttachShader(p,f);GL20.glLinkProgram(p);check(GL20.glGetProgrami(p,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(p));GL20.glDeleteShader(v);GL20.glDeleteShader(f);return p;}
    static void matrix(int p,String name,Matrix4f value){var b=BufferUtils.createFloatBuffer(16);value.get(b);GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(p,name),false,b);}
    static void vector(int p,String name,float x,float y,float z,float w){
        GL20.glUniform4f(GL20.glGetUniformLocation(p,name),x,y,z,w);
        if(name.equals("Shape")){
            var style=com.iridium126.createmanaindustry.client.allaystorm.rift.JuliaShape.projection(java.lang.Math.sqrt((double)x*x+(double)y*y+(double)z*z));
            GL20.glUniform2f(GL20.glGetUniformLocation(p,"ProjectionStyle"),style.weak(),style.gain());
        }
    }
    static double iou(boolean[] a,boolean[] b){int both=0,either=0;for(int i=0;i<a.length;i++){if(a[i]&&b[i])both++;if(a[i]||b[i])either++;}return either==0?1:(double)both/either;}
    static void clear(){GL30.glClearBufferfv(GL11.GL_COLOR,0,new float[]{-1,0,0,0});GL30.glClearBufferfv(GL11.GL_DEPTH,0,new float[]{1});}
    static boolean[] mask(){var b=BufferUtils.createFloatBuffer(SIZE*SIZE);GL11.glReadPixels(0,0,SIZE,SIZE,GL11.GL_RED,GL11.GL_FLOAT,b);var result=new boolean[SIZE*SIZE];for(int i=0;i<result.length;i++)result[i]=b.get(i)>0;return result;}
    static int covered(boolean[] mask){int n=0;for(boolean pixel:mask)if(pixel)n++;return n;}
    static void save(boolean[] mask,String name)throws Exception {
        var output=Path.of("build/julia-projection-diagnostics");Files.createDirectories(output);
        var image=new java.awt.image.BufferedImage(SIZE,SIZE,java.awt.image.BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<SIZE;y++)for(int x=0;x<SIZE;x++)image.setRGB(x,SIZE-1-y,mask[y*SIZE+x]?0xffffff:0);
        javax.imageio.ImageIO.write(image,"png",output.resolve(name+".png").toFile());
    }
    static void run()throws Exception {
        Path shaderRoot=Path.of("src/main/resources/assets/createmanaindustry/shaders/core");
        int runtime=program(Files.readString(shaderRoot.resolve("julia_projection.vsh")),Files.readString(shaderRoot.resolve("julia_projection.fsh")));
        int original=program("#version 330 core\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.-1.,0.,1.);}",Files.readString(Path.of("scripts/art/julia-offline.frag")));
        var shape=com.iridium126.createmanaindustry.client.allaystorm.rift.JuliaShape.load();
        float[] segments=shape.segments;int count=segments.length/4;
        check(count>0,"production Julia loaded from bundled data resources");
        int vao=GL30.glGenVertexArrays(),buffer=GL15.glGenBuffers(),texture=GL11.glGenTextures(),fbo=GL30.glGenFramebuffers(),color=GL11.glGenTextures(),depth=GL30.glGenRenderbuffers();
        try {
            GL30.glBindVertexArray(vao);GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER,buffer);GL15.glBufferData(GL31.GL_TEXTURE_BUFFER,segments,GL15.GL_STATIC_DRAW);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,texture);GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,GL30.GL_RGBA32F,buffer);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,color);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL30.GL_RGBA32F,SIZE,SIZE,0,GL11.GL_RGBA,GL11.GL_FLOAT,(ByteBuffer)null);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER,depth);GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER,GL14.GL_DEPTH_COMPONENT24,SIZE,SIZE);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,color,0);GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER,GL30.GL_DEPTH_ATTACHMENT,GL30.GL_RENDERBUFFER,depth);GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            check(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)==GL30.GL_FRAMEBUFFER_COMPLETE,"framebuffer");
            GL11.glViewport(0,0,SIZE,SIZE);GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthMask(true);GL11.glDepthFunc(GL11.GL_LESS);GL11.glDisable(GL11.GL_CULL_FACE);GL11.glDisable(GL11.GL_BLEND);
            GL20.glUseProgram(runtime);GL20.glUniform1i(GL20.glGetUniformLocation(runtime,"Segments"),0);
            matrix(runtime,"View",new Matrix4f().lookAt(0,0,0,0,1,0,0,0,1));matrix(runtime,"Projection",new Matrix4f().ortho(-1.85f,1.85f,-1.85f,1.85f,.01f,10));vector(runtime,"Shape",0,4,0,1);
            clear();GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_STRIP,0,258,count);boolean[] actual=mask();
            GL20.glUseProgram(original);GL20.glUniform2f(GL20.glGetUniformLocation(original,"resolution"),SIZE,SIZE);vector(original,"juliaC",-.5f,0,0,0);
            for(var value:new Object[][]{{"degree",2},{"iterations",128},{"steps",1100},{"silhouette",1}})GL20.glUniform1i(GL20.glGetUniformLocation(original,(String)value[0]),(int)value[1]);
            GL20.glUniform1f(GL20.glGetUniformLocation(original,"epsilon"),.00045f);GL20.glUniform1f(GL20.glGetUniformLocation(original,"viewScale"),1.85f);
            GL20.glUniform3f(GL20.glGetUniformLocation(original,"eye"),0,0,4);GL20.glUniform3f(GL20.glGetUniformLocation(original,"upHint"),0,1,0);
            clear();GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);boolean[] expected=mask();
            int intersection=0,union=0;for(int i=0;i<actual.length;i++){if(actual[i]&&expected[i])intersection++;if(actual[i]||expected[i])union++;}
            double iou=(double)intersection/union;System.out.println("Runtime/original Julia silhouette IoU="+iou);check(iou>.99,"original quadratic shape preserved");
            double ratio=1.2*16/64,scale=shape.scale(16,128,384,2);
            GL20.glUseProgram(runtime);matrix(runtime,"View",new Matrix4f().lookAt(0,0,0,0,1,0,0,0,1));matrix(runtime,"Projection",new Matrix4f().perspective((float)java.lang.Math.PI/2,1,.05f,256));vector(runtime,"Shape",0,320,0,(float)scale);
            clear();GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_STRIP,0,258,count);boolean[] reference=mask();
            save(reference,"runtime-reference-weak");save(actual,"runtime-original-orthographic");
            var directView=new Matrix4f().lookAt(0,0,0,0,1,0,0,0,1);
            for(float fov:new float[]{60,90,110})for(float tilt:new float[]{0,.22f,-.22f}) {
                var view=new Matrix4f().lookAt(0,0,0,tilt,1,0,0,0,1);
                var projection=new Matrix4f().perspective((float)java.lang.Math.toRadians(fov),1,.05f,256);
                GL20.glUseProgram(runtime);matrix(runtime,"View",view);matrix(runtime,"Projection",projection);
                clear();GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_STRIP,0,258,count);var result=mask();
                GL20.glUseProgram(original);GL20.glUniform1i(GL20.glGetUniformLocation(original,"perspective"),2);
                double gain=com.iridium126.createmanaindustry.client.allaystorm.rift.JuliaShape.projection(320).gain();
                GL20.glUniform3f(GL20.glGetUniformLocation(original,"eye"),0,0,(float)(320/(scale*gain)));
                matrix(original,"viewRayTransform",new Matrix4f().rotationX((float)(-java.lang.Math.PI/2)).mul(new Matrix4f(view).invert()).mul(new Matrix4f(projection).invert()));
                clear();GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);var expectedFront=mask();
                double score=iou(result,expectedFront);System.out.println("Weak/original front IoU fov="+fov+" tilt="+tilt+": "+score);
                check(score>.99,"reference matches unmodified front projection after head rotation and FOV changes");
                if(fov==90&&tilt==0)save(expectedFront,"original-reference-orthographic");
            }
            GL20.glUseProgram(runtime);
            matrix(runtime,"View",directView);matrix(runtime,"Projection",new Matrix4f().perspective((float)java.lang.Math.PI/2,1,.05f,256));
            for(int i=0;i<720;i++){double angle=i*java.lang.Math.PI/360;int x=(int)((1+ratio*java.lang.Math.cos(angle))*SIZE/2),y=(int)((1+ratio*java.lang.Math.sin(angle))*SIZE/2);check(reference[y*SIZE+x],"reference circle at angle "+i);}
            for(int i=0;i<720;i++){double angle=i*java.lang.Math.PI/360;int x=(int)((1+.2*java.lang.Math.cos(angle))*SIZE/2),y=(int)((1+.2*java.lang.Math.sin(angle))*SIZE/2);check(reference[y*SIZE+x],"entrance circle at angle "+i);}
            for(float[] centre:new float[][]{{0,320,0},{-320,320,0},{-512,320,0},{-100,100,0},{-100,0,0},{0,-200,0}}) {
                var view=new Matrix4f().lookAt(0,0,0,centre[0],centre[1],centre[2],0,0,1);
                var projection=new Matrix4f().perspective((float)java.lang.Math.PI/2,1,.05f,256);
                matrix(runtime,"View",view);matrix(runtime,"Projection",projection);
                vector(runtime,"Shape",centre[0],centre[1],centre[2],(float)shape.entranceScale);
                clear();GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_STRIP,0,258,count);var entryMask=mask();
                for(int i=0;i<720;i++){
                    double angle=i*java.lang.Math.PI/360;
                    var screen=new Vector4f(centre[0]+(float)(64*java.lang.Math.cos(angle)),centre[1],centre[2]+(float)(64*java.lang.Math.sin(angle)),1);
                    projection.transform(view.transform(screen));
                    if(screen.w<=0)continue;
                    int x=(int)((screen.x/screen.w+1)*SIZE/2),y=(int)((screen.y/screen.w+1)*SIZE/2);
                    if(x>=0&&x<SIZE&&y>=0&&y<SIZE)check(entryMask[y*SIZE+x],"first frame entry coverage "+java.util.Arrays.toString(centre)+" angle="+i);
                }
            }
            for(float[] centre:new float[][]{{0,320,0},{-320,320,0},{0,-320,0}}) {
                var view=new Matrix4f().lookAt(0,0,0,centre[0],centre[1],centre[2],0,0,1);
                var projection=new Matrix4f().perspective((float)java.lang.Math.PI/2,1,.05f,256);
                for(double radius:new double[]{16,32,64}) {
                    double s=shape.scale(radius,128,384,2);
                    GL20.glUseProgram(runtime);matrix(runtime,"View",view);matrix(runtime,"Projection",projection);vector(runtime,"Shape",centre[0],centre[1],centre[2],(float)s);
                    clear();GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_STRIP,0,258,count);var projected=mask();
                    var lens=com.iridium126.createmanaindustry.client.allaystorm.rift.JuliaShape.projection(java.lang.Math.sqrt((double)centre[0]*centre[0]+(double)centre[1]*centre[1]));
                    GL20.glUseProgram(original);GL20.glUniform1i(GL20.glGetUniformLocation(original,"perspective"),2);
                    GL20.glUniform3f(GL20.glGetUniformLocation(original,"eye"),(float)(-centre[0]/(s*lens.gain())),(float)(-centre[2]/(s*lens.gain())),(float)(centre[1]/(s*lens.gain())));
                    matrix(original,"viewRayTransform",new Matrix4f().rotationX((float)(-java.lang.Math.PI/2)).mul(new Matrix4f(view).invert()).mul(new Matrix4f(projection).invert()));
                    clear();GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);var expectedView=mask();
                    check(iou(projected,expectedView)>.99,"original Julia preserved for oblique/above views and population radius="+radius);
                }
            }
            GL20.glUseProgram(runtime);matrix(runtime,"Projection",new Matrix4f().perspective((float)java.lang.Math.PI/2,1,.05f,256));vector(runtime,"Shape",0,320,0,(float)scale);
            matrix(runtime,"View",new Matrix4f().lookAt(0,0,0,0,0,-1,0,1,0));clear();GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_STRIP,0,258,count);boolean[] horizon=mask();for(int x=0;x<SIZE;x++)check(!horizon[(SIZE/2)*SIZE+x],"no horizon stripe");
            check(GL11.glGetError()==GL11.GL_NO_ERROR,"no GL errors");
        } finally {GL20.glDeleteProgram(runtime);GL20.glDeleteProgram(original);GL30.glDeleteVertexArrays(vao);GL15.glDeleteBuffers(buffer);GL11.glDeleteTextures(texture);GL30.glDeleteFramebuffers(fbo);GL11.glDeleteTextures(color);GL30.glDeleteRenderbuffers(depth);}
    }
    public static void main(String[] args)throws Exception {
        check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,3);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,3);long window=GLFW.glfwCreateWindow(SIZE,SIZE,"Julia projection validation",0,0);check(window!=0,"context");
        try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();run();System.out.println("Julia projection: "+checks+" assertions passed");}finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
