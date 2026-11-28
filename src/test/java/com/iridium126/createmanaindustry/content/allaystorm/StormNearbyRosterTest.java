package com.iridium126.createmanaindustry.content.allaystorm;

import java.util.Arrays;
import java.util.BitSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StormNearbyRosterTest {
    @Test void ringAndStarfallShareNearestEligibleMembers() {
        var center = new Vec3(10, 128, -5);
        var data = AllayStormData.create(BlockPos.containing(center), 12000, 0, 1234);
        var reserved = new BitSet();
        data.dead.set(0, 13);
        reserved.set(13, 45);
        var nearby = new StormNearbyRoster(data, center, 1000, reserved);
        var ring = new Vec3[StormRingGeometry.MEMBERS];
        var axis = new Vec3(1, 0, 0);
        var right = StormRingGeometry.right(axis, Vec3.ZERO);
        for (int i = 0; i < ring.length; i++) ring[i] = center.add(0, -4, 0)
                .add(StormRingGeometry.offset(axis, right, i, 0));
        var ringMatch = nearby.match(ring, 17);
        assertArrayEquals(ringMatch.members(), nearby.match(ring, 17).members());
        assertEquals(ring.length, Arrays.stream(ringMatch.members()).distinct().count());
        for (int member : ringMatch.members()) {
            assertTrue(member >= 45);
            reserved.set(member);
        }

        var column = new Vec3[StarfallColumnRules.MEMBERS];
        for (int i = 0; i < column.length; i++) column[i] = StarfallColumnRules.gather(center.add(8, -8, 0), i, 17);
        var columnMatch = nearby.match(column, 17);
        assertEquals(column.length, Arrays.stream(columnMatch.members()).distinct().count());
        for (int member : columnMatch.members()) assertFalse(reserved.get(member) || data.dead.get(member));

        float radius = AllayStormData.vortexRadius(data.count);
        var phase = AllayStormPhases.evaluate(data.creationRadius, data.finalRadius, (float) data.growthPerSecond,
                data.createdAtGameTime, 1000, data.count, data.stormSeed);
        assertFasterThanRandom(data, phase, center, radius, column, reserved, columnMatch);
        var empty = new BitSet();
        assertFasterThanRandom(data, phase, center, radius, ring, empty, ringMatch);
        assertTrue(StormNearbyRoster.gatherSeconds(columnMatch.farthest(), 5)
                < Math.max(4, (radius * 2 + 16) * 2 / StormAttackMembers.SPEED));
    }

    private static void assertFasterThanRandom(AllayStormData data, AllayStormPhases phase, Vec3 center,
                                                float radius, Vec3[] slots, BitSet reserved, StormNearbyRoster.Match match) {
        int[] random = StormAttackMembers.selectExact(data.count, data.dead, reserved, slots.length, 17);
        double farthest = 0;
        for (int i = 0; i < slots.length; i++) {
            var home = StormNearbyRoster.home(StormMemberHash.memberSeed(data.stormSeed, random[i]), radius, phase, center);
            farthest = Math.max(farthest, home.distanceTo(slots[i]));
        }
        assertTrue(match.farthest() < farthest);
    }

    @Test void returningMembersYieldToCleanNeighbours() {
        var center = new Vec3(0, 128, 0);
        var data = AllayStormData.create(BlockPos.containing(center), 16, 0, 1234);
        var reserved = new BitSet();
        float radius = AllayStormData.vortexRadius(data.count);
        var phase = AllayStormPhases.evaluate(data.creationRadius, data.finalRadius, (float) data.growthPerSecond,
                data.createdAtGameTime, 1000, data.count, data.stormSeed);
        var home = StormNearbyRoster.home(StormMemberHash.memberSeed(data.stormSeed, 0), radius, phase, center);
        var slots = new Vec3[]{home};
        assertEquals(0, new StormNearbyRoster(data, center, 1000, reserved).match(slots, 7).members()[0]);
        var returns = new StormMemberReturns();
        returns.released(0, 1000, home.add(500, 0, 0), 0);
        int picked = new StormNearbyRoster(data, center, 1000, reserved, returns, 1000).match(slots, 7).members()[0];
        assertNotEquals(0, picked);
    }

    @Test void exhaustedReservationsDoNotReturnPartialRosters() {
        var data = AllayStormData.create(BlockPos.ZERO, 16, 0, 1);
        var reserved = new BitSet();
        reserved.set(4, 16);
        var slots = new Vec3[5];
        Arrays.fill(slots, Vec3.ZERO);
        assertEquals(0, new StormNearbyRoster(data, Vec3.ZERO, 10, reserved).match(slots, 9).members().length);
    }
}
