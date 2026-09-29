package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import java.util.Objects;
import java.util.UUID;

/** Server-thread ownership fence. Pool indices never cross this boundary. */
public final class PackageLease {
    public enum State { CREATE_OWNED, ACQUIRING, GPU_OWNED, RELEASING }
    public record Identity(long id, long generation) {
        public Identity {
            if (id <= 0 || generation <= 0) throw new IllegalArgumentException("Invalid package identity");
        }
    }
    public record Pose(double x, double y, double z, float vx, float vy, float vz, float yaw) {
        public Pose {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(vx) || !Float.isFinite(vy) || !Float.isFinite(vz)
                    || !Float.isFinite(yaw)) throw new IllegalArgumentException("Non-finite package pose");
        }
    }

    public static final long TIMEOUT_TICKS = 2;
    private final Identity identity;
    private State state = State.CREATE_OWNED;
    private UUID authority;
    private long epoch, baselineRevision, lastSequence = -1, lastTransaction = -1, lastReceiptTick;
    private Pose committed;

    public PackageLease(Identity identity, Pose initial) {
        this.identity = Objects.requireNonNull(identity);
        committed = Objects.requireNonNull(initial);
    }

    /** Begin only after server eligibility checks; Create keeps simulating until ready. */
    public long acquire(UUID client, Pose current, long tick) {
        if (state != State.CREATE_OWNED) throw new IllegalStateException("Package already leased");
        authority = Objects.requireNonNull(client);
        committed = Objects.requireNonNull(current);
        epoch = Math.incrementExact(epoch);
        baselineRevision = Math.incrementExact(baselineRevision);
        lastSequence = lastTransaction = -1;
        lastReceiptTick = tick;
        state = State.ACQUIRING;
        return epoch;
    }

    /** Caller confirms collision coverage, model, pool allocation and the current baseline revision. */
    public boolean ready(UUID client, long candidateEpoch, long candidateBaseline, long tick, Pose current) {
        Objects.requireNonNull(current);
        if (state != State.ACQUIRING || !matches(client, candidateEpoch) || expired(tick)
                || candidateBaseline != baselineRevision || !current.equals(committed)) return false;
        lastReceiptTick = tick;
        state = State.GPU_OWNED;
        return true;
    }

    /** While Create is still moving the object, replace the offered baseline and require a fresh ACK. */
    public long refreshAcquisition(Pose current) {
        if (state != State.ACQUIRING) throw new IllegalStateException("No acquisition in progress");
        if (!Objects.requireNonNull(current).equals(committed)) {
            committed=current;
            baselineRevision=Math.incrementExact(baselineRevision);
        }
        return baselineRevision;
    }

    /** Receipt time is server-owned. Client timestamps cannot extend a stale lease. */
    public boolean commit(UUID client, long candidateEpoch, long sequence, long tick, Pose pose,
                          double maxDisplacement) {
        Objects.requireNonNull(pose);
        if (state != State.GPU_OWNED || !matches(client, candidateEpoch) || expired(tick)
                || sequence <= lastSequence || sequence < 0 || !(maxDisplacement >= 0)
                || !Double.isFinite(maxDisplacement)) return false;
        double dx = pose.x - committed.x, dy = pose.y - committed.y, dz = pose.z - committed.z;
        if (Math.hypot(Math.hypot(dx, dy), dz) > maxDisplacement) return false;
        committed = pose;
        lastSequence = sequence;
        lastReceiptTick = tick;
        return true;
    }

    /** Region heartbeat keeps sleeping packages leased without repeated coordinate packets. */
    public boolean heartbeat(UUID client, long candidateEpoch, long tick) {
        if (state != State.GPU_OWNED || !matches(client, candidateEpoch) || expired(tick)) return false;
        lastReceiptTick = tick;
        return true;
    }

    /** Ordered reliable event stream; validate the proposed operation BEFORE claiming its sequence. */
    public boolean claimTransaction(UUID client, long candidateEpoch, long sequence, long tick) {
        if (state != State.GPU_OWNED || !matches(client, candidateEpoch) || expired(tick)
                || sequence < 0 || sequence != lastTransaction + 1) return false;
        lastTransaction = sequence;
        return true;
    }

    /** Invalidates authority before the caller restores the committed checkpoint to Create. */
    public Pose release() {
        if (state == State.CREATE_OWNED || state == State.RELEASING) return committed;
        epoch = Math.incrementExact(epoch);
        authority = null;
        state = State.RELEASING;
        return committed;
    }

    public void restored() {
        if (state != State.RELEASING) throw new IllegalStateException("No pending restore");
        state = State.CREATE_OWNED;
    }

    public boolean expired(long tick) { return tick < lastReceiptTick || tick - lastReceiptTick > TIMEOUT_TICKS; }
    private boolean matches(UUID client, long candidateEpoch) {
        return candidateEpoch == epoch && authority != null && authority.equals(client);
    }
    public Identity identity() { return identity; }
    public State state() { return state; }
    public long epoch() { return epoch; }
    public long baselineRevision() { return baselineRevision; }
    public Pose committed() { return committed; }
}
