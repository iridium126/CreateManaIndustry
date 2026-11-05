package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.client.allaystorm.AllayStormRuntime;
import com.iridium126.createmanaindustry.client.allaystorm.StormFeedback;
import com.iridium126.createmanaindustry.client.gpu.buffer.GpuReadbackBuffer;
import com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands;
import com.iridium126.createmanaindustry.content.allaystorm.StormIdentity;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL42;

/** A committed attack batch has a single tick and identity, regardless of frame staging pressure. */
final class StormBatchReadbacks implements AutoCloseable {
    private static final int STRIDE=ParticleBuffers.WAVECONTACT_ENTRY_FLOATS;
    private static final int CAPACITY=ParticleBuffers.WAVECONTACT_CAP;
    private static final int BYTES=4+CAPACITY*STRIDE*Float.BYTES;
    private static final int WARM_SLOTS=3;
    static final class Batch {
        final GpuReadbackBuffer storage=new GpuReadbackBuffer(BYTES);
        long fence,tick,epoch;
        StormIdentity identity;
        boolean published;
    }
    private final ArrayDeque<Batch> pending=new ArrayDeque<>(),free=new ArrayDeque<>();
    private final ByteBuffer data=BufferUtils.createByteBuffer(BYTES);
    private long waitsFailed;

    Batch stage(int source,long tick,long epoch,StormIdentity identity){
        var batch=free.pollFirst();if(batch==null)batch=new Batch();
        try{
            GpuReplayCommands.memoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
            GpuReplayCommands.copyNamedBufferSubData(source,batch.storage.id(),0,0,BYTES);
            batch.fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);
            if(batch.fence==0)throw new IllegalStateException("Storm attack batch fence unavailable");
            batch.tick=tick;batch.epoch=epoch;batch.identity=identity;batch.published=false;
            pending.addLast(batch);return batch;
        }catch(RuntimeException|LinkageError failure){batch.storage.close();throw failure;}
    }
    void commit(Batch batch){if(batch!=null)batch.published=true;}
    void poll(long epoch){
        while(!pending.isEmpty()){
            var batch=pending.peekFirst();
            int status=GL32.glClientWaitSync(batch.fence,0,0);
            if(status==GL32.GL_TIMEOUT_EXPIRED)return;
            pending.removeFirst();
            GL32.glDeleteSync(batch.fence);batch.fence=0;
            if(status==GL32.GL_WAIT_FAILED){
                batch.storage.close();
                if(waitsFailed++==0)com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.error("Storm attack batch fence failed at tick {}",batch.tick);
                continue;
            }
            try{
                if(batch.published&&batch.epoch==epoch&&batch.identity!=null&&batch.identity.equals(AllayStormRuntime.INSTANCE.identity())){
                    data.clear();batch.storage.readCompleted(0,4,data);
                    int count=Math.min(CAPACITY,Math.max(0,data.getInt(0)));
                    if(count!=0)batch.storage.readCompleted(4,count*STRIDE*Float.BYTES,data);
                    StormFeedback.INSTANCE.acceptWaveSnapshot(ParticleReadbacks.entries(data,0,CAPACITY,STRIDE));
                }
            }finally{
                batch.identity=null;
                if(free.size()<WARM_SLOTS)free.addLast(batch);else batch.storage.close();
            }
        }
    }
    @Override public void close(){
        for(var batch:pending){GL32.glDeleteSync(batch.fence);batch.storage.close();}
        for(var batch:free)batch.storage.close();
        pending.clear();free.clear();
    }
}
