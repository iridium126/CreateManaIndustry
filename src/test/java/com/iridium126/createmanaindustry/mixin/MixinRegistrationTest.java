package com.iridium126.createmanaindustry.mixin;

import static org.junit.jupiter.api.Assertions.*;

import com.electronwill.nightconfig.toml.TomlParser;
import com.google.gson.JsonParser;
import com.iridium126.createmanaindustry.infrastructure.config.ExperimentalStartup.Settings;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig.HexJitMode;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

class MixinRegistrationTest {
    @Test
    void everyMixinHasOneRegistrationAndGroup() throws Exception {
        var manifestEntries = new HashSet<String>();
        try (var metadata = resource("META-INF/neoforge.mods.toml")) {
            List<com.electronwill.nightconfig.core.Config> declarations = new TomlParser().parse(
                    new InputStreamReader(metadata, StandardCharsets.UTF_8)).get("mixins");
            assertEquals(2, declarations.size());
            for (var declaration : declarations) {
                String config = declaration.get("config");
                boolean jit = config.equals("createmanaindustry.hexjit.mixins.json");
                try (var input = resource(config)) {
                    var manifest = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
                    assertEquals(CMIMixinPlugin.class.getName(), manifest.get("plugin").getAsString());
                    assertTrue(manifest.get("required").getAsBoolean(), config);
                    assertEquals(1, manifest.getAsJsonObject("injectors").get("defaultRequire").getAsInt());
                    if (jit) assertEquals(1, manifest.get("priority").getAsInt());
                    String prefix = manifest.get("package").getAsString() + ".";
                    for (String side : List.of("mixins", "client")) {
                        if (!manifest.has(side)) continue;
                        for (var entry : manifest.getAsJsonArray(side)) {
                            String mixin = prefix + entry.getAsString();
                            assertTrue(manifestEntries.add(mixin), "Duplicate Mixin " + mixin);
                            var group = MixinGroups.of(mixin);
                            assertNotNull(group, "Missing group for " + mixin);
                            assertEquals(jit, group == MixinGroups.HEX_JIT, mixin);
                            if (group.clientOnly) assertEquals("client", side, mixin);
                            if (group == MixinGroups.PACKAGE_SERVER || group == MixinGroups.PACKAGE_SHARED)
                                assertEquals("mixins", side, mixin);
                            verifyTargetSide(mixin, side);
                        }
                    }
                }
            }
        }
        for (String name : MixinGroups.overrides()) {
            assertTrue(manifestEntries.contains(MixinGroups.ROOT + name), "Unused Mixin rule " + name);
        }
        Path classes = Path.of(CMIMixinPlugin.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Set<String> compiledMixins = new HashSet<>();
        try (var files = Files.walk(classes.resolve(MixinGroups.ROOT.replace('.', '/')))) {
            for (Path path : files.filter(file -> file.toString().endsWith(".class")).toList()) {
                try (var input = Files.newInputStream(path)) {
                    var node = read(input);
                    if (node.invisibleAnnotations != null && node.invisibleAnnotations.stream()
                            .anyMatch(annotation -> annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")))
                        compiledMixins.add(node.name.replace('/', '.'));
                }
            }
        }
        assertEquals(compiledMixins, manifestEntries);
    }

    @Test
    void startupGroupsRespectOptionalDependenciesAndPhysicalSides() {
        var all = Set.of("create", "hexcasting", "iris", "irisveil", "sable");
        var server = new Settings(true, false, HexJitMode.PROFILE);
        var client = new Settings(false, true, HexJitMode.AUTO);
        var off = new Settings(false, false, HexJitMode.OFF);
        assertTrue(MixinGroups.PACKAGE_SERVER.enabled(true, server, all::contains));
        assertFalse(MixinGroups.PACKAGE_CLIENT.enabled(true, server, all::contains));
        assertTrue(MixinGroups.PACKAGE_SABLE.enabled(true, client, all::contains));
        assertFalse(MixinGroups.PACKAGE_SABLE.enabled(false, client, all::contains));
        assertFalse(MixinGroups.PACKAGE_SABLE.enabled(true, client, Set.of("create")::contains));
        assertTrue(MixinGroups.PACKAGE_SHARED.enabled(false, server, all::contains));
        assertTrue(MixinGroups.PACKAGE_SHARED.enabled(true, client, all::contains));
        assertFalse(MixinGroups.PACKAGE_SHARED.enabled(true, off, all::contains));
        assertFalse(MixinGroups.HEX_JIT.enabled(true, off, all::contains));
        assertTrue(MixinGroups.HEX_JIT.enabled(false, server, all::contains));
        assertFalse(MixinGroups.HEX_JIT.enabled(false, server, Set.of("hexal")::contains));
        assertTrue(MixinGroups.IRIS_HEX.enabled(true, off, all::contains));
        assertFalse(MixinGroups.IRIS_HEX.enabled(true, off, Set.of("iris")::contains));
        assertFalse(MixinGroups.IRIS_VEIL.enabled(true, off, Set.of("irisveil")::contains));
        assertFalse(MixinGroups.JEI.enabled(true, off, all::contains));
    }

    private static void verifyTargetSide(String mixin, String side) throws IOException {
        if (!side.equals("mixins")) return;
        try (var input = resource(mixin.replace('.', '/') + ".class")) {
            var annotation = read(input).invisibleAnnotations.stream()
                    .filter(value -> value.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")).findFirst().orElseThrow();
            for (int i = 0; i < annotation.values.size(); i += 2) {
                if (!Set.of("value", "targets").contains(annotation.values.get(i))) continue;
                for (Object target : (List<?>) annotation.values.get(i + 1)) {
                    String name = target.toString().replace('.', '/');
                    assertFalse(name.contains("net/minecraft/client/") || name.contains("com/mojang/blaze3d/"), mixin);
                }
            }
        }
    }

    private static ClassNode read(InputStream input) throws IOException {
        var node = new ClassNode();
        new ClassReader(input).accept(node, ClassReader.SKIP_CODE);
        return node;
    }

    private static InputStream resource(String name) {
        var input = MixinRegistrationTest.class.getClassLoader().getResourceAsStream(name);
        assertNotNull(input, name);
        return input;
    }
}
