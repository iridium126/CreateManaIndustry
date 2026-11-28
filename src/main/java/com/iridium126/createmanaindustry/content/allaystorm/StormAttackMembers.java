package com.iridium126.createmanaindustry.content.allaystorm;

import java.util.Arrays;
import java.util.BitSet;

/** Member identity, selection, packing and shared-clock rules for every storm attack. */
public final class StormAttackMembers {
    public static final int MAX_MEMBERS = 131072;
    /** Ground route speed, blocks/s (column descent and legacy route timing). */
    public static final float SPEED = 6;

    public record Selection(int columns, int perColumn, int[] members) {}

    private StormAttackMembers() {}

    /** Cheap opportunity gate; a small bitset union avoids a failed full identity scan. */
    public static int available(int generated, BitSet dead, BitSet reserved) {
        generated = Math.max(0, Math.min(MAX_MEMBERS, generated));
        BitSet unavailable = (BitSet) dead.clone();
        unavailable.or(reserved);
        return generated - unavailable.get(0, generated).cardinality();
    }

    public static int[] selectExact(int generated, BitSet dead, BitSet reserved, int quota, int seed) {
        generated = Math.max(0, Math.min(MAX_MEMBERS, generated));
        quota = Math.max(0, Math.min(generated, quota));
        int[] chosen = new int[quota];
        int size = 0, offset = mix(seed) & (MAX_MEMBERS - 1), stride = (mix(seed ^ 0x619c731b) | 1) & (MAX_MEMBERS - 1);
        for (int rank = 0; rank < MAX_MEMBERS && size < quota; rank++) {
            int member = (offset + rank * stride) & (MAX_MEMBERS - 1);
            if (member < generated && !dead.get(member) && !reserved.get(member)) chosen[size++] = member;
        }
        return Arrays.copyOf(chosen, size);
    }

    public static void reserve(BitSet reserved, int[] members) {
        for (int member : members) reserved.set(member);
    }

    public static void release(BitSet reserved, int[] members) {
        for (int member : members) reserved.clear(member);
    }

    public static void releaseUnfinished(BitSet reserved, int[] members, BitSet finished) {
        for (int rank = 0; rank < members.length; rank++)
            if (!finished.get(rank)) reserved.clear(members[rank]);
    }

    public static long epoch(long created, int seed) {
        return created * 0x9e3779b97f4a7c15L ^ Integer.toUnsignedLong(seed);
    }

    public static int mix(int n) {
        n ^= n >>> 16;
        n *= 0x7feb352d;
        n ^= n >>> 15;
        n *= 0x846ca68b;
        return n ^ (n >>> 16);
    }

    public static float crownRadius(float stormRadius, int columns) {
        return Math.max(stormRadius * .7f, columns * 4f / (float) (2 * Math.PI));
    }

    public static int pack(int slot, int column, int rank) {
        return 1 | (slot << 1) | (column << 3) | (rank << 8);
    }

    public static int slot(int packed) {
        return (packed >>> 1) & 3;
    }

    public static int column(int packed) {
        return (packed >>> 3) & 31;
    }

    public static int rank(int packed) {
        return packed >>> 8;
    }

    /** Shared clock wrapped at 2^21 ticks; the result is the shortest signed difference. */
    public static float elapsed(float now, float then) {
        float d = now - then;
        return d < -52428.8f ? d + 104857.6f : d > 52428.8f ? d - 104857.6f : d;
    }
}