package com.iridium126.createmanaindustry.compat.trickster.casting;

import com.iridium126.createmanaindustry.compat.trickster.partition.PartitionManager;
import com.iridium126.createmanaindustry.compat.trickster.sharedfate.SharedFateManager;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

public final class CastChains {
    private static final ThreadLocal<CastChain> CURRENT = new ThreadLocal<>();
    private static final Map<MinecraftServer, Map<UUID, CastChain>> LIVE = new IdentityHashMap<>();
    private CastChains() {
    }
    public static CastChain current() {
        return CURRENT.get();
    }
    public static boolean inverted() {
        return current() != null && current().inverted();
    }
    public static boolean belongsTo(UUID player) {
        return current() != null && player.equals(current().player());
    }
    public static boolean invertedFor(UUID player) {
        return inverted() && belongsTo(player);
    }
    public static CastChain inherit() {
        return current() == null ? new CastChain() : current();
    }
    public static CastChain find(ServerPlayer player, String encoded) {
        if (encoded.isEmpty()) return null;
        var chains=LIVE.get(player.getServer());
        if(chains==null)return null;
        CastChain chain=chains.get(CastChain.decode(encoded).id());
        return chain!=null&&player.getUUID().equals(chain.player())?chain:null;
    }
    public static CastChain attach(CastChain chain, ServerPlayer player, Object owner) {
        chain.bind(player.getUUID());
        var chains = LIVE.computeIfAbsent(player.getServer(), key -> new HashMap<>());
        CastChain canonical = chains.computeIfAbsent(chain.id(), key -> chain);
        canonical.runtime(player.getServer());
        canonical.retain(owner);
        PartitionManager.resume(canonical, player);
        SharedFateManager.resume(canonical, player);
        return canonical;
    }
    public static void release(CastChain chain, Object owner) {
        if (chain == null) return;
        chain.release(owner);
        if (!chain.live()) {
            PartitionManager.end(chain);
            SharedFateManager.endChain(chain);
        }
        var chains = LIVE.get(chain.runtime());
        if (chains != null && !chain.live()) chains.remove(chain.id(), chain);
    }
    public static void resetStaff(ServerPlayer player, String encoded) {
        if (encoded.isEmpty()) return;
        var chains = LIVE.get(player.getServer());
        if (chains == null) return;
        CastChain saved = CastChain.decode(encoded);
        CastChain live = chains.get(saved.id());
        if (live != null) release(live, live);
    }
    public static boolean nameExempt(MinecraftServer server, UUID player) {
        Map<UUID, CastChain> chains = LIVE.get(server);
        return chains != null && chains.values().stream().anyMatch(c -> c.live() && c.inverted() && player.equals(c.player()));
    }
    public static Scope enter(CastChain chain) {
        CastChain previous = CURRENT.get();
        CURRENT.set(chain);
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }
    public interface Scope extends AutoCloseable {
        void close();
    }
    public static void register() {
        NeoForge.EVENT_BUS.addListener(CastChains::died);
        NeoForge.EVENT_BUS.addListener(CastChains::loggedOut);
        NeoForge.EVENT_BUS.addListener(CastChains::stopped);
    }
    private static void died(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        SharedFateManager.suspend(player.getServer(), player.getUUID());
        var chains = LIVE.get(player.getServer());
        if (chains == null) return;
        chains.values().removeIf(chain -> {
            if (!player.getUUID().equals(chain.player())) return false;
            PartitionManager.end(chain);
            chain.restoreFateTargets(List.of());
            chain.suspend();
            return true;
        });
    }
    private static void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        SharedFateManager.suspend(player.getServer(), player.getUUID());
        InvertedPayments.logout(player);
        var chains = LIVE.get(player.getServer());
        if (chains == null) return;
        chains.values().removeIf(chain -> {
            if (!player.getUUID().equals(chain.player())) return false;
            PartitionManager.suspend(chain);
            chain.suspend();
            return true;
        });
    }
    private static void stopped(ServerStoppedEvent event) {
        LIVE.remove(event.getServer());
        CURRENT.remove();
        InvertedPayments.stopped(event.getServer());
    }
}
