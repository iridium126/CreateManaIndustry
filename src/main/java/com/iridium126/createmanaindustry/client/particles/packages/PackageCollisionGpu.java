package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.function.LongSupplier;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/** Versioned, bounded world atlas. Persistent writes only touch slots no live GPU view references. */
public final class PackageCollisionGpu implements AutoCloseable {
    public static final int CELL_BYTES=4096*16,HEAD_BYTES=32,BANKS=4,SLICE_BYTES=16384;
    public static final int DEFAULT_SECTIONS=256,DEFAULT_SHAPES=1024,DEFAULT_UPLOAD_BYTES=262144;
    public static final long DEFAULT_UPLOAD_NANOS=10_000_000;
    private static final int MAP_FLAGS=GL30.GL_MAP_WRITE_BIT|GL44.GL_MAP_PERSISTENT_BIT|GL44.GL_MAP_COHERENT_BIT;
    private static final class Entry {
        final PackageCollisionCache.Section section;
        /** Latest CPU revision requested for this section. */
        long revision;
        /** Revision in the currently visible slot; it remains usable during replacement upload. */
        long visibleRevision=Long.MIN_VALUE;
        int visible=-1,staging=-1,progress;
        boolean visibleEmpty;
        PackageCollisionCache.Snapshot snapshot;
        ByteBuffer cells,boxes;
        Entry(PackageCollisionCache.Section section){this.section=section;}
    }
    private static final class Bank {
        int buffer;ByteBuffer mapped;long fence;boolean leased;
        long version=Long.MIN_VALUE;boolean coarse;
        final BitSet references=new BitSet();
    }
    public record Stats(int residents,int ready,int pending,int retired,long uploadedBytes,long capacityRejections,long evictions,
                        long shapeRejections,long skippedViews,long lastUploadNanos,long overruns,long p50Nanos,long p95Nanos) {}
    private final Thread owner=Thread.currentThread();
    private final int capacity,shapeCapacity,tableSize,slotBytes;
    private final LongSupplier clock;
    private final LinkedHashMap<PackageCollisionCache.Section,Entry> entries=new LinkedHashMap<>(16,.75f,true);
    private final LinkedHashMap<PackageCollisionCache.Section,Entry> pending=new LinkedHashMap<>();
    private final Set<PackageCollisionCache.Section> packageUsage=new HashSet<>();
    private final Bank[] banks=new Bank[BANKS];
    private final ArrayDeque<Integer> free=new ArrayDeque<>();
    private final BitSet retired=new BitSet();
    private final long[] timings=new long[128];
    private int timingCursor,timingCount,data;
    private ByteBuffer mappedData;
    private long serial,usageGeneration=Long.MIN_VALUE,uploadedBytes,capacityRejections,evictions,shapeRejections,skippedViews,lastUploadNanos,overruns;
    private boolean closed,viewOpen,failed;

    public PackageCollisionGpu(int capacity,int shapes){this(capacity,shapes,System::nanoTime);}
    public PackageCollisionGpu(int capacity,int shapes,LongSupplier clock) {
        if(capacity<=0 || capacity>1024 || shapes<=0 || shapes>PackageCollisionCache.MAX_GPU_SHAPES)
            throw new IllegalArgumentException("World atlas capacity");
        this.capacity=capacity;shapeCapacity=shapes;this.clock=Objects.requireNonNull(clock);
        tableSize=Integer.highestOneBit(Math.max(2,capacity-1))<<2;slotBytes=CELL_BYTES+shapes*32;
        long bytes=(long)slotBytes*capacity*2;
        if(bytes>GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE) || bytes>Integer.MAX_VALUE)
            throw new IllegalArgumentException("World atlas exceeds device SSBO limit");
        try {
            data=GL15.glGenBuffers();mappedData=map(data,(int)bytes);
            for(int i=0;i<BANKS;i++){Bank bank=new Bank();banks[i]=bank;bank.buffer=GL15.glGenBuffers();bank.mapped=map(bank.buffer,tableSize*HEAD_BYTES);empty(bank.mapped);}
            for(int i=0;i<capacity*2;i++)free.addLast(i);
        }catch(RuntimeException failure){close();throw failure;}
    }
    private static ByteBuffer map(int buffer,int bytes) {
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);GL44.glBufferStorage(GL43.GL_SHADER_STORAGE_BUFFER,bytes,MAP_FLAGS);
        ByteBuffer mapped=GL30.glMapBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,0,bytes,MAP_FLAGS);
        if(mapped==null)throw new IllegalStateException("Persistent world mapping unavailable");
        return mapped.order(ByteOrder.nativeOrder());
    }
    private static void empty(ByteBuffer table) {
        for(int p=0;p<table.capacity();p+=HEAD_BYTES)table.putInt(p+12,-1);
    }
    private void owner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("World atlas off owner thread");}
    private void open(){owner();if(closed || failed)throw new IllegalStateException("World atlas unavailable");}
    /**
     * Mark a section dirty without dropping its last complete GPU snapshot. The previous
     * geometry remains in every newly published table until a replacement has uploaded in
     * full. This avoids turning a block edit into a temporary empty-world collision hole.
     */
    public void invalidate(PackageCollisionCache.Section section,long revision) {
        open();Entry entry=entries.get(section);if(entry==null)return;
        if(revision<entry.revision)return;
        discardStaging(entry);entry.revision=revision;entry.snapshot=null;
    }
    /** Discard an incomplete replacement but keep the last fully visible slot. */
    private void discardStaging(Entry entry) {
        pending.remove(entry.section);
        if(entry.staging>=0){free.addLast(entry.staging);entry.staging=-1;}
        entry.progress=0;entry.cells=null;entry.boxes=null;
    }
    private void revoke(Entry entry) {
        if(entry.visible>=0){retired.set(entry.visible);entry.visible=-1;}
        entry.visibleRevision=Long.MIN_VALUE;entry.visibleEmpty=false;
        discardStaging(entry);entry.snapshot=null;
    }
    /** CPU-only latest-version queue. Unsupported geometry/capacity stays with Create. */
    public boolean offer(PackageCollisionCache.Section section,PackageCollisionCache.Snapshot snapshot) {
        open();Objects.requireNonNull(section);Objects.requireNonNull(snapshot);
        Entry entry=entries.get(section);
        if(entry!=null && snapshot.revision()<entry.revision)return false;
        if(entry!=null && entry.snapshot==snapshot)return true;
        if(snapshot.revision()<=0 || snapshot.gpuShapeCount()<0 || snapshot.gpuShapeCount()>shapeCapacity) {
            shapeRejections++;if(entry!=null){revoke(entry);entry.revision=snapshot.revision();serial++;}return false;
        }
        ByteBuffer cells=snapshot.gpuCells(),boxes=snapshot.gpuBoxes();
        if(cells.remaining()!=CELL_BYTES || boxes.remaining()!=snapshot.gpuShapeCount()*32) {
            shapeRejections++;if(entry!=null){revoke(entry);entry.revision=snapshot.revision();serial++;}return false;
        }
        if(entry==null) {
            if(entries.size()>=capacity && !evictLeastRecent()){capacityRejections++;return false;}
            entry=new Entry(section);entries.put(section,entry);
        }
        // Keep the old visible slot until this replacement is completely uploaded.
        discardStaging(entry);entry.revision=snapshot.revision();entry.snapshot=snapshot;
        entry.cells=cells;entry.boxes=boxes;
        pending.put(section,entry);return true;
    }
    /** Drop the oldest CPU mapping; its GPU slots remain retired until every table fence releases them. */
    private boolean evictLeastRecent() {
        var iterator=entries.entrySet().iterator();
        while(iterator.hasNext()) {
            var candidate=iterator.next();if(packageUsage.contains(candidate.getKey()))continue;
            Entry entry=candidate.getValue();iterator.remove();packageUsage.remove(entry.section);revoke(entry);serial++;evictions++;return true;
        }
        return false;
    }
    public void forget(PackageCollisionCache.Section section){open();Entry entry=entries.remove(section);packageUsage.remove(section);if(entry!=null){revoke(entry);serial++;}}
    public void clear(){open();for(Entry entry:entries.values())revoke(entry);entries.clear();packageUsage.clear();usageGeneration=Long.MIN_VALUE;serial++;}
    public void clearPackageUsage(){open();packageUsage.clear();usageGeneration=Long.MIN_VALUE;}
    /** Begin an exact usage set only when the completed scan references the current table version. */
    public boolean beginPackageUsage(long tableVersion) {
        open();if(tableVersion!=serial)return false;
        for(Bank bank:banks)if(bank.version==tableVersion){packageUsage.clear();usageGeneration=tableVersion;return true;}
        return false;
    }
    /** Resolve a GPU table row to its current section and protect that active swept region from eviction. */
    public PackageCollisionCache.Section touchPackageUsage(long tableVersion,int row) {
        open();if(tableVersion!=serial || usageGeneration!=tableVersion || row<0 || row>=tableSize)return null;
        for(Bank bank:banks)if(bank.version==tableVersion) {
            int p=row*HEAD_BYTES,slot=bank.mapped.getInt(p+12);
            if(slot<0 || bank.mapped.getInt(p+24)!=1)return null;
            var section=new PackageCollisionCache.Section(bank.mapped.getInt(p),bank.mapped.getInt(p+4),bank.mapped.getInt(p+8));
            Entry entry=entries.get(section);
            if(entry==null || entry.visible!=slot || entry.visibleRevision!=bank.mapped.getLong(p+16))return null;
            packageUsage.add(section);return section;
        }
        return null;
    }
    /** A CPU snapshot alone is never proof of GPU coverage. */
    public boolean covered(PackageCollisionCache.Section section,long revision) {
        open();Entry entry=entries.get(section);return entry!=null && entry.visible>=0 && entry.visibleRevision==revision;
    }
    /** True when a complete previous snapshot is available while a new one is being prepared. */
    public boolean covered(PackageCollisionCache.Section section) {
        open();Entry entry=entries.get(section);return entry!=null && entry.visible>=0;
    }
    private void collect() {
        for(Bank bank:banks)if(bank.fence!=0) {
            int status=GL32.glClientWaitSync(bank.fence,GL32.GL_SYNC_FLUSH_COMMANDS_BIT,0);
            if(status==GL32.GL_WAIT_FAILED)throw new IllegalStateException("World view fence failed");
            if(status!=GL32.GL_TIMEOUT_EXPIRED){GL32.glDeleteSync(bank.fence);bank.fence=0;}
        }
        for(int slot=retired.nextSetBit(0);slot>=0;slot=retired.nextSetBit(slot+1)) {
            boolean used=false;for(Bank bank:banks)if((bank.leased || bank.fence!=0) && bank.references.get(slot)){used=true;break;}
            if(!used){retired.clear(slot);free.addLast(slot);}
        }
    }
    /** Byte and soft time budgets. No fence wait or world access; incomplete versions remain absent. */
    public void pump(int maximumBytes,long budgetNanos) {
        open();if(viewOpen)throw new IllegalStateException("World upload during immutable view");
        if(maximumBytes<=0 || budgetNanos<=0)return;
        long start=clock.getAsLong();collect();int copied=0,attempts=pending.size();
        while(attempts-- >0 && !pending.isEmpty() && copied<maximumBytes && clock.getAsLong()-start<budgetNanos) {
            var iterator=pending.entrySet().iterator();Entry entry=iterator.next().getValue();iterator.remove();
            if(entry.staging<0){if(free.isEmpty()){pending.put(entry.section,entry);continue;}entry.staging=free.removeFirst();}
            int total=CELL_BYTES+entry.snapshot.gpuShapeCount()*32;
            int n=Math.min(Math.min(SLICE_BYTES,maximumBytes-copied),total-entry.progress);
            ByteBuffer source=entry.progress<CELL_BYTES?entry.cells:entry.boxes;source.clear();
            int offset=entry.progress<CELL_BYTES?entry.progress:entry.progress-CELL_BYTES;
            n=Math.min(n,source.limit()-offset);source.position(offset).limit(offset+n);
            mappedData.position(entry.staging*slotBytes+entry.progress);mappedData.put(source);
            entry.progress+=n;copied+=n;uploadedBytes+=n;
            if(entry.progress==total){
                int previous=entry.visible;entry.visible=entry.staging;entry.visibleRevision=entry.revision;entry.staging=-1;
                entry.visibleEmpty=entry.snapshot.gpuEmpty();
                if(previous>=0)retired.set(previous);
                entry.progress=0;entry.cells=null;entry.boxes=null;serial++;
            }
            else {pending.put(entry.section,entry);attempts++;}
        }
        lastUploadNanos=clock.getAsLong()-start;timings[timingCursor]=lastUploadNanos;timingCursor=(timingCursor+1)%timings.length;
        timingCount=Math.min(timingCount+1,timings.length);if(lastUploadNanos>budgetNanos)overruns++;
    }
    private static int hash(int x,int y,int z,int mask){return (x*73856093 ^ y*19349663 ^ z*83492791)&mask;}
    private static boolean validOrigin(int section){return section>=Integer.MIN_VALUE/16 && section<=Integer.MAX_VALUE/16-1;}
    /** One versioned view can serve all physics substeps in a submitted frame. */
    public View view(int originSectionX,int originSectionY,int originSectionZ) {
        return view(originSectionX,originSectionY,originSectionZ,true);
    }
    /** Internal validation switch: disabling coarse empty metadata keeps identical coverage. */
    public View view(int originSectionX,int originSectionY,int originSectionZ,boolean coarseEmpty) {
        if(!validOrigin(originSectionX) || !validOrigin(originSectionY) || !validOrigin(originSectionZ))
            throw new IllegalArgumentException("World origin outside signed block coordinates");
        open();if(viewOpen)throw new IllegalStateException("Nested world view");collect();
        Bank bank=null;
        // Reuse the same immutable table even while previous commands still read it. Its
        // replacement fence covers both submissions; unchanged worlds need no new bank.
        for(Bank candidate:banks)if(!candidate.leased && candidate.version==serial && candidate.coarse==coarseEmpty){bank=candidate;break;}
        if(bank==null)for(Bank candidate:banks)if(candidate.fence==0 && !candidate.leased){bank=candidate;break;}
        if(bank!=null) {
            if(bank.version!=serial || bank.coarse!=coarseEmpty) {
                empty(bank.mapped);bank.references.clear();
                for(Entry entry:entries.values())if(entry.visible>=0) {
                    var s=entry.section;int row=hash(s.x(),s.y(),s.z(),tableSize-1);
                    while(bank.mapped.getInt(row*HEAD_BYTES+12)!=-1)row=(row+1)&(tableSize-1);
                    int p=row*HEAD_BYTES;
                    bank.mapped.putInt(p,s.x()).putInt(p+4,s.y()).putInt(p+8,s.z()).putInt(p+12,entry.visible)
                            .putLong(p+16,entry.visibleRevision).putInt(p+24,1).putInt(p+28,coarseEmpty && entry.visibleEmpty?1:0);
                    bank.references.set(entry.visible);
                }
                bank.version=serial;bank.coarse=coarseEmpty;
            }
            bank.leased=true;
        } else skippedViews++;
        viewOpen=true;return new View(bank,serial,originSectionX,originSectionY,originSectionZ);
    }
    public final class View implements AutoCloseable {
        private final Bank bank;
        private final long version;
        private final int x,y,z;
        private boolean ended;
        private View(Bank bank,long version,int x,int y,int z){this.bank=bank;this.version=version;this.x=x;this.y=y;this.z=z;}
        public long version(){open();if(ended)throw new IllegalStateException("World view ended");return version;}
        /** True only while this view still names the current uploaded table generation. */
        public boolean ready(){open();if(ended)throw new IllegalStateException("World view ended");return bank!=null&&version==serial;}
        public boolean bind(int[] locations,int first,boolean bindBuffers) {
            open();if(ended)throw new IllegalStateException("World view ended");
            GL42.glMemoryBarrier(GL44.GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT);
            if(bindBuffers)try(var stack=MemoryStack.stackPush()){GL44.glBindBuffersBase(GL43.GL_SHADER_STORAGE_BUFFER,4,stack.ints(bank==null?banks[0].buffer:bank.buffer,data));}
            GL20.glUniform1i(locations[first],bank!=null && version==serial?1:0);
            GL20.glUniform3i(locations[first+1],x,y,z);
            GL30.glUniform1ui(locations[first+2],tableSize-1);
            GL30.glUniform1ui(locations[first+3],slotBytes/4);
            GL30.glUniform1ui(locations[first+4],shapeCapacity);
            return bank!=null && version==serial;
        }
        @Override public void close() {
            owner();if(ended)return;ended=true;viewOpen=false;
            if(bank!=null) {
                bank.leased=false;
                if(!closed) {
                    long fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);
                    if(fence==0){failed=true;bank.leased=true;throw new IllegalStateException("World view fence allocation failed");}
                    if(bank.fence!=0)GL32.glDeleteSync(bank.fence);bank.fence=fence;
                }
            }
        }
    }
    /** Cheap shared-budget accounting; percentile sorting belongs only in diagnostics. */
    public long uploadedBytes(){open();return uploadedBytes;}
    public Stats stats() {
        open();int ready=0;for(Entry entry:entries.values())if(entry.visible>=0)ready++;
        long[] sorted=Arrays.copyOf(timings,timingCount);Arrays.sort(sorted);
        return new Stats(entries.size(),ready,pending.size(),retired.cardinality(),uploadedBytes,capacityRejections,evictions,shapeRejections,skippedViews,lastUploadNanos,overruns,
                timingCount==0?0:sorted[(timingCount-1)/2],timingCount==0?0:sorted[(int)Math.ceil(timingCount*.95)-1]);
    }
    @Override public void close() {
        owner();if(closed)return;closed=true;
        for(Bank bank:banks)if(bank!=null){if(bank.fence!=0)GL32.glDeleteSync(bank.fence);if(bank.buffer!=0)GL15.glDeleteBuffers(bank.buffer);}
        if(data!=0)GL15.glDeleteBuffers(data);entries.clear();pending.clear();free.clear();retired.clear();
    }
}
