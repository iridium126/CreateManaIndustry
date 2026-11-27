package com.iridium126.createmanaindustry.content.allaystorm;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Per-tick analytic home positions. Reservation changes are visible to every draw. */
final class StormNearbyRoster {
    private static final int CELL = 8;
    private final Vec3[] positions;
    private final ArrayList<Bucket> buckets;
    private final BitSet reserved;

    private record Bucket(BlockPos key, IntArrayList members) {
        double lowerBound(Vec3 point) {
            double x = axis(point.x, key.getX()), y = axis(point.y, key.getY()), z = axis(point.z, key.getZ());
            return x * x + y * y + z * z;
        }
        private static double axis(double point, int key) {
            double start = key * (double) CELL;
            return Math.max(0, Math.max(start - point, point - (start + CELL)));
        }
    }
    private record Visit(Bucket bucket, double distance) {}
    record Match(int[] members, double farthest) {
        static final Match EMPTY = new Match(new int[0], 0);
    }

    static Supplier<StormNearbyRoster> lazy(AllayStormData data, Vec3 center, long tick, BitSet reserved) {
        return new Supplier<>() {
            private StormNearbyRoster roster;
            @Override public StormNearbyRoster get() {
                if (roster == null) roster = new StormNearbyRoster(data, center, tick, reserved);
                return roster;
            }
        };
    }

    StormNearbyRoster(AllayStormData data, Vec3 center, long tick, BitSet reserved) {
        this.reserved = reserved;
        positions = new Vec3[data.count];
        var radius = AllayStormData.vortexRadius(data.count);
        var phase = AllayStormPhases.evaluate(data.creationRadius, data.finalRadius, (float) data.growthPerSecond,
                data.createdAtGameTime, tick, data.count, data.stormSeed);
        var cells = new HashMap<BlockPos, IntArrayList>();
        for (int member = data.dead.nextClearBit(0); member < data.count; member = data.dead.nextClearBit(member + 1)) {
            var point = home(AllayStormWaves.memberSeed(data.stormSeed, member), radius, phase, center);
            positions[member] = point;
            var key = BlockPos.containing(point.x / CELL, point.y / CELL, point.z / CELL);
            cells.computeIfAbsent(key, ignored -> new IntArrayList()).add(member);
        }
        buckets = new ArrayList<>(cells.size());
        cells.forEach((key, members) -> buckets.add(new Bucket(key, members)));
    }

    Match match(Vec3[] slots, int seed) {
        var chosen = new BitSet();
        var roster = new int[slots.length];
        var visits = new Visit[buckets.size()];
        double farthest = 0;
        Vec3 center=Vec3.ZERO;
        for(Vec3 slot:slots)center=center.add(slot);
        center=center.scale(1.0/slots.length);
        double extent=0;
        for(Vec3 slot:slots)extent=Math.max(extent,slot.distanceTo(center));
        for(int i=0;i<visits.length;i++){
            var bucket=buckets.get(i);
            visits[i]=new Visit(bucket,bucket.lowerBound(center));
        }
        Arrays.sort(visits,Comparator.comparingDouble(Visit::distance));
        int first = Math.floorMod(AllayStarfall.mix(seed), slots.length);
        for (int offset = 0; offset < slots.length; offset++) {
            int rank = (first + offset) % slots.length;
            Vec3 point = slots[rank];
            int best = -1;
            double distance = Double.POSITIVE_INFINITY;
            double stop = Double.POSITIVE_INFINITY;
            for (var visit : visits) {
                if (visit.distance > stop) break;
                if (visit.bucket.lowerBound(point) > distance) continue;
                var members = visit.bucket.members;
                for (int i = 0; i < members.size(); i++) {
                    int member = members.getInt(i);
                    if (chosen.get(member) || reserved.get(member)) continue;
                    double candidate = positions[member].distanceToSqr(point);
                    if (candidate < distance || candidate == distance && member < best) {
                        best = member;
                        distance = candidate;
                        double bound = Math.sqrt(distance) + extent;
                        stop = bound * bound + 1e-9;
                    }
                }
            }
            if (best < 0) return Match.EMPTY;
            roster[rank] = best;
            chosen.set(best);
            farthest = Math.max(farthest, distance);
        }
        return new Match(roster, Math.sqrt(farthest));
    }

    static float gatherSeconds(double farthest, double speedAdvantage) {
        return (float) (Math.ceil((farthest / speedAdvantage + 2) * 20) / 20);
    }

    private static float hash(float value) {
        return AllayStormWaves.hash1(value);
    }

    // Position half of cmiTyphoonHome, including the per-member vertical band and micro jitter.
    static Vec3 home(float member, float radius, AllayStormPhases phase, Vec3 center) {
        float arm = (float) Math.floor(hash(member * 5.7f) * 6) * 1.04719755f;
        float gaussianSeed = member * 9.1f;
        double jitter = Math.sqrt(-2 * Math.log(Math.max(hash(gaussianSeed), 1e-6f)))
                * Math.cos(6.2831853f * hash(gaussianSeed + 17.17f)) * .2094f;
        float rate = .15f + hash(member * 13.9f) * .2f;
        float fringe = hash(member * 23.1f);
        float span = .9f + .15f * fringe;
        float cycle = hash(member * 11.3f) + phase.conveyor() * (rate / span);
        double flow = 6.2831853f * (cycle - Math.floor(cycle));
        double radialCycle = (1 - Math.cos(flow)) * .5;
        float eye = radius * .1f;
        double r = eye + radius * span * radialCycle;
        double funnel = Math.clamp(radius * .2f, 2, 6) * (1 - radialCycle);
        double y = -1.2f + (funnel + 1.2f) * (.5 + .5 * Math.sin(flow))
                + (hash(member * 29.3f) - .5f) * Math.clamp(radius * .4f, 3, 8);
        double theta = arm + jitter + phase.rotation() - Math.signum(phase.omega())
                * Math.PI / Math.log(radius * (1 + .15f * fringe) / eye) * Math.log(r / eye);
        float jitterSeed = member * 17.3f;
        double azimuth = hash(jitterSeed) * 6.2831853f;
        double jy = hash(jitterSeed + 3.1f) * 2 - 1;
        double jxy = Math.sqrt(Math.max(0, 1 - jy * jy));
        double magnitude = .4f * hash(member * 19.3f);
        return center.add(Math.cos(theta) * r + Math.cos(azimuth) * jxy * magnitude,
                y + jy * magnitude, Math.sin(theta) * r + Math.sin(azimuth) * jxy * magnitude);
    }
}
