package com.iridium126.createmanaindustry.content.allaystorm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.DoubleSupplier;
import java.util.function.Function;

/**
 * Shared per-player opportunity clocks, probability rolls and fair draw order.
 * Attack implementations only run after this class has admitted a player.
 */
public final class StormAttackDraw {
    private static final int KINDS = StormAttackKind.values().length;

    private final Map<UUID, long[]> nextTick = new HashMap<>();
    private final int[] cursors = new int[KINDS];
    private int drawCursor;

    public void reset() {
        nextTick.clear();
        Arrays.fill(cursors, 0);
        drawCursor = 0;
    }

    public void forget(UUID player) {
        nextTick.remove(player);
    }

    public <T> boolean anyDue(StormAttackKind kind, List<T> candidates, Function<? super T, UUID> id, long now) {
        int slot = kind.ordinal();
        for (T candidate : candidates) {
            long[] ticks = nextTick.get(id.apply(candidate));
            long next = ticks == null ? Long.MIN_VALUE : ticks[slot];
            if (next == Long.MIN_VALUE ? kind.immediateFirstDraw() : now >= next) return true;
        }
        return false;
    }

    /**
     * Advances only the supplied candidates' clocks and returns admitted players in the
     * rotated candidate order. Range and outer eligibility are the caller's responsibility.
     */
    public <T> List<T> due(StormAttackKind kind, List<T> candidates, Function<? super T, UUID> id,
                           long now, DoubleSupplier random, StormAttackStats stats) {
        int count = candidates.size();
        if (count == 0) return List.of();
        int slot = kind.ordinal();
        int first = Math.floorMod(cursors[slot]++, count);
        List<T> due = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            T candidate = candidates.get((first + i) % count);
            long[] ticks = nextTick.computeIfAbsent(id.apply(candidate), ignored -> {
                long[] created = new long[KINDS];
                Arrays.fill(created, Long.MIN_VALUE);
                return created;
            });
            long next = ticks[slot];
            if (next == Long.MIN_VALUE) {
                if (!kind.immediateFirstDraw()) {
                    ticks[slot] = now + intervalTicks(kind, random);
                    continue;
                }
                next = now;
            }
            if (now < next) continue;
            ticks[slot] = now + intervalTicks(kind, random);
            if (random.getAsDouble() >= kind.chance()) {
                stats.count(StormAttackStats.Reason.DRAW_MISSED);
                continue;
            }
            due.add(candidate);
        }
        return due;
    }

    /**
     * Rotating start slot over every attack kind. The cursor advances only when at least two
     * kinds have an opportunity, so frequent solo draws cannot consume the rotation phase.
     */
    public int start(int dueMask) {
        int start = drawCursor;
        if (Integer.bitCount(dueMask) >= 2) drawCursor = (drawCursor + 1) % KINDS;
        return start;
    }

    private static long intervalTicks(StormAttackKind kind, DoubleSupplier random) {
        double low = Math.min(kind.minIntervalSeconds(), kind.maxIntervalSeconds());
        double high = Math.max(kind.minIntervalSeconds(), kind.maxIntervalSeconds());
        double seconds = low == high ? low : low + (high - low) * random.getAsDouble();
        return Math.max(1, Math.round(seconds * 20));
    }
}