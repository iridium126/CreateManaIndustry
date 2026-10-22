package com.iridium126.createmanaindustry.content.allaystorm;

import java.util.*;

public final class StarfallSnapshotSync {
    public record Request(int id, int token, UUID source) {}
    private static final class Column {
        final Set<UUID> holders = new LinkedHashSet<>(), waiting = new LinkedHashSet<>();
        Request request;
        long requested;
        int cursor;
    }
    private final Map<Integer, Column> columns = new LinkedHashMap<>();
    private int sequence;

    public void created(int id, Collection<UUID> holders) {
        var column = new Column(); column.holders.addAll(holders); columns.put(id, column);
    }

    public void observe(int id, UUID observer) {
        var column = columns.get(id);
        if (column != null && !column.holders.contains(observer)) column.waiting.add(observer);
    }

    public void retain(Set<Integer> ids, Set<UUID> active) {
        columns.keySet().retainAll(ids);
        for (var column : columns.values()) {
            column.holders.retainAll(active); column.waiting.retainAll(active);
            if (column.waiting.isEmpty()) column.request = null;
            if (column.request != null && !column.holders.contains(column.request.source())) column.request = null;
        }
    }

    public Set<Integer> unavailable() {
        var result = new HashSet<Integer>();
        for (var entry : columns.entrySet()) if (entry.getValue().holders.isEmpty()) result.add(entry.getKey());
        return result;
    }

    public List<Request> poll(long tick, UUID authority) {
        var result = new ArrayList<Request>();
        for (var entry : columns.entrySet()) {
            var column = entry.getValue();
            if (column.waiting.isEmpty() || column.holders.isEmpty() || column.request != null && tick - column.requested < 20) continue;
            var sources = new ArrayList<>(column.holders);
            sources.sort(Comparator.comparing((UUID source) -> !source.equals(authority)).thenComparing(UUID::toString));
            UUID source = sources.get(Math.floorMod(column.cursor++, sources.size()));
            if (sequence == Integer.MAX_VALUE) throw new IllegalStateException("Starfall snapshot token exhausted");
            column.request = new Request(entry.getKey(), ++sequence, source); column.requested = tick;
            result.add(column.request);
        }
        return result;
    }

    public Set<UUID> accept(int id, int token, UUID source, long tick) {
        var column = columns.get(id);
        if (column == null || column.request == null || column.request.token() != token
                || !column.request.source().equals(source) || tick - column.requested >= 20) return Set.of();
        var result = Set.copyOf(column.waiting); column.holders.addAll(result); column.waiting.clear(); column.request = null;
        return result;
    }

    public void clear() { columns.clear(); }
}
