import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.joml.Matrix4f;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.AllayModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.animal.allay.Allay;
import com.iridium126.createmanaindustry.client.particles.engine.AllayAnimationGpu;
import com.iridium126.createmanaindustry.client.allaystorm.StormPlayerGrid;

/** Production GLSL against real vanilla model parts, player snapshots, and transactional sidecars. */
public class AllayAnimationGpuValidation extends DomeGpuValidation {
    public static class OracleAllay extends Allay {
        boolean dance,spinning;float carry,spin;
        OracleAllay(){super(net.minecraft.world.entity.EntityType.ALLAY,null);}
        @Override public boolean isDancing(){return dance;}
        @Override public boolean isSpinning(){return spinning;}
        @Override public float getHoldingItemAnimationProgress(float partial){return carry;}
        @Override public float getSpinningProgress(float partial){return spin;}
    }
    static OracleAllay oracle()throws Exception{
        var field=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);
        return (OracleAllay)((sun.misc.Unsafe)field.get(null)).allocateInstance(OracleAllay.class);
    }
    static void vanillaMatrices()throws Exception{
        net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        var entity=oracle();var model=new AllayModel(AllayModel.createBodyLayer().bakeRoot());
        var root=model.root();var body=root.getChild("body");
        ModelPart[][] chains={{root,root.getChild("head")},{root,body},{root,body,body.getChild("right_arm")},{root,body,body.getChild("left_arm")},{root,body,body.getChild("right_wing")},{root,body,body.getChild("left_wing")},{root,body}};
        int p=compileText("actual vanilla AllayModel matrices",source("chunks/allay_pose.glsl")+
                "uniform float uAmount,uPhase,uPitch,uHead,uCarry,uDance,uSpin;layout(local_size_x=1)in;layout(std430,binding=10)buffer R{mat4 matrix[8];}r;void main(){cmiAllayAnimationValid=true;cmiAnimationAmount=uAmount;cmiAnimationPhase=uPhase;cmiAnimationPitch=uPitch;cmiAnimationHeadYaw=uHead;cmiAnimationCarry=uCarry;cmiAnimationDance=uDance;cmiAnimationSpinProgress=uSpin;for(int part=0;part<8;part++){float yaw;r.matrix[part]=cmiAllayPartTransform(12.0175,0.,vec3(0),uDance<0.?0:1,part,yaw,uCarry);}}");
        int out=buffer(bytes(8*64));bind(10,out);
        try{
            for(boolean dance:new boolean[]{false,true})for(float amount:new float[]{0,.1f,.3f,1})for(float carry:new float[]{0,.4f,1})for(float pitch:new float[]{-40,0,40}){
                entity.dance=dance;entity.spinning=dance;entity.spin=.4f;entity.carry=carry;entity.tickCount=240;
                model.setupAnim(entity,1.25f,amount,240.35f,32,pitch);
                f(p,"uAmount",amount);f(p,"uPhase",1.25f);f(p,"uPitch",pitch*(float)Math.PI/180);f(p,"uHead",32*(float)Math.PI/180);f(p,"uCarry",carry);f(p,"uDance",dance?6:-1);f(p,"uSpin",.4f);dispatch(p,1);
                var actual=read(out,8*64);
                for(int part=0;part<8;part++){
                    var stack=new PoseStack();Matrix4f expected;
                    if(part==7){
                        model.translateToHand(net.minecraft.world.entity.HumanoidArm.RIGHT,stack);
                        stack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-90));stack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180));stack.translate(1f/16,.125f,-.625f);
                        new net.minecraft.client.renderer.block.model.ItemTransform(new org.joml.Vector3f(0,-90,55),new org.joml.Vector3f(0,.25f,.03125f),new org.joml.Vector3f(.85f)).apply(false,stack);
                        stack.translate(-.5f,-.5f,-.5f);
                        expected=new Matrix4f().scaling(16).mul(stack.last().pose());
                    }else{
                        for(var joint:chains[part])joint.translateAndRotate(stack);
                        expected=new Matrix4f(stack.last().pose());expected.m30(expected.m30()*16).m31(expected.m31()*16).m32(expected.m32()*16);
                    }
                    float[] values=new float[16];expected.get(values);
                    for(int k=0;k<16;k++)check(Math.abs(values[k]-actual.getFloat(part*64+k*4))<.0012,"Vanilla matrix mismatch part="+part+" element="+k+" dance="+dance+" amount="+amount+" pitch="+pitch);
                }
            }
        }finally{GL15.glDeleteBuffers(out);deleteProgram(p);}
    }
    static void playerSnapshot(){
        int p=compileText("shared player eye/feet/centre semantics","layout(std430,binding=18)readonly buffer P{vec4 pl[];}players;uniform int uPlayerCount;"+source("chunks/storm_players.glsl")+
                "layout(local_size_x=1)in;layout(std430,binding=10)buffer R{vec4 result[4];}r;void main(){int i=cmiNearestPlayer(vec3(0));r.result[0]=vec4(float(i<0?0:cmiPlayerId(i)),cmiNearPlayerFeet(vec3(0))?1.:0.,0,0);r.result[1]=vec4(cmiPlayerCenter(0),0);r.result[2]=vec4(cmiPlayerEyes(0),0);r.result[3]=vec4(cmiPlayerRepel(vec3(0),3.,14.),0);}");
        int out=buffer(bytes(64));bind(10,out);
        try{
            for(int n:new int[]{1,16,17,100}){
                float[] feet=new float[n*3],eyes=new float[n*3],centres=new float[n*3];int[] ids=new int[n];
                for(int i=0;i<n;i++){feet[i*3]=i==0?6:100+i;eyes[i*3]=feet[i*3];centres[i*3]=feet[i*3];eyes[i*3+1]=3.24f;centres[i*3+1]=1.8f;ids[i]=73+i;}
                var grid=new StormPlayerGrid();grid.build(centres,eyes,feet,ids,n,null);var packed=bytes(grid.words()*4);for(int i=0;i<grid.words();i++)packed.putFloat(i*4,grid.data()[i]);int players=buffer(packed);bind(18,players);GL41.glProgramUniform1i(p,GL20.glGetUniformLocation(p,"uPlayerCount"),n);dispatch(p,1);var actual=read(out,64);
                check(actual.getFloat(0)==73&&actual.getFloat(4)==1,"Inclusive six-block feet boundary or player hash failed n="+n);
                check(Math.abs(actual.getFloat(20)-1.8)<.0001&&Math.abs(actual.getFloat(36)-3.24)<.0001,"Eye coordinates changed centre semantics");GL15.glDeleteBuffers(players);
                // A scaled player's centre can be several hash cells above its feet.
                feet[0]=eyes[0]=centres[0]=0;feet[1]=-18;eyes[1]=12;centres[1]=-1;
                grid.build(centres,eyes,feet,ids,n,null);for(int i=0;i<grid.words();i++)packed.putFloat(i*4,grid.data()[i]);players=buffer(packed);bind(18,players);dispatch(p,1);actual=read(out,64);
                check(actual.getFloat(0)==0&&actual.getFloat(4)==0,"Eye/centre height changed vanilla feet range");
                check(Math.abs(actual.getFloat(52)-14.*2/3)<.001,"Scaled centre escaped the foot-indexed repulsion query n="+n);GL15.glDeleteBuffers(players);
            }
        }finally{GL15.glDeleteBuffers(out);deleteProgram(p);}
    }
    static int animationKernel(){
        return compileText("production allay animation state",
                "layout(std430,binding=5)readonly buffer E{vec4 u[];}emitters;layout(std430,binding=18)readonly buffer P{vec4 pl[];}players;layout(std430,binding=28)buffer S{uint words[];}starStateWrite;uniform int uPlayerCount,uCollisionOn;uniform float uAge;uniform uint uCount,uInitialize,uReverse,uMode;uniform vec3 uVelocity,uPosition;bool cmiStormVoxel=false;bool cmiStormKnown(vec3 p){return false;}bool cmiStormSolid(vec3 p){return false;}bool solidAt(vec3 p,vec3 origin,int slice){return false;}"+
                source("chunks/allay_storm.glsl")+source("chunks/storm_players.glsl")+source("chunks/allay_animation_state.glsl")+source("chunks/allay_animation_update.glsl")+
                "layout(local_size_x=64)in;void main(){uint i=gl_GlobalInvocationID.x;if(i>=uCount)return;vec3 p=uPosition+vec3(float(i)*.001,0,0);if(uInitialize!=0u){cmiInitializeAllayAnimation(i,p,vec3(float(i)*.01,0,1),float(i)/float(uCount));return;}uint dst=uReverse!=0u?uCount-i-1u:i;vec4 p0=vec4(p,1),p1=vec4(uVelocity,0),p3=vec4(uAge,20,float(i)/float(uCount),0);cmiUpdateAllayAnimation(i,dst,p0,p1,p3,p,uVelocity,uAge,false,uMode==3u,int(uMode),uMode==2u?1.:0.,false,0.);}");
    }
    static void sidecar()throws Exception{
        int p=animationKernel(),header=buffer(headers()),empty=buffer(bytes(16)),n=64;bind(5,header);bind(18,empty);GL41.glProgramUniform1i(p,GL20.glGetUniformLocation(p,"uPlayerCount"),0);ui(p,"uCount",n);
        try(var animation=new AllayAnimationGpu()){
            animation.bindCompute(p,0);
            check(!animation.allocated()&&GL20.glGetUniformi(p,GL20.glGetUniformLocation(p,"cmi_AllayAnimationRead"))==3,"Disabled animation aliased the collision sampler or allocated storage");
            int draw=compileText("unallocated animation draw sampler",source("chunks/allay_animation_read.glsl")+"layout(local_size_x=1)in;layout(std430,binding=10)buffer R{float value;}r;void main(){cmiLoadAllayAnimation(0u,0.);r.value=cmiAnimationHeadYaw;}");
            try{animation.bindDraw(draw,0);check(GL20.glGetUniformi(draw,GL20.glGetUniformLocation(draw,"cmi_AllayAnimation"))==15,"Disabled draw animation aliased the atlas sampler");}finally{deleteProgram(draw);}
            animation.ensure(n);animation.bindCompute(p,0);ui(p,"uInitialize",1);dispatch(p,1);GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
            var original=read(animation.buffer(1),n*64);
            animation.bindCompute(p,1);ui(p,"uInitialize",0);ui(p,"uReverse",1);f(p,"uAge",0);dispatch(p,1);GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);var reversed=read(animation.buffer(0),n*64);
            for(int i=0;i<n;i++)for(int k=0;k<16;k++)check(original.getInt(i*64+k*4)==reversed.getInt((n-i-1)*64+k*4),"Pool compaction lost animation state");
            check(read(animation.buffer(1),n*64).equals(original),"Uncommitted animation mutated the read bank");
            ui(p,"uCount",1);ui(p,"uReverse",0);animation.clear();int bank=0;
            for(int tick=0;tick<=8;tick++){
                animation.bindCompute(p,bank);f(p,"uAge",tick*.05f);ui(p,"uMode",2);dispatch(p,1);bank=1-bank;
            }
            GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);var held=read(animation.buffer(bank),64);check((held.getInt(24)>>>16)==65535,"Held-item ramp did not reach one after five ticks");
            animation.bindCompute(p,bank);dispatch(p,1);bank=1-bank;check(animationRead(animation,bank).equals(held),"Paused animation advanced without a tick");
            for(int tick=9;tick<=15;tick++){animation.bindCompute(p,bank);f(p,"uAge",tick*.05f);ui(p,"uMode",0);dispatch(p,1);bank=1-bank;}
            GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);check((read(animation.buffer(bank),64).getInt(24)>>>16)==0,"Held-item release did not finish in five ticks");
            animation.bindCompute(p,bank);f(p,"uAge",.8f);ui(p,"uMode",3);dispatch(p,1);bank=1-bank;GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);var dead=read(animation.buffer(bank),64);check(dead.getInt(16)==0&&dead.getInt(20)==0,"Death retained walk phase");
            check(animation.bytes()==2L*AllayAnimationGpu.CAPACITY*64,"Unexpected sidecar allocation");
            com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
            int foreignBuffer=buffer(bytes(16)),foreignTexture=GL11.glGenTextures();
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,foreignTexture);GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,GL30.GL_R32UI,foreignBuffer);GL42.glBindImageTexture(0,foreignTexture,0,false,0,GL15.GL_WRITE_ONLY,GL30.GL_R32UI);
            var boundary=new com.iridium126.createmanaindustry.client.gpu.state.GpuComputeState();boundary.capture();animation.bindCompute(p,0);boundary.restore();
            check(GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_NAME,0)==foreignTexture&&GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_FORMAT,0)==GL30.GL_R32UI&&GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_ACCESS,0)==GL15.GL_WRITE_ONLY,"Animation polluted the caller's image binding");
            GL11.glDeleteTextures(foreignTexture);GL15.glDeleteBuffers(foreignBuffer);
        }finally{for(int buffer:new int[]{header,empty})GL15.glDeleteBuffers(buffer);deleteProgram(p);}
    }
    static void vector(int p,String name,float x,float y,float z){GL41.glProgramUniform3f(p,GL20.glGetUniformLocation(p,name),x,y,z);}
    static ByteBuffer animationRead(AllayAnimationGpu animation,int bank){GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);return read(animation.buffer(bank),64);}
    static void cadenceAndLook(){
        int p=animationKernel(),header=buffer(headers()),players=buffer(bytes(4096));bind(5,header);bind(18,players);ui(p,"uCount",1);ui(p,"uMode",0);
        try(var animation=new AllayAnimationGpu()){
            animation.ensure(1);
            for(int hz:new int[]{10,20,60,144,240,-1}){
                animation.clear();int bank=0,frame=0;float time=0;GL41.glProgramUniform1i(p,GL20.glGetUniformLocation(p,"uPlayerCount"),0);
                vector(p,"uVelocity",1,0,0);
                while(true){vector(p,"uPosition",time,0,0);f(p,"uAge",time);animation.bindCompute(p,bank);dispatch(p,1);bank=1-bank;if(time>=2)break;float dt=hz>0?1f/hz:new float[]{.007f,.037f,.012f,.05f}[frame%4];time=Math.min(2,time+dt);frame++;}
                var state=animationRead(animation,bank);float amount=(state.getInt(12)>>>16)/65535f,phase=state.getFloat(20);
                check(Math.abs(amount-.2)<.0001,"Frame rate changed vanilla walk amount hz="+hz+" value="+amount);
                double expected=8-.3*(1-Math.pow(.6,40));check(Math.abs(Math.atan2(Math.sin(phase-expected),Math.cos(phase-expected)))<.002,"Frame rate changed accumulated walk phase hz="+hz+" value="+phase);
                double expectedPrevious=7.8-.3*(1-Math.pow(.6,39));check(Math.abs(Math.atan2(Math.sin(state.getFloat(16)-expectedPrevious),Math.cos(state.getFloat(16)-expectedPrevious)))<.002,"Low frame rate did not retain the preceding tick for interpolation hz="+hz);
            }
            animation.clear();vector(p,"uVelocity",0,0,0);vector(p,"uPosition",0,0,0);int bank=0;
            float[] feet={0,0,3, 0,0,4},eyes={0,1.62f,3, 0,1.62f,4},centers={0,.9f,3, 0,.9f,4};
            var grid=new StormPlayerGrid();grid.build(centers,eyes,feet,new int[]{73,99},2,null);var packed=bytes(grid.words()*4);for(int i=0;i<grid.words();i++)packed.putFloat(i*4,grid.data()[i]);put(players,packed);GL41.glProgramUniform1i(p,GL20.glGetUniformLocation(p,"uPlayerCount"),2);
            for(int tick=0;tick<=39;tick++){animation.bindCompute(p,bank);f(p,"uAge",tick*.05f);dispatch(p,1);bank=1-bank;}
            var looked=animationRead(animation,bank);check(looked.getInt(32)==73,"Idle allay failed to choose closest player");check(((looked.getInt(4)>>>16)/65535.-.5)*Math.PI<-.01,"Higher player eyes did not produce negative head pitch");
            grid.build(new float[]{0,.9f,4,0,.9f,3},new float[]{0,1.62f,4,0,1.62f,3},new float[]{0,0,4,0,0,3},new int[]{99,73},2,null);for(int i=0;i<grid.words();i++)packed.putFloat(i*4,grid.data()[i]);put(players,packed);
            animation.bindCompute(p,bank);f(p,"uAge",2);dispatch(p,1);bank=1-bank;check(animationRead(animation,bank).getInt(32)==73,"Player reorder changed the observed entity");
            grid.build(new float[]{0,.9f,7},new float[]{0,1.62f,7},new float[]{0,0,7},new int[]{73},1,null);for(int i=0;i<grid.words();i++)packed.putFloat(i*4,grid.data()[i]);put(players,packed);
            GL41.glProgramUniform1i(p,GL20.glGetUniformLocation(p,"uPlayerCount"),1);animation.bindCompute(p,bank);f(p,"uAge",2.05f);dispatch(p,1);bank=1-bank;var lost=animationRead(animation,bank);check(lost.getInt(32)==0,"Out-of-range player remained a look target");check(Math.abs(((lost.getInt(4)>>>16)/65535.-.5)*Math.PI)<.0001,"Head did not return to horizontal after target loss");
            GL41.glProgramUniform1i(p,GL20.glGetUniformLocation(p,"uPlayerCount"),0);animation.bindCompute(p,bank);f(p,"uAge",2.1f);dispatch(p,1);bank=1-bank;check(animationRead(animation,bank).getInt(32)==0,"Removed player remained a look target");
            animation.clear();bank=0;ui(p,"uMode",1);int spin=0;
            for(int tick=0;tick<=120;tick++){animation.bindCompute(p,bank);f(p,"uAge",tick*.05f);dispatch(p,1);bank=1-bank;if(tick>0)spin=Math.clamp(spin+(tick%55<15?1:-1),0,15);var state=animationRead(animation,bank);check(((state.getInt(28)>>>16)&15)==spin,"Dance accumulator differs from Allay.tick at tick="+tick);}
        }finally{GL15.glDeleteBuffers(header);GL15.glDeleteBuffers(players);deleteProgram(p);}
    }
    public static void main(String[] args)throws Exception{
        GLFWErrorCallback.createPrint(System.err).set();check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(64,64,"Vanilla Allay pose oracle",0,0);
        try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();System.out.println(GL11.glGetString(GL11.GL_RENDERER)+" / "+GL11.glGetString(GL11.GL_VERSION));vanillaMatrices();playerSnapshot();sidecar();cadenceAndLook();check(GL11.glGetError()==0,"Allay animation GL error");System.out.println("Allay animation GPU: "+checks+" assertions passed");}finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
