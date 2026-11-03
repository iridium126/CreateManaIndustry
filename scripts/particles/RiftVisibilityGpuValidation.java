import com.iridium126.createmanaindustry.client.allaystorm.rift.*;
import com.iridium126.createmanaindustry.client.render.shaderpack.NativeSkySources;
import com.iridium126.createmanaindustry.infrastructure.config.RiftCulling;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import java.nio.file.*;
import java.util.*;

/** Executes production conditional transfers and fragment clip wrapper in a hidden context. */
public class RiftVisibilityGpuValidation {
 static int checks;
 static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 static void field(String name,Object value)throws Exception{var f=RiftVisibility.class.getDeclaredField(name);f.setAccessible(true);f.set(null,value);}
 static int shader(int type,String source){int s=GL20.glCreateShader(type);GL20.glShaderSource(s,source);GL20.glCompileShader(s);check(GL20.glGetShaderi(s,GL20.GL_COMPILE_STATUS)!=0,GL20.glGetShaderInfoLog(s));return s;}
 static int program(String fragment){int p=GL20.glCreateProgram();for(int s:new int[]{shader(GL20.GL_VERTEX_SHADER,"#version 150\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.-1.,0,1);}"),shader(GL20.GL_FRAGMENT_SHADER,fragment)}){GL20.glAttachShader(p,s);GL20.glDeleteShader(s);}GL20.glLinkProgram(p);check(GL20.glGetProgrami(p,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(p));return p;}
 static int texture(int format,int w,int h,int external,float[] data){int t=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,t);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,format,w,h,0,external,GL11.GL_FLOAT,data);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);return t;}
 static void clippedMasks(int vao,int fbo)throws Exception{
  int p=program(NativeSkySources.clipTerminal("#version 150\nuniform sampler2D CmiJuliaHit;out vec4 color;void main(){color=vec4(.5,.6,.7,1);}"));
  GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_DEPTH_ATTACHMENT,GL11.GL_TEXTURE_2D,0,0);GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDisable(GL11.GL_SCISSOR_TEST);GL30.glBindVertexArray(vao);
  for(int size:new int[]{31,32,63})for(int pattern=0;pattern<6;pattern++)for(boolean clip:new boolean[]{false,true}){
   float[] hits=new float[size*size];Arrays.fill(hits,-1);
   for(int y=0;y<size;y++)for(int x=0;x<size;x++)if(pattern==1||pattern==2&&x==size/2&&y==size/2||pattern==3&&x==0&&y==0||pattern==4&&x==size-1&&y==size-1||pattern==5&&x==y)hits[y*size+x]=10;
   int output=texture(GL30.GL_RGBA32F,size,size,GL11.GL_RGBA,null),hit=texture(GL30.GL_R32F,size,size,GL11.GL_RED,hits);
   GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,output,0);GL11.glViewport(0,0,size,size);
   GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glBindTexture(GL11.GL_TEXTURE_2D,hit);GL20.glUseProgram(p);GL20.glUniform1i(GL20.glGetUniformLocation(p,"CmiJuliaHit"),0);GL20.glUniform1i(GL20.glGetUniformLocation(p,"CmiTargetClip"),clip?1:0);
   GL30.glClearBufferfv(GL11.GL_COLOR,0,new float[]{0,0,0,0});GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);
   float[] pixels=new float[size*size*4];GL11.glReadPixels(0,0,size,size,GL11.GL_RGBA,GL11.GL_FLOAT,pixels);
   for(int y=0;y<size;y++)for(int x=0;x<size;x++){
    boolean required=!clip||x/2>=(size-2)/2&&y/2>=(size-2)/2;
    // Each member of a derivative quad must retain the bilinear halo of every member.
    for(int qy=y/2*2;qy<y/2*2+2;qy++)for(int qx=x/2*2;qx<x/2*2+2;qx++)for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++)if(hits[Math.clamp(qy+dy,0,size-1)*size+Math.clamp(qx+dx,0,size-1)]>0)required=true;
    check(pixels[(y*size+x)*4]==(required?.5f:0),"current pixels/halo/quad/history; size="+size+" pattern="+pattern+" clip="+clip);
   }
   GL11.glDeleteTextures(output);GL11.glDeleteTextures(hit);
  }
  GL20.glDeleteProgram(p);
 }
 static void conditionalWork(int vao,int fbo,int color,int depth)throws Exception{
  int counter=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,counter);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,new int[]{0},GL15.GL_DYNAMIC_COPY);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,counter);
  int draw=program("#version 430\nlayout(std430,binding=0)buffer Count{uint calls;};out vec4 color;void main(){atomicAdd(calls,1);color=vec4(.75,0,0,1);}");
  int cs=shader(GL43.GL_COMPUTE_SHADER,"#version 430\nlayout(local_size_x=1)in;layout(std430,binding=0)buffer Count{uint calls;};void main(){atomicAdd(calls,1);}");
  int compute=GL20.glCreateProgram();GL20.glAttachShader(compute,cs);GL20.glLinkProgram(compute);check(GL20.glGetProgrami(compute,GL20.GL_LINK_STATUS)!=0,"compute link");GL20.glDeleteShader(cs);
  float[] initial=new float[32*32*4];Arrays.fill(initial,.125f);
  GL11.glBindTexture(GL11.GL_TEXTURE_2D,color);int colorFormat=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_INTERNAL_FORMAT);
  GL11.glBindTexture(GL11.GL_TEXTURE_2D,depth);int depthFormat=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_INTERNAL_FORMAT);
  int copy=texture(colorFormat,32,32,GL11.GL_RGBA,initial),depthCopy=texture(depthFormat,32,32,GL11.GL_DEPTH_COMPONENT,null);
  for(int scene:new int[]{0,1,0,2,0,3}){
   boolean hidden=scene==0;
   GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);GL11.glViewport(0,0,32,32);GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDepthMask(true);GL11.glDisable(GL11.GL_SCISSOR_TEST);GL30.glClearBufferfv(GL11.GL_COLOR,0,new float[]{.25f,0,0,1});GL30.glClearBufferfv(GL11.GL_DEPTH,0,new float[]{scene==1?1:.2f});
   if(scene>=2){GL11.glEnable(GL11.GL_SCISSOR_TEST);GL11.glScissor(scene==2?0:31,16,1,1);GL30.glClearBufferfv(GL11.GL_DEPTH,0,new float[]{1});GL11.glDisable(GL11.GL_SCISSOR_TEST);}
   GL11.glBindTexture(GL11.GL_TEXTURE_2D,copy);GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,0,32,32,GL11.GL_RGBA,GL11.GL_FLOAT,initial);
   RiftTargetTransfer.mipmaps(color);
   GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,counter);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,new int[]{0});
   GL20.glUseProgram(draw);GL30.glBindVertexArray(vao);
   try(var scope=RiftVisibility.depth(true)){
    GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);GL20.glUseProgram(compute);GL43.glDispatchCompute(1,1,1);GL20.glUseProgram(draw);
    RiftTargetTransfer.copy(color,copy,32,32,false);RiftTargetTransfer.copy(depth,depthCopy,32,32,true);RiftTargetTransfer.mipmaps(color);
   }
   GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);int[] calls=new int[1];GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,counter);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,calls);check(calls[0]==(hidden?0:1025),"conditional draw AND compute, including single-pixel slits; scene="+scene+" calls="+calls[0]);
   GL11.glBindTexture(GL11.GL_TEXTURE_2D,copy);float[] result=new float[4096];GL11.glGetTexImage(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA,GL11.GL_FLOAT,result);check(result[0]==(hidden?.125f:.75f),"conditional history copy");
   GL11.glBindTexture(GL11.GL_TEXTURE_2D,color);float[] last=new float[4];GL11.glGetTexImage(GL11.GL_TEXTURE_2D,5,GL11.GL_RGBA,GL11.GL_FLOAT,last);check(last[0]==(hidden?.25f:.75f),"conditional complete mipmap chain");
   if(!hidden){GL11.glBindTexture(GL11.GL_TEXTURE_2D,depthCopy);float[] d=new float[1024];GL11.glGetTexImage(GL11.GL_TEXTURE_2D,0,GL11.GL_DEPTH_COMPONENT,GL11.GL_FLOAT,d);check(Math.abs(d[16*32+16]-(scene==1?1:.2f))<.00001,"depth blit format/value");}
   check(GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)==draw,"program restored");check(GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)==fbo,"framebuffer restored");
  }
  GL20.glUseProgram(0);GL20.glDeleteProgram(draw);GL20.glDeleteProgram(compute);GL15.glDeleteBuffers(counter);GL11.glDeleteTextures(copy);GL11.glDeleteTextures(depthCopy);
 }
 static void fallbacks(int color,int depth)throws Exception{
  for(RiftCulling mode:RiftCulling.values()){
   field("mode",mode);try(var scope=RiftVisibility.depth(false,depth)){check(!RiftVisibility.conditional(),"unqualified/no-culling mode keeps visible");}
  }
  field("mode",RiftCulling.FRUSTUM_DEPTH);
  try(var scope=RiftVisibility.depth(true,color)){check(!RiftVisibility.conditional(),"mismatched depth resource falls back");}
  GL11.glDepthRange(.1,1);try(var scope=RiftVisibility.depth(true,depth)){check(!RiftVisibility.conditional(),"nonstandard depth range falls back");}finally{GL11.glDepthRange(0,1);}
  GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);try(var scope=RiftVisibility.depth(true,depth)){check(!RiftVisibility.conditional(),"discard state falls back");}finally{GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);}
  int other=GL15.glGenQueries();GL15.glBeginQuery(GL33.GL_ANY_SAMPLES_PASSED,other);try(var scope=RiftVisibility.depth(true,depth)){check(!RiftVisibility.conditional(),"existing occlusion query falls back");}finally{GL15.glEndQuery(GL33.GL_ANY_SAMPLES_PASSED);GL15.glDeleteQueries(other);}
  field("clipping",true);field("rectangle",new float[]{1,1,-1,-1,0});RiftVisibility.scissor(32,32);int[] box=new int[4];GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX,box);check(box[2]==0&&box[3]==0,"NONE plus clipping outside view is an empty scissor");GL11.glDisable(GL11.GL_SCISSOR_TEST);field("rectangle",new float[]{-1,-1,1,1,.4f});
 }
 static void mrtIsolation(int depth){
  int second=texture(GL30.GL_RGBA32F,32,32,GL11.GL_RGBA,null);
  GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT1,GL11.GL_TEXTURE_2D,second,0);GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0,GL30.GL_COLOR_ATTACHMENT1});GL11.glColorMask(true,true,true,true);GL30.glClearBufferfv(GL11.GL_COLOR,1,new float[]{.375f,.5f,.625f,1});
  GL30.glColorMaski(1,false,true,false,true);
  try(var scope=RiftVisibility.depth(true,depth)){}catch(Exception e){throw new RuntimeException(e);}
  GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT1);float[] value=new float[4];GL11.glReadPixels(16,16,1,1,GL11.GL_RGBA,GL11.GL_FLOAT,value);check(value[0]==.375f&&value[1]==.5f&&value[2]==.625f,"occlusion proxy cannot pollute other MRT attachments");
  var mask=org.lwjgl.BufferUtils.createByteBuffer(4);GL30.glGetBooleani_v(GL11.GL_COLOR_WRITEMASK,1,mask);check(mask.get(0)==0&&mask.get(1)!=0&&mask.get(2)==0&&mask.get(3)!=0,"independent MRT masks restored");
  GL30.glColorMaski(1,true,true,true,true);GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT1,GL11.GL_TEXTURE_2D,0,0);GL11.glDeleteTextures(second);
 }
 static void boundedProxy()throws Exception{
  field("rectangle",new float[]{-.25f,-.25f,.25f,.25f,.4f});
  GL11.glDepthMask(true);GL11.glDisable(GL11.GL_SCISSOR_TEST);GL30.glClearBufferfv(GL11.GL_DEPTH,0,new float[]{1});GL30.glClearBufferfv(GL11.GL_COLOR,0,new float[]{.25f,0,0,1});
  GL11.glEnable(GL11.GL_SCISSOR_TEST);GL11.glScissor(8,8,16,16);GL30.glClearBufferfv(GL11.GL_DEPTH,0,new float[]{.2f});GL11.glDisable(GL11.GL_SCISSOR_TEST);
  try(var scope=RiftVisibility.depth(true)){GL30.glClearBufferfv(GL11.GL_COLOR,0,new float[]{.75f,0,0,1});}
  float[] pixel=new float[4];GL11.glReadPixels(16,16,1,1,GL11.GL_RGBA,GL11.GL_FLOAT,pixel);check(pixel[0]==.25f,"local opaque occluder covers conservative rectangle while background is visible");
  GL30.glClearBufferfv(GL11.GL_DEPTH,0,new float[]{1});GL11.glEnable(GL32.GL_SAMPLE_MASK);GL32.glSampleMaski(0,0);GL11.glEnable(GL13.GL_SAMPLE_COVERAGE);GL13.glSampleCoverage(0,false);
  try(var scope=RiftVisibility.depth(true)){GL30.glClearBufferfv(GL11.GL_COLOR,0,new float[]{.75f,0,0,1});}
  GL11.glReadPixels(16,16,1,1,GL11.GL_RGBA,GL11.GL_FLOAT,pixel);check(pixel[0]==.75f,"scene coverage cannot falsely hide proxy");check(GL11.glIsEnabled(GL32.GL_SAMPLE_MASK)&&GL11.glIsEnabled(GL13.GL_SAMPLE_COVERAGE),"coverage enable state restored");check(GL30.glGetIntegeri(GL32.GL_SAMPLE_MASK_VALUE,0)==0,"coverage mask value retained");
  GL11.glDisable(GL32.GL_SAMPLE_MASK);GL32.glSampleMaski(0,-1);GL11.glDisable(GL13.GL_SAMPLE_COVERAGE);GL13.glSampleCoverage(1,false);field("rectangle",new float[]{-1,-1,1,1,.4f});
 }
 public static void main(String[] args)throws Exception{
  check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);
  long window=GLFW.glfwCreateWindow(64,64,"Rift conditional regression",0,0);check(window!=0,"window");
  try{
   GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
   int vao=GL30.glGenVertexArrays();GL30.glBindVertexArray(vao);
   int color=texture(GL30.GL_RGBA32F,32,32,GL11.GL_RGBA,null),depth=texture(GL30.GL_DEPTH_COMPONENT32F,32,32,GL11.GL_DEPTH_COMPONENT,null),copy=texture(GL30.GL_RGBA32F,32,32,GL11.GL_RGBA,null),fbo=GL30.glGenFramebuffers();
   GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,color,0);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_DEPTH_ATTACHMENT,GL11.GL_TEXTURE_2D,depth,0);GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);GL11.glViewport(0,0,32,32);
   field("visible",true);field("mode",RiftCulling.FRUSTUM_DEPTH);field("rectangle",new float[]{-1,-1,1,1,.4f});
   for(boolean hidden:new boolean[]{true,false,true,false}){
    GL11.glDisable(GL11.GL_SCISSOR_TEST);GL11.glDepthMask(true);GL30.glClearBufferfv(GL11.GL_DEPTH,0,new float[]{hidden?.2f:1});GL30.glClearBufferfv(GL11.GL_COLOR,0,new float[]{.25f,0,0,1});
    try(var scope=RiftVisibility.depth(true)){GL30.glClearBufferfv(GL11.GL_COLOR,0,new float[]{.75f,0,0,1});RiftTargetTransfer.copy(color,copy,32,32,false);RiftTargetTransfer.mipmaps(color);}
    float[] pixel=new float[4];GL11.glReadPixels(16,16,1,1,GL11.GL_RGBA,GL11.GL_FLOAT,pixel);check(pixel[0]==(hidden?.25f:.75f),"same-frame conditional clear; no stale visibility: hidden="+hidden+" actual="+pixel[0]);
    GL11.glBindTexture(GL11.GL_TEXTURE_2D,RiftVisibility.texture());float[] flag=new float[1];GL11.glGetTexImage(GL11.GL_TEXTURE_2D,0,GL11.GL_RED,GL11.GL_FLOAT,flag);check(flag[0]==(hidden?0:1),"consumer visibility flag");
   }
   int clipped=program(NativeSkySources.clipTerminal("#version 150\nuniform sampler2D CmiJuliaHit;out vec4 color;void main(){color=vec4(.5,.6,.7,1);}"));
   for(int coordinate:new int[]{0,15,31}){
    float[] hits=new float[1024];Arrays.fill(hits,-1);hits[coordinate*32+coordinate]=10;int hit=texture(GL30.GL_R32F,32,32,GL11.GL_RED,hits);
    GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glBindTexture(GL11.GL_TEXTURE_2D,hit);GL20.glUseProgram(clipped);GL20.glUniform1i(GL20.glGetUniformLocation(clipped,"CmiJuliaHit"),0);GL20.glUniform1i(GL20.glGetUniformLocation(clipped,"CmiTargetClip"),1);GL11.glDisable(GL11.GL_DEPTH_TEST);GL30.glBindVertexArray(vao);GL30.glClearBufferfv(GL11.GL_COLOR,0,new float[]{0,0,0,0});GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);
    float[] image=new float[4096];GL11.glReadPixels(0,0,32,32,GL11.GL_RGBA,GL11.GL_FLOAT,image);
    for(int y=Math.max(0,coordinate-1);y<=Math.min(31,coordinate+1);y++)for(int x=Math.max(0,coordinate-1);x<=Math.min(31,coordinate+1);x++)check(image[(y*32+x)*4]==.5f,"filter halo missing");
    check(image[(31*32+31)*4]==.5f,"global history pixel missing");GL11.glDeleteTextures(hit);
   }
   conditionalWork(vao,fbo,color,depth);
   int packed=texture(GL30.GL_R11F_G11F_B10F,32,32,GL11.GL_RGBA,null),depth24=texture(GL14.GL_DEPTH_COMPONENT24,32,32,GL11.GL_DEPTH_COMPONENT,null);
   GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,packed,0);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_DEPTH_ATTACHMENT,GL11.GL_TEXTURE_2D,depth24,0);
   conditionalWork(vao,fbo,packed,depth24);fallbacks(packed,depth24);mrtIsolation(depth24);boundedProxy();clippedMasks(vao,fbo);
   check(GL11.glGetError()==0,"GL errors");RiftVisibility.close();RiftTargetTransfer.close();
   Path out=Path.of(System.getProperty("cmi.benchmark.output"));Files.createDirectories(out);Files.writeString(out.resolve("rift-visibility.txt"),"checks="+checks+"\nSynthetic correctness only; no client FPS claim.\n");System.out.println("Rift visibility GPU: "+checks+" checks passed");
  }finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
 }
}
