package com.iridium126.createmanaindustry.compat.ysm;

import com.iridium126.createmanaindustry.compat.ysm.model.YsmCompiledExporter;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmModelSnapshot;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.INBTSerializable;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Optional server facade. All model decoding and symbol scanning happen off the game thread. */
public final class YsmServerRuntime implements AutoCloseable {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static YsmServerRuntime active;
    private final MinecraftServer server;
    private final YsmSnapshotStore snapshots = new YsmSnapshotStore();
    private final YsmReferenceStore references;
    private final YsmPreparedCache prepared = new YsmPreparedCache();
    private final com.iridium126.createmanaindustry.compat.ysm.net.YsmServerArchives transfers;
    private final com.iridium126.createmanaindustry.compat.ysm.net.YsmServerPreviews previews;
    private final CompletableFuture<YsmRuntimeSymbols.Snapshot> mapping;
    private YsmOverrideManager overrides;
    private String failure;
    private YsmServerRuntime(MinecraftServer server) {
        this.server = server;
        this.references = new YsmReferenceStore(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT));
        this.transfers = new com.iridium126.createmanaindustry.compat.ysm.net.YsmServerArchives(server);
        this.previews = new com.iridium126.createmanaindustry.compat.ysm.net.YsmServerPreviews(server, references);
        var file = ModList.get().getModFileById(YsmRuntimeSymbols.MOD_ID);
        var path = file.getFile().getFilePath();
        String version = ModList.get().getModContainerById(YsmRuntimeSymbols.MOD_ID).orElseThrow().getModInfo().getVersion().toString();
        mapping = CompletableFuture.supplyAsync(() -> {
            try { return YsmRuntimeSymbols.inspect(path, version); }
            catch (Exception exception) { throw new CompletionException(exception); }
        });
    }
    public static void register() {
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> active = new YsmServerRuntime(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> {
            if (active != null && active.server == event.getServer()) { active.close(); active = null; }
        });
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
            if (active != null && active.server == event.getServer()) {
                active.transfers.tick();
            }
            if (active == null || active.server != event.getServer() || event.getServer().getTickCount() % 20 != 0) return;
            for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
                try { active.read(player); }
                catch (IllegalStateException ignored) { /* Not ready or unsupported; the casting action reports the reason. */ }
                if (active.overrides != null) active.overrides.maintain(player);
            }
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (active != null && active.overrides != null && event.getEntity() instanceof ServerPlayer player)
                active.overrides.forget(player);
            if (active != null && event.getEntity() instanceof ServerPlayer player)
                active.transfers.forget(player.getUUID());
            if (active != null && event.getEntity() instanceof ServerPlayer player)
                active.previews.forget(player.getUUID());
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerEvent.StartTracking event) -> {
            if (active != null && active.overrides != null && event.getEntity() instanceof ServerPlayer viewer
                    && event.getTarget() instanceof ServerPlayer target)
                active.overrides.startTracking(viewer, target);
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerEvent.Clone event) -> {
            if (active != null && active.overrides != null && event.getEntity() instanceof ServerPlayer player)
                active.overrides.markTransition(player);
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerRespawnEvent event) -> {
            if (active != null && active.overrides != null && event.getEntity() instanceof ServerPlayer player)
                active.overrides.markTransition(player);
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent event) -> {
            if (active != null && active.overrides != null && event.getEntity() instanceof ServerPlayer player)
                active.overrides.markTransition(player);
        });
    }
    public static YsmServerRuntime get(MinecraftServer server) {
        if (active == null || active.server != server) throw new IllegalStateException("YSM session is not ready");
        return active;
    }
    public YsmSnapshotStore.Result read(ServerPlayer player) {
        if (player.server != server || player.hasDisconnected()) throw new IllegalStateException("Player is no longer in this session");
        var symbols = symbols();
        try {
            var loader = YsmServerRuntime.class.getClassLoader();
            if (!(Boolean) Class.forName("com.elfmcys.yesstevemodel.YesSteveModel", false, loader).getMethod("isAvailable").invoke(null))
                throw new IllegalStateException("YSM native runtime is unavailable");
            @SuppressWarnings("unchecked") var state = (INBTSerializable<CompoundTag>) ((Optional<?>) symbols.playerState().bind(loader).invoke(null, player)).orElseThrow();
            String modelId = state.serializeNBT(server.registryAccess()).getString("model_id");
            if (((Optional<?>) symbols.serverLookup().bind(loader).invoke(null, modelId)).isEmpty())
                throw new IllegalStateException("Selected YSM model is not in the native catalog");
            YsmCompiledExporter.safePath(modelId);
            var result = snapshots.query(modelId);
            if (result.status() == YsmSnapshotStore.Status.MISSING) {
                var root = FMLPaths.CONFIGDIR.get().resolve("yes_steve_model");
                snapshots.prewarm(modelId, List.of(root.resolve("custom").resolve(modelId), root.resolve("builtin").resolve(modelId)));
                result = snapshots.query(modelId);
            }
            if (result.status() == YsmSnapshotStore.Status.READY) references.persist(result.snapshot());
            return result;
        } catch (ReflectiveOperationException | IllegalArgumentException | NoSuchElementException exception) {
            throw new IllegalStateException("Unable to read YSM player resources: " + exception.getMessage(), exception);
        }
    }
    public YsmModelSnapshot require(String digest) {
        symbols();
        try { return snapshots.require(digest); }
        catch (IllegalArgumentException missingMemorySnapshot) {
            var result = references.querySnapshot(digest);
            if (result.state() == YsmReferenceStore.State.READY) return result.snapshot();
            throw new IllegalStateException(result.reason());
        }
    }
    public YsmReferenceStore references() { return references; }
    public Optional<String> sourceModelId(String digest) { symbols(); return snapshots.modelIdForDigest(digest); }
    public YsmOverrideManager overrides() {
        if (overrides == null) overrides = new YsmOverrideManager(server, symbols(), transfers);
        return overrides;
    }
    public com.iridium126.createmanaindustry.compat.ysm.net.YsmServerArchives transfers() { return transfers; }
    public void requestPreview(ServerPlayer player, String key, boolean cube) {
        if (player.server != server || player.hasDisconnected()) throw new IllegalStateException("Preview requester left this world");
        previews.request(player, key, cube);
    }
    public YsmPreparedCache.Result prepare(List<com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Group> roots) {
        if (roots.isEmpty() || roots.getFirst().root() == null) throw new IllegalArgumentException("Expected a complete geometry file list");
        return prepared.getOrPrepare(require(roots.getFirst().root().snapshot()), roots);
    }
    private YsmRuntimeSymbols.Snapshot symbols() {
        if (failure != null) throw new IllegalStateException(failure);
        if (!mapping.isDone()) throw new IllegalStateException("YSM runtime mapping is still loading; retry later");
        try { return mapping.join(); }
        catch (CompletionException exception) {
            failure = "YSM compatibility disabled: " + exception.getCause().getMessage();
            LOGGER.error(failure, exception.getCause());
            throw new IllegalStateException(failure, exception);
        }
    }
    @Override public void close() {
        if (overrides != null) overrides.close();
        mapping.cancel(false); prepared.close(); snapshots.close(); references.close(); transfers.close(); previews.close();
    }
    private YsmServerRuntime() { throw new AssertionError(); }
}
