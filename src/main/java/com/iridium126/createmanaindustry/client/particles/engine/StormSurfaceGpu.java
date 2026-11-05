package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.client.gpu.state.GpuBindings;

import com.iridium126.createmanaindustry.client.allaystorm.StormCollisionGpu;
import com.iridium126.createmanaindustry.client.allaystorm.StormCollisionPages.Section;
import com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import java.nio.ByteBuffer;
import java.util.function.Consumer;
import static com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.*;
import static com.iridium126.createmanaindustry.client.particles.engine.StormSurfaceCache.*;

/** One ring of packed heights, small dirty/feedback masks and a bounded shared patch staging area. */
public final class StormSurfaceGpu implements AutoCloseable {
    public static final int WIDTH=256,BYTES=WIDTH*WIDTH*4,BINDING=9;
    private static final int[] ZERO={0};
    private final StormSurfaceCache cache=new StormSurfaceCache();
    private final ByteBuffer staging=BufferUtils.createByteBuffer(PATCH_CAP*16);
    private final Consumer<Section> changed=s->cache.voxelChanged(s.x(),s.y(),s.z());
    private int buffer,program;
    private long rebuilds,uploaded;
    private StormCollisionGpu source;
    private double feedbackX=Double.NaN,feedbackZ,feedbackRadius;
    private boolean feedbackDue,feedbackGenerated,feedbackStaged,automatic;
    public StormSurfaceCache cache(){return cache;}
    public void frame(ClientLevel level,Vec3 center,double radius,boolean enabled){
        feedbackGenerated=feedbackStaged=false;
        if(!enabled||level==null){if(automatic)reset();return;}
        automatic=true;cache.configure(center.x,center.y,center.z,radius);StormSurfaceCapture.activate(level,cache);
    }
    public boolean prepare(int nextProgram,StormCollisionGpu nextSource,Vec3 center){return prepare(nextProgram,nextSource,center,64,16384);}
    public boolean prepare(int nextProgram,StormCollisionGpu nextSource,Vec3 center,double radius,int uploadBudget){
        cache.configure(center.x,center.y,center.z,radius);
        if(center.x!=feedbackX||center.z!=feedbackZ||radius!=feedbackRadius){feedbackDue=true;feedbackX=center.x;feedbackZ=center.z;feedbackRadius=radius;}
        if(source!=nextSource){if(source!=null)source.surfaceListener(null);source=nextSource;if(source!=null)source.surfaceListener(changed);cache.invalidateAll(false);}
        if(program!=0&&program!=nextProgram)cache.invalidateAll(true);
        if(buffer==0){buffer=GL15.glGenBuffers();GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,buffer);GpuTransfers.allocate(GL43.GL_SHADER_STORAGE_BUFFER,TOTAL_BYTES,GL15.GL_DYNAMIC_COPY);com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.clearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,ZERO);}
        int dirty=cache.dirty.cardinality(),budget=Math.min(16384,Math.max(0,uploadBudget));
        if(dirty==0&&!cache.hasPatches()){program=nextProgram;return true;}
        if(budget<dirty*16)return false;
        bind();com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT);com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(nextProgram);program=nextProgram;
        staging.clear();for(int t=cache.dirty.nextSetBit(0);t>=0;t=cache.dirty.nextSetBit(t+1))staging.putInt(cache.tileX[t]).putInt(cache.tileZ[t]).putInt(cache.versions[t]).putInt(cache.resetCpu.get(t)?1:0);
        int patches=cache.appendPatches(staging,Math.min(PATCH_CAP,budget/16)-dirty);staging.flip();
        if(staging.hasRemaining())upload(staging);
        if(dirty>0){
            GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,buffer);com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.clearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,DIRTY_WORD*4L,32,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,ZERO);
            setIntUniform(program,"uSurfaceTop",cache.top);setUIntUniform(program,"uSurfaceCount",dirty);setUIntUniform(program,"uSurfacePhase",1);com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchCompute(dirty,1,1);barrier();
            cache.dirty.clear();cache.resetCpu.clear();budget-=dirty*16;
            if(source!=null&&source.revision()>=0){source.bind(program);setUIntUniform(program,"uSurfacePhase",0);int count=source.tableSize(),groups=Math.min(count,16384);com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchCompute(groups,(count+groups-1)/groups,1);barrier();}
            feedbackDue=true;rebuilds++;
        }
        if(patches>0){setUIntUniform(program,"uSurfacePatchStart",dirty);setUIntUniform(program,"uSurfaceCount",patches);setUIntUniform(program,"uSurfacePhase",3);com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchCompute((patches+63)/64,1,1);barrier();cache.acceptPatches(staging,dirty,patches);}
        return true;
    }
    private void upload(ByteBuffer data){GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,buffer);uploaded+=data.remaining();GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,PATCH_WORD*4L,data);barrier();}
    public void feedback(Vec3 center,double radius,boolean frame){
        if(!frame||!feedbackDue||buffer==0)return;
        bind();com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(program);com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current2f(loc(program,"uSurfaceCenter"),(float)center.x,(float)center.z);setFloatUniform(program,"uSurfaceRadius",(float)(2*radius));
        setUIntUniform(program,"uSurfacePhase",2);com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchCompute(32,1,1);barrier();feedbackGenerated=true;
    }
    void copySnapshot(ParticleReadbacks.Slot slot){
        slot.goldSurface=feedbackGenerated;slot.goldSurfaceEpoch=cache.epoch;if(!feedbackGenerated)return;
        GpuBindings.bind(GL31.GL_COPY_READ_BUFFER,buffer);GpuBindings.bind(GL31.GL_COPY_WRITE_BUFFER,slot.buffer);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.copyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,BYTES,ParticleReadbacks.GOLD_SURFACE_OFFSET,FEEDBACK_BYTES);feedbackStaged=true;
    }
    void observe(ParticleReadbacks.Slot slot){if(slot.goldSurface)cache.feedback(slot.goldSurfaceEpoch,slot.goldData);}
    void commit(){if(feedbackStaged)feedbackDue=false;feedbackGenerated=feedbackStaged=false;}
    public void bind(){GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,BINDING,buffer);}
    public void bindEmitter(int program){bind();com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current2i(loc(program,"uSurfaceOrigin"),cache.originX,cache.originZ);}
    public int buffer(){return buffer;}
    public long rebuilds(){return rebuilds;}
    public long uploaded(){return uploaded;}
    public long bytes(){return buffer==0?0:TOTAL_BYTES;}
    public void reset(){StormSurfaceCapture.deactivate(cache);if(source!=null)source.surfaceListener(null);source=null;cache.reset();program=0;feedbackX=Double.NaN;automatic=feedbackDue=feedbackGenerated=feedbackStaged=false;}
    @Override public void close(){if(buffer!=0)com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(buffer);buffer=0;reset();}
    private static void barrier(){com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);}
}
