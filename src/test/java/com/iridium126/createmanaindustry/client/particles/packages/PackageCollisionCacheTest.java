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
}
