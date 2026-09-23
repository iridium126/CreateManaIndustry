package com.iridium126.ysmprobe;

import com.iridium126.createmanaindustry.compat.ysm.model.YsmModelSnapshot;
import com.iridium126.createmanaindustry.compat.ysm.YsmRuntimeSymbols;
import java.nio.file.*;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Verifies the approved client export can be read back as a complete plaintext model. */
public final class YsmFullClientProbe {
    private int ticks;
    private boolean checked;
    private boolean observerChecked;
    private YsmRuntimeSymbols.Snapshot symbols;
    private java.lang.reflect.Method stateLookup;
    public static void register() {
        YsmClientProbe.register();
        NeoForge.EVENT_BUS.addListener(new YsmFullClientProbe()::tick);
    }
    private void tick(ClientTickEvent.Post event) {
        if (++ticks % 100 != 0 || Minecraft.getInstance().player == null) return;
        if (Boolean.getBoolean("cmi.ysm.observerProbe") && !observerChecked) verifyObserver();
        if (checked) return;
        Path exports = Minecraft.getInstance().gameDirectory.toPath().resolve("exports/createmanaindustry/ysm");
        if (!Files.isDirectory(exports)) return;
        try (var tree = Files.list(exports)) {
            Path directory = tree.filter(Files::isDirectory).findFirst().orElse(null);
            if (directory == null) return;
            checked = true;
            CompletableFuture.supplyAsync(() -> {
                try { return YsmModelSnapshot.load(directory).roots().size(); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            }).whenComplete((roots, failure) -> {
                if (failure != null) { System.err.println("CMI_YSM_FULL_CLIENT export FAIL: " + failure); failure.printStackTrace(); }
                else System.out.println("CMI_YSM_FULL_CLIENT export PASS: " + roots + " readable roots at " + directory);
            });
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private void verifyObserver() {
        try {
            var minecraft = Minecraft.getInstance();
            var target = minecraft.level.players().stream()
                    .filter(player -> player.getGameProfile().getName().equals("YsmProbe")).findFirst().orElse(null);
            if (target == null) return;
            if (symbols == null) symbols = YsmRuntimeSymbols.inspect(Path.of(System.getProperty("cmi.ysm.fixture")), YsmRuntimeSymbols.VERSION);
            if (stateLookup == null) stateLookup = YsmClientProbe.findClientState();
            var targetState = (Optional<?>) stateLookup.invoke(null, target);
            if (targetState.isEmpty()) return;
            String selected = selectedId(targetState.get());
            if (!selected.startsWith("cmi_") || selected.equals("cmi_plain_sample") || selected.equals("cmi_sample.ysm")) return;
            var resource = (Optional<?>) symbols.clientLookup().bind(getClass().getClassLoader()).invoke(null, selected);
            if (resource.isEmpty()) return;
            boolean bound = false;
            for (Class<?> type = targetState.get().getClass(); type != Object.class; type = type.getSuperclass()) {
                for (var field : type.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.getType() != resource.get().getClass()) continue;
                    field.setAccessible(true);
                    if (field.get(targetState.get()) == resource.get()) bound = true;
                }
            }
            if (!bound) return;
            var cacheField = Class.forName("com.iridium126.createmanaindustry.compat.ysm.net.YsmClientArchives")
                    .getDeclaredField("CACHE");
            cacheField.setAccessible(true);
            var cache = (java.util.Map<?, ?>) cacheField.get(null);
            if (cache.keySet().stream().noneMatch(key -> key instanceof String digest
                    && digest.startsWith(selected.substring(4)))) return;
            var localState = (Optional<?>) stateLookup.invoke(null, minecraft.player);
            if (localState.isEmpty() || !selectedId(localState.get()).equals("cmi_plain_sample"))
                throw new IllegalStateException("Observer's own model selection changed");
            observerChecked = true;
            System.out.println("CMI_YSM_FULL_OBSERVER PASS: edited target bound, CMI archive received, own shared model unchanged");
        } catch (Exception failure) {
            System.err.println("CMI_YSM_FULL_OBSERVER FAIL: " + failure);
            throw new IllegalStateException(failure);
        }
    }
    private static String selectedId(Object state) throws Exception {
        var getters = java.util.Arrays.stream(state.getClass().getMethods()).filter(method ->
                java.lang.reflect.Modifier.isFinal(method.getModifiers()) && method.getReturnType() == String.class
                        && method.getParameterCount() == 0).toList();
        if (getters.size() != 1) throw new IllegalStateException("Ambiguous selected-model getter");
        return (String) getters.getFirst().invoke(state);
    }
}
