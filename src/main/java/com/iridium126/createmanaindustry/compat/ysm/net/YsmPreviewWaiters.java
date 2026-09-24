package com.iridium126.createmanaindustry.compat.ysm.net;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Coalesces equal in-flight preview requests while preserving waiter order. */
final class YsmPreviewWaiters<K> {
    private final Map<K, LinkedHashSet<UUID>> waiters = new LinkedHashMap<>();

    /** Returns true only for the first request that must schedule a render. */
    boolean add(K key, UUID player) {
        boolean first = !waiters.containsKey(key);
        LinkedHashSet<UUID> recipients = waiters.computeIfAbsent(key, ignored -> new LinkedHashSet<>());
        recipients.add(player);
        return first;
    }

    Optional<List<UUID>> take(K key) {
        LinkedHashSet<UUID> recipients = waiters.remove(key);
        return recipients == null ? Optional.empty() : Optional.of(new ArrayList<>(recipients));
    }

    void forget(UUID player) { waiters.values().forEach(recipients -> recipients.remove(player)); }
    void clear() { waiters.clear(); }
}
