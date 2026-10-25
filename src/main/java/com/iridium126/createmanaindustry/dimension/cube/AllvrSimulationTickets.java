package com.iridium126.createmanaindustry.dimension.cube;

import java.util.List;
import it.unimi.dsi.fastutil.longs.*;
import net.minecraft.server.level.ServerPlayer;

/** Independent simulation cylinder. View-only cubes never run gameplay ticks. */
public final class AllvrSimulationTickets {
    private final LongOpenHashSet active = new LongOpenHashSet();
    private long revision;
    public boolean contains(long key) { return active.contains(key); }
    public long revision() { return revision; }
    public long[] keys() { return active.toLongArray(); }
    public void clear() { if (!active.isEmpty()) { active.clear(); revision++; } }
    public void rebuild(List<ServerPlayer> players, int distance, Long2ObjectMap<AllvrCube> loaded) {
        var next = new LongOpenHashSet();
        int radius = (distance + 1) / 2;
        for (var player : players) {
            if (player.isSpectator()) continue;
            var center = AllvrCubePos.of(player.blockPosition());
            int px = AllvrVanillaRenderDistance.blockToChunk(player.getX()), pz = AllvrVanillaRenderDistance.blockToChunk(player.getZ());
            for (int y = center.getY() - radius; y <= center.getY() + radius; y++)
                for (int z = center.getZ() - radius - 1; z <= center.getZ() + radius + 1; z++)
                    for (int x = center.getX() - radius - 1; x <= center.getX() + radius + 1; x++) {
                        long key = AllvrCubePos.asLong(x, y, z);
                        if (loaded.containsKey(key) && AllvrVanillaRenderDistance.isCubeWithinCylinder(x, y, z, px, pz, center.getY(), distance, radius)) next.add(key);
                    }
        }
        if (!active.equals(next)) { active.clear(); active.addAll(next); revision++; }
    }
}
