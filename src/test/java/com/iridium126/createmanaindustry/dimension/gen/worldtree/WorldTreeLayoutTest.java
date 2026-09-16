package com.iridium126.createmanaindustry.dimension.gen.worldtree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldTreeLayoutTest {
    private static WorldTreeLayout tree(long seed) {
        return new WorldTreeLayout(WorldTreeDescriptor.central(seed));
    }

    @Test void fixedMacroSkeletonAndBoundedSeededBuds() {
        var a = tree(42); var b = tree(137);
        for (var kind : new WorldTreeLayout.Kind[]{WorldTreeLayout.Kind.TRUNK,
                WorldTreeLayout.Kind.ROOT, WorldTreeLayout.Kind.PRIMARY})
            assertEquals(a.nodes().stream().filter(n -> n.kind() == kind).toList(),
                b.nodes().stream().filter(n -> n.kind() == kind).toList());
        assertEquals(16, a.nodes().stream().filter(n -> n.kind() == WorldTreeLayout.Kind.ROOT).count());
        assertEquals(14, a.nodes().stream().filter(n -> n.kind() == WorldTreeLayout.Kind.PRIMARY).count());
        assertTrue(a.nodes().size() < 200);
        assertNotEquals(a.nodes(), b.nodes());
        assertEquals(a.nodes(), tree(42).nodes());
        assertThrows(IllegalArgumentException.class, () -> new WorldTreeDescriptor(0, 64, 0, 42, 2));
    }

    @Test void indexMatchesFullGraphAcrossNegativeCoordinatesAndSeams() {
        var random = new Random(42);
        for (long seed : new long[]{42, 137, -1}) {
            var tree = tree(seed);
            for (var node : tree.nodes()) {
                var b = node.bounds();
                for (int i = 0; i < 120; i++) {
                    int x = (int) Math.floor(b.x0() + random.nextDouble() * (b.x1() - b.x0()));
                    int y = (int) Math.floor(b.y0() + random.nextDouble() * (b.y1() - b.y0()));
                    int z = (int) Math.floor(b.z0() + random.nextDouble() * (b.z1() - b.z0()));
                    if (i % 2 == 0) x = Math.floorDiv(x, 32) * 32;
                    assertEquals(tree.sample(tree.nodes(), x, y, z), tree.sample(x, y, z),
                        "Index miss at " + x + "," + y + "," + z);
                }
            }
            assertEquals(WorldTreeLayout.Material.NONE, tree.sample(0, 30_000_000, 0));
            assertEquals(WorldTreeLayout.Material.NONE, tree.sample(-30_000_000, 64, 0));
        }
    }

    @Test void solidSectionOptimizationNeverFillsBarkOrAir() {
        var tree = tree(2026);
        int classified = 0;
        for (int y = 64; y < 1152; y += 32)
        for (int x = -48; x <= 48; x += 16)
        for (int z = -48; z <= 48; z += 16) {
            var nodes = tree.candidates(x, y, z);
            if (!tree.solidHeartwood(nodes, x, y, z)) continue;
            classified++;
            for (int dy = 0; dy < 16; dy++) for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++)
                assertEquals(WorldTreeLayout.Material.HEARTWOOD, tree.sample(nodes, x + dx, y + dy, z + dz));
        }
        assertTrue(classified > 100, "Fast path must actually classify trunk sections");
    }

    @Test void sectionCullingAndNarrowWoodenTipsPreserveGeometry() {
        for (long seed : new long[]{42, 137, 2026}) {
            var tree = tree(seed);
            for (var node : tree.nodes()) {
                if (node.kind() != WorldTreeLayout.Kind.LEAF) {
                    for (var p : new WorldTreeLayout.Point[]{node.a(), node.b()}) {
                        var material = tree.sample((int) Math.floor(p.x()), (int) Math.floor(p.y()), (int) Math.floor(p.z()));
                        assertTrue(material == WorldTreeLayout.Material.BARK || material == WorldTreeLayout.Material.HEARTWOOD,
                            "Wooden joint/tip disappeared: " + node.id());
                    }
                }
                int x = Math.floorDiv((int) Math.floor(node.b().x()), 16) * 16;
                int y = Math.floorDiv((int) Math.floor(node.b().y()), 16) * 16;
                int z = Math.floorDiv((int) Math.floor(node.b().z()), 16) * 16;
                for (int offset : new int[]{-32, -16, 0, 16, 32}) {
                    final int sx = x + offset;
                    var filtered = tree.candidates(sx, y, z).stream().filter(n -> tree.mayTouchSection(n, sx, y, z)).toList();
                    for (int dy = 0; dy < 16; dy += 3) for (int dz = 0; dz < 16; dz += 3) for (int dx = 0; dx < 16; dx += 3)
                        assertEquals(tree.sample(sx + dx, y + dy, z + dz), tree.sample(filtered, sx + dx, y + dy, z + dz));
                }
            }
        }
    }

    @Test void chunkCubeSeamAndShuffledConcurrentRequestsAreDeterministic() {
        var tree = tree(42);
        var points = new ArrayList<int[]>();
        for (int y : new int[]{63, 64, 95, 96, 367, 368, 383, 384, 399, 400, 415, 416, 1100})
        for (int x = -80; x <= 80; x += 7) for (int z = -80; z <= 80; z += 7)
            points.add(new int[]{x, y, z});
        var expected = points.stream().map(p -> tree.sample(p[0], p[1], p[2])).toList();
        var order = new ArrayList<Integer>();
        for (int i = 0; i < points.size(); i++) order.add(i);
        Collections.shuffle(order, new Random(137));
        var other = tree(42);
        order.parallelStream().forEach(i -> {
            int[] p = points.get(i);
            assertEquals(expected.get(i), other.sample(p[0], p[1], p[2]));
        });
        for (int y = 64; y < 1000; y++)
            assertEquals(WorldTreeLayout.Material.HEARTWOOD, tree.sample(0, y, 0), "Broken trunk at " + y);
        assertTrue(tree.intersectsCell(0, 384, 0));
    }
}
