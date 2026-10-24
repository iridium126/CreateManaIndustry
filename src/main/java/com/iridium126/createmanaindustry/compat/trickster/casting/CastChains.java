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
    public static CastChain find(ServerPlayer player, UUID id) {
        if (id == null) return null;
        var chains = LIVE.get(player.getServer());
        CastChain chain = chains == null ? null : chains.get(id);
        return chain != null && chain.live() && chain.runtime() == player.getServer() && player.getUUID().equals(chain.player()) ? chain : null;
    }
    public static CastChain attach(CastChainSnapshot snapshot, ServerPlayer player, Object owner) {
        if (snapshot != null && snapshot.player().isPresent() && !snapshot.player().get().equals(player.getUUID()))
            throw new IllegalStateException("Cross-player casting snapshot");
        CastChain live = snapshot == null ? null : find(player, snapshot.id());
        return attach(live != null ? live : snapshot == null ? new CastChain() : CastChain.restore(snapshot), player, owner);
    }
    public static CastChain attach(CastChain chain, ServerPlayer player, Object owner) {
        chain.bind(player.getUUID());
        if (chain.runtime() != null && chain.runtime() != player.getServer()) throw new IllegalStateException("Cross-server cast chain");
        var chains = LIVE.computeIfAbsent(player.getServer(), key -> new HashMap<>());
        CastChain canonical = chains.get(chain.id());
        if (canonical != null) {
            canonical.bind(player.getUUID());
            canonical.runtime(player.getServer());
        }
        if (canonical == null || !canonical.live()) canonical = chain;
        canonical.bind(player.getUUID());
        canonical.runtime(player.getServer());
        chains.put(canonical.id(), canonical);
        canonical.retain(owner);
        try {
            PartitionManager.resume(canonical, player);
            SharedFateManager.resume(canonical, player);
            return canonical;
        } catch (RuntimeException | Error failure) {
            release(canonical, owner);
            throw failure;
        }
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
    public static void resetStaff(ServerPlayer player, UUID id) {
        CastChain live = find(player, id);
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
            chain.clearFateTargets();
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
