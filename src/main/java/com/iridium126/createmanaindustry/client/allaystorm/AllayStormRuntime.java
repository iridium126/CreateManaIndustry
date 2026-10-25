package com.iridium126.createmanaindustry.client.allaystorm;

import java.util.BitSet;
import com.iridium126.createmanaindustry.client.particles.emitter.EmitCommandWriter;

import com.iridium126.createmanaindustry.client.particles.engine.ParticleBuffers;
import com.iridium126.createmanaindustry.content.allaystorm.AllayStormData;
import com.iridium126.createmanaindustry.content.allaystorm.AllayStormManager;
import com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormStatePacket;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Render-thread Storm state, scheduling and server feedback policy.
 * Shared-pool resources and dispatch bindings belong to the engine-owned port.
 * Per-member work uses this domain's state and performs no engine discovery.
 */
public final class AllayStormRuntime {
    public static final AllayStormRuntime INSTANCE=new AllayStormRuntime(com.iridium126.createmanaindustry.client.particles.engine.ParticleRuntime.INSTANCE);

    private final StormParticlePort particles;

    public AllayStormRuntime(StormParticlePort particles) {
        this.particles = java.util.Objects.requireNonNull(particles);
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
    public void tickClock(long gameTime) {tickClock(gameTime,0);}
    public void tickClock(long gameTime,float partialTick) {
        this.gameTimeNow = gameTime;
        starfallGpu.clock(((gameTime&((1L<<21)-1))+Math.clamp(partialTick,0,1))/20.0);
        this.timeSec = com.iridium126.createmanaindustry.content.allaystorm.StarfallColumnRules.renderSeconds(gameTime,partialTick);
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
            var phases = com.iridium126.createmanaindustry.content.allaystorm.AllayStormPhases.evaluate(
                    stormCreationRadius, stormFinalRadius, stormGrowthPerSecond, stormCreatedAtGameTime,
                    gameTimeNow, countInterp, stormSeed);
            stormRotPhase = phases.rotation();
            stormConvPhase = phases.conveyor();
            stormConvRate = phases.conveyorRate();
            stormOmegaNow = phases.omega();
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
    private final com.iridium126.createmanaindustry.content.allaystorm.StormStreamClock centerClock = new com.iridium126.createmanaindustry.content.allaystorm.StormStreamClock();

    public void applyStormCenter(com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormCenterPacket packet) {
        var level = Minecraft.getInstance().level;
        if (level == null || !centerClock.accept(packet.identity(), identity(), level.dimension().location(), packet.gameTime())) return;
        applyStormCenter(packet.x(), packet.y(), packet.z(), packet.velX(), packet.velZ(), packet.count(), packet.gameTime());
    }

    private void applyStormCenter(float x, float y, float z, float velX, float velZ,
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
        if(this.stormCount>=16384)com.iridium126.createmanaindustry.content.allaystorm.DomeTemplate.preloadKeys();
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
    private com.iridium126.createmanaindustry.content.allaystorm.StormIdentity stormIdentity;
    public com.iridium126.createmanaindustry.content.allaystorm.StormIdentity identity() { return stormIdentity; }
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

    // ---- synced player collection (storm repulsion + readback near-test) -----

    /** Scratch for per-frame synced player positions (xyz per player). */
    private float[] playerScratch = new float[ParticleBuffers.MAX_STORM_PLAYERS * 3];
    private float[] playerEyes=new float[ParticleBuffers.MAX_STORM_PLAYERS*3],playerFeet=new float[ParticleBuffers.MAX_STORM_PLAYERS*3];
    private int[] playerIds=new int[ParticleBuffers.MAX_STORM_PLAYERS];
    private final float[] attackEyes=new float[68*4];
    public float[] playerEyes(){return playerEyes;}public float[] playerFeet(){return playerFeet;}public int[] playerIds(){return playerIds;}
    public float[] attackEyes(){return attackEyes;}
    /** Player count uploaded with the last collectSyncedPlayers (pass handoff). */
    private int lastPlayerCount = 0;
    public int lastPlayerCount(){return lastPlayerCount;}

    // ---- public protocol API (called directly by the network handler) -------

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
    public void applyStormState(ClientboundStormStatePacket p, net.minecraft.resources.ResourceLocation currentDimension) {
        if (!p.identity().acceptsLifecycle(p.action(), currentDimension, stormIdentity)) return;
        if (p.action() == ClientboundStormStatePacket.ACTION_ACTIVATE) stormIdentity = p.identity();
        applyStormState(p.action(), p.authority(), p.correctionHz(), Vec3.atLowerCornerOf(p.anchor()),
                p.count(), p.stormSeed(), p.creationRadius(), p.finalRadius(), p.growthPerSecond(), p.createdAt(), p.deadBitmap());
    }

    private void applyStormState(int action, boolean authority, double correctionHz,
            Vec3 anchor, int count, int seed,
            float creationRadius, float finalRadius, float growthPerSecond, long createdAt,
            byte[] deadBitmap) {
        switch (action) {
            case ClientboundStormStatePacket.ACTION_ACTIVATE -> {
                centerClock.reset();
                StormFeedback.INSTANCE.reset();
                // full restart: expire whatever storm generation is live
                if (this.stormActive && this.stormEmitId >= 0)
                    this.stormKillEmitId = this.stormEmitId;
                // identities may be re-derived against a new seed: stale
                // identity -> slot entries must never serve member-keyed
                // combat origins
                if (particles.initialized())
                    particles.invalidateParticleIdentities();
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
                if(count>=16384)com.iridium126.createmanaindustry.content.allaystorm.DomeTemplate.preloadKeys();
                this.stormAnalyticUpTo = count; // everything present at ACTIVATE spawns settled
                this.stormAuthority = authority;
                this.stormCorrectionHz = correctionHz;
                this.stormSpawnCursor = 0;
                this.pendingStormSpawns = Math.max(0, count - this.stormDead.cardinality());
                this.stormHeaderWritten = false;
                com.iridium126.createmanaindustry.client.particles.interaction.ModelInteractionRuntime.INSTANCE.dropHitKeys(); // identities reshuffle
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
                    h[16 * 4 + 0] = particles.ensurePoofEmitter();
                    particles.header(this.stormEmitId, h);
                }
            }
            case ClientboundStormStatePacket.ACTION_DEACTIVATE, ClientboundStormStatePacket.ACTION_STOP -> {
                centerClock.reset();
                StormFeedback.INSTANCE.reset();
                this.stormIdentity = null;
                // local dispersal; server state is not this side's business
                if (this.stormActive && this.stormEmitId >= 0)
                    this.stormKillEmitId = this.stormEmitId;
                if (particles.initialized())
                    particles.invalidateParticleIdentities();
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
                com.iridium126.createmanaindustry.client.particles.interaction.ModelInteractionRuntime.INSTANCE.dropHitKeys();
                clearWaveState(); // members disperse; no wave exists to steer them
            }
            default -> {
            }
        }
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
            float light, boolean died, Vec3 hitPosition, long gameTime) {
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
            this.stormDead.set(memberIdx);StormCollisionRuntime.dead(memberIdx);
        }
        com.iridium126.createmanaindustry.client.particles.interaction.ModelInteractionRuntime.INSTANCE.enqueueDamageEntry(memberIdx,damage,kbX,kbZ,light,ParticleBuffers.DAMAGE_FLAG_MEMBER|(died?ParticleBuffers.DAMAGE_FLAG_DIED:0));
        StormFeedback.INSTANCE.applyHitCorrection(memberIdx, hitPosition, gameTime);
    }

    /** Full storm teardown (level clear/shutdown): forget the emitter id too. */
    public void resetStormState() {
        centerClock.reset();
        this.stormIdentity = null;
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
        StormFeedback.INSTANCE.reset();
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
        if (hadMembers) this.particles.clearMembers();
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
     * Collects all living, non-spectator synced
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
        int size=mc.level.players().size();if(playerScratch.length<size*3)playerScratch=new float[Integer.highestOneBit(Math.max(1,size*3-1))<<1];
        if(playerEyes.length<playerScratch.length){playerEyes=new float[playerScratch.length];playerFeet=new float[playerScratch.length];playerIds=new int[playerScratch.length/3];}
        int count=0;for(var p:mc.level.players()){if(!p.isAlive()||p.isSpectator())continue;
            playerScratch[count*3]=(float)p.getX();playerScratch[count*3+1]=(float)p.getBoundingBox().getCenter().y;playerScratch[count*3+2]=(float)p.getZ();count++;
            int i=count-1;playerEyes[i*3]=(float)p.getX();playerEyes[i*3+1]=(float)p.getEyeY();playerEyes[i*3+2]=(float)p.getZ();
            playerFeet[i*3]=(float)p.getX();playerFeet[i*3+1]=(float)p.getY();playerFeet[i*3+2]=(float)p.getZ();playerIds[i]=p.getId();
        }
        java.util.Arrays.fill(attackEyes,0);for(int slot=0;slot<68;slot++){int id=starfallGpu.lookTarget(slot);var target=id==0?null:mc.level.getEntity(id);if(target!=null&&target.isAlive()){int b=slot*4;attackEyes[b]=(float)target.getX();attackEyes[b+1]=(float)target.getEyeY();attackEyes[b+2]=(float)target.getZ();attackEyes[b+3]=1;}}
        this.lastPlayerCount = count;
        return count;
    }

    /** Player positions (xyz) from the last {@link #collectSyncedPlayers}. */
    public float[] playerScratch() {
        return this.playerScratch;
    }

    // ---- emit scheduling ---------------------------------------------------------

    /**
     * Storm trickle-in: at most ~60 frames of member batches regardless of
     * population (the swarm assembles over ~1s instead of popping in), SPLIT
     * around the server-reported dead members so each command's member index
     * base + inner (GPU-side) reproduces the server's member numbering
     * exactly — identity stays cross-client. Runs every active frame, not
     * just while trickle-spawning: the first members of frame one draw in
     * this very tick (so the allay atlas must be live BEFORE emission), and the independent section-bit collision runtime retains the full active
     * storm envelope without consuming generic emitter bake slots.
     */
    public void scheduleSpawnRuns(EmitCommandWriter s) {
        if (this.stormActive) {
            particles.prepareAppearance(this.center);
        }
        if (this.stormActive && this.pendingStormSpawns > 0) {
            int id = particles.ensureEmitter(AllayStormSpec.SPEC);
            if (id >= 0) {
                if (!this.stormHeaderWritten) {
                    // Anchor + storm parameters ride the per-id header slots 18/19
                    // (specs stay position-free; this spec dedupes to its own id).
                    // AllayStormSpec owns the slot layout; only the death-chain
                    // emitter id is patched here (MODEL particles poof on expiry).
                    // refreshCenterHeader rewrites the center every frame after
                    // this, so the initial value is just the seed.
                    float[] h = AllayStormSpec.packedHeader(this.stormRadius, this.center);
                    h[16 * 4 + 0] = particles.ensurePoofEmitter();
                    particles.header(id, h); // uploaded below this frame
                    this.stormEmitId = id;
                    this.stormHeaderWritten = true;
                }
                // Drain in ~60 frames regardless of population (see javadoc).
                int budget = Math.min(this.pendingStormSpawns,
                        Math.max(s.capacity(),
                                (this.pendingStormSpawns + 59) / 60));
                int scheduled = 0;
                while (scheduled < budget && !s.full()
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
                    if (!s.members(id,runLen,this.center,runStart,ring)) {
                        this.stormSpawnCursor=runStart;
                        break;
                    }
                    scheduled += runLen;
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
        h[16 * 4 + 0] = particles.ensurePoofEmitter();
        particles.header(this.stormEmitId, h);
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

    /** Four major-attack slots followed by 64 independent column slots. */
    public static final int MAX_WAVES = 68;
    /** Corridor waypoints per wave shipped in the packet (GPU uniform budget). */
    public static final int MAX_WAVE_PATH = 6;

    private static final class WaveClient {
        ContactAuthority feedbackIdentity;
        int[] gatherCounts=new int[4];
        boolean alive,simulated;int slotIdx=-1,waveId,targetEntityId;long epoch;
        com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormWavePacket packet;
        final java.util.BitSet completed=new java.util.BitSet();
        final float[] bounds=new float[6];boolean windowReady;
        int ringCommittedMask,ringScheduledMask,columnSoundMask;boolean startSound,lockSound;
    }
    private final WaveClient[] waveSlots=new WaveClient[MAX_WAVES];
    private final com.iridium126.createmanaindustry.content.allaystorm.StormWaveSlots waveLifecycle = new com.iridium126.createmanaindustry.content.allaystorm.StormWaveSlots(MAX_WAVES);
    private final com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormWavePacket[] starfallSnapshots=new com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormWavePacket[MAX_WAVES];
    private final StarfallGpuBuffers starfallGpu=StarfallGpuBuffers.INSTANCE;
    private boolean starfallDirty=true;private String lastHelixAbort="none";
    private int lastGatherId,lastGatherKind,lastGatherAbort;private int[] lastGatherCounts=new int[4];

    boolean livingMember(int member){return member>=0&&member<stormCount&&!stormDead.get(member);}
    public boolean anyWaveActive(){for(var w:waveSlots)if(w!=null&&w.alive&&!w.packet.abort())return true;return false;}
    private boolean locallyRelevant(WaveClient w){
        var me=Minecraft.getInstance().player;if(me==null||w==null||!w.alive||w.packet.abort())return false;
        if(stormAuthority)return true; // Aggregate gather/completion feedback is independent of local contact range.
        var p=w.packet;if(p.helixAttack()){if(!p.locked()||com.iridium126.createmanaindustry.content.allaystorm.AllayStarfall.elapsed(timeSec,p.lockSec())<0||com.iridium126.createmanaindustry.content.allaystorm.AllayStarfall.elapsed(timeSec,p.diveUntilSec())>0)return false;return me.getX()>=w.bounds[0]&&me.getX()<=w.bounds[3]&&me.getY()>=w.bounds[1]&&me.getY()<=w.bounds[4]&&me.getZ()>=w.bounds[2]&&me.getZ()<=w.bounds[5];}
        if(p.column()){double dx=me.getX()-p.landings()[0],dz=me.getZ()-p.landings()[2];return dx*dx+dz*dz<=256&&Math.abs(me.getY()-p.landings()[1])<5;}
        if(p.starfall())return w.targetEntityId==me.getId();
        if(!p.locked())return false;double dx=me.getX()-p.centerX(),dz=me.getZ()-p.centerZ(),r=p.crownRadius()+4;
        return dx*dx+dz*dz<=r*r&&me.getY()>=p.centerY()-3&&me.getY()<=p.centerY()+p.crownRadius()+4;
    }
    public boolean anyWaveTargetingLocalPlayer(){for(var w:waveSlots)if(locallyRelevant(w))return true;return false;}
    public void completeAttack(com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackCompletedPacket packet){
        var identity=identity();if(identity==null||!identity.dimension().equals(packet.dimension())||identity.epoch()!=packet.epoch())return;
        var wave=findWaveById(packet.id());if(wave==null)return;
        var delta=java.util.BitSet.valueOf(packet.ranks());delta.andNot(wave.completed);wave.completed.or(delta);
        if(wave.simulated)StormCollisionRuntime.completed(wave.packet,delta.toLongArray());starfallGpu.completed(packet.id(),packet.ranks());starfallDirty=true;
        if(wave.completed.cardinality()==wave.packet.members().length){wave.alive=false;waveLifecycle.complete(wave.waveId);starfallSnapshots[wave.slotIdx]=null;}
    }
    public void applyColumn(com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormColumnPacket p){
        var mc=Minecraft.getInstance();if(mc.level==null||!mc.level.dimension().location().equals(p.dimension()))return;
        boolean replacement=findWaveById(p.id())==null;
        applyWave(p.snapshot());
        var wave=findWaveById(p.id());
        if(replacement&&p.resume()&&wave!=null){starfallGpu.columnWaiting(wave.slotIdx,p.id(),true);wave.lockSound=true;starfallDirty=true;}
    }
    public com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormWavePacket columnPacket(int id){var wave=findWaveById(id);return wave==null||!wave.packet.column()||wave.packet.abort()?null:wave.packet;}
    public int columnSlot(int id){var wave=findWaveById(id);return wave==null?-1:wave.slotIdx;}
    public java.util.BitSet columnCompleted(int id){var wave=findWaveById(id);return wave==null?new java.util.BitSet():(java.util.BitSet)wave.completed.clone();}
    public void columnRestored(int id,int token){var wave=findWaveById(id);if(wave==null)return;starfallGpu.columnWaiting(wave.slotIdx,id,false);starfallGpu.columnRestoreToken(wave.slotIdx,id,token);starfallDirty=true;}
    public void applyWave(com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormWavePacket p){
        if(!stormActive||p.epoch()!=com.iridium126.createmanaindustry.content.allaystorm.AllayStarfall.epoch(stormCreatedAtGameTime,stormSeed))return;
        var update = waveLifecycle.apply(p, timeSec);
        for (int slot = 0; slot < MAX_WAVES; slot++)
            if (waveLifecycle.packet(slot) == null && waveSlots[slot] != null && waveSlots[slot].alive) {
                waveSlots[slot].alive = false;
                starfallDirty = true;
            }
        if (update == null) return;
        WaveClient w = waveSlots[update.slot()];
        if (update.replacement()) {
            w = new WaveClient(); w.slotIdx = update.slot(); waveSlots[update.slot()] = w;
        }
        if(p.event()==0&&!p.abort())StormCollisionRuntime.claimed(update.packet());
        w.packet = update.packet();
        if (p.abort()) {
            if(w.simulated&&(w.packet.helixAttack()||w.packet.dome()||w.packet.column())){var all=new java.util.BitSet();all.set(0,w.packet.members().length);all.andNot(w.completed);StormCollisionRuntime.completed(w.packet,all.toLongArray());}
            if(w.packet.helixAttack()||w.packet.dome()){lastGatherId=w.waveId;lastGatherKind=w.packet.attackType();lastGatherAbort=p.fallbackReason();lastGatherCounts=w.gatherCounts.clone();}
            if (w.packet.helixAttack()) lastHelixAbort = switch(p.fallbackReason()) {
                case 5 -> "blocked"; case 6 -> "target-lost"; case 7 -> "aim-timeout"; case 8 -> "unsafe-route"; default -> "aborted";
            };
            starfallDirty = true;
            return;
        }
        if(p.helixAttack()){java.util.Arrays.fill(w.bounds,0,3,Float.POSITIVE_INFINITY);java.util.Arrays.fill(w.bounds,3,6,Float.NEGATIVE_INFINITY);float pad=p.helix().radius()*1.6f+4;for(int i=0;i<48;i++)for(int axis=0;axis<3;axis++){float v=p.helix().samples()[i*4+axis];w.bounds[axis]=Math.min(w.bounds[axis],v-pad);w.bounds[axis+3]=Math.max(w.bounds[axis+3],v+pad);}}
        w.alive=true;w.waveId=p.waveId();w.epoch=p.epoch();w.targetEntityId=p.targetEntityId();starfallDirty=true;
        if(w.feedbackIdentity==null||w.feedbackIdentity.id()!=w.waveId||w.feedbackIdentity.epoch()!=w.epoch)w.feedbackIdentity=new ContactAuthority(w.waveId,w.epoch);
        if(w.packet.completedRanks().length>0)completeAttack(new com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackCompletedPacket(identity().dimension(),w.epoch,w.waveId,w.packet.completedRanks()));
        StormFeedback.INSTANCE.retireWaveReports();
    }
    private WaveClient findWaveById(int id){for(var w:waveSlots)if(w!=null&&w.alive&&w.waveId==id)return w;return null;}
    public void clearWaveState(){java.util.Arrays.fill(navigationStats,0);java.util.Arrays.fill(depenetrationStats,0);ColumnSnapshotClient.INSTANCE.clear();StormCollisionRuntime.clearReturns();waveLifecycle.clear();lastHelixAbort="none";lastGatherId=0;lastGatherAbort=0;java.util.Arrays.fill(lastGatherCounts,0);java.util.Arrays.fill(waveSlots,null);java.util.Arrays.fill(starfallSnapshots,null);StormFeedback.INSTANCE.retireWaves();starfallDirty=true;starfallGpu.invalidate();}
    private long columnSoundTick=Long.MIN_VALUE;private int columnSounds;
    public void refreshWaves(){
        waveLifecycle.retire(timeSec);
        starfallGpu.resetUniforms();
        if(columnSoundTick!=gameTimeNow){columnSoundTick=gameTimeNow;columnSounds=0;}
        int soundBudget=2;
        for(int slot=0;slot<MAX_WAVES;slot++){
            var w=waveSlots[slot];if(w==null||!w.alive){starfallSnapshots[slot]=null;continue;}
            if(com.iridium126.createmanaindustry.content.allaystorm.AllayStarfall.elapsed(timeSec,w.packet.diveUntilSec())>0){if(w.simulated&&!w.packet.abort()&&(w.packet.helixAttack()||w.packet.dome()||w.packet.column())){var all=new java.util.BitSet();all.set(0,w.packet.members().length);all.andNot(w.completed);StormCollisionRuntime.completed(w.packet,all.toLongArray());}w.alive=false;starfallDirty=true;starfallSnapshots[slot]=null;continue;}
            w.ringScheduledMask=0;var p=w.packet;starfallSnapshots[slot]=p;
            var mc=Minecraft.getInstance();
            if(!p.column()&&!p.abort()&&mc.level!=null){
                float startAge=com.iridium126.createmanaindustry.content.allaystorm.AllayStarfall.elapsed(timeSec,p.startSec());
                if(!w.startSound&&startAge>=0){w.startSound=true;if(startAge<.5&&soundBudget>0){soundBudget--;mc.level.playLocalSound(p.crownX(),p.crownY(),p.crownZ(),net.minecraft.sounds.SoundEvents.ALLAY_AMBIENT_WITH_ITEM,net.minecraft.sounds.SoundSource.HOSTILE,.4f,.7f,false);}}
                if(p.event()==2){float lockAge=com.iridium126.createmanaindustry.content.allaystorm.AllayStarfall.elapsed(timeSec,p.lockSec());if(!w.lockSound){w.lockSound=true;if(lockAge<.5&&soundBudget>0){soundBudget--;mc.level.playLocalSound(!p.starfall()?p.centerX():p.landings()[0],!p.starfall()?p.centerY()+p.crownRadius():p.landings()[1],!p.starfall()?p.centerZ():p.landings()[2],net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME,net.minecraft.sounds.SoundSource.HOSTILE,.45f,1.1f,false);}}
                    for(int c=0;c<(p.helixAttack()||p.dome()?1:p.columns());c++){float phaseStart=p.helixAttack()?p.helix().thrust():p.dome()?com.iridium126.createmanaindustry.content.allaystorm.AllayDome.warningStart(p.lockSec(),p.crownRadius(),c):p.lockSec()+1+c*.35f;float age=com.iridium126.createmanaindustry.content.allaystorm.AllayStarfall.elapsed(timeSec,phaseStart);int bit=1<<c;if((w.columnSoundMask&bit)==0&&age>=0){w.columnSoundMask|=bit;if(age<.2&&soundBudget>0){soundBudget--;int k=c*4;mc.level.playLocalSound(p.dome()?p.centerX():p.landings()[k],!p.starfall()?p.centerY()+6:p.landings()[k+1]+6,p.dome()?p.centerZ():p.landings()[k+2],net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_SWEEP,net.minecraft.sounds.SoundSource.HOSTILE,.3f,1+c*.01f,false);}}}
                }
            }
            if(slot<4)starfallGpu.waveUniform(slot,p.waveSeed(),p.startSec(),p.diveUntilSec(),p.waveId(),p.swordTier());
            if(p.column()){w.windowReady=!p.abort();starfallGpu.columnReady(slot,w.windowReady);}
            else if(p.helixAttack()){w.windowReady=!p.abort();}

        }
        StormCollisionRuntime.refresh(stormActive,center,(float)stormRadius,starfallSnapshots);
        if(starfallDirty){starfallGpu.rebuild(starfallSnapshots);starfallDirty=false;}for(var w:waveSlots)if(w!=null&&w.alive&&w.packet.helixAttack())starfallGpu.helixReady(w.slotIdx,w.windowReady);
    }

    /** GPU-confirmed touchdown feedback, merged by column ID and capped per game tick. */
    public void columnTouchSound(int id,float x,float y,float z){
        var w=findWaveById(id);var mc=Minecraft.getInstance();
        if(w==null||!w.packet.column()||w.packet.abort()||w.lockSound||mc.level==null)return;
        w.lockSound=true;
        if(columnSoundTick!=gameTimeNow){columnSoundTick=gameTimeNow;columnSounds=0;}
        if(columnSounds>=2)return;columnSounds++;
        mc.level.playLocalSound(x,y,z,net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME,net.minecraft.sounds.SoundSource.HOSTILE,.35f,.9f+(w.packet.waveSeed()&7)*.03f,false);
    }

    private static final com.iridium126.createmanaindustry.client.particles.emitter.EmitterSpec STARFALL_RING_SPEC=
        com.iridium126.createmanaindustry.client.particles.emitter.EmitterSpec.builder().speed(0,0).life(180,180).sizeOverLife(.135,.135,1).gravity(0,0,0).drag(0).glow(1).color(.3f,.8f,1,1).spawnStyle(7).type(com.iridium126.createmanaindustry.client.particles.emitter.ParticleTypes.standard(com.iridium126.createmanaindustry.client.particles.emitter.ParticleTypes.Material.ADDITIVE)).build();
    public void scheduleRings(EmitCommandWriter schedule){
        int emitter=-1;for(var w:waveSlots){if(w==null||!w.alive||w.ringCommittedMask==(w.packet.columns()==32?-1:(1<<w.packet.columns())-1)||w.packet.event()!=2||!w.packet.starfall())continue;
            if(emitter<0){emitter=particles.ensureEmitter(STARFALL_RING_SPEC);if(emitter<0)return;particles.header(emitter,STARFALL_RING_SPEC.packed());}
            for(int c=0;c<w.packet.columns()&&!schedule.full();c++){
                if((w.ringCommittedMask&(1<<c))!=0)continue;
                if (!schedule.ring(emitter,w.waveId,w.slotIdx,c)) break;
                w.ringScheduledMask|=1<<c;
            }
        }
    }
    private final float[] depenetrationStats=new float[6];
    public void depenetrationProgress(int offset,float a,float b,float c,float d){depenetrationStats[offset]=a;depenetrationStats[offset+1]=b;if(offset==0){depenetrationStats[2]=c;depenetrationStats[3]=d;}}
    private final float[] navigationStats=new float[6];
    public void navigationProgress(int offset,float a,float b,float c,float d){navigationStats[offset]=a;navigationStats[offset+1]=b;if(offset==0){navigationStats[2]=c;navigationStats[3]=d;}}
    public boolean domeGather(int id){var w=findWaveById(id);return w!=null&&w.packet.dome();}
    com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormWavePacket wavePacket(int id){var wave=findWaveById(id);return wave==null||!wave.alive||wave.packet.abort()?null:wave.packet;}
    public void gatherProgress(int id,int[] counts){var w=findWaveById(id);if(w!=null)w.gatherCounts=counts;}
    public String starfallStatus(){
        int stars=0,domes=0,helices=0,members=0;
        var stages=new StringBuilder();
        for(var w:waveSlots)if(w!=null&&w.alive&&!w.packet.abort()){
            var p=w.packet;members+=p.members().length;
            stages.append("\nStorm #").append(w.waveId).append(": ");
            if(p.helixAttack()){helices++;var h=p.helix();float now=p.startSec()+com.iridium126.createmanaindustry.content.allaystorm.AllayStarfall.elapsed(timeSec,p.startSec());
                String stage=now<p.assembleSec()?"assemble":!p.locked()?(now<h.charge()?"approach":"charge"):now<h.thrust()?"locked":now<h.finish()?"thrust":"return";
                stages.append("helix ").append(stage).append("; window=").append(w.windowReady?"ready":"pending");
            }else if(p.dome()){domes++;stages.append("dome ").append(p.locked()?"contract":"assemble");}
            else{stars++;stages.append("starfall; columns=").append(p.columns());}
            if((p.helixAttack()||p.dome())&&!p.locked())stages.append("; gather ready/eligible/blocked/unknown=")
                    .append(w.gatherCounts[0]).append('/').append(w.gatherCounts[1]).append('/').append(w.gatherCounts[2]).append('/').append(w.gatherCounts[3]);
            if(p.fallbackReason()!=0)stages.append("; fallback=").append(p.fallbackReason()==1?"too-few":p.fallbackReason()==2?"terrain":p.fallbackReason()==3?"helix-arms":p.fallbackReason()==4?"helix-route":"helix-blocked");
        }
        String navigation=com.iridium126.createmanaindustry.client.gpu.profile.ProfileReportFormat.namedValues(new String[]{"queries","nodes","fallback","reconnect","wait","active"},navigationStats);
        String escape=com.iridium126.createmanaindustry.client.gpu.profile.ProfileReportFormat.namedValues(new String[]{"embedded","corrected","noExit","unknown","wait","distance"},depenetrationStats);
        if(members==0&&navigation.isEmpty()&&escape.isEmpty()&&lastHelixAbort.equals("none")&&lastGatherAbort==0&&!StormCollisionRuntime.active())return "";
        var out=new StringBuilder("Storm live: waves starfall/helix/dome=").append(stars).append('/').append(helices).append('/').append(domes)
                .append("; members=").append(members).append("; upload total=").append(com.iridium126.createmanaindustry.client.gpu.profile.ProfileReportFormat.bytes(starfallGpu.uploadedBytes())).append(stages);
        if(!navigation.isEmpty())out.append("\nStorm navigation: ").append(navigation);
        if(!escape.isEmpty())out.append("\nStorm depenetration: ").append(escape);
        if(!lastHelixAbort.equals("none"))out.append("\nStorm last helix abort: ").append(lastHelixAbort);
        if(lastGatherAbort!=0)out.append("\nStorm last gather abort: wave=").append(lastGatherId).append("; kind=").append(lastGatherKind).append("; reason=").append(lastGatherAbort)
                .append("; ready/eligible/blocked/unknown=").append(lastGatherCounts[0]).append('/').append(lastGatherCounts[1]).append('/').append(lastGatherCounts[2]).append('/').append(lastGatherCounts[3]);
        if(domes>0)out.append("\nStorm dome: ").append(starfallGpu.domeResourceStatus());
        if(StormCollisionRuntime.active())out.append('\n').append(StormCollisionRuntime.profilingReport());
        return out.toString();
    }
    public int carrierCount(){int count=0;for(var w:waveSlots)if(w!=null&&w.alive&&!w.packet.abort())count+=w.packet.members().length;return count;}
    public boolean ringsRequested(){for(var w:waveSlots)if(w!=null&&w.alive&&w.ringCommittedMask!=(w.packet.columns()==32?-1:(1<<w.packet.columns())-1)&&w.packet.event()==2&&w.packet.starfall())return true;return false;}
    public void commitStarfall(){starfallGpu.commit();for(var w:waveSlots)if(w!=null&&w.alive){w.simulated=true;if(w.packet.event()==2&&!w.packet.dome())w.ringCommittedMask|=w.ringScheduledMask;}}

    private int candidateSpawnCursor,candidatePendingSpawns;
    public void checkpointSpawnInputs() {candidateSpawnCursor=stormSpawnCursor;candidatePendingSpawns=pendingStormSpawns;}
    public void restoreSpawnInputs() {stormSpawnCursor=candidateSpawnCursor;pendingStormSpawns=candidatePendingSpawns;}

    record FeedbackState(boolean active,boolean authority,double correctionHz,Vec3 center,float time,int players,int waveMask) {}
    FeedbackState feedbackState() {
        int mask=0;for(int i=0;i<MAX_WAVES;i++)if(locallyRelevant(waveSlots[i]))mask|=i<4?1<<i:16;
        if((stormAuthority&&anyWaveActive())||StormCollisionRuntime.returning()||ColumnSnapshotClient.INSTANCE.restoring())mask|=31;
        return new FeedbackState(stormActive,stormAuthority,stormCorrectionHz,center,timeSec,lastPlayerCount,mask);
    }
    public record ContactAuthority(int id,long epoch) {}
    ContactAuthority contactAuthority(int id) {var wave=findWaveById(id);return wave==null||wave.packet.abort()?null:wave.feedbackIdentity;}
}
