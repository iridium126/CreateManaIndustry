package com.iridium126.createmanaindustry.client.packages.world;


import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

class PackageCollisionCacheTest {
    @Test void provenEmptySectionPublishesWithoutCellCaptureOrWorkerAllocation() {
        var tasks=new ArrayDeque<Runnable>();var cache=new PackageCollisionCache(tasks::add,2,()->0L);
        var source=new PackageCollisionCache.Source(){
            public boolean empty(PackageCollisionCache.Section section){return true;}
            public PackageCollisionCache.Cell capture(PackageCollisionCache.Section section,int index){throw new AssertionError("empty section sampled a cell");}
        };
        cache.request(SECTION);cache.tick(source,1);
        var first=cache.snapshot(SECTION);assertNotNull(first);assertTrue(first.gpuEmpty());assertTrue(tasks.isEmpty());
        var other=new PackageCollisionCache.Section(1,0,0);cache.request(other);cache.tick(source,1);
        assertNotEquals(first.gpuIdentity(),cache.snapshot(other).gpuIdentity());
        assertEquals(0,first.shapeStart(3000));assertEquals(.6f,first.friction(3000));
        cache.invalidate(SECTION);assertNull(cache.snapshot(SECTION));cache.tick(source,1);
        assertTrue(cache.snapshot(SECTION).revision()>first.revision());
    }
    @Test void forceInterestFollowsOccupiedSectionsAcrossNegativeAndPositiveRegionBoundaries() {
        var cache=new PackageCollisionCache(Runnable::run,8,()->0L);
        var sections=List.of(new PackageCollisionCache.Section(-1,0,0),new PackageCollisionCache.Section(0,0,0),
                new PackageCollisionCache.Section(4,-1,0),new PackageCollisionCache.Section(7,-1,0));
        for(var section:sections){cache.request(section);cache.protectPackageUsage(section);}
        var anchor=new com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageRegion(-1,0,0);
        var result=cache.forceRegions(List.of(anchor));
        assertEquals(List.of(anchor,new com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageRegion(0,0,0),
                new com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageRegion(1,-1,0)),result);
        assertEquals(result,cache.forceRegions(List.of(anchor,anchor)));
        cache.clearPackageUsage();assertEquals(List.of(anchor),cache.forceRegions(List.of(anchor)));
    }
    private static final PackageCollisionCache.Section SECTION=new PackageCollisionCache.Section(0,0,0);
    private static final PackageCollisionCache.Cell AIR=new PackageCollisionCache.Cell(List.of(),.6f,0);
    @Test void anObsoleteWorkerCannotBlockLocalRescueOrOverwriteLaterNotifications(){
        var tasks=new ArrayDeque<Runnable>();var cache=new PackageCollisionCache(tasks::add,1,()->0L);cache.request(SECTION);
        cache.tick((s,c)->AIR,1);tasks.remove().run();cache.tick((s,c)->AIR,0);int cell=8|8<<4|8<<8;
        var complex=new PackageCollisionCache.Cell(java.util.Collections.nCopies(65,new PackageCollisionCache.Box(0,0,0,1,1,1)),.6f,0);
        cache.invalidateChangedBlock(8,8,8,(s,c)->c==cell?complex:AIR);cache.tick((s,c)->c==cell?complex:AIR,1);
        long obsolete=cache.versions().get(SECTION);assertEquals(1,tasks.size());
        var slab=new PackageCollisionCache.Cell(List.of(new PackageCollisionCache.Box(0,0,0,1,.5f,1)),.6f,0);
        cache.invalidateChangedBlock(8,8,8,(s,c)->c==cell?slab:AIR);cache.tick((s,c)->c==cell?slab:AIR,1);
        assertNotNull(cache.snapshot(SECTION));assertEquals(1,tasks.size(),"rescue must not add a new worker");
        var quarter=new PackageCollisionCache.Cell(List.of(new PackageCollisionCache.Box(0,0,0,1,.25f,1)),.8f,0);
        cache.invalidateChangedBlock(8,8,8,(s,c)->c==cell?quarter:AIR);var latest=cache.snapshot(SECTION);assertNotNull(latest);
        var history=new java.util.ArrayList<PackageCollisionCache.Snapshot>();cache.listener(new PackageCollisionCache.Listener(){
            public void historical(PackageCollisionCache.Section section,PackageCollisionCache.Snapshot result){history.add(result);}
        });
        tasks.remove().run();cache.tick((s,c)->{fail("ready local snapshots must not recapture");return AIR;},0);
        assertSame(latest,cache.snapshot(SECTION));assertEquals(Boolean.TRUE,cache.capturedCellMatches(SECTION,cell,quarter));
        assertEquals(1,history.size());assertEquals(obsolete,history.getFirst().revision());
    }
    @Test void zeroCaptureBudgetDoesNotReadDirtyCellsForRescue(){
        var cache=new PackageCollisionCache(Runnable::run,1,()->0L);cache.request(SECTION);
        cache.tick((s,c)->AIR,1);cache.tick((s,c)->AIR,0);
        cache.invalidateChangedBlock(8,8,8,(s,c)->null);
        cache.tick((s,c)->{fail("zero-budget rescue read mutable world state");return AIR;},0);assertNull(cache.snapshot(SECTION));
    }
    @Test void captureTickRescuesAColdLocalBudgetFailureWithoutSubmittingAWorker(){
        var tasks=new ArrayDeque<Runnable>();var time=new AtomicLong();boolean[] cold={false};
        var cache=new PackageCollisionCache(tasks::add,1,()->cold[0]?time.getAndAdd(600_000):time.get());cache.request(SECTION);
        cache.tick((s,c)->AIR,1);tasks.remove().run();cache.tick((s,c)->AIR,0);
        var solid=new PackageCollisionCache.Cell(List.of(new PackageCollisionCache.Box(0,0,0,1,1,1)),.6f,0);int cell=8|8<<4|8<<8;
        cold[0]=true;cache.invalidateChangedBlock(8,8,8,(s,c)->c==cell?solid:AIR);assertNull(cache.snapshot(SECTION));
        long wanted=cache.versions().get(SECTION);cold[0]=false;int[] reads={0};
        cache.tick((s,c)->{reads[0]++;return c==cell?solid:AIR;},10_000);
        assertNotNull(cache.snapshot(SECTION),"pending local work must retry after the notification's cold budget failure");
        assertEquals(wanted,cache.snapshot(SECTION).revision());assertTrue(tasks.isEmpty());
        assertEquals(1,reads[0],"rescue should reread only the dirty cell");
    }
    @Test void continuousLocalNotificationsKeepAnOlderGpuBaselineWithoutWorkerJobs(){
        var tasks=new ArrayDeque<Runnable>();var cache=new PackageCollisionCache(tasks::add,1,()->0L);cache.request(SECTION);
        cache.tick((s,c)->AIR,1);tasks.remove().run();cache.tick((s,c)->AIR,0);var base=cache.snapshot(SECTION);int cell=8|8<<4|8<<8;
        for(int i=1;i<=7;i++){
            var value=new PackageCollisionCache.Cell(List.of(new PackageCollisionCache.Box(0,0,0,1,i/10f,1)),.6f,0);
            cache.invalidateChangedBlock(8,8,8,(s,c)->c==cell?value:AIR);
            assertNotNull(cache.snapshot(SECTION));assertTrue(tasks.isEmpty());assertEquals(Boolean.TRUE,cache.capturedCellMatches(SECTION,cell,value));
        }
        var latest=cache.snapshot(SECTION);var patch=latest.gpuPatch(base.gpuIdentity(),base.revision());
        assertNotNull(patch);assertEquals(List.of(cell),patch.cells());assertEquals(240,patch.bytes());
        assertEquals(0,base.shapeEnd(4095));assertEquals(0,base.gpuCells().getInt(cell*16+4));
        assertTrue(cache.describeCapture(SECTION,latest.revision()).contains("observedCaptureMode=local"));
        for(int i=8;i<=20;i++){
            var value=new PackageCollisionCache.Cell(List.of(),i/10f,0);cache.invalidateChangedBlock(8,8,8,(s,c)->c==cell?value:AIR);
        }
        assertNull(cache.snapshot(SECTION).gpuPatch(base.gpuIdentity(),base.revision()),"old proof history must be bounded");
    }
    @Test void aFailedNeighbourSampleCannotPublishOnlyTheOtherKnownLocalChanges(){
        var cache=new PackageCollisionCache(Runnable::run,1,()->0L);cache.request(SECTION);cache.tick((s,c)->AIR,1);cache.tick((s,c)->AIR,0);
        int cell=8|8<<4|8<<8;var solid=new PackageCollisionCache.Cell(List.of(new PackageCollisionCache.Box(0,0,0,1,1,1)),.6f,0);
        cache.invalidateChangedBlock(8,8,8,(s,c)->{if(c==cell+1)throw new IllegalStateException("unavailable neighbor");return c==cell?solid:AIR;});
        assertNull(cache.snapshot(SECTION),"a missing neighbour must revoke the entire unknown replacement until recapture");
    }
    @Test void localTimeBudgetExpiryFallsBackWithoutChangingTheBaseline(){
        var time=new AtomicLong();boolean[] bounded={false};
        var cache=new PackageCollisionCache(Runnable::run,1,()->bounded[0]?time.getAndAdd(600_000):time.get());cache.request(SECTION);
        cache.tick((s,c)->AIR,1);cache.tick((s,c)->AIR,0);var base=cache.snapshot(SECTION);
        var solid=new PackageCollisionCache.Cell(List.of(new PackageCollisionCache.Box(0,0,0,1,1,1)),.6f,0);bounded[0]=true;
        cache.invalidateChangedBlock(8,8,8,(s,c)->c==(8|8<<4|8<<8)?solid:AIR);
        assertNull(cache.snapshot(SECTION));assertEquals(0,base.shapeEnd(4095));
        bounded[0]=false;cache.tick((s,c)->c==(8|8<<4|8<<8)?solid:AIR,1);cache.tick((s,c)->AIR,0);
        assertEquals(Boolean.TRUE,cache.capturedCellMatches(SECTION,8|8<<4|8<<8,solid));
    }
    @Test void aSmallLocalEditPublishesImmediatelyWithoutAnotherWorkerOrWorldRecapture(){
        var tasks=new ArrayDeque<Runnable>();var cache=new PackageCollisionCache(tasks::add,1,()->0L);cache.request(SECTION);
        cache.tick((s,c)->AIR,1);tasks.remove().run();cache.tick((s,c)->AIR,0);var old=cache.snapshot(SECTION);
        var solid=new PackageCollisionCache.Cell(List.of(new PackageCollisionCache.Box(0,0,0,1,1,1)),.8f,16);int cell=8|8<<4|8<<8;
        cache.invalidateChangedBlock(8,8,8,(s,c)->c==cell?solid:AIR);
        var updated=cache.snapshot(SECTION);assertNotNull(updated,"known local changes must not wait for a whole-section capture/worker");
        assertTrue(tasks.isEmpty());assertTrue(updated.revision()>old.revision());
        assertEquals(Boolean.TRUE,cache.capturedCellMatches(SECTION,cell,solid));assertEquals(0,old.gpuShapeCount());
        cache.tick((s,c)->{fail("an immediate local snapshot must not query more world cells");return AIR;},1);
    }
    @Test void localGpuPatchContainsAllChangedHeadersAndOnlyAnUnchangedDictionaryPrefix(){
        var cache=new PackageCollisionCache(Runnable::run,1,()->0L);cache.request(SECTION);
        cache.tick((s,c)->AIR,1);cache.tick((s,c)->AIR,0);long base=cache.snapshot(SECTION).revision();
        var solid=new PackageCollisionCache.Cell(List.of(new PackageCollisionCache.Box(0,0,0,1,1,1)),.8f,16);
        int edited=8|8<<4|8<<8;cache.invalidateChangedBlock(8,8,8,(s,c)->c==edited?solid:AIR);
        cache.tick((s,c)->solid,1);cache.tick((s,c)->AIR,0);var patch=cache.snapshot(SECTION).gpuPatch();
        assertNotNull(patch);assertEquals(base,patch.baseRevision());assertEquals(0,patch.baseShapes());
        assertEquals(List.of(edited),patch.cells());assertEquals(48,patch.bytes());
        assertThrows(UnsupportedOperationException.class,()->patch.cells().clear());
        cache.invalidateChunk(0,0);cache.tick((s,c)->c==edited?solid:AIR,1);cache.tick((s,c)->AIR,0);
        assertNull(cache.snapshot(SECTION).gpuPatch(),"full context changes must not infer a local GPU patch");
    }
    @Test void incompatibleShapeDictionaryCannotDescribeAnInPlaceGpuPatch(){
        var cache=new PackageCollisionCache(Runnable::run,1,()->0L);cache.request(SECTION);
        var cube=new PackageCollisionCache.Cell(List.of(new PackageCollisionCache.Box(0,0,0,1,1,1)),.6f,0);
        var slab=new PackageCollisionCache.Cell(List.of(new PackageCollisionCache.Box(0,0,0,1,.5f,1)),.6f,0);
        cache.tick((s,c)->c==0?cube:c==1?slab:AIR,1);cache.tick((s,c)->AIR,0);
        cache.invalidateChangedBlock(0,0,0,(s,c)->c==1?slab:AIR);
        cache.tick((s,c)->AIR,1);cache.tick((s,c)->AIR,0);
        assertNotNull(cache.snapshot(SECTION).gpuPatch());assertEquals(2,cache.snapshot(SECTION).gpuShapeCount());
        assertEquals(1,cache.snapshot(SECTION).gpuCells().getInt(16));
        cache.invalidate(SECTION);cache.tick((s,c)->c==1?slab:AIR,1);cache.tick((s,c)->AIR,0);
        assertNull(cache.snapshot(SECTION).gpuPatch(),"complete context recapture must compact independently");
        assertEquals(Boolean.TRUE,cache.capturedCellMatches(SECTION,1,slab));
    }
    @Test void deferredVersionDiagnosticsRetainTheirOwnCauseAndClearlyLabelCurrentProgress(){
        var cache=new PackageCollisionCache(Runnable::run,1,()->0L);cache.request(SECTION);
        cache.tick((s,c)->AIR,1);cache.tick((s,c)->AIR,0);long initial=cache.versions().get(SECTION);
        cache.invalidateChunk(-1,0,PackageCollisionCache.Invalidation.CHUNK_LOAD);long chunk=cache.versions().get(SECTION);
        assertEquals(new PackageCollisionCache.CaptureCause(SECTION,PackageCollisionCache.Invalidation.CHUNK_LOAD,-1,0,0,1),cache.captureCause(SECTION,chunk));
        cache.tick((s,c)->AIR,1);cache.tick((s,c)->AIR,0);
        var solid=new PackageCollisionCache.Cell(List.of(new PackageCollisionCache.Box(0,0,0,1,1,1)),.6f,0);
        cache.invalidateChangedBlock(8,8,8,(s,c)->c==(8|8<<4|8<<8)?solid:AIR);long block=cache.versions().get(SECTION);
        assertEquals(PackageCollisionCache.Invalidation.BLOCK,cache.captureCause(SECTION,block).cause());
        assertEquals(PackageCollisionCache.Invalidation.INITIAL,cache.captureCause(SECTION,initial).cause());
        var delayed=cache.describeCapture(SECTION,chunk);assertTrue(delayed.contains("captureCause=CHUNK_LOAD causeChunk=-1,0"));
        assertTrue(delayed.contains("observedTargetRevision="+block+" observedCapture=complete observedCells=4096/4096"));
        assertNull(cache.captureCause(new PackageCollisionCache.Section(1,0,0),chunk));
    }
    @Test void captureCauseHistoryIsBoundedAndCoalescedNotificationsAreCounted(){
        var cache=new PackageCollisionCache(Runnable::run,4100,()->0L);cache.request(SECTION);long first=cache.versions().get(SECTION);
        cache.invalidateChunk(0,0,PackageCollisionCache.Invalidation.CHUNK_UNLOAD);
        assertEquals(2,cache.captureCause(SECTION,first).notifications());
        for(int i=1;i<4100;i++)cache.request(new PackageCollisionCache.Section(i,0,0));
        assertNull(cache.captureCause(SECTION,first));assertTrue(cache.describeCapture(SECTION,first).startsWith("captureCause=unretained"));
        var latest=new PackageCollisionCache.Section(4099,0,0);assertNotNull(cache.captureCause(latest,cache.versions().get(latest)));
        cache.clear();assertNull(cache.captureCause(latest,4100));
    }
    @Test void aPackageUsedSectionFinishesAheadOfBackgroundCaptureButBackgroundStillProgresses(){
        var clock=new AtomicLong();var cache=new PackageCollisionCache(Runnable::run,33,clock::get);
        for(int i=0;i<32;i++)cache.request(new PackageCollisionCache.Section(i,0,0));
        var live=new PackageCollisionCache.Section(-1,4,0);cache.request(live);cache.protectPackageUsage(live);
        int[] backgroundReads={0};PackageCollisionCache.Source source=(s,c)->{clock.incrementAndGet();if(!s.equals(live))backgroundReads[0]++;return AIR;};
        cache.tick(source,4096);cache.tick(source,4096);cache.tick(source,0);
        assertNotNull(cache.snapshot(live),"a live package section stayed behind all background sections");
        assertTrue(backgroundReads[0]>0,"live demand must not starve background captures");
        assertTrue(cache.readyCount()<cache.size(),"the priority assertion must run while background sections are unfinished");
        assertEquals(8192,clock.get(),"priority must retain the total wall-clock capture allowance");
    }
    @Test void captureIsBudgetedAndOnlyCompletedCurrentRevisionPublishes() {
        var tasks=new ArrayDeque<Runnable>();var clock=new AtomicLong();
        var cache=new PackageCollisionCache(tasks::add,2,clock::get);
        assertTrue(cache.request(SECTION));
        PackageCollisionCache.Source source=(s,i)->{clock.addAndGet(100);return AIR;};
        cache.tick(source,250);assertEquals(300,cache.lastCaptureNanos());assertEquals(1,cache.overrunCount());
        assertNull(cache.snapshot(SECTION));
        for(int i=0;i<200;i++)cache.tick(source,10_000);
        assertEquals(1,tasks.size()); // unfinished worker is never joined or redundantly submitted
        cache.invalidate(SECTION);tasks.remove().run();cache.tick(source,10_000);
        assertNull(cache.snapshot(SECTION));
        for(int i=0;i<200;i++)cache.tick(source,10_000);
        tasks.remove().run();cache.tick(source,10_000);
        assertNotNull(cache.snapshot(SECTION));assertEquals(0,cache.snapshot(SECTION).shapeEnd(4095));
        cache.invalidate(SECTION);assertNull(cache.snapshot(SECTION));
    }
    @Test void unavailableSectionNeverBecomesAirAndEvictionDropsWorkerResult() {
        var tasks=new ArrayDeque<Runnable>();var cache=new PackageCollisionCache(tasks::add,1,()->0L);
        cache.request(SECTION);cache.tick((s,i)->null,1);assertTrue(tasks.isEmpty());
        assertFalse(cache.request(new PackageCollisionCache.Section(1,0,0)));
        for(int i=0;i<128;i++)cache.tick((s,n)->AIR,1);
        assertEquals(1,tasks.size());cache.evict(SECTION);tasks.remove().run();cache.tick((s,i)->AIR,1);
        assertNull(cache.snapshot(SECTION));
    }
    @Test void demandEvictsLeastRecentlyUsedAndStaleWorkerCannotPublishIntoReplacement() {
        var tasks=new ArrayDeque<Runnable>();var cache=new PackageCollisionCache(tasks::add,2,()->0L);
        var a=new PackageCollisionCache.Section(0,0,0);var b=new PackageCollisionCache.Section(1,0,0);
        var c=new PackageCollisionCache.Section(2,0,0);var removed=new java.util.ArrayList<PackageCollisionCache.Section>();
        cache.listener(new PackageCollisionCache.Listener(){@Override public void removed(PackageCollisionCache.Section section){removed.add(section);}});
        assertTrue(cache.requestDemand(a));assertTrue(cache.requestDemand(b));
        assertTrue(cache.requestDemand(a)); // A becomes more recent than B.
        assertTrue(cache.requestDemand(c));assertEquals(List.of(b),removed);assertEquals(2,cache.size());
        assertFalse(cache.request(b),"ordinary requests keep their explicit no-eviction contract");
        assertTrue(cache.requestDemand(b));assertEquals(List.of(b,a),removed);
        assertEquals(2,cache.size());assertEquals(2,cache.capacityEvictions());

        var one=new PackageCollisionCache(tasks::add,1,()->0L);var old=new PackageCollisionCache.Section(10,0,0);
        assertTrue(one.requestDemand(old));one.tick((s,i)->AIR,1);assertEquals(1,tasks.size());
        var replacement=new PackageCollisionCache.Section(11,0,0);assertTrue(one.requestDemand(replacement));
        tasks.remove().run();assertNull(one.snapshot(old),"an evicted worker result must never republish stale world data");
        assertNull(one.snapshot(replacement));assertEquals(1,one.capacityEvictions());

        var protectedCache=new PackageCollisionCache(tasks::add,2,()->0L);
        assertTrue(protectedCache.requestDemand(a));assertTrue(protectedCache.requestDemand(b));
        protectedCache.protectPackageUsage(a);protectedCache.protectPackageUsage(b);
        assertFalse(protectedCache.requestDemand(c),"a full live package set must reject new capture rather than evict active collision data");
        assertEquals(2,protectedCache.size());assertEquals(0,protectedCache.capacityEvictions());
        assertEquals(1,protectedCache.capacityRejections());
        protectedCache.clearPackageUsage();assertTrue(protectedCache.requestDemand(c));
        assertEquals(1,protectedCache.capacityEvictions());
    }
    @Test void shapesAreCopiedAndTranslatedOffThreadWithoutWorldAccess() {
        var tasks=new ArrayDeque<Runnable>();var cache=new PackageCollisionCache(tasks::add,1,()->0L);
        var mutable=new java.util.ArrayList<PackageCollisionCache.Box>();
        mutable.add(new PackageCollisionCache.Box(0,0,0,1,.5f,1));
        var slab=new PackageCollisionCache.Cell(mutable,.8f,2);mutable.clear();
        cache.request(SECTION);
        for(int i=0;i<128;i++)cache.tick((s,n)->n==4095?slab:AIR,1);
        tasks.remove().run();cache.tick((s,i)->AIR,1);
        var snapshot=cache.snapshot(SECTION);assertEquals(1,snapshot.shapeEnd(4095));
        assertEquals(15,snapshot.coordinate(0,0));assertEquals(15.5f,snapshot.coordinate(0,4));
        assertEquals(2,snapshot.flags(4095));
    }
    @Test void capturedCellComparisonUsesTheLatestPublishableCollisionInput() {
        var tasks=new ArrayDeque<Runnable>();var cache=new PackageCollisionCache(tasks::add,1,()->0L);
        var shape=List.of(new PackageCollisionCache.Box(0,0,0,1,1,1));
        var current=new PackageCollisionCache.Cell(shape,.6f,0);
        var changedShape=new PackageCollisionCache.Cell(List.of(new PackageCollisionCache.Box(0,0,0,1,.5f,1)),.6f,0);
        var changedMaterial=new PackageCollisionCache.Cell(shape,.8f,0);
        var changedFlags=new PackageCollisionCache.Cell(shape,.6f,PackageWorldCollisionSource.MACHINE);
        cache.request(SECTION);
        assertNull(cache.capturedCellMatches(SECTION,0,current),"a not-yet-captured cell will observe the latest world state");
        cache.tick((s,i)->i==0?current:AIR,Long.MAX_VALUE);
        assertEquals(Boolean.TRUE,cache.capturedCellMatches(SECTION,0,current),"an in-flight bake retains its immutable cell input");
        assertEquals(Boolean.FALSE,cache.capturedCellMatches(SECTION,0,changedShape));
        assertEquals(Boolean.FALSE,cache.capturedCellMatches(SECTION,0,changedMaterial));
        assertEquals(Boolean.FALSE,cache.capturedCellMatches(SECTION,0,changedFlags));
        tasks.remove().run();cache.tick((s,i)->AIR,Long.MAX_VALUE);
        assertEquals(Boolean.TRUE,cache.capturedCellMatches(SECTION,0,current),"the published snapshot can be compared without rebuilding the section");
        assertEquals(Boolean.FALSE,cache.capturedCellMatches(SECTION,0,changedShape));
    }
    private static void finish(PackageCollisionCache cache,ArrayDeque<Runnable> tasks) {
        // Worker scheduling remains bounded at four, including evicted work.
        for(int i=0;i<20 && cache.readyCount()!=cache.size();i++) {
            cache.tick((s,n)->AIR,1);
            assertTrue(tasks.size()<=4);
            while(!tasks.isEmpty())tasks.remove().run();
        }
        cache.tick((s,n)->AIR,1);assertEquals(cache.size(),cache.readyCount());
    }
    @Test void boundaryNotificationKeepsUnchangedAdjacentSectionsButRevokesChangedContext(){
        var cache=new PackageCollisionCache(Runnable::run,8,()->0L);
        for(int x=-1;x<=0;x++)for(int y=-1;y<=0;y++)for(int z=-1;z<=0;z++)cache.request(new PackageCollisionCache.Section(x,y,z));
        cache.tick((s,c)->AIR,1);cache.tick((s,c)->AIR,1);assertEquals(8,cache.readyCount());
        var versions=cache.versions();var solid=new PackageCollisionCache.Cell(List.of(new PackageCollisionCache.Box(0,0,0,1,1,1)),.6f,0);
        var changed=new PackageCollisionCache.Section(0,0,0);var neighbor=new PackageCollisionCache.Section(-1,0,0);
        cache.invalidateChangedBlock(0,0,0,(s,c)->s.equals(changed)&&c==0?solid:AIR);
        assertEquals(8,cache.readyCount());assertNotNull(cache.snapshot(changed));
        for(var section:versions.keySet())if(!section.equals(changed))assertEquals(versions.get(section),cache.versions().get(section));
        cache.tick((s,c)->s.equals(changed)&&c==0?solid:AIR,1);cache.tick((s,c)->s.equals(changed)&&c==0?solid:AIR,1);
        cache.invalidateChangedBlock(0,0,0,(s,c)->s.equals(changed)&&c==0?solid:s.equals(neighbor)&&c==15?solid:AIR);
        assertNotNull(cache.snapshot(neighbor));assertNotNull(cache.snapshot(changed));assertEquals(8,cache.readyCount());
        assertEquals(Boolean.TRUE,cache.capturedCellMatches(neighbor,15,solid));
    }
    @Test void completedStaticBakePublishesWithZeroCaptureBudgetAndOldBakeStaysUnavailable(){
        var tasks=new ArrayDeque<Runnable>();var cache=new PackageCollisionCache(tasks::add,1,()->0L);cache.request(SECTION);
        cache.tick((s,c)->AIR,1);tasks.remove().run();
        cache.tick((s,c)->{fail("zero-budget publication must not query a world");return AIR;},0);
        assertNotNull(cache.snapshot(SECTION));
        cache.invalidate(SECTION);cache.tick((s,c)->AIR,1);tasks.remove().run();cache.invalidate(SECTION);
        var oldResults=new java.util.ArrayList<PackageCollisionCache.Snapshot>();cache.listener(new PackageCollisionCache.Listener(){
            @Override public void historical(PackageCollisionCache.Section section,PackageCollisionCache.Snapshot snapshot){oldResults.add(snapshot);}
        });
        cache.tick((s,c)->{fail("zero-budget stale bake check must not query a world");return AIR;},0);
        assertNull(cache.snapshot(SECTION));
        assertEquals(1,oldResults.size());assertTrue(oldResults.getFirst().revision()<cache.versions().get(SECTION));
    }
    @Test void localEditsReadOnlyChangedCellsAndReuseExactUntouchedLocalShapeCoordinates(){
        var cache=new PackageCollisionCache(Runnable::run,1,()->0L);cache.request(SECTION);
        var original=new PackageCollisionCache.Cell(List.of(new PackageCollisionCache.Box(.1f,.2f,.3f,.9f,.8f,.7f)),.6f,0);
        cache.tick((s,c)->c==4095?original:AIR,1);cache.tick((s,c)->AIR,1);
        var solid=new PackageCollisionCache.Cell(List.of(new PackageCollisionCache.Box(0,0,0,1,1,1)),.8f,16);
        cache.invalidateChangedBlock(8,8,8,(s,c)->c==(8|8<<4|8<<8)?solid:c==4095?original:AIR);
        var read=new java.util.ArrayList<Integer>();cache.tick((s,c)->{read.add(c);return solid;},1);cache.tick((s,c)->{fail("finished partial capture must not query more cells");return AIR;},1);
        assertTrue(read.isEmpty(),"local publication must not reread unchanged cells");var snapshot=cache.snapshot(SECTION);assertNotNull(snapshot);
        assertEquals(Boolean.TRUE,cache.capturedCellMatches(SECTION,4095,original));assertEquals(Boolean.TRUE,cache.capturedCellMatches(SECTION,8|8<<4|8<<8,solid));
        var data=snapshot.gpuBoxes();boolean exact=false;for(int p=0;p<data.remaining();p+=32)if(data.getFloat(p)==.1f&&data.getFloat(p+4)==.2f&&data.getFloat(p+8)==.3f)exact=true;
        assertTrue(exact,"unchanged fractional local shapes must not acquire translated-coordinate rounding");
        cache.invalidateChunk(0,0);read.clear();cache.tick((s,c)->{read.add(c);return AIR;},1);
        assertEquals(4096,read.size(),"chunk replacement must retain full-context recapture");
    }
    @Test void repeatedLocalEditsRetainTheUnionOfDirtyCellsUntilCurrentPublication(){
        var tasks=new ArrayDeque<Runnable>();var cache=new PackageCollisionCache(tasks::add,1,()->0L);cache.request(SECTION);
        cache.tick((s,c)->AIR,1);tasks.remove().run();cache.tick((s,c)->AIR,0);
        var solid=new PackageCollisionCache.Cell(java.util.Collections.nCopies(65,new PackageCollisionCache.Box(0,0,0,1,1,1)),.6f,0);
        int first=8|8<<4|8<<8,second=9|8<<4|8<<8;
        cache.invalidateChangedBlock(8,8,8,(s,c)->c==first?solid:AIR);cache.tick((s,c)->c==first?solid:AIR,1);
        cache.invalidateChangedBlock(9,8,8,(s,c)->c==first||c==second?solid:AIR);tasks.remove().run();
        var reads=new java.util.ArrayList<Integer>();cache.tick((s,c)->{reads.add(c);return solid;},1);tasks.remove().run();cache.tick((s,c)->AIR,0);
        assertEquals(List.of(first,second),reads);assertEquals(Boolean.TRUE,cache.capturedCellMatches(SECTION,first,solid));assertEquals(Boolean.TRUE,cache.capturedCellMatches(SECTION,second,solid));
    }
    @Test void editsBeforeIncrementalCaptureCompareTheRetainedBaselineAndFullInvalidationDiscardsIt(){
        var cache=new PackageCollisionCache(Runnable::run,1,()->0L);cache.request(SECTION);
        cache.tick((s,c)->AIR,1);cache.tick((s,c)->AIR,0);
        var solid=new PackageCollisionCache.Cell(List.of(new PackageCollisionCache.Box(0,0,0,1,1,1)),.6f,0);
        int first=8|8<<4|8<<8,second=9|8<<4|8<<8;
        cache.invalidateChangedBlock(8,8,8,(s,c)->c==first?solid:AIR);
        assertEquals(Boolean.TRUE,cache.capturedCellMatches(SECTION,second,AIR));
        cache.invalidateChangedBlock(9,8,8,(s,c)->c==first||c==second?solid:AIR);
        var reads=new java.util.ArrayList<Integer>();cache.tick((s,c)->{reads.add(c);return solid;},1);cache.tick((s,c)->AIR,0);
        assertTrue(reads.isEmpty());assertEquals(Boolean.TRUE,cache.capturedCellMatches(SECTION,first,solid));assertEquals(Boolean.TRUE,cache.capturedCellMatches(SECTION,second,solid));
        cache.invalidateChangedBlock(8,8,8,(s,c)->AIR);
        cache.invalidateChunk(0,0);reads.clear();cache.tick((s,c)->{reads.add(c);return AIR;},1);
        assertEquals(4096,reads.size(),"full invalidation before capture cannot keep a partial baseline through coalescing");
    }
    @Test void unavailableEditedCellRevokesCoverageAndWaitsUntilTheSourceRecovers(){
        var cache=new PackageCollisionCache(Runnable::run,1,()->0L);cache.request(SECTION);
        cache.tick((s,c)->AIR,1);cache.tick((s,c)->AIR,0);int changed=8|8<<4|8<<8;
        cache.invalidateChangedBlock(8,8,8,(s,c)->c==changed?null:AIR);
        assertNull(cache.snapshot(SECTION));var reads=new java.util.ArrayList<Integer>();
        cache.tick((s,c)->{reads.add(c);return null;},1);assertFalse(reads.isEmpty());assertTrue(reads.stream().allMatch(c->c==changed));assertNull(cache.snapshot(SECTION));
        var solid=new PackageCollisionCache.Cell(List.of(new PackageCollisionCache.Box(0,0,0,1,1,1)),.6f,0);
        cache.tick((s,c)->solid,1);cache.tick((s,c)->AIR,0);
        assertEquals(Boolean.TRUE,cache.capturedCellMatches(SECTION,changed,solid));
    }
    @Test void boundaryEditsInvalidateNeighbourContextAcrossNegativeSections() {
        var tasks=new ArrayDeque<Runnable>();var cache=new PackageCollisionCache(tasks::add,9,()->0L);
        for(int x=-1;x<=0;x++)for(int y=-1;y<=0;y++)for(int z=-1;z<=0;z++)cache.request(new PackageCollisionCache.Section(x,y,z));
        var distant=new PackageCollisionCache.Section(8,0,8);cache.request(distant);finish(cache,tasks);
        for(int i=0;i<1000;i++)cache.invalidateBlock(-1,-1,-1);
        assertEquals(1,cache.readyCount());assertNotNull(cache.snapshot(distant));
        finish(cache,tasks);assertEquals(9,cache.readyCount());
        cache.invalidateBlock(-8,-8,-8);assertEquals(8,cache.readyCount());
    }
    @Test void chunkReplacementRevokesAdjacentColumnsAtAllRequestedHeights() {
        var tasks=new ArrayDeque<Runnable>();var cache=new PackageCollisionCache(tasks::add,5,()->0L);
        var affected=List.of(new PackageCollisionCache.Section(0,0,0),new PackageCollisionCache.Section(0,2000,0),
                new PackageCollisionCache.Section(-1,-2000,1),new PackageCollisionCache.Section(1,4,-1));
        affected.forEach(cache::request);var distant=new PackageCollisionCache.Section(2,0,0);cache.request(distant);finish(cache,tasks);
        cache.invalidateChunk(0,0);for(var section:affected)assertNull(cache.snapshot(section));assertNotNull(cache.snapshot(distant));
        for(var section:affected)cache.evict(section);
        assertEquals(1,cache.size());cache.invalidateChunk(0,0);assertNotNull(cache.snapshot(distant));
        cache.clear();assertEquals(0,cache.size());assertEquals(0,cache.readyCount());
    }
    @Test void clearAndReRequestCannotPublishOldWorldWorker() {
        var tasks=new ArrayDeque<Runnable>();var cache=new PackageCollisionCache(tasks::add,1,()->0L);
        cache.request(SECTION);cache.tick((s,n)->AIR,1);assertEquals(1,tasks.size());
        cache.clear();cache.request(SECTION);tasks.remove().run();cache.tick((s,n)->null,1);
        assertNull(cache.snapshot(SECTION));assertEquals(0,cache.readyCount());
        finish(cache,tasks);assertNotNull(cache.snapshot(SECTION));
    }
    @Test void evictionDoesNotFreeAnUnfinishedWorkerBudget() {
        var tasks=new ArrayDeque<Runnable>();var cache=new PackageCollisionCache(tasks::add,4,()->0L);
        for(int i=0;i<4;i++)cache.request(new PackageCollisionCache.Section(i,0,0));cache.tick((s,n)->AIR,1);
        assertEquals(4,tasks.size());cache.clear();cache.request(SECTION);cache.tick((s,n)->AIR,1);
        assertEquals(4,tasks.size());assertNull(cache.snapshot(SECTION));
        while(!tasks.isEmpty())tasks.remove().run();finish(cache,tasks);
    }
    @Test void repeatedShapesKeepPerCellMaterialAndCpuCoordinates() {
        var tasks=new ArrayDeque<Runnable>();var cache=new PackageCollisionCache(tasks::add,1,()->0L);
        var boxes=List.of(new PackageCollisionCache.Box(0,0,0,1,1,1));
        cache.request(SECTION);cache.tick((s,i)->new PackageCollisionCache.Cell(boxes,i==4095?.98f:.6f,i==4095?1:0),1);
        tasks.remove().run();cache.tick((s,i)->AIR,1);
        var snapshot=cache.snapshot(SECTION);
        assertEquals(4096,snapshot.shapeEnd(4095));assertEquals(15,snapshot.coordinate(4095,0));
        assertEquals(1,snapshot.gpuShapeCount());assertEquals(32,snapshot.gpuBoxes().remaining());
        assertFalse(snapshot.gpuEmpty());
        var cells=snapshot.gpuCells();assertEquals(65536,cells.remaining());
        assertEquals(0,cells.getInt(4095*16));assertEquals(1,cells.getInt(4095*16+4));
        assertEquals(.98f,cells.getFloat(4095*16+8));assertEquals(1,cells.getInt(4095*16+12));
        assertEquals(0,snapshot.gpuBoxes().getFloat(0));assertEquals(1,snapshot.gpuBoxes().getFloat(16));
        assertThrows(java.nio.ReadOnlyBufferException.class,()->cells.putInt(0,9));
        cells.position(24);assertEquals(0,snapshot.gpuCells().position());
    }
    @Test void overhangingAndOversizedDictionariesStayAvailableOnlyToCpu() {
        for(boolean large:new boolean[]{false,true}) {
            var tasks=new ArrayDeque<Runnable>();var cache=new PackageCollisionCache(tasks::add,1,()->0L);
            var shapes=new java.util.ArrayList<PackageCollisionCache.Box>();
            for(int i=0;i<(large?PackageCollisionCache.MAX_GPU_SHAPES+1:1);i++)
                shapes.add(new PackageCollisionCache.Box(large?i/20000f:-1.01f,0,0,1,1,1));
            var cell=new PackageCollisionCache.Cell(shapes,.6f,0);
            cache.request(SECTION);cache.tick((s,i)->i==0?cell:AIR,1);tasks.remove().run();cache.tick((s,i)->AIR,1);
            var snapshot=cache.snapshot(SECTION);assertNotNull(snapshot);assertEquals(shapes.size(),snapshot.shapeEnd(0));
            assertEquals(-1,snapshot.gpuShapeCount());assertEquals(0,snapshot.gpuBoxes().remaining());
        }
    }
    @Test void callbacksPublishOnlyCurrentDataOnOwnerThread() throws InterruptedException {
        var tasks=new ArrayDeque<Runnable>();var cache=new PackageCollisionCache(tasks::add,1,()->0L);
        Thread owner=Thread.currentThread();var events=new java.util.ArrayList<String>();
        cache.listener(new PackageCollisionCache.Listener() {
            public void invalidated(PackageCollisionCache.Section section,long revision){assertSame(owner,Thread.currentThread());events.add("invalidated");}
            public void published(PackageCollisionCache.Section section,PackageCollisionCache.Snapshot snapshot){assertSame(owner,Thread.currentThread());events.add("published");}
            public void removed(PackageCollisionCache.Section section){assertSame(owner,Thread.currentThread());events.add("removed");}
            public void cleared(){assertSame(owner,Thread.currentThread());events.add("cleared");}
        });
        cache.request(SECTION);cache.tick((s,i)->{assertSame(owner,Thread.currentThread());return AIR;},1);
        cache.invalidate(SECTION);
        Thread worker=new Thread(tasks.remove());worker.start();worker.join();
        assertEquals(List.of("invalidated","invalidated"),events);
        cache.tick((s,i)->AIR,1);worker=new Thread(tasks.remove());worker.start();worker.join();cache.tick((s,i)->AIR,1);
        assertEquals(List.of("invalidated","invalidated","published"),events);
        cache.forEachReady((s,snapshot)->assertSame(cache.snapshot(s),snapshot));
        assertTrue(cache.snapshot(SECTION).gpuEmpty());
        cache.evict(SECTION);cache.clear();assertEquals(List.of("invalidated","invalidated","published","removed","cleared"),events);
    }
    @Test void emptyHazardSectionsCannotBypassCellChecks() {
        for(int flags:new int[]{1,2,4,8,16}) {
            var tasks=new ArrayDeque<Runnable>();var cache=new PackageCollisionCache(tasks::add,1,()->0L);
            cache.request(SECTION);cache.tick((s,i)->i==3000?new PackageCollisionCache.Cell(List.of(),.6f,flags):AIR,1);
            tasks.remove().run();cache.tick((s,i)->AIR,1);
            assertEquals(0,cache.snapshot(SECTION).gpuShapeCount());assertFalse(cache.snapshot(SECTION).gpuEmpty());
        }
    }
}
