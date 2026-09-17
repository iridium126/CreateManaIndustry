package com.iridium126.createmanaindustry.dimension.gen;

import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import static org.junit.jupiter.api.Assertions.*;

class AllvrSanctuaryTest {
    @Test void platformCalmLandAndBlendHaveNoBoundaryStep() {
        for (int original : new int[]{-30, 63, 95, 180, 300}) {
            for (int z = -90; z <= 90; z++) for (int x = -90; x <= 90; x++)
                if (Math.hypot(x, z) <= 90) assertEquals(95, AllvrSanctuary.surface(x, z, original));
            for (int x = 0; x <= 500; x++)
                assertTrue(Math.abs(AllvrSanctuary.surface(x, 0, original) - 95) <= 4);
            assertEquals(original, AllvrSanctuary.surface(700, 0, original));
            assertEquals(original, AllvrSanctuary.surface(701, 0, original));
            for (int boundary : new int[]{90, 220, 500, 700})
                assertTrue(Math.abs(AllvrSanctuary.surface(boundary - 1, 0, original)
                    - AllvrSanctuary.surface(boundary + 1, 0, original)) <= 1);
        }
    }

    @Test void allTenCrossingsRemainWalkableAndConnectedAfterPolarRasterization() {
        for (long seed : new long[]{0, 137, -1, Long.MAX_VALUE}) {
            var f = new AllvrSanctuary(seed);
            int size = 441, count = 0, start = -1;
            boolean[] deck = new boolean[size * size], seen = new boolean[deck.length];
            for (int z = -220; z <= 220; z++) for (int x = -220; x <= 220; x++) {
                var c = f.column(x, z);
                if (c.bridge()) { int i = x + 220 + (z + 220) * size; deck[i] = true; start = i; count++; }
            }
            var queue = new ArrayDeque<Integer>(); queue.add(start); seen[start] = true;
            int visited = 0;
            while (!queue.isEmpty()) {
                int i = queue.remove(); visited++;
                for (int j : new int[]{i - 1, i + 1, i - size, i + size})
                    if (j >= 0 && j < deck.length && deck[j] && !seen[j]) { seen[j] = true; queue.add(j); }
            }
            assertEquals(count, visited, "Disconnected walkable deck for seed " + seed);
            // Count components in a real block annulus, avoiding repeated rounded angular samples.
            for (int i = 0; i < deck.length; i++) {
                double r = Math.hypot(i % size - 220, i / size - 220);
                seen[i] = false; deck[i] &= r >= 108 && r <= 114;
            }
            int mouths = 0;
            for (int i = 0; i < deck.length; i++) if (deck[i] && !seen[i]) {
                mouths++; queue.add(i); seen[i] = true;
                while (!queue.isEmpty()) {
                    int k = queue.remove();
                    for (int j : new int[]{k - 1, k + 1, k - size, k + size})
                        if (j >= 0 && j < deck.length && deck[j] && !seen[j]) { seen[j] = true; queue.add(j); }
                }
            }
            assertEquals(10, mouths, "Expected ten inner bridge mouths");
        }
    }

    @Test void karstPreservesRootsAndDepthAndHasLargeOpenArchSpans() {
        var f = new AllvrSanctuary(137);
        int openings = 0, piers = 0, deepFloor = 0;
        for (int z = -210; z <= 210; z += 2) for (int x = -210; x <= 210; x += 2) {
            var c = f.column(x, z);
            assertTrue(c.floor() >= -5 && c.floor() <= 2);
            assertFalse(f.cavity(c, -5));
            if (c.floor() == -5) deepFloor++;
            if (c.radius() <= 90) for (int y = -5; y <= 95; y++) assertFalse(f.cavity(c, y));
            if (c.bridge() && f.cavity(c, 40)) {
                if (!c.masonry(40)) openings++;
                else { piers++; assertTrue(c.masonry(c.floor()), "Pier must reach bedrock"); }
            }
        }
        assertTrue(openings > 1000); assertTrue(piers > 100); assertTrue(deepFloor > 1000);
    }
}
