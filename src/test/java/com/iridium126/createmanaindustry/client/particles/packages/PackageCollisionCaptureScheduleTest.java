package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackageCollisionCaptureScheduleTest {
    @Test void crossColumnLightingCannotDropContraptionAndSableHistory() {
        for(boolean movingFirst:new boolean[]{false,true})for(boolean dirtyFirst:new boolean[]{false,true}) {
            var clock=new AtomicLong();
            var moving=new PackageMovingCollisionCache(Runnable::run,2,clock::get);
            var a=new PackageMovingCollisionCacheTest.Source(clock);a.length=1;
            var b=new PackageMovingCollisionCacheTest.Source(clock){
                @Override public PackageMovingGeometry.Key key(){return new PackageMovingGeometry.Key(1,super.key().id());}
            };b.length=1;
            moving.offer(a);moving.offer(b);moving.tick(10_000);moving.tick(10_000);
            var lights=new PackageLightCache(1,clock::get);lights.request(new PackageCollisionCache.Section(0,0,0));
            boolean[] available={false};long started=clock.get();
            PackageCollisionCaptureSchedule.tick(new PackageCollisionCaptureSchedule.Work() {
                public void invalidateLight(long deadline){if(dirtyFirst)clock.addAndGet(300);}
                public void discoverMoving(long deadline){available[0]=clock.get()<deadline;clock.addAndGet(10);}
                public void captureLight(long budget){lights.tick(new PackageLightCache.Source() {
                    public byte[] copy(PackageCollisionCache.Section section){return null;}
                    public int sample(PackageCollisionCache.Section section,int cell){clock.addAndGet(100);return 0xff;}
                },budget);}
                public void captureMoving(long budget){moving.tick(budget);}
                public void captureWorld(long budget) {}
            },movingFirst,true,250,clock::get);
            moving.captureHistory(100,available[0]);
            assertNotNull(moving.history(100),"crossing to a lit column consumed the budget before moving-source discovery");
            assertEquals(2,moving.history(100).size());assertTrue(moving.posesReady());
            assertTrue(clock.get()-started<=350,"lighting capture should keep its bounded final sample overrun");
        }
    }
    @Test void genuinelyUnavailableDiscoveryStillPreventsHistoricalSimulation() {
        var clock=new AtomicLong();var moving=new PackageMovingCollisionCache(Runnable::run,1,clock::get);
        moving.offer(new PackageMovingCollisionCacheTest.Source(clock));
        boolean[] available={true};
        PackageCollisionCaptureSchedule.tick(new PackageCollisionCaptureSchedule.Work() {
            public void discoverMoving(long deadline){clock.addAndGet(300);available[0]=clock.get()<deadline;}
            public void invalidateLight(long deadline) {}
            public void captureLight(long budget){assertEquals(0,budget);}
            public void captureMoving(long budget){assertEquals(0,budget);moving.tick(budget);}
            public void captureWorld(long budget){assertEquals(0,budget);}
        },false,true,250,clock::get);
        moving.captureHistory(100,available[0]);
        assertTrue(moving.posesReady());assertNull(moving.history(100),"a missing membership scan must not reuse old scene coverage");
    }
}
