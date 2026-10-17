package com.iridium126.createmanaindustry.client.packages.world;


import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackageCollisionCaptureScheduleTest {
    @Test void spareGeometryTimeDoesNotResampleMovingPosesAndIdleCategoriesReturnTheirAllowance(){
        for(boolean movingFirst:new boolean[]{false,true})for(boolean lightFirst:new boolean[]{false,true}){
            var clock=new AtomicLong();int[] poseReads={0};
            var source=new PackageMovingCollisionCacheTest.Source(clock){
                @Override public PackageMovingGeometry.Pose pose(boolean previous){poseReads[0]++;return new PackageMovingGeometry.Pose(1,0,0,0,1,0,0,0,1,poseReads[0],0,0);}
            };
            source.length=100;var moving=new PackageMovingCollisionCache(Runnable::run,1,clock::get);moving.offer(source);
            PackageCollisionCaptureSchedule.tick(new PackageCollisionCaptureSchedule.Work(){
                public void discoverMoving(long deadline){}
                public void invalidateLight(long deadline){}
                public void captureLight(long budget){}
                public void captureWorld(long budget){}
                public void captureMoving(long budget){moving.tick(budget);}
                public void captureMovingGeometry(long budget){moving.continueGeometry(budget);}
            },movingFirst,lightFirst,1200,clock::get);
            assertEquals(1200,clock.get(),"idle world and lighting must return time to unfinished moving geometry");
            assertEquals(1200,moving.lastCaptureNanos());assertEquals(2,poseReads[0]);
            moving.captureHistory(50,true);var entry=moving.history(50).getFirst();
            assertEquals(1,entry.previous.tx());assertEquals(2,entry.current.tx());
        }
    }
    @Test void lightInvalidationCoalescingLeavesCollisionTimeAndSingleSampleOverrunDoesNotStartMoreReads(){
        var clock=new AtomicLong();long[] movingBudget={-1},worldBudget={-1};
        PackageCollisionCaptureSchedule.tick(new PackageCollisionCaptureSchedule.Work(){
            public void discoverMoving(long deadline){}
            public void invalidateLight(long deadline){clock.set(deadline);}
            public void captureLight(long budget){clock.addAndGet(budget+1200);}
            public void captureWorld(long budget){worldBudget[0]=budget;}
            public void captureMoving(long budget){movingBudget[0]=budget;}
            public void captureMovingGeometry(long budget){fail("a single light sample overrun must not start spare geometry");}
        },true,true,1200,clock::get);
        assertEquals(1800,clock.get());assertEquals(0,movingBudget[0]);assertEquals(0,worldBudget[0]);
    }
    @Test void lightingAndMovingGeometryCannotConsumeTheEntireWorldCaptureAllowance(){
        for(boolean movingFirst:new boolean[]{false,true})for(boolean lightFirst:new boolean[]{false,true}) {
            var clock=new AtomicLong();var world=new PackageCollisionCache(Runnable::run,1,clock::get);
            var section=new PackageCollisionCache.Section(0,4,0);world.request(section);
            int[] reads={0};var air=new PackageCollisionCache.Cell(java.util.List.of(),.6f,0);
            PackageCollisionCaptureSchedule.tick(new PackageCollisionCaptureSchedule.Work() {
                public void discoverMoving(long deadline) {}
                public void invalidateLight(long deadline) {}
                public void captureLight(long budget){clock.addAndGet(budget);}
                public void captureMoving(long budget){clock.addAndGet(budget);}
                public void captureMovingGeometry(long budget){clock.addAndGet(budget);}
                public void captureWorld(long budget){world.tick((s,c)->{reads[0]++;clock.incrementAndGet();return air;},budget);}
            },movingFirst,lightFirst,1200,clock::get);
            assertTrue(reads[0]>=400,"optional lighting or the first geometry category starved an invalidated world section");
            assertEquals(1200,clock.get(),"sharing capture time must not raise the total allowance");
        }
    }
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
                public void captureMovingGeometry(long budget){moving.continueGeometry(budget);}
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
            public void captureMovingGeometry(long budget){fail("exhausted discovery cannot start geometry capture");}
            public void captureWorld(long budget){assertEquals(0,budget);}
        },false,true,250,clock::get);
        moving.captureHistory(100,available[0]);
        assertTrue(moving.posesReady());assertNull(moving.history(100),"a missing membership scan must not reuse old scene coverage");
    }
}
