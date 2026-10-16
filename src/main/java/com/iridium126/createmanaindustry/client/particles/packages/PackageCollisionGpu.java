package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.function.LongSupplier;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/** Versioned, bounded world atlas. Persistent writes only touch slots no live GPU view references. */
public final class PackageCollisionGpu implements AutoCloseable {
    public static final int EMPTY_SLOT=-4,MAX_COVERAGE=1024;
    public record GeometryRevision(PackageCollisionCache.Section section,long revision) {}
    private record HistoricalSlot(int slot,boolean empty,int summary) {}
    public static final int CELL_BYTES=4096*16,HEAD_BYTES=32,BANKS=4,SLICE_BYTES=16384;
    public static final int DEFAULT_SECTIONS=256,DEFAULT_SHAPES=1024,DEFAULT_UPLOAD_BYTES=262144;
    public static final long DEFAULT_UPLOAD_NANOS=10_000_000;
    private static final int MAP_FLAGS=GL30.GL_MAP_WRITE_BIT|GL44.GL_MAP_PERSISTENT_BIT|GL44.GL_MAP_COHERENT_BIT;
    private static final class Entry {
        final PackageCollisionCache.Section section;
        /** Latest CPU revision requested for this section. */
        long revision;
        /** Revision in the currently visible slot; it remains usable during replacement upload. */
        long visibleRevision=Long.MIN_VALUE,visibleIdentity;
        int visible=-1,staging=-1,progress;
        boolean visibleEmpty;
        int visibleSummary;
        PackageCollisionCache.Snapshot snapshot;
        ByteBuffer cells,boxes;
        Entry(PackageCollisionCache.Section section){this.section=section;}
    }
    private record TableRow(int slot,long revision,int active,int flags) {}
    private Map<PackageCollisionCache.Section,TableRow> tableRows(boolean coarse,Map<PackageCollisionCache.Section,Long> versions) {
        var rows=new HashMap<PackageCollisionCache.Section,TableRow>();
        for(Entry entry:entries.values())if(visible(entry)&&(versions==null||Objects.equals(versions.get(entry.section),entry.visibleRevision)))
            rows.put(entry.section,new TableRow(entry.visible,entry.visibleRevision,1,entry.visibleSummary|(coarse&&entry.visibleEmpty?1:0)));
        if(versions!=null)for(var item:historical.entrySet()){
            var reference=item.getKey();var slot=item.getValue();
            if(!retainedHistory.contains(reference)||!Objects.equals(versions.get(reference.section()),reference.revision()))continue;
            rows.putIfAbsent(reference.section(),new TableRow(slot.slot(),reference.revision(),1,slot.summary()|(coarse&&slot.empty()?1:0)));
        }
        if(versions!=null)for(Entry entry:entries.values()){
            Long wanted=versions.get(entry.section);if(wanted==null||!visible(entry)||rows.containsKey(entry.section))continue;
            var reference=new GeometryRevision(entry.section,wanted);
            int wait=historicalUploads.containsKey(reference)||entry.cells!=null&&entry.snapshot!=null&&entry.snapshot.revision()==wanted?2:
                    entry.revision==wanted&&entry.snapshot==null?1:3;
            rows.put(entry.section,new TableRow(-2,wanted,0,wait));
        }
        return Map.copyOf(rows);
    }
    private static final class Bank {
        int buffer;ByteBuffer mapped;long fence;boolean leased;
        long version=Long.MIN_VALUE,historyVersion=Long.MIN_VALUE,tableToken;boolean coarse;
        Map<PackageCollisionCache.Section,Long> versions;
        Map<PackageCollisionCache.Section,TableRow> rows;
        final BitSet references=new BitSet();
    }
    public record Stats(int residents,int ready,int pending,int retired,long uploadedBytes,long capacityRejections,long evictions,
                        long shapeRejections,long skippedViews,long lastUploadNanos,long overruns,long p50Nanos,long p95Nanos,
                        int emptyCoverage,int historicalVersions) {}
    private static boolean visible(Entry entry){return entry.visible>=0||entry.visibleEmpty;}
    private static boolean dense(Entry entry){return entry.snapshot!=null?!entry.snapshot.gpuEmpty():entry.visible>=0;}
    private int residents(){int count=0;for(Entry entry:entries.values())if(dense(entry))count++;return count;}
    private final Thread owner=Thread.currentThread();
    private final int capacity,shapeCapacity,tableSize,slotBytes;
    private final LongSupplier clock;
    private final LinkedHashMap<PackageCollisionCache.Section,Entry> entries=new LinkedHashMap<>(16,.75f,true);
    private final LinkedHashMap<PackageCollisionCache.Section,Entry> pending=new LinkedHashMap<>();
    /** Superseded complete CPU snapshots may still be needed by input ticks not yet submitted. */
    private final LinkedHashMap<GeometryRevision,Entry> historicalUploads=new LinkedHashMap<>();
    private final Set<PackageCollisionCache.Section> packageUsage=new HashSet<>();
    private final Bank[] banks=new Bank[BANKS];
    private final ArrayDeque<Integer> free=new ArrayDeque<>();
    private final BitSet retired=new BitSet();
    private final Map<GeometryRevision,HistoricalSlot> historical=new HashMap<>();
    private final ArrayDeque<GeometryRevision> pendingEmptyHistory=new ArrayDeque<>();
    private final GeometryRevision[] slotRevisions;
    private Set<GeometryRevision> retainedHistory=Set.of();
    private final long[] timings=new long[128];
    private int timingCursor,timingCount,data;
    private ByteBuffer mappedData;
    private record OldData(int buffer,long fence) {}
    private final ArrayDeque<OldData> oldData=new ArrayDeque<>();
    private int allocatedSlots,lowUsageFrames;
    private long dataCopyFence;
    private long tableTokens;
    private long serial,historySerial,usageGeneration=Long.MIN_VALUE,uploadedBytes,capacityRejections,evictions,shapeRejections,skippedViews,lastUploadNanos,overruns;
    private boolean closed,viewOpen,failed;

    public PackageCollisionGpu(int capacity,int shapes){this(capacity,shapes,System::nanoTime);}
    /** Both visible and replacement slots must fit in one SSBO. No GL calls for policy tests. */
    public static int deviceCapacity(int requested,int shapes,long maximumBlockBytes) {
        if(requested<1||requested>1024||shapes<1||shapes>PackageCollisionCache.MAX_GPU_SHAPES)
            throw new IllegalArgumentException("World atlas capacity");
        long perSection=2L*(CELL_BYTES+shapes*32L);
        return (int)Math.max(0,Math.min(requested,Math.min(Integer.MAX_VALUE,maximumBlockBytes)/perSection));
    }
    public PackageCollisionGpu(int capacity,int shapes,LongSupplier clock) {
        if(capacity<=0 || capacity>1024 || shapes<=0 || shapes>PackageCollisionCache.MAX_GPU_SHAPES)
            throw new IllegalArgumentException("World atlas capacity");
        this.capacity=capacity;shapeCapacity=shapes;this.clock=Objects.requireNonNull(clock);
        slotRevisions=new GeometryRevision[capacity*2];
        tableSize=MAX_COVERAGE*2;slotBytes=CELL_BYTES+shapes*32;
        long bytes=(long)slotBytes*capacity*2;
        if(bytes>GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE) || bytes>Integer.MAX_VALUE)
            throw new IllegalArgumentException("World atlas exceeds device SSBO limit");
        try {
            data=GL15.glGenBuffers();mappedData=map(data,16);
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
        discardStaging(entry);entry.revision=revision;entry.snapshot=null;historySerial++;
    }
    /** References are registered at the next GL boundary, possibly after several client
     * ticks. Keep a bounded superseded upload until that boundary can decide its lifetime. */
    private void discardStaging(Entry entry) {
        if(pending.remove(entry.section)!=null&&entry.snapshot!=null) {
            var reference=new GeometryRevision(entry.section,entry.snapshot.revision());
            Entry existing=historicalUploads.get(reference);
            if(existing!=null&&existing.progress>=entry.progress) {
                // One immutable revision needs only the furthest-progressed copy.
            } else if(existing!=null||historicalUploads.size()<capacity) {
                if(existing!=null&&existing.staging>=0)free.addLast(existing.staging);
                Entry saved=new Entry(entry.section);saved.revision=entry.snapshot.revision();saved.snapshot=entry.snapshot;
                saved.staging=entry.staging;saved.progress=entry.progress;saved.cells=entry.cells;saved.boxes=entry.boxes;
                historicalUploads.put(reference,saved);entry.staging=-1;historySerial++;
            } else capacityRejections++; // Bounded overflow stays unavailable; never substitute a newer version.
        }
        if(entry.staging>=0){free.addLast(entry.staging);entry.staging=-1;}
        entry.progress=0;entry.cells=null;entry.boxes=null;
    }
    private void revoke(Entry entry) {
        if(visible(entry)){retire(entry,entry.visible);entry.visible=-1;}
        entry.visibleRevision=Long.MIN_VALUE;entry.visibleIdentity=0;entry.visibleEmpty=false;entry.visibleSummary=0;
        discardStaging(entry);entry.snapshot=null;
    }
    private void retire(Entry entry,int slot) {
        var reference=new GeometryRevision(entry.section,entry.visibleRevision);
        rememberHistory(reference,new HistoricalSlot(slot,entry.visibleEmpty,entry.visibleSummary));
        if(slot>=0){slotRevisions[slot]=reference;retired.set(slot);}
    }
    private void rememberHistory(GeometryRevision reference,HistoricalSlot slot){
        historical.put(reference,slot);
        if(slot.empty()&&!retainedHistory.contains(reference)){
            pendingEmptyHistory.addLast(reference);
            // Retention is registered at the next GL boundary. Keep this pending
            // metadata bounded without allocating a geometry slot for any version.
            while(pendingEmptyHistory.size()>MAX_COVERAGE)pruneEmpty(pendingEmptyHistory.removeFirst());
        }
    }
    private void pruneEmpty(GeometryRevision reference){
        var value=historical.get(reference);
        if(value!=null&&value.empty()&&!retainedHistory.contains(reference))historical.remove(reference);
    }
    /** Register immutable input references before uploading replacements. Physical storage
     * remains bounded by the existing two-slot-per-section allocation. */
    public void retainHistory(Set<GeometryRevision> retained) {
        open();if(viewOpen)throw new IllegalStateException("World history change during view");
        if(retainedHistory.equals(retained))return;
        var previous=retainedHistory;retainedHistory=Set.copyOf(retained);
        for(var reference:previous)if(!retainedHistory.contains(reference))pruneEmpty(reference);
        historySerial++;
    }
    /** CPU-only latest-version queue. Unsupported geometry/capacity stays with Create. */
    public boolean offer(PackageCollisionCache.Section section,PackageCollisionCache.Snapshot snapshot) {
        open();Objects.requireNonNull(section);Objects.requireNonNull(snapshot);
        Entry entry=entries.get(section);
        if(entry!=null && snapshot.revision()<entry.revision)return false;
        if(entry!=null && entry.snapshot==snapshot)return true;
        if(snapshot.revision()<=0 || !snapshot.gpuEmpty()&&(snapshot.gpuShapeCount()<0 || snapshot.gpuShapeCount()>shapeCapacity)) {
            shapeRejections++;if(entry!=null){revoke(entry);entry.revision=snapshot.revision();serial++;}return false;
        }
        ByteBuffer cells=snapshot.gpuCells(),boxes=snapshot.gpuBoxes();
        if(cells.remaining()!=CELL_BYTES || boxes.remaining()!=snapshot.gpuShapeCount()*32) {
            shapeRejections++;if(entry!=null){revoke(entry);entry.revision=snapshot.revision();serial++;}return false;
        }
        if(entry==null) {
            // Empty coverage has its own metadata budget and never evicts dense geometry.
            if(entries.size()>=MAX_COVERAGE && !evictLeastRecent(false)){capacityRejections++;return false;}
            if(!snapshot.gpuEmpty()&&residents()>=capacity&&!evictLeastRecent(true)){capacityRejections++;return false;}
            entry=new Entry(section);entries.put(section,entry);
        } else if(!snapshot.gpuEmpty()&&!dense(entry)&&residents()>=capacity&&!evictLeastRecent(true)) {
            capacityRejections++;return false;
        }
        // Keep the old visible slot until this replacement is completely uploaded.
        discardStaging(entry);entry.revision=snapshot.revision();entry.snapshot=snapshot;
        if(snapshot.gpuEmpty()) {
            if(visible(entry))retire(entry,entry.visible);
            entry.visible=EMPTY_SLOT;entry.visibleEmpty=true;entry.visibleSummary=0;entry.visibleRevision=snapshot.revision();entry.visibleIdentity=snapshot.gpuIdentity();
            serial++;historySerial++;return true;
        }
        entry.cells=cells;entry.boxes=boxes;
        pending.put(section,entry);historySerial++;return true;
    }
    /** An older coherent CPU capture serves only exact historical input, never live coverage. */
    public boolean offerHistorical(PackageCollisionCache.Section section,PackageCollisionCache.Snapshot snapshot) {
        open();var reference=new GeometryRevision(section,snapshot.revision());
        Entry live=entries.get(section);
        if(historical.containsKey(reference)||historicalUploads.containsKey(reference)
                ||live!=null&&visible(live)&&live.visibleRevision==snapshot.revision())return true;
        if(snapshot.revision()>0&&snapshot.gpuEmpty()) {
            rememberHistory(reference,new HistoricalSlot(EMPTY_SLOT,true,0));historySerial++;return true;
        }
        if(snapshot.revision()<=0||snapshot.gpuShapeCount()<0||snapshot.gpuShapeCount()>shapeCapacity
                ||snapshot.gpuCells().remaining()!=CELL_BYTES||snapshot.gpuBoxes().remaining()!=snapshot.gpuShapeCount()*32){shapeRejections++;return false;}
        if(historicalUploads.size()==capacity){capacityRejections++;return false;}
        Entry upload=new Entry(section);upload.revision=snapshot.revision();upload.snapshot=snapshot;upload.cells=snapshot.gpuCells();upload.boxes=snapshot.gpuBoxes();
        historicalUploads.put(reference,upload);historySerial++;return true;
    }
    /** Drop the oldest CPU mapping; its GPU slots remain retired until every table fence releases them. */
    private boolean evictLeastRecent(boolean nonempty) {
        var iterator=entries.entrySet().iterator();
        while(iterator.hasNext()) {
            var candidate=iterator.next();if(packageUsage.contains(candidate.getKey())||dense(candidate.getValue())!=nonempty)continue;
            Entry entry=candidate.getValue();iterator.remove();packageUsage.remove(entry.section);revoke(entry);serial++;evictions++;return true;
        }
        return false;
    }
    public void forget(PackageCollisionCache.Section section){open();Entry entry=entries.remove(section);packageUsage.remove(section);if(entry!=null){revoke(entry);serial++;}}
    public void clear(){open();for(Entry entry:entries.values())revoke(entry);entries.clear();historical.clear();pendingEmptyHistory.clear();retainedHistory=Set.of();
        for(Entry upload:historicalUploads.values())if(upload.staging>=0)free.addLast(upload.staging);historicalUploads.clear();
        packageUsage.clear();usageGeneration=Long.MIN_VALUE;serial++;}
    public void clearPackageUsage(){open();packageUsage.clear();usageGeneration=Long.MIN_VALUE;}
    /** Begin an exact usage set only when the completed scan references the current table version. */
    public boolean beginPackageUsage(long tableVersion) {
        open();
        for(Bank bank:banks)if(bank.tableToken==tableVersion&&bank.version==serial){packageUsage.clear();usageGeneration=tableVersion;return true;}
        return false;
    }
    /** Resolve a GPU table row to its current section and protect that active swept region from eviction. */
    public PackageCollisionCache.Section touchPackageUsage(long tableVersion,int row) {
        open();if(usageGeneration!=tableVersion || row<0 || row>=tableSize)return null;
        for(Bank bank:banks)if(bank.tableToken==tableVersion&&bank.version==serial) {
            int p=row*HEAD_BYTES,slot=bank.mapped.getInt(p+12);
            if(slot<0&&slot!=EMPTY_SLOT || bank.mapped.getInt(p+24)!=1)return null;
            var section=new PackageCollisionCache.Section(bank.mapped.getInt(p),bank.mapped.getInt(p+4),bank.mapped.getInt(p+8));
            Entry entry=entries.get(section);
            if(entry==null || entry.visible!=slot || entry.visibleRevision!=bank.mapped.getLong(p+16))return null;
            packageUsage.add(section);return section;
        }
        return null;
    }
    /** A CPU snapshot alone is never proof of GPU coverage. */
    public boolean covered(PackageCollisionCache.Section section,long revision) {
        open();Entry entry=entries.get(section);return entry!=null && visible(entry) && entry.visibleRevision==revision;
    }
    /** True when a complete previous snapshot is available while a new one is being prepared. */
    public boolean covered(PackageCollisionCache.Section section) {
        open();Entry entry=entries.get(section);return entry!=null && visible(entry);
    }
    private void collect() {
        var old=oldData.iterator();
        while(old.hasNext()) {
            OldData buffer=old.next();int status=GL32.glClientWaitSync(buffer.fence(),0,0);
            if(status==GL32.GL_WAIT_FAILED)throw new IllegalStateException("World data fence failed");
            if(status!=GL32.GL_TIMEOUT_EXPIRED){if(dataCopyFence==buffer.fence())dataCopyFence=0;GL32.glDeleteSync(buffer.fence());GL15.glDeleteBuffers(buffer.buffer());old.remove();}
        }
        while(!pendingEmptyHistory.isEmpty())pruneEmpty(pendingEmptyHistory.removeFirst());
        for(Bank bank:banks)if(bank.fence!=0) {
            int status=GL32.glClientWaitSync(bank.fence,GL32.GL_SYNC_FLUSH_COMMANDS_BIT,0);
            if(status==GL32.GL_WAIT_FAILED)throw new IllegalStateException("World view fence failed");
            if(status!=GL32.GL_TIMEOUT_EXPIRED){GL32.glDeleteSync(bank.fence);bank.fence=0;}
        }
        for(int slot=retired.nextSetBit(0);slot>=0;slot=retired.nextSetBit(slot+1)) {
            var reference=slotRevisions[slot];var historicalSlot=historical.get(reference);
            if(historicalSlot!=null&&historicalSlot.slot()==slot&&retainedHistory.contains(reference))continue;
            boolean used=false;for(Bank bank:banks)if((bank.leased || bank.fence!=0) && bank.references.get(slot)){used=true;break;}
            if(!used){
                if(historicalSlot!=null&&historicalSlot.slot()==slot)historical.remove(reference);
                slotRevisions[slot]=null;retired.clear(slot);free.addLast(slot);
            }
        }
    }
    /** Byte and soft time budgets. No fence wait or world access; incomplete versions remain absent. */
    public void nextFrame() {
        open();if(viewOpen)throw new IllegalStateException("World frame during immutable view");
        collect();shrinkData();
    }
    public void pump(int maximumBytes,long budgetNanos) {
        open();if(viewOpen)throw new IllegalStateException("World upload during immutable view");
        if(maximumBytes<=0 || budgetNanos<=0)return;
        long start=clock.getAsLong();collect();int copied=0;
        var obsolete=historicalUploads.entrySet().iterator();
        while(obsolete.hasNext()) {
            var row=obsolete.next();
            if(retainedHistory.contains(row.getKey())&&!historical.containsKey(row.getKey()))continue;
            if(row.getValue().staging>=0)free.addLast(row.getValue().staging);obsolete.remove();historySerial++;
        }
        // Reserve the queued staging demand in one growth operation. Growing once per
        // first slice would repeatedly copy the atlas and stall unrelated small uploads.
        int waiting=0;for(Entry entry:pending.values())if(entry.staging<0)waiting++;
        for(Entry entry:historicalUploads.values())if(entry.staging<0)waiting++;
        if(waiting>0){int needed=allocatedSlots;for(int slot:free){if(waiting--==0)break;needed=Math.max(needed,slot+1);}ensureData(needed);}
        // Occupied sections get the first opportunity within the existing budget.
        // Keep older input first within each priority, and keep background work
        // queued for the remaining budget rather than allowing it to evict or delay users.
        boolean background=pending.values().stream().anyMatch(e->!packageUsage.contains(e.section))
                ||historicalUploads.values().stream().anyMatch(e->!packageUsage.contains(e.section));
        int reserve=background&&maximumBytes>=2*SLICE_BYTES?Math.min(SLICE_BYTES,maximumBytes/4):0;
        for(int priority=packageUsage.isEmpty()?1:0;priority<3;priority++) {
            boolean occupied=priority!=1;int limit=priority==0?maximumBytes-reserve:maximumBytes;
            int attempts=historicalUploads.size();
            while(attempts-- >0&&!historicalUploads.isEmpty()&&copied<limit&&clock.getAsLong()-start<budgetNanos) {
                var iterator=historicalUploads.entrySet().iterator();var row=iterator.next();var reference=row.getKey();Entry upload=row.getValue();iterator.remove();
                if(packageUsage.contains(upload.section)!=occupied){historicalUploads.put(reference,upload);continue;}
                if(upload.staging<0){if(free.isEmpty()){historicalUploads.put(reference,upload);continue;}upload.staging=free.removeFirst();}
                copied+=copySlice(upload,limit-copied);
                if(upload.progress==CELL_BYTES+upload.snapshot.gpuShapeCount()*32) {
                    upload.visibleRevision=upload.snapshot.revision();upload.visibleEmpty=upload.snapshot.gpuEmpty();upload.visibleSummary=upload.snapshot.environmentSummary();retire(upload,upload.staging);historySerial++;
                } else {historicalUploads.put(reference,upload);if(dataReady())attempts++;}
            }
            attempts=pending.size();
            while(attempts-- >0 && !pending.isEmpty() && copied<limit && clock.getAsLong()-start<budgetNanos) {
                var iterator=pending.entrySet().iterator();Entry entry=iterator.next().getValue();iterator.remove();
                if(packageUsage.contains(entry.section)!=occupied){pending.put(entry.section,entry);continue;}
                int patched=patchVisible(entry,Math.min(SLICE_BYTES,limit-copied));
                if(patched>=0){copied+=patched;continue;}
                if(entry.staging<0){if(free.isEmpty()){pending.put(entry.section,entry);continue;}entry.staging=free.removeFirst();}
                int total=CELL_BYTES+entry.snapshot.gpuShapeCount()*32;
                copied+=copySlice(entry,limit-copied);
                if(entry.progress==total){
                    int previous=entry.visible;if(visible(entry))retire(entry,previous);
                    entry.visible=entry.staging;entry.visibleRevision=entry.revision;entry.visibleIdentity=entry.snapshot.gpuIdentity();entry.staging=-1;
                    entry.visibleEmpty=entry.snapshot.gpuEmpty();entry.visibleSummary=entry.snapshot.environmentSummary();
                    entry.progress=0;entry.cells=null;entry.boxes=null;serial++;
                }
                else {pending.put(entry.section,entry);if(dataReady())attempts++;}
            }
        }
        lastUploadNanos=clock.getAsLong()-start;timings[timingCursor]=lastUploadNanos;timingCursor=(timingCursor+1)%timings.length;
        timingCount=Math.min(timingCount+1,timings.length);if(lastUploadNanos>budgetNanos)overruns++;
    }
    /** Atomic owner-thread patch only after every previous GPU reader has completed.
     * Historical input and incompatible dictionaries continue through the versioned copy path. */
    private int patchVisible(Entry entry,int allowance) {
        if(!dataReady())return -1;
        var patch=entry.snapshot.gpuPatch(entry.visibleIdentity,entry.visibleRevision);
        if(patch==null||entry.visible<0
                ||entry.visibleRevision!=patch.baseRevision()||entry.visibleIdentity!=patch.baseIdentity()||patch.bytes()>allowance
                ||retainedHistory.contains(new GeometryRevision(entry.section,entry.visibleRevision)))return -1;
        for(Bank bank:banks)if((bank.leased||bank.fence!=0)&&bank.references.get(entry.visible))return -1;
        // All writes finish in this call before the new table revision becomes visible.
        // No mutable view exists and no future input still needs the overwritten revision.
        int at=entry.visible*slotBytes;
        for(int cell:patch.cells()){
            var bytes=entry.snapshot.gpuCells();bytes.position(cell*16).limit(cell*16+16);
            mappedData.position(at+cell*16);mappedData.put(bytes);
        }
        int prefix=patch.baseShapes()*32;
        if(entry.snapshot.gpuBoxes().remaining()>prefix){
            var boxes=entry.snapshot.gpuBoxes();boxes.position(prefix);
            mappedData.position(at+CELL_BYTES+prefix);mappedData.put(boxes);
        }
        entry.visibleRevision=entry.revision;entry.visibleIdentity=entry.snapshot.gpuIdentity();entry.visibleEmpty=entry.snapshot.gpuEmpty();entry.visibleSummary=entry.snapshot.environmentSummary();
        if(entry.staging>=0){free.addLast(entry.staging);entry.staging=-1;}
        entry.progress=0;entry.cells=null;entry.boxes=null;uploadedBytes+=patch.bytes();serial++;
        return patch.bytes();
    }
    private int copySlice(Entry entry,int maximumBytes) {
        ensureData(entry.staging+1);
        if(!dataReady())return 0;
        int total=CELL_BYTES+entry.snapshot.gpuShapeCount()*32;
        int n=Math.min(Math.min(SLICE_BYTES,maximumBytes),total-entry.progress);
        ByteBuffer source=entry.progress<CELL_BYTES?entry.cells:entry.boxes;source.clear();
        int offset=entry.progress<CELL_BYTES?entry.progress:entry.progress-CELL_BYTES;
        n=Math.min(n,source.limit()-offset);source.position(offset).limit(offset+n);
        mappedData.position(entry.staging*slotBytes+entry.progress);mappedData.put(source);
        entry.progress+=n;uploadedBytes+=n;return n;
    }
    /** Existing slot offsets never change during growth; prior commands retain the old buffer. */
    private void ensureData(int slots) {
        if(slots<=allocatedSlots)return;
        int next=Math.min(capacity*2,slots<=1?1:Integer.highestOneBit(slots-1)<<1);
        resizeData(next);lowUsageFrames=0;
    }
    private boolean dataReady() {
        if(dataCopyFence==0)return true;
        int status=GL32.glClientWaitSync(dataCopyFence,GL32.GL_SYNC_FLUSH_COMMANDS_BIT,0);
        if(status==GL32.GL_WAIT_FAILED)throw new IllegalStateException("World data copy failed");
        return status!=GL32.GL_TIMEOUT_EXPIRED;
    }
    private void resizeData(int slots) {resizeData(slots,null);}
    private void resizeData(int slots,Map<Integer,Integer> remap) {
        int replacement=GL15.glGenBuffers();ByteBuffer mapped;
        try{mapped=map(replacement,Math.max(16,slots*slotBytes));}
        catch(RuntimeException failure){GL15.glDeleteBuffers(replacement);throw failure;}
        GL42.glMemoryBarrier(GL44.GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,data);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,replacement);
        if(remap!=null)for(var row:remap.entrySet())GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,
                (long)row.getKey()*slotBytes,(long)row.getValue()*slotBytes,slotBytes);
        else {int copied=Math.min(allocatedSlots,slots)*slotBytes;
            if(copied>0)GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,0,0,copied);}
        long fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);
        if(fence==0){GL15.glDeleteBuffers(replacement);throw new IllegalStateException("World resize fence unavailable");}
        oldData.addLast(new OldData(data,fence));
        dataCopyFence=allocatedSlots==0?0:fence;
        data=replacement;mappedData=mapped;allocatedSlots=slots;
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT);
    }
    private void shrinkData() {
        if(allocatedSlots==0||!dataReady())return;
        BitSet used=new BitSet();used.set(0,capacity*2);for(int slot:free)used.clear(slot);
        if(used.cardinality()*4>=allocatedSlots){lowUsageFrames=0;return;}
        if(++lowUsageFrames<120)return;
        for(Bank bank:banks)if(bank.leased||bank.fence!=0)return;
        // Compact only after every immutable GPU reader has finished. History keeps its revisions.
        var remap=new HashMap<Integer,Integer>();int target=0;
        for(int slot=used.nextSetBit(0);slot>=0;slot=used.nextSetBit(slot+1))remap.put(slot,target++);
        for(Entry entry:entries.values()){if(entry.visible>=0)entry.visible=remap.get(entry.visible);if(entry.staging>=0)entry.staging=remap.get(entry.staging);}
        for(Entry entry:historicalUploads.values())if(entry.staging>=0)entry.staging=remap.get(entry.staging);
        historical.replaceAll((key,value)->value.slot()<0?value:new HistoricalSlot(remap.get(value.slot()),false,value.summary()));
        var nextRevisions=new GeometryRevision[slotRevisions.length];var nextRetired=new BitSet();
        for(int slot=retired.nextSetBit(0);slot>=0;slot=retired.nextSetBit(slot+1)){int to=remap.get(slot);nextRevisions[to]=slotRevisions[slot];nextRetired.set(to);}
        System.arraycopy(nextRevisions,0,slotRevisions,0,slotRevisions.length);retired.clear();retired.or(nextRetired);
        free.clear();for(int slot=target;slot<capacity*2;slot++)free.addLast(slot);
        int slots=target==0?0:target==1?1:Math.min(capacity*2,Integer.highestOneBit(target-1)<<1);
        resizeData(slots,remap);serial++;historySerial++;lowUsageFrames=0;
        for(Bank bank:banks){bank.rows=null;bank.version=Long.MIN_VALUE;bank.references.clear();}
    }
    private static int hash(int x,int y,int z,int mask){return (x*73856093 ^ y*19349663 ^ z*83492791)&mask;}
    private static boolean validOrigin(int section){return section>=Integer.MIN_VALUE/16 && section<=Integer.MAX_VALUE/16-1;}
    /** One versioned view can serve all physics substeps in a submitted frame. */
    public View view(int originSectionX,int originSectionY,int originSectionZ) {
        return view(originSectionX,originSectionY,originSectionZ,true);
    }
    /** Internal validation switch: disabling coarse empty metadata keeps identical coverage. */
    public View view(int originSectionX,int originSectionY,int originSectionZ,boolean coarseEmpty) {
        return view(originSectionX,originSectionY,originSectionZ,coarseEmpty,null);
    }
    /** Missing historical versions stay absent, so only bodies querying them pause. */
    public View historicalView(int x,int y,int z,Map<PackageCollisionCache.Section,Long> versions){
        if(versions.size()>MAX_COVERAGE)throw new IllegalArgumentException("Historical world coverage exceeds metadata capacity");
        return view(x,y,z,true,Map.copyOf(versions));
    }
    private View view(int originSectionX,int originSectionY,int originSectionZ,boolean coarseEmpty,Map<PackageCollisionCache.Section,Long> versions) {
        if(!validOrigin(originSectionX)||!validOrigin(originSectionY)||!validOrigin(originSectionZ))throw new IllegalArgumentException("World origin outside signed block coordinates");
        open();if(viewOpen)throw new IllegalStateException("Nested world view");collect();
        Bank bank=null;Map<PackageCollisionCache.Section,TableRow> rows=null;
        for(Bank candidate:banks)if(!candidate.leased&&candidate.version==serial
                &&(versions==null||candidate.historyVersion==historySerial)&&candidate.coarse==coarseEmpty&&Objects.equals(candidate.versions,versions)){bank=candidate;break;}
        if(bank==null){
            rows=tableRows(coarseEmpty,versions);
            // Identical mapped bytes can be read again while the old submission is in flight.
            // Unresident requests/retention changes alone must not consume another table bank.
            for(Bank candidate:banks)if(!candidate.leased&&rows.equals(candidate.rows)){bank=candidate;break;}
            if(bank==null)for(Bank candidate:banks)if(candidate.fence==0&&!candidate.leased){bank=candidate;break;}
        }
        if(bank!=null){
            if(rows!=null&&!rows.equals(bank.rows)){
                empty(bank.mapped);bank.references.clear();
                for(var item:rows.entrySet()){
                    var key=item.getKey();var value=item.getValue();int row=hash(key.x(),key.y(),key.z(),tableSize-1);
                    while(bank.mapped.getInt(row*HEAD_BYTES+12)!=-1)row=(row+1)&(tableSize-1);
                    int p=row*HEAD_BYTES;
                    bank.mapped.putInt(p,key.x()).putInt(p+4,key.y()).putInt(p+8,key.z()).putInt(p+12,value.slot)
                            .putLong(p+16,value.revision).putInt(p+24,value.active).putInt(p+28,value.flags);
                    if(value.slot>=0)bank.references.set(value.slot);
                }
                bank.rows=rows;bank.tableToken=++tableTokens;
            }
            bank.version=serial;bank.historyVersion=historySerial;bank.coarse=coarseEmpty;bank.versions=versions;bank.leased=true;
        }else skippedViews++;
        viewOpen=true;return new View(bank,serial,originSectionX,originSectionY,originSectionZ);
    }
    public final class View implements AutoCloseable {
        private final Bank bank;
        private final long version,historyVersion;
        private final int x,y,z;
        private boolean ended;
        private View(Bank bank,long version,int x,int y,int z){this.bank=bank;this.version=version;historyVersion=bank!=null&&bank.versions!=null?bank.historyVersion:Long.MIN_VALUE;this.x=x;this.y=y;this.z=z;}
        public long version(){open();if(ended)throw new IllegalStateException("World view ended");return bank==null?0:bank.tableToken;}
        /** True only while this view still names the current uploaded table generation. */
        public boolean ready(){open();if(ended)throw new IllegalStateException("World view ended");return bank!=null&&version==serial&&(historyVersion==Long.MIN_VALUE||historyVersion==historySerial);}
        public boolean bind(int[] locations,int first,boolean bindBuffers) {
            open();if(ended)throw new IllegalStateException("World view ended");
            GL42.glMemoryBarrier(GL44.GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT);
            if(bindBuffers)try(var stack=MemoryStack.stackPush()){GL44.glBindBuffersBase(GL43.GL_SHADER_STORAGE_BUFFER,4,stack.ints(bank==null?banks[0].buffer:bank.buffer,data));}
            GL20.glUniform1i(locations[first],ready()?1:0);
            GL20.glUniform3i(locations[first+1],x,y,z);
            GL30.glUniform1ui(locations[first+2],tableSize-1);
            GL30.glUniform1ui(locations[first+3],slotBytes/4);
            GL30.glUniform1ui(locations[first+4],shapeCapacity);
            return ready();
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
    /** Numeric progress at log time, not a reconstruction of a deferred freeze event. */
    public String describeUpload(PackageCollisionCache.Section section,long required) {
        open();var reference=new GeometryRevision(section,required);
        Entry entry=entries.get(section);
        if(retainedHistory.contains(reference)&&historical.containsKey(reference))return " observedUploadRevision="+required+" observedUpload=historical-complete";
        if(entry!=null&&entry.visibleRevision==required)return " observedUploadRevision="+required+" observedUpload=complete";
        Entry upload=historicalUploads.get(reference);if(upload==null)upload=entry;
        if(upload==null)return " observedUpload=unresident";
        if(upload==entry&&upload.visibleRevision==upload.revision)return " observedUploadRevision="+upload.revision+" observedUpload=complete-other-revision";
        if(upload.snapshot==null)return " observedUploadRevision="+upload.revision+" observedUpload=capture";
        var patch=upload.snapshot.gpuPatch(upload.visibleIdentity,upload.visibleRevision);if(patch==null)patch=upload.snapshot.gpuPatch();String gate="dictionary-or-full-capture";
        if(patch!=null){
            gate=upload.visibleRevision!=patch.baseRevision()?"base-revision":upload.visibleIdentity!=patch.baseIdentity()?"base-snapshot":
                    retainedHistory.contains(new GeometryRevision(section,upload.visibleRevision))?"history":"eligible";
            if(gate.equals("eligible"))for(Bank bank:banks)if((bank.leased||bank.fence!=0)&&bank.references.get(upload.visible)){gate="gpu-reader";break;}
        }
        return " observedUploadRevision="+upload.revision+" observedUpload=copy observedUploadBytes="+upload.progress+"/"+
                (CELL_BYTES+upload.snapshot.gpuShapeCount()*32)+" observedPatchBytes="+(patch==null?-1:patch.bytes())+" observedPatchGate="+gate;
    }
    /** Cheap shared-budget accounting; percentile sorting belongs only in diagnostics. */
    public long uploadedBytes(){open();return uploadedBytes;}
    public Stats stats() {
        open();int ready=0,empty=0;for(Entry entry:entries.values())if(visible(entry)){ready++;if(entry.visibleEmpty)empty++;}
        long[] sorted=Arrays.copyOf(timings,timingCount);Arrays.sort(sorted);
        return new Stats(residents(),ready,pending.size()+historicalUploads.size(),retired.cardinality(),uploadedBytes,capacityRejections,evictions,shapeRejections,skippedViews,lastUploadNanos,overruns,
                timingCount==0?0:sorted[(timingCount-1)/2],timingCount==0?0:sorted[(int)Math.ceil(timingCount*.95)-1],empty,historical.size());
    }
    @Override public void close() {
        owner();if(closed)return;closed=true;
        for(Bank bank:banks)if(bank!=null){if(bank.fence!=0)GL32.glDeleteSync(bank.fence);if(bank.buffer!=0)GL15.glDeleteBuffers(bank.buffer);}
        if(data!=0)GL15.glDeleteBuffers(data);entries.clear();pending.clear();historicalUploads.clear();free.clear();retired.clear();historical.clear();pendingEmptyHistory.clear();retainedHistory=Set.of();
        for(OldData old:oldData){GL32.glDeleteSync(old.fence());GL15.glDeleteBuffers(old.buffer());}oldData.clear();
    }
}
