package com.iridium126.createmanaindustry.compat.ysm.net;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Access-ordered byte-bounded LRU used only for encoded preview PNGs. */
final class YsmPngCache<K> {
    private final int maxEntries;
    private final long maxBytes;
    private final LinkedHashMap<K, byte[]> entries = new LinkedHashMap<>(128, .75f, true);
    private long bytes;

    YsmPngCache(int maxEntries, long maxBytes) {
        if (maxEntries < 1 || maxBytes < 1) throw new IllegalArgumentException("Cache bounds must be positive");
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
    }

    byte[] get(K key) {
        byte[] value = entries.get(key);
        return value == null ? null : value.clone();
    }

    void put(K key, byte[] value) {
        if (value == null || value.length == 0) throw new IllegalArgumentException("Empty preview cache value");
        byte[] previous = entries.remove(key);
        if (previous != null) bytes -= previous.length;
        if (value.length > maxBytes) return;
        entries.put(key, value.clone());
        bytes += value.length;
        Iterator<Map.Entry<K, byte[]>> iterator = entries.entrySet().iterator();
        while ((entries.size() > maxEntries || bytes > maxBytes) && iterator.hasNext()) {
            Map.Entry<K, byte[]> eldest = iterator.next();
            bytes -= eldest.getValue().length;
            iterator.remove();
        }
    }

    void clear() {
        entries.clear();
        bytes = 0;
    }

    int size() { return entries.size(); }
    long bytes() { return bytes; }
}
