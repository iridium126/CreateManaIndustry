package com.iridium126.createmanaindustry.dimension.cube;

import java.util.List;
import java.util.ArrayDeque;
import java.util.function.LongConsumer;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

/** Entity files remain XZ columns, including columns whose terrain is entirely void. */
public final class AllvrEntityColumnTickets {
    private final LongOpenHashSet requested = new LongOpenHashSet();
    private final ArrayDeque<Long> pending = new ArrayDeque<>();
    public void rebuild(List<ServerPlayer> players, int distance) {
        var needed = new LongOpenHashSet();
        for (var player : players) {
            int x = player.getBlockX() >> 4, z = player.getBlockZ() >> 4;
            for (int dz = -distance; dz <= distance; dz++) for (int dx = -distance; dx <= distance; dx++)
                needed.add(ChunkPos.asLong(x + dx, z + dz));
        }
        pending.removeIf(key -> !needed.contains(key));
        requested.retainAll(needed);
        for (long key : needed) if (requested.add(key)) pending.add(key);
    }
    public void tick(LongConsumer request) { for (int n = 0; n < 16 && !pending.isEmpty(); n++) request.accept(pending.remove()); }
    public void clear() { requested.clear(); pending.clear(); }
    public int pending() { return pending.size(); }
}
