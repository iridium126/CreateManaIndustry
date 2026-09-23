package com.iridium126.createmanaindustry.compat.ysm;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.common.util.INBTSerializable;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Opt-in standalone probe mod; never included in the CMI release jar. */
@Mod("cmi_ysm_probe")
public final class YsmProductionProbe {
    private boolean started;
    private boolean finished;
    private int ticks;
    private final String modelId = "cmi_probe_" + UUID.randomUUID().toString().replace("-", "");
    private CompletableFuture<Void> prepared;
    private boolean reloadRequested;
    private volatile String reloadError;
    private int clientJoinedAt;
    private int clientPhase;
    private com.iridium126.createmanaindustry.compat.ysm.model.YsmModelSnapshot sampleSnapshot;
    private com.iridium126.createmanaindustry.compat.ysm.model.YsmResourceArchive managerArchive;
    private List<com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Group> editedRoots;
    private String managerId;
    private boolean managerRequested;
    private boolean managerRestored;
    private boolean managerFakeSelected;
    public YsmProductionProbe() {
        YsmServerRuntime.register();
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient() && Boolean.getBoolean("cmi.ysm.renderProbe"))
            YsmClientProbe.register();
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> {
            YsmCodecValidation.verify(event.getServer().registryAccess());
            started = true;
        });
        NeoForge.EVENT_BUS.addListener(this::tick);
    }

    @SuppressWarnings("unchecked")
    private void tick(ServerTickEvent.Post event) {
        if (!started || finished || ++ticks % 20 != 0) return;
        try {
            if (reloadError != null) throw new IllegalStateException(reloadError);
            var loader = getClass().getClassLoader();
            if (!(Boolean) Class.forName("com.elfmcys.yesstevemodel.YesSteveModel", false, loader)
                    .getMethod("isAvailable").invoke(null)) throw new IllegalStateException("Native runtime unavailable");
            var symbols = YsmRuntimeSymbols.inspect(Path.of(System.getProperty("cmi.ysm.fixture")), YsmRuntimeSymbols.VERSION);
            var player = FakePlayerFactory.getMinecraft(event.getServer().overworld());
            var state = (INBTSerializable<CompoundTag>) ((Optional<?>) symbols.playerState().bind(loader)
                    .invoke(null, player)).orElseThrow();
            var original = state.serializeNBT(event.getServer().registryAccess()).copy();
            if (!original.contains("model_id")) throw new IllegalStateException("No model selection in player state");
            var catalog = (Map<?, ?>) symbols.serverCatalog().bind(loader).invoke(null);
            if (catalog.isEmpty()) {
                if (ticks < 1200) return;
                throw new IllegalStateException("Native model catalog stayed empty for 60 seconds");
            }
            for (Object key : catalog.keySet()) {
                if (((Optional<?>) symbols.serverLookup().bind(loader).invoke(null, key)).isEmpty())
                    throw new IllegalStateException("Catalog lookup did not return a model");
            }
            String sample = System.getProperty("cmi.ysm.sample");
            if (sample != null && ((Optional<?>) symbols.serverLookup().bind(loader).invoke(null, sample)).isEmpty())
                throw new IllegalStateException("Provided compiled sample did not load in native YSM");
            if (!original.equals(state.serializeNBT(event.getServer().registryAccess())))
                throw new IllegalStateException("Read-only probe changed player selection");
            String plaintext = System.getProperty("cmi.ysm.plaintext");
            if (plaintext != null) {
                var exported = (Optional<?>) symbols.serverLookup().bind(loader).invoke(null, plaintext);
                if (exported.isEmpty()) throw new IllegalStateException("Exported plaintext model did not load: " + plaintext);
                if (sample != null && exported.get() == ((Optional<?>) symbols.serverLookup().bind(loader).invoke(null, sample)).orElseThrow())
                    throw new IllegalStateException("Plaintext export reused the compiled model instance");
                if (sample != null) {
                    YsmSnapshotStore.Result snapshot;
                    try {
                        symbols.playerSelect().bind(loader).invoke(state, sample, "default");
                        snapshot = YsmServerRuntime.get(event.getServer()).read(player);
                    } finally {
                        symbols.playerSelect().bind(loader).invoke(state, original.getString("model_id"), original.getString("select_texture"));
                    }
                    if (snapshot.status() == YsmSnapshotStore.Status.LOADING) {
                        if (ticks > 2400) throw new IllegalStateException("Production snapshot read timed out");
                        return;
                    }
                    if (snapshot.status() != YsmSnapshotStore.Status.READY || snapshot.snapshot().roots().isEmpty())
                        throw new IllegalStateException("Production snapshot read failed: " + snapshot.reason());
                    sampleSnapshot = snapshot.snapshot();
                    if (!Boolean.getBoolean("cmi.ysm.waitClient"))
                        System.out.println("CMI_YSM_SNAPSHOT PASS: production player lookup and async source snapshot, roots=" + snapshot.snapshot().roots().size());
                }
                if (Boolean.getBoolean("cmi.ysm.managerApply") && !Boolean.getBoolean("cmi.ysm.waitClient")) {
                    if (!managerFakeSelected) {
                        symbols.playerSelect().bind(loader).invoke(state, plaintext, "default");
                        managerFakeSelected = true;
                    }
                    ensureManagerApply(event, player, symbols, loader);
                    if (managerId == null || ((Optional<?>) symbols.serverLookup().bind(loader).invoke(null, managerId)).isEmpty()) {
                        if (ticks > 6000) throw new IllegalStateException("Production manager did not apply to fake player");
                        return;
                    }
                    System.out.println("CMI_YSM_MANAGER PASS: prepared edit and native reload of independent resource");
                    System.out.println("CMI_YSM_PROBE PASS: native manager server-only deployment");
                    finished = true;
                    return;
                }
                if (Boolean.getBoolean("cmi.ysm.waitClient")) {
                    var realPlayer = event.getServer().getPlayerList().getPlayers().stream()
                            .filter(p -> p.getGameProfile().getName().equals("YsmProbe")).findFirst();
                    if (realPlayer.isEmpty()) {
                        if (ticks > 12000) throw new IllegalStateException("Render probe client did not connect");
                        return;
                    }
                    var target = realPlayer.get();
                    if (clientJoinedAt == 0) clientJoinedAt = ticks;
                    int elapsed = ticks - clientJoinedAt;
                    if (elapsed < 600) return;
                    boolean managerApply = Boolean.getBoolean("cmi.ysm.managerApply");
                    boolean liveApply = Boolean.getBoolean("cmi.ysm.liveApply");
                    if (managerApply && elapsed >= 2400 && !managerRestored && !ensureManagerApply(event, target, symbols, loader)) {
                        if (elapsed > 4400) throw new IllegalStateException("Production manager did not select an edited model");
                    } else if (liveApply && !managerApply && elapsed >= 2400 && !ensureLiveReload(symbols, loader)) {
                        if (elapsed > 4400) throw new IllegalStateException("Live modified model did not load");
                    }
                    int phase = elapsed < 1800 ? 1 : elapsed < 3000 || !liveApply || managerApply && !managerRequested || !managerApply && !reloadRequested
                            || ((Optional<?>) symbols.serverLookup().bind(loader).invoke(null, modelId)).isEmpty() ? 2 : 3;
                    if (managerApply) {
                        phase = elapsed < 1800 ? 1 : elapsed < 3000 || !managerRequested ? 2 : elapsed < 4200 ? 3 : 4;
                        if (phase == 4 && !managerRestored && clientPhase == 3) {
                            YsmServerRuntime.get(event.getServer()).overrides().restore(target);
                            managerRestored = true;
                            System.out.println("CMI_YSM_MANAGER restored original selection");
                        }
                    }
                    if (phase != clientPhase) {
                        var targetState = ((Optional<?>) symbols.playerState().bind(loader).invoke(null, target)).orElseThrow();
                        if (managerApply && phase >= 3) {
                            String actual = ((INBTSerializable<CompoundTag>) targetState).serializeNBT(event.getServer().registryAccess()).getString("model_id");
                            String expected = phase == 3 ? managerId : plaintext;
                            if (!expected.equals(actual)) return;
                        } else symbols.playerSelect().bind(loader).invoke(targetState, phase == 1 ? sample : phase == 2 ? plaintext : modelId, "default");
                        target.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
                        target.teleportTo(0.5, 100, 0.5);
                        target.getAbilities().flying = true; target.onUpdateAbilities();
                        event.getServer().overworld().setDayTime(6000);
                        System.out.println("CMI_YSM_RENDER selected phase=" + phase);
                        clientPhase = phase;
                    }
                    if (elapsed < (managerApply ? 5400 : liveApply ? 4800 : 4200)) return;
                    if (managerApply) System.out.println("CMI_YSM_MANAGER PASS: production prepared edit, native reload, player-bound override and restore");
                }
                System.out.println("CMI_YSM_PROBE PASS: native compiled and exported plaintext models loaded as independent resources");
                finished = true;
                return;
            }
            if (prepared == null) {
                prepared = CompletableFuture.runAsync(this::prepareModel);
                return;
            }
            if (!prepared.isDone()) {
                if (ticks > 2400) throw new IllegalStateException("Model preparation timed out");
                return;
            }
            prepared.join();
            if (!reloadRequested) {
                Consumer<Object> callback = result -> {
                    try {
                        var methods = java.util.Arrays.stream(result.getClass().getMethods()).filter(m -> m.getParameterCount() == 0).toList();
                        var flags = methods.stream().filter(m -> m.getReturnType() == boolean.class).toList();
                        if (flags.size() != 1) throw new IllegalStateException("Ambiguous reload result");
                        if (!(Boolean)flags.getFirst().invoke(result)) {
                            var messages = methods.stream().filter(m -> m.getReturnType() == net.minecraft.network.chat.Component.class).toList();
                            String reason = messages.size() == 1 ? String.valueOf(messages.getFirst().invoke(result)) : "No error component";
                            reloadError = "Native reload failed: " + reason;
                        }
                        var maps = methods.stream().filter(m -> m.getReturnType() == Map.class).toList();
                        if (maps.size() == 1) {
                            var returned = (Map<?, ?>) maps.getFirst().invoke(result);
                            System.out.println("CMI_YSM_PROBE reload contains requested ID=" + returned.containsKey(modelId)
                                    + "; test IDs=" + returned.keySet().stream().filter(k -> k.toString().startsWith("cmi_probe_")).toList());
                        }
                        System.out.println("CMI_YSM_PROBE native reload callback success=" + (reloadError == null));
                    } catch (ReflectiveOperationException | IllegalStateException failure) { reloadError = failure.toString(); }
                };
                if (!(Boolean) symbols.serverReload().bind(loader).invoke(null, callback, null))
                    throw new IllegalStateException("Native reload refused");
                reloadRequested = true;
                return;
            }
            var modified = (Optional<?>) symbols.serverLookup().bind(loader).invoke(null, modelId);
            if (modified.isEmpty()) {
                if (ticks > 2400) throw new IllegalStateException("Modified model did not load");
                return;
            }
            var source = (Optional<?>) symbols.serverLookup().bind(loader).invoke(null, "default");
            if (source.isEmpty() || source.get() == modified.get()) throw new IllegalStateException("Modified model is not independent");
            var other = FakePlayerFactory.get(event.getServer().overworld(), new GameProfile(UUID.randomUUID(), "YsmObserver"));
            var otherState = (INBTSerializable<CompoundTag>) ((Optional<?>) symbols.playerState().bind(loader)
                    .invoke(null, other)).orElseThrow();
            var otherOriginal = otherState.serializeNBT(event.getServer().registryAccess()).copy();
            var select = symbols.playerSelect().bind(loader);
            try {
                select.invoke(state, modelId, original.getString("select_texture"));
                if (!state.serializeNBT(event.getServer().registryAccess()).getString("model_id").equals(modelId))
                    throw new IllegalStateException("Target player did not select independent model");
                if (!otherOriginal.equals(otherState.serializeNBT(event.getServer().registryAccess())))
                    throw new IllegalStateException("Another player's selection changed");
            } finally {
                select.invoke(state, original.getString("model_id"), original.getString("select_texture"));
            }
            if (!original.equals(state.serializeNBT(event.getServer().registryAccess())))
                throw new IllegalStateException("Target selection did not restore");
            System.out.println("CMI_YSM_PROBE PASS: native catalog, modified model load, isolated player selection and restore; compiled sample checked=" + (sample != null));
            finished = true;
        } catch (Throwable failure) {
            finished = true;
            System.err.println("CMI_YSM_PROBE FAIL: " + failure);
            failure.printStackTrace();
        } finally {
            if (finished) event.getServer().halt(false);
        }
    }

    private void prepareModel() {
        try {
            boolean liveApply = Boolean.getBoolean("cmi.ysm.liveApply");
            Path source = liveApply ? Path.of("config/yes_steve_model/custom/cmi_plain_sample")
                    : Path.of("config/yes_steve_model/builtin/default");
            Path destination = Path.of("config/yes_steve_model/custom", modelId);
            try (var files = Files.walk(source)) {
                for (Path file : files.toList()) {
                    Path target = destination.resolve(source.relativize(file));
                    if (Files.isDirectory(file)) Files.createDirectories(target);
                    else Files.copy(file, target);
                }
            }
            Path main = destination.resolve("models/main.json");
            var json = JsonParser.parseString(Files.readString(main)).getAsJsonObject();
            boolean edited = false;
            for (var geometry : json.getAsJsonArray("minecraft:geometry")) {
                for (var bone : geometry.getAsJsonObject().getAsJsonArray("bones")) {
                    var cubes = bone.getAsJsonObject().getAsJsonArray("cubes");
                    if (cubes == null || cubes.isEmpty()) continue;
                    var origin = cubes.get(0).getAsJsonObject().getAsJsonArray("origin");
                    origin.set(0, new com.google.gson.JsonPrimitive(origin.get(0).getAsDouble() + (liveApply ? 8 : 1)));
                    edited = true;
                    break;
                }
                if (edited) break;
            }
            if (!edited) throw new IllegalStateException("No editable cube in default fixture");
            Files.writeString(main, json.toString());
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to prepare independent model fixture", failure);
        }
    }

    private boolean ensureLiveReload(YsmRuntimeSymbols.Snapshot symbols, ClassLoader loader) throws Exception {
        if (reloadError != null) throw new IllegalStateException(reloadError);
        if (prepared == null) { prepared = CompletableFuture.runAsync(this::prepareModel); return false; }
        if (!prepared.isDone()) return false;
        prepared.join();
        if (!reloadRequested) {
            Consumer<Object> callback = result -> {
                try {
                    var methods = java.util.Arrays.stream(result.getClass().getMethods()).filter(m -> m.getParameterCount() == 0).toList();
                    var flags = methods.stream().filter(m -> m.getReturnType() == boolean.class).toList();
                    if (flags.size() != 1 || !(Boolean) flags.getFirst().invoke(result))
                        reloadError = "Native live reload failed";
                    var maps = methods.stream().filter(m -> m.getReturnType() == Map.class).toList();
                    if (maps.size() == 1 && !((Map<?, ?>) maps.getFirst().invoke(result)).containsKey(modelId))
                        reloadError = "Native reload did not publish the modified model ID";
                    System.out.println("CMI_YSM_RENDER live reload=" + (reloadError == null));
                } catch (ReflectiveOperationException exception) { reloadError = exception.toString(); }
            };
            if (!(Boolean) symbols.serverReload().bind(loader).invoke(null, callback, null))
                throw new IllegalStateException("Native live reload refused");
            reloadRequested = true;
        }
        return ((Optional<?>) symbols.serverLookup().bind(loader).invoke(null, modelId)).isPresent();
    }

    private boolean ensureManagerApply(ServerTickEvent.Post event, net.minecraft.server.level.ServerPlayer target,
            YsmRuntimeSymbols.Snapshot symbols, ClassLoader loader) throws Exception {
        var runtime = YsmServerRuntime.get(event.getServer());
        if (editedRoots == null) {
            editedRoots = new ArrayList<>();
            boolean[] changed = {false};
            for (var root : sampleSnapshot.roots()) editedRoots.add(moveFirstCube(root, changed));
            if (!changed[0]) throw new IllegalStateException("Compiled sample has no editable cube");
        }
        if (managerArchive == null) {
            var result = runtime.prepare(editedRoots);
            if ((event.getServer().getTickCount() % 200) == 0)
                System.out.println("CMI_YSM_MANAGER preparation=" + result.state() + " reason=" + result.reason());
            if (result.state() == YsmPreparedCache.State.LOADING) return false;
            if (result.state() != YsmPreparedCache.State.READY) throw new IllegalStateException(result.reason());
            managerArchive = result.archive();
            managerId = "cmi_" + managerArchive.digest().substring(0, 24);
            System.out.println("CMI_YSM_MANAGER prepared id=" + managerId);
        }
        if (!managerRequested) {
            runtime.overrides().apply(target, managerArchive);
            managerRequested = true;
            System.out.println("CMI_YSM_MANAGER requested native deployment");
        }
        var targetState = (INBTSerializable<CompoundTag>) ((Optional<?>) symbols.playerState().bind(loader).invoke(null, target)).orElseThrow();
        return managerId.equals(targetState.serializeNBT(event.getServer().registryAccess()).getString("model_id"));
    }

    private static com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Group moveFirstCube(
            com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Group group, boolean[] changed) {
        var cubes = new ArrayList<>(group.cubes());
        if (!changed[0] && !cubes.isEmpty()) {
            var cube = cubes.getFirst(); var origin = cube.origin();
            cubes.set(0, new com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Cube(
                    new com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Vector(origin.x() + 8, origin.y(), origin.z()),
                    cube.size(), cube.pivot(), cube.rotation(), cube.scale(), cube.inflate(), cube.visible(), cube.faces(), cube.extraJson()));
            changed[0] = true;
        }
        var children = new ArrayList<com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Group>();
        for (var child : group.children()) children.add(changed[0] ? child : moveFirstCube(child, changed));
        return new com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Group(group.name(), group.pivot(),
                group.rotation(), group.scale(), group.visible(), cubes, children, group.root(), group.extraJson());
    }
}
