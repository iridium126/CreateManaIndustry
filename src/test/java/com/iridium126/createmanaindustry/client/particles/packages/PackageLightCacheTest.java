package com.iridium126.createmanaindustry.client.particles.packages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PackageLightCacheTest {
    private static final PackageCollisionCache.Section ZERO=new PackageCollisionCache.Section(0,0,0);
    private static PackageLightCache.Source filled(byte[] data){return new PackageLightCache.Source() {
        public byte[] copy(PackageCollisionCache.Section s){return data;}
        public int sample(PackageCollisionCache.Section s,int i){throw new AssertionError("Detached layer must bypass queries");}
    };}
    @Test void detachedLayersAreImmutableAndKeepVanillaNibbleOrder() {
        byte[] bytes=new byte[4096];bytes[0]=(byte)0x93;bytes[2048]=(byte)0xe7;bytes[4095]=(byte)0xf0;
        var cache=new PackageLightCache(1,()->0L);assertTrue(cache.request(ZERO));cache.tick(filled(bytes),1);
        var snapshot=cache.snapshot(ZERO);Arrays.fill(bytes,(byte)0);
        assertEquals(0x73,snapshot.light(0));assertEquals(0xe9,snapshot.light(1));assertEquals(0xf0,snapshot.light(4095));
        assertTrue(snapshot.bytes().isReadOnly());assertThrows(IllegalArgumentException.class,()->snapshot.light(4096));
        assertEquals(0,cache.pending());
    }
    @Test void inheritedSkyFallbackResumesWithinBudget() {
        var clock=new AtomicLong();var cache=new PackageLightCache(1,clock::get);cache.request(ZERO);
        var source=new PackageLightCache.Source() {
            public byte[] copy(PackageCollisionCache.Section s){return null;}
            public int sample(PackageCollisionCache.Section s,int i){clock.addAndGet(100);return (i&15)|(((i>>>4)&15)<<4);}
        };
        cache.tick(source,250);assertNull(cache.snapshot(ZERO));assertEquals(300,cache.lastNanos());assertEquals(1,cache.overruns());
        for(int i=0;i<200;i++)cache.tick(source,10000);
        var snapshot=cache.snapshot(ZERO);assertNotNull(snapshot);
        for(int i=0;i<4096;i++)assertEquals((i&15)|(((i>>>4)&15)<<4),snapshot.light(i));
    }
    @Test void invalidationDropsPartialCaptureAndDoesNotGrowQueue() {
        var cache=new PackageLightCache(1,()->0L);cache.request(ZERO);
        var source=new PackageLightCache.Source() {
            public byte[] copy(PackageCollisionCache.Section s){return null;}
            public int sample(PackageCollisionCache.Section s,int i){return 255;}
        };
        cache.tick(source,1);for(int i=0;i<1000;i++)cache.invalidate(ZERO);
        assertEquals(1,cache.pending());byte[] bytes=new byte[4096];cache.tick(filled(bytes),1);
        assertEquals(1001,cache.snapshot(ZERO).revision());assertEquals(0,cache.snapshot(ZERO).light(0));
    }
    @Test void missingSectionsNeverPublishAndCapacityIsBounded() {
        var cache=new PackageLightCache(1,()->0L);cache.request(ZERO);
        var queries=new java.util.concurrent.atomic.AtomicInteger();
        var missing=new PackageLightCache.Source() {
            public byte[] copy(PackageCollisionCache.Section s){return null;}
            public int sample(PackageCollisionCache.Section s,int i){queries.incrementAndGet();return -1;}
        };
        for(int i=0;i<1000;i++)cache.tick(missing,1);
        assertNull(cache.snapshot(ZERO));assertEquals(1000,queries.get());assertEquals(1,cache.pending());assertFalse(cache.request(new PackageCollisionCache.Section(1,0,0)));
        cache.clear();assertEquals(0,cache.size());assertEquals(0,cache.pending());assertTrue(cache.request(ZERO));
    }
    @Test void oneInheritedSectionCanUseRemainingBudgetWithoutWaiting128Ticks() {
        var cache=new PackageLightCache(1,()->0L);cache.request(ZERO);
        cache.tick(new PackageLightCache.Source() {
            public byte[] copy(PackageCollisionCache.Section s){return null;}
            public int sample(PackageCollisionCache.Section s,int i){return 0xe3;}
        },1);
        assertNotNull(cache.snapshot(ZERO));assertEquals(0xe3,cache.snapshot(ZERO).light(4095));assertEquals(0,cache.pending());
    }
    @Test void sourceFailuresRevokeAvailabilityAndRetryWithoutEscapingTick() {
        var cache=new PackageLightCache(1,()->0L);cache.request(ZERO);
        var unavailable=new PackageLightCache.Source() {
            public byte[] copy(PackageCollisionCache.Section s){throw new LinkageError("optional world light API");}
            public int sample(PackageCollisionCache.Section s,int i){throw new AssertionError("failed bulk source must stop this tick");}
        };
        assertDoesNotThrow(()->cache.tick(unavailable,1));assertEquals(1,cache.sourceFailures());assertNull(cache.snapshot(ZERO));
        cache.tick(filled(new byte[4096]),1);assertNotNull(cache.snapshot(ZERO));
    }
    @Test void columnChangesInvalidateInheritedSkyAtAllRequestedHeights() {
        var cache=new PackageLightCache(3,()->0L);var below=new PackageCollisionCache.Section(-1,-3000,1);var above=new PackageCollisionCache.Section(-1,3000,1);
        cache.request(below);cache.request(above);cache.request(ZERO);cache.tick(filled(new byte[4096]),1);
        cache.invalidateColumn(-1,1);assertNull(cache.snapshot(below));assertNull(cache.snapshot(above));assertNotNull(cache.snapshot(ZERO));
        assertEquals(2,cache.pending());assertNotEquals(PackageLightCache.column(-1,1),PackageLightCache.column(1,-1));
    }
    @Test void captureRejectsWrongThreadAndMalformedDetachedLayers() throws Exception {
        var cache=new PackageLightCache(1);cache.request(ZERO);var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var thread=new Thread(()->{try{cache.tick(filled(new byte[4096]),1);}catch(Throwable error){failure.set(error);}});thread.start();thread.join();
        assertInstanceOf(IllegalStateException.class,failure.get());
        assertThrows(IllegalArgumentException.class,()->cache.tick(filled(new byte[4095]),1000000));
    }
}
