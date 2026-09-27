package com.iridium126.createmanaindustry.compat.ysm.net;

import com.iridium126.createmanaindustry.compat.ysm.model.YsmResourceArchive;
import com.iridium126.createmanaindustry.infrastructure.concurrent.CMIThreadFactory;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/** Rate-limited CMI archive transport. The native YSM sync still owns model rendering. */
public final class YsmServerArchives implements AutoCloseable {
    private final MinecraftServer server;
    private final ExecutorService encoder =
            Executors.newSingleThreadExecutor(CMIThreadFactory.daemonFactory("ysm-archive-encoder"));
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private final ArrayDeque<Job> jobs = new ArrayDeque<>();
    private final Map<UUID, UUID> transmitting = new HashMap<>();
    private final LinkedHashMap<String, CompletableFuture<byte[]>> encoded = new LinkedHashMap<>(4, .75f, true);
    private record Job(UUID recipient, UUID target, long revision, int purpose, String model,
            YsmResourceArchive archive, UUID transfer, CompletableFuture<byte[]> bytes, int next, boolean begun) {
        Job advance() { return new Job(recipient, target, revision, purpose, model, archive, transfer, bytes, next + 1, true); }
        Job markBegun() { return new Job(recipient, target, revision, purpose, model, archive, transfer, bytes, next, true); }
    }
    public YsmServerArchives(MinecraftServer server) { this.server = server; }
    public boolean canQueue() { return jobs.size() < 16; }
    public void export(ServerPlayer caster, YsmResourceArchive archive) {
        if (!schedule(caster.getUUID(), caster.getUUID(), 0, ClientboundYsmArchivePacket.EXPORT, "", archive))
            throw new IllegalStateException("YSM export queue is full; retry later");
    }
    public void apply(ServerPlayer target, long revision, String model, YsmResourceArchive archive) {
        if (archive == null) return;
        cancelApply(target.getUUID(), null);
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            if (viewer.serverLevel() == target.serverLevel() && viewer.distanceToSqr(target) < 192 * 192)
                if (!schedule(viewer.getUUID(), target.getUUID(), revision, ClientboundYsmArchivePacket.APPLY, model, archive))
                    LOGGER.warn("YSM archive queue full while syncing model {} to {}", model, viewer.getUUID());
        }
    }
    public void startTracking(ServerPlayer viewer, ServerPlayer target, long revision, String model, YsmResourceArchive archive) {
        cancelApply(target.getUUID(), viewer.getUUID());
        if (archive != null && !schedule(viewer.getUUID(), target.getUUID(), revision, ClientboundYsmArchivePacket.APPLY, model, archive))
            LOGGER.warn("YSM archive queue full while syncing model {} to new observer {}", model, viewer.getUUID());
    }
    public void clear(ServerPlayer target, long revision) {
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            if (!viewer.hasDisconnected() && viewer.serverLevel() == target.serverLevel() && viewer.distanceToSqr(target) < 192 * 192)
                PacketDistributor.sendToPlayer(viewer, ClientboundYsmArchivePacket.clear(target.getUUID(), revision));
        }
        cancelApply(target.getUUID(), null);
    }
    private void cancelApply(UUID target, UUID recipient) {
        jobs.removeIf(job -> {
            boolean cancel = job.target.equals(target) && job.purpose == ClientboundYsmArchivePacket.APPLY
                    && (recipient == null || job.recipient.equals(recipient));
            if (cancel) transmitting.remove(job.recipient, job.transfer);
            return cancel;
        });
    }
    private boolean schedule(UUID recipient, UUID target, long revision, int purpose, String model, YsmResourceArchive archive) {
        if (jobs.size() >= 16) return false;
        var bytes = encoded.computeIfAbsent(archive.digest(),
                ignored -> CompletableFuture.supplyAsync(archive::encode, encoder));
        while (encoded.size() > 2) encoded.remove(encoded.keySet().iterator().next());
        jobs.addLast(new Job(recipient, target, revision, purpose, model, archive, UUID.randomUUID(),
                bytes, 0, false));
        return true;
    }
    public void tick() {
        int budget = 16;
        int rounds = 16;
        while (budget > 0 && rounds-- > 0 && !jobs.isEmpty()) {
            Job job = jobs.removeFirst();
            ServerPlayer viewer = server.getPlayerList().getPlayer(job.recipient);
            if (viewer == null || viewer.hasDisconnected()) {
                transmitting.remove(job.recipient, job.transfer);
                continue;
            }
            if (!job.bytes.isDone()) { jobs.addLast(job); continue; }
            byte[] bytes;
            try { bytes = job.bytes.join(); }
            catch (Exception failure) { transmitting.remove(job.recipient, job.transfer); continue; }
            if (!job.begun) {
                UUID current = transmitting.get(job.recipient);
                if (current != null && !current.equals(job.transfer)) { jobs.addLast(job); continue; }
                transmitting.put(job.recipient, job.transfer);
                PacketDistributor.sendToPlayer(viewer, ClientboundYsmArchivePacket.begin(job.purpose, job.transfer,
                        job.target, job.revision, job.archive.digest(), job.model, bytes.length));
                job = job.markBegun(); budget--;
            }
            if (budget > 0 && job.next * ClientboundYsmArchivePacket.CHUNK_BYTES < bytes.length) {
                int from = job.next * ClientboundYsmArchivePacket.CHUNK_BYTES;
                byte[] chunk = Arrays.copyOfRange(bytes, from, Math.min(bytes.length, from + ClientboundYsmArchivePacket.CHUNK_BYTES));
                PacketDistributor.sendToPlayer(viewer, ClientboundYsmArchivePacket.chunk(job.transfer, job.target, job.revision, job.next, chunk));
                job = job.advance(); budget--;
            }
            if (job.next * ClientboundYsmArchivePacket.CHUNK_BYTES < bytes.length) jobs.addLast(job);
            else transmitting.remove(job.recipient, job.transfer);
        }
    }
    public void forget(UUID recipient) { jobs.removeIf(job -> job.recipient.equals(recipient)); transmitting.remove(recipient); }
    @Override public void close() {
        jobs.clear(); transmitting.clear(); encoded.clear();
        encoder.shutdownNow();
    }
}
