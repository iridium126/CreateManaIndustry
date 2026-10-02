package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/**
 * Incremental owner-thread capture, immutable worker input, versioned publication.
 * The source must invalidate a section whenever a captured shape or its context changes.
 * A single capture call cannot be preempted: budget overruns are measured, never hidden.
 */
public final class PackageCollisionCache {
    public static final long DEFAULT_BUDGET_NANOS = 10_000_000;
    public static final int BLOCKS = 4096;
    public static final int UNSUPPORTED=8,MAX_GPU_SHAPES=16384;
    public interface Listener {
        default void invalidated(Section section,long revision) {}
        default void published(Section section,Snapshot snapshot) {}
        default void removed(Section section) {}
        default void cleared() {}
    }
    public record Section(int x, int y, int z) {}
    public record Box(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        public Box {
            if (!Float.isFinite(minX) || !Float.isFinite(minY) || !Float.isFinite(minZ)
                    || !Float.isFinite(maxX) || !Float.isFinite(maxY) || !Float.isFinite(maxZ)
                    || minX > maxX || minY > maxY || minZ > maxZ)
                throw new IllegalArgumentException("Invalid collision box");
        }
    }
    public record Cell(java.util.List<Box> shapes, float friction, int flags) {
        public Cell {
            shapes = java.util.List.copyOf(shapes);
            if(!Float.isFinite(friction) || friction<0)throw new IllegalArgumentException("Invalid friction");
        }
    }
    @FunctionalInterface public interface Source {
        /** Owner-thread only. null means unavailable/unsupported; never interpreted as air. */
        Cell capture(Section section, int localIndex);
    }
    public static final class Snapshot {
        private final long revision;
        private final long bakeNanos;
        private final int[] offsets, flags;
        private final float[] boxes, friction;
        private final java.nio.ByteBuffer gpuCells,gpuBoxes;
        private final int gpuShapeCount;
        private final boolean gpuEmpty;
        private Snapshot(long revision, int[] offsets, int[] flags, float[] boxes, float[] friction,
                         java.nio.ByteBuffer gpuCells,java.nio.ByteBuffer gpuBoxes,int gpuShapeCount,boolean gpuEmpty,long bakeNanos) {
            this.revision=revision; this.offsets=offsets; this.flags=flags; this.boxes=boxes; this.friction=friction;
            this.gpuCells=gpuCells;this.gpuBoxes=gpuBoxes;this.gpuShapeCount=gpuShapeCount;
            this.gpuEmpty=gpuEmpty;
            this.bakeNanos=bakeNanos;
        }
        public long revision() { return revision; }
        public long bakeNanos(){return bakeNanos;}
        public int shapeStart(int cell) { return offsets[cell]; }
        public int shapeEnd(int cell) { return offsets[cell+1]; }
        public float coordinate(int box, int component) { return boxes[box*6+component]; }
        public float friction(int cell) { return friction[cell]; }
        public int flags(int cell) { return flags[cell]; }
        public int gpuShapeCount(){return gpuShapeCount;}
        /** Only a fully captured, flag-free empty section can use the GPU coarse fast path. */
        public boolean gpuEmpty(){return gpuEmpty;}
        public java.nio.ByteBuffer gpuCells(){return gpuCells.asReadOnlyBuffer().order(java.nio.ByteOrder.nativeOrder());}
        public java.nio.ByteBuffer gpuBoxes(){return gpuBoxes.asReadOnlyBuffer().order(java.nio.ByteOrder.nativeOrder());}
    }
    private static final class Work {
        long revision;
        int cursor;
        Cell[] cells;
        Snapshot published;
        CompletableFuture<Snapshot> future;
        boolean queued;
    }
    private final Thread owner = Thread.currentThread();
    private final LinkedHashMap<Section, Work> sections = new LinkedHashMap<>(16,.75f,true);
    // Constant-time invalidation/eviction even during a large block update burst.
    private final LinkedHashMap<Section,Work> pending = new LinkedHashMap<>();
    private final Map<Long,Set<Section>> columns = new HashMap<>();
    private final Set<Section> packageUsage=new HashSet<>();
    private final Executor executor;
    private final LongSupplier clock;
    private final int maxSections;
    private final java.util.concurrent.atomic.AtomicInteger workers=new java.util.concurrent.atomic.AtomicInteger();
    private long revision, lastCaptureNanos, overrunCount,capacityEvictions,capacityRejections;
    private final long[] captureTimes=new long[128];
    private final long[] bakeTimes=new long[128];
    private int captureSamples,captureCursor;
    private int bakeSamples,bakeCursor;
    private Listener listener=new Listener(){};

    public PackageCollisionCache(Executor executor, int maxSections) { this(executor,maxSections,System::nanoTime); }
    public PackageCollisionCache(Executor executor, int maxSections, LongSupplier clock) {
        if (maxSections <= 0) throw new IllegalArgumentException("Section capacity");
        this.executor=java.util.Objects.requireNonNull(executor);
        this.clock=java.util.Objects.requireNonNull(clock); this.maxSections=maxSections;
    }
    private void owner() {
        if (Thread.currentThread()!=owner) throw new IllegalStateException("Collision cache accessed off owner thread");
    }
    public void listener(Listener listener){owner();this.listener=java.util.Objects.requireNonNull(listener);}
    public void forEachReady(java.util.function.BiConsumer<Section,Snapshot> consumer) {
        owner();sections.forEach((section,work)->{if(work.published!=null)consumer.accept(section,work.published);});
    }
    public boolean request(Section section) {
        owner();return request(section,false);
    }
    /** Demand from a package path may evict the least recently used immutable section. */
    public boolean requestDemand(Section section) {
        owner();return request(section,true);
    }
    private boolean request(Section section,boolean evictForDemand) {
        Work existing=sections.get(section);if(existing!=null)return true;
        if(sections.size()>=maxSections) {
            if(!evictForDemand){capacityRejections++;return false;}
            var iterator=sections.entrySet().iterator();Map.Entry<Section,Work> victim=null;
            while(iterator.hasNext()) {var candidate=iterator.next();if(!packageUsage.contains(candidate.getKey())){victim=candidate;iterator.remove();break;}}
            if(victim==null){capacityRejections++;return false;}
            removeRetired(victim.getKey());capacityEvictions++;
        }
        Work work=new Work(); sections.put(section,work);
        columns.computeIfAbsent(column(section.x,section.z),k->new HashSet<>()).add(section);
        invalidate(section); return true;
    }
    /** Invalidation revokes coverage immediately, including an in-flight capture. */
    public void invalidate(Section section) {
        owner(); Work work=sections.get(section); if(work==null)return;
        // Multiple edits before any new capture share one invalidation. An obsolete worker
        // must still finish, but can never publish into the replacement revision.
        if(work.revision!=0 && work.cursor==0 && work.cells==null && work.published==null && work.future==null && work.queued)return;
        work.revision=++revision; work.cursor=0; work.cells=null; work.published=null;
        listener.invalidated(section,work.revision);
        // Keep an obsolete worker in flight until it completes: repeated edits cannot flood the executor.
        enqueue(section,work);
    }
    /** Revoke both sides of section boundaries for neighbour-dependent vanilla shapes. */
    public void invalidateBlock(int x,int y,int z) {
        owner();
        int sx=x>>4,sy=y>>4,sz=z>>4;
        int x0=sx-((x&15)==0?1:0),x1=sx+((x&15)==15?1:0);
        int y0=sy-((y&15)==0?1:0),y1=sy+((y&15)==15?1:0);
        int z0=sz-((z&15)==0?1:0),z1=sz+((z&15)==15?1:0);
        for(int cx=x0;cx<=x1;cx++)for(int cy=y0;cy<=y1;cy++)for(int cz=z0;cz<=z1;cz++)invalidate(new Section(cx,cy,cz));
    }
    /** Packet replacement/unload changes context in the eight adjacent chunk columns too. */
    public void invalidateChunk(int x,int z) {
        owner();
        for(int cx=x-1;cx<=x+1;cx++)for(int cz=z-1;cz<=z+1;cz++) {
            Set<Section> affected=columns.get(column(cx,cz));
            if(affected!=null)for(Section section:affected)invalidate(section);
        }
    }
    public void evict(Section section) {
        owner();if(sections.remove(section)==null)return;removeRetired(section);
    }
    private void removeRetired(Section section) {
        pending.remove(section);packageUsage.remove(section);
        long key=column(section.x,section.z);Set<Section> set=columns.get(key);
        if(set!=null){set.remove(section);if(set.isEmpty())columns.remove(key);}
        listener.removed(section);
    }
    public void clear() { owner(); sections.clear(); pending.clear(); columns.clear();packageUsage.clear(); ++revision;listener.cleared(); }
    public void clearPackageUsage(){owner();packageUsage.clear();}
    public void protectPackageUsage(Section section){owner();if(sections.containsKey(section))packageUsage.add(section);}
    public Snapshot snapshot(Section section) { owner(); Work w=sections.get(section); return w==null?null:w.published; }
    public int size(){owner();return sections.size();}
    public long capacityEvictions(){owner();return capacityEvictions;}
    public long capacityRejections(){owner();return capacityRejections;}
    public int readyCount(){owner();int count=0;for(Work work:sections.values())if(work.published!=null)count++;return count;}
    private static long column(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}

    /** Poll and capture share the same budget; never joins an unfinished worker. */
    public void tick(Source source, long budgetNanos) {
        owner(); if(budgetNanos<=0)return;
        long start=clock.getAsLong();
        int attempts=pending.size();
        while(attempts-- > 0 && !pending.isEmpty() && clock.getAsLong()-start<budgetNanos) {
            var iterator=pending.entrySet().iterator();var queued=iterator.next();iterator.remove();
            Section section=queued.getKey();Work w=queued.getValue();
            w.queued=false;
            if(w.future!=null) {
                if(w.future.isDone()) {
                    if(!w.future.isCompletedExceptionally() && !w.future.isCancelled()) {
                        Snapshot result=w.future.getNow(null);
                        if(result!=null && result.revision==w.revision){
                            w.published=result;bakeTimes[bakeCursor]=result.bakeNanos;bakeCursor=(bakeCursor+1)%bakeTimes.length;
                            bakeSamples=Math.min(bakeSamples+1,bakeTimes.length);listener.published(section,result);
                        }
                    }
                    w.future=null;
                }
                if(w.published!=null)continue;
                if(w.future!=null) { enqueue(section,w); continue; }
            }
            if(w.cells==null)w.cells=new Cell[BLOCKS];
            // Small batches keep one hot section from monopolizing the capture budget.
            int before=w.cursor;
            int end=Math.min(BLOCKS,w.cursor+32);
            while(w.cursor<end && clock.getAsLong()-start<budgetNanos) {
                Cell cell;
                try { cell=source.capture(section,w.cursor); }
                catch(RuntimeException failure) { enqueue(section,w);throw failure; }
                if(cell==null)break;
                w.cells[w.cursor++]=cell;
            }
            boolean progressed=w.cursor>before;
            if(w.cursor==BLOCKS) {
                if(workers.get()<4) {
                    Cell[] captured=w.cells; long version=w.revision;
                    var accounting=workers;
                    workers.incrementAndGet();
                    try {
                        w.future=CompletableFuture.supplyAsync(() -> {
                            try { return pack(version,captured); } finally { accounting.decrementAndGet(); }
                        },executor);
                        w.cells=null; w.cursor=0;
                    } catch(java.util.concurrent.RejectedExecutionException rejected) { workers.decrementAndGet(); }
                }
            }
            enqueue(section,w);
            // Round-robin another batch only while capture makes progress and time remains.
            if(progressed)attempts++;
        }
        lastCaptureNanos=clock.getAsLong()-start;
        captureTimes[captureCursor]=lastCaptureNanos;captureCursor=(captureCursor+1)%captureTimes.length;
        captureSamples=Math.min(captureSamples+1,captureTimes.length);
        if(lastCaptureNanos>budgetNanos)overrunCount++;
    }
    private void enqueue(Section s,Work w) { if(!w.queued){pending.put(s,w);w.queued=true;} }
    private static Snapshot pack(long revision,Cell[] cells) {
        long begin=System.nanoTime();
        int[] offsets=new int[BLOCKS+1],flags=new int[BLOCKS]; float[] friction=new float[BLOCKS];
        int count=0,combinedFlags=0;
        for(int i=0;i<BLOCKS;i++){offsets[i]=count;count=Math.addExact(count,cells[i].shapes.size());combinedFlags|=cells[i].flags;}
        offsets[BLOCKS]=count; float[] boxes=new float[Math.multiplyExact(count,6)]; int p=0;
        for(int i=0;i<BLOCKS;i++) {
            Cell c=cells[i]; friction[i]=c.friction;flags[i]=c.flags;
            int x=i&15,y=i>>>8,z=(i>>>4)&15;
            for(Box b:c.shapes) {
                boxes[p++]=x+b.minX;boxes[p++]=y+b.minY;boxes[p++]=z+b.minZ;
                boxes[p++]=x+b.maxX;boxes[p++]=y+b.maxY;boxes[p++]=z+b.maxZ;
            }
        }
        // Identical local shape lists share geometry. Translation happens in the shader;
        // friction/flags remain per cell. Only immutable primitive buffers leave this worker.
        record Range(int first,int count) {}
        var dictionary=new HashMap<java.util.List<Box>,Range>();
        var unique=new java.util.ArrayList<Box>();
        var gpuCells=java.nio.ByteBuffer.allocateDirect(BLOCKS*16).order(java.nio.ByteOrder.nativeOrder());
        boolean oversized=false;
        for(int i=0;i<BLOCKS;i++) {
            Cell cell=cells[i];int cellFlags=cell.flags;
            for(Box box:cell.shapes)if(box.minX< -1 || box.minY< -1 || box.minZ< -1
                    || box.maxX>2 || box.maxY>2 || box.maxZ>2){oversized=true;break;}
            if(oversized)break;
            Range range=null;
            if((cellFlags&UNSUPPORTED)==0) {
                range=dictionary.get(cell.shapes);
                if(range==null) {
                    if(unique.size()+cell.shapes.size()>MAX_GPU_SHAPES){oversized=true;break;}
                    range=new Range(unique.size(),cell.shapes.size());dictionary.put(cell.shapes,range);unique.addAll(cell.shapes);
                }
            }
            gpuCells.putInt(range==null?0:range.first).putInt(range==null?0:range.count).putFloat(cell.friction).putInt(cellFlags);
        }
        gpuCells.flip();
        var gpuBoxes=java.nio.ByteBuffer.allocateDirect(oversized?0:unique.size()*32).order(java.nio.ByteOrder.nativeOrder());
        if(!oversized)for(Box box:unique)gpuBoxes.putFloat(box.minX).putFloat(box.minY).putFloat(box.minZ).putFloat(0)
                .putFloat(box.maxX).putFloat(box.maxY).putFloat(box.maxZ).putFloat(0);
        gpuBoxes.flip();
        return new Snapshot(revision,offsets,flags,boxes,friction,gpuCells,gpuBoxes,oversized?-1:unique.size(),count==0 && combinedFlags==0,System.nanoTime()-begin);
    }
    public long lastCaptureNanos(){return lastCaptureNanos;}
    public long overrunCount(){return overrunCount;}
    /** Diagnostic query only; the capture tick does not allocate or sort timing samples. */
    public long capturePercentile(double percentile) {
        return percentile(captureTimes,captureSamples,percentile);
    }
    public long bakePercentile(double percentile){return percentile(bakeTimes,bakeSamples,percentile);}
    private long percentile(long[] values,int count,double percentile) {
        owner();if(!(percentile>0 && percentile<=1))throw new IllegalArgumentException("Capture percentile");
        if(count==0)return 0;
        var sorted=java.util.Arrays.copyOf(values,count);java.util.Arrays.sort(sorted);
        return sorted[(int)Math.ceil(percentile*count)-1];
    }
}
