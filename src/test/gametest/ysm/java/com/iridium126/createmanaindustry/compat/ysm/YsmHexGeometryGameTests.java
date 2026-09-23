package com.iridium126.createmanaindustry.compat.ysm;

import java.util.List;
import com.iridium126.createmanaindustry.compat.hexcasting.ysm.*;
import at.petrak.hexcasting.api.casting.iota.*;
import at.petrak.hexcasting.api.casting.mishaps.Mishap;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
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
    public static void immutableEditingAndTraversal(GameTestHelper helper) throws Mishap {
        var original = (CubeIota) op(true, "create", false);
        var moved = (CubeIota) op(true, "origin", true, original, new Vec3Iota(new Vec3(3, 4, 5)));
        helper.assertTrue(original.value().origin().x() == 0 && moved.value().origin().x() == 3, "Cube editing mutated its source");
        var blank = (GroupIota) op(false, "create", false);
        var group = (GroupIota) op(false, "cubes", true, blank, new ListIota(List.of(moved)));
        helper.assertTrue(blank.value().cubes().isEmpty() && group.value().cubes().size() == 1, "List replacement mutated its source");
        var renamed = (GroupIota) op(false, "name", true, group, new ListIota(List.of(new DoubleIota(0x9aa8), new DoubleIota(0x1f600))));
        helper.assertTrue(renamed.value().name().equals("骨😀"), "Unicode name was not preserved");
        helper.assertTrue(group.visit(iota -> iota) == group, "Identity traversal rebuilt a group");
        var traversed = group.visit(iota -> iota instanceof CubeIota ? original : iota);
        helper.assertTrue(traversed instanceof GroupIota g && g.value().cubes().getFirst().equals(original.value()), "Traversal did not replace cube");
        helper.assertTrue(group.visit(iota -> iota instanceof CubeIota ? new NullIota() : iota) instanceof GarbageIota, "Incompatible traversal was not rejected");
        try {
            op(false, "name", true, group, new ListIota(List.of(new DoubleIota(0xd800))));
            helper.fail("Unpaired Unicode surrogate accepted");
        } catch (Mishap expected) { }
        Iota uv = op(true, "uv", false, moved);
        helper.assertTrue(op(true, "uv", true, moved, uv).equals(moved), "Per-face UV did not round trip");
        helper.succeed();
    }
    private static Iota op(boolean cube, String property, boolean setter, Iota... args) throws Mishap {
        return new OpGeometry(cube, property, setter).execute(List.of(args), null).getFirst();
    }
}
