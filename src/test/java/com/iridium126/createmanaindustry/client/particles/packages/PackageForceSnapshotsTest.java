package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackageForceSnapshotsTest {
    @Test void renderingAfterConsumptionKeepsNewestImmutableAdmissionCapture(){
        var snapshots=new PackageForceSnapshots();snapshots.capture(1,List.of(),0,0,0,Runnable::run);
        var identity=snapshots.identity(1);snapshots.consumed(1);
        assertFalse(snapshots.needsCapture(1));assertSame(identity,snapshots.identity(1));assertTrue(snapshots.admissionReady(1));
        snapshots.capture(2,List.of(),0,0,0,Runnable::run);snapshots.consumed(2);assertFalse(snapshots.contains(1));assertTrue(snapshots.ready(2));
    }
    @Test void acceleratedRangeSharesOneBakeButKeepsExactStepIdentities(){
        var worker=new Worker();var snapshots=new PackageForceSnapshots();snapshots.tickRate(200);
        snapshots.captureRange(1,10,List.of(),0,0,0,worker);assertEquals(1,worker.tasks.size());
        assertFalse(snapshots.ready(10));worker.complete();
        for(int tick=1;tick<=10;tick++){assertEquals(tick,snapshots.snapshot(tick).tick());assertSame(snapshots.identity(1),snapshots.identity(tick));}
        snapshots.consumed(5);assertFalse(snapshots.contains(5));assertTrue(snapshots.ready(6));assertTrue(snapshots.ready(10));
        snapshots.captureRange(11,20,List.of(),0,0,0,worker);assertEquals(1,worker.tasks.size());
        for(int n=0;n<25;n++)snapshots.captureRange(21+n*10,30+n*10,List.of(),0,0,0,worker);
        assertTrue(worker.tasks.size()<=4);assertFalse(snapshots.contains(10));
        while(!snapshots.ready(80))while(!worker.tasks.isEmpty())worker.complete();
        assertEquals(80,snapshots.snapshot(80).tick());assertTrue(snapshots.ready(76));
    }
    @Test void twoHundredTpsHistoryRetainsExactInputsWithoutFloodingWorkers(){
        var worker=new Worker();var snapshots=new PackageForceSnapshots();snapshots.tickRate(200);
        for(long tick=0;tick<250;tick++)snapshots.capture(tick,List.of(),0,0,0,worker);
        assertEquals(4,worker.tasks.size());assertFalse(snapshots.contains(49));assertTrue(snapshots.contains(50));
        snapshots.tickRate(20);assertTrue(snapshots.contains(50));
        while(!snapshots.ready(50))while(!worker.tasks.isEmpty())worker.complete();
        assertEquals(50,snapshots.snapshot(50).tick());
    }
    private static final class Worker implements Executor {
        final ArrayDeque<Runnable> tasks=new ArrayDeque<>();
        @Override public void execute(Runnable task){tasks.addLast(task);}
        void complete(){tasks.removeFirst().run();}
    }
    @Test void readinessPollsAJobCompletedAfterFramePreparation() {
        var worker=new Worker();var snapshots=new PackageForceSnapshots();
        snapshots.capture(10,List.of(),0,0,0,worker);
        assertFalse(snapshots.ready(10));
        assertFalse(snapshots.needsCapture(10));
        worker.complete();
        assertTrue(snapshots.ready(10));assertEquals(10,snapshots.snapshot(10).tick());
        assertFalse(snapshots.needsCapture(10));
    }
    @Test void catchUpTicksDoNotAuthorizeAnExpiredSnapshot() {
        var worker=new Worker();var snapshots=new PackageForceSnapshots();
        snapshots.capture(10,List.of(),0,0,0,worker);worker.complete();
        assertFalse(snapshots.ready(11));assertFalse(snapshots.ready(12));
        assertThrows(IllegalStateException.class,()->snapshots.snapshot(12));
        snapshots.capture(12,List.of(),0,0,0,worker);
        assertFalse(snapshots.ready(12));worker.complete();assertTrue(snapshots.ready(12));
    }
    @Test void changedRegionsPreserveCatchupAndWaitForFreshAdmissionInputs() {
        var worker=new Worker();var snapshots=new PackageForceSnapshots();
        snapshots.capture(10,List.of(),0,0,0,worker);worker.complete();assertTrue(snapshots.ready(10));
        snapshots.capture(11,List.of(),0,0,0,worker);
        snapshots.changedRegions(11);assertTrue(snapshots.ready(10));assertFalse(snapshots.admissionReady(11));
        worker.complete();assertTrue(snapshots.ready(11));assertFalse(snapshots.admissionReady(11));
        snapshots.capture(12,List.of(),0,0,0,worker);worker.complete();assertTrue(snapshots.admissionReady(12));
        assertEquals(10,snapshots.snapshot(10).tick());assertEquals(11,snapshots.snapshot(11).tick());
        snapshots.consumed(10);assertFalse(snapshots.contains(10));assertTrue(snapshots.ready(11));
        snapshots.changedRegions(13);snapshots.capture(13,List.of(),0,0,0,worker);worker.complete();assertTrue(snapshots.admissionReady(13));
    }
    @Test void captureFreezesInputAndPreservesEachTick() {
        var worker=new Worker();var snapshots=new PackageForceSnapshots();
        var sources=new ArrayList<PackageForceScene.Source>();
        sources.add(new PackageForceScene.Source(PackageForceScene.ENTITY,0,0,0,1,1,1,.5,0,.5,1,0,0,0,0));
        snapshots.capture(10,sources,0,0,0,worker);sources.clear();
        snapshots.capture(11,List.of(),0,0,0,worker);assertEquals(2,worker.tasks.size());
        worker.complete();assertEquals(1,snapshots.snapshot(10).sources());assertFalse(snapshots.ready(11));worker.complete();assertEquals(0,snapshots.snapshot(11).sources());assertFalse(snapshots.ready(9));
    }
    @Test void workerFailureIsReportedInsteadOfPublishingEmptyForces() {
        var worker=new Worker();var snapshots=new PackageForceSnapshots();
        snapshots.capture(10,List.of(),Double.NaN,0,0,worker);worker.complete();
        assertThrows(CompletionException.class,()->snapshots.ready(10));
    }
    @Test void stalledWorkerHasBoundedJobsAndOneSecondHistory(){
        var worker=new Worker();var snapshots=new PackageForceSnapshots();
        for(long tick=0;tick<100;tick++)snapshots.capture(tick,List.of(),0,0,0,worker);
        assertEquals(4,worker.tasks.size());assertFalse(snapshots.contains(79));assertTrue(snapshots.contains(80));
        while(!worker.tasks.isEmpty())worker.complete();
        snapshots.ready(80);assertTrue(worker.tasks.size()<=4);
    }
    @Test void shutdownCannotPublishLateWorkerResults() {
        var worker=new Worker();var snapshots=new PackageForceSnapshots();
        snapshots.capture(10,List.of(),0,0,0,worker);snapshots.clear();worker.complete();
        assertFalse(snapshots.ready(10));
    }
}
