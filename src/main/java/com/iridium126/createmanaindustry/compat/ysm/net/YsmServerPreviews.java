package com.iridium126.createmanaindustry.compat.ysm.net;

import com.iridium126.createmanaindustry.compat.ysm.YsmReferenceStore;
import com.iridium126.createmanaindustry.compat.ysm.render.YsmGeometryThumbnail;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/** Bounded asynchronous preview rasterizer with a session-local PNG cache. */
public final class YsmServerPreviews implements AutoCloseable {
    private record Key(String value, boolean cube) {}
    private record Result(Key key, byte[] png, String error, boolean retryable) {}

    private static final int MAX_CACHE_ENTRIES = 1024;
    private static final long MAX_CACHE_BYTES = 32L * 1024 * 1024;

    private final MinecraftServer server;
    private final YsmReferenceStore references;
    private final ThreadPoolExecutor workers = createWorkers();
    private final YsmPngCache<Key> cache = new YsmPngCache<>(MAX_CACHE_ENTRIES, MAX_CACHE_BYTES);
    private final YsmPreviewWaiters<Key> inFlight = new YsmPreviewWaiters<>();
    private volatile boolean closed;

    public YsmServerPreviews(MinecraftServer server, YsmReferenceStore references) {
        this.server = server;
        this.references = references;
    }

    /** Must be called on the server thread. Equal requests share one render task. */
    public void request(ServerPlayer player, String key, boolean cube) {
        if (closed || player.server != server || player.hasDisconnected()) return;
        Key request = new Key(key, cube);
        byte[] cached = cache.get(request);
        if (cached != null) {
            send(player, ClientboundYsmPreviewPacket.image(key, cube, cached));
            return;
        }
        if (!inFlight.add(request, player.getUUID())) return;
        try {
            workers.execute(() -> render(request));
        } catch (RejectedExecutionException full) {
            inFlight.take(request);
            send(player, ClientboundYsmPreviewPacket.error(key, cube, true,
                    "YSM preview queue is full; retry later"));
        }
    }

    private void render(Key key) {
        byte[] png = null;
        String error = null;
        boolean retryable = false;
        try {
            png = YsmGeometryThumbnail.renderPng(references.preview(key.value(), key.cube()));
        } catch (RuntimeException failure) {
            error = failure.getMessage() == null ? "YSM preview is unavailable" : failure.getMessage();
            String normalized = error.toLowerCase(Locale.ROOT);
            retryable = normalized.contains("loading") || normalized.contains("busy") || normalized.contains("retry later");
        }
        Result result = new Result(key, png, error, retryable);
        try { server.execute(() -> complete(result)); }
        catch (RejectedExecutionException ignored) { /* Server is stopping; discard the result. */ }
    }

    /** Runs on the server thread to update the cache and deliver completed images. */
    private void complete(Result result) {
        if (closed) return;
        Optional<List<UUID>> recipients = inFlight.take(result.key());
        if (recipients.isEmpty()) return;
        if (result.png() != null) cache(result.key(), result.png());
        for (UUID id : recipients.get()) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null || player.hasDisconnected()) continue;
            ClientboundYsmPreviewPacket packet = result.png() != null
                    ? ClientboundYsmPreviewPacket.image(result.key().value(), result.key().cube(), result.png())
                    : ClientboundYsmPreviewPacket.error(result.key().value(), result.key().cube(), result.retryable(), result.error());
            send(player, packet);
        }
    }

    private void cache(Key key, byte[] png) {
        cache.put(key, png);
    }

    private static void send(ServerPlayer player, ClientboundYsmPreviewPacket packet) {
        PacketDistributor.sendToPlayer(player, packet);
    }

    /** Disconnecting a viewer drops their waiter while shared rendering can still populate the cache. */
    public void forget(UUID player) {
        inFlight.forget(player);
    }

    @Override public void close() {
        closed = true;
        workers.shutdownNow();
        inFlight.clear();
        cache.clear();
    }

    static ThreadPoolExecutor createWorkers() {
        return new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(32), task -> {
                    Thread thread = new Thread(task, "CMI YSM preview renderer");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }
}
