package com.iridium126.createmanaindustry.compat.ysm;

import com.iridium126.createmanaindustry.util.concurrent.CMIThreadFactory;

import com.iridium126.createmanaindustry.compat.ysm.model.YsmResourceArchive;
import com.iridium126.createmanaindustry.compat.ysm.net.YsmServerArchives;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.util.INBTSerializable;

/** Session-scoped resource deployment and per-player selection; all public calls run on the server thread. */
public final class YsmOverrideManager implements AutoCloseable {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private record Selection(String model, String texture) {}
    private record ActiveOverride(Selection original, Selection selected, YsmResourceArchive archive) {}
    private record Request(UUID player, long revision, Selection expected, Selection original, String resourceId,
                           String texture, YsmResourceArchive archive, Map<UUID, Selection> selections) {}
    private record Staged(Path directory, boolean owned) {}
    private record Load(String id, String digest, Staged staged, CompletableFuture<String> future,
                        Map<UUID, Selection> selections) {}
    private final MinecraftServer server;
    private final YsmRuntimeSymbols.Snapshot symbols;
    private final YsmServerArchives transfers;
    private final ThreadPoolExecutor files = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), CMIThreadFactory.daemonFactory("ysm-model-staging"));
    private final Map<UUID, Long> revisions = new HashMap<>();
    private final Map<UUID, ActiveOverride> overrides = new HashMap<>();
    private final Map<UUID, Long> pendingReassert = new HashMap<>();
    private final Map<String, CompletableFuture<String>> deployments = new HashMap<>();
    private final ArrayDeque<Load> loads = new ArrayDeque<>();
    private final Set<Path> owned = new HashSet<>();
    private boolean loading, closed;

    public YsmOverrideManager(MinecraftServer server, YsmRuntimeSymbols.Snapshot symbols, YsmServerArchives transfers) {
        this.server = server; this.symbols = symbols; this.transfers = transfers;
    }
    public void apply(ServerPlayer target, YsmResourceArchive archive) {
        if (closed || target.server != server || target.hasDisconnected()) throw new IllegalStateException("Target player left the server");
        UUID id = target.getUUID();
        Selection selected = selection(target);
        ActiveOverride previous = overrides.get(id);
        if (previous != null && !selected.model.equals(previous.selected.model)) {
            overrides.remove(id); previous = null;
        }
        Selection original = previous == null ? selected : previous.original;
        long revision = revisions.merge(id, 1L, Long::sum);
        String modelId = "cmi_" + archive.digest().substring(0, 24);
        // YSM may queue a second player-selection reset after its native loader callback.
        // Keep this snapshot through activation as well as through the catalog callback.
        Map<UUID, Selection> selections = new HashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers())
            selections.put(player.getUUID(), selection(player));
        Request request = new Request(id, revision, selected, original, modelId, archive.defaultTexture(), archive, selections);
        var deployment = deployments.get(archive.digest());
        if (deployment == null) {
            deployment = new CompletableFuture<>();
            deployments.put(archive.digest(), deployment);
            var promised = deployment;
            try {
                files.execute(() -> {
                    try {
                        Staged staged = stage(modelId, archive);
                        server.execute(() -> {
                            if (closed) {
                                if (staged.owned) {
                                    try { removeDirectory(staged.directory); }
                                    catch (IOException failure) { LOGGER.warn("Could not remove staged YSM model {}", staged.directory, failure); }
                                }
                                promised.cancel(false); return;
                            }
                            if (staged.owned) owned.add(staged.directory);
                            LOGGER.info("Staged temporary YSM model {}", modelId);
                            loads.addLast(new Load(modelId, archive.digest(), staged, promised, selections));
                            reloadNext();
                        });
                    } catch (Exception failure) { server.execute(() -> promised.completeExceptionally(failure)); }
                });
            } catch (RejectedExecutionException busy) {
                deployments.remove(archive.digest());
                throw new IllegalStateException("YSM model staging is busy; retry later", busy);
            }
        }
        var promisedDeployment = deployment;
        deployment.whenComplete((loadedId, error) -> server.execute(() -> {
            LOGGER.info("YSM deployment completed for {} (loaded={}, failure={})", modelId, loadedId, error == null ? "none" : error.toString());
            if (error != null) {
                if (deployments.get(archive.digest()) == promisedDeployment) deployments.remove(archive.digest());
                LOGGER.warn("Temporary YSM model deployment failed for {}", modelId, error);
                inform(id, YsmChatMessages.applicationFailed(error.getMessage())); return;
            }
            restoreSelections(request.selections);
            activate(request, loadedId);
        }));
    }
    public void applyExisting(ServerPlayer target, String modelId, String sourceTexture, YsmResourceArchive archive) {
        if (closed || target.server != server || target.hasDisconnected()) throw new IllegalStateException("Target player left the server");
        try {
            if (((Optional<?>) symbols.serverLookup().bind(getClass().getClassLoader()).invoke(null, modelId)).isEmpty())
                throw new IllegalStateException("Source YSM model is no longer loaded");
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Unable to find source YSM model", failure); }
        UUID id = target.getUUID();
        Selection selected = selection(target);
        ActiveOverride prior = overrides.get(id);
        Selection original = prior != null && selected.model.equals(prior.selected.model) ? prior.original : selected;
        long revision = revisions.merge(id, 1L, Long::sum);
        Selection replacement = new Selection(modelId, sourceTexture);
        select(target, replacement);
        overrides.put(id, new ActiveOverride(original, replacement, archive));
        transfers.apply(target, revision, modelId, archive);
        inform(id, YsmChatMessages.applicationSucceeded());
    }
    public boolean restore(ServerPlayer target) {
        if (target.server != server || target.hasDisconnected()) throw new IllegalStateException("Target player left the server");
        UUID id = target.getUUID(); revisions.merge(id, 1L, Long::sum);
        pendingReassert.remove(id);
        ActiveOverride prior = overrides.remove(id);
        boolean restored = false;
        if (prior != null) {
            if (selection(target).model.equals(prior.selected.model)) {
                select(target, prior.original);
                restored = true;
            }
            transfers.clear(target, revisions.get(id));
        }
        return restored;
    }
    public void forget(ServerPlayer target) {
        UUID id = target.getUUID(); revisions.merge(id, 1L, Long::sum);
        pendingReassert.remove(id);
        ActiveOverride prior = overrides.remove(id);
        if (prior != null) {
            if (selection(target).model.equals(prior.selected.model)) select(target, prior.original);
            transfers.clear(target, revisions.get(id));
        }
    }
    public void maintain(ServerPlayer player) {
        // YSM resets selections during its global catalog reload; reconcile them after its callback.
        if (loading || deployments.values().stream().anyMatch(future -> !future.isDone())) return;
        UUID id = player.getUUID();
        ActiveOverride override = overrides.get(id);
        Long due = pendingReassert.get(id);
        if (override != null && due != null) {
            if (server.getTickCount() < due) return;
            pendingReassert.remove(id);
            select(player, override.selected);
            transfers.apply(player, revisions.getOrDefault(id, 0L), override.selected.model, override.archive);
            return;
        }
        if (override != null && !selection(player).model.equals(override.selected.model)) {
            long revision = revisions.merge(player.getUUID(), 1L, Long::sum);
            overrides.remove(player.getUUID());
            transfers.clear(player, revision);
        }
    }
    public void markTransition(ServerPlayer player) {
        if (overrides.containsKey(player.getUUID())) pendingReassert.put(player.getUUID(), (long) server.getTickCount() + 1);
    }
    public void startTracking(ServerPlayer viewer, ServerPlayer target) {
        ActiveOverride override = overrides.get(target.getUUID());
        if (override != null) transfers.startTracking(viewer, target,
                revisions.getOrDefault(target.getUUID(), 0L), override.selected.model, override.archive);
    }
    private void activate(Request request, String modelId) {
        if (closed || !Objects.equals(revisions.get(request.player), request.revision)) {
            LOGGER.info("Discarded stale YSM override revision {} for {}", request.revision, request.player);
            return;
        }
        ServerPlayer target = server.getPlayerList().getPlayer(request.player);
        if (target == null || target.hasDisconnected()) {
            LOGGER.info("Discarded YSM override because target {} left", request.player);
            return;
        }
        Selection current = selection(target);
        if (!current.model.equals(request.expected.model)) {
            LOGGER.info("Discarded YSM override for {} because selected model changed from {} to {}",
                    request.player, request.expected.model, current.model);
            return;
        }
        Selection replacement = new Selection(modelId, request.texture);
        select(target, replacement);
        overrides.put(request.player, new ActiveOverride(request.original, replacement, request.archive));
        transfers.apply(target, request.revision, modelId, request.archive);
        inform(request.player, YsmChatMessages.applicationSucceeded());
    }
    private void reloadNext() {
        if (loading || loads.isEmpty() || closed) return;
        Load load = loads.removeFirst(); loading = true;
        try {
            LOGGER.info("Reloading YSM model {} with {} pre-stage player selections", load.id, load.selections.size());
            LOGGER.info("Requesting native YSM reload for {}", load.id);
            Consumer<Object> callback = result -> {
                LOGGER.info("Native YSM reload callback received for {}", load.id);
                server.execute(() -> {
                try {
                    var methods = Arrays.stream(result.getClass().getMethods()).filter(method -> method.getParameterCount() == 0).toList();
                    var flags = methods.stream().filter(method -> method.getReturnType() == boolean.class).toList();
                    var maps = methods.stream().filter(method -> method.getReturnType() == Map.class).toList();
                    if (flags.size() != 1 || maps.size() != 1 || !(Boolean) flags.getFirst().invoke(result)
                            || !((Map<?, ?>) maps.getFirst().invoke(result)).containsKey(load.id))
                        throw new IllegalStateException("YSM native reload did not publish the modified resource");
                    LOGGER.info("Native YSM catalog published {}", load.id);
                    restoreSelections(load.selections);
                    load.future.complete(load.id);
                } catch (Exception failure) {
                    restoreSelections(load.selections);
                    load.future.completeExceptionally(failure);
                }
                finally { loading = false; reloadNext(); }
                });
            };
            if (!(Boolean) symbols.serverReload().bind(getClass().getClassLoader()).invoke(null, callback, null))
                throw new IllegalStateException("YSM native loader is busy");
            LOGGER.info("Native YSM reload accepted for {}", load.id);
        } catch (Exception failure) {
            load.future.completeExceptionally(failure);
            loading = false; reloadNext();
        }
    }
    private void restoreSelections(Map<UUID, Selection> before) {
        int restored = 0;
        for (var entry : before.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || player.hasDisconnected() || entry.getValue().model.equals("default")) continue;
            try {
                Selection current = selection(player);
                LOGGER.debug("YSM selection recovery for {}: before={}, now={}", entry.getKey(), entry.getValue().model, current.model);
                if (current.model.equals("default")) {
                    select(player, entry.getValue()); restored++;
                }
            } catch (Exception failure) { LOGGER.warn("Could not restore YSM selection after catalog reload for {}", entry.getKey(), failure); }
        }
        LOGGER.info("Restored {} YSM player selections after catalog reload", restored);
    }
    private Staged stage(String id, YsmResourceArchive archive) throws IOException {
        Path root = FMLPaths.CONFIGDIR.get().resolve("yes_steve_model/custom").toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root)) throw new IOException("Linked YSM custom directory is not allowed");
        Files.createDirectories(root);
        root = root.toRealPath();
        Path destination = root.resolve(id);
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(destination) || !YsmResourceArchive.readDirectory(destination).digest().equals(archive.digest()))
                throw new IOException("Conflicting YSM resource ID");
            return new Staged(destination, false);
        }
        Path temporary = Files.createTempDirectory(root, ".cmi-stage-");
        boolean moved = false;
        try {
            for (String relative : archive.paths()) {
                Path file = temporary.resolve(relative).normalize();
                if (!file.startsWith(temporary)) throw new IOException("Model resource escaped staging directory");
                Files.createDirectories(file.getParent());
                Files.write(file, archive.resource(relative), StandardOpenOption.CREATE_NEW);
            }
            Files.move(temporary, destination);
            moved = true;
            return new Staged(destination, true);
        } finally { if (!moved) removeDirectory(temporary); }
    }
    private Selection selection(ServerPlayer player) {
        try {
            @SuppressWarnings("unchecked") var state = (INBTSerializable<CompoundTag>) ((Optional<?>) symbols.playerState()
                    .bind(getClass().getClassLoader()).invoke(null, player)).orElseThrow();
            CompoundTag tag = state.serializeNBT(server.registryAccess());
            return new Selection(tag.getString("model_id"), tag.getString("select_texture"));
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Unable to read YSM model selection", failure); }
    }
    private void select(ServerPlayer player, Selection selection) {
        try {
            var state = ((Optional<?>) symbols.playerState().bind(getClass().getClassLoader()).invoke(null, player)).orElseThrow();
            symbols.playerSelect().bind(getClass().getClassLoader()).invoke(state, selection.model, selection.texture);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Unable to select YSM model", failure); }
    }
    private void inform(UUID player, Component message) {
        ServerPlayer target = server.getPlayerList().getPlayer(player);
        if (target != null) target.sendSystemMessage(message);
    }
    private static void removeDirectory(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (var tree = Files.walk(directory)) {
            for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
    @Override public void close() {
        closed = true;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try { restore(player); } catch (Exception ignored) { /* Server is already stopping. */ }
        }
        deployments.values().forEach(future -> future.cancel(false));
        files.shutdownNow();
        for (Path directory : owned) {
            try { removeDirectory(directory); }
            catch (IOException failure) { com.mojang.logging.LogUtils.getLogger().warn("Could not remove temporary YSM model {}", directory, failure); }
        }
        owned.clear(); overrides.clear(); pendingReassert.clear(); deployments.clear(); revisions.clear(); loads.clear();
    }
}
