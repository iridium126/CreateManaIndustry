package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.util.*;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.*;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ClientboundPackageObserverPacket;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ClientboundPackageObserverPacket.Visual;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.lwjgl.BufferUtils;
import net.minecraft.resources.ResourceLocation;

/** Engine-boundary wire-to-GPU consumer for up to eight regional streams in one observer domain.
 * Java maintains identity/resource transitions only, never the current merged pose. Uploads can
 * span frames without dropping a release. Native ownership is the Lifecycle adapter's separate
 * committed-admission responsibility, not a consequence of receiving/uploading a baseline. */
public final class PackageObserverGpuController implements AutoCloseable {
    public interface Lifecycle {
        /** Remaining shared body/candidate reservation, read once per input batch. */
        int availableSlots();
        boolean supports(PackageObserverFeed.Member<Visual> member);
        /** Stage hidden pool metadata; do not hide Create until committed admission confirms it. */
        void uploaded(PackageObserverFeed.Member<Visual> member,int local);
        default void uploaded(PackageRegion region,long epoch,long stream,PackageObserverFeed.Member<Visual> member,int local){uploaded(member,local);}
        /** Stage retirement under the existing engine generation rules. */
        void retired(PackageLease.Identity identity,int local);
        void namespaceRetired(PackageRegion region,long epoch,long stream);
        void fallback(String reason);
    }
    private record Arrival(ClientboundPackageObserverPacket packet,long receivedNanos,long oneWayNanos) {}
    private static final class Stream {
        final PackageRegion region;final long epoch,revision,id;
        final Int2ObjectOpenHashMap<Entry> entries=new Int2ObjectOpenHashMap<>();
        long sequence=-1;boolean complete,closed;
        Stream(ClientboundPackageObserverPacket p){region=p.region();epoch=p.epoch();revision=p.revision();id=p.stream();}
    }
    private static final class Entry {
        final Stream stream;final PackageObserverFeed.Member<Visual> member;final int local;
        long tick;boolean retired;
        Entry(Stream stream,PackageObserverFeed.Member<Visual> member,int local){this.stream=stream;this.member=member;this.local=local;tick=member.stateTick();}
    }
    private static final int MAX_PACKETS=1024,MAX_RECORDS=131072,MAX_REGIONS=8;
    private final Thread owner=Thread.currentThread();
    private final ResourceLocation dimension;
    private final Set<PackageRegion> subscribed;
    private final PackageObserverGpu gpu;
    private final PackageObserverFeedbackGpu feedback;
    private final PackageObserverClock clock=new PackageObserverClock();
    private final Lifecycle lifecycle;
    private final double ox,oy,oz;
    private final ArrayDeque<Arrival> queue=new ArrayDeque<>();
    private final Map<PackageRegion,Stream> streams=new HashMap<>();
    private final Map<PackageLease.Identity,Entry> identities=new HashMap<>();
    private final Map<Integer,Entry> localEntries=new HashMap<>();
    private final TreeSet<Integer> reusable=new TreeSet<>();
    private final ByteBuffer full=BufferUtils.createByteBuffer(ClientboundPackageObserverPacket.MAX_RECORDS*128);
    private final ByteBuffer compact=BufferUtils.createByteBuffer(ClientboundPackageObserverPacket.MAX_RECORDS*PackageObserverGpu.COMPACT_BYTES);
    private final Entry[] additions=new Entry[ClientboundPackageObserverPacket.MAX_RECORDS],changes=new Entry[ClientboundPackageObserverPacket.MAX_RECORDS];
    private final Int2ObjectOpenHashMap<Entry> overlay=new Int2ObjectOpenHashMap<>();
    private final Set<PackageLease.Identity> addingIdentities=new HashSet<>();
    private Arrival pending;private Stream pendingStream;
    private int queuedRecords,introduced,slots,fullCount,compactCount,phase;
    private long operation,lastCommitted=-1;
    private String failure;
    private boolean feedbackRejected;
    private final java.util.function.Consumer<PackageObserverFeedbackGpu.Status> consumeFeedback=status->{if(status.needsFallback())feedbackRejected=true;};
    private boolean closed;
    public PackageObserverGpuController(ResourceLocation dimension,Set<PackageRegion> subscribed,PackageObserverGpu gpu,
            long resourceEpoch,double ox,double oy,double oz,Lifecycle lifecycle) {
        this.dimension=Objects.requireNonNull(dimension);this.subscribed=Set.copyOf(subscribed);
        if(this.subscribed.size()>MAX_REGIONS || !Double.isFinite(ox) || !Double.isFinite(oy) || !Double.isFinite(oz))
            throw new IllegalArgumentException("Observer controller scope/origin");
        this.gpu=Objects.requireNonNull(gpu);this.lifecycle=Objects.requireNonNull(lifecycle);
        if(gpu.count()!=0)throw new IllegalArgumentException("Observer controller needs a fresh custom-protocol domain");
        this.ox=ox;this.oy=oy;this.oz=oz;feedback=new PackageObserverFeedbackGpu(resourceEpoch);
    }
    /** Network/main-thread receipt; no GL calls or world scanning. Unknown dimensions/regions are
     * ignored. Extra processing latency counts from this receipt, separately from network lag. */
    public boolean enqueue(ClientboundPackageObserverPacket packet,long receivedNanos,long oneWayNanos) {
        open();Objects.requireNonNull(packet);
        if(failure!=null || !dimension.equals(packet.dimension()) || !subscribed.contains(packet.region()))return false;
        int records=packet.baselines().size()+packet.changes().size();
        if(queue.size()>=MAX_PACKETS || records>MAX_RECORDS-queuedRecords){fail("Observer input queue exhausted");return false;}
        if(oneWayNanos<0 || oneWayNanos>5_000_000_000L)throw new IllegalArgumentException("Observer estimated network latency");
        queue.addLast(new Arrival(packet,receivedNanos,oneWayNanos));queuedRecords+=records;return true;
    }
    /** GL engine boundary, before mixed publication/pool staging. The budget counts packets,
     * including idle/stale records; no busy-wait or synchronous query of GPU result buffers. */
    public int prepare(long now,int packetBudget) {
        open();if(packetBudget<0 || packetBudget>MAX_PACKETS)throw new IllegalArgumentException("Observer packet budget");
        if(failure!=null)return 0;
        try {
            feedbackRejected=false;feedback.poll(consumeFeedback);
            if(feedbackRejected){fail("Observer GPU rejected/expired presentation");return 0;}
            int consumed=0;
            while(consumed<packetBudget && !queue.isEmpty()) {
                var arrival=queue.getFirst();
                if(now-arrival.receivedNanos()<0){fail("Observer monotonic clock reversed");return consumed;}
                if(pending==null && !stage(arrival,now)){removeHead();consumed++;continue;}
                float time=clock.now(now);
                if(phase==0) {
                    if(fullCount>0 && !gpu.tryApply(full,fullCount,slots,time))break;
                    phase=1;
                }
                if(compactCount>0 && !gpu.tryApplyCompact(compact,compactCount,slots,time))break;
                finish();removeHead();consumed++;
            }
            if(clock.initialized())gpu.sample(clock.now(now));
            return consumed;
        }catch(RuntimeException error){fail("Observer controller failed: "+error.getMessage());return 0;}
    }
    private boolean stage(Arrival arrival,long now) {
        var p=arrival.packet();var stream=streams.get(p.region());boolean reset=(p.flags()&p.RESET)!=0;
        if(stream!=null && p.stream()<stream.id)return false;
        if((p.flags()&p.CLOSE)!=0) {
            if(stream==null || stream.closed || p.stream()!=stream.id || p.epoch()!=stream.epoch || p.revision()!=stream.revision)return false;
            clock.observe(p.serverTick(),arrival.receivedNanos(),arrival.oneWayNanos());
            retire(stream,clock.now(now));return false;
        }
        if(stream!=null && p.stream()==stream.id && (stream.closed || p.sequence()<=stream.sequence))return false;
        if(reset) {
            if(stream!=null && p.stream()<=stream.id)throw new IllegalArgumentException("Observer RESET stream reused");
            clock.observe(p.serverTick(),arrival.receivedNanos(),arrival.oneWayNanos());
            if(stream!=null)retire(stream,clock.now(now));
            stream=new Stream(p);streams.put(p.region(),stream);
        }else {
            if(stream==null || p.stream()!=stream.id || p.epoch()!=stream.epoch || p.revision()!=stream.revision
                    || p.sequence()!=stream.sequence+1 || stream.complete && (p.flags()&p.COMPLETE)==0)
                throw new IllegalArgumentException("Observer envelope gap/namespace mismatch");
            clock.observe(p.serverTick(),arrival.receivedNanos(),arrival.oneWayNanos());
        }
        if(p.epoch()!=stream.epoch || p.revision()!=stream.revision)throw new IllegalArgumentException("Observer authority changed inside stream");
        if(identities.size()+p.baselines().size()>MAX_RECORDS)throw new IllegalArgumentException("Observer identity namespace exhausted; renew domain");
        pending=arrival;pendingStream=stream;phase=0;fullCount=compactCount=0;slots=gpu.count();
        overlay.clear();addingIdentities.clear();full.clear();compact.clear();
        long baselineOperation=Math.incrementExact(operation);operation=baselineOperation;
        int remaining=Math.min(availableLocalSlots(),Math.max(0,lifecycle.availableSlots()));
        for(int i=0;i<p.baselines().size();i++) {
            var member=p.baselines().get(i);var old=identities.get(member.identity());
            if(stream.entries.containsKey(member.index()) || overlay.containsKey(member.index()) || !addingIdentities.add(member.identity())
                    || old!=null && !old.retired && !old.stream.closed)
                throw new IllegalArgumentException("Observer duplicate introduction/identity");
            var visual=member.metadata();
            boolean supported=remaining>0 && visual.width()<=2 && visual.height()<=2 && lifecycle.supports(member);
            int local=supported?(reusable.isEmpty()?slots++:reusable.pollFirst()):-1;
            var entry=new Entry(stream,member,local);additions[i]=entry;overlay.put(member.index(),entry);
            if(supported) {
                remaining--;
                full.position(fullCount*128);PackageObserverPatch.baseline(full,entry.local,member.index(),member.identity(),p.epoch(),p.stream(),baselineOperation,
                        member.state(),(float)(p.region().originX()-ox),(float)(p.region().originY()-oy),(float)(p.region().originZ()-oz),
                        visual.width(),visual.height(),clock.receipt(member.stateTick()));fullCount++;
            }
        }
        long deltaOperation=Math.incrementExact(operation);operation=deltaOperation;
        for(int i=0;i<p.changes().size();i++) {
            var change=p.changes().get(i);var entry=overlay.get(change.id());if(entry==null)entry=stream.entries.get(change.id());
            if(entry==null || entry.retired || p.stateTicks().get(i)<entry.tick)throw new IllegalArgumentException("Observer unknown/retired/backdated delta");
            changes[i]=entry;
            if(entry.local>=0) {
                compact.position(compactCount*PackageObserverGpu.COMPACT_BYTES);PackageObserverPatch.compactDelta(compact,entry.local,p.epoch(),p.stream(),deltaOperation,
                        change,clock.receipt(p.stateTicks().get(i)));compactCount++;
            }
        }
        full.position(0).limit(fullCount*128);compact.position(0).limit(compactCount*PackageObserverGpu.COMPACT_BYTES);return true;
    }
    private void finish() {
        var p=pending.packet();var stream=pendingStream;
        for(int i=0;i<p.baselines().size();i++) {
            var entry=additions[i];stream.entries.put(entry.member.index(),entry);identities.put(entry.member.identity(),entry);
            introduced++;if(entry.local>=0)localEntries.put(entry.local,entry);if(entry.local>=0)lifecycle.uploaded(stream.region,stream.epoch,stream.id,entry.member,entry.local);additions[i]=null;
        }
        for(int i=0;i<p.changes().size();i++) {
            var entry=changes[i];entry.tick=p.stateTicks().get(i);
            if(p.changes().get(i).mask()==PackageDeltaCodec.RELEASE){entry.retired=true;identities.remove(entry.member.identity(),entry);
                if(entry.local>=0)lifecycle.retired(entry.member.identity(),entry.local);}
            changes[i]=null;
        }
        stream.sequence=p.sequence();stream.complete=(p.flags()&p.COMPLETE)!=0;pending=null;pendingStream=null;
        overlay.clear();addingIdentities.clear();
    }
    private void retire(Stream stream,float time) {
        gpu.retireNamespace(stream.epoch,stream.id,time);stream.closed=true;
        lifecycle.namespaceRetired(stream.region,stream.epoch,stream.id);
    }
    private void removeHead(){var packet=queue.removeFirst().packet();queuedRecords-=packet.baselines().size()+packet.changes().size();}
    /** Only after the COMPLETE common particle/draw submission commits; failed frames do not
     * capture feedback or grant native render ownership. Full feedback rings retry next commit. */
    public void committed(long generation) {
        open();if(generation<0 || generation<lastCommitted)throw new IllegalArgumentException("Observer commit generation");
        if(generation==lastCommitted || failure!=null || !clock.initialized())return;
        lastCommitted=generation;feedback.capture(gpu);
    }
    public void failedFrame(){open();fail("Observer frame failed; restore native ownership");}
    private void fail(String reason){if(failure==null){failure=reason;lifecycle.fallback(reason);}}
    public boolean healthy(){return !closed && failure==null;}
    public String failure(){return failure;}
    public int queued(){open();return queue.size();}
    public long stream(PackageRegion region){open();var s=streams.get(region);return s==null||s.closed?0:s.id;}
    public void suspend(PackageRegion region,long now){open();var s=streams.get(region);if(s!=null&&!s.closed)retire(s,clock.now(now));}
    public int availableLocalSlots(){open();return gpu.capacity()-gpu.count()+reusable.size();}
    public void recycle(int local){
        open();var entry=localEntries.get(local);if(entry==null||!entry.retired&&!entry.stream.closed)throw new IllegalArgumentException("Live observer cannot be recycled");
        gpu.reclaimSlot(local);localEntries.remove(local);identities.remove(entry.member.identity(),entry);entry.stream.entries.remove(entry.member.index(),entry);reusable.add(local);
    }
    public int pendingFeedback(){open();return feedback.pending();}
    private void open(){if(closed || Thread.currentThread()!=owner)throw new IllegalStateException("Observer controller closed/off owner thread");}
    @Override public void close(){if(closed)return;open();closed=true;feedback.close();queue.clear();streams.clear();identities.clear();localEntries.clear();reusable.clear();
        overlay.clear();addingIdentities.clear();Arrays.fill(additions,null);Arrays.fill(changes,null);pending=null;pendingStream=null;}
}
