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
    private com.iridium126.createmanaindustry.content.allaystorm.StormIdentity stormPosIdentity;
    private final com.iridium126.createmanaindustry.content.allaystorm.StormStreamClock correctionClock = new com.iridium126.createmanaindustry.content.allaystorm.StormStreamClock();

    /**
     * Applies one relayed position snapshot ({@code entries} stride
     * {@code 8: memberIdx, pad, posRelToAnchor.xyz, vel.xyz}) by writing soft
     * correction slots — positions are never written directly (no teleports);
     * the update pass eases members toward the extrapolated targets.
     */
    public void applyCorrections(com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormPositionsPacket packet) {
        var state=AllayStormRuntime.INSTANCE.feedbackState();
        var level = Minecraft.getInstance().level;
        if (level == null || !correctionClock.accept(packet.identity(), AllayStormRuntime.INSTANCE.identity(), level.dimension().location(), packet.gameTime())) return;
        float[] entries = packet.entries();
        long gameTime = packet.gameTime();
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
    public void applyHitCorrection(int memberIdx, Vec3 position, long gameTime) {
        var state=AllayStormRuntime.INSTANCE.feedbackState();
        if (!state.active() || memberIdx < 0 || memberIdx >= StormIdentityBuffers.LIMIT)
            return;
        float arrival = (gameTime & ((1L<<21)-1))*.05f;
        particles.correction(memberIdx,
                (float) position.x,
                (float) position.y,
                (float) position.z,
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
        this.stormPosIdentity = AllayStormRuntime.INSTANCE.identity();
    }

    /**
     * Consumes a pending storm position readback at the fence poll (success
     * path): reads the staging buffer and ships it to the server.
     */
    public boolean positionSnapshotPending() { return this.stormPosPending; }
    public long positionSnapshotTime() { return this.stormPosGameTime; }
    public com.iridium126.createmanaindustry.content.allaystorm.StormIdentity positionSnapshotIdentity() { return stormPosIdentity; }
    public boolean waveSnapshotPending() { return this.waveContactPending; }

    public void acceptPositionSnapshot(com.iridium126.createmanaindustry.content.allaystorm.StormIdentity identity, long gameTime, float[] entries) {
        var level = Minecraft.getInstance().level;
        if (identity == null || level == null || !identity.equals(AllayStormRuntime.INSTANCE.identity())
                || !identity.dimension().equals(level.dimension().location()) || !AllayStormRuntime.INSTANCE.feedbackState().authority()) return;
        if (entries != null && entries.length >= 8)
            PacketDistributor.sendToServer(new ServerboundStormPositionsPacket(identity.dimension(), identity.epoch(), gameTime, entries));
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
    private final java.util.Map<Integer,java.util.BitSet> completions=new java.util.HashMap<>();
    private final java.util.Map<AllayStormRuntime.ContactAuthority,Long> gathered=new java.util.HashMap<>();
    private final java.util.Map<AllayStormRuntime.ContactAuthority,Integer> flowRevisions=new java.util.HashMap<>();
    private long flowTick=Long.MIN_VALUE;
    private long completionTick=Long.MIN_VALUE;
    public void acceptWaveSnapshot(float[] entries) {
        retireWaves();
        if (entries == null)
            return;
        var flowTimes=new java.util.HashMap<Integer,float[]>();
        var flowVersions=new java.util.HashMap<Integer,Integer>();
        var ready=new java.util.HashMap<Integer,java.util.BitSet>();
        var eligible=new java.util.HashMap<Integer,java.util.BitSet>();
        var counts=new java.util.HashMap<Integer,int[]>();
        var references=new java.util.HashMap<Integer,float[]>();
        for(int o=0;o+5<entries.length;o+=6){int member=Math.round(entries[o]),id=Math.round(entries[o+1]);var w=AllayStormRuntime.INSTANCE.contactAuthority(id);
            if(member== -11){AllayStormRuntime.INSTANCE.navigationProgress(0,entries[o+2],entries[o+3],entries[o+4],entries[o+5]);continue;}
            if(member== -12){AllayStormRuntime.INSTANCE.navigationProgress(4,entries[o+2],entries[o+3],0,0);continue;}
            if(member== -10){if(w!=null)references.put(id,new float[]{entries[o+2],entries[o+3]});continue;}
            if(member== -9){ColumnSnapshotClient.INSTANCE.installed(id,Math.round(entries[o+2]),Math.round(entries[o+3])|(Math.round(entries[o+4])<<16));continue;}
            if(member== -8){if(w!=null)flowVersions.put(id,Math.round(entries[o+2]));continue;}
            if(member== -7){if(w!=null){int flow=Math.round(entries[o+2]);float pause=entries[o+3];if(flow>=0&&flow<66&&Float.isFinite(pause)&&pause>=0&&pause<=600){float[] times=flowTimes.computeIfAbsent(id,k->{float[] t=new float[66];java.util.Arrays.fill(t,-1);return t;});times[flow]=pause;}}continue;}
            if(member== -6){if(w!=null){int[] c={Math.round(entries[o+2]),Math.round(entries[o+3]),Math.round(entries[o+4]),Math.round(entries[o+5])};counts.put(id,c);AllayStormRuntime.INSTANCE.gatherProgress(id,c);}continue;}
            if(member== -4||member== -5){if(w!=null){int word=Math.round(entries[o+2]),bits=Math.round(entries[o+3])|(Math.round(entries[o+4])<<16);if(word>=0&&word<384){var mask=(member== -4?ready:eligible).computeIfAbsent(id,k->new java.util.BitSet());for(int bit=0;bit<32;bit++)if((bits&(1<<bit))!=0)mask.set(word*32+bit);}}continue;}
            if(member== -3){StormCollisionRuntime.arrived(id,Math.round(entries[o+2]));continue;}
            if(member== -2){if(w!=null){int word=Math.round(entries[o+2]),bits=Math.round(entries[o+3])|(Math.round(entries[o+4])<<16);if(word>=0&&word<384){var mask=completions.computeIfAbsent(id,k->new java.util.BitSet());for(int bit=0;bit<32;bit++)if((bits&(1<<bit))!=0)mask.set(word*32+bit);}}continue;}
            if(member<0){if(w!=null)AllayStormRuntime.INSTANCE.columnTouchSound(id,entries[o+2],entries[o+3],entries[o+4]);continue;}
            if(w==null||!reported.computeIfAbsent(w,k->new it.unimi.dsi.fastutil.ints.IntOpenHashSet()).add(member))continue;
            PacketDistributor.sendToServer(new ServerboundStormWaveContactPacket(Minecraft.getInstance().level.dimension().location(),id,w.epoch(),entries[o+5],member,entries[o+2],entries[o+3],entries[o+4]));
        }
        var mc=Minecraft.getInstance();
        // Publish confirmed exits before readiness from the same GPU snapshot.
        if(mc.level!=null&&completionTick!=mc.level.getGameTime()){
            completionTick=mc.level.getGameTime();
            for(var entry:completions.entrySet()){var w=AllayStormRuntime.INSTANCE.contactAuthority(entry.getKey());if(w==null)continue;
                var packet=new com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackCompletedPacket(mc.level.dimension().location(),w.epoch(),w.id(),entry.getValue().toLongArray());
                if(AllayStormRuntime.INSTANCE.feedbackState().authority())PacketDistributor.sendToServer(packet);
                AllayStormRuntime.INSTANCE.completeAttack(packet);
            }completions.clear();
        }
        if(mc.level!=null&&AllayStormRuntime.INSTANCE.feedbackState().authority()&&flowTick!=mc.level.getGameTime()){
            var ids=new java.util.HashSet<Integer>(ready.keySet());ids.addAll(flowTimes.keySet());
            boolean sent=false;
            for(int id:ids){var w=AllayStormRuntime.INSTANCE.contactAuthority(id);if(w==null)continue;
                var flows=flowTimes.containsKey(id)?new com.iridium126.createmanaindustry.content.allaystorm.DomeGatherState(flowTimes.get(id)):com.iridium126.createmanaindustry.content.allaystorm.DomeGatherState.EMPTY;
                if(AllayStormRuntime.INSTANCE.domeGather(id)&&flowVersions.getOrDefault(id,-1)!=flows.revision())continue;
                var r=ready.get(id);var e=eligible.get(id);var c=counts.get(id);
                var plan=AllayStormRuntime.INSTANCE.wavePacket(id);
                boolean valid=r!=null&&e!=null&&plan!=null&&com.iridium126.createmanaindustry.content.allaystorm.StormGatherRules.valid(r,e,12288,AllayStormRuntime.INSTANCE.timeSec(),plan.startSec(),plan.assembleSec());
                boolean canGather=valid&&(!plan.helixAttack()||references.containsKey(id))&&c!=null&&r.cardinality()==c[0]&&e.cardinality()==c[1]&&(!gathered.containsKey(w)||mc.level.getGameTime()-gathered.get(w)>=5);
                if(canGather||flows.revision()>flowRevisions.getOrDefault(w,0)){
                    if(canGather)gathered.put(w,mc.level.getGameTime());flowRevisions.put(w,flows.revision());sent=true;
                    PacketDistributor.sendToServer(new com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackGatheredPacket(mc.level.dimension().location(),w.epoch(),id,canGather?r.toLongArray():new long[0],canGather?e.toLongArray():new long[0],flows,references.containsKey(id)?Math.round(references.get(id)[0]):0,references.containsKey(id)?references.get(id)[1]:0));
                }
            }if(sent)flowTick=mc.level.getGameTime();
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
    public void reset() {stormPosPending=false;stormPosIdentity=null;correctionClock.reset();lastSnapshotGameTime=Long.MIN_VALUE;waveContactPending=false;reported.clear();gathered.clear();flowRevisions.clear();flowTick=Long.MIN_VALUE;completions.clear();completionTick=Long.MIN_VALUE;}
    public void retireWaves() {
        waveContactPending=false;
        retireWaveReports();
    }
    /** Prune replaced identities without dropping other waves' in-flight GPU feedback. */
    public void retireWaveReports() {
        flowRevisions.keySet().removeIf(key->!key.equals(AllayStormRuntime.INSTANCE.contactAuthority(key.id())));
        gathered.keySet().removeIf(key->!key.equals(AllayStormRuntime.INSTANCE.contactAuthority(key.id())));
        reported.keySet().removeIf(key->!key.equals(AllayStormRuntime.INSTANCE.contactAuthority(key.id())));
    }
    private long attackSequence;
    public void reportHit(int member, Vec3 hit) {
        var identity = AllayStormRuntime.INSTANCE.identity();
        var level = Minecraft.getInstance().level;
        if (identity == null || level == null) return;
        PacketDistributor.sendToServer(new com.iridium126.createmanaindustry.content.allaystorm.network.ServerboundStormHitPacket(
                identity.dimension(), identity.epoch(), member, ++attackSequence, hit.x, hit.y, hit.z));
    }

}
