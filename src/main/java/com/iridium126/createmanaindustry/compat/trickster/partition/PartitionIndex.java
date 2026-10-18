package com.iridium126.createmanaindustry.compat.trickster.partition;

import com.iridium126.createmanaindustry.compat.trickster.partition.PartitionManager.Domain;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** World-partitioned ordered X bounds; query semantics remain independent of networking. */
final class PartitionIndex {
    private final Map<ServerLevel, NavigableMap<Integer, List<Domain>>> worlds = new IdentityHashMap<>();

    boolean active(Level level) {
        var index = worlds.get(level);
        return index != null && !index.isEmpty();
    }
    Collection<List<Domain>> before(Level level, double high) {
        var index = worlds.get(level);
        return index == null ? List.of() : index.headMap((int) Math.floor(high), true).values();
    }
    void add(Domain domain) {
        worlds.computeIfAbsent(domain.level(), key -> new TreeMap<>())
                .computeIfAbsent(domain.barrier().bounds.minX(), key -> new ArrayList<>()).add(domain);
    }
    void remove(Domain domain) {
        var index = worlds.get(domain.level());
        if (index == null) return;
        int minX = domain.barrier().bounds.minX();
        var bucket = index.get(minX);
        if (bucket != null) {
            bucket.remove(domain);
            if (bucket.isEmpty()) index.remove(minX);
        }
        if (index.isEmpty()) worlds.remove(domain.level());
    }
    void clear(MinecraftServer server) {
        worlds.keySet().removeIf(level -> {
            if (level.getServer() != server) return false;
            PartitionCollision.clear(level);
            return true;
        });
    }
}
