import java.nio.*;
import com.iridium126.createmanaindustry.client.particles.engine.ParticleBuffers;
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
        }finally{GL15.glDeleteBuffers(output);deleteProgram(probe);}
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
        int out=buffer(bytes(96));try{bind(10,out);dispatch(probe,1);var values=read(out,96);for(int i=0;i<6;i++){check(values.getFloat(i*16+12)<=3.1418,"Dome pose yaw exceeded 180 degrees per second or reset near vertical/idle");double norm=0;for(int j=0;j<3;j++)norm+=Math.pow(values.getFloat(i*16+j*4),2);check(Math.abs(norm-1)<.0001,"Pose compression lost a unit direction");}}finally{GL15.glDeleteBuffers(out);deleteProgram(probe);}
    }
    // Execute the production publication block with controlled navigation and
    // velocity inputs. Path solving/physics remain covered by their full kernels.
    static int returnPoseProgram(){
        String update=source("update.comp");
        int begin=update.indexOf("    // Publish once per simulation frame.");
        int end=update.indexOf("    uint slot = cmiReserveParticle();",begin);
        check(begin>=0&&end>begin,"Production pose publication block not found");
        return compileText("local-navigation return pose",
                "layout(std430,binding=27)readonly buffer O{uint words[];}starStateRead;layout(std430,binding=28)buffer S{uint words[];}starStateWrite;"+
                "uniform float uDt,uAge;uniform bool uDomePoseStorageEnabled,uCorpse,cmiNavigationApplied;uniform uint uPhase,uId;uniform vec3 uVelocity,uPreviousVelocity;"+
                source("chunks/allay_storm.glsl")+source("chunks/dome_pose_math.glsl")+source("chunks/dome_path_state.glsl")+source("chunks/allay_animation_state.glsl")+
                "layout(local_size_x=1)in;void main(){uint idx=0u,animationSlot=0u;bool corpse=uCorpse;uint cmiNavigationCurrentMember=0u;vec4 stormA=vec4(2,16,0,6),p0=vec4(0,0,0,1),p1=vec4(uPreviousVelocity,0),p3=vec4(uAge-uDt,20,.123,0);vec3 vel=uVelocity,cmiDomePoseTangent=vel;float age=uAge,roll=0.;CMI_STATE(starStateWrite,0u)=uId;CMI_STATE(starStateWrite,1u)=uPhase;CMI_STATE(starStateWrite,cmiNavigationExtra(0u))=2u;"+
                update.substring(begin,end)+"}");
    }
    static double angleDelta(double from,double to){return Math.atan2(Math.sin(to-from),Math.cos(to-from));}
    static double[] poseAngles(int encoded){return new double[]{(encoded&65535)*(Math.PI*2/65535)-Math.PI,(encoded>>>16)*(Math.PI/65535)-Math.PI/2};}
    static ByteBuffer poseStep(StarfallGpuBuffers gpu,int program,int empty,int frame,float time,float dt,int phase,int id,boolean navigating,boolean corpse,float[] previous,float[] velocity){
        gpu.prepare(frame);gpu.bind(program,true);bind(26,empty);bool(program,"uStarfallEnabled",true);
        f(program,"uDt",dt);f(program,"uAge",time);f(program,"uTimeSec",time);ui(program,"uPhase",phase);ui(program,"uId",id);
        bool(program,"cmiNavigationApplied",navigating);bool(program,"uCorpse",corpse);
        GL41.glProgramUniform3f(program,GL20.glGetUniformLocation(program,"uPreviousVelocity"),previous[0],previous[1],previous[2]);
        GL41.glProgramUniform3f(program,GL20.glGetUniformLocation(program,"uVelocity"),velocity[0],velocity[1],velocity[2]);
        dispatch(program,1);var pose=DomePathGpuValidation.range(gpu.candidateStateBuffer(),StarfallGpuBuffers.DOME_POSE,2);gpu.commit();return pose;
    }
    static void localReturnPose(){
        int program=returnPoseProgram(),empty=buffer(bytes(StarfallGpuBuffers.DATA_WORDS*4));
        try{
            for(float step:new float[]{.1f,.05f,1f/60,1f/144,1f/240,-1})try(var gpu=new StarfallGpuBuffers()){
                gpu.prepare(1);gpu.commit();int frame=2;float time=12,dt=step>0?step:.037f;
                float[] previous={0,0,6};
                for(int i=0;i<8;i++){
                    var pose=poseStep(gpu,program,empty,frame++,time+=dt,dt,6,77,false,false,previous,previous);
                    check(pose.getInt(4)>=0,"Ordinary return enabled a navigation pose");
                }
                double[] last={0,0};double lastSpeed=6;
                for(int i=0;i<180;i++){
                    dt=step>0?step:new float[]{.007f,.037f,.012f,.05f}[i%4];
                    float[] velocity=i<30?new float[]{6,0,0}:i<60?new float[]{.001f,6,.001f}:i<85?new float[]{0,0,0}:i<115?new float[]{-.06f,0,-6}:i<145?new float[]{.06f,0,-6}:new float[]{-6,0,0};
                    // Short pauses represent search/replan gaps; they cannot reset the display.
                    boolean navigating=i%30<27||i==179;
                    var pose=poseStep(gpu,program,empty,frame++,time+=dt,dt,6,77,navigating,false,previous,velocity);
                    check(pose.getInt(4)<0,"Navigation or short search pause lost its pose");
                    double[] angles=poseAngles(pose.getInt(0));
                    double delta=Math.hypot(angleDelta(last[0],angles[0]),angles[1]-last[1]);
                    check(delta<=Math.PI*dt+.0002,"Return pose exceeded 180 degrees/second");
                    if(i>=30&&i<85&&navigating)check(Math.abs(angleDelta(last[0],angles[0]))<.0002,"Vertical/idle navigation reset yaw");
                    double speed=((pose.getInt(4)>>>16)&32767)*(64./32767.);
                    if(navigating){double target=Math.sqrt(velocity[0]*velocity[0]+velocity[1]*velocity[1]+velocity[2]*velocity[2]);check(Math.abs(speed-(lastSpeed+(target-lastSpeed)*(1-Math.exp(-dt/.12))))<.002,"Pose speed reset instead of smoothing");}
                    last=angles;lastSpeed=speed;previous=velocity;
                }
                float elapsed=0;boolean released=false;
                float[] straight={0,0,6};
                for(int i=0;i<1500;i++){
                    var pose=poseStep(gpu,program,empty,frame++,time+=dt,dt,step==.1f?6:7,77,false,false,previous,straight);previous=straight;
                    if(pose.getInt(4)>=0){
                        check(elapsed>=.5f-.00001f,"Navigation pose faded in less than .5 seconds: step="+step+" elapsed="+elapsed);
                        double[] angles=poseAngles(pose.getInt(0));check(Math.abs(angles[0])+Math.abs(angles[1])<.0012,"Pose release snapped onto ordinary flight");released=true;break;
                    }
                    elapsed+=dt;
                }
                check(released,"Navigation pose never released");
                var ordinary=poseStep(gpu,program,empty,frame++,time+=dt,dt,6,77,false,false,straight,previous);
                check(ordinary.getInt(4)>=0,"Moving away from home restarted a pose without navigation");
                var active=poseStep(gpu,program,empty,frame++,time+=dt,dt,6,77,true,false,previous,new float[]{6,0,0});check(active.getInt(4)<0,"Navigation restart did not initialize pose");
                var claimed=poseStep(gpu,program,empty,frame++,time+=dt,dt,1,88,false,false,previous,previous);check(claimed.getInt(4)>=0,"New attack inherited return pose");
                active=poseStep(gpu,program,empty,frame++,time+=dt,dt,6,88,true,false,previous,previous);check(active.getInt(4)<0,"Second return did not initialize pose");
                var corpse=poseStep(gpu,program,empty,frame++,time+=dt,dt,6,88,false,true,previous,previous);check(corpse.getInt(4)>=0,"Corpse retained return pose");
            }
        }finally{GL15.glDeleteBuffers(empty);deleteProgram(program);}
    }
    static void renderedReturnHeading(){
        int program=compileText("Helix return heading",source("chunks/allay_pose.glsl")+
                "layout(local_size_x=1)in;layout(std430,binding=10)buffer R{float yaw;}r;void main(){float yaw;cmiAllayAttackPartTransform(12.,.123,1.,vec3(0),vec3(0),0,0,yaw,1.,-4.,true);r.yaw=yaw;}");
        int output=buffer(bytes(4));
        try(var gpu=new StarfallGpuBuffers()){
            gpu.rebuild(new com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormWavePacket[]{HelixGpuValidation.helix(4096,77,2,4,false),null,null,null});
            gpu.prepare(1);gpu.bind(program,true);int state=gpu.candidateStateBuffer();writePose(state,0x80008000,0x8000ffff);gpu.commit();
            gpu.bind(program,false);f(program,"uTimeSec",12);bind(10,output);dispatch(program,1);
            check(Math.abs(read(output,4).getFloat(0))<.0001,"Live Helix override replaced the committed return heading");
            gpu.prepare(2);gpu.bind(program,true);writePose(gpu.candidateStateBuffer(),0,0);gpu.commit();gpu.bind(program,false);dispatch(program,1);
            check(Math.abs(read(output,4).getFloat(0)+Math.PI/2)<.0001,"Cleared return pose changed normal Helix heading");
        }finally{GL15.glDeleteBuffers(output);deleteProgram(program);}
    }
    // Focused release/ownership fixtures complement the full production lifecycle.
    // Two runs differ ONLY in their render pose flags; their physical rows must match.
    static ByteBuffer releasePhysics(int reason,boolean pose)throws Exception{
        int n=64,update=compile("update.comp");com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clearUniformProgram(update);
        try(var q=new Fixture(n,n,n);var gpu=new StarfallGpuBuffers()){
            var data=bytes(n*64);for(int i=0;i<n;i++){particle(data,i,2,24+i*.01f,0,20);data.putFloat(i*64+4,40).putFloat(i*64+12,i+1).putFloat(i*64+16,6).putFloat(i*64+56,.123f+i*.001f);}
            q.build(data,n);
            if(reason==4){int initialize=compileText("released at ordinary home",source("chunks/allay_storm.glsl")+"layout(local_size_x=64)in;layout(std430,binding=10)buffer P{vec4 rows[];}p;void main(){uint i=gl_GlobalInvocationID.x;vec3 point,motion;cmiTyphoonHome(p.rows[i*4u+3u].z,16.,0.,0.,0.,0.,10.,vec3(0,40,0),point,motion);p.rows[i*4u].xyz=point;}");bind(10,q.pool);dispatch(initialize,1);data=read(q.pool,n*64);deleteProgram(initialize);}
            put(q.previous,bytes(16).putInt(0,n));var h=headers();h.putFloat(2*320+18*16+4,16).putFloat(2*320+18*16+12,10).putFloat(2*320+19*16+4,40);put(q.emit,h);
            var wave=domeAt(27,2,4,10);
            var overlap=reason==5?new com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormWavePacket(26,37,0,1,1,5,1,12288,0,90,90,150.25f,0,40,0,32,java.util.stream.IntStream.range(32,12320).toArray(),new float[]{0,40,0,0,0,0},new float[4],1,1,new int[]{12288},0,0,0,0,1,0,0):null;
            gpu.rebuild(new com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormWavePacket[]{wave,overlap,null,null});warmDome(gpu,update);gpu.commit();gpu.bind(0,false);
            int state=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28);var phases=bytes(n*8);for(int i=0;i<n;i++)phases.putInt(i*8,reason==5&&i>=32?26:27).putInt(i*8+4,(reason==0||reason==5&&i>=32?1:reason==2?4:6)|(reason==6?0:0x100|DomeExitGpuValidation.INSIDE));putRange(state,0,phases);
            var poses=bytes(n*8);for(int i=0;i<n;i++)poses.putInt(i*8,0x80008000).putInt(i*8+4,pose?0x8c000000:0);putRange(state,StarfallGpuBuffers.DOME_POSE*4L,poses);
            if(reason==2)gpu.rebuild(new com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormWavePacket[]{domeAt(27,3,6,10),null,null,null});
            else if(reason!=3&&reason!=5)gpu.rebuild(new com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormWavePacket[4]);
            int output=q.alloc(bytes(n*64)),grid=q.alloc(bytes((ParticleBuffers.GRID_TABLE+n)*4)),damage=q.alloc(bytes(16)),players=q.alloc(bytes(16)),correction=q.alloc(bytes(n*32)),map=q.alloc(bytes(n*4)),empty=q.alloc(bytes(StarfallGpuBuffers.DATA_WORDS*4));
            ui(update,"uCapacity",n);ui(update,"uKillEmit",-1);f(update,"uDt",.025f);
            for(int frame=0;frame<40;frame++){
                put(q.pool,data);put(q.counter,bytes(16));bind(0,q.pool);bind(1,output);bind(3,q.counter);bind(5,q.emit);bind(13,grid);bind(14,q.previous);bind(16,damage);bind(18,players);bind(19,correction);bind(21,map);
                gpu.prepare(frame+100000);gpu.bind(update,true);if(reason!=3&&reason!=5){bind(26,empty);bool(update,"uStarfallEnabled",true);}f(update,"uTimeSec",12+frame*.025f);int error=GL11.glGetError();check(error==0,"Release setup GL error "+error+" reason="+reason+" frame="+frame);dispatch(update,1);data=read(output,n*64);
                state=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28);phases=read(state,n*8);
                var age=bytes(n*4);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,state);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,StarfallGpuBuffers.GATHER_BLOCKED*4L,age);
                for(int i=0;i<n;i++){int member=Math.round(data.getFloat(i*64+12))-1;if(reason==5&&member>=32)continue;check((phases.getInt(member*8+4)&255)>=6,"Released member followed live slot clock");check(data.getFloat(i*64+28)==0,"Released member still holds sword");if(reason<6&&frame==10)check(Math.abs(age.getFloat(member*4)-.275f)<.0001,"Live attack watchdog overwrote return clock");if(reason<6&&frame>=20)check((phases.getInt(member*8+4)&0x200)!=0,"Velocity handoff exceeded .5 seconds");}
                gpu.commit();
            }
            // Immediate slot and member reuse, followed by an old completion.
            for(var next:new com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormWavePacket[]{domeAt(28,0,1,90),HelixGpuValidation.helix(4096,29,0,1,false),StarfallColumnGpuValidation.column(0).snapshot()}){
                var snapshot=new com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormWavePacket[68];snapshot[next.column()?4:0]=next;
                gpu.rebuild(snapshot);gpu.completed(27,new long[]{-1L});gpu.rebuild(snapshot);
                put(q.pool,data);put(q.counter,bytes(16));gpu.prepare(200000+next.waveId());gpu.bind(update,true);f(update,"uTimeSec",2);dispatch(update,1);
                var claimed=read(GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28),n*8);
                for(int i=0;i<n;i++){check(claimed.getInt(i*8)==next.waveId(),"Old completion cleared new assignment");int flags=claimed.getInt(i*8+4);check((flags&255)==1&&(flags&(0x200|DomeExitGpuValidation.INSIDE))==0,"New attack inherited Dome handoff/phase");}gpu.commit();
            }
            return data;
        }finally{com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clearUniformProgram(update);deleteProgram(update);}
    }
    static void putRange(int buffer,long offset,ByteBuffer data){GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,offset,data);}
    static void returnPhysics()throws Exception{
        for(int reason=0;reason<7;reason++){var a=releasePhysics(reason,false);var b=releasePhysics(reason,true);int[] slots=new int[64];for(int i=0;i<64;i++)slots[Math.round(b.getFloat(i*64+12))-1]=i;for(int i=0;i<64;i++){int member=Math.round(a.getFloat(i*64+12))-1;for(int k=0;k<7;k++)check(Math.abs(a.getFloat(i*64+k*4)-b.getFloat(slots[member]*64+k*4))<.00001,"Pose cache changed physical return");}}
    }
    public static void main(String[] args)throws Exception{
        GLFWErrorCallback.createPrint(System.err).set();check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(64,64,"Dome pose publication",0,0);
        try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();publication(false);publication(true);foreignBindings();transitionClock();orientation();localReturnPose();renderedReturnHeading();returnPhysics();check(GL11.glGetError()==0,"Dome pose GL error");System.out.println("Dome pose GPU: "+checks+" assertions passed");}finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
