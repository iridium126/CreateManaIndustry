package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.*;
import java.util.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/** Immutable local BVHs and four nonblocking pose banks per structure. No world access or readback. */
public final class PackageMovingCollisionGpu implements AutoCloseable {
    public static final int POSE_BYTES=256,MAX_STRUCTURES=64;
    private static final int MAP_FLAGS=GL30.GL_MAP_WRITE_BIT|GL44.GL_MAP_PERSISTENT_BIT|GL44.GL_MAP_COHERENT_BIT;
    private static final class Geometry {int buffer,size,copied;ByteBuffer mapped,source;long revision;}
    private static final class Bank {int buffer;ByteBuffer mapped;long fence;Geometry geometry;boolean leased;}
    private static final class Entry {final Bank[] banks=new Bank[4];Geometry visible,pending;long revision;}
    private final Thread owner=Thread.currentThread();
    private final java.util.function.LongToIntFunction poll;
    private final Map<Integer,Entry> entries=new LinkedHashMap<>();private final List<Geometry> retired=new ArrayList<>();
    private int empty;private boolean closed,viewOpen;
    private long uploadedBytes,skippedViews;
    public PackageMovingCollisionGpu(){this(fence->GL32.glClientWaitSync(fence,GL32.GL_SYNC_FLUSH_COMMANDS_BIT,0));}
    /** Injected completion observation is used by validation to hold all four banks busy. */
    public PackageMovingCollisionGpu(java.util.function.LongToIntFunction poll){this.poll=Objects.requireNonNull(poll);empty=GL15.glGenBuffers();if(empty==0)throw new IllegalStateException("Moving fallback buffer unavailable");GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,empty);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,48,GL15.GL_DYNAMIC_DRAW);if(GL32.glGetBufferParameteri64(GL43.GL_SHADER_STORAGE_BUFFER,GL15.GL_BUFFER_SIZE)!=48){GL15.glDeleteBuffers(empty);throw new IllegalStateException("Moving fallback storage unavailable");}}
    private static ByteBuffer map(int id,int bytes){if(id==0||bytes>GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE))throw new IllegalStateException("Moving storage/device limit");GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);GL44.glBufferStorage(GL43.GL_SHADER_STORAGE_BUFFER,bytes,MAP_FLAGS);if(GL32.glGetBufferParameteri64(GL43.GL_SHADER_STORAGE_BUFFER,GL15.GL_BUFFER_SIZE)!=bytes)throw new IllegalStateException("Moving storage unavailable");var mapped=GL30.glMapBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,0,bytes,MAP_FLAGS);if(mapped==null)throw new IllegalStateException("Moving mapping unavailable");return mapped.order(ByteOrder.nativeOrder());}
    private void owner(){if(Thread.currentThread()!=owner||closed)throw new IllegalStateException("Moving GPU atlas unavailable/off owner thread");}
    private Entry entry(int identity) {
        Entry e=entries.get(identity);if(e!=null)return e;
        if(entries.size()==MAX_STRUCTURES)throw new IllegalStateException("Moving structure capacity");
        e=new Entry();
        try{for(int i=0;i<4;i++){Bank b=new Bank();e.banks[i]=b;b.buffer=GL15.glGenBuffers();b.mapped=map(b.buffer,POSE_BYTES);}}
        catch(RuntimeException failure){for(Bank b:e.banks)if(b!=null&&b.buffer!=0)GL15.glDeleteBuffers(b.buffer);throw failure;}
        entries.put(identity,e);return e;
    }
    public void sync(Collection<PackageMovingCollisionCache.Entry> captured) {
        owner();if(viewOpen)throw new IllegalStateException("Moving sync during view");
        var seen=new HashSet<Integer>();
        for(var source:captured) {
            seen.add(source.identity);Entry e=entry(source.identity);var snapshot=source.snapshot();
            if(e.revision==source.revision()&&((snapshot!=null&&(e.visible!=null||e.pending!=null))||(snapshot==null&&e.visible==null&&e.pending==null)))continue;
            if(e.visible!=null){retired.add(e.visible);e.visible=null;}
            if(e.pending!=null){dispose(e.pending);e.pending=null;}e.revision=source.revision();
            if(snapshot!=null) {
                Geometry g=new Geometry();g.size=snapshot.count()*PackageMovingGeometry.NODE_BYTES;g.revision=snapshot.revision();g.source=snapshot.nodes();
                try{g.buffer=GL15.glGenBuffers();g.mapped=map(g.buffer,Math.max(48,g.size));e.pending=g;}
                catch(RuntimeException failure){dispose(g);throw failure;}
            }
        }
        // Removing a source retires its geometry; pose banks stay until fences complete.
        for(var row:entries.entrySet())if(!seen.contains(row.getKey())) {
            Entry e=row.getValue();if(e.visible!=null){retired.add(e.visible);e.visible=null;}if(e.pending!=null){dispose(e.pending);e.pending=null;}e.revision=Long.MIN_VALUE;
        }
        collect();
        entries.entrySet().removeIf(row->{Entry e=row.getValue();if(seen.contains(row.getKey()))return false;for(Bank b:e.banks)if(b.fence!=0||b.leased)return false;for(Bank b:e.banks)GL15.glDeleteBuffers(b.buffer);return true;});
    }
    private void collect() {
        for(Entry e:entries.values())for(Bank b:e.banks)if(b.fence!=0) {
            int result=poll.applyAsInt(b.fence);
            if(result==GL32.GL_WAIT_FAILED)throw new IllegalStateException("Moving fence failed");
            if(result!=GL32.GL_TIMEOUT_EXPIRED){GL32.glDeleteSync(b.fence);b.fence=0;b.geometry=null;}
        }
        retired.removeIf(g->{for(Entry e:entries.values())for(Bank b:e.banks)if((b.fence!=0||b.leased)&&b.geometry==g)return false;dispose(g);return true;});
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
            if(g.copied==g.size){g.source=null;e.visible=g;e.pending=null;}
        }
    }
    public List<View> views(Collection<PackageMovingCollisionCache.Entry> captured,boolean posesReady,double ox,double oy,double oz) {
        if(!Double.isFinite(ox)||!Double.isFinite(oy)||!Double.isFinite(oz))throw new IllegalArgumentException("Moving origin");
        owner();if(viewOpen)throw new IllegalStateException("Nested moving scene");collect();var result=new ArrayList<View>();
        for(var source:captured) {
            Entry e=entries.get(source.identity);Bank bank=null;
            if(e!=null)for(Bank candidate:e.banks)if(candidate.fence==0&&!candidate.leased){bank=candidate;break;}
            boolean ready=posesReady&&source.previous!=null&&source.current!=null&&source.bounds!=null;
            if(bank==null){skippedViews++;result.add(new View(null,false));continue;}
            ByteBuffer p=bank.mapped;p.clear();for(int i=0;i<POSE_BYTES;i+=4)p.putInt(i,0);p.position(0);
            if(ready)try {
                source.previous.put(p,ox,oy,oz);source.current.put(p,ox,oy,oz);var b=source.bounds;
                p.putFloat((float)b.x0()).putFloat((float)b.y0()).putFloat((float)b.z0()).putInt(source.identity);
                p.putFloat((float)b.x1()).putFloat((float)b.y1()).putFloat((float)b.z1()).putInt(e.visible!=null&&e.revision==source.revision()?1:0);
                p.putInt(60*4,e.visible==null?0:e.visible.size/PackageMovingGeometry.NODE_BYTES);
            }catch(IllegalArgumentException invalidPose){ready=false;}
            bank.geometry=e.visible;bank.leased=true;result.add(new View(bank,ready,source));
        }
        viewOpen=true;return result;
    }
    public void endViews(List<View> views){owner();for(View view:views)view.close();viewOpen=false;}
    public boolean covered(Collection<PackageMovingCollisionCache.Entry> captured){owner();for(var source:captured){Entry e=entries.get(source.identity);if(e==null||e.visible==null||e.revision!=source.revision()||source.snapshot()==null||source.poseFrame==0)return false;}return true;}
    /** Unknown structure enumeration must revoke physics even if the known scene is empty. */
    public List<View> unavailableViews(){owner();if(viewOpen)throw new IllegalStateException("Nested moving scene");viewOpen=true;return java.util.List.of(new View(null,false));}
    public final class View implements AutoCloseable {
        final Bank bank;final boolean available;boolean ended;final PackageMovingCollisionCache.Entry source;final long revision,frame;
        boolean submitted;
        View(Bank bank,boolean available){this(bank,available,null);}
        View(Bank bank,boolean available,PackageMovingCollisionCache.Entry source){this.bank=bank;this.available=available;this.source=source;revision=source==null?0:source.revision();frame=source==null?0:source.poseFrame;}
        public void claimStep(PackagePhysicsGpu physics){owner();if(ended||submitted)throw new IllegalStateException("Moving view already submitted");submitted=true;if(available&&source!=null&&revision==source.revision()&&frame==source.poseFrame)physics.claimMovingFrame(source,frame);}
        public void bind(int readyLocation){owner();if(ended)throw new IllegalStateException("Moving view ended");GL42.glMemoryBarrier(GL44.GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT);
            try(var stack=MemoryStack.stackPush()){GL44.glBindBuffersBase(GL43.GL_SHADER_STORAGE_BUFFER,9,stack.ints(bank==null?empty:bank.buffer,bank==null||bank.geometry==null?empty:bank.geometry.buffer));}
            GL20.glUniform1i(readyLocation,available&&(source==null||(revision==source.revision()&&frame==source.poseFrame))?1:0);
        }
        @Override public void close(){owner();if(ended)return;ended=true;if(bank!=null){long fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);if(fence==0)throw new IllegalStateException("Moving fence unavailable");bank.leased=false;bank.fence=fence;}}
    }
    public long uploadedBytes(){return uploadedBytes;}public long skippedViews(){return skippedViews;}
    private static void dispose(Geometry g){if(g!=null&&g.buffer!=0){GL15.glDeleteBuffers(g.buffer);g.buffer=0;}}
    @Override public void close(){if(closed)return;owner();for(Entry e:entries.values()){for(Bank b:e.banks){if(b.fence!=0)GL32.glDeleteSync(b.fence);GL15.glDeleteBuffers(b.buffer);}dispose(e.visible);dispose(e.pending);}for(Geometry g:retired)dispose(g);GL15.glDeleteBuffers(empty);closed=true;}
}
