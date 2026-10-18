package com.iridium126.createmanaindustry.compat.trickster.partition;

import com.iridium126.createmanaindustry.compat.trickster.net.ClientboundPartitionPacket;
import com.iridium126.createmanaindustry.compat.trickster.partition.PartitionManager.Domain;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/** Observer state and bounded packet batching. Authority remains in PartitionManager. */
final class PartitionSynchronizer {
    private static final Map<ServerPlayer, Map<UUID, ClientboundPartitionPacket>> KNOWN = new WeakHashMap<>();
    private PartitionSynchronizer() {}

    static void sync(MinecraftServer server, Collection<Domain> domains) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            var known = KNOWN.computeIfAbsent(player, key -> new HashMap<>());
            var desired = new HashMap<UUID, ClientboundPartitionPacket>();
            // Distant teleport prediction requires every domain in this world.
            for (Domain domain : domains) {
                if (domain.level() != player.serverLevel()) continue;
                var packet = new ClientboundPartitionPacket(domain.level().dimension().location(), domain.id(),
                        domain.barrier().bounds, false, domain.born() == domain.level().getGameTime(), List.copyOf(domain.barrier().escaping));
                desired.put(domain.id(), packet);
                var old = known.get(domain.id());
                if (old == null || !old.escaping().equals(packet.escaping())) send(player, packet);
            }
            for (var old : known.values()) if (!desired.containsKey(old.id()))
                send(player, new ClientboundPartitionPacket(old.dimension(), old.id(), old.bounds(), true, false, List.of()));
            known.clear();
            known.putAll(desired);
        }
    }
    private static void send(ServerPlayer player, ClientboundPartitionPacket packet) {
        for (int index = 0; index < Math.max(1, packet.escaping().size()); index += 1024) {
            PacketDistributor.sendToPlayer(player, new ClientboundPartitionPacket(packet.dimension(), packet.id(), packet.bounds(),
                    packet.remove(), packet.animate(), packet.escaping().subList(index, Math.min(index + 1024, packet.escaping().size())), index > 0));
        }
    }
    static void stopped(MinecraftServer server) { KNOWN.keySet().removeIf(player -> player.getServer() == server); }
}
