package com.iridium126.createmanaindustry.compat.ysm;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Resolves the 2.6.5 NeoForge ABI without loading client classes on a server or
 * embedding obfuscated names. Every role must have exactly one candidate.
 * This is a structural probe, not a promise that a native call is usable.
 */
public final class YsmRuntimeSymbols {
    public static final String MOD_ID = "yes_steve_model";
    public static final String VERSION = "2.6.5-neoforge+mc1.21.1";
    private static final String PLAYER = "Lnet/minecraft/server/level/ServerPlayer;";
    private static final String CONSUMER = "Ljava/util/function/Consumer;";
    private static final String STRING = "Ljava/lang/String;";

    public record MethodSymbol(String owner, String name, String descriptor) {
        public Method bind(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> type = Class.forName(owner.replace('/', '.'), false, loader);
            List<Method> matches = new ArrayList<>();
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(name) && Type.getMethodDescriptor(method).equals(descriptor)) {
                    matches.add(method);
                }
            }
            Method method = unique(matches, "runtime method");
            if (!method.trySetAccessible()) {
                throw new IllegalStateException("YSM runtime method is inaccessible");
            }
            return method;
        }
    }

    public record Snapshot(MethodSymbol playerState, MethodSymbol playerSelect, MethodSymbol serverLookup, MethodSymbol serverCatalog,
            MethodSymbol serverReload, MethodSymbol serverSync, MethodSymbol serverExport,
            MethodSymbol clientLookup, String nativeGeometryClass) {}

    private YsmRuntimeSymbols() {}

    public static Snapshot inspect(Path jar, String version) throws IOException {
        if (!VERSION.equals(version)) {
            throw new IllegalArgumentException("Unsupported YSM version: " + version);
        }
        List<ClassNode> classes = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().startsWith("com/elfmcys/yesstevemodel/")
                        || !entry.getName().endsWith(".class")) continue;
                if (entry.getSize() > 4 * 1024 * 1024) {
                    throw new IOException("Oversized YSM class entry");
                }
                ClassNode node = new ClassNode();
                try (InputStream input = zip.getInputStream(entry)) {
                    new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
                classes.add(node);
            }
        }
        return resolve(classes);
    }

    static Snapshot resolve(List<ClassNode> classes) {
        ClassNode state = unique(classes.stream().filter(c ->
                c.interfaces.contains("net/neoforged/neoforge/common/util/INBTSerializable")
                && constant(c, "model_id") && constant(c, "select_texture")
                && constant(c, "molang_storage")).toList(), "server player state");
        MethodSymbol playerState = method(state, m -> publicStatic(m)
                && m.desc.equals("(" + PLAYER + ")Ljava/util/Optional;")
                && m.signature != null && m.signature.contains("L" + state.name + ";"), "player state lookup");

        ClassNode server = unique(classes.stream().filter(c -> c.methods.stream().anyMatch(m ->
                nativeStatic(m) && m.desc.equals("(Ljava/util/UUID;Ljava/nio/ByteBuffer;)V"))
                && c.methods.stream().anyMatch(m -> nativeStatic(m)
                && m.desc.equals("(" + STRING + STRING + CONSUMER + ")V"))).toList(), "server model manager");
        ClassNode client = unique(classes.stream().filter(c -> constant(c, "Failed to process {}")
                && c.methods.stream().anyMatch(m -> nativeStatic(m)
                && m.desc.equals("(Ljava/nio/ByteBuffer;)V"))).toList(), "client model manager");
        ClassNode geometry = unique(classes.stream().filter(c ->
                c.fields.stream().anyMatch(f -> (f.access & Opcodes.ACC_STATIC) == 0 && f.desc.equals("J"))
                && c.methods.stream().anyMatch(m -> (m.access & Opcodes.ACC_NATIVE) != 0 && m.desc.equals("(J)V"))
                && c.methods.stream().anyMatch(m -> m.name.equals("<init>")
                && m.desc.startsWith("([Lcom/elfmcys/yesstevemodel/")
                && m.desc.contains("[[Ljava/lang/String;") && m.desc.endsWith("[ZJ)V"))).toList(), "native geometry");
        return new Snapshot(playerState,
                method(state, m -> (m.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)) == Opcodes.ACC_PUBLIC
                        && m.desc.equals("(" + STRING + STRING + ")V"), "player model selection"),
                method(server, m -> publicStatic(m) && m.desc.equals("(" + STRING + ")Ljava/util/Optional;"), "server lookup"),
                method(server, m -> publicStatic(m) && m.desc.equals("()Ljava/util/Map;"), "server catalog"),
                method(server, m -> publicStatic(m) && m.desc.equals("(" + CONSUMER + CONSUMER + ")Z"), "server reload"),
                method(server, m -> publicStatic(m) && m.desc.equals("(" + PLAYER + CONSUMER + ")V"), "server sync"),
                method(server, m -> publicStatic(m) && nativeStatic(m)
                        && m.desc.equals("(" + STRING + STRING + CONSUMER + ")V"), "server export"),
                method(client, m -> publicStatic(m) && m.desc.equals("(" + STRING + ")Ljava/util/Optional;"), "client lookup"),
                geometry.name);
    }

    private static MethodSymbol method(ClassNode owner, Predicate<MethodNode> predicate, String role) {
        MethodNode node = unique(owner.methods.stream().filter(predicate).toList(), role);
        return new MethodSymbol(owner.name, node.name, node.desc);
    }

    private static boolean publicStatic(MethodNode method) {
        return (method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC))
                == (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
    }

    private static boolean nativeStatic(MethodNode method) {
        return (method.access & (Opcodes.ACC_NATIVE | Opcodes.ACC_STATIC))
                == (Opcodes.ACC_NATIVE | Opcodes.ACC_STATIC);
    }

    private static boolean constant(ClassNode node, String text) {
        return node.methods.stream().anyMatch(m -> {
            for (var instruction : m.instructions) {
                if (instruction instanceof LdcInsnNode ldc && text.equals(ldc.cst)) return true;
            }
            return false;
        });
    }

    private static <T> T unique(List<T> candidates, String role) {
        if (candidates.size() != 1) {
            throw new IllegalStateException("YSM " + role + ": expected one candidate, found " + candidates.size());
        }
        return candidates.getFirst();
    }
}
