package com.iridium126.createmanaindustry.compat.trickster;

import java.util.*;

/** A radius-two axial lattice. Edges, rather than stroke direction, identify a trick. */
public record GreatPattern(List<Edge> edges) {
    public static final int DOT_COUNT = 19;
    public static final int[] Q = {0,1,2,-1,0,1,2,-2,-1,0,1,2,-2,-1,0,1,-2,-1,0};
    public static final int[] R = {-2,-2,-2,-1,-1,-1,-1,0,0,0,0,0,1,1,1,1,2,2,2};
    public static final GreatPattern EMPTY = new GreatPattern(List.of());
    public static final GreatPattern INVERSION = fromPath(List.of(0,4,9,14,18,15,11,10,9,8,7,3,0));
    public static final GreatPattern SHARED_FATE = fromPath(List.of(9,4,3,7,12,13,9,5,6,11,15,14,9));
    public static final GreatPattern TINCTURE_SHAPING = fromPath(List.of(0,2,5,6,11,15,18,16,12,7,3,4,0));

    public GreatPattern {
        var unique = new TreeSet<Edge>();
        for (Edge edge : edges) {
            if (between(edge.a(), edge.b()).size() != 1)
                throw new IllegalArgumentException("Great-trick edge skips an intermediate dot");
            if (!unique.add(edge)) throw new IllegalArgumentException("Duplicate great-trick edge");
        }
        edges = List.copyOf(unique);
    }

    public record Edge(int a, int b) implements Comparable<Edge> {
        public Edge {
            if (a < 0 || b < 0 || a >= DOT_COUNT || b >= DOT_COUNT || a == b)
                throw new IllegalArgumentException("Invalid great-trick endpoint");
            if (a > b) { int swap = a; a = b; b = swap; }
        }
        public int compareTo(Edge other) {
            int first = Integer.compare(a, other.a);
            return first != 0 ? first : Integer.compare(b, other.b);
        }
    }

    /** All dots crossed by a stroke, excluding its start and including its end. */
    public static List<Integer> between(int from, int to) {
        if (from < 0 || to < 0 || from >= DOT_COUNT || to >= DOT_COUNT)
            throw new IllegalArgumentException("Invalid dot");
        if (from == to) return List.of();
        int dq = Q[to] - Q[from], dr = R[to] - R[from];
        var dots = new ArrayList<Integer>();
        for (int i = 0; i < DOT_COUNT; i++) {
            int x = Q[i] - Q[from], y = R[i] - R[from];
            int projection = x * dq + y * dr;
            if (x * dr == y * dq && projection > 0 && projection <= dq * dq + dr * dr) dots.add(i);
        }
        dots.sort(Comparator.comparingInt(i -> (Q[i]-Q[from])*dq+(R[i]-R[from])*dr));
        return dots;
    }

    public static GreatPattern fromPath(List<Integer> path) {
        var edges = new ArrayList<Edge>();
        for (int i = 1; i < path.size(); i++) {
            int previous = path.get(i - 1);
            for (int next : between(previous, path.get(i))) {
                edges.add(new Edge(previous, next)); previous = next;
            }
        }
        return new GreatPattern(edges);
    }

    /** Returns an unchanged path for forbidden moves; immediate backtracking erases edges. */
    public static List<Integer> move(List<Integer> path, int to) {
        if (path.isEmpty()) return List.of(to);
        var result = new ArrayList<>(path);
        for (int next : between(path.getLast(), to)) {
            int last = result.getLast();
            if (result.size() > 1 && result.get(result.size() - 2) == next) result.removeLast();
            else {
                Edge edge = new Edge(last, next);
                for (int i = 1; i < result.size(); i++)
                    if (new Edge(result.get(i-1), result.get(i)).equals(edge)) return path;
                result.add(next);
            }
        }
        return List.copyOf(result);
    }

    public byte[] bytes() {
        byte[] bytes = new byte[edges.size() * 2];
        for (int i = 0; i < edges.size(); i++) { bytes[2*i]=(byte)edges.get(i).a(); bytes[2*i+1]=(byte)edges.get(i).b(); }
        return bytes;
    }
    public static GreatPattern fromBytes(byte[] bytes) {
        if (bytes.length % 2 != 0 || bytes.length > 342) throw new IllegalArgumentException("Invalid edge data");
        var edges = new ArrayList<Edge>();
        for (int i=0; i<bytes.length; i+=2) edges.add(new Edge(bytes[i], bytes[i+1]));
        return new GreatPattern(edges);
    }
}
