package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.Objects;
import java.util.function.LongSupplier;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageChainInteraction;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ClientboundChainInteractionPacket;

/** One serial user operation, independent of GL and Minecraft callbacks. Input/query work is
 * bounded by two ticks; time waiting for the network ACK is measured separately. */
public final class PackageChainUseQueue {
    public static final long PROCESSING_NANOS=100_000_000,NETWORK_TIMEOUT_NANOS=5_000_000_000L;
    public enum Phase { EMPTY,QUEUED,QUERY,RESULT,REPLAY,SENT,TIMED_OUT }
    public record Use(long transaction,long epoch,PackagePoseQueryGpu.Ray ray,Object context,long createdNanos) {}
    private final long epoch;
    private final LongSupplier clock;
    private Phase phase=Phase.EMPTY;
    private Use use;
    private PackagePoseQueryGpu.Result result;
    private PackageChainInteraction sent;
    private long nextTransaction=1,sentAt,latestNetworkNanos;
    public PackageChainUseQueue(long epoch){this(epoch,System::nanoTime);}
    public PackageChainUseQueue(long epoch,LongSupplier clock){if(epoch<=0)throw new IllegalArgumentException("Chain use epoch");this.epoch=epoch;this.clock=Objects.requireNonNull(clock);}
    public boolean enqueue(PackagePoseQueryGpu.Ray ray,Object context) {
        if(phase!=Phase.EMPTY)return false;
        if(nextTransaction==Long.MAX_VALUE)throw new IllegalStateException("Chain use transaction exhausted");
        use=new Use(nextTransaction++,epoch,Objects.requireNonNull(ray),Objects.requireNonNull(context),clock.getAsLong());phase=Phase.QUEUED;return true;
    }
    public Use queued(){expire();return phase==Phase.QUEUED?use:null;}
    public boolean submitted(Use snapshot){expire();if(phase!=Phase.QUEUED || snapshot!=use)return false;phase=Phase.QUERY;return true;}
    public boolean completed(PackagePoseQueryGpu.Completed completed) {
        expire();if(phase!=Phase.QUERY || completed.kind()!=PackagePoseQueryGpu.Kind.PICK || completed.tag()!=use)return false;
        if(completed.results().size()!=1)throw new IllegalStateException("Chain use result length");
        result=completed.results().getFirst();phase=result.present()?Phase.RESULT:Phase.REPLAY;return true;
    }
    public PackagePoseQueryGpu.Result result(){expire();return phase==Phase.RESULT?result:null;}
    public void sent(PackageChainInteraction request) {
        expire();if(phase!=Phase.RESULT || request.epoch()!=epoch || request.transaction()!=use.transaction
                || request.identity().id()!=result.id() || request.identity().generation()!=result.generation()
                || request.track()!=result.track() || Float.floatToIntBits(request.progress())!=Float.floatToIntBits(result.progress()))
            throw new IllegalStateException("Chain use sent another operation");
        sent=request;sentAt=clock.getAsLong();phase=Phase.SENT;
    }
    public boolean acknowledge(ClientboundChainInteractionPacket packet) {
        if(phase!=Phase.SENT || packet.epoch()!=epoch || packet.transaction()!=sent.transaction() || !packet.identity().equals(sent.identity()))return false;
        latestNetworkNanos=Math.max(0,clock.getAsLong()-sentAt);clear();return true;
    }
    public void replay(){if(phase!=Phase.RESULT)throw new IllegalStateException("Chain use replay phase");phase=Phase.REPLAY;}
    public Phase phase(){expire();return phase;}
    public Use use(){return use;}
    public long latestNetworkNanos(){return latestNetworkNanos;}
    public boolean sentToServer(){return sent!=null;}
    private void expire() {
        if(use==null || phase==Phase.EMPTY || phase==Phase.TIMED_OUT)return;
        long now=clock.getAsLong();
        if(phase==Phase.SENT?now-sentAt>NETWORK_TIMEOUT_NANOS:now-use.createdNanos>PROCESSING_NANOS)phase=Phase.TIMED_OUT;
    }
    public void clear(){phase=Phase.EMPTY;use=null;result=null;sent=null;sentAt=0;}
}
