package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackageForceSnapshotsTest {
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
        assertTrue(snapshots.ready(11));assertFalse(snapshots.ready(12));
        assertThrows(IllegalStateException.class,()->snapshots.snapshot(12));
        snapshots.capture(12,List.of(),0,0,0,worker);
        assertFalse(snapshots.ready(12));worker.complete();assertTrue(snapshots.ready(12));
    }
    @Test void changedRegionsInvalidateCompletedAndInFlightCapturesWithinTheSameTick() {
        var worker=new Worker();var snapshots=new PackageForceSnapshots();
        snapshots.capture(10,List.of(),0,0,0,worker);worker.complete();assertTrue(snapshots.ready(10));
        snapshots.invalidate();assertFalse(snapshots.ready(10));assertTrue(snapshots.needsCapture(10));
        snapshots.capture(10,List.of(),0,0,0,worker);snapshots.invalidate();
        assertFalse(snapshots.needsCapture(10));assertEquals(1,worker.tasks.size());
        worker.complete();assertFalse(snapshots.ready(10));assertTrue(snapshots.needsCapture(10));
        snapshots.capture(10,List.of(),0,0,0,worker);worker.complete();assertTrue(snapshots.ready(10));
    }
    @Test void captureFreezesInputAndKeepsOnlyOneWorkerJob() {
        var worker=new Worker();var snapshots=new PackageForceSnapshots();
        var sources=new ArrayList<PackageForceScene.Source>();
        sources.add(new PackageForceScene.Source(PackageForceScene.ENTITY,0,0,0,1,1,1,.5,0,.5,1,0,0,0,0));
        snapshots.capture(10,sources,0,0,0,worker);sources.clear();
        snapshots.capture(11,List.of(),0,0,0,worker);assertEquals(1,worker.tasks.size());
        worker.complete();assertEquals(1,snapshots.snapshot(11).sources());assertFalse(snapshots.ready(9));
    }
    @Test void workerFailureIsReportedInsteadOfPublishingEmptyForces() {
        var worker=new Worker();var snapshots=new PackageForceSnapshots();
        snapshots.capture(10,List.of(),Double.NaN,0,0,worker);worker.complete();
        assertThrows(CompletionException.class,()->snapshots.ready(10));
    }
    @Test void shutdownCannotPublishLateWorkerResults() {
        var worker=new Worker();var snapshots=new PackageForceSnapshots();
        snapshots.capture(10,List.of(),0,0,0,worker);snapshots.clear();worker.complete();
        assertFalse(snapshots.ready(10));
    }
}
