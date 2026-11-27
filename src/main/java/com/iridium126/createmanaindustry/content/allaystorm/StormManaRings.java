package com.iridium126.createmanaindustry.content.allaystorm;

import com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormBlastPacket;
import com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormRingPacket;
import com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormRingPacket.Frame;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import static com.iridium126.createmanaindustry.content.allaystorm.StormRingGeometry.*;

final class StormManaRings {
    private static final int[] NO_ROSTER = new int[0];
    private final Ring[] rings = new Ring[CAPACITY];
    private long nextDraw = Long.MIN_VALUE, blastRevision;
    private int cursor;
    private boolean firstDraw = true;
    final StormAttackStats stats = new StormAttackStats();

    private static final class Ring {
        final int slot, id;
        final int[] roster;
        final UUID target;
        final long start, gatherDeadline;
        final BitSet released = new BitSet(MEMBERS);
        int phase = GATHER;
        long entered, ready = Long.MIN_VALUE;
        Vec3 center, axis, right, velocity = Vec3.ZERO;
        StormManaBeam beam;

        Ring(int slot, int id, int[] roster, UUID target, long start, long gatherDeadline,
             Vec3 center, Vec3 axis, Vec3 right) {
            this.slot = slot;
            this.id = id;
            this.roster = roster;
            this.target = target;
            this.start = start;
            this.gatherDeadline = gatherDeadline;
            this.center = center;
            this.axis = axis;
            this.right = right;
            entered = start;
        }
        Frame frame(boolean includeRoster) {
            return new Frame(slot, id, phase, start, center, axis, right, velocity,
                    includeRoster ? roster : NO_ROSTER, beam == null ? null : beam.shot);
        }
        void phase(int next, long tick) {
            phase = next;
            entered = tick;
            ready = Long.MIN_VALUE;
        }
        boolean ready(long now) { return ready >= entered && now - ready <= 20; }
    }

    void clear() {
        java.util.Arrays.fill(rings, null);
        nextDraw = Long.MIN_VALUE;
        blastRevision = 0;
        cursor = 0;
        firstDraw = true;
    }

    ClientboundStormRingPacket snapshot(StormIdentity identity, long tick) {
        var frames = new ArrayList<Frame>();
        for (Ring ring : rings) if (ring != null) frames.add(ring.frame(true));
        return frames.isEmpty() ? null : new ClientboundStormRingPacket(identity, tick, List.copyOf(frames));
    }

    int active() {
        int count = 0;
        for (Ring ring : rings) if (ring != null) count++;
        return count;
    }

    boolean beforeOtherDraws(long now) { return now >= nextDraw && firstDraw; }

    void ready(long sample, int[] ids) {
        for (int id : ids) for (Ring ring : rings) {
            if (ring != null && ring.id == id && (ring.phase == GATHER || ring.phase == HOLDING)
                    && sample >= ring.entered && sample > ring.ready) ring.ready = sample;
        }
    }

    void dead(int member, BitSet reserved) {
        for (Ring ring : rings) if (ring != null) {
            for (int rank = 0; rank < MEMBERS; rank++) {
                if (ring.roster[rank] == member && !ring.released.get(rank)) {
                    ring.released.set(rank);
                    reserved.clear(member);
                    return;
                }
            }
        }
    }

    void tick(ServerLevel level, AllayStormData data, Vec3 stormCenter, List<ServerPlayer> observers,
              List<ServerPlayer> eligible, BitSet reserved, Supplier<StormNearbyRoster> nearby, IntSupplier ids,
              Consumer<ClientboundStormRingPacket> send, Consumer<ClientboundStormBlastPacket> blast) {
        long now = level.getGameTime();
        var identity = StormIdentity.of(level, data);
        var changed = new Frame[CAPACITY];
        var removed = new ArrayList<BlockPos>();
        for (Ring ring : rings) if (ring != null) {
            ServerPlayer target = level.getServer().getPlayerList().getPlayer(ring.target);
            boolean targetValid = target != null && observers.contains(target) && target.isAlive() && !target.isSpectator();
            var oldPhase = ring.phase;
            boolean keep = ring.phase == FIRING
                    ? ring.beam.advance(level, now, removed)
                    : targetValid && ring.released.isEmpty() && advanceRing(level, ring, target, now);
            if (!keep) {
                for (int rank = 0; rank < MEMBERS; rank++) if (!ring.released.get(rank)) reserved.clear(ring.roster[rank]);
                rings[ring.slot] = null;
                changed[ring.slot] = new Frame(ring.slot, ring.id, END, 0, Vec3.ZERO, Vec3.ZERO,
                        Vec3.ZERO, Vec3.ZERO, NO_ROSTER, null);
            } else if (ring.phase != oldPhase || (ring.phase == APPROACH || ring.phase == HOLDING) && (now & 1) == 0) {
                changed[ring.slot] = ring.frame(false);
            }
        }
        if (now >= nextDraw) {
            nextDraw = now + Math.max(1, Math.round(ServerConfig.stormManaRingInterval * 20));
            firstDraw = !firstDraw;
            schedule(level, data, stormCenter, eligible, reserved, nearby, ids, now, changed);
        }
        var frames = new ArrayList<Frame>();
        for (Frame frame : changed) if (frame != null) frames.add(frame);
        if (!frames.isEmpty()) send.accept(new ClientboundStormRingPacket(identity, now, List.copyOf(frames)));
        sendBlasts(identity, removed, blast);
    }

    private void schedule(ServerLevel level, AllayStormData data, Vec3 stormCenter, List<ServerPlayer> eligible,
                          BitSet reserved, Supplier<StormNearbyRoster> nearby, IntSupplier ids, long now, Frame[] changed) {
        if (eligible.isEmpty() || ServerConfig.stormManaRingChance <= 0) return;
        var players = new ArrayList<>(eligible);
        players.sort(Comparator.comparing(ServerPlayer::getUUID));
        int first = Math.floorMod(cursor++, players.size());
        int available = -1;
        int active = active();
        float radius = AllayStormData.vortexRadius(data.count);
        for (int i = 0; i < players.size(); i++) {
            var player = players.get((first + i) % players.size());
            if (Math.hypot(player.getX() - stormCenter.x, player.getZ() - stormCenter.z) > radius) continue;
            if (level.random.nextDouble() >= ServerConfig.stormManaRingChance) continue;
            if (active >= ServerConfig.stormManaRingMaxActive) {
                stats.count(StormAttackStats.Reason.CAPACITY);
                break;
            }
            if (available < 0) available = AllayStarfall.available(data.count, data.dead, reserved);
            if (available < MEMBERS) {
                stats.count(data.aliveCount() < MEMBERS ? StormAttackStats.Reason.TOO_FEW : StormAttackStats.Reason.RESERVED);
                break;
            }
            int slot = 0;
            while (slot < CAPACITY && (rings[slot] != null || changed[slot] != null)) slot++;
            if (slot == CAPACITY) break;
            Vec3 target = player.getBoundingBox().getCenter();
            Vec3 center = stormCenter.add(0, -4, 0);
            if (center.distanceTo(target) < FIRE_DISTANCE + 2) {
                Vec3 away = center.subtract(target).normalize();
                if (away.lengthSqr() < .5) away = new Vec3(0, 1, 0);
                center = target.add(away.scale(FIRE_DISTANCE + 2));
            }
            Vec3 axis = target.subtract(center).normalize();
            Vec3 right = StormRingGeometry.right(axis, Vec3.ZERO);
            var slots = new Vec3[MEMBERS];
            for (int rank = 0; rank < MEMBERS; rank++) slots[rank] = center.add(offset(axis, right, rank, 0));
            var selection = nearby.get().match(slots, level.random.nextInt());
            if (selection.members().length == 0) continue;
            long start = now + 4;
            long budget = Math.round(20 * StormNearbyRoster.gatherSeconds(selection.farthest(), StormMotion.INTERCEPT - RADIUS * SPIN));
            var ring = new Ring(slot, ids.getAsInt(), selection.members(), player.getUUID(), start, start + budget,
                    center, axis, right);
            rings[slot] = ring;
            for (int member : ring.roster) reserved.set(member);
            active++;
            available -= MEMBERS;
            changed[slot] = ring.frame(true);
            stats.count(StormAttackStats.Reason.LAUNCHED);
        }
    }

    private boolean advanceRing(ServerLevel level, Ring ring, ServerPlayer player, long now) {
        if (ring.phase == GATHER) {
            if (ring.ready(now) && now >= ring.start) ring.phase(APPROACH, now);
            else if (now > ring.gatherDeadline) {
                stats.count(StormAttackStats.Reason.GATHER_TIMEOUT);
                return false;
            }
            return true;
        }
        if (ring.phase == APPROACH) {
            if (now - ring.entered > 1200) return false;
            Vec3 delta = player.getBoundingBox().getCenter().subtract(ring.center);
            double distance = delta.length();
            if (distance > 1e-3) {
                ring.axis = delta.scale(1 / distance);
                ring.right = StormRingGeometry.right(ring.axis, ring.right);
            }
            double step = Math.clamp(distance - FIRE_DISTANCE, 0, StormMotion.CRUISE / 20.0);
            ring.velocity = ring.axis.scale(step * 20);
            ring.center = ring.center.add(ring.axis.scale(step));
            if (distance - step <= FIRE_DISTANCE + 1e-5) {
                ring.phase(HOLDING, now);
                ring.velocity = Vec3.ZERO;
            }
            return true;
        }
        if (now - ring.entered > 40) return false;
        if (ring.ready(now)) {
            ring.phase(FIRING, now);
            ring.beam = new StormManaBeam(level, ring.center, ring.axis, now);
        }
        return true;
    }

    private void sendBlasts(StormIdentity identity, List<BlockPos> removed, Consumer<ClientboundStormBlastPacket> send) {
        var batch = new ArrayList<ClientboundStormBlastPacket.CellGroup>();
        int count = 0;
        for (var group : ClientboundStormBlastPacket.group(removed)) {
            if (count + group.cells().length > ClientboundStormBlastPacket.MAX_ENTRIES
                    || batch.size() >= ClientboundStormBlastPacket.MAX_SECTIONS) {
                send.accept(new ClientboundStormBlastPacket(identity, ++blastRevision, List.copyOf(batch)));
                batch.clear();
                count = 0;
            }
            batch.add(group);
            count += group.cells().length;
        }
        if (!batch.isEmpty()) send.accept(new ClientboundStormBlastPacket(identity, ++blastRevision, List.copyOf(batch)));
    }
}
