package com.iridium126.createmanaindustry.client.particles.engine.spatial;

import com.iridium126.createmanaindustry.content.allaystorm.DomeGatherState;
import com.iridium126.createmanaindustry.content.allaystorm.DomeTemplate;
import com.iridium126.createmanaindustry.content.allaystorm.StarfallColumnSnapshot;
import com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormColumnSnapshotRequest;
import com.iridium126.createmanaindustry.client.particles.engine.ClientFrameCapture;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicLong;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;
import static com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.*;

/** Native command lane; spatial and display result buffers have separate ownership. */
final class NativeStormControl {
    private static final int PENDING=1, COMPLETE=2, FAILED=4;
    private static final int BITMAP_WORDS=STORM_DOME_MEMBERS/32;
    private static final AtomicLong EPOCHS=new AtomicLong();
    record Hit(int target,float x,float y,float z,float time) {}
    record HelixReference(int version,float seconds) {}
    record RegionalFeedback(long tick,long identity,int id,long[] ready,long[] completed,
                            int blocked,int unknown,DomeGatherState flows,HelixReference reference,Hit contact) {}
    record ColumnFeedback(long tick,long identity,int id,int token,long completed,long installed,Hit contact,
                          StormControlJournal.Float4 sound) {}
    record OuterFeedback(long identity,int[] rows) {}
    record PositionFeedback(long identity,long tick,float[] rows) {}
    record SnapshotFeedback(long identity,long tick,float seconds,ClientboundStormColumnSnapshotRequest request,float[] values) {}
    private record Batches(List<RegionalFeedback> regional,List<ColumnFeedback> columns,OuterFeedback outer,
                           PositionFeedback positions,List<SnapshotFeedback> snapshots) {
        private static final Batches EMPTY=new Batches(List.of(),List.of(),null,null,List.of());
    }
    private static final class DisplaySlot {
        final int index,buffer;
        long sequence,tick,gameTime,identity,fence;
        int readers;
        boolean complete;
        DisplaySlot(int index,int buffer){this.index=index;this.buffer=buffer;}
    }
    /** Same lease is shared by the main pass and its shadow pass. */
    final class DisplayLease implements NativeStormSimulation.FrozenDisplay {
        private DisplaySlot slot;
        private DisplayLease(DisplaySlot slot){this.slot=slot;slot.readers++;}
        @Override public int buffer(){return slot.buffer;}
        @Override public long tick(){return slot.tick;}
        @Override public long gameTime(){return slot.gameTime;}
        @Override public long sequence(){return slot.sequence;}
        @Override public long identity(){return slot.identity;}
        @Override public void close(){
            if(slot==null)return;
            slot.readers--;slot=null;
        }
    }
    private final StormControlJournal journal;
    private final long captureEpoch=EPOCHS.incrementAndGet();
    private final Consumer<RegionalFeedback> feedback;
    private final Consumer<ColumnFeedback> columnFeedback;
    private final Consumer<OuterFeedback> outerFeedback;
    private final Consumer<PositionFeedback> positionFeedback;
    private final Consumer<SnapshotFeedback> snapshotFeedback;
    private final ByteBuffer domeResult;
    private final DisplaySlot[] displaySlots=new DisplaySlot[RT_SLOTS];
    private DisplaySlot published;
    private ByteBuffer packet;
    private ByteBuffer terrainUpload;
    private long worker,displayBridge;
    private int terrainCount;
    private boolean retiring,domeInstalled;
    private String failure="";

    NativeStormControl(Path modules,int capacity,StormControlJournal journal,Consumer<RegionalFeedback> feedback,
                       Consumer<ColumnFeedback> columnFeedback,Consumer<OuterFeedback> outerFeedback,
                       Consumer<PositionFeedback> positionFeedback,Consumer<SnapshotFeedback> snapshotFeedback){
        this.journal=journal;
        this.feedback=feedback;
        this.columnFeedback=columnFeedback;
        this.outerFeedback=outerFeedback;
        this.positionFeedback=positionFeedback;
        this.snapshotFeedback=snapshotFeedback;
        SpatialNative.load(modules);
        packet=MemoryUtil.memAlloc(STORM_TICK_HEADER_BYTES+16*STORM_CONTROL_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer result=null;
        try{
            result=MemoryUtil.memAlloc(STORM_DOME_BATCH_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            displayBridge=SpatialNative.createStormDisplayBuffers(STORM_DISPLAY_HEADER_BYTES+
                    (long)capacity*STORM_DISPLAY_RECORD_BYTES+(long)STORM_COLUMN_SLOTS*STORM_COLUMN_MEMBERS*STORM_DISPLAY_COLUMN_BYTES,RT_SLOTS);
            for(int slot=0;slot<RT_SLOTS;slot++){
                int buffer=SpatialNative.buffers(displayBridge,slot)[1];
                displaySlots[slot]=new DisplaySlot(slot,buffer);
            }
            int probes=Boolean.getBoolean("cmi.particle.wideBudget")?4:2;
            worker=SpatialNative.createStormControl(capacity,displayBridge,probes);
            domeResult=result;
        }catch(RuntimeException|LinkageError error){
            if(displayBridge!=0)SpatialNative.closeBuffers(displayBridge);
            if(result!=null)MemoryUtil.memFree(result);
            MemoryUtil.memFree(packet);
            throw error;
        }
    }
    private void installTerrain(StormControlJournal.Geometry geometry){
        if(terrainUpload==null)
            terrainUpload=MemoryUtil.memAlloc(STORM_SPATIAL_TERRAIN_LIMIT*SPATIAL_RECORD_BYTES);
        terrainUpload.clear().put(geometry.rows()).flip();
        SpatialNative.installStormTerrain(worker,geometry.revision(),terrainUpload,geometry.count());
        terrainCount=geometry.count();
    }
    private void installDome(){
        var records=new float[STORM_DOME_MEMBERS*STORM_DOME_RECORD_WORDS];
        var flows=new int[STORM_DOME_MEMBERS];
        for(int i=0;i<records.length;i++)records[i]=DomeTemplate.word(4+i);
        for(int i=0;i<flows.length;i++)flows[i]=DomeTemplate.flowIndex(i);
        SpatialNative.installStormDome(worker,records,DomeTemplate.keys(),flows);
        domeInstalled=true;
    }
    private Batches feedback(StormControlJournal.Tick tick){
        if(!SpatialNative.copyStormDomeBatch(worker,tick.sequence(),domeResult))return Batches.EMPTY;
        var result=new ArrayList<RegionalFeedback>(STORM_DOME_SLOTS);
        for(int slot=0;slot<STORM_DOME_SLOTS;slot++){
            int id=domeResult.getInt(slot*Integer.BYTES);
            if(id==0)continue;
            var ready=bitmap(STORM_DOME_READY_OFFSET+slot*BITMAP_WORDS*Integer.BYTES);
            var completed=bitmap(STORM_DOME_COMPLETED_OFFSET+slot*BITMAP_WORDS*Integer.BYTES);
            var pauses=new float[STORM_DOME_FLOWS];
            for(int flow=0;flow<pauses.length;flow++){
                float pause=domeResult.getFloat(STORM_DOME_PAUSED_OFFSET+(slot*pauses.length+flow)*Float.BYTES);
                pauses[flow]=Float.isFinite(pause)?pause:-1f;
            }
            int contact=STORM_DOME_CONTACT_OFFSET+slot*STORM_DOME_CONTACT_BYTES;
            int referenceOffset=STORM_HELIX_REFERENCE_OFFSET+slot*STORM_HELIX_REFERENCE_BYTES;
            var reference=domeResult.getInt(referenceOffset+4)==0?null:
                    new HelixReference(domeResult.getInt(referenceOffset),domeResult.getFloat(referenceOffset+8));
            result.add(new RegionalFeedback(tick.gameTime(),tick.parameters().identity(),id,ready,completed,
                    domeResult.getInt(STORM_DOME_BLOCKED_OFFSET+slot*Integer.BYTES),
                    domeResult.getInt(STORM_DOME_UNKNOWN_OFFSET+slot*Integer.BYTES),
                    new DomeGatherState(pauses),reference,hit(contact)));
        }
        var columns=new ArrayList<ColumnFeedback>();
        for(int slot=0;slot<STORM_COLUMN_SLOTS;slot++){
            int offset=STORM_COLUMN_BATCH_OFFSET+slot*STORM_COLUMN_BATCH_STRIDE;
            int id=domeResult.getInt(offset);
            if(id==0)continue;
            long completed=domeResult.getLong(offset+8),installed=domeResult.getLong(offset+16);
            var hit=hit(offset+32);
            var sound=domeResult.getFloat(offset+76)==0?null:new StormControlJournal.Float4(
                    domeResult.getFloat(offset+64),domeResult.getFloat(offset+68),domeResult.getFloat(offset+72),1);
            if(completed!=0||installed!=0||hit!=null||sound!=null)
                columns.add(new ColumnFeedback(tick.gameTime(),tick.parameters().identity(),id,domeResult.getInt(offset+4),
                        completed,installed,hit,sound));
        }
        int outerCount=Math.min(STORM_OUTER_BATCH_LIMIT,domeResult.getInt(STORM_OUTER_BATCH_OFFSET));
        OuterFeedback outer=null;
        if(outerCount>0){
            int[] rows=new int[outerCount*2];
            for(int i=0;i<rows.length;i++)rows[i]=domeResult.getInt(STORM_OUTER_BATCH_OFFSET+16+i*Integer.BYTES);
            outer=new OuterFeedback(tick.parameters().identity(),rows);
        }
        PositionFeedback positions=null;
        if(tick.positionSnapshot()){
            int count=Math.min(STORM_POSITION_BATCH_LIMIT,domeResult.getInt(STORM_POSITION_BATCH_OFFSET));
            if(count>0){
                float[] rows=new float[count*STORM_POSITION_ROW_BYTES/Float.BYTES];
                for(int i=0;i<rows.length;i++)
                    rows[i]=domeResult.getFloat(STORM_POSITION_BATCH_OFFSET+16+i*Float.BYTES);
                positions=new PositionFeedback(tick.parameters().identity(),tick.gameTime(),rows);
            }
        }
        var snapshots=new ArrayList<SnapshotFeedback>(tick.columnCaptures().size());
        for(int capture=0;capture<tick.columnCaptures().size();capture++){
            float[] values=new float[StarfallColumnSnapshot.FLOATS];
            int base=STORM_COLUMN_CAPTURE_OFFSET+capture*StarfallColumnSnapshot.BYTES;
            for(int i=0;i<values.length;i++)values[i]=domeResult.getFloat(base+i*Float.BYTES);
            snapshots.add(new SnapshotFeedback(tick.parameters().identity(),tick.gameTime(),tick.parameters().seconds(),
                    tick.columnCaptures().get(capture).request(),values));
        }
        return new Batches(result,columns,outer,positions,snapshots);
    }
    private Hit hit(int offset){
        int target=domeResult.getInt(offset);
        return target==0?null:new Hit(target,domeResult.getFloat(offset+16),domeResult.getFloat(offset+20),
                domeResult.getFloat(offset+24),domeResult.getFloat(offset+28));
    }
    private long[] bitmap(int offset){
        int count=BITMAP_WORDS/2;
        while(count>0&&domeResult.getLong(offset+(count-1)*Long.BYTES)==0)count--;
        var words=new long[count];
        for(int i=0;i<count;i++)words[i]=domeResult.getLong(offset+i*Long.BYTES);
        return words;
    }
    DisplayLease display(long identity){
        return !retiring&&published!=null&&published.identity==identity?new DisplayLease(published):null;
    }
    private void reapDisplays(){
        while(true){
            DisplaySlot first=null;
            for(var slot:displaySlots)
                if(slot.sequence!=0&&(first==null||slot.sequence<first.sequence))first=slot;
            if(first==null||first==published||!first.complete||first.readers!=0)return;
            if(first.fence==0)first.fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);
            if(!SpatialNative.recycleStormDisplay(worker,first.index,first.sequence,first.fence))return;
            GL32.glDeleteSync(first.fence);
            first.fence=first.sequence=0;first.complete=false;
        }
    }
    private DisplaySlot freeDisplay(){
        for(var slot:displaySlots)if(slot.sequence==0)return slot;
        return null;
    }
    private void drain(){
        for(var tick=journal.awaitingReceipt();tick!=null;tick=journal.awaitingReceipt()){
            int status=SpatialNative.pollStormTick(worker,tick.sequence());
            if(status==PENDING)return;
            if(status!=COMPLETE&&status!=FAILED)throw new IllegalStateException("Unknown Storm receipt "+status);
            var capture=ClientFrameCapture.INSTANCE;
            boolean measured=capture.tracksStorm(captureEpoch,tick.sequence());
            double[] gpuTimes=null;
            if(measured&&status==COMPLETE){
                try{gpuTimes=SpatialNative.stormTickTiming(worker,tick.sequence());}
                catch(RuntimeException|LinkageError error){capture.invalidate("Native Storm timing unavailable: "+error);}
            }
            var batch=Batches.EMPTY;
            if(status==COMPLETE&&!retiring&&failure.isEmpty()){
                try{batch=feedback(tick);}
                catch(RuntimeException|LinkageError error){stop(error.toString());}
            }
            status=SpatialNative.consumeStormTick(worker,tick.sequence());
            journal.receive(tick.sequence(),status==COMPLETE);
            if(measured)capture.stormCompleted(captureEpoch,tick.sequence(),journal.committed()==tick.sequence(),gpuTimes);
            for(var slot:displaySlots)if(slot.sequence==tick.sequence()){
                slot.complete=true;
                if(status==COMPLETE&&!retiring&&failure.isEmpty()&&journal.committed()==tick.sequence())published=slot;
                break;
            }
            if(status!=COMPLETE)stop(SpatialNative.stormControlDiagnostics(worker));
            else if(journal.committed()==tick.sequence()){
                try{
                    batch.regional().forEach(feedback);
                    batch.columns().forEach(columnFeedback);
                    if(batch.outer()!=null)outerFeedback.accept(batch.outer());
                    if(batch.positions()!=null)positionFeedback.accept(batch.positions());
                    batch.snapshots().forEach(snapshotFeedback);
                }catch(RuntimeException|LinkageError error){stop(error.toString());}
            }
        }
    }
    void service(){
        if(worker==0)return;
        try{
            drain();
            reapDisplays();
            if(retiring||!failure.isEmpty())return;
            for(var tick=journal.sendable();tick!=null;tick=journal.sendable()){
                var capture=ClientFrameCapture.INSTANCE;
                boolean timed=capture.collecting();
                long cpuStart=timed?System.nanoTime():0;
                if(tick.parameters().domeAvailable()&&!domeInstalled)installDome();
                if(tick.geometry()!=null)installTerrain(tick.geometry());
                if(packet.capacity()<tick.bytes()){
                    packet.clear();
                    packet=MemoryUtil.memRealloc(packet,tick.bytes()).order(ByteOrder.LITTLE_ENDIAN);
                }
                int bytes=tick.encode(packet);
                var display=freeDisplay();
                if(!SpatialNative.submitStormTick(worker,packet,bytes,display==null?-1:display.index,timed)){
                    stop("Native Storm submission refused");return;
                }
                if(display!=null){
                    display.sequence=tick.sequence();
                    display.tick=tick.tick();
                    display.gameTime=tick.gameTime();
                    display.identity=tick.parameters().identity();
                }
                journal.sent(tick);
                if(timed)capture.stormSubmitted(captureEpoch,tick.sequence(),tick.parameters().identity(),tick.tick(),
                        display==null?-1:display.index,tick.parameters().population(),terrainCount,journal.queued(),
                        journal.inFlight(),bytes,(System.nanoTime()-cpuStart)*1e-6);
            }
        }catch(RuntimeException|LinkageError error){stop(error.toString());}
    }
    private void stop(String reason){
        journal.stop();
        if(failure.isEmpty()){
            failure=reason.isEmpty()?"Native Storm control failure":reason;
            com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.warn("[CMI particles] Native Storm stopped: {}",failure);
        }
    }
    String failure(){return failure;}
    /** Call at render boundaries until all accepted work has retired. */
    boolean retire(){
        if(worker==0&&displayBridge==0)return true;
        retiring=true;published=null;service();
        try{
            if(worker!=0){
                if(!SpatialNative.closeStormControl(worker))return false;
                worker=0;
            }
            SpatialNative.closeBuffers(displayBridge);displayBridge=0;
            MemoryUtil.memFree(packet);
            MemoryUtil.memFree(domeResult);
            if(terrainUpload!=null){MemoryUtil.memFree(terrainUpload);terrainUpload=null;}
            return true;
        }catch(RuntimeException|LinkageError error){stop(error.toString());return false;}
    }
}
