package com.iridium126.createmanaindustry.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class TricksterJarPatchServiceTest {
    private static final String MIXIN = "dev/enjarai/trickster/mixin/client/key/KeyBindingMixin.class";
    private static final String WORLD_MIXIN = "dev/enjarai/trickster/mixin/chunk_pinning/ServerWorldMixin.class";
    private static final String MARKER = "META-INF/cmi/trickster-keybinding-patch.txt";
    private static final byte[] EXTRA = "custom translation".getBytes(StandardCharsets.UTF_8);

    @TempDir Path mods;

    @Test
    void patchesOriginalAndPreservesResourcesBackupAndIdempotence() throws Exception {
        Path jar = writeJar("trickster.jar", "2.0.0-beta.56", originalClass(), null);
        byte[] originalJar = Files.readAllBytes(jar);
        patch();
        assertPatched(jar);
        assertArrayEquals(originalJar, Files.readAllBytes(mods.resolve("trickster.jar.backup")));
        byte[] patchedJar = Files.readAllBytes(jar);
        patch();
        assertArrayEquals(patchedJar, Files.readAllBytes(jar));
    }

    @Test
    void leavesOldPatchAndBackupUnchanged() throws Exception {
        Path backup = writeJar("trickster.jar.backup", "2.0.0-beta.56", originalClass(), null);
        byte[] originalBackup = Files.readAllBytes(backup);
        ClassNode node = new ClassNode();
        new ClassReader(originalClass()).accept(node, 0);
        node.methods.stream().filter(method -> isWrapper(method.name)).forEach(method -> method.parameters.clear());
        ClassWriter writer = new ClassWriter(new ClassReader(originalClass()), 0);
        node.accept(writer);
        Path jar = writeJar("trickster.jar", "2.0.0-beta.56", writer.toByteArray(), "legacy-v2");
        assertUnchanged(jar);
        assertArrayEquals(originalBackup, Files.readAllBytes(backup));
    }

    @Test
    void refusesUnknownVersionChangedClassAndAmbiguousInstallation() throws Exception {
        Path jar = writeJar("trickster.jar", "2.0.0-beta.57", originalClass(), null);
        assertUnchanged(jar);
        ClassNode node = new ClassNode();
        new ClassReader(originalClass()).accept(node, 0);
        node.sourceFile = "Modified.java";
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        jar = writeJar("trickster.jar", "2.0.0-beta.56", writer.toByteArray(), null);
        assertUnchanged(jar);
        jar = writeJar("trickster.jar", "2.0.0-beta.56", originalClass(), null);
        writeJar("duplicate.jar", "2.0.0-beta.56", originalClass(), null);
        assertUnchanged(jar);
        assertFalse(Files.exists(mods.resolve("trickster.jar.backup")));
    }

    @Test
    void leavesOriginalIntactWhenBackupDoesNotMatch() throws Exception {
        Path jar = writeJar("trickster.jar", "2.0.0-beta.56", originalClass(), null);
        Path backup = mods.resolve("trickster.jar.backup");
        Files.writeString(backup, "unrelated backup");
        assertUnchanged(jar);
        assertEquals("unrelated backup", Files.readString(backup));
    }

    private void assertUnchanged(Path jar) throws Exception {
        byte[] before = Files.readAllBytes(jar);
        patch();
        assertArrayEquals(before, Files.readAllBytes(jar));
    }

    private static byte[] originalClass() throws Exception {
        try (InputStream input = TricksterJarPatchServiceTest.class.getClassLoader().getResourceAsStream(MIXIN)) {
            assertNotNull(input, "Raw Trickster runtime dependency is required");
            return input.readAllBytes();
        }
    }

    private Path writeJar(String name, String version, byte[] mixin, String marker) throws Exception {
        Path path = mods.resolve(name);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            entry(output, "fabric.mod.json", ("{\"id\":\"trickster\",\"version\":\"" + version + "\"}")
                    .getBytes(StandardCharsets.UTF_8));
            entry(output, MIXIN, mixin);
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(WORLD_MIXIN)) {
                assertNotNull(input);
                entry(output, WORLD_MIXIN, input.readAllBytes());
            }
            entry(output, "assets/trickster/lang/custom.json", EXTRA);
            if (marker != null) entry(output, MARKER, marker.getBytes(StandardCharsets.UTF_8));
        }
        return path;
    }

    private static void entry(JarOutputStream output, String name, byte[] bytes) throws Exception {
        output.putNextEntry(new JarEntry(name));
        output.write(bytes);
        output.closeEntry();
    }

    private void patch() throws Exception {
        Method method = TricksterJarPatchService.class.getDeclaredMethod("patchInstalledTrickster", Path.class);
        method.setAccessible(true);
        method.invoke(null, mods);
    }

    private static boolean isWrapper(String name) {
        return name.equals("applyKeybindContext") || name.equals("applyKeybindContext2")
                || name.equals("skipAddingContextualKeys");
    }

    private static void assertPatched(Path path) throws Exception {
        try (JarFile jar = new JarFile(path.toFile())) {
            assertTrue(new String(jar.getInputStream(jar.getJarEntry(MARKER)).readAllBytes(), StandardCharsets.UTF_8)
                    .contains("beta56-v4"));
            assertArrayEquals(EXTRA, jar.getInputStream(jar.getJarEntry("assets/trickster/lang/custom.json")).readAllBytes());
            ClassNode world = new ClassNode();
            new ClassReader(jar.getInputStream(jar.getJarEntry(WORLD_MIXIN))).accept(world, 0);
            var inject = world.methods.stream().filter(m -> m.name.equals("worldPinTick")).findFirst().orElseThrow()
                    .visibleAnnotations.getFirst();
            assertEquals(java.util.List.of("method_39998(Lnet/minecraft/class_1923;)Z"),
                    inject.values.get(inject.values.indexOf("method") + 1));
            ClassNode node = new ClassNode();
            new ClassReader(jar.getInputStream(jar.getJarEntry(MIXIN))).accept(node, 0);
            ClassNode original = new ClassNode();
            new ClassReader(originalClass()).accept(original, 0);
            assertEquals(original.methods.size(), node.methods.size());
            for (var method : node.methods) {
                if (!isWrapper(method.name)) {
                    var before = original.methods.stream().filter(m -> m.name.equals(method.name)).findFirst().orElseThrow();
                    assertEquals(before.desc, method.desc);
                    assertEquals(before.instructions.size(), method.instructions.size());
                    continue;
                }
                assertTrue(method.desc.startsWith("(Lnet/neoforged/neoforge/client/settings/KeyMappingLookup;"));
                assertEquals(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, method.access);
                assertTrue(method.visibleAnnotations.stream().anyMatch(annotation ->
                        annotation.desc.endsWith("/WrapOperation;") && annotation.values.contains(0)));
                assertTrue(method.instructions.size() > 0);
            }
        }
    }
}
