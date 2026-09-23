package com.iridium126.ysmprobe;

import com.iridium126.createmanaindustry.compat.ysm.YsmRuntimeSymbols;
import java.nio.file.Path;
import java.util.Optional;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Client-only production smoke test; screenshots require a separate visual review. */
public final class YsmClientProbe {
    private int ticks;
    private YsmRuntimeSymbols.Snapshot symbols;
    private java.lang.reflect.Method stateLookup;
    private final java.util.Set<String> captured = new java.util.HashSet<>();
    private String readyId;
    private int readyTicks;
    public static void register() { NeoForge.EVENT_BUS.addListener(new YsmClientProbe()::tick); }
    private void tick(ClientTickEvent.Post event) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) return;
        ticks++;
        minecraft.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
        minecraft.player.setYRot(0); minecraft.player.setXRot(0);
        boolean managerApply = Boolean.getBoolean("cmi.ysm.managerApply");
        if (ticks % 20 != 0 || captured.size() == (managerApply ? 4 : Boolean.getBoolean("cmi.ysm.liveApply") ? 3 : 2)) return;
        try {
            if (symbols == null) symbols = YsmRuntimeSymbols.inspect(Path.of(System.getProperty("cmi.ysm.fixture")), YsmRuntimeSymbols.VERSION);
            if (stateLookup == null) stateLookup = findClientState();
            Object state = ((Optional<?>) stateLookup.invoke(null, minecraft.player)).orElseThrow();
            var getters = java.util.Arrays.stream(state.getClass().getMethods()).filter(m ->
                    java.lang.reflect.Modifier.isFinal(m.getModifiers()) && m.getReturnType() == String.class && m.getParameterCount() == 0).toList();
            if (getters.size() != 1) throw new IllegalStateException("Ambiguous selected-model getter");
            String id = (String) getters.getFirst().invoke(state);
            if (!id.equals("cmi_sample.ysm") && !id.equals("cmi_plain_sample") && !id.startsWith("cmi_probe_")
                    && !(managerApply && id.startsWith("cmi_"))) return;
            String captureKey = id;
            if (managerApply && id.equals("cmi_plain_sample") && captured.stream().anyMatch(s -> s.startsWith("cmi_")
                    && !s.equals("cmi_plain_sample") && !s.equals("cmi_sample.ysm"))) captureKey = "restored";
            if (captured.contains(captureKey)) return;
            var resource = (Optional<?>) symbols.clientLookup().bind(getClass().getClassLoader()).invoke(null, id);
            if (resource.isEmpty()) return;
            boolean bound = false;
            for (Class<?> type = state.getClass(); type != Object.class; type = type.getSuperclass()) {
                for (var field : type.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.getType() != resource.get().getClass()) continue;
                    field.setAccessible(true);
                    if (field.get(state) == resource.get()) bound = true;
                }
            }
            if (!bound) { readyTicks = 0; return; }
            if (!id.equals(readyId)) { readyId = id; readyTicks = 0; }
            if ((readyTicks += 20) < 300) return;
            Screenshot.grab(minecraft.gameDirectory, captureKey.equals("restored") ? "ysm-restored.png" : id.endsWith(".ysm") ? "ysm-compiled.png"
                    : id.startsWith("cmi_probe_") || managerApply && id.startsWith("cmi_") && !id.equals("cmi_plain_sample") ? "ysm-edited.png" : "ysm-plaintext.png",
                    minecraft.getMainRenderTarget(), message -> System.out.println("CMI_YSM_RENDER screenshot: " + message.getString()));
            captured.add(captureKey);
            System.out.println("CMI_YSM_RENDER selected resource bound to player: " + id);
        } catch (Throwable failure) {
            System.err.println("CMI_YSM_RENDER FAIL: " + failure); failure.printStackTrace();
        }
    }

    static java.lang.reflect.Method findClientState() throws Exception {
        var matches = new java.util.ArrayList<YsmRuntimeSymbols.MethodSymbol>();
        try (var jar = new java.util.zip.ZipFile(System.getProperty("cmi.ysm.fixture"))) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().startsWith("com/elfmcys/yesstevemodel/") || !entry.getName().endsWith(".class")) continue;
                var node = new org.objectweb.asm.tree.ClassNode();
                try (var input = jar.getInputStream(entry)) {
                    new org.objectweb.asm.ClassReader(input).accept(node, org.objectweb.asm.ClassReader.SKIP_CODE);
                }
                if (node.fields.stream().noneMatch(f -> f.desc.equals("Lit/unimi/dsi/fastutil/ints/Int2ReferenceOpenHashMap;"))) continue;
                for (var method : node.methods) {
                    if ((method.access & 9) == 9 && method.desc.equals("(Lnet/minecraft/world/entity/player/Player;)Ljava/util/Optional;"))
                        matches.add(new YsmRuntimeSymbols.MethodSymbol(node.name, method.name, method.desc));
                }
            }
        }
        if (matches.size() != 1) throw new IllegalStateException("Ambiguous client player state");
        return matches.getFirst().bind(YsmClientProbe.class.getClassLoader());
    }
}
