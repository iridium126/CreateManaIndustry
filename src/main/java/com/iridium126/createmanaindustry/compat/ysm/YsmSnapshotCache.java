package com.iridium126.createmanaindustry.compat.ysm;

import com.iridium126.createmanaindustry.compat.ysm.model.YsmModelSnapshot;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared session LRU so model-id lookups and persistent references do not retain duplicate snapshots. */
final class YsmSnapshotCache {
    private static final long MAX_WEIGHT = 256L * 1024 * 1024;
    private static final int MAX_SNAPSHOTS = 32;

    private final LinkedHashMap<String, YsmModelSnapshot> entries = new LinkedHashMap<>(16, .75f, true);
    private long weight;

    synchronized YsmModelSnapshot get(String digest) { return entries.get(digest); }

    synchronized boolean contains(String digest) { return entries.containsKey(digest); }

    synchronized void put(YsmModelSnapshot snapshot) {
        YsmModelSnapshot previous = entries.remove(snapshot.digest());
        if (previous != null) weight -= previous.weight();
        entries.put(snapshot.digest(), snapshot);
        weight += snapshot.weight();

        Iterator<Map.Entry<String, YsmModelSnapshot>> iterator = entries.entrySet().iterator();
        while (entries.size() > 1 && (entries.size() > MAX_SNAPSHOTS || weight > MAX_WEIGHT)) {
            Map.Entry<String, YsmModelSnapshot> eldest = iterator.next();
            weight -= eldest.getValue().weight();
            iterator.remove();
        }
    }

    synchronized void clear() {
        entries.clear();
        weight = 0;
    }
}
