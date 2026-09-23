package com.iridium126.ysmprobe;

import com.iridium126.createmanaindustry.compat.ysm.YsmRuntimeSymbols;
import com.iridium126.createmanaindustry.compat.ysm.YsmServerRuntime;
import com.iridium126.createmanaindustry.compat.ysm.YsmSnapshotStore;
import com.iridium126.createmanaindustry.compat.ysm.YsmPreparedCache;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Group;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmModelSnapshot;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmResourceArchive;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.INBTSerializable;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Test-only mod, loaded beside the actual CMI jar in a normal NeoForge instance. */
@Mod("cmi_ysm_full_probe")
public final class YsmFullIntegrationProbe {
    private int joinedAt, ticks;
    private boolean sampleSelected, plaintextSelected, requested, editedSeen, exported, restored, finished, observerChecked,
            lateObserverSelected;
    private YsmRuntimeSymbols.Snapshot symbols;
    private YsmModelSnapshot source;
    private List<Group> edited;
    private YsmResourceArchive archive;
    private String modifiedId;
    public YsmFullIntegrationProbe() {
        if (FMLEnvironment.dist == Dist.CLIENT) YsmFullClientProbe.register();
        else NeoForge.EVENT_BUS.addListener(this::tick);
    }
    @SuppressWarnings("unchecked")
    private void tick(ServerTickEvent.Post event) {
        if (finished || ++ticks % 20 != 0) return;
        try {
            var server = event.getServer();
            ServerPlayer target = server.getPlayerList().getPlayers().stream()
                    .filter(player -> player.getGameProfile().getName().equals("YsmProbe")).findFirst().orElse(null);
            ServerPlayer observer = server.getPlayerList().getPlayers().stream()
                    .filter(player -> player.getGameProfile().getName().equals("YsmObserver")).findFirst().orElse(null);
            if (target == null) {
                if (ticks > 12000) throw new IllegalStateException("Full integration client did not join");
                return;
            }
            boolean lateObserver = Boolean.getBoolean("cmi.ysm.lateObserver");
            if (Boolean.getBoolean("cmi.ysm.multiplayer") && observer == null && !lateObserver) {
                if (ticks > 12000) throw new IllegalStateException("Observer client did not join");
                return;
            }
            if (lateObserver && observer == null && ticks > 12000)
                throw new IllegalStateException("Late observer client did not join");
            if (joinedAt == 0) joinedAt = ticks;
            int elapsed = ticks - joinedAt;
            if (symbols == null) symbols = YsmRuntimeSymbols.inspect(Path.of("mods/ysm-2.6.5-neoforge+mc1.21.1-release.jar"), YsmRuntimeSymbols.VERSION);
            Object state = ((Optional<?>) symbols.playerState().bind(getClass().getClassLoader()).invoke(null, target)).orElseThrow();
            var runtime = YsmServerRuntime.get(server);
            if (elapsed >= 400 && !sampleSelected) {
                symbols.playerSelect().bind(getClass().getClassLoader()).invoke(state, "cmi_sample.ysm", "default");
                sampleSelected = true;
                System.out.println("CMI_YSM_FULL selected compiled source");
            }
            if (sampleSelected && !plaintextSelected) {
                var result = runtime.read(target);
                if (result.status() == YsmSnapshotStore.Status.READY) source = result.snapshot();
                else if (result.status() == YsmSnapshotStore.Status.FAILED) throw new IllegalStateException(result.reason());
            }
            if (elapsed >= 900 && source != null && !plaintextSelected) {
                symbols.playerSelect().bind(getClass().getClassLoader()).invoke(state, "cmi_plain_sample", "default");
                if (observer != null && !lateObserver) {
                    Object observerState = ((Optional<?>) symbols.playerState().bind(getClass().getClassLoader()).invoke(null, observer)).orElseThrow();
                    symbols.playerSelect().bind(getClass().getClassLoader()).invoke(observerState, "cmi_plain_sample", "default");
                }
                plaintextSelected = true;
                System.out.println("CMI_YSM_FULL selected distinct target source");
            }
            if (elapsed >= 1400 && plaintextSelected && !requested) {
                if (edited == null) {
                    boolean[] moved = {false}; edited = new ArrayList<>();
                    for (Group root : source.roots()) edited.add(moveFirstCube(root, moved));
                    if (!moved[0]) throw new IllegalStateException("Fixture contains no editable cube");
                }
                var result = runtime.prepare(edited);
                if (result.state() == YsmPreparedCache.State.LOADING) return;
                if (result.state() != YsmPreparedCache.State.READY) throw new IllegalStateException(result.reason());
                archive = result.archive(); modifiedId = "cmi_" + archive.digest().substring(0, 24);
                runtime.overrides().apply(target, archive); requested = true;
                System.out.println("CMI_YSM_FULL requested edited source=" + source.digest() + " model=" + modifiedId);
            }
            String selected = ((INBTSerializable<CompoundTag>) state).serializeNBT(server.registryAccess()).getString("model_id");
            if (requested && !editedSeen && modifiedId.equals(selected)) {
                editedSeen = true;
                System.out.println("CMI_YSM_FULL edited resource bound to real player");
            }
            if (lateObserver && editedSeen && observer != null && !lateObserverSelected) {
                Object observerState = ((Optional<?>) symbols.playerState().bind(getClass().getClassLoader()).invoke(null, observer)).orElseThrow();
                symbols.playerSelect().bind(getClass().getClassLoader()).invoke(observerState, "cmi_plain_sample", "default");
                lateObserverSelected = true;
                System.out.println("CMI_YSM_FULL late observer selected original shared model");
            }
            if (editedSeen && observer != null && !observerChecked && (!lateObserver || lateObserverSelected)) {
                Object observerState = ((Optional<?>) symbols.playerState().bind(getClass().getClassLoader()).invoke(null, observer)).orElseThrow();
                String observerModel = ((INBTSerializable<CompoundTag>) observerState).serializeNBT(server.registryAccess()).getString("model_id");
                if (!observerModel.equals("cmi_plain_sample")) throw new IllegalStateException("Observer model changed to " + observerModel);
                observerChecked = true;
                System.out.println("CMI_YSM_FULL observer retained original shared model");
            }
            if (elapsed > 2300 && requested && !editedSeen)
                throw new IllegalStateException("Edited resource was not selected on real player; selected=" + selected);
            if (elapsed >= 2000 && editedSeen && !exported) {
                runtime.transfers().export(target, archive); exported = true;
                System.out.println("CMI_YSM_FULL approved client export");
            }
            if (elapsed >= (lateObserver ? 5200 : 2800) && editedSeen && !restored
                    && (!lateObserver || observerChecked)) {
                runtime.overrides().restore(target); restored = true;
                selected = ((INBTSerializable<CompoundTag>) state).serializeNBT(server.registryAccess()).getString("model_id");
                if (!selected.equals("cmi_plain_sample")) throw new IllegalStateException("Original target model was not restored");
                System.out.println("CMI_YSM_FULL target restored to original model");
            }
            if (elapsed >= (lateObserver ? 6000 : 3500) && restored && exported
                    && (!Boolean.getBoolean("cmi.ysm.multiplayer") || observerChecked)) {
                finished = true;
                System.out.println("CMI_YSM_FULL PASS: real-player source edit, independent apply, export dispatch and restore");
                server.halt(false);
            }
        } catch (Throwable failure) {
            finished = true; System.err.println("CMI_YSM_FULL FAIL: " + failure); failure.printStackTrace();
            event.getServer().halt(false);
        }
    }
    private static Group moveFirstCube(Group group, boolean[] moved) {
        var cubes = new ArrayList<>(group.cubes());
        if (!moved[0] && !cubes.isEmpty()) {
            var cube = cubes.getFirst(); var position = cube.origin();
            cubes.set(0, new YsmGeometry.Cube(new YsmGeometry.Vector(position.x() + 8, position.y(), position.z()),
                    cube.size(), cube.pivot(), cube.rotation(), cube.scale(), cube.inflate(), cube.visible(), cube.faces(), cube.extraJson()));
            moved[0] = true;
        }
        var children = new ArrayList<Group>();
        for (Group child : group.children()) children.add(moved[0] ? child : moveFirstCube(child, moved));
        return new Group(group.name(), group.pivot(), group.rotation(), group.scale(), group.visible(), cubes, children, group.root(), group.extraJson());
    }
}
