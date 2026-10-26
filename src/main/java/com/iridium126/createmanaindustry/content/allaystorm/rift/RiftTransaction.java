package com.iridium126.createmanaindustry.content.allaystorm.rift;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Explicit durable barriers. Advancing this machine never waits for an I/O future on the server thread. */
public final class RiftTransaction<S> {
    public enum Stage { PREPARING, SNAPSHOTTING, PREFLIGHT, PREPARED_LOG, COMMITTING, COMMITTED_LOG,
        PUBLISHING, SUCCEEDED, ROLLING_BACK, ROLLED_BACK_LOG, CANCELLED, QUARANTINED }
    public interface Backend<S> {
        /** Must return false while chunks/resources/membership are changing; no freeze during cold preparation. */
        boolean prepare(long deadline);
        void freeze();
        S snapshot(long deadline);
        void preflight(S snapshot);
        default boolean preflightStep(S snapshot,long deadline){preflight(snapshot);return true;}
        CompletableFuture<Void> persist(String state, S snapshot);
        void commit(S snapshot);
        void verify(S snapshot);
        default boolean commitStep(S snapshot,long deadline){commit(snapshot);verify(snapshot);return true;}
        /** Called only after a durable COMMITTED decision; finish tracking, seats and source cleanup. */
        void publish(S snapshot);
        void rollback(S snapshot);
        default boolean publishStep(S snapshot,long deadline){publish(snapshot);return true;}
        default boolean rollbackStep(S snapshot,long deadline){rollback(snapshot);return true;}
        void release();
        void quarantine(S snapshot, Throwable cause);
    }
    private final Backend<S> backend;
    private final long expiresAt;
    private Stage stage = Stage.PREPARING;
    private S snapshot;
    private CompletableFuture<Void> barrier;
    private Throwable failure;
    private boolean cancellationRequested;
    public RiftTransaction(Backend<S> backend, long requestedTick) { this.backend=Objects.requireNonNull(backend); expiresAt=requestedTick+1200; }
    public Stage stage() { return stage; }
    public Throwable failure() { return failure; }
    public boolean terminal() { return stage==Stage.SUCCEEDED || stage==Stage.CANCELLED || stage==Stage.QUARANTINED; }
    public void tick(long tick, long deadline) {
        if (terminal() || System.nanoTime()>=deadline) return;
        try {
            if(cancellationRequested&&stage.ordinal()<Stage.COMMITTING.ordinal()){cancel();if(stage!=Stage.ROLLING_BACK)return;}
            if (tick>=expiresAt && stage.ordinal()<Stage.COMMITTING.ordinal()) { cancel(); return; }
            switch (stage) {
                case PREPARING -> {
                    if (!backend.prepare(deadline)) return;
                    backend.freeze(); stage=Stage.SNAPSHOTTING;
                }
                case SNAPSHOTTING -> { snapshot=backend.snapshot(deadline); if(snapshot!=null) stage=Stage.PREFLIGHT; }
                case PREFLIGHT -> {
                    if(!backend.preflightStep(snapshot,deadline))return;
                    barrier=Objects.requireNonNull(backend.persist("PREPARED",snapshot)); stage=Stage.PREPARED_LOG;
                }
                case PREPARED_LOG -> { if (complete()) stage=Stage.COMMITTING; }
                case COMMITTING -> {
                    if(!backend.commitStep(snapshot,deadline))return;
                    barrier=Objects.requireNonNull(backend.persist("COMMITTED",snapshot)); stage=Stage.COMMITTED_LOG;
                }
                case COMMITTED_LOG -> { if (complete()) stage=Stage.PUBLISHING; }
                case PUBLISHING -> { if(!backend.publishStep(snapshot,deadline))return; release(); stage=Stage.SUCCEEDED; }
                case ROLLING_BACK -> {
                    if(!backend.rollbackStep(snapshot,deadline))return;
                    barrier=Objects.requireNonNull(backend.persist("ROLLED_BACK",snapshot)); stage=Stage.ROLLED_BACK_LOG;
                }
                case ROLLED_BACK_LOG -> { if (complete()) { release(); stage=Stage.CANCELLED; } }
                default -> {}
            }
        } catch (Throwable error) {
            if (error instanceof VirtualMachineError fatal) throw fatal;
            if (error instanceof ThreadDeath fatal) throw fatal;
            if(failure!=null&&failure!=error)error.addSuppressed(failure);failure=error;
            if (stage==Stage.ROLLING_BACK || stage==Stage.ROLLED_BACK_LOG || stage==Stage.PUBLISHING) quarantine(error);
            else if (stage==Stage.PREPARED_LOG || stage==Stage.COMMITTING || stage==Stage.COMMITTED_LOG) stage=Stage.ROLLING_BACK;
            else {
                try { release(); stage=Stage.CANCELLED; }
                catch(Throwable thawFailure) { error.addSuppressed(thawFailure); quarantine(error); }
            }
        }
    }
    private boolean complete() {
        if (!barrier.isDone()) return false;
        // getNow is used only for detached journal I/O, never a chunk future.
        barrier.getNow(null); return true;
    }
    public void cancel() {
        if (terminal()) return;
        cancellationRequested=true;
        if (stage==Stage.COMMITTING || stage==Stage.COMMITTED_LOG) { stage=Stage.ROLLING_BACK; return; }
        // An outstanding PREPARED write must settle before releasing its frozen source snapshot.
        // Otherwise a late write could become a live recovery request after cancellation.
        if (stage==Stage.PREPARED_LOG && !barrier.isDone()) return;
        if (stage==Stage.PREPARED_LOG) { stage=Stage.ROLLING_BACK; return; }
        if (stage==Stage.PUBLISHING || stage==Stage.ROLLING_BACK || stage==Stage.ROLLED_BACK_LOG) return;
        release(); stage=Stage.CANCELLED;
    }
    private void release() { backend.release(); }
    private void quarantine(Throwable error) {
        stage=Stage.QUARANTINED;
        // No release: a half-restored group must remain isolated and its journal retained.
        try { backend.quarantine(snapshot,error); } catch(Throwable quarantineError) { error.addSuppressed(quarantineError); }
    }
}
