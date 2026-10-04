package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Server-thread ownership fence. Pool indices never cross this boundary. */
public final class PackageLease {
    public enum State { IDLE, ACQUIRING, GPU_OWNED, RELEASING }
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
    /** Resource preparation has a bounded acquisition deadline. */
    public static final long ACQUISITION_TIMEOUT_TICKS = 40;
    /** Final upload plus GPU admission readback must fit the supported 5 FPS cadence.
     * This fixed server deadline is never renewed by a region heartbeat. */
    public static final long FINAL_BASELINE_TIMEOUT_TICKS = 20;
    /** Brief render/GPU stalls must not revoke an otherwise live region's packages. */
    public static final long AUTHORITY_HEARTBEAT_TIMEOUT_TICKS = 100;
    private final Identity identity;
    private State state = State.IDLE;
    private UUID authority;
    private long epoch, baselineRevision, lastSequence = -1, lastTransaction = -1, lastReceiptTick;
    private Pose committed;
    private boolean frozen;
    private final LongSupplier regionReceipt;
    private final long authorityTimeoutTicks;
    private final long finalBaselineTimeoutTicks;
    private final java.util.function.DoubleSupplier tickRate;

    public PackageLease(Identity identity, Pose initial) {
        this(identity,initial,null,TIMEOUT_TICKS);
    }
    public PackageLease(Identity identity, Pose initial, LongSupplier regionReceipt) {
        this(identity,initial,regionReceipt,TIMEOUT_TICKS);
    }
    public PackageLease(Identity identity, Pose initial, LongSupplier regionReceipt,long authorityTimeoutTicks) {
        this(identity,initial,regionReceipt,authorityTimeoutTicks,TIMEOUT_TICKS);
    }
    public PackageLease(Identity identity, Pose initial, LongSupplier regionReceipt,long authorityTimeoutTicks,long finalBaselineTimeoutTicks) {
        this(identity,initial,regionReceipt,authorityTimeoutTicks,finalBaselineTimeoutTicks,PackageTickTiming.DEFAULT_RATE);
    }
    public PackageLease(Identity identity, Pose initial, LongSupplier regionReceipt,long authorityTimeoutTicks,long finalBaselineTimeoutTicks,java.util.function.DoubleSupplier tickRate) {
        this.identity = Objects.requireNonNull(identity);
        committed = Objects.requireNonNull(initial);
        this.regionReceipt=regionReceipt;
        if(authorityTimeoutTicks<TIMEOUT_TICKS)throw new IllegalArgumentException("Authority timeout");
        this.authorityTimeoutTicks=authorityTimeoutTicks;
        if(finalBaselineTimeoutTicks<TIMEOUT_TICKS || finalBaselineTimeoutTicks>ACQUISITION_TIMEOUT_TICKS)
            throw new IllegalArgumentException("Final baseline timeout");
        this.finalBaselineTimeoutTicks=finalBaselineTimeoutTicks;
        this.tickRate=Objects.requireNonNull(tickRate);
    }

    /** Begin only after server eligibility checks; Records retain their confirmed state until ready. */
    public long acquire(UUID client, Pose current, long tick) {
        if (state != State.IDLE) throw new IllegalStateException("Package already leased");
        authority = Objects.requireNonNull(client);
        committed = Objects.requireNonNull(current);
        epoch = Math.incrementExact(epoch);
        baselineRevision = Math.incrementExact(baselineRevision);
        lastSequence = lastTransaction = -1;
        lastReceiptTick = tick;
        frozen=false;
        state = State.ACQUIRING;
        return epoch;
    }

    /** Caller confirms collision coverage, model, pool allocation and the current baseline revision. */
    public boolean ready(UUID client, long candidateEpoch, long candidateBaseline, long tick, Pose current) {
        Objects.requireNonNull(current);
        if (state != State.ACQUIRING || !frozen || !matches(client, candidateEpoch) || expired(tick)
                || candidateBaseline != baselineRevision || !current.equals(committed)) return false;
        lastReceiptTick = tick;
        state = State.GPU_OWNED;
        frozen=false;
        return true;
    }

    /** Resources are prepared; capture the current confirmed checkpoint, then wait for its exact ACK.
     * The adapter freezes the baseline during this bounded final-baseline window. */
    public long freezeBaseline(UUID client,long candidateEpoch,long candidateBaseline,long tick,Pose current) {
        if(state!=State.ACQUIRING || frozen || !matches(client,candidateEpoch) || expired(tick)
                || candidateBaseline!=baselineRevision)return -1;
        committed=Objects.requireNonNull(current);
        baselineRevision=Math.incrementExact(baselineRevision);
        lastReceiptTick=tick;frozen=true;
        return baselineRevision;
    }

    /** While the adapter is still advancing chain logistics, replace the offered baseline and require a fresh ACK. */
    public long refreshAcquisition(Pose current) {
        if (state != State.ACQUIRING) throw new IllegalStateException("No acquisition in progress");
        if(frozen)throw new IllegalStateException("Final acquisition baseline is frozen");
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
        double dx=pose.x-committed.x,dy=pose.y-committed.y,dz=pose.z-committed.z;
        return commitMeasured(client,candidateEpoch,sequence,tick,pose,maxDisplacement,
                Math.hypot(Math.hypot(dx,dy),dz));
    }
    boolean commitMeasured(UUID client,long candidateEpoch,long sequence,long tick,Pose pose,
                           double maxDisplacement,double measuredDisplacement) {
        if(!canCommitMeasured(client,candidateEpoch,sequence,tick,pose,maxDisplacement,measuredDisplacement))return false;
        committed = pose;
        lastSequence = sequence;
        lastReceiptTick = tick;
        return true;
    }
    public boolean canCommit(UUID client,long candidateEpoch,long sequence,long tick,Pose pose,double maxDisplacement) {
        Objects.requireNonNull(pose);
        double dx=pose.x-committed.x,dy=pose.y-committed.y,dz=pose.z-committed.z;
        return canCommitMeasured(client,candidateEpoch,sequence,tick,pose,maxDisplacement,
                Math.hypot(Math.hypot(dx,dy),dz));
    }
    boolean canCommitMeasured(UUID client,long candidateEpoch,long sequence,long tick,Pose pose,
                              double maxDisplacement,double measuredDisplacement) {
        Objects.requireNonNull(pose);
        if (state != State.GPU_OWNED || !matches(client, candidateEpoch) || expired(tick)
                || sequence <= lastSequence || sequence < 0 || !(maxDisplacement >= 0)
                || !Double.isFinite(maxDisplacement) || !(measuredDisplacement>=0)
                || !Double.isFinite(measuredDisplacement)) return false;
        return measuredDisplacement<=maxDisplacement;
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

    /** Invalidate authority before the adapter completes pause or chain logistics migration. */
    public Pose release() {
        if (state == State.IDLE || state == State.RELEASING) return committed;
        epoch = Math.incrementExact(epoch);
        authority = null;
        state = State.RELEASING;
        frozen=false;
        return committed;
    }

    /** Cancelling an acquisition preserves the adapter's latest confirmed progress. */
    public Pose release(Pose currentPose) {
        if(state==State.ACQUIRING)committed=Objects.requireNonNull(currentPose);
        return release();
    }

    public void finishRelease() {
        if (state != State.RELEASING) throw new IllegalStateException("No pending release");
        state = State.IDLE;
    }

    public boolean expired(long tick) {
        long receipt=lastReceiptTick;
        // Shared server-owned receipt is O(1) per region. It cannot extend acquisition deadlines.
        if(state==State.GPU_OWNED && regionReceipt!=null)receipt=Math.max(receipt,regionReceipt.getAsLong());
        long allowed=state==State.ACQUIRING?(frozen?finalBaselineTimeoutTicks:ACQUISITION_TIMEOUT_TICKS):
                state==State.GPU_OWNED && regionReceipt!=null?authorityTimeoutTicks:TIMEOUT_TICKS;
        return tick<receipt || tick-receipt>PackageTickTiming.deadlineTicks(allowed,tickRate.getAsDouble());
    }
    private boolean matches(UUID client, long candidateEpoch) {
        return candidateEpoch == epoch && authority != null && authority.equals(client);
    }
    public Identity identity() { return identity; }
    public State state() { return state; }
    public long epoch() { return epoch; }
    public long baselineRevision() { return baselineRevision; }
    public Pose committed() { return committed; }
    public boolean physicsPaused() {return state==State.GPU_OWNED || state==State.ACQUIRING && frozen;}
    public UUID authority() {return authority;}
}
