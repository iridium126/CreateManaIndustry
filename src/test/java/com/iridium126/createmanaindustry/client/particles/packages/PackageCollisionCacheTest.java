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
}
