package com.iridium126.createmanaindustry.compat.ysm;

import java.util.List;
import com.iridium126.createmanaindustry.compat.hexcasting.ysm.*;
import at.petrak.hexcasting.api.casting.iota.*;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class YsmHexGeometryGameTests {
    @GameTest(template = "worldgen_test")
    public static void archivePacketCodecBounds(GameTestHelper helper) {
        var header = com.iridium126.createmanaindustry.compat.ysm.net.ClientboundYsmArchivePacket.begin(
                com.iridium126.createmanaindustry.compat.ysm.net.ClientboundYsmArchivePacket.EXPORT,
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), 7L, "a".repeat(64), "", 12345);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            var codec = com.iridium126.createmanaindustry.compat.ysm.net.ClientboundYsmArchivePacket.STREAM_CODEC;
            codec.encode(buffer, header);
            var decoded = codec.decode(buffer);
            helper.assertTrue(header.transfer().equals(decoded.transfer()) && decoded.kind() == header.kind()
                    && decoded.size() == 12345 && !buffer.isReadable(), "Archive header codec failed");
        } finally { buffer.release(); }
        var oversized = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            oversized.writeByte(com.iridium126.createmanaindustry.compat.ysm.net.ClientboundYsmArchivePacket.CHUNK);
            oversized.writeUUID(java.util.UUID.randomUUID()); oversized.writeUUID(java.util.UUID.randomUUID());
            oversized.writeLong(1); oversized.writeVarInt(0); oversized.writeByteArray(new byte[16385]);
            try {
                com.iridium126.createmanaindustry.compat.ysm.net.ClientboundYsmArchivePacket.STREAM_CODEC.decode(oversized);
                helper.fail("Oversized archive chunk accepted");
            } catch (io.netty.handler.codec.DecoderException expected) { }
        } finally { oversized.release(); }
        helper.succeed();
    }
    @GameTest(template = "worldgen_test")
    public static void inlinePreviewPacketCodecs(GameTestHelper helper) {
        String key = "cd".repeat(32);
        var request = new com.iridium126.createmanaindustry.compat.ysm.net.ServerboundYsmPreviewRequestPacket(key, true);
        var requestBuffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            var codec = com.iridium126.createmanaindustry.compat.ysm.net.ServerboundYsmPreviewRequestPacket.STREAM_CODEC;
            codec.encode(requestBuffer, request);
            helper.assertTrue(requestBuffer.readableBytes() == 34, "Preview request must contain the kind, key length, and 32-byte key");
            var decoded = codec.decode(requestBuffer);
            helper.assertTrue(decoded.key().equals(key) && decoded.cube() && !requestBuffer.isReadable(),
                    "Preview request codec round trip failed");
        } finally { requestBuffer.release(); }

        byte[] png = new byte[] {1, 2, 3};
        var response = com.iridium126.createmanaindustry.compat.ysm.net.ClientboundYsmPreviewPacket.image(key, true, png);
        var responseBuffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            var codec = com.iridium126.createmanaindustry.compat.ysm.net.ClientboundYsmPreviewPacket.STREAM_CODEC;
            codec.encode(responseBuffer, response);
            var decoded = codec.decode(responseBuffer);
            helper.assertTrue(decoded.key().equals(key) && decoded.kind() == response.kind()
                    && decoded.cube() && java.util.Arrays.equals(decoded.png(), png),
                    "Preview image codec round trip failed");
        } finally { responseBuffer.release(); }

        var oversized = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            oversized.writeByte(com.iridium126.createmanaindustry.compat.ysm.net.ClientboundYsmPreviewPacket.IMAGE);
            oversized.writeByteArray(java.util.HexFormat.of().parseHex(key));
            oversized.writeBoolean(false);
            oversized.writeByteArray(new byte[
                    com.iridium126.createmanaindustry.compat.ysm.net.ClientboundYsmPreviewPacket.MAX_IMAGE_BYTES + 1]);
            try {
                com.iridium126.createmanaindustry.compat.ysm.net.ClientboundYsmPreviewPacket.STREAM_CODEC.decode(oversized);
                helper.fail("Oversized preview image accepted");
            } catch (io.netty.handler.codec.DecoderException expected) { }
        } finally { oversized.release(); }
        helper.succeed();
    }
    @GameTest(template = "worldgen_test")
    public static void geometryIotasAttachInlineData(GameTestHelper helper) {
        if (!net.neoforged.fml.ModList.get().isLoaded("inline")) {
            helper.succeed();
            return;
        }
        String key = "ef".repeat(32);
        List<Iota> values = List.of(new GroupIota(key), new CubeIota("12".repeat(32)));
        List<com.iridium126.createmanaindustry.compat.hexcasting.ysm.InlineYsmGeometryData.Kind> expected = List.of(
                com.iridium126.createmanaindustry.compat.hexcasting.ysm.InlineYsmGeometryData.Kind.GROUP,
                com.iridium126.createmanaindustry.compat.hexcasting.ysm.InlineYsmGeometryData.Kind.CUBE);
        for (int i = 0; i < values.size(); i++) {
            var inline = ((com.samsthenerd.inline.impl.InlineStyle) values.get(i).display().getStyle()).getInlineData();
            helper.assertTrue(inline instanceof com.iridium126.createmanaindustry.compat.hexcasting.ysm.InlineYsmGeometryData data
                    && data.kind() == expected.get(i), "Geometry Iota display did not attach its Inline preview data");
            var data = (com.iridium126.createmanaindustry.compat.hexcasting.ysm.InlineYsmGeometryData) inline;
            var encoded = data.getType().getCodec().encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, data).getOrThrow();
            var decoded = data.getType().getCodec().parse(net.minecraft.nbt.NbtOps.INSTANCE, encoded).getOrThrow();
            helper.assertTrue(decoded.kind() == data.kind() && decoded.key().equals(data.key()),
                    "Inline geometry data codec round trip failed");
        }
        helper.succeed();
    }
    @GameTest(template = "worldgen_test")
    public static void compactReferenceIotaCodecs(GameTestHelper helper) {
        String key = "ab".repeat(32);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            GroupIota group = new GroupIota(key);
            GroupIota.TYPE.streamCodec().encode(buffer, group);
            helper.assertTrue(buffer.readableBytes() == 32, "Group reference network payload is not 32 bytes");
            GroupIota decoded = GroupIota.TYPE.streamCodec().decode(buffer);
            helper.assertTrue(decoded.key().equals(key) && decoded.size() == 1 && decoded.depth() == 1,
                    "Group reference stream round trip failed");
        } finally { buffer.release(); }

        var tag = GroupIota.TYPE.codec().codec().encodeStart(net.minecraft.nbt.NbtOps.INSTANCE,
                new GroupIota(key)).getOrThrow();
        var decoded = GroupIota.TYPE.codec().codec().parse(net.minecraft.nbt.NbtOps.INSTANCE, tag).getOrThrow();
        helper.assertTrue(decoded.key().equals(key), "Persistent group reference codec round trip failed");
        helper.succeed();
    }
    @GameTest(template = "worldgen_test")
    public static void greatSpellTagsAndSlateExclusion(GameTestHelper helper) {
        var registry = at.petrak.hexcasting.xplat.IXplatAbstractions.INSTANCE.getActionRegistry();
        for (String action : List.of("ysm_model_read", "ysm_model_apply", "ysm_model_export")) {
            var key = net.minecraft.resources.ResourceKey.create(at.petrak.hexcasting.common.lib.HexRegistries.ACTION,
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("createmanaindustry", action));
            for (var tag : List.of(at.petrak.hexcasting.api.mod.HexTags.Actions.REQUIRES_ENLIGHTENMENT,
                    at.petrak.hexcasting.api.mod.HexTags.Actions.CAN_START_ENLIGHTEN,
                    at.petrak.hexcasting.api.mod.HexTags.Actions.PER_WORLD_PATTERN)) {
                helper.assertTrue(at.petrak.hexcasting.api.utils.HexUtils.isOfTag(registry, key, tag),
                        "YSM great spell tag is missing: " + action + " / " + tag.location());
            }
        }
        var restore = net.minecraft.resources.ResourceKey.create(at.petrak.hexcasting.common.lib.HexRegistries.ACTION,
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("createmanaindustry", "ysm_model_restore"));
        helper.assertTrue(!at.petrak.hexcasting.api.utils.HexUtils.isOfTag(registry, restore,
                at.petrak.hexcasting.api.mod.HexTags.Actions.PER_WORLD_PATTERN), "Restore unexpectedly became a great spell");
        var recipes = com.iridium126.createmanaindustry.compat.hexcasting.CMISlatePatternRecipes.createRecipes();
        helper.assertTrue(recipes.stream().noneMatch(recipe -> recipe.id().getPath().endsWith("/ysm_model_read")), "Great spell entered slate recipes");
        helper.assertTrue(recipes.stream().noneMatch(recipe -> recipe.id().getPath().endsWith("/ysm_model_apply")), "Application great spell entered slate recipes");
        helper.assertTrue(recipes.stream().noneMatch(recipe -> recipe.id().getPath().endsWith("/ysm_model_export")), "Export great spell entered slate recipes");
        helper.assertTrue(recipes.stream().anyMatch(recipe -> recipe.id().getPath().endsWith("/ysm_model_restore")), "Ordinary restore action lost its slate recipe");
        helper.assertTrue(recipes.stream().anyMatch(recipe -> recipe.id().getPath().endsWith("/ysm_cube_create")), "Ordinary geometry action lost its slate recipe");
        helper.succeed();
    }

    @GameTest(template = "worldgen_test")
    public static void indexedGeometryActionsAndDirectPatternsAreRegistered(GameTestHelper helper) {
        var registry = at.petrak.hexcasting.xplat.IXplatAbstractions.INSTANCE.getActionRegistry();
        for (String id : List.of("ysm_cube_create", "ysm_group_create", "ysm_geometry_get", "ysm_geometry_set",
                "ysm_model_read", "ysm_model_apply", "ysm_model_export", "ysm_model_restore")) {
            var key = net.minecraft.resources.ResourceKey.create(at.petrak.hexcasting.common.lib.HexRegistries.ACTION,
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("createmanaindustry", id));
            helper.assertTrue(registry.get(key) != null, "Expected YSM action is missing: " + id);
        }

        var expectedPatterns = java.util.Map.of(
                "ysm_cube_create", "wewqwwqweewqewqewqewqewqewq",
                "ysm_group_create", "wewqwwqweewqqweewqewqewqqwe",
                "ysm_geometry_get", "wewqwwqweqweewqewqqweewqewq",
                "ysm_geometry_set", "wewqwwqweqweewqewqqweewqqwe");
        for (var expected : expectedPatterns.entrySet()) {
            var key = net.minecraft.resources.ResourceKey.create(at.petrak.hexcasting.common.lib.HexRegistries.ACTION,
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("createmanaindustry", expected.getKey()));
            var pattern = at.petrak.hexcasting.api.casting.math.HexPattern.fromAngleString(expected.getValue(),
                    at.petrak.hexcasting.api.casting.math.HexDir.EAST);
            helper.assertTrue(registry.get(key).prototype().equals(pattern),
                    "YSM action has the wrong direct angle pattern: " + expected.getKey());
        }

        var oldProperties = new java.util.ArrayList<String>();
        for (String property : List.of("origin", "size", "pivot", "rotation", "scale", "inflate", "visible", "uv")) {
            oldProperties.add("ysm_cube_" + property);
            oldProperties.add("ysm_group_" + property);
        }
        for (String property : List.of("name", "cubes", "children", "texture_size", "part", "source")) {
            oldProperties.add("ysm_cube_" + property);
            oldProperties.add("ysm_group_" + property);
        }
        for (String property : List.of("pivot", "rotation", "scale", "visible"))
            oldProperties.add("ysm_geometry_" + property);
        for (String oldAction : oldProperties) {
            for (String operation : List.of("get", "set")) {
                var oldKey = net.minecraft.resources.ResourceKey.create(at.petrak.hexcasting.common.lib.HexRegistries.ACTION,
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("createmanaindustry", oldAction + "_" + operation));
                helper.assertTrue(registry.get(oldKey) == null, "Removed property action is still registered: " + oldAction);
            }
        }
        helper.succeed();
    }
    @GameTest(template = "worldgen_test")
    public static void immutableEditingAndTraversal(GameTestHelper helper) throws Exception {
        var world = java.nio.file.Files.createTempDirectory("cmi-ysm-iota-gametest-");
        try (var store = new com.iridium126.createmanaindustry.compat.ysm.YsmReferenceStore(world)) {
            var original = com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Cube.empty();
            String originalKey = store.writeCube(original);
            var moved = new com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Cube(
                    new com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Vector(3, 4, 5), original.size(),
                    original.pivot(), original.rotation(), original.scale(), original.inflate(), original.visible(),
                    original.faces(), original.extraJson());
            String movedKey = store.writeCubeReference(moved, originalKey);
            helper.assertTrue(store.readCube(originalKey).origin().x() == 0 && store.readCube(movedKey).origin().x() == 3,
                    "Cube edit changed the original node");

            var blank = com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Group.empty();
            String blankKey = store.writeTree(blank);
            var group = new com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Group(blank.name(), blank.pivot(),
                    blank.rotation(), blank.scale(), blank.visible(), List.of(), List.of(), null, blank.extraJson());
            String groupWithCube = store.writeGroupReference(group, List.of(movedKey), List.of(), blankKey);
            var groupNode = store.readGroup(groupWithCube);
            helper.assertTrue(store.cubeKeys(groupNode).equals(List.of(movedKey)) && store.readGroup(blankKey).value().cubes().isEmpty(),
                    "Group list edit changed its original node");

            var renamed = new com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Group("骨😀", group.pivot(),
                    group.rotation(), group.scale(), group.visible(), List.of(), List.of(), null, group.extraJson());
            String renamedKey = store.writeGroupReference(renamed, store.cubeKeys(groupNode), List.of(), groupWithCube);
            helper.assertTrue(store.readGroup(renamedKey).value().name().equals("骨😀"), "Unicode group name was not preserved");

            var groupIota = new GroupIota(groupWithCube);
            helper.assertTrue(groupIota.visit(iota -> iota) == groupIota, "Identity traversal rebuilt an atomic reference");
            helper.assertTrue(groupIota.visit(iota -> iota instanceof CubeIota ? new NullIota() : iota) == groupIota,
                    "Generic traversal entered the referenced geometry tree");
        } finally {
            try (var paths = java.nio.file.Files.walk(world)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.deleteIfExists(path);
            }
        }
        helper.succeed();
    }
}
