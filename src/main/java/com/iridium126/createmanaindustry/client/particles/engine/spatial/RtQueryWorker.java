package com.iridium126.createmanaindustry.client.particles.engine.spatial;

import com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames;
import java.nio.file.Path;
import java.util.*;
import org.lwjgl.opengl.*;

/** Three slots, ordered consumption, and fence-based retirement. No age policy. */
public final class RtQueryWorker implements AutoCloseable {
    public static final class Slot {
        public int input,output,texture;
        public long nonce,tick,scene;
        public int kind;
        public int members;
        private long fence;
        private boolean live,consumed,reported;
        private OrderedResultRing.Ticket ticket;
    }
    private static final List<RtQueryWorker> RETIRING=new ArrayList<>();
    private final Slot[] slots={new Slot(),new Slot(),new Slot()};
    private final OrderedResultRing ring=new OrderedResultRing(3);
    private long bridge,worker;
    private final int capacity,terrainCapacity;
    private boolean closing,released,failed;
    private Slot accepted;
    private String reason="no completed query";
    private String firstFailure="";
    public RtQueryWorker(int capacity,int terrainCapacity,int probes,long epoch,Path modules){
        this.capacity=capacity;this.terrainCapacity=terrainCapacity;
        try{
            if(!RETIRING.isEmpty())throw new IllegalStateException("Prior RT resources are still retiring");
            SpatialNative.load(modules);bridge=SpatialNative.createBuffers(128L+(capacity+terrainCapacity)*64L,3);
            worker=SpatialNative.create(bridge,capacity,terrainCapacity,probes,epoch,modules.toAbsolutePath().toString());
            for(int i=0;i<3;i++){int[] buffers=SpatialNative.buffers(bridge,i);var s=slots[i];s.input=buffers[0];s.output=buffers[1];s.texture=GL45.glCreateTextures(GL31.GL_TEXTURE_BUFFER);GL45.glTextureBuffer(s.texture,GL30.GL_RGBA32UI,s.output);}
        }catch(RuntimeException|LinkageError failure){close();throw failure;}
    }
    public Slot take(long tick,long scene,int kind){
        finishConsumers();reap();
        while(ring.head()!=null){
            var ticket=ring.head();var s=slots[ticket.slot()];
            int state=SpatialNative.poll(worker,ticket.slot(),s.nonce);
            if(state==1)return null;
            if(state!=2&&state!=4)throw new IllegalStateException("Unexpected RT completion state "+state);
            int receipt=SpatialNative.consume(worker,ticket.slot(),s.nonce);
            ring.consume(ticket);s.consumed=true;
            if(state==4||receipt!=0){failed=true;reason="receipt="+receipt+"; "+SpatialNative.diagnostics(worker);if(firstFailure.isEmpty())firstFailure=reason;}
            boolean apply=!closing&&state==2&&receipt==0&&s.scene==scene&&s.kind==kind;
            record(ticket.slot(),s,apply?"consumed":"consumed_without_publication");
            if(apply){accepted=s;reason="ordered completion from tick "+s.tick;return s;}
            // Identity destruction and errors still receive a consumption and retirement.
            s.fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);
        }
        if(!failed)reason="no completion; retain simulation/display state";
        return null;
    }
    public Slot acquire(){return acquire(0,0,0);}
    public Slot acquire(long tick,long scene,int kind){
        finishConsumers();reap();if(failed||closing)return null;
        var ticket=ring.acquire(tick,scene,kind);
        if(ticket==null){reason="ring full; spatial snapshot backpressure";return null;}
        var s=slots[ticket.slot()];s.ticket=ticket;s.nonce=ticket.nonce();return s;
    }
    public boolean submit(Slot slot,long tick,long scene,int kind){
        return submit(slot,tick,scene,kind,capacity,terrainCapacity);
    }
    public boolean submit(Slot slot,long tick,long scene,int kind,int members,int terrain){
        int index=Arrays.asList(slots).indexOf(slot);
        if(index<0||slot.live||slot.ticket==null)throw new IllegalStateException("RT slot ownership");
        if(!SpatialNative.submitRange(worker,index,slot.nonce,tick,scene,kind,members,terrain)){
            ring.cancel(slot.ticket);slot.ticket=null;failed=true;reason="RT submission refused";return false;
        }
        ring.submit(slot.ticket);slot.tick=tick;slot.scene=scene;slot.kind=kind;slot.members=members;slot.live=true;slot.consumed=false;slot.reported=false;
        com.iridium126.createmanaindustry.client.particles.engine.ClientFrameCapture.INSTANCE.spatial(tick,scene,slot.nonce,index,"submitted",false,null);
        return true;
    }
    public void finishConsumers(){
        if(accepted!=null){accepted.fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);accepted=null;}
    }
    private void reap(){
        if(worker==0)return;
        for(int i=0;i<3;i++){var s=slots[i];if(!s.live||!s.consumed||s.fence==0)continue;
            if(SpatialNative.recycle(worker,i,s.nonce,s.fence)){
                com.iridium126.createmanaindustry.client.particles.engine.ClientFrameCapture.INSTANCE.spatial(s.tick,s.scene,s.nonce,i,"retired",true,null);
                GL32.glDeleteSync(s.fence);s.fence=0;s.live=s.consumed=false;ring.retire(s.ticket);s.ticket=null;
            }
        }
    }
    private void record(int i,Slot s,String event){
        var capture=com.iridium126.createmanaindustry.client.particles.engine.ClientFrameCapture.INSTANCE;
        if(!s.reported&&capture.tracksSpatial(s.scene,s.nonce)){
            double[] times=null;
            try{times=SpatialNative.timing(worker,i,s.nonce);}
            catch(RuntimeException|LinkageError failure){capture.invalidate("RT timing unavailable: "+failure);}
            capture.spatial(s.tick,s.scene,s.nonce,i,event,true,times);s.reported=true;
        }
    }
    public boolean failed(){return failed;}
    public record Counts(long submitted,long consumed,long retired,int pending,int occupied) {}
    public Counts counts(){return new Counts(ring.submitted(),ring.consumptions(),ring.retired(),ring.pending(),ring.occupied());}
    public String description(){return reason+(firstFailure.isEmpty()?"":" first_failure={"+firstFailure+"}")+" submitted="+ring.submitted()+" consumed="+ring.consumptions()+" retired="+ring.retired()+" depth="+ring.pending();}
    private boolean release(){
        if(released)return true;
        for(var s:slots)if(!s.live&&s.ticket!=null){ring.cancel(s.ticket);s.ticket=null;}
        if(worker!=0){take(0,Long.MIN_VALUE,-1);reap();if(ring.occupied()!=0)return false;if(!SpatialNative.close(worker))return false;worker=0;}
        if(bridge!=0)SpatialNative.closeBuffers(bridge);bridge=0;
        for(var s:slots)if(s.texture!=0)GpuObjectNames.deleteTexture(s.texture);released=true;return true;
    }
    private boolean releaseSafely(){try{return release();}catch(RuntimeException|LinkageError failure){failed=true;reason="unretired resources isolated: "+failure;return false;}}
    @Override public void close(){if(closing)return;closing=true;if(!releaseSafely())RETIRING.add(this);}
    public static void retire(){RETIRING.removeIf(RtQueryWorker::releaseSafely);}
    public static boolean resourcesRetiring(){return !RETIRING.isEmpty();}
}
