package com.iridium126.createmanaindustry.compat.trickster.sharedfate;

import java.util.*;

/** Server-independent directed graph. Only player targets can have outgoing great tricks. */
public final class SharedFateGraph {
    public record Edge(UUID id, UUID chain, UUID caster, UUID target, boolean playerTarget) {}
    public enum Rejection { NONE, SELF, DUPLICATE, CYCLE }
    private final Map<UUID, LinkedHashMap<UUID, Edge>> outgoing = new HashMap<>();
    private final Map<UUID, Set<UUID>> players = new HashMap<>();
    public Rejection check(UUID caster, UUID target, boolean playerTarget) {
        if (caster.equals(target)) return Rejection.SELF;
        if (outgoing.getOrDefault(caster, new LinkedHashMap<>()).containsKey(target)) return Rejection.DUPLICATE;
        if (playerTarget && reachable(target, caster)) return Rejection.CYCLE;
        return Rejection.NONE;
    }
    private boolean reachable(UUID from, UUID to) {
        var pending = new ArrayDeque<UUID>(); var visited = new HashSet<UUID>(); pending.add(from);
        while (!pending.isEmpty()) {
            UUID node = pending.removeFirst();
            if (!visited.add(node)) continue;
            if (node.equals(to)) return true;
            pending.addAll(players.getOrDefault(node, Set.of()));
        }
        return false;
    }
    public Edge add(UUID chain, UUID caster, UUID target, boolean playerTarget) {
        Rejection reason = check(caster, target, playerTarget);
        if (reason != Rejection.NONE) throw new IllegalArgumentException(reason.name());
        var edge = new Edge(UUID.randomUUID(), chain, caster, target, playerTarget);
        outgoing.computeIfAbsent(caster, ignored -> new LinkedHashMap<>()).put(target, edge);
        if (playerTarget) players.computeIfAbsent(caster, ignored -> new HashSet<>()).add(target);
        return edge;
    }
    public List<Edge> outgoing(UUID caster) {
        var edges = outgoing.get(caster);
        return edges == null ? List.of() : List.copyOf(edges.values());
    }
    public Edge find(UUID caster,UUID target) {
        var edges=outgoing.get(caster);return edges==null?null:edges.get(target);
    }
    public List<Edge> edges() { return outgoing.values().stream().flatMap(m -> m.values().stream()).toList(); }
    public void remove(Edge edge) {
        var edges = outgoing.get(edge.caster());
        if (edges != null) { edges.remove(edge.target(), edge); if (edges.isEmpty()) outgoing.remove(edge.caster()); }
        var adjacent = players.get(edge.caster());
        if (adjacent != null) { adjacent.remove(edge.target()); if (adjacent.isEmpty()) players.remove(edge.caster()); }
    }
}
