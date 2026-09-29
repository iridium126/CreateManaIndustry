package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/**
 * Incremental owner-thread capture, immutable worker input, versioned publication.
 * The source must invalidate a section whenever a captured shape or its context changes.
 * A single capture call cannot be preempted: budget overruns are measured, never hidden.
 */
public final class PackageCollisionCache {
    public static final long DEFAULT_BUDGET_NANOS = 250_000;
    public static final int BLOCKS = 4096;
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
        private final int[] offsets, flags;
        private final float[] boxes, friction;
        private Snapshot(long revision, int[] offsets, int[] flags, float[] boxes, float[] friction) {
            this.revision=revision; this.offsets=offsets; this.flags=flags; this.boxes=boxes; this.friction=friction;
        }
        public long revision() { return revision; }
        public int shapeStart(int cell) { return offsets[cell]; }
        public int shapeEnd(int cell) { return offsets[cell+1]; }
        public float coordinate(int box, int component) { return boxes[box*6+component]; }
        public float friction(int cell) { return friction[cell]; }
        public int flags(int cell) { return flags[cell]; }
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
    private final Map<Section, Work> sections = new HashMap<>();
    private final ArrayDeque<Section> pending = new ArrayDeque<>();
    private final Executor executor;
    private final LongSupplier clock;
    private final int maxSections;
    private final java.util.concurrent.atomic.AtomicInteger workers=new java.util.concurrent.atomic.AtomicInteger();
    private long revision, lastCaptureNanos, overrunCount;

    public PackageCollisionCache(Executor executor, int maxSections) { this(executor,maxSections,System::nanoTime); }
    public PackageCollisionCache(Executor executor, int maxSections, LongSupplier clock) {
        if (maxSections <= 0) throw new IllegalArgumentException("Section capacity");
        this.executor=java.util.Objects.requireNonNull(executor);
        this.clock=java.util.Objects.requireNonNull(clock); this.maxSections=maxSections;
    }
    private void owner() {
        if (Thread.currentThread()!=owner) throw new IllegalStateException("Collision cache accessed off owner thread");
    }
    public boolean request(Section section) {
        owner();
        if (sections.containsKey(section)) return true;
        if (sections.size()>=maxSections) return false;
        Work work=new Work(); sections.put(section,work); invalidate(section); return true;
    }
    /** Invalidation revokes coverage immediately, including an in-flight capture. */
    public void invalidate(Section section) {
        owner(); Work work=sections.get(section); if(work==null)return;
        work.revision=++revision; work.cursor=0; work.cells=null; work.published=null;
        // Keep an obsolete worker in flight until it completes: repeated edits cannot flood the executor.
        if(!work.queued) { pending.addLast(section); work.queued=true; }
    }
    public void evict(Section section) { owner(); sections.remove(section); pending.remove(section); }
    public void clear() { owner(); sections.clear(); pending.clear(); ++revision; }
    public Snapshot snapshot(Section section) { owner(); Work w=sections.get(section); return w==null?null:w.published; }

    /** Poll and capture share the same budget; never joins an unfinished worker. */
    public void tick(Source source, long budgetNanos) {
        owner(); if(budgetNanos<=0)return;
        long start=clock.getAsLong();
        int attempts=pending.size();
        while(attempts-- > 0 && !pending.isEmpty() && clock.getAsLong()-start<budgetNanos) {
            Section section=pending.removeFirst(); Work w=sections.get(section);
            if(w==null)continue;
            w.queued=false;
            if(w.future!=null) {
                if(w.future.isDone()) {
                    if(!w.future.isCompletedExceptionally() && !w.future.isCancelled()) {
                        Snapshot result=w.future.getNow(null);
                        if(result!=null && result.revision==w.revision)w.published=result;
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
                    workers.incrementAndGet();
                    try {
                        w.future=CompletableFuture.supplyAsync(() -> {
                            try { return pack(version,captured); } finally { workers.decrementAndGet(); }
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
        if(lastCaptureNanos>budgetNanos)overrunCount++;
    }
    private void enqueue(Section s,Work w) { if(!w.queued){pending.addLast(s);w.queued=true;} }
    private static Snapshot pack(long revision,Cell[] cells) {
        int[] offsets=new int[BLOCKS+1],flags=new int[BLOCKS]; float[] friction=new float[BLOCKS];
        int count=0;
        for(int i=0;i<BLOCKS;i++){offsets[i]=count;count=Math.addExact(count,cells[i].shapes.size());}
        offsets[BLOCKS]=count; float[] boxes=new float[Math.multiplyExact(count,6)]; int p=0;
        for(int i=0;i<BLOCKS;i++) {
            Cell c=cells[i]; friction[i]=c.friction;flags[i]=c.flags;
            int x=i&15,y=i>>>8,z=(i>>>4)&15;
            for(Box b:c.shapes) {
                boxes[p++]=x+b.minX;boxes[p++]=y+b.minY;boxes[p++]=z+b.minZ;
                boxes[p++]=x+b.maxX;boxes[p++]=y+b.maxY;boxes[p++]=z+b.maxZ;
            }
        }
        return new Snapshot(revision,offsets,flags,boxes,friction);
    }
    public long lastCaptureNanos(){return lastCaptureNanos;}
    public long overrunCount(){return overrunCount;}
}
