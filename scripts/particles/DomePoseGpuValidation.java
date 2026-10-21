import java.nio.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers;
import com.iridium126.createmanaindustry.client.gpu.state.GpuTextureBufferState;

/** Committed pose publication and foreign Iris unit 15 bindings, on the real driver. */
public final class DomePoseGpuValidation extends DomeGpuValidation {
    static void writePose(int state,int direction,int flags){
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,state);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,StarfallGpuBuffers.DOME_POSE*4L,bytes(8).putInt(0,direction).putInt(4,flags));
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
    }
    static void publication(boolean grow)throws Exception{
        int probe=compileText("committed Dome pose",source("chunks/allay_storm.glsl")+source("chunks/dome_pose.glsl")+"layout(local_size_x=1)in;layout(std430,binding=10)buffer R{vec4 value;}r;void main(){vec3 direction;float carry;bool valid=cmiDomeCommittedPose(1.,direction,carry);r.value=valid?vec4(direction,carry):vec4(-9.);}");
        int output=buffer(bytes(16));try(var gpu=new StarfallGpuBuffers()){
            if(grow){gpu.rebuild(new com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormWavePacket[]{starBenchmarkPacket(64),null,null,null});gpu.prepare(1);gpu.commit();}
            gpu.rebuild(new com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormWavePacket[]{domeAt(71,0,1,90),null,null,null});
            warmDome(gpu,probe);gpu.bind(probe,true);int state=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28);
            check(StarfallGpuBuffers.DOME_POSE_BYTES==2L*1024*1024,"Pose banks exceeded two MiB");
            // Packed yaw/pitch +Z and +X; the arm blend occupies only the low 16 bits.
            writePose(state,0x80008000,0x80008000);gpu.commit();gpu.bind(probe,false);bind(10,output);dispatch(probe,1);var first=read(output,16);
            check(Math.abs(first.getFloat(8)-1)<.0001&&Math.abs(first.getFloat(12)-.5)<.0001,"Committed pose TBO did not read +Z");
            gpu.prepare(100001);gpu.bind(probe,true);int next=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28);writePose(next,0x8000bfff,0x8000ffff);
            gpu.bindPose(probe);dispatch(probe,1);check(read(output,16).equals(first),"Uncommitted pose leaked to draw");
            gpu.abort();gpu.bind(probe,false);dispatch(probe,1);check(read(output,16).equals(first),"Aborted pose became visible");
            gpu.prepare(100002);gpu.bind(probe,true);writePose(GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28),0x8000bfff,0x8000ffff);gpu.commit();gpu.bind(probe,false);dispatch(probe,1);var second=read(output,16);
            check(Math.abs(second.getFloat(0)-1)<.0001&&second.getFloat(12)==1,"Commit did not publish next pose bank");
            gpu.rebuild(new com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormWavePacket[4]);gpu.prepare(100003);gpu.bind(probe,true);gpu.bindPose(probe);dispatch(probe,1);check(read(output,16).equals(second),"Last wave removal discarded the return pose");gpu.abort();
            gpu.invalidate();gpu.prepare(100004);gpu.commit();gpu.bind(probe,false);dispatch(probe,1);check(read(output,16).getFloat(0)==-9,"A new storm identity inherited an old pose");
        }finally{GL15.glDeleteBuffers(output);GL20.glDeleteProgram(probe);}
    }
    static void foreignBindings(){
        int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE),texture=GL11.glGenTextures(),buffer=buffer(bytes(16)),foreign=GL11.glGenTextures();
        try{
            GL13.glActiveTexture(GL13.GL_TEXTURE15);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,texture);GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,GL30.GL_R32UI,buffer);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,foreign);GL13.glActiveTexture(GL13.GL_TEXTURE3);
            var boundary=new GpuTextureBufferState(10,6);boundary.capture();GL13.glActiveTexture(GL13.GL_TEXTURE15);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,0);boundary.restore();
            check(GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)==GL13.GL_TEXTURE3,"Iris active unit was lost");GL13.glActiveTexture(GL13.GL_TEXTURE15);
            check(GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER)==texture&&GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)==foreign,"Iris unit 15 targets were polluted");
            boundary.capture();com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames.deleteTexture(texture);texture=0;boundary.restore();
            check(GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER)==0,"Retired pose texture was resurrected");
        }finally{if(texture!=0)GL11.glDeleteTextures(texture);GL11.glDeleteTextures(foreign);GL15.glDeleteBuffers(buffer);GL13.glActiveTexture(active);}
    }
    static void transitionClock(){
        try(var gpu=new StarfallGpuBuffers()){
            gpu.clock(10);gpu.rebuild(new com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormWavePacket[]{domeAt(91,0,1,90),null,null,null});
            gpu.clock(12.5);var locked=domeAt(91,2,4,12.25f);gpu.rebuild(new com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormWavePacket[]{locked,null,null,null});check(gpu.domeLockSec(0)==12.5f,"Late LOCK jumped ahead of the moving formation");
            gpu.clock(13);gpu.rebuild(new com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormWavePacket[]{locked,null,null,null});check(gpu.domeLockSec(0)==12.5f,"Repeated snapshot restarted Dome");
            gpu.rebuild(new com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormWavePacket[4]);gpu.rebuild(new com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormWavePacket[]{locked,null,null,null});check(gpu.domeLockSec(0)==12.25f,"A reused slot inherited a previous local clock");
            gpu.invalidate();gpu.rebuild(new com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormWavePacket[]{locked,null,null,null});check(gpu.domeLockSec(0)==12.25f,"Observer snapshot lost its synchronized attack clock");
        }
    }
    static void orientation(){
        int probe=compileText("Dome bounded heading",source("chunks/dome_pose_math.glsl")+"layout(local_size_x=1)in;layout(std430,binding=10)buffer R{vec4 value[6];}r;void main(){float steps[6]=float[6](.05,.033333333,.016666667,.006944444,.004166667,.037);for(int k=0;k<6;k++){float dt=steps[k],worst=0.;vec2 direction=vec2(0);for(int i=0;i<500;i++){vec3 target=i<100?vec3(6,0,0):i<200?vec3(0,6,0):i<300?vec3(0):i<400?vec3(-6,0,0):vec3(.001,-6,.001);vec2 next=cmiDomeUnpackPose(cmiDomePackPose(cmiDomeAdvancePose(direction,target,dt)));float yaw=direction.x,ny=next.x,angle=abs(mod(ny-yaw+3.141592654,6.28318530718)-3.141592654);worst=max(worst,length(vec2(angle,next.y-direction.y))/max(dt,.00001));if(i>=100&&i<300&&angle>.001)worst=1000.;direction=next;}r.value[k]=vec4(cmiDomePoseDirection(direction),worst);}}");
        int out=buffer(bytes(96));try{bind(10,out);dispatch(probe,1);var values=read(out,96);for(int i=0;i<6;i++){check(values.getFloat(i*16+12)<=3.1418,"Dome pose yaw exceeded 180 degrees per second or reset near vertical/idle");double norm=0;for(int j=0;j<3;j++)norm+=Math.pow(values.getFloat(i*16+j*4),2);check(Math.abs(norm-1)<.0001,"Pose compression lost a unit direction");}}finally{GL15.glDeleteBuffers(out);GL20.glDeleteProgram(probe);}
    }
    public static void main(String[] args)throws Exception{
        GLFWErrorCallback.createPrint(System.err).set();check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(64,64,"Dome pose publication",0,0);
        try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();publication(false);publication(true);foreignBindings();transitionClock();orientation();check(GL11.glGetError()==0,"Dome pose GL error");System.out.println("Dome pose GPU: "+checks+" assertions passed");}finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
