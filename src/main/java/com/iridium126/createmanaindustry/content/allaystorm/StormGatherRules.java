package com.iridium126.createmanaindustry.content.allaystorm;

import java.util.BitSet;

/** Identity-independent arithmetic shared by server validation and GPU feedback consumers. */
public final class StormGatherRules {
    public static final int MAX_MEMBERS = 12288, BITMAP_LONGS = MAX_MEMBERS / 64;
    private StormGatherRules() {}
    public static boolean ready(int ready, int eligible) {
        return eligible > 0 && ready >= 0 && ready <= eligible && (long) ready * 100 >= (long) eligible * 95;
    }
    public static boolean valid(BitSet ready, BitSet eligible, int count) {
        if (ready.length() > count || eligible.length() > count) return false;
        var excess = (BitSet) ready.clone(); excess.andNot(eligible);
        return excess.isEmpty() && ready(ready.cardinality(), eligible.cardinality());
    }
}
