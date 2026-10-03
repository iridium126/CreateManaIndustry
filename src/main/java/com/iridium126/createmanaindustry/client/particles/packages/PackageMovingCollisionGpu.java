package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.*;
import java.util.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/** Immutable local BVHs and nonblocking pose banks per structure. No world access or readback. */
public final class PackageMovingCollisionGpu implements AutoCloseable {
    public static final int POSE_BYTES=256,MAX_STRUCTURES=64;
    private static final int MAP_FLAGS=GL30.GL_MAP_WRITE_BIT|GL44.GL_MAP_PERSISTENT_BIT|GL44.GL_MAP_COHERENT_BIT;
    private static final class Geometry {int buffer,size,copied,identity;ByteBuffer mapped,source;long revision;PackageMovingGeometry.Bounds bounds;}
    private static final class Bank {int buffer;ByteBuffer mapped;long fence;Geometry geometry;boolean leased;}
    private static final class Entry {Bank[] banks=new Bank[0];Geometry visible,pending;long revision;PackageMovingGeometry.Bounds visibleBounds;}
    private final Thread owner=Thread.currentThread();
    private final java.util.function.LongToIntFunction poll;
    private final Map<Integer,Entry> entries=new LinkedHashMap<>();private final List<Geometry> retired=new ArrayList<>();
    private Set<PackageMovingCollisionCache.GeometryRevision> retainedHistory=Set.of();
    private Set<Integer> retainedHistoryIdentities=Set.of();
    private int empty,bankCount=4;private boolean closed,viewOpen;
    private long uploadedBytes,skippedViews;
    public PackageMovingCollisionGpu(){this(fence->GL32.glClientWaitSync(fence,GL32.GL_SYNC_FLUSH_COMMANDS_BIT,0));}
    /** Injected completion observation is used by validation to hold all four banks busy. */
    public PackageMovingCollisionGpu(java.util.function.LongToIntFunction poll){this.poll=Objects.requireNonNull(poll);empty=GL15.glGenBuffers();if(empty==0)throw new IllegalStateException("Moving fallback buffer unavailable");GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,empty);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,48,GL15.GL_DYNAMIC_DRAW);if(GL32.glGetBufferParameteri64(GL43.GL_SHADER_STORAGE_BUFFER,GL15.GL_BUFFER_SIZE)!=48){GL15.glDeleteBuffers(empty);throw new IllegalStateException("Moving fallback storage unavailable");}}
    private static ByteBuffer map(int id,int bytes){if(id==0||bytes>GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE))throw new IllegalStateException("Moving storage/device limit");GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);GL44.glBufferStorage(GL43.GL_SHADER_STORAGE_BUFFER,bytes,MAP_FLAGS);if(GL32.glGetBufferParameteri64(GL43.GL_SHADER_STORAGE_BUFFER,GL15.GL_BUFFER_SIZE)!=bytes)throw new IllegalStateException("Moving storage unavailable");var mapped=GL30.glMapBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,0,bytes,MAP_FLAGS);if(mapped==null)throw new IllegalStateException("Moving mapping unavailable");return mapped.order(ByteOrder.nativeOrder());}
    private void owner(){if(Thread.currentThread()!=owner||closed)throw new IllegalStateException("Moving GPU atlas unavailable/off owner thread");}
    public void tickRate(double rate){
        owner();if(viewOpen)throw new IllegalStateException("Moving bank resize during view");
        bankCount=Math.max(bankCount,com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageTickTiming.stepsPerFrame(rate));
        for(Entry entry:entries.values())grow(entry);
    }
    private void grow(Entry entry){
        int oldCount=entry.banks.length;if(oldCount>=bankCount)return;
        Bank[] next=Arrays.copyOf(entry.banks,bankCount);
        try{for(int i=oldCount;i<bankCount;i++){Bank bank=new Bank();next[i]=bank;bank.buffer=GL15.glGenBuffers();bank.mapped=map(bank.buffer,POSE_BYTES);}}
        catch(RuntimeException failure){for(int i=oldCount;i<next.length;i++)if(next[i]!=null&&next[i].buffer!=0)GL15.glDeleteBuffers(next[i].buffer);throw failure;}
        entry.banks=next;
    }
    private Entry entry(int identity) {
        Entry e=entries.get(identity);if(e!=null)return e;
        if(entries.size()==MAX_STRUCTURES)throw new IllegalStateException("Moving structure capacity");
        e=new Entry();
        try{grow(e);}
        catch(RuntimeException failure){for(Bank b:e.banks)if(b!=null&&b.buffer!=0)GL15.glDeleteBuffers(b.buffer);throw failure;}
        entries.put(identity,e);return e;
    }
    public void sync(Collection<PackageMovingCollisionCache.Entry> captured) {
        sync(captured,Set.of());
    }
    public void sync(Collection<PackageMovingCollisionCache.Entry> captured,Set<PackageMovingCollisionCache.GeometryRevision> retained) {
        owner();if(viewOpen)throw new IllegalStateException("Moving sync during view");
        retainedHistory=Set.copyOf(retained);
        var retainedIds=new HashSet<Integer>();for(var ref:retainedHistory)retainedIds.add(ref.identity());
        retainedHistoryIdentities=Set.copyOf(retainedIds);
        var seen=new HashSet<Integer>();
        for(var source:captured) {
            seen.add(source.identity);Entry e=entry(source.identity);var snapshot=source.snapshot();
            boolean changed=e.revision!=source.revision();
            if(changed) {
                // A dirty source is being recaptured. Keep its last complete geometry live;
                // replacing it only after the new BVH has finished uploading prevents a
                // transient hole in the moving-world collision scene.
                if(e.pending!=null){dispose(e.pending);e.pending=null;}
                e.revision=source.revision();
            }
            if(source.unsupported()) {
                if(e.visible!=null){retired.add(e.visible);e.visible=null;e.visibleBounds=null;}
                if(e.pending!=null){dispose(e.pending);e.pending=null;}
                continue;
            }
            // Geometry coordinates are relative to the source's captured bounds origin. If
            // that frame changed, the old BVH cannot be safely paired with the new pose.
            if(e.visible!=null && e.visibleBounds!=null && !e.visibleBounds.equals(source.bounds)) {
                retired.add(e.visible);e.visible=null;e.visibleBounds=null;
            }
            if(snapshot!=null && (e.visible==null || e.visible.revision!=snapshot.revision())
                && (e.pending==null || e.pending.revision!=snapshot.revision())) {
                if(e.pending!=null){dispose(e.pending);e.pending=null;}
                Geometry g=new Geometry();g.identity=source.identity;g.size=snapshot.count()*PackageMovingGeometry.NODE_BYTES;g.revision=snapshot.revision();g.bounds=source.bounds;g.source=snapshot.nodes();
                try{g.buffer=GL15.glGenBuffers();g.mapped=map(g.buffer,Math.max(48,g.size));e.pending=g;}
                catch(RuntimeException failure){dispose(g);throw failure;}
            }
        }
        // Removing a source retires its geometry; pose banks stay until fences complete.
        for(var row:entries.entrySet())if(!seen.contains(row.getKey())) {
            Entry e=row.getValue();
            if(retainedIdentity(row.getKey()))continue;
            if(e.visible!=null){retired.add(e.visible);e.visible=null;e.visibleBounds=null;}if(e.pending!=null){dispose(e.pending);e.pending=null;}e.revision=Long.MIN_VALUE;
        }
        collect();
        entries.entrySet().removeIf(row->{Entry e=row.getValue();if(seen.contains(row.getKey())||retainedIdentity(row.getKey()))return false;for(Bank b:e.banks)if(b.fence!=0||b.leased)return false;for(Bank b:e.banks)GL15.glDeleteBuffers(b.buffer);return true;});
    }
    private boolean retainedIdentity(int identity){return retainedHistoryIdentities.contains(identity);}
    private Geometry geometry(Entry entry,PackageMovingCollisionCache.Entry source){
        if(entry!=null&&entry.visible!=null&&matches(entry.visible,source))return entry.visible;
        for(Geometry candidate:retired)if(matches(candidate,source))return candidate;
        return null;
    }
    private static boolean matches(Geometry geometry,PackageMovingCollisionCache.Entry source){
        return geometry.identity==source.identity&&geometry.revision==source.revision()
                &&Objects.equals(geometry.bounds,source.bounds);
    }
    private void collect() {
        for(Entry e:entries.values())for(Bank b:e.banks)if(b.fence!=0) {
            int result=poll.applyAsInt(b.fence);
            if(result==GL32.GL_WAIT_FAILED)throw new IllegalStateException("Moving fence failed");
            if(result!=GL32.GL_TIMEOUT_EXPIRED){GL32.glDeleteSync(b.fence);b.fence=0;b.geometry=null;}
        }
        retired.removeIf(g->{if(retainedHistory.contains(new PackageMovingCollisionCache.GeometryRevision(g.identity,g.revision)))return false;for(Entry e:entries.values())for(Bank b:e.banks)if((b.fence!=0||b.leased)&&b.geometry==g)return false;dispose(g);return true;});
    }
    /** Persistent copies are split into <=16KiB slices; incomplete geometry is never ready. */
    public void pump(int maximumBytes,long budgetNanos) {
        owner();if(viewOpen)throw new IllegalStateException("Moving upload during view");collect();long start=System.nanoTime();int copied=0;
        for(Entry e:entries.values()) {
            Geometry g=e.pending;if(g==null)continue;
            while(g.copied<g.size&&copied<maximumBytes&&System.nanoTime()-start<budgetNanos) {
                int n=Math.min(16384,Math.min(g.size-g.copied,maximumBytes-copied));g.source.position(g.copied).limit(g.copied+n);
                g.mapped.position(g.copied);g.mapped.put(g.source);g.copied+=n;copied+=n;uploadedBytes+=n;
            }
            if(g.copied==g.size){g.source=null;if(e.visible!=null)retired.add(e.visible);e.visible=g;e.visibleBounds=g.bounds;e.pending=null;}
        }
    }
    public List<View> views(Collection<PackageMovingCollisionCache.Entry> captured,boolean posesReady,double ox,double oy,double oz) {
        if(!Double.isFinite(ox)||!Double.isFinite(oy)||!Double.isFinite(oz))throw new IllegalArgumentException("Moving origin");
        owner();if(viewOpen)throw new IllegalStateException("Nested moving scene");collect();var result=new ArrayList<View>();
        for(var source:captured) {
            Entry e=entries.get(source.identity);Geometry geometry=geometry(e,source);Bank bank=null;
            if(e!=null)for(Bank candidate:e.banks)if(candidate.fence==0&&!candidate.leased){bank=candidate;break;}
            boolean poseReady=posesReady&&source.previous!=null&&source.current!=null&&source.bounds!=null;
            if(bank==null){skippedViews++;result.add(new View(null,false));continue;}
            ByteBuffer p=bank.mapped;p.clear();for(int i=0;i<POSE_BYTES;i+=4)p.putInt(i,0);p.position(0);
            if(poseReady)try {
                source.previous.put(p,ox,oy,oz);source.current.put(p,ox,oy,oz);var b=source.bounds;
                // moving_prepare.comp consumes these as source-local bounds, then
                // transforms them with the same render-relative pose as the bodies.
                // Supplying world bounds here would transform them a second time and
                // falsely classify distant bodies as touching an unuploaded source.
                p.putFloat((float)b.x0()).putFloat((float)b.y0()).putFloat((float)b.z0()).putInt(source.identity);
                p.putFloat((float)b.x1()).putFloat((float)b.y1()).putFloat((float)b.z1()).putInt(geometry==null?0:1);
                p.putInt(60*4,geometry==null?0:geometry.size/PackageMovingGeometry.NODE_BYTES);
            }catch(IllegalArgumentException invalidPose){poseReady=false;}
            bank.geometry=geometry;bank.leased=true;result.add(new View(bank,poseReady,source));
        }
        viewOpen=true;return result;
    }
    public void endViews(List<View> views){owner();for(View view:views)view.close();viewOpen=false;}
    public boolean covered(Collection<PackageMovingCollisionCache.Entry> captured){owner();for(var source:captured)if(!geometryCovered(source))return false;return true;}
    /** An unavailable distant BVH must not prevent unrelated packages from acquiring GPU authority. */
    public boolean covered(Collection<PackageMovingCollisionCache.Entry> captured,net.minecraft.world.phys.AABB sweptBounds){
        owner();for(var source:captured){
            if(source.bounds==null||source.previous==null||source.current==null||source.poseFrame==0)return false;
            if(!PackageMovingCollisionCoverage.intersects(source.bounds,source.previous,source.current,sweptBounds))continue;
            if(!geometryCovered(source))return false;
        }return true;
    }
    private boolean geometryCovered(PackageMovingCollisionCache.Entry source){
        Entry e=entries.get(source.identity);return e!=null&&e.visible!=null&&!source.unsupported()&&source.poseFrame!=0
                &&Objects.equals(e.visibleBounds,source.bounds);
    }
    /** Unknown structure enumeration must revoke physics even if the known scene is empty. */
    public List<View> unavailableViews(){owner();if(viewOpen)throw new IllegalStateException("Nested moving scene");viewOpen=true;return java.util.List.of(new View(null,false));}
    public final class View implements AutoCloseable {
        final Bank bank;final boolean poseAvailable;boolean ended;final PackageMovingCollisionCache.Entry source;final long revision,frame;
        boolean submitted;
        View(Bank bank,boolean poseAvailable){this(bank,poseAvailable,null);}
        View(Bank bank,boolean poseAvailable,PackageMovingCollisionCache.Entry source){this.bank=bank;this.poseAvailable=poseAvailable;this.source=source;revision=source==null?0:source.revision();frame=source==null?0:source.poseFrame;}
        /** Readiness is pose coverage; an unavailable BVH is resolved per package after coarse bounds. */
        public boolean ready(){owner();return !ended&&poseAvailable&&(source==null||revision==source.revision()&&frame==source.poseFrame);}
        public void claimStep(PackagePhysicsGpu physics){owner();if(ended||submitted)throw new IllegalStateException("Moving view already submitted");submitted=true;if(poseAvailable&&source!=null&&revision==source.revision()&&frame==source.poseFrame)physics.claimMovingFrame(source,frame);}
        public void bind(int readyLocation){owner();if(ended)throw new IllegalStateException("Moving view ended");GL42.glMemoryBarrier(GL44.GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT);
            try(var stack=MemoryStack.stackPush()){GL44.glBindBuffersBase(GL43.GL_SHADER_STORAGE_BUFFER,9,stack.ints(bank==null?empty:bank.buffer,bank==null||bank.geometry==null?empty:bank.geometry.buffer));}
            GL20.glUniform1i(readyLocation,poseAvailable&&(source==null||(revision==source.revision()&&frame==source.poseFrame))?1:0);
        }
        @Override public void close(){owner();if(ended)return;ended=true;if(bank!=null){long fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);if(fence==0)throw new IllegalStateException("Moving fence unavailable");bank.leased=false;bank.fence=fence;}}
    }
    public long uploadedBytes(){return uploadedBytes;}public long skippedViews(){return skippedViews;}
    private static void dispose(Geometry g){if(g!=null&&g.buffer!=0){GL15.glDeleteBuffers(g.buffer);g.buffer=0;}}
    @Override public void close(){if(closed)return;owner();for(Entry e:entries.values()){for(Bank b:e.banks){if(b.fence!=0)GL32.glDeleteSync(b.fence);GL15.glDeleteBuffers(b.buffer);}dispose(e.visible);dispose(e.pending);}for(Geometry g:retired)dispose(g);GL15.glDeleteBuffers(empty);closed=true;}
}
