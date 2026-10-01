package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.Objects;
import java.util.function.LongSupplier;

/** One deferred native input. Query completion is not an inventory acknowledgement. All Minecraft
 * callbacks run later outside the engine GL boundary, and an input is never replayed twice. */
public final class PackageFreePickQueue {
    public static final long PROCESSING_NANOS=100_000_000;
    public enum Action { USE, ATTACK }
    public enum Phase { EMPTY, QUEUED, QUERY, READY, TIMED_OUT }
    public record Input(long epoch,long sequence,Action action,PackagePoseQueryGpu.Ray ray,Object context,long createdNanos) {}
    private final long epoch;
    private final LongSupplier clock;
    private long sequence=1;
    private Input input;
    private PackagePoseQueryGpu.Result result;
    private Phase phase=Phase.EMPTY;
    public PackageFreePickQueue(long epoch){this(epoch,System::nanoTime);}
    public PackageFreePickQueue(long epoch,LongSupplier clock){
        if(epoch<=0)throw new IllegalArgumentException("Free pick epoch");this.epoch=epoch;this.clock=Objects.requireNonNull(clock);
    }
    public boolean enqueue(Action action,PackagePoseQueryGpu.Ray ray,Object context) {
        expire();if(phase!=Phase.EMPTY)return false;
        if(sequence==Long.MAX_VALUE)throw new IllegalStateException("Free pick sequence exhausted");
        input=new Input(epoch,sequence++,Objects.requireNonNull(action),Objects.requireNonNull(ray),Objects.requireNonNull(context),clock.getAsLong());
        phase=Phase.QUEUED;return true;
    }
    public Input queued(){expire();return phase==Phase.QUEUED?input:null;}
    public boolean submitted(Input captured){expire();if(phase!=Phase.QUEUED||captured!=input)return false;phase=Phase.QUERY;return true;}
    public boolean completed(PackagePoseQueryGpu.Completed completed) {
        expire();if(phase!=Phase.QUERY||completed.kind()!=PackagePoseQueryGpu.Kind.PICK||completed.tag()!=input)return false;
        if(completed.results().size()!=1)throw new IllegalStateException("Free pick result length");
        var next=completed.results().getFirst();
        if(next.present()&&(next.chain()||(next.flags()!=0&&next.flags()!=PackagePoolGpu.HANDBACKABLE)||!Float.isFinite(next.state())||next.state()<0
                ||!Float.isFinite(next.before())||next.before()<0||next.before()>=1
                ||!Float.isFinite(next.progress())||next.progress()<0||next.progress()>1))throw new IllegalStateException("Free pick result domain/lifecycle/fraction");
        result=next;phase=Phase.READY;return true;
    }
    public Phase phase(){expire();return phase;}
    public Input input(){return input;}
    public PackagePoseQueryGpu.Result result(){expire();return phase==Phase.READY?result:null;}
    /** The caller takes the operation before invoking Minecraft; reentrant input cannot replay it. */
    public void clear(){phase=Phase.EMPTY;input=null;result=null;}
    private void expire(){if(input!=null&&phase!=Phase.TIMED_OUT&&clock.getAsLong()-input.createdNanos()>PROCESSING_NANOS)phase=Phase.TIMED_OUT;}
}
