package com.iridium126.createmanaindustry.client.allaystorm;


import com.iridium126.createmanaindustry.content.allaystorm.network.ServerboundStormPositionsPacket;
import com.iridium126.createmanaindustry.content.allaystorm.network.ServerboundStormWaveContactPacket;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/** Owns independent position/wave feedback responsibility and correction input. */
public final class StormFeedback {

    /** gameTime (ticks) of the last dispatched position snapshot. */
    private long lastSnapshotGameTime = Long.MIN_VALUE;
    /** Set when the compute phase dispatched a readback; consumed at fence poll. */
    private boolean stormPosPending = false;
    private long stormPosGameTime = 0;

    /**
     * Applies one relayed position snapshot ({@code entries} stride
     * {@code 8: memberIdx, pad, posRelToAnchor.xyz, vel.xyz}) by writing soft
     * correction slots — positions are never written directly (no teleports);
     * the update pass eases members toward the extrapolated targets.
     */
    public void applyCorrections(float[] entries, long gameTime) {
        var state=AllayStormRuntime.INSTANCE.feedbackState();
        if (!state.active() || entries == null || entries.length < 8)
            return;
        float arrival = (gameTime & ((1L<<21)-1))*.05f;
        var target = particles; // captured before the member loop
        int cap = StormIdentityBuffers.LIMIT;
        for (int o = 0; o + 7 < entries.length; o += 8) {
            int memberIdx = (int) entries[o];
            if (memberIdx < 0 || memberIdx >= cap)
                continue; // no correction slot exists for this identity
            float mx = (float) state.center().x + entries[o + 2];
            float my = (float) state.center().y + entries[o + 3];
            float mz = (float) state.center().z + entries[o + 4];
            target.correction(memberIdx, mx, my, mz, arrival,
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
        var state=AllayStormRuntime.INSTANCE.feedbackState();
        if (!state.active() || memberIdx < 0 || memberIdx >= StormIdentityBuffers.LIMIT)
            return;
        float arrival = (gameTime & ((1L<<21)-1))*.05f;
        particles.correction(memberIdx,
                (float) (state.center().x + relToAnchor.x),
                (float) (state.center().y + relToAnchor.y),
                (float) (state.center().z + relToAnchor.z),
                arrival, 0f, 0f, 0f, 10.0f);
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
        if(!AllayStormRuntime.INSTANCE.active())return;
        var state=AllayStormRuntime.INSTANCE.feedbackState();
        if (this.stormPosPending || !state.active() || !state.authority() || state.correctionHz() <= 0)
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
            return;
        long now = mc.level.getGameTime();
        if (this.lastSnapshotGameTime != Long.MIN_VALUE
                && now - this.lastSnapshotGameTime < 20.0 / state.correctionHz())
            return;
        if (upper <= 0 || state.players() <= 0)
            return;
        this.lastSnapshotGameTime = now;
        if (!particles.capturePositions(slot, state.center(), state.players())) return;
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

    /**
     * Wave-contact detection dispatch (render thread, after the stormpos
     * readback — the same freshly written pool + counter slot): runs ONLY when
     * a live wave targets the local player; the tiny staging buffer rides the
     * frame fence back and {@link #acceptWaveSnapshot} ships the reports.
     */
    public void dispatchWaveContactReadback(int slot, int upper,float dt) {
        if(!AllayStormRuntime.INSTANCE.active())return;
        var state=AllayStormRuntime.INSTANCE.feedbackState();
        if (!state.active() || state.waveMask()==0)
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null)
            return;
        int localMask=state.waveMask();
        Vec3 aim = mc.player.position().add(0.0, 0.5, 0.0);
        if (!particles.captureWaveContacts(slot, localMask, aim, state.time(), dt,
                StarfallGpuBuffers.INSTANCE)) return;
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
        retireWaves();
        if (entries == null)
            return;
        for(int o=0;o+5<entries.length;o+=6){int member=Math.round(entries[o]),id=Math.round(entries[o+1]);var w=AllayStormRuntime.INSTANCE.contactAuthority(id);
            if(w==null||!reported.computeIfAbsent(w,k->new it.unimi.dsi.fastutil.ints.IntOpenHashSet()).add(member))continue;
            PacketDistributor.sendToServer(new ServerboundStormWaveContactPacket(id,w.epoch(),entries[o+5],member,entries[o+2],entries[o+3],entries[o+4]));
        }
    }

    /** Drops a pending wave-contact readback (fence WAIT_FAILED; a fresh one is scheduled anyway). */
    public void dropWaveContact() {
        this.waveContactPending = false;
    }
    public static final StormFeedback INSTANCE=new StormFeedback(com.iridium126.createmanaindustry.client.particles.engine.ParticleRuntime.INSTANCE);
    private final StormParticlePort particles;
    private boolean waveContactPending;
    private final java.util.Map<AllayStormRuntime.ContactAuthority,it.unimi.dsi.fastutil.ints.IntOpenHashSet> reported=new java.util.HashMap<>();
    public StormFeedback(StormParticlePort particles) {this.particles=java.util.Objects.requireNonNull(particles);}
    public void reset() {stormPosPending=false;lastSnapshotGameTime=Long.MIN_VALUE;waveContactPending=false;reported.clear();}
    public void retireWaves() {
        waveContactPending=false;reported.keySet().removeIf(key->!key.equals(AllayStormRuntime.INSTANCE.contactAuthority(key.id())));
    }
    public void reportHit(int member,float damage,float kbX,float kbZ,float light,boolean critical,boolean magic,int hearts,Vec3 hit) {
        var center=AllayStormRuntime.INSTANCE.center();
        PacketDistributor.sendToServer(new com.iridium126.createmanaindustry.content.allaystorm.network.ServerboundStormHitPacket(
                member,damage,kbX,kbZ,light,critical,magic,hearts,hit.x-center.x,hit.y-center.y,hit.z-center.z));
    }

}
