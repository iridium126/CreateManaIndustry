package com.iridium126.createmanaindustry.dimension.net;

import java.util.*;
import it.unimi.dsi.fastutil.longs.*;
import it.unimi.dsi.fastutil.ints.*;
import com.iridium126.createmanaindustry.dimension.cube.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;

/** Server-thread owner of watches and coalesced updates. Revisions describe network events only. */
public final class AllvrCubeBroadcaster {
    private final AllvrCubeMap map;
    private final Map<UUID, Long2LongOpenHashMap> watches = new HashMap<>();
    private final Map<UUID, LongOpenHashSet> resync = new HashMap<>();
    private final Long2ObjectOpenHashMap<IntOpenHashSet> changes = new Long2ObjectOpenHashMap<>();
    private long nextGeneration;
    private record SendBudget(long tick, int sent) {}
    private final Map<UUID, SendBudget> sendBudgets = new HashMap<>();
    public AllvrCubeBroadcaster(AllvrCubeMap map) { this.map = map; }
    public boolean watch(ServerPlayer player, AllvrCube cube, ClientboundAllvrCubePacket packet) {
        long now = map.getLevel().getGameTime();
        var budget = sendBudgets.get(player.getUUID());
        int sent = budget != null && budget.tick == now ? budget.sent : 0;
        if (sent >= 24) return false;
        sendBudgets.put(player.getUUID(), new SendBudget(now, sent + 1));
        long generation = ++nextGeneration;
        watches.computeIfAbsent(player.getUUID(), id -> new Long2LongOpenHashMap()).put(packet.cubePos(), generation);
        player.connection.send(new ClientboundAllvrCubePacket(packet.cubePos(), generation, cube.networkRevision(), packet.payload()));
        return true;
    }
    public void forget(ServerPlayer player, long key) {
        var watched = watches.get(player.getUUID());
        if (watched == null || !watched.containsKey(key)) return;
        player.connection.send(new ClientboundAllvrForgetCubePacket(key, watched.remove(key)));
        var pending = resync.get(player.getUUID());
        if (pending != null) pending.remove(key);
    }
    public void reset(UUID player) { watches.remove(player); resync.remove(player); sendBudgets.remove(player); }
    public void changed(BlockPos pos) {
        changes.computeIfAbsent(AllvrCubePos.asLong(pos), key -> new IntOpenHashSet()).add(AllvrCube.localIndex(pos));
    }
    public void requestResync(ServerPlayer player, long key, long generation) {
        var watched = watches.get(player.getUUID());
        if (watched == null || !watched.containsKey(key) || watched.get(key) != generation) return;
        var pending = resync.computeIfAbsent(player.getUUID(), id -> new LongOpenHashSet());
        if (pending.size() < 256) pending.add(key);
    }
    public void flush() {
        // Callbacks during update-tag creation may queue another change. Own a detached batch.
        var batch = new Long2ObjectOpenHashMap<IntOpenHashSet>(changes);
        changes.clear();
        for (var entry : batch.long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            var cube = map.getLoadedCube(key);
            if (cube == null) continue;
            for (int cell : entry.getValue()) {
                var c = cube.getPos();
                BlockPos pos = new BlockPos(c.minBlockX() + (cell & 31), c.minBlockY() + (cell >> 10), c.minBlockZ() + ((cell >> 5) & 31));
                var state = cube.getBlockState(pos);
                var be = cube.getBlockEntity(pos);
                var rawUpdate = be == null ? null : be.getUpdatePacket();
                var update = rawUpdate instanceof net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket data ? data : null;
                var tag = be == null ? null : update == null ? be.getUpdateTag(map.getLevel().registryAccess()) : update.getTag();
                long revision = cube.nextNetworkRevision();
                for (ServerPlayer player : map.getLevel().players()) {
                    var watched = watches.get(player.getUUID());
                    if (watched != null && watched.containsKey(key)) player.connection.send(
                        new ClientboundAllvrBlockUpdatePacket(key, watched.get(key), revision, cell, Block.getId(state), tag, update != null));
                }
            }
        }
        for (ServerPlayer player : map.getLevel().players()) {
            var pending = resync.get(player.getUUID());
            if (pending == null) continue;
            int budget = 4;
            var it = pending.iterator();
            while (budget-- > 0 && it.hasNext()) {
                long key = it.nextLong();
                var cube = map.getLoadedCube(key);
                var watched = watches.get(player.getUUID());
                if (cube != null && watched != null && watched.containsKey(key)) {
                    if (!watch(player, cube, ClientboundAllvrCubePacket.of(cube, map.getLevel().registryAccess()))) break;
                }
                it.remove();
            }
        }
    }
    public void clear() { watches.clear(); resync.clear(); changes.clear(); sendBudgets.clear(); }
}
