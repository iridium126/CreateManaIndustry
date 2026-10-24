import java.nio.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.allaystorm.*;
import com.iridium126.createmanaindustry.client.particles.engine.*;
import com.iridium126.createmanaindustry.content.allaystorm.*;
import com.iridium126.createmanaindustry.content.allaystorm.network.*;
import net.minecraft.world.phys.Vec3;

/** Production update, final contact audit, carrier collection and contact readback. */
public class DomeExitGpuValidation extends DomeGpuValidation {
    static final int INSIDE=0x10000000,ENTERED=0x20000000;
    static void writeRange(int buffer,long offset,ByteBuffer value){GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,offset,value);}
    static class Probe implements AutoCloseable {
        final int update=compile("update.comp"),gridProgram=compile("gridbuild.comp"),body=compile("dome_contacts.comp"),contact=compile("wavecontact.comp");
        final Fixture q=new Fixture(1,1,1);
        final StarfallGpuBuffers gpu=new StarfallGpuBuffers();
        final int output=q.alloc(bytes(64)),grid=q.alloc(bytes((ParticleBuffers.GRID_TABLE+1)*4)),damage=q.alloc(bytes(16)),players=q.alloc(bytes(64)),correction=q.alloc(bytes(32)),map=q.alloc(bytes(4)),reports=q.alloc(bytes(4+16384*24)),stamps=q.alloc(bytes(16));
        ByteBuffer data;int id=27;long frame;float blockedOverride=Float.NaN;ClientboundStormWavePacket wave;
        Probe(){
            com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clear();
            var h=headers();h.putFloat(2*320+18*16+4,16).putFloat(2*320+18*16+12,6).putFloat(2*320+19*16+4,40);put(q.emit,h);put(q.previous,bytes(16).putInt(0,1));
            ui(update,"uCapacity",1);ui(update,"uKillEmit",-1);GL41.glProgramUniform1i(update,GL20.glGetUniformLocation(update,"uPlayerCount"),1);
            wave=domeAt(id,2,4,10);gpu.rebuild(new ClientboundStormWavePacket[]{wave,null,null,null});warmDome(gpu,update);gpu.commit();
        }
        void seed(Vec3 center,int flags,float time){
            data=bytes(64);particle(data,0,2,(float)center.x,(float)center.z,20);data.putFloat(4,(float)center.y-.3f).putFloat(56,AllayStormWaves.memberSeed(1,0));
            q.build(data,1);put(q.previous,bytes(16).putInt(0,1));gpu.bind(0,false);int state=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28);
            writeRange(state,0,bytes(8).putInt(0,id).putInt(4,flags|0x100));writeRange(state,StarfallGpuBuffers.GATHER_BLOCKED*4L,bytes(4));
            writeRange(state,StarfallGpuBuffers.DOME_JOIN*4L,bytes(32).putInt(0,id).putFloat(24,6));gpu.clock(time);
        }
        int step(float time,float dt){
            put(q.pool,data);put(q.counter,bytes(16));var pg=new StormPlayerGrid();pg.build(new float[]{data.getFloat(0),data.getFloat(4)+.3f,data.getFloat(8)},1);var pp=bytes(pg.words()*4);for(int k=0;k<pg.words();k++)pp.putFloat(k*4,pg.data()[k]);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,players);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,pp,GL15.GL_DYNAMIC_COPY);
            bind(0,q.pool);bind(1,output);bind(3,q.counter);bind(5,q.emit);bind(13,grid);bind(14,q.previous);bind(16,damage);bind(18,players);bind(19,correction);bind(21,map);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,grid);GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,0,ParticleBuffers.GRID_TABLE*4L,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(IntBuffer)null);dispatch(gridProgram,1);
            gpu.clock(time);gpu.prepare(100000+frame++);gpu.bind(update,true);f(update,"uTimeSec",time);f(update,"uDt",dt);dispatch(update,1);
            if(Float.isFinite(blockedOverride)){writeRange(GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28),StarfallGpuBuffers.GATHER_BLOCKED*4L,bytes(4).putFloat(0,blockedOverride));blockedOverride=Float.NaN;}
            DomeBodyContacts.run(gridProgram,body,q.pool,q.previous,output,q.counter,grid,time,dt,1,gpu);data=read(output,64);
            int state=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28);int flags=read(state,8).getInt(4);gpu.commit();return flags;
        }
        void released(int flags,String label){
            check((flags&255)==6,label+": still attacking");check(data.getFloat(28)==0,label+": sword marker survived");
            gpu.bind(0,false);int state=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28);var done=bytes(4);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,state);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,StarfallGpuBuffers.COMPLETIONS*4L,done);check((done.getInt(0)&1)!=0,label+": no completion bit");
            // Same committed generation consumed by carrier collection and contact.
            gpu.bind(q.reset,false);gpu.bind(q.keygen,false);f(q.keygen,"uTimeSec",40);q.build(data,1);var commands=read(q.commands,ParticleBuffers.INDIRECT_UINTS*4);check(commands.getInt(ParticleBuffers.IDX_CNT_CARRIER*4)==0,label+": held sword still collected");
            bind(0,q.pool);bind(1,output);bind(14,q.previous);bind(21,map);bind(22,reports);bind(30,stamps);put(reports,bytes(4+16384*24));gpu.bind(contact,false);ui(contact,"uLocalWaveMask",1);f(contact,"uTimeSec",40);f(contact,"uDt",.05f);GL41.glProgramUniform3f(contact,GL20.glGetUniformLocation(contact,"uLocalPlayerPos"),data.getFloat(0),data.getFloat(4),data.getFloat(8));GL41.glProgramUniform4fv(contact,GL20.glGetUniformLocation(contact,"uWaveTarget"),new float[]{0,0,0,id,0,0,0,0,0,0,0,0,0,0,0,0});dispatch(contact,1);
            var hits=read(reports,4+16384*24);for(int k=0;k<hits.getInt(0);k++)check(hits.getFloat(4+k*24)<0,label+": contact reported after release");
        }
        public void close(){gpu.close();q.close();for(int p:new int[]{update,gridProgram,body,contact}){com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clearUniformProgram(p);deleteProgram(p);}}
    }
    static void gates(){
        try(var p=new Probe()){
            int mapped=DomeTemplate.mappedRank(0,10);var end=DomeTemplate.point(mapped,DomeTemplate.length(mapped));var tangent=DomeTemplate.sampleArc(mapped,DomeTemplate.length(mapped),1).normalize();
            p.seed(end.add(tangent.scale(40)),4,40);p.released(p.step(40,.05f),"exhausted missed-center route");
            // Completion is terminal while the slot and nearby player remain active.
            for(int i=1;i<=20;i++){int flags=p.step(40+i*.05f,.05f);check((flags&255)>=6&&p.data.getFloat(28)==0,"Released member re-armed near player");}
            p.seed(new Vec3(33,0,0),4,10.1f);int flags=p.step(10.1f,0);check((flags&255)==4&&(flags&INSIDE)==0,"Outer-shell ingress retired before entering");
            p.seed(new Vec3(32,0,0),4,10.1f);flags=p.step(10.1f,0);check((flags&255)==4&&(flags&INSIDE)!=0,"Exact 32m boundary must arm without retiring");
            p.data.putFloat(0,32.001f);p.released(p.step(10.2f,0),"outbound sphere crossing without centre passage");
            p.seed(new Vec3(0,33,0),4,40);p.released(p.step(40,0),"vertical exhausted route");
            p.seed(new Vec3(10,0,0),4,40);flags=p.step(40,0);check((flags&255)==4,"Exhausted route inside sphere must retain actual completion rule");
            p.seed(end.add(tangent.scale(.01)),4|ENTERED,40);flags=p.step(40,0);p.released(flags,"normal three-metre exit");check((flags&ENTERED)!=0,"Normal completion misclassified as abnormal");
            p.seed(new Vec3(10,0,0),4,10.1f);p.blockedOverride=.5f;p.released(p.step(10.1f,0),"blocked route common completion");
            // A stale inside bit cannot retire a newly assigned member on ingress.
            p.id=28;p.wave=domeAt(28,2,4,10);p.gpu.rebuild(new ClientboundStormWavePacket[]{p.wave,null,null,null});p.seed(new Vec3(33,0,0),4|INSIDE,10.1f);p.gpu.bind(0,false);writeRange(GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28),0,bytes(8).putInt(0,27).putInt(4,4|INSIDE|0x100));
            flags=p.step(10.1f,0);check((flags&255)==4&&(flags&INSIDE)==0,"Old attack sphere latch leaked into replacement");p.gpu.completed(27,new long[]{1});p.gpu.rebuild(new ClientboundStormWavePacket[]{p.wave,null,null,null});check((p.step(10.2f,0)&255)==4,"Old completion released replacement");
            p.seed(new Vec3(40,0,0),4,71);p.released(p.step(71,0),"whole attack deadline");
            // Frozen-centre subtraction and a rotated wave at large coordinates.
            Vec3 center=new Vec3(1_000_000,80,-1_000_000);var source=domeAt(29,2,4,10);p.id=29;
            p.wave=new ClientboundStormWavePacket(29,source.epoch(),source.event(),source.waveSeed(),source.targetEntityId(),source.swordTier(),source.columns(),source.perColumn(),source.startSec(),source.assembleSec(),source.lockSec(),source.diveUntilSec(),(float)center.x,(float)center.y+40,(float)center.z,32,source.members(),new float[]{(float)center.x,(float)center.y+40,(float)center.z,(float)center.x,(float)center.y,(float)center.z},source.landings(),1,4,source.groupEnds(),(float)center.x,(float)center.y,(float)center.z,1.2f,1,0,0);
            p.gpu.rebuild(new ClientboundStormWavePacket[]{p.wave,null,null,null});p.seed(center.add(10,0,0),4,40);check((p.step(40,0)&255)==4,"World origin used instead of frozen Dome centre");
            p.seed(center.add(33,0,0),4,40);p.released(p.step(40,0),"far rotated Dome boundary");
            p.wave=domeAt(p.id,0,1,90);p.gpu.rebuild(new ClientboundStormWavePacket[]{p.wave,null,null,null});p.seed(new Vec3(40,0,0),1,10.1f);flags=p.step(10.1f,0);check((flags&255)==1&&(flags&INSIDE)==0,"Gathering member used attack sphere guard");
        }
    }
    public static void main(String[] args)throws Exception{
        GLFWErrorCallback.createPrint(System.err).set();check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(64,64,"Dome exit audit",0,0);
        try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();gates();check(GL11.glGetError()==0,"Dome exit GL error");System.out.println("Dome exit GPU: "+checks+" assertions passed");}finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
