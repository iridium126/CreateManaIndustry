import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.particles.engine.*;
import com.iridium126.createmanaindustry.client.allaystorm.*;
import com.iridium126.createmanaindustry.content.allaystorm.*;
import com.iridium126.createmanaindustry.content.allaystorm.network.*;
/** Protocol 11 thick-shell Dome, through actual production shaders. */
public class DomeGpuValidation extends StarfallGpuValidation {
 static ClientboundStormWavePacket dome(int count,int id,int event,int phase){
  int[] members=java.util.stream.IntStream.range(0,8192).toArray();return new ClientboundStormWavePacket(id,37,event,1,1,5,1,8192,0,10,10,70,0,40,0,32,members,new float[]{0,40,0,0,0,0},new float[4],1,phase,new int[]{8192},0,0,0,0,1,0,0);
 }
 static ClientboundStormWavePacket starBenchmarkPacket(int count){int per=1024,cols=(count+per-1)/per;int[] roster=java.util.stream.IntStream.range(0,count).toArray();float[] land=new float[cols*4];for(int i=0;i<cols;i++){land[i*4+1]=.5f;land[i*4+3]=3.5f;}return new ClientboundStormWavePacket(1,37,2,1,1,5,cols,per,0,4,10,120,0,40,0,8,roster,new float[]{0,40,0,0,10,0},land);}
 static void assertFinite(ByteBuffer b,int o,int words){for(int j=0;j<words;j++)check(Float.isFinite(b.getFloat(o+j*4)),"nonfinite Dome output");}
 static int motionProgram(){String nav=source("chunks/storm_navigation.glsl");nav=nav.substring(nav.indexOf("vec3 cmiStarRoute"));return compileText("Dome shared lanes",source("chunks/allay_storm.glsl")+nav+"layout(local_size_x=64)in;layout(std430,binding=10)buffer Output{vec4 rows[];} result;void main(){uint i=gl_GlobalInvocationID.x;if(i>=8192u)return;uint a=cmiStarAssignment(float(i+1u));float phase=cmiStarPhase(a,uTimeSec);vec3 point=cmiDomePoint(a,0.,uTimeSec,vec3(0)),velocity=cmiDomeVelocity(a,uTimeSec);result.rows[i*3u]=vec4(point,phase);result.rows[i*3u+1u]=vec4(velocity,cmiAttackCarry(float(i+1u),uTimeSec,-4.));result.rows[i*3u+2u]=vec4(cmiAttackSpeed(float(i+1u),uTimeSec,6.),0,0,0);}");}
 static void geometry()throws Exception{
  check(DomeTemplate.available(),"Dome template missing");int p=motionProgram(),out=buffer(bytes(8192*48));try(var gpu=new StarfallGpuBuffers()){
   bind(10,out);for(boolean locked:new boolean[]{false,true}){
    stage(gpu,dome(8192,7,locked?2:0,locked?4:1),p);
    for(float t:locked?new float[]{10,10.1f,15,25,40,55,69.99f}:new float[]{0,1,9,15}){
     f(p,"uTimeSec",t);dispatch(p,128);var rows=read(out,8192*48);int done=0;
     for(int i=0;i<8192;i++){int o=i*48;assertFinite(rows,o,12);var expected=DomeTemplate.point(i,locked?Math.max(0,t-10)*6:0);
      check(Math.abs(rows.getFloat(o)-expected.x)<.004&&Math.abs(rows.getFloat(o+4)-(expected.y-.3))<.004&&Math.abs(rows.getFloat(o+8)-expected.z)<.004,"CPU/GPU lane mismatch");
      int phase=!locked?1:t-10>=DomeTemplate.duration(i)?6:4;check(rows.getFloat(o+12)==phase,"Dome per-member phase");if(phase==6)done++;
      check(rows.getFloat(o+32)==(locked?6:12),"Dome gather/attack speed cap");check(rows.getFloat(o+28)==(phase==4?1:0),"Dome carrier escaped flight phase");
      if(!locked){double radius=expected.length();check(radius>=16&&radius<=32&&expected.y>=0,"not an upper thick shell");}
      if(phase==4&&t-10+.01<DomeTemplate.duration(i)){double vx=rows.getFloat(o+16),vy=rows.getFloat(o+20),vz=rows.getFloat(o+24),speed=Math.sqrt(vx*vx+vy*vy+vz*vz);check(Math.abs(speed-6)<.025,"Dome reference arc speed "+speed);}
     }
     if(locked&&t==40)check(done>0&&done<8192,"Dome did not finish members independently");if(locked&&t>69.98)check(done==8192,"Dome held members past 60 seconds");if(!locked&&t==1){renderDome(rows,8192);bind(10,out);}
    }
   }
  }finally{GL15.glDeleteBuffers(out);GL20.glDeleteProgram(p);}
 }
 static void actualLifecycle()throws Exception{
  int n=8192,update=compile("update.comp"),contact=compile("wavecontact.comp");try(var q=new Fixture(n,n,n);var gpu=new StarfallGpuBuffers()){
   var data=bytes(n*64);for(int i=0;i<n;i++){var p=DomeTemplate.point(i,0);particle(data,i,2,(float)p.x,(float)p.z,20);data.putFloat(i*64+4,(float)p.y-.3f).putFloat(i*64+12,i+1).putFloat(i*64+56,AllayStormWaves.memberSeed(1,i));}q.build(data,n);var h=headers();h.putFloat(2*320+18*16+12,6);put(q.emit,h);put(q.previous,bytes(16).putInt(0,n));
   int output=buffer(bytes(n*64)),grid=buffer(bytes((ParticleBuffers.GRID_TABLE+n)*4)),damage=buffer(bytes(16)),players=buffer(bytes(16)),correction=buffer(bytes(n*32)),map=buffer(bytes(n*4)),reports=buffer(bytes(4+16384*24));
   ui(update,"uCapacity",n);ui(update,"uKillEmit",-1);f(update,"uDt",1/60f);gpu.rebuild(new ClientboundStormWavePacket[]{dome(n,27,0,1),null,null,null});
   for(int frame=0;frame<4;frame++){
    put(q.pool,data);put(q.counter,bytes(16));bind(0,q.pool);bind(1,output);bind(3,q.counter);bind(5,q.emit);bind(13,grid);bind(14,q.previous);bind(16,damage);bind(18,players);bind(19,correction);bind(21,map);gpu.prepare(frame+1);gpu.bind(update,true);f(update,"uTimeSec",1+frame/60f);dispatch(update,128);
    int state=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28);var ready=bytes(16);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,state);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,StarfallGpuBuffers.GATHER_COUNTS*4L,ready);check(ready.getInt(0)==(frame>=2?n:0),"three-frame actual gather readiness");gpu.commit();data=read(output,n*64);
   }
   gpu.rebuild(new ClientboundStormWavePacket[]{dome(n,27,2,4),null,null,null});BitSet done=new BitSet();
   for(int frame=0;frame<1201;frame++){
    float t=10+frame*.05f;put(q.pool,data);put(q.counter,bytes(16));bind(0,q.pool);bind(1,output);bind(3,q.counter);bind(5,q.emit);bind(13,grid);bind(14,q.previous);bind(16,damage);bind(18,players);bind(19,correction);bind(21,map);gpu.prepare(frame+10);gpu.bind(update,true);f(update,"uTimeSec",t);f(update,"uDt",.05f);dispatch(update,128);data=read(output,n*64);
    int state=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28);var phases=read(state,n*8);for(int i=0;i<n;i++){int o=i*64;assertFinite(data,o,12);int member=Math.round(data.getFloat(o+12))-1;int phase=phases.getInt(member*8+4)&255;if(phase==6)done.set(member);else if(t>10.1){double speed=0;for(int j=4;j<7;j++){double v=data.getFloat(o+j*4);speed+=v*v;}check(speed<=36.001&&speed>=35.99,"Dome actual motion is not 6m/s: time="+t+" member="+member+" speed="+Math.sqrt(speed));}check(data.getFloat(o+28)==0,"Dome holds sword without nearby players");}
    gpu.commit();if(done.cardinality()==n)break;
   }
   check(done.cardinality()==n,"Dome did not release all 8192 through production kernel");System.out.println("Dome actual lifecycle completed="+done.cardinality());
   for(int b:new int[]{output,grid,damage,players,correction,map,reports})GL15.glDeleteBuffers(b);
  }finally{GL20.glDeleteProgram(update);GL20.glDeleteProgram(contact);}
 }
 static void renderDome(ByteBuffer rows,int count)throws Exception{
  // Framebuffer geometry evidence, not a substitute for the user's in-game model/art acceptance.
  int vs=GL20.glCreateShader(GL20.GL_VERTEX_SHADER),fs=GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
  GL20.glShaderSource(vs,"#version 450 core\nlayout(std430,binding=10)readonly buffer R{vec4 p[];};uniform mat4 vp;out vec3 color;void main(){vec4 v=p[uint(gl_VertexID)*3u];gl_Position=vp*vec4(v.xyz,1);gl_PointSize=2.;color=vec3(.25,.7,1);}");
  GL20.glShaderSource(fs,"#version 450 core\nin vec3 color;out vec4 outColor;void main(){outColor=vec4(color,1);}");
  GL20.glCompileShader(vs);GL20.glCompileShader(fs);int program=GL20.glCreateProgram();GL20.glAttachShader(program,vs);GL20.glAttachShader(program,fs);GL20.glLinkProgram(program);check(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(program));
  int vao=GL30.glGenVertexArrays(),fb=GL30.glGenFramebuffers(),tex=GL11.glGenTextures(),input=buffer(rows);
  GL30.glBindVertexArray(vao);GL11.glBindTexture(GL11.GL_TEXTURE_2D,tex);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,512,384,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(ByteBuffer)null);
  GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fb);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,tex,0);bind(10,input);
  var vp=new org.joml.Matrix4f().setPerspective((float)Math.toRadians(48),512f/384,.1f,160).lookAt(60,38,75,0,12,0,0,1,0);
  GL20.glUseProgram(program);GL41.glProgramUniformMatrix4fv(program,GL20.glGetUniformLocation(program,"vp"),false,vp.get(new float[16]));GL11.glViewport(0,0,512,384);GL11.glClearColor(.025f,.035f,.07f,1);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);GL11.glEnable(GL32.GL_PROGRAM_POINT_SIZE);GL11.glDrawArrays(GL11.GL_POINTS,0,count);
  var pixels=bytes(512*384*4);GL11.glReadPixels(0,0,512,384,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);var image=new java.awt.image.BufferedImage(512,384,java.awt.image.BufferedImage.TYPE_INT_RGB);int lit=0;
  for(int y=0;y<384;y++)for(int x=0;x<512;x++){int o=(y*512+x)*4,r=pixels.get(o)&255,g=pixels.get(o+1)&255,b=pixels.get(o+2)&255;if(b>100)lit++;image.setRGB(x,383-y,(r<<16)|(g<<8)|b);}check(lit>100,"dome framebuffer missing");javax.imageio.ImageIO.write(image,"png",Path.of(System.getProperty("cmi.benchmark.output", "build/benchmarks/current")+"/dome-geometry-framebuffer.png").toFile());
  GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,0);GL11.glDisable(GL32.GL_PROGRAM_POINT_SIZE);GL15.glDeleteBuffers(input);com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames.deleteTexture(tex);GL30.glDeleteFramebuffers(fb);com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames.deleteVertexArray(vao);GL20.glUseProgram(0);GL20.glDeleteProgram(program);GL20.glDeleteShader(vs);GL20.glDeleteShader(fs);
 }

 public static void main(String[] args)throws Exception{GLFWErrorCallback.createPrint(System.err).set();check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(512,384,"Dome v11 GPU",0,0);try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();System.out.println(GL11.glGetString(GL11.GL_RENDERER));geometry();actualLifecycle();check(GL11.glGetError()==0,"Dome GL error");System.out.println("Dome GPU: "+checks+" assertions passed");}finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}}
}
