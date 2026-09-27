package com.iridium126.createmanaindustry.compat.hexparse;

import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.ListIota;
import at.petrak.hexcasting.api.casting.iota.PatternIota;
import at.petrak.hexcasting.common.casting.PatternRegistryManifest;
import at.petrak.hexcasting.xplat.IXplatAbstractions;
import com.iridium126.createmanaindustry.compat.hexcasting.TrickIota;
import com.iridium126.createmanaindustry.compat.hexcasting.ysm.CubeIota;
import com.iridium126.createmanaindustry.compat.hexcasting.ysm.GroupIota;
import com.iridium126.createmanaindustry.compat.ysm.YsmServerRuntime;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmModelSnapshot;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmPortableGeometryCodec;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmResourceArchive;
import io.yukkuric.hexparse.misc.CodeHelpers;
import io.yukkuric.hexparse.misc.StringProcessors;
import io.yukkuric.hexparse.parsers.ParserMain;
import io.yukkuric.hexparse.parsers.nbt2str.INbt2Str;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class HexParseCompatGameTests {
    @GameTest(template = "worldgen_test")
    public static void customIotasAndRegisteredActionsRoundTrip(GameTestHelper helper) throws Exception {
        helper.assertTrue(ModList.get().isLoaded("hexparse"), "HexParse test profile is not loaded");
        CodeHelpers.autoRefresh(helper.getLevel().getServer());
        var player = FakePlayerFactory.getMinecraft(helper.getLevel());

        TrickIota trick = new TrickIota(new dev.enjarai.trickster.spell.SpellPart(
                new dev.enjarai.trickster.spell.fragment.NumberFragment(42.5)));
        String trickText = ParserMain.ParseIotaNbt(trick, player, StringProcessors.READ_DEFAULT);
        Iota parsedTrick = parseOne(trickText, player);
        helper.assertTrue(parsedTrick instanceof TrickIota decoded
                        && decoded.getSpell().equals(trick.getSpell()),
                "TrickIota HexParse token did not round trip");
        assertTrickPayloadsRejected(helper, trick.getSpell().toBytes());

        List<String> actionIds = new java.util.ArrayList<>(List.of(
                "createmanaindustry:read_iota_from_block",
                "createmanaindustry:light_burner",
                "createmanaindustry:players_in_circle"));
        if (ModList.get().isLoaded("trickster")) actionIds.addAll(List.of(
                "createmanaindustry:read_trick_from_item", "createmanaindustry:execute_trick"));
        if (ModList.get().isLoaded("yes_steve_model")) actionIds.addAll(List.of(
                "createmanaindustry:ysm_cube_create", "createmanaindustry:ysm_group_create",
                "createmanaindustry:ysm_geometry_get", "createmanaindustry:ysm_geometry_set",
                "createmanaindustry:ysm_model_read", "createmanaindustry:ysm_model_apply",
                "createmanaindustry:ysm_model_export", "createmanaindustry:ysm_model_restore"));

        var registry = IXplatAbstractions.INSTANCE.getActionRegistry();
        for (String id : actionIds) {
            var key = ResourceLocation.parse(id);
            var entry = registry.get(key);
            helper.assertTrue(entry != null, "Expected CMI Action is not registered: " + id);
            var original = new PatternIota(entry.prototype());
            String text = ParserMain.ParseIotaNbt(original, player, StringProcessors.READ_DEFAULT);
            helper.assertTrue(text != null && !text.isBlank(), "HexParse emitted an empty Action token for " + id);
            Iota parsed = parseOne(text, player);
            helper.assertTrue(parsed instanceof PatternIota result
                            && result.getPattern().getSignature().equals(original.getPattern().getSignature()),
                    "HexParse did not parse Action ID back to its registered pattern: " + id);
            helper.assertTrue(PatternRegistryManifest.matchPattern(original.getPattern(),
                            new at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv(player,
                                    net.minecraft.world.InteractionHand.MAIN_HAND)) != null,
                    "Hexcasting did not recognize registered Action pattern: " + id);
        }

        if (ModList.get().isLoaded("yes_steve_model")) portableYsmIotasRoundTrip(helper, player);
        assertStringIotaHasNoHexParseBackParser(helper);
        helper.succeed();
    }

    private static Iota parseOne(String text, net.minecraft.server.level.ServerPlayer player) {
        ListIota result = (ListIota) ParserMain.ParseCode(text, player);
        if (result.getList().size() != 1) throw new IllegalStateException("HexParse returned an unexpected iota count");
        return result.getList().getFirst();
    }

    private static void portableYsmIotasRoundTrip(GameTestHelper helper,
            net.minecraft.server.level.ServerPlayer player) {
        var snapshot = YsmModelSnapshot.fromArchive(sampleArchive());
        var root = snapshot.roots().getFirst();
        var source = new YsmPortableGeometryCodec.Source(snapshot.archive(), root.root().part(), root.root().geometryIndex());
        var cube = root.children().getFirst().cubes().getFirst();
        String cubeText = YsmPortableGeometryCodec.encodeCube(new YsmPortableGeometryCodec.CubeData(cube, source));
        String groupText = YsmPortableGeometryCodec.encodeGroup(new YsmPortableGeometryCodec.GroupData(root, source));

        Iota parsedCube = parseOne(cubeText, player);
        Iota parsedGroup = parseOne(groupText, player);
        helper.assertTrue(parsedCube instanceof CubeIota && parsedGroup instanceof GroupIota,
                "Portable YSM tokens did not create save-local geometry iotas");
        String cubeRoundTrip = ParserMain.ParseIotaNbt(parsedCube, player, StringProcessors.READ_DEFAULT);
        String groupRoundTrip = ParserMain.ParseIotaNbt(parsedGroup, player, StringProcessors.READ_DEFAULT);
        helper.assertTrue(YsmPortableGeometryCodec.decodeCube(cubeRoundTrip).geometry().equals(cube),
                "Portable CubeIota geometry changed during parse and format");
        helper.assertTrue(YsmPortableGeometryCodec.decodeGroup(groupRoundTrip).geometry().equals(root),
                "Portable GroupIota tree changed during parse and format");
        helper.assertTrue(YsmServerRuntime.get(player.server).references().preview(((CubeIota) parsedCube).key(), true).texture() != null,
                "Portable CubeIota lost its source YSM texture");
        helper.assertTrue(YsmServerRuntime.get(player.server).references().preview(((GroupIota) parsedGroup).key(), false).texture() != null,
                "Portable GroupIota lost its source YSM texture");
    }

    private static void assertStringIotaHasNoHexParseBackParser(GameTestHelper helper) throws Exception {
        if (!ModList.get().isLoaded("yes_steve_model")) return;
        Field field = ParserMain.class.getDeclaredField("nbt2strParsers");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") List<INbt2Str<?>> parsers = (List<INbt2Str<?>>) field.get(null);
        helper.assertTrue(parsers.stream().noneMatch(parser -> parser.getType() ==
                        com.iridium126.createmanaindustry.compat.hexcasting.ysm.StringIota.class),
                "StringIota must not receive a HexParse back parser");
    }

    private static void assertTrickPayloadsRejected(GameTestHelper helper, byte[] validSpell) throws Exception {
        assertTrickRejected(helper, trickToken(new byte[]{1, 2, 3}), "Truncated TrickIota header was accepted");
        assertTrickRejected(helper, trickToken(trickEnvelope(255, 1, new byte[]{1})),
                "Unknown TrickIota version was accepted");
        assertTrickRejected(helper, trickToken(trickEnvelope(1, Integer.MAX_VALUE, new byte[0])),
                "Oversized TrickIota spell was accepted");
        assertTrickRejected(helper, trickToken(trickEnvelope(1, 1, new byte[0])),
                "Truncated TrickIota spell was accepted");
        byte[] validEnvelope = trickEnvelope(1, validSpell.length, validSpell);
        byte[] trailing = java.util.Arrays.copyOf(validEnvelope, validEnvelope.length + 1);
        trailing[trailing.length - 1] = 42;
        assertTrickRejected(helper, trickToken(trailing), "Trailing compressed TrickIota data was accepted");
    }

    private static void assertTrickRejected(GameTestHelper helper, String text, String message) {
        boolean rejected = false;
        try {
            TrickIotaHexParseAdapter.INSTANCE.parse(text);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, message);
    }

    private static String trickToken(byte[] compressed) {
        return "createmanaindustry:trick_" + Base64.getUrlEncoder().withoutPadding().encodeToString(compressed);
    }

    private static byte[] trickEnvelope(int version, int spellLength, byte[] spell) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(new DeflaterOutputStream(bytes))) {
            output.writeInt(0x434d4954);
            output.writeByte(version);
            output.writeInt(spellLength);
            output.write(spell);
        }
        return bytes.toByteArray();
    }

    private static YsmResourceArchive sampleArchive() {
        String manifest = "{\"files\":{\"player\":{\"model\":\"geometry/player.json\",\"texture\":\"textures/player.png\"}}}";
        String geometry = "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{\"description\":{\"identifier\":\"geometry.test\",\"texture_width\":64,\"texture_height\":64},\"bones\":[{\"name\":\"root\",\"pivot\":[0,0,0],\"cubes\":[{\"origin\":[0,0,0],\"size\":[1,1,1],\"uv\":{\"north\":{\"uv\":[0,0],\"uv_size\":[1,1]}}}]}]}]}";
        return new YsmResourceArchive(Map.of(
                "ysm.json", manifest.getBytes(StandardCharsets.UTF_8),
                "geometry/player.json", geometry.getBytes(StandardCharsets.UTF_8),
                "textures/player.png", new byte[]{1, 2, 3, 4}));
    }

    private HexParseCompatGameTests() {}
}
