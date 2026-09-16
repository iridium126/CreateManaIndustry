package com.iridium126.createmanaindustry.dimension.gen.worldtree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry-free, immutable macro graph and 32-cube spatial index.
 * Inspired by MarkovJunior TallRainforestTree's scaffold -> bounded buds -> foliage
 * stages. We grow analytic nodes, not a giant mutable voxel grid or per-cell buds:
 * a branch crossing a cell boundary has exactly the same owner and seed on both sides.
 */
public final class WorldTreeLayout {
    public enum Material { NONE, HEARTWOOD, BARK, LEAVES }
    public enum Kind { TRUNK, ROOT, PRIMARY, SECONDARY, LEAF }
    public record Cell(int x, int y, int z) {}
    public record Point(double x, double y, double z) {
        Point lerp(Point b, double t) {
            return new Point(x + (b.x - x) * t, y + (b.y - y) * t, z + (b.z - z) * t);
        }
    }
    public record Bounds(double x0, double y0, double z0, double x1, double y1, double z1) {
        boolean intersects(double x, double y, double z, int size) {
            return x <= x1 && x + size > x0 && y <= y1 && y + size > y0 && z <= z1 && z + size > z0;
        }
    }
    public record Node(long id, Kind kind, Point a, Point b, double r0, double r1,
                       double verticalScale, Bounds bounds) {
        // Capsule/conical segment in scaled space; leaves use an ellipsoid.
        double distance(double x, double y, double z) {
            double px = x - a.x, py = (y - a.y) / verticalScale, pz = z - a.z;
            if (kind == Kind.LEAF) return (Math.sqrt(px * px / (r0 * r0)
                + py * py / (r0 * r0) + pz * pz / (r1 * r1)) - 1) * Math.min(r0, r1);
            double dx = b.x - a.x, dy = (b.y - a.y) / verticalScale, dz = b.z - a.z;
            double length2 = dx * dx + dy * dy + dz * dz;
            double t = Math.clamp((px * dx + py * dy + pz * dz) / length2, 0, 1);
            px -= t * dx; py -= t * dy; pz -= t * dz;
            return Math.sqrt(px * px + py * py + pz * pz) - (r0 + (r1 - r0) * t);
        }
        double lipschitz() {
            if (kind == Kind.LEAF) return Math.max(1, 1 / verticalScale);
            double dx = b.x - a.x, dy = (b.y - a.y) / verticalScale, dz = b.z - a.z;
            return (1 + Math.abs(r1 - r0) / Math.sqrt(dx * dx + dy * dy + dz * dz))
                * Math.max(1, 1 / verticalScale);
        }
    }

    private static final double DETAIL_BOUND = 12;
    private final WorldTreeDescriptor descriptor;
    private final List<Node> nodes;
    private final Map<Cell, List<Node>> index;
    private final double phase;

    public WorldTreeLayout(WorldTreeDescriptor descriptor) {
        this.descriptor = descriptor;
        phase = WorldTreeDescriptor.unit(descriptor.nodeSeed(0)) * Math.PI * 2;
        List<Node> graph = new ArrayList<>();
        int[] heights = {-24, 32, 192, 384, 512, 640, 760, 900, 1040, 1108};
        double[] radii = {60, 64, 52, 48, 45, 38, 30, 23, 15, 3};
        for (int i = 0; i < heights.length - 1; i++)
            segment(graph, i, Kind.TRUNK, axis(heights[i]), axis(heights[i + 1]), radii[i], radii[i + 1], 1);
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4;
            Point a = radial(angle, 30, 5), b = radial(angle + .13, 100, 10);
            Point c = radial(angle - .08, 170 + 10 * (i % 3), -4);
            segment(graph, 100 + i * 2, Kind.ROOT, a, b, 20, 14, 1.25);
            segment(graph, 101 + i * 2, Kind.ROOT, b, c, 14, 2, 1.25);
        }
        for (int i = 0; i < 7; i++) {
            double angle = i * Math.PI * 2 / 7 + .2;
            int y = 520 + i * 26;
            Point start = axis(y), elbow = radial(angle + .10, 125, y + 100);
            Point tip = radial(angle, 235 + (i % 3) * 8, 790 + (i % 3) * 35);
            segment(graph, 200 + i * 2, Kind.PRIMARY, start, elbow, 25, 16, 1);
            segment(graph, 201 + i * 2, Kind.PRIMARY, elbow, tip, 16, 5, 1);
            // Fixed sockets, independent bud seeds and a finite three-segment growth budget.
            for (int socket = 0; socket < 4; socket++) {
                long id = 1000 + i * 100 + socket * 10;
                long h = descriptor.nodeSeed(id);
                Point bud = elbow.lerp(tip, .20 + socket * .23);
                double direction = angle + (socket % 2 == 0 ? -.9 : .9);
                double length = 28 + 22 * WorldTreeDescriptor.unit(h);
                for (int step = 0; step < 3; step++) {
                    double turn = (WorldTreeDescriptor.unit(WorldTreeDescriptor.mix(h + step)) - .5) * .4;
                    Point end = new Point(bud.x + Math.cos(direction + turn) * length / 3,
                        bud.y + 9 + step * 3, bud.z + Math.sin(direction + turn) * length / 3);
                    segment(graph, id + step, Kind.SECONDARY, bud, end, 5 - step, 4 - step, 1);
                    bud = end;
                }
                leaf(graph, id + 5, bud, 22 + WorldTreeDescriptor.unit(h) * 12, 19, .65);
            }
            leaf(graph, 300 + i, tip, 33, 27, .65);
        }
        // Small, separated upper whorls leave open sky around the crown core.
        for (int i = 0; i < 12; i++) {
            int y = 880 + i / 4 * 75;
            double angle = i * Math.PI / 2 + i / 4 * .55;
            Point tip = radial(angle, 120 - i / 4 * 30, y + 40);
            segment(graph, 400 + i, Kind.SECONDARY, axis(y), tip, 13 - i / 4 * 3, 3, 1);
            leaf(graph, 500 + i, tip, 32 - i / 4 * 4, 26, .6);
        }
        leaf(graph, 600, axis(1108), 12, 12, 1);
        nodes = List.copyOf(graph);
        Map<Cell, List<Node>> cells = new HashMap<>();
        for (Node node : nodes) {
            Bounds b = node.bounds;
            for (int y = cell(b.y0); y <= cell(b.y1); y++)
            for (int z = cell(b.z0); z <= cell(b.z1); z++)
            for (int x = cell(b.x0); x <= cell(b.x1); x++)
                cells.computeIfAbsent(new Cell(x, y, z), ignored -> new ArrayList<>()).add(node);
        }
        cells.replaceAll((key, value) -> List.copyOf(value));
        index = Map.copyOf(cells);
    }

    public WorldTreeDescriptor descriptor() { return descriptor; }
    public List<Node> nodes() { return nodes; }
    private static int cell(double coordinate) { return (int) Math.floor(coordinate / 32); }
    public List<Node> candidates(int x, int y, int z) {
        return index.getOrDefault(new Cell(Math.floorDiv(x, 32), Math.floorDiv(y, 32), Math.floorDiv(z, 32)), List.of());
    }

    /** Aligned 16/32 sections never cross their owning index cell. */
    public boolean intersectsCell(int x, int y, int z) { return !candidates(x, y, z).isEmpty(); }

    public Material sample(int x, int y, int z) { return sample(candidates(x, y, z), x, y, z); }
    public Material sample(List<Node> candidates, int x, int y, int z) {
        double wood = Double.POSITIVE_INFINITY, leaves = Double.POSITIVE_INFINITY;
        double noise = 2 * Math.sin(x * .17 + phase) * Math.cos(z * .13 - y * .09);
        for (Node node : candidates) {
            if (!node.bounds.intersects(x, y, z, 1)) continue;
            double d = node.distance(x + .5, y + .5, z + .5);
            if (node.kind == Kind.LEAF) leaves = Math.min(leaves, d + noise);
            else {
                double detail = noise;
                if (node.kind == Kind.TRUNK) detail += 9 * Math.sin(7 * Math.atan2(z - descriptor.originZ(),
                    x - descriptor.originX()) + y * .006);
                // Narrow tips must retain a wooden core instead of being erased by trunk-scale ridges.
                double amplitude = node.kind == Kind.TRUNK ? 11 : 2;
                wood = Math.min(wood, d + detail * Math.min(1, Math.min(node.r0, node.r1) * .35 / amplitude));
            }
        }
        if (wood <= -4) return Material.HEARTWOOD;
        if (wood <= 0) return Material.BARK;
        return leaves <= 0 ? Material.LEAVES : Material.NONE;
    }

    /** Conservative Lipschitz bound, including all surface displacement and bark depth. */
    public boolean solidHeartwood(List<Node> candidates, int x, int y, int z) {
        for (Node node : candidates) if (node.kind != Kind.LEAF
            && node.distance(x + 8, y + 8, z + 8) + node.lipschitz() * Math.sqrt(3) * 8 + DETAIL_BOUND <= -4)
            return true;
        return false;
    }

    public boolean mayTouchSection(Node node, int x, int y, int z) {
        return node.bounds.intersects(x, y, z, 16)
            && node.distance(x + 8, y + 8, z + 8) - node.lipschitz() * Math.sqrt(3) * 8 <= DETAIL_BOUND;
    }

    private Point axis(int y) { return new Point(descriptor.originX() + 8 * Math.sin(y * .005),
        descriptor.baseY() + y, descriptor.originZ() + 6 * Math.sin(y * .007)); }
    private Point radial(double angle, double radius, int y) { return new Point(descriptor.originX() + Math.cos(angle) * radius,
        descriptor.baseY() + y, descriptor.originZ() + Math.sin(angle) * radius); }
    private static void segment(List<Node> graph, long id, Kind kind, Point a, Point b, double r0, double r1, double scale) {
        double r = Math.max(r0, r1) + DETAIL_BOUND;
        graph.add(new Node(id, kind, a, b, r0, r1, scale,
            new Bounds(Math.min(a.x, b.x) - r, Math.min(a.y, b.y) - r * scale, Math.min(a.z, b.z) - r,
                Math.max(a.x, b.x) + r, Math.max(a.y, b.y) + r * scale, Math.max(a.z, b.z) + r)));
    }
    private static void leaf(List<Node> graph, long id, Point p, double rx, double rz, double scale) {
        double r = Math.max(rx, rz) + DETAIL_BOUND;
        graph.add(new Node(id, Kind.LEAF, p, p, rx, rz, scale,
            new Bounds(p.x - r, p.y - r * scale, p.z - r, p.x + r, p.y + r * scale, p.z + r)));
    }
}
