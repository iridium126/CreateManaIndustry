package com.iridium126.createmanaindustry.compat.trickster;

import at.petrak.hexcasting.api.casting.iota.*;
import com.iridium126.createmanaindustry.compat.hextrick.data.InlineTrickData;
import com.iridium126.createmanaindustry.compat.hextrick.data.TrickIota;
import com.iridium126.createmanaindustry.compat.hextrick.data.*;
import com.iridium126.createmanaindustry.compat.hextrick.data.IotaFragment;
import dev.enjarai.trickster.spell.*;
import dev.enjarai.trickster.spell.fragment.*;
import io.netty.buffer.Unpooled;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class HexInteropCodecGameTests {
    private static void rejects(GameTestHelper helper, Runnable operation, String message) {
        boolean failed = false;
        try { operation.run(); } catch (RuntimeException expected) { failed = true; }
        helper.assertTrue(failed, message);
    }
    private static byte[] compressed(byte[] bytes) {
        try {
            var output = new ByteArrayOutputStream();
            try (var gzip = new GZIPOutputStream(output)) { gzip.write(bytes); }
            byte[] encoded = output.toByteArray();
            return Arrays.copyOfRange(encoded, 10, encoded.length);
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }

    @GameTest(template = "trickster_stock_test")
    public static void compressionBombProtocolCorruptionAndTrailingDataFail(GameTestHelper helper) {
        rejects(helper, () -> BridgeFragmentCodec.decode(new byte[DecodeBudget.MAX_ENCODED + 1]), "Encoded limit missing");
        byte[] bomb = compressed(new byte[DecodeBudget.MAX_DECODED + 1]);
        rejects(helper, () -> BridgeFragmentCodec.decode(bomb), "High compression ratio escaped cumulative limit");
        rejects(helper, () -> BridgeFragmentCodec.decode(compressed(new byte[]{127, 0})), "Future protocol accepted");
        rejects(helper, () -> BridgeFragmentCodec.decode(compressed(new byte[]{0, 0})), "Unsupported legacy protocol accepted");
        byte[] valid = BridgeFragmentCodec.encode(new SpellPart(new NumberFragment(42)));
        rejects(helper, () -> BridgeFragmentCodec.decode(Arrays.copyOf(valid, valid.length - 1)), "Truncated gzip accepted");
        rejects(helper, () -> BridgeFragmentCodec.decode(Arrays.copyOf(valid, valid.length + 1)), "Trailing gzip data accepted");
        valid[valid.length - 8] ^= 1;
        rejects(helper, () -> BridgeFragmentCodec.decode(valid), "Corrupt CRC accepted");
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void nestedBudgetsShareBytesAndCollectionBounds(GameTestHelper helper) {
        try (var outer = DecodeBudget.enter()) {
            outer.bytes(DecodeBudget.MAX_DECODED - 1);
            try (var inner = DecodeBudget.enter()) {
                rejects(helper, () -> inner.bytes(2), "Nested parser received a fresh byte allowance");
            }
            rejects(helper, () -> outer.bytes(0), "Exhausted budget became usable again");
        }
        try (var fresh = DecodeBudget.enter()) {
            fresh.bytes(1);
            rejects(helper, () -> fresh.collection(Integer.MAX_VALUE), "Oversized collection accepted before allocation");
        }
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void deepGraphsAndCyclesFailBeforeNativeEncoding(GameTestHelper helper) {
        SpellPart deep = new SpellPart(new NumberFragment(42));
        for (int i = 0; i < DecodeBudget.MAX_DEPTH; i++) deep = new SpellPart(deep);
        SpellPart invalid = deep;
        rejects(helper, () -> BridgeFragmentCodec.encode(invalid), "Known graph depth was unbounded");
        var cycle = new SpellPart();
        cycle.glyph = cycle;
        rejects(helper, () -> new TrickIota(cycle), "Cycle entered native recursive codec");
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void brokenInputUsesEachEntrysNativeFailureChannel(GameTestHelper helper) {
        var tag = new CompoundTag();
        tag.putString("type", "createmanaindustry:trick");
        tag.putString("value", "broken");
        helper.assertTrue(IotaType.TYPED_CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow() instanceof GarbageIota, "NBT corruption became an executable empty spell");
        var inline = InlineTrickData.InlineTrickDataType.INSTANCE.getCodec().parse(com.mojang.serialization.JsonOps.INSTANCE,
                new com.google.gson.JsonPrimitive("broken")).getOrThrow();
        helper.assertTrue(inline.broken, "Inline corruption lacks a broken placeholder");
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            buffer.writeByteArray(new byte[]{1, 2, 3});
            rejects(helper, () -> TrickIota.TYPE.streamCodec().decode(buffer), "Network corruption became an executable empty spell");
        } finally { buffer.release(); }
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void explicitVisitorRebuildsKnownStructuresAndPreservesUnchangedIdentity(GameTestHelper helper) {
        UUID id = UUID.randomUUID();
        var nativeEntity = new EntityFragment(id, Component.literal("entity"));
        var wrapped = new IotaFragment(IotaFragment.serializeIota(new DoubleIota(1)));
        var map = new MapFragment(io.vavr.collection.HashMap.of(new NumberFragment(0), new ListFragment(List.of(nativeEntity, wrapped))));
        var iota = new TrickIota(new SpellPart(map));
        helper.assertTrue(iota.visit(value -> value) == iota, "Identity visitor rebuilt immutable snapshot");
        Iota changed = iota.visit(value -> value instanceof DoubleIota ? new DoubleIota(2) : value);
        helper.assertTrue(changed != iota && changed instanceof TrickIota, "Explicit child visitor ignored embedded iota");
        var spell = ((TrickIota) changed).getSpell();
        var list = (ListFragment) ((MapFragment) spell.glyph).map().values().head();
        helper.assertTrue(list.fragments().getFirst() instanceof EntityFragment entity && entity.uuid().equals(id), "Visitor automatically removed a native entity reference");
        helper.assertTrue(((IotaFragment) list.fragments().get(1)).getIota().equals(new DoubleIota(2)), "Visitor failed to rebuild a map/list leaf");
        helper.succeed();
    }
    private HexInteropCodecGameTests() {}

    @GameTest(template = "trickster_stock_test")
    public static void entityComponentJsonSharesStructuralLimits(GameTestHelper helper) {
        byte[] encoded = new EntityFragment(UUID.randomUUID(), Component.literal("guard")).toBytes();
        byte[] raw;
        try (var budget = DecodeBudget.enter()) {
            byte[] full = new byte[encoded.length + 10];
            byte[] header = {31, (byte) 139, 8, 0, 0, 0, 0, 0, 0, 0};
            System.arraycopy(header, 0, full, 0, 10);
            System.arraycopy(encoded, 0, full, 10, encoded.length);
            raw = BridgeFragmentCodec.decodeGzip(full, budget);
        }
        byte[] marker = "\"guard\"".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int offset = -1;
        for (int i = 1; i <= raw.length - marker.length; i++) {
            if (Arrays.equals(Arrays.copyOfRange(raw, i, i + marker.length), marker)) { offset = i; break; }
        }
        helper.assertTrue(offset > 0 && raw[offset - 1] == marker.length, "Native stringified component wire format changed");
        var buffer = new net.minecraft.network.FriendlyByteBuf(Unpooled.buffer());
        byte[] invalid;
        try {
            buffer.writeBytes(raw, 0, offset - 1);
            buffer.writeUtf("[".repeat(300) + "\"guard\"" + "]".repeat(300));
            buffer.writeBytes(raw, offset + marker.length, raw.length - offset - marker.length);
            invalid = new byte[buffer.readableBytes()]; buffer.readBytes(invalid);
        } finally { buffer.release(); }
        rejects(helper, () -> BridgeFragmentCodec.decode(compressed(invalid)), "Entity component bypassed the shared depth bound");
        helper.assertTrue(BridgeFragmentCodec.decode(encoded) instanceof EntityFragment, "Normal entity name failed bounded JSON validation");
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void nestedPotionCompressionConsumesTheOuterBudget(GameTestHelper helper) throws Exception {
        var tag = (CompoundTag) net.minecraft.world.effect.MobEffectInstance.CODEC.encodeStart(NbtOps.INSTANCE,
                new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED, 100)).getOrThrow();
        tag.putByteArray("ignored_padding", new byte[5 * 1024 * 1024]);
        var output = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, output);
        var potion = new com.iridium126.createmanaindustry.compat.trickster.fragment.PotionFragment(output.toByteArray());
        byte[] single = potion.toBytes();
        helper.assertTrue(BridgeFragmentCodec.decode(single) instanceof com.iridium126.createmanaindustry.compat.trickster.fragment.PotionFragment,
                "Valid bounded potion failed to decode");
        byte[] nested = new SpellPart(new ListFragment(List.of(potion, potion))).toBytes();
        rejects(helper, () -> BridgeFragmentCodec.decode(nested), "Two compressed potions each received a fresh decompression allowance");
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void nestedInlineCorruptionAndInnerGzipTrailingBytesAreRejected(GameTestHelper helper) {
        var nested = new SpellPart(new IotaFragment(new byte[]{1, 2, 3}));
        var inline = InlineTrickData.InlineTrickDataType.INSTANCE.getCodec().parse(com.mojang.serialization.JsonOps.INSTANCE,
                new com.google.gson.JsonPrimitive(nested.toBase64())).getOrThrow();
        helper.assertTrue(inline.broken, "Inline accepted a corrupt nested iota until rendering");
        byte[] valid = IotaFragment.serializeIota(new DoubleIota(42));
        rejects(helper, () -> new IotaFragment(Arrays.copyOf(valid, valid.length + 1)).getIota(), "Inner NBT gzip accepted trailing data");
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void nbtCollectionLengthIsCheckedBeforeAllocation(GameTestHelper helper) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new java.io.DataOutputStream(bytes)) {
            output.writeByte(10); output.writeUTF("");
            output.writeByte(9); output.writeUTF("iota"); output.writeByte(1); output.writeInt(Integer.MAX_VALUE);
        }
        try (var budget = DecodeBudget.enter()) {
            rejects(helper, () -> {
                try { BoundedIotaNbt.read(bytes.toByteArray(), budget); }
                catch (java.io.IOException failure) { throw new IllegalArgumentException(failure); }
            }, "NBT allocated an advertised unbounded list");
        }
        helper.succeed();
    }
}
