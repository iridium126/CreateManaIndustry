package com.iridium126.createmanaindustry.client.particles.engine.hardware;

import java.util.*;
import java.util.concurrent.locks.LockSupport;
import org.lwjgl.opengl.*;

/** COPY queue candidate for real state-bank copies. GL stages and consumes the
 * current bytes; the dedicated COPY queue performs the middle transfer. A late
 * result is discarded and the original GL copy runs in its entirety. */
public final class HardwareCopyStage implements AutoCloseable {
    private static final List<HardwareCopyStage> RETIRING=new ArrayList<>();
    private static final class Slot {boolean pending;long consumer;}
    private final Slot[] slots={new Slot(),new Slot(),new Slot()};
    private final int capacity;private long bridge;private boolean closed,faulted;
    private String last="inactive";private double copyMillis=Double.NaN;
    public HardwareCopyStage(int capacity){this.capacity=capacity;bridge=HardwareNative.createCopy(capacity,3);}
    private boolean reap(int index){var s=slots[index];if(!s.pending)return true;if(!HardwareNative.copyReady(bridge,index))return false;if(s.consumer!=0){int state=GL32.glClientWaitSync(s.consumer,0,0);if(state==GL32.GL_TIMEOUT_EXPIRED)return false;if(state==GL32.GL_WAIT_FAILED)throw new IllegalStateException("COPY consumer fence failed");GL32.glDeleteSync(s.consumer);s.consumer=0;}s.pending=false;return true;}
    public boolean copy(int source,int destination,long from,long to,long length,long waitNanos){
        try{return copyChecked(source,destination,from,to,length,waitNanos);}catch(RuntimeException|LinkageError failure){faulted=true;last="complete GL reference after COPY retirement failure: "+failure;return false;}
    }
    private boolean copyChecked(int source,int destination,long from,long to,long length,long waitNanos){
        copyMillis=Double.NaN;if(closed||faulted||length<=0||length>capacity||source==destination){last="complete GL reference: COPY unavailable/range";return false;}
        int slot=-1;for(int i=0;i<3;i++)if(reap(i)){slot=i;break;}if(slot<0){last="complete GL reference: COPY ring busy";return false;}
        int read=GL11.glGetInteger(GL31.GL_COPY_READ_BUFFER),write=GL11.glGetInteger(GL31.GL_COPY_WRITE_BUFFER);
        try{
            int[] shared=HardwareNative.copyBuffers(bridge,slot);com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT);
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,source);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,shared[0]);com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.copyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,from,0,length);
            slots[slot].pending=true;if(!HardwareNative.beginCopy(bridge,slot,length)){slots[slot].pending=false;last="complete GL reference: COPY rejected";return false;}
            long deadline=System.nanoTime()+Math.max(0,waitNanos);while(!HardwareNative.copyReady(bridge,slot)){if(System.nanoTime()>=deadline){last="complete GL reference: COPY late";return false;}LockSupport.parkNanos(50_000);}
            HardwareNative.awaitCopy(bridge,slot);GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,shared[1]);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,destination);com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.copyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,0,to,length);
            slots[slot].consumer=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);copyMillis=HardwareNative.copyMillis(bridge,slot);last="current state copied through D3D12 COPY queue";return true;
        }catch(RuntimeException|LinkageError error){faulted=true;last="complete GL reference after COPY failure: "+error;return false;}
        finally{GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,read);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,write);}
    }
    public String lastReason(){return last;}public double millis(){return copyMillis;}
    public boolean faulted(){return faulted;}
    private boolean retire(){if(bridge==0)return true;try{for(int i=0;i<3;i++)if(!reap(i))return false;HardwareNative.closeCopy(bridge);bridge=0;return true;}catch(RuntimeException|LinkageError unsafe){faulted=true;return false;}}
    @Override public void close(){if(closed)return;closed=true;if(!retire())RETIRING.add(this);}
    public static void pollRetirement(){RETIRING.removeIf(HardwareCopyStage::retire);}
    public static int retainedStages(){return RETIRING.size();}
}
