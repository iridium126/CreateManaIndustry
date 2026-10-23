package com.iridium126.createmanaindustry.content.allaystorm;

import java.util.BitSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.iridium126.createmanaindustry.CMIAttachments;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormCenterPacket;
import com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormDamagePacket;
import com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormPositionsPacket;
import com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormStatePacket;
import com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormWavePacket;
import com.iridium126.createmanaindustry.content.allaystorm.network.ServerboundStormHitPacket;
import com.iridium126.createmanaindustry.content.allaystorm.network.ServerboundStormPositionsPacket;
import com.iridium126.createmanaindustry.content.allaystorm.network.ServerboundStormWaveContactPacket;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server-side Allay Storm authority: activation gating, authority-client
 * election, hit processing, HP regen and position relaying. Per level (one
 * storm per dimension, matching the client engine's single-storm design).
 * <p>
 * The server never simulates member positions — it owns exactly what must be
 * durable and consistent (definition, death set, HP) and treats positions as
 * client-side GPU state:
 * <ul>
 *   <li><b>Activation</b> (per-player, hysteresis): only players within
 *       {@link #ACTIVATE_RANGE} of the anchor run the 131k-member simulation
 *       — the client engine has no simulation distance culling, so "who is
 *       active" IS the performance lever. Same-dimension distant players cost
 *       nothing. The ACTIVATE packet doubles as the join / dimension-change /
 *       re-enter-range resync (parameters + seed + dead bitmap).</li>
 *   <li><b>Authority</b>: the EARLIEST still-active client. It runs the
 *       position-snapshot readback at the configured Hz; the server relays
 *       its snapshots to the other active clients (coarse sanity check only
 *       — it cannot validate what it cannot compute; documented trust
 *       surface). Handoff on disconnect / range exit is seamless: the new
 *       authority's local state becomes the reference and the others ease
 *       toward it through the same soft correction layer.</li>
 *   <li><b>Hits</b>: reported by the attacking client (the server cannot
 *       detect hits against GPU state). Rate-limited per player, combat values are
 *       calculated from server weapon/cooldown state, dead members and remote players are
 *       ignored; death is decided HERE and only here, then broadcast with
 *       the death bit to every active player including the attacker.</li>
 *   <li><b>Regen</b>: vanilla 2 HP/s over the sparse damaged-alive table
 *       (iterating only damaged members; a full-HP boss costs nothing).</li>
 *   <li><b>Chase</b>: the anchor is not static — the center pursues the
 *       nearest player (horizontal distance) at {@link #CHASE_SPEED}, altitude
 *       pinned to {@code ServerConfig.stormChaseY} (a sky storm). The center
 *       integrates as DOUBLES in the per-level runtime (no block staircase);
 *       {@code data.anchor} stays in sync as its containing block for
 *       persistence, activation scanning and the hit reach check. Clients see
 *       the center through {@link ClientboundStormCenterPacket} snapshots
 *       every {@link #CENTER_INTERVAL_TICKS} (plus one on activation) and lerp
 *       the last two one interval behind — a stream-derivable, continuous
 *       center that is identical on every client, at half the packet rate the
 *       old per-step UPDATE broadcast produced. Being server-owned, the chased
 *       center is the shape-consistency anchor of the typhoon home points.</li>
 * </ul>
 */
@EventBusSubscriber(modid = CreateManaIndustry.MODID)
public final class AllayStormManager {

    /**
     * Activation radius in blocks: default client fade end (96 + 24 ramp =
     * 120) plus the maximum storm extent (radius 64 · 1.15 fringe ≈ 74).
     * Deliberately a server constant — the fade distance is a CLIENT config
     * the server cannot read.
     */
    public static final double ACTIVATE_RANGE = 196.0;
    /** Hysteresis margin past {@link #ACTIVATE_RANGE} before deactivating. */
    public static final double HYSTERESIS = 64.0;

    private static final int SCAN_INTERVAL_TICKS = 20;
    /** Chase speed toward the nearest player, blocks/s (horizontal only). */
    public static final double CHASE_SPEED = 2.0;
    /** Center-snapshot broadcast cadence in ticks (1 Hz; clients lerp one interval behind). */
    public static final int CENTER_INTERVAL_TICKS = 20;
    /** Coarse sanity bound for relayed positions (blocks from the anchor). */
    private static final float RELAY_BOUND = 320.0f;
    /** Coarse sanity bound for relayed velocities (b/s). */
    private static final float RELAY_VEL_BOUND = 32.0f;

    // ---- dive waves (the storm's offense; see docs/allay-storm-ai.md) --------

    /** Concurrent wave ceiling (the GPU wave-uniform array is sized to this). */
    public static final int MAX_WAVES = 4;
    /**
     * Dive-wave sword tier from the configured per-contact damage
     * ({@code ServerConfig.stormWaveDamage}): the squad's carried sword reads
     * as a damage gauge. Hardcoded thresholds in the
     * {@code EmitterSpec.HeldItem} id order (the same byte the wave packet
     * ships and the client's UV table indexes); a mid-storm config change
     * applies at the NEXT launch — in-flight waves keep the tier frozen with
     * their schedule, so no retroactive visual switch ever exists.
     */
    public static int swordTier(float damage) {
        if (damage >= 10.0f)
            return 6; // netherite
        if (damage >= 7.0f)
            return 5; // diamond (the 7.0 default)
        if (damage >= 5.0f)
            return 4; // iron
        if (damage >= 4.0f)
            return 3; // golden
        if (damage >= 3.0f)
            return 2; // stone
        return 1;     // wooden
    }

    /**
     * One launched dive wave. Everything on it is wire state (identical on
     * every client); the only server-only field is {@code reported} — the
     * per-member contact dedupe (a member lands its shot exactly once per
     * wave). Positions of the DIVERS never live here: the corridor defines
     * where they steer, the clients' GPU pools hold where they are.
     */
    private static final class Wave {
        final int id,seed; final UUID target; final long epoch,launchTick;
        final int[] members; final int perColumn,columns,tier; final BitSet membership=new BitSet();
        final BitSet completed=new BitSet();
        float offsetX,offsetZ;
        final AllayDome.Contacts reported=new AllayDome.Contacts();
        int kind=AllayDome.STARFALL, fallbackReason, checkedPetal=-1, currentPhase=1; int[] groupEnds;
        float centerX,centerY,centerZ,orientation,centerSec; int direction=1;
        int abortReason;
        AllayHelix.Flight helix; AllayHelix.Selection helixRoster; long lastObstacleTick;
        final it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap memberColumns=new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
        final float start,crownX,crownY,crownZ; float assemble,radius;
        DomeGatherState gatherFlows=DomeGatherState.EMPTY;
        float lock,end; float[] route,landings; boolean locked,gathered; long lastAimTick,lastRouteTick;
        Wave(int id,int seed,UUID target,long epoch,long tick,AllayStarfall.Selection selection,int tier,float now,float x,float y,float z,float radius,float[] route){
            this.id=id;this.seed=seed;this.target=target;this.epoch=epoch;launchTick=tick;
            members=selection.members();perColumn=selection.perColumn();columns=selection.columns();this.tier=tier;
            groupEnds=AllayDome.columns(members.length,perColumn);
            memberColumns.defaultReturnValue(-1);for(int i=0;i<members.length;i++){membership.set(members[i]);memberColumns.put(members[i],i/perColumn);}start=now+.2f;assemble=start+AllayStarfall.gatherSeconds(radius);
            crownX=x;crownY=y+8;crownZ=z;this.radius=AllayStarfall.crownRadius(radius,columns);this.route=route;
            lock=assemble+AllayStarfall.pathSeconds(route);end=lock+120;landings=new float[columns*4];lastAimTick=tick;lastRouteTick=tick;
        }
    }

    // ---- per-level runtime ---------------------------------------------------

    private static final Map<ResourceKey<Level>, Runtime> RUNTIMES = new HashMap<>();

    private static final class Runtime {
        final ServerLevel level;
        long orderCounter;
        int scanCounter = SCAN_INTERVAL_TICKS; // force a scan on the first tick
        // Continuous chased center (NaN = seed from data.anchor on first use);
        // chaseVX/VZ is the current velocity, broadcast for the client stream.
        double chaseX = Double.NaN;
        double chaseZ = Double.NaN;
        double chaseVX;
        double chaseVZ;
        int centerTimer;
        /** Fractional member accumulator of the growth clock (rate/20 per tick). */
        double growthAcc;
        final Map<UUID, Track> tracks = new HashMap<>();
        /** Live major attacks (<= MAX_WAVES, one per kind and target). */
        final List<Wave> waves = new ArrayList<>();
        final MajorAttackSchedule schedule=new MajorAttackSchedule();
        final BitSet waveReserved=new BitSet();
        final StarfallColumns columns=new StarfallColumns();
        final StarfallSnapshotSync columnSnapshots=new StarfallSnapshotSync();
        final StormAttackStats stats=new StormAttackStats();
        int waveIdCounter;

        Runtime(ServerLevel level) {
            this.level = level;
        }
    }

    private static final class Track {
        boolean active;
        boolean sky; long skyTick=Long.MIN_VALUE/2;
        boolean authority;
        long order;
        final StormMelee.Attempts melee = new StormMelee.Attempts();
        final StormStreamClock positions = new StormStreamClock();
    }

    private AllayStormManager() {
    }

    // ---- events ------------------------------------------------------------

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || !level.hasData(CMIAttachments.STORM_DATA.get()))
            return;
        tick(level);
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level)
            RUNTIMES.remove(level.dimension());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        RUNTIMES.clear();
    }

    // ---- tick ----------------------------------------------------------------

    private static void tick(ServerLevel level) {
        AllayStormData data = level.getData(CMIAttachments.STORM_DATA.get());
        Runtime rt = runtime(level);
        if (!data.active) {
            rt.columns.clear();rt.columnSnapshots.clear();rt.waves.clear();rt.schedule.reset();rt.waveReserved.clear();
            if (rt.tracks.values().stream().anyMatch(t -> t.active))
                deactivateAll(level, rt);
            return;
        }
        regen(level, data);
        chase(level, data);
        grow(level, rt, data);
        var columnPlayers=new ArrayList<ServerPlayer>();
        var eligible=new ArrayList<ServerPlayer>();
        for(var entry:rt.tracks.entrySet())if(entry.getValue().active){
            var p=level.getServer().getPlayerList().getPlayer(entry.getKey());
            if(p==null||p.serverLevel()!=level)continue;
            columnPlayers.add(p);
            if(!p.isAlive()||p.isSpectator())continue;
            var t=entry.getValue();
            if(level.getGameTime()-t.skyTick>=10){t.sky=level.canSeeSky(p.blockPosition());t.skyTick=level.getGameTime();}
            if(t.sky)eligible.add(p);
        }
        tickColumnSnapshots(level,rt,data,columnPlayers);
        tickWaves(level,rt,data,eligible);
        int first=rt.schedule.ready(level.getGameTime())?rt.schedule.first():0;
        for(int i=0;i<3;i++){int kind=(first+i)%3;
            if(kind==0)rt.columns.tick(level,data,rt.chaseX,rt.chaseZ,eligible,rt.waveReserved,()->nextAttackId(rt),packet->{if(!packet.abort())rt.columnSnapshots.created(packet.id(),columnPlayers.stream().map(ServerPlayer::getUUID).toList());for(var p:columnPlayers)PacketDistributor.sendToPlayer(p,packet);});
            else tryMajor(level,rt,data,eligible,kind==1?StormAttack.HELIX:StormAttack.DOME);
        }
        if (++rt.scanCounter >= SCAN_INTERVAL_TICKS) {
            rt.scanCounter = 0;
            scan(level, rt, data);
        }
    }

    /** Shared-clock seconds — the SAME domain every client derives: (gameTime mod 2^21)/20. */
    private static float clockSeconds(long gameTime) {
        return (gameTime & ((1 << 21) - 1)) / 20.0f;
    }

    /** Retires and advances live plans. Independent launch clocks run in tryMajor. */
    private static void tickWaves(ServerLevel level, Runtime rt, AllayStormData data, List<ServerPlayer> eligible) {
        if (rt.waves.isEmpty() && !data.active)
            return;
        long now = level.getGameTime();
        float nowSec = clockSeconds(now);

        // expire (deterministic on clients too — no packet needed) / abort (target gone)
        Iterator<Wave> it = rt.waves.iterator();
        while (it.hasNext()) {
            Wave w = it.next();
            ServerPlayer tp = level.getServer().getPlayerList().getPlayer(w.target);
            Track tt = rt.tracks.get(w.target);
            boolean invalid = tp == null || tp.serverLevel() != level
                    || tt == null || !tt.active || !tp.isAlive();
            if (w.completed.cardinality()==w.members.length || AllayStarfall.elapsed(nowSec,w.end)>0 || now-w.launchTick>12000) {
                releaseWave(rt,w);
                it.remove();

            } else if ((invalid && AllayDome.targetRequired(w.kind,w.locked)) || !data.active) {
                broadcastWave(level,rt,ClientboundStormWavePacket.abort(w.id,w.epoch,w.kind==StormAttack.HELIX?6:0));
                releaseWave(rt,w);
                it.remove();

            } else if(!(w.kind==StormAttack.HELIX?advanceHelix(level,rt,w,tp,now,nowSec):w.kind==AllayDome.DOME?advanceDome(level,rt,w,tp,now,nowSec):advanceStarfall(level,rt,w,tp,now,nowSec))) {
                if(w.abortReason==7)rt.stats.count(StormAttackStats.Reason.GATHER_TIMEOUT);broadcastWave(level,rt,ClientboundStormWavePacket.abort(w.id,w.epoch,w.abortReason));releaseWave(rt,w);it.remove();
            }
        }

    }
    private static void tryMajor(ServerLevel level,Runtime rt,AllayStormData data,List<ServerPlayer> eligible,int kind){
        double interval=kind==StormAttack.HELIX?ServerConfig.stormHelixInterval:ServerConfig.stormDomeInterval;
        if(!rt.schedule.due(kind,level.getGameTime(),interval))return;
        double chance=kind==StormAttack.HELIX?ServerConfig.stormHelixChance:ServerConfig.stormDomeChance;
        if(level.random.nextDouble()>=chance){rt.stats.count(StormAttackStats.Reason.DRAW_MISSED);return;}
        if(rt.waves.size()>=MAX_WAVES){rt.stats.count(StormAttackStats.Reason.CAPACITY);return;}
        int alive=data.aliveCount();
        if(alive<(kind==StormAttack.HELIX?16384:AllayDome.MIN_MEMBERS)){rt.stats.count(StormAttackStats.Reason.TOO_FEW);return;}
        var candidates=new ArrayList<>(eligible);candidates.sort(java.util.Comparator.comparing(ServerPlayer::getUUID));
        if(candidates.isEmpty())return;ServerPlayer target=null;int first=rt.schedule.target(kind,candidates.size());
        for(int i=0;i<candidates.size();i++){var p=candidates.get((first+i)%candidates.size());
            if(Math.hypot(p.getX()-rt.chaseX,p.getZ()-rt.chaseZ)>ServerConfig.stormWaveRange)continue;
            if(rt.waves.stream().anyMatch(w->w.kind==kind&&w.target.equals(p.getUUID())))continue;
            if(kind==StormAttack.DOME&&domeOverlapsBody(rt,data,p)){rt.stats.count(StormAttackStats.Reason.BODY_OVERLAP);continue;}
            target=p;break;
        }
        if(target==null)return;int seed=level.random.nextInt(1<<24);var track=rt.tracks.get(target.getUUID());
        if(kind==StormAttack.HELIX){
            var roster=AllayHelix.select(data.count,data.dead,rt.waveReserved,4096,data.stormSeed,seed);
            if(roster.members().length!=4096){rt.stats.count(StormAttackStats.Reason.RESERVED);return;}
            if(!launchHelix(level,rt,data,target,level.getGameTime(),seed,roster,track))rt.stats.count(StormAttackStats.Reason.RESOURCE);
        }else{
            int quota=AllayDome.MAX_MEMBERS;
            int[] roster=AllayDome.select(data.count,data.dead,rt.waveReserved,quota,seed);
            if(roster.length!=AllayDome.MAX_MEMBERS){rt.stats.count(StormAttackStats.Reason.RESERVED);return;}
            if(!launchDome(level,rt,data,target,level.getGameTime(),seed,roster,track))rt.stats.count(StormAttackStats.Reason.RESOURCE);
        }
    }

    private static int nextAttackId(Runtime rt){
        int id=++rt.waveIdCounter;if(id>=1<<24)rt.waveIdCounter=id=1;return id;
    }
    private static ClientboundStormWavePacket wavePacket(Wave w,int event,boolean roster,int targetId){
        return new ClientboundStormWavePacket(w.id,w.epoch,event,w.seed,targetId,w.tier,w.columns,w.perColumn,w.start,w.assemble,w.lock,w.end,w.crownX,w.crownY,w.crownZ,w.radius,roster?w.members:new int[0],w.route.clone(),w.landings.clone(),w.kind,w.currentPhase,w.groupEnds.clone(),w.centerX,w.centerY,w.centerZ,w.orientation,w.direction,w.centerSec,w.fallbackReason,w.helix,w.completed.toLongArray(),w.gatherFlows);
    }


    private static boolean launchHelix(ServerLevel level,Runtime rt,AllayStormData data,ServerPlayer target,long tick,int seed,AllayHelix.Selection roster,Track track){
        if(!HelixTemplate.available())return false;
        double dx=target.getX()-rt.chaseX,dz=target.getZ()-rt.chaseZ,n=Math.hypot(dx,dz);
        if(n<1){double a=(seed&65535)*Math.PI*2/65536;dx=Math.cos(a);dz=Math.sin(a);n=1;}
        float offset=(float)AllayStormData.vortexRadius(data.count),ox=(float)(dx/n*offset),oz=(float)(dz/n*offset);
        Vec3 from=new Vec3(rt.chaseX,ServerConfig.stormChaseY,rt.chaseZ),center=from.add(ox,-8,oz),axis=target.getBoundingBox().getCenter().subtract(center).normalize();
        if(axis.lengthSqr()<.5)axis=new Vec3(-dx/n,-1,-dz/n).normalize();
        int id=nextAttackId(rt);
        Wave w=new Wave(id,seed,target.getUUID(),StormIdentity.of(level,data).epoch(),tick,new AllayStarfall.Selection(2,2048,roster.members()),5,clockSeconds(tick),(float)from.x,(float)from.y-8,(float)from.z,AllayStormData.vortexRadius(data.count),new float[]{(float)from.x,(float)from.y,(float)from.z,(float)center.x,(float)center.y,(float)center.z});
        w.kind=StormAttack.HELIX;w.helixRoster=roster;w.radius=32;w.groupEnds=roster.ends();w.offsetX=ox;w.offsetZ=oz;
        w.centerX=(float)center.x;w.centerY=(float)center.y;w.centerZ=(float)center.z;w.centerSec=clockSeconds(tick);
        // Account for 2m/s body motion and worst-case rotating outer-envelope velocity.
        w.assemble=w.start+Math.max(4,(float)((offset+1.15*AllayStormData.vortexRadius(data.count)+46+8)/(12-2-Math.sqrt(2048)*Math.toRadians(8))));
        w.lock=w.assemble;w.helix=AllayHelix.linearFlight(roster,center,axis,Math.max(0,target.getBoundingBox().getCenter().subtract(center).dot(axis))+12,w.assemble);
        w.end=AllayHelix.deadline(w.helix);
        w.memberColumns.clear();for(int rank=0;rank<w.members.length;rank++)w.memberColumns.put(w.members[rank],rank/2048);
        for(int member:w.members)rt.waveReserved.set(member);rt.waves.add(w);
        rt.stats.count(StormAttackStats.Reason.LAUNCHED);broadcastWave(level,rt,wavePacket(w,AllayStarfall.START,true,target.getId()));return true;
    }
    private static boolean advanceHelix(ServerLevel level,Runtime rt,Wave w,ServerPlayer target,long tick,float clock){
        float now=w.start+AllayStarfall.elapsed(clock,w.start);
        if(!w.locked){
            if(target==null){w.abortReason=6;return false;}if(now>=w.assemble&&!w.gathered){w.abortReason=7;return false;}boolean lock=w.gathered;
            if(lock||tick-w.lastAimTick>=5){
                double seconds=(tick-w.lastAimTick)/20.;w.lastAimTick=tick;
                Vec3 center=new Vec3(rt.chaseX+w.offsetX,ServerConfig.stormChaseY-8,rt.chaseZ+w.offsetZ);
                Vec3 aim=target.getBoundingBox().getCenter().subtract(center),axis=AllayHelix.turn(AllayHelix.axis(w.helix),aim,Math.toRadians(8)*seconds);
                w.centerX=(float)center.x;w.centerY=(float)center.y;w.centerZ=(float)center.z;w.centerSec=now;
                float launch=lock?now:w.assemble;
                w.helix=AllayHelix.linearFlight(w.helixRoster,center,axis,Math.max(-32,aim.dot(axis))+12,launch);
                if(lock){w.assemble=now;w.lock=now;w.locked=true;w.currentPhase=4;}else w.currentPhase=1;
                w.end=AllayHelix.deadline(w.helix);
                broadcastWave(level,rt,wavePacket(w,lock?AllayStarfall.LOCK:AllayStarfall.UPDATE,false,target.getId()));
            }
        }
        w.currentPhase=w.locked?4:1;return true;
    }

    private static boolean domeOverlapsBody(Runtime rt,AllayStormData data,ServerPlayer target){
        double horizontal=Math.hypot((float)target.getX()-(float)rt.chaseX,(float)target.getZ()-(float)rt.chaseZ),vertical=(float)target.getBoundingBox().getCenter().y-ServerConfig.stormChaseY;
        return AllayDome.overlapsStormBody(horizontal,vertical,AllayStormData.vortexRadius(data.count));
    }
    private static boolean launchDome(ServerLevel level,Runtime rt,AllayStormData data,ServerPlayer target,long tick,int seed,int[] roster,Track track){
        if(domeOverlapsBody(rt,data,target))return false;
        if(!DomeTemplate.available())return false;
        float x=(float)target.getX(),z=(float)target.getZ(),y=(float)target.getBoundingBox().getCenter().y;
        float now=clockSeconds(tick),radius=32,orientation=(AllayStarfall.mix(seed)&65535)*(float)(Math.PI*2/65536);
        Vec3 from=new Vec3(rt.chaseX,ServerConfig.stormChaseY,rt.chaseZ);
        Wave w=new Wave(nextAttackId(rt),seed,target.getUUID(),StormIdentity.of(level,data).epoch(),tick,
            new AllayStarfall.Selection(1,AllayDome.MAX_MEMBERS,roster),5,now,(float)from.x,(float)from.y-8,(float)from.z,AllayStormData.vortexRadius(data.count),
            new float[]{(float)from.x,(float)from.y,(float)from.z,x,y,z});
        w.kind=StormAttack.DOME;w.radius=radius;w.groupEnds=new int[]{AllayDome.MAX_MEMBERS};w.orientation=orientation;
        w.centerX=x;w.centerY=y;w.centerZ=z;w.centerSec=now;
        w.assemble=w.start+AllayDome.gatherSeconds(from.distanceTo(new Vec3(x,y,z)),AllayStormData.vortexRadius(data.count));
        w.lock=w.assemble;w.end=w.assemble+60.25f;
        for(int rank=0;rank<roster.length;rank++){w.memberColumns.put(roster[rank],0);rt.waveReserved.set(roster[rank]);}
        rt.waves.add(w);rt.stats.count(StormAttackStats.Reason.LAUNCHED);
        broadcastWave(level,rt,wavePacket(w,AllayStarfall.START,true,target.getId()));return true;
    }
    private static int ground(ServerLevel level,double x,double z){
        return level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,(int)Math.floor(x),(int)Math.floor(z));
    }
    private static boolean advanceDome(ServerLevel level,Runtime rt,Wave w,ServerPlayer target,long tick,float clock){
        float now=w.start+AllayStarfall.elapsed(clock,w.start);
        if(!w.locked){
            if(DomeGatherRules.elapsedTicks(clock,w.start)>DomeGatherRules.durationTicks(w.start,w.assemble)&&!w.gathered){w.abortReason=7;return false;}
            if(!w.gathered)return true;
            w.assemble=now;w.lock=now;w.locked=true;w.currentPhase=4;w.end=now+60.25f;
            broadcastWave(level,rt,wavePacket(w,AllayStarfall.LOCK,false,target==null?-1:target.getId()));
        }
        return true;
    }
    private static float[] starfallRoute(ServerLevel level,Vec3 from,Vec3 to){double[] points=AllayStormWaves.buildCorridor(level,from,to);float[] route=new float[points.length];for(int i=0;i<points.length;i++)route[i]=(float)points[i];return route;}
    private static void releaseWave(Runtime rt,Wave wave){for(int rank=0;rank<wave.members.length;rank++)if(!wave.completed.get(rank))rt.waveReserved.clear(wave.members[rank]);}
    private static boolean safeLanding(ServerLevel level,double x,double feetY,double z){
        int bx=(int)Math.floor(x),bz=(int)Math.floor(z);
        if(!level.canSeeSky(BlockPos.containing(x,feetY,z)))return false;
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)if(level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,bx+dx,bz+dz)>feetY+1)return false;
        return true;
    }
    private static boolean advanceStarfall(ServerLevel level,Runtime rt,Wave w,ServerPlayer target,long tick,float now){
        if(w.locked){
            float sample=w.start+AllayStarfall.elapsed(now,w.start),last=w.lock;w.currentPhase=3;
            for(int c=0;c<w.columns;c++){float begin=w.lock+AllayStarfall.WARNING+c*AllayStarfall.COLUMN_GAP,finish=begin+w.landings[c*4+3];last=Math.max(last,finish);if(sample>=begin&&sample<finish)w.currentPhase=4;}
            if(sample>=last)w.currentPhase=5;return true;
        }
        if(!level.canSeeSky(target.blockPosition()))return false;
        now=w.start+AllayStarfall.elapsed(now,w.start);w.currentPhase=now<w.assemble?1:2;
        if(tick-w.lastAimTick>=10){
            w.lastAimTick=tick;Vec3 aim=target.position().add(0,10,0);int end=w.route.length-3;
            double dx=aim.x-w.route[end],dz=aim.z-w.route[end+2];
            if(dx*dx+dz*dz>64&&tick-w.lastRouteTick>=40){
                w.route=starfallRoute(level,new Vec3(w.crownX,w.crownY,w.crownZ),aim);w.lastRouteTick=tick;
                w.lock=Math.max(w.lock,now+AllayStarfall.pathSeconds(w.route));w.end=w.lock+120;
            }else {w.route[end]=(float)aim.x;w.route[end+1]=(float)aim.y;w.route[end+2]=(float)aim.z;}
            broadcastWave(level,rt,wavePacket(w,AllayStarfall.UPDATE,false,target.getId()));
        }
        if(AllayStarfall.elapsed(now,w.lock)<0)return true;
        double baseX=target.getX(),baseZ=target.getZ();int baseY=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,(int)Math.floor(baseX),(int)Math.floor(baseZ));
        if(baseY>target.getY()+2||!safeLanding(level,baseX,baseY,baseZ))return false;
        float maximumEnd=now;
        for(int c=0;c<w.columns;c++){
            double x=baseX+AllayStarfall.offsetX(c,w.seed),z=baseZ+AllayStarfall.offsetZ(c,w.seed);
            int y=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,(int)Math.floor(x),(int)Math.floor(z));
            if(Math.abs(y-baseY)>3||!safeLanding(level,x,y,z)){x=baseX;y=baseY;z=baseZ;}
            int b=c*4;w.landings[b]=(float)x;w.landings[b+1]=y+.5f;w.landings[b+2]=(float)z;
            w.landings[b+3]=Math.max(3.5f,(w.route[w.route.length-2]-y)/AllayStarfall.SPEED+2);
            maximumEnd=Math.max(maximumEnd,now+AllayStarfall.WARNING+c*AllayStarfall.COLUMN_GAP+w.landings[b+3]);
        }
        w.currentPhase=3;w.lock=now;w.end=maximumEnd+AllayStarfall.PETALS+AllayStarfall.pathSeconds(w.route)+w.radius/3+3;w.locked=true;
        broadcastWave(level,rt,wavePacket(w,AllayStarfall.LOCK,false,target.getId()));return true;
    }

    /** Broadcasts a wave event to every active-track player (all must render the same attack). */
    private static void broadcastWave(ServerLevel level, Runtime rt, ClientboundStormWavePacket packet) {
        for (var entry : rt.tracks.entrySet()) {
            if (!entry.getValue().active)
                continue;
            ServerPlayer p = rt.level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (p != null && p.serverLevel() == rt.level)
                PacketDistributor.sendToPlayer(p, packet);
        }
    }

    /**
     * Growth clock: generates {@code ServerConfig.stormGrowthPerSecond} new
     * member indices per second (fractional accumulator over the level tick)
     * while the population is below {@code ServerConfig.stormMaxCount}. Runs
     * regardless of player activity — the storm is a persistent world boss
     * that keeps assembling while unobserved. The clock counts GENERATED
     * members (kills never slow it; dead indices never regenerate), so a
     * storm's total lifetime budget is exactly the config ceiling. New
     * indices reach clients through the 1 Hz center packet; clients spawn
     * them on the spawn ring and fly them to their storm positions. A rate
     * of 0 disables growth. The radius is not server state — every client
     * derives it from the synced population ({@link AllayStormData#vortexRadius}).
     */
    private static void grow(ServerLevel level, Runtime rt, AllayStormData data) {
        double rate = data.growthPerSecond;
        int max = data.finalCount;
        if (rate <= 0.0 || data.count >= max)
            return;
        rt.growthAcc += rate / 20.0;
        int n = (int) rt.growthAcc;
        if (n <= 0)
            return;
        rt.growthAcc -= n;
        data.count = Math.min(max, data.count + n);
        level.setData(CMIAttachments.STORM_DATA.get(), data);
    }

    /**
     * Integrates the center continuously toward the nearest player (horizontal
     * distance) at {@link #CHASE_SPEED}; no players (or the player already
     * overhead) means hover. {@code data.anchor} follows as the containing
     * block so persistence, activation scanning and the hit reach check keep
     * working unchanged. Center snapshots fly every
     * {@link #CENTER_INTERVAL_TICKS}; clients lerp the last two one interval
     * behind, so a 1 Hz stream renders as continuous motion and every client
     * derives the same center from the same packets.
     */
    private static void chase(ServerLevel level, AllayStormData data) {
        Runtime rt = runtime(level);
        if (Double.isNaN(rt.chaseX)) {
            rt.chaseX = data.anchor.getX() + 0.5;
            rt.chaseZ = data.anchor.getZ() + 0.5;
        }
        ServerPlayer target = null;
        double best = Double.MAX_VALUE;
        for (ServerPlayer p : level.players()) {
            double dx = p.getX() - rt.chaseX;
            double dz = p.getZ() - rt.chaseZ;
            double d2 = dx * dx + dz * dz;
            if (d2 < best) {
                best = d2;
                target = p;
            }
        }
        double vx = 0.0;
        double vz = 0.0;
        if (target != null) {
            double dx = target.getX() - rt.chaseX;
            double dz = target.getZ() - rt.chaseZ;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist >= 1.0) {
                vx = dx / dist * CHASE_SPEED;
                vz = dz / dist * CHASE_SPEED;
            }
        }
        rt.chaseVX = vx;
        rt.chaseVZ = vz;
        // the anchor follows as the CONTAINING BLOCK of the chased center, at
        // the CONFIGURED altitude — synced every tick, not only while moving,
        // so a mid-flight config change to stormChaseY self-heals the anchor
        // Y (the hit/contact reach checks reconstruct against this anchor; a
        // stale Y would reject every report until the storm happened to move)
        BlockPos containing = BlockPos.containing(rt.chaseX, ServerConfig.stormChaseY, rt.chaseZ);
        if (!containing.equals(data.anchor)) {
            data.anchor = containing;
            level.setData(CMIAttachments.STORM_DATA.get(), data);
        }
        if (vx != 0.0 || vz != 0.0) {
            rt.chaseX += vx / 20.0;
            rt.chaseZ += vz / 20.0;
        }
        if (++rt.centerTimer >= CENTER_INTERVAL_TICKS) {
            rt.centerTimer = 0;
            sendCenter(level, rt);
        }
    }

    /** Broadcasts the continuous center + chase velocity + growth state to every active client. */
    private static void sendCenter(ServerLevel level, Runtime rt) {
        AllayStormData data = rt.level.getData(CMIAttachments.STORM_DATA.get());
        ClientboundStormCenterPacket packet = new ClientboundStormCenterPacket(
                level.dimension().location(), StormIdentity.of(level, data).epoch(),
                (float) rt.chaseX, ServerConfig.stormChaseY, (float) rt.chaseZ,
                (float) rt.chaseVX, (float) rt.chaseVZ, data.count,
                rt.level.getGameTime());
        for (var entry : rt.tracks.entrySet()) {
            if (!entry.getValue().active)
                continue;
            ServerPlayer p = rt.level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (p != null && p.serverLevel() == rt.level)
                PacketDistributor.sendToPlayer(p, packet);
        }
    }

    /** Vanilla Allay regen: heal(1.0) per 10 ticks = 2 HP/s, only damaged members. */
    private static void regen(ServerLevel level, AllayStormData data) {
        if (data.hp.isEmpty())
            return;
        boolean changed = false;
        var it = data.hp.int2FloatEntrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            float hp = e.getFloatValue() + 0.1f;
            if (hp >= AllayStormData.MAX_HP) {
                it.remove();
                changed = true;
            } else if (e.getFloatValue() != hp) {
                e.setValue(hp);
                changed = true;
            }
        }
        if (changed)
            level.setData(CMIAttachments.STORM_DATA.get(), data);
    }

    /** Re-evaluates every player's activation state and the authority assignment. */
    private static void scan(ServerLevel level, Runtime rt, AllayStormData data) {
        // players that left the level (dimension change, logout) drop their
        // track. The player lookup is GLOBAL across dimensions, so "still
        // online" is NOT enough: a cross-dimension player must be detected
        // via their current ServerLevel, or a stale track would pin authority.
        // Client level-change events clear the old local storm; no old-level
        // cleanup is sent to the new world.
        for (var it = rt.tracks.entrySet().iterator(); it.hasNext();) {
            var entry = it.next();
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null || player.serverLevel() != level) {
                Track t = entry.getValue();
                if (t.active) {
                    t.active = false;t.melee.release();
                    t.authority = false;
                }
                it.remove();
            }
        }
        if (!data.active) {
            deactivateAll(level, rt);
            return;
        }
        double ax = data.anchor.getX() + 0.5;
        double ay = data.anchor.getY();
        double az = data.anchor.getZ() + 0.5;
        double activateSq = ACTIVATE_RANGE * ACTIVATE_RANGE;
        double deactivateSq = (ACTIVATE_RANGE + HYSTERESIS) * (ACTIVATE_RANGE + HYSTERESIS);
        for (ServerPlayer player : level.players()) {
            Track t = rt.tracks.get(player.getUUID());
            double distSq = player.distanceToSqr(ax, ay, az);
            double domeSq=Double.POSITIVE_INFINITY;
            for(Wave w:rt.waves)if(w.kind==AllayDome.DOME)domeSq=Math.min(domeSq,
                    AllayDome.distanceSquared(player.getX(),player.getY(),player.getZ(),w.centerX,w.centerY,w.centerZ,w.radius));
            for(Wave w:rt.waves)if(w.kind==StormAttack.HELIX&&w.locked)domeSq=Math.min(domeSq,
                    Math.pow(Math.max(0,Math.sqrt(AllayHelix.distanceSquared(w.helix,player.getX(),player.getY(),player.getZ()))-w.radius*1.6),2));
            if (t == null || !t.active) {
                if (distSq <= activateSq || domeSq<=120*120)
                    activate(level, rt, data, player);
            } else if (distSq > deactivateSq && domeSq>(120+HYSTERESIS)*(120+HYSTERESIS)) {
                deactivate(level, rt, data, player);
            }
        }
        recomputeAuthority(rt, data);
    }

    private static void activate(ServerLevel level, Runtime rt, AllayStormData data, ServerPlayer player) {
        Track t = rt.tracks.computeIfAbsent(player.getUUID(), k -> new Track());
        t.active = true;
        t.order = rt.orderCounter++;
        // the wave cooldown counts from activation: a freshly joined player
        // gets one assemble window before the storm's first strike at them
        PacketDistributor.sendToPlayer(player, ClientboundStormStatePacket.ACTIVATE(
                StormIdentity.of(level, data), data.anchor, data.count, data.stormSeed,
                t.authority, ServerConfig.stormCorrectionHz, data.dead.toByteArray(),
                data.creationRadius, data.finalRadius, (float) data.growthPerSecond,
                data.createdAtGameTime));
        for(var packet:rt.columns.snapshots()){rt.columnSnapshots.observe(packet.id(),player.getUUID());PacketDistributor.sendToPlayer(player,packet.resuming());PacketDistributor.sendToPlayer(player,new com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackCompletedPacket(packet.dimension(),packet.epoch(),packet.id(),rt.columns.completed(packet.id())));}
        // seed the joining client's center stream immediately — without this
        // its interpolation would sit at the (block-quantized) ACTIVATE anchor
        // for up to one interval while the typhoon drifts on
        sendCenter(level, rt);
        for(Wave w:rt.waves){ServerPlayer target=level.getServer().getPlayerList().getPlayer(w.target);if(target!=null||w.kind==StormAttack.DOME||StormAttack.regional(w.kind)&&w.locked){PacketDistributor.sendToPlayer(player,wavePacket(w,AllayStarfall.START,true,target==null?-1:target.getId()));PacketDistributor.sendToPlayer(player,new com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackCompletedPacket(level.dimension().location(),w.epoch,w.id,w.completed.toLongArray()));}}
    }

    private static void deactivate(ServerLevel level, Runtime rt, AllayStormData data, ServerPlayer player) {
        Track t = rt.tracks.get(player.getUUID());
        if (t == null || !t.active)
            return;
        t.active = false;t.melee.release();
        t.authority = false;
        PacketDistributor.sendToPlayer(player, ClientboundStormStatePacket.DEACTIVATE(StormIdentity.of(level, data)));
    }

    private static void deactivateAll(ServerLevel level, Runtime rt) {
        for (var entry : rt.tracks.entrySet()) {
            Track t = entry.getValue();
            if (!t.active)
                continue;
            t.active = false;t.melee.release();
            t.authority = false;
            ServerPlayer player = rt.level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player != null && player.serverLevel() == rt.level)
                PacketDistributor.sendToPlayer(player, ClientboundStormStatePacket.DEACTIVATE(
                        StormIdentity.of(level, level.getData(CMIAttachments.STORM_DATA.get()))));
        }
    }

    /** Authority = earliest-activated still-active client; UPDATE packets flip the bit on change. */
    private static void recomputeAuthority(Runtime rt, AllayStormData data) {
        UUID earliest = null;
        long best = Long.MAX_VALUE;
        for (var entry : rt.tracks.entrySet()) {
            Track t = entry.getValue();
            if (t.active && t.order < best) {
                best = t.order;
                earliest = entry.getKey();
            }
        }
        for (var entry : rt.tracks.entrySet()) {
            Track t = entry.getValue();
            if (!t.active) {
                t.authority = false;
                continue;
            }
            boolean should = entry.getKey().equals(earliest);
            if (t.authority != should) {
                t.authority = should;
                ServerPlayer player = rt.level.getServer().getPlayerList().getPlayer(entry.getKey());
                if (player != null && player.serverLevel() == rt.level)
                    PacketDistributor.sendToPlayer(player, ClientboundStormStatePacket.UPDATE(
                            StormIdentity.of(rt.level, data), data.anchor, data.count, data.stormSeed,
                            should, ServerConfig.stormCorrectionHz,
                            data.creationRadius, data.finalRadius, (float) data.growthPerSecond,
                            data.createdAtGameTime));
            }
        }
    }

    // ---- packet handling ------------------------------------------------------

    /** Consumes a GPU candidate; all combat values come from the server. */
    public static void handleHit(ServerboundStormHitPacket packet, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) return;
        ServerLevel level = player.serverLevel();
        if (!level.hasData(CMIAttachments.STORM_DATA.get())) return;
        AllayStormData data = level.getData(CMIAttachments.STORM_DATA.get());
        Runtime rt = runtime(level);
        Track track = rt.tracks.get(player.getUUID());
        if (track == null || !track.active) return;
        var result = StormMelee.attack(player, data, StormIdentity.of(level, data), packet, track.melee);
        if (result == null) return;
        level.setData(CMIAttachments.STORM_DATA.get(), data);
        Vec3 hit = packet.contact();
        level.playSound(null, hit.x, hit.y, hit.z, result.died() ? SoundEvents.ALLAY_DEATH : SoundEvents.ALLAY_HURT,
                SoundSource.NEUTRAL, 1, 1);
        level.playSound(null, player.getX(), player.getY(), player.getZ(), result.attackSound(), SoundSource.PLAYERS, 1, 1);
        if (result.kbX() != 0 || result.kbZ() != 0)
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(player));
        var out = new ClientboundStormDamagePacket(packet.memberIdx(), result.damage(), result.kbX(), result.kbZ(),
                result.light(), result.died(), player.getId(), result.crit(), result.magic(), result.hearts(),
                hit.x, hit.y, hit.z, packet.dimension(), packet.epoch());
        for (var entry : rt.tracks.entrySet()) {
            if (!entry.getValue().active) continue;
            ServerPlayer observer = level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (observer != null && observer.serverLevel() == level) PacketDistributor.sendToPlayer(observer, out);
        }
        if(result.died())retireDeadMember(level,rt,packet.memberIdx());
    }

    private static void broadcastCompletion(ServerLevel level,Runtime rt,com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackCompletedPacket packet){
        for(var entry:rt.tracks.entrySet())if(entry.getValue().active){var observer=level.getServer().getPlayerList().getPlayer(entry.getKey());if(observer!=null&&observer.serverLevel()==level)PacketDistributor.sendToPlayer(observer,packet);}
    }
    private static void retireDeadMember(ServerLevel level,Runtime rt,int member){
        for(var w:rt.waves)if(StormAttack.regional(w.kind)&&w.membership.get(member))for(int rank=0;rank<w.members.length;rank++)if(w.members[rank]==member&&!w.completed.get(rank)){
            w.completed.set(rank);w.membership.clear(member);rt.waveReserved.clear(member);broadcastCompletion(level,rt,new com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackCompletedPacket(level.dimension().location(),w.epoch,w.id,w.completed.toLongArray()));break;
        }
        rt.columns.deadMember(member,rt.waveReserved,packet->broadcastCompletion(level,rt,packet));
    }

    /** Relays an authority client's position snapshot to the other active clients. */
    public static void relayPositions(ServerboundStormPositionsPacket packet, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player))
            return;
        ServerLevel level = player.serverLevel();
        if (!level.hasData(CMIAttachments.STORM_DATA.get()))
            return;
        AllayStormData data = level.getData(CMIAttachments.STORM_DATA.get());
        if (!data.active)
            return;
        Runtime rt = runtime(level);
        Track t = rt.tracks.get(player.getUUID());
        if (t == null || !t.active || !t.authority)
            return; // only the authority feeds the stream
        // coarse sanity per entry. Stride-8 layout: (memberIdx, pad,
        // relPos.xyz, vel.xyz) — the positions are offsets 2..4 (the old
        // check started at the always-zero pad slot and never looked at z),
        // and memberIdx must be a plausible member of the CURRENT population
        // (a snapshot from a just-replaced generation with a larger count
        // fails here and drops — fine, the soft layer self-heals). Any
        // violation discards the whole snapshot.
        float[] e = packet.entries();
        int stride = ServerboundStormPositionsPacket.STRIDE;
        // NaN-safe bound form ("!(x <= bound)" also rejects NaN and both
        // infinities). The wire quantization bounds every value today
        // (positions short → ±2048 blocks, velocities short → ±32 b/s), but this
        // check IS the trust boundary: it stays self-sufficient even if the
        // codec ever widens, and the velocity components were previously
        // unchecked outright.
        for (int i = 0; i + stride - 1 < e.length; i += stride) {
            if (!(e[i] >= 0f && e[i] < data.count)
                    || !(Math.abs(e[i + 2]) <= RELAY_BOUND) || !(Math.abs(e[i + 3]) <= RELAY_BOUND)
                    || !(Math.abs(e[i + 4]) <= RELAY_BOUND)
                    || !(Math.abs(e[i + 5]) <= RELAY_VEL_BOUND) || !(Math.abs(e[i + 6]) <= RELAY_VEL_BOUND)
                    || !(Math.abs(e[i + 7]) <= RELAY_VEL_BOUND))
                return;
        }
        if (!t.positions.accept(packet.identity(), StormIdentity.of(level, data), level.dimension().location(), packet.gameTime())) return;
        ClientboundStormPositionsPacket relay = new ClientboundStormPositionsPacket(packet.dimension(), packet.epoch(), packet.gameTime(), e);
        for (var entry : rt.tracks.entrySet()) {
            Track tr = entry.getValue();
            if (!tr.active || entry.getKey().equals(player.getUUID()))
                continue;
            ServerPlayer p = rt.level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (p != null && p.serverLevel() == rt.level)
                PacketDistributor.sendToPlayer(p, relay);
        }
    }

    // ---- dive-wave contact reports ---------------------------------------------

    /**
     * Consumes {@link ServerboundStormWaveContactPacket}: the target player's
     * client self-reports a diving member's contact; the server decides the
     * damage. Validation chain (docs/allay-storm-ai.md §7): storm and wave
     * live, the sender IS the wave's target, the report sits inside the wave
     * window (shared-clock seconds, same domain the client phased its GPU
     * with), the member index is a plausible live member, the SQUAD
     * MEMBERSHIP re-derives from the float-exact shared hash chains (the GPU
     * selection and this test cannot disagree — no state was ever synced for
     * it), the per-member-per-wave dedupe (one shot per diver), and a
     * plausibility reach check (the same +8 margin as the melee reports,
     * absorbing the chased-center-vs-anchor skew). Damage routes through
     * {@code player.hurt} with the data-driven {@code storm_peck} type, so
     * vanilla i-frames/armor/absorption/totems and the hurt sound all apply
     * with zero extra code — concurrent divers cannot burst the i-frame gate.
     * <p>
     * The source is POSITION-ONLY ({@code new DamageSource(holder, contact)}):
     * {@code isDamageSourceBlocked} runs its horizontal direction check off
     * {@code getSourcePosition()} and fails unconditionally when it is null,
     * so the old entity-less source made shields unable to block a diver.
     * With the contact point as the source position, blocking, shield
     * durability, the {@code DAMAGE_BLOCKED_BY_SHIELD} stat, the SHIELD_BLOCK
     * sound broadcast and the {@code LivingShieldBlockEvent} all ride the
     * vanilla {@code hurt} pipeline for free — and its internal 0.4 knockback
     * derives from the SAME source position (the vanilla melee shape), which
     * is why the old custom {@code knockback(0.5)} is gone (it would
     * double-push now). Near-vertical centered dives keep vanilla's overhead
     * edge case: the horizontal-only check yields NaN → unblocked, by design.
     */
    public static void handleWaveContact(ServerboundStormWaveContactPacket packet, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player))
            return;
        ServerLevel level = player.serverLevel();
        if (!level.hasData(CMIAttachments.STORM_DATA.get()))
            return;
        AllayStormData data = level.getData(CMIAttachments.STORM_DATA.get());
        if (!data.active)
            return;
        Runtime rt = runtime(level);
        Track t = rt.tracks.get(player.getUUID());
        if (t == null || !t.active)
            return;
        if(!packet.dimension().equals(level.dimension().location()))return;
        if(rt.columns.contact(player,data,StormIdentity.of(level,data),packet.waveId(),packet.epoch(),packet.sampleSec(),packet.memberIdx(),new Vec3(packet.relX(),packet.relY(),packet.relZ())))return;
        Wave w = null;
        for (Wave cand : rt.waves)
            if (cand.id == packet.waveId()) {
                w = cand;
                break;
            }
        if (w == null || (w.kind==AllayDome.STARFALL && !player.getUUID().equals(w.target)))
            return;
        int idx=packet.memberIdx();
        if(packet.epoch()!=w.epoch||idx<0||idx>=data.count||data.dead.get(idx)||!w.membership.get(idx))return;
        Vec3 contact=new Vec3(packet.relX(),packet.relY(),packet.relZ());
        if(!Double.isFinite(contact.x)||!Double.isFinite(contact.y)||!Double.isFinite(contact.z)||!w.reported.add(player.getUUID(),idx))return;

        // position-only source: the contact point feeds isDamageSourceBlocked's
        // direction check (null source position = shield can never block) and
        // hurt's own 0.4 positional knockback — no entity, no custom push
        DamageSource source = new DamageSource(
                level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                        .getHolderOrThrow(CMIDamageTypes.STORM_PECK),
                contact);
        player.hurt(source, (float) ServerConfig.stormWaveDamage);
    }

    public static void gatheredAttack(com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackGatheredPacket packet,IPayloadContext ctx){
        if(!(ctx.player() instanceof ServerPlayer player))return;var level=player.serverLevel();var data=level.getData(CMIAttachments.STORM_DATA.get());var rt=runtime(level);var track=rt.tracks.get(player.getUUID());
        if(!data.active||track==null||!track.active||!track.authority||!StormIdentity.of(level,data).equals(new StormIdentity(packet.dimension(),packet.epoch())))return;
        for(var w:rt.waves)if(w.id==packet.id()&&StormAttack.regional(w.kind)&&!w.locked&&!w.gathered){
            float now=clockSeconds(level.getGameTime());
            if(w.kind==AllayDome.DOME?DomeGatherRules.elapsedTicks(now,w.start)>DomeGatherRules.durationTicks(w.start,w.assemble):AllayStarfall.elapsed(now,w.assemble)>=0)return;
            if(w.kind==AllayDome.DOME){
                var flows=packet.flows();
                // START is scheduled slightly ahead; trust the bounded GPU phase,
                // retaining the existing identity and gather-deadline checks.
                if(!flows.extendsState(w.gatherFlows))return;
                if(flows.revision()!=w.gatherFlows.revision()){w.gatherFlows=flows;broadcastWave(level,rt,wavePacket(w,AllayStarfall.UPDATE,false,-1));}
            }else if(packet.flows().revision()!=0)return;
            var ready=BitSet.valueOf(packet.ready());var eligible=BitSet.valueOf(packet.eligible());
            if(ready.length()>w.members.length||eligible.length()>w.members.length)return;
            for(int rank=0;rank<w.members.length;rank++)if(data.dead.get(w.members[rank])||w.completed.get(rank)){ready.clear(rank);eligible.clear(rank);}
            if(w.kind==AllayDome.DOME?!DomeGatherRules.valid(ready,eligible,w.members.length,now,w.start,w.assemble):!StormGatherRules.valid(ready,eligible,w.members.length))return;
            for(int rank=0;rank<w.members.length;rank++)if(!ready.get(rank)&&!w.completed.get(rank)){w.completed.set(rank);w.membership.clear(w.members[rank]);rt.waveReserved.clear(w.members[rank]);}
            w.gathered=true;return;
        }
    }

    public static void completeAttack(com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackCompletedPacket packet,IPayloadContext ctx){
        if(!(ctx.player() instanceof ServerPlayer player))return;var level=player.serverLevel();var data=level.getData(CMIAttachments.STORM_DATA.get());var rt=runtime(level);var track=rt.tracks.get(player.getUUID());
        if(!data.active||track==null||!track.active||!track.authority||!StormIdentity.of(level,data).equals(new StormIdentity(packet.dimension(),packet.epoch())))return;
        var ranks=BitSet.valueOf(packet.ranks());long[] accepted=null;
        for(var w:rt.waves)if(w.id==packet.id()&&w.epoch==packet.epoch()&&StormAttack.regional(w.kind)&&(w.locked||w.kind==AllayDome.DOME)){if(ranks.length()>w.members.length)return;
            for(int rank=ranks.nextSetBit(0);rank>=0;rank=ranks.nextSetBit(rank+1))if(!w.completed.get(rank)){w.completed.set(rank);w.membership.clear(w.members[rank]);rt.waveReserved.clear(w.members[rank]);}
            accepted=w.completed.toLongArray();break;
        }
        if(accepted==null&&rt.columns.complete(packet.id(),ranks,rt.waveReserved))accepted=rt.columns.completed(packet.id());
        if(accepted!=null){var out=new com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackCompletedPacket(packet.dimension(),packet.epoch(),packet.id(),accepted);for(var entry:rt.tracks.entrySet())if(entry.getValue().active){var observer=level.getServer().getPlayerList().getPlayer(entry.getKey());if(observer!=null&&observer.serverLevel()==level)PacketDistributor.sendToPlayer(observer,out);}}
    }

    private static void tickColumnSnapshots(ServerLevel level,Runtime rt,AllayStormData data,List<ServerPlayer> players){
        if(rt.columns.isEmpty()){rt.columnSnapshots.clear();return;}
        var active=new java.util.HashSet<UUID>();UUID authority=null;
        for(var player:players){active.add(player.getUUID());var track=rt.tracks.get(player.getUUID());if(track!=null&&track.authority)authority=player.getUUID();}
        var ids=new java.util.HashSet<Integer>();for(var packet:rt.columns.snapshots())ids.add(packet.id());
        rt.columnSnapshots.retain(ids,active);
        for(int id:rt.columnSnapshots.unavailable())rt.columns.abort(id,rt.waveReserved,packet->{for(var player:players)PacketDistributor.sendToPlayer(player,packet);});
        var remaining=new java.util.HashSet<Integer>();for(var packet:rt.columns.snapshots())remaining.add(packet.id());rt.columnSnapshots.retain(remaining,active);
        for(var request:rt.columnSnapshots.poll(level.getGameTime(),authority)){
            var source=level.getServer().getPlayerList().getPlayer(request.source());
            if(source!=null&&source.serverLevel()==level)PacketDistributor.sendToPlayer(source,new com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormColumnSnapshotRequest(level.dimension().location(),StormIdentity.of(level,data).epoch(),request.id(),request.token(),request.source(),level.getGameTime()));
        }
    }

    public static void columnSnapshot(com.iridium126.createmanaindustry.content.allaystorm.network.StormColumnSnapshotPacket packet,IPayloadContext context){
        if(!(context.player() instanceof ServerPlayer player))return;
        var level=player.serverLevel();var data=level.getData(CMIAttachments.STORM_DATA.get());var rt=runtime(level);var track=rt.tracks.get(player.getUUID());
        long now=level.getGameTime();
        if(!data.active||track==null||!track.active||!player.getUUID().equals(packet.source())||!packet.identity().equals(StormIdentity.of(level,data))||packet.gameTime()>now+1||now-packet.gameTime()>=20)return;
        if(Math.abs(AllayStarfall.elapsed(packet.sampleSec(),clockSeconds(packet.gameTime())))>.1f)return;
        com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormColumnPacket column=null;
        for(var candidate:rt.columns.snapshots())if(candidate.id()==packet.id()){column=candidate;break;}
        if(column==null)return;
        var completed=BitSet.valueOf(rt.columns.completed(packet.id()));for(int rank=0;rank<64;rank++)if(data.dead.get(column.members()[rank]))completed.set(rank);
        var snapshot=packet.snapshot().completed(completed);
        var recipients=rt.columnSnapshots.accept(packet.id(),packet.token(),player.getUUID(),now);
        if(recipients.isEmpty())return;
        var relay=new com.iridium126.createmanaindustry.content.allaystorm.network.StormColumnSnapshotPacket(packet.dimension(),packet.epoch(),packet.id(),packet.token(),packet.source(),packet.gameTime(),packet.sampleSec(),snapshot);
        for(UUID recipient:recipients){var observer=level.getServer().getPlayerList().getPlayer(recipient);var observerTrack=rt.tracks.get(recipient);if(observer!=null&&observer.serverLevel()==level&&observerTrack!=null&&observerTrack.active)PacketDistributor.sendToPlayer(observer,relay);}
    }

    public static String attackStatus(ServerLevel level){var rt=runtime(level);return "major: "+rt.stats+" columns: "+rt.columns.stats;}

    // ---- command surface -------------------------------------------------------

    /**
     * Creates or updates the storm. The count argument is the INITIAL
     * population of a NEW storm (clamped by {@code ServerConfig.stormMaxCount});
     * the storm then grows on the tick clock toward that ceiling. Re-running
     * the command on an ACTIVE storm is an anchor-only retarget (clients ease
     * over): generated progress, seed, HP and deaths are preserved, because
     * the population is a growing quantity and can no longer define a
     * "population change" restart. {@code stop} + re-create resets everything.
     * The radius derives from the population ({@link AllayStormData#vortexRadius});
     * the angular velocity is never server state — clients derive it from the
     * radius and the seed's low bit.
     */
    public static void setStorm(ServerLevel level, BlockPos anchor, int count) {
        AllayStormData data = level.getData(CMIAttachments.STORM_DATA.get());
        if (data.active) {
            data.anchor = anchor.atY(ServerConfig.stormChaseY);
            level.setData(CMIAttachments.STORM_DATA.get(), data);
            broadcast(level, data, true);
        } else {
            int initial = Math.max(1, Math.min(ServerConfig.stormMaxCount, count));
            AllayStormData fresh = AllayStormData.create(anchor, initial,
                    level.getGameTime(), level.random.nextInt(1 << 24));
            copyInto(fresh, data);
            level.setData(CMIAttachments.STORM_DATA.get(), data);
            // already-active clients restart against the new seed immediately;
            // newly-in-range players activate through the scan below
            broadcast(level, data, false);
        }
        Runtime rt = runtime(level);
        // re-seed the continuous center from the (just re-pinned) anchor —
        // both the create and update paths land here
        rt.chaseX = data.anchor.getX() + 0.5;
        rt.chaseZ = data.anchor.getZ() + 0.5;
        rt.chaseVX = 0.0;
        rt.chaseVZ = 0.0;
        rt.centerTimer = CENTER_INTERVAL_TICKS; // broadcast on the first tick
        rt.scanCounter = SCAN_INTERVAL_TICKS; // force an immediate scan
        scan(level, rt, data);
    }

    /** Ends the storm: clients disperse and forget; persisted state clears. */
    public static void stopStorm(ServerLevel level) {
        AllayStormData data = level.getData(CMIAttachments.STORM_DATA.get());
        StormIdentity identity = StormIdentity.of(level, data);
        if (data.active || !data.dead.isEmpty() || !data.hp.isEmpty()) {
            data.active = false;
            data.dead.clear();
            data.hp.clear();
            level.setData(CMIAttachments.STORM_DATA.get(), data);
        }
        Runtime rt = runtime(level);
        rt.columns.clear();
        rt.columnSnapshots.clear();
        rt.waves.clear();
        rt.schedule.reset();
        rt.waveReserved.clear();
        for (var entry : rt.tracks.entrySet()) {
            Track t = entry.getValue();
            if (!t.active)
                continue;
            t.active = false;t.melee.release();
            t.authority = false;
            ServerPlayer player = rt.level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player != null && player.serverLevel() == rt.level)
                PacketDistributor.sendToPlayer(player, ClientboundStormStatePacket.STOP(identity));
        }
    }

    private static void broadcast(ServerLevel level, AllayStormData data, boolean updateOnly) {
        Runtime rt = runtime(level);
        for (var entry : rt.tracks.entrySet()) {
            Track t = entry.getValue();
            if (!t.active)
                continue;
            ServerPlayer player = rt.level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null || player.serverLevel() != rt.level)
                continue;
            if (updateOnly) {
                PacketDistributor.sendToPlayer(player, ClientboundStormStatePacket.UPDATE(
                        StormIdentity.of(level, data), data.anchor, data.count, data.stormSeed,
                        t.authority, ServerConfig.stormCorrectionHz,
                        data.creationRadius, data.finalRadius, (float) data.growthPerSecond,
                        data.createdAtGameTime));
            } else {
                PacketDistributor.sendToPlayer(player, ClientboundStormStatePacket.ACTIVATE(
                        StormIdentity.of(level, data), data.anchor, data.count, data.stormSeed,
                        t.authority, ServerConfig.stormCorrectionHz, data.dead.toByteArray(),
                        data.creationRadius, data.finalRadius, (float) data.growthPerSecond,
                        data.createdAtGameTime));
            }
        }
    }

    private static void copyInto(AllayStormData src, AllayStormData dst) {
        dst.active = src.active;
        dst.anchor = src.anchor;
        dst.count = src.count;
        dst.stormSeed = src.stormSeed;
        dst.creationRadius = src.creationRadius;
        dst.finalRadius = src.finalRadius;
        dst.finalCount = src.finalCount;
        dst.growthPerSecond = src.growthPerSecond;
        dst.createdAtGameTime = src.createdAtGameTime;
        dst.dead.clear();
        dst.hp.clear();
    }

    private static Runtime runtime(ServerLevel level) {
        return RUNTIMES.computeIfAbsent(level.dimension(), k -> new Runtime(level));
    }
}
