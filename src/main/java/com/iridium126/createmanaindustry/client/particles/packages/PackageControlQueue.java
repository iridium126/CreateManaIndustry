package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.util.*;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.*;

/** Client-thread control transitions only, no active-population scan. Adjacent groups preserve
 * phase/namespace order; flush at the end of the same pump without waiting for a fuller batch. */
public final class PackageControlQueue {
    public static final int MAX_PENDING=4096;
    public static final long TIMEOUT_NANOS=100_000_000;
    public record Namespace(PackageRegion region,long epoch,long revision){
        public Namespace{Objects.requireNonNull(region);if(epoch<=0||revision<=0)throw new IllegalArgumentException("Control namespace");}
    }
    /** A sparse tail retains the original single-control encoding. Sender must copy the
     * borrowed batch body before returning true; the prepared envelope survives rejection. */
    public record Prepared(Namespace namespace,int action,PackageAuthorityRegion.Baseline single,byte[] body) {}
    @FunctionalInterface public interface Sender {boolean send(Prepared message);}
    public enum Result { SENT, BLOCKED, TIMED_OUT }
    public record Stats(int pending,long sentPackets,long sentRecords,long bodyBytes,long maximumDelayNanos) {}
    private static final class Batch {
        final ArrayList<PackageAuthorityRegion.Baseline> rows=new ArrayList<>(64);
        Namespace namespace;int action;long firstNanos;Prepared prepared;
    }
    private final ArrayDeque<Batch> pending=new ArrayDeque<>(),spare=new ArrayDeque<>();
    private static final Comparator<PackageAuthorityRegion.Baseline> ORDER=Comparator.comparingInt(PackageAuthorityRegion.Baseline::index);
    private final ByteBuffer scratch=ByteBuffer.allocate(PackageControlBatchCodec.MAX_BYTES);
    private int records;
    private long packets,sent,bytes,maximumDelay;
    public void offer(Namespace namespace,int action,PackageAuthorityRegion.Baseline baseline,long now) {
        Objects.requireNonNull(namespace);Objects.requireNonNull(baseline);
        if(!PackageControlBatchCodec.supported(action)||records>=MAX_PENDING)throw new IllegalStateException("Control queue action/capacity");
        Batch tail=pending.peekLast();
        if(tail==null||tail.prepared!=null||tail.action!=action||!tail.namespace.equals(namespace)||tail.rows.size()==PackageControlBatchCodec.MAX_RECORDS) {
            tail=spare.pollFirst();if(tail==null)tail=new Batch();tail.namespace=namespace;tail.action=action;tail.firstNanos=now;pending.addLast(tail);
        }
        tail.rows.add(baseline);records++;
    }
    public Result flush(long now,Sender sender) {
        Objects.requireNonNull(sender);
        while(!pending.isEmpty()) {
            Batch batch=pending.peekFirst();
            if(now<batch.firstNanos||now-batch.firstNanos>TIMEOUT_NANOS)return Result.TIMED_OUT;
            if(batch.prepared==null) {
                if(batch.rows.size()==1)batch.prepared=new Prepared(batch.namespace,batch.action,batch.rows.getFirst(),null);
                else {
                    batch.rows.sort(ORDER);scratch.clear();
                    PackageControlBatchCodec.encode(scratch,batch.action,batch.rows);
                    batch.prepared=new Prepared(batch.namespace,batch.action,null,Arrays.copyOf(scratch.array(),scratch.position()));
                }
            }
            if(!sender.send(batch.prepared))return Result.BLOCKED;
            packets++;sent+=batch.rows.size();bytes+=batch.prepared.body()==null?0:batch.prepared.body().length;maximumDelay=Math.max(maximumDelay,now-batch.firstNanos);
            pending.removeFirst();records-=batch.rows.size();recycle(batch);
        }
        return Result.SENT;
    }
    private void recycle(Batch batch){batch.rows.clear();batch.prepared=null;batch.namespace=null;if(spare.size()<32)spare.addLast(batch);}
    /** Namespace shutdown explicitly restores Create; no individual inventory event is discarded. */
    public void clear(){while(!pending.isEmpty())recycle(pending.removeFirst());records=0;}
    public Stats stats(){return new Stats(records,packets,sent,bytes,maximumDelay);}
}
