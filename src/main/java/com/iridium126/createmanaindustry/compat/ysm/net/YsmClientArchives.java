package com.iridium126.createmanaindustry.compat.ysm.net;

import com.iridium126.createmanaindustry.compat.ysm.model.YsmResourceArchive;
import com.iridium126.createmanaindustry.compat.ysm.YsmChatMessages;
import com.iridium126.createmanaindustry.util.concurrent.CMIThreadFactory;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Client-only, session-scoped digest cache and revision-checked reassembler. */
@EventBusSubscriber(modid = "createmanaindustry", value = Dist.CLIENT)
public final class YsmClientArchives {
    private static final int MAX_TRANSFERS = 2;
    private static final ExecutorService WORKER =
            Executors.newSingleThreadExecutor(CMIThreadFactory.daemonFactory("ysm-client-archive"));
    private static final long TRANSFER_IDLE_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
    private static final LinkedHashMap<String, YsmResourceArchive> CACHE = new LinkedHashMap<>(4, .75f, true);
    private static final Map<UUID, Long> REVISIONS = new HashMap<>();
    private static final Map<UUID, Incoming> INCOMING = new HashMap<>();
    private static long session;
    private record ExportResult(String path, String error) {}
    private record Incoming(ClientboundYsmArchivePacket header, ByteArrayOutputStream bytes, int next, long lastActivity) {
        Incoming append(byte[] chunk) { bytes.writeBytes(chunk); return new Incoming(header, bytes, next + 1, System.nanoTime()); }
    }

    public static void accept(ClientboundYsmArchivePacket packet) {
        if (packet.kind() == ClientboundYsmArchivePacket.CLEAR) {
            if (packet.revision() < REVISIONS.getOrDefault(packet.target(), Long.MIN_VALUE)) return;
            REVISIONS.put(packet.target(), packet.revision());
            INCOMING.values().removeIf(in -> in.header.target().equals(packet.target()) && in.header.kind() == ClientboundYsmArchivePacket.APPLY);
            return;
        }
        if (packet.kind() == ClientboundYsmArchivePacket.CHUNK) {
            var incoming = INCOMING.get(packet.transfer());
            if (incoming == null || !incoming.header.target().equals(packet.target())
                    || incoming.header.revision() != packet.revision() || incoming.next() != packet.index()) return;
            int have = incoming.bytes.size();
            if (packet.bytes().length > incoming.header.size() - have) { INCOMING.remove(packet.transfer()); return; }
            incoming = incoming.append(packet.bytes());
            if (incoming.bytes.size() == incoming.header.size()) {
                INCOMING.remove(packet.transfer());
                var header = incoming.header(); byte[] content = incoming.bytes.toByteArray();
                long startedInSession = session;
                CompletableFuture.supplyAsync(() -> YsmResourceArchive.decode(content), WORKER).whenComplete((archive, failure) ->
                    Minecraft.getInstance().execute(() -> {
                        if (startedInSession != session) return;
                        if (failure != null || !archive.digest().equals(header.digest())) {
                            message(YsmChatMessages.transferCheckFailed()); return;
                        }
                        if (header.kind() == ClientboundYsmArchivePacket.APPLY
                                && header.revision() != REVISIONS.getOrDefault(header.target(), Long.MIN_VALUE)) return;
                        cache(archive);
                        if (header.kind() == ClientboundYsmArchivePacket.EXPORT) export(archive);
                    }));
            } else INCOMING.put(packet.transfer(), incoming);
            return;
        }
        if (packet.kind() == ClientboundYsmArchivePacket.APPLY) {
            if (packet.revision() < REVISIONS.getOrDefault(packet.target(), Long.MIN_VALUE)) return;
            REVISIONS.put(packet.target(), packet.revision());
            INCOMING.values().removeIf(in -> in.header.target().equals(packet.target()) && in.header.kind() == ClientboundYsmArchivePacket.APPLY);
        }
        var cached = CACHE.get(packet.digest());
        if (cached != null) {
            if (packet.kind() == ClientboundYsmArchivePacket.EXPORT) export(cached);
            return;
        }
        if (INCOMING.size() >= MAX_TRANSFERS) INCOMING.remove(INCOMING.keySet().iterator().next());
        INCOMING.put(packet.transfer(), new Incoming(packet, new ByteArrayOutputStream(packet.size()), 0, System.nanoTime()));
    }
    private static void cache(YsmResourceArchive archive) {
        CACHE.put(archive.digest(), archive);
        while (CACHE.size() > 2) CACHE.remove(CACHE.keySet().iterator().next());
    }
    private static void export(YsmResourceArchive archive) {
        Path gameDirectory = Minecraft.getInstance().gameDirectory.toPath();
        long startedInSession = session;
        CompletableFuture.supplyAsync(() -> {
            try { return new ExportResult(archive.export(gameDirectory).toString(), null); }
            catch (Exception failure) { return new ExportResult(null, failure.getMessage()); }
        }, WORKER).thenAccept(result -> Minecraft.getInstance().execute(() -> {
            if (startedInSession == session) message(result.error() == null
                    ? YsmChatMessages.exportSucceeded(result.path()) : YsmChatMessages.exportFailed(result.error()));
        }));
    }
    private static void message(Component message) {
        if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.sendSystemMessage(message);
    }
    @SubscribeEvent public static void onTick(ClientTickEvent.Post event) {
        long now = System.nanoTime();
        boolean lostExport = false;
        var cursor = INCOMING.values().iterator();
        while (cursor.hasNext()) {
            Incoming transfer = cursor.next();
            if (now - transfer.lastActivity() >= TRANSFER_IDLE_NANOS) {
                lostExport |= transfer.header().kind() == ClientboundYsmArchivePacket.EXPORT;
                cursor.remove();
            }
        }
        if (lostExport) message(YsmChatMessages.transferInterrupted());
    }
    @SubscribeEvent public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        session++;
        CACHE.clear(); REVISIONS.clear(); INCOMING.clear();
    }
    private YsmClientArchives() {}
}
