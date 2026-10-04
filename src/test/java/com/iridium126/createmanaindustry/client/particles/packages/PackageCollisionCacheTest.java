package com.iridium126.createmanaindustry.client.particles.packages;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

class PackageCollisionCacheTest {
    private static final PackageCollisionCache.Section SECTION=new PackageCollisionCache.Section(0,0,0);
    private static final PackageCollisionCache.Cell AIR=new PackageCollisionCache.Cell(List.of(),.6f,0);
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
