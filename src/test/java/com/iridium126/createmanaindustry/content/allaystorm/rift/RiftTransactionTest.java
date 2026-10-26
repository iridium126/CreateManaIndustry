package com.iridium126.createmanaindustry.content.allaystorm.rift;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class RiftTransactionTest {
    static final class Fake implements RiftTransaction.Backend<String> {
        final List<String> events=new ArrayList<>();
        CompletableFuture<Void> prepared=new CompletableFuture<>(), committed=new CompletableFuture<>(), rolledBack=new CompletableFuture<>();
        boolean ready=true, cancelTravel, failCommit, failMount, failRollback, failPublish;
        public boolean prepare(long deadline) { events.add("prepare");return ready; }
        public void freeze() { events.add("freeze"); }
        public String snapshot(long deadline) { events.add("snapshot");return "group"; }
        public void preflight(String snapshot) { events.add("travel-events");if(cancelTravel)throw new IllegalStateException("cancelled"); }
        public CompletableFuture<Void> persist(String state,String snapshot) { events.add(state);return switch(state){case "PREPARED"->prepared;case "COMMITTED"->committed;default->rolledBack;}; }
        public void commit(String snapshot) { events.add("move");if(failCommit)throw new IllegalStateException("member failed"); }
        public void verify(String snapshot) { events.add("verify-mounts");if(failMount)throw new IllegalStateException("mount failed"); }
        public void publish(String snapshot) { events.add("publish");if(failPublish)throw new IllegalStateException("publish failed"); }
        public void rollback(String snapshot) { events.add("restore");if(failRollback)throw new IllegalStateException("restore failed"); }
        public void release() { events.add("release"); }
        public void quarantine(String snapshot,Throwable cause) { events.add("quarantine"); }
    }
    void tick(RiftTransaction<?> transaction,int count) { for(int i=0;i<count;i++) transaction.tick(i,System.nanoTime()+1_000_000_000L); }
    @Test void coldLoadingNeverFreezesAndPreparedBarrierPrecedesAnyMove() {
        Fake backend=new Fake();backend.ready=false;var tx=new RiftTransaction<>(backend,0);
        tick(tx,5);assertFalse(backend.events.contains("freeze"));
        backend.ready=true;tick(tx,3);assertEquals(RiftTransaction.Stage.PREPARED_LOG,tx.stage());
        tick(tx,10);assertFalse(backend.events.contains("move"));
        backend.prepared.complete(null);tick(tx,2);
        assertEquals(RiftTransaction.Stage.COMMITTED_LOG,tx.stage());assertFalse(backend.events.contains("publish"));
        backend.committed.complete(null);tick(tx,2);
        assertEquals(RiftTransaction.Stage.SUCCEEDED,tx.stage());
        assertEquals(List.of("move","verify-mounts","COMMITTED","publish","release"),backend.events.subList(backend.events.indexOf("move"),backend.events.size()));
    }
    @Test void memberTravelCancellationMovesNobodyAndReleasesWholeGroup() {
        Fake backend=new Fake();backend.cancelTravel=true;var tx=new RiftTransaction<>(backend,0);tick(tx,6);
        assertEquals(RiftTransaction.Stage.CANCELLED,tx.stage());assertFalse(backend.events.contains("move"));assertFalse(backend.events.contains("PREPARED"));assertTrue(backend.events.contains("release"));
    }
    @Test void partialMoveOrMountFailureNeedsDurableCompensationBeforeRelease() {
        for(boolean mount:new boolean[]{false,true}) {
            Fake backend=new Fake();backend.failCommit=!mount;backend.failMount=mount;backend.prepared.complete(null);
            var tx=new RiftTransaction<>(backend,0);tick(tx,7);
            assertEquals(RiftTransaction.Stage.ROLLED_BACK_LOG,tx.stage());assertTrue(backend.events.contains("restore"));assertFalse(backend.events.contains("release"));
            backend.rolledBack.complete(null);tick(tx,1);assertEquals(RiftTransaction.Stage.CANCELLED,tx.stage());assertTrue(backend.events.contains("release"));assertFalse(backend.events.contains("publish"));
        }
    }
    @Test void failedRestorationStaysFrozenAndRetainsTheJournal() {
        Fake backend=new Fake();backend.prepared.complete(null);backend.failCommit=true;backend.failRollback=true;
        var tx=new RiftTransaction<>(backend,0);tick(tx,10);
        assertEquals(RiftTransaction.Stage.QUARANTINED,tx.stage());assertFalse(backend.events.contains("release"));assertTrue(backend.events.contains("quarantine"));
    }
    @Test void latePreparedWriteCannotResurrectACancelledMovingGroup() {
        Fake backend=new Fake();var tx=new RiftTransaction<>(backend,0);tick(tx,3);tx.cancel();
        assertEquals(RiftTransaction.Stage.PREPARED_LOG,tx.stage());assertFalse(backend.events.contains("release"));
        backend.prepared.complete(null);tx.cancel();tick(tx,1);
        assertEquals(RiftTransaction.Stage.ROLLED_BACK_LOG,tx.stage());assertFalse(backend.events.contains("move"));
        backend.rolledBack.complete(null);tick(tx,1);assertEquals(RiftTransaction.Stage.CANCELLED,tx.stage());
    }
    @Test void afterCommittedDecisionPublishFailureQuarantinesForRollForward() {
        Fake backend=new Fake();backend.prepared.complete(null);backend.committed.complete(null);backend.failPublish=true;
        var tx=new RiftTransaction<>(backend,0);tick(tx,10);
        assertEquals(RiftTransaction.Stage.QUARANTINED,tx.stage());assertFalse(backend.events.contains("restore"));assertFalse(backend.events.contains("release"));
    }
    @Test void timeoutWhilePreparingMovesNobody() {
        Fake backend=new Fake();backend.ready=false;var tx=new RiftTransaction<>(backend,0);
        tx.tick(1200,System.nanoTime()+1_000_000_000L);assertEquals(RiftTransaction.Stage.CANCELLED,tx.stage());assertFalse(backend.events.contains("freeze"));
    }
    @Test void cancellationIsRememberedWhileThePreparedWriteIsPending() {
        var backend=new Fake();var tx=new RiftTransaction<>(backend,0);tick(tx,3);tx.cancel();tick(tx,4);
        assertEquals(RiftTransaction.Stage.PREPARED_LOG,tx.stage());backend.prepared.complete(null);tick(tx,1);
        assertEquals(RiftTransaction.Stage.ROLLED_BACK_LOG,tx.stage());assertFalse(backend.events.contains("move"));
        backend.rolledBack.complete(null);tick(tx,1);assertEquals(RiftTransaction.Stage.CANCELLED,tx.stage());
    }
}
