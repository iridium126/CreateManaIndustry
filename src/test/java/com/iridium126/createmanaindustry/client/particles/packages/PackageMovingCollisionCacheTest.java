package com.iridium126.createmanaindustry.client.particles.packages;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

class PackageMovingCollisionCacheTest {
    @Test void acceleratedPoseIntervalsCoverRotationAndTranslationExactlyOnce(){
        var clock=new AtomicLong();var source=new Source(clock){
            @Override public PackageMovingGeometry.Pose pose(boolean previous){return previous?IDENTITY:new PackageMovingGeometry.Pose(0,1,0,-1,0,0,0,0,1,10,0,0);}
        };
        var cache=new PackageMovingCollisionCache(Runnable::run,1,()->0L);cache.tickRate(200);cache.offer(source);cache.tick(1);cache.captureHistory(1,10,true);
        var end=IDENTITY;double motion=0;
        for(int tick=1;tick<=10;tick++){
            var interval=cache.history(tick).getFirst();assertEquals(end,interval.previous);assertEquals(tick+1,interval.poseFrame);
            motion+=interval.current.tx()-interval.previous.tx();end=interval.current;
            assertEquals(tick,interval.current.tx(),1e-12);
            assertEquals(1,interval.current.xx()*interval.current.xx()+interval.current.xy()*interval.current.xy(),1e-12);
        }
        assertEquals(10,motion,1e-12);assertEquals(source.pose(false),end);
        cache.captureHistory(1,10,false);assertEquals(1,cache.history(1).getFirst().current.tx(),1e-12);
    }
    @Test void twoHundredTpsRetainsImmutablePosePairsForOneSecond(){
        var cache=new PackageMovingCollisionCache(Runnable::run,1,()->0L);cache.tickRate(200);
        for(int tick=0;tick<250;tick++){cache.tick(1);cache.captureHistory(tick,true);}
        assertFalse(cache.hasHistory(49));assertTrue(cache.hasHistory(50));assertNotNull(cache.history(50));
        cache.tickRate(20);assertTrue(cache.hasHistory(50));
        cache.captureHistory(249,false);assertNotNull(cache.history(249),"same-tick data must stay immutable");
    }
    @Test void removedLastStructureTurnsAnUnavailableIntervalIntoAConfirmedEmptyScene(){
        var source=new Source(new AtomicLong());source.length=1;
        var cache=new PackageMovingCollisionCache(Runnable::run,1,()->0L);
        assertTrue(cache.offer(source));cache.tick(1);cache.captureHistory(7,false);
        assertTrue(cache.hasHistory(7));assertNull(cache.history(7));
        cache.remove(source.key());assertTrue(cache.entries().isEmpty());
        assertNull(cache.simulationFrame(7,false));
        assertTrue(cache.simulationFrame(7,true).isEmpty());
        assertNull(cache.history(7),"historical capture remains immutable");
    }
    @Test void removedStructureGeometryRemainsReferencedUntilItsLastInputFrameExpires(){
        var source=new Source(new AtomicLong());source.length=1;
        var cache=new PackageMovingCollisionCache(Runnable::run,1,()->0L);
        assertTrue(cache.offer(source));cache.tick(1);cache.captureHistory(0,true);
        var entry=cache.history(0).getFirst();
        var reference=new PackageMovingCollisionCache.GeometryRevision(entry.identity,entry.revision());
        assertTrue(cache.retainedGeometry().contains(reference));
        cache.remove(source.key());
        assertTrue(cache.retainedGeometry().contains(reference));
        for(int tick=1;tick<=PackageSimulationClock.HISTORY_TICKS;tick++)cache.captureHistory(tick,true);
        assertFalse(cache.retainedGeometry().contains(reference));
    }
    static final PackageMovingGeometry.Pose IDENTITY=new PackageMovingGeometry.Pose(1,0,0,0,1,0,0,0,1,0,0,0);
    static class Source implements PackageMovingCollisionCache.Source {
        final Thread owner=Thread.currentThread();final PackageMovingGeometry.Key key=new PackageMovingGeometry.Key(0,UUID.randomUUID());
        long revision=1;int length=40;boolean missing,poseMissing,throwCursor;final AtomicLong clock;
        Source(AtomicLong clock){this.clock=clock;}
        void owner(){assertSame(owner,Thread.currentThread());}
        public PackageMovingGeometry.Key key(){return key;}
        public long revision(){owner();return revision;}
        public boolean alive(){owner();return true;}
        public PackageMovingGeometry.Bounds bounds(){owner();return new PackageMovingGeometry.Bounds(0,0,0,length,1,1);}
        public PackageMovingGeometry.Pose pose(boolean previous){owner();if(poseMissing)throw new IllegalStateException();return IDENTITY;}
        public PackageMovingCollisionCache.Cursor open(){owner();return new PackageMovingCollisionCache.Cursor(){
            int next;
            public boolean hasNext(){owner();if(throwCursor)throw new ConcurrentModificationException();return next<length;}
            public List<PackageMovingGeometry.Box> next(){owner();if(missing)return null;clock.addAndGet(100);int x=next++;return List.of(new PackageMovingGeometry.Box(x,0,0,x+1,1,1,.6f,0));}
        };}
    }
    @Test void budgetSplitsOwnerCaptureAndWorkerTouchesOnlyPrimitives() throws Exception {
        var tasks=new ArrayDeque<Runnable>();var clock=new AtomicLong();var source=new Source(clock);
        var cache=new PackageMovingCollisionCache(tasks::add,1,clock::get);cache.offer(source);
        cache.tick(250);assertEquals(300,cache.lastCaptureNanos());assertEquals(1,cache.overruns());
        assertNull(cache.entries().iterator().next().snapshot());assertTrue(tasks.isEmpty());
        for(int i=0;i<10;i++)cache.tick(10000);
        assertEquals(1,tasks.size());var worker=new Thread(tasks.remove());worker.start();worker.join();
        cache.tick(10000);var result=cache.entries().iterator().next().snapshot();assertNotNull(result);
        assertEquals(1,result.count());assertEquals(40,result.nodes().getFloat(16));assertTrue(result.nodes().isReadOnly());
    }
    @Test void staleTasksAndClearedEntriesCannotBecomeCurrent() {
        var tasks=new ArrayDeque<Runnable>();var source=new Source(new AtomicLong());source.length=1;
        var cache=new PackageMovingCollisionCache(tasks::add,1,()->0L);cache.offer(source);cache.tick(1);
        var old=cache.entries().iterator().next();long before=old.revision();cache.clear();
        assertEquals(0,old.poseFrame);assertTrue(old.revision()>before);
        cache.offer(source);tasks.remove().run();cache.tick(1);assertNull(cache.entries().iterator().next().snapshot());
        assertEquals(2,cache.entries().iterator().next().identity);tasks.remove().run();cache.tick(1);assertNotNull(cache.entries().iterator().next().snapshot());
        cache.remove(source.key());assertFalse(cache.entries().iterator().hasNext());
    }
    @Test void unavailableChunksAndPosesAreNeverAirAndCanRecover() {
        var source=new Source(new AtomicLong());source.length=1;source.missing=true;
        var cache=new PackageMovingCollisionCache(Runnable::run,1,()->0L);cache.offer(source);cache.tick(1);cache.tick(1);
        assertNull(cache.entries().iterator().next().snapshot());
        source.missing=false;cache.tick(1);cache.tick(1);assertNotNull(cache.entries().iterator().next().snapshot());
        source.poseMissing=true;cache.tick(1);assertFalse(cache.posesReady());
        source.poseMissing=false;cache.tick(1);assertTrue(cache.posesReady());assertNotNull(cache.entries().iterator().next().snapshot());
        cache.tick(0);assertFalse(cache.posesReady());
    }
    @Test void missingMovingBvhKeepsPoseAndBoundsForBodyLocalCollisionPauses() {
        var source=new Source(new AtomicLong());source.length=1;source.missing=true;
        var cache=new PackageMovingCollisionCache(Runnable::run,1,()->0L);
        assertTrue(cache.offer(source));cache.tick(1);
        var entry=cache.entries().iterator().next();
        assertNull(entry.snapshot());assertTrue(cache.posesReady());
        assertNotNull(entry.bounds);assertNotNull(entry.previous);assertNotNull(entry.current);
        cache.captureHistory(4,true);
        var historical=cache.history(4).getFirst();
        assertNotNull(historical.bounds);assertNotNull(historical.previous);assertNotNull(historical.current);
        assertNull(historical.snapshot());
    }
    @Test void iteratorChangesAreRetriedWithoutPublishingPartialGeometry() {
        var source=new Source(new AtomicLong());source.length=1;source.throwCursor=true;
        var cache=new PackageMovingCollisionCache(Runnable::run,1,()->0L);cache.offer(source);cache.tick(1);
        assertNull(cache.entries().iterator().next().snapshot());source.throwCursor=false;
        cache.tick(1);cache.tick(1);assertNotNull(cache.entries().iterator().next().snapshot());
        source.revision++;cache.tick(1);assertNull(cache.entries().iterator().next().snapshot());
        cache.tick(1);assertNotNull(cache.entries().iterator().next().snapshot());
    }
    @Test void globalWorkerBudgetSurvivesWorldClear() {
        var tasks=new ArrayDeque<Runnable>();var cache=new PackageMovingCollisionCache(tasks::add,8,()->0L);
        for(int i=0;i<8;i++){var source=new Source(new AtomicLong());source.length=1;assertTrue(cache.offer(source));}
        cache.tick(1);assertEquals(4,tasks.size());cache.clear();
        var source=new Source(new AtomicLong());source.length=1;cache.offer(source);cache.tick(1);assertEquals(4,tasks.size());
        while(!tasks.isEmpty())tasks.remove().run();
        cache.tick(1);assertEquals(1,tasks.size());tasks.remove().run();cache.tick(1);assertNotNull(cache.entries().iterator().next().snapshot());
    }
    @Test void geometryRetainsHolesMaterialsAndCapacityFailure() {
        var boxes=new ArrayList<PackageMovingGeometry.Box>();boxes.add(new PackageMovingGeometry.Box(0,0,0,1,1,1,.6f,0));
        boxes.add(new PackageMovingGeometry.Box(1,0,0,2,1,1,.7f,0));assertEquals(3,PackageMovingGeometry.bake(1,boxes).count());
        boxes.set(1,new PackageMovingGeometry.Box(2,0,0,3,1,1,.6f,0));assertEquals(3,PackageMovingGeometry.bake(1,boxes).count());
        boxes.clear();for(int i=0;i<4097;i++)boxes.add(new PackageMovingGeometry.Box(i*2,0,0,i*2+1,1,1,.6f,0));
        assertThrows(IllegalArgumentException.class,()->PackageMovingGeometry.bake(1,boxes));
    }
    @Test void optionalAbiFailuresRevokeCoverageAtEveryCaptureStage() {
        for(int stage=0;stage<5;stage++) {
            final int selected=stage;
            boolean[] broken={true};
            var source=new Source(new AtomicLong()) {
                void fail(int at){if(broken[0]&&selected==at)throw new NoSuchMethodError("Injected optional ABI change");}
                @Override public long revision(){fail(0);return super.revision();}
                @Override public PackageMovingGeometry.Pose pose(boolean previous){fail(1);return super.pose(previous);}
                @Override public PackageMovingCollisionCache.Cursor open(){
                    fail(2);var delegate=super.open();
                    return new PackageMovingCollisionCache.Cursor(){
                        public boolean hasNext(){fail(3);return delegate.hasNext();}
                        public List<PackageMovingGeometry.Box> next(){fail(4);return delegate.next();}
                    };
                }
            };
            source.length=1;
            var cache=new PackageMovingCollisionCache(Runnable::run,1,()->0L);cache.offer(source);
            assertDoesNotThrow(()->cache.tick(1));
            var entry=cache.entries().iterator().next();
            assertNull(entry.snapshot(),"ABI stage "+stage);
            if(stage<2)assertFalse(cache.posesReady());
            broken[0]=false;source.revision++;
            cache.tick(1);cache.tick(1);
            assertNotNull(entry.snapshot(),"Recovery stage "+stage);
            assertTrue(cache.posesReady());
        }
    }
}
