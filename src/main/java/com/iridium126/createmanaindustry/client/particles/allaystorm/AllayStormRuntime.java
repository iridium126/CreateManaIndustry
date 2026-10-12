package com.iridium126.createmanaindustry.client.particles.allaystorm;

import java.util.BitSet;

import com.iridium126.createmanaindustry.client.particles.engine.CMIParticleEngine;
import com.iridium126.createmanaindustry.client.particles.engine.ParticleBuffers;
import com.iridium126.createmanaindustry.content.allaystorm.AllayStormData;
import com.iridium126.createmanaindustry.content.allaystorm.AllayStormManager;
import com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormStatePacket;
import com.iridium126.createmanaindustry.content.allaystorm.network.ServerboundStormPositionsPacket;
import com.iridium126.createmanaindustry.content.allaystorm.network.ServerboundStormWaveContactPacket;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Allay Storm RUNTIME: every mutable storm state and behavior that used to
 * live on {@link CMIParticleEngine}, extracted so the engine keeps only the
 * frame skeleton (dispatch ordering, pool bookkeeping, abort discipline) while
 * the storm owns its own state machine — the completion of the
 * {@link AllayStormSpec} extraction ("the engine file keeps only the storm's
 * RUNTIME state"). Sits alongside the other two storm pillars:
 * {@link AllayStormSpec} (what a member IS — static spec + header packing) and
 * {@link AllayStormClientHandler} (how packets ENTER — thin unwrapping).
 * <p>
 * Contract:
 * <ul>
 *   <li><b>Render thread only</b>, exactly like the engine fields it
 *       replaced: payload handlers enqueueWork to the main thread, GPU reads
 *       and writes happen inside the engine's compute phase. NO
 *       synchronization is added here — the storm is single-threaded by
 *       design, and a lock would be a real regression.</li>
 *   <li><b>Back-reference discipline</b>: the constructor stores the engine
 *       reference WITHOUT dereferencing it (engine fields initialize after
 *       this one); every engine touch goes through the engine's
 *       internal accessors at call time.</li>
 *   <li><b>Loop discipline</b> (the zero-regression contract from the
 *       extraction review): per-element loops iterate over THIS class's own
 *       fields or references captured before the loop ({@link EmitSchedule},
 *       {@link #playerScratch}); no engine accessor may be called inside a
 *       per-element loop.</li>
 * </ul>
 * The engine's public storm API ({@code applyStormState / applyCorrections /
 * applyStormDamage / timeSec}) forwards here one-line, so
 * {@link AllayStormClientHandler} and the shader-pack merge hook are untouched.
 * The shared simulation clock and the crosshair hit snapshot live here too —
 * the MODEL material exists to serve the storm (non-storm allay MODEL
 * particles are the early test harness), so the allay pose clock and the
 * melee hit-query state are storm assets by classification.
 */
public final class AllayStormRuntime {

    private final CMIParticleEngine engine;

    /** Stores the back-reference ONLY — see the contract javadoc above. */
    public AllayStormRuntime(CMIParticleEngine engine) {
        this.engine = engine;
    }

    /**
     * Per-frame emit scheduling context handed to
     * {@link #scheduleSpawnRuns}: the engine's command arrays by reference
     * plus the running entry/spawn counters (which are runCompute LOCALS —
     * carrying them here lets the storm's scheduling loop write through the
     * same shape the engine's burst/stream loops use). Constructed fresh each
     * frame before the storm scheduling call; the engine copies the counters
     * back and every later writer (bursts/streams/free-pool rescale) keeps
     * touching its own arrays and locals unchanged.
     */
    public static final class EmitSchedule {
        public final int[] emitIds;
        public final int[] emitCounts;
        public final Vec3[] emitOrigins;
        public final boolean[] emitTranslucent;
        public final float[] emitOriginRef;
        public final float[] emitLight;
        public final int[] emitMemberBase;
        public final int[] emitMemberKey;
        public final boolean[] emitRingSpawn;
        public int entryCount;
        public int totalSpawn;

        public EmitSchedule(int[] emitIds, int[] emitCounts, Vec3[] emitOrigins, boolean[] emitTranslucent,
                float[] emitOriginRef, float[] emitLight, int[] emitMemberBase, int[] emitMemberKey,
                boolean[] emitRingSpawn) {
            this.emitIds = emitIds;
            this.emitCounts = emitCounts;
            this.emitOrigins = emitOrigins;
            this.emitTranslucent = emitTranslucent;
            this.emitOriginRef = emitOriginRef;
            this.emitLight = emitLight;
            this.emitMemberBase = emitMemberBase;
            this.emitMemberKey = emitMemberKey;
            this.emitRingSpawn = emitRingSpawn;
            this.entryCount = 0;
            this.totalSpawn = 0;
        }
    }

    // ---- shared simulation clock --------------------------------------------
    /**
     * Shared simulation clock: {@code (gameTime mod 2^21) / 20} seconds. All
     * clients derive the SAME value (integer modulo, no accumulation), so the
     * vortex phases and correction-slot timestamps agree
     * without any clock sync. Float precision at the 2^21-tick wrap horizon
     * (~29 h of continuous uptime) stays at ~8 ms; the wrap itself perturbs
     * the orbit once per period — accepted (boss fights never span it).
     * Consumed beyond the storm proper by the allay pose paths (model render
     * uniform, shader-pack merge hook) — hence "shared".
     */
    private float timeSec = 0f;

    /** Advances the shared clock from the level's game time (render thread). */
    public void tickClock(long gameTime) {
        this.gameTimeNow = gameTime;
        this.timeSec = (gameTime & ((1 << 21) - 1)) / 20.0f;
        refreshInterpolatedCenter();
        refreshInterpolatedCount();
    }

    /**
     * Re-evaluates the interpolated center for this frame: render time sits
     * one interval behind the newest snapshot; before the first center packet
     * (and after deactivation) it falls back to the raw packet anchor.
     */
    private void refreshInterpolatedCenter() {
        if (!this.centerValid) {
            this.center = this.stormAnchor;
            return;
        }
        float renderT = this.timeSec - CENTER_LAG_SECONDS;
        float span = this.centerCurrT - this.centerPrevT;
        float alpha = span <= 0.0f ? 1.0f : (renderT - this.centerPrevT) / span;
        alpha = Math.max(0.0f, Math.min(1.0f, alpha));
        this.center = new Vec3(
                this.centerPrevX + (this.centerCurrX - this.centerPrevX) * alpha,
                this.centerPrevY + (this.centerCurrY - this.centerPrevY) * alpha,
                this.centerPrevZ + (this.centerCurrZ - this.centerPrevZ) * alpha);
    }

    /**
     * Re-evaluates the interpolated generated count for this frame — the same
     * double-snapshot scheme as {@link #refreshInterpolatedCenter} (shared
     * clock, one interval behind, clamped alpha): bit-identical on every
     * client. The storm radius DERIVES from it every frame
     * ({@code sqrt(count)/8}, unquantized) and ω follows ({@code SPIN_K/radius}),
     * so the expanding typhoon shell grows smoothly instead of jumping at
     * packet or quantization steps. Before the first center packet (and
     * after ACTIVATE resets validity) the count falls back to the raw packet
     * integer — an ACTIVATE-time derivation is already exact.
     */
    private void refreshInterpolatedCount() {
        if (!this.countValid) {
            this.countInterp = this.stormCount;
        } else {
            float renderT = this.timeSec - CENTER_LAG_SECONDS;
            float span = this.countCurrT - this.countPrevT;
            float alpha = span <= 0.0f ? 1.0f : (renderT - this.countPrevT) / span;
            alpha = Math.max(0.0f, Math.min(1.0f, alpha));
            this.countInterp = this.countPrev + (this.countCurr - this.countPrev) * alpha;
        }
        if (this.stormActive) {
            this.stormRadius = AllayStormData.vortexRadius(this.countInterp);
            // growth-law phase integrals (see the field doc above): LINEAR in
            // the grown radius during the growth window, constant-rate after.
            float r0 = this.stormCreationRadius;
            float rf = Math.max(this.stormFinalRadius, 0.5f);
            float rNow = (float) this.stormRadius;
            float grown = Math.min(rNow, rf) - r0;
            float age = (this.gameTimeNow - this.stormCreatedAtGameTime) / 20.0f;
            float growDur = this.stormGrowthPerSecond > 0.0f
                    ? (float) (64.0 * ((double) rf * rf - (double) r0 * r0) / this.stormGrowthPerSecond)
                    : 0.0f;
            float postSec = Math.max(0.0f, age - growDur);
            float g = Math.max(this.stormGrowthPerSecond, 1.0e-6f);
            // rotPhase grows unbounded post-growth (SPIN_K/rf per second), so
            // a bare float loses fractional-turn precision after a few weeks
            // of uptime (the ULP exceeds the per-frame increment and the
            // rotation stutters). Accumulate in double and reduce mod 2*pi
            // before the float store — an EXACT equivalence, not an
            // approximation: every consumer (servo target + analytic spawn,
            // CPU and GPU) reads the phase through cos/sin with coefficient
            // 1, and Java's % is fully-specified IEEE fmod, so all clients
            // stay bit-identical. convPhase cannot take the same reduction
            // (see the stormConvPhase field).
            double rotPhase = (double) AllayStormData.SPIN_K * 128.0 * grown / g
                    + (double) AllayStormData.SPIN_K / rf * postSec;
            this.stormRotPhase = (float) (rotPhase % (2.0 * Math.PI));
            this.stormConvPhase = 64.0f * grown / g + postSec / (2.0f * rf);
            this.stormConvRate = (rNow < rf ? 1.0f / (2.0f * rNow) : 0.0f)
                    + (postSec > 0.0f ? 1.0f / (2.0f * rf) : 0.0f);
            this.stormOmegaNow = AllayStormData.vortexOmega(rNow, this.stormSeed);
        }
    }

    /**
     * Applies one {@code ClientboundStormCenterPacket}: the previous current
     * snapshot rolls into prev, the packet becomes current. The FIRST packet
     * initializes both snapshots (no lerp from the stale block anchor).
     * Timestamps share the simulation clock's seconds domain, exactly like
     * the correction slots. {@code velX}/{@code velZ} ride the wire for
     * diagnostics / a future extrapolation path — the clamped lerp ignores
     * them (a late packet freezes the center for under a second, invisible
     * at 2 b/s).
     * <p>
     * The packet's growth state drives two consumers: the generated count
     * joins the double-snapshot interpolation (geometry derivation, see
     * {@link #refreshInterpolatedCount}) and its integer delta joins the
     * spawn backlog (new indices later spawn on the ring and fly in).
     */
    public void applyStormCenter(float x, float y, float z, float velX, float velZ,
            int count, long gameTime) {
        if (!this.stormActive)
            return;
        float t = clockSeconds(gameTime);
        if (!this.centerValid) {
            this.centerPrevX = x;
            this.centerPrevY = y;
            this.centerPrevZ = z;
            this.centerPrevT = t;
        } else if (this.centerCurrT != t) {
            this.centerPrevX = this.centerCurrX;
            this.centerPrevY = this.centerCurrY;
            this.centerPrevZ = this.centerCurrZ;
            this.centerPrevT = this.centerCurrT;
        }
        this.centerCurrX = x;
        this.centerCurrY = y;
        this.centerCurrZ = z;
        this.centerCurrT = t;
        this.centerValid = true;

        // count snapshot (same double-buffer scheme as the center above)
        if (!this.countValid) {
            this.countPrev = count;
            this.countPrevT = t;
        } else if (this.countCurrT != t) {
            this.countPrev = this.countCurr;
            this.countPrevT = this.countCurrT;
        }
        this.countCurr = count;
        this.countCurrT = t;
        this.countValid = true;

        // growth delta: the server's generated population advanced — the new
        // indices join the spawn backlog (members cannot be removed by this
        // stream; only ACTIVATE/STOP reshape the population)
        if (count > this.stormCount)
            this.pendingStormSpawns += count - this.stormCount;
        this.stormCount = Math.max(this.stormCount, count);
    }

    /** Shared simulation clock in seconds (storm phases/dance bursts + pose paths). */
    public float timeSec() {
        return this.timeSec;
    }

    /** Shared simulation clock from a game-time tick value (see {@link #timeSec}). */
    private static float clockSeconds(long gameTime) {
        return (gameTime & ((1 << 21) - 1)) / 20.0f;
    }

    // ---- storm sync state ----------------------------------------------------

    private boolean stormActive = false;
    private int stormEmitId = -1;
    private Vec3 stormAnchor = Vec3.ZERO;

    // ---- continuous chased center (double-snapshot interpolation) -----------
    /**
     * The chased center arrives as {@code ClientboundStormCenterPacket}
     * snapshots every {@code AllayStormManager.CENTER_INTERVAL_TICKS} (20
     * ticks = 1 s). Every frame renders ONE INTERVAL BEHIND the newest
     * snapshot, lerping the last two — the standard entity-sync scheme, and a
     * deterministic function of the packet stream: every client derives the
     * SAME center, so the servo targets stay mutually consistent while the
     * motion is continuous at frame rate. Each client sits ~2 blocks (one
     * interval at the 2 b/s chase speed) behind the server's truth; the hit
     * reach check's +8 margin absorbs that, and every anchor-relative wire
     * coordinate (hit reports, corrections, snapshots) is produced AND
     * consumed against this same interpolated center.
     * <p>
     * A packet that never arrives clamps alpha at 1 — the center freezes for
     * under a second instead of teleporting; the wire's velocity fields are
     * carried for diagnostics and any future extrapolation path.
     */
    private static final float CENTER_LAG_SECONDS = AllayStormManager.CENTER_INTERVAL_TICKS / 20.0f;
    private float centerPrevX;
    private float centerPrevY;
    private float centerPrevZ;
    private float centerCurrX;
    private float centerCurrY;
    private float centerCurrZ;
    private float centerPrevT;
    private float centerCurrT;
    private boolean centerValid = false;
    /** Interpolated center, refreshed every {@link #tickClock}; all consumers read this. */
    private Vec3 center = Vec3.ZERO;

    // ---- continuous generated-count interpolation (growth channel) ----------
    /**
     * The generated population arrives as center-packet integers at 1 Hz; the
     * vortex radius/omega derive from it CONTINUOUSLY through the same
     * double-snapshot scheme as the chased center (shared-clock alpha, so
     * every client computes bit-identical values — no per-client divergence,
     * no packet-step or 0.5-quantization jumps in the expanding shell). The
     * raw integer {@link #stormCount} stays the spawn-backlog authority; this
     * interpolated float feeds only the geometry derivation.
     */
    private float countPrev;
    private float countCurr;
    private float countPrevT;
    private float countCurrT;
    private boolean countValid = false;
    /** Interpolated generated count, refreshed every {@link #tickClock}. */
    private float countInterp = 0f;

    private double stormRadius = 8.0;

    // ---- frozen growth law + per-frame phase integrals (vortex) -------------
    /**
     * The typhoon's rotation and conveyor phases are INTEGRALS of rates that
     * depend on the radius. The naive {@code rate(R) * timeSec} form re-derives
     * the entire pattern phase whenever R moves — amplified by the accumulated
     * world clock into target teleports and the "whole storm clumps into a
     * blob" collapse (the members cannot track a pattern spinning at
     * {@code dω/dR * dR/dt * timeSec} rad/s, so the servo's time-averaged pull
     * drags everything to the rotation center). The fix ships the FROZEN
     * growth law from the server (state packet, frozen at creation like the
     * seed): with {@code count(t) = count0 + g*t} and {@code R = sqrt(count)/8},
     * both phase integrals are CLOSED FORM and LINEAR in the grown radius —
     * bounded expressions every client evaluates identically:
     * <pre>
     *   rotPhase  = SPIN_K·128·(min(R,Rf) − R0)/g + (SPIN_K/Rf)·postSec
     *   convPhase = (rate_i/a_i)·(64·(min(R,Rf) − R0)/g + postSec/(2Rf))
     * </pre>
     * (postSec = the age past the growth window; {@code dt = 128·R·dR/g} is
     * what makes ∫dt/R linear). Every term is a constant rate times a bounded
     * quantity — a radius change can only advance the pattern by its
     * physically-correct amount, never teleport it.
     */
    private float stormCreationRadius;
    private float stormFinalRadius;
    private float stormGrowthPerSecond;
    private long stormCreatedAtGameTime;
    /** Raw current gameTime (the post-growth phase terms' age clock). */
    private long gameTimeNow;
    /** Shared rotation phase (radians) — refreshed every {@link #tickClock}. */
    private float stormRotPhase;
    /**
     * Shared conveyor phase scalar (per-member scale rate/a in the shader).
     * ACCEPTED DRIFT: unlike rotPhase this phase cannot be reduced mod
     * anything — the shader multiplies it by a per-member hash-derived
     * rate/aI, so any modulus would teleport every member's circulation
     * phase. The float magnitude grows post-growth at 1/(2Rf) per second;
     * after weeks of uptime the ULP exceeds the per-frame increment and the
     * conveyor quantizes into slow positional jitter (the same documented
     * acceptance as the shared clock's 2^21 wrap). Revisit only if a
     * long-running server reports it (rate-quantized buckets or fp64).
     */
    private float stormConvPhase;
    /** d(convPhase)/dt — drives the conveyor component of the target velocity. */
    private float stormConvRate;
    /** Instantaneous spin rate ω = ±SPIN_K/R_now (the servo's orbital term). */
    private float stormOmegaNow;
    private int pendingStormSpawns = 0;
    private boolean stormHeaderWritten = false;
    /** Emitter id force-expired on the NEXT update dispatch (-1 = none). */
    private int stormKillEmitId = -1;
    /** Server-owned 24-bit storm instance seed (member identity derivation). */
    private int stormSeed = 0;
    /** Total member population of the current storm (alive + dead). */
    private int stormCount = 0;
    /**
     * Generated-population threshold between the two spawn styles: indices
     * BELOW it existed at ACTIVATE time and spawn ANALYTICALLY (settled at
     * their home points — a joining client sees a formed storm); indices AT
     * OR ABOVE it arrived through GROWTH (center-packet deltas) and spawn on
     * the ring outside the visible envelope, flying to their storm positions.
     * Set to the packet count on ACTIVATE; growth only raises stormCount past
     * it. {@code scheduleSpawnRuns} splits its runs at this boundary.
     */
    private int stormAnalyticUpTo = 0;
    /** Server-decided dead members (this client's mirror of the truth). */
    private final BitSet stormDead = new BitSet();
    /** True while THIS client is the authority (runs the position readback). */
    private boolean stormAuthority = false;
    private double stormCorrectionHz = 5.0;
    /** Next member index to schedule for spawning (skips dead members). */
    private int stormSpawnCursor = 0;
    /** gameTime (ticks) of the last dispatched position snapshot. */
    private long lastSnapshotGameTime = Long.MIN_VALUE;
    /** Set when the compute phase dispatched a readback; consumed at fence poll. */
    private boolean stormPosPending = false;
    private long stormPosGameTime = 0;

    // ---- crosshair hit-query snapshot (storm asset; see the class javadoc) ---
    /**
     * Newest fence-signalled hit-query snapshot: the packed sort key of the
     * allay under the crosshair ({@link ParticleBuffers#HIT_MISS} = none) and
     * its HP as float bits. At most one frame old -- a click consumes it with
     * zero stalls; a stale camera for one frame is accepted (documented).
     */
    private int hitKeySnapshot = ParticleBuffers.HIT_MISS;
    private int hitHpBits = 0;
    /**
     * Non-consuming mirror of {@link #hitKeySnapshot} for the crosshair pick
     * ({code CMIParticleEngine.injectCrosshairPick}): the click path
     * consume-once resets {@code hitKeySnapshot}, and the crosshair must not
     * flicker for the frame between a click and the next fence poll. Refreshed
     * at the same fence poll, cleared wherever the click snapshot is
     * invalidated.
     */
    private int crosshairHitKey = ParticleBuffers.HIT_MISS;
    /** Storm member identity of the newest hit-query snapshot (-1 = non-storm). */
    private int hitMemberIdx = -1;

    // ---- synced player collection (storm repulsion + readback near-test) -----

    /** Scratch for per-frame synced player positions (xyz per player). */
    private final float[] playerScratch = new float[ParticleBuffers.MAX_STORM_PLAYERS * 3];
    private final double[] playerDistScratch = new double[ParticleBuffers.MAX_STORM_PLAYERS];
    /** Player count uploaded with the last collectSyncedPlayers (pass handoff). */
    private int lastPlayerCount = 0;

    // ---- public protocol API (engine delegates; handler is the caller) -------

    /**
     * Applies a server storm-state packet (ACTIVATE / UPDATE / DEACTIVATE /
     * STOP). Called on the render thread (payload handlers enqueueWork there),
     * which is the thread all storm state lives on — direct mutation is safe.
     * <p>
 * ACTIVATE (also join / dimension change / re-enter range): full restart
 * against the server definition — existing members are expired via the
 * kill path, the dead bitmap replaces the local mirror, and exactly the
 * alive members trickle in with seed-derived identity, so member counts
 * agree across clients and restarts by construction. UPDATE: anchor-only
 * retarget — identity, HP and deaths are preserved (springs retarget);
 * also carries the authority flag and correction rate. The radius and the
 * vortex angular velocity ride NO wire: both paths derive them here from
 * the synced count and seed. DEACTIVATE (out of range) and STOP (storm
 * over): local dispersal; the members compact away on the next update dispatch.
     */
    public void applyStormState(int action, boolean authority, double correctionHz,
            Vec3 anchor, int count, int seed,
            float creationRadius, float finalRadius, float growthPerSecond, long createdAt,
            byte[] deadBitmap) {
        switch (action) {
            case ClientboundStormStatePacket.ACTION_ACTIVATE -> {
                // full restart: expire whatever storm generation is live
                if (this.stormActive && this.stormEmitId >= 0)
                    this.stormKillEmitId = this.stormEmitId;
                // identities may be re-derived against a new seed: stale
                // identity -> slot entries must never serve member-keyed
                // combat origins
                if (engine.initialized())
                    engine.invalidateParticleIdentities();
                this.stormDead.clear();
                if (deadBitmap != null) {
                    byte[] bm = deadBitmap;
                    for (int i = 0; i < bm.length; i++) {
                        int b = bm[i] & 0xFF;
                        for (int bit = 0; bit < 8; bit++)
                            if ((b & (1 << bit)) != 0)
                                this.stormDead.set(i * 8 + bit);
                    }
                }
                this.stormActive = true;
                this.stormAnchor = anchor;
                // the immediate center packet on the server's activation path
                // seeds the interpolation; until then consumers read the raw
                // (block-quantized) anchor
                this.centerValid = false;
                this.center = anchor;
                this.countValid = false; // new generation: the count restarts from the packet
                this.stormSeed = seed & AllayStormSpec.SEED_MASK_CLIENT;
                // the FROZEN growth law rides the state packet (the phase
                // integrals' constants — identical on every client); the
                // radius derives from the synced count
                this.stormCreationRadius = creationRadius;
                this.stormFinalRadius = finalRadius;
                this.stormGrowthPerSecond = growthPerSecond;
                this.stormCreatedAtGameTime = createdAt;
                this.stormRadius = AllayStormData.vortexRadius(count);
                this.stormCount = count;
                this.stormAnalyticUpTo = count; // everything present at ACTIVATE spawns settled
                this.stormAuthority = authority;
                this.stormCorrectionHz = correctionHz;
                this.stormSpawnCursor = 0;
                this.pendingStormSpawns = Math.max(0, count - this.stormDead.cardinality());
                this.stormHeaderWritten = false;
                dropHitKeys(); // identities reshuffle
                clearWaveState(); // new generation: live waves are void (server aborts too)
            }
            case ClientboundStormStatePacket.ACTION_UPDATE -> {
                if (!this.stormActive)
                    return;
                this.stormAnchor = anchor;
                // the frozen law is generation-constant, but re-store it anyway
                // (UPDATE is also the config-change propagation path)
                this.stormCreationRadius = creationRadius;
                this.stormFinalRadius = finalRadius;
                this.stormGrowthPerSecond = growthPerSecond;
                this.stormCreatedAtGameTime = createdAt;
                // the radius derives from the current generated count; the
                // per-frame refresh keeps tracking the interpolated count
                this.stormRadius = AllayStormData.vortexRadius(this.stormCount);
                this.stormAuthority = authority;
                this.stormCorrectionHz = correctionHz;
                if (this.stormHeaderWritten && this.stormEmitId >= 0) {
                    // re-pack header so G(t)/springs retarget on the next dispatch
                    float[] h = AllayStormSpec.packedHeader(this.stormRadius, this.center);
                    h[16 * 4 + 0] = engine.ensurePoofEmitter();
                    engine.gpu().setEmitterHeader(this.stormEmitId, h);
                }
            }
            case ClientboundStormStatePacket.ACTION_DEACTIVATE, ClientboundStormStatePacket.ACTION_STOP -> {
                // local dispersal; server state is not this side's business
                if (this.stormActive && this.stormEmitId >= 0)
                    this.stormKillEmitId = this.stormEmitId;
                if (engine.initialized())
                    engine.invalidateParticleIdentities();
                this.stormActive = false;
                this.stormAuthority = false;
                this.pendingStormSpawns = 0;
                this.stormHeaderWritten = false;
                this.stormDead.clear();
                this.centerValid = false;
                this.countValid = false;
                this.stormRotPhase = 0f;
                this.stormConvPhase = 0f;
                this.stormConvRate = 0f;
                this.stormOmegaNow = 0f;
                dropHitKeys();
                clearWaveState(); // members disperse; no wave exists to steer them
            }
            default -> {
            }
        }
    }

    /**
     * Applies one relayed position snapshot ({@code entries} stride
     * {@code 8: memberIdx, pad, posRelToAnchor.xyz, vel.xyz}) by writing soft
     * correction slots — positions are never written directly (no teleports);
     * the update pass eases members toward the extrapolated targets.
     */
    public void applyCorrections(float[] entries, long gameTime) {
        if (!this.stormActive || entries == null || entries.length < 8)
            return;
        float arrival = clockSeconds(gameTime);
        var gpu = engine.gpu(); // captured before the loop (loop discipline)
        int cap = gpu.capacity();
        for (int o = 0; o + 7 < entries.length; o += 8) {
            int memberIdx = (int) entries[o];
            if (memberIdx < 0 || memberIdx >= cap)
                continue; // no correction slot exists for this identity
            float mx = (float) this.center.x + entries[o + 2];
            float my = (float) this.center.y + entries[o + 3];
            float mz = (float) this.center.z + entries[o + 4];
            gpu.writeCorrection(memberIdx, mx, my, mz, arrival,
                    entries[o + 5], entries[o + 6], entries[o + 7], 1.0f);
        }
    }

    /**
     * Applies a hit correction from a server DAMAGE broadcast: every client
     * (authority included) eases the struck member to the attacker's exact
     * hit spot over a short strong window, so hurt-flash / corpse / poof all
     * play at the same place without any periodic snapshot needing to catch up.
     */
    public void applyHitCorrection(int memberIdx, Vec3 relToAnchor, long gameTime) {
        if (!this.stormActive || memberIdx < 0 || memberIdx >= engine.gpu().capacity())
            return;
        float arrival = clockSeconds(gameTime);
        engine.gpu().writeCorrection(memberIdx,
                (float) (this.center.x + relToAnchor.x),
                (float) (this.center.y + relToAnchor.y),
                (float) (this.center.z + relToAnchor.z),
                arrival, 0f, 0f, 0f, 10.0f);
    }

    /**
     * Applies a server DAMAGE broadcast for one storm member: the damage/
     * knockback entry goes through the GPU damage queue (matched by MEMBER
     * identity, immune to pool recompaction; the server's death bit forces
     * the corpse unconditionally), the death lands in the spawn-side dead
     * set (a member killed before it spawned on THIS client must never
     * materialize later — see the comment in the body), and a strong short
     * hit-correction slot eases the member to the attacker's exact hit spot.
     * Every active client — the attacker included — runs this identical path.
     */
    public void applyStormDamage(int memberIdx, float damage, float kbX, float kbZ,
            float light, boolean died, Vec3 relToAnchor, long gameTime) {
        if (!this.stormActive)
            return;
        if (died && memberIdx >= 0) {
            // The damage queue lives ONE frame — a member killed during the
            // trickle-in / growth window may not exist in the pool yet, so the
            // entry scans past it and dies unheard. Without this mirror the
            // spawn cursor would later materialize that index as a full-HP
            // member the SERVER already counts dead (reports against it are
            // rejected forever — an unkillable ghost). scheduleSpawnRuns'
            // skip loop re-reads stormDead every frame; the bit is idempotent.
            this.stormDead.set(memberIdx);
        }
        engine.enqueueDamageEntry(memberIdx, damage, kbX, kbZ, light,
                ParticleBuffers.DAMAGE_FLAG_MEMBER | (died ? ParticleBuffers.DAMAGE_FLAG_DIED : 0));
        applyHitCorrection(memberIdx, relToAnchor, gameTime);
    }

    /** Full storm teardown (level clear/shutdown): forget the emitter id too. */
    public void resetStormState() {
        this.stormActive = false;
        this.pendingStormSpawns = 0;
        this.stormHeaderWritten = false;
        this.stormEmitId = -1;
        this.stormKillEmitId = -1;
        this.stormDead.clear();
        this.stormAuthority = false;
        this.stormSpawnCursor = 0;
        this.stormCount = 0;
        this.stormAnalyticUpTo = 0;
        this.stormPosPending = false;
        this.lastSnapshotGameTime = Long.MIN_VALUE;
        this.centerValid = false;
        this.center = Vec3.ZERO;
        this.countValid = false;
        this.countInterp = 0f;
        this.stormRotPhase = 0f;
        this.stormConvPhase = 0f;
        this.stormConvRate = 0f;
        this.stormOmegaNow = 0f;
        clearWaveState();
    }

    // ---- runCompute skeleton hooks (single reads, never inside loops) --------

    /** True while a storm generation is live (grid pass + spawn scheduling). */
    public boolean active() {
        return this.stormActive;
    }

    /** True while a storm emitter kill is queued for the next update dispatch. */
    public boolean killPending() {
        return this.stormKillEmitId >= 0;
    }

    /**
     * The grid pass must run when storm steering can execute: an active storm,
     * or a kill request still standing (whose targets are still in the read
     * pool until the next update compacts them away).
     */
    public boolean needsGridPass() {
        return this.stormActive || this.stormKillEmitId >= 0;
    }

    /** {@code uKillEmit} uniform value: the emitter id to expire, -1 = none (~0u). */
    public int killEmitId() {
        return this.stormKillEmitId;
    }

    /**
     * Shared typhoon phase scalars for the {@code uStormRotPhase} /
     * {@code uStormConvPhase} / {@code uStormConvRate} / {@code uStormOmegaNow}
     * uniforms — the growth-law integrals evaluated once per frame on the CPU
     * (see the field doc). Zero while no storm runs.
     */
    public float rotPhase() {
        return this.stormRotPhase;
    }

    public float convPhase() {
        return this.stormConvPhase;
    }

    public float convRate() {
        return this.stormConvRate;
    }

    public float omegaNow() {
        return this.stormOmegaNow;
    }

    /** 24-bit instance seed for the {@code uStormSeed} emit uniform. */
    public float seed() {
        return this.stormSeed;
    }

    /**
     * Retires the queued kill at the update phase's SUCCESS TAIL (after the
     * pool swap): the just-swapped pool is now the authoritative read source,
     * so every kill target provably compacted away. Until then the pending id
     * keeps the grid pass armed and refires the kill.
     * <p>
     * Also drops the member identity map. update.comp returns on the kill
     * BEFORE its per-frame map rebuild (the early return precedes the memberMap
     * write), so the killed generation's entries persist pointing at pool slots
     * the next compaction hands to other particles — and the rebuild only ever
     * writes PRESENT members, so nothing else removes them. A member-keyed
     * combat spawn (tracking bursts survive a stop) would then resolve a dead
     * member onto a recycled slot. Clearing here is race-free: this frame's
     * emit pass already consumed the map, and the next update rebuilds it
     * before emit reads it again.
     */
    public void retireKill() {
        boolean hadMembers = this.stormActive || this.stormKillEmitId >= 0;
        this.stormKillEmitId = -1;
        if (hadMembers) this.engine.gpu().clearMemberMap();
    }

    // ---- crosshair hit-query snapshot plumbing -------------------------------

    /**
     * Stores the fence-fresh hit-query readback (engine's
     * {@code pollCounterSnapshot}): packed key, winner HP bits, storm member
     * identity ({@code HIT_MISS} = non-storm MODEL winner).
     */
    private int hitToken;
    public int hitToken() { return this.hitToken; }
    public void onHitReadback(int key, int hpBits, int memberIdx, int token) {
        this.hitToken = token;
        this.hitKeySnapshot = key;
        this.crosshairHitKey = key;
        this.hitHpBits = hpBits;
        this.hitMemberIdx = memberIdx == ParticleBuffers.HIT_MISS ? -1 : memberIdx;
    }

    /** Full hit-snapshot drop (pool reset): keys + HP + member identity. */
    public void dropHitSnapshots() {
        this.hitToken=0;
        this.hitKeySnapshot = ParticleBuffers.HIT_MISS;
        this.crosshairHitKey = ParticleBuffers.HIT_MISS;
        this.hitHpBits = 0;
        this.hitMemberIdx = -1;
    }

    /**
     * Keys-only drop (storm kill frame / ACTIVATE / STOP): identities
     * reshuffle, but the HP and member slots are refreshed by the next fence
     * poll regardless.
     */
    public void dropHitKeys() {
        this.hitToken=0;
        this.hitKeySnapshot = ParticleBuffers.HIT_MISS;
        this.crosshairHitKey = ParticleBuffers.HIT_MISS;
    }

    /** Packed key of the newest hit snapshot (consume-once click path). */
    public int hitKeySnapshot() {
        return this.hitKeySnapshot;
    }

    /**
     * Consume-once click path: clears ONLY {@link #hitKeySnapshot} — the
     * crosshair mirror {@link #crosshairHitKey} stays so the crosshair does
     * not flicker in the frame between a click and the next fence poll.
     */
    public void consumeHitSnapshot() {
        this.hitKeySnapshot = ParticleBuffers.HIT_MISS;
    }

    /** Non-consuming mirror for the crosshair pick injection. */
    public int crosshairHitKey() {
        return this.crosshairHitKey;
    }

    /** Winner HP (float bits decoded) for the hurt/death sound choice. */
    public float hitHp() {
        return Float.intBitsToFloat(this.hitHpBits);
    }

    /** Storm member identity of the newest snapshot (-1 = non-storm winner). */
    public int hitMemberIdx() {
        return this.hitMemberIdx;
    }

    /**
     * Storm center (world space) — the INTERPOLATED chased center. Every
     * anchor-relative wire coordinate is produced and consumed against this
     * reference; the raw packet anchor stays internal (fallback until the
     * first center snapshot lands).
     */
    public Vec3 center() {
        return this.center;
    }

    // ---- synced player collection ---------------------------------------------

    /**
     * Collects the nearest {@link ParticleBuffers#MAX_STORM_PLAYERS} synced
     * players (vanilla entity sync positions) by distance to the storm anchor
     * into {@link #playerScratch}. Every client feeds the SAME player set
     * (others lagged by interpolation), so the repulsion field is
     * near-identical across clients with zero extra packets.
     */
    public int collectSyncedPlayers() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            this.lastPlayerCount = 0;
            return 0;
        }
        int count = 0;
        double[] dist = this.playerDistScratch;
        for (var p : mc.level.players()) {
            double dx = p.getX() - this.center.x;
            double dy = p.getY() - this.center.y;
            double dz = p.getZ() - this.center.z;
            double d2 = dx * dx + dy * dy + dz * dz;
            int max = ParticleBuffers.MAX_STORM_PLAYERS;
            if (count >= max) {
                // worse than the farthest kept player: skip
                if (d2 >= dist[max - 1])
                    continue;
                count = max - 1; // overwrite the farthest, then re-insert
            }
            int i = count++;
            while (i > 0 && dist[i - 1] > d2) {
                dist[i] = dist[i - 1];
                this.playerScratch[i * 3] = this.playerScratch[(i - 1) * 3];
                this.playerScratch[i * 3 + 1] = this.playerScratch[(i - 1) * 3 + 1];
                this.playerScratch[i * 3 + 2] = this.playerScratch[(i - 1) * 3 + 2];
                i--;
            }
            dist[i] = d2;
            this.playerScratch[i * 3] = (float) p.getX();
            this.playerScratch[i * 3 + 1] = (float) p.getY();
            this.playerScratch[i * 3 + 2] = (float) p.getZ();
        }
        this.lastPlayerCount = count;
        return count;
    }

    /** Player positions (xyz) from the last {@link #collectSyncedPlayers}. */
    public float[] playerScratch() {
        return this.playerScratch;
    }

    // ---- authority position readback -------------------------------------------

    /**
     * 6c. Authority-only: dispatches {@code stormpos.comp} over the freshly
     * written pool at the configured snapshot rate, compacting storm members
     * within melee reach of any synced player into the staging buffer. The
     * readback happens at the next fence poll ({@link #acceptPositionSnapshot})
     * and ships as a {@link ServerboundStormPositionsPacket}. Skipped on
     * non-authority clients and while no storm runs; costs the distance tests
     * alone in steady state. {@code upper} is the engine's dispatch bound
     * over its pool census ({@code aliveKnown + spawnDelta + 64}) — pool
     * bookkeeping stays on the engine side of the seam.
     */
    public void dispatchStormPosReadback(int slot, int cap, int upper) {
        if (this.stormPosPending || !this.stormActive || !this.stormAuthority || this.stormCorrectionHz <= 0)
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
            return;
        long now = mc.level.getGameTime();
        if (this.lastSnapshotGameTime != Long.MIN_VALUE
                && now - this.lastSnapshotGameTime < 20.0 / this.stormCorrectionHz)
            return;
        if (upper <= 0 || this.lastPlayerCount <= 0)
            return;
        this.lastSnapshotGameTime = now;
        int sp = engine.programs().stormPos();
        if (sp == 0)
            return;
        engine.gpu().clearStormPosCount();
        org.lwjgl.opengl.GL20.glUseProgram(sp);
        engine.gpu().bindParticleWrite(ParticleBuffers.PARTICLE_BB_WRITE);
        engine.gpu().bindParticleRead(ParticleBuffers.PARTICLE_BB_READ);engine.gpu().bindMemberMap();
        engine.gpu().bindCounter(3, slot);
        engine.gpu().bindEmitters(5);
        engine.gpu().bindPlayers();
        engine.gpu().bindStormPos();
        engine.gpu().bindPopulationRead(sp,true,engine.currentGeneration()+1);
        Vec3 a = this.center;
        CMIParticleEngine.setFloatUniform(sp, "uAnchor", (float) a.x, (float) a.y, (float) a.z);
        CMIParticleEngine.setIntUniform(sp, "uPlayerCount", this.lastPlayerCount);
        engine.gpu().dispatch(ParticleBuffers.STORM_DISPATCH);
        org.lwjgl.opengl.GL42.glMemoryBarrier(
                org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BARRIER_BIT | org.lwjgl.opengl.GL42.GL_ATOMIC_COUNTER_BARRIER_BIT);
        this.stormPosPending = true;
        this.stormPosGameTime = now;
    }

    /**
     * Consumes a pending storm position readback at the fence poll (success
     * path): reads the staging buffer and ships it to the server.
     */
    public boolean positionSnapshotPending() { return this.stormPosPending; }
    public long positionSnapshotTime() { return this.stormPosGameTime; }
    public boolean waveSnapshotPending() { return this.waveContactPending; }

    public void acceptPositionSnapshot(long gameTime, float[] entries) {
        if (entries != null && entries.length >= 8)
            PacketDistributor.sendToServer(new ServerboundStormPositionsPacket(gameTime, entries));
    }

    /** Drops a pending readback (fence WAIT_FAILED; a fresh one is scheduled anyway). */
    public void dropPositionSnapshot() {
        this.stormPosPending = false;
    }

    // ---- emit scheduling ---------------------------------------------------------

    /**
     * Storm trickle-in: at most ~60 frames of member batches regardless of
     * population (the swarm assembles over ~1s instead of popping in), SPLIT
     * around the server-reported dead members so each command's member index
     * base + inner (GPU-side) reproduces the server's member numbering
     * exactly — identity stays cross-client. Runs every active frame, not
     * just while trickle-spawning: the first members of frame one draw in
     * this very tick (so the allay atlas must be live BEFORE emission), and a
     * stray burst evicting a storm volume via LRU must heal instead of
     * silently stripping REST collision. The swarm pins a dedicated 2x2 bake
     * grid (+-48 block footprint); routing it through ensureEmitterRuntime
     * would squat an extra off-grid centred slot the swarm never needs.
     */
    public void scheduleSpawnRuns(EmitSchedule s) {
        if (this.stormActive) {
            engine.allayAtlas().ensureLoaded();
            engine.collisionBake().ensureQuadrants(this.center);
        }
        if (this.stormActive && this.pendingStormSpawns > 0) {
            int id = engine.ensureEmitter(AllayStormSpec.SPEC);
            if (id >= 0) {
                if (!this.stormHeaderWritten) {
                    // Anchor + storm parameters ride the per-id header slots 18/19
                    // (specs stay position-free; this spec dedupes to its own id).
                    // AllayStormSpec owns the slot layout; only the death-chain
                    // emitter id is patched here (MODEL particles poof on expiry).
                    // refreshCenterHeader rewrites the center every frame after
                    // this, so the initial value is just the seed.
                    float[] h = AllayStormSpec.packedHeader(this.stormRadius, this.center);
                    h[16 * 4 + 0] = engine.ensurePoofEmitter();
                    engine.gpu().setEmitterHeader(id, h); // uploaded below this frame
                    this.stormEmitId = id;
                    this.stormHeaderWritten = true;
                }
                // Drain in ~60 frames regardless of population (see javadoc).
                // The storm spec is FIXED (always MODEL) — resolve its
                // translucency once, outside the scheduling loop.
                boolean translucent = engine.isTranslucent(AllayStormSpec.SPEC);
                int budget = Math.min(this.pendingStormSpawns,
                        Math.max(ParticleBuffers.MAX_EMIT_COMMANDS,
                                (this.pendingStormSpawns + 59) / 60));
                int scheduled = 0;
                while (scheduled < budget && s.entryCount < ParticleBuffers.MAX_EMIT_COMMANDS
                        && this.stormSpawnCursor < this.stormCount) {
                    while (this.stormSpawnCursor < this.stormCount && this.stormDead.get(this.stormSpawnCursor))
                        this.stormSpawnCursor++;
                    if (this.stormSpawnCursor >= this.stormCount)
                        break;
                    // Runs must not cross the analytic/ring style boundary:
                    // indices BELOW stormAnalyticUpTo existed at ACTIVATE and
                    // spawn settled; indices AT OR ABOVE it arrived through
                    // growth and spawn on the ring outside the visible
                    // envelope, flying to their storm positions.
                    boolean ring = this.stormSpawnCursor >= this.stormAnalyticUpTo;
                    int styleLimit = ring ? this.stormCount : Math.min(this.stormCount, this.stormAnalyticUpTo);
                    int runStart = this.stormSpawnCursor;
                    while (this.stormSpawnCursor < styleLimit && !this.stormDead.get(this.stormSpawnCursor)
                            && scheduled + (this.stormSpawnCursor - runStart) < budget)
                        this.stormSpawnCursor++;
                    int runLen = this.stormSpawnCursor - runStart;
                    if (runLen <= 0)
                        break;
                    s.emitIds[s.entryCount] = id;
                    s.emitCounts[s.entryCount] = runLen;
                    s.emitOrigins[s.entryCount] = this.center;
                    s.emitTranslucent[s.entryCount] = translucent;
                    s.emitOriginRef[s.entryCount] = 0f;
                    s.emitLight[s.entryCount] = 0f;
                    s.emitMemberBase[s.entryCount] = runStart;
                    s.emitMemberKey[s.entryCount] = 0; // c.z unused by the storm spawn style
                    s.emitRingSpawn[s.entryCount] = ring; // c.w: ring-spawn flag (growth members)
                    s.totalSpawn += runLen;
                    scheduled += runLen;
                    s.entryCount++;
                }
                if (this.stormSpawnCursor >= this.stormCount)
                    this.pendingStormSpawns = 0;
                else
                    this.pendingStormSpawns -= scheduled;
            }
        }
    }

    /**
     * Per-frame (compute phase, before the emitter upload): re-packs the storm
     * emitter header with the INTERPOLATED center so the servo targets ride
     * the continuous chase — snapshots land at 1 Hz, this renders the motion
     * at frame rate. Marks the header dirty for this frame's upload (~1.25 KB,
     * noise). Cheap no-op on every non-storm frame.
     */
    public void refreshCenterHeader() {
        if (!this.stormActive || !this.stormHeaderWritten || this.stormEmitId < 0)
            return;
        float[] h = AllayStormSpec.packedHeader(this.stormRadius, this.center);
        h[16 * 4 + 0] = engine.ensurePoofEmitter();
        engine.gpu().setEmitterHeader(this.stormEmitId, h);
    }

    // ---- dive waves (client mirror of the server's wave events) ---------------
    // The wave state is a thin EVENT STAGING layer: everything that defines an
    // attack (seed, fraction, corridor, schedule, target id) arrives in one
    // packet and rides into the GPU as uniforms; per-member engagement is
    // stateless inside the shaders (hash membership + roll-staggered launch +
    // position-derived contact), so there is no per-member client state to
    // sync or heal. The only mutable client-side state is the slot table below
    // (<= 4 live waves), each wave's reported-contact dedupe set, and the
    // shaft's pinned bake anchor with its re-pin hysteresis.

    /** Concurrent wave ceiling (mirrors AllayStormManager.MAX_WAVES). */
    public static final int MAX_WAVES = 4;
    /** Corridor waypoints per wave shipped in the packet (GPU uniform budget). */
    public static final int MAX_WAVE_PATH = 6;

    private static final class WaveClient {
        boolean alive;int slotIdx=-1,waveId,targetEntityId;long epoch;
        com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormWavePacket packet;
        final float[] bounds=new float[6];boolean windowReady;
        final IntOpenHashSet reported=new IntOpenHashSet();int ringCommittedMask,ringScheduledMask,columnSoundMask;boolean startSound,lockSound;
    }
    private final WaveClient[] waveSlots=new WaveClient[MAX_WAVES];
    private final com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormWavePacket[] starfallSnapshots=new com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormWavePacket[MAX_WAVES];
    private final StarfallGpuBuffers starfallGpu=new StarfallGpuBuffers();
    private boolean starfallDirty=true,waveContactPending;private String lastHelixAbort="none";
    private final float[] waveUniform=new float[16],waveTargetUniform=new float[16],wavePathUniform=new float[96],waveTierUniform=new float[4];
    public float[] waveUniform(){return waveUniform;}public float[] waveTargetUniform(){return waveTargetUniform;}public float[] wavePathUniform(){return wavePathUniform;}public float[] waveTierUniform(){return waveTierUniform;}
    public StarfallGpuBuffers starfallGpu(){return starfallGpu;}
    public boolean anyWaveActive(){for(var w:waveSlots)if(w!=null&&w.alive&&!w.packet.abort())return true;return false;}
    private boolean locallyRelevant(WaveClient w){
        var me=Minecraft.getInstance().player;if(me==null||w==null||!w.alive||w.packet.abort())return false;
        var p=w.packet;if(p.helixAttack()){var h=p.helix();if(!p.locked()||com.iridium126.createmanaindustry.content.allaystorm.AllayHelix.phase(timeSec,p.assembleSec(),p.lockSec(),true,h)!=4)return false;return me.getX()>=w.bounds[0]&&me.getX()<=w.bounds[3]&&me.getY()>=w.bounds[1]&&me.getY()<=w.bounds[4]&&me.getZ()>=w.bounds[2]&&me.getZ()<=w.bounds[5];}
        if(p.starfall())return w.targetEntityId==me.getId();
        if(!p.locked())return false;double dx=me.getX()-p.centerX(),dz=me.getZ()-p.centerZ(),r=p.crownRadius()+4;
        return dx*dx+dz*dz<=r*r&&me.getY()>=p.centerY()-3&&me.getY()<=p.centerY()+p.crownRadius()+4;
    }
    public boolean anyWaveTargetingLocalPlayer(){for(var w:waveSlots)if(locallyRelevant(w))return true;return false;}
    public void applyWave(com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormWavePacket p){
        if(!stormActive||p.epoch()!=com.iridium126.createmanaindustry.content.allaystorm.AllayStarfall.epoch(stormCreatedAtGameTime,stormSeed))return;
        WaveClient w=findWaveById(p.waveId());
        if(p.abort()){
            if(w==null)return;var old=w.packet;
            float now=old.startSec()+com.iridium126.createmanaindustry.content.allaystorm.AllayStarfall.elapsed(timeSec,old.startSec());
            w.packet=old.copy(3,6,now,now+.25f,old.members()).reason(p.fallbackReason());
            if(old.helixAttack())lastHelixAbort=switch(p.fallbackReason()){case 5->"blocked";case 6->"target-lost";case 7->"aim-timeout";case 8->"unsafe-route";default->"aborted";};
            starfallDirty=true;return;
        }
        if(w==null){if(p.event()!=0)return;for(int slot=0;slot<4;slot++)if(waveSlots[slot]==null||!waveSlots[slot].alive){w=new WaveClient();w.slotIdx=slot;waveSlots[slot]=w;break;}if(w==null)return;}
        if(w.packet!=null&&w.packet.locked()&&p.event()==1)return;
        int[] members=p.members().length==0&&w.packet!=null?w.packet.members():p.members();
        w.packet=p.copy(p.locked()?2:p.event(),p.phase(),p.lockSec(),p.diveUntilSec(),members);
        if(p.helixAttack()){java.util.Arrays.fill(w.bounds,0,3,Float.POSITIVE_INFINITY);java.util.Arrays.fill(w.bounds,3,6,Float.NEGATIVE_INFINITY);float pad=p.helix().radius()+4;for(int i=0;i<48;i++)for(int axis=0;axis<3;axis++){float v=p.helix().samples()[i*4+axis];w.bounds[axis]=Math.min(w.bounds[axis],v-pad);w.bounds[axis+3]=Math.max(w.bounds[axis+3],v+pad);}}
        w.alive=true;w.waveId=p.waveId();w.epoch=p.epoch();w.targetEntityId=p.targetEntityId();starfallDirty=true;
    }
    private WaveClient findWaveById(int id){for(var w:waveSlots)if(w!=null&&w.alive&&w.waveId==id)return w;return null;}
    public void clearWaveState(){lastHelixAbort="none";java.util.Arrays.fill(waveSlots,null);java.util.Arrays.fill(starfallSnapshots,null);waveContactPending=false;starfallDirty=true;starfallGpu.invalidate();}
    public void refreshWaves(){
        java.util.Arrays.fill(waveUniform,0);java.util.Arrays.fill(waveTargetUniform,0);java.util.Arrays.fill(waveTierUniform,0);
        int soundBudget=2;
        for(int slot=0;slot<4;slot++){
            var w=waveSlots[slot];if(w==null||!w.alive){starfallSnapshots[slot]=null;continue;}
            if(com.iridium126.createmanaindustry.content.allaystorm.AllayStarfall.elapsed(timeSec,w.packet.diveUntilSec())>0){w.alive=false;starfallDirty=true;starfallSnapshots[slot]=null;continue;}
            w.ringScheduledMask=0;var p=w.packet;starfallSnapshots[slot]=p;
            var mc=Minecraft.getInstance();
            if(!p.abort()&&mc.level!=null){
                float startAge=com.iridium126.createmanaindustry.content.allaystorm.AllayStarfall.elapsed(timeSec,p.startSec());
                if(!w.startSound&&startAge>=0){w.startSound=true;if(startAge<.5&&soundBudget>0){soundBudget--;mc.level.playLocalSound(p.crownX(),p.crownY(),p.crownZ(),net.minecraft.sounds.SoundEvents.ALLAY_AMBIENT_WITH_ITEM,net.minecraft.sounds.SoundSource.HOSTILE,.4f,.7f,false);}}
                if(p.event()==2){float lockAge=com.iridium126.createmanaindustry.content.allaystorm.AllayStarfall.elapsed(timeSec,p.lockSec());if(!w.lockSound){w.lockSound=true;if(lockAge<.5&&soundBudget>0){soundBudget--;mc.level.playLocalSound(!p.starfall()?p.centerX():p.landings()[0],!p.starfall()?p.centerY()+p.crownRadius():p.landings()[1],!p.starfall()?p.centerZ():p.landings()[2],net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME,net.minecraft.sounds.SoundSource.HOSTILE,.45f,1.1f,false);}}
                    for(int c=0;c<(p.helixAttack()?1:p.dome()?6:p.columns());c++){float phaseStart=p.helixAttack()?p.helix().thrust():p.dome()?com.iridium126.createmanaindustry.content.allaystorm.AllayDome.warningStart(p.lockSec(),p.crownRadius(),c):p.lockSec()+1+c*.35f;float age=com.iridium126.createmanaindustry.content.allaystorm.AllayStarfall.elapsed(timeSec,phaseStart);int bit=1<<c;if((w.columnSoundMask&bit)==0&&age>=0){w.columnSoundMask|=bit;if(age<.2&&soundBudget>0){soundBudget--;int k=c*4;mc.level.playLocalSound(p.dome()?p.centerX():p.landings()[k],!p.starfall()?p.centerY()+6:p.landings()[k+1]+6,p.dome()?p.centerZ():p.landings()[k+2],net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_SWEEP,net.minecraft.sounds.SoundSource.HOSTILE,.3f,1+c*.01f,false);}}}
                }
            }
int b=slot*4;waveUniform[b]=p.waveSeed();waveUniform[b+2]=p.startSec();waveUniform[b+3]=p.diveUntilSec();waveTargetUniform[b+3]=p.waveId();waveTierUniform[slot]=p.swordTier();
            if(p.helixAttack()){updateHelixWindow(w);}
            else if(p.dome()){engine.collisionBake().ensureColumn(p.centerX(),p.centerY()-.5,p.centerZ());}
            else if(p.event()==2){engine.collisionBake().ensureColumn(p.landings()[0],p.landings()[1]-.5,p.landings()[2]);}
            else {int end=p.path().length-3;engine.collisionBake().ensureColumn(p.path()[end],p.path()[end+1]-10,p.path()[end+2]);}
        }
        if(starfallDirty){starfallGpu.rebuild(starfallSnapshots);starfallDirty=false;}for(var w:waveSlots)if(w!=null&&w.alive&&w.packet.helixAttack())starfallGpu.helixReady(w.slotIdx,w.windowReady);
    }

    private final float[] helixWindowPoint=new float[3],helixAheadPoint=new float[3];
    private void updateHelixWindow(WaveClient wave){
        var p=wave.packet;var h=p.helix();float now=p.startSec()+com.iridium126.createmanaindustry.content.allaystorm.AllayStarfall.elapsed(timeSec,p.startSec());
        float arc=com.iridium126.createmanaindustry.content.allaystorm.AllayHelix.progress(h,now)-h.length()*.5f;
        com.iridium126.createmanaindustry.content.allaystorm.AllayHelix.point(h.controls(),com.iridium126.createmanaindustry.content.allaystorm.AllayHelix.parameter(h,arc),helixWindowPoint,0);
        if(!p.locked()){helixWindowPoint[0]=p.centerX()-(float)Math.cos(p.orientation())*h.length()*.5f;helixWindowPoint[1]=p.centerY();helixWindowPoint[2]=p.centerZ()-(float)Math.sin(p.orientation())*h.length()*.5f;}
        wave.windowReady=engine.collisionBake().helixWindow(helixWindowPoint[0],helixWindowPoint[1],helixWindowPoint[2],false);
        if(p.locked()&&now>=h.thrust()&&now<h.finish()){
            float ahead=com.iridium126.createmanaindustry.content.allaystorm.AllayHelix.progress(h,now+.75f)-h.length()*.5f;
            com.iridium126.createmanaindustry.content.allaystorm.AllayHelix.point(h.controls(),com.iridium126.createmanaindustry.content.allaystorm.AllayHelix.parameter(h,ahead),helixAheadPoint,0);
            engine.collisionBake().helixWindow(helixAheadPoint[0],helixAheadPoint[1],helixAheadPoint[2],true);
        }
    }

    private static final com.iridium126.createmanaindustry.client.particles.emitter.EmitterSpec STARFALL_RING_SPEC=
        com.iridium126.createmanaindustry.client.particles.emitter.EmitterSpec.builder().speed(0,0).life(180,180).sizeOverLife(.27,.27,1).gravity(0,0,0).drag(0).glow(1).color(.3f,.8f,1,1).type(com.iridium126.createmanaindustry.client.particles.emitter.ParticleTypes.standard(com.iridium126.createmanaindustry.client.particles.emitter.ParticleTypes.Material.ADDITIVE)).build();
    public void scheduleRings(EmitSchedule schedule){
        int emitter=-1;for(var w:waveSlots){if(w==null||!w.alive||w.ringCommittedMask==(w.packet.columns()==32?-1:(1<<w.packet.columns())-1)||w.packet.event()!=2||!w.packet.starfall())continue;
            if(emitter<0){emitter=engine.ensureEmitter(STARFALL_RING_SPEC);if(emitter<0)return;float[] h=STARFALL_RING_SPEC.packed().clone();h[17*4+1]=7;engine.gpu().setEmitterHeader(emitter,h);}
            for(int c=0;c<w.packet.columns()&&schedule.entryCount<schedule.emitIds.length;c++){
                if((w.ringCommittedMask&(1<<c))!=0)continue;w.ringScheduledMask|=1<<c;
                int i=schedule.entryCount++;schedule.emitIds[i]=emitter;schedule.emitCounts[i]=128;schedule.emitOrigins[i]=Vec3.ZERO;schedule.emitTranslucent[i]=false;
                schedule.emitOriginRef[i]=0;schedule.emitLight[i]=w.waveId;schedule.emitMemberBase[i]=w.slotIdx;schedule.emitMemberKey[i]=c;schedule.emitRingSpawn[i]=true;schedule.totalSpawn+=128;
            }
        }
    }
    public String starfallStatus(){
        int stars=0,domes=0,helices=0,helixMembers=0,starMembers=0,domeMembers=0,columns=0;StringBuilder helixStages=new StringBuilder();StringBuilder stages=new StringBuilder(),fallbacks=new StringBuilder();
        for(var w:waveSlots)if(w!=null&&w.alive&&!w.packet.abort()){
            var p=w.packet;
            if(p.helixAttack()){helices++;helixMembers+=p.members().length;var h=p.helix();float now=p.startSec()+com.iridium126.createmanaindustry.content.allaystorm.AllayStarfall.elapsed(timeSec,p.startSec());
                String stage=now<p.assembleSec()?"assemble":!p.locked()?(now<h.charge()?"approach":"charge"):now<h.thrust()?"locked":now<h.finish()?"thrust":"return";
                helixStages.append('#').append(w.waveId).append(':').append(stage).append(", arc=").append(h.arcLength()).append(", advanceSpeed=").append(com.iridium126.createmanaindustry.content.allaystorm.AllayHelix.speedAt(h,now)).append(", memberCap=").append(stage.equals("thrust")?h.maxSpeed():6).append(", window=").append(w.windowReady?"ready":"pending").append(' ');
            }else if(p.dome()){domes++;domeMembers+=p.members().length;int g=com.iridium126.createmanaindustry.content.allaystorm.AllayDome.closingPetal(timeSec,p.lockSec(),p.crownRadius());
                float age=com.iridium126.createmanaindustry.content.allaystorm.AllayStarfall.elapsed(timeSec,p.lockSec());
                int petal=(int)Math.floor(age/(1.5f+com.iridium126.createmanaindustry.content.allaystorm.AllayDome.closeSeconds(p.crownRadius())));
                String stage=!p.locked()?(com.iridium126.createmanaindustry.content.allaystorm.AllayStarfall.elapsed(timeSec,p.assembleSec())<0?"assemble":"approach"):petal>=6?"return":g<0?"warning-"+(petal+1):"closing-"+(g+1);
                stages.append('#').append(w.waveId).append(':').append(stage).append(' ');
            }else{stars++;starMembers+=p.members().length;columns+=p.columns();}
            if(p.fallbackReason()!=0)fallbacks.append('#').append(w.waveId).append(':').append(p.fallbackReason()==1?"too-few":p.fallbackReason()==2?"terrain":p.fallbackReason()==3?"helix-arms":p.fallbackReason()==4?"helix-route":"helix-blocked").append(' ');
        }
        return "Starfall: waves="+stars+", columns="+columns+", members="+starMembers+"; Helix: waves="+helices+", members="+helixMembers+", stages="+helixStages+", lastAbort="+lastHelixAbort+"; Dome: waves="+domes+", members="+domeMembers+", phases="+stages+", fallback="+fallbacks+", domainBytes="+starfallGpu.bytes()+", uploadedBytes="+starfallGpu.uploadedBytes();
    }
    public int carrierCount(){int count=0;for(var w:waveSlots)if(w!=null&&w.alive&&!w.packet.abort())count+=w.packet.members().length;return count;}
    public boolean ringsRequested(){for(var w:waveSlots)if(w!=null&&w.alive&&w.ringCommittedMask!=(w.packet.columns()==32?-1:(1<<w.packet.columns())-1)&&w.packet.event()==2&&w.packet.starfall())return true;return false;}
    public void commitStarfall(){starfallGpu.commit();for(var w:waveSlots)if(w!=null&&w.alive&&w.packet.event()==2&&!w.packet.dome())w.ringCommittedMask|=w.ringScheduledMask;}

    /**
     * Wave-contact detection dispatch (render thread, after the stormpos
     * readback — the same freshly written pool + counter slot): runs ONLY when
     * a live wave targets the local player; the tiny staging buffer rides the
     * frame fence back and {@link #acceptWaveSnapshot} ships the reports.
     */
    public void dispatchWaveContactReadback(int slot, int upper,float dt) {
        if (!this.stormActive || !anyWaveTargetingLocalPlayer())
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null)
            return;
        int wc = engine.programs().waveContact();
        if (wc == 0)
            return;
        org.lwjgl.opengl.GL20.glUseProgram(wc);
        engine.gpu().bindParticleWrite(ParticleBuffers.PARTICLE_BB_WRITE);
        engine.gpu().bindCounter(3, slot);
        engine.gpu().bindEmitters(5);
        engine.gpu().bindWaveContact();
        starfallGpu.bind(wc,true);
        engine.gpu().bindPopulationRead(wc,true,engine.currentGeneration()+1);
        int localMask=0;for(int i=0;i<4;i++){var w=waveSlots[i];if(locallyRelevant(w))localMask|=1<<i;}
        CMIParticleEngine.setUIntUniform(wc,"uLocalWaveMask",localMask);
        Vec3 aim = mc.player.position().add(0.0, 0.5, 0.0);
        CMIParticleEngine.setFloatUniform(wc, "uLocalPlayerPos",
                (float) aim.x, (float) aim.y, (float) aim.z);
        CMIParticleEngine.setFloatUniform(wc, "uTimeSec", this.timeSec);
        CMIParticleEngine.setFloatUniform(wc,"uDt",dt);
        CMIParticleEngine.setVec4ArrayUniform(wc, "uWave", this.waveUniform);
        CMIParticleEngine.setVec4ArrayUniform(wc, "uWaveTarget", this.waveTargetUniform);
        engine.gpu().dispatch(ParticleBuffers.STORM_DISPATCH);
        org.lwjgl.opengl.GL42.glMemoryBarrier(
                org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BARRIER_BIT
                        | org.lwjgl.opengl.GL42.GL_ATOMIC_COUNTER_BARRIER_BIT);
        this.waveContactPending = true;
    }

    /**
     * Consumes a pending wave-contact readback at the fence poll: dedupes per
     * member per wave and ships one ServerboundStormWaveContactPacket per new
     * contact. The contact point rides rel-to-the-interpolated-center, the
     * same frame the melee hit reports use (the server's +8 reach margin
     * absorbs the sub-2-block anchor skew).
     */
    public void acceptWaveSnapshot(float[] entries) {
        if (entries == null)
            return;
        for(int o=0;o+5<entries.length;o+=6){int member=Math.round(entries[o]),id=Math.round(entries[o+1]);WaveClient w=findWaveById(id);
            if(w==null||!w.reported.add(member))continue;
            PacketDistributor.sendToServer(new ServerboundStormWaveContactPacket(id,w.epoch,entries[o+5],member,entries[o+2],entries[o+3],entries[o+4]));
        }
    }

    /** Drops a pending wave-contact readback (fence WAIT_FAILED; a fresh one is scheduled anyway). */
    public void dropWaveContact() {
        this.waveContactPending = false;
    }
}
