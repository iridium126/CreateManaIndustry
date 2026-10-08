package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageRegion;
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
    private static final java.util.concurrent.atomic.AtomicLong SNAPSHOT_IDS=new java.util.concurrent.atomic.AtomicLong();
    public interface Listener {
        default void invalidated(Section section,long revision) {}
        default void published(Section section,Snapshot snapshot) {}
        /** A completed capture predates a later edit; never make it the current CPU snapshot. */
        default void historical(Section section,Snapshot snapshot) {}
        default void removed(Section section) {}
        default void cleared() {}
    }
    public record Section(int x, int y, int z) {}
    public enum Invalidation { INITIAL,BLOCK,CHUNK,CHUNK_LOAD,CHUNK_UNLOAD,EXPLICIT }
    public record CaptureCause(Section section,Invalidation cause,int x,int y,int z,int notifications) {}
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
        /** A byte-exact prefix proof; it retains no preceding snapshot. */
        public record GpuPatch(long baseRevision,long baseIdentity,int baseShapes,java.util.List<Integer> cells,int bytes) {
            public GpuPatch {cells=java.util.List.copyOf(cells);}
        }
        private final long revision;
        private final long gpuIdentity=SNAPSHOT_IDS.incrementAndGet();
        private final long bakeNanos;
        private final int[] offsets, flags;
        private final float[] boxes, friction;
        private final java.nio.ByteBuffer gpuCells,gpuBoxes;
        private final int gpuShapeCount;
        private final boolean gpuEmpty;
        private final java.util.List<GpuPatch> gpuPatches;
        private final boolean localUpdate;
        private Snapshot(long revision, int[] offsets, int[] flags, float[] boxes, float[] friction,
                         java.nio.ByteBuffer gpuCells,java.nio.ByteBuffer gpuBoxes,int gpuShapeCount,boolean gpuEmpty,java.util.List<GpuPatch> gpuPatches,boolean localUpdate,long bakeNanos) {
            this.revision=revision; this.offsets=offsets; this.flags=flags; this.boxes=boxes; this.friction=friction;
            this.gpuCells=gpuCells;this.gpuBoxes=gpuBoxes;this.gpuShapeCount=gpuShapeCount;
            this.gpuEmpty=gpuEmpty;
            this.gpuPatches=java.util.List.copyOf(gpuPatches);this.localUpdate=localUpdate;
            this.bakeNanos=bakeNanos;
        }
        public long revision() { return revision; }
        public long gpuIdentity(){return gpuIdentity;}
        public long bakeNanos(){return bakeNanos;}
        public int shapeStart(int cell) { return offsets[cell]; }
        public int shapeEnd(int cell) { return offsets[cell+1]; }
        public float coordinate(int box, int component) { return boxes[box*6+component]; }
        public float friction(int cell) { return friction[cell]; }
        public int flags(int cell) { return flags[cell]; }
        private boolean matchesCell(int cell,Cell candidate) {
            if(cell<0||cell>=BLOCKS||flags[cell]!=candidate.flags
                    ||Float.floatToIntBits(friction[cell])!=Float.floatToIntBits(candidate.friction))return false;
            int start=offsets[cell],end=offsets[cell+1];
            if(end-start!=candidate.shapes.size())return false;
            int x=cell&15,y=cell>>>8,z=(cell>>>4)&15;
            for(int i=0;i<candidate.shapes.size();i++) {
                Box shape=candidate.shapes.get(i);int p=(start+i)*6;
                if(Float.floatToIntBits(boxes[p])!=Float.floatToIntBits(x+shape.minX)
                        ||Float.floatToIntBits(boxes[p+1])!=Float.floatToIntBits(y+shape.minY)
                        ||Float.floatToIntBits(boxes[p+2])!=Float.floatToIntBits(z+shape.minZ)
                        ||Float.floatToIntBits(boxes[p+3])!=Float.floatToIntBits(x+shape.maxX)
                        ||Float.floatToIntBits(boxes[p+4])!=Float.floatToIntBits(y+shape.maxY)
                        ||Float.floatToIntBits(boxes[p+5])!=Float.floatToIntBits(z+shape.maxZ))return false;
            }
            return true;
        }
        public int gpuShapeCount(){return gpuShapeCount;}
        /** Only a fully captured, flag-free empty section can use the GPU coarse fast path. */
        public boolean gpuEmpty(){return gpuEmpty;}
        public GpuPatch gpuPatch(){return gpuPatches.isEmpty()?null:gpuPatches.getFirst();}
        public GpuPatch gpuPatch(long identity,long version){for(var patch:gpuPatches)if(patch.baseIdentity==identity&&patch.baseRevision==version)return patch;return null;}
        public java.nio.ByteBuffer gpuCells(){return gpuCells.asReadOnlyBuffer().order(java.nio.ByteOrder.nativeOrder());}
        public java.nio.ByteBuffer gpuBoxes(){return gpuBoxes.asReadOnlyBuffer().order(java.nio.ByteOrder.nativeOrder());}
        /** Exact local coordinates come from the immutable dictionary, not from
         * subtracting cell coordinates from already rounded translated floats. */
        private Cell capturedCell(int cell) {
            if(gpuShapeCount<0)return null;
            int at=cell*16,first=gpuCells.getInt(at),count=gpuCells.getInt(at+4);
            if(count!=offsets[cell+1]-offsets[cell])return null;
            var local=new java.util.ArrayList<Box>(count);
            for(int i=0;i<count;i++){
                int p=(first+i)*32;local.add(new Box(gpuBoxes.getFloat(p),gpuBoxes.getFloat(p+4),gpuBoxes.getFloat(p+8),
                        gpuBoxes.getFloat(p+16),gpuBoxes.getFloat(p+20),gpuBoxes.getFloat(p+24)));
            }
            return new Cell(local,friction[cell],flags[cell]);
        }
    }
    private static final class Work {
        long revision,rescueEpoch;
        int cursor;
        Cell[] cells;
        Cell[] futureCells;
        long futureRevision;
        Snapshot published;
        Snapshot baseline;
        Map<Integer,Cell> rescueCells;
        java.util.BitSet dirtyCells;
        CompletableFuture<Snapshot> future;
        boolean queued;
        String localFallback="initial";
    }
    private final Thread owner = Thread.currentThread();
    private final LinkedHashMap<Section, Work> sections = new LinkedHashMap<>(16,.75f,true);
    // Constant-time invalidation/eviction even during a large block update burst.
    private final LinkedHashMap<Section,Work> pending = new LinkedHashMap<>();
    private final Map<Long,Set<Section>> columns = new HashMap<>();
    private final Set<Section> packageUsage=new HashSet<>();
    // Deferred GPU events may arrive after this revision has already published/replaced.
    // Keep only numeric origins; no world, block entity or mutable capture is retained.
    private final LinkedHashMap<Long,CaptureCause> captureCauses=new LinkedHashMap<>();
    private static final int MAX_CAPTURE_CAUSES=4096;
    private final Executor executor;
    private final LongSupplier clock;
    private final int maxSections;
    private final java.util.concurrent.atomic.AtomicInteger workers=new java.util.concurrent.atomic.AtomicInteger();
    private static final long LOCAL_BUDGET_NANOS=1_000_000;
    private long localSpentNanos,localPublications,captureEpoch;
    private String localFailure="none";
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
        invalidate(section,Invalidation.INITIAL,section.x,section.y,section.z); return true;
    }
    /** Invalidation revokes coverage immediately, including an in-flight capture. */
    public void invalidate(Section section) {
        invalidate(section,Invalidation.EXPLICIT,section.x,section.y,section.z);
    }
    private void invalidate(Section section,Invalidation cause,int x,int y,int z) {
        owner(); Work work=sections.get(section); if(work==null)return;
        if(cause!=Invalidation.BLOCK)work.localFallback="full-context";
        work.baseline=null;work.dirtyCells=null;work.rescueCells=null;
        // Multiple edits before any new capture share one invalidation. An obsolete worker
        // must still finish, but can never publish into the replacement revision.
        if(work.revision!=0 && work.cursor==0 && work.cells==null && work.published==null && work.future==null && work.queued){recordCause(section,work.revision,cause,x,y,z);return;}
        work.revision=++revision; work.cursor=0; work.cells=null;work.futureCells=null;work.futureRevision=0;work.published=null;
        recordCause(section,work.revision,cause,x,y,z);
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
        for(int cx=x0;cx<=x1;cx++)for(int cy=y0;cy<=y1;cy++)for(int cz=z0;cz<=z1;cz++)invalidate(new Section(cx,cy,cz),Invalidation.BLOCK,x,y,z);
    }
    /** Compare the same one-cell context neighborhood, but revoke only sections
     * whose owned collision input actually changed. Boundary guards read those
     * cells directly; unchanged adjacent sections need no new revision. */
    public void invalidateChangedBlock(int x,int y,int z,Source source) {
        owner();var changed=new java.util.HashMap<Section,java.util.BitSet>();var values=new HashMap<Section,Map<Integer,Cell>>();
        for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++) {
            long nx=(long)x+dx,ny=(long)y+dy,nz=(long)z+dz;
            if(nx<Integer.MIN_VALUE||nx>Integer.MAX_VALUE||ny<Integer.MIN_VALUE||ny>Integer.MAX_VALUE||nz<Integer.MIN_VALUE||nz>Integer.MAX_VALUE)continue;
            int cx=(int)nx,cy=(int)ny,cz=(int)nz;var section=new Section(cx>>4,cy>>4,cz>>4);
            int cell=(cy&15)<<8|(cz&15)<<4|(cx&15);if(!hasCapturedCell(section,cell))continue;
            try {
                Cell live=source.capture(section,cell);
                if(live==null||Boolean.FALSE.equals(capturedCellMatches(section,cell,live))){changed.computeIfAbsent(section,key->new java.util.BitSet(BLOCKS)).set(cell);values.computeIfAbsent(section,key->new HashMap<>()).put(cell,live);}
            }catch(RuntimeException|LinkageError unavailable){changed.computeIfAbsent(section,key->new java.util.BitSet(BLOCKS)).set(cell);values.computeIfAbsent(section,key->new HashMap<>()).put(cell,null);}
        }
        for(var entry:changed.entrySet())if(!publishLocal(entry.getKey(),values.get(entry.getKey()),x,y,z))invalidateCells(entry.getKey(),entry.getValue(),x,y,z);
    }
    private static java.util.List<Snapshot.GpuPatch> patchLineage(Snapshot base,java.util.List<Integer> changed,int shapes) {
        var patches=new java.util.ArrayList<Snapshot.GpuPatch>();
        patches.add(new Snapshot.GpuPatch(base.revision,base.gpuIdentity,base.gpuShapeCount,changed,changed.size()*16+(shapes-base.gpuShapeCount)*32));
        for(var previous:base.gpuPatches){
            if(patches.size()==8)break;
            var union=new java.util.TreeSet<Integer>(previous.cells);union.addAll(changed);
            patches.add(new Snapshot.GpuPatch(previous.baseRevision,previous.baseIdentity,previous.baseShapes,
                    java.util.List.copyOf(union),union.size()*16+(shapes-previous.baseShapes)*32));
        }
        return java.util.List.copyOf(patches);
    }
    /** Bounded numeric work only; every changed cell was already sampled by the owner. */
    private boolean publishLocal(Section section,Map<Integer,Cell> changes,int x,int y,int z) {
        Work work=sections.get(section);
        if(work==null)return false;
        if(work.published==null){if(work.localFallback.equals("none")||work.localFallback.equals("full-context"))work.localFallback="capture-in-progress";return false;}
        if(localSpentNanos>=LOCAL_BUDGET_NANOS){work.localFallback="time-budget";return false;}
        long start=clock.getAsLong();Snapshot updated=localSnapshot(work.published,changes,revision+1,start+LOCAL_BUDGET_NANOS-localSpentNanos);
        localSpentNanos+=Math.max(0,clock.getAsLong()-start);
        if(updated==null){work.localFallback=localFailure;return false;}
        work.localFallback="none";work.revision=++revision;work.published=null;recordCause(section,work.revision,Invalidation.BLOCK,x,y,z);
        listener.invalidated(section,work.revision);
        work.published=updated;work.baseline=null;work.dirtyCells=null;work.rescueCells=null;
        if(work.future==null){pending.remove(section);work.queued=false;}else enqueue(section,work);
        localPublications++;listener.published(section,updated);return true;
    }
    /** Retry a local replacement at the budgeted capture boundary, even with an obsolete worker. */
    private boolean retryLocal(Section section,Work work,Source source,long deadline) {
        if(work.published!=null||work.baseline==null||work.dirtyCells==null||work.dirtyCells.cardinality()>27)return false;
        if(work.rescueEpoch==captureEpoch||localSpentNanos>=LOCAL_BUDGET_NANOS||clock.getAsLong()>=deadline)return false;
        work.rescueEpoch=captureEpoch;
        long started=clock.getAsLong(),version=work.revision;Snapshot base=work.baseline;
        var dirty=(java.util.BitSet)work.dirtyCells.clone();var values=new HashMap<Integer,Cell>();work.rescueCells=values;
        long until=Math.min(deadline,started+LOCAL_BUDGET_NANOS-localSpentNanos);Snapshot updated=null;
        for(int cell=dirty.nextSetBit(0);cell>=0;cell=dirty.nextSetBit(cell+1)){
            if(clock.getAsLong()>=until){work.localFallback="time-budget";break;}
            try{values.put(cell,source.capture(section,cell));}
            catch(RuntimeException|LinkageError unavailable){values.put(cell,null);}
        }
        if(work.revision!=version||work.baseline!=base||work.dirtyCells==null||!work.dirtyCells.equals(dirty)||work.rescueCells!=values){
            work.rescueCells=null;localSpentNanos+=Math.max(0,clock.getAsLong()-started);return false;
        }
        if(values.size()==dirty.cardinality())updated=localSnapshot(base,values,version,until);
        else localFailure="time-budget";
        localSpentNanos+=Math.max(0,clock.getAsLong()-started);
        if(updated==null){work.localFallback=localFailure;return false;}
        work.published=updated;work.baseline=null;work.dirtyCells=null;work.cells=null;work.cursor=0;work.rescueCells=null;
        work.localFallback="none";localPublications++;listener.published(section,updated);
        return true;
    }
    private Snapshot localSnapshot(Snapshot base,Map<Integer,Cell> input,long version,long deadline) {
        if(input==null||input.isEmpty())return rejectLocal("input-unavailable");
        if(input.size()>27)return rejectLocal("cell-limit");
        if(base.gpuShapeCount<0||base.gpuShapeCount>PackageCollisionGpu.DEFAULT_SHAPES)return rejectLocal("dictionary-limit");
        var changes=new java.util.TreeMap<Integer,Cell>(input);int changedShapes=0,total=base.offsets[BLOCKS];
        boolean sameGeometry=true;
        for(var row:changes.entrySet()){
            Cell cell=row.getValue();if(cell==null)return rejectLocal("input-unavailable");changedShapes+=cell.shapes.size();
            if(changedShapes>64)return rejectLocal("changed-shape-limit");
            for(Box b:cell.shapes)if(b.minX< -1||b.minY< -1||b.minZ< -1||b.maxX>2||b.maxY>2||b.maxZ>2)return rejectLocal("shape-bounds");
            total+=cell.shapes.size()-(base.offsets[row.getKey()+1]-base.offsets[row.getKey()]);
            Cell previous=base.capturedCell(row.getKey());sameGeometry&=previous!=null&&previous.shapes.equals(cell.shapes);
        }
        if(total>8192)return rejectLocal("cpu-shape-limit");
        if(clock.getAsLong()>=deadline)return rejectLocal("time-budget");
        record Range(int first,int count){}
        var ranges=new HashMap<Integer,Range>();var appended=new java.util.ArrayList<Box>();
        for(var row:changes.entrySet()){
            Cell cell=row.getValue();int first=0,count=(cell.flags&UNSUPPORTED)==0?cell.shapes.size():0;
            if(count>0){
                first=-1;int available=base.gpuShapeCount+appended.size();
                for(int candidate=0;candidate+count<=available;candidate++){
                    boolean equal=true;
                    for(int i=0;i<count&&equal;i++){
                        int at=candidate+i;Box b=cell.shapes.get(i);
                        equal=at<base.gpuShapeCount?boxMatches(base.gpuBoxes,at*32,b):appended.get(at-base.gpuShapeCount).equals(b);
                    }
                    if(equal){first=candidate;break;}
                }
                if(first<0){
                    first=available;if(first+count>PackageCollisionGpu.DEFAULT_SHAPES)return rejectLocal("dictionary-limit");
                    appended.addAll(cell.shapes);
                }
            }
            ranges.put(row.getKey(),new Range(first,count));
            if(clock.getAsLong()>=deadline)return rejectLocal("time-budget");
        }
        int[] offsets=base.offsets,flags=base.flags.clone();float[] boxes=base.boxes,friction=base.friction.clone();
        if(!sameGeometry){
            offsets=base.offsets.clone();boxes=new float[total*6];int shift=0,boundary=0,oldBox=0,newBox=0;
            for(var row:changes.entrySet()){
                int cell=row.getKey(),first=base.offsets[cell],end=base.offsets[cell+1];
                for(int i=boundary;i<=cell;i++)offsets[i]=base.offsets[i]+shift;
                int unchanged=first-oldBox;System.arraycopy(base.boxes,oldBox*6,boxes,newBox*6,unchanged*6);newBox+=unchanged;
                int cx=cell&15,cy=cell>>>8,cz=(cell>>>4)&15;
                for(Box b:row.getValue().shapes){
                    int p=newBox++*6;boxes[p]=cx+b.minX;boxes[p+1]=cy+b.minY;boxes[p+2]=cz+b.minZ;
                    boxes[p+3]=cx+b.maxX;boxes[p+4]=cy+b.maxY;boxes[p+5]=cz+b.maxZ;
                }
                shift+=row.getValue().shapes.size()-(end-first);boundary=cell+1;oldBox=end;
            }
            for(int i=boundary;i<=BLOCKS;i++)offsets[i]=base.offsets[i]+shift;
            System.arraycopy(base.boxes,oldBox*6,boxes,newBox*6,(base.offsets[BLOCKS]-oldBox)*6);
        }
        var gpuCells=java.nio.ByteBuffer.allocateDirect(BLOCKS*16).order(java.nio.ByteOrder.nativeOrder());gpuCells.put(base.gpuCells()).flip();
        for(var row:changes.entrySet()){
            int cell=row.getKey();Cell value=row.getValue();Range range=ranges.get(cell);flags[cell]=value.flags;friction[cell]=value.friction;
            gpuCells.putInt(cell*16,range.first).putInt(cell*16+4,range.count).putFloat(cell*16+8,value.friction).putInt(cell*16+12,value.flags);
        }
        var gpuBoxes=base.gpuBoxes;
        if(!appended.isEmpty()){
            gpuBoxes=java.nio.ByteBuffer.allocateDirect((base.gpuShapeCount+appended.size())*32).order(java.nio.ByteOrder.nativeOrder());gpuBoxes.put(base.gpuBoxes());
            for(Box b:appended)gpuBoxes.putFloat(b.minX).putFloat(b.minY).putFloat(b.minZ).putFloat(0).putFloat(b.maxX).putFloat(b.maxY).putFloat(b.maxZ).putFloat(0);
            gpuBoxes.flip();
        }
        boolean empty=total==0;if(empty)for(int flag:flags)if(flag!=0){empty=false;break;}
        int shapes=base.gpuShapeCount+appended.size();var patches=patchLineage(base,java.util.List.copyOf(changes.keySet()),shapes);
        if(clock.getAsLong()>=deadline)return rejectLocal("time-budget");
        return new Snapshot(version,offsets,flags,boxes,friction,gpuCells,gpuBoxes,shapes,empty,patches,true,0);
    }
    private Snapshot rejectLocal(String reason){localFailure=reason;return null;}
    private static boolean boxMatches(java.nio.ByteBuffer data,int p,Box box){
        return Float.floatToIntBits(data.getFloat(p))==Float.floatToIntBits(box.minX)&&Float.floatToIntBits(data.getFloat(p+4))==Float.floatToIntBits(box.minY)
                &&Float.floatToIntBits(data.getFloat(p+8))==Float.floatToIntBits(box.minZ)&&Float.floatToIntBits(data.getFloat(p+16))==Float.floatToIntBits(box.maxX)
                &&Float.floatToIntBits(data.getFloat(p+20))==Float.floatToIntBits(box.maxY)&&Float.floatToIntBits(data.getFloat(p+24))==Float.floatToIntBits(box.maxZ);
    }
    private void invalidateCells(Section section,java.util.BitSet changed,int x,int y,int z) {
        Work work=sections.get(section);if(work==null)return;
        Snapshot base=work.baseline!=null?work.baseline:work.published;
        if(base==null||base.gpuShapeCount()<0){invalidate(section,Invalidation.BLOCK,x,y,z);return;}
        var dirty=work.dirtyCells==null?new java.util.BitSet(BLOCKS):(java.util.BitSet)work.dirtyCells.clone();dirty.or(changed);
        invalidate(section,Invalidation.BLOCK,x,y,z);work.baseline=base;work.dirtyCells=dirty;
    }
    /** Packet replacement/unload changes context in the eight adjacent chunk columns too. */
    public void invalidateChunk(int x,int z) {
        invalidateChunk(x,z,Invalidation.CHUNK);
    }
    public void invalidateChunk(int x,int z,Invalidation cause) {
        owner();
        for(int cx=x-1;cx<=x+1;cx++)for(int cz=z-1;cz<=z+1;cz++) {
            Set<Section> affected=columns.get(column(cx,cz));
            if(affected!=null)for(Section section:affected)invalidate(section,cause,x,0,z);
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
    public void clear() { owner(); sections.clear(); pending.clear(); columns.clear();packageUsage.clear();captureCauses.clear(); ++revision;listener.cleared(); }
    private void recordCause(Section section,long version,Invalidation cause,int x,int y,int z) {
        var previous=captureCauses.get(version);int notifications=previous==null?1:previous.notifications==Integer.MAX_VALUE?Integer.MAX_VALUE:previous.notifications+1;
        captureCauses.put(version,new CaptureCause(section,cause,x,y,z,notifications));
        while(captureCauses.size()>MAX_CAPTURE_CAUSES)captureCauses.pollFirstEntry();
    }
    public CaptureCause captureCause(Section section,long version){owner();var cause=captureCauses.get(version);return cause!=null&&cause.section.equals(section)?cause:null;}
    /** Origin is for the exact event revision; progress is explicitly the state at log time. */
    public String describeCapture(Section section,long version){
        owner();var cause=captureCause(section,version);Work work=sections.get(section);
        String origin="captureCause=unretained";
        if(cause!=null){
            String at=switch(cause.cause){case BLOCK->" causeBlock="+cause.x+","+cause.y+","+cause.z;case CHUNK,CHUNK_LOAD,CHUNK_UNLOAD->" causeChunk="+cause.x+","+cause.z;default->" causeSection="+cause.x+","+cause.y+","+cause.z;};
            origin="captureCause="+cause.cause+at+" notifications="+cause.notifications;
        }
        if(work==null)return origin+" observedCapture=evicted";
        String state=work.published!=null?"complete":work.future!=null?(work.futureRevision==work.revision?"worker":"obsolete-worker"):"capture";
        int cells=work.published!=null||work.futureRevision==work.revision?BLOCKS:work.cursor;
        return origin+" observedTargetRevision="+work.revision+" observedCapture="+state+" observedCells="+cells+"/"+BLOCKS
                +" observedCaptureMode="+(work.published!=null&&work.published.localUpdate?"local":"incremental")+" observedLocalFallback="+work.localFallback+" localPublications="+localPublications;
    }
    /** Force capture follows completed GPU usage, including packages retaining an older
     * acquisition origin. Stable order avoids resetting history for map iteration changes. */
    public List<PackageRegion> forceRegions(Collection<PackageRegion> anchors) {
        owner();var result=new TreeSet<PackageRegion>(Comparator.comparingInt(PackageRegion::x)
                .thenComparingInt(PackageRegion::y).thenComparingInt(PackageRegion::z));
        result.addAll(anchors);
        for(Section section:packageUsage)result.add(new PackageRegion(
                Math.floorDiv(section.x(),4),Math.floorDiv(section.y(),4),Math.floorDiv(section.z(),4)));
        return List.copyOf(result);
    }
    public void clearPackageUsage(){owner();packageUsage.clear();}
    public void protectPackageUsage(Section section){owner();if(sections.containsKey(section))packageUsage.add(section);}
    public Snapshot snapshot(Section section) { owner(); Work w=sections.get(section); return w==null?null:w.published; }
    public java.util.Map<Section,Long> versions(){owner();var result=new java.util.HashMap<Section,Long>();for(var entry:sections.entrySet())result.put(entry.getKey(),entry.getValue().revision);return java.util.Map.copyOf(result);}
    public int size(){owner();return sections.size();}
    public long capacityEvictions(){owner();return capacityEvictions;}
    public long capacityRejections(){owner();return capacityRejections;}
    public int readyCount(){owner();int count=0;for(Work work:sections.values())if(work.published!=null)count++;return count;}
    private static long column(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}

    /**
     * Compare a live cell with the newest capture that could still publish. A null result means
     * the active capture has not reached this cell, so it will observe the live value directly.
     */
    public Boolean capturedCellMatches(Section section,int cell,Cell live) {
        owner();if(cell<0||cell>=BLOCKS)return null;
        Work work=sections.get(section);if(work==null)return null;
        if(work.published!=null)return work.published.matchesCell(cell,live);
        if(work.rescueCells!=null&&work.rescueCells.containsKey(cell)){Cell captured=work.rescueCells.get(cell);return captured!=null&&captured.equals(live);}
        if(work.futureCells!=null&&work.futureRevision==work.revision)
            return work.futureCells[cell]!=null&&work.futureCells[cell].equals(live);
        if(work.cells!=null&&cell<work.cursor&&work.cells[cell]!=null)return work.cells[cell].equals(live);
        if(work.baseline!=null&&!work.dirtyCells.get(cell))return work.baseline.matchesCell(cell,live);
        return null;
    }
    public boolean hasCapturedCell(Section section,int cell) {
        owner();if(cell<0||cell>=BLOCKS)return false;
        Work work=sections.get(section);if(work==null)return false;
        return work.published!=null
                ||work.rescueCells!=null&&work.rescueCells.get(cell)!=null
                ||work.futureCells!=null&&work.futureRevision==work.revision&&work.futureCells[cell]!=null
                ||work.cells!=null&&cell<work.cursor&&work.cells[cell]!=null
                ||work.baseline!=null&&!work.dirtyCells.get(cell);
    }

    /** Poll and capture share the same budget; never joins an unfinished worker. */
    public void tick(Source source, long budgetNanos) {
        owner();localSpentNanos=0;captureEpoch++;
        long start=clock.getAsLong();
        // Publishing immutable completed work needs no world reads. A moving/light
        // capture that spent the shared budget must not delay ready static geometry.
        var completed=new java.util.ArrayList<Section>();
        for(var row:pending.entrySet())if(row.getValue().future!=null&&row.getValue().future.isDone())completed.add(row.getKey());
        for(Section section:completed){Work work=sections.get(section);if(work!=null)publishCompleted(section,work);}
        if(budgetNanos<=0){lastCaptureNanos=clock.getAsLong()-start;return;}
        try {
        boolean live=false,background=false;
        for(Section section:pending.keySet())if(packageUsage.contains(section))live=true;else background=true;
        if(live&&background){
            captureQueued(source,start,budgetNanos-budgetNanos/4,true);
            captureQueued(source,start,budgetNanos,false);
            // A blocked/finished background queue returns unused time to live demand.
            captureQueued(source,start,budgetNanos,true);
        }else captureQueued(source,start,budgetNanos,null);
        } finally {for(Work work:sections.values())work.rescueCells=null;}
        lastCaptureNanos=clock.getAsLong()-start;
        captureTimes[captureCursor]=lastCaptureNanos;captureCursor=(captureCursor+1)%captureTimes.length;
        captureSamples=Math.min(captureSamples+1,captureTimes.length);
        if(lastCaptureNanos>budgetNanos)overrunCount++;
    }
    private void captureQueued(Source source,long start,long allowance,Boolean live) {
        if(clock.getAsLong()-start>=allowance)return;
        var queue=new java.util.ArrayDeque<Map.Entry<Section,Work>>();
        for(var row:pending.entrySet())if(live==null||packageUsage.contains(row.getKey())==live)queue.addLast(Map.entry(row.getKey(),row.getValue()));
        int attempts=queue.size();
        while(attempts-- > 0 && !queue.isEmpty() && clock.getAsLong()-start<allowance) {
            var queued=queue.removeFirst();
            Section section=queued.getKey();Work w=queued.getValue();
            if(pending.get(section)!=w)continue;
            pending.remove(section);
            w.queued=false;
            if(retryLocal(section,w,source,start+allowance)){if(w.future!=null)enqueue(section,w);continue;}
            if(w.future!=null) {
                publishCompleted(section,w);
                if(w.published!=null){w.rescueCells=null;if(w.future!=null)enqueue(section,w);continue;}
                if(w.future!=null) { w.rescueCells=null;enqueue(section,w);queue.addLast(queued);continue; }
            }
            if(w.published!=null){w.rescueCells=null;continue;}
            if(w.cells==null)w.cells=new Cell[BLOCKS];
            // Small batches keep one hot section from monopolizing the capture budget.
            int before=w.cursor;
            int end=Math.min(BLOCKS,w.cursor+32);
            while(w.cursor<end && clock.getAsLong()-start<allowance) {
                Cell cell;
                try {
                    boolean sampled=w.rescueCells!=null&&w.rescueCells.containsKey(w.cursor);
                    cell=sampled?w.rescueCells.get(w.cursor):w.baseline!=null&&!w.dirtyCells.get(w.cursor)?w.baseline.capturedCell(w.cursor):null;
                    if(cell==null&&!sampled)cell=source.capture(section,w.cursor);
                }
                catch(RuntimeException failure) { w.rescueCells=null;enqueue(section,w);throw failure; }
                if(cell==null)break;
                w.cells[w.cursor++]=cell;
            }
            boolean progressed=w.cursor>before;
            if(w.cursor==BLOCKS) {
                if(workers.get()<4) {
                    Cell[] captured=w.cells; long version=w.revision;Snapshot baseline=w.baseline;
                    var accounting=workers;
                    workers.incrementAndGet();
                    try {
                        w.future=CompletableFuture.supplyAsync(() -> {
                            try { return pack(version,captured,baseline); } finally { accounting.decrementAndGet(); }
                        },executor);
                        w.futureCells=captured;w.futureRevision=version;w.cells=null; w.cursor=0;
                    } catch(java.util.concurrent.RejectedExecutionException rejected) { workers.decrementAndGet(); }
                }
            }
            enqueue(section,w);
            queue.addLast(queued);
            // Round-robin another batch only while capture makes progress and time remains.
            if(progressed)attempts++;
        }
    }
    private void publishCompleted(Section section,Work work) {
        var future=work.future;if(future==null||!future.isDone())return;
        Snapshot result=!future.isCompletedExceptionally()&&!future.isCancelled()?future.getNow(null):null;
        work.future=null;work.futureCells=null;work.futureRevision=0;
        if(result==null){if(work.published!=null){pending.remove(section);work.queued=false;}return;}
        if(result.revision==work.revision&&work.published==null) {
            work.published=result;work.baseline=null;work.dirtyCells=null;pending.remove(section);work.queued=false;
            bakeTimes[bakeCursor]=result.bakeNanos;bakeCursor=(bakeCursor+1)%bakeTimes.length;
            bakeSamples=Math.min(bakeSamples+1,bakeTimes.length);listener.published(section,result);
        } else {
            if(result.revision!=work.revision)listener.historical(section,result);
            if(work.published!=null){pending.remove(section);work.queued=false;}
        }
    }
    private void enqueue(Section s,Work w) { if(!w.queued){pending.put(s,w);w.queued=true;} }
    private static Snapshot pack(long revision,Cell[] cells,Snapshot baseline) {
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
        Snapshot.GpuPatch patch=null;
        if(!oversized&&baseline!=null&&baseline.gpuShapeCount>=0&&unique.size()>=baseline.gpuShapeCount){
            int prefix=baseline.gpuBoxes.remaining();var leading=gpuBoxes.asReadOnlyBuffer();leading.limit(prefix);
            if(baseline.gpuBoxes.mismatch(leading)==-1){
                var changed=new java.util.ArrayList<Integer>();
                for(int i=0;i<BLOCKS;i++)for(int component=0;component<16;component+=4)
                    if(baseline.gpuCells.getInt(i*16+component)!=gpuCells.getInt(i*16+component)){changed.add(i);break;}
                patch=new Snapshot.GpuPatch(baseline.revision,baseline.gpuIdentity,baseline.gpuShapeCount,changed,changed.size()*16+gpuBoxes.remaining()-prefix);
            }
        }
        return new Snapshot(revision,offsets,flags,boxes,friction,gpuCells,gpuBoxes,oversized?-1:unique.size(),count==0 && combinedFlags==0,patch==null?java.util.List.of():patchLineage(baseline,patch.cells,unique.size()),false,System.nanoTime()-begin);
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
