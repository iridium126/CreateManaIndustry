package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Consumer;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.BufferUtils;

/** Engine consumer of native entity events. Java stores immutable identity/model transitions,
 * never a merged pose. Native packets remain responsible for the retained interaction entity;
 * GPU ownership is published only after matching committed admission and valid pose feedback.
 * The pool already reserves active packages before ordinary particles; metadata count is
 * bounded by pool capacity, so confirmed active members cannot lose slots to ordinary emission. */
public final class PackageNativeObserverController implements AutoCloseable {
    public record Baseline(int entityId,UUID uuid,ResourceLocation model,float width,float height,int light,
                           double x,double y,double z,double vx,double vy,double vz,float yaw,boolean ground) {}
    public interface Lifecycle {
        /** Must recheck the current world, UUID and eligibility before hiding a native renderer. */
        boolean admitted(Baseline baseline,long epoch,long visualId,long generation,int slotPlusOne);
        void released(Baseline baseline,long epoch,long visualId,long generation);
        void closed(long epoch);
        void failed(String reason);
    }
    public enum Observation { EXISTING,INTRODUCED,IGNORED }
    private enum Phase { BASELINE,HIDDEN,VISIBLE,ACTIVE,RETIRE,RETIRED,CLOSED }
    private static final class Entry {
        final Baseline baseline;final int local;final long id,generation;final float receipt;
        int candidate=-1;long sequence,capturedPublication;Phase phase=Phase.BASELINE,capturedPhase;
        boolean captured;
        Entry(Baseline baseline,int local,long epoch,float receipt){this.baseline=baseline;this.local=local;id=Long.MAX_VALUE-local;generation=epoch;this.receipt=receipt;}
    }
    private static final int BATCH=1024,ADMISSIONS=32768;
    private final Thread owner=Thread.currentThread();
    private final long epoch,start=System.nanoTime();
    private final PackageMixedPhysicsGpu physics;
    private final PackageObserverGpu gpu;
    private final PackagePoolGpu pool;
    private final Map<ResourceLocation,PackageModelCache.Style> styles;
    private final Lifecycle lifecycle;
    private final PackageNativeObserverCommands commands;
    private final PackageAdmissionTracker admissions;
    private final PackageObserverFeedbackGpu feedback;
    private final Int2ObjectOpenHashMap<Entry> entities=new Int2ObjectOpenHashMap<>(),candidates=new Int2ObjectOpenHashMap<>();
    private final ArrayDeque<Entry> introducing=new ArrayDeque<>(),retiring=new ArrayDeque<>();
    private final LinkedHashSet<Entry> awaiting=new LinkedHashSet<>();
    private final ArrayDeque<PackageAdmissionTracker.Outcome> confirmed=new ArrayDeque<>();
    private final ArrayList<Entry> captureEntries=new ArrayList<>(ADMISSIONS);
    private final ArrayList<PackageAdmissionTracker.Expected> expected=new ArrayList<>(ADMISSIONS);
    private final ByteBuffer full=BufferUtils.createByteBuffer(BATCH*128),metadata=BufferUtils.createByteBuffer(BATCH*80),raw=BufferUtils.createByteBuffer(64);
    private final PackageNativeObserverCommands.Submit submit;
    private final Consumer<PackageObserverFeedbackGpu.Status> checkFeedback=status->{if(status.needsFallback())fail("Native package GPU rejected/expired state");};
    private int reserved,active;
    private long lastCommit=-1,operation;
    private String failure;
    private boolean closed;
    public PackageNativeObserverController(PackageMixedPhysicsGpu physics,PackagePoolGpu pool,Map<ResourceLocation,PackageModelCache.Style> styles,long epoch,Lifecycle lifecycle) {
        this.physics=Objects.requireNonNull(physics);this.pool=Objects.requireNonNull(pool);this.styles=Map.copyOf(styles);this.lifecycle=Objects.requireNonNull(lifecycle);
        this.epoch=epoch;gpu=physics.observers();
        submit=(data,n,slots,time)->gpu.tryApplyNative(data,n,slots,time,true);
        if(epoch<=0||!gpu.nativePackets()||gpu.count()!=0)throw new IllegalArgumentException("Fresh native observer domain required");
        commands=new PackageNativeObserverCommands(gpu.capacity(),gpu.capacity()*4,epoch);
        // Own only staging resources, never the shared domain/pool. No completed fence is waited on.
        PackageAdmissionTracker a=null;PackageObserverFeedbackGpu f=null;
        try{a=new PackageAdmissionTracker(ADMISSIONS,epoch);f=new PackageObserverFeedbackGpu(epoch);}
        catch(RuntimeException failed){if(a!=null)a.close();if(f!=null)f.close();throw failed;}
        admissions=a;feedback=f;
    }
    private float time(long now){if(now<start||now-start>3_600_000_000_000L)throw new IllegalStateException("Native observer clock/window");return (now-start)*1e-9f;}
    /** Snapshot AFTER a native handler: introducing its resulting baseline consumes that first
     * packet already; only EXISTING members may enqueue the same handler's raw command. */
    public Observation observe(Baseline baseline,long now) {
        open();if(failure!=null)return Observation.IGNORED;
        Objects.requireNonNull(baseline);Objects.requireNonNull(baseline.uuid());Objects.requireNonNull(baseline.model());
        var previous=entities.get(baseline.entityId());
        if(previous!=null && previous.baseline.uuid().equals(baseline.uuid()) && previous.phase!=Phase.CLOSED) {
            if(previous.baseline.model().equals(baseline.model())&&previous.baseline.width()==baseline.width()&&previous.baseline.height()==baseline.height())return Observation.EXISTING;
            retire(previous);
        } else if(previous!=null)retire(previous);
        if(!styles.containsKey(baseline.model())||baseline.width()<=0||baseline.height()<=0||baseline.width()>2||baseline.height()>2
                ||reserved>=gpu.count()+physics.observerRemaining()||pool.metadataCount()+reserved-gpu.count()>=Math.min(pool.capacity(),131072))return Observation.IGNORED;
        var entry=new Entry(baseline,reserved++,epoch,time(now));entities.put(baseline.entityId(),entry);introducing.addLast(entry);return Observation.INTRODUCED;
    }
    /** Cheap identity lookup; UUID reuse cannot address the previous member. */
    public boolean tracked(int entityId,UUID uuid){open();var e=entities.get(entityId);return e!=null&&e.baseline.uuid().equals(uuid)&&e.phase!=Phase.RETIRE&&e.phase!=Phase.RETIRED&&e.phase!=Phase.CLOSED;}
    private Entry member(int entityId,UUID uuid){var e=entities.get(entityId);return e!=null&&e.baseline.uuid().equals(uuid)&&e.phase!=Phase.RETIRE&&e.phase!=Phase.RETIRED&&e.phase!=Phase.CLOSED?e:null;}
    public boolean move(int id,UUID uuid,short dx,short dy,short dz,boolean position,boolean rotation,byte yaw,boolean ground,long now) {
        open();var e=member(id,uuid);if(e==null)return false;raw.clear();PackageNativeObserverPatch.move(raw,e.local,id,epoch,++e.sequence,dx,dy,dz,position,rotation,yaw,ground,time(now));enqueue();return true;
    }
    public boolean teleport(int id,UUID uuid,double x,double y,double z,byte yaw,boolean ground,long now) {
        open();var e=member(id,uuid);if(e==null)return false;raw.clear();PackageNativeObserverPatch.teleport(raw,e.local,id,epoch,++e.sequence,x,y,z,yaw,ground,time(now));enqueue();return true;
    }
    public boolean motion(int id,UUID uuid,int vx,int vy,int vz,long now) {
        open();var e=member(id,uuid);if(e==null)return false;raw.clear();PackageNativeObserverPatch.motion(raw,e.local,id,epoch,++e.sequence,vx,vy,vz,time(now));enqueue();return true;
    }
    private void enqueue(){raw.flip();if(!commands.offer(raw))fail("Native package command inbox exhausted");}
    /** Removal uses the current native stream's entity-ID mapping; packets have TCP order. */
    public void remove(int id){open();var e=entities.get(id);if(e!=null)retire(e);}
    public void remove(int id,UUID uuid){open();var e=member(id,uuid);if(e!=null)retire(e);}
    private void retire(Entry e) {
        if(e.phase==Phase.RETIRE||e.phase==Phase.RETIRED||e.phase==Phase.CLOSED)return;
        boolean owned=e.phase==Phase.ACTIVE;if(owned)active--;
        raw.clear();PackageNativeObserverPatch.release(raw,e.local,e.baseline.entityId(),epoch,++e.sequence,time(System.nanoTime()));enqueue();
        e.phase=Phase.RETIRE;if(owned)retiring.addFirst(e);else retiring.addLast(e);
        entities.remove(e.baseline.entityId(),e);
    }
    /** All GL work is inside the engine boundary. At most one bounded baseline batch per call. */
    public void prepare(long now) {
        open();if(failure!=null)return;
        feedback.poll(checkFeedback);admissions.poll(confirmed::addLast);
        if(feedback.overdue(now))fail("Native package feedback exceeded budget");
        if(failure!=null)return;
        while(!confirmed.isEmpty()) {
            var e=candidates.get(confirmed.getFirst().candidate());var health=feedback.latest();
            if(health==null||health.publication()<e.capturedPublication)break;
            confirm(confirmed.removeFirst());
        }
        full.clear();metadata.clear();int added=Math.min(BATCH,introducing.size());
        if(added>physics.observerRemaining()||added>Math.min(pool.capacity(),131072)-pool.metadataCount()){fail("Native package shared reservation exhausted");return;}
        if(added>0) {
            int n=0;for(var e:introducing) {
                if(n++==added)break;var b=e.baseline;
                PackageNativeObserverPatch.baseline(full,e.local,b.entityId(),e.id,e.generation,epoch,0,b.x(),b.y(),b.z(),b.vx(),b.vy(),b.vz(),b.width(),b.height(),b.yaw(),b.ground(),e.receipt);
            }
            full.flip();
            if(gpu.tryApplyNative(full,added,gpu.count()+added,time(now),false)) {
                int first=pool.metadataCount();
                for(int i=0;i<added;i++) {
                    var e=introducing.removeFirst();e.candidate=first+i;encodeMetadata(e);candidates.put(e.candidate,e);
                    if(e.phase==Phase.BASELINE)e.phase=Phase.HIDDEN;awaiting.add(e);
                }
                metadata.flip();pool.appendMetadata(metadata,added);
            }
        }
        commands.drain(submit,gpu.count(),time(now),4);
        for(int i=0,n=Math.min(BATCH,retiring.size());i<n;i++) {
            var e=retiring.removeFirst();if(e.candidate<0){retiring.addLast(e);continue;}
            pool.setHidden(e.candidate,true);e.phase=Phase.RETIRED;awaiting.add(e);
        }
        physics.sampleObservers(time(now));
    }
    private void encodeMetadata(Entry e) {
        int p=metadata.position();for(int j=0;j<80;j+=8)metadata.putLong(p+j,0);
        var b=e.baseline;var style=styles.get(b.model());
        metadata.putLong(p,e.id).putLong(p+8,e.generation).putInt(p+16,physics.observerBodyIndex(e.local))
            .putInt(p+20,style.box()).putInt(p+24,PackagePoolGpu.NO_MESH).putInt(p+28,PackagePoolGpu.HIDDEN)
            .putInt(p+60,b.light()&0x00ffffff).putFloat(p+76,b.height()*.85f);metadata.position(p+80);
    }
    /** Never called for a failed engine frame. Pose health must be captured before admission. */
    public void committed(long generation) {
        open();if(failure!=null||generation==lastCommit)return;if(generation<lastCommit)throw new IllegalArgumentException("Native commit generation");lastCommit=generation;
        var origin=gpu.nativeOrigin();
        if(!pool.sourceMatches(physics.bodyBuffer(),physics.chainBuffer(),physics.historyBuffer(),physics.bodyCount(),(float)origin.x(),(float)origin.y(),(float)origin.z()))
            throw new IllegalStateException("Native admission uses a different physics publication");
        if(!feedback.capture(gpu))return;
        captureEntries.clear();expected.clear();
        for(var e:awaiting)if(!e.captured) {
            if(!captureEntries.isEmpty()&&e.candidate!=captureEntries.getLast().candidate+1){if(!capture(generation))return;captureEntries.clear();expected.clear();}
            captureEntries.add(e);expected.add(new PackageAdmissionTracker.Expected(e.id,e.generation,e.phase==Phase.VISIBLE?0:PackagePoolGpu.HIDDEN));
            if(captureEntries.size()==ADMISSIONS){if(!capture(generation))return;captureEntries.clear();expected.clear();}
        }
        if(!captureEntries.isEmpty())capture(generation);
    }
    private boolean capture(long generation) {
        // AdmissionTracker's submission is the engine generation; multiple noncontiguous
        // fragments need separate monotonically increasing ring sequences. Keep one fragment
        // per generation, so pose health and visibility cannot be paired with another frame.
        if(operation==generation+1)return false;
        if(!admissions.submit(pool,captureEntries.getFirst().candidate,expected,generation))return false;
        operation=generation+1;for(var e:captureEntries){e.captured=true;e.capturedPhase=e.phase;e.capturedPublication=gpu.publicationVersion();}return true;
    }
    private void confirm(PackageAdmissionTracker.Outcome outcome) {
        var e=candidates.get(outcome.candidate());
        if(e==null||e.id!=outcome.id()||e.generation!=outcome.generation())throw new IllegalStateException("Native admission identity mismatch");
        e.captured=false;if(e.phase!=e.capturedPhase)return;
        switch(e.phase) {
            case HIDDEN -> {if(!outcome.accepted()){retire(e);return;}pool.setHidden(e.candidate,false);e.phase=Phase.VISIBLE;}
            case VISIBLE -> {
                if(!outcome.accepted()||!lifecycle.admitted(e.baseline,epoch,e.id,e.generation,outcome.slotPlusOne())){retire(e);return;}
                e.phase=Phase.ACTIVE;active++;awaiting.remove(e);
            }
            case RETIRED -> {e.phase=Phase.CLOSED;awaiting.remove(e);lifecycle.released(e.baseline,epoch,e.id,e.generation);}
            default -> throw new IllegalStateException("Unexpected native admission phase "+e.phase);
        }
    }
    public int active(){open();return active;}
    public String failure(){open();return failure;}
    private void fail(String reason){if(failure==null){failure=reason;lifecycle.failed(reason);}}
    private void open(){if(closed||Thread.currentThread()!=owner)throw new IllegalStateException("Native observer off client/engine thread or closed");}
    @Override public void close(){if(closed)return;open();closed=true;try{lifecycle.closed(epoch);}finally{admissions.close();feedback.close();
        entities.clear();candidates.clear();introducing.clear();retiring.clear();awaiting.clear();confirmed.clear();active=0;}}
}
